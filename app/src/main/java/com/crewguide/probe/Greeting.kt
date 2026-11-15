package com.crewguide.probe

// 아이 프로필 1b: 호칭 문장 순수 로직(Android 클래스 없음, 화면 연결 없음).
// 별명 TTS 읽기는 기본 꺼짐이며, 꺼져 있으면 음성용 호칭에 별명을 넣지 않는다.
// 2026-11-14 PM 결정 27(사용자 지시): 승무원 컨셉에 맞게 정중한 해요체 호칭. 반말 호칭(친구야·얘들아·~아/~야) 금지.
// 호칭·환영 문구는 이 파일의 상수 한 곳에서만 정한다(표현을 바꾸려면 [SINGLE]·[PLURAL]·[NAME_SUFFIX]·[WELCOME_BODY]만 고친다).

/** 호칭 방식. 기본 GENERIC */
enum class AddressMode { NAMES, GENERIC, NONE }

object Greeting {
    val DEFAULT_MODE = AddressMode.GENERIC
    const val MAX_CODEPOINTS = 20
    /** 총칭(여럿) */
    const val PLURAL = "어린이 승객 여러분"
    /** 총칭(한 명, 기본값: 별명 읽기 꺼짐·별명 없음) */
    const val SINGLE = "어린이 승객님"
    /** 별명 호칭 꼬리: "하늘" → "하늘 승객님"(받침 처리 없음) */
    const val NAME_SUFFIX = " 승객님"
    /** 탑승 환영 본문(호칭 뒤). VoiceLines.welcome이 쓴다 */
    const val WELCOME_BODY = "탑승을 환영해요! 오늘도 톡톡크루와 함께 가요."

    /** 별명 한 명의 호칭 */
    fun named(nick: String): String = nick.trim() + NAME_SUFFIX

    /**
     * 받침 판정: 마지막 글자가 한글 음절(가~힣)이면 (코드 - 0xAC00) % 28 != 0 일 때 받침 있음(ㄹ받침 포함).
     * 한글이 아닌 글자(영문·숫자 등)는 받침 없음으로 처리한다.
     * 결정 27 뒤 호칭에는 쓰지 않는다('승객님'은 받침과 무관). 다른 조사 처리용으로 남긴다.
     */
    fun hasJongseong(c: Char): Boolean =
        c in '가'..'힣' && (c.code - 0xAC00) % 28 != 0

    /** 문자열 마지막 글자 기준. 빈 문자열은 받침 없음 */
    fun hasJongseong(s: String?): Boolean =
        if (s.isNullOrEmpty()) false else hasJongseong(s.last())

    private fun generic(count: Int): String = when {
        count <= 0 -> ""
        count == 1 -> SINGLE
        else -> PLURAL
    }

    /** 호칭 만들기(결정 27). 한 명 "하늘 승객님", 두 명 "하늘 승객님, 바다 승객님", 세 명 이상 총칭. 길이 상한(20 코드포인트) 초과 시 총칭으로 대체 */
    fun greeting(mode: AddressMode, nicknames: List<String>): String {
        try {
            val names = nicknames.filter { it.isNotBlank() }
            val n = names.size
            val text = when (mode) {
                AddressMode.NONE -> ""
                AddressMode.GENERIC -> generic(n)
                AddressMode.NAMES -> when {
                    n == 0 -> ""
                    n == 1 -> named(names[0])
                    n == 2 -> "${named(names[0])}, ${named(names[1])}"
                    else -> PLURAL
                }
            }
            return if (text.codePointCount(0, text.length) > MAX_CODEPOINTS) generic(n) else text
        } catch (e: Exception) {
            return ""
        }
    }

    /** greeting이 비면 sentence 그대로, 아니면 "$greeting, $sentence" */
    fun compose(greeting: String, sentence: String): String =
        if (greeting.isEmpty()) sentence else "$greeting, $sentence"

    /**
     * 음성(TTS)용 호칭. NAMES이고 speakNicknames가 꺼져 있으면 별명을 읽지 않고 총칭으로 대체한다.
     * GENERIC·NONE은 설정과 무관하다.
     */
    fun spokenGreeting(mode: AddressMode, nicknames: List<String>, speakNicknames: Boolean): String {
        if (mode == AddressMode.NAMES && !speakNicknames) {
            val n = try { nicknames.count { it.isNotBlank() } } catch (e: Exception) { 0 }
            return generic(n)
        }
        return greeting(mode, nicknames)
    }
}

fun hasJongseong(c: Char): Boolean = Greeting.hasJongseong(c)
fun greeting(mode: AddressMode, nicknames: List<String>): String = Greeting.greeting(mode, nicknames)
fun compose(greeting: String, sentence: String): String = Greeting.compose(greeting, sentence)
fun spokenGreeting(mode: AddressMode, nicknames: List<String>, speakNicknames: Boolean): String =
    Greeting.spokenGreeting(mode, nicknames, speakNicknames)
