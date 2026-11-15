package com.crewguide.probe

/**
 * 2026-11-15 r7 2절(나)(개발 D, 사용자 지시): 뒷문이 열린 적 없어(예: 앞문으로 내림) 자동 마무리가 안 될 때 **보호자 버튼으로 이동을 끝낸다.**
 * 순수 규칙(Android·SDK 클래스 없음). 발화·화면은 호출자(MainActivity)가 한다.
 *
 * 규칙
 *  - 하차 안내 화면에서만: 출발한 이동 + 지금 주행 아님 + 정차 허용(유효 P·플랫폼 허용·화면 조작 가능) + 아직 마무리 전(allLeftSaid=false) +
 *    (도착 신호(실제 또는 "예, 도착했어요")를 받고 도착 안내가 나갔음 **또는** "내릴까요?"에 예(중간 하차)).
 *  - 누르면 하차 마무리(ALL_LEFT)와 같은 길로 끝낸다: allLeftSaid=true + 음성 [TEXT](호출자 applyTrip이 ALL_LEFT 처리로 작별 인사·끝난 이동 저장·
 *    스티커 처리를 그대로 한다). 신호 기준 문장("내린 것 같아요")은 쓰지 않는다(**임시 결정 47**).
 *  - 자동 마무리(배정 자리 모두 내림)와 겹치지 않는다: 어느 쪽이든 allLeftSaid가 먼저 true가 되면 다른 쪽은 무시된다
 *    ([TripGuide.onOccupancy]의 finaleReady와 [allowed] 모두 !allLeftSaid를 본다).
 *  - 조건이 아니면 null(호출자는 기존 [이번 이동 끝내기] 동작을 한다).
 *
 * 문구(임시 결정 47, PM 문구·사용자 확인 대기): 음성 [TEXT] + 기존 작별 인사(VoiceLines.FAREWELL, 호출자),
 * 화면 제목 [TITLE], 기준 줄 [BASIS](디자인 F가 그린다, 계약 v7 exitByGuardian=true일 때). 버튼 이름 [BUTTON]은 하차 안내 화면에서만.
 */
object GuardianFinishRule {
    const val TEXT = "뒷좌석을 한 번 더 확인해 주세요."
    const val TITLE = "이번 이동을 마칠게요"
    const val BASIS = "(보호자 확인)"
    const val BUTTON = "다 내렸어요"

    /** 하차 안내 화면에서 보호자 버튼으로 끝낼 수 있는가. alighting = ArrivalAskRule.alightingByAnswer */
    fun allowed(tg: TripGuideState, alighting: Boolean, parkAllowed: Boolean): Boolean =
        parkAllowed && tg.confirmed && tg.departed && !tg.drivingNow && !tg.allLeftSaid &&
            ((tg.arrivalSignal && tg.arrivedSaid) || (alighting && !tg.arrivalSignal))

    /**
     * r8 1절(검수 세션 위임 판단, 계약 v8): 누른 순간 배정 뒷자리(ROW_2_*)에 착석 신호(OCCUPIED)가 남아 있는가.
     * 확인 불가(UNKNOWN)는 남음으로 치지 않는다(신호 기준으로만 말한다).
     */
    fun seatStillOccupied(tg: TripGuideState, occ: Map<String, SeatOcc>): Boolean =
        tg.occupiedAtConfirm.any { it.startsWith("ROW_2_") && occ[it] == SeatOcc.OCCUPIED }

    /** 끝낼 때 음성: 배정 뒷자리에 착석 신호가 남았으면 두고 내림 문구(LeftBehindRule.TEXT), 아니면 [TEXT]. 작별 인사는 호출자가 그대로 붙인다 */
    fun speech(seatOccupied: Boolean): String = if (seatOccupied) LeftBehindRule.TEXT else TEXT

    /** 보호자 버튼 끝내기. 조건이 아니면 null. seatOccupied = [seatStillOccupied] */
    fun finish(tg: TripGuideState, alighting: Boolean, parkAllowed: Boolean, seatOccupied: Boolean = false): TripGuideStep? =
        if (!allowed(tg, alighting, parkAllowed)) null
        else TripGuideStep(tg.copy(allLeftSaid = true), listOf(TripSay(TripSayKind.ALL_LEFT, speech(seatOccupied))))
}
