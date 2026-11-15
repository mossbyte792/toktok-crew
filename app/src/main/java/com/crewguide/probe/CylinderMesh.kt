package com.crewguide.probe

/** 원기둥 메시 데이터(순수 로직, Android 클래스 없음). 3D 시험(단계 1b)용 */
class CylinderMeshData(val positions: FloatArray, val indices: ShortArray, val slices: Int)

/**
 * 높이 -0.5~+0.5, 반지름 1, y축이 위쪽인 원기둥.
 * 정점 수 = 4*slices + 6  (옆면 위·아래 고리 2*(slices+1) + 위 뚜껑 (1+(slices+1)) + 아래 뚜껑 (1+(slices+1)))
 * 인덱스 수 = 12*slices   (옆면 slices*6 + 위 뚜껑 slices*3 + 아래 뚜껑 slices*3)
 * 정점 배치: 위 고리 0..s, 아래 고리 s+1..2s+1, 위 중심 2s+2, 위 뚜껑 고리 2s+3..3s+3,
 *           아래 중심 3s+4, 아래 뚜껑 고리 3s+5..4s+5  (s = slices)
 */
object CylinderMesh {
    const val MIN_SLICES = 3
    const val MAX_VERTICES = 30000   // 인덱스가 Short 범위(32767)를 넘지 않도록 하는 상한
    const val MAX_SLICES = (MAX_VERTICES - 1 - 6) / 4   // 7498: 먼저 이 값으로 자르면 아래 계산이 Int를 넘지 않는다

    fun vertexCount(slices: Int): Int = 4 * slices + 6
    fun indexCount(slices: Int): Int = 12 * slices

    /** 입력 보정: 3 이상, 정점 수가 상한 미만이 되도록(Long 비교) 줄인다 */
    fun clampSlices(slices: Int): Int {
        var s = slices.coerceIn(MIN_SLICES, MAX_SLICES)
        while (4L * s + 6L >= MAX_VERTICES.toLong() && s > MIN_SLICES) s--
        return s
    }

    /** 법선. generate와 같은 보정·정점 순서·개수: 옆면 두 고리=(cos,0,sin), 위 중심·위 뚜껑 고리=(0,1,0), 아래 중심·아래 뚜껑 고리=(0,-1,0) */
    fun normals(slices: Int): FloatArray {
        val s = clampSlices(slices)
        val nor = FloatArray(vertexCount(s) * 3)
        var p = 0
        fun put(x: Double, y: Double, z: Double) {
            nor[p++] = x.toFloat(); nor[p++] = y.toFloat(); nor[p++] = z.toFloat()
        }
        fun cos(j: Int) = Math.cos(2.0 * Math.PI * j / s)
        fun sin(j: Int) = Math.sin(2.0 * Math.PI * j / s)
        for (j in 0..s) put(cos(j), 0.0, sin(j))           // 위 고리(옆면)
        for (j in 0..s) put(cos(j), 0.0, sin(j))           // 아래 고리(옆면)
        put(0.0, 1.0, 0.0)                                  // 위 중심
        for (j in 0..s) put(0.0, 1.0, 0.0)                  // 위 뚜껑 고리
        put(0.0, -1.0, 0.0)                                 // 아래 중심
        for (j in 0..s) put(0.0, -1.0, 0.0)                 // 아래 뚜껑 고리
        return nor
    }

    fun generate(slices: Int): CylinderMeshData {
        val s = clampSlices(slices)
        val pos = FloatArray(vertexCount(s) * 3)
        var p = 0
        fun put(x: Double, y: Double, z: Double) {
            pos[p++] = x.toFloat(); pos[p++] = y.toFloat(); pos[p++] = z.toFloat()
        }
        fun cos(j: Int) = Math.cos(2.0 * Math.PI * j / s)
        fun sin(j: Int) = Math.sin(2.0 * Math.PI * j / s)
        for (j in 0..s) put(cos(j), 0.5, sin(j))           // 위 고리
        for (j in 0..s) put(cos(j), -0.5, sin(j))          // 아래 고리
        put(0.0, 0.5, 0.0)                                  // 위 중심
        for (j in 0..s) put(cos(j), 0.5, sin(j))           // 위 뚜껑 고리
        put(0.0, -0.5, 0.0)                                 // 아래 중심
        for (j in 0..s) put(cos(j), -0.5, sin(j))          // 아래 뚜껑 고리

        val idx = ShortArray(indexCount(s))
        var q = 0
        for (j in 0 until s) {   // 옆면
            val a = j; val b = s + 1 + j
            idx[q++] = a.toShort(); idx[q++] = b.toShort(); idx[q++] = (a + 1).toShort()
            idx[q++] = (a + 1).toShort(); idx[q++] = b.toShort(); idx[q++] = (b + 1).toShort()
        }
        val topC = 2 * s + 2; val topR = 2 * s + 3
        for (j in 0 until s) {
            idx[q++] = topC.toShort(); idx[q++] = (topR + j + 1).toShort(); idx[q++] = (topR + j).toShort()
        }
        val botC = 3 * s + 4; val botR = 3 * s + 5
        for (j in 0 until s) {
            idx[q++] = botC.toShort(); idx[q++] = (botR + j).toShort(); idx[q++] = (botR + j + 1).toShort()
        }
        return CylinderMeshData(pos, idx, s)
    }
}
