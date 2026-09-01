package app.bildfang

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * One recorded ARCore camera pose.
 *
 * Frame: ARCore world frame (segmented), camera-local frame uses the
 * OpenGL convention (x right, y up, z back — camera looks along -Z).
 * See docs/coordinate-system.md.
 *
 * `timestampNs` is *derived* (session-relative, `arcore_frame` domain,
 * anchor in session.json); `frameTsRawNs` is the raw `Frame.getTimestamp()`
 * value (same domain, epoch unknown/opaque) and is always preserved when
 * available. P3: raw and derived both stored.
 *
 * `segment` starts at 0 and increments when a trajectory discontinuity
 * with a translation jump is detected (provisional world-frame-reset
 * indicator — see DiscontinuityEvent; not a verdict).
 */
data class PoseRecord(
    val index: Int,
    val timestampNs: Long,
    val androidCameraTimestampNs: Long = 0L, // HAL clock; aligns with video PTS
    val frameTsRawNs: Long = 0L, // raw ARCore frame timestamp (arcore_frame domain)
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val trackingState: String, // TRACKING | PAUSED | STOPPED
    val segment: Int,
)

/**
 * Serialization of the `bildfang-capture/v1-poses` document.
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM.
 */
object PoseJson {

    const val SCHEMA = "bildfang-capture/v1-poses"
    const val COORDINATE_SYSTEM = "arcore-world-v1"

    // Locale.US is mandatory: the device locale (de-AT) uses decimal
    // commas, which corrupted poses.json into invalid JSON (2026-09-01).
    private fun f(v: Float): String = String.format(Locale.US, "%.6f", v)

    fun build(records: List<PoseRecord>): String {
        // Normalize each segment so its first pose is exactly (0,0,0)
        // (anchor convention, see docs/coordinate-system.md §1).
        val firstPerSegment = HashMap<Int, PoseRecord>()
        for (r in records) firstPerSegment.putIfAbsent(r.segment, r)

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"coordinate_system\": \"").append(COORDINATE_SYSTEM).append("\",\n")
        sb.append("  \"clock\": \"timestamp_ns is session-relative (arcore_frame domain, anchor in session.json); raw value in frame_timestamp_raw_ns\",\n")
        sb.append("  \"units\": { \"translation\": \"meters\", \"rotation\": \"quaternion x,y,z,w\" },\n")
        sb.append("  \"poses\": [\n")
        records.forEachIndexed { i, r ->
            val anchor = firstPerSegment.getValue(r.segment)
            sb.append("    {\n")
            sb.append("      \"i\": ").append(r.index).append(",\n")
            sb.append("      \"timestamp_ns\": ").append(r.timestampNs).append(",\n")
            if (r.frameTsRawNs != 0L) {
                sb.append("      \"frame_timestamp_raw_ns\": ").append(r.frameTsRawNs).append(",\n")
            }
            if (r.androidCameraTimestampNs != 0L) {
                sb.append("      \"android_camera_timestamp_ns\": ").append(r.androidCameraTimestampNs).append(",\n")
            }
            if (r.segment > 0) sb.append("      \"segment\": ").append(r.segment).append(",\n")
            sb.append("      \"translation\": { \"x\": ").append(f(r.x - anchor.x)).append(", ")
              .append("\"y\": ").append(f(r.y - anchor.y)).append(", ")
              .append("\"z\": ").append(f(r.z - anchor.z)).append(" },\n")
            sb.append("      \"rotation_quaternion\": { \"x\": ").append(f(r.qx)).append(", ")
              .append("\"y\": ").append(f(r.qy)).append(", ")
              .append("\"z\": ").append(f(r.qz)).append(", ")
              .append("\"w\": ").append(f(r.qw)).append(" },\n")
            sb.append("      \"tracking_state\": \"").append(r.trackingState).append("\"\n")
            sb.append("    }").append(if (i < records.size - 1) "," else "").append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }
}

/**
 * A trajectory discontinuity: a point where consecutive poses do not form
 * a physically continuous trajectory. This is an *informational event* —
 * it reports which signals fired; it is deliberately not a verdict (the
 * downstream consumer decides whether it means a world-frame reset,
 * relocalization, a bad pose, or legitimate fast motion). P5.
 */
data class DiscontinuityEvent(
    val frame: Int, // pose index where it was detected
    val reasons: List<String>, // e.g. ["tracking_recovered", "translation_jump", "rotation_jump"]
    val translationJumpM: Float,
    val rotationJumpDeg: Float,
    val dtMs: Float,
)

/**
 * Serialization of the `bildfang-capture/v1-discontinuities` document
 * (written to `poses/discontinuities.json`). Pure Kotlin.
 */
object DiscontinuityJson {

    const val SCHEMA = "bildfang-capture/v1-discontinuities"

    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v)

    fun build(events: List<DiscontinuityEvent>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"note\": \"informational: which signals fired at each discontinuity; not a verdict\",\n")
        sb.append("  \"events\": [")
        events.forEachIndexed { i, e ->
            sb.append('\n')
            sb.append("    { \"i\": ").append(e.frame)
            sb.append(", \"reasons\": [")
            sb.append(e.reasons.joinToString(", ") { "\"$it\"" })
            sb.append("], \"translation_jump_m\": ").append(f(e.translationJumpM))
            sb.append(", \"rotation_jump_deg\": ").append(f(e.rotationJumpDeg))
            sb.append(", \"dt_ms\": ").append(f(e.dtMs))
            sb.append(" }").append(if (i < events.size - 1) "," else "")
        }
        if (events.isEmpty()) sb.append(']')
        else sb.append("\n  ]")
        sb.append("\n}\n")
        return sb.toString()
    }
}

/**
 * Angle between two (x,y,z,w) quaternions, in degrees: the rotation angle
 * θ = 2·acos(|q₁·q₂|) mapping one orientation to the other (double-cover
 * safe via abs). Pure Kotlin.
 */
fun quaternionAngleDeg(
    ax: Float, ay: Float, az: Float, aw: Float,
    bx: Float, by: Float, bz: Float, bw: Float,
): Float {
    val dot = abs(ax * bx + ay * by + az * bz + aw * bw)
    val clamped = minOf(1f, dot)
    return 2f * Math.toDegrees(Math.acos(clamped.toDouble())).toFloat()
}
