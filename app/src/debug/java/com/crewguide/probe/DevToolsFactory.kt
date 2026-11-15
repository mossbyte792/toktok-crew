package com.crewguide.probe

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// debug 빌드 전용 모의 도구(2026-11-07 Unit 5.1 P0-1). MainActivity에 있던 모의 내비·모의 기어 패널 코드를 그대로 옮겼다.
// release 빌드에는 이 파일이 없고 src/release/DevToolsFactory.kt(아무것도 하지 않음)가 대신 들어간다.
private const val INJECT_BELT_ACTION = "com.crewguide.probe.DEBUG_BELT"
private const val TTS_BENCH_ACTION = "com.crewguide.probe.DEBUG_TTS_BENCH"
private const val STT_BENCH_ACTION = "com.crewguide.probe.DEBUG_STT_BENCH"
// 2026-11-13 시연 시나리오(debug 전용): adb shell am broadcast --user 10 -a com.crewguide.probe.SCENARIO_START
private const val SCENARIO_START_ACTION = "com.crewguide.probe.SCENARIO_START"
private const val SCENARIO_CANCEL_ACTION = "com.crewguide.probe.SCENARIO_CANCEL"
// 2026-11-13 ISSUE-07 실기 확인(debug 전용): 에뮬 내비는 위치가 움직이지 않아(geo fix·VC 속도 모두 내비 위치 불변 확인) 남은 시간 감소·도착을
// 실제로 만들 수 없다. 앱이 화면 뒤에 있을 때도 같은 모의 입력 경로(onMockRemain/onMockArrival, 출처 MOCK)로 넣는다.
// adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_NAVI --es event remain --ei sec 240
// adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_NAVI --es event arrive
private const val NAVI_INJECT_ACTION = "com.crewguide.probe.DEBUG_NAVI"
// 2026-11-14 debug 착석 주입(회색 칸·[보호자 확인] 실기 확인용). 실제 VC 착석과 같은 처리 경로(injectOccEvent, 출처 모의)
// adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_OCC --es seat ROW_1_RIGHT --es value true|false|unknown|clear
private const val OCC_INJECT_ACTION = "com.crewguide.probe.DEBUG_OCC"
// 2026-11-14(지시 6) 스티커 기록 초기화(시험·제출 영상 녹화 전). 아이 등록은 남긴다. debug 전용, 보낸 쪽 DUMP 권한 필요
// adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_RESET_STICKERS
private const val RESET_STICKERS_ACTION = "com.crewguide.probe.DEBUG_RESET_STICKERS"
// 2026-11-14 r5 3절 교통 상식 간격 단축(QA가 8~12분을 기다리지 않게). debug 전용, 보낸 쪽 DUMP 권한 필요
// adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_TIP_FAST --ez on true   (끄기: --ez on false)
private const val TIP_FAST_ACTION = "com.crewguide.probe.DEBUG_TIP_FAST"
/** 단축 값: 출발 뒤 10초, 다음 상식 20~30초 무작위, 다른 안내 뒤 조용 5초, 점검 2초(기본 90초·8~12분·45초·15초) */
private val TIP_FAST_TIMING = TipTiming(firstDelayMs = 10_000L, gapMinMs = 20_000L, gapMaxMs = 30_000L, quietMs = 5_000L, tickMs = 2_000L)
private const val FRONT_PROBE_ACTION = "com.crewguide.probe.DEBUG_FRONT"
// 2026-11-15 r10 H 이동 알림 상한 단축(QA가 10분·30분을 기다리지 않게). debug 전용, 보낸 쪽 DUMP 권한 필요
// adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_TRIP_CAP_FAST --ez on true   (끄기: --ez on false)
private const val TRIP_CAP_FAST_ACTION = "com.crewguide.probe.DEBUG_TRIP_CAP_FAST"
/** 단축 값: 확인 완료 뒤 출발 대기 60초, 정차 대기 90초, 점검 5초(기본 10분·30분·60초) */
private val TRIP_CAP_FAST_TIMING = TripCapTiming(departWaitMs = 60_000L, exitWaitMs = 90_000L, tickMs = 5_000L)

object DevToolsFactory {
    fun create(host: DevToolsHost): DevTools = DebugDevTools(host)
}

private class DebugDevTools(private val host: DevToolsHost) : DevTools {
    private val ui = Handler(Looper.getMainLooper())

    // ---- 앱 내부 모의 내비(가안, 시연용) ----
    private val mockNavi = MockNavi()
    private var mockLastTickMs = 0L
    private var mockLoggedBucket = -1
    private var mockStatusView: TextView? = null
    private val mockTick = object : Runnable {
        override fun run() {
            if (host.isDestroyed()) return
            val now = SystemClock.elapsedRealtime()
            val dt = now - mockLastTickMs
            mockLastTickMs = now
            if (mockNavi.tick(dt)) {
                arrived()
            } else if (mockNavi.state == MockNaviState.MOVING) {
                val bucket = mockNavi.remainingMeters / 100
                if (bucket != mockLoggedBucket) { mockLoggedBucket = bucket; host.devLog("[모의NAVI] 이동 중 남은 ${mockNavi.remainingMeters} m") }
                ui.postDelayed(this, MOCK_TICK_MS)
            }
            refreshNaviPanel()
            host.refreshTripViews()
        }
    }

    private fun scheduleTick() {
        ui.removeCallbacks(mockTick)
        mockLastTickMs = SystemClock.elapsedRealtime()
        ui.postDelayed(mockTick, MOCK_TICK_MS)
    }

    private fun arrived() {
        host.devLog("[모의NAVI] 도착")
        host.onMockArrival()
    }

    override fun startMockNavi() {
        if (host.isDestroyed() || !DevMockGuard.allowed || !mockNavi.start()) return
        mockLoggedBucket = mockNavi.remainingMeters / 100
        host.devLog("[모의NAVI] 출발 총 ${mockNavi.remainingMeters} m")
        scheduleTick()
        refreshNaviPanel(); host.refreshTripViews()
    }

    override fun forceArriveMockNavi() {
        if (host.isDestroyed() || !DevMockGuard.allowed || !mockNavi.forceArrive()) return
        ui.removeCallbacks(mockTick)
        arrived()
        refreshNaviPanel(); host.refreshTripViews()
    }

    private fun cancelMockNavi() {
        if (host.isDestroyed()) return
        val wasMoving = mockNavi.state == MockNaviState.MOVING
        ui.removeCallbacks(mockTick)
        mockNavi.cancel()
        if (wasMoving) host.devLog("[모의NAVI] 취소")
        refreshNaviPanel(); host.refreshTripViews()
    }

    private fun refreshNaviPanel() {
        mockStatusView?.text = MockText.panelStatus(mockNavi.state, mockNavi.remainingMeters)
    }

    // ---- 모의 기어 패널(가안, 개발용). 실제 반영은 앱(requestMockGear*)이 DevMockGuard로 다시 확인한다 ----
    private var gearStatusView: TextView? = null
    private val gearButtons = mutableListOf<Button>()

    override fun refreshGearPanel() {
        val v = gearStatusView ?: return
        for (b in gearButtons) b.isEnabled = host.mockGearOn()
        v.text = GearSource.panelStatus(host.mockGearOn(), host.currentGearName())
    }

    // ---- 2026-11-13 모의 경로 시간(MockTrip). 실제 내비 시간과 같은 입력 경로(host.onMockRouteStarted/onMockRemain/onMockArrival) ----
    private val mockTrip = MockTrip()
    private var tripLastTickMs = 0L
    private var tripStatusView: TextView? = null
    private val tripTick = object : Runnable {
        override fun run() {
            if (host.isDestroyed() || !mockTrip.active) return
            val now = SystemClock.elapsedRealtime()
            val dt = now - tripLastTickMs
            tripLastTickMs = now
            if (mockTrip.tick(dt)) {
                host.devLog("[모의NAVI] 모의 경로 도착(남은 시간 0)")
                host.onMockArrival()
            } else {
                host.onMockRemain(mockTrip.remainingSec)
                ui.postDelayed(this, MOCK_TICK_MS)
            }
            refreshTripPanel()
        }
    }

    private fun scheduleTripTick() {
        ui.removeCallbacks(tripTick)
        tripLastTickMs = SystemClock.elapsedRealtime()
        ui.postDelayed(tripTick, MOCK_TICK_MS)
    }

    override fun startMockTrip(totalSec: Int) {
        if (host.isDestroyed() || !DevMockGuard.allowed) return
        if (mockTrip.active) mockTrip.cancel()
        mockTrip.start(totalSec)
        host.devLog("[모의NAVI] 모의 경로 시작 총 ${mockTrip.totalSec}초(앱 내부 모의, 실제 내비 아님)")
        host.onMockRouteStarted(mockTrip.totalSec)
        scheduleTripTick()
        refreshTripPanel()
    }

    override fun fastForwardMockTrip() {
        if (host.isDestroyed() || !DevMockGuard.allowed || !mockTrip.active) return
        mockTrip.setScale(MockTrip.FAST_SCALE)
        host.devLog("[모의NAVI] 빨리 감기(1초 = 1분)")
        refreshTripPanel()
    }

    private fun refreshTripPanel() {
        tripStatusView?.text = when {
            mockTrip.active -> "모의 경로 · 남은 ${mockTrip.remainingSec}초 · 배속 ${mockTrip.timeScale}"
            mockTrip.arrived -> "모의 경로 도착(앱 내부 모의)"
            else -> "모의 경로 준비"
        }
    }

    // ---- 2026-11-13 모의 좌석(착석·벨트) 토글. 실제 이벤트 경로(onOccEvent/onBeltEvent)로 합류, 출처 모의 ----
    private val occMock = mutableMapOf<String, Boolean>()
    private val beltMock = mutableMapOf<String, Boolean?>()   // true 체결, false 미체결, null 값 없음
    private val seatButtons = mutableMapOf<String, Pair<Button, Button>>()
    private val seatKeys = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")

    private fun setOcc(seat: String, v: Boolean) { occMock[seat] = v; host.injectOccEvent(seat, v); refreshSeatPanel() }
    private fun setBelt(seat: String, v: Boolean?) {
        beltMock[seat] = v
        if (v == null) host.clearBeltDebug(seat) else host.injectBeltEvent(seat, v)
        refreshSeatPanel()
    }
    private fun refreshSeatPanel() {
        for ((seat, pair) in seatButtons) {
            pair.first.text = "${SeatCheckRule.label(seat)} 착석 " + when (occMock[seat]) { true -> "켬"; false -> "끔"; null -> "-" }
            pair.second.text = "벨트 " + when { !beltMock.containsKey(seat) -> "-"; beltMock[seat] == true -> "체결"; beltMock[seat] == false -> "미체결"; else -> "값 없음" }
        }
    }

    // ---- 2026-11-13 시연 시나리오(한 번 누르면 끝까지). 단계마다 logcat [SCENARIO] step=... ----
    private class ScStep(val name: String, val minWaitMs: Long, val timeoutMs: Long, val needIdle: Boolean,
                         val until: () -> Boolean, val action: () -> Unit)
    private var scSteps: List<ScStep>? = null
    private var scIndex = 0
    private var scActed = false
    private var scStartMs = 0L
    private var scenarioStatusView: TextView? = null
    private val scTick = Runnable { scenarioTick() }

    override fun scenarioRunning(): Boolean = scSteps != null

    override fun startScenario() {
        if (host.isDestroyed() || !DevMockGuard.allowed) return
        if (scSteps != null) { host.scenarioLog("[SCENARIO] 이미 실행 중 → 무시"); return }
        val seat = "ROW_2_LEFT"
        val gear = { host.currentGearName() }
        val phase = { host.scenarioPhase() }
        scSteps = listOf(
            ScStep("mock_gear_P", 1000, 10_000, false, { gear() == GearSource.MOCK_PARK }) {
                if (host.mockGearOn()) host.requestMockGearInput(GearSource.MOCK_PARK, "P") else host.requestMockGear(true, GearSource.MOCK_PARK)
            },
            ScStep("home_start", 800, 5_000, false, { true }) { host.scenarioHomeStart() },
            ScStep("pick_first_child", 800, 5_000, false, { true }) {
                if (!host.scenarioPickFirstChild()) host.scenarioLog("[SCENARIO] 등록된 아이 없음 → 등록 없이 진행")
            },
            ScStep("select_seat_$seat", 800, 5_000, false, { true }) { host.scenarioSelectOnly(seat) },
            ScStep("start_check", 1500, 10_000, false, { phase() == AutoPhase.CHECKING.name }) { host.scenarioStartCheck() },
            ScStep("mock_occupied", 1500, 10_000, true, { true }) { setOcc(seat, true) },
            ScStep("mock_unbuckled_reminder", 2500, 20_000, true, { true }) { setBelt(seat, false) },
            ScStep("mock_buckled_auto_confirm", 2500, 30_000, true, { phase() == AutoPhase.CONFIRMED.name }) { setBelt(seat, true) },
            ScStep("mock_route_start_8min", 800, 5_000, false, { true }) { startMockTrip(MockTrip.DEFAULT_TOTAL_SEC) },
            ScStep("mock_D_depart", 2000, 20_000, true,
                { phase() == AutoPhase.DEPARTING.name || phase() == AutoPhase.DRIVING.name }) { host.requestMockGearInput(GearSource.MOCK_DRIVE, "D") },
            ScStep("fast_forward_5min_1min_arrive", 2000, 40_000, true, { mockTrip.arrived }) { fastForwardMockTrip() },
            ScStep("mock_P_arrived", 2000, 20_000, true, { phase() == AutoPhase.ARRIVED.name || phase() == AutoPhase.ALL_LEFT.name }) {
                host.requestMockGearInput(GearSource.MOCK_PARK, "P")
            },
            ScStep("mock_leave_all_left", 2000, 20_000, true, { phase() == AutoPhase.ALL_LEFT.name }) { setOcc(seat, false) },
        )
        scIndex = 0; scActed = false
        host.scenarioLog("[SCENARIO] step=begin 모의 시연 시작(앱 내부 모의 신호, 실제 차량 아님)")
        ui.removeCallbacks(scTick); ui.post(scTick)
        refreshScenarioPanel()
    }

    override fun cancelScenario() {
        if (scSteps == null) return
        scSteps = null
        ui.removeCallbacks(scTick)
        host.scenarioLog("[SCENARIO] step=cancel 모의 시연 취소")
        refreshScenarioPanel()
    }

    private fun scenarioTick() {
        val steps = scSteps ?: return
        if (host.isDestroyed()) { scSteps = null; return }
        if (scIndex >= steps.size) {
            scSteps = null
            host.scenarioLog("[SCENARIO] step=end 모의 시연 끝(단계 ${steps.size}개)")
            refreshScenarioPanel()
            return
        }
        val st = steps[scIndex]
        val now = SystemClock.elapsedRealtime()
        if (!scActed) {
            host.scenarioLog("[SCENARIO] step=${st.name} (${scIndex + 1}/${steps.size})")
            scActed = true; scStartMs = now
            st.action()
            refreshScenarioPanel()
        } else {
            val elapsed = now - scStartMs
            val ok = elapsed >= st.minWaitMs && st.until() && (!st.needIdle || host.scenarioTtsIdle())
            if (ok || elapsed > st.timeoutMs) {
                if (!ok) host.scenarioLog("[SCENARIO] step=${st.name} 시간 초과(${st.timeoutMs}ms, 단계=${host.scenarioPhase()}) → 다음 단계")
                scIndex++; scActed = false
            }
        }
        ui.postDelayed(scTick, SCENARIO_TICK_MS)
    }

    private fun refreshScenarioPanel() {
        scenarioStatusView?.text = scSteps?.let { "모의 시연 실행 중 ${minOf(scIndex + 1, it.size)}/${it.size} · ${it.getOrNull(scIndex)?.name ?: "끝"}" } ?: "모의 시연 대기"
    }

    override fun attachPanels(devBox: LinearLayout) {
        if (!DevMockGuard.allowed) return
        val ctx = devBox.context
        attachAutoPanels(devBox)
        val minPx = maxOf(host.devMinTouchPx(), host.devPx(76))
        // 모의 내비
        devBox.addView(TextView(ctx).apply {
            text = MockText.PANEL_TITLE; textSize = host.devTitleSp(); setTextColor(host.devTextColor()); setPadding(0, host.devPx(8), 0, host.devPx(4))
        })
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val actions = listOf<Pair<String, () -> Unit>>(
            MockText.BTN_START to { startMockNavi() },
            MockText.BTN_ARRIVE to { forceArriveMockNavi() },
            MockText.BTN_CANCEL to { cancelMockNavi() },
        )
        for ((label, action) in actions) {
            val b = Button(ctx).apply { text = label; setOnClickListener { action() } }
            host.styleDevButton(b); b.minHeight = minPx; b.minimumHeight = minPx
            btnRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        devBox.addView(btnRow)
        mockStatusView = TextView(ctx).apply { textSize = host.devAuxSp(); setTextColor(host.devTextColor()); setPadding(0, host.devPx(4), 0, host.devPx(8)) }
        devBox.addView(mockStatusView)
        refreshNaviPanel()
        // 모의 기어
        devBox.addView(TextView(ctx).apply {
            text = GearSource.PANEL_TITLE; textSize = host.devTitleSp(); setTextColor(host.devTextColor()); setPadding(0, host.devPx(8), 0, host.devPx(4))
        })
        devBox.addView(android.widget.Switch(ctx).apply {
            text = GearSource.SWITCH_TEXT
            textSize = host.devTitleSp(); setTextColor(host.devTextColor()); minimumHeight = minPx
            isChecked = false
            setOnCheckedChangeListener { _, checked -> host.requestMockGear(checked, GearSource.MOCK_PARK) }
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, name, logLabel) in listOf(
            Triple(GearSource.BTN_PARK, GearSource.MOCK_PARK, "P"),
            Triple(GearSource.BTN_DRIVE, GearSource.MOCK_DRIVE, "D"),
        )) {
            val b = Button(ctx).apply { text = label; setOnClickListener { host.requestMockGearInput(name, logLabel) } }
            host.styleDevButton(b); b.minHeight = minPx; b.minimumHeight = minPx
            b.isEnabled = false   // 모의 기어가 꺼져 있으면 비활성
            gearButtons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        devBox.addView(row)
        gearStatusView = TextView(ctx).apply { textSize = host.devAuxSp(); setTextColor(host.devTextColor()); setPadding(0, host.devPx(4), 0, host.devPx(8)) }
        devBox.addView(gearStatusView)
        refreshGearPanel()
    }

    /** 2026-11-13: 시연 시나리오·모의 경로 시간·모의 좌석 패널(개발용 보기 위쪽) */
    private fun attachAutoPanels(devBox: LinearLayout) {
        val ctx = devBox.context
        val minPx = maxOf(host.devMinTouchPx(), host.devPx(76))
        fun title(t: String) = TextView(ctx).apply {
            text = t; textSize = host.devTitleSp(); setTextColor(host.devTextColor()); setPadding(0, host.devPx(8), 0, host.devPx(4))
        }
        fun status() = TextView(ctx).apply { textSize = host.devAuxSp(); setTextColor(host.devTextColor()); setPadding(0, host.devPx(4), 0, host.devPx(8)) }
        fun row(vararg items: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in items) {
                val b = Button(ctx).apply { text = label; setOnClickListener { action() } }
                host.styleDevButton(b); b.minHeight = minPx; b.minimumHeight = minPx
                r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            return r
        }
        devBox.addView(title("모의 시연(가안, 개발용 · 앱 내부 모의 신호)"))
        devBox.addView(row("모의 시연 시작" to { startScenario() }, "모의 시연 취소" to { cancelScenario() }))
        scenarioStatusView = status(); devBox.addView(scenarioStatusView); refreshScenarioPanel()
        devBox.addView(title("모의 경로 시간(가안, 개발용)"))
        devBox.addView(row("모의 경로 시작(8분)" to { startMockTrip(MockTrip.DEFAULT_TOTAL_SEC) }, "빨리 감기(1초=1분)" to { fastForwardMockTrip() },
            "모의 즉시 도착" to { if (mockTrip.active) { mockTrip.cancel(); host.onMockArrival(); refreshTripPanel() } }))
        tripStatusView = status(); devBox.addView(tripStatusView); refreshTripPanel()
        // 2026-11-13 2단계 D·B 시연(시나리오 버튼에는 넣지 않음).
        // D: 확인·출발 뒤 모의 P에서 아래 '착석 켬'을 유지하면 정차 뒤 대기(기본 60초) 후 안내. 아래 버튼은 대기를 5초/10초로 줄인다.
        // B: 확인 뒤 모의 D 상태에서 아래 '벨트' 버튼으로 체결 → 미체결로 바꾸면 좌석 이름 짧은 음성(30초 간격, 좌석당 3회).
        devBox.addView(title("모의: 두고 내림·벨트 풀림(가안, 개발용)"))
        val fastBtn = Button(ctx)
        fun fastLabel() = if (host.leftBehindDemoFast()) "두고 내림 대기: 시연 5초/10초" else "두고 내림 대기: 기본 60초/120초"
        fastBtn.text = fastLabel()
        fastBtn.setOnClickListener { host.setLeftBehindDemoFast(!host.leftBehindDemoFast()); fastBtn.text = fastLabel() }
        host.styleDevButton(fastBtn); fastBtn.minHeight = minPx; fastBtn.minimumHeight = minPx
        devBox.addView(fastBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // r5 3절: 교통 상식 간격 단축(debug 전용). 기본 출발 90초 뒤 첫 상식·그 뒤 8~12분 → 단축 10초·20~30초
        val tipBtn = Button(ctx)
        fun tipLabel() = if (host.debugTipTimingFast()) "교통 상식 간격: 단축 10초/20~30초" else "교통 상식 간격: 기본 90초/8~12분"
        tipBtn.text = tipLabel()
        tipBtn.setOnClickListener { host.debugSetTipTiming(if (host.debugTipTimingFast()) null else TIP_FAST_TIMING); tipBtn.text = tipLabel() }
        host.styleDevButton(tipBtn); tipBtn.minHeight = minPx; tipBtn.minimumHeight = minPx
        devBox.addView(tipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        devBox.addView(title("모의 좌석: 착석·벨트(가안, 개발용)"))
        for (seat in seatKeys) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val occB = Button(ctx).apply { setOnClickListener { setOcc(seat, occMock[seat] != true) } }
            val beltB = Button(ctx).apply {
                // 체결 → 미체결 → 값 없음 → 체결
                setOnClickListener { setBelt(seat, when { !beltMock.containsKey(seat) || beltMock[seat] == null -> true; beltMock[seat] == true -> false; else -> null }) }
            }
            for (b in listOf(occB, beltB)) {
                host.styleDevButton(b); b.minHeight = minPx; b.minimumHeight = minPx
                r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            seatButtons[seat] = occB to beltB
            devBox.addView(r)
        }
        refreshSeatPanel()
    }

    override fun onResume() {
        if (host.isDestroyed()) return
        if (mockNavi.state == MockNaviState.MOVING) scheduleTick()   // 모의 이동 이어서
        if (mockTrip.active) scheduleTripTick()
    }
    override fun onPause() { ui.removeCallbacks(mockTick); ui.removeCallbacks(tripTick) }   // 상태는 유지, onResume에서 이어서
    override fun onDestroy() {
        ui.removeCallbacks(tripTick); ui.removeCallbacks(scTick); scSteps = null
        tripStatusView = null; scenarioStatusView = null; seatButtons.clear()
        scenarioReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; scenarioReceiver = null
        naviInjectReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; naviInjectReceiver = null
        occInjectReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; occInjectReceiver = null
        stickerResetReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; stickerResetReceiver = null
        tipFastReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; tipFastReceiver = null
        frontProbeReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; frontProbeReceiver = null
        tripCapFastReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; tripCapFastReceiver = null
        ui.removeCallbacks(mockTick); mockStatusView = null; gearStatusView = null; gearButtons.clear()
        injector?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; injector = null
        benchReceiver?.let { r -> runCatching { host.devContext().unregisterReceiver(r) } }; benchReceiver = null
    }

    // ---- 디버그 주입: 벨트 변화(2026-11-08 Unit 5.3). debug 빌드에만 있다 ----
    // adb shell am broadcast -a com.crewguide.probe.DEBUG_BELT --es seat ROW_2_LEFT --es value false
    // 실제 차량 신호가 아니다. 앱의 실제 벨트 이벤트 처리 경로에 같은 값을 넣어 경고 흐름(화면 이탈 포함)을 시험한다
    private var injector: android.content.BroadcastReceiver? = null
    private var benchReceiver: android.content.BroadcastReceiver? = null
    private var ttsBench: TtsBench? = null
    private var sttBench: SttBench? = null
    override fun startInjector() {
        if (injector != null || host.isDestroyed() || !DevMockGuard.allowed) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val seat = i.getStringExtra("seat") ?: return
                // 반복 6(P1-5): --es value clear → 받은 값 지우기(NO_SIGNAL 표시 시험)
                if (i.getStringExtra("value") == "clear") { ui.post { if (!host.isDestroyed()) host.clearBeltDebug(seat) }; return }
                val v = when (i.getStringExtra("value")) { "true" -> true; "false" -> false; else -> null }
                ui.post { if (!host.isDestroyed()) host.injectBeltEvent(seat, v) }
            }
        }
        val f = android.content.IntentFilter(INJECT_BELT_ACTION)
        // adb(shell)에서 보내므로 EXPORTED가 필요하다. 플래그 상수가 있는 API 33 이상에서만 등록(에뮬레이터 API 34)
        if (android.os.Build.VERSION.SDK_INT < 33) { host.devLog("[디버그주입] API 33 미만이라 수신기 등록 안 함"); return }
        // 감사 10: 보내는 쪽에 DUMP 권한을 요구한다(adb shell은 가짐, 일반 앱은 못 보냄)
        host.devContext().registerReceiver(r, f, android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        injector = r
        host.devLog("[디버그주입] 벨트 주입 수신기 등록(debug 전용)")
        // v3 반복 5(§7): TTS 콜백 지연 측정(debug 전용, 별도 TextToSpeech 인스턴스). adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_TTS_BENCH
        val benchR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (i.action == STT_BENCH_ACTION) {   // v3 반복 6: STT 이벤트 경로 측정(sendAudio 파일 입력, 마이크 없음)
                    ui.post { if (!host.isDestroyed()) { val b = sttBench ?: SttBench(host.devContext()).also { sttBench = it }; b.run() } }
                    return
                }
                ui.post { if (!host.isDestroyed()) { val b = ttsBench ?: TtsBench(host.devContext()).also { ttsBench = it }; b.run() } }
            }
        }
        host.devContext().registerReceiver(benchR, android.content.IntentFilter(TTS_BENCH_ACTION).apply { addAction(STT_BENCH_ACTION) }, android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        benchReceiver = benchR
        // 2026-11-13 시연 시나리오 시작·취소(debug 전용, DUMP 권한 보낸 쪽만): adb shell am broadcast --user 10 -a com.crewguide.probe.SCENARIO_START
        val scR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val start = i.action == SCENARIO_START_ACTION
                ui.post { if (!host.isDestroyed()) { if (start) startScenario() else cancelScenario() } }
            }
        }
        host.devContext().registerReceiver(scR, android.content.IntentFilter(SCENARIO_START_ACTION).apply { addAction(SCENARIO_CANCEL_ACTION) },
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        scenarioReceiver = scR
        host.devLog("[디버그주입] 시연 시나리오 수신기 등록(debug 전용)")
        val nvR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val ev = i.getStringExtra("event") ?: return
                val sec = i.getIntExtra("sec", -1)
                ui.post {
                    if (host.isDestroyed()) return@post
                    when (ev) {
                        "remain" -> { host.devLog("[모의NAVI] 주입 남은 ${sec}초(앱 내부 모의)"); host.onMockRemain(sec) }
                        "route" -> { host.devLog("[모의NAVI] 주입 경로 시작 ${sec}초(앱 내부 모의)"); host.onMockRouteStarted(sec) }
                        "arrive" -> { host.devLog("[모의NAVI] 주입 도착(앱 내부 모의)"); host.onMockArrival() }
                    }
                }
            }
        }
        host.devContext().registerReceiver(nvR, android.content.IntentFilter(NAVI_INJECT_ACTION),
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        naviInjectReceiver = nvR
        val ocR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val seat = i.getStringExtra("seat") ?: return
                val raw = i.getStringExtra("value")
                ui.post {
                    if (host.isDestroyed()) return@post
                    when (val v = DebugOccValue.parse(raw)) {
                        DebugOccValue.CLEAR -> host.clearOccDebug(seat)
                        DebugOccValue.INVALID -> host.devLog("[디버그주입] DEBUG_OCC 값 모름($raw) → 무시")
                        else -> host.injectOccEvent(seat, v.value)
                    }
                }
            }
        }
        host.devContext().registerReceiver(ocR, android.content.IntentFilter(OCC_INJECT_ACTION),
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        occInjectReceiver = ocR
        val stR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                ui.post { if (!host.isDestroyed()) host.debugResetStickers() }
            }
        }
        host.devContext().registerReceiver(stR, android.content.IntentFilter(RESET_STICKERS_ACTION),
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        stickerResetReceiver = stR
        val tpR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val on = i.getBooleanExtra("on", true)
                ui.post { if (!host.isDestroyed()) host.debugSetTipTiming(if (on) TIP_FAST_TIMING else null) }
            }
        }
        host.devContext().registerReceiver(tpR, android.content.IntentFilter(TIP_FAST_ACTION),
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        tipFastReceiver = tpR
        // 2026-11-14 루프 r5 1절: 앱을 앞으로 올리는 방법 시험(DebugFrontProbe). 지연 뒤 실행(셸 방송 영향 제외)
        val frR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val m = i.getStringExtra("method") ?: return
                val delay = i.getLongExtra("delayMs", 12_000L).coerceIn(0L, 120_000L)
                host.devLog("[앞으로시험] ${m} ${delay}ms 뒤 실행 예약")
                ui.postDelayed({ if (!host.isDestroyed()) DebugFrontProbe.run(host.devContext(), m) }, delay)
            }
        }
        host.devContext().registerReceiver(frR, android.content.IntentFilter(FRONT_PROBE_ACTION),
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        frontProbeReceiver = frR
        // r10 H: 이동 알림 상한 단축
        val tcR = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val on = i.getBooleanExtra("on", true)
                ui.post { if (!host.isDestroyed()) host.debugSetTripCapTiming(if (on) TRIP_CAP_FAST_TIMING else null) }
            }
        }
        host.devContext().registerReceiver(tcR, android.content.IntentFilter(TRIP_CAP_FAST_ACTION),
            android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        tripCapFastReceiver = tcR
    }
    private var tripCapFastReceiver: android.content.BroadcastReceiver? = null
    private var tipFastReceiver: android.content.BroadcastReceiver? = null
    private var frontProbeReceiver: android.content.BroadcastReceiver? = null
    private var stickerResetReceiver: android.content.BroadcastReceiver? = null
    private var scenarioReceiver: android.content.BroadcastReceiver? = null
    private var naviInjectReceiver: android.content.BroadcastReceiver? = null
    private var occInjectReceiver: android.content.BroadcastReceiver? = null

    override fun movingLine(): String? =
        if (mockNavi.state == MockNaviState.MOVING) MockText.movingText(mockNavi.remainingMeters) else null

    override fun naviStateName(): String = mockNavi.state.name
    override fun onMockSignal(current: SignalSource): SignalSource = MockSource.onMock(current)
    override fun doneFor(byMock: Boolean, base: String): String = MockText.doneFor(byMock, base)
}

/** 시연 시나리오 단계 점검 간격(ms) */
private const val SCENARIO_TICK_MS = 300L
