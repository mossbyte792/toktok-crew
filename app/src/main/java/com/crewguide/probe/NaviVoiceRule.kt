package com.crewguide.probe

import android.media.AudioAttributes

// 2026-11-14 r5 2절(개발 B): 톡톡크루 음성이 내비 음성 안내 위에 겹치지 않게 하는 순수 규칙(Android 상태 없음, 단위 시험).
// 에뮬 조사(2026-11-14 23:01, dumpsys audio·car_service):
//  - 내비(ai.umos.maps.android.navigation.app)는 말할 때 포커스를 요청한다(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE/SPEECH, GAIN_TRANSIENT_MAY_DUCK).
//    소리는 '딩' MediaPlayer(내비 uid) + Pleos TTS 서비스(ai.pleos.playground.caas)의 AudioTrack, 둘 다 usage=NAVIGATION_GUIDANCE·content=SPEECH.
//  - 톡톡크루 TTS는 같은 caas 서비스가 usage=USAGE_MEDIA로 재생한다. 앱 차임은 NAVIGATION_GUIDANCE + content=SONIFICATION.
//  - 앱이 말하는 중 내비가 포커스를 요청해도 둘 다 GRANTED(동시 허가)이고 앱은 포커스 상실 콜백을 받지 않는다 → 약 2.1초 겹침 확인.
//  → 포커스만으로는 막을 수 없어, 재생 목록(AudioPlaybackCallback)에서 '내비 안내 음성'을 보고 기다린다.

/** 내비 음성 감시 상태. [available]=false면 감시 못 함(지금처럼 막지 않고 말한다) */
data class NaviVoiceState(
    val available: Boolean = false,
    /** 지금 재생 중인 내비 안내 음성 수 */
    val activeCount: Int = 0,
    /** 0 → 1 이상이 된 시각(없으면 -1) */
    val activeSinceMs: Long = -1L,
    /** 마지막으로 0이 된 시각(없으면 -1) */
    val lastEndMs: Long = -1L,
)

object NaviVoiceRule {
    /** 내비 음성이 끝난 뒤 앱이 말하기 전 쉬는 시간(0.8~1.5초 사이에서 고름) */
    const val GAP_AFTER_NAVI_MS = 1_200L
    /** 내비가 말하는 중일 때 다시 볼 간격 */
    const val RECHECK_MS = 500L
    /** 내비 음성이 이보다 오래 이어지면 감시 값이 멈춘 것으로 보고 더 막지 않는다(안내가 영영 밀리지 않게) */
    const val MAX_HOLD_MS = 20_000L
    /** 앱 발화 요청 직후 이 시간 안에 생긴 안내 음성 재생은 앱 자신의 것일 수 있어 끼어들기로 보지 않는다(실차 TTS 용도 미확인 대비) */
    const val OWN_ECHO_MS = 250L
    /**
     * 결정 37(사용자 확정, 11-15): 주행 중 자동 안내를 말하는 중 내비가 말을 시작하면 앱 음성을 바로 멈추고, 내비가 끝난 뒤 그 문장을 처음부터 다시 말한다.
     * 2026-11-15 r6a: P 자동 안내에도 적용([YIELD_AT_PARK]), 확인 완료 음성은 임시 결정 42([CONFIRM_RESAY_ON_NAVI]).
     * 되돌리기: false로 바꾸면 앱 문장은 끝까지 말하고(겹칠 수 있음) 다음 문장부터 내비를 기다린다.
     */
    const val YIELD_ON_NAVI = true
    /** 일반 안내(확인 완료·질문·하차 한 줄 등, speak/speakExit)가 내비를 기다리는 최대 시간. 지나면 버리지 않고 그냥 말한다 */
    const val MAX_DEFER_MS = 30_000L

    /**
     * 2026-11-15 루프 r6a 1절(PM 지시): P에서도 자동 안내(예정 시간 등)는 내비가 끼어들면 멈추고 다시 말한다.
     * 검수 로그(00:29, P): 경로 시작 때 앱 예정 시간 안내가 내비 시작 안내보다 0.18초 먼저 시작해 약 1.8초 겹침.
     * 되돌리기: false로 바꾸면 예전처럼 주행 중 안내에만 멈춤을 적용한다.
     */
    const val YIELD_AT_PARK = true

    /**
     * 임시 결정 42(PM, 사용자 확인 대기): 확인 완료 음성(끝나면 내비로 넘기는 문장)도 내비가 끼어들면 멈추고,
     * 내비가 끝나고 [GAP_AFTER_NAVI_MS] 뒤 처음부터 [MAX_CONFIRM_RESAY]번만 다시 말한 다음 내비로 넘긴다. 내비 전환 안전망 시간은 그대로.
     * 되돌리기: false로 바꾸면 확인 완료 음성은 끝까지 말한다.
     */
    const val CONFIRM_RESAY_ON_NAVI = true
    const val MAX_CONFIRM_RESAY = 1

    /** 끼어들기 멈춤을 적용하나: 주행 중 자동 안내, 그리고 [YIELD_AT_PARK]면 P 자동 안내도 */
    fun yieldApplies(driving: Boolean): Boolean = YIELD_ON_NAVI && (driving || YIELD_AT_PARK)

    /** 확인 완료 음성을 내비 때문에 다시 말할까: [resaid] = 이미 다시 말한 횟수 */
    fun confirmResay(yielded: Boolean, resaid: Int): Boolean = CONFIRM_RESAY_ON_NAVI && yielded && resaid < MAX_CONFIRM_RESAY

    /** 일반 안내의 대기 시간: [waitMs]와 같되, [waitedSinceMs]부터 [MAX_DEFER_MS]가 지나면 0(그냥 말함) */
    fun deferWaitMs(s: NaviVoiceState, nowMs: Long, waitedSinceMs: Long): Long =
        if (nowMs - waitedSinceMs >= MAX_DEFER_MS) 0L else waitMs(s, nowMs).coerceAtMost(MAX_DEFER_MS - (nowMs - waitedSinceMs))
    /** 같은 문장을 내비 때문에 다시 말하는 최대 횟수(넘으면 버림) */
    const val MAX_RESAY = 2

    /** 재생 하나가 내비 안내 음성인가: 용도 NAVIGATION_GUIDANCE이고 효과음(SONIFICATION, 앱 차임)이 아님 */
    fun isNaviVoice(usage: Int, contentType: Int): Boolean =
        usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE && contentType != AudioAttributes.CONTENT_TYPE_SONIFICATION

    fun onCount(s: NaviVoiceState, count: Int, nowMs: Long): NaviVoiceState {
        val c = count.coerceAtLeast(0)
        return when {
            s.activeCount == 0 && c > 0 -> s.copy(activeCount = c, activeSinceMs = nowMs)
            s.activeCount > 0 && c == 0 -> s.copy(activeCount = 0, activeSinceMs = -1L, lastEndMs = nowMs)
            else -> s.copy(activeCount = c)
        }
    }

    /** 내비가 말하는 중인가(오래 멈춘 값은 제외) */
    fun speaking(s: NaviVoiceState, nowMs: Long): Boolean =
        s.available && s.activeCount > 0 && !(s.activeSinceMs >= 0 && nowMs - s.activeSinceMs >= MAX_HOLD_MS)

    /** 지금 앱이 말을 시작해도 되나. 0 = 바로, 0보다 크면 그만큼 뒤 다시 본다. 감시 못 하면 항상 0(막지 않음) */
    fun waitMs(s: NaviVoiceState, nowMs: Long): Long {
        if (!s.available) return 0L
        if (s.activeCount > 0) return if (speaking(s, nowMs)) RECHECK_MS else 0L
        if (s.lastEndMs >= 0) {
            val left = GAP_AFTER_NAVI_MS - (nowMs - s.lastEndMs)
            if (left > 0) return left
        }
        return 0L
    }

    /**
     * 앱 발화 중 재생 수가 바뀜. [baseline] = 앱 발화 시작 때(앱 자신 몫 포함) 내비 안내 음성 수.
     * 요청 뒤 [OWN_ECHO_MS] 안의 증가는 앱 자신일 수 있어 baseline에 넣는다. 그 뒤 baseline보다 늘면 내비가 끼어든 것.
     * [driving] = 지금 주행 중(멈춤은 [yieldApplies]일 때만). 반환: (새 baseline, 멈춰야 하나)
     */
    fun onCountWhileSpeaking(baseline: Int, count: Int, sinceRequestMs: Long, driving: Boolean): Pair<Int, Boolean> = when {
        count < baseline -> count to false
        sinceRequestMs < OWN_ECHO_MS -> maxOf(baseline, count) to false
        count > baseline -> baseline to yieldApplies(driving)
        else -> baseline to false
    }
}

/** 자동 안내 줄의 순서 등급: 벨트 풀림 경고 → 도착·예정 시간 등 안내 → 교통 상식 */
enum class SayRank { BELT, GUIDE, TIP }

/** 줄에 선 안내 하나(순수 판단용). [validMs] = 줄에 선 뒤 유효 시간(null이면 만료 없음) */
data class QueuedSay(val rank: SayRank, val atMs: Long, val validMs: Long?, val graceMs: Long = 0L)

object VoiceQueueRule {
    /** 5분 전 안내: 1분 넘게 밀리면 때를 놓친 것으로 보고 버린다 */
    const val ETA5_VALID_MS = 60_000L
    /** 1분 전 안내: 20초 넘게 밀리면 버린다 */
    const val ETA1_VALID_MS = 20_000L
    /** 교통 상식: 1분 넘게 밀리면 버린다(다음 간격에 새 상식) */
    const val TIP_VALID_MS = 60_000L

    const val LABEL_BELT = "벨트풀림"
    const val LABEL_TIP = "교통상식"
    const val LABEL_ETA5 = "이동안내-ETA5"
    const val LABEL_ETA1 = "이동안내-ETA1"
    const val LABEL_ETA_TOTAL = "이동안내-ETA_TOTAL"
    const val LABEL_ARRIVED = "이동안내-ARRIVED"

    /**
     * 2026-11-15 r6a 1절: 내비 신호(경로 시작·남은 시간·도착)에 맞춰 나오는 앱 안내는 내비도 같은 순간 말을 시작하므로,
     * 신호를 받고 이 시간 동안 내비 음성이 시작되는지 본 뒤 말한다(시작되면 기존 대기 규칙). 검수 로그상 내비는 약 0.2초 뒤 시작.
     */
    const val NAVI_SIGNAL_GRACE_MS = 1_800L

    fun graceMsOf(label: String): Long = when (label) {
        LABEL_ETA_TOTAL, LABEL_ETA5, LABEL_ETA1, LABEL_ARRIVED -> NAVI_SIGNAL_GRACE_MS
        else -> 0L
    }

    /** 아직 말하지 말고 기다릴 시간(내비 신호 직후 지켜보기). 0이면 바로 */
    fun holdMs(q: QueuedSay, nowMs: Long): Long = (q.atMs + q.graceMs - nowMs).coerceAtLeast(0L)

    fun rankOf(label: String): SayRank = when (label) {
        LABEL_BELT -> SayRank.BELT
        LABEL_TIP -> SayRank.TIP
        else -> SayRank.GUIDE
    }

    fun validMsOf(label: String): Long? = when (label) {
        LABEL_ETA5 -> ETA5_VALID_MS
        LABEL_ETA1 -> ETA1_VALID_MS
        LABEL_TIP -> TIP_VALID_MS
        else -> null
    }

    fun of(label: String, atMs: Long): QueuedSay = QueuedSay(rankOf(label), atMs, validMsOf(label), graceMsOf(label))

    fun expired(q: QueuedSay, nowMs: Long): Boolean = q.validMs != null && nowMs - q.atMs > q.validMs

    /** 다음에 말할 항목 번호: 만료 안 된 것 중 등급이 가장 앞이고, 같은 등급이면 줄 순서. 없으면 null */
    fun pick(items: List<QueuedSay>, nowMs: Long): Int? {
        var best: Int? = null
        for (i in items.indices) {
            val q = items[i]
            if (expired(q, nowMs)) continue
            if (best == null || q.rank.ordinal < items[best].rank.ordinal) best = i
        }
        return best
    }
}
