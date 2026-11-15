package com.crewguide.probe

import android.content.Context

// 2026-11-15 루프 r6 6절(가) 다시 탔을 때 빠른 시작(사용자 지시: "다시 시동을 걸면 내비는 이어 가기로 돼 있을 테니, 좌석 설정부터 다시 시작.
// 앞의 '톡톡크루예요' 같은 건 생략. 60분 안에 다시 켜면 좌석 설정부터").
//
// 조건: 지난 이동이 다음 중 하나로 끝났고, 그때부터 [QuickStartRule.WINDOW_MS](60분) 안에 앱 프로세스가 새로 시작됐다.
//  - 중간 하차: "내릴까요?"에 예(r6 5절, 개발 A) → 그 자리에서 하차 마무리(ALL_LEFT)까지 감 → [QuickEndKind.ALIGHTED]
//  - 끝내지 못한 채 종료: 확인 완료 뒤 이동 중이었는데 하차 마무리·[이번 이동 끝내기] 없이 프로세스가 사라짐 → [QuickEndKind.ACTIVE]가 남음
// 정상 하차 마무리(도착 뒤 ALL_LEFT)·[이번 이동 끝내기]·확인 완료 뒤 홈으로 취소는 기록을 지운다 → 빠른 시작 안 함.
// 동작: 홈·홈 인사를 건너뛰고, 유효 P + 플랫폼 허용이 되면 바로 좌석 확인(새 이동)을 연다. 탑승 환영 대신 [QuickStartRule.RESTART_LINE].
//
// 저장하는 것(이 기기 앱 저장소, 기기 밖으로 보내지 않음): 기록 종류(이동 중 / 중간 하차), 이동 번호(앱 내부 순번), 시각(벽시계 ms) 하나.
// 앱이 갑자기 꺼지면 '끝난 시각'을 남길 수 없어서, 이동 중에는 [QuickStartRule.HEARTBEAT_MS]마다 시각을 새로 쓴다(마지막으로 살아 있던 시각).
// 아이 이름·목적지·주소·좌표·좌석 정보는 저장하지 않는다.
// 판단은 순수 함수(Android 없음, 단위 테스트), 저장 입출력은 [QuickStartStore], 프로세스 상태는 [QuickStartSession].

/** 저장된 기록의 종류 */
enum class QuickEndKind {
    /** 확인 완료 뒤 이동 중(하차 마무리 전). 앱이 갑자기 꺼지면 이 값이 남는다. 시각 = 마지막으로 살아 있던 때 */
    ACTIVE,
    /** 중간 하차('내릴까요?' 예)로 하차 마무리까지 간 이동. 시각 = 하차 마무리 때 */
    ALIGHTED,
}

data class QuickStartRecord(val kind: QuickEndKind, val tripKey: Int, val atWallMs: Long)

/** 앱 시작 때(프로세스당 1회) 판단 */
enum class QuickStartDecision { HOME, QUICK_START }

/** 빠른 시작을 기다리는 동안 게이트가 바뀔 때마다의 판단 */
enum class QuickGateDecision { WAIT, START, DROP, TIMEOUT }

/** 이동 상태가 바뀔 때 저장소에 할 일 */
enum class QuickMarkAction { WRITE_ACTIVE, WRITE_ALIGHTED, CLEAR, NONE }

object QuickStartRule {
    /** 빠른 시작 끄는 스위치(false면 기록은 남겨도 앱 시작은 늘 홈) */
    const val ENABLED = true
    /** 다시 켰을 때 빠른 시작을 하는 시간(사용자 지시 60분). 한 곳에서 바꾼다 */
    const val WINDOW_MS = 60 * 60_000L
    /** 이동 중 '살아 있음' 시각 갱신 간격. 갑자기 꺼졌을 때 끝난 시각 오차가 이 정도(창이 짧아지는 쪽, 보수적) */
    const val HEARTBEAT_MS = 60_000L
    /** 임시 결정 43(PM, 사용자 확인 대기): 빠른 시작 때 탑승 환영 인사 대신 하는 한 줄 */
    const val RESTART_LINE = "다시 출발 전에 벨트를 확인할게요"

    /** 기록 시각부터 지금까지 창 안인가. 시계 역행(지금이 기록보다 앞)이면 false(기기 시계가 바뀐 것으로 보고 하지 않음) */
    fun withinWindow(rec: QuickStartRecord, nowWallMs: Long): Boolean =
        nowWallMs >= rec.atWallMs && nowWallMs - rec.atWallMs <= WINDOW_MS

    /**
     * 앱 프로세스 시작 때 1회. [tripKey]·[allLeftTrip]·[tripReadFailed]는 복원된 이동 세션(TripSessionStore) 값.
     *  - 기록 없음·스위치 꺼짐·이동 세션 읽기 실패 → HOME
     *  - 60분 지남·시계 역행 → HOME
     *  - ACTIVE: 기록한 이동이 지금 이동이고(경계를 넘기지 않음) 그 이동이 하차 마무리에 닿지 않았을 때만(지우기 실패 대비)
     *  - ALIGHTED: 기록한 이동이 지금 이동일 때만(그 뒤 [시작] 등으로 이동 번호가 넘어갔으면 이미 다른 일이 있었다)
     */
    fun decide(rec: QuickStartRecord?, nowWallMs: Long, tripKey: Int, allLeftTrip: Int, tripReadFailed: Boolean,
               enabled: Boolean = ENABLED): QuickStartDecision {
        if (!enabled || rec == null || tripReadFailed) return QuickStartDecision.HOME
        if (!withinWindow(rec, nowWallMs)) return QuickStartDecision.HOME
        val ok = when (rec.kind) {
            QuickEndKind.ACTIVE -> rec.tripKey == tripKey && allLeftTrip != tripKey
            QuickEndKind.ALIGHTED -> rec.tripKey == tripKey
        }
        return if (ok) QuickStartDecision.QUICK_START else QuickStartDecision.HOME
    }

    /**
     * 빠른 시작 대기 중 게이트 변화마다. 흐름이 홈이 아니면(다른 흐름이 이미 시작됨) 버린다. 창이 지나면 버린다.
     * 유효 P + 플랫폼 허용(정차 허용, 화면 앞)이 아니면 기다린다 — 잠금 화면(S09) 규칙이 우선이다.
     */
    fun onGate(rec: QuickStartRecord, nowWallMs: Long, flowAtHome: Boolean, parkAllowed: Boolean): QuickGateDecision = when {
        !flowAtHome -> QuickGateDecision.DROP
        !withinWindow(rec, nowWallMs) -> QuickGateDecision.DROP
        !parkAllowed -> QuickGateDecision.WAIT
        else -> QuickGateDecision.START
    }

    /**
     * 이동 상태가 바뀔 때(이동 서비스 판단과 같은 시점). [tripActive] = 확인 완료 && 하차 마무리 전(TripKeepAliveRule.tripActive),
     * [wasActive] = 이 프로세스에서 이동 중 표시를 써 둔 상태. 프로세스 시작 직후의 '이동 아님'으로는 지우지 않는다(지난 기록은 판단에 써야 함).
     * 이동 중 → 아님 전환: 하차 마무리(allLeftSaid)이고 중간 하차로 끝났으면 ALIGHTED, 그 밖(정상 하차 마무리·이동 끝내기·취소)은 지움.
     */
    fun markAction(tripActive: Boolean, wasActive: Boolean, allLeftSaid: Boolean, endedByAlight: Boolean): QuickMarkAction = when {
        tripActive -> if (wasActive) QuickMarkAction.NONE else QuickMarkAction.WRITE_ACTIVE
        !wasActive -> QuickMarkAction.NONE
        allLeftSaid && endedByAlight -> QuickMarkAction.WRITE_ALIGHTED
        else -> QuickMarkAction.CLEAR
    }

    /**
     * 계약 v6.1 `AutoCheckUi.quickStart`: 빠른 시작으로 연 확인(그 흐름 세대)이 아직 진행 중일 때만 true.
     * 확인 완료·[처음으로](홈)·이동 끝·새 확인(세대 바뀜)이면 false.
     */
    fun contractFlag(quickGen: Int, flowGen: Int, checking: Boolean, completed: Boolean): Boolean =
        quickGen >= 0 && quickGen == flowGen && checking && !completed

    // ---- 2026-11-15 루프 r7 3절(개발 B): 빠른 시작 대기 중 홈이 먼저 보이던 문제(클립 R6_quickstart 17~21초) ----
    /**
     * 임시 결정 49(PM, 사용자 확인 대기): 빠른 시작으로 판단한 뒤 유효 P + 플랫폼 허용을 기다리는 상한. 넘으면 홈으로 간다.
     * 홈으로 가도 홈 인사는 생략한 채로 둔다(다시 탄 상황은 그대로라서, 개발 B 판단 — [greetSkippedAfterTimeout]).
     */
    const val WAIT_MAX_MS = 15_000L
    /** 상한을 넘겨 홈으로 갈 때도 홈 인사를 생략할지(되돌리기: false면 홈 인사 1회) */
    const val GREET_SKIPPED_AFTER_TIMEOUT = true

    /** 대기 상한을 넘겼나([waitStartMs] = 대기 시작 elapsedRealtime, 없으면 -1) */
    fun waitTimedOut(waitStartMs: Long, nowMs: Long): Boolean = waitStartMs >= 0 && nowMs - waitStartMs >= WAIT_MAX_MS

    /**
     * 대기 중 게이트 변화·시간 점검마다: [onGate]에 상한을 더한 판단. 순서 = 다른 흐름 시작·60분 지남(DROP) → 상한(TIMEOUT) → 잠금·P 아님(WAIT) → START.
     * 상한 직전에 유효 P가 와도 상한이 먼저면 홈(보수적, 같은 순간이면 START가 아님).
     */
    fun onWait(rec: QuickStartRecord, nowWallMs: Long, waitStartMs: Long, nowMs: Long, flowAtHome: Boolean, parkAllowed: Boolean): QuickGateDecision {
        val g = onGate(rec, nowWallMs, flowAtHome, parkAllowed)
        if (g == QuickGateDecision.DROP) return g
        if (waitTimedOut(waitStartMs, nowMs)) return QuickGateDecision.TIMEOUT
        return g
    }

    /** 계약 v7 `quickStartWaiting`: 빠른 시작 대기 중이고 상한 전 */
    fun waiting(pending: Boolean, waitStartMs: Long, nowMs: Long): Boolean = pending && !waitTimedOut(waitStartMs, nowMs)

    /** 대기 중에는 홈(홈 말풍선 "안녕하세요, 톡톡크루 승무원이에요" 포함)을 그리지 않는다. 시작 유예의 '잠금 대신 홈'(StartupHomeRule)보다 앞선다 */
    fun homeAllowed(waiting: Boolean): Boolean = !waiting

    /** 상한으로 홈에 갔을 때 홈 인사를 다시 허용할지(HomeGreetStore.said를 되돌릴지) */
    fun restoreGreetOnTimeout(): Boolean = !GREET_SKIPPED_AFTER_TIMEOUT

    // ---- 2026-11-15 r10 G(개발 3): 빠른 시작 대기 중 '기어 확인 중' 좌석 화면(계약 v10 gearCheckPending) ----
    /** 되돌리기: false면 gearCheckPending은 늘 false(예전처럼 기어 첫 값 전에는 전체 잠금 카드) */
    const val GEAR_CHECK_SCREEN_ENABLED = true

    /**
     * 계약 v10 `gearCheckPending`. 빠른 시작 대기 화면([waiting] = 계약 quickStartWaiting)이고 잠금 중인데([locked]), 그 잠금이
     * '기어 값을 아직 모름' 때문일 때만 true:
     *  - [firstGearHolding]: 첫 기어 값을 0.5초 안정화하며 붙잡는 중(FirstGearHold)
     *  - [startupChecking]: 시작 유예 안에서 기어 값을 아직 한 번도 못 받음(lockDisplayKind == CHECKING)
     * D/R/N(P 아님)·기어 불명 이름·구독 실패·유예 지난 미수신·플랫폼 제한이면 false → 잠금(S09). 유효 P(잠금 없음)면 false.
     * 화면 복귀 재조회(resumeRequerying)는 넣지 않는다(복귀 전 값이 D였을 수 있어 잠금 카드 유지, StartupHomeRule과 같은 원칙).
     */
    fun gearCheckPending(waiting: Boolean, locked: Boolean, firstGearHolding: Boolean, startupChecking: Boolean,
                         enabled: Boolean = GEAR_CHECK_SCREEN_ENABLED): Boolean =
        enabled && waiting && locked && (firstGearHolding || startupChecking)

    // ---- 저장 형식 "q1,종류,이동번호,시각ms". 이름·목적지·위치·좌석 없음 ----
    const val VERSION = "q1"

    fun encode(r: QuickStartRecord): String = "$VERSION,${r.kind.name},${r.tripKey},${r.atWallMs}"

    /** 없음·형식 오류·음수 = null(기록 없음 → 빠른 시작 안 함, 보수적) */
    fun decode(text: String?): QuickStartRecord? {
        val f = text?.split(',') ?: return null
        if (f.size != 4 || f[0] != VERSION) return null
        val kind = QuickEndKind.values().firstOrNull { it.name == f[1] } ?: return null
        val key = f[2].toIntOrNull() ?: return null
        val at = f[3].toLongOrNull() ?: return null
        if (key < 0 || at <= 0L) return null
        return QuickStartRecord(kind, key, at)
    }
}

/**
 * 2026-11-15 r10 F(개발 3): 확인 완료 음성 직전 예정 시간 다시 읽기.
 * 증상: 빠른 시작이 내비 연결(NaviHelper 초기화는 첫 프레임 3초 뒤)보다 먼저 확인을 열면, 확인 시작 때의 남은 시간 요청이 나가지 못해
 * 확인 완료 음성에 "도착까지 약 N분 걸려요."가 붙지 않았다.
 * 동작: 확인 완료 순간 예정 시간 값이 없고 [shouldWait]이면, 남은 시간을 다시 요청하고 최대 [WAIT_MAX_MS] 동안 값이 오기를 기다린 뒤
 * 완료 음성을 만든다(값이 오면 바로). 그래도 없으면 예정 시간 없이 말한다. 기다리는 동안 완료 음성은 아직 요청 전이라
 * 내비 전환(결정 24 ①)·내비 음성 겹침(결정 42) 규칙은 그대로 적용된다. 시간 숫자만 다룬다(목적지 이름·좌표 없음).
 * 되돌리기: [ENABLED] = false(예전처럼 완료 순간 값으로만).
 */
object ConfirmEtaWaitRule {
    /** r10 F 스위치(임시 결정 후보) */
    const val ENABLED = true
    /** 완료 음성을 미루는 상한 */
    const val WAIT_MAX_MS = 1_000L
    /** 값이 왔는지 보는 간격 */
    const val STEP_MS = 100L

    /**
     * 기다릴지. 이미 예정 시간을 붙였거나([hasEta]) 이번 이동에 이미 말했으면([etaSaid]) 기다리지 않는다. 내비 초기화 실패(FAILED)면 기다려도 오지 않는다.
     * 빠른 시작으로 연 확인이면 내비가 연결돼 있어도 한 번 다시 읽고 기다린다(이어 가는 경로는 경로 시작 이벤트가 다시 오지 않음).
     * 일반 확인은 내비가 아직 연결 전([NaviLink.UNKNOWN])일 때만 기다린다(경로 없는 이동의 완료 음성이 늘 1초 늦어지지 않게).
     */
    fun shouldWait(hasEta: Boolean, etaSaid: Boolean, quickStart: Boolean, naviLink: NaviLink, enabled: Boolean = ENABLED): Boolean =
        enabled && !hasEta && !etaSaid && naviLink != NaviLink.FAILED && (quickStart || naviLink == NaviLink.UNKNOWN)

    /** 계속 기다릴지: 쓸 수 있는 시간 값이 아직 없고 상한 전 */
    fun keepWaiting(hasValue: Boolean, deadlineMs: Long, nowMs: Long): Boolean = !hasValue && nowMs < deadlineMs

    /** 예정 시간에 쓸 수 있는 값이 있는가(TripGuide.onConfirmEta와 같은 기준: 남은 시간 우선, 0 초과, 도착 신호 전) */
    fun hasValue(s: TripGuideState): Boolean = !s.arrivalSignal && ((s.remainSec ?: s.totalSec) ?: 0) > 0
}

/** 이 기기 앱 안에만 저장(SharedPreferences, 동기 commit — 갑자기 꺼져도 남게). 읽기 실패·깨진 값 = 기록 없음 */
object QuickStartStore {
    const val FILE_NAME = "crewguide_probe_quick_start"
    const val KEY = "trip_mark_q1"

    fun load(context: Context): QuickStartRecord? = try {
        QuickStartRule.decode(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY, null))
    } catch (e: Exception) { null }

    fun save(context: Context, rec: QuickStartRecord): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putString(KEY, QuickStartRule.encode(rec)).commit()
    } catch (e: Exception) { false }

    fun clear(context: Context): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    } catch (e: Exception) { false }
}

/** 프로세스 상태(메인 스레드 전용). Activity 재생성에도 유지되고 프로세스가 끝나면 사라진다 */
object QuickStartSession {
    /** 유효 P를 기다리는 빠른 시작(없으면 null) */
    var pending: QuickStartRecord? = null
    /** r7 3절: 대기를 시작한 시각(elapsedRealtime, 없으면 -1). 임시 결정 49 상한 계산 */
    var waitStartMs = -1L
    /** 빠른 시작으로 연 확인의 흐름 세대(계약 quickStart, 없으면 -1) */
    var quickGen = -1
    /** 이 프로세스에서 이동 중 표시(ACTIVE)를 써 둔 상태인가 */
    var markedActive = false
}
