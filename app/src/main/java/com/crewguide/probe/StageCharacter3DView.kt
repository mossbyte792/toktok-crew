package com.crewguide.probe

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 무대 중앙 3D 승무원 아바타 렌더러(OpenGL ES 2.0 + 투명 알파 TextureView).
 * - StageScreen의 레이어 순서(배경 그라데이션·스포트라이트 위, 카드·좌석 지도·말풍선·하단 패널 아래)와 자연스럽게 겹치도록
 *   SurfaceView 대신 isOpaque=false 인 TextureView + EGL14(알파 8비트·깊이 16비트)를 사용한다.
 * - 요구 구동으로만 그리며(StageScreen.motionTick 이 setPose 를 부를 때만 프레임 요청), LOCKED·숨김·백그라운드에서는 유휴 상태다.
 * - EGL·셰이더 초기화 실패 시 자동으로 내부 SvgCharacterView 로 폴백해 무대가 비지 않게 보장한다.
 */
class StageCharacter3DView(
    context: Context,
    var onInfo: ((String) -> Unit)? = null,
) : FrameLayout(context), CharacterRenderer {

    override val view: View get() = this

    private val fallbackView = SvgCharacterView(context).apply {
        fitInside = true
        viewport = StageLayout.CHARACTER_VIEWPORT
        visibility = View.GONE
    }

    private val textureView = TextureView(context).apply {
        isOpaque = false
    }

    @Volatile private var currentStyle: CharacterStyle = CharacterStyle.DEFAULT
    @Volatile private var currentPose: CharacterPose = CharacterPose()
    @Volatile private var currentScene: Scene = Scene.PICK
    @Volatile private var currentSceneElapsedMs: Long = 0L
    @Volatile private var currentAbsoluteMs: Long = 0L
    @Volatile private var usingFallback: Boolean = false
    @Volatile private var drawQueued: Boolean = false

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var glSession: GlSession? = null

    init {
        clipChildren = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = DemoLabel.PIECE_GRAPHIC

        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        addView(
            fallbackView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (usingFallback) return
                startGlSession(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                if (usingFallback) return
                val h = workerHandler ?: return
                h.post {
                    glSession?.resize(width, height)
                    glSession?.draw(
                        currentStyle, currentPose, currentScene, currentSceneElapsedMs, currentAbsoluteMs,
                    )
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopGlSessionSync()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (w > 0 && h > 0 && !usingFallback) {
            // SvgCharacterView(fitInside=true) 및 StageBubblePlacement.squareSidePx 와 똑같이 가운데 min(w,h) 정사각에 맞춘다
            val side = min(w, h)
            val lp = textureView.layoutParams as LayoutParams
            if (lp.width != side || lp.height != side) {
                lp.width = side
                lp.height = side
                textureView.layoutParams = lp
            }
            val l = (w - side) / 2
            val t = (h - side) / 2
            textureView.layout(l, t, l + side, t + side)
        } else {
            super.onLayout(changed, left, top, right, bottom)
        }
        if (fallbackView.visibility == View.VISIBLE) {
            fallbackView.layout(0, 0, w, h)
        }
    }

    override fun applyStyle(style: CharacterStyle) {
        val sanitized = CharacterOptions.sanitize(style)
        val changed = currentStyle != sanitized
        currentStyle = sanitized
        fallbackView.applyStyle(sanitized)
        if (changed) requestDraw()
    }

    /** StageScreen 이 장면과 경과 시간을 함께 알려 3D 브리핑 동작(벨트 시연·환영 인사·경청 등)을 구동한다 */
    fun setSceneTiming(scene: Scene, sceneElapsedMs: Long, absoluteMs: Long) {
        currentScene = scene
        currentSceneElapsedMs = sceneElapsedMs
        currentAbsoluteMs = absoluteMs
    }

    override fun setPose(pose: CharacterPose) {
        currentPose = pose
        if (usingFallback) {
            fallbackView.setPose(pose)
            return
        }
        requestDraw()
    }

    private fun requestDraw() {
        if (usingFallback) {
            fallbackView.invalidate()
            return
        }
        val h = workerHandler ?: return
        if (drawQueued) return
        drawQueued = true
        h.post {
            drawQueued = false
            val s = glSession ?: return@post
            if (!s.draw(currentStyle, currentPose, currentScene, currentSceneElapsedMs, currentAbsoluteMs)) {
                activateFallback("draw_failed")
            }
        }
    }

    private fun activateFallback(reason: String) {
        if (usingFallback) return
        usingFallback = true
        post {
            Log.w(TAG, "[3D] 2D SVG 폴백 전환: $reason")
            onInfo?.invoke("[3D] 폴백 전환($reason)")
            textureView.visibility = View.GONE
            fallbackView.applyStyle(currentStyle)
            fallbackView.setPose(currentPose)
            fallbackView.visibility = View.VISIBLE
            requestLayout()
        }
    }

    private fun startGlSession(surface: SurfaceTexture, width: Int, height: Int) {
        stopGlSessionSync()
        val thread = HandlerThread("CrewStage3D").also { it.start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        workerHandler = handler
        handler.post {
            val session = GlSession()
            if (session.init(surface, width, height)) {
                glSession = session
                val ver = session.glVersion
                post { onInfo?.invoke("[3D] 무대 아바타 활성 ($ver)") }
                session.draw(currentStyle, currentPose, currentScene, currentSceneElapsedMs, currentAbsoluteMs)
            } else {
                val err = session.lastError
                session.release()
                activateFallback(err)
            }
        }
    }

    private fun stopGlSessionSync() {
        val h = workerHandler
        val t = workerThread
        workerHandler = null
        workerThread = null
        drawQueued = false
        if (h != null && t != null) {
            val latch = CountDownLatch(1)
            h.post {
                try {
                    glSession?.release()
                } catch (_: Throwable) {
                } finally {
                    glSession = null
                    latch.countDown()
                }
            }
            try {
                latch.await(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            t.quitSafely()
        } else {
            glSession = null
        }
    }

    override fun onDetachedFromWindow() {
        stopGlSessionSync()
        super.onDetachedFromWindow()
    }

    private class MeshBuffers(
        val vertexBuf: FloatBuffer,
        val normalBuf: FloatBuffer,
        val indexBuf: ShortBuffer,
        val indexCount: Int,
    )

    private class GlSession {
        var glVersion: String = ""
        var lastError: String = "unknown"

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var vpWidth = 1
        private var vpHeight = 1
        private var ready = false

        private var aPosition = -1
        private var aNormal = -1
        private var uMvp = -1
        private var uModel = -1
        private var uNormalMat = -1
        private var uColor = -1
        private var uShininess = -1
        private var uKeyDir = -1

        private var sphereMesh: MeshBuffers? = null
        private var cylinderMesh: MeshBuffers? = null

        private val proj = FloatArray(16)
        private val viewMat = FloatArray(16)
        private val vp = FloatArray(16)
        private val model = FloatArray(16)
        private val mvp = FloatArray(16)
        private val inv = FloatArray(16)
        private val invT = FloatArray(16)
        private val normalMat = FloatArray(9)
        private val keyDir = Lighting.normalize(floatArrayOf(-0.36f, 0.66f, 0.74f))

        fun init(surfaceTexture: SurfaceTexture, width: Int, height: Int): Boolean {
            return try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                    lastError = "no_display"; return false
                }
                val vers = IntArray(2)
                if (!EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1)) {
                    lastError = "egl_init"; return false
                }
                val cfg = chooseConfig(eglDisplay) ?: run {
                    lastError = "no_config"; return false
                }
                val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
                if (eglContext == EGL14.EGL_NO_CONTEXT) {
                    lastError = "no_context"; return false
                }
                val surfAttrs = intArrayOf(EGL14.EGL_NONE)
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surfaceTexture, surfAttrs, 0)
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    lastError = "no_surface"; return false
                }
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    lastError = "make_current"; return false
                }

                glVersion = (GLES20.glGetString(GLES20.GL_VERSION) ?: "GLES2").take(48)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                GLES20.glDepthFunc(GLES20.GL_LEQUAL)
                GLES20.glClearColor(0f, 0f, 0f, 0f)

                val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return false
                val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return false
                val prog = GLES20.glCreateProgram()
                GLES20.glAttachShader(prog, vs)
                GLES20.glAttachShader(prog, fs)
                GLES20.glLinkProgram(prog)
                val linkOk = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkOk, 0)
                GLES20.glDeleteShader(vs)
                GLES20.glDeleteShader(fs)
                if (linkOk[0] == 0) {
                    lastError = "link_program"
                    GLES20.glDeleteProgram(prog)
                    return false
                }
                program = prog
                aPosition = GLES20.glGetAttribLocation(prog, "aPosition")
                aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
                uMvp = GLES20.glGetUniformLocation(prog, "uMVP")
                uModel = GLES20.glGetUniformLocation(prog, "uModel")
                uNormalMat = GLES20.glGetUniformLocation(prog, "uNormalMat")
                uColor = GLES20.glGetUniformLocation(prog, "uColor")
                uShininess = GLES20.glGetUniformLocation(prog, "uShininess")
                uKeyDir = GLES20.glGetUniformLocation(prog, "uKeyDir")

                val sm = SphereMesh.generate(28, 36)
                sphereMesh = buildBuffers(sm.positions, SphereMesh.normals(28, 36), sm.indices)
                val cm = CylinderMesh.generate(36)
                cylinderMesh = buildBuffers(cm.positions, CylinderMesh.normals(36), cm.indices)

                ready = aPosition >= 0 && aNormal >= 0 && uMvp >= 0 && uModel >= 0 &&
                    uNormalMat >= 0 && uColor >= 0 && uShininess >= 0 && uKeyDir >= 0
                if (!ready) {
                    lastError = "attrib_missing"
                    return false
                }
                resize(width, height)
                true
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName
                false
            }
        }

        private fun chooseConfig(display: EGLDisplay): EGLConfig? {
            // 4x MSAA 알파 설정을 먼저 시도하고, 에뮬레이터 GPU가 지원하지 않으면 기본 알파 설정으로 내린다
            val msaaSpec = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_SAMPLE_BUFFERS, 1,
                EGL14.EGL_SAMPLES, 4,
                EGL14.EGL_NONE,
            )
            val baseSpec = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(display, msaaSpec, 0, configs, 0, 1, num, 0) && num[0] > 0 && configs[0] != null) {
                return configs[0]
            }
            if (EGL14.eglChooseConfig(display, baseSpec, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                return configs[0]
            }
            return null
        }

        private fun compileShader(type: Int, src: String): Int? {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                lastError = "shader_compile"
                GLES20.glDeleteShader(sh)
                return null
            }
            return sh
        }

        private fun buildBuffers(pos: FloatArray, nor: FloatArray, idx: ShortArray): MeshBuffers {
            val vb = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
                it.put(pos); it.position(0)
            }
            val nb = ByteBuffer.allocateDirect(nor.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
                it.put(nor); it.position(0)
            }
            val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also {
                it.put(idx); it.position(0)
            }
            return MeshBuffers(vb, nb, ib, idx.size)
        }

        fun resize(w: Int, h: Int) {
            vpWidth = w.coerceAtLeast(1)
            vpHeight = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, vpWidth, vpHeight)
            val aspect = vpWidth.toFloat() / vpHeight.toFloat()
            Matrix.perspectiveM(proj, 0, CrewAvatar3D.CAM_FOV_DEG, aspect, 0.5f, 12f)
            Matrix.setLookAtM(
                viewMat, 0,
                0f, CrewAvatar3D.CAM_EYE_Y, CrewAvatar3D.CAM_EYE_Z,
                0f, CrewAvatar3D.CAM_CENTER_Y, 0f,
                0f, 1f, 0f,
            )
            Matrix.multiplyMM(vp, 0, proj, 0, viewMat, 0)
        }

        fun draw(
            style: CharacterStyle,
            pose: CharacterPose,
            scene: Scene,
            sceneElapsedMs: Long,
            absoluteMs: Long,
        ): Boolean {
            if (!ready || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return false
            val sph = sphereMesh ?: return false
            val cyl = cylinderMesh ?: return false
            return try {
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
                GLES20.glViewport(0, 0, vpWidth, vpHeight)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                GLES20.glUseProgram(program)
                GLES20.glUniform3f(uKeyDir, keyDir[0], keyDir[1], keyDir[2])
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glEnableVertexAttribArray(aNormal)

                val parts = CrewAvatar3D.build(style, pose, scene, sceneElapsedMs, absoluteMs)
                for (part in parts) {
                    val mesh = if (part.mesh == MeshKind.CYLINDER) cyl else sph
                    Matrix.setIdentityM(model, 0)
                    if (part.groupRxDeg != 0f || part.groupRyDeg != 0f || part.groupRzDeg != 0f) {
                        Matrix.translateM(model, 0, part.pivotX, part.pivotY, part.pivotZ)
                        if (part.groupRzDeg != 0f) Matrix.rotateM(model, 0, part.groupRzDeg, 0f, 0f, 1f)
                        if (part.groupRyDeg != 0f) Matrix.rotateM(model, 0, part.groupRyDeg, 0f, 1f, 0f)
                        if (part.groupRxDeg != 0f) Matrix.rotateM(model, 0, part.groupRxDeg, 1f, 0f, 0f)
                        Matrix.translateM(model, 0, -part.pivotX, -part.pivotY, -part.pivotZ)
                    }
                    Matrix.translateM(model, 0, part.tx, part.ty, part.tz)
                    if (part.rzDeg != 0f) Matrix.rotateM(model, 0, part.rzDeg, 0f, 0f, 1f)
                    if (part.ryDeg != 0f) Matrix.rotateM(model, 0, part.ryDeg, 0f, 1f, 0f)
                    if (part.rxDeg != 0f) Matrix.rotateM(model, 0, part.rxDeg, 1f, 0f, 0f)
                    Matrix.scaleM(model, 0, part.sx, part.sy, part.sz)

                    Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                    GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                    GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)

                    if (Matrix.invertM(inv, 0, model, 0)) {
                        Matrix.transposeM(invT, 0, inv, 0)
                        for (col in 0..2) for (row in 0..2) normalMat[col * 3 + row] = invT[col * 4 + row]
                    } else {
                        for (k in 0..8) normalMat[k] = if (k % 4 == 0) 1f else 0f
                    }
                    GLES20.glUniformMatrix3fv(uNormalMat, 1, false, normalMat, 0)

                    val c = part.colorRgb
                    GLES20.glUniform3f(
                        uColor,
                        ((c shr 16) and 0xFF) / 255f,
                        ((c shr 8) and 0xFF) / 255f,
                        (c and 0xFF) / 255f,
                    )
                    GLES20.glUniform1f(uShininess, part.shininess)

                    mesh.vertexBuf.position(0)
                    mesh.normalBuf.position(0)
                    mesh.indexBuf.position(0)
                    GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, mesh.vertexBuf)
                    GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 12, mesh.normalBuf)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indexBuf)
                }

                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aNormal)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun release() {
            ready = false
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        if (program != 0) {
                            GLES20.glDeleteProgram(program)
                            program = 0
                        }
                    }
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                } catch (_: Throwable) {
                }
            }
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private companion object {
        const val TAG = "CrewGuide3D"

        // 부드러운 반-램버트 스튜디오 주광 + 상하 반구 보조광 + 테두리광(Rim) + 재질별 반사광(Blinn-Phong)
        const val VERTEX_SHADER =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "uniform mat3 uNormalMat;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec3 aNormal;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vViewDir;\n" +
            "void main() {\n" +
            "  vec4 worldPos = uModel * aPosition;\n" +
            "  vNormal = uNormalMat * aNormal;\n" +
            "  vViewDir = vec3(0.0, 0.10, 3.35) - worldPos.xyz;\n" +
            "  gl_Position = uMVP * aPosition;\n" +
            "}\n"

        const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform vec3 uColor;\n" +
            "uniform vec3 uKeyDir;\n" +
            "uniform float uShininess;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vViewDir;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  vec3 l = normalize(uKeyDir);\n" +
            "  vec3 v = normalize(vViewDir);\n" +
            "  float wrap = clamp((dot(n, l) + 0.50) / 1.50, 0.0, 1.0);\n" +
            "  float hemi = mix(0.92, 1.05, n.y * 0.5 + 0.5);\n" +
            "  vec3 warmShade = mix(vec3(0.74, 0.69, 0.74), vec3(1.04, 1.02, 1.00), wrap) * hemi;\n" +
            "  vec3 h = normalize(l + v);\n" +
            "  float spec = pow(max(dot(n, h), 0.0), 28.0) * uShininess * 0.46;\n" +
            "  float rim = pow(1.0 - max(dot(n, v), 0.0), 2.8) * 0.14;\n" +
            "  vec3 lit = uColor * warmShade + vec3(spec) + vec3(0.86, 0.93, 1.0) * rim;\n" +
            "  gl_FragColor = vec4(clamp(lit, 0.0, 1.0), 1.0);\n" +
            "}\n"
    }
}
