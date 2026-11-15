package com.crewguide.probe

/** 무대 상단 보조 버튼·단계 점 크기 상수(순수 값, Android 클래스 금지). 최소 기준은 DesignRules, 검증은 StageUiTokensTest */
object StageUiTokens {
    const val QUIET_BUTTON_MIN_DP = 76     // 보조 버튼 높이(터치 영역)
    const val QUIET_BUTTON_SP = 24         // 보조 버튼 글자
    const val QUIET_BUTTON_PAD_DP = 24     // 글자 좌우 여백
    const val QUIET_BUTTON_GAP_DP = 23     // [설정]과 [닫기] 사이 간격
    const val QUIET_BUTTON_STROKE_DP = 2   // 윤곽선 두께
    const val DOT_DP = 20                  // 단계 점 지름
    const val DOT_CURRENT_RING_DP = 28     // 현재 점 바깥 링 지름
    const val DOT_LINE_DP = 24             // 점 사이 연결선 길이
    const val DOT_LINE_THICK_DP = 3        // 연결선 두께
    const val DOT_STROKE_DP = 2            // 윤곽 원 윤곽선 두께

    // ---- 버튼 마감(U-2b): 알약 + 아래쪽 두꺼운 가장자리. 디자인 값은 여기 한곳에 모은다 ----
    const val EDGE_DP = DuoTokens.EDGE_DP  // v3 단계 2: 공통 토큰 하나로(값 6 그대로). (시안 2: 5→6) 아래 가장자리 두께(눌리면 본체가 이만큼 내려가 가려진다)
    const val QUIET_EDGE_DP = 0            // 조용한 보조 버튼은 가장자리 없음
    const val EDGE_PERCENT = 78            // 가장자리 색 = 바탕 각 채널 × 78%(정수 반올림)

    // ---- 설정 아이콘 버튼(둥근 톱니바퀴). 글자 없음: 화면 읽기는 contentDescription(SETTINGS_DESC) ----
    const val SETTINGS_BUTTON_DP = 76      // 지름 = 터치 영역(정사각)
    const val SETTINGS_OUTLINE_DP = 3      // 윤곽 두께
    const val SETTINGS_ICON_PERCENT = 58   // 톱니 아이콘 한 변 = 지름의 58%
    const val SETTINGS_DAY_FILL = 0xFFFFFF
    const val SETTINGS_DAY_INK = 0x3B2A22  // 낮 윤곽·아이콘
    // 밤은 기존 Palette 밤 값 재사용: 채움 NIGHT_BTN_OFF_BG, 아이콘 NIGHT_TEXT, 윤곽 NIGHT_CHIP_STROKE
    const val SETTINGS_NIGHT_FILL = Palette.NIGHT_BTN_OFF_BG
    const val SETTINGS_NIGHT_INK = Palette.NIGHT_TEXT
    const val SETTINGS_NIGHT_OUTLINE = Palette.NIGHT_CHIP_STROKE

    fun settingsFill(night: Boolean): Int = if (night) SETTINGS_NIGHT_FILL else SETTINGS_DAY_FILL
    fun settingsInk(night: Boolean): Int = if (night) SETTINGS_NIGHT_INK else SETTINGS_DAY_INK
    fun settingsOutline(night: Boolean): Int = if (night) SETTINGS_NIGHT_OUTLINE else SETTINGS_DAY_INK
    /** 아이콘 한 변(dp, 정수 나눗셈): 76*58/100 = 44 */
    fun settingsIconDp(): Int = SETTINGS_BUTTON_DP * SETTINGS_ICON_PERCENT / 100

    /**
     * 글자 없는 아이콘 버튼(설정 톱니)에서 아이콘의 왼쪽·위 좌표(px): 본체 정중앙. 본체 = 뷰 폭 × (뷰 높이 − 아래 띠 [edgePx]).
     * 뷰 패딩을 쓰지 않는다(패딩이 한쪽에만 있어도 가운데). 남는 픽셀이 홀수면 반 픽셀은 왼쪽·위로 붙는다(차이 0.5px).
     */
    fun iconOnlyLeft(viewW: Int, iconW: Int): Int = Math.floorDiv(viewW - iconW, 2)
    fun iconOnlyTop(viewH: Int, edgePx: Int, iconH: Int): Int = Math.floorDiv(viewH - edgePx - iconH, 2)

    /** 알약 반지름(px) = 본체 높이의 절반(완전 둥근 양 끝) */
    fun pillRadius(bodyHeightPx: Float): Float = bodyHeightPx / 2f

    /**
     * 가장자리 색: 0xRRGGBB 바탕색의 각 채널 × 0.78, 정수 반올림((c*78+50)/100). 알파는 붙이지 않는다.
     * 손계산: 0xA0C4FF → R 160*0.78=124.8→125(7D), G 196*0.78=152.88→153(99), B 255*0.78=198.9→199(C7) = 0x7D99C7
     */
    fun edgeColorOf(rgb: Int): Int {
        fun d(c: Int): Int = (c * EDGE_PERCENT + 50) / 100
        val r = d((rgb shr 16) and 0xFF)
        val g = d((rgb shr 8) and 0xFF)
        val b = d(rgb and 0xFF)
        return (r shl 16) or (g shl 8) or b
    }
}
