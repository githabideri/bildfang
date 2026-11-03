// Bildfang — P9 capture_health JSON block (pure, JVM-safe).
package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthSummaryJsonTest {

    @Test
    fun blockParsesWithAllFields() {
        val s = HealthSummaryJson.Summary(
            samples = 76,
            codes = linkedMapOf(
                "GOOD" to 60,
                "MOVE_SLOWER" to 16,
            ),
            meanLuminance = 121.4f,
            meanSharpness = 9.7f,
            meanTransVelMs = 0.18f,
            meanAngVelDegS = 14.2f,
            maxTransVelMs = 1.9f,
            pausedFraction = 0.021f,
        )
        val text = HealthSummaryJson.build(s)
        // The block is a key:value pair ("capture_health": { … }); wrap
        // it in an object alongside a dummy sibling to parse it alone.
        val v = Json.parse("{\"x\": 1, $text}") as Map<*, *>
        val h = v["capture_health"] as Map<*, *>
        val codes = h["codes"] as Map<*, *>
        assertEquals(76, (h["samples"] as Number).toLong())
        assertEquals(2, (h["sample_rate_hz"] as Number).toLong())
        assertEquals(60L, (codes["GOOD"] as Number).toLong())
        assertEquals(16L, (codes["MOVE_SLOWER"] as Number).toLong())
        assertEquals(121.4, (h["mean_luminance"] as Number).toDouble(), 0.01)
        assertEquals(9.7, (h["mean_sharpness"] as Number).toDouble(), 0.01)
        assertEquals(0.18, (h["mean_trans_vel_ms"] as Number).toDouble(), 0.001)
        assertEquals(0.021, (h["paused_fraction"] as Number).toDouble(), 1e-6)
        // note documents the advisory-only contract.
        assertTrue("advisory" in (h["note"] as String))
    }

    @Test
    fun degradedMeansBecomeNull() {
        val s = HealthSummaryJson.Summary(
            samples = 4,
            codes = linkedMapOf("FIND_FEATURES" to 4),
            meanLuminance = Float.NaN,
            meanSharpness = Float.NaN,
            meanTransVelMs = 0f,
            meanAngVelDegS = 0f,
            maxTransVelMs = 0f,
            pausedFraction = 0.5f,
        )
        val text = HealthSummaryJson.build(s)
        val v = Json.parse("{\"x\": 1, $text}") as Map<*, *>
        val h = v["capture_health"] as Map<*, *>
        assertNull(h["mean_luminance"])
        assertNull(h["mean_sharpness"])
        assertEquals(0.5, (h["paused_fraction"] as Number).toDouble(), 1e-9)
        assertEquals(4L, (h["samples"] as Number).toLong())
    }

    private fun assertTrue(b: Boolean, msg: String = "failed") {
        if (!b) throw AssertionError(msg)
    }

    @Test
    fun blockEmbedsInSessionJsonShape() {
        // The way MainActivity embeds it: the raw block (key at column 0,
        // inner lines 4) inside a bigger object with a 12-space base
        // indent. Must parse and the sibling keys must survive.
        val s = HealthSummaryJson.Summary(
            samples = 3,
            codes = linkedMapOf("READY" to 3),
            meanLuminance = 100f,
            meanSharpness = 8f,
            meanTransVelMs = 0.1f,
            meanAngVelDegS = 5f,
            maxTransVelMs = 0.2f,
            pausedFraction = 0f,
        )
        val block = HealthSummaryJson.build(s)
        // Mirrors the production buildSessionJson template: the block
        // follows the closing "clock" object, whose comma is added only
        // when the block is present.
        val doc = """
            {
              "schema": "bildfang-capture/v1",
              "clock": {
                "anchor_frame_ts": 1234567
              }${if (block.isNotEmpty()) "," else ""}
${if (block.isNotEmpty()) block else ""}
            }
        """.trimIndent()
        val v = Json.parse(doc) as Map<*, *>
        assertEquals("bildfang-capture/v1", v["schema"])
        val h = v["capture_health"] as Map<*, *>
        assertEquals(3L, (h["samples"] as Number).toLong())
        assertEquals(1234567L, ((v["clock"] as Map<*, *>)["anchor_frame_ts"] as Number).toLong())
    }
}
