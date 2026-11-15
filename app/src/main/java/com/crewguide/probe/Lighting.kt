package com.crewguide.probe

/** 간단한 조명 계산(순수 로직, Android 클래스 없음). 3D 시험(단계 1c)용 */
object Lighting {
    /** 방향광이 오는 쪽(월드 기준): 위쪽-앞쪽-왼쪽 */
    private const val RAW_X = -0.4f
    private const val RAW_Y = 0.7f
    private const val RAW_Z = 0.6f

    /** 환경광 비율(0..1) */
    const val AMBIENT = 0.45f

    /** 최소 밝기(0..1) */
    const val MIN_BRIGHTNESS = 0.2f

    /** 정규화된 방향광 방향(길이 1) */
    val LIGHT_DIR: FloatArray = normalize(floatArrayOf(RAW_X, RAW_Y, RAW_Z))

    /** 벡터 정규화. 길이가 0에 가까우면 (0,1,0) */
    fun normalize(v: FloatArray): FloatArray {
        if (v.size < 3) return floatArrayOf(0f, 1f, 0f)
        val len = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        if (len < 1e-6f) return floatArrayOf(0f, 1f, 0f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** 램버트: ambient + (1-ambient)*max(0, n·l), 결과 0..1. n, l은 정규화되어 있다고 본다 */
    fun lambert(nx: Float, ny: Float, nz: Float, lx: Float, ly: Float, lz: Float, ambient: Float): Float {
        val a = ambient.coerceIn(0f, 1f)
        val d = Math.max(0f, nx * lx + ny * ly + nz * lz)
        return (a + (1f - a) * d).coerceIn(0f, 1f)
    }
}
