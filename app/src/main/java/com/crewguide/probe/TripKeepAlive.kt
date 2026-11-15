package com.crewguide.probe

// 2026-11-13 ISSUE-07(사용자 결정 D-1): 이동 중(확인 완료 ~ 이동 끝)에만 foreground service로 앱 동결(cached-apps freezer)을 막는다.
// 이 파일은 순수 판단만 한다(Android 클래스 없음). 서비스 시작·종료는 MainActivity, 서비스 본체는 TripKeepAliveService.
//
// 규칙
//  - 이동 중 = 이번 이동 확인 완료(자동 확인 또는 보호자 대체 확인) && 하차 마무리(ALL_LEFT) 전.
//    [이번 이동 끝내기]·흐름 취소(홈으로)·새 확인 시작은 TripGuideState를 비우므로 이동 중이 아니게 된다.
//  - 시작: 이동 중 && 꺼져 있음 && 앱이 화면에 보임(Android 14는 백그라운드에서 FGS를 시작할 수 없다. 보일 때 다시 판단).
//  - 종료: 이동 중이 아님 && 켜져 있음(시작 중 포함). 단 하차 마무리 음성이 아직 나가는 중이면 그 음성이 끝날 때까지
//    (최대 [TAIL_MAX_MS]) 기다린다. 앱이 내비 뒤에 있을 때 서비스를 먼저 내리면 마지막 음성 중에 동결될 수 있다.
//  - 앱 종료(onDestroy isFinishing)는 판단 없이 바로 끈다(호출자).
// SDK 리스너의 주인은 Activity다. 서비스는 프로세스 우선순위만 올린다(동결 방지).

enum class KeepAliveAction { START, STOP, NONE }

/** 서비스 수명 상태(프로세스 메모리, 메인 스레드 전용) */
enum class KeepAliveRun { OFF, STARTING, ON }

/**
 * 2026-11-14 PM 결정 24 ②③: 이동 서비스(FGS)가 켜진 이동 중에는 앱이 뒤로 가도(화면 이탈·전경 아님) 확인 완료 상태
 * (BoardingStep.CHILD_NOTICE·FINISHED = 예전 보호자 확인 YES_REPORTED)를 IDLE로 되돌리지 않는다. 그래서 공통 취소(StopAll)가
 * 확인 완료 음성(CHILD_NOTICE)을 '게이트 중단'으로 끊지 않는다(검수 09:32:49, 0.2초 만에 끊김).
 * 실제 P 이탈(D/R/N)·기어 불명·단절·플랫폼 제한은 예전처럼 취소한다(화면 조작 제한 그대로). 오디오 포커스 손실·차량 경고 우선은 TtsGuide가 그대로 지킨다.
 */
object TripLeaveRule {
    /** 화면 이탈(onPause LeftForeground)에서 확인 완료 상태를 유지할지 */
    fun keepOnLeave(step: BoardingStep, keepAliveOn: Boolean): Boolean =
        keepAliveOn && (step == BoardingStep.CHILD_NOTICE || step == BoardingStep.FINISHED)

    /** 게이트가 정차 허용이 아닐 때: 전경 조건만 빠진 경우(전경이라면 정차 허용)에만 유지한다 */
    fun keepOnGate(step: BoardingStep, keepAliveOn: Boolean, input: GateInput): Boolean =
        keepOnLeave(step, keepAliveOn) && !input.foreground &&
            VehicleGateRule.evaluate(input.copy(foreground = true)) == VehicleGate.PARK_ALLOWED
}

object TripKeepAliveRule {
    /** 하차 마무리 음성을 기다리는 최대 시간(콜백이 오지 않아도 서비스를 남기지 않는다) */
    const val TAIL_MAX_MS = 20_000L

    /** 서비스를 유지할 이동 구간인가 */
    fun tripActive(s: TripGuideState): Boolean = s.confirmed && !s.allLeftSaid

    /** 하차 마무리 음성 꼬리: 마무리 음성을 냈고, 아직 말하는 중이며, 상한 시간 안 */
    fun tailHold(allLeftSaid: Boolean, speaking: Boolean, allLeftAtMs: Long, nowMs: Long): Boolean =
        allLeftSaid && speaking && allLeftAtMs >= 0 && nowMs - allLeftAtMs < TAIL_MAX_MS

    /**
     * [capped]: r10 H 상한으로 이번 정차의 서비스를 끝냈다([TripCapRule.capped]). 이때는 앱이 보여도 다시 켜지 않는다(새 정차·이동 끝이면 풀림)
     */
    fun decide(tripActive: Boolean, run: KeepAliveRun, appVisible: Boolean, tailHold: Boolean, capped: Boolean = false): KeepAliveAction = when {
        tripActive && run == KeepAliveRun.OFF && appVisible && !capped -> KeepAliveAction.START
        !tripActive && run != KeepAliveRun.OFF && !tailHold -> KeepAliveAction.STOP
        else -> KeepAliveAction.NONE
    }

    /** 종료 사유(로그용). 하차 마무리면 그 사유를 우선한다 */
    fun stopReason(s: TripGuideState, why: String): String = if (s.allLeftSaid) "하차 마무리" else why
}

/**
 * 서비스와 화면이 함께 보는 프로세스 상태. 메인 스레드 전용.
 * [wanted]: 화면이 서비스를 원하는가. 시작 요청 직후(STARTING) 종료가 오면 바로 stopService하지 않고(startForeground 전 종료는
 * 플랫폼이 앱을 강제 종료할 수 있다) wanted=false만 두며, 서비스가 startForeground 뒤 이 값을 보고 스스로 끝낸다.
 */
object TripKeepAlive {
    var run = KeepAliveRun.OFF
    var wanted = false
    /** 하차 마무리 음성을 낸 시각(elapsedRealtime, 없으면 -1). 꼬리 대기 판단용 */
    var allLeftAtMs = -1L
    /** r10 H: 확인 완료 뒤 출발(D)을 기다리기 시작한 시각(elapsedRealtime, 대기 중이 아니면 -1). [TripCapRule.departMark] */
    var departWaitSinceMs = -1L
    /** r10 H: 상한으로 서비스를 끝낸 정차(그때의 TripGuideState.parkedSinceMs, 없으면 -1). [TripCapRule.capped] */
    var cappedStopMs = -1L
}

/** r10 H 시간 값. 기본은 [TripCapRule] 상수, debug만 짧게 바꾼다(DEBUG_TRIP_CAP_FAST, DevMockGuard로 release 차단) */
data class TripCapTiming(
    val departWaitMs: Long = TripCapRule.DEPART_WAIT_MS,
    val exitWaitMs: Long = TripCapRule.EXIT_WAIT_MS,
    val tickMs: Long = TripCapRule.TICK_MS,
)

/**
 * 2026-11-15 r10 H: 이동 알림(FGS) 상한. 순수 판단(Android 클래스 없음). 시계는 MainActivity의 메인 스레드 Handler가 돌린다
 * (이동 상태·차량 리스너의 주인이 Activity이고, 서비스가 켜져 있는 동안은 프로세스가 동결되지 않아 앱이 뒤에 있어도 돈다.
 * 동결됐다가 풀려도 elapsedRealtime으로 판단해 늦은 만큼 바로 처리한다).
 *  ① 확인 완료 뒤 [DEPART_WAIT_MS] 동안 출발(주행 기어)이 없으면 이동 끝내기와 같게 정리한다. 확인 완료·출발 전([AutoPhase.CONFIRMED])의
 *     [이번 이동 끝내기]와 같은 길(MainActivity.leaveConfirmedToHome: 확인을 접고 홈, FGS 종료)이고 스티커는 주지 않는다.
 *     정리는 유효 P + 플랫폼 허용(잠금 사유 없음)이고 배정 뒷자리가 모두 '비어 있음' 신호일 때만 한다([rearAllEmpty]).
 *     앉음·확인 불가가 하나라도 있으면 계속 기다린다(아이가 앉은 채 음성 없이 홈으로 접히지 않게, PM r10 수정 1).
 *  ② 출발한 이동이 유효 P로 선 뒤(TripGuideState.parkedSinceMs, 주행·잠금이면 비워짐) [EXIT_WAIT_MS] 동안 하차 마무리가 없고
 *     배정 뒷자리가 모두 '비어 있음' 신호면([rearAllEmpty]) 서비스와 이동 알림만 끝낸다. 이동 상태·화면은 그대로([다 내렸어요] 가능).
 *     도착 질문 대답으로 두고 내림을 멈췄거나 횟수를 다 쓴 것만으로는 끄지 않는다. 확인 불가는 앉음으로 본다(PM r10 수정 2).
 *     같은 정차에서는 다시 켜지 않는다([capped]). 다시 주행하면 풀리고, 앱이 안 보여도 서비스 시작을 한 번 시도한다([restartOnDrive]).
 * 스위치: [DEPART_WAIT_ENABLED]·[EXIT_WAIT_ENABLED](false = r10 전과 같음).
 */
object TripCapRule {
    /** ① 확인 완료 뒤 출발 없음 정리 스위치 */
    const val DEPART_WAIT_ENABLED = true
    /** ② 하차 안내 뒤 유효 P 상한 스위치 */
    const val EXIT_WAIT_ENABLED = true
    /** ① 확인 완료 뒤 출발을 기다리는 시간 */
    const val DEPART_WAIT_MS = 10 * 60_000L
    /** ② 정차(유효 P) 뒤 서비스를 유지하는 시간 */
    const val EXIT_WAIT_MS = 30 * 60_000L
    /** 이동 중 다시 보는 간격(정차 시작·주행 재개는 기어 쪽에서 바뀌므로 주기적으로 본다) */
    const val TICK_MS = 60_000L
    val DEFAULT = TripCapTiming()

    enum class Action { NONE, END_UNDEPARTED, STOP_KEEPALIVE }

    /** ① 대상: 확인 완료 && 아직 출발 안 함 && 마무리 전 */
    fun waitingDepart(tg: TripGuideState): Boolean = tg.confirmed && !tg.departed && !tg.allLeftSaid

    /** ② 대상: 출발한 이동이 지금 유효 P로 서 있음 && 마무리 전 */
    fun waitingExit(tg: TripGuideState): Boolean =
        tg.confirmed && tg.departed && !tg.drivingNow && !tg.allLeftSaid && tg.parkedSinceMs >= 0

    /** ① 대기 시작 시각: 대상이 아니면 -1, 이미 재고 있으면 그대로, 처음이면 지금 */
    fun departMark(prevMs: Long, tg: TripGuideState, nowMs: Long): Long = when {
        !waitingDepart(tg) -> -1L
        prevMs >= 0 -> prevMs
        else -> nowMs
    }

    /** ①② 대상 자리: 확인 때 배정한 자리 + 확인 때 착석이던 자리 가운데 뒷줄 */
    fun rearTargets(tg: TripGuideState): Set<String> =
        (tg.assigned.keys + tg.occupiedAtConfirm).filter { LeftBehindRule.isRear(it) }.toSet()

    /**
     * ①② 정리·종료 조건: 대상 뒷자리가 모두 '비어 있음'(EMPTY) 신호인가. 앉음·확인 불가·값 없음이 하나라도 있으면 false(확인 불가 = 앉음으로 봄).
     * 대상 자리가 없으면 확인 때 착석 확인 불가가 없었을 때만 true
     */
    fun rearAllEmpty(tg: TripGuideState, occ: Map<String, SeatOcc>): Boolean {
        val seats = rearTargets(tg)
        if (seats.isEmpty()) return !tg.occUnknownAtConfirm
        return seats.all { occ[it] == SeatOcc.EMPTY }
    }

    /** ② 상한 뒤 주행(D)을 받아 상한이 풀렸고 서비스가 꺼져 있으면, 앱이 안 보여도 서비스 시작을 시도한다(실패하면 보일 때 다시) */
    fun restartOnDrive(wasCapped: Boolean, cappedNow: Boolean, tg: TripGuideState, run: KeepAliveRun): Boolean =
        wasCapped && !cappedNow && tg.drivingNow && TripKeepAliveRule.tripActive(tg) && run == KeepAliveRun.OFF

    /** ② 이번 정차에서 이미 상한으로 끝냈는가(같은 parkedSinceMs). 이동이 끝났거나 새 정차면 false */
    fun capped(cappedStopMs: Long, tg: TripGuideState): Boolean =
        cappedStopMs >= 0 && TripKeepAliveRule.tripActive(tg) && tg.parkedSinceMs == cappedStopMs

    /** [rearEmpty] = [rearAllEmpty] 결과(배정 뒷자리가 모두 '비어 있음' 신호) */
    fun decide(tg: TripGuideState, departSinceMs: Long, parkOk: Boolean, rearEmpty: Boolean, capped: Boolean, nowMs: Long,
               t: TripCapTiming = DEFAULT, departEnabled: Boolean = DEPART_WAIT_ENABLED, exitEnabled: Boolean = EXIT_WAIT_ENABLED): Action = when {
        departEnabled && parkOk && rearEmpty && waitingDepart(tg) && departSinceMs >= 0 && nowMs - departSinceMs >= t.departWaitMs -> Action.END_UNDEPARTED
        exitEnabled && parkOk && rearEmpty && !capped && waitingExit(tg) && nowMs - tg.parkedSinceMs >= t.exitWaitMs -> Action.STOP_KEEPALIVE
        else -> Action.NONE
    }

    /**
     * 다음 점검까지의 시간(ms). 볼 것이 없으면 null(타이머 취소: 이동 아님·마무리·스위치 꺼짐).
     * 기한 전이면 기한(최대 [TripCapTiming.tickMs]), 기한이 지났는데 조건이 안 맞아 남았으면 tick 뒤(바쁜 반복 없음)
     */
    fun nextCheckMs(tg: TripGuideState, departSinceMs: Long, capped: Boolean, nowMs: Long, t: TripCapTiming = DEFAULT,
                    departEnabled: Boolean = DEPART_WAIT_ENABLED, exitEnabled: Boolean = EXIT_WAIT_ENABLED): Long? {
        val due = when {
            departEnabled && waitingDepart(tg) && departSinceMs >= 0 -> departSinceMs + t.departWaitMs
            exitEnabled && TripKeepAliveRule.tripActive(tg) && tg.departed ->
                if (waitingExit(tg) && !capped) tg.parkedSinceMs + t.exitWaitMs else nowMs + t.tickMs   // 주행 중·상한 뒤: 새 정차를 tick으로 본다
            else -> return null
        }
        val d = due - nowMs
        return if (d <= 0) t.tickMs else minOf(d, t.tickMs)
    }
}

// 2026-11-13 B: 확인 완료 음성이 끝나면 내비 앱을 앞으로 띄운다(앱은 뒤에서 음성만). 순수 판단.
enum class HandoffDecision { LAUNCH, WAIT, SKIP }

object NaviHandoffRule {
    /** 에뮬레이터 Pleos 내비(adb 확인 2026-11-13: ai.umos.maps.android.navigation.app/.MainActivity, LAUNCHER·APP_MAPS) */
    const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
    /** 음성을 내지 못하는 경우(음성 꺼짐·엔진 미준비·콜백 없음) 확인 화면을 보여 주고 넘기기까지 기다리는 시간 */
    const val NO_SPEECH_DELAY_MS = 3_000L
    /** 완료 음성 콜백이 끝내 오지 않을 때의 안전망(이 시간 뒤 말하는 중이 아니면 넘긴다) */
    const val FALLBACK_MS = 15_000L

    /**
     * armed: 이번 확인 완료에서 넘기기를 예약했는가(이동당 1회). tripActive: 아직 이동 중(취소·끝내기 아님).
     * speaking: 완료 음성(또는 이어지는 자동 안내)이 아직 나가는 중. appVisible: 앱이 화면에 보임(아니면 다른 앱이 이미 앞이다).
     * appMock: debug 모의 기어·시연 시나리오 중(앱 안의 개발자 패널로 주행을 흉내 내므로 앱을 가리지 않는다).
     * 2026-11-14 PM 결정 24 ①: completeVoicePending = 확인 완료 음성(+예정 시간)을 아직 말하지 않았거나 말하는 중(완료 콜백 전).
     *   예전에는 앞 자동 안내가 끝난 순간(완료 음성이 앞 안내를 기다리는 틈)에 '말하는 중 아님'으로 보고 넘겨, 완료 음성이 내비 전환 직후
     *   화면 이탈로 0.2초 만에 끊겼다(검수 09:32:49). 완료 콜백(끝·오류·중지 무엇이든)이 올 때까지 기다린다.
     *   fallbackElapsed = 안전망 시간([FALLBACK_MS])이 지남: 완료 콜백이 끝내 오지 않아도 지금 말하는 중이 아니면 넘긴다(안전망 유지).
     */
    fun decide(armed: Boolean, tripActive: Boolean, speaking: Boolean, appVisible: Boolean, appMock: Boolean = false,
               completeVoicePending: Boolean = false, fallbackElapsed: Boolean = false): HandoffDecision = when {
        !armed || !tripActive || !appVisible || appMock -> HandoffDecision.SKIP
        speaking -> HandoffDecision.WAIT
        completeVoicePending && !fallbackElapsed -> HandoffDecision.WAIT
        else -> HandoffDecision.LAUNCH
    }
}

// 알림 권한(POST_NOTIFICATIONS, Android 13+) 요청 판단. 거부해도 서비스는 돈다(알림만 보이지 않음).
object NotifPermissionRule {
    /**
     * 요청 조건: Android 13 이상 && 아직 허용 안 됨 && 이번 설치에서 아직 묻지 않음 && 화면이 보이는 홈(흐름 시작 전)이고
     * 유효 P·정차 허용. 확인 흐름 중에 묻지 않는 이유: 권한 대화상자가 화면을 가리면 onPause → 흐름이 '화면 이탈'로 취소된다.
     */
    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean, parkAllowed: Boolean, homeIdle: Boolean, inFlight: Boolean): Boolean =
        sdk >= 33 && !granted && !askedBefore && parkAllowed && homeIdle && !inFlight
}
