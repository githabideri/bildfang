package app.bildfang

/**
 * Recorder abstraction (P2a decision, 2026-09-01): the activity is never
 * coupled to a specific implementation.
 *
 *   MediaCodecRecorder     -- default, portable, preservation-grade
 *   ArCoreDatasetRecorder  -- capability-dependent supplemental (P2b;
 *                             native recording is broken on Pixel 7 /
 *                             GrapheneOS 17, dead end there)
 *
 * Contract:
 *  - [start] prepares the pipeline and returns before any frame is sent.
 *  - [submitFrame] is called from the single GL update loop, once per new
 *    camera frame, with that frame's raw timestamps. Implementations must
 *    be able to drop a frame only by recording it in [counters]
 *    (`dropped`) — silent loss is forbidden.
 *  - [stop] flushes the encoder, finalizes the container **atomically**
 *    (a stopped recorder must leave either a valid complete file or no
 *    file at all, plus the frame manifest), and is safe to call after a
 *    failure in [start] (then it finalizes with what was captured).
 *  - [status] is diagnostics: counters, state, warnings.
 *
 * The encoder may transform representation; it may not hide timing or
 * frame-loss behavior (see docs/ROADMAP.md P2a).
 */
interface VideoRecorder {
    val counters: RecorderCounters
    val timebaseOriginNs: Long // raw first-encoded camera timestamp (0 until start)

    fun start(): Boolean
    fun submitFrame(androidCameraTimestampNs: Long, arcoreFrameTimestampRawNs: Long)
    fun stop()
    fun status(): String
}

/**
 * Frame accounting. Every observed camera frame must end up in exactly
 * one of: submitted (and then encoded, possibly dropped later by the
 * encoder/muxer) — the counters make any loss observable.
 */
data class RecorderCounters(
    var cameraFramesObserved: Long = 0,
    var framesSubmitted: Long = 0,
    var framesEncoded: Long = 0,
    var framesMuxed: Long = 0,
    var framesDropped: Long = 0,
)

/**
 * One encoded video frame, for the authoritative `video/frames.json`
 * mapping (P2a). [ptsNs] is session-relative, derived from the camera
 * clock: `androidCameraTimestampNs - timebaseOriginNs`. [poseIndex] is
 * the index into `poses/poses.json` of the pose recorded for the same
 * camera frame, or null when no pose exists for it (e.g. tracking was
 * not active) — the relationship is encoded as it actually is, never
 * pretended to be 1:1.
 */
data class EncodedFrameRecord(
    val idx: Int,
    val ptsNs: Long,
    val androidCameraTimestampNs: Long,
    val arcoreFrameTimestampRawNs: Long,
    val poseIndex: Int?,
)

/**
 * Serialization of the `bildfang-capture/v1-frames` document
 * (`video/frames.json`). Pure Kotlin (no Android imports) so it is
 * unit-testable on the JVM.
 */
object FramesJson {

    const val SCHEMA = "bildfang-capture/v1-frames"

    fun build(
        frames: List<EncodedFrameRecord>,
        originRawNs: Long, // first encoded android_camera timestamp (raw)
        counters: RecorderCounters,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"video_timebase\": {\n")
        sb.append("    \"source_clock\": \"android_camera\",\n")
        sb.append("    \"origin_raw_ns\": ").append(originRawNs).append(",\n")
        sb.append("    \"unit\": \"ns\"\n")
        sb.append("  },\n")
        sb.append("  \"pts_domain\": \"container_pts; pts_ns = android_camera_timestamp_ns - origin_raw_ns\",\n")
        sb.append("  \"counters\": {\n")
        sb.append("    \"camera_frames_observed\": ").append(counters.cameraFramesObserved).append(",\n")
        sb.append("    \"frames_submitted\": ").append(counters.framesSubmitted).append(",\n")
        sb.append("    \"frames_encoded\": ").append(counters.framesEncoded).append(",\n")
        sb.append("    \"frames_muxed\": ").append(counters.framesMuxed).append(",\n")
        sb.append("    \"frames_dropped\": ").append(counters.framesDropped).append("\n")
        sb.append("  },\n")
        sb.append("  \"frames\": [")
        frames.forEachIndexed { i, fr ->
            sb.append('\n')
            sb.append("    { \"idx\": ").append(fr.idx)
            sb.append(", \"pts_ns\": ").append(fr.ptsNs)
            sb.append(", \"android_camera_timestamp_ns\": ").append(fr.androidCameraTimestampNs)
            sb.append(", \"arcore_frame_timestamp_raw_ns\": ").append(fr.arcoreFrameTimestampRawNs)
            if (fr.poseIndex != null) sb.append(", \"pose_index\": ").append(fr.poseIndex)
            sb.append(" }").append(if (i < frames.size - 1) "," else "")
        }
        if (frames.isEmpty()) sb.append("]")
        else sb.append("\n  ]")
        sb.append('\n').append("}\n")
        return sb.toString()
    }
}
