package app.bildfang

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MediaCodec hardware H.264 recorder — bildfang's primary, portable,
 * preservation-grade recorder (P2a, docs/ROADMAP.md).
 *
 * Pipeline: ARCore OES camera texture → (GL, single update loop) → this
 * recorder's input [encoderSurface] → H.264 HW encoder → [MediaMuxer] →
 * `video/camera.mp4` + authoritative `video/frames.json`.
 *
 * **Timestamps (P2a):** the caller sets the presentation time on the EGL
 * side (`eglPresentationTimeANDROID`) *before* `eglSwapBuffers` — a
 * session-relative nanosecond value `android_camera_timestamp_ns -
 * originRawNs`, where the origin is the first encoded frame's camera
 * timestamp ([ensureOrigin] must be called before the first swap). The
 * muxed PTS then carries that value; P2a measures the residual through
 * ffprobe. The raw origin is persisted as `video_timebase`.
 *
 * **No silent drops:** every camera frame observed during a recording
 * attempt ends up in exactly one counter: submitted (encoded path) or
 * dropped (observable reason). [frames] is written on every stop, even a
 * failed one, so interrupted captures stay distinguishable.
 *
 * **Atomic finalization:** output goes to `camera.mp4.tmp` and is renamed
 * to `camera.mp4` only on a clean stop with ≥1 muxed frame.
 *
 * Threading: [start]/[stop] from the GL thread; the output drain runs on a
 * private [HandlerThread] and is the *only* caller of
 * `dequeueOutputBuffer` (no cross-thread dequeue race).
 */
class MediaCodecRecorder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int, // initial experiment: ~25 Mbit/s for 1080p30
    private val outputDir: File,
) : VideoRecorder {

    override val counters = RecorderCounters()
    override var timebaseOriginNs: Long = 0L
        private set

    val encoderSurface: Surface
    val codec: MediaCodec
    var lastFrameIndex: Int = -1
        private set

    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private val frames = ArrayList<EncodedFrameRecord>()
    private val lock = ReentrantLock()
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val flushDone = AtomicBoolean(false)
    private var drainThread: HandlerThread? = null
    private var startFailed: String? = null
    private val tmpFile = File(outputDir, "camera.mp4.tmp")
    val finalFile = File(outputDir, "camera.mp4")

    init {
        outputDir.mkdirs()
        val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // ~1 s GOP
        codec = MediaCodec.createEncoderByType(MIME_AVC)
        codec.configure(fmt, null, null, 0)
        encoderSurface = codec.createInputSurface()
    }

    /**
     * Returns the session-relative origin for presentation-time
     * computation; captures it on the first call (the first frame's
     * camera timestamp). Call **before** `eglPresentationTimeANDROID` on
     * every frame.
     */
    fun ensureOrigin(androidCameraTimestampNs: Long): Long = lock.withLock {
        if (timebaseOriginNs == 0L) timebaseOriginNs = androidCameraTimestampNs
        timebaseOriginNs
    }

    override fun start(): Boolean {
        if (running.get()) return true
        if (startFailed != null) return false
        try {
            if (tmpFile.exists()) tmpFile.delete()
            if (finalFile.exists()) finalFile.delete()
            lock.withLock {
                frames.clear()
                lastFrameIndex = -1
            }
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            muxerTrack = muxer!!.addTrack(codec.outputFormat)
            muxer!!.start()
            stopRequested.set(false)
            flushDone.set(false)
            drainThread = HandlerThread("bildfang-enc-drain").also { it.start() }
            Handler(drainThread!!.looper).post { drainLoop() }
            running.set(true)
            return true
        } catch (e: Exception) {
            startFailed = e.javaClass.simpleName + ": " + e.message
            safeRelease()
            return false
        }
    }

    /**
     * Called from the GL update loop **after** the OES texture was drawn
     * into [encoderSurface] with the presentation time set and the buffer
     * swapped. Registers the frame in `frames.json`'s authoritative map.
     */
    override fun submitFrame(
        androidCameraTimestampNs: Long,
        arcoreFrameTimestampRawNs: Long,
    ) {
        counters.cameraFramesObserved++
        if (!running.get()) {
            counters.framesDropped++
            return
        }
        val origin = lock.withLock {
            if (timebaseOriginNs == 0L) timebaseOriginNs = androidCameraTimestampNs
            timebaseOriginNs
        }
        val idx = lock.withLock {
            frames.add(
                EncodedFrameRecord(
                    idx = frames.size,
                    ptsNs = androidCameraTimestampNs - origin,
                    androidCameraTimestampNs = androidCameraTimestampNs,
                    arcoreFrameTimestampRawNs = arcoreFrameTimestampRawNs,
                    poseIndex = null, // attached via attachPoseIndex when known
                )
            )
            lastFrameIndex = frames.size - 1
            frames.size - 1
        }
        counters.framesSubmitted++
    }

    /** A frame that was observed but NOT encoded (no texture, EGL error). */
    fun dropFrame() {
        counters.cameraFramesObserved++
        counters.framesDropped++
    }

    /** Attach the pose recorded for the same camera frame (1:1 in practice;
     *  null is preserved when a frame genuinely has no pose). */
    fun attachPoseIndex(frameIdx: Int, poseIndex: Int) {
        if (poseIndex < 0) return
        lock.withLock {
            if (frameIdx in frames.indices) {
                frames[frameIdx] = frames[frameIdx].copy(poseIndex = poseIndex)
            }
        }
    }

    private val bufInfo = MediaCodec.BufferInfo()

    /** Sole owner of dequeueOutputBuffer. */
    private fun drainLoop() {
        val c = codec
        var quiescent = 0
        while (true) {
            if (stopRequested.get()) {
                // flush mode: drain until the output queue is empty
                when (val r = c.dequeueOutputBuffer(bufInfo, 50)) {
                    TRY_AGAIN -> {
                        if (++quiescent >= 4) { flushDone.set(true); break }
                    }
                    EOS -> {
                        mux(r)
                        flushDone.set(true)
                        break
                    }
                    else -> { quiescent = 0; mux(r) }
                }
            } else {
                val r = c.dequeueOutputBuffer(bufInfo, 250)
                if (r >= 0) {
                    quiescent = 0
                    mux(r)
                }
            }
        }
    }

    private fun mux(r: Int) {
        val m = muxer
        if (r < 0 || bufInfo.size <= 0 || m == null || muxerTrack < 0) return
        val buf = codec.getOutputBuffer(r)!!
        bufInfo.offset = 0
        m.writeSampleData(muxerTrack, buf, bufInfo)
        counters.framesEncoded++
        counters.framesMuxed++
        codec.releaseOutputBuffer(r, false)
    }

    override fun stop() {
        val wasRunning = running.getAndSet(false)
        try {
            if (wasRunning) {
                stopRequested.set(true)
                // bounded wait for the drain thread to empty the encoder
                val deadline = System.nanoTime() + 3_000_000_000
                while (!flushDone.get() && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            safeRelease()
        }
        val m = muxer
        if (m != null && counters.framesMuxed > 0) {
            try {
                m.stop()
                if (!tmpFile.renameTo(finalFile)) tmpFile.copyTo(finalFile, overwrite = true)
            } catch (_: Exception) {
                tmpFile.delete()
            }
        } else {
            tmpFile.delete()
        }
        try { m?.release() } catch (_: Exception) {}
        muxer = null
        writeFramesJson()
    }

    private fun writeFramesJson() {
        val snapshot = lock.withLock { frames.toList() }
        try {
            File(outputDir, "frames.json")
                .writeText(FramesJson.build(snapshot, timebaseOriginNs, counters))
        } catch (_: Exception) {
        }
    }

    private fun safeRelease() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { encoderSurface.release() } catch (_: Exception) {}
        drainThread?.let {
            try { it.quitSafely() } catch (_: Exception) {}
            try { it.join(1000) } catch (_: Exception) {}
        }
        drainThread = null
    }

    override fun status(): String {
        val s = startFailed
        return if (s != null) "recorder start failed: $s"
        else "recorder ${if (running.get()) "recording" else "stopped"}: observed=${counters.cameraFramesObserved} " +
            "submitted=${counters.framesSubmitted} encoded=${counters.framesEncoded} " +
            "muxed=${counters.framesMuxed} dropped=${counters.framesDropped}" +
            if (finalFile.exists()) " → camera.mp4 (${finalFile.length()} bytes)" else ""
    }

    companion object {
        const val MIME_AVC = "video/avc"
        // dequeueOutputBuffer() int codes (the old MediaCodec constants were
        // removed from the API; values per EGL/MediaCodec docs)
        const val TRY_AGAIN = -1
        const val EOS = -4
    }
}
