package com.crewguide.probe

// 풍선 글자의 공백 기준 줄바꿈(순수 함수, Android 클래스 없음). measure는 호출자가 Paint.measureText를 넘긴다.
// 규칙: 공백(' ')으로 단어를 나누고 탐욕적으로 한 줄에 채운다. 문장 부호는 앞 단어에 붙어 있으므로 별도 처리 없음.
// 한 단어가 한 줄 폭보다 길면 그 단어만 글자 단위로 쪼갠다(안전 장치). maxLines를 넘으면 마지막 줄 끝을 '…'로 줄이고 truncated=true.
// 롤백: 이 파일을 삭제하고 StageScreen의 풍선 글자를 TextView 자동 줄바꿈으로 되돌린다.

data class WrapResult(val lines: List<String>, val truncated: Boolean)

object BubbleWrap {
    const val ELLIPSIS = "…"
}

fun wrapByWords(text: String, maxWidthPx: Float, measure: (String) -> Float, maxLines: Int): WrapResult {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return WrapResult(emptyList(), false)
    val limit = maxLines.coerceAtLeast(1)
    val lines = ArrayList<String>()
    var cur = ""
    for (word in words) {
        if (measure(word) > maxWidthPx) {
            // 긴 단어: 앞 줄을 마감하고 글자 단위로 쪼갠다(한 조각은 최소 한 글자)
            if (cur.isNotEmpty()) { lines.add(cur); cur = "" }
            var piece = ""
            for (ch in word) {
                val next = piece + ch
                if (piece.isNotEmpty() && measure(next) > maxWidthPx) { lines.add(piece); piece = ch.toString() }
                else piece = next
            }
            cur = piece
        } else {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isEmpty() || measure(candidate) <= maxWidthPx) cur = candidate
            else { lines.add(cur); cur = word }
        }
    }
    if (cur.isNotEmpty()) lines.add(cur)
    if (lines.size <= limit) return WrapResult(lines, false)
    val kept = ArrayList(lines.subList(0, limit))
    var last = kept[limit - 1].trimEnd()
    while (last.isNotEmpty() && measure(last + BubbleWrap.ELLIPSIS) > maxWidthPx) last = last.dropLast(1).trimEnd()
    kept[limit - 1] = last + BubbleWrap.ELLIPSIS
    return WrapResult(kept, true)
}
