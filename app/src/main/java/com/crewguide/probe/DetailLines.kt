package com.crewguide.probe

/**
 * 상세 상태 줄을 두 묶음으로 나눈다(순수 함수, Android 클래스 없음). 문구는 기존 그대로.
 * guardian: 보호자용 세 줄(음성 사용 불가 사유, 음성 안내(TTS) 상태, 기어 수신 상태) — '상태 안내' 접이식(2026-11-14: "(가안)" 표기 지움)
 * dev: 나머지 줄 — '상세 상태(가안, 개발용)' 접이식(개발용 보기 안)
 * 각 줄은 '\n'으로 끝난다(기존 조립과 같음).
 */
object DetailLines {
    const val GUARDIAN_TITLE = "상태 안내"
    const val DEV_TITLE = "상세 상태(가안, 개발용)"

    data class Split(val guardian: String, val dev: String)

    /** ttsStatus: tts?.statusLines() 결과, tts가 아직 없으면 null */
    fun split(
        voiceEnabled: Boolean,
        sttDisabledText: String,
        sttReady: Boolean,
        guidanceEnabled: Boolean,
        ttsStatus: String?,
        gearLinkOk: Boolean,
        gearName: String?,
        targetSeats: List<String>
    ): Split {
        val g = StringBuilder()
        val d = StringBuilder()
        d.append("[보호자 확인 흐름(터치/음성 응답, 벨트 값 출처는 아래 표시)] 응답 방식=" + (if (voiceEnabled) "터치 + 음성(ON_DEVICE, 미검증)" else "터치만") + " (잠정)\n")
        if (!voiceEnabled) d.append("음성 응답 꺼짐(터치만)\n")
        else if (sttDisabledText.isNotEmpty()) g.append(sttDisabledText).append('\n')
        else if (!sttReady) g.append("음성 준비 중 또는 미초기화 (터치로 응답 가능)\n")
        if (!guidanceEnabled) d.append("안내 음성 꺼짐(화면 문구만)\n")
        else g.append(ttsStatus ?: "음성 안내: 준비 중").append('\n')
        g.append("기어(실제 수신): ").append(if (gearLinkOk) (gearName ?: "불명") else "수신 안 됨").append('\n')
        d.append("확인 대상 좌석(보호자 선택): ").append(if (targetSeats.isEmpty()) "없음" else targetSeats.joinToString()).append('\n')
        d.append("  ※ 점유 신호는 참고용이며 자동으로 대상이 되지 않음\n")
        return Split(g.toString(), d.toString())
    }
}
