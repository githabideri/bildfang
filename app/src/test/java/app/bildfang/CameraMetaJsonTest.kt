package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.1 camera-metadata tests: JSON builder and availability verdicts.
 */
class CameraMetaJsonTest {

    @Test
    fun `availability of empty record list is all supported-but-unavailable`() {
        val a = CameraMetaJson.availabilityOf(emptyList())
        assertEquals(7, a.size)
        for (key in CameraMetaJson.ALL_KEYS) {
            assertEquals(CameraMetaJson.SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE, a[key])
        }
    }

    @Test
    fun `availability reflects exactly the keys that were reported`() {
        val rec = CameraMetaRecord(
            frame = 0,
            androidCameraTimestampNs = 123L,
            exposureTimeNs = 40_000_000L,
            cropRegion = intArrayOf(0, 0, 4000, 3000),
        )
        val a = CameraMetaJson.availabilityOf(listOf(rec))
        assertEquals(CameraMetaJson.AVAILABLE_AND_CAPTURED, a["SENSOR_EXPOSURE_TIME"])
        assertEquals(CameraMetaJson.AVAILABLE_AND_CAPTURED, a["SCALER_CROP_REGION"])
        assertEquals(CameraMetaJson.SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE, a["SENSOR_SENSITIVITY"])
        assertEquals(CameraMetaJson.SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE, a["SENSOR_ROLLING_SHUTTER_SKEW"])
    }

    @Test
    fun `json contains schema, recorded values, and availability`() {
        val recs = listOf(
            CameraMetaRecord(0, 100L, exposureTimeNs = 40_000_000L, sensitivityIso = 160),
            CameraMetaRecord(1, 150L, exposureTimeNs = 30_000_000L),
        )
        val json = CameraMetaJson.build(
            recs,
            CameraMetaJson.availabilityOf(recs),
            "EIS OFF (explicitly set, Config.ImageStabilizationMode.OFF)",
        )
        assertTrue(json.contains("\"schema\": \"bildfang-capture/v1-camera\""))
        assertTrue(json.contains("\"exposure_time_ns\": 40000000"))
        assertTrue(json.contains("\"sensitivity_iso\": 160"))
        assertTrue(json.contains("\"CONTROL_VIDEO_STABILIZATION_MODE\": \"SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE\""))
        // a missing field must be absent, not zero
        val second = json.substringAfter("frame\": 1")
        assertFalse(second.contains("sensitivity_iso"))
        assertTrue(json.contains("EIS OFF (explicitly set, Config.ImageStabilizationMode.OFF)"))
    }

    @Test
    fun `empty frame list still produces a valid document`() {
        val json = CameraMetaJson.build(emptyList(), CameraMetaJson.availabilityOf(emptyList()), "unknown")
        assertTrue(json.contains("\"frames\": []"))
        assertTrue(json.contains("\"stabilization_config\": \"unknown\""))
    }
}
