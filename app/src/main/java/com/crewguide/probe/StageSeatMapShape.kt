package com.crewguide.probe

// 좌석 지도 장식(차체·바퀴·앞유리·사이드미러·뒷유리선) 좌표 순수 로직(Android 클래스 없음). 단위 dp, 지도 왼쪽 위 기준, 332×240.
// View는 이 좌표에 밀도를 곱해 Path를 만든다. 장식은 타일보다 먼저(아래) 그려지고 터치를 받지 않는다.
// 지도는 좌석 고르기 용도이며 벨트 상태 표시가 아니다.
// 롤백: StageSeatMapView.onDraw의 장식 그리기를 이전 판(둥근 사각형 addRoundRect)으로 되돌리거나, seatSlotsFor가 null을 돌려주게 한다.

object StageSeatMapShape {
    data class P(val x: Float, val y: Float)

    sealed class Seg {
        data class Move(val p: P) : Seg()
        data class Line(val p: P) : Seg()
        data class Cubic(val c1: P, val c2: P, val e: P) : Seg()
        object Close : Seg()
    }

    /** 3차 곡선으로 사분원을 근사하는 상수 */
    const val KAPPA = 0.5523f

    const val HOOD_INSET_DP = 8          // 윗변 폭을 양옆으로 줄이는 양
    const val SHOULDER_R_DP = 44         // 어깨 반경(세로 44, 가로 44 + 8)
    const val TRUNK_R_DP = 44            // 트렁크 모서리 가로 반경
    // 트렁크 모서리 세로 반경: 뒷바퀴 아랫변(204)이 곡선 시작(윤곽 중심선 기준 238.5 − 32 = 206.5) 위에 있도록 세로를 32로 눌렀다(가로 44 유지)
    const val TRUNK_RY_DP = 32

    /** 윤곽 중심선 좌표(선 두께 3의 절반 1.5를 안쪽으로 둔다) */
    fun left(): Float = StageSeatMap.WHEEL_OUT_DP + StageSeatMap.BODY_STROKE_DP / 2f      // 6.5
    fun right(): Float = StageSeatMap.widthDp() - StageSeatMap.WHEEL_OUT_DP - StageSeatMap.BODY_STROKE_DP / 2f   // 325.5
    fun top(): Float = StageSeatMap.BODY_STROKE_DP / 2f                                   // 1.5
    fun bottom(): Float = StageSeatMap.heightDp() - StageSeatMap.BODY_STROKE_DP / 2f      // 238.5

    /** 직선 구간(옆면) y 범위: 어깨 끝 ~ 트렁크 시작 (중심선 기준) = 45.5 ~ 206.5 */
    fun sideStraightTop(): Float = top() + SHOULDER_R_DP
    fun sideStraightBottom(): Float = bottom() - TRUNK_RY_DP

    /** 차체: 좌우 대칭 닫힌 Path 하나. 시계 방향, 윗변 왼쪽 시작 */
    fun bodySegments(): List<Seg> {
        val l = left(); val r = right(); val t = top(); val b = bottom()
        val hx = (HOOD_INSET_DP + SHOULDER_R_DP).toFloat()   // 52: 윗변이 시작하는 x 거리
        val sy = SHOULDER_R_DP.toFloat()
        val tx = TRUNK_R_DP.toFloat()
        val ty = TRUNK_RY_DP.toFloat()
        val k = KAPPA
        return listOf(
            Seg.Move(P(l + hx, t)),
            Seg.Line(P(r - hx, t)),
            Seg.Cubic(P(r - hx + k * hx, t), P(r, t + sy - k * sy), P(r, t + sy)),
            Seg.Line(P(r, b - ty)),
            Seg.Cubic(P(r, b - ty + k * ty), P(r - tx + k * tx, b), P(r - tx, b)),
            Seg.Line(P(l + tx, b)),
            Seg.Cubic(P(l + tx - k * tx, b), P(l, b - ty + k * ty), P(l, b - ty)),
            Seg.Line(P(l, t + sy)),
            Seg.Cubic(P(l, t + sy - k * sy), P(l + hx - k * hx, t), P(l + hx, t)),
            Seg.Close,
        )
    }

    /** 바퀴 4개(앞왼, 앞오른, 뒤왼, 뒤오른) */
    fun wheels(): List<StageSeatMap.DecoBox> = listOf(
        StageSeatMap.wheelBox(true, true), StageSeatMap.wheelBox(true, false),
        StageSeatMap.wheelBox(false, true), StageSeatMap.wheelBox(false, false),
    )

    /** 앞유리 사다리꼴 꼭짓점(왼위, 오른위, 오른아래, 왼아래) */
    fun glassPoints(): List<P> {
        val cx = StageSeatMap.widthDp() / 2f
        val th = StageSeatMap.GLASS_TOP_HALF_DP.toFloat(); val bh = StageSeatMap.GLASS_BOTTOM_HALF_DP.toFloat()
        val ty = StageSeatMap.GLASS_TOP_Y_DP.toFloat(); val by = StageSeatMap.GLASS_BOTTOM_Y_DP.toFloat()
        return listOf(P(cx - th, ty), P(cx + th, ty), P(cx + bh, by), P(cx - bh, by))
    }

    /** 사이드미러 타원 상자(왼, 오른). 차체 바깥 가장자리(x 5 / 327)에서 3dp 나오고 y 46~58(직선 구간) */
    fun mirrors(): List<StageSeatMap.DecoBox> = listOf(StageSeatMap.mirrorBox(true), StageSeatMap.mirrorBox(false))

    /** 뒷유리선 2차 곡선: 시작, 제어점, 끝 */
    fun rearLine(): Triple<P, P, P> {
        val cx = StageSeatMap.widthDp() / 2f
        val y = StageSeatMap.REAR_LINE_Y_DP.toFloat()
        val h = StageSeatMap.REAR_LINE_HALF_DP.toFloat()
        return Triple(P(cx - h, y), P(cx, y + StageSeatMap.REAR_LINE_DIP_DP), P(cx + h, y))
    }
}
