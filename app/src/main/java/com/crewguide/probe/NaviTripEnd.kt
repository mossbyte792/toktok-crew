package com.crewguide.probe

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler

/**
 * NaviHelper 도착·경로 종료 이벤트 수신(가안). 이벤트 종류만 전달하고 객체 내용(위치·주소·시간 등)은
 * 읽지도 저장하지도 보내지도 않는다. 초기화 실패·예외는 '확인 불가'로만 표시되며 다른 기능에 영향을 주지 않는다.
 * 모든 콜백은 ui(메인 스레드)로 넘겨 onEvent/onLink를 호출한다.
 *
 * 2026-11-13 시간 값 확장([onTime]): 경로 시작·남은 시간의 **숫자만** 읽는다. 목적지 이름·주소·좌표·POI 필드는 읽지도,
 * 로그(toString 포함)·저장·전송하지도 않는다. NaviHelper 3.0.5 시그니처(javap 확인):
 *   RouteStartInfo.getDuration(): int, DestinationInfo.getRemainTime(): int, DrivingInfo.getDestination(): DrivingState(getDuration(): int),
 *   NaviHelper.getDestinationInfo(): void(결과는 onDestinationInfo 콜백).
 * 가정(문서 미확인): 시간 단위는 초, DrivingState.duration은 목적지까지 남은 시간. 에뮬레이터에서 이 이벤트가 오는지는 미검증.
 */
class NaviTripEnd(
    private val appContext: Context,
    private val ui: Handler,
    private val log: (String) -> Unit,
    private val onEvent: (NaviEvent) -> Unit,
    private val onLink: (NaviLink, String?) -> Unit,
    /** 시간 값(초, 가정). 경로 시작·남은 시간·경로 취소. 기본 없음 */
    private val onTime: ((NaviTime) -> Unit)? = null,
) {
    /** 결정 41: getRouteStateInfo 결과(경로 안내 중인가). 메인 스레드에서 부른다 */
    var onRouteState: ((Boolean, String) -> Unit)? = null
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var listenerAdded = false
    private var started = false      // 중복 초기화 방지(메인 스레드에서만 접근)
    private var released = false

    /** 메인 스레드에서 한 번만 호출한다. 두 번째 호출부터는 아무것도 하지 않는다 */
    fun start() {
        if (started || released) return
        started = true
        try {
            val h = NaviHelper(appContext)
            helper = h
            val l = object : NaviHelperEventListener {
                override fun onDestinationArrived(info: DestinationArrivedInfo) { post(NaviEvent.DESTINATION_ARRIVED, "[NAVI] 도착 신호") }
                override fun onRouteEnded() { post(NaviEvent.ROUTE_ENDED, "[NAVI] 경로 종료 신호") }
                override fun onNavigationStatus(status: NavigationStatus) { post(NaviEvent.NAVIGATION_STATUS, "[NAVI] 상태 이벤트") }
                override fun onError(code: NaviErrorCode) { post(NaviEvent.ERROR, "[NAVI] 오류 이벤트") }
                // 시간 값: 숫자 필드만 읽는다(이름·주소·좌표 필드 접근 금지)
                override fun onRouteStarted(info: RouteStartInfo) { val d = info.duration; postTime(NaviTime(NaviTimeKind.ROUTE_STARTED, d)) }
                override fun onDestinationInfo(info: DestinationInfo) { val r = info.remainTime; postTime(NaviTime(NaviTimeKind.REMAIN, r)) }
                override fun onDrivingInfo(info: DrivingInfo) {
                    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
                    val r = info.destination?.duration ?: return
                    postTime(NaviTime(NaviTimeKind.REMAIN, r))
                }
                override fun onRouteCancelled() { postTime(NaviTime(NaviTimeKind.CLEARED, null)) }
                // 결정 41: 경로 안내 중인지는 naviMode로 본다(ROUTE_GUIDING·SIMULATION_GUIDING). isRouting은 에뮬에서 안내 중에도 false였다
                // (2026-11-14 23:56 확인: 내비 화면 '안내 종료' 버튼이 보이는 안내 중에 isRouting=false) → 로그로만 남긴다. 목적지·좌표는 읽지 않는다
                override fun onRouteStateInfo(info: RouteStateInfo) {
                    val mode = info.naviMode
                    val guiding = mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING
                    val note = "naviMode=${mode?.name} isRouting=${info.isRouting}"
                    ui.post { if (!released) onRouteState?.invoke(guiding, note) }
                }
            }
            listener = l
            h.initialize(
                {
                    ui.post {
                        if (released) return@post
                        try {
                            h.addListener(l); listenerAdded = true
                            log("[NAVI] 초기화 성공, 리스너 등록")
                            onLink(NaviLink.READY, null)
                        } catch (t: Throwable) {
                            fail(t)
                        }
                    }
                },
                { t: Throwable -> ui.post { if (!released) fail(t) } },
            )
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun post(e: NaviEvent, line: String) {
        ui.post {
            if (released) return@post
            log(line)   // 종류만 기록
            onEvent(e)
        }
    }

    private fun postTime(t: NaviTime) {
        val cb = onTime ?: return
        ui.post {
            if (released) return@post
            cb(t)
        }
    }

    // ---- 주행 중 남은 시간 조회(30초 주기). 호출자(MainActivity)가 주행 여부로 켜고 끈다. 메인 스레드 전용 ----
    private var polling = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (released || !polling) return
            requestDestinationInfo()
            ui.postDelayed(this, NAVI_POLL_MS)
        }
    }

    /** 주행 중에만 켠다. 같은 값이면 아무것도 하지 않는다 */
    fun setPolling(on: Boolean) {
        if (released || on == polling) return
        polling = on
        ui.removeCallbacks(pollRunnable)
        if (on) ui.post(pollRunnable)
    }

    /** 목적지 정보 1회 요청(결과는 onDestinationInfo 콜백). 미연결·예외는 무시(확인 불가로 남음) */
    fun requestDestinationInfo() {
        val h = helper ?: return
        if (!listenerAdded) return
        try { h.getDestinationInfo() } catch (t: Throwable) { log("[NAVI] 남은 시간 요청 실패 원인=${t.javaClass.simpleName}") }
    }

    /**
     * 2026-11-14 루프 r5 **결정 41(사용자 확정(11-15))**: 지금 내비가 경로 안내 중인지 1회 조회(공식 API NaviHelper.getRouteStateInfo,
     * 결과는 onRouteStateInfo → [onRouteState], 판단 값은 RouteStateInfo.naviMode). SDK가 pleos.car.permission.NAVI_ROUTE를 확인한다(javap manager.c.s()).
     * 보냈으면 true, 미연결·예외면 false(호출자가 시간 값 갱신 시각으로 대신 판단). 경로 정보 중 isRouting만 읽는다(목적지·좌표 접근 없음).
     * (결정 39 취소(사용자, 11-15): 도착 뒤 cancelRoute로 안내를 끝내던 코드는 뺐다. 내비는 건드리지 않는다)
     */
    fun requestRouteState(): Boolean {
        val h = helper ?: return false
        if (!listenerAdded || released) return false
        return try { h.getRouteStateInfo(); true } catch (t: Throwable) { log("[NAVI] 경로 상태 요청 실패 원인=${t.javaClass.simpleName}"); false }
    }

    private fun fail(t: Throwable) {
        val name = t.javaClass.simpleName
        log("[NAVI] 초기화 실패 원인=$name")
        onLink(NaviLink.FAILED, name)
    }

    /** onDestroy에서 호출. removeListener → release 순서, 각각 예외는 무시한다 */
    fun release() {
        if (released) return
        released = true
        polling = false
        ui.removeCallbacks(pollRunnable)
        val h = helper
        val l = listener
        if (h != null && l != null && listenerAdded) runCatching { h.removeListener(l) }
        listenerAdded = false
        if (h != null) runCatching { h.release() }
        helper = null; listener = null
    }
}

enum class NaviTimeKind { ROUTE_STARTED, REMAIN, CLEARED }

/** 내비 시간 값(초, 가정). 숫자만 담는다 */
data class NaviTime(val kind: NaviTimeKind, val seconds: Int?)

/**
 * PM 결정 12(ISSUE-05, 2026-11-13): NaviHelper 시간 값이 0 이하이면 '값 없음'(경로 없음·알 수 없음)이지 '0초 남음'이 아니다.
 * 근거: 경로 없는 내비가 getDestinationInfo()에 remainTime 0을 돌려줘 출발 직후 "1분 후에 도착해요"가 나갔다
 * (demo/20261113-2020_312b15a/logcat_app.txt 1444·1450·1471행).
 * 실제 NaviHelper(onRouteStarted·onDestinationInfo·onDrivingInfo)와 debug 모의가 같은 입력 경로(MainActivity.onNaviTime)에서
 * 이 함수를 거친다. 값 없음은 임계값 통과로 치지 않고, 표시·발화 분을 바꾸지 않으며, 5분·1분 안내를 소진하지 않는다.
 * 도착은 onDestinationArrived(또는 모의 도착)로만 판단한다. 경로 시작 총 시간 0 이하는 기존 '시간 없는 출발 안내'로 간다.
 */
object NaviTimeRule {
    /** 쓸 수 있는 초 값. null·0·음수는 null(값 없음) */
    fun usable(sec: Int?): Int? = sec?.takeIf { it > 0 }

    /** 경로 시작·남은 시간의 0 이하 값을 null(값 없음)로 바꾼다. 경로 취소(CLEARED)는 그대로 */
    fun sanitize(t: NaviTime): NaviTime =
        if (t.kind == NaviTimeKind.CLEARED || t.seconds == null) t else t.copy(seconds = usable(t.seconds))

    /** 값 없음으로 무시했으면 로그 한 줄, 아니면 null */
    fun ignoredLog(t: NaviTime): String? {
        val sec = t.seconds ?: return null
        if (t.kind == NaviTimeKind.CLEARED || sec > 0) return null
        return when (t.kind) {
            NaviTimeKind.REMAIN -> "[내비] 남은 시간 $sec → 값 없음으로 무시"
            else -> "[내비] 경로 시작 총 시간 $sec → 값 없음으로 무시(시간 문구 없는 출발 안내)"
        }
    }
}

/** 주행 중 getDestinationInfo() 조회 주기(ms) */
const val NAVI_POLL_MS = 30_000L
