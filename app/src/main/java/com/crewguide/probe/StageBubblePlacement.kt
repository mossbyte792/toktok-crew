package com.crewguide.probe

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// 말풍선 '장면별 위치 규칙' 한 곳(순수 로직, Android 클래스 없음). 단위는 모두 화면 px(상수는 dp×밀도).
// 장면마다 달라지는 것은 (점유 사각형 목록, 글자 수)뿐이다. 점유 목록은 controlsOf·chipsVisible·settingsAllowed에서 읽는다.
// 같은 입력이면 같은 결과이므로 같은 장면·같은 문구에서는 위치가 흔들리지 않는다.
// 롤백: StageScreen이 slotFor 대신 chestOnly(...)를 부르게 하면 가슴 위 가운데 고정이 된다.

enum class BubbleSlot { RIGHT_OF_HEAD, LEFT_OF_HEAD, CHEST_CENTER }

/** 꼬리가 나가는 몸체의 변: RIGHT_OF_HEAD는 LEFT(왼쪽 변), LEFT_OF_HEAD는 RIGHT(오른쪽 변), CHEST_CENTER는 UP(윗변) */
enum class TailDir { LEFT, RIGHT, UP }

data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    /** 변만 닿는 경우(맞닿음)는 겹침이 아니다 */
    fun intersects(o: PxRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** 화면 크기와 안전 여백(px). 인셋은 StageScreen의 topPadPx/bottomPadPx/rightPadPx와 같은 값을 넘긴다 */
data class StageGeometry(
    val screenW: Int,
    val screenH: Int,
    val density: Float,
    val topInsetPx: Int,
    val bottomInsetPx: Int,
    val leftInsetPx: Int = 0,
    val rightInsetPx: Int = 0,
    val viewport: CharacterViewport = StageLayout.CHARACTER_VIEWPORT,
    /** v3 반복 5(S02 2영역): 캐릭터 칸 왼쪽 끝(px). 0이면 기존처럼 화면 전체 폭의 가운데 */
    val charLeftPx: Float = 0f,
    /** v3 반복 5: 왼쪽 '고르기' 영역의 오른쪽 끝(px). 0이면 2영역 아님. 좌석 장면에서 말풍선은 이 선을 넘지 않는다 */
    val seatZoneRightPx: Float = 0f,
) {
    val split: Boolean get() = seatZoneRightPx > 0f
}

/** 말풍선이 피해야 할 점유 사각형. 보이지 않는 것은 null */
data class BubbleOccupancy(val cards: PxRect?, val map: PxRect?, val settings: PxRect?, val dots: PxRect, val hand: PxRect?) {
    fun all(): List<PxRect> = listOfNotNull(cards, map, settings, dots, hand)
}

/**
 * 위치 결과. (left, top, maxWidth, maxHeight)는 꼬리 여백을 포함한 말풍선 뷰의 최대 범위, estRect는 추정 줄 수로 본 실제 범위(보수적으로 maxWidth 폭).
 * allowedLines는 TextView.maxLines로 쓴다. fitsHeight가 false이면 위로 올리지 않고 줄 수만 줄인 것이다.
 */
data class BubblePlacement(
    val slot: BubbleSlot,
    val left: Float,
    val top: Float,
    val maxWidth: Float,
    val maxHeight: Float,
    val tail: TailDir,
    val targetX: Float,
    val targetY: Float,
    val estLines: Int,
    val allowedLines: Int,
    val fitsHeight: Boolean,
    val estRect: PxRect,
    val overlapsHand: Boolean,
) {
    /** 글자가 말줄임 없이 들어가는지(추정) */
    val fits: Boolean get() = fitsHeight && estLines <= allowedLines
}

object StageBubblePlacement {
    const val MIN_BUBBLE_W_DP = 260     // 옆 자리의 최소 폭(꼬리 여백 포함)
    const val GAP_DP = 16               // 얼굴·카드·지도·화면 가장자리와의 간격
    const val MAX_LINES = 3
    const val INNER_PAD_DP = 24         // 글자 좌우 안쪽 여백
    const val PAD_V_DP = 20             // 글자 위아래 안쪽 여백(합 40)
    const val LINE_H_DP = 56            // 44sp 한 줄 높이 가정(글꼴 실측 못 함)

    /**
     * 2026-11-08 P1-B 큰 글꼴: 시스템 글꼴 배율(fontScale)이 1보다 크면 최신 풍선이 잘리지 않게 줄 수 상한을 배율만큼 늘린다
     * (글자를 줄이지 않는다). 상한은 [MAX_LINES_LARGE_FONT]. 배율 1 이하이거나 잘리지 않으면 기본 상한 그대로.
     */
    const val MAX_LINES_LARGE_FONT = 6
    fun linesForFontScale(baseAllowed: Int, fontScale: Float, truncated: Boolean): Int {
        val base = baseAllowed.coerceAtLeast(1)
        if (fontScale <= 1.01f || !truncated) return base
        val scaled = kotlin.math.ceil(MAX_LINES * fontScale.toDouble()).toInt()
        return maxOf(base, minOf(MAX_LINES_LARGE_FONT, scaled))
    }

    /** 한 줄 높이(px): 가정값(56dp)과 실제 TextView 줄 높이 중 큰 값. 큰 글꼴에서 풍선 높이가 글자보다 작아 잘리던 문제 */
    fun lineHeightPx(density: Float, actualLineHeightPx: Int): Float = maxOf(LINE_H_DP * density, actualLineHeightPx.toFloat())
    const val EXTRA_BOTTOM_DP = StageBubble.BOTTOM_RESERVE_DP   // 아래 그림자 여유 = 9 (값 불변)
    const val CHEST_GAP_DP = 12
    const val CHEST_MAX_PERCENT = 60
    const val CHEST_MAX_W_DP = 1009
    const val CLOSE_BTN_EST_DP = 216    // [미리보기 닫기] 폭 추정 = 7글자×24 + 좌우 24×2 (보수적)
    const val TOP_BAR_RIGHT_MARGIN_DP = 16
    const val TEXT_DP = 44f             // 한글 한 글자 폭 = 1em으로 보수적으로 가정(sp=dp 가정, 글꼴 배율 무시)
    const val EM_FACTOR = 0.95f

    // 캔버스(0~100) 좌표의 기준 사각형. 손 흔들기: 앞 팔 기본 x 66~84, y 70~99.2(CharacterParts.kt:138), 어깨 pivot (72,84)에서 −10~25도 회전
    const val FACE_X0 = 28f
    const val FACE_X1 = 72f
    const val FACE_Y0 = 18f
    const val FACE_Y1 = 62f             // = StageLayout.FACE_BOTTOM_CANVAS_Y
    const val EYE_Y0 = 36f
    const val EYE_Y1 = 48f
    const val HAND_X0 = 60f
    const val HAND_X1 = 92f
    const val HAND_Y0 = 70f
    const val HAND_Y1 = 104f
    const val MOUTH_X = 50f
    const val MOUTH_Y = 53f

    // ---- 화면 환산 ----
    fun areaTopPx(g: StageGeometry): Float = g.topInsetPx + StageLayout.TOP_BAR_DP * g.density
    fun bandPx(g: StageGeometry): Float = StageLayout.SUB_LABEL_DP * g.density + g.bottomInsetPx
    private fun areaHeightPx(g: StageGeometry): Float = max(g.screenH - areaTopPx(g) - bandPx(g), 0f)
    private fun charAreaWidthPx(g: StageGeometry): Float = max(g.screenW - g.charLeftPx, 0f)
    fun squareSidePx(g: StageGeometry): Float = min(charAreaWidthPx(g), areaHeightPx(g))
    fun squareLeftPx(g: StageGeometry): Float = g.charLeftPx + (charAreaWidthPx(g) - squareSidePx(g)) / 2f

    /** v3 반복 5: 2영역의 왼쪽 '고르기' 영역 전체(좌석 지도·범례·아이 카드). 말풍선 점유로 쓴다 */
    fun seatZoneRect(g: StageGeometry): PxRect = PxRect(0f, areaTopPx(g), g.seatZoneRightPx, g.screenH - bandPx(g))
    fun squareTopPx(g: StageGeometry): Float = areaTopPx(g) + (areaHeightPx(g) - squareSidePx(g)) / 2f

    /** 캔버스 점 → 화면 점. 뷰포트 BUST_UPPER: sx = 정사각 왼쪽 + (x − x0)/size × side */
    fun canvasPoint(g: StageGeometry, cx: Float, cy: Float): BubblePt {
        val side = squareSidePx(g)
        return BubblePt(
            squareLeftPx(g) + (cx - g.viewport.x0) / g.viewport.size * side,
            squareTopPx(g) + (cy - g.viewport.y0) / g.viewport.size * side,
        )
    }

    fun canvasRect(g: StageGeometry, x0: Float, y0: Float, x1: Float, y1: Float): PxRect {
        val a = canvasPoint(g, x0, y0)
        val b = canvasPoint(g, x1, y1)
        return PxRect(a.x, a.y, b.x, b.y)
    }

    fun faceRect(g: StageGeometry): PxRect = canvasRect(g, FACE_X0, FACE_Y0, FACE_X1, FACE_Y1)
    fun eyeRect(g: StageGeometry): PxRect = canvasRect(g, FACE_X0, EYE_Y0, FACE_X1, EYE_Y1)
    fun handRect(g: StageGeometry): PxRect = canvasRect(g, HAND_X0, HAND_Y0, HAND_X1, HAND_Y1)
    fun mouthPoint(g: StageGeometry): BubblePt = canvasPoint(g, MOUTH_X, MOUTH_Y)

    // ---- 점유 사각형 ----
    /** 아이 카드 열 전체의 바깥 사각형(카드 층은 화면 왼쪽 끝에서 시작하고 카드는 왼쪽 여백 16dp부터) */
    fun cardsRect(g: StageGeometry, count: Int): PxRect {
        val d = g.density
        val availDp = ChildCardLayout.availableHeightDp(g.screenH / d, g.topInsetPx / d, g.bottomInsetPx / d)
        val cols = ChildCardLayout.cardColumns(count, availDp)
        val perCol = (count + cols - 1) / cols
        val top = areaTopPx(g) + ChildCardLayout.TOP_GAP_DP * d
        return PxRect(
            ChildCardLayout.MARGIN_LEFT_DP * d, top,
            ChildCardLayout.rightEdgeDp(cols) * d, top + ChildCardLayout.columnHeightDp(perCol) * d,
        )
    }

    fun mapRect(g: StageGeometry): PxRect {
        val d = g.density
        val left = StageSeatMap.leftEdgeDp(g.screenW / d, g.rightInsetPx / d) * d
        val top = StageSeatMap.topEdgeDp(g.topInsetPx / d) * d
        // 2026-11-11 U3: 확대된 지도 + 아래 범례까지 점유
        val wDp = g.screenW / d
        return PxRect(left, top, left + StageSeatMap.displayWidthDp(wDp) * d, StageSeatMap.legendBottomEdgeDp(g.topInsetPx / d, wDp) * d)
    }

    /** 설정 버튼: 미리보기([닫기] 표시)면 [닫기] 추정 폭과 간격만큼 안쪽, 기본 무대(showCloseButton=false)면 오른쪽 여백 바로 안쪽 */
    fun settingsRect(g: StageGeometry, showCloseButton: Boolean = true): PxRect {
        val d = g.density
        val size = StageUiTokens.SETTINGS_BUTTON_DP * d
        val closeOffsetDp = if (showCloseButton) CLOSE_BTN_EST_DP + StageUiTokens.QUIET_BUTTON_GAP_DP else 0
        val right = g.screenW - g.rightInsetPx - (TOP_BAR_RIGHT_MARGIN_DP + closeOffsetDp) * d
        val top = g.topInsetPx + (StageLayout.TOP_BAR_DP * d - size) / 2f
        return PxRect(right - size, top, right, top + size)
    }

    /** 단계 점 줄: 가로 가운데, 세로는 상단 바 가운데 */
    fun dotsRect(g: StageGeometry): PxRect {
        val d = g.density
        val n = StageSteps.NAMES.size
        val w = (n * StageUiTokens.DOT_CURRENT_RING_DP + (n - 1) * StageUiTokens.DOT_LINE_DP) * d
        val h = StageUiTokens.DOT_CURRENT_RING_DP * d
        val left = (g.screenW - w) / 2f
        val top = g.topInsetPx + (StageLayout.TOP_BAR_DP * d - h) / 2f
        return PxRect(left, top, left + w, top + h)
    }

    /** 장면에서 보이는 점유 사각형. 손은 흔드는 YES 장면만(다른 장면은 가만히 내린 팔이라 점유로 넣지 않고 overlapsHand로 보고) */
    fun occupancyFor(
        scene: Scene,
        g: StageGeometry,
        profileCount: Int,
        hasSeatMap: Boolean,
        showCloseButton: Boolean = true,
    ): BubbleOccupancy =
        BubbleOccupancy(
            // 2영역: 카드·지도는 왼쪽 영역 안에 있으므로 그 영역 전체를 하나의 점유로 본다
            cards = if (!g.split && chipsVisible(scene, profileCount)) cardsRect(g, profileCount) else null,
            map = if (g.split) {
                // 2026-11-13: 자동 확인 패널 장면도 왼쪽 영역 전체를 점유로 본다(g.split은 2영역이 실제로 켜졌을 때만 참)
                if ((hasSeatMap && controlsOf(scene, false).showSeats) || chipsVisible(scene, profileCount) || AutoCheckPanelRules.panelScene(scene)) seatZoneRect(g) else null
            } else if (hasSeatMap && controlsOf(scene, false).showSeats) mapRect(g) else null,
            settings = if (settingsAllowed(scene)) settingsRect(g, showCloseButton) else null,
            dots = dotsRect(g),
            hand = if (scene == Scene.YES) handRect(g) else null,
        )

    // ---- 줄 수·높이 추정 ----
    /** 추정 줄 수 = ceil(글자 수 × 44 × 0.95 / (몸체 폭 − 좌우 안쪽 여백 2×24)). 글자가 없으면 1. 폭이 모자라면 Int.MAX_VALUE */
    fun estimateLines(chars: Int, bodyWidthPx: Float, density: Float): Int {
        if (chars <= 0) return 1
        val innerDp = bodyWidthPx / density - 2f * INNER_PAD_DP
        if (innerDp <= 0f) return Int.MAX_VALUE
        return max(1, ceil(chars * TEXT_DP * EM_FACTOR / innerDp).toInt())
    }

    /** 말풍선 뷰 높이 추정(px) = 줄 수×56 + 위아래 여백 40 + 처짐·그림자 9 (+ 위쪽 꼬리 30) */
    fun estimateHeightPx(lines: Int, tailUp: Boolean, density: Float): Float =
        (lines * LINE_H_DP + 2 * PAD_V_DP + EXTRA_BOTTOM_DP + (if (tailUp) StageBubble.TAIL_MAX_DP else 0)) * density

    // ---- 장면별 위치 규칙(우선순위 순서) ----
    /**
     * 1) RIGHT_OF_HEAD: 얼굴 오른쪽+16 ~ (화면 오른쪽 −16 −인셋, 좌석 지도가 보이면 지도 왼쪽 −16), 세로는 얼굴 위(캔버스 y 18)에서 시작
     * 2) LEFT_OF_HEAD: (아이 카드가 보이면 카드 오른쪽 끝+16, 아니면 16+인셋) ~ 얼굴 왼쪽 −16
     *    1·2의 조건: 폭 ≥ 260, 추정 줄 수 ≤ 3, 컨트롤 위(−12)에 들어감, 점유 사각형·얼굴·눈과 안 겹침(손은 YES에서만 점유)
     * 3) CHEST_CENTER: 화면 가운데, 폭 ≤ min(화면 60%, 1009dp), 얼굴 아래(캔버스 y 62)+12 ~ 컨트롤 위−12.
     *    높이가 모자라면 위로 올리지 않고 줄 수만 줄인다(fitsHeight=false 로 알림). 눈·얼굴을 가리지 않는 쪽이 우선이다.
     */
    fun slotFor(
        scene: Scene,
        captionChars: Int,
        g: StageGeometry,
        profileCount: Int,
        hasSeatMap: Boolean,
        showCloseButton: Boolean = true,
    ): BubblePlacement {
        val d = g.density
        val occ = occupancyFor(scene, g, profileCount, hasSeatMap, showCloseButton)
        val face = faceRect(g)
        val eyes = eyeRect(g)
        val hand = handRect(g)
        val mouth = mouthPoint(g)
        val gap = GAP_DP * d
        val bottomLimit = g.screenH - bandPx(g) - StageLayout.CONTROL_PANEL_MAX_DP * d - CHEST_GAP_DP * d

        // 2026-11-13: 자동 확인 패널 장면(2영역)은 머리 옆 칸이 세로로 넉넉해 한 줄 더 허용한다(가슴 가운데로 내려가 몸을 가리고 말줄임되던 것 방지)
        val sideMax = if (g.split && AutoCheckPanelRules.panelScene(scene)) MAX_LINES + 1 else MAX_LINES
        fun side(slot: BubbleSlot, tail: TailDir, l: Float, r: Float): BubblePlacement? {
            val w = r - l
            if (w < MIN_BUBBLE_W_DP * d) return null
            val lines = estimateLines(captionChars, w - StageBubble.TAIL_MAX_DP * d, d)
            if (lines > sideMax) return null
            val rect = PxRect(l, face.top, r, face.top + estimateHeightPx(lines, false, d))
            if (rect.bottom > bottomLimit) return null
            if (rect.intersects(face) || rect.intersects(eyes)) return null
            if (occ.all().any { rect.intersects(it) }) return null
            return BubblePlacement(
                slot, l, face.top, w, (bottomLimit - face.top).coerceAtLeast(0f), tail, mouth.x, mouth.y,
                lines, sideMax, true, rect, rect.intersects(hand),
            )
        }

        // 2영역: 지도는 왼쪽 영역에 있으므로 오른쪽 한계는 화면 가장자리, 왼쪽 한계는 고르기 영역 오른쪽 끝(좌석 장면만)
        val rightLimit = if (g.split) g.screenW - g.rightInsetPx - gap
            else min(g.screenW - g.rightInsetPx - gap, occ.map?.let { it.left - gap } ?: Float.MAX_VALUE)
        side(BubbleSlot.RIGHT_OF_HEAD, TailDir.LEFT, face.right + gap, rightLimit)?.let { return it }
        val leftLimit = if (g.split) (occ.map?.let { it.right + gap } ?: (g.leftInsetPx + gap))
            else occ.cards?.let { it.right + gap } ?: (g.leftInsetPx + gap)
        side(BubbleSlot.LEFT_OF_HEAD, TailDir.RIGHT, leftLimit, face.left - gap)?.let { return it }
        return chestCenter(captionChars, g, hand, mouth, bottomLimit, if (g.split) leftLimit else 0f)
    }

    /** 롤백/폴백용: 가슴 위 가운데 배치만 계산한다 */
    fun chestOnly(captionChars: Int, g: StageGeometry): BubblePlacement {
        val bottomLimit = g.screenH - bandPx(g) - StageLayout.CONTROL_PANEL_MAX_DP * g.density - CHEST_GAP_DP * g.density
        return chestCenter(captionChars, g, handRect(g), mouthPoint(g), bottomLimit)
    }

    private fun chestCenter(captionChars: Int, g: StageGeometry, hand: PxRect, mouth: BubblePt, bottomLimit: Float, minLeft: Float = 0f): BubblePlacement {
        val d = g.density
        val gap = GAP_DP * d
        var width = min(
            min(g.screenW * CHEST_MAX_PERCENT / 100f, CHEST_MAX_W_DP * d),
            g.screenW - 2f * (gap + max(g.leftInsetPx, g.rightInsetPx)),
        )
        var left = (g.screenW - width) / 2f
        if (minLeft > 0f) {
            // 2영역: 캐릭터 입 아래 가운데를 기준으로 하되 고르기 영역(minLeft)과 화면 오른쪽 끝 안에 둔다
            val rightEdge = g.screenW - g.rightInsetPx - gap
            width = min(width, rightEdge - minLeft)
            left = (mouth.x - width / 2f).coerceIn(minLeft, rightEdge - width)
        }
        val top = canvasPoint(g, 0f, FACE_Y1).y + CHEST_GAP_DP * d
        val avail = (bottomLimit - top).coerceAtLeast(0f)
        val lines = estimateLines(captionChars, width, d)
        val fixedPx = (2 * PAD_V_DP + EXTRA_BOTTOM_DP + StageBubble.TAIL_MAX_DP) * d
        val fitLines = floor((avail - fixedPx) / (LINE_H_DP * d)).toInt()
        val fitsHeight = fitLines >= 1
        val allowed = fitLines.coerceIn(1, MAX_LINES)
        val shownLines = min(lines, allowed)
        val rect = PxRect(left, top, left + width, top + estimateHeightPx(shownLines, true, d))
        return BubblePlacement(
            BubbleSlot.CHEST_CENTER, left, top, width, avail, TailDir.UP, mouth.x, mouth.y,
            lines, allowed, fitsHeight, rect, rect.intersects(hand),
        )
    }
}
