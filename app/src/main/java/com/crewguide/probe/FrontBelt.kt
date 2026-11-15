package com.crewguide.probe

// 2026-11-14 PM 결정 22(사용자 결정): 앞줄 벨트 판정. 실차·에뮬 공통 순수 규칙(Android·SDK 클래스 없음).
// 근거(javap, 2026-11-14): 좌석별 벨트 API는 PleosPropertyIds.SEAT_BELT_BUCKLED, 경고등은 safetyApi.registerTelltales/getTelltalesInfo
//  (PleosPropertyIds.TELLTALES, TelltaleData(type=SEAT_BELT, state=OFF|ON|BLINK)). 경고등은 좌석을 구분하지 않는다.
// QA 에뮬 관찰(2026-11-14 01:00): VC 경고등 On/Blink → TT-EVT에 SEAT_BELT ON|BLINK, Off → 벨트 항목 없음. 좌석별 GET은 늘 false.
// 규칙
//  - 앞줄 좌석별 값이 체결(true) → 체결(출처 좌석별 신호).
//  - 좌석별 값이 미체결(false)·없음·확인 불가이고 경고등을 받고 있으면(지원·연결 정상) → 경고등 기준:
//    SEAT_BELT ON/BLINK = 미체결, 항목 없음(OFF) = 체결. 운전석·조수석을 구분할 수 없어 앞줄 전체에 같은 값을 쓴다.
//  - 경고등을 받지 못하면(미지원·끊김·아직 못 받음) 좌석별 값 그대로(없음·확인 불가 → '확인 불가' + [보호자 확인]).
//  - 경고등 기준은 실제 차량 신호다(모의 아님, beltMock=false). 뒷줄 벨트는 지금처럼 모의다.

enum class BeltBasis { SEAT, TELLTALE }

data class FrontBelt(val ref: SeatBeltRef, val basis: BeltBasis)

object FrontBeltRule {
    /** 경고등 기준 미체결의 재안내·풀림 기록 키(앞줄 한 묶음) */
    const val FRONT_KEY = "ROW_1"
    val FRONT_SEATS = setOf("ROW_1_LEFT", "ROW_1_RIGHT")
    /** 경고등 기준 미체결 재안내(자리 이름 대신 '앞자리') */
    const val TEXT_REMIND = "앞자리 벨트 체결 신호를 기다려요. 앞자리 벨트를 매 주세요"
    /** 주행 중 경고등 기준 풀림 */
    const val TEXT_RELEASE = "앞자리 벨트 신호가 풀렸어요. 앞자리 벨트를 매 주세요"

    fun isFront(seat: String): Boolean = seat in FRONT_SEATS

    /**
     * [seatRef]: 좌석별 벨트 신호(StageSeatSignal.beltRefOf 결과). [telltaleOn]: 경고등 SEAT_BELT가 ON/BLINK면 true, 항목 없음(OFF)이면 false,
     * 경고등을 받지 못하면(미지원·끊김·아직 없음) null.
     */
    fun resolve(seatRef: SeatBeltRef, telltaleOn: Boolean?): FrontBelt = when {
        seatRef == SeatBeltRef.BUCKLED_SIGNAL -> FrontBelt(seatRef, BeltBasis.SEAT)
        telltaleOn != null -> FrontBelt(if (telltaleOn) SeatBeltRef.UNBUCKLED_SIGNAL else SeatBeltRef.BUCKLED_SIGNAL, BeltBasis.TELLTALE)
        else -> FrontBelt(seatRef, BeltBasis.SEAT)
    }

    /** 주행 중 풀림: 경고등이 꺼져 있다가(항목 없음) 켜짐/깜빡임으로 바뀐 순간 */
    fun releaseEdge(prevOn: Boolean?, nowOn: Boolean?): Boolean = prevOn == false && nowOn == true

    /**
     * 2026-11-14 PM 결정 23: 주행 중 앞줄 풀림 안내를 낼 이동인가. 확인을 마친 이동에서 앞줄에 앉음 신호가 하나라도 있으면(확인 대상 여부·아이 배정과 무관).
     * 예전 조건('앞줄을 확인 대상으로 고른 이동에서만')은 지웠다. 간격·횟수는 BeltReleaseAlertRule(30초·3회) 그대로
     */
    fun releaseArmed(confirmed: Boolean, frontOcc: Map<String, SeatOcc>): Boolean =
        confirmed && frontOcc.any { (k, v) -> isFront(k) && v == SeatOcc.OCCUPIED }
}
