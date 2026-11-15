package com.crewguide.probe

import android.widget.LinearLayout

// release 빌드 전용. 모의 내비·모의 기어 구현이 없고, 모든 동작이 아무것도 하지 않는다(2026-11-07 Unit 5.1 P0-1).
// 같은 이름의 debug 사본(src/debug/DevToolsFactory.kt)만 모의 구현을 만든다.
object DevToolsFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(host: DevToolsHost): DevTools = NoDevTools
}

private object NoDevTools : DevTools {
    override fun attachPanels(devBox: LinearLayout) {}
    override fun startMockNavi() {}
    override fun forceArriveMockNavi() {}
    override fun onResume() {}
    override fun onPause() {}
    override fun onDestroy() {}
    override fun startInjector() {}
    override fun refreshGearPanel() {}
    override fun movingLine(): String? = null
    override fun naviStateName(): String = "없음(release)"
    override fun onMockSignal(current: SignalSource): SignalSource = current
    override fun doneFor(byMock: Boolean, base: String): String = base
    // 2026-11-13: 모의 경로 시간·시연 시나리오 없음(release)
    override fun startMockTrip(totalSec: Int) {}
    override fun fastForwardMockTrip() {}
    override fun startScenario() {}
    override fun cancelScenario() {}
    override fun scenarioRunning(): Boolean = false
}
