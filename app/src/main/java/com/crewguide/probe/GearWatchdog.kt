package com.crewguide.probe

// 기어 수신 감시(조용한 단절·응답 시간 초과). Android·SDK 클래스를 쓰지 않는 순수 로직이다. 2026-11-07 Unit 5.1 N3.
// 차량 서비스가 onFailed 없이 멈추면(조용한 단절) 마지막 기어 값(예: P)이 그대로 남는다. 이를 막기 위해
// 일정 간격으로 현재 기어를 다시 요청하고, 정해진 시간 안에 응답(성공·실패 콜백 어느 것이든)이 없으면 '수신 끊김'으로 본다.
// 기어 이벤트 도착도 살아 있다는 근거로 인정한다. 시간 초과 판정은 잠금(구독 실패와 같은 처리)으로만 이어지고 허용으로 바뀌지 않는다.

data class GearWatchState(
    /** 응답을 기다리는 요청 번호(없으면 null) */
    val pendingToken: Int? = null,
    /** 그 요청을 보낸 시각(ms, elapsedRealtime) */
    val sentAtMs: Long = 0L,
    /** 마지막으로 부여한 요청 번호 */
    val lastToken: Int = 0,
    /** 시간 초과로 끊김을 이미 알렸는가(같은 구간에서 반복 처리 방지). 응답·이벤트가 오면 풀린다 */
    val timedOut: Boolean = false,
)

enum class GearWatchAction { NONE, SEND_PROBE, TIMED_OUT }

object GearWatchdog {
    /** 감시 간격(가안). 이 간격마다 점검한다 */
    const val INTERVAL_MS = 5_000L
    /** 응답 대기 상한(가안). 창구 측정에서 첫 기어 값이 약 3.3초였던 것보다 길게 둔다 */
    const val TIMEOUT_MS = 6_000L

    /** 점검 1회. 대기 중 요청이 상한을 넘으면 TIMED_OUT(끊김 구간당 한 번만), 대기 요청이 없으면 SEND_PROBE */
    fun tick(s: GearWatchState, nowMs: Long, timeoutMs: Long = TIMEOUT_MS): Pair<GearWatchState, GearWatchAction> {
        val p = s.pendingToken
        if (p != null) {
            if (nowMs - s.sentAtMs < timeoutMs) return s to GearWatchAction.NONE
            // 시간 초과: 기다리던 요청을 버리고(늦은 응답은 무시) 다음 점검에서 새로 요청한다. 끊김 처리는 구간당 한 번만
            val dropped = s.copy(pendingToken = null)
            return if (s.timedOut) dropped to GearWatchAction.NONE else dropped.copy(timedOut = true) to GearWatchAction.TIMED_OUT
        }
        return s to GearWatchAction.SEND_PROBE
    }

    /** 요청을 보냄: 새 번호를 돌려준다 */
    fun sent(s: GearWatchState, nowMs: Long): GearWatchState = s.copy(pendingToken = s.lastToken + 1, sentAtMs = nowMs, lastToken = s.lastToken + 1)

    /** 요청 응답(성공·실패 콜백). 지금 기다리는 번호가 아니면(늦은 응답) 무시한다. 응답이 오면 끊김 표시를 푼다 */
    fun answered(s: GearWatchState, token: Int): GearWatchState =
        if (s.pendingToken != token) s else s.copy(pendingToken = null, timedOut = false)

    /** 기어 이벤트 도착: 서비스가 살아 있다는 근거. 대기 요청은 그대로 두되(응답은 따로 올 수 있음) 시간 초과 기준 시각을 지금으로 미룬다 */
    fun eventSeen(s: GearWatchState, nowMs: Long): GearWatchState =
        if (s.pendingToken == null) s.copy(timedOut = false) else s.copy(sentAtMs = nowMs, timedOut = false)

    /** 감시 중지(화면 이탈·모의 기어·종료): 대기 요청을 버린다. 번호는 유지해 늦은 응답을 거른다 */
    fun stop(s: GearWatchState): GearWatchState = s.copy(pendingToken = null, timedOut = false)
}
