package app.bildfang

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.opengl.GLSurfaceView
import android.util.Size
import android.widget.Button
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.Track
import android.opengl.GLES20
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.Volatile
import kotlin.math.max

/**
 * Phase-2 UI + capture loop.
 *
 *   [live preview] → ARCore camera texture rendered in GL (GL_TEXTURE_EXTERNAL_OES)
 *   [timer]        → recording duration
 *   [metrics]      → tracking state / fps / frame count / segment / pose / draw rate
 *   [ START ] →  [ STOP ]
 *
 * Architecture (ARCore 1.54, pull-style, GL-context bound):
 *
 *  - One Session for the lifetime of the activity, created in onCreate and
 *    resumed in onResume. The session runs continuously so the preview is
 *    live and tracking is warm before the user presses START.
 *
 *  - The ARCore update loop lives in the GLSurfaceView renderer thread
 *    (Session.update() throws MissingGlContextException off a GL thread —
 *    verified on-device 2026-09-01). Update mode is LATEST_CAMERA_IMAGE:
 *    non-blocking, the loop runs at display refresh and each draw binds the
 *    ARCore camera texture (frame.getCameraTextureName()) into a
 *    full-screen passthrough quad. No CPU image conversion anywhere —
 *    the software YUV preview of Phase 1 was throttling the pipeline to
 *    ~3 Hz (acquireCameraImage/close round-trips serialized with the
 *    camera producer).
 *
 *  - START: session.startRecording(RecordingConfig) with an MP4 dataset
 *    (ARCore-native H.264, presentation timestamps, IMU) plus a custom
 *    JSON pose track (frame.recordTrackData per frame). STOP:
 *    session.stopRecording(), then export poses.json + session.json.
 *    Video↔pose timing is aligned by construction: the track data and the
 *    video frames share ARCore's internal frame clock, and each pose also
 *    carries the Android camera timestamp (frame.getAndroidCameraTimestamp)
 *    used by the HAL for video PTS.
 *
 *  - Geospatial mode is DISABLED explicitly: bildfang is a local-only
 *    logger, it neither needs nor stores location data.
 */
class MainActivity : Activity() {

    private var session: Session? = null
    @Volatile private var sessionActive = false
    @Volatile private var recording = false
    @Volatile private var stopRequested = false
    private var sessionStartMonoNs = 0L

    // Recording state
    private var trackUuid = UUID.randomUUID()
    private var sessionDir: File? = null
    private var videoFile: File? = null
    private var camImageSize: Size? = null
    private var camFpsRange = ""

    private val poses = ArrayList<PoseRecord>()
    private val posesLock = Any()
    private var segment = 0
    private var lastTracking: PoseRecord? = null
    private var fps = 0f

    private var lastMetricsNs = 0L
    private val METRICS_INTERVAL_NS = 500_000_000L // 2 Hz
    private var consecutiveUpdateFailures = 0
    private var lastResumeRetryNs = 0L
    private var drawFrameCount = 0
    private var distinctFrameCount = 0
    private var lastDistinctTsNs = 0L

    // 1.54's Frame.getTimestamp() is NOT on the elapsedRealtimeNanos clock
    // (observed ~1.78e18 ns — a fixed epoch, likely unix ns). All bildfang
    // frame clocks are therefore session-relative, anchored at the first
    // frame; session.json stores the anchor for absolute reconstruction.
    private var frameTsAnchor = 0L // raw frame timestamp of the first frame
    private var anchorMonoNs = 0L  // SystemClock.elapsedRealtimeNanos at anchor
    private var anchorUnixMs = 0L  // System.currentTimeMillis at anchor

    private lateinit var glView: GLSurfaceView
    private lateinit var timerView: TextView
    private lateinit var metricsView: TextView
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val random = SecureRandom()

    // ---- GL preview (created on the GL thread) ----------------------------

    private val program = IntArray(1)
    private var aPosUv = 0
    private var uTex = 0
    private var uDebugRed = 0
    // Toggle to force a solid-red frame (compositing check).
    private var debugRed = false
    private val quadBufferId = IntArray(1) // glGenBuffers in onSurfaceCreated
    private var oesTextureId = 0

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            program[0] = buildProgram(
                "attribute vec4 aPosUv;\nvarying vec2 vUv;\n" +
                    "void main() { vUv = aPosUv.zw; gl_Position = vec4(aPosUv.xy, 0.0, 1.0); }",
                // GLSL ES 100 on the (ES2-requested, ES3.2-actual) context;
                // the OES external-texture extension compiled fine on-device
                // (Pixel 7 / Mali r54, 2026-09-01).
                "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\nvarying vec2 vUv;\nuniform sampler2D uTex;\n" +
                    "uniform float uDebugRed;\n" +
                    "void main() { if (uDebugRed > 0.5) { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); } else { gl_FragColor = texture2D(uTex, vUv); } }"
            )
            aPosUv = GLES20.glGetAttribLocation(program[0], "aPosUv")
            uTex = GLES20.glGetUniformLocation(program[0], "uTex")
            uDebugRed = GLES20.glGetUniformLocation(program[0], "uDebugRed")

            // 4 corners: xy = clip space, zw = texture uv (Y flipped:
            // camera image is top-down, clip space origin is bottom-left)
            val quad = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                1f, 1f, 1f, 0f,
                -1f, 1f, 0f, 0f
            )
            GLES20.glGenBuffers(1, quadBufferId, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBufferId[0])
            val buf = ByteBuffer.allocate(quad.size * 4)
            buf.asFloatBuffer().put(quad)
            buf.rewind() // glBufferData reads from position(); put() advanced it past the data
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES20.GL_STATIC_DRAW)

            // The camera texture ARCore writes into. 1.54 expects an
            // external OES texture registered via setCameraTextureName.
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            oesTextureId = t[0]
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
            android.util.Log.i("bildfang", "GL ready: program=${program[0]} oes=$oesTextureId ctx=${GLES20.glGetString(GLES20.GL_VERSION)}")

            // The session is created in onCreate, before this surface exists:
            // register the texture now that we have one (requires a GL
            // context — hence the GL thread). Without it update() fails.
            try {
                session?.setCameraTextureName(oesTextureId)
            } catch (e: Exception) {
                android.util.Log.w("bildfang", "setCameraTextureName: $e")
            }

            GLES20.glClearColor(0.06f, 0.08f, 0.10f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            // Required before frames flow: ARCore warns "Display geometry
            // has an invalid width: 0" and the frame manager withholds
            // frames until the viewport is known (verified on-device
            // 2026-09-01).
            try {
                session?.setDisplayGeometry(width, height, 60)
            } catch (e: Exception) {
                android.util.Log.w("bildfang", "setDisplayGeometry: $e")
            }
        }
        override fun onDrawFrame(gl: GL10?) {
            drawFrameCount++
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val s = session ?: return

            if (!sessionActive) {
                // The camera-permission dialog pauses the activity, so a
                // resume() from onResume() can race ahead of the grant and
                // throw; retry here (throttled) until the session is live
                // (verified on-device 2026-08-31).
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastResumeRetryNs >= 500_000_000L) {
                    lastResumeRetryNs = now
                    try {
                        s.resume()
                        sessionActive = true
                    } catch (e: Exception) {
                        if (consecutiveUpdateFailures == 0) {
                            android.util.Log.w("bildfang", "resume() failing", e)
                            postToUi { statusView.text = "Waiting for camera…" }
                        }
                    }
                }
                return
            }

            if (stopRequested) {
                // stopRecording() from the GL thread (Session is not
                // thread-safe); the export is posted to the UI after.
                stopRequested = false
                try {
                    s.stopRecording()
                    recording = false
                    postToUi { finalizeExport() }
                } catch (e: Exception) {
                    recording = false
                    postToUi { statusView.text = "Stop failed: ${e.message}" }
                }
                return
            }

            try {
                val f = s.update()
                consecutiveUpdateFailures = 0
                onFrame(f)
                drawPreview(f)
            } catch (e: Exception) {
                consecutiveUpdateFailures++
                if (consecutiveUpdateFailures == 1) {
                    android.util.Log.w("bildfang", "update() failing", e)
                }
                if (consecutiveUpdateFailures == 100) {
                    postToUi {
                        statusView.text = "ARCore update failing: ${e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    /**
     * Passthrough of the ARCore camera texture into the GL view.
     *
     * OPEN ISSUE (2026-09-01, Pixel 7 / GrapheneOS 17 / ARCore 1.54):
     * the EGL surface is composited (the clear color is visible on
     * screen, proving setZOrderOnTop + transparent window work) but the
     * quad drawn here is not visible, even though program/link/attribute/
     * buffer/viewport are all valid and glGetError() stays 0. Both a
     * solid-red shader and the OES-sampling shader reproduce it. Next
     * candidates: z-order media overlay instead of on-top, EGL config
     * interplay with the ES3.2 context, or the ARCore-owned GL threads
     * interfering with our surface. The tracking/pose pipeline (onFrame)
     * is unaffected — poses.json records are correct regardless.
     */
    private fun drawPreview(frame: Frame) {
        if (program[0] == 0) return
        // 1.54 may return 0 (ARCore does not own the texture — the app's
        // setCameraTextureName target is the one being filled).
        val tex = frame.cameraTextureName.takeIf { it != 0 } ?: oesTextureId
        if (tex == 0) {
            if (drawFrameCount % 60 == 0) {
                android.util.Log.w("bildfang", "no texture: frameTex=${frame.cameraTextureName} oes=$oesTextureId")
            }
            return
        }
        GLES20.glUseProgram(program[0])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uDebugRed, if (debugRed) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBufferId[0])
        GLES20.glEnableVertexAttribArray(aPosUv)
        GLES20.glVertexAttribPointer(aPosUv, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosUv)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                GLES20.glDeleteShader(sh)
                throw RuntimeException("shader compile: " + GLES20.glGetShaderInfoLog(sh))
            }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(p)
            throw RuntimeException("program link: " + GLES20.glGetProgramInfoLog(p))
        }
        return p
    }

    private fun postToUi(block: () -> Unit) {
        runOnUiThread { if (!isFinishing) block() }
    }

    // ---- Session lifecycle ------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.gl)
        timerView = findViewById(R.id.timerView)
        metricsView = findViewById(R.id.metricsView)
        statusView = findViewById(R.id.statusView)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // GLSurfaceView is a SurfaceView: it renders *behind* the window.
        // Our root layout has an opaque background, which would hide the
        // surface entirely (verified on-device 2026-09-01: a hard-coded
        // red shader produced a black screen). Classic fix: transparent
        // window + surface on top; the layout below keeps its own opaque
        // background for the non-GL region.
        glView.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        glView.setZOrderOnTop(true)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        startBtn.setOnClickListener { startRecording() }
        stopBtn.setOnClickListener { stopRecording() }

        val avail = ArCoreApk.getInstance().checkAvailability(this)
        statusView.text = when (avail) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                getString(R.string.support_ok)
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                getString(R.string.support_install)
            else ->
                // Include the raw enum: distinguishes
                // UNSUPPORTED_DEVICE_NOT_CAPABLE from UNKNOWN_TIMED_OUT /
                // UNKNOWN_ERROR, which read the same in the UI.
                "${getString(R.string.support_unsupported)} (check: $avail)"
        }

        if (avail == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                return // the grant callback creates the session
            }
            createSession()
        }
    }

    private fun createSession() {
        if (session != null) return
        try {
            val s = Session(this)
            val cfg = s.config
                .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            if (s.isGeospatialModeSupported(Config.GeospatialMode.DISABLED)) {
                cfg.setGeospatialMode(Config.GeospatialMode.DISABLED)
            }
            s.configure(cfg)

            // Highest-resolution CPU image: it becomes the recorded video
            // track (ARCore records the CPU image, never the GPU texture —
            // see the recording-and-playback docs).
            val best = s.supportedCameraConfigs.maxByOrNull {
                it.imageSize.width * it.imageSize.height
            }
            if (best != null) {
                s.setCameraConfig(best)
                camImageSize = best.imageSize
                val r = best.fpsRange
                camFpsRange = "${r.lower}–${r.upper}"
            }

            if (oesTextureId != 0) s.setCameraTextureName(oesTextureId)

            session = s
            sessionActive = false
            try {
                s.resume()
                sessionActive = true
            } catch (e: Exception) {
                // camera not usable yet; the GL loop retries resume()
                sessionActive = false
            }
            statusView.text = "ARCore ready · live preview"
        } catch (e: Exception) {
            statusView.text = "Session error: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            session?.resume()
            sessionActive = true
        } catch (ignored: Exception) {
            // permission pending or camera busy; GL loop retries
        }
    }

    override fun onPause() {
        super.onPause()
        sessionActive = false
        try {
            session?.pause() // auto-stops the recording (setAutoStopOnPause)
        } catch (ignored: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (recording) session?.stopRecording()
            session?.pause()
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            createSession()
        }
    }

    // ---- Recording --------------------------------------------------------

    private fun startRecording() {
        if (recording) return
        val s = session ?: run {
            statusView.text = "Session not ready"
            return
        }
        if (oesTextureId == 0) {
            // GL surface not created yet (rare): the texture must be
            // generated on the GL thread, not here (no GL context).
            statusView.text = "Preview not ready yet — try again"
            return
        }

        val dir = createSessionDir()
        videoFile = File(dir, "video.mp4")
        sessionDir = dir
        trackUuid = UUID.randomUUID()
        sessionStartMonoNs = SystemClock.elapsedRealtimeNanos()
        fps = 0f
        drawFrameCount = 0
        distinctFrameCount = 0
        lastDistinctTsNs = 0L
        synchronized(posesLock) {
            poses.clear()
            segment = 0
            lastTracking = null
        }

        val cfg = RecordingConfig(s)
            .setMp4DatasetUri(Uri.fromFile(videoFile!!))
            .setAutoStopOnPause(true)
            .addTrack(
                Track(s)
                    .setId(trackUuid)
                    .setMimeType("application/json")
                    .setMetadata(
                        ByteBuffer.wrap(
                            "\"bildfang-capture/v1 pose track: one compact JSON pose per video frame\""
                                .encodeToByteArray()
                        )
                    )
            )
        try {
            s.startRecording(cfg)
            recording = true
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
            statusView.setText(R.string.status_recording)
        } catch (e: Exception) {
            statusView.text = "Recording failed: ${e.message}"
            startBtn.isEnabled = true
        }
    }

    private fun stopRecording() {
        if (!recording) return
        stopRequested = true // executed on the GL thread (Session is not thread-safe)
        stopBtn.isEnabled = false
        statusView.text = "Finalizing…"
    }

    /** Main thread, after stopRecording() completed on the GL thread. */
    private fun finalizeExport() {
        recording = false
        startBtn.isEnabled = true
        stopBtn.isEnabled = false

        val snapshot = synchronized(posesLock) { poses.toList() }
        val dir = sessionDir
        if (snapshot.isEmpty() || dir == null) {
            statusView.text = if (snapshot.isEmpty())
                "Nothing recorded (no frames)."
            else
                "Export skipped (no session dir)."
            return
        }

        try {
            val poseFile = File(dir, "poses/poses.json").apply { parentFile?.mkdirs() }
            poseFile.writeText(PoseJson.build(snapshot))
            File(dir, "session.json").writeText(buildSessionJson())
            statusView.text = "Saved · ${snapshot.size} poses · video + pose track"
        } catch (e: Exception) {
            statusView.text = "Export failed: ${e.message}"
        }
    }

    private fun buildSessionJson(): String {
        val arcore = try {
            packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val v = camImageSize
        return """
            {
              "schema": "bildfang-capture/v1",
              "app": "bildfang",
              "app_version": "0.2.0",
              "created_utc": "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())}",
              "arcore_sdk": "$arcore",
              "device": "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
              "tracking": {
                "world_frame": "ARCore device tracking, segment 0 first pose = origin",
                "note": "trajectory estimate, not ground truth"
              },
              "video": {
                "file": "video.mp4",
                "producer": "ARCore native recording (H.264, presentation timestamps, IMU)",
                "cpu_image_resolution": ${if (v == null) "null" else "\"${v.width}x${v.height}\""},
                "fps_range": ${if (camFpsRange.isEmpty()) "null" else "\"$camFpsRange\""}
              },
              "pose_track": {
                "uuid": "$trackUuid",
                "mime": "application/json",
                "alignment": "one record per video frame; PTS shared with the video track"
              },
              "poses_file": "poses/poses.json",
              "clock": {
                "frame_timestamp_ns": "ARCore frame clock, epoch unknown (fixed, likely unix ns)",
                "anchor_frame_ts": $frameTsAnchor,
                "anchor_unix_ms": $anchorUnixMs,
                "anchor_monotonic_ns": $anchorMonoNs,
                "poses_timestamps": "relative to anchor_frame_ts"
              }
            }
        """.trimIndent()
    }

    /** Runs on the GL thread (has the GL context ARCore 1.54 requires). */
    private fun onFrame(frame: Frame) {
        if (frameTsAnchor == 0L) {
            frameTsAnchor = frame.timestamp
            anchorMonoNs = SystemClock.elapsedRealtimeNanos()
            anchorUnixMs = System.currentTimeMillis()
        }
        val cam: Camera = frame.camera
        val pose = cam.pose
        val t = pose.translation
        val q = pose.rotationQuaternion
        val state = when (cam.trackingState) {
            com.google.ar.core.TrackingState.TRACKING -> "TRACKING"
            com.google.ar.core.TrackingState.PAUSED -> "PAUSED"
            com.google.ar.core.TrackingState.STOPPED -> "STOPPED"
        }

        if (frame.timestamp != lastDistinctTsNs) {
            lastDistinctTsNs = frame.timestamp
            distinctFrameCount++
        }

        if (recording) {
            val frameTs = frame.timestamp - frameTsAnchor // session-relative ns
            synchronized(posesLock) {
                // World-frame reset heuristic: a large jump between
                // consecutive TRACKING frames means ARCore re-initialized
                // the world frame.
                val prev = lastTracking
                if (state == "TRACKING" && prev != null) {
                    val dx = t[0] - prev.x
                    val dy = t[1] - prev.y
                    val dz = t[2] - prev.z
                    if (frameTs - prev.timestampNs < 1_000_000_000L &&
                        (dx * dx + dy * dy + dz * dz) > 4.0f // 2 m jump in < 1 s
                    ) {
                        segment++
                    }
                }
                val r = PoseRecord(
                    index = poses.size,
                    timestampNs = frameTs,
                    androidCameraTimestampNs = frame.androidCameraTimestamp,
                    x = t[0], y = t[1], z = t[2],
                    qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                    trackingState = state,
                    segment = segment,
                )
                poses.add(r)
                if (state == "TRACKING") lastTracking = r
            }

            // Pose into the recording: one compact JSON record per frame,
            // stamped by ARCore with the same PTS as the video frame.
            try {
                val json = "{\"t\":$frameTs,\"tcam\":${frame.androidCameraTimestamp}," +
                    "\"p\":[$t[0],$t[1],$t[2]]," +
                    "\"q\":[$q[0],$q[1],$q[2],$q[3]]," +
                    "\"s\":\"$state\"}"
                frame.recordTrackData(trackUuid, ByteBuffer.wrap(json.encodeToByteArray()))
            } catch (e: Exception) {
                // non-fatal: poses.json is the canonical export
            }
        }

        val elapsedNs = frame.timestamp - frameTsAnchor
        val elapsedS = max(0L, elapsedNs / 1_000_000_000L)
        if (elapsedS >= 1) fps = distinctFrameCount.toFloat() / elapsedS

        if (elapsedNs - lastMetricsNs >= METRICS_INTERVAL_NS) {
            lastMetricsNs = elapsedNs
            val frameCount = synchronized(posesLock) { poses.size }
            val drawRate = drawFrameCount / max(1, elapsedS)
            val recS = if (recording) {
                max(0L, (SystemClock.elapsedRealtimeNanos() - sessionStartMonoNs) / 1_000_000_000L)
            } else 0L
            postToUi {
                timerView.text = formatClock(recS)
                metricsView.text = String.format(
                    Locale.US,
                    "%s · %d poses (%d cam frames) · %.1f fps · draw %d/s · seg %d%s\nx %.2f  y %.2f  z %.2f",
                    state, frameCount, distinctFrameCount, fps, drawRate, segment,
                    if (recording) " · rec" else "", t[0], t[1], t[2]
                )
            }
        }
    }

    private fun formatClock(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun createSessionDir(): File {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val hex = String.format(Locale.US, "%06x", random.nextInt())
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "sessions/capture-$stamp-$hex")
    }

    private companion object {
        // GL_TEXTURE_EXTERNAL_OES (not in GLES20)
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
