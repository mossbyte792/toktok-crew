package com.crewguide.probe

// 기어 잠금 판정. Android·SDK 클래스를 쓰지 않는 순수 로직이다(MainActivity에서 옮김, 결과 문구 동일).

/** P로 인정하는 기어 이름. 대소문자 무시 정확 일치로만 비교한다.
 *  'GEAR_PARK'는 00_인수인계.md:366의 기록에 근거한 것이며 SDK 원본으로 확인한 값이 아니다(미검증). */
val PARK_GEAR_NAMES: Set<String> = setOf("P", "PARK", "PARKING", "GEAR_PARK")

/**
 * 값이 있지만 '유효한 기어'라고 볼 수 없는 이름(대소문자 무시 정확 일치, 공백만인 이름 포함).
 * 이런 이름은 'P 아님(주행)'으로 단정하지 않고 '기어 상태를 알 수 없음'으로 분류한다(상태 불명은 별도 상태).
 * SDK가 실제로 이런 이름을 보내는지는 확인하지 않았다(미검증). 어느 쪽이든 잠금이므로 조작 허용 여부는 같다.
 */
val INVALID_GEAR_NAMES: Set<String> = setOf("UNKNOWN", "GEAR_UNKNOWN", "NONE", "INVALID", "NOT_AVAILABLE", "UNAVAILABLE", "NULL")

/** 이름이 유효한 기어 값이 아닌가(빈 문자열·공백·불명 계열) */
fun isInvalidGearName(name: String): Boolean = name.isBlank() || name.uppercase() in INVALID_GEAR_NAMES

/**
 * 잠금 사유 문구. null이면 잠금 아님.
 * 2026-11-07 검토자 P0-2: 구독 실패([subFailed])는 연결 상태([linkOk])와 관계없이 잠근다.
 * 연결이 살아 있어도 기어 구독이 실패했다면 마지막으로 받은 이름(예: P)은 현재값의 근거가 아니다.
 */
fun gearLockReason(name: String?, linkOk: Boolean, subFailed: Boolean): String? = when {
    subFailed -> LOCK_TEXT_SUB_FAILED
    !linkOk -> LOCK_TEXT_DISCONNECTED
    name == null -> LOCK_TEXT_UNKNOWN
    isInvalidGearName(name) -> LOCK_TEXT_UNKNOWN
    name.uppercase() !in PARK_GEAR_NAMES -> LOCK_TEXT_NOT_PARK
    else -> null
}

/** 기어 상태 한 벌(이름·링크·구독 실패). 순수 값 */
data class GearSnapshot(val name: String?, val linkOk: Boolean, val subFailed: Boolean)

/**
 * debug 모의 기어를 끈 직후의 기어 상태(2026-11-07 Unit 5.1 N1).
 * 실제 기어 GET·이벤트가 오기 전에는 이름 없음·링크 미확인으로 둔다. 이전 모의 P를 이어 쓰지 않는다(잠금: 기어 신호 미수신).
 */
object RealGearReturn {
    val AFTER_MOCK_OFF = GearSnapshot(name = null, linkOk = false, subFailed = false)
}

// ---- 2026-11-15 r10 G(개발 3): 앱 시작 직후 첫 기어 값 안정화 ----
// 에뮬에서 앱을 강제 종료한 직후 첫 기어 이벤트가 D로 오고 30 ms 뒤 P가 와서, 잠금 카드가 잠깐 '주차(P) 상태가 아니에요'로 깜빡였다.
// 그래서 이 Activity에서 처음 받는 **이름 있는 기어 값**은 [FirstGearHold.HOLD_MS] 동안 붙잡아 두고, 그 사이 더 새로운 값이 오면 마지막 값을 쓴다.
// 붙잡는 동안 앱의 기어 값은 그대로 '미수신'(잠금)이다 → 허용(P)만 최대 0.5초 늦어지고, 잠금이 풀리는 일은 없다.
// 첫 값을 반영한 뒤로는 지금처럼 바로 반영한다(주행 중 D/R/N 잠금 반응이 늦어지지 않음).
// 이름 없는 값(구독 실패·GET 실패·링크 끊김)은 붙잡지 않고 바로 반영한다. 붙잡던 값이 있으면 버린다(더 새로운 값이 실패라서).
// 되돌리기: [FirstGearHold.ENABLED] = false(예전처럼 첫 값부터 바로 반영).

/** 첫 기어 값 붙잡기 상태. [held]가 있으면 붙잡는 중, [done]이면 첫 값을 반영했다(이후 바로 반영) */
data class FirstGearHoldState(
    val done: Boolean = false,
    val held: GearSnapshot? = null,
    /** 붙잡은 값을 반영할 시각(elapsedRealtime ms). 첫 값을 받은 때 + HOLD_MS로 고정한다(새 값이 와도 미루지 않음) */
    val deadlineMs: Long = -1L,
)

enum class FirstGearAction {
    /** 지금 바로 반영한다(첫 값 반영 뒤·스위치 꺼짐·이름 없는 값) */
    APPLY_NOW,
    /** 첫 이름 있는 값: 붙잡기 시작(호출자는 [FirstGearHold.HOLD_MS] 뒤 [FirstGearHold.flush]를 예약) */
    HOLD_START,
    /** 붙잡는 중 새 값: 마지막 값으로 바꿈(반영 시각은 그대로) */
    HOLD_REPLACE,
}

object FirstGearHold {
    /** r10 G 스위치(임시 결정 후보) */
    const val ENABLED = true
    /** 첫 값 안정화 시간 */
    const val HOLD_MS = 500L

    fun holding(s: FirstGearHoldState): Boolean = s.held != null

    /**
     * 기어 값 하나 도착(이벤트·GET 응답·실패 모두, 모의 기어 아님). 반환 상태와 할 일.
     * 이름 없는 값이 붙잡는 중에 오면 붙잡던 값을 버리고(첫 값 대기로 돌아감) 바로 반영한다 — 다음 이름 있는 값이 다시 첫 값이 된다.
     */
    fun onValue(s: FirstGearHoldState, v: GearSnapshot, nowMs: Long, enabled: Boolean = ENABLED): Pair<FirstGearHoldState, FirstGearAction> = when {
        !enabled || s.done -> s to FirstGearAction.APPLY_NOW
        v.name == null -> FirstGearHoldState() to FirstGearAction.APPLY_NOW
        s.held == null -> FirstGearHoldState(held = v, deadlineMs = nowMs + HOLD_MS) to FirstGearAction.HOLD_START
        else -> s.copy(held = v) to FirstGearAction.HOLD_REPLACE
    }

    /** 반영 시각에 붙잡은 마지막 값을 내준다(이후 done). 아직 시각 전이면 그대로(null), 붙잡은 값이 없으면 그대로(null) */
    fun flush(s: FirstGearHoldState, nowMs: Long): Pair<FirstGearHoldState, GearSnapshot?> {
        val v = s.held ?: return s to null
        if (nowMs < s.deadlineMs) return s to null
        return FirstGearHoldState(done = true) to v
    }

    /** 반영 시각까지 남은 시간(붙잡는 중이 아니면 0) */
    fun remainingMs(s: FirstGearHoldState, nowMs: Long): Long = if (s.held == null) 0L else (s.deadlineMs - nowMs).coerceAtLeast(0L)
}
