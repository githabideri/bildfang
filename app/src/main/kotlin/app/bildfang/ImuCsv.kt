package app.bildfang

import java.util.Locale

/**
 * `imu/imu.csv` (P8): the merged raw IMU stream.
 *
 * Format (full device):
 *
 *     ts_ns,type,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z
 *     1234,accel,0.01,9.82,-0.03,,,,
 *     1237,gyro,,,,0.001,-0.002,0.001
 *
 * - `ts_ns` is `elapsedRealtimeNanos()` at event time (android_monotonic
 *   domain — the only domain this file may use).
 * - `type` is the sensor that produced the newest sample at that
 *   timestamp (accel wins ties).
 * - A stream that has not produced a fresh sample within [MAX_CARRY_GAP]
 *   of the row timestamp is left empty (not carried forward): the gap
 *   itself is the data.
 * - A sensor that is missing on the device drops its columns entirely
 *   (the header says which columns are present).
 * - Units: m/s² and rad/s, raw — no gravity removal, no calibration, no
 *   resampling. Downstream filtering is the consumer's job.
 * - Decimals are locale-fixed (US): a German-locale device must not
 *   emit `9,82`.
 */
object ImuCsv {

    const val HEADER = "ts_ns,type,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z"
    const val HEADER_ACCEL_ONLY = "ts_ns,type,accel_x,accel_y,accel_z"
    const val HEADER_GYRO_ONLY = "ts_ns,type,gyro_x,gyro_y,gyro_z"

    private const val MAX_CARRY_GAP = 100_000_000L // 100 ms

    /**
     * Merge the two raw streams (already timestamp-ordered, since
     * SensorEvent callbacks arrive in order). Null = sensor missing on
     * this device.
     */
    fun build(
        accel: List<ImuLogger.Sample>?,
        gyro: List<ImuLogger.Sample>?,
    ): String {
        val sb = StringBuilder()
        sb.append(
            when {
                accel != null && gyro != null -> HEADER
                accel != null -> HEADER_ACCEL_ONLY
                gyro != null -> HEADER_GYRO_ONLY
                else -> HEADER // both missing: full header, zero rows
            },
        ).append('\n')
        if (accel == null && gyro == null) return sb.toString()

        // Non-null stand-ins: an absent sensor reads as an empty stream
        // (its pointer never advances; the column-existence checks below
        // still use the original nullable parameters).
        val a = accel ?: emptyList()
        val g = gyro ?: emptyList()
        // Two-pointer merge; a timestamp collision goes to accel.
        var ai = -1
        var gi = -1
        while (true) {
            val na = a.getOrNull(ai + 1)
            val ng = g.getOrNull(gi + 1)
            if (na == null && ng == null) break
            val aWins = na != null && (ng == null || na.tsNs <= ng.tsNs)
            if (aWins) ai++ else gi++
            val tsPick = if (aWins) a[ai].tsNs else g[gi].tsNs
            val aFresh = ai >= 0 && (tsPick - a[ai].tsNs) < MAX_CARRY_GAP
            val gFresh = gi >= 0 && (tsPick - g[gi].tsNs) < MAX_CARRY_GAP

            val fields = ArrayList<String>(8)
            fields.add(tsPick.toString())
            fields.add(if (aWins) "accel" else "gyro")
            if (accel != null) {
                if (aFresh) {
                    val s = a[ai]
                    fields.add(f(s.x)); fields.add(f(s.y)); fields.add(f(s.z))
                } else repeat(3) { fields.add("") }
            }
            if (gyro != null) {
                if (gFresh) {
                    val s = g[gi]
                    fields.add(f(s.x)); fields.add(f(s.y)); fields.add(f(s.z))
                } else repeat(3) { fields.add("") }
            }
            for ((i, v) in fields.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append(v)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.6f", v)
}
