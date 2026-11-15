package com.crewguide.probe

/**
 * TTS 발화가 끝난 사유(2026-11-08 P0-C 재감사, 팀 지시 4). Android·SDK 클래스를 쓰지 않는 순수 값이다.
 * 정상 완료(onDone)만 [DONE]이며, 나머지는 '안내가 전달됐다'는 근거가 아니다.
 */
enum class TtsEnd(val label: String) {
    /** SDK onDone(현재 발화의 것으로 귀속됨) */
    DONE("완료"),
    /** SDK onError 또는 SDK onStop(앱이 요청하지 않은 중지) */
    ERROR("오류·중지"),
    /** 요청 뒤 onStart가 상한 안에 오지 않음 */
    START_TIMEOUT("시작 확인 없음"),
    /** onStart 뒤 종료 콜백이 상한 안에 오지 않음 */
    END_TIMEOUT("완료 확인 없음"),
    /** 오디오 포커스 상실로 앱이 멈춤 */
    FOCUS_LOST("포커스 상실"),
    /** 앱이 명시적으로 stop()(새 발화·화면 갱신 등, 흐름 취소가 아님) */
    APP_STOP("앱 중지"),
    /** 기어 이탈·UXR 제한·연결 상실·화면 이탈로 흐름이 취소되며 멈춤 */
    GATE_STOP("게이트 중단"),
}

/**
 * 보호자 안내(S04) 단계가 어떻게 끝났는가. 흐름 상태에 남겨 질문 화면 문구를 정한다.
 * [SPOKEN]만 '안내 음성 완료'이며, 어느 값도 보호자 '예'(보고)를 만들지 않는다.
 */
enum class AnnounceOutcome(val spokenConfirmed: Boolean, val isFault: Boolean, val note: String) {
    /** 안내 발화 onDone(음성 재생 완료일 뿐, 보호자 확인·안전 확인과 별개) */
    SPOKEN(true, false, ""),
    /** 오류·시작/완료 확인 없음·포커스 상실·발화 요청 예외·마지막 안전망 상한. 안내 전문을 화면에 남기고 터치로 확인 */
    UNCONFIRMED(false, true, "소리 안내를 확인하지 못했어요 · " + UserTexts.GUARDIAN_PROMPT),
    /** 사용자가 안내 음성을 끔(오류 아님). 시간 경과로 넘기지 않고 바로 화면 읽기·터치 경로 */
    VOICE_OFF(false, false, "안내 음성을 끈 상태예요 · " + UserTexts.GUARDIAN_PROMPT),
    /** 음성 엔진 미준비·초기화 실패·오디오 포커스 거부로 발화하지 못함. 화면 읽기·터치 경로 */
    NOT_READY(false, true, "소리 안내를 준비하지 못했어요 · " + UserTexts.GUARDIAN_PROMPT),
    /** 흐름은 그대로인데 앱이 안내 발화를 멈춤. 같은 세대여도 완료 아님. 질문으로 넘기지 않고 보호자의 명시적 [다시 시작]을 기다린다 */
    CANCELLED(false, true, "안내 음성이 중단됐어요. 준비되면 다시 시작해 주세요"),
}

/**
 * 음성 실패 시 읽기·터치 경로의 완료 조건(2026-11-08 ch 조건, 코드 기록).
 *  - 안내 전문([UserTexts.GUARDIAN_PROMPT])은 질문 화면 보조 줄([AnnounceOutcome.note])에 남는다.
 *  - 다음 절차(아이 안내)는 보호자의 명시적 [예](터치 또는 허용 목록 음성) **하나뿐**이며, 그 시점 게이트(G2)를 다시 판정한다.
 *  - 시간 경과(무응답 상한)는 재안내·보호자 대기로만 이어지고 '예'·완료가 되지 않는다. 화면 전환만으로 완료되지 않는다.
 */
object ReadPathRule {
    /** 질문 화면에서 다음 절차로 갈 수 있는 유일한 이벤트 */
    const val ONLY_ADVANCE = "보호자 예(터치·허용 음성) + 실행 시점 정차 허용"
}

object NoSpeechRule {
    /** 안내 발화 요청이 나가지 않았을 때의 결과. 사용자 끔을 오류처럼 다루지 않는다 */
    fun outcome(userEnabled: Boolean, engineReady: Boolean, speakException: Boolean): AnnounceOutcome = when {
        !userEnabled -> AnnounceOutcome.VOICE_OFF
        engineReady && speakException -> AnnounceOutcome.UNCONFIRMED
        else -> AnnounceOutcome.NOT_READY
    }
}

object AnnounceEndRule {
    /**
     * 안내 발화 종료 사유 → 흐름 이벤트.
     *  - DONE → 질문(SPOKEN)
     *  - 오류·상한·포커스 상실 → 질문(UNCONFIRMED: 안내 문구 유지 + 터치 확인). 통합설계 v1.1 S04 'TTS 완료 또는 실패/상한 대기 후 동일 질문'
     *  - APP_STOP·GATE_STOP → 취소(보호자 대기). 30초 상한을 기다리지 않는다. 게이트 취소는 보통 흐름 세대가 이미 올라가 이 이벤트가 버려지지만,
     *    흐름이 아직 같은 세대로 남은 예외 순서에서도 안내 단계에 머물러 있지 않게 한다(2026-11-08 독립 감사 LOW 1)
     */
    fun eventFor(end: TtsEnd, flowGeneration: Int): BoardingEvent = when (end) {
        TtsEnd.DONE -> BoardingEvent.AnnounceFinished(flowGeneration, AnnounceOutcome.SPOKEN)
        TtsEnd.ERROR, TtsEnd.START_TIMEOUT, TtsEnd.END_TIMEOUT, TtsEnd.FOCUS_LOST ->
            BoardingEvent.AnnounceFinished(flowGeneration, AnnounceOutcome.UNCONFIRMED)
        TtsEnd.APP_STOP, TtsEnd.GATE_STOP -> BoardingEvent.AnnounceCancelled(flowGeneration)
    }

    /** 로그 한 줄: 종료 사유 → 화면 상태 → 완료 플래그 → 다음 허용 동작 */
    fun trace(cause: String, outcome: AnnounceOutcome): String {
        val screen = if (outcome == AnnounceOutcome.CANCELLED) "보호자 대기(안내 중단 문구)"
            else if (outcome.spokenConfirmed) "질문" else "질문 + 안내 전문 보조 줄"
        val next = if (outcome == AnnounceOutcome.CANCELLED) "[다시 시작]만" else "예/아니오(터치·음성), 무응답=재안내·대기"
        return "[보호자확인] 종료사유=$cause(${outcome.name}) → 화면=$screen → 안내완료=${outcome.spokenConfirmed} → 다음=$next"
    }
}
