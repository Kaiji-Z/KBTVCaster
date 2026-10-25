package com.kbtv.caster.airplay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MStar-tier display path. The field firmware's OMX.MS.AVC.Decoder decodes
 * fine into a SurfaceTexture surface (DecoderAbTest.surfaceModeRecipe:
 * 648/649 frames with the low-latency key set) but rejects access units when
 * configured with the display surface, and the previous CPU blit tier capped
 * at ~40fps and died on 1920x1080 (RenderScript + software canvas). This
 * class owns the decoder's SurfaceTexture, consumes frames on a private EGL
 * context, and draws each onto the display Surface — the zero-copy design
 * commercial receivers use on this chipset class.
 *
 * All EGL and texture work is confined to one handler thread. The renderer
 * hands in the display Surface, receives the decoder Surface, and releases
 * on teardown; any init failure surfaces as a null decoder Surface so the
 * renderer can fall back to the CPU blit tier.
 */
class MirrorGlDisplay(private val displaySurface: Surface, private val decodeWidth: Int, private val decodeHeight: Int) {

    private val thread = HandlerThread("mirror-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val ready = CountDownLatch(1)

    @Volatile private var initOk = false
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var currentSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var decodeTexture: SurfaceTexture? = null
    private var decodeSurfaceRef: Surface? = null
    private var program = 0
    private var drewFrames = 0L
    private var firstDrawLogged = false
    private val texMatrix = FloatArray(16)
    private val quad: ByteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(floatArrayOf(
                // x, y, u, v per vertex; TRIANGLE_STRIP order
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ))
            position(0)
        }

    init {
        handler.post { initEgl() }
    }

    /** Blocks until the decoder Surface exists; null when init failed. */
    fun createDecoderSurface(timeoutMs: Long): Surface? {
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return if (initOk) decodeSurfaceRef else null
    }

    private fun initEgl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay === EGL14.EGL_NO_DISPLAY) return
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            // The shader forces alpha to 1.0 (see FRAGMENT_SHADER): this
            // chipset's old GLES driver fed content-coupled sampled alpha
            // into the alpha-bearing window surface, and SurfaceFlinger
            // composited the video to shifting shades of black (2026-10-25
            // field session) while counters and the raw-pixel test stayed
            // green. ALPHA_SIZE stays 8 — ImageReader-based tests require it.
            val confAttribs = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(eglDisplay, confAttribs, 0, configs, 0, 1, num, 0) ||
                num[0] == 0
            ) return
            eglConfig = configs[0]
            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (eglContext === EGL14.EGL_NO_CONTEXT) return
            // Pbuffer makes the context current while creating the OES texture.
            val pb = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            EGL14.eglMakeCurrent(eglDisplay, pb, pb, eglContext)

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = buildProgram()

            val st = SurfaceTexture(textureId)
            // pre-size BEFORE the producer connects: this chipset's OMX does
            // not reliably resize the queue post-connection and a 1x1 default
            // collapses every sample to one pixel (magnified-color screen)
            st.setDefaultBufferSize(decodeWidth, decodeHeight)
            st.setOnFrameAvailableListener({ onFrame() }, handler)
            decodeTexture = st
            decodeSurfaceRef = Surface(st)
            if (displaySurface.isValid) createWindowSurface()
            initOk = true
        } catch (e: Exception) {
            Timber.w(e, "mirror GL display init failed")
        } finally {
            ready.countDown()
        }
    }

    private fun createWindowSurface(): Boolean {
        if (eglConfig == null || windowSurface !== EGL14.EGL_NO_SURFACE) return windowSurface !== EGL14.EGL_NO_SURFACE
        windowSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, displaySurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (windowSurface === EGL14.EGL_NO_SURFACE) {
            Timber.w("mirror GL window surface create failed: %d", EGL14.eglGetError())
            return false
        }
        return true
    }

    private fun onFrame() {
        val st = decodeTexture ?: return
        var ws = windowSurface
        if (ws === EGL14.EGL_NO_SURFACE && displaySurface.isValid && createWindowSurface()) {
            ws = windowSurface
        }
        // SurfaceView resize without recreation leaves the EGL window surface
        // at the old size (0x0 at first layout included) — rebuild on drift.
        if (ws !== EGL14.EGL_NO_SURFACE) {
            val sz = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, ws, EGL14.EGL_WIDTH, sz, 0)
            val w = sz[0]
            EGL14.eglQuerySurface(eglDisplay, ws, EGL14.EGL_HEIGHT, sz, 0)
            if (w == 0 || sz[0] == 0) {
                Timber.w("mirror GL window surface is %dx%d — rebuilding", w, sz[0])
                EGL14.eglDestroySurface(eglDisplay, ws)
                windowSurface = EGL14.EGL_NO_SURFACE
                currentSurface = EGL14.EGL_NO_SURFACE
                if (displaySurface.isValid) createWindowSurface()
                ws = windowSurface
                if (ws === EGL14.EGL_NO_SURFACE) {
                    st.updateTexImage()
                    return
                }
            }
        }
        // Consume every frame unconditionally: a full BufferQueue stalls the
        // decoder. With nowhere to show it, consume and drop.
        if (ws === EGL14.EGL_NO_SURFACE || !displaySurface.isValid) {
            try {
                st.updateTexImage()
            } catch (e: Exception) {
                Timber.w(e, "mirror GL updateTexImage drop")
            }
            return
        }
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            drawFrame(ws)
        } catch (e: Exception) {
            Timber.w(e, "mirror GL frame failed")
        }
    }

    private fun makeCurrent(ws: EGLSurface) {
        if (currentSurface === ws) return
        if (!EGL14.eglMakeCurrent(eglDisplay, ws, ws, eglContext)) {
            Timber.w("mirror GL makeCurrent failed: 0x%x", EGL14.eglGetError())
            return
        }
        currentSurface = ws
        if (!firstDrawLogged) Timber.i("mirror GL made current on surface=%d", ws.hashCode())
    }

    /** Non-zero once at least one frame reached the display surface. */
    fun drewFrameCount(): Long = drewFrames

    /**
     * Fresh SurfaceTextures default to a 1x1 buffer; this chipset's OMX
     * producer does not reliably resize it, so every sample landed on one
     * content-coupled pixel — the live screen showed a single color filling
     * the TV (2026-10-25). Call when the stream resolution is known.
     */
    fun setDecodeBufferSize(width: Int, height: Int) {
        handler.post {
            try {
                decodeTexture?.setDefaultBufferSize(width, height)
            } catch (e: Exception) {
                Timber.w(e, "mirror GL setDefaultBufferSize")
            }
        }
    }

    private fun drawFrame(ws: EGLSurface) {
        // init left the context current on the 1x1 pbuffer; without this
        // bind every draw went to the pbuffer and eglSwapBuffers silently
        // failed with EGL_BAD_SURFACE — the live black screen of 2026-10-22
        // while trace showed 364 frames rendered at p95 29ms.
        makeCurrent(ws)
        val size = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, ws, EGL14.EGL_WIDTH, size, 0)
        val width = size[0]
        EGL14.eglQuerySurface(eglDisplay, ws, EGL14.EGL_HEIGHT, size, 0)
        // Aspect-fit letterbox: a portrait 500x1080 stream must not stretch
        // across the 1920x1080 panel (the fullscreen quad is stretch-to-fill).
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (width > 0 && size[0] > 0 && decodeWidth > 0 && decodeHeight > 0) {
            val fit = minOf(width.toFloat() / decodeWidth, size[0].toFloat() / decodeHeight)
            val vw = (decodeWidth * fit).toInt()
            val vh = (decodeHeight * fit).toInt()
            GLES20.glViewport((width - vw) / 2, (size[0] - vh) / 2, vw, vh)
        } else {
            GLES20.glViewport(0, 0, width, size[0])
        }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        // matrix passed row-wise via glUniform4f: GLES20's glUniformMatrix4fv
        // overloads did not resolve under the Kotlin toolchain here
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uR0"),
            texMatrix[0], texMatrix[1], texMatrix[2], texMatrix[3])
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uR1"),
            texMatrix[4], texMatrix[5], texMatrix[6], texMatrix[7])
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uR2"),
            texMatrix[8], texMatrix[9], texMatrix[10], texMatrix[11])
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uR3"),
            texMatrix[12], texMatrix[13], texMatrix[14], texMatrix[15])
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        if (!firstDrawLogged) {
            val err = GLES20.glGetError()
            Timber.i("mirror GL draw locs pos=%d uv=%d glErr=0x%x progLinked ok", posLoc, uvLoc, err)
        }
        quad.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(posLoc)
        quad.position(8)  // byte offset: u,v are floats 2,3 (ByteBuffer positions are BYTES)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (!firstDrawLogged) {
            // shader-output probe: read back corners + center of the drawn
            // framebuffer BEFORE swap — separates "shader produced garbage"
            // from "compositor lost it"
            val px = IntArray(4)
            val buf = IntArray(4)
            val glb = java.nio.IntBuffer.wrap(buf)
            GLES20.glReadPixels(width / 4, size[0] / 4, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, glb); px[0] = buf[0]
            glb.position(0)
            GLES20.glReadPixels(width / 2, size[0] / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, glb); px[1] = buf[0]
            glb.position(0)
            GLES20.glReadPixels(3 * width / 4, 3 * size[0] / 4, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, glb); px[2] = buf[0]
            glb.position(0)
            GLES20.glReadPixels(1, 1, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, glb); px[3] = buf[0]
            Timber.i("mirror GL readback rgba=%s", px.joinToString(" ") { "%08x".format(it) })
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, ws)) {
            Timber.w("mirror GL swap failed: 0x%x", EGL14.eglGetError())
        } else {
            drewFrames++
            if (!firstDrawLogged) {
                firstDrawLogged = true
                Timber.i("mirror GL first draw %dx%d tex=[%s]",
                    width, size[0],
                    texMatrix.take(8).joinToString(" ") { "%.2f".format(it) })
            } else if (drewFrames % 60 == 0L) {
                Timber.i("mirror GL drew %d frames", drewFrames)
            }
        }
    }

    private fun buildProgram(): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vs, VERTEX_SHADER)
        GLES20.glCompileShader(vs)
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fs, FRAGMENT_SHADER)
        GLES20.glCompileShader(fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            throw IllegalStateException("mirror GL program link failed: $log")
        }
        return p
    }

    /** Stops the GL thread and frees everything; blocks up to 1s for order. */
    fun release() {
        handler.post {
            try {
                if (windowSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                currentSurface = EGL14.EGL_NO_SURFACE
                decodeSurfaceRef?.release()
                decodeSurfaceRef = null
                decodeTexture?.release()
                decodeTexture = null
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Exception) {
                Timber.w(e, "mirror GL display release")
            } finally {
                thread.quitSafely()
            }
        }
        thread.join(1000)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform vec4 uR0;
            uniform vec4 uR1;
            uniform vec4 uR2;
            uniform vec4 uR3;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec4 t = vec4(aTexCoord.xy, 0.0, 1.0);
                vTexCoord = (uR0 * t.x + uR1 * t.y + uR2 * t.z + uR3 * t.w).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                // DIAGNOSTIC (2026-10-25): raw UVs, transform bypassed. A
                // correct-looking frame (possibly flipped) implicates the
                // transform path; a solid color implicates attributes/texture.
                gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);
            }
        """
    }
}
