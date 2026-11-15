package com.crewguide.probe

/**
 * 2026-11-12 PHASE A2: 보호자 질문 [다시 듣기] 노출 규칙(순수 로직, Android 클래스 없음).
 *
 * 배경: v4.1 에뮬 실측에서 TTS 장애 뒤 재연결은 됐지만 다시 말할 수단이 없어, 다음 소리는 무응답 재안내 타이머(21.2s)에 묶였다.
 * 자동 재질문은 하지 않는다(같은 안내 반복 금지). 대신 보호자가 직접 누를 때만 현재 질문을 한 번 재생한다.
 *
 * 규칙
 * 1. 이번 질문의 음성이 실패(시작 확인 없음·오류·완료 확인 없음·포커스 상실·재연결 중 건너뜀)했을 때만 보인다.
 * 2. 유효한 P + 플랫폼 허용(잠금 없음)이고 질문 단계일 때만. D/R/N·불명·단절·UXR 제한이면 숨긴다.
 * 3. 음성 엔진이 준비돼 있고(재연결 완료) 다른 발화·음성 인식이 진행 중이 아닐 때만(빠른 연타·중복 큐 방지).
 * 4. 안내 음성을 사용자가 끈 경우는 실패가 아니므로 보이지 않는다.
 * 5. 다시 듣기는 질문 상태를 바꾸지 않는다. [예]는 여전히 보호자 터치·음성 응답으로만 생긴다(재생 성공이 [예]가 아님).
 */
object VoiceReplayRule {
    const val LABEL = "다시 듣기"
    const val DESC = "질문을 한 번 더 듣기"

    fun available(
        asking: Boolean,
        lockedOrNotPark: Boolean,
        questionVoiceFailed: Boolean,
        guidanceOn: Boolean,
        ttsReady: Boolean,
        ttsBusy: Boolean,
        sttActive: Boolean,
    ): Boolean = asking && !lockedOrNotPark && questionVoiceFailed && guidanceOn && ttsReady && !ttsBusy && !sttActive

    /** 음성 실패로 볼 종료 사유. 앱 중지·게이트 중단은 사용자가/안전 규칙이 끊은 것이라 [다시 듣기] 대상이 아니다 */
    fun isVoiceFailure(end: TtsEnd): Boolean = when (end) {
        TtsEnd.DONE, TtsEnd.APP_STOP, TtsEnd.GATE_STOP -> false
        else -> true
    }
}
