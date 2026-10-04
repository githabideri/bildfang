package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Tests for `manifest.json` (P7). JSON validity itself is asserted with
 * a minimal strict parser ([Json]), so the hand-rolled JSON in the
 * builders cannot silently emit a syntax error (the exact failure class
 * that has bitten this codebase before: locale decimal commas).
 */
class ManifestJsonTest {

    private fun num(m: Map<*, *>, k: String): Long = (m[k] as Number).toLong()

    private fun data(
        files: List<ManifestJson.ManifestFile>,
        warnings: List<String> = emptyList(),
    ) = ManifestJson.ManifestData(
        captureId = "capture-20261004T120000-ab12cd",
        appName = "bildfang",
        appVersion = "0.4.0",
        appCommit = "c06bc9a",
        deviceManufacturer = "Google",
        deviceModel = "Pixel 9 Pro",
        androidVersion = "15",
        sdkInt = 34,
        arcoreVersion = "1.54.0",
        startedAtIso = "2026-10-04T12:00:00Z",
        endedAtIso = "2026-10-04T12:00:38Z",
        durationNs = 38_000_000_000L,
        files = files,
        warnings = warnings,
    )

    @Test
    fun `builds valid json with completeness marker and all fields`() {
        val json = ManifestJson.build(data(
            files = listOf(
                ManifestJson.ManifestFile("video/camera.mp4", ManifestJson.T_VIDEO, 1234, "ab"),
                ManifestJson.ManifestFile("imu/imu.csv", ManifestJson.T_IMU, 56, "cd"),
            ),
        ))
        val v = Json.parse(json) as Map<*, *>
        assertEquals("bildfang-capture/v1", v["schema"])
        assertEquals("complete", v["completeness"])
        assertEquals("capture-20261004T120000-ab12cd", v["capture_id"])
        val app = v["app"] as Map<*, *>
        assertEquals("bildfang", app["name"])
        assertEquals("0.4.0", app["version"])
        assertEquals("c06bc9a", app["commit"])
        val dev = v["device"] as Map<*, *>
        assertEquals("Google", dev["manufacturer"])
        assertEquals(34L, num(dev, "sdk_int"))
        assertEquals("1.54.0", (v["arcore"] as Map<*, *>)["play_services_version"])
        assertEquals(38_000_000_000L, num(v, "duration_ns"))
        val files = v["files"] as List<*>
        assertEquals(2, files.size)
        val f0 = files[0] as Map<*, *>
        assertEquals("video/camera.mp4", f0["path"])
        assertEquals("video", f0["type"])
        assertEquals(1234L, num(f0, "size_bytes"))
        assertEquals("ab", f0["sha256"])
        assertTrue(v["warnings"] is List<*>)
    }

    @Test
    fun `empty warnings list serializes as empty json array`() {
        val v = Json.parse(ManifestJson.build(data(files = emptyList()))) as Map<*, *>
        assertEquals(0, (v["warnings"] as List<*>).size)
        assertEquals(0, (v["files"] as List<*>).size)
    }

    @Test
    fun `device model with quotes is escaped`() {
        val v = Json.parse(ManifestJson.build(
            data(files = emptyList()).copy(deviceModel = "Pixel \"9\" Pro")
        )) as Map<*, *>
        assertEquals("Pixel \"9\" Pro", (v["device"] as Map<*, *>)["model"])
    }

    @Test
    fun `warnings are escaped and round-trip`() {
        val v = Json.parse(ManifestJson.build(
            data(files = emptyList(), warnings = listOf("no IMU samples captured (sensor \"suspended\")"))
        )) as Map<*, *>
        val w = v["warnings"] as List<*>
        assertEquals(1, w.size)
        assertEquals("no IMU samples captured (sensor \"suspended\")", w[0])
    }

    @Test
    fun `sha256 matches a known vector`() {
        val f = Files.createTempFile("bildfang-test", ".bin").toFile()
        try {
            f.writeText("bildfang")
            // sha256sum-verified for the ASCII string "bildfang"
            assertEquals(
                "82b3782b492ae3648f72202c004357f6baebf40dda0eb2070658cd2e7458fea9",
                ManifestJson.sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sha256 of an empty file is the well-known empty digest`() {
        val f = Files.createTempFile("bildfang-test", ".bin").toFile()
        try {
            f.writeBytes(ByteArray(0))
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ManifestJson.sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }
}
