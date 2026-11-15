package com.crewguide.probe

/**
 * 2026-11-14 루프 r5, **결정 41(사용자 확정(11-15))**: 도착 신호 없이 유효 P가 됐을 때 "목적지 있는 이동인가"를 그 순간의 내비 상태로 판정한다(순수 규칙).
 *
 * 문제(에뮬 확인, d03dd46 보고): 주행 중 내비 남은 시간 값이 한 번 오면(내비 화면에는 경로 없음) 그 값이 이동 끝까지 남아,
 * 목적지 있는 이동으로 보고 "목적지에 도착했나요?"를 묻지 않았다(뒤에 오는 0초는 결정 12로 무시되어 값이 지워지지 않음).
 *
 * 규칙
 *  - 1순위: NaviHelper 공식 조회 `getRouteStateInfo()` → 콜백 `onRouteStateInfo(RouteStateInfo)`의 `naviMode`(ROUTE_GUIDING·SIMULATION_GUIDING = 안내 중,
 *    SAFE_GUIDING·NOT_WORKING = 아님). NAVI_ROUTE 권한(javap 확인). `isRouting()`은 에뮬에서 안내 중에도 false라 쓰지 않는다.
 *    경로 안내 중 = [Verdict.LIVE](시간 값을 쓰고 도착 신호를 기다림), 아님 = [Verdict.GONE](옛 시간 값을 지우고 질문).
 *  - 조회를 보낼 수 없거나(내비 미연결·예외) [QUERY_TIMEOUT_MS] 안에 답이 없으면: 마지막 유효 시간 값이 [STALE_MS] 안에 갱신됐으면 LIVE, 아니면 GONE.
 *  - 조회 답을 기다리는 동안은 [Verdict.WAIT](묻지도 지우지도 않음).
 *  - 시간 값이 없으면 지울 것이 없다(GONE, 기존 질문 경로 그대로).
 *  - 시간 값이 앱 내부 모의(debug DEBUG_NAVI·모의 경로)면 실제 내비 조회로 지우지 않는다(LIVE: 모의 목적지, 모의 도착으로 끝남).
 *  - 정차마다 새로 판정한다(주행을 보면 조회 상태를 비운다, 호출자).
 *  - 내비 경로 취소(onRouteCancelled)는 지금처럼 그때 시간 값을 지운다(NaviTripEnd → NaviTimeKind.CLEARED). onRouteEnded는 에뮬 내비가
 *    경로 시작 직후에도 보내(결정 18) 시간 값을 지우는 데 쓰지 않는다.
 * 개발 C 5절(앞 이동 경로가 살아 있으면 새 확인 완료 음성에 예정 시간)과의 관계: 경로가 살아 있으면 시간을 쓰고, 없으면 옛 시간을 버린다(같은 방향).
 * 되돌리기: [ENABLED]를 false로(예전처럼 남은 시간 값이 있으면 목적지 있는 이동).
 */
object RouteLiveRule {
    /** 결정 41(사용자 확정(11-15)) 스위치 */
    const val ENABLED = true
    /** 조회 답을 기다리는 상한 */
    const val QUERY_TIMEOUT_MS = 3_000L
    /** 조회 실패·미지원 때: 마지막 유효 시간 값이 이보다 오래되면 경로가 없다고 본다 */
    const val STALE_MS = 120_000L

    enum class Verdict { LIVE, GONE, WAIT }

    /** 이번 정차의 조회 상태 */
    sealed class Query {
        object None : Query()
        data class Pending(val sentAtMs: Long) : Query()
        data class Answered(val routing: Boolean) : Query()
        object Failed : Query()
    }

    /** 정차 판정이 필요한 상황인가: 확인·출발, 주행 아님, 도착 신호 없음, 하차 마무리 전, 유효 P로 정차, 실제 시간 값이 남아 있음 */
    fun needsCheck(tg: TripGuideState, parkOk: Boolean, enabled: Boolean = ENABLED): Boolean =
        enabled && parkOk && tg.confirmed && tg.departed && !tg.drivingNow && !tg.arrivalSignal && !tg.allLeftSaid &&
            tg.parkedSinceMs >= 0 && AppFrontRule.hasRoute(tg) && tg.etaSource != SignalSource.MOCK

    fun verdict(tg: TripGuideState, q: Query, lastTimeAtMs: Long, nowMs: Long): Verdict {
        if (!AppFrontRule.hasRoute(tg)) return Verdict.GONE
        if (tg.etaSource == SignalSource.MOCK) return Verdict.LIVE
        return when (q) {
            is Query.Answered -> if (q.routing) Verdict.LIVE else Verdict.GONE
            is Query.Pending -> if (nowMs - q.sentAtMs < QUERY_TIMEOUT_MS) Verdict.WAIT else fallback(lastTimeAtMs, nowMs)
            Query.Failed, Query.None -> fallback(lastTimeAtMs, nowMs)
        }
    }

    /** 조회 실패·시간 초과 때: 최근 [STALE_MS] 안에 유효 시간 값이 왔으면 LIVE */
    fun fallback(lastTimeAtMs: Long, nowMs: Long): Verdict =
        if (lastTimeAtMs >= 0 && nowMs - lastTimeAtMs <= STALE_MS) Verdict.LIVE else Verdict.GONE
}

/** 프로세스 보관소. 메인 스레드 전용 */
object RouteLiveStore {
    var query: RouteLiveRule.Query = RouteLiveRule.Query.None
    /** 마지막으로 유효한(0 초과) 실제 내비 시간 값을 받은 시각(elapsedRealtime, 없으면 -1) */
    var lastTimeAtMs = -1L
}
