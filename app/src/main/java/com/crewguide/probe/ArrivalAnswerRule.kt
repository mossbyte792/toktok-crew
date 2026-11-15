package com.crewguide.probe

// 2026-11-14 r5 추가 지시 A(개발 B 몫, 순수 규칙): 목적지 없는 이동이 출발 뒤 유효 P가 되면 "목적지에 도착했나요?"를 묻는다(버튼은 개발 A).
// 버튼이 기본이고 음성 대답은 덧붙임. 질문 음성이 끝난 뒤 [LISTEN_MS] 동안만 듣고, 알아들은 말만 버튼과 같은 경로(onArrivalAnswer)로 보낸다.
// 애매하면 대답 없음(NONE) → 버튼만. 인식 원문은 저장·로그하지 않는다(판정값만).

/** 도착 질문 음성 대답 판정 */
enum class ArrivalAnswer { ARRIVED, NOT_YET, NONE }

object ArrivalAnswerRule {
    /** 질문 음성이 끝난 뒤 듣는 시간 */
    const val LISTEN_MS = 5_000L
    /** 이보다 긴 말(정규화 뒤 글자 수)은 대화로 보고 대답 없음 */
    const val MAX_LEN = 16

    /** 짧은 예 대답(정확 일치만. "네비"·"네 번째"처럼 다른 말의 앞부분은 예가 아님) */
    // "내": 에뮬 합성 음성 '네'가 '내'로 인식된 일이 있어(2026-11-14 STT 파일 입력 시험) 단독일 때만 예로 받는다
    val YES_SHORT = setOf("예", "네", "응", "넵", "네네", "예예", "응응", "네에", "예에", "내")
    /** 아니요 표지(어디에 있어도 아니요 쪽) */
    val NO_MARKS = listOf("아니", "아직", "잠깐", "안했", "안왔", "못했", "멈췄")
    /** 도착 표지(옛 규칙 [decideLegacy]에서만 씀) */
    const val ARRIVE_MARK = "도착"

    // ---- r10 C(개발 1, 2026-11-15): "곧 도착해요"가 예로 판정됨(사람 목소리 재현, 인식 원문은 정확) → 엄격 판정 ----
    /** 끄면(false) 옛 규칙([decideLegacy]: "도착"이 있으면 예)으로 되돌린다 */
    const val STRICT_ENABLED = true
    /** 엄격 판정의 예 표지(공백 제거 뒤 포함). "도착"만으로는 예가 아니다 */
    val ARRIVED_MARKS = listOf("도착했", "도착이에요", "도착입니다", "다왔")
    /** 한 낱말 대답 "도착"(정확 일치만). 기존 판정표 유지용 — "도착해요"·"도착이요"는 예가 아니다 */
    val ARRIVED_EXACT = setOf("도착")
    /** 엄격 판정의 아니요 표지 = 기존 표지 + 곧·거의·금방·조금·도착 전·멀었. "내릴까요?"([decideAlight])에는 쓰지 않는다("곧 내려요"는 예) */
    // r10 수정(PM): "안 도착했어요"가 예로 판정됨("안도착했어요"에 도착했) → 부정 표지 안도착·못도착·도착안·도착못 추가
    val ARRIVE_NO_MARKS = NO_MARKS + listOf("곧", "거의", "금방", "조금", "도착전", "멀었", "안도착", "못도착", "도착안", "도착못")

    /** 공백·문장부호 제거(원문은 남기지 않는다) */
    fun normalize(text: String): String = text.filter { !it.isWhitespace() && it !in ".,?!~…。·" }

    /**
     * r10 C 엄격 판정([STRICT_ENABLED]). 예는 두 경우뿐이다.
     * - 빈 말·너무 긴 말 → NONE
     * - 짧은 예("예·네·응·내" 등) 또는 "도착" 한 낱말 정확 일치 → ARRIVED
     * - 아니요 표지([ARRIVE_NO_MARKS], "곧·거의·금방·조금·도착 전·멀었" 포함)가 있음 → NOT_YET ("곧 도착해요", "거의 다 왔어요", "도착 전이에요")
     *   단, 예로 시작하면서 아니요 표지도 있으면 섞인 말로 보고 NONE("네 아니요", "네 곧 도착해요")
     * - 예 표지([ARRIVED_MARKS]: 도착했·도착이에요·도착입니다·다왔)가 있음 → ARRIVED ("도착했어요", "네 도착했어요", "다 왔어요")
     * - 그 밖("도착해요" 등) → NONE(버튼만)
     */
    fun decide(text: String?): ArrivalAnswer {
        if (!STRICT_ENABLED) return decideLegacy(text)
        val t = normalize(text ?: return ArrivalAnswer.NONE)
        if (t.isEmpty() || t.length > MAX_LEN) return ArrivalAnswer.NONE
        if (t in YES_SHORT || t in ARRIVED_EXACT) return ArrivalAnswer.ARRIVED
        val hasNo = ARRIVE_NO_MARKS.any { t.contains(it) }
        if (hasNo) return if (startsYes(t)) ArrivalAnswer.NONE else ArrivalAnswer.NOT_YET
        if (ARRIVED_MARKS.any { t.contains(it) }) return ArrivalAnswer.ARRIVED
        return ArrivalAnswer.NONE
    }

    /** 예로 시작하는 말인가(옛 규칙과 같은 표. "네비"는 예가 아님) */
    private fun startsYes(t: String): Boolean =
        t.startsWith("예") || t.startsWith("응") || t.startsWith("넵") || (t.startsWith("네") && !t.startsWith("네비")) || t.startsWith("내")

    /**
     * r5~r9 옛 규칙([STRICT_ENABLED] = false일 때만).
     * - 빈 말·너무 긴 말 → NONE
     * - 짧은 예("예·네·응" 등) 정확 일치 → ARRIVED
     * - "도착"이 있고 아니요 표지가 없음 → ARRIVED ("도착했어요", "네 도착했어요")
     * - 아니요 표지가 있음 → NOT_YET ("아니요", "아직", "잠깐 멈췄어요", "아직 도착 안 했어요")
     *   단, 예로 시작하면서 아니요 표지도 있으면 섞인 말로 보고 NONE("네 아니요")
     * - 그 밖 → NONE
     */
    fun decideLegacy(text: String?): ArrivalAnswer {
        val t = normalize(text ?: return ArrivalAnswer.NONE)
        if (t.isEmpty() || t.length > MAX_LEN) return ArrivalAnswer.NONE
        if (t in YES_SHORT) return ArrivalAnswer.ARRIVED
        val hasNo = NO_MARKS.any { t.contains(it) }
        val startsYes = t.startsWith("예") || t.startsWith("응") || t.startsWith("넵") || (t.startsWith("네") && !t.startsWith("네비")) || t.startsWith("내")
        if (hasNo) return if (startsYes) ArrivalAnswer.NONE else ArrivalAnswer.NOT_YET
        if (t.contains(ARRIVE_MARK)) return ArrivalAnswer.ARRIVED
        return ArrivalAnswer.NONE
    }

    /** r6 5절: "내릴까요?" 아니요 표지(도착 질문 표지 + 계속 가요·안 내려요) */
    val ALIGHT_NO_MARKS = NO_MARKS + listOf("계속", "안내려", "안내릴", "그냥가")
    /** r6 5절: "내릴까요?" 예 표지("내려요", "내릴게요") */
    val ALIGHT_YES_MARKS = listOf("내려", "내릴")

    /**
     * r6 5절(개발 A, 개발 B 규칙 일반화): "내릴까요?" 음성 대답. 반환은 같은 enum([ArrivalAnswer.ARRIVED] = 예, [ArrivalAnswer.NOT_YET] = 아니요).
     * - 짧은 예(예·네·응 등) → 예 / "내려요"·"내릴게요" → 예 / "아니요"·"계속 가요"·"안 내려요" → 아니요
     * - 예로 시작하면서 아니요 표지도 있으면 섞인 말로 보고 NONE. 그 밖 NONE(버튼만)
     */
    fun decideAlight(text: String?): ArrivalAnswer {
        val t = normalize(text ?: return ArrivalAnswer.NONE)
        if (t.isEmpty() || t.length > MAX_LEN) return ArrivalAnswer.NONE
        if (t in YES_SHORT) return ArrivalAnswer.ARRIVED
        val hasNo = ALIGHT_NO_MARKS.any { t.contains(it) }
        val startsYes = t.startsWith("예") || t.startsWith("응") || t.startsWith("넵") || (t.startsWith("네") && !t.startsWith("네비"))
        if (hasNo) return if (startsYes) ArrivalAnswer.NONE else ArrivalAnswer.NOT_YET
        if (ALIGHT_YES_MARKS.any { t.contains(it) }) return ArrivalAnswer.ARRIVED
        return ArrivalAnswer.NONE
    }

    /**
     * 듣기를 시작해도 되나. 하나라도 아니면 버튼만(호출자가 로그 한 줄).
     * [answerEnabled] = 설정 '음성으로 답하기', [sttReady] = STT 초기화 성공, [questionSpoken] = 질문 음성이 끝까지 나감(DONE),
     * [stillAsking] = 아직 버튼 대답 전, [parkAllowed] = 유효 P + 플랫폼 허용, [naviBusy] = 내비 안내 음성 중·직후
     */
    fun listenBlock(answerEnabled: Boolean, sttReady: Boolean, questionSpoken: Boolean, stillAsking: Boolean,
                    parkAllowed: Boolean, naviBusy: Boolean): String? = when {
        !stillAsking -> "이미 대답함"
        !answerEnabled -> "설정에서 음성 대답 꺼짐"
        !sttReady -> "음성 인식 준비 안 됨"
        !questionSpoken -> "질문 음성이 끝까지 나가지 않음"
        !parkAllowed -> "유효 P 아님"
        naviBusy -> "내비 안내 음성 중"
        else -> null
    }
}
