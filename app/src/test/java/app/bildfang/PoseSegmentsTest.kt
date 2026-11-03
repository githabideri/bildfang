// Bildfang — P5: per-segment raw anchors + translation_raw in poses.json
// (pure, JVM-safe).
package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSegmentsTest {

    private fun pose(
        i: Int, ts: Long, rawTs: Long,
        x: Float, y: Float, z: Float,
        qx: Float = 0f, qy: Float = 0f, qz: Float = 0f, qw: Float = 1f,
        state: String = "TRACKING", segment: Int = 0,
    ) = PoseRecord(
        index = i, timestampNs = ts, frameTsRawNs = rawTs,
        x = x, y = y, z = z,
        qx = qx, qy = qy, qz = qz, qw = qw,
        trackingState = state, segment = segment,
    )

    @Suppress("UNCHECKED_CAST")
    @Test
    fun segmentsArrayCarriesRawAnchorsAndFirstPoseIndices() {
        val json = PoseJson.build(
            listOf(
                pose(0, 1000, 4321000000000L, 1.0f, 1.4f, -2.0f, 0f, 0f, 0f, 1f),
                pose(1, 2000, 4321000010000L, 1.1f, 1.4f, -1.9f),
                pose(2, 3000, 4321000020000L, 9.0f, 1.0f, 3.0f, 0.1f, 0.0f, 0.0f, 0.995f, segment = 1),
                pose(3, 4000, 4321000030000L, 9.2f, 1.0f, 3.1f, 0.1f, 0.0f, 0.0f, 0.995f, segment = 1),
            )
        )
        val v = Json.parse(json) as Map<*, *>
        val segs = v["segments"] as List<*>
        assertEquals(2, segs.size)

        val s0 = segs[0] as Map<*, *>
        assertEquals(0, (s0["segment"] as Number).toInt())
        assertEquals(0, (s0["first_pose"] as Number).toInt())
        val a0 = s0["anchor"] as Map<*, *>
        assertEquals(4321000000000L, (a0["frame_timestamp_raw_ns"] as Number).toLong())
        val t0 = a0["translation_raw"] as Map<*, *>
        assertEquals(1.0, (t0["x"] as Number).toDouble(), 1e-6)
        assertEquals(1.4, (t0["y"] as Number).toDouble(), 1e-6)
        assertEquals(-2.0, (t0["z"] as Number).toDouble(), 1e-6)

        val s1 = segs[1] as Map<*, *>
        assertEquals(1, (s1["segment"] as Number).toInt())
        assertEquals(2, (s1["first_pose"] as Number).toInt())
        val a1 = s1["anchor"] as Map<*, *>
        assertEquals(4321000020000L, (a1["frame_timestamp_raw_ns"] as Number).toLong())
        val t1 = a1["translation_raw"] as Map<*, *>
        assertEquals(9.0, (t1["x"] as Number).toDouble(), 1e-6)
        val q1 = a1["rotation_quaternion"] as Map<*, *>
        assertEquals(0.1, (q1["x"] as Number).toDouble(), 1e-6)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun translationRawIsUnnormalizedAndTranslationIsDerivedFromIt() {
        val json = PoseJson.build(
            listOf(
                pose(0, 1000, 111L, 1.0f, 1.4f, -2.0f),
                pose(1, 2000, 222L, 1.1f, 1.4f, -1.9f),
                pose(2, 3000, 333L, 9.0f, 1.0f, 3.0f, segment = 1),
                pose(3, 4000, 444L, 9.2f, 1.0f, 3.1f, segment = 1),
            )
        )
        val v = Json.parse(json) as Map<*, *>
        val poses = v["poses"] as List<*>
        val p1 = poses[1] as Map<*, *>
        // raw: (1.1, 1.4, -1.9); normalized vs anchor (1.0, 1.4, -2.0): (0.1, 0, 0.1)
        val raw1 = p1["translation_raw"] as Map<*, *>
        assertEquals(1.1, (raw1["x"] as Number).toDouble(), 1e-6)
        val norm1 = p1["translation"] as Map<*, *>
        assertEquals(0.1, (norm1["x"] as Number).toDouble(), 1e-6)
        assertEquals(0.0, (norm1["y"] as Number).toDouble(), 1e-6)
        assertEquals(0.1, (norm1["z"] as Number).toDouble(), 1e-6)

        val p3 = poses[3] as Map<*, *>
        val raw3 = p3["translation_raw"] as Map<*, *>
        assertEquals(9.2, (raw3["x"] as Number).toDouble(), 1e-6)
        val norm3 = p3["translation"] as Map<*, *>
        assertEquals(0.2, (norm3["x"] as Number).toDouble(), 1e-6)
        assertEquals(0.0, (norm3["y"] as Number).toDouble(), 1e-6)
        assertEquals(0.1, (norm3["z"] as Number).toDouble(), 1e-6)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun singlePoseSessionStillEmitsOneEntrySegmentsArray() {
        val json = PoseJson.build(listOf(pose(0, 1, 9L, 0f, 0f, 0f)))
        assertTrue(json.contains("\"segments\": ["))
        val v = Json.parse(json) as Map<*, *>
        val segs = v["segments"] as List<*>
        assertEquals(1, segs.size)
        // The old contract is untouched: first pose still at the origin.
        assertTrue(json.contains("\"x\": 0.000000, \"y\": 0.000000, \"z\": 0.000000"))
    }
}
