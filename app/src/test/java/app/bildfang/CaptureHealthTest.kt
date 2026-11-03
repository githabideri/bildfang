// Bildfang — P9 capture-health advisory engine (pure, JVM-safe).
package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHealthTest {

    private fun sample(
        tracking: String = "TRACKING",
        pausedMs: Long = 0,
        transVelMs: Float = 0.15f,
        angVelDegS: Float = 10f,
        luminance: Float = 120f,
        sharpness: Float = 10f,
        clippedFrac: Float = 0.001f,
    ) = HealthSample(tracking, pausedMs, transVelMs, angVelDegS, luminance, sharpness, clippedFrac)

    @Test
    fun trackingLostWhileRecordingIsWorst() {
        val adv = CaptureHealth.advise(
            sample(tracking = "PAUSED", pausedMs = 40_000), recording = true, stillMs = 0
        )
        assertEquals("TRACKING_LOST", adv.code)
        assertEquals(CaptureHealth.SEV_BAD, adv.severity)
        // STOPPED state: same code, same severity.
        val stopped = CaptureHealth.advise(sample(tracking = "STOPPED"), true, 0)
        assertEquals("TRACKING_LOST", stopped.code)
        assertEquals(CaptureHealth.SEV_BAD, stopped.severity)
        // A dark scene does NOT beat a lost tracker.
        val dark = CaptureHealth.advise(
            sample(tracking = "PAUSED", pausedMs = 5_000, luminance = 10f, clippedFrac = 0f),
            true, 0,
        )
        assertEquals("TRACKING_LOST", dark.code)
    }

    @Test
    fun trackingLostPreRecordingAsksForFeatures() {
        val adv = CaptureHealth.advise(
            sample(tracking = "PAUSED", pausedMs = 5_000), recording = false, stillMs = 0
        )
        assertEquals("FIND_FEATURES", adv.code)
        assertEquals(CaptureHealth.SEV_WARN, adv.severity)
    }

    @Test
    fun brightnessChecks() {
        assertEquals("TOO_DARK",
            CaptureHealth.advise(sample(luminance = 10f, clippedFrac = 0f), false, 0).code)
        assertEquals("TOO_BRIGHT",
            CaptureHealth.advise(sample(luminance = 100f, clippedFrac = 0.4f), false, 0).code)
        // A dark frame with clipped highlights is dark (dark is checked first).
        assertEquals("TOO_DARK",
            CaptureHealth.advise(sample(luminance = 10f, clippedFrac = 0.35f), false, 0).code)
    }

    @Test
    fun lowDetailBeatsMotionAdvice() {
        val adv = CaptureHealth.advise(
            sample(transVelMs = 2.0f, sharpness = 0.5f), recording = true, stillMs = 0
        )
        assertEquals("LOW_VISUAL_DETAIL", adv.code)
    }

    @Test
    fun moveSlowerForRushing() {
        assertEquals("MOVE_SLOWER",
            CaptureHealth.advise(sample(transVelMs = 2.0f), true, 0).code)
        assertEquals("MOVE_SLOWER",
            CaptureHealth.advise(sample(angVelDegS = 200f), true, 0).code)
        // A walking pace (~0.5 m/s) is not rushing.
        assertEquals("GOOD",
            CaptureHealth.advise(sample(transVelMs = 0.5f), true, 0).code)
    }

    @Test
    fun stillWhileRecordingAsksToMove() {
        assertEquals("STILL",
            CaptureHealth.advise(sample(transVelMs = 0.01f), true, 7_000).code)
        // Not still yet (under the 6 s threshold) → GOOD.
        assertEquals("GOOD",
            CaptureHealth.advise(sample(transVelMs = 0.01f), true, 0).code)
    }

    @Test
    fun preRecordingReadiness() {
        // Healthy scene, no recording → READY.
        val adv = CaptureHealth.advise(sample(transVelMs = 0.10f), false, 0)
        assertEquals("READY", adv.code)
        assertEquals(CaptureHealth.SEV_OK, adv.severity)
    }

    @Test
    fun goodCase() {
        val adv = CaptureHealth.advise(sample(transVelMs = 0.3f, angVelDegS = 20f), true, 0)
        assertEquals("GOOD", adv.code)
        assertEquals(CaptureHealth.SEV_OK, adv.severity)
    }

    @Test
    fun nanSignalsDegradeNotAlarm() {
        // Degraded grid read (NaN) must never read as too dark / low detail.
        val adv = CaptureHealth.advise(
            sample(luminance = Float.NaN, sharpness = Float.NaN, clippedFrac = Float.NaN),
            false, 0,
        )
        assertEquals("READY", adv.code)
        val recording = CaptureHealth.advise(
            sample(luminance = Float.NaN, sharpness = Float.NaN, clippedFrac = Float.NaN),
            true, 0,
        )
        assertEquals("GOOD", recording.code)
    }

    // -- accumulator ----------------------------------------------------

    @Test
    fun accumulatorSummary() {
        val acc = HealthAccumulator()
        repeat(10) {
            acc.add(sample(), Advisory("GOOD", "ok", CaptureHealth.SEV_OK))
        }
        repeat(2) {
            acc.add(
                sample(tracking = "PAUSED", pausedMs = 500, luminance = 10f, clippedFrac = 0f),
                Advisory("TOO_DARK", "too dark", CaptureHealth.SEV_BAD),
            )
        }
        val s = acc.toSummary()
        assertEquals(12, s.samples)
        assertEquals(10, s.codes["GOOD"] ?: 0)
        assertEquals(2, s.codes["TOO_DARK"] ?: 0)
        assertEquals(2.0 / 12.0, s.pausedFraction.toDouble(), 1e-6)
        // 10 × 120 + 2 × 10 over 12 samples.
        assertEquals(101.666, s.meanLuminance!!.toDouble(), 0.1)
        assertTrue(s.meanTransVelMs!! >= 0f)
        acc.reset()
        assertEquals(0, acc.samples)
    }
}
