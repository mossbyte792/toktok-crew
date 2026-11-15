package com.crewguide.probe

// [debug 소스 세트 전용, 2026-11-07 Unit 5.1 P0-1] release APK에는 들어가지 않는다(src/release에는 이 파일이 없음).
// 앱 내부 모의 내비(가안, 시연용). Android·SDK 클래스를 쓰지 않는 순수 로직이다. 시간은 인자(dtMs)로만 받는다.
// 실제 내비 경로 요청이나 실제 도착 이벤트와 무관하며, 화면에는 '앱 내부 모의'로 구분해 표기한다.
//
// 전이표
//   IDLE   --start-->        MOVING   (총 거리 설정)
//   ARRIVED--start-->        MOVING   (다시 출발)
//   MOVING --start-->        MOVING   (무시)
//   MOVING --tick, 남은 거리 0 이하--> ARRIVED (도착 이벤트 1회)
//   MOVING --forceArrive-->  ARRIVED  (도착 이벤트 1회)
//   어느 상태든 --cancel--> IDLE
//   IDLE/ARRIVED에서 tick·forceArrive는 무시

enum class MockNaviState { IDLE, MOVING, ARRIVED }

/** 모의 이동 갱신 간격(ms, 가안) */
const val MOCK_TICK_MS = 500L

class MockNavi {
    companion object {
        const val DEFAULT_TOTAL_M = 600
        const val SPEED_MPS = 30   // 가안: 초당 30m → 600m가 약 20초 (내부는 mm 정수로 계산해 오차 없음: 30mm/ms)
        private const val MAX_DT_MS = 1_000_000_000L   // 곱셈 넘침 방지용 상한(약 11일)
    }

    var state: MockNaviState = MockNaviState.IDLE
        private set
    private var totalMm = DEFAULT_TOTAL_M * 1000L
    private var remainingMm = 0L

    /** 남은 거리(m, 올림). 이동 중에는 1 이상, 그 밖에는 0. 음수 없음 */
    val remainingMeters: Int
        get() = if (state == MockNaviState.MOVING) ((remainingMm + 999L) / 1000L).toInt() else 0

    /** 진행률 0..1 */
    val progress: Double
        get() = when (state) {
            MockNaviState.IDLE -> 0.0
            MockNaviState.ARRIVED -> 1.0
            MockNaviState.MOVING -> (1.0 - remainingMm.toDouble() / totalMm.toDouble()).coerceIn(0.0, 1.0)
        }

    /** IDLE 또는 ARRIVED에서만 출발한다. 이동 중이면 무시(false). totalMeters가 0 이하면 기본값 */
    fun start(totalMeters: Int = DEFAULT_TOTAL_M): Boolean {
        if (state == MockNaviState.MOVING) return false
        totalMm = (if (totalMeters <= 0) DEFAULT_TOTAL_M else totalMeters) * 1000L
        remainingMm = totalMm
        state = MockNaviState.MOVING
        return true
    }

    /** 시간 경과. 도착한 순간에만 true(1회). 음수 dt는 0으로 본다 */
    fun tick(dtMs: Long): Boolean {
        if (state != MockNaviState.MOVING) return false
        val dt = dtMs.coerceIn(0L, MAX_DT_MS)
        remainingMm -= SPEED_MPS * dt   // 30m/s = 30mm/ms
        if (remainingMm <= 0L) {
            remainingMm = 0L
            state = MockNaviState.ARRIVED
            return true
        }
        return false
    }

    fun cancel() {
        state = MockNaviState.IDLE
        remainingMm = 0L
    }

    /** 이동 중에서만 즉시 도착. 도착한 순간에만 true(1회) */
    fun forceArrive(): Boolean {
        if (state != MockNaviState.MOVING) return false
        remainingMm = 0L
        state = MockNaviState.ARRIVED
        return true
    }
}

object MockSource {
    /** 실제 신호는 항상 REAL (모의 표시가 사라진다) */
    fun onReal(@Suppress("UNUSED_PARAMETER") current: SignalSource): SignalSource = SignalSource.REAL

    /** 모의 신호: 이미 REAL이면 REAL 유지, 아니면 MOCK */
    fun onMock(current: SignalSource): SignalSource =
        if (current == SignalSource.REAL) SignalSource.REAL else SignalSource.MOCK

    fun reset(): SignalSource = SignalSource.NONE
}

/** 모의 표기 문구와 조립(순수 로직) */
object MockText {
    const val MOCK_LABEL = StageText.MOCK_ARRIVAL_LABEL
    const val ARRIVAL_SUFFIX = "(앱 내부 모의 도착)"
    const val PANEL_TITLE = "모의 내비(가안, 개발용)"
    const val BTN_START = "모의 출발"
    const val BTN_ARRIVE = "모의 즉시 도착"
    const val BTN_CANCEL = "모의 취소"

    fun movingText(remainingMeters: Int): String = "앱 내부 모의 이동 중 · 남은 거리 $remainingMeters m"

    // 2026-11-14: 이동 끝 안내 문구의 모의 표기 바꾸기(promptFor)는 안내 문구와 함께 지웠다(사용자 지시)
    /** 마침 문구: 모의 도착으로 끝냈으면 접미 표기를 붙인다 */
    fun doneFor(byMock: Boolean, base: String): String = if (byMock) base + ARRIVAL_SUFFIX else base

    /** 시연 도우미 패널의 상태 한 줄 */
    fun panelStatus(state: MockNaviState, remainingMeters: Int): String = when (state) {
        MockNaviState.IDLE -> "준비"
        MockNaviState.MOVING -> "이동 중 · 남은 거리 $remainingMeters m"
        MockNaviState.ARRIVED -> "도착(앱 내부 모의)"
    }
}

// ---- 2026-11-13 모의 경로 시간(시연용, debug 전용). 기존 거리 모의(MockNavi)는 그대로 두고 시간만 따로 센다 ----
// 경로 시작(기본 8분) → 남은 시간 감소(배속: 모의 초/실제 초) → 0이 되면 도착 1회. 시간은 인자(dtMs)로만 받는다.
// 실제 내비 값과 같은 TripGuide 입력 경로(onRouteStarted·onRemain·도착)로 들어가며 출처는 SignalSource.MOCK이다.
class MockTrip {
    companion object {
        const val DEFAULT_TOTAL_SEC = 480
        /** 빨리 감기 배속(1초 = 1분) */
        const val FAST_SCALE = 60
        private const val MAX_DT_MS = 1_000_000_000L
    }

    var active = false; private set
    var arrived = false; private set
    var totalSec = 0; private set
    /** 모의 초/실제 초 */
    var timeScale = 1; private set
    private var remainingMs = 0L   // 모의 시간(ms)

    /** 남은 모의 시간(초, 올림). 이동 중이 아니면 0 */
    val remainingSec: Int get() = if (active) ((remainingMs + 999L) / 1000L).toInt() else 0

    /** 이미 이동 중이면 무시(false). 0 이하는 기본값 */
    fun start(total: Int = DEFAULT_TOTAL_SEC, scale: Int = 1): Boolean {
        if (active) return false
        totalSec = if (total <= 0) DEFAULT_TOTAL_SEC else total
        remainingMs = totalSec * 1000L
        timeScale = scale.coerceAtLeast(1)
        active = true; arrived = false
        return true
    }

    /** 배속 변경(남은 모의 시간은 그대로) */
    fun setScale(scale: Int) { timeScale = scale.coerceAtLeast(1) }

    /** 실제 시간 경과. 도착한 순간에만 true(1회) */
    fun tick(dtMs: Long): Boolean {
        if (!active) return false
        remainingMs -= dtMs.coerceIn(0L, MAX_DT_MS) * timeScale
        if (remainingMs <= 0L) {
            remainingMs = 0L; active = false; arrived = true
            return true
        }
        return false
    }

    fun cancel() { active = false; arrived = false; remainingMs = 0L }
}
