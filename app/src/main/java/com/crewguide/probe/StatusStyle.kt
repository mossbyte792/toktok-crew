package com.crewguide.probe

/**
 * 상태 표시 체계(순수 로직, Android 클래스 사용 금지). 색만으로 구분하지 않도록
 * 상태마다 아이콘 기호·라벨·색을 함께 정한다. 기호는 기본 글꼴에 흔한 단순 도형만 쓴다(체크표시 등 글꼴 의존 기호 미사용).
 * 색은 0xRRGGBB. 글자색/배경색 대비는 일반 글자 기준 4.5:1 이상.
 */
object StatusStyle {
    /** fg/bg는 주간 값(변경 금지), nightFg/nightBg는 야간 값. 아이콘·라벨은 모드와 무관하게 공통. */
    enum class Kind(
        val icon: String, val label: String, val fg: Int, val bg: Int,
        val nightFg: Int, val nightBg: Int,
    ) {
        PROGRESS("●", "진행 중", 0x0D47A1, 0xE3F2FD, 0x90CAF9, 0x1A2A3F),
        CAUTION("▲", "주의", 0x7A3E00, 0xFFF3E0, 0xFFCC80, 0x3A2A12),
        REPORTED("■", "보호자 보고 받음", 0x1B5E20, 0xE8F5E9, 0xA5D6A7, 0x1B2E1D),
        UNKNOWN("○", "확인 불가", 0x424242, 0xEEEEEE, 0xBDBDBD, 0x2C2C2C);

        /** 라벨 접두: 아이콘 + 공백. 원문 문구 앞에만 붙인다. */
        val prefix: String get() = "$icon "
        val ratio: Double get() = DesignRules.contrastRatio(fg, bg)
        val nightRatio: Double get() = DesignRules.contrastRatio(nightFg, nightBg)

        /** 지정한 모드의 글자색/배경색(0xRRGGBB). */
        fun fgFor(night: Boolean): Int = if (night) nightFg else fg
        fun bgFor(night: Boolean): Int = if (night) nightBg else bg

        /** Palette 현재 모드의 글자색/배경색(0xRRGGBB). */
        val currentFg: Int get() = fgFor(Palette.current.night)
        val currentBg: Int get() = bgFor(Palette.current.night)
    }

    /** 사용 금지어(앱이 확인했다고 오해할 표현). */
    val FORBIDDEN_WORDS: List<String> = listOf("완료", "안전", "확실", "올바르게")

    fun withIcon(kind: Kind, text: String): String = kind.prefix + text
}
