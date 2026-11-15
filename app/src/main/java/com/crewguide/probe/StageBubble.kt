package com.crewguide.probe

import kotlin.math.hypot
import kotlin.math.min

// 말풍선 자막(시안 2) 순수 로직(Android 클래스 없음): 크기 상수·색·꼬리 좌표 계산.
// 그리기는 BubbleBackground.kt가 한다. 위치 규칙은 StageBubblePlacement.kt 한 곳.
// 롤백: StageBubblePlacement 호출을 CHEST_CENTER 고정으로 바꾸거나, StageScreen의 captionPanel을 bottomStack으로 되돌리고
// BubbleBackground를 이전 모양(윗변 가운데 직선 삼각형 꼬리)으로 되돌린다.

/** 꼬리 삼각형의 한 점(px) */
data class BubblePt(val x: Float, val y: Float)

/** 말풍선 색(0xRRGGBB, 알파 없음) */
data class BubbleColors(val fillRgb: Int, val outlineRgb: Int, val textRgb: Int)

/** 곡선 물방울 꼬리: p1 → (c1 제어) → a, a → b(뭉툭한 끝 4dp 선분), b → (c2 제어) → p2. a·b는 끝의 양쪽 점 */
data class TailCurve(val p1: BubblePt, val c1: BubblePt, val a: BubblePt, val b: BubblePt, val c2: BubblePt, val p2: BubblePt)

object StageBubble {
    const val OUTLINE_DP = 3
    const val CORNER_DP = 28            // 구형(직선 삼각형 시절) 반경. 새 모양은 RADIUS_MAX_DP·cornerRadiusPx를 쓴다(상수는 기존 테스트 때문에 남김)
    const val TAIL_DP = 22              // 꼬리 길이 하한(새 모양: 길이 범위 TAIL_DP~TAIL_MAX_DP)
    const val TAIL_WIDTH_DP = 36        // 꼬리 밑변 폭
    const val TAIL_CENTER_PERCENT = 50  // 구형 tailPoints용 꼬리 중심 위치(%). 새 꼬리는 입 방향으로 정한다

    // ---- 새 모양(말풍선 개선) ----
    // ---- 아이폰 문자 스타일(이번 개선) ----
    /** 얇은 외곽선 옵션. 켜고 끄는 지점은 이 상수 하나(BubbleBackground.draw/rebuild에서 분기). 기본 끔 */
    const val OUTLINE_ENABLED = false
    const val OUTLINE_DP_THIN = 1.5f
    const val CORNER_MAX_DP = 28        // 여러 줄 풍선의 모서리 반경 상한(기존 56에서 줄임: 56이면 2줄 이상에서도 거의 타원이 되어 글자 모서리가 잘릴 위험)
    const val SINGLE_LINE_MAX_DP = 124  // 본체 높이가 이 값 이하면 한 줄로 본다(한 줄 96 = 56+20×2, 두 줄 152의 중간값 124)
    const val TAIL_W_DP = 12            // 꼬리가 몸체 옆(또는 위)으로 나가는 길이(가로 10~14 범위)
    const val TAIL_H_DP = 14            // 꼬리가 몸체 변을 따라 올라가는 길이(세로 12~16 범위)
    const val TAIL_EXTENT_DP = 14       // 뷰 안쪽에 꼬리용으로 비우는 여백 = TAIL_W_DP + 외곽선 절반(0.75) 올림
    const val TAIL_BASE_DP = 4          // 꼬리 시작점이 모서리에서 변 위로 떨어진 거리
    const val BOTTOM_RESERVE_DP = 9     // 뷰 아래쪽 비움(그림자 2dp + 여유). 값을 기존 처짐 6+그림자 3과 같게 두어 StageBubblePlacement 계산이 변하지 않게 함
    const val TAIL_MAX_DP = 30          // StageBubblePlacement의 보수적 예약 폭/높이(위치 규칙을 그대로 두려고 유지). 실제 그림은 TAIL_EXTENT_DP만 쓴다
    const val TIP_CAP_DP = 4            // (구형 tailCurve 용, 그리기에는 쓰지 않음)
    const val TIP_SHIFT_PERCENT = 40    // (구형 tailCurve 용, 그리기에는 쓰지 않음)
    const val SHADOW_DY_DP = 2          // 약한 그림자: 같은 Path를 아래로 옮겨 그린다
    const val SHADOW_ALPHA = 0x1F       // 검정 알파
    const val BUBBLE_IN_SCALE = 0.92f   // 문구가 바뀔 때 크기 변화 시작 배율
    const val BUBBLE_IN_MS = 150L

    /** 모서리 반경(px): 한 줄 높이면 높이/2(완전 알약), 여러 줄이면 min(높이/2, CORNER_MAX_DP×밀도) */
    fun cornerRadiusPx(bodyHeightPx: Float, density: Float): Float =
        if (bodyHeightPx <= SINGLE_LINE_MAX_DP * density) bodyHeightPx / 2f
        else min(bodyHeightPx / 2f, CORNER_MAX_DP * density)

    /**
     * 대화창 풍선 몸체 반경(px) = min(CORNER_DP×밀도, 높이/2). 높이가 40dp보다 낮아도 높이/2로 줄어 모서리가 겹치지 않는다.
     * (위 cornerRadiusPx·SINGLE_LINE_MAX_DP·CORNER_MAX_DP는 이전 알약 규칙이라 이름만 남기고 그리기에서는 쓰지 않는다)
     */
    fun bodyRadiusPx(bodyHeightPx: Float, density: Float): Float =
        min(BubbleStackRules.CORNER_DP * density, bodyHeightPx / 2f)

    /** 꼬리 길이(px) = 목표까지 거리를 TAIL_DP~TAIL_MAX_DP 범위로 자른 값 */
    fun tailLengthPx(distToTargetPx: Float, density: Float): Float =
        distToTargetPx.coerceIn(TAIL_DP * density, TAIL_MAX_DP * density)

    /**
     * 곡선 물방울 꼬리의 점들. p1→p2는 몸체 가장자리를 따라 지나가는 방향의 밑변 두 점, (outX,outY)는 몸체 바깥쪽 단위 방향.
     * 끝 중심 = 밑변 중심 + out×len + lat×shift (lat = p1→p2 단위, shift = 목표 쪽 가로 성분을 ±len×40%로 자른 값).
     * 제어점은 밑변에서 바깥으로 len의 65%, 옆으로는 끝 쪽 거리의 25%만 간 곳이라 밑변 근처에서 부드럽게 좁아진다.
     */
    fun tailCurve(p1: BubblePt, p2: BubblePt, outX: Float, outY: Float, len: Float, target: BubblePt, capHalfPx: Float): TailCurve {
        var lx = p2.x - p1.x
        var ly = p2.y - p1.y
        val bl = hypot(lx, ly)
        if (bl > 0f) { lx /= bl; ly /= bl } else { lx = -outY; ly = outX }
        val mx = (p1.x + p2.x) / 2f
        val my = (p1.y + p2.y) / 2f
        val towards = (target.x - mx) * lx + (target.y - my) * ly
        val maxShift = len * TIP_SHIFT_PERCENT / 100f
        val shift = towards.coerceIn(-maxShift, maxShift)
        val tipX = mx + outX * len + lx * shift
        val tipY = my + outY * len + ly * shift
        val a = BubblePt(tipX - lx * capHalfPx, tipY - ly * capHalfPx)
        val b = BubblePt(tipX + lx * capHalfPx, tipY + ly * capHalfPx)
        val reach = len * 0.65f
        val sideA = ((a.x - p1.x) * lx + (a.y - p1.y) * ly) * 0.25f
        val sideB = ((b.x - p2.x) * lx + (b.y - p2.y) * ly) * 0.25f
        val c1 = BubblePt(p1.x + outX * reach + lx * sideA, p1.y + outY * reach + ly * sideA)
        val c2 = BubblePt(p2.x + outX * reach + lx * sideB, p2.y + outY * reach + ly * sideB)
        return TailCurve(p1, c1, a, b, c2, p2)
    }

    /** 장면 전환 크기 변화를 시작할지: 문구가 바뀌었고 보이며 글자가 있고 시스템 애니메이션이 켜져 있고 잠금 장면이 아닐 때만 */
    fun shouldAnimateIn(captionChanged: Boolean, hasText: Boolean, shown: Boolean, animatorsEnabled: Boolean, scene: Scene): Boolean =
        captionChanged && hasText && shown && animatorsEnabled && scene != Scene.LOCKED

    /** 말풍선 적용 판정: 준비 전·크기 모름이면 SKIP(위치만 미룸), 자막이 비면 HIDE, 아니면 SHOW */
    enum class Apply { SKIP, HIDE, SHOW }

    fun shouldApplyBubble(ready: Boolean, sizeKnown: Boolean, captionEmpty: Boolean): Apply = when {
        !ready || !sizeKnown -> Apply.SKIP
        captionEmpty -> Apply.HIDE
        else -> Apply.SHOW
    }

    /** 위치 적용을 건너뛸지: 직전 결과와 같고 강제가 아닐 때만 건너뛴다 */
    fun needsPlacementApply(force: Boolean, samePlacement: Boolean): Boolean = force || !samePlacement

    const val DAY_FILL = 0x0B63CE      // 흰 글자 대비 약 5.69 (아이폰 #007AFF는 약 4.0이라 쓰지 않음)
    const val DAY_OUTLINE = 0x3B2A22    // 옵션이 켜졌을 때만 사용
    const val DAY_TEXT = 0xFFFFFF
    const val NIGHT_FILL = 0x1976D2     // 흰 글자 대비 약 4.61
    const val NIGHT_OUTLINE = 0x9E9E9E  // 옵션이 켜졌을 때만 사용
    const val NIGHT_TEXT = 0xFFFFFF
    // 보호자 풍선(진한 초록, 흰 글자). 손계산 대비: 2E7D32 L=0.1548 → 1.05/0.2048 = 5.13.
    // 밤 후보 388E3C는 L=0.2052 → 1.05/0.2552 = 4.11로 4.5 미달이라 쓰지 않고 밤도 같은 2E7D32를 쓴다
    const val GUARDIAN_DAY_FILL = 0x2563EB
    const val GUARDIAN_NIGHT_FILL = 0x2563EB

    /**
     * 꼬리 삼각형 세 점(왼쪽 밑, 꼭짓점, 오른쪽 밑). 밑변은 본체 윗변 y=bodyTopPx 위에 있고 꼭짓점은 위쪽(y 작은 쪽)이다.
     * x는 0에서 widthPx까지의 좌표계(호출자가 왼쪽 시작점을 더한다). 중심 x = widthPx × centerPercent / 100.
     */
    fun tailPoints(widthPx: Float, bodyTopPx: Float, tailPx: Float, tailWidthPx: Float, centerPercent: Int): List<BubblePt> {
        val cx = widthPx * centerPercent / 100f
        val half = tailWidthPx / 2f
        return listOf(
            BubblePt(cx - half, bodyTopPx),
            BubblePt(cx, bodyTopPx - tailPx),
            BubblePt(cx + half, bodyTopPx),
        )
    }
}

fun bubbleColorsOf(night: Boolean): BubbleColors =
    if (night) BubbleColors(StageBubble.NIGHT_FILL, StageBubble.NIGHT_OUTLINE, StageBubble.NIGHT_TEXT)
    else BubbleColors(StageBubble.DAY_FILL, StageBubble.DAY_OUTLINE, StageBubble.DAY_TEXT)

/** 말하는 쪽별 풍선 색: CREW는 기존 파랑, GUARDIAN은 진한 초록(외곽선 색은 옵션용으로 CREW와 같음). when에 else 없음 */
fun bubbleColorsOf(speaker: Speaker, night: Boolean): BubbleColors = when (speaker) {
    Speaker.CREW -> bubbleColorsOf(night)
    Speaker.GUARDIAN -> {
        val base = bubbleColorsOf(night)
        BubbleColors(if (night) StageBubble.GUARDIAN_NIGHT_FILL else StageBubble.GUARDIAN_DAY_FILL, base.outlineRgb, base.textRgb)
    }
}
