package com.crewguide.probe

// 아이 프로필 3단계: 아이용 문구의 말투·호칭 조합 순수 로직(Android 클래스 없음). 문구는 모두 가안이다.
// 호칭은 아이 안내 문구에만 붙인다. 예시 안내·주행 벨트 안내는 말투만 적용한다.

object ChildNotice {
    /** 호칭을 붙인 TTS 한 문장의 길이 상한(코드포인트, 40자 안팎). 넘으면 호칭을 총칭으로 줄이고, 그래도 넘으면 호칭을 뺀다 */
    const val MAX_SENTENCE_CODEPOINTS = 40

    /** 선택된 아이가 있으면 그 아이들의 말투(A가 하나라도 있으면 A), 없으면 기기 단위 말투 */
    fun effectiveTone(selected: List<ChildProfile>, deviceTone: Tone): Tone =
        if (selected.isEmpty()) deviceTone else ChildSelections.selectedTone(selected)

    private fun len(s: String) = s.codePointCount(0, s.length)

    private fun fit(greeting: String, sentence: String, nicknames: List<String>): String {
        val full = Greeting.compose(greeting, sentence)
        if (len(full) <= MAX_SENTENCE_CODEPOINTS) return full
        val n = nicknames.count { it.isNotBlank() }
        val generic = when { n <= 0 -> ""; n == 1 -> Greeting.SINGLE; else -> Greeting.PLURAL }
        val g = Greeting.compose(generic, sentence)
        // PM 결정 27: 정중한 총칭("어린이 승객님" 등)은 예전 총칭보다 길어 총칭으로도 상한을 넘을 수 있다 → 그때는 호칭 없이 문장만(상한 40자 유지)
        return if (len(g) <= MAX_SENTENCE_CODEPOINTS) g else sentence
    }

    /** 화면용(말풍선·보호자 줄) 아이 안내: 호칭 + 말투별 문구. 선택이 없으면 호칭 없음 */
    fun screenText(tone: Tone, mode: AddressMode, nicknames: List<String>): String {
        val sentence = ToneSetting.text(tone, ToneKind.CHILD_NOTICE)
        if (nicknames.isEmpty()) return sentence
        return fit(Greeting.greeting(mode, nicknames), sentence, nicknames)
    }

    /** 음성(TTS)용 아이 안내. 별명 TTS 읽기가 꺼져 있으면 별명이 들어가지 않는다 */
    fun spokenText(tone: Tone, mode: AddressMode, nicknames: List<String>, speakNicknames: Boolean): String {
        val sentence = ToneSetting.text(tone, ToneKind.CHILD_NOTICE)
        if (nicknames.isEmpty()) return sentence
        return fit(Greeting.spokenGreeting(mode, nicknames, speakNicknames), sentence, nicknames)
    }

    /** 예시 안내(호칭 없음, 말투만) */
    fun exampleText(tone: Tone): String = ToneSetting.text(tone, ToneKind.EXAMPLE)

    /** 주행 벨트 안내(호칭 없음, 말투만) */
    fun driveText(tone: Tone): String = ToneSetting.text(tone, ToneKind.DRIVE)
}
