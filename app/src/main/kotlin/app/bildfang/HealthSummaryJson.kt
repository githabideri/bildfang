package app.bildfang

import java.util.Locale

/**
 * Builder for the optional `capture_health` block in `session.json`
 * (build 0.5.0+, P9). Pure Kotlin, so the hand-rolled JSON is
 * round-tripped through the strict test parser (the same failure
 * class — a syntax error the device would choke on — is what this
 * codebase has been bitten by before).
 *
 * Non-breaking addition: the block is optional (present only when at
 * least one 2 Hz sample was taken during recording) and consumers must
 * ignore unknown fields (capture-format.md). It never changes any
 * other field of the document.
 */
object HealthSummaryJson {

    data class Summary(
        val samples: Int,
        val codes: LinkedHashMap<String, Int>,
        val meanLuminance: Float?,
        val meanSharpness: Float?,
        val meanTransVelMs: Float?,
        val meanAngVelDegS: Float?,
        val maxTransVelMs: Float?,
        val pausedFraction: Float,
    )

    private fun opt(v: Float?): String =
        // JSON has no NaN literal — a degraded (NaN) statistic is null.
        if (v == null || v.isNaN()) "null" else String.format(Locale.US, "%.2f", v)

    fun build(s: Summary): String {
        val codes = s.codes.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }
        return buildString {
            appendLine("\"capture_health\": {")
            appendLine(
                "    \"note\": \"P9 advisory layer: statistics over ~2 Hz signal samples and the " +
                    "advisory time distribution during this capture. Advisory only - it never " +
                    "filters, discards, or alters the raw capture (docs/ROADMAP.md guiding principle).\",")
            appendLine("    \"sample_rate_hz\": 2,")
            appendLine("    \"samples\": ${s.samples},")
            appendLine("    \"codes\": { $codes },")
            appendLine("    \"mean_luminance\": ${opt(s.meanLuminance)},")
            appendLine("    \"mean_sharpness\": ${opt(s.meanSharpness)},")
            appendLine("    \"mean_trans_vel_ms\": ${opt(s.meanTransVelMs)},")
            appendLine("    \"mean_ang_vel_deg_s\": ${opt(s.meanAngVelDegS)},")
            appendLine("    \"max_trans_vel_ms\": ${opt(s.maxTransVelMs)},")
            append("    \"paused_fraction\": ${String.format(Locale.US, "%.4f", s.pausedFraction)}")
            append("}")
        }
    }
}
