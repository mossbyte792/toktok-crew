package com.crewguide.probe

/**
 * 2026-11-13 ISSUE-06 남은 위험: 홈 화면 누름(시작·설정·꾸미기) 허용 판단(순수).
 * 화면 조작 게이트와 같은 기준이다: resume된 상태 + 정차 허용(유효 P + 플랫폼 허용) + 잠금 사유 없음.
 * 차량 화면은 '보이지만 resume 안 됨'으로 오래 남을 수 있어(PM 결정 13) 그동안 들어온 누름은 버린다.
 */
object ScreenTapGate {
    fun allowed(resumed: Boolean, gate: VehicleGate, lockReason: String?): Boolean =
        resumed && gate == VehicleGate.PARK_ALLOWED && lockReason == null
}
