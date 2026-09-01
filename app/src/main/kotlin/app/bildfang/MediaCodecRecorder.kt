package app.bildfang

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
    val fps: Int,
    private val bitrate: Int, // initial experiment: ~25 Mbit/s for 1080p30
    private val outputDir: File,
) : VideoRecorder {

    override val counters = RecorderCounters()
    override var timebaseOriginNs: Long = 0L
        private set

    /** Set in start(); read by the GL feed after start() returned true. */
    lateinit var encoderSurface: Surface
    lateinit var codec: MediaCodec
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
            // Codec creation (per ChatGPT review 2026-09-01 — the earlier
            // attempt had three API bugs, the first of which alone explains
            // all observed failures):
            //   1. configure(..., 0) — missing CONFIGURE_FLAG_ENCODE (the
            //      documented IllegalArgumentException cause for encoders)
            //   2. createEncoderByType(codecName) — expects a MIME type;
            //      an explicit codec needs createByCodecName()
            //   3. no explicit COLOR_FormatSurface in the input format
            // Order: default system encoder (platform's choice), then
            // hardware codecs by name, then software.
            val c2 = MediaCodecList(0)
            val all = c2.codecInfos
            val avcEncoders = all
                .filter { it.isEncoder && it.supportedTypes.contains(MIME_AVC) }
            val ordered = avcEncoders.filter { it.isHardwareAccelerated } +
                avcEncoders.filter { !it.isHardwareAccelerated }
            android.util.Log.i("bildfang", "AVC encoder candidates: " +
                ordered.joinToString { "${it.name}(${if (it.isHardwareAccelerated) "HW" else "SW"})" })
            val candidates = listOf<String?>(null) + ordered.map { it.name }
            val ladder = listOf(bitrate, 12_000_000, 6_000_000)
            var lastErr: Exception? = null
            var lastErrStage = ""
            var ok = false
            outer@ for (name in candidates) {
                for (br in ladder) {
                    var c: MediaCodec? = null
                    try {
                        c = if (name == null) {
                            MediaCodec.createEncoderByType(MIME_AVC) // default, by MIME
                        } else {
                            MediaCodec.createByCodecName(name)       // explicit, by name
                        }
                        val fmt = MediaFormat.createVideoFormat(MIME_AVC, width, height)
                        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        fmt.setInteger(MediaFormat.KEY_BIT_RATE, br)
                        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // ~1 s GOP
                        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        codec = c
                        encoderSurface = c.createInputSurface()
                        lastErr = null
                        lastErrStage = ""
                        ok = true
                        break@outer
                    } catch (e: Exception) {
                        lastErr = e
                        lastErrStage = when (e) {
                            is java.lang.IllegalArgumentException -> "configure (flag/format)"
                            is MediaCodec.CodecException -> if (c == null) "create" else "configure/surface"
                            else -> "create"
                        }
                        android.util.Log.w("bildfang", "attempt failed (${name ?: "default"}, br=$br) stage=${lastErrStage}: ${e.javaClass.simpleName} ${e.message}")
                        try { c?.release() } catch (_: Exception) {}
                    }
                }
            }
            if (ok) {
                android.util.Log.i("bildfang", "encoder ready: ${codec.name} ${width}x${height} @${fps}fps ${bitrate / 1_000_000}Mbit/s")
            }
            if (!ok) {
                // Valid capability probe (replaces the earlier invalid
                // mime-only probe): full format, encode flag, surface
                // input, create→configure→surface→start→stop, no camera.
                for (name in candidates) {
                    var pc: MediaCodec? = null
                    try {
                        pc = if (name == null) MediaCodec.createEncoderByType(MIME_AVC)
                             else MediaCodec.createByCodecName(name)
                        val pf = MediaFormat.createVideoFormat(MIME_AVC, width, height)
                        pf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        pf.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                        pf.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        pf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        pc!!.configure(pf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        val surf = pc.createInputSurface()
                        pc.start()
                        pc.stop()
                        surf.release()
                        android.util.Log.e("bildfang", "VALID PROBE PASSED: ${name ?: "default"}")
                    } catch (e: Exception) {
                        android.util.Log.e("bildfang", "valid probe FAILED on ${name ?: "default"}: ${e.javaClass.simpleName} ${e.message}")
                        try { pc?.release() } catch (_: Exception) {}
                    }
                }
                try {
                    val d = MediaCodec.createDecoderByType(MIME_AVC)
                    android.util.Log.i("bildfang", "AVC decoder instantiated: ${d.name}")
                    d.release()
                } catch (e: Exception) {
                    android.util.Log.e("bildfang", "AVC decoder also fails", e)
                }
                throw lastErr ?: IllegalStateException("no usable AVC encoder")
            }

            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            // Deliberately no addTrack/start here: the muxable output format
            // (with SPS/PPS as csd-0/csd-1) only becomes valid when the
            // codec signals INFO_OUTPUT_FORMAT_CHANGED; the drain thread
            // starts the muxer at that point and gates writes on it.
            stopRequested.set(false)
            flushDone.set(false)
            drainThread = HandlerThread("bildfang-enc-drain").also { it.start() }
            Handler(drainThread!!.looper).post { drainLoop() }
            running.set(true)
            return true
        } catch (e: Exception) {
            startFailed = e.javaClass.simpleName + ": " + (e.message ?: "no message")
            android.util.Log.e("bildfang", "recorder start failed", e)
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

    /** Intentional cadence skip (source faster than encoder): not a failure. */
    fun skipFrameForRate() {
        counters.cameraFramesObserved++
        counters.framesRateSkipped++
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
    @Volatile private var muxerStarted = false

    /** Sole owner of dequeueOutputBuffer. */
    private fun drainLoop() {
        val c = codec
        var quiescent = 0
        while (true) {
            val timeoutMs: Long = if (stopRequested.get()) 50 else 250
            val r = c.dequeueOutputBuffer(bufInfo, timeoutMs)
            if (r == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    val m = muxer ?: error("muxer missing at format change")
                    muxerTrack = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                    android.util.Log.i("bildfang", "muxer started at INFO_OUTPUT_FORMAT_CHANGED (track $muxerTrack, ${c.outputFormat})")
                }
                // The format-change event carries no real buffer. AOSP
                // convention is to consume it via releaseOutputBuffer(
                // INFO_OUTPUT_END_OF_STREAM), but the Exynos C2 port rejects
                // sentinel indices ("index out of range", crash on-device
                // 2026-09-01) — so it is simply skipped; it is a one-shot
                // event per stream and holds no buffer to leak.
                quiescent = 0
                continue
            }
            if (stopRequested.get()) {
                when (r) {
                    TRY_AGAIN -> {
                        if (++quiescent >= 4) { flushDone.set(true); break }
                    }
                    EOS -> { mux(r); flushDone.set(true); break }
                    else -> { quiescent = 0; mux(r) }
                }
            } else if (r >= 0) {
                quiescent = 0
                mux(r)
            }
        }
    }

    private fun mux(r: Int) {
        val m = muxer
        if (r < 0 || m == null || !muxerStarted) {
            if (r >= 0) codec.releaseOutputBuffer(r, false)
            return
        }
        if (bufInfo.size <= 0) {
            codec.releaseOutputBuffer(r, false)
            return
        }
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
        if (this::codec.isInitialized) {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        if (this::encoderSurface.isInitialized) {
            try { encoderSurface.release() } catch (_: Exception) {}
        }
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
            "muxed=${counters.framesMuxed} dropped=${counters.framesDropped} " +
            "rate_skipped=${counters.framesRateSkipped}" +
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
