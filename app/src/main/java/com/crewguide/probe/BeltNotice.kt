package com.crewguide.probe

/**
 * 정차 화면(홈)에 보호자에게 보이는 알림 문구(2026-11-08 Unit 5.3). 순수 값.
 * 확인한 사실처럼 단정하지 않고, 안전을 보장하거나 감시가 계속된 것처럼 말하지 않는다.
 * 홈은 유효한 P + 플랫폼 허용일 때만 보이므로 이 문구도 주행 중에는 보이지 않는다.
 */
object BeltNotice {
    /** 경고를 음성으로 전하지 못한 해제 이벤트가 있었음(미전달 기록 n건) */
    fun undelivered(n: Int): String = "이동 중 벨트 신호 변화를 음성으로 전하지 못한 적이 있어요(${n}번). 아이 벨트를 확인해 주세요."
    /** 감시 세션 중 신호 확인이 끊긴 구간이 있었음 */
    const val GAP = "이번 이동 중 벨트·기어 신호를 확인하지 못한 구간이 있었어요. 그동안의 벨트 변화는 알 수 없어요."
    /** debug 모의 기어 사용 중(release는 항상 꺼짐) */
    const val MOCK_GEAR = DemoLabel.MOCK_GEAR_PIECE
}
