package com.crewguide.probe

/**
 * 안내 음성(TTS) 사용 설정(순수 로직, Android 클래스 없음). 저장 항목은 켜짐/꺼짐 1비트뿐이다.
 * 저장 문자열: "on" / "off". 그 밖의 값·null·빈 문자열은 기본값(켜짐)으로 본다.
 */
object GuidanceSetting {
    const val DEFAULT_ENABLED = true
    const val ON = "on"
    const val OFF = "off"

    fun encode(enabled: Boolean): String = if (enabled) ON else OFF

    fun decode(text: String?): Boolean = when (text) {
        OFF -> false
        ON -> true
        else -> DEFAULT_ENABLED
    }

    /** 발화 허용 여부: 켜짐이면 허용, 꺼짐이면 불허 */
    fun speechAllowed(enabled: Boolean): Boolean = enabled
}
