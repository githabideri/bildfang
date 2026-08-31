package app.bildfang

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phase-1 UI: the entire app.
 *
 *   [ Start ]  →  Recording…  Duration / Frames / Tracking  →  [ Stop ]
 *
 * No preview in Phase 1 (the screen shows status; the live camera view and
 * video recording come with Phase 2). ARCore still runs the camera + IMU
 * fusion. ARCore 1.54's API is pull-style (the old setFrameListener is
 * gone): we drive Session.update() at display refresh rate via
 * Choreographer, which keeps the loop on the main thread.
 */
class MainActivity : Activity() {

    private var session: Session? = null
    private var recording = false
    private var sessionStartMonoNs = 0L

    private val poses = ArrayList<PoseRecord>()
    private var segment = 0
    private var lastTracking: PoseRecord? = null

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var pathView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val random = SecureRandom()

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!recording) return
            val s = session ?: return
            try {
                onFrame(s.update())
            } catch (e: Exception) {
                // camera transiently unavailable (e.g. backgrounded);
                // keep the loop alive and retry next vsync
            }
            choreographer.postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        statsView = findViewById(R.id.stats)
        pathView = findViewById(R.id.path)
        startBtn = findViewById(R.id.start)
        stopBtn = findViewById(R.id.stop)

        startBtn.setOnClickListener { startRecording() }
        stopBtn.setOnClickListener { stopRecording() }

        when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                statusView.setText(R.string.support_ok)
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                statusView.setText(R.string.support_install)
            else ->
                statusView.setText(R.string.support_unsupported)
        }
    }

    private fun startRecording() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        try {
            val s = Session(this)
            s.resume()
            session = s
            sessionStartMonoNs = SystemClock.elapsedRealtimeNanos()
            recording = true
            choreographer.postFrameCallback(frameCallback)
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
            statusView.setText(R.string.status_recording)
        } catch (e: Exception) {
            statusView.text = "Session error: ${e.message}"
            startBtn.isEnabled = true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    /** Frame listener runs on the main thread (no GL surface attached). */
    private fun onFrame(frame: Frame) {
        if (!recording) return
        val cam: Camera = frame.camera
        val pose = cam.pose
        val t = pose.translation
        val q = pose.rotationQuaternion
        val state = when (cam.trackingState) {
            com.google.ar.core.TrackingState.TRACKING -> "TRACKING"
            com.google.ar.core.TrackingState.PAUSED -> "PAUSED"
            com.google.ar.core.TrackingState.STOPPED -> "STOPPED"
        }

        // World-frame reset heuristic: a large jump between consecutive
        // TRACKING frames means ARCore re-initialized the world frame.
        val prev = lastTracking
        if (state == "TRACKING" && prev != null) {
            val dx = t[0] - prev.x
            val dy = t[1] - prev.y
            val dz = t[2] - prev.z
            if (frame.timestamp - prev.timestampNs < 1_000_000_000L &&
                (dx * dx + dy * dy + dz * dz) > 4.0f // 2 m jump in < 1 s
            ) {
                segment++
            }
        }

        val r = PoseRecord(
            index = poses.size,
            timestampNs = frame.timestamp,
            x = t[0], y = t[1], z = t[2],
            qx = q[0], qy = q[1], qz = q[2], qw = q[3],
            trackingState = state,
            segment = segment,
        )
        poses.add(r)
        if (state == "TRACKING") lastTracking = r

        val elapsed = (frame.timestamp - sessionStartMonoNs) / 1_000_000_000L
        statsView.text = String.format(
            Locale.US,
            "Duration: %02d:%02d:%02d   Frames: %d   Tracking: %s   Segment: %d",
            elapsed / 3600, (elapsed / 60) % 60, elapsed % 60, poses.size, state, segment
        )
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        choreographer.removeFrameCallback(frameCallback)
        try {
            session?.pause()
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
        stopBtn.isEnabled = false

        if (poses.isEmpty()) {
            statusView.text = "Nothing recorded (no tracked frames)."
            return
        }

        val dir = createSessionDir()
        val file = File(dir, "poses/poses.json").apply { parentFile?.mkdirs() }
        try {
            file.writeText(PoseJson.build(poses))
            statusView.setText(R.string.status_stopped)
            pathView.text = file.absolutePath
            pathView.visibility = View.VISIBLE
        } catch (e: Exception) {
            statusView.text = "Export failed: ${e.message}"
        }
    }

    override fun onPause() {
        super.onPause()
        if (recording) {
            choreographer.removeFrameCallback(frameCallback)
            try {
                session?.pause()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (recording) {
            try {
                session?.resume()
                choreographer.postFrameCallback(frameCallback)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun createSessionDir(): File {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val hex = String.format(Locale.US, "%06x", random.nextInt())
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "sessions/capture-$stamp-$hex")
    }
}
