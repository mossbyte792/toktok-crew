package com.crewguide.probe

// 2026-11-13 2단계(D·B) 순수 로직. Android·SDK 클래스를 쓰지 않는다. 시간은 인자로만 받는다.
// 발화·타이머(Handler)·로그는 호출자(MainActivity)가 한다. 문구는 신호 기준으로만 말하고 감지·안전을 보증하지 않는다.
//
// D 두고 내림 방지(LeftBehindRule)
//  - 출발한 이동(자동 확인 완료 → 주행)에서 유효 P(정차 허용 게이트, TripGuideState.parkedSinceMs)가 [FIRST_DELAY_MS] 이어졌는데
//    확인 때 착석이던 뒷좌석(ROW_2_*) 중 착석 신호(OCCUPIED)가 남은 좌석이 있으면 1회, 그 뒤 [SECOND_DELAY_MS] 지나도 남아 있으면 1회 더(최대 2회).
//  - 내비 도착 신호가 있었던 이동은 도착 안내가 나간 뒤에만 센다(도착 안내와 겹치지 않게).
//  - 착석 확인 불가(UNKNOWN)인 좌석은 근거로 쓰지 않는다. 확인 때 착석 확인 불가가 있었던 이동(occUnknownAtConfirm)은 하지 않는다.
//  - 하차 마무리(앉은 신호 모두 꺼짐)·이동 끝내기·주행 재개 때 취소한다(주행 재개 뒤 다시 정차하면 새로 센다).
//  - 정차 게이트에 전경 조건이 있어 앱이 화면 뒤(내비 앞)에 있으면 parkedSinceMs가 비어 세지 않는다(도착 안내와 같은 한계, 미검증).
// B 주행 중 벨트 풀림(BeltReleaseAlertRule)
//  - 주행 중(유효한 비-P 기어) 자동 확인 감시 세션(BeltWarningPolicy.armed)의 좌석이 체결 신호 → 미체결 신호로 바뀌면 좌석 이름으로 짧은 음성.
//  - 같은 좌석 최소 간격 [MIN_INTERVAL_MS](간격 안의 재풀림은 말하지 않음, 미루지 않음), 이동당 좌석별 최대 [MAX_PER_SEAT]회.
//  - 다시 체결돼도 그 좌석 횟수는 유지한다. 미체결이 아니라 확인 불가(null)로 바뀌는 것은 풀림으로 보지 않는다.
//  - 2026-11-14 PM 임시 결정 31: 풀림이 이어지면(다시 체결 안 함) 30초 간격으로 다시 알린다(onRepeat, 첫 안내 포함 최대 3회).
//  - 화면 변화는 없다(주행 중 S09 유지, 음성만).

data class LeftBehindState(
    /** 첫 안내 시각(없으면 -1) */
    val firstAtMs: Long = -1L,
    /** 이번 정차에서 말한 횟수(최대 2). r8 5절: 줄에 세울 때 미리 세고, 실제로 나가지 못하면 [LeftBehindRule.onNotDelivered]로 되돌린다 */
    val said: Int = 0,
    /** r8 5절: 마지막 안내를 줄에 세웠고 실제로 나갔는지(TTS 완료) 아직 모름. 이 동안은 새로 말하지 않는다 */
    val pending: Boolean = false,
)

sealed class LeftBehindDecision {
    /** 할 일 없음(조건 아님). 다시 볼 필요가 없거나 다른 이벤트를 기다린다 */
    object None : LeftBehindDecision() { override fun toString() = "None" }
    /** 이 시각에 다시 평가 */
    data class RecheckAt(val atMs: Long) : LeftBehindDecision()
    /** 지금 말한다(남은 좌석 화면 이름) */
    data class Speak(val seats: List<String>, val text: String) : LeftBehindDecision()
}

object LeftBehindRule {
    /** 정차 뒤 첫 안내까지(가안, 상수) */
    const val FIRST_DELAY_MS = 60_000L
    /** 첫 안내 뒤 두 번째 안내까지(가안, 상수) */
    const val SECOND_DELAY_MS = 120_000L
    const val MAX_SAYS = 2
    /** 신호 기준 문구. '아이가 있다'·'감지했다'고 단정하지 않는다 */
    const val TEXT = "뒷자리에 앉은 신호가 아직 있어요. 뒷자리를 한 번 더 확인해 주세요."
    /** 음성 줄 이름(MainActivity enqueueSay 라벨, 결과 콜백에서 이 안내를 알아보는 데 쓴다) */
    const val VOICE_LABEL = "두고내림"
    /** r8 5절: 결과를 기다리다 이 시간이 지나도 줄·재생 중에 없으면 못 나간 것으로 본다(포커스 거부·버림은 결과 콜백이 없다) */
    const val PENDING_CHECK_MS = 30_000L
    /** r8 5절: 못 나간 뒤 다시 시도하기까지(가안) */
    const val RETRY_MS = 30_000L

    fun isRear(seat: String): Boolean = seat.startsWith("ROW_2_")

    /**
     * 평가 1회. occ = 좌석별 현재 착석 신호. firstDelayMs·secondDelayMs는 상수 기본값(debug 시연만 짧게 바꿀 수 있다).
     * 돌려준 상태는 말했을 때만 바뀐다(취소·초기화는 [reset]).
     */
    fun step(s: LeftBehindState, tg: TripGuideState, occ: Map<String, SeatOcc>, nowMs: Long,
             firstDelayMs: Long = FIRST_DELAY_MS, secondDelayMs: Long = SECOND_DELAY_MS): Pair<LeftBehindState, LeftBehindDecision> {
        val none = s to LeftBehindDecision.None
        if (!tg.confirmed || !tg.departed || tg.drivingNow || tg.allLeftSaid || tg.occUnknownAtConfirm) return none
        if (tg.parkedSinceMs < 0) return none
        if (tg.arrivalSignal && !tg.arrivedSaid) return none
        if (s.said >= MAX_SAYS) return none
        if (s.pending) return none   // r8 5절: 앞 안내 결과를 기다리는 중(결과가 오면 호출자가 다시 평가한다)
        val rear = tg.occupiedAtConfirm.filter { isRear(it) }
        val remaining = rear.filter { occ[it] == SeatOcc.OCCUPIED }
        if (remaining.isEmpty()) return none   // 모두 꺼짐(하차 마무리 몫) 또는 확인 불가만 남음
        val due = if (s.said == 0) tg.parkedSinceMs + firstDelayMs else s.firstAtMs + secondDelayMs
        if (nowMs < due) return s to LeftBehindDecision.RecheckAt(due)
        val labels = remaining.sortedBy { SeatCheckRule.LABELS.keys.indexOf(it) }.map { SeatCheckRule.label(it) }
        val next = LeftBehindState(firstAtMs = if (s.said == 0) nowMs else s.firstAtMs, said = s.said + 1, pending = true)
        return next to LeftBehindDecision.Speak(labels, TEXT)
    }

    /** r8 5절: 안내가 실제로 나갔다(TTS 완료). 센 횟수를 확정한다 */
    fun onDelivered(s: LeftBehindState): LeftBehindState = if (s.pending) s.copy(pending = false) else s

    /**
     * r8 5절: 안내가 나가지 못했다(포커스 거부·음성 꺼짐·중단·오류·버림). 미리 센 횟수를 되돌려 다음 평가 때 다시 시도하게 한다.
     * 첫 안내였으면 첫 안내 시각도 지운다(두 번째 안내 간격은 실제로 나간 첫 안내부터 센다). 최대 횟수는 그대로다
     */
    fun onNotDelivered(s: LeftBehindState): LeftBehindState =
        if (!s.pending) s
        else LeftBehindState(firstAtMs = if (s.said <= 1) -1L else s.firstAtMs, said = (s.said - 1).coerceAtLeast(0), pending = false)

    /** 주행 재개·하차 마무리·이동 끝내기·새 확인 */
    fun reset(): LeftBehindState = LeftBehindState()
}

data class ReleaseAlertState(
    val counts: Map<String, Int> = emptyMap(),
    val lastAtMs: Map<String, Long> = emptyMap(),
    /** PM 임시 결정 31: 풀림이 이어지면 다시 알릴 좌석(풀림 안내 뒤 아직 다시 체결·확인 불가·정차가 아닌 좌석) */
    val repeat: Set<String> = emptySet(),
)

object BeltReleaseAlertRule {
    const val MIN_INTERVAL_MS = 30_000L
    const val MAX_PER_SEAT = 3

    fun text(seat: String): String = "${SeatCheckRule.label(seat)}, 벨트 신호가 풀렸어요"

    /**
     * 벨트 값 변화 1건. armed=자동 확인 감시 세션 좌석인가, wasBuckled=직전 유효값이 체결이었는가, driving=유효한 비-P 기어.
     * 반환 second=true면 지금 말한다(횟수·시각을 기록한 상태와 함께).
     */
    fun onChange(s: ReleaseAlertState, seat: String, armed: Boolean, wasBuckled: Boolean, newValue: Boolean?, driving: Boolean,
                 nowMs: Long): Pair<ReleaseAlertState, Boolean> {
        if (!armed || !driving || !wasBuckled || newValue != false) return s to false
        val n = s.counts[seat] ?: 0
        if (n >= MAX_PER_SEAT) return s.copy(repeat = s.repeat - seat) to false
        val last = s.lastAtMs[seat]
        // 간격 안의 재풀림은 지금 말하지 않는다. 결정 31: 풀림이 이어지면 간격이 찬 때 다시 알림(onRepeat)으로 말한다
        if (last != null && nowMs - last < MIN_INTERVAL_MS) return s.copy(repeat = s.repeat + seat) to false
        return s.copy(counts = s.counts + (seat to n + 1), lastAtMs = s.lastAtMs + (seat to nowMs), repeat = s.repeat + seat) to true
    }

    /**
     * 2026-11-14 PM 임시 결정 31: 주행 중 벨트 풀림이 이어지면(다시 체결 안 함) [MIN_INTERVAL_MS] 간격으로 다시 알린다.
     * 자리당 이동당 최대 [MAX_PER_SEAT]회(첫 안내 포함), 다시 체결해도 횟수는 그대로. 차임 + 음성, 화면 변화 없음(호출자).
     * stillReleased = 지금도 미체결 신호(앞줄은 경고등 On), armed·driving은 onChange와 같은 뜻.
     * 다시 체결·확인 불가·감시 대상 아님·주행 아님이면 이 좌석의 다시 알림을 멈춘다(다음 풀림 변화가 오면 onChange가 다시 건다).
     */
    fun onRepeat(s: ReleaseAlertState, seat: String, armed: Boolean, stillReleased: Boolean, driving: Boolean,
                 nowMs: Long): Pair<ReleaseAlertState, Boolean> {
        // PM 검토: 예약이 일찍 오거나 다른 경로에서 불려도 마지막 안내 뒤 간격 안에는 다시 말하지 않는다(상태도 그대로)
        val last = s.lastAtMs[seat]
        if (last != null && nowMs - last < MIN_INTERVAL_MS) return s to false
        if (seat !in s.repeat) return s to false
        val n = s.counts[seat] ?: 0
        if (!armed || !driving || !stillReleased || n >= MAX_PER_SEAT) return s.copy(repeat = s.repeat - seat) to false
        val n2 = n + 1
        val rep = if (n2 >= MAX_PER_SEAT) s.repeat - seat else s.repeat
        return ReleaseAlertState(s.counts + (seat to n2), s.lastAtMs + (seat to nowMs), rep) to true
    }

    /** 루프 r2 L4: 다시 체결(앞줄은 경고등 꺼짐) 신호를 받은 즉시 그 좌석의 다시 알림을 멈춘다(횟수는 이동당 그대로). 멈춘 게 있으면 true */
    fun onRebuckle(s: ReleaseAlertState, seat: String): Pair<ReleaseAlertState, Boolean> =
        if (seat in s.repeat) s.copy(repeat = s.repeat - seat) to true else s to false

    /** 다시 알림 로그의 회차 문구: 마지막(상한) 회차면 더 알리지 않는다는 말을 붙인다 */
    fun countText(n: Int): String = if (n >= MAX_PER_SEAT) "$n/${MAX_PER_SEAT}회째(마지막, 이번 이동에서 이 자리는 더 알리지 않음)" else "$n/${MAX_PER_SEAT}회째"

    /** 다음 다시 알림 평가 시각(없으면 null). 다시 알릴 좌석 중 가장 이른 '마지막 안내 + 간격' */
    fun nextRepeatAt(s: ReleaseAlertState): Long? =
        s.repeat.filter { (s.counts[it] ?: 0) < MAX_PER_SEAT }.mapNotNull { s.lastAtMs[it]?.plus(MIN_INTERVAL_MS) }.minOrNull()
}

/** 이번 이동의 D·B 상태. TripGuideStore와 같이 프로세스 메모리에 둔다(메인 스레드 전용) */
data class TripAlertState(
    val leftBehind: LeftBehindState = LeftBehindState(),
    val release: ReleaseAlertState = ReleaseAlertState(),
    /** 2026-11-13 이동 중 교통 상식(VoiceLines·SafetyTipRule). 이동 경계에서 같이 비운다 */
    val tips: SafetyTipState = SafetyTipState(),
)
