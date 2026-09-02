package app.bildfang

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.Display
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.opengl.EGL14
import androidx.documentfile.provider.DocumentFile
import javax.microedition.khronos.egl.EGL10 as JEGL10
import javax.microedition.khronos.egl.EGLConfig as JConfig
import javax.microedition.khronos.egl.EGLContext as JContext
import javax.microedition.khronos.egl.EGLDisplay as JDisplay
import javax.microedition.khronos.egl.EGLSurface as JSurface
import android.opengl.GLSurfaceView
import android.util.Size
import android.widget.Button
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import com.google.ar.core.Session
import android.opengl.GLES20
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Phase-2 UI + capture loop.
 *
 *   [live preview] → ARCore camera texture rendered in GL (GL_TEXTURE_EXTERNAL_OES)
 *   [timer]        → recording duration
 *   [metrics]      → tracking state / fps / frame count / segment / pose / draw rate
 *   [ START ] →  [ STOP ]
 *
 * Architecture (ARCore 1.54, pull-style, GL-context bound):
 *
 *  - One Session for the lifetime of the activity, created in onCreate and
 *    resumed in onResume. The session runs continuously so the preview is
 *    live and tracking is warm before the user presses START.
 *
 *  - The ARCore update loop lives in the GLSurfaceView renderer thread
 *    (Session.update() throws MissingGlContextException off a GL thread —
 *    verified on-device 2026-09-01). Update mode is LATEST_CAMERA_IMAGE:
 *    non-blocking, the loop runs at display refresh and each draw binds the
 *    ARCore camera texture (frame.getCameraTextureName()) into a
 *    full-screen passthrough quad. No CPU image conversion anywhere —
 *    the software YUV preview of Phase 1 was throttling the pipeline to
 *    ~3 Hz (acquireCameraImage/close round-trips serialized with the
 *    camera producer).
*
*  - START: MediaCodecRecorder (H.264 hardware encoder, surface input) +
*    MediaMuxer — bildfang's own encoder, its own presentation timestamps,
*    its own muxing (P2a; ARCore's native recorder is dead on GrapheneOS,
*    see docs/ROADMAP.md). The same camera frame is drawn into the
*    encoder's input surface in the same GL loop as the preview, with a
*    session-relative presentation time (android_camera timestamp minus
*    the first encoded frame's; persisted as video_timebase). STOP:
*    encoder flush, atomic finalization, then poses.json + video/frames.json
*    + session.json.
*
*  - Geospatial mode is DISABLED explicitly: bildfang is a local-only
*    logger, it neither needs nor stores location data.
 */
class MainActivity : Activity() {

    private var session: Session? = null
    @Volatile private var sessionActive = false
    @Volatile private var recording = false
    @Volatile private var stopRequested = false
    @Volatile private var pendingTextureRebind = false // consumed on the GL thread
    private var sessionStartMonoNs = 0L

    // Recording state (MediaCodec path — P2a; ARCore's native recorder is
    // dead on GrapheneOS, see docs/ROADMAP.md P1/P2b)
    private var recorder: MediaCodecRecorder? = null
    @Volatile private var eglRef: JEGL10? = null // captured from GLSurfaceView factories
    private var encoderEglPair: Pair<JDisplay, JSurface>? = null // GL thread only
    private var lastEncodeCamNs = 0L
    private var sessionDir: File? = null
    private var videoFile: File? = null
    private var camImageSize: Size? = null
    private var camFpsRange = ""

    // ---- P1.1 (2026-09-02): geometry frozen at START (orientation policy) ----
    private var sessionGeom: SessionGeometry? = null
    // The GL surface dimensions at the moment the geometry is frozen.
    private var previewSurfW = 0
    private var previewSurfH = 0
    private var encW = 0 // encoder canvas = display canvas in the frozen orientation
    private var encH = 0
    @Volatile private var geomFrozen = false
    @Volatile private var uvFreezePending = false // set at START, consumed on the first recording frame
    @Volatile private var uvFrozen = false
    private var rotationEventsDuringRec = 0
    // Snapshot of the preview quad when the geometry froze — the source data
    // for the encoded->source affine persisted in session.json.
    private var frozenQuadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private var frozenQuadTex = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private var encAffine: FloatArray? = null // encoded-px -> ARCore-texture-px
    private var encRectilinear: CameraIntrinsics? = null
    private var encModelRefused = false
    private var lastSensorOrientation = 0

    // P1.1 headless acceptance test: `am start -n app.bildfang/.MainActivity
    // --ei bf_autotest <seconds>` starts a capture once ARCore is tracking
    // (polling from onResume, ~750 ms cadence) and stops it after <seconds>.
    // One-shot per process; used for unattended geometry-regression runs.
    @Volatile private var autotestPending = false
    @Volatile private var autotestDone = false
    private var needTracking = false
    @Volatile private var lastAutotestLogTick = 0L
    @Volatile private var trackingState = "UNKNOWN" // GL thread -> main thread

    // ---- P1.1 step 5: per-frame camera metadata ----
    private val camMetaRecords = ArrayList<CameraMetaRecord>()
    private var camMetaProbeDone = false
    private var stabilizationConfig = "unknown"

    // ---- P1.1 steps 6-7: storage (SAF) + session browser ----
    private var storageRootUri: Uri? = null
    private var storageRootName = "app-external"
    private var lastBitrate = 0

    private val poses = ArrayList<PoseRecord>()
    private val posesLock = Any()
    private var segment = 0
    private var lastPose: PoseRecord? = null
    private val discontinuities = ArrayList<DiscontinuityEvent>()
    private var fps = 0f

    private var lastMetricsNs = 0L
    private val METRICS_INTERVAL_NS = 500_000_000L // 2 Hz
    private var consecutiveUpdateFailures = 0
    private var lastResumeRetryNs = 0L
    private var drawFrameCount = 0
    private var distinctFrameCount = 0
    private var lastDistinctTsNs = 0L

    // 1.54's Frame.getTimestamp() is NOT on the elapsedRealtimeNanos clock
    // (observed ~1.78e18 ns — a fixed epoch, likely unix ns). All bildfang
    // frame clocks are therefore session-relative, anchored at the first
    // frame; session.json stores the anchor for absolute reconstruction.
    private var frameTsAnchor = 0L // raw frame timestamp of the first frame
    private var anchorMonoNs = 0L  // SystemClock.elapsedRealtimeNanos at anchor
    private var anchorUnixMs = 0L  // System.currentTimeMillis at anchor

    private lateinit var glView: GLSurfaceView
    private lateinit var timerView: TextView
    private lateinit var metricsView: TextView
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var sessionsBtn: Button
    private lateinit var storageBtn: Button

    private val random = SecureRandom()

    // ---- GL preview (created on the GL thread) ----------------------------

    private val program = IntArray(1)
    private var aPosUv = 0
    private var uTex = 0
    private var uDebugRed = 0
    // debug: force solid red to test GL compositing vs texture sampling
    private var debugRed = false
    private val quadBufferId = IntArray(1) // glGenBuffers in onSurfaceCreated
    private var oesTextureId = 0
    private var lastLoggedCtn = -1
    // Canonical ARCore/BackgroundRenderer quad order for GL_TRIANGLE_STRIP:
    // BL, BR, TL, TR (NDC). Texture UVs start as a plain Y-flip and are
    // replaced by transformCoordinates2d once display geometry is valid.
    private val quadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private var texDirty = true

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            program[0] = buildProgram(
                // GLSL ES 3.00. The context on current devices is ES3.x even
                // when client version 2 is requested; in that mode the GLSL-100
                // `#extension GL_OES_EGL_image_external` path compiled but
                // sampled black on-device (Pixel 7 / Mali r54, 2026-09-01).
                // ES 3.00 needs the _essl3 variant of the external-texture
                // extension (samplerExternalOES is only core from ES 4.00 on);
                // without the directive the shader fails to compile on Mali.
                "#version 300 es\n" +
                    "layout(location = 0) in vec4 aPosUv;\n" +
                    "out vec2 vUv;\n" +
                    "void main() { vUv = aPosUv.zw; gl_Position = vec4(aPosUv.xy, 0.0, 1.0); }",
                "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision mediump float;\n" +
                    "in vec2 vUv;\nout vec4 fragColor;\n" +
                    "uniform highp samplerExternalOES uTex;\n" +
                    "uniform float uDebugRed;\n" +
                    "void main() { if (uDebugRed > 0.5) { fragColor = vec4(1.0, 0.0, 0.0, 1.0); } else { fragColor = texture(uTex, vUv); } }"
            )
            aPosUv = GLES20.glGetAttribLocation(program[0], "aPosUv")
            uTex = GLES20.glGetUniformLocation(program[0], "uTex")
            uDebugRed = GLES20.glGetUniformLocation(program[0], "uDebugRed")

            // 4 corners: xy = clip space, zw = texture uv (Y flipped: camera
            // image is top-down, clip space origin is bottom-left).
            //
            // CRITICAL (root cause of the invisible-quad bug, confirmed
            // 2026-09-01): the driver on ARM reads buffer bytes in native
            // (little) endian, but ByteBuffer.allocate()/wrap() are
            // BIG_ENDIAN by default — the JNI copy passes raw bytes, so the
            // vertex floats came out byte-swapped (1.0f → ~4e-41), collapsing
            // the whole quad to a zero-area point near the origin. glClear was
            // still visible, glGetError stayed 0. Must use
            // allocateDirect + ByteOrder.nativeOrder (as Google's
            // BackgroundRenderer does).
            val interleaved = FloatArray(16)
            for (i in 0 until 4) {
                interleaved[i * 4] = quadNdc[i * 2]
                interleaved[i * 4 + 1] = quadNdc[i * 2 + 1]
                interleaved[i * 4 + 2] = quadTex[i * 2]
                interleaved[i * 4 + 3] = quadTex[i * 2 + 1]
            }
            uploadQuad(interleaved)

            // The camera texture ARCore writes into. 1.54 expects an
            // external OES texture registered via setCameraTextureName.
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            oesTextureId = t[0]
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
            android.util.Log.i("bildfang", "GL ready: program=${program[0]} oes=$oesTextureId ctx=${GLES20.glGetString(GLES20.GL_VERSION)}")

            // The session is created in onCreate, before this surface exists:
            // register the texture now that we have one (requires a GL
            // context — hence the GL thread). Without it update() fails.
            try {
                session?.setCameraTextureName(oesTextureId)
            } catch (e: Exception) {
                android.util.Log.w("bildfang", "setCameraTextureName: $e")
            }

            GLES20.glClearColor(0.06f, 0.08f, 0.10f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            // P1.1: the viewport is explicit per-surface state. The preview
            // viewport is set here (and restored after every encoder frame);
            // the encoder sets its own in encodeFrame(). Neither is ever
            // carried over from the other surface implicitly.
            GLES20.glViewport(0, 0, width, height)
            if (previewSurfW != 0 && (width > height) != (previewSurfW > previewSurfH)) {
                if (geomFrozen) {
                    // The device was physically rotated while a recording is
                    // in flight. The capture coordinate system stays frozen
                    // at START (orientation policy); the event is only
                    // logged (rotation_events_during_recording). No geometry,
                    // UV or encoder state is changed here.
                    rotationEventsDuringRec++
                    android.util.Log.w("bildfang", String.format(
                        Locale.US,
                        "device rotated mid-recording (event #%d): surface now %dx%d; geometry stays frozen at %dx%d (%s)",
                        rotationEventsDuringRec, width, height,
                        previewSurfW, previewSurfH, sessionGeom?.orientation?.label ?: "?"))
                }
            }
            previewSurfW = width
            previewSurfH = height
            if (!geomFrozen) {
                // Required before frames flow: ARCore warns "Display
                // geometry has an invalid width: 0" and the frame manager
                // withholds frames until the viewport is known (verified
                // on-device 2026-09-01). NOTE: the argument order is
                // (rotation, width, height) — rotation is a
                // Surface.ROTATION_x constant of the *display*, not a
                // pixel dimension. While the geometry is frozen
                // (recording), this is NOT called: ARCore display
                // geometry is part of the frozen session geometry.
                try {
                    val dm = glView.context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    val rotation = dm.getDisplay(Display.DEFAULT_DISPLAY).rotation
                    session?.setDisplayGeometry(rotation, width, height)
                } catch (e: Exception) {
                    android.util.Log.w("bildfang", "setDisplayGeometry: $e")
                }
            }
        }
        override fun onDrawFrame(gl: GL10?) {
            drawFrameCount++
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val s = session ?: return

            if (!sessionActive) {
                // The camera-permission dialog pauses the activity, so a
                // resume() from onResume() can race ahead of the grant and
                // throw; retry here (throttled) until the session is live
                // (verified on-device 2026-08-31).
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastResumeRetryNs >= 500_000_000L) {
                    lastResumeRetryNs = now
                    try {
                        s.resume()
                        sessionActive = true
                        // Re-register: the texture may have been registered
                        // while the session was not yet active (onSurfaceCreated
                        // races the first resume), and an inactive session may
                        // not have bound it.
                        if (oesTextureId != 0) s.setCameraTextureName(oesTextureId)
                    } catch (e: Exception) {
                        if (consecutiveUpdateFailures == 0) {
                            android.util.Log.w("bildfang", "resume() failing", e)
                            postToUi { statusView.text = "Waiting for camera…" }
                        }
                    }
                }
            if (pendingTextureRebind && oesTextureId != 0) {
                pendingTextureRebind = false
                s.setCameraTextureName(oesTextureId)
            }
            return
        }

        if (pendingTextureRebind) {
            pendingTextureRebind = false
            if (oesTextureId != 0) s.setCameraTextureName(oesTextureId)
        }

        if (stopRequested) {
                // Stop on the GL thread: no more frames after this one, and
                // the encoder flush/finalize happens before the export is
                // posted to the UI. (ARCore's native recorder is not used.
                // )
                stopRequested = false
                val rec = recorder
                val pair = encoderEglPair
                val j = eglRef
                if (pair != null && j != null) {
                    // P1.1: make the preview surface explicitly current
                    // (the encodeFrame guard already restores it, but do
                    // not rely on that before destroying the encoder
                    // surface), restore the preview viewport, then destroy.
                    runCatching {
                        val prev = j.eglGetCurrentSurface(JEGL10.EGL_DRAW)
                        if (prev != pair.second) {
                            j.eglMakeCurrent(pair.first, prev, prev, j.eglGetCurrentContext())
                        }
                        GLES20.glViewport(0, 0, previewSurfW, previewSurfH)
                        j.eglDestroySurface(pair.first, pair.second)
                    }
                }
                encoderEglPair = null
                // Unfreeze only when the recording is actually over: a new
                // recording re-freezes its own geometry at its own START.
                geomFrozen = false
                uvFrozen = false
                uvFreezePending = false
                try {
                    rec?.stop()
                    recording = false
                    postToUi { finalizeExport() }
                } catch (e: Exception) {
                    recording = false
                    postToUi { statusView.text = "Stop failed: ${e.message}" }
                }
                return
            }

            try {
                val f = s.update()
                consecutiveUpdateFailures = 0
                onFrame(f)
                drawPreview(f)
                if (recording) {
                    encodeFrame(f)
                }
            } catch (e: Exception) {
                consecutiveUpdateFailures++
                if (consecutiveUpdateFailures == 1) {
                    android.util.Log.w("bildfang", "update() failing", e)
                }
                if (consecutiveUpdateFailures == 100) {
                    postToUi {
                        statusView.text = "ARCore update failing: ${e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    /**
     * Passthrough of the ARCore camera texture into the GL view.
     *
     * UV mapping: while display geometry is (in)valid, ARCore tells us how
     * to map the screen quad into texture space via transformCoordinates2d
     * (sensor orientation, display rotation, aspect, center crop) — the
     * same approach as the official ARCore BackgroundRenderer. Until the
     * first valid transform, a plain Y-flipped UV is used.
     */
    private fun drawPreview(frame: Frame) {
        if (program[0] == 0) return
        // 1.54 may return 0 (ARCore does not own the texture — the app's
        // setCameraTextureName target is the one being filled).
        val ctn = frame.cameraTextureName
        if (ctn != 0 && ctn != lastLoggedCtn) {
            lastLoggedCtn = ctn
            android.util.Log.w("bildfang", "frame.cameraTextureName=$ctn (oes=$oesTextureId)")
        }
        val tex = ctn.takeIf { it != 0 } ?: oesTextureId
        if (tex == 0) {
            if (drawFrameCount % 60 == 0) {
                android.util.Log.w("bildfang", "no texture: frameTex=${frame.cameraTextureName} oes=$oesTextureId")
            }
            return
        }
        // P1.1: while the session geometry is frozen (recording in flight)
        // the UV mapping is part of the frozen geometry and is never
        // updated; before recording it tracks the (mutable) display
        // geometry as before.
        if (!uvFrozen && (texDirty || frame.hasDisplayGeometryChanged())) {
            if (tryUpdateQuadUvs(frame)) texDirty = false
        }
        drawQuad(tex)
    }

    /** Draws the (already UV-mapped) camera quad with the current program.
     *  Used for the preview surface and — after an eglMakeCurrent switch —
     *  for the encoder input surface. GL thread. */
    private fun drawQuad(tex: Int) {
        GLES20.glUseProgram(program[0])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uDebugRed, if (debugRed) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBufferId[0])
        GLES20.glEnableVertexAttribArray(aPosUv)
        GLES20.glVertexAttribPointer(aPosUv, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosUv)
    }

    /**
     * Draws the camera frame into the encoder's EGL window surface and
     * swaps it, submitting the frame's timestamps to the recorder.
     *
     * Driven through the captured javax EGL10 instance (the binding
     * family the GLSurfaceView preview uses; the android EGL14 binding
     * returns zero configs on the Exynos driver). No javax binding
     * exists for eglPresentationTimeANDROID, so the MP4 container PTS is
     * left to the driver default; frames.json (camera clock + session
     * origin) remains the authoritative timestamp mapping.
     *
     * P1.1 geometry discipline (fixes the 2026-09-01 washroom-capture
     * bug): the encoder draw runs with an EXPLICIT glViewport(0, 0,
     * encW, encH) — the viewport is per-surface state and was
     * previously never set for the encoder, so the preview's viewport
     * was used on a differently-sized encoder framebuffer (camera
     * content on the left, blank on the right). A finally-block then
     * restores the preview surface AND the preview viewport on every
     * path, and the restore is verified by reading the viewport back —
     * a mismatch is a logged error, never a silent leak.
     */
    private fun encodeFrame(f: Frame) {
        val rec = recorder ?: return
        val j = eglRef ?: return
        // The encoder input buffer queue holds only a few buffers and the
        // encoder consumes at its own frame rate: submitting at the camera
        // rate (60 fps) overflows it within ~3 s and every later swap fails
        // with EGL_BAD_SURFACE. Throttle submissions to the encoder rate;
        // skipped frames are counted separately (frames_rate_skipped),
        // never silent.
        val camTs = f.androidCameraTimestamp
        if (lastEncodeCamNs != 0L) {
            val intervalNs = 1_000_000_000L / rec.fps
            if (camTs - lastEncodeCamNs < intervalNs - 2_000_000L) {
                rec.skipFrameForRate()
                return
            }
        }
        var pair = encoderEglPair
        if (pair == null) {
            // Creation can be expensive on a picky driver; only retry every
            // 30 frames so a persistent failure doesn't flood the log.
            if (drawFrameCount % 30 != 0) {
                rec.dropFrame()
                return
            }
            pair = createEncoderEglSurfaceJ() ?: run {
                rec.dropFrame()
                return
            }
            encoderEglPair = pair
        }
        val ctx = j.eglGetCurrentContext()
        val prev = j.eglGetCurrentSurface(JEGL10.EGL_DRAW)
        if (!j.eglMakeCurrent(pair.first, pair.second, pair.second, ctx)) {
            android.util.Log.e("bildfang", "javax eglMakeCurrent(encoder) failed")
            rec.dropFrame()
            return
        }
        // The viewport belongs to the surface: the encoder canvas is NOT
        // the preview size (different orientation/aspect), so it must be
        // set explicitly before drawing into it. This was missing before
        // P1.1 — the preview's viewport was applied to the encoder
        // framebuffer, corrupting every encoded frame.
        GLES20.glViewport(0, 0, encW, encH)
        var swapOk = false
        try {
            val tex = f.cameraTextureName.takeIf { it != 0 } ?: oesTextureId
            if (tex != 0) {
                drawQuad(tex)
                rec.ensureOrigin(camTs)
                swapOk = j.eglSwapBuffers(pair.first, pair.second)
            } else {
                android.util.Log.e("bildfang", "no camera texture for encoder draw")
            }
        } finally {
            // Restore the preview surface AND its viewport on EVERY path.
            j.eglMakeCurrent(pair.first, prev, prev, ctx)
            GLES20.glViewport(0, 0, previewSurfW, previewSurfH)
            // Restore invariant: verify, don't assume. If anything ever
            // leaves the preview viewport wrong, it is a logged error
            // here, not a silently corrupted capture later.
            val vp = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0)
            if (vp[0] != 0 || vp[1] != 0 || vp[2] != previewSurfW || vp[3] != previewSurfH) {
                android.util.Log.e("bildfang", String.format(Locale.US,
                    "VIEWPORT RESTORE MISMATCH after encoder frame: got [%d %d %d %d], expected [0 0 %d %d]",
                    vp[0], vp[1], vp[2], vp[3], previewSurfW, previewSurfH))
            }
        }
        if (!swapOk) {
            android.util.Log.e("bildfang", "javax eglSwapBuffers(encoder) failed")
            rec.dropFrame()
            return
        }
        lastEncodeCamNs = camTs
        rec.submitFrame(camTs, f.timestamp)
        val pi = synchronized(posesLock) { poses.lastIndex }
        rec.attachPoseIndex(rec.lastFrameIndex, pi)
    }

    /** EGL window surface over the encoder's input Surface. GL thread. */
    /**
     * Creates the encoder's EGL window surface through the *captured*
     * javax EGL10 instance (the same one that drives our GLSurfaceView
     * preview, which the Exynos driver handles; the android EGL14
     * binding returns zero configs on this device). May be called from
     * the main thread at recording start or lazily from the GL thread.
     * Returns (display, surface).
     */
    private fun createEncoderEglSurfaceJ(): Pair<JDisplay, JSurface>? {
        val rec = recorder ?: return null
        val j = eglRef ?: run {
            android.util.Log.e("bildfang", "no captured EGL10 instance yet")
            return null
        }
        return try {
            val disp = j.eglGetDisplay(JEGL10.EGL_DEFAULT_DISPLAY)
            j.eglInitialize(disp, null)
            val attribs = intArrayOf(
                JEGL10.EGL_RENDERABLE_TYPE, 0x4, // EGL_OPENGL_ES2_BIT (not in the javax stub)
                JEGL10.EGL_SURFACE_TYPE, JEGL10.EGL_WINDOW_BIT,
                JEGL10.EGL_RED_SIZE, 8,
                JEGL10.EGL_GREEN_SIZE, 8,
                JEGL10.EGL_BLUE_SIZE, 8,
                JEGL10.EGL_ALPHA_SIZE, 8,
                JEGL10.EGL_NONE)
            val cfgs = arrayOfNulls<JConfig>(1)
            val num = IntArray(1)
            j.eglChooseConfig(disp, attribs, cfgs, 1, num)
            if (num[0] == 0 || cfgs[0] == null) {
                android.util.Log.e("bildfang", "javax eglChooseConfig: 0 configs (err=${j.eglGetError()})")
                return null
            }
            android.util.Log.i("bildfang", "javax eglChooseConfig: ${num[0]} match(es)")
            val surf = j.eglCreateWindowSurface(disp, cfgs[0], rec.encoderSurface, null)
            if (surf == null || surf == JEGL10.EGL_NO_SURFACE) {
                android.util.Log.e("bildfang", "javax eglCreateWindowSurface(encoder) failed")
                return null
            }
            android.util.Log.i("bildfang", "javax encoder EGL surface created")
            Pair(disp, surf)
        } catch (e: Exception) {
            android.util.Log.e("bildfang", "javax encoder EGL: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /**
     * Ask ARCore to remap the screen quad into texture space (sensor
     * orientation, display rotation, aspect ratio, center crop). Returns
     * true on success; the quad keeps its previous UVs on failure.
     * GL thread.
     */
    private fun tryUpdateQuadUvs(frame: Frame): Boolean {
        try {
            frame.transformCoordinates2d(
                com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadNdc,
                com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
                quadTex
            )
            val interleaved = FloatArray(16)
            for (i in 0 until 4) {
                interleaved[i * 4] = quadNdc[i * 2]
                interleaved[i * 4 + 1] = quadNdc[i * 2 + 1]
                interleaved[i * 4 + 2] = quadTex[i * 2]
                interleaved[i * 4 + 3] = quadTex[i * 2 + 1]
            }
            uploadQuad(interleaved)
            return true
        } catch (e: Exception) {
            android.util.Log.w("bildfang", "transformCoordinates2d: ${e.message}")
            return false
        }
    }

    /** Rebuilds the interleaved quad VBO with native byte order. GL thread. */
    private fun uploadQuad(quad: FloatArray) {
        if (quadBufferId[0] == 0) GLES20.glGenBuffers(1, quadBufferId, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBufferId[0])
        val bytes = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
        bytes.asFloatBuffer().put(quad)
        bytes.position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, bytes, GLES20.GL_STATIC_DRAW)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(sh)
                GLES20.glDeleteShader(sh)
                throw RuntimeException("shader compile (type=$type) infoLog='$log' SRC: $src")
            }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(p)
            throw RuntimeException("program link: " + GLES20.glGetProgramInfoLog(p))
        }
        return p
    }

    private fun postToUi(block: () -> Unit) {
        runOnUiThread { if (!isFinishing) block() }
    }

    // ---- Session lifecycle ------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.gl)
        timerView = findViewById(R.id.timerView)
        metricsView = findViewById(R.id.metricsView)
        statusView = findViewById(R.id.statusView)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        sessionsBtn = findViewById(R.id.sessionsBtn)
        storageBtn = findViewById(R.id.storageBtn)

        glView.setEGLContextClientVersion(2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        // Capture the javax EGL10 implementation instance GLSurfaceView
        // uses (com.google.android.gles_jni.EGLImpl on this device): the
        // android EGL14 binding is broken on this driver (chooseConfig
        // returns zero configs), but this instance - the one that creates
        // our preview context and surface - works. We only override the
        // *window surface* factory (stock behavior, plus the capture);
        // the stock context factory stays in place because the Exynos
        // driver rejects custom-context-factory eglCreateContext calls.
        glView.setEGLWindowSurfaceFactory(object : GLSurfaceView.EGLWindowSurfaceFactory {
            override fun createWindowSurface(egl: JEGL10, display: JDisplay, config: JConfig, window: Any): JSurface? {
                eglRef = egl
                return egl.eglCreateWindowSurface(display, config, window, null)
            }
            override fun destroySurface(egl: JEGL10, display: JDisplay, surface: JSurface) {
                egl.eglDestroySurface(display, surface)
            }
        })
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // GLSurfaceView is a SurfaceView: it renders *behind* the window.
        // Our root layout has an opaque background, which would hide the
        // surface entirely (verified on-device 2026-09-01: a hard-coded
        // red shader produced a black screen). Classic fix: transparent
        // window + surface on top; the layout below keeps its own opaque
        // background for the non-GL region.
        glView.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        glView.setZOrderOnTop(true)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        startBtn.setOnClickListener { startRecording() }
        stopBtn.setOnClickListener { stopRecording() }
        sessionsBtn.setOnClickListener { showSessionBrowser() }
        storageBtn.setOnClickListener { pickStorageRoot() }
        applyStoredSafRoot()

        val avail = ArCoreApk.getInstance().checkAvailability(this)
        statusView.text = when (avail) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                getString(R.string.support_ok)
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
                getString(R.string.support_install)
            else ->
                // Include the raw enum: distinguishes
                // UNSUPPORTED_DEVICE_NOT_CAPABLE from UNKNOWN_TIMED_OUT /
                // UNKNOWN_ERROR, which read the same in the UI.
                "${getString(R.string.support_unsupported)} (check: $avail)"
        }

        if (avail == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                return // the grant callback creates the session
            }
            createSession()
        }
    }

    private fun createSession() {
        if (session != null) return
        try {
            val s = Session(this)
            val cfg = s.config
                .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
                .setTextureUpdateMode(Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES)
            if (s.isGeospatialModeSupported(Config.GeospatialMode.DISABLED)) {
                cfg.setGeospatialMode(Config.GeospatialMode.DISABLED)
            }
            // P1.1: EIS explicitly OFF (the default is device-dependent and
            // an unknown stabilization changes image geometry — it must be
            // known and logged per session). A/B EIS later, as a controlled
            // experiment, never by accident.
            if (s.isImageStabilizationModeSupported(Config.ImageStabilizationMode.OFF)) {
                cfg.setImageStabilizationMode(Config.ImageStabilizationMode.OFF)
                stabilizationConfig = "EIS OFF (explicitly set, Config.ImageStabilizationMode.OFF)"
            } else {
                stabilizationConfig = "EIS OFF not controllable on this device (unsupported); state not forced"
            }
            android.util.Log.i("bildfang", "stabilization: $stabilizationConfig")
            s.configure(cfg)

            // Highest-resolution CPU image: it becomes the recorded video
            // track (ARCore records the CPU image, never the GPU texture —
            // see the recording-and-playback docs).
            val best = s.supportedCameraConfigs.maxByOrNull {
                it.imageSize.width * it.imageSize.height
            }
            if (best != null) {
                s.setCameraConfig(best)
                camImageSize = best.imageSize
                val r = best.fpsRange
                camFpsRange = "${r.lower}–${r.upper}"
            }

            if (oesTextureId != 0) s.setCameraTextureName(oesTextureId)

            session = s
            sessionActive = false
            try {
                s.resume()
                sessionActive = true
            } catch (e: Exception) {
                // camera not usable yet; the GL loop retries resume()
                sessionActive = false
            }
            statusView.text = "ARCore ready · live preview"
        } catch (e: Exception) {
            statusView.text = "Session error: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            session?.resume()
            sessionActive = true
            pendingTextureRebind = true
        } catch (ignored: Exception) {
            // permission pending or camera busy; GL loop retries
        }
        if (!autotestDone) {
            val secs = intent?.getIntExtra("bf_autotest", 0) ?: 0
            if (secs > 0) {
                autotestPending = true
                needTracking = intent?.getBooleanExtra("bf_autotest_need_tracking", false) ?: false
                android.util.Log.i("bildfang", "bf_autotest armed: ${secs}s capture " +
                    "(need_tracking=$needTracking)")
            }
        }
        if (autotestPending && !autotestDone && !recording) {
            val h = android.os.Handler(mainLooper)
            val poll = object : Runnable {
                override fun run() {
                    if (autotestPending && !autotestDone && !recording) {
                        // Geometry verification works in PAUSED (tracking not
                        // established) too — frames still flow. STOPPED means
                        // the camera is not delivering; never start there.
                        val stateOk = if (needTracking) {
                            trackingState == "TRACKING"
                        } else {
                            trackingState == "TRACKING" || trackingState == "PAUSED"
                        }
                        if (stateOk && !geomFrozen && previewSurfW > 0) {
                            startRecording()
                            if (recording) {
                                autotestDone = true
                                val secs = intent?.getIntExtra("bf_autotest", 4) ?: 4
                                android.util.Log.i("bildfang", "bf_autotest: started, stopping in ${secs}s")
                                h.postDelayed({
                                    if (recording) stopRecording()
                                }, (secs * 1000).toLong())
                            }
                        } else {
                            // Diagnostics: why the autotest has not fired yet
                            // (screen off -> session paused, or scene without
                            // enough texture for ARCore to establish tracking).
                            if ((SystemClock.elapsedRealtime() / 5000).toLong() != lastAutotestLogTick) {
                                lastAutotestLogTick = (SystemClock.elapsedRealtime() / 5000).toLong()
                                android.util.Log.i("bildfang", "bf_autotest waiting: " +
                                    "tracking=${trackingState} geomFrozen=$geomFrozen " +
                                    "surf=${previewSurfW}x${previewSurfH} sessionActive=$sessionActive")
                            }
                            h.postDelayed(this, 750)
                        }
                    }
                }
            }
            h.postDelayed(poll, 750)
        }
    }

    override fun onPause() {
        super.onPause()
        sessionActive = false
        // A recording in progress is finalized (best effort) so the
        // session is never left half-written; the manifest (P7) marks it.
        if (recording) {
            try { recorder?.stop() } catch (ignored: Exception) {}
            recorder = null
            recording = false
        }
        try {
            session?.pause()
        } catch (ignored: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (recording) recorder?.stop()
            session?.pause()
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            createSession()
        }
    }

    // ---- Recording --------------------------------------------------------

    private fun startRecording() {
        if (recording) return
        val s = session ?: run {
            statusView.text = "Session not ready"
            return
        }
        if (oesTextureId == 0 || previewSurfW <= 0 || previewSurfH <= 0) {
            // GL surface not created yet (rare): the texture and the
            // preview dimensions must be generated on the GL thread, not
            // here (no GL context).
            statusView.text = "Preview not ready yet — try again"
            return
        }

        // P1.1 orientation policy: the capture orientation is chosen by
        // the device's current orientation and FROZEN now. It cannot
        // change mid-recording; a physical rotation is only logged.
        val rot = displayRotation()
        val orientation = SessionGeometry.orientationForDisplayRotation(rot)
        // The encoder canvas is the display canvas in that orientation
        // (same aspect as the preview), so the ARCore UV mapping is
        // exactly valid for the encoder — no crop re-derivation, no
        // assumption about ARCore's crop policy.
        val (ew, eh) = SessionGeometry.encoderDimensions(orientation, previewSurfW, previewSurfH)
        encW = ew
        encH = eh
        val bitrate = ((25_000_000L * ew * eh) / (1920L * 1080L)).toInt()
        lastBitrate = bitrate

        val dir = createSessionDir()
        videoFile = File(dir, "video/camera.mp4")
        sessionDir = dir
        sessionStartMonoNs = SystemClock.elapsedRealtimeNanos()
        fps = 0f
        drawFrameCount = 0
        distinctFrameCount = 0
        lastDistinctTsNs = 0L
        synchronized(posesLock) {
            poses.clear()
            segment = 0
            lastPose = null
            discontinuities.clear()
        }
        // P1.1: fresh geometry + metadata state for this session.
        sessionGeom = null
        encAffine = null
        encRectilinear = null
        encModelRefused = false
        rotationEventsDuringRec = 0
        lastSensorOrientation = 0
        camMetaRecords.clear()
        camMetaProbeDone = false
        geomFrozen = false
        uvFrozen = false
        uvFreezePending = true // consumed on the first recording frame (GL thread)

        // P2a: MediaCodec H.264 (surface input) + MediaMuxer — our recorder,
        // our timestamps, our muxing. Bitrate scales with the canvas area
        // relative to the 1080p/25 Mbit experiment baseline (reconstruction
        // quality > file size; measure, don't assume).
        val rec = MediaCodecRecorder(ew, eh, 30, bitrate, File(dir, "video"))
        if (!rec.start()) {
            android.util.Log.e("bildfang", "recorder start failed", Exception(rec.status()))
            statusView.text = "Recording failed: ${rec.status()}"
            return
        }
        recorder = rec
        encoderEglPair = null
        lastEncodeCamNs = 0L
        // Pre-create the encoder's EGL window surface on the main thread
        // (javax world); the GL thread then only makeCurrent/swap.
        runCatching {
            encoderEglPair = createEncoderEglSurfaceJ()
        }
        recording = true
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
        statusView.setText(R.string.status_recording)
    }

    private fun stopRecording() {
        if (!recording) return
        stopRequested = true // executed on the GL thread (Session is not thread-safe)
        stopBtn.isEnabled = false
        statusView.text = "Finalizing…"
    }

    /** Main thread, after stopRecording() completed on the GL thread. */
    private fun finalizeExport() {
        recording = false
        startBtn.isEnabled = true
        stopBtn.isEnabled = false

        val snapshot = synchronized(posesLock) { poses.toList() }
        val discSnapshot = synchronized(posesLock) { discontinuities.toList() }
        val dir = sessionDir
        if (snapshot.isEmpty() || dir == null) {
            statusView.text = if (snapshot.isEmpty())
                "Nothing recorded (no frames)."
            else
                "Export skipped (no session dir)."
            return
        }

        try {
            val poseFile = File(dir, "poses/poses.json").apply { parentFile?.mkdirs() }
            poseFile.writeText(PoseJson.build(snapshot))
            File(dir, "poses/discontinuities.json")
                .writeText(DiscontinuityJson.build(discSnapshot))
            // P1.1 step 5: per-frame camera metadata (exposure, sensitivity,
            // rolling-shutter skew, stabilization state, crop region) with
            // per-key availability verdicts. Written even when empty — the
            // availability table is then the record of what the device did
            // NOT report.
            val availability = CameraMetaJson.availabilityOf(camMetaRecords)
            File(dir, "camera/frames.json").apply { parentFile?.mkdirs() }
                .writeText(CameraMetaJson.build(camMetaRecords, availability, stabilizationConfig))
            File(dir, "session.json").writeText(buildSessionJson())
            // P1.1 steps 6/7: if the user picked a SAF folder, the finished
            // session is mirrored there and the app-private copy is
            // removed.
            if (storageRootUri != null) {
                copySessionToSaf(dir)
            } else {
                statusView.text = "Saved · ${snapshot.size} poses · video + pose track"
            }
        } catch (e: Exception) {
            statusView.text = "Export failed: ${e.message}"
        }
    }

    private fun buildSessionJson(): String {
        val arcore = try {
            packageManager.getPackageInfo("com.google.ar.core", 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val v = camImageSize
        val g = sessionGeom
        val jn = { d: Double? -> if (d == null) "null" else String.format(Locale.US, "%.3f", d) }
        val k = { ci: CameraIntrinsics? ->
            if (ci == null) {
                "null"
            } else {
                String.format(Locale.US,
                    "{\"width\": %d, \"height\": %d, \"fx\": %.3f, \"fy\": %.3f, \"cx\": %.3f, \"cy\": %.3f}",
                    ci.width, ci.height, ci.fx, ci.fy, ci.cx, ci.cy)
            }
        }
        val affineJson = encAffine?.let {
            "[" + it.joinToString(", ") { String.format(Locale.US, "%.6f", it) } + "]"
        } ?: "null"
        val rectRot = encAffine?.let { GeometryMath.mappingRotationDeg(it) } ?: -1
        val rectJson = when {
            encRectilinear != null -> String.format(Locale.US,
                "{\"fx\": %.3f, \"fy\": %.3f, \"cx\": %.3f, \"cy\": %.3f, \"rotation\": %d, \"status\": \"EXACT (orthogonal mapping: %d° rotation + scale + translation)\", \"note\": \"valid only for the encoded pixel grid; the mapping chain above is the canonical geometry\"}",
                encRectilinear!!.fx, encRectilinear!!.fy, encRectilinear!!.cx, encRectilinear!!.cy, rectRot, rectRot)
            encModelRefused -> "\"REFUSED (mapping non-affine or intrinsics unavailable; do not guess)\""
            else -> "{\"status\": \"ABSENT (mapping contains shear / non-orthogonal components; a rectilinear K does not exist for the encoded image — use the mapping chain with source intrinsics and the ARCore camera pose)\"}"
        }
        val avail = CameraMetaJson.availabilityOf(camMetaRecords)
        val availJson = avail.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }
        // JSON string escaping happens OUTSIDE the raw string below
        // (a backslash-quote sequence inside a triple-quoted string
        // would corrupt the literal).
        val storageRootEsc = storageRootName.replace("\\", "\\\\").replace("\"", "\\\"")
        val stabEsc = stabilizationConfig.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            {
              "schema": "bildfang-capture/v1",
              "app": "bildfang",
              "app_version": "0.3.0",
              "created_utc": "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())}",
              "arcore_sdk": "$arcore",
              "device": "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
              "orientation": ${if (g == null) "null" else "\"${g.orientation.label}\""},
              "orientation_policy": "frozen at START; physical rotation during recording is logged, never applied",
              "rotation_events_during_recording": ${g?.let { rotationEventsDuringRec } ?: 0},
              "tracking": {
                "world_frame": "ARCore device tracking, segment 0 first pose = origin",
                "note": "trajectory estimate, not ground truth"
              },
              "video": {
                "file": "video/camera.mp4",
                "producer": "bildfang MediaCodecRecorder (H.264 hardware encoder, surface input, MediaMuxer)",
                "resolution": "${encW}x${encH}",
                "fps_nominal": 30,
                "bitrate_bps": $lastBitrate,
                "orientation": ${if (g == null) "null" else "\"${g.orientation.label}\""},
                "preview_geometry": ${if (g == null) "null" else String.format(Locale.US, "{\"width\": %d, \"height\": %d, \"display_rotation\": %d}", g.previewWidth, g.previewHeight, g.displayRotationAtStart)},
                "texture_rotation_deg": ${g?.textureRotationDeg ?: 0},
                "source_image": ${k(g?.sourceTextureIntrinsics)},
                "source_image_model": "ARCore Camera.getTextureIntrinsics() at START (the image the OES quad samples)",
                "source_camera_image": ${k(g?.sourceImageIntrinsics)},
                "encoded_image": {
                  "width": ${g?.encoderWidth ?: encW},
                  "height": ${g?.encoderHeight ?: encH},
                  "orientation": ${if (g == null) "null" else "\"${g.orientation.label}\""},
                  "geometry_source": "bildfang-encoder/v1",
                  "mapping": {
                    "kind": "2d_affine_to_source_texture",
                    "affine_enc_to_src": $affineJson,
                    "convention": "p_src = M p_enc + t (pixels, top-left origin); camera ray in the ARCore camera frame = K_src^-1 [p_src, 1]"
                  },
                  "rectilinear_model": $rectJson
                },
                "cpu_image_resolution": ${if (v == null) "null" else "\"${v.width}x${v.height}\""},
                "fps_range": ${if (camFpsRange.isEmpty()) "null" else "\"$camFpsRange\""},
                "video_timebase": {
                  "source_clock": "android_camera",
                  "origin_raw_ns": ${recorder?.timebaseOriginNs ?: 0L},
                  "unit": "ns"
                },
                "counters": {
                  "camera_frames_observed": ${recorder?.counters?.cameraFramesObserved ?: 0L},
                  "frames_submitted": ${recorder?.counters?.framesSubmitted ?: 0L},
                  "frames_encoded": ${recorder?.counters?.framesEncoded ?: 0L},
                  "frames_muxed": ${recorder?.counters?.framesMuxed ?: 0L},
                  "frames_dropped": ${recorder?.counters?.framesDropped ?: 0L},
                  "frames_rate_skipped": ${recorder?.counters?.framesRateSkipped ?: 0L}
                }
              },
              "camera_metadata": {
                "file": "camera/frames.json",
                "stabilization_config": "$stabEsc",
                "availability": { $availJson },
                "note": "per-frame Camera2-derived values via Frame.getImageMetadata() (ARCore 1.54); availability: AVAILABLE_AND_CAPTURED | SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE | NOT_EXPOSED_BY_CURRENT_API"
              },
              "storage": {
                "root": "$storageRootEsc",
                "sessions_path": "sessions/"
              },
              "frames_file": "video/frames.json",
              "poses_file": "poses/poses.json",
              "discontinuities_file": "poses/discontinuities.json",
              "clock": {
                "anchor_frame_ts": $frameTsAnchor,
                "anchor_unix_ms": $anchorUnixMs,
                "anchor_monotonic_ns": $anchorMonoNs,
                "note": "anchor_frame_ts is the raw ARCore frame clock (epoch unknown/opaque); poses are session-relative from it; see docs/capture-format.md clock domains"
              }
            }
        """.trimIndent()
    }

    /** Runs on the GL thread (has the GL context ARCore 1.54 requires). */
    private fun onFrame(frame: Frame) {
        if (frameTsAnchor == 0L) {
            frameTsAnchor = frame.timestamp
            anchorMonoNs = SystemClock.elapsedRealtimeNanos()
            anchorUnixMs = System.currentTimeMillis()
        }
        val cam: Camera = frame.camera
        val pose = cam.pose
        val t = pose.translation
        val q = pose.rotationQuaternion
        val state = when (cam.trackingState) {
            com.google.ar.core.TrackingState.TRACKING -> "TRACKING"
            com.google.ar.core.TrackingState.PAUSED -> "PAUSED"
            com.google.ar.core.TrackingState.STOPPED -> "STOPPED"
        }
        trackingState = state

        if (frame.timestamp != lastDistinctTsNs) {
            lastDistinctTsNs = frame.timestamp
            distinctFrameCount++
        }

        if (recording) {
            // P1.1: the very first recording frame freezes the session
            // geometry (orientation, display, encoder, camera models) and
            // derives the encoded-image mapping. Exactly once, on the GL
            // thread, before any frame is encoded.
            if (uvFreezePending) {
                uvFreezePending = false
                uvFrozen = true
                geomFrozen = true
                if (texDirty) tryUpdateQuadUvs(frame)
                finalizeFrozenGeometry(frame)
            }
            captureCameraMeta(frame)
            val frameTs = frame.timestamp - frameTsAnchor // session-relative ns
            synchronized(posesLock) {
                // Trajectory discontinuity detection (P5): multiple
                // independent signals; the result is an *informational*
                // event, never a verdict — downstream decides whether it
                // means a world-frame reset, relocalization, a bad pose, or
                // legitimate fast motion. Only a translation jump bumps the
                // segment (the actual coordinate-frame change).
                val prev = lastPose
                if (prev != null && state == "TRACKING") {
                    val dtMs = (frameTs - prev.timestampNs) / 1_000_000f
                    val dx = t[0] - prev.x
                    val dy = t[1] - prev.y
                    val dz = t[2] - prev.z
                    val jumpM = sqrt(dx * dx + dy * dy + dz * dz)
                    val rotDeg = quaternionAngleDeg(
                        prev.qx, prev.qy, prev.qz, prev.qw, q[0], q[1], q[2], q[3]
                    )
                    val reasons = ArrayList<String>()
                    if (prev.trackingState != "TRACKING") {
                        reasons.add("tracking_recovered")
                    }
                    if (prev.trackingState == "TRACKING" && dtMs in 0f..1000f) {
                        if (jumpM > 2.0f) reasons.add("translation_jump") // >2 m in <1 s
                        if (rotDeg > 45f) reasons.add("rotation_jump")     // >45° in <1 s
                    }
                    if (reasons.isNotEmpty()) {
                        discontinuities.add(DiscontinuityEvent(
                            frame = poses.size,
                            reasons = reasons,
                            translationJumpM = jumpM,
                            rotationJumpDeg = rotDeg,
                            dtMs = dtMs
                        ))
                        if (reasons.contains("translation_jump")) segment++
                    }
                }
                val r = PoseRecord(
                    index = poses.size,
                    timestampNs = frameTs,
                    androidCameraTimestampNs = frame.androidCameraTimestamp,
                    frameTsRawNs = frame.timestamp,
                    x = t[0], y = t[1], z = t[2],
                    qx = q[0], qy = q[1], qz = q[2], qw = q[3],
                    trackingState = state,
                    segment = segment,
                )
                poses.add(r)
                lastPose = r
            }

        }

        val elapsedNs = frame.timestamp - frameTsAnchor
        val elapsedS = max(0L, elapsedNs / 1_000_000_000L)
        if (elapsedS >= 1) fps = distinctFrameCount.toFloat() / elapsedS

        if (elapsedNs - lastMetricsNs >= METRICS_INTERVAL_NS) {
            lastMetricsNs = elapsedNs
            val frameCount = synchronized(posesLock) { poses.size }
            val drawRate = drawFrameCount / max(1, elapsedS)
            val recS = if (recording) {
                max(0L, (SystemClock.elapsedRealtimeNanos() - sessionStartMonoNs) / 1_000_000_000L)
            } else 0L
            postToUi {
                timerView.text = formatClock(recS)
                metricsView.text = String.format(
                    Locale.US,
                    "%s · %d poses (%d cam frames) · %.1f fps · draw %d/s · seg %d%s\nx %.2f  y %.2f  z %.2f",
                    state, frameCount, distinctFrameCount, fps, drawRate, segment,
                    if (recording) " · rec" else "", t[0], t[1], t[2]
                )
            }
        }
    }

    private fun formatClock(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun createSessionDir(): File {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val hex = String.format(Locale.US, "%06x", random.nextInt())
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "sessions/capture-$stamp-$hex")
    }


    // ---- P1.1: frozen geometry + encoded-image model ----------------------

    /** Current display rotation (android.view.Surface.ROTATION_*). UI or GL
     *  thread (DisplayManager is thread-safe). */
    private fun displayRotation(): Int =
        glView.context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY).rotation

    /**
     * GL thread. Called exactly once, on the first frame of a recording:
     * freezes the session geometry (P1.1 orientation policy) and derives
     * the encoded-image camera model from the actual preview mapping.
     *
     *  - `source_image` = ARCore camera texture intrinsics (the image the
     *    OES quad actually samples);
     *  - `encoded_image.mapping` = the affine encoded-pixel ->
     *    source-texture-pixel mapping, derived from the frozen preview
     *    quad (transformCoordinates2d NDC->UV) composed with the
     *    canvas/viewport scaling. This is the canonical, exact geometry
     *    of every encoded frame;
     *  - `encoded_image.rectilinear_model` = a pinhole K for the encoded
     *    image ONLY when the affine is a pure per-axis scale/flip (exact);
     *    otherwise null — never a scaled copy of the source intrinsics.
     *
     * If the mapping is not affine (driver-specific nonlinearity > 1 px)
     * or no texture intrinsics exist, the model is marked REFUSED in
     * session.json instead of emitting a wrong one.
     */
    private fun finalizeFrozenGeometry(frame: Frame) {
        val cam: Camera = frame.camera
        val texK: CameraIntrinsics? = try {
            val ti = cam.textureIntrinsics
            CameraIntrinsics(
                ti.imageDimensions[0], ti.imageDimensions[1],
                ti.focalLength[0].toDouble(), ti.focalLength[1].toDouble(),
                ti.principalPoint[0].toDouble(), ti.principalPoint[1].toDouble())
        } catch (e: Exception) {
            android.util.Log.w("bildfang", "textureIntrinsics unavailable: ${e.message}")
            null
        }
        val imgK: CameraIntrinsics? = try {
            val ii = cam.imageIntrinsics
            CameraIntrinsics(
                ii.imageDimensions[0], ii.imageDimensions[1],
                ii.focalLength[0].toDouble(), ii.focalLength[1].toDouble(),
                ii.principalPoint[0].toDouble(), ii.principalPoint[1].toDouble())
        } catch (e: Exception) {
            null
        }
        // Snapshot the preview quad first: it is the frozen mapping itself,
        // and the affine fit must exist before SessionGeometry is built (the
        // measured texture rotation is a field of it).
        frozenQuadNdc = quadNdc.copyOf()
        frozenQuadTex = quadTex.copyOf()
        encAffine = if (texK != null) {
            try {
                GeometryMath.encoderToSourceAffine(
                    frozenQuadNdc, frozenQuadTex,
                    previewSurfW, previewSurfH,
                    encW, encH,
                    texK.width, texK.height)
            } catch (e: Exception) {
                android.util.Log.w("bildfang", "encoderToSourceAffine: ${e.message}")
                null
            }
        } else null
        if (encAffine != null) {
            val aff = encAffine!!
            val rawDeg = Math.toDegrees(Math.atan2(aff[3].toDouble(), aff[0].toDouble()))
            lastSensorOrientation = (rawDeg / 90.0).roundToInt() * 90
            android.util.Log.i("bildfang", String.format(Locale.US,
                "texture rotation (measured from affine): %.1f -> snapped %d deg",
                rawDeg, lastSensorOrientation))
        }
        sessionGeom = SessionGeometry(
            orientation = SessionGeometry.orientationForDisplayRotation(displayRotation()),
            displayRotationAtStart = displayRotation(),
            previewWidth = previewSurfW,
            previewHeight = previewSurfH,
            encoderWidth = encW,
            encoderHeight = encH,
            encoderFps = recorder?.fps ?: 30,
            textureRotationDeg = lastSensorOrientation,
            sourceTextureIntrinsics = texK,
            sourceImageIntrinsics = imgK,
            stabilization = stabilizationConfig,
        )
        encRectilinear = if (texK != null && encAffine != null) {
            try {
                GeometryMath.tryExactRectilinear(texK, encAffine!!)?.let {
                    it.copy(width = sessionGeom!!.encoderWidth, height = sessionGeom!!.encoderHeight)
                }
            } catch (e: Exception) {
                encModelRefused = true
                android.util.Log.w("bildfang", "rectilinear fit refused: ${e.message}")
                null
            }
        } else {
            if (texK == null) encModelRefused = true
            null
        }
        val frozenRot = encAffine?.let { GeometryMath.mappingRotationDeg(it) } ?: -1
        android.util.Log.i("bildfang", String.format(Locale.US,
            "FROZEN GEOMETRY: %s display=%dx%d enc=%dx%d sensor=%ddeg srcTex=%s affine=[%s] rectilinear=%s",
            sessionGeom!!.orientation, sessionGeom!!.previewWidth, sessionGeom!!.previewHeight,
            sessionGeom!!.encoderWidth, sessionGeom!!.encoderHeight, lastSensorOrientation,
            texK?.let { "${it.width}x${it.height}" } ?: "n/a",
            encAffine?.joinToString(", ") { String.format(Locale.US, "%.6f", it) } ?: "null",
            if (encRectilinear != null) "EXACT (rot ${frozenRot}deg)" else "ABSENT (shear: use mapping chain)"))
    }

    /**
     * GL thread. P1.1 step 5: per-frame camera metadata from
     * Frame.getImageMetadata() (a Camera2Metadata-derived view in ARCore
     * 1.54). Only what the device/HAL actually reports is stored; the
     * per-key availability verdicts are derived from the records and
     * persisted in the camera/frames.json header.
     */
    private fun captureCameraMeta(frame: Frame) {
        val md: ImageMetadata = try {
            frame.imageMetadata
        } catch (e: Exception) {
            if (!camMetaProbeDone) {
                camMetaProbeDone = true
                android.util.Log.w("bildfang", "Frame.getImageMetadata(): ${e.javaClass.simpleName} ${e.message}")
            }
            return
        }
        if (!camMetaProbeDone) {
            camMetaProbeDone = true
            val keys = try { md.keys } catch (e: Exception) { null }
            android.util.Log.i("bildfang", "ImageMetadata: ${keys?.size ?: 0} keys available on this device")
        }
        fun ln(key: Int): Long? = try { md.getLong(key) } catch (e: Exception) { null }
        fun in_(key: Int): Int? = try { md.getInt(key) } catch (e: Exception) { null }
        fun bn(key: Int): Int? = try { md.getByte(key).toInt() } catch (e: Exception) { null }
        fun rect(key: Int): IntArray? = try {
            val a = md.getIntArray(key)
            if (a == null || a.size < 4) null else intArrayOf(a[0], a[1], a[2], a[3])
        } catch (e: Exception) { null }
        camMetaRecords.add(CameraMetaRecord(
            frame = camMetaRecords.size,
            androidCameraTimestampNs = frame.androidCameraTimestamp,
            exposureTimeNs = ln(ImageMetadata.SENSOR_EXPOSURE_TIME),
            sensitivityIso = in_(ImageMetadata.SENSOR_SENSITIVITY),
            frameDurationNs = ln(ImageMetadata.SENSOR_FRAME_DURATION),
            rollingShutterSkewNs = ln(ImageMetadata.SENSOR_ROLLING_SHUTTER_SKEW),
            videoStabilizationMode = bn(ImageMetadata.CONTROL_VIDEO_STABILIZATION_MODE),
            opticalStabilizationMode = bn(ImageMetadata.LENS_OPTICAL_STABILIZATION_MODE),
            cropRegion = rect(ImageMetadata.SCALER_CROP_REGION),
        ))
    }

    // ---- P1.1 steps 6-7: storage (SAF) + session browser -------------------

    private fun storagePrefs() = getSharedPreferences("bildfang", MODE_PRIVATE)

    private fun applyStoredSafRoot() {
        val u = storagePrefs().getString("saf_root_uri", null) ?: return
        storageRootUri = Uri.parse(u)
        storageRootName = "saf:$u"
    }

    private fun pickStorageRoot() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, 1001)
        } catch (e: Exception) {
            statusView.text = "Storage: picker failed (${e.message})"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val u = data?.data
            if (u == null) {
                statusView.text = "Storage: no folder selected"
                return
            }
            // The API-34 SDK stubs in our toolchain lack
            // takePersistableUriPermission, so go through reflection: the
            // runtime method is present on every real device. If it ever
            // fails, the root stays valid for this process lifetime and the
            // user is told re-picking may be needed after an app restart.
            val persisted = try {
                val m = Activity::class.java.getMethod(
                    "takePersistableUriPermission", Uri::class.java, Int::class.javaPrimitiveType)
                m.invoke(this, u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                true
            } catch (e: Exception) {
                android.util.Log.w("bildfang", "takePersistableUriPermission: ${e.javaClass.simpleName} ${e.message}")
                false
            }
            if (!persisted) {
                statusView.text = "Storage set for this run (persistence unavailable — re-pick after restart)"
            }
            storageRootUri = u
            storageRootName = "saf:$u"
            storagePrefs().edit().putString("saf_root_uri", u.toString()).apply()
            statusView.text = "Storage root: $u"
        }
    }

    /**
     * After finalization into the app-private session dir, mirrors the
     * whole session tree into <SAF root>/Bildfang/sessions/<name>/ and,
     * on confirmed success, removes the app-private copy. MediaMuxer
     * requires a real file path, so SAF gets the finished files, not the
     * live stream.
     */
    private fun copySessionToSaf(src: File) {
        val u = storageRootUri ?: return
        try {
            val tree = DocumentFile.fromTreeUri(this, u) ?: run {
                statusView.text = "SAF: cannot open folder tree"
                return
            }
            val bf = tree.findFile("Bildfang") ?: tree.createDirectory("Bildfang") ?: run {
                statusView.text = "SAF: cannot create Bildfang dir"
                return
            }
            val sesRoot = bf.findFile("sessions") ?: bf.createDirectory("sessions") ?: run {
                statusView.text = "SAF: cannot create sessions dir"
                return
            }
            val dst = sesRoot.findFile(src.name) ?: sesRoot.createDirectory(src.name) ?: run {
                statusView.text = "SAF: cannot create session dir"
                return
            }
            var ok = true
            for (f in src.walkTopDown().filter { it.isFile }.toList()) {
                val parts = f.relativeTo(src).path.split("/")
                var d: DocumentFile = dst
                for (i in 0 until parts.lastIndex) {
                    val sub = d.findFile(parts[i]) ?: d.createDirectory(parts[i])
                    if (sub == null) { ok = false; break }
                    d = sub
                }
                if (!ok) break
                val existing = d.findFile(parts.last())
                if (existing != null) existing.delete()
                val target = d.createFile("application/octet-stream", parts.last())
                if (target == null) { ok = false; break }
                val out = contentResolver.openOutputStream(target.uri)
                if (out == null) { ok = false; break }
                out.use { o -> f.inputStream().use { i2 -> i2.copyTo(o) } }
            }
            if (ok) {
                src.deleteRecursively()
                statusView.text = "Saved to $u (sessions dir)"
            } else {
                statusView.text = "SAF copy FAILED — session kept in app storage"
            }
        } catch (e: Exception) {
            statusView.text = "SAF copy failed (${e.message}) — session kept in app storage"
        }
    }

    private fun safSessions(): List<DocumentFile> {
        val u = storageRootUri ?: return emptyList()
        return try {
            val tree = DocumentFile.fromTreeUri(this, u) ?: return emptyList()
            val bf = tree.findFile("Bildfang") ?: return emptyList()
            val ses = bf.findFile("sessions") ?: return emptyList()
            ses.listFiles().filter { it.isDirectory }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

    private fun docSize(d: DocumentFile): Long =
        d.length() + d.listFiles().filter { it.isDirectory }.map { docSize(it) }.sum()

    private fun showSessionBrowser() {
        data class Item(val label: String, val bytes: Long, val complete: Boolean, val node: Any)
        val items = ArrayList<Item>()
        val apBase = getExternalFilesDir(null) ?: filesDir
        val apSes = File(apBase, "sessions")
        if (apSes.isDirectory) {
            apSes.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach {
                items.add(Item("app: ${it.name}", dirSize(it), File(it, "session.json").exists(), it))
            }
        }
        safSessions().sortedBy { it.name }.forEach {
            items.add(Item("saf: ${it.name}", docSize(it), it.findFile("session.json") != null, it))
        }
        if (items.isEmpty()) {
            statusView.text = "No sessions yet"
            return
        }
        val labels = items.map {
            val mb = it.bytes / 1048576.0
            String.format(Locale.US, "%s · %.2f MB%s", it.label, mb, if (it.complete) "" else " · INCOMPLETE")
        }
        AlertDialog.Builder(this)
            .setTitle("Sessions")
            .setItems(labels.toTypedArray()) { _, i ->
                val item = items[i]
                AlertDialog.Builder(this)
                    .setTitle("Delete session?")
                    .setMessage("${item.label} (${item.bytes / 1048576.0} MB) will be permanently deleted.")
                    .setPositiveButton("Delete") { _, _ -> deleteSession(item) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun deleteSession(item: Any) {
        try {
            if (item is java.io.File) {
                item.deleteRecursively()
                statusView.text = "Deleted ${item.name} (app)"
            } else if (item is DocumentFile) {
                item.delete()
                statusView.text = "Deleted ${item.name} (SAF)"
            }
        } catch (e: Exception) {
            statusView.text = "Delete failed: ${e.message}"
        }
    }

    private companion object {
        // GL_TEXTURE_EXTERNAL_OES (not in GLES20)
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
