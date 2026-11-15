package com.crewguide.probe

/**
 * 2026-11-14 PM 임시 결정 34(사용자 확인 대기, 루프 r1 J2, 계약 v4): '모두 내림'(ALL_LEFT) 뒤 뒷줄에 다시 앉은 신호.
 * - [reseated]: phase == ALL_LEFT + 뒷줄(ROW_2_*) 착석 '앉음'(OCCUPIED) 1석 이상 + 유효 P·플랫폼 허용. 다시 비면 false.
 *   앞줄·운전석 착석, 확인 불가(UNKNOWN)는 근거로 쓰지 않는다. 화면은 부제와 [새로 출발 확인]만 바꾼다(자동 전환·음성 없음).
 * - [acceptNewTrip]: [새로 출발 확인]을 누른 순간 같은 조건을 다시 본다. 받으면 MainActivity가 결정 29·30 경로(하차 마무리 도달 = 끝난 이동)로
 *   이동 번호를 넘기고(스티커는 이미 지급한 그대로, 이동당 1회 규칙으로 중복 0) 새 확인(CHECKING)을 시작한다.
 */
object ReseatRule {
    fun isRear(seat: String): Boolean = seat.startsWith("ROW_2_")

    fun reseated(phase: AutoPhase?, occ: Map<String, SeatOcc>, parkAllowed: Boolean): Boolean =
        phase == AutoPhase.ALL_LEFT && parkAllowed && occ.any { (seat, o) -> isRear(seat) && o == SeatOcc.OCCUPIED }

    fun acceptNewTrip(phase: AutoPhase?, occ: Map<String, SeatOcc>, parkAllowed: Boolean): Boolean = reseated(phase, occ, parkAllowed)
}

/**
 * r10 K(2026-11-15, 사용자 지시): "아니요, 계속 가요"로 답한 정차에서 하차 마무리(ALL_LEFT)가 된 뒤, 기어가 P인 채로 배정 자리에 모두 다시 앉으면
 * [새로 출발 확인] 없이 이동을 되살린다(잠깐 내렸다 다시 탄 것). 순수 규칙.
 *
 * 규칙
 *  - 무장([onAllLeft]): 하차 마무리 순간 이번 정차의 최종 대답이 '아니요'([ArrivalAskRule.answeredContinue] — "내릴까요?" 아니요,
 *    "도착했나요?" 아니요 뒤 "내릴까요?" 아니요, '예' 뒤 되돌리기) + 도착 신호 없음 + "내릴까요?" 예 아님 + 보호자 버튼 마무리 아님.
 *    '예, 내려요'·도착(신호·'예, 도착했어요')으로 끝난 하차는 무장하지 않는다 → 기존 [새로 출발 확인](ReseatRule).
 *  - 되살림([evaluate] = REVIVE): 무장 + 같은 정차(그 뒤 주행 없음, 같은 이동 번호, 아직 ALL_LEFT, 대답 상태 그대로) + 마무리 뒤 [WINDOW_MS] 안
 *    + 유효 P·허용 + 하차 마무리 대상 자리(확인 때 착석, [TripGuideState.occupiedAtConfirm])가 **모두** 착석 신호 '앉음'.
 *    착석 지도는 r9 기준선을 뺀 지도(ExitDoorRule.reseatOcc)를 쓴다 — 대상 자리는 마무리 때 비어 있었으므로 기준선에 들지 않아 충돌이 없다.
 *    일부만 앉으면 기다린다(그동안 기존 reseatedAfterAllLeft·[새로 출발 확인]이 그대로 보인다). 확인 불가는 '앉음'이 아니다.
 *  - 주행·시간 지남·이동 경계·대답 상태가 바뀌면 EXPIRED(무장 풀림, 이후는 기존 [새로 출발 확인]).
 *  - 되살린 뒤: allLeftSaid만 되돌린다(확인·출발·배정·대답 상태 그대로 → 계약 v5.1 pausedByAnswer "잠깐 멈췄어요" 화면).
 *    "다시 앉은 신호가 있어요. 이어서 안내할게요." 한 줄([TEXT], 말풍선 포함, 착석 신호 기준 — 탑승을 단정하지 않는다). 벨트는 다시 본다([RECHECK_BELT]): 대상 자리 중 미체결 신호 자리는
 *    기존 재안내 문장 1회(보호자가 미체결로 확인한 자리는 뺌), 다시 주행하면 r8 2절 감시 재개(다시 출발 때 이미 미체결이면 안내).
 *  - **정차당 1회**(r10 수정, 검수 지적): 되살린 뒤에도 이번 정차 문 열림 기록이 남아, 아이가 일어났다 앉을 때마다
 *    "모두 내린 것 같아요" ↔ 되살림이 되풀이될 수 있다 → 한 정차에서 자동 되살림은 한 번만([State.revivedThisStop]).
 *    같은 정차의 다음 마무리는 무장하지 않는다(기존 [새로 출발 확인]). 주행을 보거나 이동 번호가 바뀌면 다시 받는다([onDrive]).
 *  - 이동 서비스(FGS) 유지(r10 수정 2, QA 에뮬 관찰): 무장한 마무리에서는 서비스를 끄지 않고 되살림 창([WINDOW_MS]) 동안 유지한다
 *    ([holdsKeepAlive], 스위치 [KEEP_FGS_IN_WINDOW]). "계속 가요" 뒤 앱이 내비 뒤로 가 있으면 서비스 없이 동결돼 다시 앉은 신호를 받지 못했다.
 *    창이 끝나거나 무장이 풀리면(주행·이동 경계·되살림 1회 소진 뒤 다음 마무리) 그때 끈다. 알림 문구는 기존 이동 알림 그대로.
 *  - 스티커는 이동당 1회 그대로(마무리 때 줬으면 되돌리지 않고, 다음 마무리에서 다시 주지 않음).
 * 되돌리기: [ENABLED] = false면 r9b와 같다(무장하지 않음). 벨트 다시 보기만 끄려면 [RECHECK_BELT] = false.
 */
object ContinueReseatRule {
    /** r10 K 스위치 */
    const val ENABLED = true
    /** 마무리 뒤 자동 되살림을 받는 시간(10분) */
    const val WINDOW_MS = 10 * 60_000L
    /** 되살린 뒤 미체결 신호 자리에 재안내 1회 */
    const val RECHECK_BELT = true
    /** r10 수정 2: 무장한 하차 마무리에서 되살림 창 동안 이동 서비스(FGS)를 유지. false면 예전처럼 마무리 때 끈다 */
    const val KEEP_FGS_IN_WINDOW = true
    /** 서비스 종료 사유(로그) */
    const val STOP_WINDOW_END = "다시탐 창 끝"
    const val STOP_DISARMED = "다시탐 무장 풀림"
    const val TEXT = "다시 앉은 신호가 있어요. 이어서 안내할게요."
    const val VOICE_LABEL = "다시탐"

    /** [revivedThisStop] = 이번 정차에서 이미 자동으로 되살렸음([tripKey]의 이동, 주행을 보면 지운다) */
    data class State(val armed: Boolean = false, val allLeftAtMs: Long = -1L, val tripKey: Int = -1, val revivedThisStop: Boolean = false)

    enum class Verdict { NONE, REVIVE, EXPIRED }

    /** 이 정차가 '계속 가요'로 답한 정차인가 */
    fun continuedStop(ask: ArrivalAskRule.State, tg: TripGuideState): Boolean =
        ArrivalAskRule.answeredContinue(ask) && ask.alightAnswer != ArrivalAskRule.Answer.YES && !tg.arrivalSignal

    /**
     * 하차 마무리 순간(tg = 마무리 뒤 상태, [prev] = 그때까지의 보관 상태). 조건이 아니면 무장하지 않은 상태.
     * 같은 이동의 같은 정차에서 이미 되살렸으면([prev].revivedThisStop) 무장하지 않고 그 표시만 남긴다(정차당 1회)
     */
    fun onAllLeft(ask: ArrivalAskRule.State, tg: TripGuideState, tripKey: Int, nowMs: Long, byGuardian: Boolean,
                  enabled: Boolean = ENABLED, prev: State = State()): State {
        if (prev.revivedThisStop && prev.tripKey == tripKey) return State(tripKey = tripKey, revivedThisStop = true)
        return if (enabled && tg.allLeftSaid && !byGuardian && continuedStop(ask, tg)) State(armed = true, allLeftAtMs = nowMs, tripKey = tripKey)
        else State()
    }

    /** 되살린 직후 보관 상태: 무장 풀림 + 이번 정차는 되살림을 썼음 */
    fun afterRevive(s: State): State = State(tripKey = s.tripKey, revivedThisStop = true)

    /** 주행 = 정차 경계: 무장과 '이번 정차 되살림 씀' 표시를 모두 지운다 */
    fun onDrive(s: State): State = if (s == State()) s else State()

    fun evaluate(s: State, tg: TripGuideState, ask: ArrivalAskRule.State, occ: Map<String, SeatOcc>, parkOk: Boolean, driving: Boolean,
                 tripKey: Int, nowMs: Long): Verdict {
        if (!s.armed) return Verdict.NONE
        if (driving || tg.drivingNow || !tg.allLeftSaid || tripKey != s.tripKey || !continuedStop(ask, tg)) return Verdict.EXPIRED
        if (nowMs - s.allLeftAtMs > WINDOW_MS) return Verdict.EXPIRED
        if (!parkOk) return Verdict.NONE
        val seats = tg.occupiedAtConfirm
        if (seats.isEmpty() || !seats.all { occ[it] == SeatOcc.OCCUPIED }) return Verdict.NONE
        return Verdict.REVIVE
    }

    /**
     * r10 수정 2: 이동 서비스를 유지할지(TripKeepAliveRule.decide의 tailHold에 더한다). 무장 + 아직 마무리 화면(ALL_LEFT) + 주행 아님 + 창 안.
     * 이동 중이 아니므로 decide는 START를 하지 않고 STOP만 미룬다(H 상한 capped와 무관, 끈 서비스를 다시 켜지 않는다)
     */
    fun holdsKeepAlive(s: State, tg: TripGuideState, nowMs: Long, enabled: Boolean = KEEP_FGS_IN_WINDOW): Boolean =
        enabled && s.armed && tg.allLeftSaid && !tg.drivingNow && s.allLeftAtMs >= 0 && nowMs - s.allLeftAtMs <= WINDOW_MS

    /** 창이 끝나기까지 남은 시간(무장 아님이면 null, 이미 지났으면 0) — 창 끝 점검 예약용 */
    fun windowLeftMs(s: State, nowMs: Long): Long? =
        if (!s.armed || s.allLeftAtMs < 0) null else (s.allLeftAtMs + WINDOW_MS - nowMs).coerceAtLeast(0L)

    /** 무장이 풀린 이유에 맞는 서비스 종료 사유: 창 시간이 지났으면 [STOP_WINDOW_END], 아니면 [STOP_DISARMED] */
    fun disarmReason(s: State, nowMs: Long): String =
        if (s.allLeftAtMs >= 0 && nowMs - s.allLeftAtMs > WINDOW_MS) STOP_WINDOW_END else STOP_DISARMED

    /** 이동 되살림: 하차 마무리 표시만 되돌린다 */
    fun revive(tg: TripGuideState): TripGuideState = tg.copy(allLeftSaid = false)

    /** 마무리 화면용으로 고정한 문 기준·기준선을 지운다(이번 정차 문 열림 기록은 남긴다 — 다시 내리면 같은 정차 기준으로 마무리) */
    fun reviveDoor(s: ExitDoorState): ExitDoorState =
        s.copy(finaleByDoor = false, finaleByGuardian = false, guardianSeatedAtFinish = emptySet(), guardianVacated = emptySet(),
            guardianSeatOccupied = false)

    /** 되살린 뒤 다시 볼 벨트: 대상 자리 중 미체결 신호(UNBUCKLED_SIGNAL)인 자리, 보호자가 미체결로 확인한 자리는 뺀다. 이름순 */
    fun unbuckledAfterReseat(seats: Set<String>, belt: Map<String, SeatBeltRef>, guardianUnbuckled: Set<String>): List<String> =
        seats.filter { ReseatRule.isRear(it) && belt[it] == SeatBeltRef.UNBUCKLED_SIGNAL && it !in guardianUnbuckled }.sorted()   // r10d B: 조수석 마무리 대상은 뺀다(앞자리 벨트는 기존 앞자리 규칙)
}

/** 프로세스 보관소(Activity 재생성에도 무장 유지). 메인 스레드 전용 */
object ContinueReseatStore {
    var state = ContinueReseatRule.State()
}
