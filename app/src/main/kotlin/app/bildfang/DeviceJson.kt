package app.bildfang

import java.util.Locale

/**
 * `metadata/device.json` — device identity at **model level** (spec,
 * docs/capture-format.md): no `android_id`, no serial, no account
 * identifiers — the capture stays portable and non-identifying.
 *
 * The IMU block reports the rate that was **measured** from the captured
 * sample streams (P8: "measure the actual sample rate; document what
 * was observed, not an assumption"). `imu_rate_hz` is null when fewer
 * than two samples were captured (rate undecidable) rather than a
 * guessed 50.
 *
 * Pure Kotlin so it is unit-testable on the JVM.
 */
object DeviceJson {

    const val SCHEMA = "bildfang-capture/v1-device"

    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String, // e.g. "15" (VERSION.RELEASE)
        val sdkInt: Int,
        val os: String, // build fingerprint (GrapheneOS on the fleet)
        val screenW: Int,
        val screenH: Int,
        val densityDpi: Int,
        val accelPresent: Boolean,
        val gyroPresent: Boolean,
        val accelName: String,
        val gyroName: String,
        val imuAccelRateHz: Double?, // measured; null = <2 samples
        val imuGyroRateHz: Double?,
        val wallClockAtStartIso: String, // wall_clock domain
        val bootTimeAtStartMs: Long, // android_monotonic at session anchor
    )

    private fun hz(v: Double?): String =
        if (v == null || v <= 0.0) "null" else String.format(Locale.US, "%.1f", v)

    fun build(d: DeviceInfo): String {
        val f = { v: Int -> String.format(Locale.US, "%d", v) }
        val sensor = { present: Boolean, name: String ->
            if (!present) "\"ABSENT\"" else "\"${name.replace("\"", "\\\"")}\""
        }
        return """
            {
              "schema": "${SCHEMA}",
              "manufacturer": "${d.manufacturer.replace("\"", "\\\"")}",
              "model": "${d.model.replace("\"", "\\\"")}",
              "android_version": "${d.androidVersion}",
              "sdk_int": ${d.sdkInt},
              "os": "${d.os.replace("\"", "\\\"")}",
              "screen": {
                "width_px": ${f(d.screenW)},
                "height_px": ${f(d.screenH)},
                "density_dpi": ${f(d.densityDpi)}
              },
              "sensors": {
                "accelerometer": ${sensor(d.accelPresent, d.accelName)},
                "gyroscope": ${sensor(d.gyroPresent, d.gyroName)},
                "imu_rate_hz": {
                  "accelerometer": ${hz(d.imuAccelRateHz)},
                  "gyroscope": ${hz(d.imuGyroRateHz)},
                  "note": "measured from the captured sample streams (SENSOR_DELAY_GAME requested); null when fewer than two samples"
                }
              },
              "camera": {
                "back": {
                  "note": "ARCore-managed camera; physical id not queried by v0.4 (model-level identity only)"
                }
              },
              "wall_clock_at_start": "${d.wallClockAtStartIso}",
              "boot_time_at_start_ms": ${d.bootTimeAtStartMs}
            }
        """.trimIndent() + "\n"
    }
}
