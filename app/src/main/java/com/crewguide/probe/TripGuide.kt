package com.crewguide.probe

// 2026-11-13 출발·도착 음성 안내(순수 로직, Android·SDK 클래스 없음). 판단만 하고 발화·화면은 호출자(MainActivity)가 한다.
//
// 입력
//  - 확인 완료(자동 확인 또는 보호자 대체 버튼): [onConfirmed]
//  - 기어: 주행(BeltReleaseRule.isDriving) / 정차 허용 게이트(VehicleGate.PARK_ALLOWED): [onGear]
//  - 내비 시간(숫자만): 경로 시작 총 시간 [onRouteStarted], 남은 시간 [onRemain]. 단위는 초(가정, NaviHelper 문서 미확인)
//  - 내비 도착(실제 onDestinationArrived 또는 debug 모의 도착): [onArrived]
//  - 좌석 착석 신호: [onOccupancy]
// 규칙
//  - 도착 예정 시간(PM 결정 14, 2026-11-13 사용자 지시): 이동당 1회 "도착까지 약 N분 걸려요."(올림).
//    목적지를 확인 완료 전에 설정했으면 확인 완료 직후([onConfirmEta], 호출자가 완료 음성 끝에 붙인다),
//    확인 완료 뒤에 설정하면 유효한 시간(0 초과, 결정 12)이 처음 들어오는 즉시([onRouteStarted]/[onRemain], 주행 중이어도 음성만).
//    목적지가 없으면(시간 값 없음) 말하지 않는다. 도착 신호 뒤에는 말하지 않는다.
//  - 출발: 이번 이동 확인 완료 + 주행 기어 관측 시 1회 "출발할게요."(결정 14: 예정 시간은 위 안내 1회로 모아 출발 문구에서 뺐다).
//    판단 근거는 BoardingFlow 단계가 아니라 이 상태의 [TripGuideState.confirmed]다(D 순간 BoardingFlow는 HOME으로 정리된다).
//    출발·남은 시간 안내는 기어 기준이라 앱이 화면 뒤에 있어도 동작한다(정차 게이트의 전경 조건을 쓰지 않음).
//  - 5분·1분: 출발 뒤 임계값 위(남은 시간 > 임계값)에서 관측된 뒤 이하로 내려올 때 각 1회 "N분 후에 도착해요"(올림, 최소 1).
//    출발 때 이미 이하면 그 안내는 생략(출발 안내와 겹치지 않게). 한 번의 갱신에서 둘 다 통과하면 1분 안내만 하고 5분은 소진(PM 정정 2026-11-13).
//  - 도착: 출발한 이동에서 도착 신호 뒤 유효 P(정차 허용 게이트)에서만 1회. 주행 중 도착은 P까지 미룬다(PM 결정 4).
//    정차 판단은 전경 조건 없이(호출자 tripVoicePark) 한다. 루프 r5 1절: 이때 앱이 뒤에 있으면 호출자가 앱을 앞으로 올린다(AppFrontRule).
//    도착 안내에는 뒷좌석 확인 한 문장을 합친다. 도착 신호가 있었던 이동은 기존 하차 한 줄(ExitCheckRule)을 내지 않는다([suppressExitLine]).
//  - 하차 마무리: 출발 뒤 유효 P에서, 확인 때 착석(OCCUPIED)이던 좌석의 착석 신호가 모두 EMPTY가 되면 1회(PM 결정 5).
//    확인 때 선택 좌석 중 착석 확인 불가(UNKNOWN)가 있었거나 지금 하나라도 UNKNOWN이면 마무리하지 않는다(기존 하차 안내만).
//  - 값 없음·내비 연결 실패면 시간 문구만 빼고 같은 흐름이다. 내비 시간 0 이하도 값 없음이다(PM 결정 12, NaviTimeRule).
// 2단계 확장 여지(구현 안 함): 주행 중 벨트 해제 짧은 안내(B)는 BeltWarningPolicy 쪽, 도착 뒤 일정 시간 착석 신호가 남는 경우(D)는
//  [TripGuideState.parkedSinceMs]와 [onOccupancy]의 남은 좌석으로 판단할 수 있게 값을 남겨 둔다.

enum class TripSayKind { DEPART, ETA_TOTAL, ETA5, ETA1, ARRIVED, ALL_LEFT }

data class TripSay(val kind: TripSayKind, val text: String)

data class TripGuideState(
    /** 이번 이동의 확인 완료(자동 또는 보호자 대체) */
    val confirmed: Boolean = false,
    /** 확인에 보호자 대체 버튼이 쓰였는가 */
    val byGuardian: Boolean = false,
    /** 확인한 선택 좌석(화면 행 표시용) */
    val selected: Set<String> = emptySet(),
    /** 보호자 대체 버튼으로 확인한 좌석(화면 행 표시용) */
    val guardianSeats: Set<String> = emptySet(),
    /** r8b: [guardianSeats] 가운데 미체결 신호 자리를 보호자가 확인한 자리(화면 행 판정에서 미체결 신호를 '보호자 확인'으로 보이기) */
    val guardianUnbuckledSeats: Set<String> = emptySet(),
    /** 계약 v2: 확인 때 자리별 배정 아이 id(도착·하차 화면 칸의 아이 표시용) */
    val assigned: Map<String, Int> = emptyMap(),
    /** 확인 때 착석 신호가 있던 좌석(하차 마무리 대상) */
    val occupiedAtConfirm: Set<String> = emptySet(),
    /** 확인 때 선택 좌석 중 착석 확인 불가가 있었는가(true면 하차 마무리 없음) */
    val occUnknownAtConfirm: Boolean = false,
    val departed: Boolean = false,
    /** 출발 뒤 지금 주행 중인가 */
    val drivingNow: Boolean = false,
    /** 출발 뒤 유효 P(정차 허용)로 돌아온 시각(없으면 -1). 2단계 D(착석 신호가 남는 경우) 판단용 */
    val parkedSinceMs: Long = -1L,
    val totalSec: Int? = null,
    val remainSec: Int? = null,
    val etaSource: SignalSource = SignalSource.NONE,
    /** 이번 이동에서 도착 예정 시간 안내(결정 14)를 이미 냈는가(이동당 1회) */
    val etaSaid: Boolean = false,
    val seenAbove5: Boolean = false,
    val said5: Boolean = false,
    val seenAbove1: Boolean = false,
    val said1: Boolean = false,
    /** 출발 안내 발화가 끝났는가(DEPARTING → DRIVING 표시용) */
    val departSpoken: Boolean = false,
    /** 도착 신호를 받음(출발한 이동에서) */
    val arrivalSignal: Boolean = false,
    val arrivedSaid: Boolean = false,
    val allLeftSaid: Boolean = false,
    /** r10 L: 도착 신호 뒤에 새 경로 시작(onRouteStarted)을 받았는가(도착 뒤 다시 출발할 때 이 경로의 시간 값은 남긴다) */
    val routeAfterArrival: Boolean = false,
)

data class TripGuideStep(val state: TripGuideState, val says: List<TripSay>)

object TripGuide {
    const val FIVE_MIN_SEC = 300
    const val ONE_MIN_SEC = 60

    const val TEXT_DEPART = "출발할게요."
    /** 도착 예정 시간 1회 안내(PM 결정 14) */
    fun etaTotalText(minutes: Int): String = "도착까지 약 ${minutes}분 걸려요."
    fun etaText(minutes: Int): String = "${minutes}분 후에 도착해요"
    /** 도착 안내(PM 원문) */
    const val TEXT_ARRIVED = "도착했어요. 내리기 전에 벨트를 풀고 물건을 챙겨요"
    /** 도착 음성: 원문 + 뒷좌석 확인 한 문장(기존 하차 한 줄을 이 이동에서는 내지 않으므로 합친다) */
    const val TEXT_ARRIVED_SPOKEN = "$TEXT_ARRIVED. 뒷좌석도 한 번 더 확인해 주세요."
    /** r6 5절(개발 A): "내릴까요?" 예(중간 하차) 하차 안내 음성. 도착이라 말하지 않는다(도착 음성에서 '도착했어요.'만 뺀 문장) */
    const val TEXT_ALIGHT_SPOKEN = "내리기 전에 벨트를 풀고 물건을 챙겨요. 뒷좌석도 한 번 더 확인해 주세요."
    // ---- 하차 마무리 문구 ----
    // 결정 38(사용자 확정, 2026-11-14): "아이가 모두 내린 것 같아요. 확인해 주세요." / 제목 "아이가 모두 내린 것 같아요" + 기준 줄
    //   (예전 "앉은 신호가 모두 꺼졌어요…" / "모두 내렸어요(착석 신호 기준)"). 단정하지 않고 보호자 확인을 청한다.
    // r10c(사용자 결정 11-15 14:10): 손님이 함께 탄 이동(등록 아이 1명 이상 + 마무리 대상 자리에 손님)만 '아이가'를 뺐다.
    // r10d A(사용자 지시 "운전석 빼고 다 내리면 모두 내린것 같아요로 하자"): 신호로 끝난 마무리는 **모든 이동에서** '아이가' 없이.
    //   기준 줄("(문·착석 신호 기준)"/"(착석 신호 기준)")은 그대로. 보호자 버튼 끝냄("이번 이동을 마칠게요"·"(보호자 확인)")은 그대로.
    // 판정 규칙([onOccupancy], 마무리 대상 자리의 착석·문 신호)은 이 문구와 무관하다(ExitDoorRule).
    /** r10d A 스위치. false면 r10c 동작(손님이 함께 탄 이동만 '아이가' 없음, 나머지는 결정 38) */
    const val FINISH_TEXT_ALL = true
    /** 공통 하차 마무리 음성(말풍선도 같은 문장) */
    const val TEXT_ALL_LEFT_COMMON = "모두 내린 것 같아요. 확인해 주세요."
    /** 공통 하차 마무리 자막(제목 + 착석 기준) */
    const val TITLE_ALL_LEFT_COMMON = "모두 내린 것 같아요(착석 신호 기준)"
    /** 결정 38 음성('아이가' 있음). 스위치를 끈 r10c에서 손님 동승이 아닌 이동 */
    const val TEXT_ALL_LEFT_KID = "아이가 모두 내린 것 같아요. 확인해 주세요."
    /** 결정 38 자막('아이가' 있음) */
    const val TITLE_ALL_LEFT_KID = "아이가 모두 내린 것 같아요(착석 신호 기준)"
    /** 하차 마무리 음성 기본값(손님 동승 여부를 모를 때). 스위치 켬 = 공통, 끔 = 결정 38 */
    val TEXT_ALL_LEFT: String = if (FINISH_TEXT_ALL) TEXT_ALL_LEFT_COMMON else TEXT_ALL_LEFT_KID
    /** 하차 마무리 화면 자막 기본값(제목 + "(착석 신호 기준)"). 스위치 켬 = 공통, 끔 = 결정 38 */
    val TITLE_ALL_LEFT: String = if (FINISH_TEXT_ALL) TITLE_ALL_LEFT_COMMON else TITLE_ALL_LEFT_KID

    // ---- 스위치를 끈 r10c 경로에서만 쓰는 손님 동승 판단 ----
    /** 마무리 대상 자리 가운데 손님(id -1)이 배정된 자리 */
    fun guestFinishSeats(s: TripGuideState): Set<String> =
        s.occupiedAtConfirm.filter { SeatCheckRule.isGuest(s.assigned[it]) }.toSet()

    /** (r10c) 손님이 함께 탄 이동인가. [hasRegistered] = 등록 아이가 1명 이상(SeatBoardRules.hasRegistered) */
    fun guestRide(s: TripGuideState, hasRegistered: Boolean): Boolean = hasRegistered && guestFinishSeats(s).isNotEmpty()

    /** 하차 마무리 음성: 스위치 켬이면 늘 공통 문장, 끔이면 손님 동승만 공통(r10c) 나머지는 결정 38 */
    fun allLeftText(guestRide: Boolean, all: Boolean = FINISH_TEXT_ALL): String =
        if (all || guestRide) TEXT_ALL_LEFT_COMMON else TEXT_ALL_LEFT_KID

    /**
     * (r10c, 스위치 끔일 때만) 화면 계약 행에 손님 표시를 남긴다. 모두 내린 뒤에는 빈 칸이라 childId가 비어(SeatCheckRule.shownChild)
     * markGuests만으로는 guest가 늘 false다. 마무리 화면([allLeft])에서 마무리 대상 손님 자리 행에 guest=true를 둔다(childId는 그대로 null —
     * 빈 칸에 "손님"을 그리지 않음, SeatBoardRules.guestShown). 스위치 켬(공통 문구)·손님 자리 없음이면 같은 목록
     */
    fun markFinishGuests(rows: List<SeatCheckRow>, s: TripGuideState, allLeft: Boolean, all: Boolean = FINISH_TEXT_ALL): List<SeatCheckRow> {
        if (all || !allLeft) return rows
        val seats = guestFinishSeats(s)
        if (seats.isEmpty()) return rows
        return rows.map { if (it.seat in seats && !it.guest) it.copy(guest = true) else it }
    }
    /** 확인 완료 뒤 정차 중 화면 문구 */
    const val TEXT_READY_TO_GO = "출발하면 안내할게요"

    /**
     * 확인 완료 음성(2026-11-13 B): "<신호 확인 문구> 출발하면 안내할게요." 뒤에 결정 14 예정 시간 안내가 있으면 붙인다.
     * 예전에는 아이 안내("…같이 벨트를 확인해 볼까요")를 이어 붙여 확인 뒤에 다시 확인을 권하는 어색한 문장이 됐다.
     */
    fun confirmSpoken(doneText: String, eta: TripSay?): String =
        "${doneText.trim()} $TEXT_READY_TO_GO." + (eta?.let { " ${it.text}" } ?: "")

    /** 초 → 분(올림, 최소 1). null·음수는 null */
    fun ceilMin(sec: Int?): Int? = if (sec == null || sec < 0) null else maxOf(1, (sec + 59) / 60)

    /** 2026-11-14 PM 결정 23 정리: 이동 상태의 배정·선택 좌석에서 앞줄(배정 불가 자리)을 지운다. 지울 것이 없으면 같은 객체 */
    fun dropFrontAssignments(s: TripGuideState): TripGuideState {
        val a = s.assigned.filterKeys { SeatCheckRule.isAssignable(it) }
        val sel = s.selected.filter { SeatCheckRule.isAssignable(it) }.toSet()
        return if (a.size == s.assigned.size && sel.size == s.selected.size) s else s.copy(assigned = a, selected = sel)
    }

    /** 새 확인 시작: 안내 흐름은 비우고 내비 시간 값만 남긴다(경로가 확인 전에 시작됐을 수 있다) */
    fun keepTimes(prev: TripGuideState): TripGuideState =
        TripGuideState(totalSec = prev.totalSec, remainSec = prev.remainSec, etaSource = prev.etaSource)

    /** 확인 완료. 이전 이동 상태는 버리고 새로 시작한다(내비 시간 값은 유지) */
    fun onConfirmed(occupied: Set<String>, occUnknown: Boolean, byGuardian: Boolean, prev: TripGuideState = TripGuideState(),
                    selected: Set<String> = emptySet(), guardianSeats: Set<String> = emptySet(),
                    assigned: Map<String, Int> = emptyMap(), guardianUnbuckledSeats: Set<String> = emptySet()): TripGuideState =
        keepTimes(prev).copy(confirmed = true, byGuardian = byGuardian, occupiedAtConfirm = occupied, occUnknownAtConfirm = occUnknown,
            selected = selected, guardianSeats = guardianSeats, assigned = assigned, guardianUnbuckledSeats = guardianUnbuckledSeats)

    /**
     * 확인 완료 직후 예정 시간 안내(결정 14). 목적지를 미리 설정해 유효한 시간 값이 있으면 1회, 없으면 아무것도 하지 않는다.
     * 호출자는 반환된 안내를 확인 완료 음성 끝에 붙인다([confirmSpoken]).
     */
    fun onConfirmEta(s: TripGuideState): TripGuideStep {
        val r = s.remainSec ?: s.totalSec
        if (!s.confirmed || s.etaSaid || s.arrivalSignal || r == null || r <= 0) return TripGuideStep(s, emptyList())
        return TripGuideStep(s.copy(etaSaid = true), listOf(etaTotalSay(r)))
    }

    private fun etaTotalSay(sec: Int) = TripSay(TripSayKind.ETA_TOTAL, etaTotalText(ceilMin(sec) ?: 1))

    /** 경로 시작(총 시간). 남은 시간도 같은 값으로 둔다 */
    fun onRouteStarted(s: TripGuideState, totalSec: Int?, source: SignalSource): TripGuideStep {
        val t = NaviTimeRule.usable(totalSec)   // 0 이하 = 값 없음(PM 결정 12): 시간 없는 출발 안내
        return onRemain(s.copy(totalSec = t, etaSource = source, routeAfterArrival = s.routeAfterArrival || s.arrivalSignal), t, source)
    }

    /** 경로 취소·종료: 시간 값만 비운다(안내 흐름은 그대로) */
    fun onRouteCleared(s: TripGuideState): TripGuideState = s.copy(totalSec = null, remainSec = null, routeAfterArrival = false)

    // ---- r10 L(2026-11-15, 사용자 지시): 도착 뒤 다시 출발 = 새 구간 ----
    // 예전(r9b): 도착(내비 도착 신호 또는 "도착했나요?" 예) 뒤 하차 마무리 전에 D로 출발하면 arrivalSignal이 남아
    //  5분·1분·예정 시간 안내가 다시 나오지 않고(onRemain이 도착 신호 뒤를 거름), 다음 P에서도 질문(ArrivalAskRule.stopTrip)·
    //  경로 판정(결정 41 RouteLiveRule.needsCheck)·교통 상식이 꺼진 채 '도착' 화면으로 돌아왔다. 벨트 감시는 r8 2절(tripActive)대로 다시 켜졌다.
    // 이제: 도착 안내가 나간 이동에서 배정 자리(확인 때 착석)에 착석 신호가 남은 채 주행을 보면 도착 표시를 지우고 같은 이동의 새 구간으로 잇는다.
    //  - 이동 번호·확인 상태·배정·벨트 감시 자리·스티커(이동당 1회)는 그대로. 출발 안내("출발할게요")는 다시 하지 않는다.
    //  - 도착한 경로의 시간 값은 버린다. 도착 뒤에 새 경로가 시작됐으면([TripGuideState.routeAfterArrival]) 그 값은 남기고 예정 시간을 1회 다시 말한다.
    //  - 그 뒤 새 경로가 시작되면 onRouteStarted → 예정 시간 1회 → 5분·1분 → 도착(onArrived)이 처음 구간과 같이 다시 동작한다.
    //  - 경로가 없으면 다음 유효 P에서 ArrivalAskRule이 "목적지에 도착했나요?"를 다시 묻는다(도착 신호가 지워졌으므로).
    //  - 착석 신호가 모두 비었거나 확인 불가면 예전과 같다(확인 불가를 '앉음'으로 바꾸지 않는다).
    // 되돌리기: [NEW_LEG_AFTER_ARRIVAL] = false면 r9b와 같다.

    /** r10 L 스위치 */
    const val NEW_LEG_AFTER_ARRIVAL = true

    /** 새 구간 판단에 쓰는 자리: 확인 때 착석이던 배정 자리(하차 마무리 대상), 없으면 배정 자리 */
    fun legSeats(s: TripGuideState): Set<String> = s.occupiedAtConfirm.ifEmpty { s.assigned.keys }.filter { SeatCheckRule.isAssignable(it) }.toSet()   // r10d B: 조수석(마무리 대상)은 빼고 아이 자리만

    /**
     * 도착 뒤 다시 출발인가. [driving] = 지금 유효한 비-P 기어(호출자가 onGear 전에 부른다 — 그래서 [TripGuideState.drivingNow]는 아직 false),
     * [occ] = [legSeats]의 지금 착석 신호. 도착 안내가 나간(arrivedSaid) 이동 + 하차 마무리 전 + 배정 자리 하나 이상 '앉음'.
     */
    fun newLegDue(s: TripGuideState, driving: Boolean, occ: Map<String, SeatOcc>, enabled: Boolean = NEW_LEG_AFTER_ARRIVAL): Boolean =
        enabled && driving && s.confirmed && s.departed && !s.drivingNow && s.arrivalSignal && s.arrivedSaid && !s.allLeftSaid &&
            legSeats(s).any { occ[it] == SeatOcc.OCCUPIED }

    /** 새 구간 시작: 도착 표시·안내 소진을 지운다. 도착 뒤 시작한 경로의 시간 값이 있으면 예정 시간 1회(주행 중 음성만) */
    fun onNewLeg(s: TripGuideState): TripGuideStep {
        val keep = s.routeAfterArrival
        var st = s.copy(arrivalSignal = false, arrivedSaid = false, etaSaid = false,
            seenAbove5 = false, said5 = false, seenAbove1 = false, said1 = false,
            totalSec = if (keep) s.totalSec else null, remainSec = if (keep) s.remainSec else null,
            etaSource = if (keep) s.etaSource else SignalSource.NONE, routeAfterArrival = false)
        val r = st.remainSec ?: st.totalSec
        if (r == null || r <= 0) return TripGuideStep(st, emptyList())
        // 출발 때와 같이: 임계값 위면 관측, 이하면 그 안내는 소진
        st = st.copy(etaSaid = true, seenAbove5 = r > FIVE_MIN_SEC, said5 = r <= FIVE_MIN_SEC,
            seenAbove1 = r > ONE_MIN_SEC, said1 = r <= ONE_MIN_SEC)
        return TripGuideStep(st, listOf(etaTotalSay(r)))
    }

    /**
     * 2026-11-14 r5 5절: 이동 경계(끝내기·모두 내림 뒤 새 이동)에서 다음 이동으로 넘길 상태.
     * 안내 흐름은 비우고, 앞 이동의 내비 경로가 아직 살아 있으면 마지막 유효 시간 값만 넘긴다([keepTimes]).
     * 그래야 내비 안내를 끄지 않고 [이번 이동 끝내기] → [시작]한 새 이동의 확인 완료 음성 끝에 예정 시간이 붙는다([onConfirmEta]).
     * (예전에는 경계에서 상태를 통째로 비워, 새 값이 올 때까지 시간이 없어 13초 뒤 따로 나갔다 — 22:41 로그)
     * '경로 살아 있음' 조건(PM 주의 2026-11-14, 개발 A 결정 41(사용자 확정(11-15))과 같은 원칙): 다음 중 하나라도 있으면 옛 값을 버린다.
     *  - 앞 이동에 도착 신호([TripGuideState.arrivalSignal])
     *  - 이동 끝 신호(도착·결정 18로 거르지 않은 경로 종료, [endSignal] = TripEndState.signalReceived)
     *  - 경로 상태 조회 결과 경로 없음([routeAlive] = false. 개발 A 결정 41의 조회 결과를 통합 때 넘긴다, 모르면 null)
     *  경로 취소는 [onRouteCleared]가 이미 값을 비웠다. 넘긴 뒤에도 출발 전에 경로 끝·경로 없음(남은 시간 0 이하 응답)이 오면 [onNoRoute]가 비운다.
     */
    fun nextTrip(prev: TripGuideState, endSignal: Boolean, routeAlive: Boolean? = null): TripGuideState =
        if (endSignal || prev.arrivalSignal || routeAlive == false) TripGuideState() else keepTimes(prev)

    /**
     * 2026-11-14 r5 5절: 출발 전(이동 경계 뒤 새 확인 중 등)에 내비가 경로가 없다고 알리면 남겨 둔 시간 값은 끝난 경로의 값이다 → 비운다.
     * 경로 없다는 근거 = 도착·경로 종료 신호(결정 18로 거른 것 제외) 또는 남은 시간 0 이하 응답(결정 12: 0 이하 = 경로 없음, ISSUE-05 관찰).
     * 출발한 이동에서는 그대로 둔다(5분·1분·도착 흐름은 결정 12·[onArrived] 그대로, 경계에서 [nextTrip]이 정리).
     */
    fun onNoRoute(s: TripGuideState): TripGuideState = if (s.departed) s else onRouteCleared(s)

    /**
     * 남은 시간 갱신. 출발 뒤·도착 전에만 5분·1분을 판단한다.
     * 확인 완료 뒤 예정 시간 안내(결정 14)를 아직 안 했으면 이 값으로 1회 한다(출발 전·주행 중 모두, 도착 신호 뒤는 제외).
     * 0 이하는 '값 없음'(PM 결정 12, [NaviTimeRule]): 상태·분·안내 소진 모두 그대로 둔다. 도착은 도착 신호로만.
     */
    fun onRemain(s: TripGuideState, remainSec: Int?, source: SignalSource): TripGuideStep {
        val r = NaviTimeRule.usable(remainSec)
        var st = s.copy(remainSec = r ?: s.remainSec, etaSource = if (r != null) source else s.etaSource)
        if (r == null || st.arrivalSignal) return TripGuideStep(st, emptyList())
        val says = ArrayList<TripSay>()
        if (st.confirmed && !st.etaSaid) {
            st = st.copy(etaSaid = true)
            says += etaTotalSay(r)
        }
        if (!st.departed) return TripGuideStep(st, says)
        val cross5 = st.seenAbove5 && !st.said5 && r <= FIVE_MIN_SEC
        val cross1 = st.seenAbove1 && !st.said1 && r <= ONE_MIN_SEC
        when {
            cross1 -> {   // 1분 통과(같은 갱신에서 5분도 통과했으면 1분만 말하고 5분은 소진)
                st = st.copy(said1 = true, said5 = true)   // r ≤ 1분이면 5분 안내는 더 이상 의미가 없다(소진)
                says += TripSay(TripSayKind.ETA1, etaText(ceilMin(r) ?: 1))
            }
            cross5 -> {
                st = st.copy(said5 = true)
                says += TripSay(TripSayKind.ETA5, etaText(ceilMin(r) ?: 1))
            }
        }
        // 임계값 위 관측(경로 재계산으로 다시 길어진 경우 포함, 이미 말한 것은 다시 말하지 않음)
        if (r > FIVE_MIN_SEC && !st.said5) st = st.copy(seenAbove5 = true)
        if (r > ONE_MIN_SEC && !st.said1) st = st.copy(seenAbove1 = true)
        return TripGuideStep(st, says)
    }

    /**
     * 기어·게이트 변화. driving = 유효한 비-P 기어(링크 정상), parkAllowed = 정차 허용 게이트.
     * 확인 완료된 이동에서 처음 주행을 보면 출발 안내, 출발 뒤 정차 허용이면 미뤄 둔 도착 안내.
     */
    fun onGear(s: TripGuideState, driving: Boolean, parkAllowed: Boolean, nowMs: Long): TripGuideStep {
        var st = s
        val says = ArrayList<TripSay>()
        if (driving) {
            st = st.copy(drivingNow = true, parkedSinceMs = -1L)
            if (st.confirmed && !st.departed) {
                st = st.copy(departed = true)
                val r = st.remainSec ?: st.totalSec
                // 출발 때 이미 임계값 이하면 그 안내는 생략(소진). 위면 관측으로 기록
                if (r != null) {
                    st = if (r > FIVE_MIN_SEC) st.copy(seenAbove5 = true) else st.copy(said5 = true)
                    st = if (r > ONE_MIN_SEC) st.copy(seenAbove1 = true) else st.copy(said1 = true)
                }
                says += TripSay(TripSayKind.DEPART, TEXT_DEPART)   // 결정 14: 시간은 예정 시간 안내 1회로만
            }
        } else if (st.departed) {
            st = st.copy(drivingNow = false)
            if (parkAllowed && st.parkedSinceMs < 0) st = st.copy(parkedSinceMs = nowMs)
            if (!parkAllowed) st = st.copy(parkedSinceMs = -1L)
        }
        if (parkAllowed && st.departed && st.arrivalSignal && !st.arrivedSaid) {
            st = st.copy(arrivedSaid = true)
            // 루프 r5 1절: 앱이 뒤에 있어도 같은 문장. "앱을 열어…"는 없앴고, 호출자가 앱을 앞으로 올린다(AppFrontRule)
            says += TripSay(TripSayKind.ARRIVED, TEXT_ARRIVED_SPOKEN)
        }
        return TripGuideStep(st, says)
    }

    /** 내비 도착 신호. 출발한 이동에서만 기록하고, 정차 허용이면 바로 안내한다(주행 중이면 P까지 미룸) */
    fun onArrived(s: TripGuideState, parkAllowed: Boolean, driving: Boolean, nowMs: Long): TripGuideStep {
        if (!s.departed || s.arrivalSignal) return TripGuideStep(s, emptyList())
        return onGear(s.copy(arrivalSignal = true), driving, parkAllowed && !driving, nowMs)
    }

    /**
     * 착석 신호 갱신(유효 P에서). 확인 때 착석이던 좌석이 모두 EMPTY면 하차 마무리 1회.
     * 도착 신호가 있었으면 도착 안내가 나간 뒤에만. 확인 불가(UNKNOWN)가 하나라도 있으면 하지 않는다.
     * 2026-11-15 r6 4절: [door]를 주면 '내렸다' = 착석 EMPTY + 이번 정차에 뒷문 열림([ExitDoorRule.allLeft], 결정 45·46).
     * null이면 예전 규칙(착석만).
     */
    fun onOccupancy(s: TripGuideState, occ: Map<String, SeatOcc>, parkAllowed: Boolean, door: ExitDoorState? = null,
                    guestRide: Boolean = false): TripGuideStep {
        if (!finaleReady(s, parkAllowed)) return TripGuideStep(s, emptyList())
        if (!ExitDoorRule.allLeft(door, s.occupiedAtConfirm, occ).allLeft) return TripGuideStep(s, emptyList())
        // r10d A: 공통 문구(스위치 끔이면 r10c — 손님이 함께 탄 이동([guestRide])만 '아이가' 없이)
        return TripGuideStep(s.copy(allLeftSaid = true), listOf(TripSay(TripSayKind.ALL_LEFT, allLeftText(guestRide))))
    }

    private fun finaleReady(s: TripGuideState, parkAllowed: Boolean): Boolean =
        s.confirmed && s.departed && !s.drivingNow && parkAllowed && !s.allLeftSaid &&
            !s.occUnknownAtConfirm && s.occupiedAtConfirm.isNotEmpty() && (!s.arrivalSignal || s.arrivedSaid)

    /** 하차 마무리를 할 수 있는 이동인가(착석 신호 기준). false면 기존 하차 안내만 */
    fun finalePossible(s: TripGuideState): Boolean = s.confirmed && !s.occUnknownAtConfirm && s.occupiedAtConfirm.isNotEmpty()

    /** 이 이동에서 기존 하차 한 줄(ExitCheckRule)을 내지 않을지: 도착 신호를 받은 이동은 도착 안내에 합쳤다 */
    fun suppressExitLine(s: TripGuideState): Boolean = s.departed && s.arrivalSignal

    /** 발화가 끝났다는 알림(표시 단계용) */
    fun onSpoken(s: TripGuideState, kind: TripSayKind): TripGuideState =
        if (kind == TripSayKind.DEPART) s.copy(departSpoken = true) else s

    /** 표시 단계. 확인 전이면 null */
    fun phase(s: TripGuideState): AutoPhase? = when {
        !s.confirmed -> null
        s.allLeftSaid -> AutoPhase.ALL_LEFT
        !s.departed -> AutoPhase.CONFIRMED
        s.drivingNow -> if (s.departSpoken) AutoPhase.DRIVING else AutoPhase.DEPARTING
        s.arrivalSignal -> AutoPhase.ARRIVED
        else -> AutoPhase.EXITING
    }

    fun eta(s: TripGuideState): TripEta? =
        if (s.totalSec == null && s.remainSec == null) null
        else TripEta(ceilMin(s.totalSec), ceilMin(s.remainSec), if (s.etaSource == SignalSource.NONE) SignalSource.REAL else s.etaSource)
}

/** 프로세스 보관소(Activity 재생성에도 이번 이동 안내 상태 유지). 메인 스레드 전용. 프로세스가 끝나면 사라진다(재시작 뒤 출발·도착 안내 없음) */
object TripGuideStore {
    var state = TripGuideState()
    /** 2026-11-13 2단계: 두고 내림 방지(D)·주행 중 벨트 풀림(B) 상태(TripAlerts.kt). 이동 상태와 같이 초기화한다 */
    var alerts = TripAlertState()
}
