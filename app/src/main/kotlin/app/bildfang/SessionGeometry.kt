package app.bildfang

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Capture orientation, frozen at START (v0.3.0 orientation policy).
 *
 * Once a recording starts, orientation / display geometry / encoder
 * dimensions / camera geometry are immutable for the whole session. If
 * the device is physically rotated mid-recording the capture coordinate
 * system stays fixed and the event is logged
 * (rotation_events_during_recording in session.json) — the geometry is
 * never silently changed. See docs/ROADMAP.md (P1.1) and
 * docs/capture-format.md.
 */
enum class CaptureOrientation(val label: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
}

/**
 * Rectilinear pinhole camera model.
 *
 * [width]/[height] are the pixel dimensions of the image plane this
 * model describes (top-left pixel origin, x right, y down). [fx]/[fy]
 * in pixels, [cx]/[cy] the principal point in pixels.
 */
data class CameraIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
)

/**
 * Everything that is frozen when a capture starts. Pure data (no Android
 * imports) so the geometry math is unit-testable on the JVM.
 */
data class SessionGeometry(
    val orientation: CaptureOrientation,
    val displayRotationAtStart: Int, // android.view.Surface.ROTATION_*
    val previewWidth: Int, // GL surface size at START (display-oriented pixels)
    val previewHeight: Int,
    val encoderWidth: Int,
    val encoderHeight: Int,
    val encoderFps: Int,
    // Measured rotation (degrees, signed, snapped to 90) of the ARCore
    // texture relative to the raw camera image, derived from the fitted
    // affine — ARCore 1.54's Camera exposes no sensor-orientation getter,
    // and the affine is the measurement, not a guess.
    val textureRotationDeg: Int,
    val sourceTextureIntrinsics: CameraIntrinsics?, // ARCore texture model (what we draw)
    val sourceImageIntrinsics: CameraIntrinsics?, // raw ARCore camera image model
    val stabilization: String, // e.g. "EIS OFF (Config.ImageStabilizationMode.OFF)"
) {
    companion object {
        /** Display rotation 0/180 => portrait, 90/270 => landscape. */
        fun orientationForDisplayRotation(rotation: Int): CaptureOrientation =
            if (rotation % 180 == 0) CaptureOrientation.PORTRAIT else CaptureOrientation.LANDSCAPE

        /**
         * Encoder canvas = the display canvas in the frozen orientation,
         * rounded down to even pixel dimensions.
         *
         * Rationale (2026-09-02, P1.1): the ARCore texture->screen mapping
         * (transformCoordinates2d, display-geometry dependent) is applied
         * to the preview. A canvas with the *same aspect* makes that exact
         * mapping valid for the encoder too — no re-derived crop, no
         * assumption about ARCore's crop policy, and the encoded frame is
         * pixel-for-pixel what the user saw in the preview. A fixed
         * 1080x1920 canvas on a non-9:16 display would change the crop
         * and invalidate the mapping.
         */
        fun encoderDimensions(
            orientation: CaptureOrientation,
            displayW: Int,
            displayH: Int,
        ): Pair<Int, Int> {
            val (w, h) = if (orientation == CaptureOrientation.PORTRAIT)
                displayW to displayH
            else
                displayH to displayW
            return (w - (w % 2)).coerceAtLeast(16) to (h - (h % 2)).coerceAtLeast(16)
        }
    }
}

/**
 * Pure 2D geometry for the source->encoded image mapping.
 *
 * Convention: image pixels use top-left origin (x right, y down); NDC
 * uses the GL convention (x right, y up, origin center). All functions
 * are side-effect free and JVM-testable.
 */
object GeometryMath {

    /**
     * Fits the affine  p' = [a b c; d e f] * [x y 1]  from 4 point
     * correspondences. [src]/[dst] are each 8 floats (4 points, xy
     * pairs, any order). Returns [a, b, c, d, e, f] or null when
     * degenerate. Each output row is solved independently (3x3 normal
     * equations, exact for an affine).
     */
    fun fitAffine(src: FloatArray, dst: FloatArray): FloatArray? {
        if (src.size < 8 || dst.size < 8) return null
        val xs = DoubleArray(4) { src[2 * it].toDouble() }
        val ys = DoubleArray(4) { src[2 * it + 1].toDouble() }
        val tx = DoubleArray(4) { dst[2 * it].toDouble() }
        val ty = DoubleArray(4) { dst[2 * it + 1].toDouble() }
        val rx = fitLinearRow(xs, ys, tx) ?: return null
        val ry = fitLinearRow(xs, ys, ty) ?: return null
        return floatArrayOf(rx[0], rx[1], rx[2], ry[0], ry[1], ry[2])
    }

    /**
     * Least-squares fit of  t = m0*x + m1*y + m2  (4+ points, 3x3 normal
     * equations). Returns [m0, m1, m2] or null when the normal matrix is
     * singular (e.g. all points collinear).
     */
    fun fitLinearRow(x: DoubleArray, y: DoubleArray, t: DoubleArray): FloatArray? {
        val n = x.size
        if (n < 3) return null
        val sx = x.sum(); val sy = y.sum()
        val sx2 = x.sumOf { it * it }
        val sy2 = y.sumOf { it * it }
        val sxy = x.zip(y).sumOf { (a, b) -> a * b }
        val stx = x.zip(t).sumOf { (a, b) -> a * b }
        val sty = y.zip(t).sumOf { (a, b) -> a * b }
        val st = t.sum()
        // Normal equations for t = m0*x + m1*y + m2, every row scaled by
        // n (so the RHS n*<x|t> etc. stays consistent):
        //   n[Σx² Σxy Σx] [m0]   n[Σ x t]
        //   n[Σxy Σy² Σy] [m1] = n[Σ y t]
        //   n[Σx  Σy  n ] [m2]   n[Σ t  ]
        val A = arrayOf(
            floatArrayOf((n * sx2).toFloat(), (n * sxy).toFloat(), (n * sx).toFloat()),
            floatArrayOf((n * sxy).toFloat(), (n * sy2).toFloat(), (n * sy).toFloat()),
            floatArrayOf((n * sx).toFloat(), (n * sy).toFloat(), (n * n).toFloat()),
        )
        val b = floatArrayOf((n * stx).toFloat(), (n * sty).toFloat(), (n * st).toFloat())
        return solve3(A, b)?.let { floatArrayOf(it[0], it[1], it[2]) }
    }

    /** 3x3 Gaussian elimination with partial pivoting (m row-major). */
    fun solve3(m: Array<FloatArray>, b: FloatArray): FloatArray? {
        val a = Array(3) { r -> m[r].copyOf() }
        val y = b.copyOf()
        for (c in 0 until 3) {
            var p = c
            for (r in c + 1 until 3) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12f) return null
            if (p != c) {
                val tr = a[p]; a[p] = a[c]; a[c] = tr
                val ty = y[p]; y[p] = y[c]; y[c] = ty
            }
            for (r in c + 1 until 3) {
                val f = a[r][c] / a[c][c]
                for (k in c until 3) a[r][k] -= f * a[c][k]
                y[r] -= f * y[c]
            }
        }
        val x = FloatArray(3)
        for (r in 2 downTo 0) {
            var s = y[r]
            for (k in r + 1 until 3) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }

    /** Applies a 6-coefficient affine [a b c; d e f] to a point. */
    fun applyAffine(aff: FloatArray, x: Double, y: Double): DoubleArray {
        return doubleArrayOf(
            aff[0] * x + aff[1] * y + aff[2],
            aff[3] * x + aff[4] * y + aff[5],
        )
    }

    /**
     * Builds the affine **encoded-pixel -> ARCore-texture-pixel** mapping
     * from the frozen preview mapping. This is the canonical, exact
     * geometry of the encoded image (see docs/capture-format.md).
     *
     * @param quadNdc 8 floats: the 4 NDC corners in draw order
     *                (BL, BR, TL, TR — the quadNdc order in MainActivity).
     * @param quadTex 8 floats: the texture-UV images of those corners as
     *                produced by Frame.transformCoordinates2d
     *                (OPENGL_NDC -> TEXTURE_NORMALIZED; texture origin
     *                top-left).
     * @param previewW/H frozen GL surface size (display-oriented pixels).
     * @param encW/H encoder canvas size.
     * @param texW/H ARCore camera texture size (texture intrinsics).
     * @throws IllegalStateException if the composed mapping deviates from
     *         an affine by more than one pixel — in that case no affine
     *         model of the encoded image exists and we refuse rather
     *         than emit a wrong one.
     */
    fun encoderToSourceAffine(
        quadNdc: FloatArray,
        quadTex: FloatArray,
        previewW: Int,
        previewH: Int,
        encW: Int,
        encH: Int,
        texW: Int,
        texH: Int,
    ): FloatArray? {
        val mNdcUv = fitAffine(quadNdc, quadTex) ?: return null
        fun encToSrc(u: Double, v: Double): DoubleArray {
            val px = u * previewW.toDouble() / encW
            val py = v * previewH.toDouble() / encH
            val nx = 2.0 * px / previewW - 1.0
            val ny = 1.0 - 2.0 * py / previewH
            val m = applyAffine(mNdcUv, nx, ny)
            return doubleArrayOf(m[0] * texW, m[1] * texH)
        }
        val cxs = doubleArrayOf(0.0, encW.toDouble(), 0.0, encW.toDouble())
        val cys = doubleArrayOf(0.0, 0.0, encH.toDouble(), encH.toDouble())
        val encC = FloatArray(8)
        val srcC = FloatArray(8)
        for (i in 0 until 4) {
            encC[2 * i] = cxs[i].toFloat()
            encC[2 * i + 1] = cys[i].toFloat()
            val s = encToSrc(cxs[i], cys[i])
            srcC[2 * i] = s[0].toFloat()
            srcC[2 * i + 1] = s[1].toFloat()
        }
        val aff = fitAffine(encC, srcC) ?: return null
        // The mapping must be affine (sensor rotation + crop + scale all
        // are). Verify with the center point to catch a driver-specific
        // non-linear transform before any camera model is derived from
        // it; a deviation > 1 px means no honest affine exists.
        val cEnc = applyAffine(aff, encW / 2.0, encH / 2.0)
        val cSrc = encToSrc(encW / 2.0, encH / 2.0)
        val devPx = sqrt((cEnc[0] - cSrc[0]) * (cEnc[0] - cSrc[0]) + (cEnc[1] - cSrc[1]) * (cEnc[1] - cSrc[1]))
        if (devPx > 1.0) {
            throw IllegalStateException(
                String.format(
                    Locale.US,
                    "encoded->source mapping deviates from affine by %.2f px at center; refusing to emit a camera model",
                    devPx,
                )
            )
        }
        return aff
    }

    /**
     * Exact rectilinear model of the encoded image, **only when the
     * encoded->source mapping is a pure per-axis scale+flip**
     * (diagonal linear part). In that case the encoded camera is a
     * rectilinear pinhole with:
     *
     *   fx_e = fx_s / m00      fy_e = fy_s / m11
     *   cx_e = cx_s - tx/m00   cy_e = cy_s - ty/m11
     *
     * (derived by demanding the normalized ray coordinates be identical
     * for every pixel). When the mapping contains rotation or shear —
     * e.g. a 90-degree sensor rotation — NO rectilinear K represents it
     * exactly; the canonical model is the affine chain itself (encoded
     * pixel -> texture pixel -> K_src^-1 ray) and this returns null.
     * Never invent numbers: null is the honest answer.
     */
    /**
     * Rotation class of an affine mapping: the k in `M ≈ R(k) · S` with R a
     * 90° multiple rotation and S a diagonal scale — 0 (identity or
     * axis-flips), 90 (CCW), 180, 270 — or −1 when M has genuine shear /
     * non-orthogonal structure.
     */
    fun mappingRotationDeg(aff: FloatArray): Int {
        val m00 = aff[0]; val m01 = aff[1]; val m10 = aff[3]; val m11 = aff[4]
        val scale = maxOf(abs(m00), abs(m01), abs(m10), abs(m11), 1e-9f)
        val off = { v: Float -> abs(v) <= 1e-3f * scale }
        return when {
            off(m01) && off(m10) -> if (m00 < 0.0 && m11 < 0.0) 180 else 0
            off(m00) && off(m11) -> if (m01 < 0.0 && m10 > 0.0) 90 else 270
            else -> -1
        }
    }

    /**
     * A zero-skew (rectilinear) K for the encoded image exists for every
     * 0/90/180/270-degree axis permutation with independent scale and
     * translation (M = R(k)·S, S diagonal): those are ordinary pinhole
     * images in encoded pixel coordinates (for 90/270 the focal lengths
     * swap axes and the principal point is carried through the rotation).
     * Only genuine shear / non-orthogonal M returns null — then no
     * zero-skew K exists and the affine chain with source intrinsics is
     * the model. Never fabricate intrinsics.
     */
    fun tryExactRectilinear(src: CameraIntrinsics, aff: FloatArray): CameraIntrinsics? {
        val m00 = aff[0].toDouble(); val m01 = aff[1].toDouble()
        val m10 = aff[3].toDouble(); val m11 = aff[4].toDouble()
        val tx = aff[2].toDouble(); val ty = aff[5].toDouble()
        val scale = maxOf(abs(m00), abs(m01), abs(m10), abs(m11), 1e-9)
        val off = { v: Double -> abs(v) <= 1e-3 * scale }
        return when {
            // 0°/180°: p_src = (m00·u + tx, m11·v + ty)
            off(m01) && off(m10) && abs(m00) > 1e-9 && abs(m11) > 1e-9 ->
                CameraIntrinsics(
                    width = src.width, height = src.height,
                    fx = src.fx / abs(m00), fy = src.fy / abs(m11),
                    cx = (src.cx - tx) / m00, cy = (src.cy - ty) / m11,
                )
            // 90°/270°: p_src = (m01·v + tx, m10·u + ty) — axes swap
            off(m00) && off(m11) && abs(m01) > 1e-9 && abs(m10) > 1e-9 ->
                CameraIntrinsics(
                    width = src.width, height = src.height,
                    fx = src.fy / abs(m10), fy = src.fx / abs(m01),
                    cx = (src.cy - ty) / m10, cy = (src.cx - tx) / m01,
                )
            else -> null
        }
    }
}
