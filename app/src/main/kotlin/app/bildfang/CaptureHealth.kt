package app.bildfang

/**
 * P9 — cheap capture-health signals (the advisory layer).
 *
 * The decision function is pure (no Android imports) and unit-tested.
 * It maps cheap, explainable signals — ARCore tracking state, pose
 * velocities, and low-rate image statistics (mean luminance, gradient
 * energy, clipped fraction) — onto actionable advisory messages
 * (`CAPTURE GOOD`, `MOVE SLOWER`, `TOO DARK`, `TRACKING LOST`, …).
 *
 * Guiding principle (docs/ROADMAP.md): this layer ADVISES. It never
 * filters, discards, or alters the raw capture in any way; frames are
 * never dropped because of these scores, and the raw signal values are
 * preserved as a summary in `session.json → capture_health` so a later
 * (possibly better) model can re-evaluate the same data offline.
 */

/** One 2 Hz health sample. All values are measured, never assumed. */
data class HealthSample(
    val tracking: String,      // "TRACKING" | "PAUSED" | "STOPPED"
    val pausedMs: Long,        // ms since tracking left TRACKING (0 while TRACKING)
    val transVelMs: Float,     // EMA of |Δpos|/Δt over consecutive TRACKING poses (m/s)
    val angVelDegS: Float,     // EMA of quaternion angle / Δt over consecutive TRACKING poses (deg/s)
    val luminance: Float,      // 0..255, mean over the downsampled grid; NaN = unavailable
    val sharpness: Float,      // mean |Δ| of grid luminance (gradient energy); NaN = unavailable
    val clippedFrac: Float,    // fraction of grid pixels with luminance >= CaptureHealth.CLIP_LEVEL
)

/** The result of [CaptureHealth.advise]: a code, a short human message, a severity. */
data class Advisory(val code: String, val message: String, val severity: Int)

/** Image statistics of one downsampled grid read of the live preview. */
data class HealthGridStats(val luminance: Float, val sharpness: Float, val clippedFrac: Float)

object CaptureHealth {

    const val SEV_OK = 0
    const val SEV_WARN = 1
    const val SEV_BAD = 2

    // Thresholds — one place. Initial values are conservative starting
    // points from fleet-device captures; they are tuned live (ROADMAP
    // P9) and the tuning is recorded there, not here.
    const val TOO_DARK_LUM = 40f          // mean grid luminance (0..255)
    const val CLIP_LEVEL = 250f           // a grid pixel at/above this counts as clipped
    const val TOO_BRIGHT_FRAC = 0.25f     // clipped fraction that is "too bright"
    const val LOW_SHARPNESS = 4.0f        // mean luminance gradient on the small grid
    const val MOVE_SLOWER_VEL_MS = 1.5f   // translational speed (m/s)
    const val MOVE_SLOWER_ANG_DEG_S = 150f // angular speed (deg/s)
    const val STILL_AFTER_MS = 6_000L     // "walk" advisory after this long near-still (recording)
    const val STILL_VEL_MS = 0.05f        // below this the user counts as standing still

    /**
     * Worst condition first: a lost tracker is the worst thing that can
     * happen to the data (paused pose runs are useless to
     * reconstruction), then exposure problems, then motion problems.
     * `stillMs` is the time since the last meaningful movement.
     */
    fun advise(s: HealthSample, recording: Boolean, stillMs: Long): Advisory = when {
        s.tracking == "STOPPED" ->
            Advisory("TRACKING_LOST", "TRACKING LOST — hold the phone steady", SEV_BAD)
        s.tracking != "TRACKING" ->
            if (recording)
                Advisory("TRACKING_LOST", "TRACKING LOST — hold still, wait for it to recover", SEV_BAD)
            else
                Advisory("FIND_FEATURES", "FIND FEATURES — point at a textured scene", SEV_WARN)
        !s.luminance.isNaN() && s.luminance < TOO_DARK_LUM ->
            Advisory("TOO_DARK", "TOO DARK — add light", SEV_BAD)
        s.clippedFrac >= TOO_BRIGHT_FRAC ->
            Advisory("TOO_BRIGHT", "TOO BRIGHT — avoid direct sun / lamps", SEV_WARN)
        !s.sharpness.isNaN() && s.sharpness < LOW_SHARPNESS ->
            Advisory("LOW_VISUAL_DETAIL", "LOW VISUAL DETAIL — move closer or face a textured surface", SEV_WARN)
        s.transVelMs > MOVE_SLOWER_VEL_MS || s.angVelDegS > MOVE_SLOWER_ANG_DEG_S ->
            Advisory("MOVE_SLOWER", "MOVE SLOWER — walk, don't rush", SEV_WARN)
        recording && stillMs >= STILL_AFTER_MS && s.transVelMs < STILL_VEL_MS ->
            Advisory("STILL", "MOVE — walk slowly through the room", SEV_WARN)
        recording ->
            Advisory("GOOD", "CAPTURE GOOD", SEV_OK)
        else ->
            Advisory("READY", "READY — press START CAPTURE", SEV_OK)
    }
}

/**
 * Accumulator over the 2 Hz samples of one recording; produces the
 * optional `session.json → capture_health` summary at finalization.
 * Pure Kotlin. The summary is *informational* (the manifest and the
 * raw payloads are untouched by it).
 */
class HealthAccumulator {
    var samples = 0
    val codes = LinkedHashMap<String, Int>()
    private var lumSum = 0f
    private var lumN = 0
    private var sharpSum = 0f
    private var sharpN = 0
    private var velSum = 0f
    private var angSum = 0f
    var maxTransVelMs = 0f
    private var pausedSamples = 0

    fun reset() {
        samples = 0
        codes.clear()
        lumSum = 0f; lumN = 0
        sharpSum = 0f; sharpN = 0
        velSum = 0f; angSum = 0f
        maxTransVelMs = 0f
        pausedSamples = 0
    }

    fun add(s: HealthSample, a: Advisory) {
        samples++
        codes[a.code] = (codes[a.code] ?: 0) + 1
        if (!s.luminance.isNaN()) { lumSum += s.luminance; lumN++ }
        if (!s.sharpness.isNaN()) { sharpSum += s.sharpness; sharpN++ }
        velSum += s.transVelMs
        angSum += s.angVelDegS
        if (s.transVelMs > maxTransVelMs) maxTransVelMs = s.transVelMs
        if (s.tracking != "TRACKING") pausedSamples++
    }

    fun toSummary(): HealthSummaryJson.Summary = HealthSummaryJson.Summary(
        samples = samples,
        codes = codes,
        meanLuminance = if (lumN > 0) lumSum / lumN else null,
        meanSharpness = if (sharpN > 0) sharpSum / sharpN else null,
        meanTransVelMs = if (samples > 0) velSum / samples else null,
        meanAngVelDegS = if (samples > 0) angSum / samples else null,
        maxTransVelMs = if (samples > 0) maxTransVelMs else null,
        pausedFraction = if (samples > 0) pausedSamples.toFloat() / samples else 0f,
    )
}
