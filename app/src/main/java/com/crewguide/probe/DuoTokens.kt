package com.crewguide.probe

/**
 * UI/UX v2(U1) 공통 디자인 토큰 — 순수 값(Android 클래스 없음, 단위 테스트로 대비 검사).
 *
 * - 기존 단일 출처 `Palette`의 확정 값(메인 파랑 2563EB·띠 1E40AF, 주황 F97316 + 글자 111111·띠 C2410C, 배경 F8FAFC)을 역할 이름으로 다시 묶는다.
 *   새 색은 역할(카드 바탕·보조 글자·비활성·스위치·상태 칩)에 필요한 것만 더했다. 초록은 브랜드 색으로 쓰지 않는다(상태 의미색 '체결 신호'만 예외).
 * - 크기 수치(본문 24sp, 보조 18sp, 버튼 76dp 이상, 주버튼 96dp)는 **앱 내부 설계 목표**다. 실차 화면의 물리 크기·density는 확인 전이다.
 * - 화면 코드는 `DuoViews.kt`(DuoButton·DuoCard·DuoToggleRow·StatusChipView)가 이 값을 읽는다.
 */
object DuoTokens {
    // ---- 간격·모양(dp) ----
    const val GRID_DP = 8
    const val CARD_PAD_DP = 24
    const val CARD_GAP_DP = 16
    const val CARD_RADIUS_DP = 24
    const val CARD_STROKE_DP = 2
    const val BUTTON_RADIUS_DP = 16
    const val EDGE_DP = 6              // 주요 버튼 아래 두툼한 띠. 누르면 본체가 띠만큼 내려간다
    const val BUTTON_MIN_DP = 76       // DesignRules.MIN_TOUCH와 같은 값(띠 포함)
    const val PRIMARY_MIN_DP = 96      // 주버튼 내부 목표(감사 #17, 가정)
    const val CHIP_MIN_DP = 44
    const val CHIP_RADIUS_DP = 20

    // ---- 글자(sp) ----
    // 2026-11-11 에뮬레이터 확인 후 조정: 앱 창 1682×1210 mdpi에서 24sp가 작게 보여 기존 DesignRules 하한(핵심 32 / 보조 24)에 맞춘다
    const val DISPLAY_SP = 52f
    const val TITLE_SP = 40f
    const val BODY_SP = 32f
    const val CAPTION_SP = 24f         // 보조 문구 하한(감사 #3: 8~14px 표기 제거)

    /** 버튼 종류: 주(파랑) / 강조(주황) / 보조(연회색) */
    /** 버튼 종류: 주(파랑) / 강조(주황) / 보조(연회색) / 주의(연회색 바탕 + 위험 글자색, 지우기 같은 되돌리기 어려운 동작. v3 2026-11-11) */
    enum class Kind { PRIMARY, ACCENT, NEUTRAL, DANGER }

    class Roles(
        val night: Boolean,
        val bg: Int,
        val surface: Int, val surfaceStroke: Int,
        val text: Int, val textMuted: Int,
        val primaryFill: Int, val primaryText: Int, val primaryEdge: Int,
        val accentFill: Int, val accentText: Int, val accentEdge: Int,
        val neutralFill: Int, val neutralText: Int, val neutralEdge: Int,
        val disabledFill: Int, val disabledText: Int, val disabledStroke: Int,
        val trackOn: Int, val trackOff: Int, val thumb: Int,
        val stateOnText: Int,
        val dangerText: Int,
    ) {
        fun fill(k: Kind): Int = when (k) { Kind.PRIMARY -> primaryFill; Kind.ACCENT -> accentFill; Kind.NEUTRAL, Kind.DANGER -> neutralFill }
        fun ink(k: Kind): Int = when (k) { Kind.PRIMARY -> primaryText; Kind.ACCENT -> accentText; Kind.NEUTRAL -> neutralText; Kind.DANGER -> dangerText }
        fun edge(k: Kind): Int = when (k) { Kind.PRIMARY -> primaryEdge; Kind.ACCENT -> accentEdge; Kind.NEUTRAL, Kind.DANGER -> neutralEdge }
    }

    val DAY = Roles(
        night = false,
        bg = Palette.CREW_BG,                       // F8FAFC
        surface = 0xFFFFFF, surfaceStroke = 0x64748B, // 카드 윤곽: 흰 바탕 4.76, 배경 4.55
        text = 0x111111, textMuted = 0x334155,
        primaryFill = Palette.MAIN_BLUE, primaryText = 0xFFFFFF, primaryEdge = Palette.STAGE_GREEN_EDGE,   // 2563EB / 1E40AF
        accentFill = Palette.CREW_ORANGE_FILL, accentText = Palette.CREW_ORANGE_TEXT, accentEdge = Palette.CREW_ORANGE_EDGE,
        neutralFill = 0xE2E8F0, neutralText = 0x111111, neutralEdge = 0x64748B,
        disabledFill = 0xF1F5F9, disabledText = 0x475569, disabledStroke = 0x94A3B8,
        trackOn = Palette.MAIN_BLUE, trackOff = 0x64748B, thumb = 0xFFFFFF,
        stateOnText = 0x1E40AF,
        dangerText = 0x9A3412,
    )

    // 밤: 감사 #2·#15 — 주버튼이 비활성처럼 보이던 채도 낮은 파랑(6F8FBF) 대신 채도 높은 밝은 파랑 + 어두운 글자
    val NIGHT = Roles(
        night = true,
        bg = 0x0F172A,
        surface = 0x1E293B, surfaceStroke = 0x94A3B8,
        text = 0xF1F5F9, textMuted = 0xCBD5E1,
        primaryFill = 0x60A5FA, primaryText = 0x0B1220, primaryEdge = 0x2563EB,
        accentFill = Palette.NIGHT_CREW_ORANGE_FILL, accentText = Palette.NIGHT_CREW_ORANGE_TEXT, accentEdge = Palette.CREW_ORANGE_EDGE,
        neutralFill = 0x334155, neutralText = 0xF1F5F9, neutralEdge = 0x0B1220,
        disabledFill = 0x182234, disabledText = 0x94A3B8, disabledStroke = 0x475569,
        trackOn = 0x60A5FA, trackOff = 0x94A3B8, thumb = 0xF8FAFC,
        stateOnText = 0x93C5FD,
        dangerText = 0xFDBA74,
    )

    fun roles(night: Boolean): Roles = if (night) NIGHT else DAY

    /** 글자·비글자 대비 검사 대상(DuoTokensTest가 모두 통과해야 한다) */
    fun pairs(r: Roles): List<DesignRules.ColorPair> {
        val t = DesignRules.Kind.TEXT
        val n = DesignRules.Kind.NON_TEXT
        val tag = if (r.night) "밤" else "낮"
        return listOf(
            DesignRules.ColorPair("$tag 본문 / 카드", r.text, r.surface, t),
            DesignRules.ColorPair("$tag 본문 / 배경", r.text, r.bg, t),
            DesignRules.ColorPair("$tag 보조 글자 / 카드", r.textMuted, r.surface, t),
            DesignRules.ColorPair("$tag 보조 글자 / 배경", r.textMuted, r.bg, t),
            DesignRules.ColorPair("$tag 카드 윤곽 / 배경", r.surfaceStroke, r.bg, n),
            DesignRules.ColorPair("$tag 주버튼 글자 / 채움", r.primaryText, r.primaryFill, t),
            DesignRules.ColorPair("$tag 강조 버튼 글자 / 채움", r.accentText, r.accentFill, t),
            DesignRules.ColorPair("$tag 보조 버튼 글자 / 채움", r.neutralText, r.neutralFill, t),
            DesignRules.ColorPair("$tag 비활성 버튼 글자 / 채움", r.disabledText, r.disabledFill, t),
            DesignRules.ColorPair("$tag 주버튼 채움 / 카드", r.primaryFill, r.surface, n),
            DesignRules.ColorPair("$tag 스위치 켜짐 트랙 / 카드", r.trackOn, r.surface, n),
            DesignRules.ColorPair("$tag 스위치 꺼짐 트랙 / 카드", r.trackOff, r.surface, n),
            DesignRules.ColorPair("$tag '켜짐' 글자 / 카드", r.stateOnText, r.surface, t),
            DesignRules.ColorPair("$tag 주의 글자 / 카드", r.dangerText, r.surface, t),
        ) + StatusChipSpec.State.values().map {
            val c = StatusChipSpec.colors(it, r.night)
            DesignRules.ColorPair("$tag 상태 칩 ${it.label} 글자 / 칩", c.fg, c.bg, t)
        } + StatusChipSpec.State.values().map {
            val c = StatusChipSpec.colors(it, r.night)
            DesignRules.ColorPair("$tag 상태 칩 ${it.label} 윤곽 / 카드", c.stroke, r.surface, n)
        } + infoChip(r.night).let { c ->
            listOf(
                DesignRules.ColorPair("$tag 정보 칩 글자 / 칩", c.fg, c.bg, t),
                DesignRules.ColorPair("$tag 정보 칩 윤곽 / 카드", c.stroke, r.surface, n),
            )
        } + listOf(
            // 2026-11-13 자동 확인 패널: 비선택 좌석 줄(비활성 바탕) 위 칩 윤곽, 모의 배지(머리말 색 글자 / 카드), 선택 좌석 강조 윤곽
            DesignRules.ColorPair("$tag 비선택 좌석 칩 윤곽 / 비활성 바탕", r.surfaceStroke, r.disabledFill, n),
            DesignRules.ColorPair("$tag 비선택 좌석 글자 / 비활성 바탕", r.disabledText, r.disabledFill, t),
            DesignRules.ColorPair("$tag 모의 배지 글자 / 카드", V6Tokens.eyebrow(r.night), r.surface, t),
            DesignRules.ColorPair("$tag 선택 좌석 윤곽 / 카드", r.primaryFill, r.surface, n),
        )
    }

    /** 2026-11-13 정보 칩(앉음·보호자 확인함·진행 단계 현재 밖 표시). 색만으로 구분하지 않고 기호·글자를 함께 쓴다 */
    fun infoChip(night: Boolean): StatusChipSpec.Colors =
        if (night) StatusChipSpec.Colors(Palette.NIGHT_INFO_CHIP_TEXT, Palette.NIGHT_INFO_CHIP_FILL, Palette.NIGHT_INFO_CHIP_STROKE, false)
        else StatusChipSpec.Colors(Palette.INFO_CHIP_TEXT, Palette.INFO_CHIP_FILL, Palette.INFO_CHIP_STROKE, false)
}

/**
 * 좌석 벨트 **참고** 상태 칩(U1 StatusChip). 네 상태를 글자 + 기호 + 색으로 함께 구분한다(색만으로 구분하지 않음).
 * 표시 전용: 보호자 확인 판정·잠금에 쓰지 않는다. '체결됨'·'안전' 같은 단정 표현은 쓰지 않는다(StageSeatSignal 규칙과 같음).
 */
object StatusChipSpec {
    enum class State(val label: String, val glyph: String, val meaning: String) {
        BUCKLED("체결 신호", "●", "차량이 체결 신호를 보냈어요(참고)"),
        UNBUCKLED("미체결 신호", "!", "차량이 미체결 신호를 보냈어요(참고)"),
        UNAVAILABLE("확인 불가", "?", "신호를 읽지 못했거나 연결이 끊겼어요"),
        NO_SIGNAL("신호 없음", "–", "아직 받은 신호가 없어요"),
    }

    class Colors(val fg: Int, val bg: Int, val stroke: Int, val dashed: Boolean)

    fun colors(s: State, night: Boolean): Colors = if (!night) when (s) {
        State.BUCKLED -> Colors(0x14532D, 0xDCFCE7, 0x15803D, false)
        State.UNBUCKLED -> Colors(0x7C2D12, 0xFFEDD5, 0xC2410C, false)
        State.UNAVAILABLE -> Colors(0x1E293B, 0xE2E8F0, 0x475569, false)
        State.NO_SIGNAL -> Colors(0x334155, 0xFFFFFF, 0x64748B, true)
    } else when (s) {
        State.BUCKLED -> Colors(0xDCFCE7, 0x14532D, 0x4ADE80, false)
        State.UNBUCKLED -> Colors(0xFFEDD5, 0x7C2D12, 0xFB923C, false)
        State.UNAVAILABLE -> Colors(0xF1F5F9, 0x334155, 0xCBD5E1, false)
        State.NO_SIGNAL -> Colors(0xCBD5E1, 0x1E293B, 0x94A3B8, true)
    }

    fun of(ref: SeatBeltRef): State = when (ref) {
        SeatBeltRef.BUCKLED_SIGNAL -> State.BUCKLED
        SeatBeltRef.UNBUCKLED_SIGNAL -> State.UNBUCKLED
        SeatBeltRef.UNKNOWN -> State.UNAVAILABLE
        SeatBeltRef.NO_SIGNAL -> State.NO_SIGNAL
    }

    /** 칩 글자(기호 + 이름). 접근성 이름은 [a11y] */
    fun text(s: State): String = "${s.glyph} ${s.label}"
    fun a11y(s: State): String = "${s.label}: ${s.meaning}"
}
