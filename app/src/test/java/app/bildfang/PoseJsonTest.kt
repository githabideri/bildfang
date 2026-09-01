package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseJsonTest {

    private fun pose(
        i: Int, ts: Long,
        x: Float, y: Float, z: Float,
        state: String = "TRACKING", segment: Int = 0,
    ) = PoseRecord(
        index = i, timestampNs = ts, x = x, y = y, z = z,
        qx = 0f, qy = 0f, qz = 0f, qw = 1f,
        trackingState = state, segment = segment,
    )

    @Test
    fun `android camera timestamp is emitted when present and omitted when zero`() {
        val withCam = PoseRecord(
            index = 0, timestampNs = 1000, androidCameraTimestampNs = 987654321L,
            x = 0f, y = 0f, z = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f,
            trackingState = "TRACKING", segment = 0,
        )
        val withoutCam = PoseRecord(
            index = 0, timestampNs = 1000, androidCameraTimestampNs = 0L,
            x = 0f, y = 0f, z = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f,
            trackingState = "TRACKING", segment = 0,
        )
        assertTrue(PoseJson.build(listOf(withCam)).contains("\"android_camera_timestamp_ns\": 987654321"))
        assertTrue(!PoseJson.build(listOf(withoutCam)).contains("android_camera_timestamp_ns"))
    }

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

    @Test
    fun `raw arcore frame timestamp is emitted when present and omitted when zero`() {
        val withRaw = pose(0, 1000, 0f, 0f, 0f).copy(frameTsRawNs = 1780000000000000000L)
        val withoutRaw = pose(0, 1000, 0f, 0f, 0f) // default 0L
        assertTrue(PoseJson.build(listOf(withRaw)).contains("\"frame_timestamp_raw_ns\": 1780000000000000000"))
        assertTrue(!PoseJson.build(listOf(withoutRaw)).contains("\"frame_timestamp_raw_ns\":"))
    }

    @Test
    fun `discontinuity events serialize with all signals`() {
        val json = DiscontinuityJson.build(
            listOf(
                DiscontinuityEvent(frame = 7, reasons = listOf("tracking_recovered", "translation_jump"),
                    translationJumpM = 2.3f, rotationJumpDeg = 5.0f, dtMs = 40f),
                DiscontinuityEvent(frame = 31, reasons = listOf("rotation_jump"),
                    translationJumpM = 0.05f, rotationJumpDeg = 62f, dtMs = 33f),
            )
        )
        assertEquals(true, json.contains("\"schema\": \"bildfang-capture/v1-discontinuities\""))
        assertEquals(true, json.contains("\"tracking_recovered\", \"translation_jump\""))
        assertEquals(true, json.contains("\"i\": 31"))
        assertEquals(true, json.contains("\"rotation_jump_deg\": 62.000"))
        assertEquals(2, json.split("\"i\": ").size - 1)
    }

    @Test
    fun `empty discontinuities list serializes as empty array`() {
        val json = DiscontinuityJson.build(emptyList())
        assertEquals(true, json.contains("\"events\": []"))
    }

    @Test
    fun `quaternion angle identity is zero and 90 degree rotation about z is 90`() {
        assertEquals(0f, quaternionAngleDeg(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 0.001f)
        val r90 = (1f / Math.sqrt(2.0)).toFloat()
        assertEquals(90f, quaternionAngleDeg(0f, 0f, 0f, 1f, 0f, 0f, r90, r90), 0.01f)
        // double-cover: -q represents the same rotation
        assertEquals(0f, quaternionAngleDeg(0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f), 0.01f)
    }
}
