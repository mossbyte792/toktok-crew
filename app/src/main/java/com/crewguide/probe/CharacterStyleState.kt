package com.crewguide.probe

/**
 * 꾸미기 상태의 단일 보관소(순수 로직). ViewModel 역할: 꾸미기 화면이 [set]으로 바꾸면 홈·무대·꾸미기 화면이
 * 구독([observe])한 콜백으로 바로 받는다. 이 앱은 androidx lifecycle(ViewModel)을 쓰지 않아 같은 구조를 직접 둔다.
 * - 같은 값을 다시 넣으면 알리지 않는다.
 * - [observe]는 구독 즉시 현재 값을 한 번 알린다(나중에 만들어진 화면도 최신 모습으로 시작).
 * - 반환된 함수를 부르면 구독이 풀린다(화면이 사라질 때 호출).
 * 메인 스레드에서만 쓴다.
 */
class CharacterStyleState(initial: CharacterStyle = CharacterStyle.DEFAULT) {
    var style: CharacterStyle = CharacterOptions.sanitize(initial)
        private set
    private val listeners = ArrayList<(CharacterStyle) -> Unit>()

    /** 바뀌었으면 true. 범위 밖 값은 보정한 뒤 비교한다 */
    fun set(next: CharacterStyle): Boolean {
        val fixed = CharacterOptions.sanitize(next)
        if (fixed == style) return false
        style = fixed
        for (l in listeners.toList()) l(fixed)   // 알리는 중에 구독이 바뀌어도 안전하게 복사본으로 돈다
        return true
    }

    fun observe(listener: (CharacterStyle) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(style)
        return { listeners.remove(listener) }
    }

    val observerCount: Int get() = listeners.size
}
