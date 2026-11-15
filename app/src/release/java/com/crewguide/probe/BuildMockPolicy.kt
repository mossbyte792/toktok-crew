package com.crewguide.probe

// release 빌드 전용 사본. 같은 이름의 debug 사본(src/debug)은 MOCK_ALLOWED = true다.
// 2026-11-07 검토자 지시 3(작업 단위 3): 출시 빌드에서는 앱 내부 모의 값으로 정차 조작을 열 수 없다.
// [VehicleGateRule.evaluate]는 mockAllowed=false일 때 모의 기어·모의 플랫폼 입력을 RESTRICTED로 본다.

/** 이 빌드가 앱 내부 모의 값을 정차 판정에 쓸 수 있는가 */
object BuildMockPolicy {
    const val MOCK_ALLOWED: Boolean = false
    /** 2026-11-10 2차 디렉티브 ②: release: 사용자 화면에서 개발·시연 표기를 지운다(ReleaseCopy). 안전 고지·출처 표기는 지우지 않는다 */
    const val SHOW_DRAFT_MARKS: Boolean = false
    /**
     * 2026-11-10 4차 결정 B: 말투 B 문구는 사용자 확정 전 가안이라 release 런타임에서 발화·표시하지 않는다.
     * 저장값(ToneStore·프로필 tone)의 B는 바꾸거나 지우지 않고, 쓰는 순간에만 A로 바꾼다([ToneSetting.runtime]).
     * 문구 승인 뒤 이 값을 true로 바꾸고 설정 화면에 A/B 선택을 붙인다(문서: docs/개발기록 4차 결정 보고).
     */
    const val TONE_B_ALLOWED: Boolean = false
    const val VARIANT: String = "release"
}
