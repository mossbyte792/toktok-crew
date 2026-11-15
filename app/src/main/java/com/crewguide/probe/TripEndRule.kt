package com.crewguide.probe

// 이동 끝 신호 기록·끝내기 규칙. Android·SDK 클래스를 쓰지 않는 순수 로직이다.
//
// 2026-11-14 사용자 지시: 내비 신호가 오면 "이번 이동이 끝난 것 같아요(내비 신호 기준). 끝내기를 눌러 주세요"라고 알리던
// 이동 끝 '안내'(안내 카드·무대 TRIP_END 말풍선·같은 문장 음성)는 없앴다(11-08 수동 끝내기 시절 기능). 이동 끝은
// '모두 내렸어요' 자동 마무리(TripGuide ALL_LEFT)와 [이번 이동 끝내기] 버튼(확인 대화상자)으로만 처리한다.
// 내비 도착·경로 종료 신호의 '기록'은 그대로 쓴다: 도착 안내(TripGuide), 결정 18 판단, 끝내기 허용 근거(finishTrip), 끝내기 로그의 출처.
// 그래서 안내 문구(PROMPT_TEXT)와 안내 표시 판정(showPrompt)은 지웠다.
//
// 규칙
// - DESTINATION_ARRIVED, ROUTE_ENDED만 '이동 끝 신호 수신'으로 기록한다. NAVIGATION_STATUS, ERROR, NONE은 이동 끝이 아니다.
// - 연결 FAILED, 이벤트가 한 번도 없는 UNKNOWN은 '내비 신호 확인 불가/확인 전'이며 이동 끝으로 해석하지 않는다.
// - 신호는 기록만 한다(도착 이벤트만으로 화면을 열거나 안내하지 않음).
// - 같은 이동에서 끝내기 처리는 한 번만 한다.
// - 새 이동의 시작(단순화): 앱 재시작, 또는 끝내기를 마친 뒤 P가 아니게 되는 시점. 기어가 P에서 벗어났다 돌아오는 것만으로는 새 이동으로 보지 않는다.
// - 수동 끝내기(내비 신호 없이)는 유효한 P에서만 가능하고 같은 초기화를 한다.
// - PM 결정 18(2026-11-13, 에뮬 관찰): 경로가 시작된 뒤 주행(D 등 유효한 비-P)이 한 번도 관측되지 않은 상태에서 온 ROUTE_ENDED는
//   이동 끝 신호로 쓰지 않는다. 에뮬레이터 내비는 경로 시작 직후 바로 onRouteEnded를 보낸다(5회 모두). 무시는 호출자가 로그로 남긴다.
//   DESTINATION_ARRIVED는 그대로 받는다. 경로 시작을 본 적이 없으면(앱이 경로 뒤에 켜짐 등) 예전처럼 받는다.

enum class NaviEvent { DESTINATION_ARRIVED, ROUTE_ENDED, NAVIGATION_STATUS, ERROR, NONE }

enum class NaviLink { UNKNOWN, READY, FAILED }

/** signalReceived: 이번 이동의 끝 신호 기록. finished: 이번 이동은 이미 끝내기 처리함(이동당 1회). endedBySignal: 끝내기 때 신호가 있었는지 */
data class TripEndState(
    val signalReceived: Boolean = false,
    val finished: Boolean = false,
    val endedBySignal: Boolean = false,
    /** 결정 18: 이번 경로의 시작(onRouteStarted)을 봤는가 */
    val routeStarted: Boolean = false,
    /** 결정 18: 그 경로 시작 뒤 주행을 관측했는가 */
    val drivenSinceRoute: Boolean = false,
)

/**
 * 2026-11-14: 신호 기반 안내 표시(showPrompt)는 지웠다(이동 끝 안내 제거).
 * [showManual]·[showDone]은 옛 화면 카드(MainActivity.tripCard, 새 무대에서는 늘 숨김 = 사용 안 함)만 읽는다.
 */
data class TripEndOutput(
    val tripEndConfirmed: Boolean, // 이동 끝 신호 기록 여부(안전·도착 보장 아님)
    val showManual: Boolean,       // (옛 화면 카드, 사용 안 함) 수동 끝내기 보조 버튼 표시
    val showDone: Boolean,         // (옛 화면 카드, 사용 안 함) '이번 이동을 마쳤어요' 표시
)

object TripEndRule {
    fun isEndSignal(e: NaviEvent): Boolean = e == NaviEvent.DESTINATION_ARRIVED || e == NaviEvent.ROUTE_ENDED

    /** 이벤트 수신. 이미 끝내기를 마친 이동에서는 기록하지 않는다. 결정 18로 무시하는 ROUTE_ENDED도 기록하지 않는다 */
    fun onEvent(s: TripEndState, e: NaviEvent): TripEndState =
        if (isEndSignal(e) && !s.finished && !ignoresRouteEnded(s, e)) s.copy(signalReceived = true) else s

    /** 결정 18: 경로 시작 뒤 주행 관측 전에 온 ROUTE_ENDED인가(이동 끝 신호로 쓰지 않음) */
    fun ignoresRouteEnded(s: TripEndState, e: NaviEvent): Boolean =
        e == NaviEvent.ROUTE_ENDED && s.routeStarted && !s.drivenSinceRoute

    /** 결정 18: 내비 경로 시작(총 시간 이벤트). 새 경로마다 주행 관측을 다시 센다 */
    fun onRouteStarted(s: TripEndState): TripEndState = s.copy(routeStarted = true, drivenSinceRoute = false)

    /**
     * 움직임 변화(2026-11-08 Unit 5.3, F7). 끝내기를 마친 뒤 주행(MOVING)이 확인될 때만 새 이동으로 본다.
     * 기어 불명·단절(UNKNOWN)은 새 이동의 근거가 아니다(감시 시간 초과 한 번에 이동 끝 기록이 지워지던 문제)
     */
    fun onMotion(s: TripEndState, moving: Boolean): TripEndState = when {
        s.finished && moving -> TripEndState()
        moving && s.routeStarted && !s.drivenSinceRoute -> s.copy(drivenSinceRoute = true)   // 결정 18
        else -> s
    }

    /** 기어 변화(이전 규칙, 앱은 [onMotion]을 쓴다). 끝내기를 마친 뒤 유효한 P가 아니게 되면 새 이동으로 본다 */
    fun onGear(s: TripEndState, validP: Boolean): TripEndState =
        if (s.finished && !validP) TripEndState() else s

    /** 끝내기(신호 기반 또는 수동): 이동 끝 기록을 비우고 이번 이동은 마친 것으로 한다 */
    fun finish(s: TripEndState): TripEndState =
        if (s.finished) s else TripEndState(signalReceived = false, finished = true, endedBySignal = s.signalReceived,
            routeStarted = s.routeStarted, drivenSinceRoute = s.drivenSinceRoute)

    fun evaluate(s: TripEndState, validP: Boolean): TripEndOutput = TripEndOutput(
        tripEndConfirmed = s.signalReceived,
        showManual = validP && !s.finished,
        showDone = validP && s.finished,
    )

    /** 상태 줄 문구. 연결 FAILED는 원인을 붙인다 */
    fun linkLabel(link: NaviLink, cause: String?): String = when (link) {
        NaviLink.UNKNOWN -> "내비 신호: 확인 전"
        NaviLink.READY -> "내비 신호: 연결됨(수신 확인 전)"
        NaviLink.FAILED -> "내비 신호: 확인 불가" // 원인 클래스명(cause)은 화면에 쓰지 않는다
    }

    // 아래 문구 3개는 옛 화면 카드(MainActivity.tripCard)만 쓴다. 새 무대(StageFlags.NEW_STAGE)에서는 그 카드가 늘 숨김이라 화면에 나오지 않는다(사용 안 함).
    // 안내 문구 PROMPT_TEXT("이번 이동이 끝난 것 같아요…")는 2026-11-14에 지웠다.
    const val DONE_TEXT = "이번 이동 정리를 마쳤어요"
    const val DONE_MANUAL_TEXT = "이번 이동 정리를 마쳤어요(내비 신호 없이 끝냄)"
    const val TITLE = "이번 이동 끝내기"
}
