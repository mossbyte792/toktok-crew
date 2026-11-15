package com.crewguide.probe

/**
 * 2026-11-14 SCREEN_EXIT_CHECK ❌·⑦: 차량 신호 다시 연결 간격(순수 규칙). 예약·로그·실행은 MainActivity가 한다.
 *
 * - 구독 실패·단절(⑦): 빠른 재시도 [FAST_INTERVAL_MS] × [FAST_MAX]회(화면이 보이거나 주행 벨트 감시 중). 그 뒤에도 멈추지 않고
 *   [SLOW_INTERVAL_MS] 간격으로 계속한다(화면이 보일 때만). 잠금 카드의 "신호가 돌아오면 자동으로 열려요"가 사실이 되게 한다.
 * - Vehicle 초기화 실패(❌, vehicle == null): 첫 지연 초기화 뒤 화면이 보이는 동안 [VEHICLE_INTERVAL_MS] 간격으로 다시 초기화한다.
 * 어느 경우에도 잠금(S09)은 신호를 다시 받을 때까지 그대로다(이 규칙은 잠금을 풀지 않는다).
 */
object ReconnectRule {
    const val FAST_INTERVAL_MS = 5_000L
    const val FAST_MAX = 6
    const val SLOW_INTERVAL_MS = 30_000L
    const val VEHICLE_INTERVAL_MS = 10_000L

    /** 구독 재연결 다음 예약 지연. done = 지금까지 한 재시도 횟수. null이면 예약하지 않는다 */
    fun linkDelay(done: Int, visible: Boolean, watchActive: Boolean): Long? = when {
        done < FAST_MAX -> if (visible || watchActive) FAST_INTERVAL_MS else null
        visible -> SLOW_INTERVAL_MS
        else -> null
    }

    /** 빠른 재시도를 다 썼는지(느린 재시도 구간) */
    fun slow(done: Int): Boolean = done >= FAST_MAX

    /** Vehicle 다시 초기화 예약 지연. 첫 초기화 전(initDone=false)·이미 있음·화면 안 보임이면 null */
    fun vehicleDelay(initDone: Boolean, vehicleReady: Boolean, visible: Boolean, destroyed: Boolean): Long? =
        if (initDone && !vehicleReady && visible && !destroyed) VEHICLE_INTERVAL_MS else null
}
