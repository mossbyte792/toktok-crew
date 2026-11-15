package com.crewguide.probe

// 2026-11-10 M01: 게이트 로그 한 줄에 '출처'와 '요청 세대'를 같은 단어로 붙인다. 판정 로직은 없다(표시 전용).
// 출처 단어는 REAL(실제 Pleos 수신) / MOCK(debug 모의 기어) 두 가지만 쓴다. release는 모의가 허용되지 않으므로 항상 REAL.
// 세대: 화면 구간(gate epoch)과 기어·플랫폼 값을 받은 구간. 둘이 다르면 그 값은 게이트에서 오래된 값으로 취급된다.
object GateLogTag {
    const val REAL = "REAL"
    const val MOCK = "MOCK"

    const val NONE = "없음"

    /** 기어 출처. 모의 기어가 켜져 있고 빌드가 모의를 허용할 때만 MOCK. 현재 기어 값이 없으면(미수신·모의 끈 직후) 없음 */
    fun gearSource(mockGearOn: Boolean, gearKnown: Boolean = true, allowed: Boolean = DevMockGuard.allowed): String = when {
        DevMockGuard.admit(mockGearOn, allowed) -> MOCK
        !gearKnown -> NONE
        else -> REAL
    }

    /** 예: " | 기어출처=REAL 세대=3 기어세대=3 플랫폼세대=2". 값은 로그를 남긴 시점(적용 직전일 수 있음) */
    fun suffix(source: String, gateEpoch: Int, gearEpoch: Int, platformEpoch: Int): String =
        " | 기어출처=$source 세대=$gateEpoch 기어세대=${epochText(gearEpoch)} 플랫폼세대=${epochText(platformEpoch)}"

    /** GET 요청 꼬리표. rid=조회 묶음 번호, req=기어 요청 번호 */
    fun request(rid: Int, req: Int): String = " (rid=$rid req=$req)"

    private fun epochText(e: Int): String = if (e < 0) "없음" else e.toString()
}
