package com.crewguide.probe

/**
 * 2026-11-14 루프 r2(디자인 D): 설정 화면 정리 — 카드 문구 단일 출처와 순수 배치 계산.
 * 화면 만들기는 아직 MainActivity.buildSettingsLayout(카드 빌더)에 있고, 이 파일은 문구·배치 값만 준다(다음 단계에서 화면 구성을 이 파일로 옮긴다).
 * 카드 순서(위→아래, 2단 균형 가능): 아이 관리 → 안내 음성 → 이동 안내 알림 → (이번 주 스티커: 아이 관리 카드 안) → 기록·초기화 → 앱 정보·개인정보 → 앱 나가기.
 */
object SettingsCopy {
    /** 머리말 한 줄(예전: "현재 위치: 설정 · 주차(P) 상태에서만 바꿀 수 있어요. 차가 움직이면 이 화면은 닫혀요.") */
    const val HEADER_NOTE = "주차(P) 중에만 바꿀 수 있어요"
    const val KIDS_TITLE = "아이 관리"
    const val KIDS_DESC = "별명만 등록해요. 등록하지 않아도 쓸 수 있어요."
    const val VOICE_TITLE = "안내 음성"
    const val RECORD_TITLE = "기록·초기화"
    /** 루프 r2 캡처 뒤: 스티커 카드가 접힌 줄 하나만 있어 빈 카드처럼 보였다 → 카드 제목 + 접이식 줄 "도장판 보기" */
    const val STICKER_TITLE = "이번 주 스티커"
    const val STICKER_TOGGLE = "도장판 보기"
    /** 안내 음성 카드 안 첫 스위치 이름(카드 제목 '안내 음성'과 겹치지 않게) */
    const val VOICE_SWITCH = "소리로 안내하기"
    /** 루프 r3: 앱 나가기 카드는 제목 없이 이 한 줄 + [앱 나가기] 버튼(같은 말 두 번 금지) */
    const val EXIT_DESC = "앱을 끝내요. 별명·스티커 기록은 그대로 남아요"
    /** 지난 좌석 이름은 아직 저장된다(BoardingEffect.RecordSuggestion) → 지울 수 있게 [지난 좌석 추천 지우기]는 남긴다 */
    const val RECORD_DESC = "지난번에 고른 좌석 이름이 이 기기에 저장돼 있어요."

    /** 설정 화면에서 빠져야 하는 옛 흐름 문구(소스 검사 테스트용) */
    val OLD_FLOW_PHRASES = listOf(
        "음성으로 답하기(마이크)는 지금은 쓰지 않아요",
        "현재 위치: 설정",
        "별명 등록·수정·삭제와 이번 주 스티커 기록이에요",
        "아이 별명은 ‘아이 관리’의 [선택한 아이 삭제]",
    )

    fun all(): List<String> = listOf(HEADER_NOTE, KIDS_TITLE, KIDS_DESC, VOICE_TITLE, RECORD_TITLE, RECORD_DESC, STICKER_TITLE, STICKER_TOGGLE, VOICE_SWITCH, EXIT_DESC)

    /**
     * 루프 r3 고정 배치: 2단 = 왼쪽 ①④⑤ / 오른쪽 ②③, 그 아래 전체 폭 ⑥ 앱 정보·개인정보, 구분선, ⑦ 앱 나가기.
     * (QA P2: ⑥을 오른쪽 열에 두면 고지 문장이 길어 왼쪽 열 아래에 큰 빈칸이 생겼다, 글꼴 2.0에서 특히)
     */
    val LEFT_COLUMN = listOf(KIDS_TITLE, STICKER_TITLE, RECORD_TITLE)
    val RIGHT_COLUMN = listOf(VOICE_TITLE, "이동 안내 알림")
    val FULL_WIDTH = listOf("앱 정보·개인정보")
}

/** 설정 2단 배치 순수 계산(dp). MainActivity.buildSettingsLayout과 같은 값(바깥 여백 8, 열 무게 1.15 : 1, 열 사이 CARD_GAP_DP) */
object SettingsLayout {
    const val OUTER_PAD_DP = 8
    const val SIDE_PAD_DP = 16
    const val LEFT_WEIGHT = 1.15f
    const val RIGHT_WEIGHT = 1f
    /** 루프 r3: 자동 열 균형으로 옮길 카드 없음(고정 배치) */
    val MOVABLE_NONE: List<android.view.View> = emptyList()

    /** 설정 창 폭(dp) → (왼쪽 열, 오른쪽 열) 폭 */
    fun columns(windowDp: Float): Pair<Float, Float> {
        val inner = windowDp - OUTER_PAD_DP * 2 - DuoTokens.CARD_GAP_DP
        val l = inner * LEFT_WEIGHT / (LEFT_WEIGHT + RIGHT_WEIGHT)
        return l to (inner - l)
    }

    /** 한 줄 글자 폭 추정(한글 1em, 띄어쓰기 0.35em) */
    fun textWidth(text: String, sp: Float, fontScale: Float): Float =
        text.sumOf { if (it == ' ') 0.35 else 1.0 }.toFloat() * sp * fontScale

    /** 버튼 안 글자 줄 수(말줄임 없음, 좌우 안쪽 여백 16dp씩) */
    fun buttonLines(label: String, widthDp: Float, sp: Float, fontScale: Float): Int {
        val inner = widthDp - 32f
        if (inner <= 0f) return Int.MAX_VALUE
        return Math.max(1, Math.ceil((textWidth(label, sp, fontScale) / inner).toDouble()).toInt())
    }
}

/**
 * 2026-11-14 루프 r2(디자인 D): 설정 버튼 3종 정리. 주요 = 파랑(PRIMARY), 보통 = 연회색(NEUTRAL), 지우기 = 빨강 글자(DANGER) + 기호 "✕".
 * 색만으로 구분하지 않도록 지우기 버튼 글자 앞에 기호를 붙인다. 글꼴은 설정 화면 버튼 전체 Jua(StageFonts BIG_BUTTON).
 */
object SettingsLook {
    const val DANGER_MARK = "✕"
    fun dangerLabel(text: String): String = if (text.startsWith(DANGER_MARK)) text else "$DANGER_MARK $text"

    /** 설정 화면 묶음 안의 앱 버튼(DuoButton) 글꼴을 Jua로 바꾼다(뷰 트리 한 번 훑기, 모양·크기는 그대로) */
    fun applyJua(root: android.view.View) {
        if (root is DuoButton) root.typeface = StageFonts.typeface(root.context, StageFonts.FontRole.BIG_BUTTON)
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) applyJua(root.getChildAt(i))
    }
}
