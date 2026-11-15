package com.crewguide.probe

// 하차 시 '뒷좌석 한 번 더 확인' 음성 한 줄(가안) 허용 판정. Android·SDK 클래스를 쓰지 않는 순수 로직이다.
// 이 규칙은 "안내를 허용할지"만 정한다. 발화·포커스·로그는 호출자가 한다.
//
// 규칙
// - 이번 세션(앱 실행 1회)에서 주행(정상 수신 중 P가 아닌 기어)이 한 번이라도 관측된 뒤,
//   유효한 P(링크 정상·구독 실패 아님·P 이름, 5초 시작 유예 종료)로 돌아왔을 때 한 번만 허용한다.
// - 기어를 모르거나 연결이 끊긴 상태는 주행으로도 유효한 P로도 보지 않는다.
// - 안내를 끈 상태에서는 허용하지 않는다.
// - 아이 선택·보호자 확인 여부·뒷좌석 점유 신호는 입력으로 받지 않는다(센서 신뢰성 한계).
// - 세션 초기화는 앱 종료·재시작뿐이다(P 복귀로 초기화하지 않는다).
// - 2026-11-08 6C: 정차 허용 게이트(유효 P + 플랫폼 UXR 허용, 신선도 포함)가 열려 있을 때만 허용한다.
//   1회 소진은 TTS onDone(TtsEnd.DONE)에서만 한다. 요청만으로는 소진하지 않는다.
//   DONE이 아닌 결과(중단·오류·상한·포커스)나 요청 실패·건너뜀이면 소진하지 않고 driveSeen을 내려
//   같은 P에서 자동 재생하지 않는다(다음 주행 → 유효 P + 허용에서만 다시). 결과가 올 때 주행 중이면 driveSeen은 유지한다.
//   진행 중 요청(pendingGen)이 있으면 새로 허용하지 않고, 다른 세대의 결과는 무시한다.
//   P 복귀·이 결과는 도착·이동 종료의 근거가 아니다(TripEndRule과 연결하지 않음).
// - 2026-11-08 ch 결정 1: 1회 단위는 '이동'이다. 이동 경계는 보호자의 '이번 이동 끝내기'(TripSessionRule.finish)뿐이다.
//   기어 D→P·잠깐 정차·재출발·경로 취소는 경계가 아니다. 끝내기를 하지 않으면 기존처럼 앱 실행(Activity) 동안 1회다.
//   새 이동에서도 주행 관측(driveSeen)부터 다시 시작하므로 끝내기 직후 같은 P에서는 나오지 않는다.

/** driveSeen: 이번 세션에서 주행을 본 적이 있는지. consumed: 이번 세션의 1회를 이미 썼는지 */
data class ExitCheckState(
    val driveSeen: Boolean = false,
    val consumed: Boolean = false,
    /** 결과를 기다리는 하차 한 줄 발화 세대(없으면 -1) */
    val pendingGen: Int = -1,
    /** 2026-11-08 QA RISK 3: 다른 발화(보호자 안내·질문·주행 경고)가 진행 중이라 미룬 상태. 그 발화가 끝나면 게이트를 다시 보고 재평가한다 */
    val waitingIdle: Boolean = false,
    /** 이번 이동에서 '다른 발화 중'으로 미룬 횟수(상한 [MAX_BUSY_WAITS]) */
    val busyWaits: Int = 0,
)

/** 호출 시점의 기어 상태·설정 값. graceOver는 시작 후 5초 유예가 끝났는지 */
data class ExitCheckContext(
    val gearName: String?,
    val linkOk: Boolean,
    val subFailed: Boolean,
    val guidanceEnabled: Boolean,
    val graceOver: Boolean,
    /** 정차 허용 게이트: 유효 P이고 플랫폼 주행 제한(UXR)이 허용이며 값이 신선함. UNKNOWN·제한이면 false */
    val gateAllowed: Boolean,
)

object ExitCheckRule {
    /** 음성 문구(가안). 안전 보장·단정 표현을 쓰지 않는다 */
    const val TEXT = "내리기 전에 뒷좌석을 한 번 더 확인해 주세요."

    /** 기어 변화 1건: 주행 관측을 기록하고, 허용 여부(ALLOW)를 함께 돌려준다. 1회 소비는 consume으로 따로 한다 */
    fun onGear(state: ExitCheckState, ctx: ExitCheckContext): Pair<ExitCheckState, Boolean> {
        if (BeltReleaseRule.isDriving(ctx.gearName, ctx.linkOk, ctx.subFailed)) {
            return (if (state.driveSeen) state else state.copy(driveSeen = true)) to false
        }
        val validP = BeltReleaseRule.isValidPark(ctx.gearName, ctx.linkOk, ctx.subFailed)
        val allow = validP && ctx.gateAllowed && ctx.graceOver && ctx.guidanceEnabled &&
            state.driveSeen && !state.consumed && state.pendingGen < 0
        return state to allow
    }

    /** 발화 요청이 나감(세대 g). 아직 소진이 아니다 */
    fun onRequested(state: ExitCheckState, g: Int): ExitCheckState = state.copy(pendingGen = g)

    /** 허용됐지만 발화 요청이 나가지 않음(준비 안 됨·포커스 거부·예외) 또는 미룬 대기를 취소. 소진 없이 같은 P 반복만 막는다 */
    fun onNotRequested(state: ExitCheckState, drivingNow: Boolean): ExitCheckState =
        state.copy(driveSeen = drivingNow, waitingIdle = false)

    /** 다른 발화와 겹치지 않게 미룰 수 있는 최대 횟수(이동당). 넘으면 미전달로 끝낸다(소진 아님) */
    const val MAX_BUSY_WAITS = 3

    /**
     * 허용됐지만 다른 발화가 진행 중(QA RISK 3). 진행 중인 발화를 끊지 않고 미룬다(driveSeen 유지, 소진 아님).
     * 상한을 넘으면 [onNotRequested]처럼 미전달로 끝낸다. 반환 second = 계속 기다리는지
     */
    fun onBusy(state: ExitCheckState, drivingNow: Boolean): Pair<ExitCheckState, Boolean> {
        val n = state.busyWaits + 1
        return if (n > MAX_BUSY_WAITS) onNotRequested(state, drivingNow).copy(busyWaits = n) to false
        else state.copy(waitingIdle = true, busyWaits = n) to true
    }

    /** 미룬 상태에서 게이트가 닫힘(주행·UXR 제한·단절)·화면 이탈: 오래된 안내를 나중에 재생하지 않게 대기를 취소한다 */
    fun cancelWaiting(state: ExitCheckState, drivingNow: Boolean): ExitCheckState =
        if (state.waitingIdle) onNotRequested(state, drivingNow) else state

    /** 새 이동(끝내기 완료). 소진·주행 관측을 비우고, 진행 중이던 발화의 결과는 이전 이동 것이므로 무시한다 */
    fun onNewTrip(@Suppress("UNUSED_PARAMETER") state: ExitCheckState): ExitCheckState = ExitCheckState()

    /** 발화 결과. 기다리던 세대가 아니면 무시. DONE만 소진, 나머지는 소진 없이 driveSeen을 내린다(주행 중이면 유지) */
    fun onResult(state: ExitCheckState, g: Int, end: TtsEnd, drivingNow: Boolean): ExitCheckState {
        if (state.pendingGen < 0 || g != state.pendingGen) return state
        return if (end == TtsEnd.DONE) state.copy(consumed = true, pendingGen = -1, waitingIdle = false)
        else state.copy(driveSeen = drivingNow, pendingGen = -1, waitingIdle = false)
    }
}
