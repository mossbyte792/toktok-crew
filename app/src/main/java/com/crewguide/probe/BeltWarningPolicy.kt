package com.crewguide.probe

// 주행 벨트 경고 정책(2026-11-08 Unit 5.3, ch DECISION 3·4). Android·SDK 클래스를 쓰지 않는 순수 로직이다.
// 벨트 '해제 이벤트'(pending)와 경고 '전달 완료'(delivered)를 분리한다. 발화·포커스·로그·타이머는 호출자가 한다.
//
// 정책 요약
// - 세션 = 보호자 '예'(arm) ~ 명확한 종료(무장 후 주행을 본 뒤 유효한 P, [이동 끝내기], 새 '예', 앱 종료).
//   화면 이탈(내비 전경 등)은 세션을 끝내지 않는다(DECISION 4).
// - 해제 이벤트: armed 좌석의 체결(true) → 미체결(false) 실제 변화. 정차가 확인된 P(STATIONARY)에서는 이벤트로 만들지 않는다.
//   재체결(true)이면 그 좌석은 다시 감시하고 미처리 이벤트는 정리한다. 다시 풀리면 새 이벤트다.
// - MOVING: 주행 확인 경고. 세션당 1회는 '실제로 전달된(onDone)' 경고만 센다. 소진 뒤 새 해제 이벤트는 말하지 않고 정리한다.
// - UNKNOWN: 정차로 보지 않는다. 이벤트를 보존하고 [NEUTRAL_HOLD_MS] 동안 상태 복구를 기다린다.
//   복구되면 재평가(MOVING → 주행 경고, P → 정리). 계속 UNKNOWN이면 차량 상태를 단정하지 않는 중립 경고 1회(세션당).
//   중립 경고 전달은 주행 경고 1회를 소진하지 않는다. 중립 경고가 이미 전달됐거나 주행 경고가 소진됐으면 UNKNOWN에서는 보존만 한다.
// - 전달 실패(오류·중지·포커스 거부·음성 꺼짐·준비 안 됨): 완료 처리하지 않고 이벤트를 보존, [RETRY_DELAY_MS] 뒤 재시도,
//   [MAX_FAILURES]회 실패하면 정리하고 '미전달' 기록을 남긴다.
// - 같은 이벤트로 반복 발화하지 않는다: 전달된 경고는 그때까지 쌓인 이벤트를 모두 처리한다(좌석별 문구가 아니다).
// - 다른 발화가 진행 중이면 끊지 않고 기다린다(호출자가 다시 평가한다).

enum class WarningKind { MOVING, NEUTRAL }

/** 미처리 해제 이벤트 1건: 처음 관측 시각과 그때의 움직임 맥락 */
data class PendingRelease(val atMs: Long, val motion: MotionState)

enum class UndeliveredReason {
    /** 경고를 전하기 전에 유효한 P가 확인됨(주행 중 이벤트였거나 전달 실패가 있었던 경우만 기록) */
    PARKED_BEFORE_DELIVERY,
    /** 재시도 한도 초과 */
    RETRY_LIMIT,
    /** 세션 종료(이동 끝내기·새 '예'·앱 종료)로 정리 */
    SESSION_ENDED,
    /** 이번 세션 주행 경고 1회가 이미 전달돼 새 해제 이벤트는 말하지 않음(정책) */
    SESSION_LIMIT,
}

/** 화면 복귀 때 보호자에게 알릴 미전달 기록(메모리만, 저장하지 않는다) */
data class UndeliveredRecord(val seats: Set<String>, val reason: UndeliveredReason, val atMs: Long)

data class BeltWarnState(
    val armed: Set<String> = emptySet(),
    /** 직전 유효값이 체결(true)인 armed 좌석 */
    val lastBuckled: Set<String> = emptySet(),
    /** 해제 이벤트가 있었으나 경고가 아직 전달되지 않은 좌석 */
    val pending: Map<String, PendingRelease> = emptyMap(),
    /** 이번 세션 주행 확인 경고가 실제로 전달됨(세션 1회 소진) */
    val movingDelivered: Boolean = false,
    /** 이번 세션 중립 경고가 실제로 전달됨 */
    val neutralDelivered: Boolean = false,
    /** 발화 요청이 나가 결과를 기다리는 경고 종류(없으면 null) */
    val inFlight: WarningKind? = null,
    /** 그 경고가 처리하는 좌석(요청 시점 pending) */
    val inFlightSeats: Set<String> = emptySet(),
    /** 그 경고의 발화 세대(TtsGuide.speakDrive 반환값). 결과 콜백 귀속에 쓴다 */
    val inFlightGen: Int = -1,
    /** 요청 시점의 이벤트 사본. 발화 중 같은 좌석이 다시 풀린 새 이벤트를 전달 결과에 흡수하지 않기 위해 비교한다(감사 9) */
    val inFlightPending: Map<String, PendingRelease> = emptyMap(),
    /** 지금 미처리 묶음의 전달 실패 횟수 */
    val failures: Int = 0,
    val lastFailAtMs: Long = Long.MIN_VALUE,
) {
    val active: Boolean get() = armed.isNotEmpty()
}

sealed class BeltWarnAction {
    object None : BeltWarnAction() { override fun toString() = "None" }
    data class Speak(val kind: WarningKind, val seats: Set<String>) : BeltWarnAction()
    /** 이 시각에 다시 평가해 달라(UNKNOWN 유예·재시도 간격) */
    data class RecheckAt(val atMs: Long) : BeltWarnAction()
    /** 다른 발화가 끝나면 다시 평가해 달라 */
    object WaitTtsIdle : BeltWarnAction() { override fun toString() = "WaitTtsIdle" }
}

data class BeltWarnStep(
    val state: BeltWarnState,
    val action: BeltWarnAction = BeltWarnAction.None,
    val notes: List<String> = emptyList(),
    val records: List<UndeliveredRecord> = emptyList(),
)

object BeltWarningPolicy {
    /** UNKNOWN에서 중립 경고 전에 상태 복구를 기다리는 시간(가안). 기어 감시 시간 초과(6초)보다 짧게 둔다 */
    const val NEUTRAL_HOLD_MS = 2_000L
    /** 전달 실패 뒤 재시도 간격(가안) */
    const val RETRY_DELAY_MS = 3_000L
    /** 같은 미처리 묶음의 최대 실패 횟수(가안) */
    const val MAX_FAILURES = 3

    /** 중립 경고 문구: 주행·정차를 단정하지 않는다(ch DECISION 3). 허용·보장 표현 없음 */
    const val NEUTRAL_TEXT = "벨트 신호가 바뀐 것 같아요. 어른이 벨트를 확인해 주세요."

    /** 보호자 '예' 순간: 대상 좌석 armed, 그 시점 최신 값이 체결인 좌석만 감시. 새 세션 */
    fun arm(targets: Set<String>, latest: Map<String, Boolean?>): BeltWarnState {
        val armed = targets.toSet()
        return BeltWarnState(armed = armed, lastBuckled = armed.filter { latest[it] == true }.toSet())
    }

    /** 세션 종료. 주행 중 이벤트·실패가 남아 있었으면 미전달로 기록한다 */
    fun end(s: BeltWarnState, nowMs: Long): BeltWarnStep {
        val rec = recordIfNeeded(s, UndeliveredReason.SESSION_ENDED, nowMs)
        return BeltWarnStep(BeltWarnState(), notes = if (s.active) listOf("세션 종료") else emptyList(), records = rec)
    }

    /** 값 기록만(GET 응답·구독 단절). 체결이면 다시 감시, null/false면 감시에서 뺀다. 이벤트는 만들지 않는다 */
    fun track(s: BeltWarnState, seat: String, value: Boolean?): BeltWarnState {
        if (seat !in s.armed) return s
        val next = if (value == true) s.lastBuckled + seat else s.lastBuckled - seat
        return if (next == s.lastBuckled) s else s.copy(lastBuckled = next)
    }

    /** 벨트 변화 이벤트 1건(실제 수신). 해제 이벤트·재체결을 반영한다. 평가는 [evaluate]로 따로 한다 */
    fun observe(s: BeltWarnState, seat: String, newValue: Boolean?, ctx: DriveContext, nowMs: Long): BeltWarnStep {
        if (seat !in s.armed) return BeltWarnStep(s)
        val wasBuckled = seat in s.lastBuckled
        var next = track(s, seat, newValue)
        val notes = mutableListOf<String>()
        if (newValue == true && seat in next.pending) {
            next = next.copy(pending = next.pending - seat)
            notes += "$seat 다시 체결 → 미처리 해제 이벤트 정리"
        }
        if (wasBuckled && newValue == false) {
            val motion = BeltReleaseRule.motionOf(ctx.gearName, ctx.linkOk, ctx.subFailed)
            when {
                ctx.simMode -> notes += "$seat 해제(모의 모드) → 대상 아님"
                motion == MotionState.STATIONARY -> notes += "$seat 해제(정차 확인) → 안내 안 함"
                seat in next.pending -> {}
                else -> {
                    next = next.copy(pending = next.pending + (seat to PendingRelease(nowMs, motion)))
                    notes += "$seat 해제 이벤트 보존(맥락=$motion)"
                }
            }
        }
        return BeltWarnStep(next, notes = notes)
    }

    /** 미처리 이벤트를 지금 어떻게 할지. ttsBusy=다른 발화 진행 중 */
    fun evaluate(s: BeltWarnState, ctx: DriveContext, ttsBusy: Boolean, nowMs: Long): BeltWarnStep {
        if (s.inFlight != null || s.pending.isEmpty() || ctx.simMode) return BeltWarnStep(s)
        val motion = BeltReleaseRule.motionOf(ctx.gearName, ctx.linkOk, ctx.subFailed)
        if (motion == MotionState.STATIONARY) {
            val rec = recordIfNeeded(s, UndeliveredReason.PARKED_BEFORE_DELIVERY, nowMs)
            return BeltWarnStep(clear(s), notes = listOf("정차 확인 → 미처리 해제 이벤트 ${s.pending.size}건 정리(경고 안 함)"), records = rec)
        }
        val kind: WarningKind
        if (motion == MotionState.MOVING) {
            if (s.movingDelivered) return BeltWarnStep(clear(s), notes = listOf("주행 경고 세션 1회 이미 전달 → 새 해제 이벤트 ${s.pending.size}건은 말하지 않음(기록)"),
                records = listOf(UndeliveredRecord(s.pending.keys.toSet(), UndeliveredReason.SESSION_LIMIT, nowMs)))
            kind = WarningKind.MOVING
        } else {   // UNKNOWN
            if (s.movingDelivered || s.neutralDelivered) return BeltWarnStep(s, notes = listOf("상태 불명 + 이번 세션 경고 전달됨 → 보존, 상태 복구 후 재평가"))
            val holdUntil = s.pending.values.minOf { it.atMs } + NEUTRAL_HOLD_MS
            if (nowMs < holdUntil) return BeltWarnStep(s, BeltWarnAction.RecheckAt(holdUntil))
            kind = WarningKind.NEUTRAL
        }
        if (s.failures >= MAX_FAILURES) {
            val rec = listOf(UndeliveredRecord(s.pending.keys.toSet(), UndeliveredReason.RETRY_LIMIT, nowMs))
            return BeltWarnStep(clear(s), notes = listOf("전달 실패 ${s.failures}회 → 재시도 중단, 미전달 기록"), records = rec)
        }
        if (s.failures > 0 && nowMs < s.lastFailAtMs + RETRY_DELAY_MS) return BeltWarnStep(s, BeltWarnAction.RecheckAt(s.lastFailAtMs + RETRY_DELAY_MS))
        if (ttsBusy) return BeltWarnStep(s, BeltWarnAction.WaitTtsIdle, listOf("다른 발화 진행 중 → 끝난 뒤 재평가"))
        return BeltWarnStep(s, BeltWarnAction.Speak(kind, s.pending.keys.toSet()))
    }

    /** 발화 요청이 나감(결과 대기). gen=발화 세대 */
    fun requested(s: BeltWarnState, kind: WarningKind, seats: Set<String>, gen: Int): BeltWarnState =
        s.copy(inFlight = kind, inFlightSeats = seats, inFlightGen = gen, inFlightPending = s.pending.filterKeys { it in seats })

    /** 발화 요청 자체가 거절됨(음성 꺼짐·준비 안 됨·포커스 거부·예외): 실패 1회 */
    fun rejected(s: BeltWarnState, nowMs: Long): BeltWarnState =
        s.copy(inFlight = null, inFlightSeats = emptySet(), inFlightGen = -1, inFlightPending = emptyMap(), failures = s.failures + 1, lastFailAtMs = nowMs)

    /**
     * 발화 결과. done=true(onDone)만 전달 완료다. 기다리는 경고가 아니면(세대 불일치·중복 콜백·취소·세션 종료 뒤) 아무것도 바꾸지 않는다(멱등).
     * 전달된 경고는 요청 시점 좌석의 이벤트를 처리한다. 발화 중 새로 생긴 이벤트는 남아 다시 평가된다.
     */
    fun result(s: BeltWarnState, gen: Int, done: Boolean, nowMs: Long): BeltWarnStep {
        val kind = s.inFlight ?: return BeltWarnStep(s)
        if (gen != s.inFlightGen) return BeltWarnStep(s, notes = listOf("다른 세대($gen ≠ ${s.inFlightGen})의 결과 → 무시"))
        if (!done) return BeltWarnStep(rejected(s, nowMs), notes = listOf("${label(kind)} 전달 안 됨 → 완료 처리 안 함, 이벤트 보존(실패 ${s.failures + 1}회)"))
        val next = s.copy(
            // 요청 때와 같은 이벤트만 처리됨. 발화 중 재체결 후 다시 풀린 같은 좌석의 새 이벤트(시각이 다름)는 남긴다
            pending = s.pending.filter { (seat, ev) -> s.inFlightPending[seat] != ev },
            movingDelivered = s.movingDelivered || kind == WarningKind.MOVING,
            neutralDelivered = s.neutralDelivered || kind == WarningKind.NEUTRAL,
            inFlight = null, inFlightSeats = emptySet(), inFlightGen = -1, inFlightPending = emptyMap(), failures = 0, lastFailAtMs = Long.MIN_VALUE,
        )
        return BeltWarnStep(next, notes = listOf("${label(kind)} 전달 완료" + if (kind == WarningKind.MOVING) "(세션 1회 소진)" else "(주행 경고 1회는 유지)"))
    }

    /**
     * 진행 중 경고를 앱이 멈춤(유효한 P 확인 등). 결과 콜백은 기다리지 않는다(취소한 발화의 늦은 콜백은 TtsGuide가 거른다).
     * 전달 완료가 아니므로 이벤트는 남기고 실패로 세지 않는다.
     */
    fun cancelled(s: BeltWarnState): BeltWarnState = s.copy(inFlight = null, inFlightSeats = emptySet(), inFlightGen = -1, inFlightPending = emptyMap())

    fun label(k: WarningKind): String = if (k == WarningKind.MOVING) "주행 경고" else "중립 경고"

    private fun clear(s: BeltWarnState) = s.copy(pending = emptyMap(), failures = 0, lastFailAtMs = Long.MIN_VALUE)

    private fun recordIfNeeded(s: BeltWarnState, reason: UndeliveredReason, nowMs: Long): List<UndeliveredRecord> {
        if (s.pending.isEmpty()) return emptyList()
        // 기록 대상: 실패가 있었거나, 주행 중 이벤트였거나, 상태 불명 유예 시간을 넘겨 경고 없이 보존만 하던 이벤트(감사 4)
        val important = s.failures > 0 || s.pending.values.any { it.motion == MotionState.MOVING || nowMs - it.atMs >= NEUTRAL_HOLD_MS }
        return if (important) listOf(UndeliveredRecord(s.pending.keys.toSet(), reason, nowMs)) else emptyList()
    }
}
