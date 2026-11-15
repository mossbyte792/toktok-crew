package com.crewguide.probe

/**
 * TTS 종료 콜백 귀속(2026-11-08 Unit 5.3, ch DECISION 5). Android·SDK 클래스를 쓰지 않는 순수 로직이다.
 *
 * 배경: Pleos TTS SDK 3.3.1의 공개 API는 `speak(String, Boolean)`이고 `EventListener`(onStart·onDone·onStop·onError)에
 * 발화 식별자가 없다(APK dex 시그니처로 확인). 그래서 앱이 발화마다 세대 번호(utteranceId 역할)를 붙이고,
 * SDK 콜백의 **순서**로 어느 발화의 것인지 정한다. Unit 5.2의 600ms 시간 창 규칙(TtsStaleStop)을 대체한다.
 *
 * 규칙
 * 1. 종료 콜백(onDone·onStop·onError)은 **onStart를 받은 현재 발화**에만 귀속한다.
 * 2. 현재 발화가 아직 onStart 전인데 종료 콜백이 오면, 앞선 발화(앱이 stop()했거나 이미 결과가 정해진 발화)의 늦은 콜백일 수 있다.
 *    앞선 발화가 있었으면(tail=true) 버린다. 초기화 뒤 첫 발화처럼 앞선 발화가 없으면 현재 발화의 것으로 본다.
 * 3. 결과가 한 번 정해진 발화의 추가 종료 콜백(onDone 뒤 onStop 등)은 무시한다(멱등). 다음 발화가 onStart를 받기 전까지는 2로 걸러진다.
 * 4. onStart가 오지 않은 채 [START_TIMEOUT_MS]가 지나면 그 발화는 '확인 불가'로 끝낸다(완료로 보지 않음).
 *    규칙 2로 버려진 새 발화의 진짜 빠른 오류도 여기서 실패로 정리된다(안전 쪽: 전달 완료로 바뀌지 않음).
 * 시간 창이 없으므로 콜백 지연·메인 스레드 지연 길이와 무관하게 새 발화의 정상 완료(onStart 뒤 onDone)를 버리지 않는다.
 *
 * 남는 전제(기기 확인 대상): SDK가 콜백을 발생 순서대로 보내고, 새 발화의 onStart가 이전 발화의 종료 콜백보다 늦게 온다.
 * 이전 발화를 onStart 전에 멈췄는데 그 발화의 onStart가 새 발화 요청 뒤 늦게 오면 새 발화가 시작된 것으로 오인될 수 있다.
 */
data class TtsLedgerState(
    /** 결과를 기다리는 발화 번호(없으면 -1) */
    val currentId: Int = -1,
    /** 현재 발화가 onStart를 받았는가 */
    val started: Boolean = false,
    /** 앞선 발화의 늦은 콜백이 아직 올 수 있는가(앱 stop 또는 결과 확정 뒤 다음 onStart 전까지) */
    val tail: Boolean = false,
)

enum class TtsAttribution {
    /** 현재 발화의 결과 */
    CURRENT,
    /** 앞선 발화의 늦은 콜백(버림) */
    STALE,
    /** 기다리는 발화가 없음(중복·늦은 콜백, 무시) */
    NO_PENDING,
}

object TtsCallbackLedger {
    /** onStart 대기 상한(가안). 에뮬레이터 실측 요청→onStart 약 60~200ms */
    const val START_TIMEOUT_MS = 6_000L
    /**
     * onStart 뒤 종료 콜백 대기 **최대** 상한. 넘으면 '확인 불가'(완료 아님, 감사 6).
     * 2026-11-12: 실제 상한은 문장 길이로 정한다([endTimeoutMs]). 이 값은 아주 긴 문장의 상한이다.
     */
    const val END_TIMEOUT_MS = 20_000L
    /** 문장 길이 기반 상한의 하한(짧은 문장도 냉시작·메인 스레드 지연을 견딘다) */
    const val END_TIMEOUT_MIN_MS = 4_500L
    /** 예상 발화 시간 = 고정 + 글자당(반복 5 실측 18문장: 1자 707ms, 5자 1378, 30자 4195, 81자 9676 → 약 850 + 110×글자) */
    const val EXPECT_BASE_MS = 850L
    const val EXPECT_PER_CHAR_MS = 110L
    /** 예상의 1.5배 + 2.5초(냉시작 첫 PCM 지연 실측 약 +1.0초, 에뮬레이터 부하) */
    const val EXPECT_FACTOR_NUM = 3L
    const val EXPECT_FACTOR_DEN = 2L
    const val END_SLACK_MS = 2_500L

    /** 실측 기반 예상 발화 시간(ms). 상한 계산·기록용이며 완료 판단에 쓰지 않는다 */
    fun expectedSpeechMs(textLength: Int): Long = EXPECT_BASE_MS + EXPECT_PER_CHAR_MS * textLength.coerceAtLeast(0)

    /**
     * onStart 뒤 종료 콜백 대기 상한(2026-11-12 P0 TTS 무음 대기 수정, ch D1 승인).
     * 발화 도중 TTS 서비스가 죽으면 onDone·onError·onStop이 모두 오지 않는다(반복 6 [에뮬]). 고정 20초 대신
     * 문장 길이로 상한을 정해, 정상 문장은 실측의 1.7배 이상 여유를 두고(조기 종료 없음) 서비스 장애 시에는 빨리 '확인 불가'로 넘어간다.
     * 결과는 [END_TIMEOUT_MIN_MS]..[END_TIMEOUT_MS] 범위. 상한이 지나도 완료로 보지 않는다(확인 불가).
     */
    fun endTimeoutMs(textLength: Int): Long {
        val raw = expectedSpeechMs(textLength) * EXPECT_FACTOR_NUM / EXPECT_FACTOR_DEN + END_SLACK_MS
        return raw.coerceIn(END_TIMEOUT_MIN_MS, END_TIMEOUT_MS)
    }

    /** 종료 콜백 대기 시간 초과 점검. 같은 발화가 시작된 뒤 결과가 아직 없으면 true(호출자가 '확인 불가'로 끝낸다) */
    fun onEndTimeout(s: TtsLedgerState, id: Int): Pair<TtsLedgerState, Boolean> =
        if (s.currentId == id && s.started) TtsLedgerState(currentId = -1, started = false, tail = true) to true else s to false

    /** 새 발화 요청(앞선 발화는 호출자가 stop 또는 결과 확정한 상태) */
    fun onSpeak(s: TtsLedgerState, id: Int): TtsLedgerState =
        s.copy(currentId = id, started = false, tail = s.tail || s.currentId >= 0)

    /** 앱이 SDK stop()을 호출함(진행 중 발화 취소 또는 강제 정지) */
    fun onAppStop(s: TtsLedgerState): TtsLedgerState = TtsLedgerState(currentId = -1, started = false, tail = true)

    /** 요청 자체가 실패(예외)해 대기를 비움. SDK에 아무 것도 나가지 않았을 수 있으나 보수적으로 tail은 유지한다 */
    fun onAbandon(s: TtsLedgerState): TtsLedgerState = s.copy(currentId = -1, started = false)

    /** onStart 도착. 반환 second=true면 현재 발화의 시작으로 기록함 */
    fun onStart(s: TtsLedgerState): Pair<TtsLedgerState, Boolean> =
        if (s.currentId >= 0 && !s.started) s.copy(started = true, tail = false) to true else s to false

    /** 종료 콜백 도착. CURRENT면 현재 발화의 결과가 확정되고 대기가 비워진다 */
    fun onTerminal(s: TtsLedgerState): Pair<TtsLedgerState, TtsAttribution> = when {
        s.currentId < 0 -> s to TtsAttribution.NO_PENDING
        s.started || !s.tail -> TtsLedgerState(currentId = -1, started = false, tail = true) to TtsAttribution.CURRENT
        else -> s to TtsAttribution.STALE
    }

    /** onStart 대기 시간 초과 점검. 같은 발화가 아직 시작 전이면 true(호출자가 '확인 불가'로 끝낸다) */
    fun onStartTimeout(s: TtsLedgerState, id: Int): Pair<TtsLedgerState, Boolean> =
        if (s.currentId == id && !s.started) TtsLedgerState(currentId = -1, started = false, tail = true) to true else s to false
}
