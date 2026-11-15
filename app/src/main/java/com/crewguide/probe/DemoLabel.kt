package com.crewguide.probe

/**
 * 아이 화면 아래의 작은 보조 라벨 문자열 조립(순수 로직, Android 클래스 없음).
 * 심사자·보호자에게 알리는 표기를 아이용 큰 문장과 분리해 한 줄에 모은다. 조각 문구는 기존 그대로다.
 */
object DemoLabel {
    /**
     * 기존 캐릭터 아래 라벨 문구.
     * 2026-11-14 사용자 지시("(가안, 시연용)" 표기 정리): "(시연용 그래픽)"을 떼고, "시연용(가안)" 조각(StickerRule.LABEL)은 라벨에 넣지 않는다.
     * debug 화면도 release 화면(ReleaseCopy가 지우던 결과)과 같은 글자가 된다. 모의 표기·기준 표기는 그대로 남긴다
     */
    const val PIECE_GRAPHIC = "안내자 캐릭터"
    /** 스티커 기준 표기. 2026-11-13: 자동 확인 흐름으로 '(보호자 보고 기준)' → '(신호 확인 기준)' */
    const val PIECE_REPORT = StickerRule.PARENT_NOTE
    /** 보호자 대체 버튼이 쓰인 이동의 기준 표기 */
    const val PIECE_REPORT_GUARDIAN = StickerRule.PARENT_NOTE_GUARDIAN
    /** 자동 흐름에 앱 내부 모의 좌석·내비 값이 섞였을 때(debug만) */
    const val MOCK_SIGNAL_PIECE = "앱 내부 모의 신호(실제 차량 아님)"
    const val SEPARATOR = " · "
    /** 모의 기어 사용 중 표기(debug 빌드에서만 켜짐. release는 모의 기어 상태가 항상 꺼짐) */
    const val MOCK_GEAR_PIECE = "앱 내부 모의 기어(실제 차량 아님)"

    /**
     * @param stickerShown 스티커 행이 보이는 상태(YES_REPORTED)인지
     * @param kidCount 이번에 스티커를 받은 아이 수. 스티커가 보이고 1 이상일 때만 '(아이 N명에게 +1)'을 붙인다
     */
    /** 스티커 기록 저장·읽기 실패 표기(참여 표시와 영구 기록 성공을 구분) */
    const val RECORD_FAILED_PIECE = "스티커 기록 안 됨"

    fun build(stickerShown: Boolean, kidCount: Int, mockGear: Boolean = false, recordFailed: Boolean = false,
              guardianIncluded: Boolean = false, mockSignal: Boolean = false): String {
        val parts = mutableListOf(AppName.NAME)
        if (mockGear) parts.add(MOCK_GEAR_PIECE)   // 말줄임은 끝에서 생기므로 모의 표기를 앞쪽에 둔다
        if (mockSignal) parts.add(MOCK_SIGNAL_PIECE)
        parts.addAll(listOf(PIECE_GRAPHIC, if (guardianIncluded) PIECE_REPORT_GUARDIAN else PIECE_REPORT))
        if (stickerShown && kidCount > 0) parts.add(StickerLogs.grantNote(kidCount))
        if (stickerShown && recordFailed) parts.add(1, RECORD_FAILED_PIECE)   // 말줄임에 가려지지 않게 앞쪽에 둔다
        return parts.joinToString(SEPARATOR)
    }
}
