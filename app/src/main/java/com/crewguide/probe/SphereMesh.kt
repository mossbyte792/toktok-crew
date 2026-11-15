package com.crewguide.probe

/** 단위 구 메시 생성(순수 로직, Android 클래스 없음). 3D 시험(단계 1a)용 */
class SphereMeshData(val positions: FloatArray, val indices: ShortArray, val stacks: Int, val slices: Int)

object SphereMesh {
    const val MIN_DIV = 3
    const val MAX_VERTICES = 30000   // 인덱스가 Short 범위(32767)를 넘지 않도록 하는 상한

    /** 입력 보정: 3 이상, 정점 수가 상한 미만이 될 때까지 줄인다 */
    // 한쪽이 최소값(3)일 때도 (값+1)*4 < MAX_VERTICES 가 되는 한쪽 최대값. 먼저 이 값으로 자르면 아래 곱셈이 Int를 넘지 않는다
    const val MAX_DIV = MAX_VERTICES / 4 - 2   // 7498

    fun clampDiv(stacks: Int, slices: Int): Pair<Int, Int> {
        var st = stacks.coerceIn(MIN_DIV, MAX_DIV)
        var sl = slices.coerceIn(MIN_DIV, MAX_DIV)
        // Long으로 계산(오버플로 방지)
        while ((st + 1).toLong() * (sl + 1).toLong() >= MAX_VERTICES) {
            if (st >= sl && st > MIN_DIV) st-- else if (sl > MIN_DIV) sl-- else break
        }
        return Pair(st, sl)
    }

    fun vertexCount(stacks: Int, slices: Int): Int = (stacks + 1) * (slices + 1)
    fun indexCount(stacks: Int, slices: Int): Int = stacks * slices * 6

    /** 법선(단위 구라서 위치와 같다). generate와 같은 보정·정점 순서·개수 */
    fun normals(stacks: Int, slices: Int): FloatArray {
        val (st, sl) = clampDiv(stacks, slices)
        val nor = FloatArray(vertexCount(st, sl) * 3)
        var p = 0
        for (i in 0..st) {
            val phi = Math.PI * i / st
            val y = Math.cos(phi)
            val r = Math.sin(phi)
            for (j in 0..sl) {
                val theta = 2.0 * Math.PI * j / sl
                nor[p++] = (r * Math.cos(theta)).toFloat()
                nor[p++] = y.toFloat()
                nor[p++] = (r * Math.sin(theta)).toFloat()
            }
        }
        return nor
    }

    fun generate(stacks: Int, slices: Int): SphereMeshData {
        val (st, sl) = clampDiv(stacks, slices)
        val pos = FloatArray(vertexCount(st, sl) * 3)
        var p = 0
        for (i in 0..st) {
            val phi = Math.PI * i / st            // 0..π (위→아래)
            val y = Math.cos(phi)
            val r = Math.sin(phi)
            for (j in 0..sl) {
                val theta = 2.0 * Math.PI * j / sl
                pos[p++] = (r * Math.cos(theta)).toFloat()
                pos[p++] = y.toFloat()
                pos[p++] = (r * Math.sin(theta)).toFloat()
            }
        }
        val idx = ShortArray(indexCount(st, sl))
        var q = 0
        val row = sl + 1
        for (i in 0 until st) {
            for (j in 0 until sl) {
                val a = i * row + j
                val b = a + row
                idx[q++] = a.toShort(); idx[q++] = b.toShort(); idx[q++] = (a + 1).toShort()
                idx[q++] = (a + 1).toShort(); idx[q++] = b.toShort(); idx[q++] = (b + 1).toShort()
            }
        }
        return SphereMeshData(pos, idx, st, sl)
    }
}
