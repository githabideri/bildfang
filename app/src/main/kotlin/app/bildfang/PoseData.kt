package app.bildfang

/**
 * One recorded ARCore camera pose.
 *
 * Frame: ARCore world frame (segmented), camera-local frame uses the
 * OpenGL convention (x right, y up, z back — camera looks along -Z).
 * See docs/coordinate-system.md. `segment` starts at 0 and increments on
 * every ARCore world-frame reset (detected as a large jump between two
 * consecutive TRACKING frames).
 */
data class PoseRecord(
    val index: Int,
    val timestampNs: Long,
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

    private fun f(v: Float): String = String.format("%.6f", v)

    fun build(records: List<PoseRecord>): String {
        // Normalize each segment so its first pose is exactly (0,0,0)
        // (anchor convention, see docs/coordinate-system.md §1).
        val firstPerSegment = HashMap<Int, PoseRecord>()
        for (r in records) firstPerSegment.putIfAbsent(r.segment, r)

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"coordinate_system\": \"").append(COORDINATE_SYSTEM).append("\",\n")
        sb.append("  \"clock\": \"monotonic_ns\",\n")
        sb.append("  \"units\": { \"translation\": \"meters\", \"rotation\": \"quaternion x,y,z,w\" },\n")
        sb.append("  \"poses\": [\n")
        records.forEachIndexed { i, r ->
            val anchor = firstPerSegment.getValue(r.segment)
            sb.append("    {\n")
            sb.append("      \"i\": ").append(r.index).append(",\n")
            sb.append("      \"timestamp_ns\": ").append(r.timestampNs).append(",\n")
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
