package com.crewguide.probe

// 무대 왼쪽 위 '아이 얼굴 카드' 순수 로직(Android 클래스 없음): 크기·색·열 감김·위치 계산.
// 카드는 별명과 별명 첫 글자 원(장식)만 보이며(나이·실명·사진·캐릭터 그림 없음, 2026-11-10 4차 결정 A),
// 선택은 파랑 굵은 윤곽 + 체크 배지 + 접근성 설명으로 알린다.
// 상태를 저장하지 않는다. 눌림은 StageActions.onToggleChild(id)로만 전달된다.

/** 카드 한 장의 위치(dp). col·row는 0부터, 열 우선으로 채운다 */
data class CardPos(val index: Int, val col: Int, val row: Int, val xDp: Int, val yDp: Int)

/** 카드 색(0xRRGGBB, 알파 없음) */
data class CardColors(val fill: Int, val outline: Int, val text: Int, val badgeFill: Int, val badgeCheck: Int)

object ChildCardLayout {
    const val CARD_W_DP = 132
    const val CARD_H_DP = 164
    const val GAP_DP = 16               // 카드 사이 간격(세로·가로)
    const val MARGIN_LEFT_DP = 16       // 무대 왼쪽 여백
    const val TOP_GAP_DP = 8            // 상단 바 아래 여백
    const val STROKE_DP = 3
    const val STROKE_SELECTED_DP = 5
    const val RADIUS_DP = 20
    const val AVATAR_DP = 96            // 원형 아바타 배경 지름
    const val INITIAL_DP = 48f          // 원 안 별명 첫 글자 크기(dp 고정, 원 지름의 1/2)
    const val AVATAR_TOP_DP = 12
    const val NAME_GAP_DP = 6
    const val SIDE_PAD_DP = 6
    const val BADGE_DP = 32
    const val BADGE_INSET_DP = 4
    const val BADGE_ICON_PAD_DP = 6
    const val NAME_SP = 24f
    const val NAME_MAX_CODEPOINTS = 8   // ChildProfiles.MAX_NICKNAME_CODEPOINTS와 같은 값(chipLabel이 이미 자른다)
    const val MIN_TOUCH_DP = 76         // DesignRules.MIN_TOUCH와 같은 값

    // 낮 색
    const val DAY_FILL = 0xFFFFFF
    const val DAY_OUTLINE = 0x3B2A22

    /**
     * 아바타 원 배경(장식, 카드 순서별 순환). 의미 구분은 별명과 체크 배지로 한다.
     * 2026-11-10 4차 결정 C(M11): 셋째 연초록 E3F4D7을 중립 slate E2E8F0(CLAUDE.md 보조 채움)으로 바꿨다. 글자 111111 대비 14 이상.
     */
    val AVATAR_BGS: List<Int> = listOf(0xFFE3D3, 0xD6EBFF, 0xE2E8F0, 0xFFF1C9)

    fun avatarBg(index: Int): Int = AVATAR_BGS[((index % AVATAR_BGS.size) + AVATAR_BGS.size) % AVATAR_BGS.size]

    fun strokeDp(selected: Boolean): Int = if (selected) STROKE_SELECTED_DP else STROKE_DP

    /** 낮은 흰 채움 + 어두운 갈색 윤곽, 선택 시 메인 파랑(기존 [예] 색). 밤은 기존 칩 색을 재사용, 선택 시 기존 활성 버튼 색 */
    fun colors(night: Boolean, selected: Boolean): CardColors =
        if (night) {
            CardColors(
                fill = Palette.NIGHT_CHIP_BG,
                outline = if (selected) Palette.NIGHT_BTN_ON_BG else Palette.NIGHT_CHIP_STROKE,
                text = Palette.NIGHT_CHIP_TEXT,
                badgeFill = Palette.NIGHT_BTN_ON_BG,
                badgeCheck = Palette.NIGHT_BTN_ON_TEXT,
            )
        } else {
            CardColors(
                fill = DAY_FILL,
                outline = if (selected) Palette.STAGE_YES_FILL else DAY_OUTLINE,
                text = Palette.TEXT,
                badgeFill = Palette.STAGE_YES_FILL,
                badgeCheck = Palette.STAGE_YES_TEXT,
            )
        }

    /** 접근성 설명: '별명, 선택됨' / '별명, 선택 안 됨' */
    fun description(label: String, selected: Boolean): String =
        if (selected) "$label, 선택됨" else "$label, 선택 안 됨"

    /** 카드 rows장을 한 열에 세로로 쌓았을 때의 높이(dp) */
    fun columnHeightDp(rows: Int): Int = if (rows <= 0) 0 else rows * CARD_H_DP + (rows - 1) * GAP_DP

    /** 한 열에 다 들어가면 1, 넘치면 2(최대 프로필 4명이면 2열 × 2행으로 충분하다) */
    fun cardColumns(count: Int, availableHeightDp: Int): Int =
        if (count <= 1 || columnHeightDp(count) <= availableHeightDp) 1 else 2

    /** 카드별 위치(dp, 카드 층의 왼쪽 위 기준). 열 우선: 첫 열을 위에서 아래로 채운 뒤 둘째 열 */
    fun cardPositions(count: Int, availableHeightDp: Int): List<CardPos> {
        if (count <= 0) return emptyList()
        val cols = cardColumns(count, availableHeightDp)
        val perCol = (count + cols - 1) / cols
        return List(count) { i ->
            val col = i / perCol
            val row = i % perCol
            CardPos(i, col, row, MARGIN_LEFT_DP + col * (CARD_W_DP + GAP_DP), TOP_GAP_DP + row * (CARD_H_DP + GAP_DP))
        }
    }

    /** 카드 열들의 오른쪽 끝 x(dp) = 왼쪽 여백 + 열×폭 + (열−1)×간격 (2열이면 16+264+16 = 296) */
    fun rightEdgeDp(cols: Int): Int = MARGIN_LEFT_DP + cols * CARD_W_DP + (cols - 1).coerceAtLeast(0) * GAP_DP

    /**
     * 카드가 쓸 수 있는 높이(dp): 화면 − 상단 인셋 − 상단 바 − 위 여백 − (보조 라벨 띠 + 하단 인셋 + 컨트롤 바탕 최대 높이 + 자막 판).
     * 컨트롤 바탕·자막 판은 최대값 기준이라 실제보다 보수적이다(카드가 그 판과 겹치지 않는 쪽).
     */
    fun availableHeightDp(screenHDp: Float, topInsetDp: Float, bottomInsetDp: Float): Int {
        val reserved = StageLayout.SUB_LABEL_DP + bottomInsetDp + StageLayout.CONTROL_PANEL_MAX_DP + StageLayout.CAPTION_BLOCK_DP
        return (screenHDp - topInsetDp - StageLayout.TOP_BAR_DP - TOP_GAP_DP - reserved).toInt()
    }

    /** 캐릭터 정사각의 왼쪽 끝 x(dp, 가운데 놓기): (화면 폭 − side)/2, side = min(폭, 캐릭터 영역 높이) */
    fun characterSquareLeftDp(screenWDp: Float, areaHDp: Float): Float =
        (screenWDp - minOf(screenWDp, areaHDp)) / 2f
}
