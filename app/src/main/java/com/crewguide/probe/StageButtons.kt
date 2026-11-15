package com.crewguide.probe

// 무대 버튼 체계(순수 로직, Android 클래스 금지). 종류별 크기·아이콘·색 '역할'만 정하고 실제 색은 Palette에서 읽는다.
// 화면 적용은 StageScreen, 아이콘 그림은 IconDrawable. 롤백: 이 파일·IconDrawable.kt·StageButtonsTest.kt 삭제 + StageScreen.kt 이전 판 복원.

/** 버튼 종류. FINISH는 [끝내기]용(체크 아이콘을 쓰면 완료·안전 단정으로 읽힐 수 있어 아이콘 없음) */
enum class ButtonKind { PRIMARY_YES, SECONDARY_NO, PRIMARY_START, QUIET, DISABLED_START, FINISH }

enum class IconKind { CHECK, CROSS, PLAY, GEAR, NONE }

/** 색 역할 이름. NONE은 '칠하지 않음(투명)'. 실제 값은 [StageButtons.colorOf]가 Palette에서 읽는다 */
enum class ColorRole2 {
    NONE, BTN_ON_BG, BTN_ON_TEXT, BTN_OFF_BG, BTN_OFF_TEXT, STAGE_CAPTION,
    YES_FILL, YES_TEXT, START_FILL, START_TEXT,   // U-2b 추가(무대 전용 파스텔·선명 채움)
    NO_FILL, NO_TEXT, FINISH_FILL, FINISH_TEXT,   // 시안 2 추가([아니오] 회색, [끝내기] 살구)
}

/** heightDp = 터치 영역 전체 높이(가장자리 포함). 본체 높이 = heightDp − edgeDp. edgeDp 0이면 가장자리 없음 */
data class ButtonSpec(
    val heightDp: Int,
    val textSp: Int,
    val strokeDp: Int,
    val iconKind: IconKind,
    val fillRole: ColorRole2,
    val textRole: ColorRole2,
    val edgeDp: Int = 0,
) {
    val bodyDp: Int get() = heightDp - edgeDp
}

object StageButtons {
    const val ICON_GAP_DP = 12          // 아이콘과 글자 사이
    const val ICON_PERCENT = 40         // 아이콘 크기 = 버튼 높이의 40%
    const val STROKE_PERCENT = 12       // 선 두께 = 아이콘 크기의 12%
    const val PAD_DP = 16               // 글자·아이콘 묶음 좌우 여백
    const val PRESSED_ALPHA = 0.85f     // 눌린 동안 알파

    /** 아이콘 한 변(dp, 정수 나눗셈): 112→44, 96→38, 76→30 (원형이 아닌 아이콘: 재생 삼각형) */
    fun iconSizeDp(heightDp: Int): Int = heightDp * ICON_PERCENT / 100

    const val CIRCLE_PERCENT = 46       // 원형 아이콘 지름 = 버튼 높이의 46%
    /** 원형 아이콘 지름(dp, 정수 나눗셈): 112→51 (5152/100), 96→44 */
    fun circleDiameterDp(heightDp: Int): Int = heightDp * CIRCLE_PERCENT / 100

    /** 원 안에 그리는 아이콘 종류인가(체크·엑스). 재생 삼각형은 현재 모양 유지(원 없음) */
    fun isCircled(icon: IconKind): Boolean = icon == IconKind.CHECK || icon == IconKind.CROSS

    /** 아이콘 한 변(dp): 원형이면 지름, 아니면 기존 40% */
    fun iconBoxDp(icon: IconKind, heightDp: Int): Int =
        if (isCircled(icon)) circleDiameterDp(heightDp) else iconSizeDp(heightDp)

    /** 원 색(0xRRGGBB). 원형이 아니면 0 */
    fun iconCircleColor(icon: IconKind, night: Boolean): Int {
        val c = Palette.stage(night)
        return when (icon) {
            IconKind.CHECK -> c.yesIconCircle
            IconKind.CROSS -> c.noIconCircle
            IconKind.PLAY, IconKind.GEAR, IconKind.NONE -> 0
        }
    }

    /** 원 안 기호 색(0xRRGGBB). 원형이 아니면 0 */
    fun iconSymbolColor(icon: IconKind, night: Boolean): Int {
        val c = Palette.stage(night)
        return when (icon) {
            IconKind.CHECK -> c.yesIconSymbol
            IconKind.CROSS -> c.noIconSymbol
            IconKind.PLAY, IconKind.GEAR, IconKind.NONE -> 0
        }
    }

    /** 원 테두리 색(0xRRGGBB, 0이면 테두리 없음). 낮 [아니오]만(회색 바탕 위에서 흰 원이 약해서) */
    fun iconRingColor(icon: IconKind, night: Boolean): Int =
        if (icon == IconKind.CROSS) Palette.stage(night).noIconRing else 0

    /** 역할 → 0xRRGGBB(알파 없음). NONE은 0(쓰지 않음) */
    fun colorOf(role: ColorRole2, night: Boolean): Int {
        val s = Palette.scheme(night)
        return when (role) {
            ColorRole2.NONE -> 0
            ColorRole2.BTN_ON_BG -> s.btnOnBg
            ColorRole2.BTN_ON_TEXT -> s.btnOnText
            ColorRole2.BTN_OFF_BG -> s.btnOffBg
            ColorRole2.BTN_OFF_TEXT -> s.btnOffText
            ColorRole2.STAGE_CAPTION -> Palette.stage(night).caption
            ColorRole2.YES_FILL -> Palette.stage(night).yesFill
            ColorRole2.YES_TEXT -> Palette.stage(night).yesText
            ColorRole2.START_FILL -> Palette.stage(night).startFill
            ColorRole2.START_TEXT -> Palette.stage(night).startText
            ColorRole2.NO_FILL -> Palette.stage(night).noFill
            ColorRole2.NO_TEXT -> Palette.stage(night).noText
            ColorRole2.FINISH_FILL -> Palette.stage(night).finishFill
            ColorRole2.FINISH_TEXT -> Palette.stage(night).finishText
        }
    }

    /**
     * 가장자리 색(0xRRGGBB): 기본은 바탕색(채움 역할) × 0.78. 채움이 없는(NONE) 버튼은 0.
     * U-2c: [예]·[시작] 계열은 StageColors.yesEdge/startEdge가 0이 아니면(낮 초록 1B5E20) 그 값을 쓴다.
     */
    fun edgeColorFor(spec: ButtonSpec, night: Boolean): Int {
        if (spec.fillRole == ColorRole2.NONE) return 0
        val c = Palette.stage(night)
        val fixed = when (spec.fillRole) {
            ColorRole2.YES_FILL -> c.yesEdge
            ColorRole2.START_FILL -> c.startEdge
            ColorRole2.NO_FILL -> c.noEdge
            ColorRole2.FINISH_FILL -> c.finishEdge
            else -> 0
        }
        return if (fixed != 0) fixed else StageUiTokens.edgeColorOf(colorOf(spec.fillRole, night))
    }

    /**
     * U-2d: 낮 '미선택' 칩(좌석·아이)에만 윤곽을 둔다(바탕 E0E0E0이 흰 패널 위에서 대비 ≈1.32라 분리가 약함).
     * 선택된 칩·밤 칩은 0(불변). 윤곽색은 Palette.CHIP_STROKE(424242, 흰 패널 대비 ≈10.0).
     */
    const val CHIP_OUTLINE_DP = 2
    fun chipOutlineDp(selected: Boolean, night: Boolean): Int = if (!selected && !night) CHIP_OUTLINE_DP else 0

    /** 비활성 [시작]의 윤곽 두께(낮에만; 흰 패널 위에서 바탕-패널 분리용. 밤은 기존 모양 유지 = 0) */
    const val DISABLED_OUTLINE_DP = 3
    /** [아니오] 윤곽 두께(2026-11-06) */
    const val NO_OUTLINE_DP = 3
    fun strokeDpFor(kind: ButtonKind, night: Boolean): Int =
        when {
            kind == ButtonKind.DISABLED_START && !night -> DISABLED_OUTLINE_DP
            kind == ButtonKind.SECONDARY_NO -> NO_OUTLINE_DP   // 주황 [아니오]: 글자색(111111) 윤곽, 낮·밤 공통
            else -> specOf(kind).strokeDp
        }
}

fun specOf(kind: ButtonKind): ButtonSpec = when (kind) {
    ButtonKind.PRIMARY_YES -> ButtonSpec(112, 40, 0, IconKind.CHECK, ColorRole2.YES_FILL, ColorRole2.YES_TEXT, StageUiTokens.EDGE_DP)
    ButtonKind.SECONDARY_NO -> ButtonSpec(112, 40, 0, IconKind.CROSS, ColorRole2.NO_FILL, ColorRole2.NO_TEXT, StageUiTokens.EDGE_DP)
    ButtonKind.PRIMARY_START -> ButtonSpec(96, 32, 0, IconKind.PLAY, ColorRole2.START_FILL, ColorRole2.START_TEXT, StageUiTokens.EDGE_DP)
    ButtonKind.DISABLED_START -> ButtonSpec(96, 32, 0, IconKind.PLAY, ColorRole2.BTN_OFF_BG, ColorRole2.BTN_OFF_TEXT, StageUiTokens.EDGE_DP)
    ButtonKind.QUIET -> ButtonSpec(
        StageUiTokens.QUIET_BUTTON_MIN_DP, StageUiTokens.QUIET_BUTTON_SP, StageUiTokens.QUIET_BUTTON_STROKE_DP,
        IconKind.NONE, ColorRole2.NONE, ColorRole2.STAGE_CAPTION, StageUiTokens.QUIET_EDGE_DP,
    )
    ButtonKind.FINISH -> ButtonSpec(96, 32, 0, IconKind.NONE, ColorRole2.FINISH_FILL, ColorRole2.FINISH_TEXT, StageUiTokens.EDGE_DP)
}

/** [시작] 버튼의 모양: 활성이면 PRIMARY_START, 아니면 DISABLED_START */
fun startKind(enabled: Boolean): ButtonKind = if (enabled) ButtonKind.PRIMARY_START else ButtonKind.DISABLED_START
