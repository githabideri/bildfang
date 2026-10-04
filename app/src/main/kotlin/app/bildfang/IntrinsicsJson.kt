package app.bildfang

import java.util.Locale

/**
 * `camera/intrinsics.json` (P6: document what is actually provided).
 *
 * The `source` field is explicit — the numbers in this file are what the
 * platform *actually exposed*: ARCore's `CameraIntrinsics` provides only
 * width, height, fx, fy, cx, cy (for the ARCore image size). It exposes
 * **no distortion coefficients** — a k1/k2/p1/p2/k3 model belongs to
 * Camera2 `CALIBRATION_REVIEW` metadata and is not available through the
 * ARCore camera object on the tested fleet devices; this file therefore
 * never carries one. If a future build captures Camera2 distortion it
 * goes into `camera/frames.json`, never into a fabricated field here.
 *
 * The encoded-image model is **derived** (source texture intrinsics +
 * the frozen affine mapping, exact for 90°-multiple orthogonal mappings)
 * and is tagged as such — scaling the ARCore intrinsics by a dimension
 * ratio is wrong whenever the mapping rotates or translates and is
 * refused (that error is what invalidated the 2026-09-01 washroom
 * video). The canonical description stays the affine chain in
 * session.json → video.encoded_image.mapping; see
 * docs/capture-format.md.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM.
 */
object IntrinsicsJson {

    const val SCHEMA = "bildfang-capture/v1-intrinsics"

    const val SOURCE_ARCORE = "arcore"

    /** [status] is one of EXACT / ABSENT / REFUSED (the three states of
     *  session.json video.encoded_image.rectilinear_model). */
    data class EncodedModel(
        val width: Int,
        val height: Int,
        val status: String, // EXACT | ABSENT | REFUSED
        val rotationDeg: Int, // 90-multiple; -1 when no rotation class exists
        val k: CameraIntrinsics?, // non-null only for EXACT
    )

    private fun kBlock(name: String, ci: CameraIntrinsics?, source: String, note: String): String {
        if (ci == null) return """
            |  "$name": null,
            |""".trimMargin()
        val noteJson = if (note.isEmpty()) "" else ",\n    \"note\": \"$note\""
        return String.format(
            Locale.US,
            """
              |  "$name": {
              |    "width": %d,
              |    "height": %d,
              |    "fx": %.3f,
              |    "fy": %.3f,
              |    "cx": %.3f,
              |    "cy": %.3f,
              |    "source": "%s"%s
              |  },
              |""".trimMargin(),
            ci.width, ci.height, ci.fx, ci.fy, ci.cx, ci.cy, source, noteJson
        )
    }

    fun build(
        arcoreImage: CameraIntrinsics?, // texture intrinsics (what the encoder samples)
        arcoreCameraImage: CameraIntrinsics?, // raw sensor image intrinsics
        encoded: EncodedModel?,
        cameraId: String,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n")
        sb.append("  \"model\": \"pinhole\",\n")
        sb.append("  \"source\": \"").append(SOURCE_ARCORE).append("\",\n")
        sb.append("  \"distortion\": null,\n")
        sb.append("  \"distortion_note\": \"ARCore CameraIntrinsics exposes no distortion coefficients; none are invented (a k1/k2/p1/p2/k3 model belongs to Camera2 CALIBRATION_REVIEW metadata)\",\n")
        sb.append(kBlock(
            "arcore_image", arcoreImage, SOURCE_ARCORE,
            "ARCore Camera.getTextureIntrinsics() at START: the image the encoder actually samples"
        ))
        sb.append(kBlock(
            "arcore_camera_image", arcoreCameraImage, SOURCE_ARCORE,
            "ARCore Camera.getImageIntrinsics(): the raw sensor image (equal to arcore_image on current fleet devices)"
        ))
        when {
            encoded == null ->
                sb.append("  \"encoded_image\": { \"status\": \"REFUSED (no mapping or no source intrinsics; use session.json video.encoded_image as-is)\" },\n")
            encoded.status == "EXACT" && encoded.k != null -> {
                val k = encoded.k
                sb.append(String.format(
                    Locale.US,
                    "  \"encoded_image\": {\n" +
                        "    \"width\": %d,\n" +
                        "    \"height\": %d,\n" +
                        "    \"model\": \"pinhole\",\n" +
                        "    \"status\": \"EXACT (derived: arcore texture intrinsics + frozen affine mapping, %d-degree rotation)\",\n" +
                        "    \"source\": \"derived:arcore\",\n" +
                        "    \"rotation\": %d,\n" +
                        "    \"fx\": %.3f,\n" +
                        "    \"fy\": %.3f,\n" +
                        "    \"cx\": %.3f,\n" +
                        "    \"cy\": %.3f\n" +
                        "  },\n",
                    encoded.width, encoded.height, encoded.rotationDeg, encoded.rotationDeg,
                    k.fx, k.fy, k.cx, k.cy
                ))
            }
            else ->
                sb.append("  \"encoded_image\": { \"width\": ").append(encoded.width)
                    .append(", \"height\": ").append(encoded.height)
                    .append(", \"status\": \"").append(encoded.status)
                    .append(" (no rectilinear K exists for this mapping; use the affine chain in session.json video.encoded_image.mapping)\" },\n")
        }
        sb.append("  \"canonical\": \"session.json → video.encoded_image.mapping (affine_enc_to_src) + arcore_image K is the exact model; see docs/capture-format.md\",\n")
        sb.append("  \"camera_id\": \"").append(cameraId).append("\"\n")
        sb.append("}\n")
        return sb.toString()
    }
}
