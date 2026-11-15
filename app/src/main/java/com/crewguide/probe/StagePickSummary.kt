package com.crewguide.probe

/**
 * v3 반복 4(P1, S02): 좌석·아이 고르기 장면의 '현재 선택 → 다음 행동' 요약 한 칸(순수 문구, Android 클래스 없음).
 *
 * 자리: 하단 행동 띠의 [보호자 확인 시작] 왼쪽 빈 칸(줄 폭 1/4). 새 줄을 더하지 않으므로 행동 띠 높이·캐릭터 가림 예산은 그대로다.
 * 레퍼런스 02의 '현재 선택 요약 카드'를 무대 배치 변경 없이 옮긴 것. 벨트 상태는 쓰지 않는다(지도·범례가 보여 주며, 요약은 선택만 말한다).
 * 롤백: StageScreen에서 pickSummary 뷰 대신 빈 View를 다시 넣는다.
 */
object StagePickSummary {
    const val NONE_SELECTED = "먼저 오른쪽 좌석 지도에서 아이 자리를 눌러 주세요"
    /** v3 반복 5: 2영역에서는 좌석 지도가 왼쪽에 있다 */
    const val NONE_SELECTED_LEFT = "먼저 왼쪽 좌석 지도에서 아이 자리를 눌러 주세요"
    const val NEXT = "다음: [보호자 확인 시작]"

    /**
     * @param seatDescs 선택된 좌석의 읽기 이름(예: "뒷줄 왼쪽"), 지도 순서
     * @param selectedChildren 선택된 아이 수
     * @param hasProfiles 등록한 아이가 있는지(없으면 아이 수를 말하지 않는다)
     * @param canStart 시작 버튼이 켜졌는지
     */
    fun text(seatDescs: List<String>, selectedChildren: Int, hasProfiles: Boolean, canStart: Boolean, mapOnLeft: Boolean = false): String {
        if (seatDescs.isEmpty()) return if (mapOnLeft) NONE_SELECTED_LEFT else NONE_SELECTED
        val seats = if (seatDescs.size <= 2) seatDescs.joinToString(", ") else "좌석 ${seatDescs.size}곳"
        val kids = if (hasProfiles) " · 아이 ${selectedChildren}명" else ""
        val head = "선택: $seats$kids"
        return if (canStart) "$head\n$NEXT" else head
    }
}
