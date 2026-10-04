package app.bildfang

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * `manifest.json` — the completeness marker of `bildfang-capture/v1`
 * (P7, docs/capture-format.md).
 *
 * **Written last, atomically** (temp file + rename, never a truncated
 * in-place write): a session without a complete `manifest.json` is
 * invalid/incomplete — an interrupted capture (process killed mid-
 * recording or mid-finalization) stays distinguishable from a finalized
 * one, and is never mistaken for a complete session by a consumer.
 *
 * The manifest hashes every payload (SHA-256, computed by the app on
 * export; downstream re-verification is a `sha256sum` away), records
 * versions (schema/app/git/device/ARCore), start/end, sizes, warnings,
 * and the completeness status.
 *
 * Pure Kotlin (JVM `MessageDigest`) so hashing and serialization are
 * unit-testable.
 */
object ManifestJson {

    const val SCHEMA = "bildfang-capture/v1"

    /** `file.type` values (spec list + two extensions; consumers must
     *  ignore unknown types, not fail on them). */
    const val T_VIDEO = "video"
    const val T_FRAME_INDEX = "frame_index"
    const val T_POSE = "pose"
    const val T_DISCONTINUITY = "discontinuities"
    const val T_IMU = "imu"
    const val T_INTRINSICS = "intrinsics"
    const val T_CAMERA_METADATA = "camera_metadata"
    const val T_DEVICE = "device"
    const val T_SESSION = "session"

    data class ManifestFile(
        val path: String, // session-relative
        val type: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    data class ManifestData(
        val captureId: String, // == session folder name
        val appName: String,
        val appVersion: String,
        val appCommit: String,
        val deviceManufacturer: String,
        val deviceModel: String,
        val androidVersion: String,
        val sdkInt: Int,
        val arcoreVersion: String,
        val startedAtIso: String, // wall_clock domain
        val endedAtIso: String,
        val durationNs: Long,
        val files: List<ManifestFile>,
        val warnings: List<String>,
    )

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun build(d: ManifestData): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"completeness\": \"complete\",\n")
        sb.append("  \"capture_id\": \"").append(esc(d.captureId)).append("\",\n")
        sb.append("  \"app\": {\n")
        sb.append("    \"name\": \"").append(esc(d.appName)).append("\",\n")
        sb.append("    \"version\": \"").append(esc(d.appVersion)).append("\",\n")
        sb.append("    \"commit\": \"").append(esc(d.appCommit)).append("\"\n")
        sb.append("  },\n")
        sb.append("  \"device\": {\n")
        sb.append("    \"manufacturer\": \"").append(esc(d.deviceManufacturer)).append("\",\n")
        sb.append("    \"model\": \"").append(esc(d.deviceModel)).append("\",\n")
        sb.append("    \"android_version\": \"").append(esc(d.androidVersion)).append("\",\n")
        sb.append("    \"sdk_int\": ").append(d.sdkInt).append("\n")
        sb.append("  },\n")
        sb.append("  \"arcore\": { \"play_services_version\": \"").append(esc(d.arcoreVersion)).append("\" },\n")
        sb.append("  \"started_at\": \"").append(esc(d.startedAtIso)).append("\",\n")
        sb.append("  \"ended_at\": \"").append(esc(d.endedAtIso)).append("\",\n")
        sb.append("  \"duration_ns\": ").append(d.durationNs).append(",\n")
        sb.append("  \"files\": [\n")
        d.files.forEachIndexed { i, f ->
            sb.append("    { \"path\": \"").append(esc(f.path))
              .append("\", \"type\": \"").append(esc(f.type))
              .append("\", \"size_bytes\": ").append(f.sizeBytes)
              .append(", \"sha256\": \"").append(f.sha256).append("\" }")
              .append(if (i < d.files.size - 1) "," else "").append('\n')
        }
        sb.append("  ],\n")
        sb.append("  \"warnings\": [")
        d.warnings.forEachIndexed { i, w ->
            sb.append('\n').append("    \"").append(esc(w)).append("\"")
            if (i < d.warnings.size - 1) sb.append(',')
        }
        if (d.warnings.isEmpty()) sb.append(']') else sb.append("\n  ]")
        sb.append('\n').append("}\n")
        return sb.toString()
    }

    /**
     * Streams SHA-256 of a file (does not load it — video payloads can
     * be hundreds of MB). Hex, lowercase.
     */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var n = ins.read(buf)
            while (n >= 0) {
                if (n > 0) md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
