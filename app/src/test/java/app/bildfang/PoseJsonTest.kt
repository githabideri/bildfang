package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseJsonTest {

    private fun pose(
        i: Int, ts: Long,
        x: Float, y: Float, z: Float,
        state: String = "TRACKING", segment: Int = 0,
    ) = PoseRecord(i, ts, x, y, z, 0f, 0f, 0f, 1f, state, segment)

    @Test
    fun `first pose of a segment is normalized to origin`() {
        val json = PoseJson.build(
            listOf(
                pose(0, 1000, 1.0f, 1.4f, -2.0f),
                pose(1, 2000, 1.1f, 1.4f, -1.9f),
            )
        )
        assertTrue(json.contains("\"x\": 0.000000, \"y\": 0.000000, \"z\": 0.000000"))
    }

    @Test
    fun `second segment is normalized independently and carries a segment field`() {
        val json = PoseJson.build(
            listOf(
                pose(0, 1000, 0f, 0f, 0f),
                pose(1, 2000, 0.5f, 0f, -0.5f),
                pose(2, 3000, 9.0f, 1.0f, 3.0f, segment = 1),
                pose(3, 4000, 9.2f, 1.0f, 3.1f, segment = 1),
            )
        )
        assertTrue(json.contains("\"segment\": 1"))
        // segment-1 anchor (9.0, 1.0, 3.0) → its first pose is (0,0,0), second (0.2, 0, 0.1)
        assertTrue(json.contains("\"x\": 0.200000, \"y\": 0.000000, \"z\": 0.100000"))
    }

    @Test
    fun `timestamps and states pass through unchanged`() {
        val json = PoseJson.build(
            listOf(
                pose(0, 4321000000000L, 0f, 0f, 0f),
                pose(1, 4321000500000L, 0.1f, 0f, -0.1f, state = "PAUSED"),
            )
        )
        assertEquals(true, json.contains("\"timestamp_ns\": 4321000500000"))
        assertEquals(true, json.contains("\"tracking_state\": \"PAUSED\""))
        assertEquals(true, json.contains("\"schema\": \"bildfang-capture/v1-poses\""))
        assertEquals(true, json.contains("\"coordinate_system\": \"arcore-world-v1\""))
    }

    @Test
    fun `single pose yields valid json structure`() {
        val json = PoseJson.build(listOf(pose(0, 1, 0f, 0f, 0f)))
        // parse-free structural checks: one entry, balanced array
        assertEquals(true, json.contains("\"poses\": ["))
        assertEquals(1, json.split("\"i\": ").size - 1)
    }
}
