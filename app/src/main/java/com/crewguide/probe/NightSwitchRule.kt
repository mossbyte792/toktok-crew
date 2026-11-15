package com.crewguide.probe

/**
 * P1-A 야간 전환(Activity 재생성) 허용 판정. 순수 값만 받는다(안드로이드 의존 없음).
 *
 * 2026-11-09 보완: 벨트 감시 세션이 켜져 있으면(경고 발화가 없어도) 재생성하지 않는다.
 * `inFlight == null`은 "지금 말하는 경고가 없다"일 뿐 "감시가 끝났다"가 아니다.
 * 재생성하면 차량 리스너를 해제·재등록하는 사이 감시가 끊기므로, 세션이 정상 종료된 뒤에 적용한다.
 * 이 규칙은 세션을 끝내거나 안전 조건을 낮추지 않는다(보류만 한다).
 */
object NightSwitchRule {

    data class Inputs(
        val pending: Boolean,
        val destroyed: Boolean,
        val finishing: Boolean,
        val resumed: Boolean,
        val locked: Boolean,             // lockReason() != null
        val parkAllowed: Boolean,        // 유효 P + UXR 정차 허용
        val guardianIdle: Boolean,       // 보호자 확인 IDLE
        val voiceActive: Boolean,        // STT 진행
        val ttsBusy: Boolean,
        val beltSessionActive: Boolean,  // 벨트 감시 세션 켜짐
        val beltWarningInFlight: Boolean,
        val exitLinePending: Boolean,    // 하차 한 줄 발화 대기·진행
        val confirmOpen: Boolean,        // 종료 확인창
        val customizeOpen: Boolean,
    )

    /** 지금 재생성해도 되는가 */
    fun canApply(i: Inputs): Boolean =
        i.pending && !i.destroyed && !i.finishing && i.resumed &&
            !i.locked && i.parkAllowed && i.guardianIdle &&
            !i.voiceActive && !i.ttsBusy &&
            !i.beltSessionActive && !i.beltWarningInFlight &&
            !i.exitLinePending && !i.confirmOpen && !i.customizeOpen

    /** 구성 변경 시 보류 여부: 화면 색과 같아지면(원래 값으로 되돌림) 보류를 취소해 불필요한 재생성을 막는다 */
    fun pendingAfterConfig(systemNight: Boolean, paletteNight: Boolean): Boolean = systemNight != paletteNight
}
