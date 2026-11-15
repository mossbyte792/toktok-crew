package com.crewguide.probe

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 2026-11-15 루프 r7 5절(임시 결정 50): 자동 열기 모드에서 이동 알림 서비스(TripKeepAliveService)가 갖는 감시자. 판단은 [AutoOpenRule.step](순수).
 * 서비스 안에서 돌아 수신기 60초 상한이 없다. 메인 스레드 전용.
 *
 * 읽는 것(읽기만, 차량 제어 없음): 기어(Vehicle GET, [AutoOpenRule.TICK_MS]마다), 플랫폼 주행 제한(PlatformUxSource),
 * 내비 경로 상태 naviMode(NaviTripEnd.requestRouteState, 목적지·좌표는 읽지 않음), 빠른 시작 기록(QuickStartStore, 매번 다시 읽음).
 * 띄우기: 1회차 AppFrontLauncher PENDING_INTENT(BAL 허용 옵션), 2회차 ACTIVITY(서비스 Context에서 startActivity). 실패면 [onFallback].
 * [release]에서 리스너·연결을 모두 놓는다.
 */
class AutoOpenWatcher(
    private val ctx: Context,
    /** 자동 열기를 끝낼 때(이유, 대체 알림을 남길지 — AutoOpenRule.keepNoticeOnStop) */
    private val onStop: (String, Boolean) -> Unit,
    /** 띄우기 시도를 다 써도 앱이 안 보일 때 1회(알림 제목을 대체 문구로) */
    private val onFallback: () -> Unit,
) {
    private val app = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private val startedAt = SystemClock.elapsedRealtime()

    private var vehicle: Vehicle? = null
    private var gearName: String? = null
    private var gearOk = false
    private var drivingSeen = false
    private var platform: PlatformUxSource? = null
    private var ux = PlatformUx.UNKNOWN
    private var navi: NaviTripEnd? = null
    private var routeGuiding = false
    private var launches = 0
    private var lastLaunchAt = 0L
    private var fallbackShown = false
    private var lastLogKey = ""

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            evaluate()
            if (!released) handler.postDelayed(this, AutoOpenRule.TICK_MS)
        }
    }

    fun start() {
        log("[자동열기] 시작 부팅후=${SystemClock.elapsedRealtime() / 1000}초")
        vehicle = try { Vehicle(app).also { it.initialize() } } catch (t: Throwable) {
            log("[자동열기] Vehicle 초기화 예외 ${t.javaClass.simpleName} → 기어 확인 불가(띄우지 않음)"); null
        }
        platform = PlatformUxSource(app, handler) { v, why -> if (!released) { if (v != ux) log("[자동열기] 플랫폼 제한 $v ($why)"); ux = v } }.also { it.start() }
        navi = NaviTripEnd(app, handler, { log(it) }, onEvent = {}, onLink = { l, _ -> if (l == NaviLink.READY) requestRoute() }).also {
            it.onRouteState = { guiding, note ->
                if (!released && guiding && !routeGuiding) log("[자동열기] 내비 길 안내 중 확인($note) 부팅후=${SystemClock.elapsedRealtime() / 1000}초")
                if (guiding) routeGuiding = true   // 한 번 보이면 유지(내비 창은 이미 끝났다)
            }
            it.start()
        }
        handler.postDelayed(tick, AutoOpenRule.VEHICLE_SETTLE_MS)
    }

    private fun requestRoute() { if (!released && !routeGuiding) navi?.requestRouteState() }

    // PM 통합(r7): MainActivity의 private 확장(gearApi)은 이 파일에서 보이지 않아 같은 리플렉션 getter를 여기 둔다(읽기만, set 없음)
    private val Vehicle.gearApi: ai.pleos.playground.vehicle.api.Gear
        get() {
            @Suppress("UNCHECKED_CAST")
            return this.javaClass.getMethod("getGear").invoke(this) as ai.pleos.playground.vehicle.api.Gear
        }

    private fun readGear() {
        val v = vehicle ?: return
        try {
            v.gearApi.getCurrentGearSelectionState(
                { g: VehicleGear? -> handler.post { if (!released) onGear(g?.name, g != null) } },
                { _: Exception? -> handler.post { if (!released) onGear(null, false) } })
        } catch (t: Throwable) { onGear(null, false) }
    }

    private fun onGear(name: String?, ok: Boolean) {
        if (name != gearName || ok != gearOk) log("[자동열기] 기어 ${name ?: "불명"}${if (ok) "" else "(읽기 실패)"}")
        gearName = name; gearOk = ok
        if (AutoOpenRule.driving(name, ok)) drivingSeen = true
    }

    private fun evaluate() {
        readGear()
        requestRoute()
        val now = SystemClock.elapsedRealtime()
        val quickDue = quickDue()
        val visible = AppVisibleStore.visible
        val runMs = now - startedAt
        val gate = AutoOpenRule.gateFor(gearName, gearOk, ux)
        val step = AutoOpenRule.step(quickDue, visible, runMs, drivingSeen, launches, now - lastLaunchAt, routeGuiding, now, gate)
        val key = "$step/${gate.name}/$routeGuiding"
        if (key != lastLogKey) { lastLogKey = key; log("[자동열기] 판단=$step 게이트=${gate.name} 길안내=$routeGuiding 부팅후=${now / 1000}초 시도=$launches") }
        when (step) {
            AutoOpenRule.Step.WAIT -> {}
            AutoOpenRule.Step.STOP -> {
                val why = AutoOpenRule.stopReason(quickDue, visible, runMs, drivingSeen)
                log("[자동열기] 끝냄: $why")
                onStop(why, AutoOpenRule.keepNoticeOnStop(quickDue, visible, drivingSeen))
            }
            AutoOpenRule.Step.FALLBACK -> if (!fallbackShown) {
                fallbackShown = true
                log("[자동열기] 띄우기 ${launches}회 뒤에도 앱이 안 보임 → 대체 알림(눌러서 시작)")
                onFallback()
            }
            AutoOpenRule.Step.LAUNCH -> {
                launches++
                lastLaunchAt = now
                val method = if (launches == 1) AppFrontLauncher.Method.PENDING_INTENT else AppFrontLauncher.Method.ACTIVITY
                val ok = AppFrontLauncher.launch(ctx, method, "자동 열기 ${launches}회")
                log("[자동열기] 띄우기 ${launches}회(${method.name}) ${if (ok) "호출함" else "실패"} 부팅후=${now / 1000}초 — 앞으로 왔는지는 [수명] onStart·BAL 로그")
            }
        }
    }

    /** 빠른 시작 조건을 지금 다시 본다(앱이 열려 기록을 썼거나 60분이 지나면 false) */
    private fun quickDue(): Boolean {
        val rec = QuickStartStore.load(app)
        val (trip, readFailed) = TripSessionRule.restore(StickerLogStore.loadTripOrNull(app))
        val allLeft = if (readFailed) -1 else StickerLogStore.loadAllLeftTrip(app)
        return QuickStartRule.decide(rec, System.currentTimeMillis(), trip.tripKey, allLeft, readFailed) == QuickStartDecision.QUICK_START
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(tick)
        runCatching { navi?.release() }; navi = null
        runCatching { platform?.release() }; platform = null
        runCatching { vehicle?.release() }; vehicle = null
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    companion object { private const val TAG = "G1PROBE" }
}
