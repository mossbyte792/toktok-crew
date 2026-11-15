package com.crewguide.probe

/**
 * 디자인 점검용 순수 로직(Android 클래스 사용 금지). 화면 모양에는 영향을 주지 않는다.
 *
 * 대비비 공식(WCAG 2.x):
 *  - 채널값 c = 0..255 → s = c/255
 *  - 선형화: s <= 0.03928 이면 s/12.92, 아니면 ((s+0.055)/1.055)^2.4
 *    (0.04045 쓰는 판본도 있으나 8비트 값에서는 결과가 같다)
 *  - 상대 휘도 L = 0.2126*R + 0.7152*G + 0.0722*B
 *  - 대비비 = (밝은쪽 L + 0.05) / (어두운쪽 L + 0.05)
 */
object DesignRules {

    // ---- 기준 상수 ----
    // 주의: 아래 수치는 창구 문서에 "공식 sizing 문서 기준"이라고 적혀 있으나,
    // 공식 원문과 대조하기 전이다(원문 대조 전). 대조 후 다르면 이 값을 고친다.
    const val MIN_TOUCH = 76          // 터치 최소(화면 코드에서는 dp 단위로 쓴다)
    const val MIN_TARGET_GAP = 23     // 목표 사이 간격 최소
    const val MIN_CORE_TEXT = 32      // 핵심 글자 최소
    const val MIN_AUX_TEXT = 24       // 보조 글자 최소
    const val MIN_TEXT_CONTRAST = 4.5 // 일반 글자 대비
    const val MIN_LARGE_OR_NON_TEXT_CONTRAST = 3.0 // 큰 글자·비글자 요소(아이콘·테두리) 대비

    /** 0xRRGGBB(상위 알파 바이트는 무시)의 sRGB 상대 휘도. */
    fun relativeLuminance(rgb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = lin((rgb shr 16) and 0xFF)
        val g = lin((rgb shr 8) and 0xFF)
        val b = lin(rgb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** 두 색의 대비비(1.0 ~ 21.0). 인자 순서와 무관하다. */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val a = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    enum class Kind(val minContrast: Double) {
        TEXT(MIN_TEXT_CONTRAST),
        NON_TEXT(MIN_LARGE_OR_NON_TEXT_CONTRAST)
    }

    class ColorPair(val name: String, val fg: Int, val bg: Int, val kind: Kind) {
        val ratio: Double get() = contrastRatio(fg, bg)
        val passes: Boolean get() = ratio >= kind.minContrast
    }

    // ---- 현재 앱이 실제로 쓰는 색 조합 ----
    // 값은 Palette(주간)를 직접 참조한다(수동 동기화 없음). 용도 이름·판정 기준은 이전과 같다.
    // 미포함: GuideCharacterView/CharacterOptions의 그림용 색(장식 그래픽), 스와치 버튼의 휘도 기반 글자색.
    val currentPairs: List<ColorPair> = listOf(
        ColorPair("본문 글자 / 흰 배경(Readability TEXT·BG)", Palette.TEXT, Palette.SCREEN_BG, Kind.TEXT),
        ColorPair("활성·선택 버튼 글자 / 진한 파랑(BTN_TEXT·BTN_BG, 프로필 선택, 초기화)", Palette.BTN_ON_TEXT, Palette.BTN_ON_BG, Kind.TEXT),
        ColorPair("비활성 버튼 글자 / 연회색(BTN_OFF_TEXT·BTN_OFF_BG)", Palette.BTN_OFF_TEXT, Palette.BTN_OFF_BG, Kind.TEXT),
        ColorPair("프로필 미선택 버튼 글자 / 연청회(E8EAF0)", Palette.CHIP_TEXT, Palette.CHIP_BG, Kind.TEXT),
        ColorPair("프로필 미선택 버튼 테두리 / 연청회", Palette.CHIP_STROKE, Palette.CHIP_BG, Kind.NON_TEXT),
        ColorPair("프로필 미선택 버튼 테두리 / 흰 배경", Palette.CHIP_STROKE, Palette.SCREEN_BG, Kind.NON_TEXT),
        ColorPair("예시·시연 카드 글자 / 카드 배경(F5F5F5)", Palette.TEXT, Palette.CARD_BG, Kind.TEXT),
        ColorPair("예시·시연 카드 테두리 / 카드 배경(F5F5F5)", Palette.CARD_STROKE, Palette.CARD_BG, Kind.NON_TEXT),
        ColorPair("예시·시연 카드 테두리 / 흰 배경", Palette.CARD_STROKE, Palette.SCREEN_BG, Kind.NON_TEXT),
        ColorPair("예시 카드 경고 문구 / 카드 배경(B71C1C·F5F5F5)", Palette.WARN_TEXT, Palette.CARD_BG, Kind.TEXT),
        ColorPair("음성 스위치 칸 글자 / 칸 배경(EEEEEE)", Palette.TEXT, Palette.SWITCH_BG, Kind.TEXT),
        ColorPair("음성 스위치 칸 테두리 / 칸 배경(EEEEEE)", Palette.SWITCH_STROKE, Palette.SWITCH_BG, Kind.NON_TEXT),
        // 스티커 원: 채움은 그대로 두고 어두운 테두리를 더해 흰 배경과의 경계를 확보
        ColorPair("스티커 표시 원 테두리(5D4037) / 흰 배경", Palette.STICKER_STROKE, Palette.SCREEN_BG, Kind.NON_TEXT),
        ColorPair("스티커 표시 원 테두리(5D4037) / 채움(FFB300)", Palette.STICKER_STROKE, Palette.STICKER_FILL, Kind.NON_TEXT),
    ) + StatusStyle.Kind.values().map {
        ColorPair("상태 표시 ${it.label} 글자 / 배경", it.fg, it.bg, Kind.TEXT)
    }

    // ---- 야간 팔레트에서 실제로 쓰는 색 조합(글자 4.5 이상, 비글자 3.0 이상) ----
    // 비활성 버튼 배경 / 화면 배경은 비활성 구성요소라 기준에서 제외한다(주간 E0E0E0/흰색도 같은 상태).
    // 스티커 윤곽선은 어두운 배경과 대비가 낮아 화면 배경 대신 채움과의 대비로 확인하고, 채움은 화면 배경과 비교한다.
    val nightPairs: List<ColorPair> = Palette.NIGHT.let { n ->
        listOf(
            ColorPair("야간 본문 글자 / 화면 배경", n.text, n.screenBg, Kind.TEXT),
            ColorPair("야간 활성·선택 버튼 글자 / 버튼 배경", n.btnOnText, n.btnOnBg, Kind.TEXT),
            ColorPair("야간 활성·선택 버튼 배경 / 화면 배경", n.btnOnBg, n.screenBg, Kind.NON_TEXT),
            ColorPair("야간 비활성 버튼 글자 / 버튼 배경", n.btnOffText, n.btnOffBg, Kind.TEXT),
            ColorPair("야간 칩 글자 / 칩 배경", n.chipText, n.chipBg, Kind.TEXT),
            ColorPair("야간 스와치·옵션 버튼 흰 글자 / 칩 배경", n.swatchTextLight, n.chipBg, Kind.TEXT),
            ColorPair("야간 칩 테두리 / 칩 배경", n.chipStroke, n.chipBg, Kind.NON_TEXT),
            ColorPair("야간 칩 테두리 / 화면 배경", n.chipStroke, n.screenBg, Kind.NON_TEXT),
            ColorPair("야간 카드 글자 / 카드 배경", n.text, n.cardBg, Kind.TEXT),
            ColorPair("야간 카드 테두리 / 카드 배경", n.cardStroke, n.cardBg, Kind.NON_TEXT),
            ColorPair("야간 카드 테두리 / 화면 배경", n.cardStroke, n.screenBg, Kind.NON_TEXT),
            ColorPair("야간 경고 글자 / 카드 배경", n.warnText, n.cardBg, Kind.TEXT),
            ColorPair("야간 경고 글자 / 화면 배경", n.warnText, n.screenBg, Kind.TEXT),
            ColorPair("야간 스위치 칸 글자 / 칸 배경", n.text, n.switchBg, Kind.TEXT),
            ColorPair("야간 스위치 칸 테두리 / 칸 배경", n.switchStroke, n.switchBg, Kind.NON_TEXT),
            ColorPair("야간 스위치 칸 테두리 / 화면 배경", n.switchStroke, n.screenBg, Kind.NON_TEXT),
            ColorPair("야간 스티커 원 채움 / 화면 배경", n.stickerFill, n.screenBg, Kind.NON_TEXT),
            ColorPair("야간 스티커 원 테두리 / 채움", n.stickerStroke, n.stickerFill, Kind.NON_TEXT),
        ) + CharacterOptions.HAIR.map {
            // 캐릭터 머리색이 배경판 위에서 구분되는지(배경판은 야간 전용)
            ColorPair("야간 캐릭터 배경판 / 머리색 ${it.name}", it.argb and 0xFFFFFF, n.charPlate!!, Kind.NON_TEXT)
        }
    }

    // ---- 무대 색 조합(자막 글자 4.5 이상, 단계 점 3.0 이상; 배경 위·아래 두 색 모두) ----
    val stagePairs: List<ColorPair> = listOf(
        "주간" to Palette.STAGE_DAY, "야간" to Palette.STAGE_NIGHT,
    ).flatMap { (label, c) ->
        listOf(
            ColorPair("무대 $label 자막 / 배경 위", c.caption, c.bgTop, Kind.TEXT),
            ColorPair("무대 $label 자막 / 배경 아래", c.caption, c.bgBottom, Kind.TEXT),
            ColorPair("무대 $label 현재 점 / 배경 위", c.dotOn, c.bgTop, Kind.NON_TEXT),
            ColorPair("무대 $label 현재 점 / 배경 아래", c.dotOn, c.bgBottom, Kind.NON_TEXT),
            ColorPair("무대 $label 나머지 점 윤곽 / 배경 위", c.dotOff, c.bgTop, Kind.NON_TEXT),
            ColorPair("무대 $label 나머지 점 윤곽 / 배경 아래", c.dotOff, c.bgBottom, Kind.NON_TEXT),
        )
    }

    // ---- 승무원 콘셉트 색 조합(파랑 구성요소·주황 강조). 글자 4.5 이상, 비글자 3.0 이상 ----
    val crewPairs: List<ColorPair> = listOf(
        ColorPair("메인 액션 파랑 글자 / 채움(2563EB)", Palette.STAGE_YES_TEXT, Palette.MAIN_BLUE, Kind.TEXT),
        ColorPair("메인 액션 파랑 띠(1E40AF) / 흰 패널", Palette.STAGE_GREEN_EDGE, Palette.STAGE_PANEL_FILL, Kind.NON_TEXT),
        ColorPair("승무원 파랑 버튼 글자 / 채움", Palette.CREW_BLUE_TEXT, Palette.CREW_BLUE, Kind.TEXT),
        ColorPair("승무원 파랑 띠 / 화면 배경(slate-50)", Palette.CREW_BLUE_EDGE, Palette.CREW_BG, Kind.NON_TEXT),
        ColorPair("승무원 주황 버튼 글자 / 채움", Palette.CREW_ORANGE_TEXT, Palette.CREW_ORANGE_FILL, Kind.TEXT),
        ColorPair("승무원 주황 띠 / 화면 배경(slate-50)", Palette.CREW_ORANGE_EDGE, Palette.CREW_BG, Kind.NON_TEXT),
        ColorPair("승무원 주황 글자 / 화면 배경(slate-50)", Palette.CREW_ORANGE_INK, Palette.CREW_BG, Kind.TEXT),
        ColorPair("승무원 본문 글자 / 화면 배경(slate-50)", Palette.TEXT, Palette.CREW_BG, Kind.TEXT),
        ColorPair("야간 승무원 파랑 버튼 글자 / 채움", Palette.NIGHT_CREW_BLUE_TEXT, Palette.NIGHT_CREW_BLUE, Kind.TEXT),
        ColorPair("야간 승무원 주황 버튼 글자 / 채움", Palette.NIGHT_CREW_ORANGE_TEXT, Palette.NIGHT_CREW_ORANGE_FILL, Kind.TEXT),
        ColorPair("야간 승무원 주황 선 / 화면 배경", Palette.NIGHT_CREW_ORANGE_INK, Palette.NIGHT_SCREEN_BG, Kind.NON_TEXT),
        ColorPair("오렌지 [아니오] 글자 / 채움", Palette.ORANGE_NO_TEXT, Palette.ORANGE_NO_FILL, Kind.TEXT),
        ColorPair("오렌지 [아니오] 띠 / 흰 패널", Palette.ORANGE_NO_EDGE, Palette.STAGE_PANEL_FILL, Kind.NON_TEXT),
        ColorPair("오렌지 뱃지 글자 / 채움", Palette.ORANGE_BADGE_TEXT, Palette.ORANGE_BADGE_FILL, Kind.TEXT),
        ColorPair("오렌지 뱃지 윤곽 / 흰 패널", Palette.ORANGE_BADGE_RING, Palette.STAGE_PANEL_FILL, Kind.NON_TEXT),
        ColorPair("오렌지 뱃지 윤곽 / 화면 배경(slate-50)", Palette.ORANGE_BADGE_RING, Palette.CREW_BG, Kind.NON_TEXT),
        ColorPair("오렌지 뱃지 윤곽 / 무대 배경 위", Palette.ORANGE_BADGE_RING, Palette.STAGE_BG_TOP, Kind.NON_TEXT),
        ColorPair("야간 오렌지 [아니오] 글자 / 채움", Palette.NIGHT_ORANGE_NO_TEXT, Palette.NIGHT_ORANGE_NO_FILL, Kind.TEXT),
        ColorPair("야간 오렌지 [아니오] 띠 / 밤 패널", Palette.NIGHT_ORANGE_NO_EDGE, Palette.NIGHT_STAGE_PANEL_FILL, Kind.NON_TEXT),
        ColorPair("야간 오렌지 [아니오] 채움 / 밤 패널", Palette.NIGHT_ORANGE_NO_FILL, Palette.NIGHT_STAGE_PANEL_FILL, Kind.NON_TEXT),
        ColorPair("야간 오렌지 뱃지 글자 / 채움", Palette.NIGHT_ORANGE_BADGE_TEXT, Palette.NIGHT_ORANGE_BADGE_FILL, Kind.TEXT),
        ColorPair("야간 오렌지 뱃지 채움 / 화면 배경", Palette.NIGHT_ORANGE_BADGE_FILL, Palette.NIGHT_SCREEN_BG, Kind.NON_TEXT),
    ) + listOf("주간" to Palette.STAGE_DAY, "야간" to Palette.STAGE_NIGHT).flatMap { (label, c) ->
        listOf(
            ColorPair("무대 $label 현재 점 링(주황) / 배경 위", c.dotRing, c.bgTop, Kind.NON_TEXT),
            ColorPair("무대 $label 현재 점 링(주황) / 배경 아래", c.dotRing, c.bgBottom, Kind.NON_TEXT),
        )
    }

    // ---- 현재 글자·터치 크기 상수 ----
    // 주의: MainActivity.Readability 값의 복제(수동 동기화 필요, 다음 단계에서 단일 출처로 통합 예정).
    // 글자 크기는 sp, 터치·간격은 dp 값이다.
    enum class SizeRole(val min: Int) {
        CORE_TEXT(MIN_CORE_TEXT),
        AUX_TEXT(MIN_AUX_TEXT),
        TOUCH(MIN_TOUCH),
        GAP(MIN_TARGET_GAP)
    }

    class SizeItem(val name: String, val value: Float, val role: SizeRole) {
        val passes: Boolean get() = value >= role.min
    }

    val currentSizes: List<SizeItem> = listOf(
        SizeItem("CORE_SP 질문·안내·응답 문구", 24f, SizeRole.CORE_TEXT),
        SizeItem("BUBBLE_SP 말풍선", 24f, SizeRole.CORE_TEXT),
        SizeItem("CONTROL_SP 예/아니오/시작/좌석 버튼 글자", 24f, SizeRole.CORE_TEXT),
        SizeItem("SWITCH_SP 스위치·고지 제목 글자", 24f, SizeRole.CORE_TEXT),
        SizeItem("NOTICE_BODY_SP 음성 사용 안내 본문", 20f, SizeRole.AUX_TEXT),
        SizeItem("AUX_SP 그 밖의 보조 문구", 20f, SizeRole.AUX_TEXT),
        SizeItem("개발용 로그·모의 영역 글자(14f 직접 지정)", 14f, SizeRole.AUX_TEXT),
        SizeItem("MIN_TOUCH_DP 터치 영역 최소 높이", 76f, SizeRole.TOUCH),
        SizeItem("GAP_DP 버튼 사이 간격", 8f, SizeRole.GAP),
        // 보호자 확인 영역 전용(MainActivity.GuardLayout 복제, 통과 항목)
        SizeItem("GUARD_CORE_SP 보호자 확인 질문·예/아니오 글자", 32f, SizeRole.CORE_TEXT),
        SizeItem("GUARD_BUTTON_MIN_DP 보호자 확인 예/아니오 높이", 96f, SizeRole.TOUCH),
        SizeItem("GUARD_GAP_DP 보호자 확인 버튼·질문 간격", 24f, SizeRole.GAP),
    )
}
