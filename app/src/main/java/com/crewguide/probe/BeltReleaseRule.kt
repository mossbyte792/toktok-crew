package com.crewguide.probe

// 주행 벨트 경고의 움직임 맥락 판정(순수 로직). 2026-11-08 Unit 5.3부터 해제 이벤트·경고 전달 정책은
// BeltWarningPolicy가 맡고, 이 파일은 기어 맥락(정차·주행·불명)과 세션 종료 조건·주행 경고 문구만 둔다.

/** 현재 기어·수신 상태·모의 여부(호출 시점 값) */
data class DriveContext(
    val gearName: String?,
    val linkOk: Boolean,
    val subFailed: Boolean,
    /** 앱이 벨트 값을 모의로 쓰는 중이면 true(실제 수신만 대상) */
    val simMode: Boolean,
)

/**
 * 차량 움직임 맥락(2026-11-07 ch DECISION 2, Unit 5.1). 이진 값(주행/아님)으로 합치지 않는다.
 * - STATIONARY: 유효한 P(링크 정상·구독 실패 아님·P 이름)
 * - MOVING: 정상 수신 중이고 기어 이름이 유효하며 P가 아님
 * - UNKNOWN: 기어 미수신·불명 계열 이름·단절·구독 실패. 정차로도 주행으로도 단정하지 않는다
 */
enum class MotionState { MOVING, STATIONARY, UNKNOWN }

object BeltReleaseRule {
    /** 안내 문구(가안). 확인한 사실처럼 말하지 않고 안전 보장·위협 표현을 쓰지 않는다 */
    const val TEXT = "벨트 신호가 바뀐 것 같아요. 안전한 곳에서 어른과 함께 확인해 주세요."

    /** 기어가 유효한 P(이름이 있고 링크 정상, 구독 실패 아님, P 이름) */
    fun isValidPark(gearName: String?, linkOk: Boolean, subFailed: Boolean): Boolean =
        linkOk && !subFailed && gearName != null && gearName.uppercase() in PARK_GEAR_NAMES

    /** 움직임 맥락 3분류. UNKNOWN을 STATIONARY나 MOVING으로 바꾸지 않는다(ch DECISION 2) */
    fun motionOf(gearName: String?, linkOk: Boolean, subFailed: Boolean): MotionState = when {
        isValidPark(gearName, linkOk, subFailed) -> MotionState.STATIONARY
        linkOk && !subFailed && gearName != null && !isInvalidGearName(gearName) -> MotionState.MOVING
        else -> MotionState.UNKNOWN
    }

    /**
     * 주행을 '확인'했는가(MOVING만 true). 하차 확인의 '주행 관측' 기록처럼 주행을 근거로 쓰는 곳만 쓴다.
     * false는 '정차'라는 뜻이 아니다(UNKNOWN 포함). 허용·안전 판단에 이 값을 쓰지 않는다(ch DECISION 2).
     */
    fun isDriving(gearName: String?, linkOk: Boolean, subFailed: Boolean): Boolean =
        motionOf(gearName, linkOk, subFailed) == MotionState.MOVING

    /** 무장 중 주행(MOVING)을 본 적이 있는지 누적(2026-11-07 Unit 5.1 감사 F1). UNKNOWN은 주행 관측으로 치지 않는다 */
    fun movedAfter(moved: Boolean, armed: Boolean, ctx: DriveContext): Boolean =
        moved || (armed && motionOf(ctx.gearName, ctx.linkOk, ctx.subFailed) == MotionState.MOVING)

    /**
     * 앱이 세션을 끝낼 때: 무장 후 주행을 본 뒤 유효한 P로 돌아왔을 때만(= 이번 이동 종료).
     * 주차 중 같은 P를 다시 받거나(감시 재조회) P→불명→P(재연결)로는 끝내지 않는다. 그러면 출발 후 경고가 사라지기 때문이다.
     */
    fun endsSession(moved: Boolean, ctx: DriveContext): Boolean = moved && isValidPark(ctx.gearName, ctx.linkOk, ctx.subFailed)
}
