package com.crewguide.probe

// debug 빌드 전용 사본. 같은 이름의 release 사본(src/release)은 MOCK_ALLOWED = false다.
// 2026-11-07 검토자 지시 3(작업 단위 3): 앱 내부 모의 기어·모의 플랫폼 허용은 개발·시연(debug) 빌드에서만 인정한다.
// 이 값은 빌드 종류(소스 세트)로만 정해지고, 화면 토글·저장값·인텐트로 바꿀 수 없다.

/** 이 빌드가 앱 내부 모의 값을 정차 판정에 쓸 수 있는가 */
object BuildMockPolicy {
    const val MOCK_ALLOWED: Boolean = true
    /** 2026-11-10 2차 디렉티브 ②: debug: 개발·시연 표기((가안, 시연용) 등)를 그대로 보인다 */
    const val SHOW_DRAFT_MARKS: Boolean = true
    /**
     * 2026-11-10 4차 결정 B: debug는 말투 B(사용자 확정 전 가안) 실험을 그대로 쓴다.
     * release 사본은 false → 저장값이 B여도 화면·음성은 A([ToneSetting.runtime]).
     */
    const val TONE_B_ALLOWED: Boolean = true
    const val VARIANT: String = "debug"
}
