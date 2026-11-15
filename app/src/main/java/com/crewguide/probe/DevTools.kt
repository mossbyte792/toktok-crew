package com.crewguide.probe

import android.widget.Button
import android.widget.LinearLayout

// 개발용 모의 도구(모의 내비·모의 기어 패널)의 경계. 2026-11-07 Unit 5.1(ch DECISION 3 P0-1).
// 구현은 빌드 종류별 소스 세트에만 있다: src/debug = DebugDevTools(모의 구현), src/release = 아무것도 하지 않는 대체본.
// release APK에는 모의 내비·모의 기어 구현 클래스가 들어가지 않는다. 진입 함수는 [DevToolsFactory.create] 하나다.
// MainActivity의 모의 상태 변수(mockGearOn 등)는 이 인터페이스 밖에서도 DevMockGuard.admit을 거친다(이중 차단).

/** 모의 도구가 앱에 요청할 수 있는 동작. 모든 호출은 메인 스레드 */
interface DevToolsHost {
    fun devLog(msg: String)
    fun isDestroyed(): Boolean
    fun styleDevButton(b: Button)
    fun devTextColor(): Int
    fun devTitleSp(): Float
    fun devAuxSp(): Float
    fun devMinTouchPx(): Int
    fun devPx(dp: Int): Int
    /** 모의 기어 켜기/끄기 요청. 실제 반영은 앱이 DevMockGuard로 다시 확인한다 */
    fun requestMockGear(on: Boolean, parkName: String)
    /** 모의 기어 P/D 입력 요청 */
    fun requestMockGearInput(name: String, label: String)
    fun mockGearOn(): Boolean
    fun currentGearName(): String?
    /** 모의 내비 도착: 앱은 출처를 MOCK으로 기록하고 실제 도착과 같은 경로로 처리한다 */
    fun onMockArrival()
    fun refreshTripViews()
    /** 앱 Context(디버그 주입 수신기 등록용) */
    fun devContext(): android.content.Context
    /**
     * 디버그 주입 벨트 변화(2026-11-08 Unit 5.3). 에뮬레이터 Virtual Console에 좌석별 벨트 조작이 없어(G1 한계)
     * 기기 위에서 실제 벨트 이벤트 경로(onBeltEvent)를 시험하기 위한 주입이다. 실제 차량 신호가 아니며 로그에 [디버그주입]으로 남긴다
     */
    fun injectBeltEvent(seat: String, value: Boolean?)
    /**
     * v3 반복 6(P1-5): debug 전용. 좌석의 받은 벨트 값을 지워 '아직 받은 신호 없음'(NO_SIGNAL) 표시 경로를 시험한다.
     * 지원·연결·읽기 상태는 건드리지 않는다. 이후 실제 GET 값이 덮지 않게 주입 좌석으로 묶는다(앱 재시작 때 풀림)
     */
    fun clearBeltDebug(seat: String) {}

    // ---- 2026-11-13 자동 확인·내비 안내 시연(debug 전용). release DevTools는 아래를 부르지 않는다 ----
    /** 모의 착석(true=착석, false=빈 좌석, null=값 없음). 실제 점유 이벤트 경로(onOccEvent)로 합류, 출처는 모의 */
    fun injectOccEvent(seat: String, value: Boolean?) {}
    /** 2026-11-14 debug 착석 주입 해제(DEBUG_OCC value=clear): 실제 값을 다시 쓴다 */
    fun clearOccDebug(seat: String) {}
    /** 모의 경로 시작(총 초). 실제 NaviHelper onRouteStarted와 같은 TripGuide 입력 경로, 출처 MOCK */
    fun onMockRouteStarted(totalSec: Int) {}
    /** 모의 남은 시간(초). 실제 onDestinationInfo와 같은 입력 경로, 출처 MOCK */
    fun onMockRemain(sec: Int) {}
    /** 시연 시나리오 단계 기록(logcat [SCENARIO]) */
    fun scenarioLog(msg: String) { devLog(msg) }
    /** 홈 [시작]과 같은 동작(홈을 닫고 무대로) */
    fun scenarioHomeStart() {}
    /** 첫 아이 프로필만 선택. 등록된 아이가 없으면 false(등록 없이 진행) */
    fun scenarioPickFirstChild(): Boolean = false
    /** 확인 좌석을 이 좌석 하나로 맞춘다 */
    fun scenarioSelectOnly(seat: String) {}
    /** 무대 [시작]과 같은 동작(자동 확인 시작) */
    fun scenarioStartCheck() {}
    /** 음성 안내가 쉬는 중인가(대기 발화·대기 큐 없음) */
    fun scenarioTtsIdle(): Boolean = true
    /** 현재 자동 진행 단계 이름(없으면 null) */
    fun scenarioPhase(): String? = null
    /** 2026-11-13 2단계 D: 두고 내림 안내 대기를 시연용으로 짧게(60초/120초 → 5초/10초). release는 막힘 */
    fun setLeftBehindDemoFast(on: Boolean) {}
    fun leftBehindDemoFast(): Boolean = false
    /** 2026-11-14(지시 6) debug 전용: 스티커 기록만 지운다(아이 등록은 남김). 시험·제출 영상 녹화 전 초기화용. release는 부르지 않는다 */
    fun debugResetStickers() {}
    /**
     * 2026-11-14 r5 3절 debug 전용: 교통 상식 시간 값을 바꾼다(null = 기본 90초·8~12분). QA가 긴 간격을 기다리지 않게.
     * 짧은 값은 src/debug에만 있다. release DevTools는 부르지 않고 앱이 DevMockGuard로 한 번 더 막는다
     */
    fun debugSetTipTiming(t: TipTiming?) {}
    fun debugTipTimingFast(): Boolean = false
    /**
     * 2026-11-15 r10 H debug 전용: 이동 알림 상한 시간 값을 바꾼다(null = 기본 10분·30분, TripCapRule). QA가 긴 대기를 기다리지 않게.
     * 짧은 값은 src/debug에만 있다. release DevTools는 부르지 않고 앱이 DevMockGuard로 한 번 더 막는다
     */
    fun debugSetTripCapTiming(t: TripCapTiming?) {}
    fun debugTripCapFast(): Boolean = false
}

interface DevTools {
    /** 개발용 보기 안에 모의 패널을 붙인다(release: 아무것도 붙이지 않음) */
    fun attachPanels(devBox: LinearLayout)
    fun startMockNavi()
    fun forceArriveMockNavi()
    fun onResume()
    fun onPause()
    fun onDestroy()
    /** 디버그 주입 수신기 등록(release: 없음) */
    fun startInjector()
    fun refreshGearPanel()
    /** 이동 카드에 붙일 모의 이동 줄(없으면 null) */
    fun movingLine(): String?
    /** 시연 도우미 상태 줄 */
    fun naviStateName(): String
    /** 모의 도착 신호가 왔을 때의 출처 갱신(release는 출처를 바꾸지 않음) */
    fun onMockSignal(current: SignalSource): SignalSource
    /** 마침 문구(모의 도착으로 끝냈을 때만 바뀜) */
    fun doneFor(byMock: Boolean, base: String): String
    /** 2026-11-13 모의 경로 시간 시작(기본 8분). release: 없음 */
    fun startMockTrip(totalSec: Int = 480) {}
    /** 모의 경로 빨리 감기(1초 = 1분). release: 없음 */
    fun fastForwardMockTrip() {}
    /** 시연 시나리오 시작·취소(debug 개발자 패널·adb broadcast). release: 없음 */
    fun startScenario() {}
    fun cancelScenario() {}
    /** 시연 시나리오 실행 중인가(release: 항상 false) */
    fun scenarioRunning(): Boolean = false
}
