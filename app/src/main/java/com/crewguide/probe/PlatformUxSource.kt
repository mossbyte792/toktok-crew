package com.crewguide.probe

import android.content.Context
import android.os.Handler

// 플랫폼 주행 제한(CarUxRestrictionsManager) 수명주기 어댑터. 2026-11-07 Unit 5.1(ch 22:50 승인 조건).
// - 상태 판단은 순수 [PlatformUxTracker]가 한다: 연결·단절마다 세대가 바뀌고 이전 세대 콜백은 버린다.
// - 연결되면 리스너를 등록한 뒤 현재값을 조회한다. 조회 도중 변화 이벤트가 오면 이벤트가 우선한다.
// - 단절·초기 연결 실패·권한 오류·null은 UNKNOWN, 라이브러리·서비스 없음은 NOT_INTEGRATED. 어느 쪽도 게이트를 열지 않는다.
// - 화면 복귀 때 [requery]로 다시 조회한다(앱은 복귀마다 값을 '오래됨'으로 보고 새 값을 기다린다, GateFreshness).
// - 콜백은 모두 [handler](메인 스레드)에서 [onChange]로 전달한다. 종료 때 리스너 해제·연결 해제.
// - 에뮬레이터·실차가 이 값을 실제로 주는지는 기기에서 확인해야 한다(NOT_RUN).
class PlatformUxSource(
    private val context: Context,
    private val handler: Handler,
    private val onChange: (PlatformUx, String) -> Unit,
) {
    private var car: android.car.Car? = null
    private var manager: android.car.drivingstate.CarUxRestrictionsManager? = null
    private var released = false
    var track = PlatformUxTrack(); private set

    private fun publish(next: PlatformUxTrack, why: String) {
        track = next
        onChange(next.value, why)
    }

    fun start() {
        released = false
        try {
            val c = android.car.Car.createCar(context, handler, android.car.Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { carObj, ready ->
                handler.post {
                    if (released) return@post
                    if (ready) attach(carObj) else { detachManager(); publish(PlatformUxTracker.disconnected(track), "차량 서비스 연결 끊김") }
                }
            }
            car = c
            if (c == null) publish(PlatformUxTracker.unavailable(track), "차량 서비스 없음")
        } catch (t: Throwable) {   // android.car 라이브러리가 없는 기기(NoClassDefFoundError 포함)·권한 오류
            car = null
            publish(PlatformUxTracker.unavailable(track), "플랫폼 제한 연동 불가: ${t.javaClass.simpleName}")
        }
    }

    private fun attach(c: android.car.Car) {
        val m = try {
            c.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE) as? android.car.drivingstate.CarUxRestrictionsManager
        } catch (t: Throwable) {
            publish(PlatformUxTracker.disconnected(track), "주행 제한 관리자 얻기 실패: ${t.javaClass.simpleName}")
            return
        }
        if (m == null) { publish(PlatformUxTracker.unavailable(track), "주행 제한 관리자 없음"); return }
        detachManager()
        manager = m
        val connected = PlatformUxTracker.connected(track)
        publish(connected, "연결됨(현재값 조회 전)")
        val session = connected.session
        try {
            m.registerListener { r ->
                val requiresDO = r?.isRequiresDistractionOptimization
                val active = r?.activeRestrictions
                handler.post {
                    if (released) return@post
                    publish(PlatformUxTracker.event(track, session, requiresDO, active), "변화 이벤트")
                }
            }
        } catch (t: Throwable) {
            publish(PlatformUxTracker.disconnected(track), "리스너 등록 실패: ${t.javaClass.simpleName}")
            return
        }
        requery()
    }

    /** 현재값 다시 조회(연결 직후·화면 복귀). 연결이 없으면 아무것도 하지 않는다(값은 UNKNOWN 유지) */
    fun requery() {
        val m = manager ?: return
        if (released || !track.connected) return
        track = PlatformUxTracker.readStarted(track)
        val session = track.session
        val token = track.readToken
        val r = try { m.currentCarUxRestrictions } catch (t: Throwable) {
            publish(PlatformUxTracker.disconnected(track), "현재값 조회 실패: ${t.javaClass.simpleName}")
            return
        }
        val requiresDO = r?.isRequiresDistractionOptimization
        val active = r?.activeRestrictions
        handler.post {
            if (released) return@post
            publish(PlatformUxTracker.current(track, session, token, requiresDO, active), "현재값")
        }
    }

    private fun detachManager() {
        val m = manager ?: return
        runCatching { m.unregisterListener() }
        manager = null
    }

    fun release() {
        released = true
        runCatching { detachManager() }
        runCatching { car?.disconnect() }
        car = null
    }
}
