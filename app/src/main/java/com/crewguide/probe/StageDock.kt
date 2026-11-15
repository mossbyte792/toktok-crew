package com.crewguide.probe

/**
 * 2차 U2 '하단 통합 도크' 크기 규칙(순수 로직, Android 클래스 없음).
 * 준비 단계(PICK·READY)에서 아이 얼굴 카드·보조 칩·[보호자 확인 시작]을 한 줄로 모은다.
 * 도크 전체 높이(줄 높이 + 위아래 안쪽 여백 + 아래 띄움)는 말풍선·카드 배치가 비워 두는 CONTROL_PANEL_MAX_DP를 넘지 않는다.
 * 좌석 지도는 타일 76dp 터치 기준 때문에 도크에 넣으면 높이 예산을 넘으므로 오른쪽 위 카드에 그대로 둔다.
 */
object StageDock {
    const val SIDE_MARGIN_DP = 24
    const val BOTTOM_MARGIN_DP = 8

    /** 도크 한 줄의 높이(dp): 카드가 있으면 카드 높이, 없으면 [시작]·칩 중 큰 값 */
    fun rowHeightDp(hasCards: Boolean): Int {
        val start = specOf(ButtonKind.PRIMARY_START).heightDp
        val base = maxOf(start, StageControlLayout.CHIP_MIN_DP)
        return if (hasCards) maxOf(base, ChildCardLayout.CARD_H_DP) else base
    }

    /** 도크 판이 차지하는 전체 높이(dp) */
    fun panelHeightDp(hasCards: Boolean): Int =
        rowHeightDp(hasCards) + 2 * StageLayout.CONTROL_PANEL_PAD_DP + BOTTOM_MARGIN_DP

    /** 아이 카드 n장 + 보조 칩 폭 추정(dp): 카드 폭×n + 간격. 칩은 최소 터치 폭만 센다 */
    fun stripWidthDp(cards: Int, hasChip: Boolean): Int {
        val n = cards.coerceAtLeast(0)
        var w = n * ChildCardLayout.CARD_W_DP + (n - 1).coerceAtLeast(0) * StageControlLayout.CHIP_GAP_DP
        if (hasChip) w += (if (n > 0) StageControlLayout.CHIP_GAP_DP else 0) + DesignRules.MIN_TOUCH
        return w
    }
}
