package com.crewguide.probe

/**
 * UI 색 역할의 단일 출처(순수 로직, Android 클래스 사용 금지). 주간 값과 야간 값을 같은 역할 이름으로 둔다.
 * 값은 0xRRGGBB(알파 없음). 화면에 칠할 때는 [argb] 또는 [OPAQUE]와 or 해서 쓴다.
 *
 * 6-1단계: 이전 화면 코드에 흩어져 있던 값을 그대로 옮겼다(주간 색은 바꾸지 않음).
 * 6-2단계: 야간 값 추가. 모드(야간 여부 Boolean)로 [scheme]이 고르고, Activity가 onCreate에서 뷰를 만들기 전에
 * [select]로 한 번 정한다. 값은 PaletteTest가 고정하고 대비는 DesignRules.nightPairs 테스트가 확인한다.
 * 캐릭터 그림 색(GuideCharacterView, CharacterOptions)은 범위 밖이다(야간에는 뒤에 밝은 배경판 [Scheme.charPlate]만 깐다).
 */
object Palette {
    /** 알파 0xFF를 붙이는 상수(const 식에서 쓰기 위한 값). */
    const val OPAQUE: Int = 0xFF000000.toInt()

    /** 0xRRGGBB를 불투명 ARGB로 바꾼다. */
    fun argb(rgb: Int): Int = OPAQUE or (rgb and 0xFFFFFF)

    // ---- 주간 값(6-1단계와 동일, 이름·값 변경 금지) ----
    const val SCREEN_BG = 0xFFFFFF          // 화면 배경
    const val TEXT = 0x111111               // 본문 글자
    const val BTN_ON_BG = 0x0D47A1          // 활성·선택 버튼 배경
    const val BTN_ON_TEXT = 0xFFFFFF        // 활성·선택 버튼 글자
    const val BTN_OFF_BG = 0xE0E0E0         // 비활성 버튼 배경
    const val BTN_OFF_TEXT = 0x424242       // 비활성 버튼 글자
    const val CHIP_BG = 0xE8EAF0            // 선택 안 된 칩(버튼) 배경
    const val CHIP_TEXT = 0x111111          // 선택 안 된 칩 글자
    const val CHIP_STROKE = 0x424242        // 선택 안 된 칩 테두리(스와치 테두리 포함)
    const val CARD_BG = 0xF5F5F5            // 예시·시연 카드 배경
    const val CARD_STROKE = 0x757575        // 예시·시연 카드 테두리
    const val SWITCH_BG = 0xEEEEEE          // 음성 스위치 칸 배경
    const val SWITCH_STROKE = 0x424242      // 음성 스위치 칸 테두리
    const val WARN_TEXT = 0xB71C1C          // 경고 글자
    const val STICKER_FILL = 0xFFB300       // 스티커 원 채움
    const val STICKER_STROKE = 0x5D4037     // 스티커 원 테두리
    const val SWATCH_TEXT_DARK = 0x111111   // 스와치 버튼 글자(밝은 색 위)
    const val SWATCH_TEXT_LIGHT = 0xFFFFFF  // 스와치 버튼 글자(어두운 색 위)

    // ---- 야간 값(대비는 DesignRules.nightPairs가 확인) ----
    const val NIGHT_SCREEN_BG = 0x121212
    const val NIGHT_TEXT = 0xE8EAF0
    const val NIGHT_BTN_ON_BG = 0x90CAF9
    const val NIGHT_BTN_ON_TEXT = 0x111111
    const val NIGHT_BTN_OFF_BG = 0x2C2C2C
    const val NIGHT_BTN_OFF_TEXT = 0xBDBDBD
    const val NIGHT_CHIP_BG = 0x2A2E38
    const val NIGHT_CHIP_TEXT = 0xE8EAF0
    const val NIGHT_CHIP_STROKE = 0x9E9E9E
    const val NIGHT_CARD_BG = 0x1E1E1E
    const val NIGHT_CARD_STROKE = 0x9E9E9E
    const val NIGHT_SWITCH_BG = 0x242424
    const val NIGHT_SWITCH_STROKE = 0x9E9E9E
    const val NIGHT_WARN_TEXT = 0xFF8A80
    const val NIGHT_STICKER_FILL = 0xFFB300      // 채움은 주간과 같다(어두운 배경 위에서 충분히 밝음)
    const val NIGHT_STICKER_STROKE = 0x5D4037    // 밝은 채움 위의 어두운 윤곽선(채움 대비 3.0 이상)
    const val NIGHT_SWATCH_TEXT_DARK = 0x111111
    const val NIGHT_SWATCH_TEXT_LIGHT = 0xFFFFFF
    const val NIGHT_CHAR_PLATE = 0xE0E0E0        // 야간 캐릭터 뒤 밝은 둥근 배경판

    /** 한 모드의 색 묶음. [charPlate]가 null이면 캐릭터 배경판을 깔지 않는다(주간). */
    class Scheme(
        val night: Boolean,
        val screenBg: Int, val text: Int,
        val btnOnBg: Int, val btnOnText: Int, val btnOffBg: Int, val btnOffText: Int,
        val chipBg: Int, val chipText: Int, val chipStroke: Int,
        val cardBg: Int, val cardStroke: Int,
        val switchBg: Int, val switchStroke: Int,
        val warnText: Int,
        val stickerFill: Int, val stickerStroke: Int,
        val swatchTextDark: Int, val swatchTextLight: Int,
        val charPlate: Int?,
    ) {
        /** 역할 이름 → 값(순서 고정, 주간/야간 같은 이름 집합) */
        val roles: List<Pair<String, Int>> = listOf(
            "SCREEN_BG" to screenBg,
            "TEXT" to text,
            "BTN_ON_BG" to btnOnBg,
            "BTN_ON_TEXT" to btnOnText,
            "BTN_OFF_BG" to btnOffBg,
            "BTN_OFF_TEXT" to btnOffText,
            "CHIP_BG" to chipBg,
            "CHIP_TEXT" to chipText,
            "CHIP_STROKE" to chipStroke,
            "CARD_BG" to cardBg,
            "CARD_STROKE" to cardStroke,
            "SWITCH_BG" to switchBg,
            "SWITCH_STROKE" to switchStroke,
            "WARN_TEXT" to warnText,
            "STICKER_FILL" to stickerFill,
            "STICKER_STROKE" to stickerStroke,
            "SWATCH_TEXT_DARK" to swatchTextDark,
            "SWATCH_TEXT_LIGHT" to swatchTextLight,
        )
    }

    val DAY: Scheme = Scheme(
        night = false,
        screenBg = SCREEN_BG, text = TEXT,
        btnOnBg = BTN_ON_BG, btnOnText = BTN_ON_TEXT, btnOffBg = BTN_OFF_BG, btnOffText = BTN_OFF_TEXT,
        chipBg = CHIP_BG, chipText = CHIP_TEXT, chipStroke = CHIP_STROKE,
        cardBg = CARD_BG, cardStroke = CARD_STROKE,
        switchBg = SWITCH_BG, switchStroke = SWITCH_STROKE,
        warnText = WARN_TEXT,
        stickerFill = STICKER_FILL, stickerStroke = STICKER_STROKE,
        swatchTextDark = SWATCH_TEXT_DARK, swatchTextLight = SWATCH_TEXT_LIGHT,
        charPlate = null,
    )

    val NIGHT: Scheme = Scheme(
        night = true,
        screenBg = NIGHT_SCREEN_BG, text = NIGHT_TEXT,
        btnOnBg = NIGHT_BTN_ON_BG, btnOnText = NIGHT_BTN_ON_TEXT, btnOffBg = NIGHT_BTN_OFF_BG, btnOffText = NIGHT_BTN_OFF_TEXT,
        chipBg = NIGHT_CHIP_BG, chipText = NIGHT_CHIP_TEXT, chipStroke = NIGHT_CHIP_STROKE,
        cardBg = NIGHT_CARD_BG, cardStroke = NIGHT_CARD_STROKE,
        switchBg = NIGHT_SWITCH_BG, switchStroke = NIGHT_SWITCH_STROKE,
        warnText = NIGHT_WARN_TEXT,
        stickerFill = NIGHT_STICKER_FILL, stickerStroke = NIGHT_STICKER_STROKE,
        swatchTextDark = NIGHT_SWATCH_TEXT_DARK, swatchTextLight = NIGHT_SWATCH_TEXT_LIGHT,
        charPlate = NIGHT_CHAR_PLATE,
    )

    // ---- 무대(StageScreen) 색: 기존 Scheme·roles와 별개로 둔다(기존 값·역할 수 불변) ----
    // U-2c: 낮 배경을 시안 1 값으로 교체(이전 낮 값 E6EEF6 / C9D8E8). 밤 값은 그대로
    const val STAGE_BG_TOP = 0x8EC8FA
    const val STAGE_BG_BOTTOM = 0xD8EEFB
    const val NIGHT_STAGE_BG_TOP = 0x1A2230
    const val NIGHT_STAGE_BG_BOTTOM = 0x121212

    // ---- 승무원 콘셉트 색(2026-11-06 사용자 확정): 기존 Scheme·roles와 별개(역할 수 18 불변) ----
    // 역할 분담(2026-11-06 확정, 초록 제외): 메인 액션 버튼(시작·예 등) = MAIN_BLUE 2563EB / 파랑 구성요소 = CREW_BLUE / 주황 = 포인트(F97316, 밝은 배경 위 선·글자는 어두운 9A3412).
    // 원색(aviation 3B82F6, playfulOrange F97316)은 흰 글자 3.68 / 2.80이라 글자 배경에 못 쓴다 → 아래 보정 값을 쓴다.
    const val MAIN_BLUE = 0x2563EB             // 메인 액션 버튼 채움(흰 글자 5.17). 무대 배경(8EC8FA) 위 비글자는 2.90이라 점·링에는 못 쓴다
    const val CREW_BLUE = 0x1E3A8A             // 파랑 구성요소·단계 점(흰 글자 10 이상, 무대 낮 모든 배경색 위 비글자 3.97 이상; 1D4ED8은 코랄 배경에서 2.57이라 기각)
    const val CREW_BLUE_TEXT = 0xFFFFFF
    const val CREW_BLUE_EDGE = 0x1E3A8A        // 파랑 아래 띠
    const val CREW_ORANGE_FILL = 0xF97316      // 선샤인 오렌지 채움(원색 유지) — 글자는 CREW_ORANGE_TEXT
    const val CREW_ORANGE_TEXT = 0x111111      // 주황 채움 위 글자(6.74)
    const val CREW_ORANGE_EDGE = 0xC2410C      // 주황 채움 아래 띠
    const val CREW_ORANGE_INK = 0x9A3412       // 밝은 배경 위 주황 선·강조 글자(무대 배경 위 4.1 이상)
    const val NIGHT_CREW_BLUE = 0x60A5FA       // 밤 파랑 채움(어두운 글자 111111 7.43)
    const val NIGHT_CREW_BLUE_TEXT = 0x111111
    const val NIGHT_CREW_ORANGE_FILL = 0xFB923C
    const val NIGHT_CREW_ORANGE_TEXT = 0x111111
    const val NIGHT_CREW_ORANGE_INK = 0xFB923C // 밤 배경 위 주황 선(6.0 이상)
    const val CREW_BG = 0xF8FAFC               // slate-50

    // ---- 2026-11-13 자동 확인 패널 '정보 칩'(앉음·보호자 확인함·진행 단계). 대비는 DuoTokensTest가 계산해 검사한다 ----
    // 낮: 글자 1E3A8A / 바탕 DBEAFE = 8.50, 윤곽 1E40AF / 흰 카드 = 8.72. 밤: 글자 DBEAFE / 바탕 1E3A8A = 8.50, 윤곽 93C5FD / 밤 카드 1E293B = 8.11
    const val INFO_CHIP_TEXT = 0x1E3A8A
    const val INFO_CHIP_FILL = 0xDBEAFE
    const val INFO_CHIP_STROKE = 0x1E40AF
    const val NIGHT_INFO_CHIP_TEXT = 0xDBEAFE
    const val NIGHT_INFO_CHIP_FILL = 0x1E3A8A
    const val NIGHT_INFO_CHIP_STROKE = 0x93C5FD

    // ---- 오렌지 포인트 용도별 상수(2026-11-06): [아니오] 버튼 채움, 중요 알림 뱃지. 아직 화면에 적용하지 않은 예약 값 ----
    // 낮 [아니오]: 채움 F97316 + 글자 111111(6.74) + 띠 C2410C(흰 패널 5.18). 채움만으로는 흰 패널과 2.80이라 띠가 경계를 맡는다.
    const val ORANGE_NO_FILL = CREW_ORANGE_FILL
    const val ORANGE_NO_TEXT = CREW_ORANGE_TEXT
    const val ORANGE_NO_EDGE = CREW_ORANGE_EDGE
    // 낮 알림 뱃지: 채움 F97316 + 글자 111111(6.74) + 윤곽 9A3412(흰·slate-50·무대 배경 위 4.1 이상). 윤곽은 필수(채움만 2.80)
    const val ORANGE_BADGE_FILL = CREW_ORANGE_FILL
    const val ORANGE_BADGE_TEXT = CREW_ORANGE_TEXT
    const val ORANGE_BADGE_RING = CREW_ORANGE_INK
    // 밤: 채움 FB923C + 글자 111111(8.3) — 어두운 배경(121212)과 8.3이라 윤곽 불필요. [아니오] 띠는 C2410C(밤 패널 3.4 이상)
    const val NIGHT_ORANGE_NO_FILL = NIGHT_CREW_ORANGE_FILL
    const val NIGHT_ORANGE_NO_TEXT = NIGHT_CREW_ORANGE_TEXT
    const val NIGHT_ORANGE_NO_EDGE = CREW_ORANGE_EDGE
    const val NIGHT_ORANGE_BADGE_FILL = NIGHT_CREW_ORANGE_FILL
    const val NIGHT_ORANGE_BADGE_TEXT = NIGHT_CREW_ORANGE_TEXT

    /** 무대 한 모드의 색: 배경 세로 그라데이션 위/아래, 자막 글자, 단계 점(현재=채움, 나머지=윤곽) */
    // 무대 버튼 색. 낮 [예]·[시작] = 메인 파랑 2563EB + 흰 글자(2026-11-06 사용자 결정으로 옛 초록 2E7D32 대체)
    const val STAGE_YES_FILL = 0x2563EB
    const val STAGE_YES_TEXT = 0xFFFFFF
    const val STAGE_START_FILL = 0x2563EB
    const val STAGE_START_TEXT = 0xFFFFFF
    const val STAGE_GREEN_EDGE = 0x1E40AF      // 낮 메인 파랑 버튼 아래 띠(이름만 옛 초록, 값은 파랑)
    const val STAGE_PANEL_FILL = 0xFFFFFF      // 낮 하단 컨트롤 패널 바탕
    const val NIGHT_STAGE_PANEL_FILL = NIGHT_STAGE_BG_BOTTOM   // 밤 패널 바탕 = 기존 배경 아래색(이전과 같은 색)
    const val NIGHT_STAGE_YES_FILL = 0x90CAF9
    const val NIGHT_STAGE_YES_TEXT = 0x111111
    const val NIGHT_STAGE_START_FILL = 0x6F8FBF
    const val NIGHT_STAGE_START_TEXT = 0x111111

    // ---- 시안 2 마감: [아니오] 회색 알약, [끝내기] 연한 살구 알약, 원형 아이콘 색 ----
    // 2026-11-06 사용자 결정: 초록 제외, 메인 액션 = 에비에이션 블루 2563EB(흰 글자 5.17, 띠 1E40AF), 포인트 = 선샤인 오렌지 F97316.
    // 이름의 GREEN은 옛 이름이 남은 것(값은 파랑 띠). 낮 [예]·[시작]·원형 체크가 이 파랑을 쓴다.
    // 낮 [아니오]: 채움 D5DADF · 글자 111111 · 띠 868D94(띠/흰 패널 ≈3.36; 처음 값 9AA1A8은 ≈2.61이라 진하게)
    const val STAGE_NO_FILL = ORANGE_NO_FILL        // 2026-11-06: 회색 D5DADF → 선샤인 오렌지(글자 111111 6.74, 띠 C2410C, 윤곽 111111)
    const val STAGE_NO_TEXT = ORANGE_NO_TEXT
    const val STAGE_NO_EDGE = ORANGE_NO_EDGE
    // 낮 [끝내기]: 채움 FFD9C2 · 글자 111111 · 띠 B5764F(띠/흰 패널 ≈3.70; 처음 값 C98B63은 ≈2.85라 진하게)
    const val STAGE_FINISH_FILL = 0xFFD9C2
    const val STAGE_FINISH_TEXT = 0x111111
    const val STAGE_FINISH_EDGE = 0xB5764F
    // 낮 원형 아이콘: 원 흰색, 체크 메인 파랑 2563EB, [아니오] 검은 원 + 흰 X
    const val STAGE_ICON_CIRCLE = 0xFFFFFF
    const val STAGE_ICON_CHECK = 0x2563EB
    const val STAGE_ICON_CROSS = 0xFFFFFF         // [아니오] X: 검은 원(111111) 위 흰 X(18.9). 원/주황 채움 6.74라 별도 테두리 불필요
    const val STAGE_ICON_NO_CIRCLE = 0x111111
    const val STAGE_ICON_NO_RING = 0            // 0 = 테두리 없음
    // 밤 [아니오]: 어두운 회색 채움 3A3F4B · 글자 E8EAF0 · 띠 6B7280(띠/밤 패널 ≈3.87)
    const val NIGHT_STAGE_NO_FILL = NIGHT_ORANGE_NO_FILL
    const val NIGHT_STAGE_NO_TEXT = NIGHT_ORANGE_NO_TEXT
    const val NIGHT_STAGE_NO_EDGE = NIGHT_ORANGE_NO_EDGE
    // 밤 [끝내기]: 어두운 따뜻한 채움 5A3E32 · 글자 E8EAF0 · 띠 A0694B(띠/밤 패널 ≈4.12)
    const val NIGHT_STAGE_FINISH_FILL = 0x5A3E32
    const val NIGHT_STAGE_FINISH_TEXT = NIGHT_TEXT
    const val NIGHT_STAGE_FINISH_EDGE = 0xA0694B
    // 밤 원형 아이콘: [예] 원 111111 + 체크 / [아니오] 원 111111 + 흰 X
    // 2026-11-10 4차 결정 C(M11): 밤 [예] 체크(긍정 액션)를 연초록 A5D6A7 → 밤 [예] 채움과 같은 파랑 90CAF9로. 체크/원 111111 대비 10.9
    const val NIGHT_ICON_CIRCLE_YES = 0x111111
    const val NIGHT_ICON_CHECK = NIGHT_STAGE_YES_FILL
    const val NIGHT_ICON_CIRCLE_NO = 0x111111
    const val NIGHT_ICON_CROSS = 0xFFFFFF

    class StageColors(
        val bgTop: Int, val bgBottom: Int, val caption: Int, val dotOn: Int, val dotOff: Int,
        val yesFill: Int, val yesText: Int, val startFill: Int, val startText: Int,
        /** 하단 컨트롤 패널 바탕(U-2c 추가) */
        val panelFill: Int,
        /** [예]·[시작] 계열 아래 띠 색. 0이면 StageUiTokens.edgeColorOf(바탕)로 계산(U-2c 추가) */
        val yesEdge: Int, val startEdge: Int,
        /** 시안 2: [아니오]·[끝내기] 채움/글자/띠 */
        val noFill: Int, val noText: Int, val noEdge: Int,
        val finishFill: Int, val finishText: Int, val finishEdge: Int,
        /** 시안 2: 원형 아이콘 — [예] 원/체크, [아니오] 원/X/원 테두리(0이면 테두리 없음) */
        val yesIconCircle: Int, val yesIconSymbol: Int,
        val noIconCircle: Int, val noIconSymbol: Int, val noIconRing: Int,
        /** 현재 단계 점의 바깥 링 색(승무원 강조 주황). 기본은 dotOn과 같다 */
        val dotRing: Int = dotOn,
    )

    val STAGE_DAY = StageColors(
        STAGE_BG_TOP, STAGE_BG_BOTTOM, TEXT, CREW_BLUE, CHIP_STROKE,
        STAGE_YES_FILL, STAGE_YES_TEXT, STAGE_START_FILL, STAGE_START_TEXT,
        STAGE_PANEL_FILL, STAGE_GREEN_EDGE, STAGE_GREEN_EDGE,
        STAGE_NO_FILL, STAGE_NO_TEXT, STAGE_NO_EDGE,
        STAGE_FINISH_FILL, STAGE_FINISH_TEXT, STAGE_FINISH_EDGE,
        STAGE_ICON_CIRCLE, STAGE_ICON_CHECK,
        STAGE_ICON_NO_CIRCLE, STAGE_ICON_CROSS, STAGE_ICON_NO_RING,
        CREW_ORANGE_INK,
    )
    val STAGE_NIGHT = StageColors(
        NIGHT_STAGE_BG_TOP, NIGHT_STAGE_BG_BOTTOM, NIGHT_TEXT, NIGHT_CREW_BLUE, NIGHT_CHIP_STROKE,
        NIGHT_STAGE_YES_FILL, NIGHT_STAGE_YES_TEXT, NIGHT_STAGE_START_FILL, NIGHT_STAGE_START_TEXT,
        NIGHT_STAGE_PANEL_FILL, 0, 0,
        NIGHT_STAGE_NO_FILL, NIGHT_STAGE_NO_TEXT, NIGHT_STAGE_NO_EDGE,
        NIGHT_STAGE_FINISH_FILL, NIGHT_STAGE_FINISH_TEXT, NIGHT_STAGE_FINISH_EDGE,
        NIGHT_ICON_CIRCLE_YES, NIGHT_ICON_CHECK,
        NIGHT_ICON_CIRCLE_NO, NIGHT_ICON_CROSS, 0,
        NIGHT_CREW_ORANGE_INK,
    )

    fun stage(night: Boolean): StageColors = if (night) STAGE_NIGHT else STAGE_DAY

    /** 모드(야간 여부)로 팔레트를 고르는 순수 함수 */
    fun scheme(night: Boolean): Scheme = if (night) NIGHT else DAY

    /**
     * 화면이 쓰는 현재 팔레트. 기본은 주간. Activity가 onCreate에서 뷰를 만들기 전에 [select]로 한 번 정한다.
     * 메인 스레드에서만 바꾼다(뷰 생성·그리기도 메인 스레드).
     */
    @Volatile var current: Scheme = DAY
        private set

    /** 현재 팔레트를 정하고 돌려준다. */
    fun select(night: Boolean): Scheme { current = scheme(night); return current }

    /** 주간 역할 이름 → 값(중복 이름 검사·테스트용, 6-1단계 호환). */
    val roles: List<Pair<String, Int>> = DAY.roles

    // ---- 2026-11-15 r10e: 스티커 무지개(StickerStyle). 기존 Scheme·roles와 별개(역할 수 불변). colors.xml·design-tokens.json(sticker)이 사본 ----
    // 여섯 색 모두 칸 바탕 대비 3:1 이상(비글자: 테두리·띠·★). 글자는 이 색들 위에 올리지 않는다. 좌석 '이용중' 주황(F97316)은 쓰지 않는다
    const val STICKER_RB_RED = 0xDC2626        // 흰 바탕 4.83
    const val STICKER_RB_ORANGE = 0xEA580C     // 3.56
    const val STICKER_RB_YELLOW = 0xD97706     // 3.19(노랑은 흰 바탕에서 옅어 호박색)
    const val STICKER_RB_GREEN = 0x16A34A      // 3.30(무지개 안의 한 색, 브랜드색 아님)
    const val STICKER_RB_BLUE = 0x2563EB       // 5.17(메인 파랑)
    const val STICKER_RB_PURPLE = 0x7C3AED     // 5.70
    const val NIGHT_STICKER_RB_RED = 0xF87171      // 밤 카드(1E293B) 5.29
    const val NIGHT_STICKER_RB_ORANGE = 0xFDBA74   // 9.0(밤 좌석 주황 FB923C와 다르게, r10e)
    const val NIGHT_STICKER_RB_YELLOW = 0xFACC15   // 9.55
    const val NIGHT_STICKER_RB_GREEN = 0x4ADE80    // 8.40
    const val NIGHT_STICKER_RB_BLUE = 0x60A5FA     // 5.75
    const val NIGHT_STICKER_RB_PURPLE = 0xA78BFA   // 5.38

    private val STICKER_RAINBOW_DAY = listOf(STICKER_RB_RED, STICKER_RB_ORANGE, STICKER_RB_YELLOW, STICKER_RB_GREEN, STICKER_RB_BLUE, STICKER_RB_PURPLE)
    private val STICKER_RAINBOW_NIGHT = listOf(NIGHT_STICKER_RB_RED, NIGHT_STICKER_RB_ORANGE, NIGHT_STICKER_RB_YELLOW,
        NIGHT_STICKER_RB_GREEN, NIGHT_STICKER_RB_BLUE, NIGHT_STICKER_RB_PURPLE)

    /** 스티커 무지개 여섯 색(빨·주·노·초·파·보 순서) */
    fun stickerRainbow(night: Boolean): List<Int> = if (night) STICKER_RAINBOW_NIGHT else STICKER_RAINBOW_DAY
}
