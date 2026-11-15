package com.crewguide.probe

// 가안(원문 대조 전): 이 파일의 아이용 문구는 모두 가안이다. 공식 교육 자료·차종·장구 설명서 확인 후 확정한다.
// Android·SDK 클래스를 쓰지 않는 순수 로직이다. 연령·이름·생년월일은 받지 않고 저장하지 않는다.
// 말투 A는 쉬운 말(6~8세 가정), B는 조금 자세히(9~12세 가정)이며 보호자가 직접 고른다. 연령을 묻거나 저장하지 않는다.
// 보호자에게 하는 말(ANNOUNCE_TEXT·QUESTION_TEXT)은 여기에 두지 않는다.

/** 아이에게 들려주는 문구 종류 */
enum class ToneKind { CHILD_NOTICE, EXAMPLE, DRIVE }

/** 말투. 기본값 A */
enum class Tone { A, B }

object ToneSetting {
    val DEFAULT: Tone = Tone.A

    // ---- 가안 문구(원문 대조 전). 각 한 문장, 짧게 ----
    // A는 사용자 확정 문구(UserTexts). B는 사용자 확정 전 가안: A 뒤에 짧은 문장을 덧붙인 형태
    const val CHILD_NOTICE_A = UserTexts.CHILD_NOTICE_A
    const val CHILD_NOTICE_B = UserTexts.CHILD_NOTICE_A + ". 어른과 함께 한 번 더 봐요."
    const val EXAMPLE_A = "안내자 목소리 예시예요"
    const val EXAMPLE_B = "지금 들리는 것은 안내자 목소리 예시예요"
    const val DRIVE_A = "벨트 신호가 바뀐 것 같아요. 어른과 함께 확인해 주세요."
    // 기존 가안 문구(BeltReleaseRule.TEXT)를 B로 쓴다
    const val DRIVE_B = BeltReleaseRule.TEXT

    const val STORE_A = "a"
    const val STORE_B = "b"

    /** 저장용 한 글자. A→"a", B→"b" */
    fun encode(tone: Tone): String = if (tone == Tone.B) STORE_B else STORE_A

    /** 복원. "b"만 B, null·빈 문자열·알 수 없는 값은 A */
    fun decode(raw: String?): Tone = if (raw == STORE_B) Tone.B else DEFAULT

    /**
     * 2026-11-10 4차 결정 B: 실제로 쓸 말투. 저장값이 B라도 B가 허용되지 않은 빌드(release)에서는 A.
     * 저장값은 바꾸지 않는다(이 함수는 읽기 결과만 바꾼다). bAllowed는 BuildMockPolicy.TONE_B_ALLOWED.
     */
    fun runtime(stored: Tone, bAllowed: Boolean): Tone = if (stored == Tone.B && !bAllowed) Tone.A else stored

    /** 말투와 문구 종류로 문구를 고른다(호출 시점마다 선택) */
    fun text(tone: Tone, kind: ToneKind): String = when (kind) {
        ToneKind.CHILD_NOTICE -> if (tone == Tone.B) CHILD_NOTICE_B else CHILD_NOTICE_A
        ToneKind.EXAMPLE -> if (tone == Tone.B) EXAMPLE_B else EXAMPLE_A
        ToneKind.DRIVE -> if (tone == Tone.B) DRIVE_B else DRIVE_A
    }
}
