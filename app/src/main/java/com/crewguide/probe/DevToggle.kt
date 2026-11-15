package com.crewguide.probe

/** 개발용 영역 토글 줄 문구(가안). 펼침 상태는 화면 메모리에만 두고 저장하지 않는다 */
object DevToggle {
    const val TITLE = "개발용 보기(시연·점검용, 가안)"
    fun label(open: Boolean): String = (if (open) "▼ " else "▶ ") + TITLE
}
