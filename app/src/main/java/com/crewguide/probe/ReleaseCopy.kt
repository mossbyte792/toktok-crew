package com.crewguide.probe

/**
 * 2026-11-10 2차 디렉티브 ②: 출시(release) 화면에서 개발·시연용 표기를 지우는 순수 규칙(Android 클래스 없음).
 * 지우는 것: 괄호 안에 '가안'이 든 표기, "시연용(가안)" 조각, "(시연용 그래픽)", '문구가 가안'이라는 문장.
 * 지우지 않는 것: "(보호자 보고 기준)" 같은 안전 고지, "앱 내부 모의…(실제 차량 아님)" 같은 출처 표기.
 * debug 빌드는 [BuildMockPolicy.SHOW_DRAFT_MARKS] = true라 원문 그대로 보인다.
 * 2026-11-14 사용자 지시: 사용자 화면(release에도 있는 화면)에 쓰는 상수에서는 표기를 직접 지웠다(ProfileUi·CharacterCustomizer·GuardianHeader·
 * DetailLines.GUARDIAN_TITLE·TripEndRule.TITLE·DemoLabel). 그래서 이 통로는 그 글자들에는 하는 일이 없고, 표기가 남은 곳
 * (debug 전용 카드·개발용 보기, 담당 밖 상수 StickerLogs.SUB_TITLE·StickerRule.LABEL)을 위한 안전망으로 남긴다.
 */
object DraftMarks {
    private val PAREN_DRAFT = Regex("""\s?\([^()]*가안[^()]*\)""")
    private val SENTENCES = listOf(
        "이 안내는 가안이며 법적 고지 문구가 아니에요" to "이 안내는 법적 고지 문구가 아니에요",
        " 문구는 모두 가안이에요." to "",
        " ${AppName.DRAFT_NOTE}" to "",
        StickerRule.LABEL to "",
        "(시연용 그래픽)" to "",
    )

    /** 개발·시연 표기를 지운 문구. 조각 구분자(" · ")로 이어진 라벨은 빈 조각을 빼고 다시 잇는다 */
    fun strip(text: String): String {
        var s = text
        for ((from, to) in SENTENCES) s = s.replace(from, to)
        s = PAREN_DRAFT.replace(s, "")
        if (s.contains(DemoLabel.SEPARATOR.trim())) {
            s = s.split(DemoLabel.SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(DemoLabel.SEPARATOR)
        }
        return s.trimEnd()
    }
}

/** 화면에 내보내는 문구 통로: 빌드 종류에 따라 원문 또는 [DraftMarks.strip] 결과 */
object ReleaseCopy {
    fun t(text: String): String = if (BuildMockPolicy.SHOW_DRAFT_MARKS) text else DraftMarks.strip(text)
}
