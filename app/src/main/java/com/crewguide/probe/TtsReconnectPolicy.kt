package com.crewguide.probe

/**
 * v4 G4(2026-11-12): TTS 연결 상실 회복 규칙. Android·SDK 클래스를 쓰지 않는 순수 로직이다.
 *
 * 배경(에뮬레이터 실측 2026-11-12): 같은 프로세스의 다른 TextToSpeech 인스턴스(debug 측정기)가 release된 뒤,
 * 앱 TtsGuide의 발화가 onStart를 한 번도 받지 못하고 매번 시작 대기 상한(6초)으로 '확인 불가'가 됐다. 앱이 다시 시작될 때까지 회복되지 않았다.
 * SDK에는 연결 상태 콜백이 없으므로(공개 API: speak·stop·EventListener·initialize·release) **연속된 시작 실패**를 연결 상실 신호로 쓴다.
 *
 * 규칙
 * 1. onStart가 오면 연속 실패 수를 0으로 한다(정상 재생 확인).
 * 2. 시작 대기 상한 초과(START_TIMEOUT)가 [FAILS_TO_RECONNECT]번 연속이면 TTS를 다시 초기화한다.
 * 3. 재초기화는 성공 확인(onStart) 없이 [MAX_RECONNECTS]번까지만 한다(무한 반복 방지). 성공하면 다시 허용한다.
 * 4. 실패한 발화를 다시 말하지 않는다(같은 안내 반복 금지). 다음 발화부터 새 연결을 쓴다. 완료로 바꾸지 않는다(자동 [예] 없음).
 * 종료 대기 상한(END_TIMEOUT)은 세지 않는다: 발화 도중 서비스가 죽은 경우는 시스템이 다음 요청에서 서비스를 다시 띄워 회복됐다(v3 반복 6 실측).
 */
data class TtsReconnectState(
    val consecutiveStartFails: Int = 0,
    /** 마지막 성공(onStart) 뒤 재초기화 횟수 */
    val reconnectsSinceOk: Int = 0,
)

object TtsReconnectPolicy {
    /**
     * 2026-11-12 PHASE A2: 2→1. 2회 기준에서는 안내·질문 두 문장이 각각 시작 대기(6s)를 다 쓴 뒤에야 재연결해
     * 다시 안내 가능까지 12.2s가 걸렸다(v4.1 에뮬 실측). 재초기화는 49~170ms이고 [MAX_RECONNECTS] 상한이 반복을 막는다.
     * 시작 대기 상한(6s)·종료 상한은 줄이지 않는다(정상 긴 문장 보호).
     */
    const val FAILS_TO_RECONNECT = 1
    const val MAX_RECONNECTS = 3

    fun onStartOk(@Suppress("UNUSED_PARAMETER") s: TtsReconnectState): TtsReconnectState = TtsReconnectState()

    /** 시작 대기 상한 초과. second=true면 지금 다시 초기화한다 */
    fun onStartTimeout(s: TtsReconnectState): Pair<TtsReconnectState, Boolean> {
        val fails = s.consecutiveStartFails + 1
        return if (fails >= FAILS_TO_RECONNECT && s.reconnectsSinceOk < MAX_RECONNECTS)
            TtsReconnectState(consecutiveStartFails = 0, reconnectsSinceOk = s.reconnectsSinceOk + 1) to true
        else s.copy(consecutiveStartFails = fails) to false
    }
}
