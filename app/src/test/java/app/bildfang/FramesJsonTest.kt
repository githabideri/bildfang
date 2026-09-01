package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesJsonTest {

    private fun frame(idx: Int, camTs: Long, poseIndex: Int? = idx) =
        EncodedFrameRecord(
            idx = idx,
            ptsNs = camTs - 1000L, // origin = 1000
            androidCameraTimestampNs = camTs,
            arcoreFrameTimestampRawNs = 1780000000000000000L + camTs,
            poseIndex = poseIndex,
        )

    @Test
    fun `timebase origin is persisted and reversible`() {
        val json = FramesJson.build(
            listOf(frame(0, 1000L), frame(1, 41667L)),
            originRawNs = 1000L,
            counters = RecorderCounters(2, 2, 2, 2, 0),
        )
        assertTrue(json.contains("\"source_clock\": \"android_camera\""))
        assertTrue(json.contains("\"origin_raw_ns\": 1000"))
        assertTrue(json.contains("\"pts_ns\": 0"))
        assertTrue(json.contains("\"pts_ns\": 40667"))
        assertTrue(json.contains("\"android_camera_timestamp_ns\": 41667"))
    }

    @Test
    fun `pose index is emitted when present and omitted when null`() {
        val with = FramesJson.build(listOf(frame(0, 1000L)), 1000L, RecorderCounters())
        val without = FramesJson.build(
            listOf(frame(0, 1000L, poseIndex = null)), 1000L, RecorderCounters()
        )
        assertTrue(with.contains("\"pose_index\": 0"))
        assertTrue(!with.contains("null"))
        assertTrue(!without.contains("pose_index"))
    }

    @Test
    fun `counters are all present`() {
        val json = FramesJson.build(
            emptyList(), 0L,
            RecorderCounters(
                cameraFramesObserved = 300,
                framesSubmitted = 299,
                framesEncoded = 298,
                framesMuxed = 298,
                framesDropped = 2,
            )
        )
        assertTrue(json.contains("\"camera_frames_observed\": 300"))
        assertTrue(json.contains("\"frames_submitted\": 299"))
        assertTrue(json.contains("\"frames_encoded\": 298"))
        assertTrue(json.contains("\"frames_muxed\": 298"))
        assertTrue(json.contains("\"frames_dropped\": 2"))
        assertTrue(json.contains("\"frames\": []"))
    }

    @Test
    fun `schema and pts domain are documented in the document`() {
        val json = FramesJson.build(emptyList(), 0L, RecorderCounters())
        assertTrue(json.contains("\"schema\": \"bildfang-capture/v1-frames\""))
        assertTrue(json.contains("pts_ns = android_camera_timestamp_ns - origin_raw_ns"))
    }
}
