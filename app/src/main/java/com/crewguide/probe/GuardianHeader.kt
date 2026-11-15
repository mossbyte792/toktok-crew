package com.crewguide.probe

/** 보호자 설정 영역 구분 머리글(옛 흐름 화면, 새 무대에서는 숨김). 좌석 선택 아래에만 놓이며 Android 클래스를 쓰지 않는 순수 상수다. 2026-11-14: "(가안)" 표기 지움 */
object GuardianHeader {
    const val TEXT = "보호자 설정"
    const val SP = 24f          // 굵은 글씨 없음, 보조 글자 최소(DesignRules.MIN_AUX_TEXT) 이상
    const val TOP_DP = 12       // 위아래 여백 합 20dp(32dp 이내)
    const val BOTTOM_DP = 8
}
