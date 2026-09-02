package app.bildfang

/**
 * Per-frame camera metadata stream (`camera/frames.json`, P1.1 step 5).
 *
 * Bildfang's principle: log what the sensor actually did. ARCore 1.54
 * exposes `Frame.getImageMetadata()` (a Camera2Metadata-derived view);
 * we read the keys we need **per frame** and record whatever the
 * device/HAL actually reports. Availability is never assumed — each key
 * gets one of three verdicts, determined at runtime and persisted in
 * the document header:
 *
 *   AVAILABLE_AND_CAPTURED            the key was present and logged
 *   SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE  the API offers it; this device
 *                                       did not report it for this frame
 *   NOT_EXPOSED_BY_CURRENT_API        no binding/key in ARCore 1.54
 *
 * Keys captured (Camera2 semantics):
 *   SENSOR_EXPOSURE_TIME        long   ns
 *   SENSOR_SENSITIVITY          int    ISO
 *   SENSOR_FRAME_DURATION       long   ns
 *   SENSOR_ROLLING_SHUTTER_SKEW long   ns
 *   CONTROL_VIDEO_STABILIZATION_MODE byte (0=off, 1=on, 2=auto, 3=handheld)
 *   LENS_OPTICAL_STABILIZATION_MODE  byte (0=off, 1=on)
 *   SCALER_CROP_REGION          int[5] Rect(left,top,right,bottom[,n])
 *
 * Pure Kotlin + JSON; the Android-specific extraction (ImageMetadata
 * access, exception mapping) lives in MainActivity.
 */
data class CameraMetaRecord(
    val frame: Int, // index within the session's camera-frame stream
    val androidCameraTimestampNs: Long,
    val exposureTimeNs: Long? = null,
    val sensitivityIso: Int? = null,
    val frameDurationNs: Long? = null,
    val rollingShutterSkewNs: Long? = null,
    val videoStabilizationMode: Int? = null,
    val opticalStabilizationMode: Int? = null,
    val cropRegion: IntArray? = null, // [l, t, r, b]
)

object CameraMetaJson {

    const val SCHEMA = "bildfang-capture/v1-camera"

    const val AVAILABLE_AND_CAPTURED = "AVAILABLE_AND_CAPTURED"
    const val SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE = "SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE"
    const val NOT_EXPOSED_BY_CURRENT_API = "NOT_EXPOSED_BY_CURRENT_API"

    /**
     * Builds the `camera/frames.json` document. [availability] maps key
     * name -> verdict string; [stabilizationConfig] is the mode the
     * session was configured with (e.g. "EIS OFF (Config.ImageStabilizationMode.OFF)").
     */
    fun build(
        records: List<CameraMetaRecord>,
        availability: Map<String, String>,
        stabilizationConfig: String,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"source\": \"Frame.getImageMetadata() (Camera2Metadata-derived, ARCore 1.54)\",\n")
        sb.append("  \"stabilization_config\": \"").append(escape(stabilizationConfig)).append("\",\n")
        sb.append("  \"availability\": {")
        availability.entries.forEachIndexed { i, e ->
            sb.append('\n').append("    \"").append(e.key).append("\": \"").append(e.value).append("\"")
            if (i < availability.size - 1) sb.append(',')
        }
        if (availability.isEmpty()) sb.append(' ')
        sb.append("\n  },\n")
        sb.append("  \"frames\": [")
        records.forEachIndexed { i, r ->
            sb.append('\n').append("    { \"frame\": ").append(r.frame)
            sb.append(", \"android_camera_timestamp_ns\": ").append(r.androidCameraTimestampNs)
            r.exposureTimeNs?.let { sb.append(", \"exposure_time_ns\": ").append(it) }
            r.sensitivityIso?.let { sb.append(", \"sensitivity_iso\": ").append(it) }
            r.frameDurationNs?.let { sb.append(", \"frame_duration_ns\": ").append(it) }
            r.rollingShutterSkewNs?.let { sb.append(", \"rolling_shutter_skew_ns\": ").append(it) }
            r.videoStabilizationMode?.let { sb.append(", \"video_stabilization_mode\": ").append(it) }
            r.opticalStabilizationMode?.let { sb.append(", \"optical_stabilization_mode\": ").append(it) }
            r.cropRegion?.let {
                sb.append(", \"crop_region\": [")
                sb.append(it.joinToString(", "))
                sb.append(']')
            }
            sb.append(" }").append(if (i < records.size - 1) "," else "")
        }
        if (records.isEmpty()) sb.append("]") else sb.append("\n  ]")
        sb.append("\n}\n")
        return sb.toString()
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * The default (first-release) availability table, used when the
     * metadata stream never produced a single value for a key. Keys with
     * no ARCore 1.54 binding would be NOT_EXPOSED_BY_CURRENT_API — none
     * of the keys in [ALL_KEYS] fall into that class in 1.54, but the
     * distinction is kept for forward compatibility.
     */
    val ALL_KEYS = listOf(
        "SENSOR_EXPOSURE_TIME",
        "SENSOR_SENSITIVITY",
        "SENSOR_FRAME_DURATION",
        "SENSOR_ROLLING_SHUTTER_SKEW",
        "CONTROL_VIDEO_STABILIZATION_MODE",
        "LENS_OPTICAL_STABILIZATION_MODE",
        "SCALER_CROP_REGION",
    )

    /**
     * Computes the availability verdicts from observed records: a key is
     * AVAILABLE_AND_CAPTURED if at least one record carries it,
     * otherwise SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE. Pure function so it
     * is testable.
     */
    fun availabilityOf(records: List<CameraMetaRecord>): Map<String, String> = ALL_KEYS.associateWith { key ->
        val present = records.any { rec ->
            when (key) {
                "SENSOR_EXPOSURE_TIME" -> rec.exposureTimeNs != null
                "SENSOR_SENSITIVITY" -> rec.sensitivityIso != null
                "SENSOR_FRAME_DURATION" -> rec.frameDurationNs != null
                "SENSOR_ROLLING_SHUTTER_SKEW" -> rec.rollingShutterSkewNs != null
                "CONTROL_VIDEO_STABILIZATION_MODE" -> rec.videoStabilizationMode != null
                "LENS_OPTICAL_STABILIZATION_MODE" -> rec.opticalStabilizationMode != null
                "SCALER_CROP_REGION" -> rec.cropRegion != null
                else -> false
            }
        }
        if (present) AVAILABLE_AND_CAPTURED else SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE
    }
}
