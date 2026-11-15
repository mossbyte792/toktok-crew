package com.crewguide.probe

// 개발용 모의 값(모의 기어·모의 벨트·모의 내비·개발 도구 잠금 해제)의 단일 관문. Android·SDK 클래스를 쓰지 않는다.
// 2026-11-07 검토자 지시 P0-1(Unit 3 후속): release 빌드에서는 화면 진입점만 숨기지 않고 모의 상태 주입 자체를 막는다.
// MainActivity의 모의 상태 변수(useSim·mockGearOn·devUnlocked)는 setter에서 [admit]을 거치므로
// release에서는 어떤 호출 경로로도 true가 되지 않는다. 허용 여부는 빌드 종류([BuildMockPolicy], src/debug·src/release)로만 정해진다.

object DevMockGuard {
    /** 이 빌드에서 개발용 모의 값을 쓸 수 있는가(debug=true, release=false) */
    val allowed: Boolean get() = BuildMockPolicy.MOCK_ALLOWED

    /** 모의 상태 변수에 넣을 값. 허용되지 않는 빌드에서는 요청과 관계없이 false */
    fun admit(requested: Boolean, allowed: Boolean = this.allowed): Boolean = requested && allowed

    /**
     * 실제 기어 수신을 받아들일지. 모의 기어가 켜져 있으면 실제 수신을 무시하지만,
     * 허용되지 않는 빌드에서는 모의 상태 값과 관계없이 항상 실제 수신을 받는다(모의 값이 실제 값을 가리지 못함).
     */
    fun acceptRealGear(mockGearOn: Boolean, allowed: Boolean = this.allowed): Boolean = !admit(mockGearOn, allowed)

    /** 차단 시 남기는 로그 문구 */
    const val BLOCKED_LOG = "[모의차단] 출시 빌드라 개발용 모의 값을 받지 않음"
}
