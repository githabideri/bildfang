package app.bildfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P1.1 geometry unit tests: orientation policy, encoder canvas sizing,
 * affine fitting and the exact-rectilinear rule.
 */
class SessionGeometryTest {

    @Test
    fun `display rotation maps to orientation`() {
        assertEquals(CaptureOrientation.PORTRAIT, SessionGeometry.orientationForDisplayRotation(0))
        assertEquals(CaptureOrientation.LANDSCAPE, SessionGeometry.orientationForDisplayRotation(90))
        assertEquals(CaptureOrientation.PORTRAIT, SessionGeometry.orientationForDisplayRotation(180))
        assertEquals(CaptureOrientation.LANDSCAPE, SessionGeometry.orientationForDisplayRotation(270))
    }

    @Test
    fun `encoder canvas is the display canvas in the frozen orientation, even-sized`() {
        // portrait: canvas keeps the display aspect
        assertEquals(1080 to 2400, SessionGeometry.encoderDimensions(CaptureOrientation.PORTRAIT, 1080, 2400))
        // landscape: swapped
        assertEquals(2400 to 1080, SessionGeometry.encoderDimensions(CaptureOrientation.LANDSCAPE, 1080, 2400))
        // odd dimensions round down to even
        assertEquals(960 to 2140, SessionGeometry.encoderDimensions(CaptureOrientation.PORTRAIT, 961, 2141))
        // aspect is preserved (same canvas family as the preview)
        val (w, h) = SessionGeometry.encoderDimensions(CaptureOrientation.PORTRAIT, 960, 2142)
        val previewAspect = 960.0 / 2142.0
        val encAspect = w.toDouble() / h
        assertTrue(abs(previewAspect - encAspect) < 1e-4)
    }

    @Test
    fun `fitAffine recovers a known scale + translation`() {
        val m = floatArrayOf(2f, 0f, 10f, 0f, 3f, 20f)
        val src = floatArrayOf(0f, 0f, 100f, 0f, 0f, 50f, 100f, 50f)
        val dst = FloatArray(src.size)
        for (i in 0 until 4) {
            dst[2 * i] = GeometryMath.applyAffine(m, src[2 * i].toDouble(), src[2 * i + 1].toDouble())[0].toFloat()
            dst[2 * i + 1] = GeometryMath.applyAffine(m, src[2 * i].toDouble(), src[2 * i + 1].toDouble())[1].toFloat()
        }
        val fit = GeometryMath.fitAffine(src, dst)!!
        for (i in 0 until 6) {
            assertEquals(m[i], fit[i], 1e-3f)
        }
    }

    @Test
    fun `fitAffine recovers a 90-degree rotation + scale`() {
        // y-down image rotation by 90° (clockwise on screen): (x,y) -> (W - y, x) ...
        // expressed as a pure linear part R + uniform scale s.
        val W = 100.0
        val src = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        val dst = FloatArray(src.size)
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble()
            val y = src[2 * i + 1].toDouble()
            // 90° clockwise in a y-down frame: x' = W - y, y' = x
            dst[2 * i] = (W - y).toFloat()
            dst[2 * i + 1] = x.toFloat()
        }
        val fit = GeometryMath.fitAffine(src, dst)!!
        // expected: [0, -1, 100, 1, 0, 0]
        val expected = floatArrayOf(0f, -1f, 100f, 1f, 0f, 0f)
        for (i in 0 until 6) {
            assertEquals(expected[i], fit[i], 1e-3f)
        }
    }

    @Test
    fun `encoderToSourceAffine is exact for an identity-style Y-flip mapping`() {
        // preview == encoder (640x480), texture 320x240, UV = Y-flip only:
        // u = (ndcX + 1)/2, v = (1 - ndcY)/2.
        val quadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f) // BL, BR, TL, TR
        val quadTex = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val aff = GeometryMath.encoderToSourceAffine(
            quadNdc, quadTex,
            previewW = 640, previewH = 480,
            encW = 640, encH = 480,
            texW = 320, texH = 240
        )!!
        // derived above: src px = (u/2, v/2)
        val expected = floatArrayOf(0.5f, 0f, 0f, 0f, 0.5f, 0f)
        for (i in 0 until 6) {
            assertEquals(expected[i], aff[i], 1e-4f)
        }
    }

    @Test
    fun `encoderToSourceAffine handles the 90-degree sensor case`() {
        // Portrait preview 480x640 from a 90°-rotated sensor: the texture
        // (640x480 landscape) is rotated so that display x maps to
        // texture y and display y maps to (W - texture x).
        //
        // NDC corner -> texture UV (this is what transformCoordinates2d
        // produces for a 90° clockwise sensor rotation on a portrait
        // display, with matching aspect so no crop):
        //   BL(-1,-1) -> (0, 0)
        //   BR( 1,-1) -> (0, 1)
        //   TL(-1, 1) -> (1, 0)
        //   TR( 1, 1) -> (1, 1)
        val quadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val quadTex = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
        val aff = GeometryMath.encoderToSourceAffine(
            quadNdc, quadTex,
            previewW = 480, previewH = 640,
            encW = 480, encH = 640,
            texW = 640, texH = 480
        )!!
        // Work out the expectation from the corner mapping itself:
        // u_uv = (ny+1)/2 and v_uv = (nx+1)/2 (both affine in NDC; check:
        // BL(-1,-1)->(0,0), BR(1,-1)->(0,1), TL(-1,1)->(1,0), TR(1,1)->(1,1)).
        // With nx = 2u/480 - 1, ny = 1 - 2v/640 (enc == preview 480x640):
        //   src.x = uv_x * 640 = (1 - v/640) * 640 = 640 - v
        //   src.y = uv_y * 480 = (u/480) * 480 = u
        val expected = floatArrayOf(0f, -1f, 640f, 1f, 0f, 0f)
        for (i in 0 until 6) {
            assertEquals(expected[i], aff[i], 1e-3f)
        }
        // A 90° mapping IS still a rectilinear pinhole image in encoded
        // pixels (axes swap): the derived K must exist with swapped
        // focal lengths and the principal point carried through.
        val k = CameraIntrinsics(640, 480, 100.0, 150.0, 320.0, 240.0)
        val ke = GeometryMath.tryExactRectilinear(k, aff)!!
        assertEquals(150.0, ke.fx, 1e-3)   // fy_s / |m10|
        assertEquals(100.0, ke.fy, 1e-3)   // fx_s / |m01|
        assertEquals(240.0, ke.cx, 1e-3)   // (cy_s - ty) / m10
        assertEquals(320.0, ke.cy, 1e-3)   // (cx_s - tx) / m01 = (320-640)/-1
        assertEquals(90, GeometryMath.mappingRotationDeg(aff))
    }

    @Test
    fun `tryExactRectilinear is exact for diagonal mappings`() {
        val src = CameraIntrinsics(320, 240, 100.0, 150.0, 160.0, 120.0)
        val aff = floatArrayOf(0.5f, 0f, 0f, 0f, 0.5f, 0f)
        val k = GeometryMath.tryExactRectilinear(src, aff)!!
        assertEquals(200.0, k.fx, 1e-9)
        assertEquals(300.0, k.fy, 1e-9)
        assertEquals(320.0, k.cx, 1e-9)   // (160 - 0) / 0.5
        assertEquals(240.0, k.cy, 1e-9)   // (120 - 0) / 0.5
        // with scale + translation: cx = (cx_s - tx)/m00, cy = (cy_s - ty)/m11
        val aff2 = floatArrayOf(2f, 0f, 10f, 0f, 3f, 20f)
        val k2 = GeometryMath.tryExactRectilinear(src, aff2)!!
        assertEquals(50.0, k2.fx, 1e-9)
        assertEquals(50.0, k2.fy, 1e-9)
        assertEquals(75.0, k2.cx, 1e-9)
        assertEquals(100.0 / 1.0 * 0.0 + (120.0 - 20.0) / 3.0, k2.cy, 1e-9)
    }

    @Test
    fun `tryExactRectilinear accepts all four orthogonal rotations`() {
        val src = CameraIntrinsics(320, 240, 100.0, 150.0, 160.0, 120.0)
        // 0° (scale + translation)
        val k0 = GeometryMath.tryExactRectilinear(src, floatArrayOf(0.5f, 0f, 10f, 0f, 0.5f, 5f))!!
        assertEquals(200.0, k0.fx, 1e-9); assertEquals(300.0, k0.fy, 1e-9)
        assertEquals(300.0, k0.cx, 1e-9); assertEquals(230.0, k0.cy, 1e-9)
        assertEquals(0, GeometryMath.mappingRotationDeg(floatArrayOf(0.5f, 0f, 10f, 0f, 0.5f, 5f)))
        // 90° CCW: M = [[0,-1],[1,0]] + t
        val aff90 = floatArrayOf(0f, -1f, 100f, 1f, 0f, 0f)
        val k90 = GeometryMath.tryExactRectilinear(src, aff90)!!
        assertEquals(150.0, k90.fx, 1e-3)   // fy_s / |m10|
        assertEquals(100.0, k90.fy, 1e-3)   // fx_s / |m01|
        assertEquals(120.0, k90.cx, 1e-3)   // (cy_s - ty) / m10
        assertEquals(-60.0, k90.cy, 1e-3)   // (cx_s - tx) / m01
        assertEquals(90, GeometryMath.mappingRotationDeg(aff90))
        // 180°: M = [[-1,0],[0,-1]]
        val aff180 = floatArrayOf(-1f, 0f, 320f, 0f, -1f, 240f)
        val k180 = GeometryMath.tryExactRectilinear(src, aff180)!!
        assertEquals(100.0, k180.fx, 1e-9)
        assertEquals(150.0, k180.fy, 1e-9)
        assertEquals(160.0, k180.cx, 1e-9)   // (160 - 320) / -1
        assertEquals(120.0, k180.cy, 1e-9)   // (120 - 240) / -1
        assertEquals(180, GeometryMath.mappingRotationDeg(aff180))
        // 270°: M = [[0,1],[-1,0]]
        val aff270 = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f)
        val k270 = GeometryMath.tryExactRectilinear(src, aff270)!!
        assertEquals(150.0, k270.fx, 1e-9)   // fy_s / |m10|
        assertEquals(100.0, k270.fy, 1e-9)   // fx_s / |m01|
        assertEquals(-120.0, k270.cx, 1e-9)  // (cy_s - ty) / m10 = 120 / -1
        assertEquals(160.0, k270.cy, 1e-9)   // (cx_s - tx) / m01 = 160 / 1
        assertEquals(270, GeometryMath.mappingRotationDeg(aff270))
    }

    @Test
    fun `projection round-trip, encoded K plus encoded-camera rotation matches source K plus affine, all four rotations`() {
        val src = CameraIntrinsics(1920, 1080, 1390.8, 1392.5, 967.1, 539.0)
        // known 3D points in the ARCore camera frame (x right, y down, z forward)
        val pts = arrayOf(
            doubleArrayOf(0.1, 0.05, 2.0),
            doubleArrayOf(-0.3, 0.2, 1.5),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(0.5, -0.4, 3.0),
        )
        for (rot in listOf(0, 90, 180, 270)) {
            val s = 1.19
            val a = 1.23  // anisotropic scale, distinct from s
            // linear part M (encoder px -> source px) for each rotation
            val m00 = when (rot) { 0 -> a; 180 -> -a; else -> 0.0 }
            val m01 = when (rot) { 90 -> -s; 270 -> s; else -> 0.0 }
            val m10 = when (rot) { 90 -> s; 270 -> -s; else -> 0.0 }
            val m11 = when (rot) { 0 -> a; 180 -> -a; else -> 0.0 }
            val t = doubleArrayOf(121.4, 37.2)
            val aff = floatArrayOf(m00.toFloat(), m01.toFloat(), t[0].toFloat(), m10.toFloat(), m11.toFloat(), t[1].toFloat())

            assertEquals("rotation classification (rot=$rot)", rot, GeometryMath.mappingRotationDeg(aff))
            val k = GeometryMath.tryExactRectilinear(src, aff) ?: error("no rectilinear K for rot=$rot")

            val R = GeometryMath.sourceFromEncodedRotation(rot)
            for (p in pts) {
                // Path A (canonical): ARCore cam point -> source K -> source px -> affine^-1 -> encoded px
                val xS = src.fx * p[0] / p[2] + src.cx
                val yS = src.fy * p[1] / p[2] + src.cy
                // invert 2x2 affine: p_enc = M^-1 (p_src - t)
                val det = m00 * m11 - m01 * m10
                val dx = xS - t[0]; val dy = yS - t[1]
                val uA = (m11 * dx - m01 * dy) / det
                val vA = (m00 * dy - m10 * dx) / det

                // Path B: rotate the point into the encoded camera frame
                // (p_enc_cam = R^T p_arcore), then plain pinhole with encoded K.
                val xe = R[0] * p[0] + R[3] * p[1] + R[6] * p[2]
                val ye = R[1] * p[0] + R[4] * p[1] + R[7] * p[2]
                val ze = R[2] * p[0] + R[5] * p[1] + R[8] * p[2]
                val uB = k.fx * xe / ze + k.cx
                val vB = k.fy * ye / ze + k.cy

                val du = (uA - uB).let { if (it > 0) it else -it }
                val dv = (vA - vB).let { if (it > 0) it else -it }
                assertTrue(
                    "round-trip mismatch rot=$rot pt=${p.contentToString()} du=$du dv=$dv",
                    du < 1e-3 && dv < 1e-3  // sub-pixel; residual is float32 precision of the fitted affine
                )
            }
        }
    }

    @Test
    fun `tryExactRectilinear refuses genuine shear`() {
        val src = CameraIntrinsics(320, 240, 100.0, 150.0, 160.0, 120.0)
        assertNull(GeometryMath.tryExactRectilinear(src, floatArrayOf(0.5f, 0.1f, 0f, 0f, 0.5f, 0f)))
        assertNull(GeometryMath.tryExactRectilinear(src, floatArrayOf(0.5f, 0f, 0f, 0.2f, 0.5f, 0f)))
        assertEquals(-1, GeometryMath.mappingRotationDeg(floatArrayOf(0.5f, 0.1f, 0f, 0f, 0.5f, 0f)))
    }
}
