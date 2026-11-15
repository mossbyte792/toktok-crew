package com.crewguide.probe

import kotlin.random.Random

/** 이동 중 교통 상식 진행 상태(이동마다 새로). TripAlertState에 둔다 */
data class SafetyTipState(
    /** 이번 이동에서 낸 상식 수(기록·로그용, 제한 없음) */
    val said: Int = 0,
    /** 마지막 상식 시각(elapsedRealtime, 없으면 -1) */
    val lastAtMs: Long = -1L,
    /** 이번 이동에서 처음 주행을 본 시각(없으면 -1) */
    val departAtMs: Long = -1L,
    /** 마지막 상식 뒤 다음 상식까지 기다릴 간격(ms, 상식을 낼 때 무작위로 정함). 없으면 -1 */
    val nextGapMs: Long = -1L,
)

/**
 * 교통 상식 시간 값 묶음. 기본은 [SafetyTipRule]의 상수. debug 개발자 패널만 짧은 값으로 바꾼다(QA용, release는 바꾸지 않음).
 */
data class TipTiming(
    val firstDelayMs: Long = SafetyTipRule.FIRST_DELAY_MS,
    val gapMinMs: Long = SafetyTipRule.GAP_MIN_MS,
    val gapMaxMs: Long = SafetyTipRule.GAP_MAX_MS,
    val quietMs: Long = SafetyTipRule.QUIET_MS,
    val tickMs: Long = SafetyTipRule.TICK_MS,
)

/**
 * 교통 상식 시점 규칙(순수). 2026-11-14 r5 3절(사용자: "8시간인데 2건은 너무 적다. 시간을 정해서 랜덤으로"):
 * 횟수 제한 없음. 첫 상식은 출발 뒤 [FIRST_DELAY_MS], 그다음은 앞 상식 뒤 [GAP_MIN_MINUTES]~[GAP_MAX_MINUTES]분 사이 무작위 간격.
 * 그대로 두는 조건:
 *  - 설정 켜짐, 출발함 + 지금 주행 중, 도착 신호 전·하차 마무리 전
 *  - 마지막 이동 안내(ETA·5/1분·경고·도착 등) 뒤 [QUIET_MS] 지남, 지금 말하는 중이 아님, 내비가 말하는 중·직후가 아님(r5 2절)
 *  - 남은 시간을 알면 [MIN_REMAIN_SEC] 초과일 때만(1분 안내·도착과 겹치지 않게)
 * 화면에는 올리지 않는다(음성만). 문장 순서(42문장 한 바퀴 반복 없음·무작위)는 [TripTips].
 */
object SafetyTipRule {
    // ---- 사용자가 바꾸기 쉬운 곳: 다음 상식까지 간격(분). 이 두 줄만 고치면 된다 ----
    const val GAP_MIN_MINUTES = 8
    const val GAP_MAX_MINUTES = 12

    const val GAP_MIN_MS = GAP_MIN_MINUTES * 60_000L
    const val GAP_MAX_MS = GAP_MAX_MINUTES * 60_000L
    const val FIRST_DELAY_MS = 90_000L
    const val QUIET_MS = 45_000L
    const val MIN_REMAIN_SEC = 150
    /** 시점 점검 간격(호출자 타이머) */
    const val TICK_MS = 15_000L

    val DEFAULT = TipTiming()

    fun onDrive(s: SafetyTipState, nowMs: Long): SafetyTipState = if (s.departAtMs < 0) s.copy(departAtMs = nowMs) else s

    /** [gapMinMs, gapMaxMs] 사이 무작위 간격(양 끝 포함) */
    fun randomGapMs(t: TipTiming, rnd: Random): Long =
        if (t.gapMaxMs <= t.gapMinMs) t.gapMinMs else rnd.nextLong(t.gapMinMs, t.gapMaxMs + 1)

    fun due(
        s: SafetyTipState, tg: TripGuideState, enabled: Boolean, speaking: Boolean, naviBusy: Boolean,
        lastSayEndMs: Long, nowMs: Long, t: TipTiming = DEFAULT,
    ): Boolean {
        if (!enabled || speaking || naviBusy) return false
        if (!tg.confirmed || !tg.departed || !tg.drivingNow || tg.arrivalSignal || tg.allLeftSaid) return false
        if (s.departAtMs < 0 || nowMs - s.departAtMs < t.firstDelayMs) return false
        if (lastSayEndMs >= 0 && nowMs - lastSayEndMs < t.quietMs) return false
        if (s.said >= 1 && s.lastAtMs >= 0) {
            val gap = if (s.nextGapMs >= 0) s.nextGapMs else t.gapMinMs
            if (nowMs - s.lastAtMs < gap) return false
        }
        val r = tg.remainSec
        if (r != null && r <= MIN_REMAIN_SEC) return false
        return true
    }

    /** 상식을 냄: 횟수를 올리고 다음 간격을 무작위로 정한다 */
    fun onSaid(s: SafetyTipState, nowMs: Long, rnd: Random = Random.Default, t: TipTiming = DEFAULT): SafetyTipState =
        s.copy(said = s.said + 1, lastAtMs = nowMs, nextGapMs = randomGapMs(t, rnd))

    /** 시간 값을 바꿨을 때(debug 단축 켜기/끄기): 이미 정한 다음 간격을 새 범위에서 다시 정한다 */
    fun retime(s: SafetyTipState, rnd: Random, t: TipTiming): SafetyTipState =
        if (s.said >= 1) s.copy(nextGapMs = randomGapMs(t, rnd)) else s

    /** 다음 간격 로그 문구(분·초) */
    fun gapText(ms: Long): String = if (ms >= 60_000L) "${ms / 60_000L}분 ${(ms % 60_000L) / 1000L}초" else "${ms / 1000L}초"
}

/**
 * r10 E(개발 1, 2026-11-15, 순수): 교통 상식이 정차 질문을 밀어내지 않게.
 * 관찰(11:53:16~21): 주행 중 시작한 교통 상식이 끝날 때까지 "목적지에 도착했나요?"가 약 4초 밀렸다.
 *  - 유효 P(잠금 사유 없음 + 주행 아님)에서 [PARK_URGENT_LABELS] 안내가 줄에 서 있으면, 재생 중이거나 시작 전인 교통 상식을 끊는다([shouldCut]).
 *  - P에서는 교통 상식을 새로 시작하지 않는다([startAllowed]). 줄에 서 있던 상식도 보낼 때 같은 조건으로 버린다.
 *  - 끊긴 상식은 다시 틀지 않는다(임시 결정 후보). 덱 위치는 이미 넘어가 그 문장은 이번 바퀴에서 건너뛰고,
 *    다음 상식 간격은 끊긴 상식을 낸 시각(SafetyTipState.lastAtMs)부터 기존대로 센다(다시 주행해도 첫 90초 대기를 새로 두지 않음).
 * 내비 음성 겹침 방지(NaviVoiceRule)·차량 경고(주행 벨트 안내, 자동 안내가 아님)는 건드리지 않는다: 끊는 것은 교통 상식 하나뿐이고,
 * 정차 안내는 그 뒤에도 내비 대기 규칙을 그대로 거친다.
 */
object TipCutRule {
    /** 끄면(false) 옛 동작: 교통 상식이 끝날 때까지 정차 안내가 기다린다 */
    const val CUT_ENABLED = true
    /** 끄면(false) 옛 동작: 시작 조건은 SafetyTipRule.due + 보낼 때 주행 중 확인만 */
    const val PARK_START_BLOCK_ENABLED = true
    /** 유효 P에서 교통 상식보다 먼저 나가야 하는 안내: 도착 질문 · 내릴까요 질문 · 중간 하차 안내 · 도착(하차) 안내 · 하차 마무리 · 두고 내림 */
    val PARK_URGENT_LABELS: Set<String> = setOf(
        ArrivalAskText.VOICE_LABEL,
        ArrivalAskText.ALIGHT_VOICE_LABEL,
        ArrivalAskText.ALIGHTING_VOICE_LABEL,
        VoiceQueueRule.LABEL_ARRIVED,
        "이동안내-" + TripSayKind.ALL_LEFT.name,
        LeftBehindRule.VOICE_LABEL,
    )

    fun urgent(label: String): Boolean = label in PARK_URGENT_LABELS

    /**
     * 교통 상식을 끊을까. [tipActive] = 지금 TTS에 걸린 자동 안내가 교통 상식(재생 중·시작 전), [waitingLabels] = 줄에 선 안내 이름,
     * [voicePark] = 잠금 사유 없음(유효 P + 플랫폼 허용), [driving] = 주행 중
     */
    fun shouldCut(tipActive: Boolean, waitingLabels: Collection<String>, voicePark: Boolean, driving: Boolean,
                  enabled: Boolean = CUT_ENABLED): Boolean =
        enabled && tipActive && voicePark && !driving && waitingLabels.any { urgent(it) }

    /** 교통 상식을 시작(또는 줄에서 보내기)해도 되나: 주행 중이고 기어가 P가 아님. 스위치를 끄면 옛 조건(주행 중만) */
    fun startAllowed(driving: Boolean, gearIsP: Boolean, enabled: Boolean = PARK_START_BLOCK_ENABLED): Boolean =
        if (!enabled) driving else driving && !gearIsP

    /** 줄에서 지울 교통 상식인가(끊을 때 같이 지운다) */
    fun dropQueuedTip(label: String): Boolean = label == VoiceQueueRule.LABEL_TIP
}
