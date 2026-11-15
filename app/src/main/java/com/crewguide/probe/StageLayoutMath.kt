package com.crewguide.probe

import kotlin.math.max
import kotlin.math.min

/**
 * 무대 높이 예산 계산(순수 함수, Android 클래스 없음). 화면 코드(StageScreen)와 테스트가 같은 식을 쓴다.
 *
 * 예산(dp, 2560x1440에서 dp=px 가정): 상단 바 100 + 자막 140 + 말풍선 꼬리 22 + 컨트롤 예약 220 + 하단 라벨 40 + 여백 40 = 562.
 * 캐릭터 한 변 = min(가로, 높이 − 예약합) (하한 CHAR_MIN_DP). 1440 높이에서 1440 − 562 = 878(약 61%).
 * overlayBudget의 captionDp에는 꼬리를 포함한 StageLayout.CAPTION_BLOCK_DP(178)를 넘긴다.
 * 예약은 dp×밀도로 px 환산한다(dp와 px를 한 번만 환산, 이중 차감 없음).
 */
/** overlayBudget 결과(px). faceCovered = 얼굴 하단(canvas y 62)이 자막 판 위쪽보다 아래에 있어 가려질 수 있음 */
data class OverlayBudget(
    val side: Int,
    val faceBottomY: Float,
    val captionTopY: Float,
    val controlsTopY: Float,
    val faceCovered: Boolean,
)

object StageLayoutMath {
    /** 예약합(dp): 상단 바 + 자막 + 컨트롤 예약 + 하단 라벨 + 여백 */
    fun reserveDp(): Int =
        StageLayout.TOP_BAR_DP + StageLayout.CAPTION_AREA_DP + StageLayout.CAPTION_TAIL_DP + StageLayout.CONTROL_RESERVE_DP +
            StageLayout.SUB_LABEL_DP + StageLayout.MARGIN_DP

    /** 캐릭터 한 변(px). 하한은 CHAR_MIN_DP×밀도 */
    fun characterSize(screenW: Int, screenH: Int, density: Float): Int {
        val byHeight = screenH - (reserveDp() * density).toInt()
        return min(screenW, byHeight).coerceAtLeast((StageLayout.CHAR_MIN_DP * density).toInt())
    }

    /** 캐릭터 영역(상단 바 아래 ~ 자막 위)의 높이(px). 캐릭터는 이 영역의 가운데에 놓인다 */
    fun characterAreaHeight(screenH: Int, density: Float): Int {
        val fixed = ((StageLayout.TOP_BAR_DP + StageLayout.CAPTION_AREA_DP + StageLayout.CAPTION_TAIL_DP + StageLayout.CONTROL_RESERVE_DP + StageLayout.SUB_LABEL_DP) * density).toInt()
        return max(screenH - fixed, 0)
    }

    /**
     * 겹침(오버레이) 구조의 얼굴 가림 예산(순수 함수). 캐릭터 영역은 상단 바 아래부터 맨 아래 라벨 띠 위까지이고
     * (띠 높이 = labelDp, 하단 인셋 포함, 장면과 무관한 고정값),
     * 그 안에서 side = min(W, H − 상단 바 − 띠)인 정사각이 가운데에 그려진다(영역이 side보다 높으면 위아래 (영역−side)/2씩 남는다).
     * 얼굴 하단(canvas y=62)의 화면 y = 상단 바 + (영역−side)/2 + (62−y0)/size·side (기본 뷰포트 FULL이면 0.62·side).
     * 자막 판 위쪽 y = H − (라벨 띠 + 컨트롤 바탕 + 자막 판)·density. 가림 = 얼굴 하단 y > 자막 판 위쪽 y.
     * topBarDp에는 상단 인셋을 포함해 넘기고, labelDp에는 하단 인셋(또는 고정 여백)을 포함해 넘긴다.
     */
    fun overlayBudget(
        screenW: Int, screenH: Int, topBarDp: Int, controlsDp: Int, captionDp: Int, labelDp: Int, density: Float,
        viewport: CharacterViewport = CharacterViewport.FULL,
    ): OverlayBudget {
        val topBarPx = topBarDp * density
        val areaH = max(screenH - topBarPx - labelDp * density, 0f)
        val side = min(screenW.toFloat(), areaH).toInt()
        val offsetY = (areaH - side) / 2f
        val faceBottomY = topBarPx + offsetY + (StageLayout.FACE_BOTTOM_CANVAS_Y - viewport.y0) / viewport.size * side
        val controlsTopY = screenH - (labelDp + controlsDp) * density
        val captionTopY = screenH - (labelDp + controlsDp + captionDp) * density
        return OverlayBudget(side, faceBottomY, captionTopY, controlsTopY, faceBottomY > captionTopY)
    }

    /** 컨트롤 패널 최소 높이(px) = min(화면 높이×14%, CONTROL_PANEL_MAX_DP×밀도). 상한 덕에 overlayBudget의 controlsDp(MAX)와 어긋나지 않는다 */
    fun controlPanelMinPx(screenH: Int, density: Float): Int =
        min(screenH * StageLayout.CONTROL_PANEL_PERCENT / 100, (StageLayout.CONTROL_PANEL_MAX_DP * density).toInt()).coerceAtLeast(0)

    /** 스포트라이트 한 변(px): 캐릭터의 1.15배이되 캐릭터 영역(높이·가로) 밖으로 나가지 않는다 */
    fun spotlightSize(side: Int, areaH: Int, screenW: Int): Int =
        min((side * StageLayout.SPOTLIGHT_SCALE).toInt(), min(areaH, screenW)).coerceAtLeast(0)

    /**
     * 분할창(Pleos Connect [853,90][2535,1300] 등)에서 시스템 바 인셋 이중 차감 방지(순수 함수).
     * 뷰의 화면상 위치(windowTopOnScreen, windowBottomOnScreen)가 이미 상·하단 시스템 바 안쪽으로 떨어져 있으면,
     * 이미 분리된 여백만큼을 원시 systemBars() 인셋에서 빼서 실제 겹치는 분량만 남긴다.
     */
    fun effectiveSystemInsets(
        windowTopOnScreen: Int,
        windowBottomOnScreen: Int,
        displayHeight: Int,
        rawTopInset: Int,
        rawBottomInset: Int,
    ): Pair<Int, Int> {
        val topAlreadyCleared = max(windowTopOnScreen, 0)
        val bottomAlreadyCleared = if (displayHeight > 0 && windowBottomOnScreen > 0) max(displayHeight - windowBottomOnScreen, 0) else 0
        val effTop = max(rawTopInset - topAlreadyCleared, 0)
        val effBottom = max(rawBottomInset - bottomAlreadyCleared, 0)
        return effTop to effBottom
    }

    /** 오른쪽 시스템 바 인셋도 창 오른쪽 끝이 이미 전체 화면 오른쪽에서 떨어져 있으면 중복 차감하지 않는다 */
    fun effectiveRightInset(windowRightOnScreen: Int, displayWidth: Int, rawRightInset: Int): Int {
        val rightAlreadyCleared = if (displayWidth > 0 && windowRightOnScreen > 0) max(displayWidth - windowRightOnScreen, 0) else 0
        return max(rawRightInset - rightAlreadyCleared, 0)
    }
}
