package com.crewguide.probe

/**
 * 출발 전 참여 스티커 판정(순수 로직, Android 클래스 없음).
 * 보상은 "확인 단계에 참여했다"는 보호자 보고 기준이며 벨트 착용·안전 인증이 아니다.
 * 2026-11-08 F1: 1회 단위는 이동(TripSessionRule.stickerStep). 잠금·onPause로는 초기화하지 않는다. 끝내기·프로세스 재시작에서 새로 시작.
 */
data class StickerResult(
    /** 이 세션에서 이미 받은 상태(다음 판정에 넘길 값) */
    val sessionReceived: Boolean,
    /** 지금 스티커를 화면에 보여 줄지 */
    val show: Boolean,
    /** 이번 판정에서 새로 부여됐는지(세션당 최대 1회) */
    val newlyGranted: Boolean,
)

object StickerRule {
    /** 아이에게 읽히는 스티커 문장 */
    const val CHILD_TEXT = "출발 전 확인에 참여했어요"
    /**
     * 보호자·심사자용 표기(작은 보조 라벨로 분리해 표시).
     * 2026-11-13 규칙 변경(이전 → 이후): '(보호자 보고 기준)' → '(신호 확인 기준)'. 스티커 조건이 보호자 '예'에서
     * 자동 확인 완료(착석·벨트 신호) 또는 보호자 대체 버튼으로 바뀌었다. 대체 버튼이 쓰였으면 [PARENT_NOTE_GUARDIAN].
     */
    const val PARENT_NOTE = "(신호 확인 기준)"
    /** 보호자 대체 버튼이 쓰인 이동(신호 확인 + 보호자 확인) */
    const val PARENT_NOTE_GUARDIAN = "(보호자 확인 포함)"
    /** 전체 문구(CHILD_TEXT + PARENT_NOTE) */
    const val TEXT = CHILD_TEXT + PARENT_NOTE
    const val LABEL = "시연용(가안)"

    /**
     * @param sessionReceived 현재 세션에서 이미 받았는지
     * @param targetCount 보호자가 고른 대상 좌석 수
     * @param yesReported 보호자 확인 상태가 YES_REPORTED인지
     * 벨트 신호·좌석 상태 모델의 확인 불가는 입력이 아니다.
     */
    fun evaluate(sessionReceived: Boolean, targetCount: Int, yesReported: Boolean): StickerResult {
        if (targetCount <= 0 || !yesReported) return StickerResult(sessionReceived, show = false, newlyGranted = false)
        return StickerResult(sessionReceived = true, show = true, newlyGranted = !sessionReceived)
    }

    /** 세션 상태 초기화 값(2026-11-08부터 앱은 잠금·onPause에서 부르지 않는다. 테스트 호환용) */
    fun reset(): Boolean = false
}
