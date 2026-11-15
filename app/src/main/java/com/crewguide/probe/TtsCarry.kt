package com.crewguide.probe

/**
 * r10 N(2026-11-15): 화면을 다시 그릴 때(Activity 재생성: 글꼴 크기·야간/주간·창 배치) 유지 음성(keep=true)이 빠지지 않게 넘긴다.
 * 순수 규칙([TtsCarryRule]) + 프로세스 보관소([TtsCarryStore]). Android·SDK 클래스를 쓰지 않는다.
 *
 * 원인(r9b): 자동 안내 줄(MainActivity.autoQueue)과 지금 말하는 자동 안내(autoSayItem)는 Activity 필드다.
 *  - 재생성 때 onDestroy가 tts.release()로 재생 중 문장을 끊고 결과 콜백도 떼어 낸다(onAutoResult가 오지 않음).
 *  - 줄에 서 있던 문장은 Activity와 함께 사라진다.
 *  - TripGuide·ArrivalAsk는 문장을 만들 때 이미 '말함'으로 적는다(arrivedSaid·asked 등). 그래서 새 화면은 그 문장을 다시 만들지 않는다.
 *  → 도착 안내·남은 시간·정차 질문 음성이 빠졌다. 야간 전환은 TTS가 쉬는 때만 다시 그리지만 줄에 남은 문장은 보지 않았고,
 *    글꼴 크기 변경(configChanges에 fontScale 없음)은 말하는 중에도 바로 다시 만든다.
 *
 * 고침
 *  1. 재생성(isChangingConfigurations)으로 끝나는 onDestroy에서, tts.release() 전에 유지 음성을 [TtsCarryStore]로 넘긴다([pack]).
 *     재생 중이던 문장은 처음부터 다시 낸다(끊긴 채 끝났으므로). 말풍선은 이미 올렸으니 다시 올리지 않는다(resaid ≥ 1, BubblePushRule).
 *  2. 새 화면은 TTS가 준비되고 기어·플랫폼 첫 값을 받은 뒤(최대 [GATE_WAIT_MAX_MS]) 이어 낸다([adoptAction]·[adopt]).
 *     재생성 직후에는 '기어 확인 중' 잠금이라 유효 P 전용 안내·정차 질문이 보낼 때 검사에서 버려지기 때문이다.
 *  3. 같은 문장이 두 번 나지 않게: 넘김마다 순번(seq)을 붙여 한 번만 받는다(받은 순번 이하는 버림).
 *     같은 이름+글이 새 화면 줄(또는 지금 말하는 안내)에 이미 있으면 넣지 않는다.
 *
 * 넘기지 않는 것([verdict])
 *  - keep=false: 확인 단계 재안내. 확인은 CheckSessionStore가 이어 가며 필요하면 다시 만든다.
 *  - 벨트 풀림: 결정 31 다시 알림을 새 화면이 onStart에서 다시 예약한다(보낼 때 조건이 좌석·기어에 묶여 있음).
 *  - 두고 내림: 나간 것만 세고 못 나가면 다시 시도하는 r8 5절 규칙이 다시 말한다(넘기면 두 번 날 수 있음).
 *  - 홈 인사: 이번 실행 1회, 옛 화면의 홈 조건에 묶여 있다.
 *  - 보낼 때 조건(stillValid)을 새 화면에서 다시 만들 수 없는 안내([RECHECK_LABELS] 밖).
 * 오디오 포커스·내비 음성 겹침 방지·차량 경고 우선은 그대로다: 이어 낸 문장도 같은 줄(pumpAuto → speakAuto)로 나간다.
 * 메모리만(디스크 저장 안 함). 프로세스가 끝나면 사라진다.
 */

/** 넘길 후보(Activity 줄의 한 항목을 값만 옮긴 것). [hasCheck] = 보낼 때 다시 볼 조건(stillValid)이 있었는가 */
data class CarryCandidate(
    val text: String,
    val label: String,
    val keep: Boolean,
    val trip: TripSayKind?,
    val hasCheck: Boolean,
    val chime: Boolean,
    val atMs: Long,
    val resaid: Int,
)

/** 보관소에 넘긴 유지 음성 한 줄 */
data class CarriedSay(
    /** 넘김 순번(같은 문장을 두 번 받지 않게) */
    val seq: Long,
    val text: String,
    val label: String,
    val trip: TripSayKind?,
    val chime: Boolean,
    /** 처음 줄에 선 시각(유효 시간 판단은 그대로 이어 간다, elapsedRealtime) */
    val atMs: Long,
    val resaid: Int,
    /** 넘길 때 재생 중이었는가(처음부터 다시) */
    val wasSpeaking: Boolean,
    /** 새 화면에서 보낼 때 조건을 다시 붙여야 하는가 */
    val recheck: Boolean,
    val savedAtMs: Long,
)

enum class CarryVerdict(val label: String) {
    CARRY("넘김"),
    CARRY_RECHECK("넘김·조건 다시 봄"),
    DROP_NOT_KEEP("유지 음성 아님"),
    DROP_SELF_RETRY("새 화면이 다시 만듦"),
    DROP_NO_RECHECK("조건을 다시 만들 수 없음"),
}

data class TtsCarryState(
    val items: List<CarriedSay> = emptyList(),
    val nextSeq: Long = 1L,
    /** 새 화면이 이미 받은 마지막 순번 */
    val lastAdoptedSeq: Long = 0L,
)

data class CarryPack(
    val state: TtsCarryState,
    /** 이번에 새로 넘긴 수 */
    val carried: Int,
    /** 그중 재생 중이라 처음부터 다시 낼 수 */
    val restarted: Int,
    /** 넘기지 않은 것(이름, 사유) */
    val dropped: List<Pair<String, CarryVerdict>>,
)

data class CarryAdopt(
    val state: TtsCarryState,
    /** 새 화면 줄에 넣을 것(넘긴 순서) */
    val items: List<CarriedSay>,
    /** 같은 문장이 이미 있어 넣지 않은 것(이름) */
    val skippedDup: List<String>,
    /** 너무 오래 보관돼 버린 것(이름) */
    val skippedOld: List<String>,
)

enum class AdoptAction {
    /** 넘긴 것이 없다 */
    NONE,
    /** TTS 준비를 기다린다(준비되면 onReady가 다시 부른다) */
    WAIT_TTS,
    /** 기어·플랫폼 첫 값을 기다린다(호출자가 [TtsCarryRule.ADOPT_RECHECK_MS] 뒤 다시) */
    WAIT_GATE,
    ADOPT,
    /** TTS 초기화 실패: 보관한 것을 버린다(화면 문구로만) */
    DROP,
}

object TtsCarryRule {
    /** 이보다 오래 보관된 문장은 이어 내지 않는다(오래된 안내 부활 방지) */
    const val MAX_AGE_MS = 30_000L
    /** 새 화면에서 기어·플랫폼 첫 값을 기다리는 최대 시간(onCreate 기준). 지나면 그냥 줄에 넣는다(보낼 때 검사는 그대로) */
    const val GATE_WAIT_MAX_MS = 8_000L
    /** 첫 값을 기다릴 때 다시 보는 간격 */
    const val ADOPT_RECHECK_MS = 300L

    /** 새 화면이 스스로 다시 만들거나 다시 시도하는 안내(넘기면 두 번 날 수 있음) */
    val SELF_RETRY_LABELS: Set<String> = setOf(VoiceQueueRule.LABEL_BELT, LeftBehindRule.VOICE_LABEL, BubblePushRule.LABEL_HOME_GREET)
    /** 보낼 때 조건을 새 화면에서 같은 식으로 다시 붙이는 안내(MainActivity.carriedCheck와 짝) */
    val RECHECK_LABELS: Set<String> = setOf(VoiceQueueRule.LABEL_TIP, ArrivalAskText.VOICE_LABEL, ArrivalAskText.ALIGHT_VOICE_LABEL)

    fun verdict(c: CarryCandidate): CarryVerdict = when {
        !c.keep -> CarryVerdict.DROP_NOT_KEEP
        c.label in SELF_RETRY_LABELS -> CarryVerdict.DROP_SELF_RETRY
        !c.hasCheck -> CarryVerdict.CARRY
        c.label in RECHECK_LABELS -> CarryVerdict.CARRY_RECHECK
        else -> CarryVerdict.DROP_NO_RECHECK
    }

    private fun fresh(c: CarriedSay, nowMs: Long): Boolean = nowMs >= c.savedAtMs && nowMs - c.savedAtMs <= MAX_AGE_MS

    /**
     * 재생성 직전 넘기기. [speaking] = 재생 중(요청했고 결과 전)인 자동 안내, [queued] = 줄 순서.
     * 아직 받지 않은 이전 넘김(연달아 다시 그린 경우)은 앞에 그대로 둔다. [enabled]=false면 아무것도 하지 않는다(r9b와 같음).
     */
    fun pack(prev: TtsCarryState, speaking: CarryCandidate?, queued: List<CarryCandidate>, nowMs: Long, enabled: Boolean): CarryPack {
        if (!enabled) return CarryPack(prev, 0, 0, emptyList())
        val kept = prev.items.filter { it.seq > prev.lastAdoptedSeq && fresh(it, nowMs) }
        val out = kept.toMutableList()
        val dropped = mutableListOf<Pair<String, CarryVerdict>>()
        var seq = prev.nextSeq
        var restarted = 0
        val all = listOfNotNull(speaking?.let { it to true }) + queued.map { it to false }
        for ((c, sp) in all) {
            val v = verdict(c)
            if (v != CarryVerdict.CARRY && v != CarryVerdict.CARRY_RECHECK) { dropped += c.label to v; continue }
            out += CarriedSay(
                seq = seq++, text = c.text, label = c.label, trip = c.trip,
                chime = if (sp) false else c.chime,
                atMs = c.atMs,
                resaid = if (sp) maxOf(c.resaid, 1) else c.resaid,
                wasSpeaking = sp, recheck = v == CarryVerdict.CARRY_RECHECK, savedAtMs = nowMs,
            )
            if (sp) restarted++
        }
        return CarryPack(prev.copy(items = out, nextSeq = seq), out.size - kept.size, restarted, dropped)
    }

    /** 새 화면에서 지금 받을지 */
    fun adoptAction(hasItems: Boolean, ttsReady: Boolean, ttsFailed: Boolean, gateSettled: Boolean, waitedMs: Long): AdoptAction = when {
        !hasItems -> AdoptAction.NONE
        ttsFailed && !ttsReady -> AdoptAction.DROP
        !ttsReady -> AdoptAction.WAIT_TTS
        gateSettled || waitedMs >= GATE_WAIT_MAX_MS -> AdoptAction.ADOPT
        else -> AdoptAction.WAIT_GATE
    }

    /**
     * 새 화면이 받는다(한 번만). 받은 순번 이하·너무 오래된 것은 버리고, [existing](이름, 글)에 이미 있는 문장은 넣지 않는다.
     * 보관소는 비우고 받은 순번을 올린다.
     */
    fun adopt(s: TtsCarryState, nowMs: Long, existing: Set<Pair<String, String>>): CarryAdopt {
        val unseen = s.items.filter { it.seq > s.lastAdoptedSeq }
        val old = unseen.filter { !fresh(it, nowMs) }
        val seen = existing.toMutableSet()
        val out = mutableListOf<CarriedSay>()
        val dup = mutableListOf<String>()
        for (c in unseen) {
            if (c in old) continue
            val key = c.label to c.text
            if (key in seen) { dup += c.label; continue }
            seen += key
            out += c
        }
        val maxSeq = maxOf(s.lastAdoptedSeq, s.items.maxOfOrNull { it.seq } ?: 0L)
        return CarryAdopt(s.copy(items = emptyList(), lastAdoptedSeq = maxSeq), out, dup, old.map { it.label })
    }

    /** 받지 않고 버린다(TTS 초기화 실패). 순번은 받은 것으로 올려 다시 나오지 않게 한다 */
    fun discard(s: TtsCarryState): TtsCarryState =
        s.copy(items = emptyList(), lastAdoptedSeq = maxOf(s.lastAdoptedSeq, s.items.maxOfOrNull { it.seq } ?: 0L))
}

/** 프로세스 메모리 보관소(메인 스레드 전용). 프로세스가 끝나면 사라진다 */
object TtsCarryStore {
    var state = TtsCarryState()
}
