package com.crewguide.probe

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private class GpuMesh(val vertexBuf: FloatBuffer, val normalBuf: FloatBuffer, val indexBuf: ShortBuffer, val indexCount: Int)

/**
 * 3D 시험(단계 1b, 개발용): 부위별 평면 색 도형(기본: 승무원 실루엣, DEFAULT_SCENE)을 정지 화면 1장으로 그린다. ES 2.0 호환.
 * 조명(1c 이후): 방향광 1개 + 환경광의 램버트 조명이며, LIGHTING_ON 상수를 false로 하면 꺼진다(환경광 1.0, 음영 없음).
 * 콜백은 모두 메인 스레드(post)에서 호출된다.
 */
class StageGlView(
    context: Context,
    private val onShaderReady: () -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onError: (String) -> Unit,
    private val onGlVersion: (String) -> Unit,
    private val onInfo: (String) -> Unit = {},
) : GLSurfaceView(context) {

    companion object {
        const val SCENE_SPHERE = 0       // 1a: 단색 구 하나
        const val SCENE_AVATAR = 1       // 1b: 부위 조합 실루엣
        /** 롤백: 이 값을 SCENE_SPHERE로 바꾸면 1a 상태가 된다 */
        const val DEFAULT_SCENE = SCENE_AVATAR
        private const val SPHERE_SOLID = 0x2563EB
        /** 롤백: false이면 환경광 1.0(음영 없음)으로 1b와 같은 평면 색이 된다 */
        const val LIGHTING_ON = true

        private const val VERTEX_SRC =
            "uniform mat4 uMVP;\n" +
            "uniform mat3 uNormalMat;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec3 aNormal;\n" +
            "varying vec3 vNormal;\n" +
            "void main() {\n" +
            "  vNormal = uNormalMat * aNormal;\n" +
            "  gl_Position = uMVP * aPosition;\n" +
            "}\n"
        private const val FRAGMENT_SRC =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uAmbient;\n" +
            "varying vec3 vNormal;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  float d = max(dot(n, normalize(uLightDir)), 0.0);\n" +
            "  float k = uAmbient + (1.0 - uAmbient) * d;\n" +
            "  gl_FragColor = vec4(uColor.rgb * k, uColor.a);\n" +
            "}\n"
    }

    // 기본 EGL 설정(GLSurfaceView 기본 선택기)은 16비트 깊이 버퍼를 포함한다. 별도 지정하지 않는다.
    private val renderer = SphereRenderer()

    init {
        try {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
        } catch (e: Exception) {
            onError("초기화: ${e.javaClass.simpleName}")
        }
    }

    private fun report(block: () -> Unit) { post { block() } }

    private inner class SphereRenderer : GLSurfaceView.Renderer {
        private var program = 0
        private var aPosition = -1
        private var uMvp = -1
        private var uColor = -1
        private var aNormal = -1
        private var uNormalMat = -1
        private var uLightDir = -1
        private var uAmbient = -1
        private val inv = FloatArray(16)
        private val invT = FloatArray(16)
        private val normalMat = FloatArray(9)
        private var sphereMesh: GpuMesh? = null
        private var cylinderMesh: GpuMesh? = null
        private val parts: List<AvatarPart> = if (DEFAULT_SCENE == SCENE_AVATAR) AvatarParts.ALL else
            listOf(AvatarPart("구", MeshKind.SPHERE, 0f, 0f, 0f, 1f, 1f, 1f, SPHERE_SOLID))
        private val model = FloatArray(16)
        private val mvp = FloatArray(16)
        private var ready = false
        private var firstFrameReported = false
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val vp = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            ready = false
            try {
                val ver = GLES20.glGetString(GLES20.GL_VERSION)
                if (ver != null) { val s = ver.take(60); report { onGlVersion(s) } }
                GLES20.glClearColor(0.88f, 0.88f, 0.88f, 1f)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC) ?: return
                val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC) ?: return
                val prog = GLES20.glCreateProgram()
                GLES20.glAttachShader(prog, vs)
                GLES20.glAttachShader(prog, fs)
                GLES20.glLinkProgram(prog)
                val status = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = (GLES20.glGetProgramInfoLog(prog) ?: "").take(200)
                    GLES20.glDeleteProgram(prog)
                    report { onError("링크: $log") }
                    return
                }
                program = prog
                aPosition = GLES20.glGetAttribLocation(prog, "aPosition")
                uMvp = GLES20.glGetUniformLocation(prog, "uMVP")
                uColor = GLES20.glGetUniformLocation(prog, "uColor")
                aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
                uNormalMat = GLES20.glGetUniformLocation(prog, "uNormalMat")
                uLightDir = GLES20.glGetUniformLocation(prog, "uLightDir")
                uAmbient = GLES20.glGetUniformLocation(prog, "uAmbient")
                val sm = SphereMesh.generate(24, 32)
                sphereMesh = makeMesh(sm.positions, sm.indices, SphereMesh.normals(24, 32))
                val cm = CylinderMesh.generate(32)
                cylinderMesh = makeMesh(cm.positions, cm.indices, CylinderMesh.normals(32))
                ready = aPosition >= 0 && uMvp >= 0 && uColor >= 0 &&
                    aNormal >= 0 && uNormalMat >= 0 && uLightDir >= 0 && uAmbient >= 0
                if (ready) {
                    val n = parts.size
                    report { onShaderReady() }
                    report { onInfo("부위 ${n}개") }
                    report { onInfo("[3D] 조명 방향광1+환경광") }
                } else report { onError("위치: 변수 찾기 실패") }
            } catch (e: Exception) {
                report { onError("예외: ${e.javaClass.simpleName}") }
            }
        }

        private fun makeMesh(positions: FloatArray, indices: ShortArray, normals: FloatArray): GpuMesh {
            val vb = ByteBuffer.allocateDirect(positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
                it.put(positions); it.position(0)
            }
            val nb = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
                it.put(normals); it.position(0)
            }
            val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also {
                it.put(indices); it.position(0)
            }
            return GpuMesh(vb, nb, ib, indices.size)
        }

        private fun compile(type: Int, src: String): Int? {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val status = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = (GLES20.glGetShaderInfoLog(sh) ?: "").take(200)
                GLES20.glDeleteShader(sh)
                report { onError("컴파일: $log") }
                return null
            }
            return sh
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            try {
                GLES20.glViewport(0, 0, width, height)
                val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
                Matrix.perspectiveM(proj, 0, 45f, aspect, 0.1f, 20f)
                Matrix.setLookAtM(view, 0, 0f, 0f, 3.5f, 0f, 0f, 0f, 0f, 1f, 0f)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
                requestRender()
            } catch (e: Exception) {
                report { onError("화면크기: ${e.javaClass.simpleName}") }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                val sph = sphereMesh; val cyl = cylinderMesh
                if (!ready || sph == null || cyl == null) return
                GLES20.glUseProgram(program)
                val ld = Lighting.LIGHT_DIR
                GLES20.glUniform3f(uLightDir, ld[0], ld[1], ld[2])
                GLES20.glUniform1f(uAmbient, if (LIGHTING_ON) Math.max(Lighting.AMBIENT, Lighting.MIN_BRIGHTNESS) else 1f)
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glEnableVertexAttribArray(aNormal)
                for (part in parts) {
                    val m = if (part.mesh == MeshKind.CYLINDER) cyl else sph
                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(model, 0, part.tx, part.ty, part.tz)
                    Matrix.scaleM(model, 0, part.sx, part.sy, part.sz)
                    Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                    GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                    // 법선 행렬 = 모델 행렬 역전치의 상단 3x3 (실패하면 단위 행렬)
                    if (Matrix.invertM(inv, 0, model, 0)) {
                        Matrix.transposeM(invT, 0, inv, 0)
                        for (col in 0..2) for (row in 0..2) normalMat[col * 3 + row] = invT[col * 4 + row]
                    } else {
                        for (k in 0..8) normalMat[k] = if (k % 4 == 0) 1f else 0f
                    }
                    GLES20.glUniformMatrix3fv(uNormalMat, 1, false, normalMat, 0)
                    val c = part.color
                    GLES20.glUniform4f(uColor, ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
                    m.vertexBuf.position(0); m.normalBuf.position(0); m.indexBuf.position(0)
                    GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, m.vertexBuf)
                    GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 12, m.normalBuf)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_SHORT, m.indexBuf)
                }
                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aNormal)
                val err = GLES20.glGetError()
                if (err != GLES20.GL_NO_ERROR) { report { onError("그리기: GL 오류 $err") }; return }
                if (!firstFrameReported) { firstFrameReported = true; report { onFirstFrame() } }
            } catch (e: Exception) {
                report { onError("예외: ${e.javaClass.simpleName}") }
            }
        }
    }
}
