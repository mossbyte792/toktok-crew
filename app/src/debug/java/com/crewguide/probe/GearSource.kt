package com.crewguide.probe

// [debug 소스 세트 전용, 2026-11-07 Unit 5.1 P0-1] release APK에는 들어가지 않는다.
// 모의 기어 시연 도우미(개발용)의 순수 로직. Android·SDK 클래스를 쓰지 않는다.
// 모의 기어는 applyGear에 P/D 이름을 넣을 뿐이며, 잠금·벨트·이동 끝·하차 확인 판정은 기존 규칙이 그대로 한다.

object GearSource {
    /** 모의 P 이름. 기존 P 집합(PARK_GEAR_NAMES)의 값이며 GearLock.kt 주석·기존 테스트가 쓰는 이름이다(SDK 원본 미검증) */
    const val MOCK_PARK = "GEAR_PARK"

    /** 모의 D 이름. P 집합에 없는 이름이면 기존 판정이 주행(P 아님)으로 본다. 기존 테스트가 쓰는 이름이다(SDK 원본 미검증) */
    const val MOCK_DRIVE = "GEAR_DRIVE"

    /** 보조 라벨에 앞쪽으로 붙이는 모의 기어 표기 */
    const val MOCK_GEAR_LABEL = DemoLabel.MOCK_GEAR_PIECE

    /** 모의 기어가 켜져 있으면 실제 기어 수신을 받아들이지 않는다 */
    fun acceptReal(mockOn: Boolean): Boolean = !mockOn

    // 개발용 패널 문구
    const val PANEL_TITLE = "모의 기어(가안, 개발용)"
    const val SWITCH_TEXT = "모의 기어 사용"
    const val BTN_PARK = "모의 P"
    const val BTN_DRIVE = "모의 D"
    const val STATUS_OFF = "모의 기어 꺼짐"

    /** 패널 상태 한 줄. 꺼짐이면 현재 기어를 표시하지 않는다 */
    fun panelStatus(mockOn: Boolean, currentName: String?): String = when {
        !mockOn -> STATUS_OFF
        currentName == MOCK_DRIVE -> "모의 기어 켜짐 · 현재 D"
        currentName == MOCK_PARK -> "모의 기어 켜짐 · 현재 P"
        else -> "모의 기어 켜짐 · 현재 확인 중"
    }
}
