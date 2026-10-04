package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `camera/intrinsics.json` (P6: source tagging, no fabricated
 * distortion, derived-only encoded model).
 */
class IntrinsicsJsonTest {

    private val texK = CameraIntrinsics(
        width = 960, height = 2142,
        fx = 623.1, fy = 623.4, cx = 479.5, cy = 1071.2,
    )
    private val imgK = CameraIntrinsics(
        width = 960, height = 2142,
        fx = 623.1, fy = 623.4, cx = 479.5, cy = 1071.2,
    )

    private fun num(m: Map<*, *>, k: String): Double = (m[k] as Number).toDouble()

    @Test
    fun `source is tagged arcore and distortion is explicitly absent`() {
        val v = Json.parse(IntrinsicsJson.build(texK, imgK, null, "back")) as Map<*, *>
        assertEquals(IntrinsicsJson.SCHEMA, v["schema"])
        assertEquals("pinhole", v["model"])
        assertEquals(IntrinsicsJson.SOURCE_ARCORE, v["source"])
        assertNull(v["distortion"])
        assertTrue((v["distortion_note"] as String).isNotEmpty())
        assertEquals("back", v["camera_id"])
    }

    @Test
    fun `arcore blocks carry source tag and exact numbers`() {
        val v = Json.parse(IntrinsicsJson.build(texK, imgK, null, "back")) as Map<*, *>
        val a = v["arcore_image"] as Map<*, *>
        assertEquals(IntrinsicsJson.SOURCE_ARCORE, a["source"])
        assertEquals(960.0, num(a, "width"), 0.0)
        assertEquals(623.1, num(a, "fx"), 1e-3)
        assertEquals(1071.2, num(a, "cy"), 1e-3)
    }

    @Test
    fun `null arcore image serializes as null block`() {
        val v = Json.parse(IntrinsicsJson.build(null, null, null, "back")) as Map<*, *>
        assertNull(v["arcore_image"])
        assertNull(v["arcore_camera_image"])
    }

    @Test
    fun `exact encoded model carries derived k and rotation`() {
        val enc = IntrinsicsJson.EncodedModel(
            width = 1080, height = 2400,
            status = "EXACT",
            rotationDeg = 270,
            k = CameraIntrinsics(1080, 2400, 400.0, 401.0, 540.0, 1200.0),
        )
        val v = Json.parse(IntrinsicsJson.build(texK, imgK, enc, "back")) as Map<*, *>
        val e = v["encoded_image"] as Map<*, *>
        assertEquals("derived:arcore", e["source"])
        assertEquals(270.0, num(e, "rotation"), 0.0)
        assertEquals(400.0, num(e, "fx"), 1e-3)
        assertEquals(1080.0, num(e, "width"), 0.0)
        assertTrue((e["status"] as String).startsWith("EXACT"))
    }

    @Test
    fun `absent and refused models carry no k`() {
        for (status in listOf("ABSENT", "REFUSED")) {
            val enc = IntrinsicsJson.EncodedModel(1080, 2400, status, -1, null)
            val v = Json.parse(IntrinsicsJson.build(texK, imgK, enc, "back")) as Map<*, *>
            val e = v["encoded_image"] as Map<*, *>
            assertNull(e["fx"])
            assertTrue((e["status"] as String).startsWith(status))
        }
    }
}
