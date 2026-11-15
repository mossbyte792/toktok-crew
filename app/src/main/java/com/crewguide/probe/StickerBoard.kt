package com.crewguide.probe

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * UI/UX v3 S05(TTC-S05-STICKER-BOARD): 이번 주 요일별 도장판(순수 로직, Android 클래스 없음).
 *
 * - 데이터는 기존 [StickerLog]만 읽는다(새 저장 항목 없음, 28일 보관·세션당 1회 지급 규칙 그대로).
 * - 이번 주 = 오늘이 속한 월~일 7칸. 다른 주 기록은 이 판에 넣지 않는다(7칸에 28일을 압축하지 않음).
 * - 별 기호·숫자만으로 전달하지 않고 글자(“참여 N번”/“아직 없어요”/“오늘”)를 함께 쓴다.
 * - 스티커는 보호자 확인 **참여 기록**이다. 벨트 착용·안전을 인증하는 표현을 쓰지 않는다. 순위·연속·비교 표현도 쓰지 않는다.
 */
data class BoardCell(
    val date: String,
    val dayIndex: Int,        // 0=월 … 6=일
    val count: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

object StickerBoard {
    val DAY_SHORT = listOf("월", "화", "수", "목", "금", "토", "일")

    const val FUTURE_TEXT = "–"
    const val EMPTY_TEXT = "아직 없어요"
    const val TODAY_TAG = "오늘"
    const val EMPTY_BOARD_NOTE = "보호자 확인에 함께하면 그날 칸에 별이 생겨요."
    const val MEANING_NOTE = "별은 보호자 확인에 참여한 기록이에요. 벨트 착용을 확인한 표시가 아니에요."

    private fun parse(s: String?): LocalDate? {
        if (s == null || s.length != 10) return null
        return try { LocalDate.parse(s).takeIf { it.toString() == s } } catch (e: DateTimeParseException) { null }
    }

    /** 오늘이 속한 주의 7칸. 오늘 날짜가 잘못되면 빈 목록(화면은 빈 판 설명만 보여 준다) */
    fun week(log: StickerLog, id: Int, today: String): List<BoardCell> {
        val t = parse(today) ?: return emptyList()
        val monday = t.minusDays((t.dayOfWeek.value - 1).toLong())
        val days = log.counts[id] ?: emptyMap()
        return (0 until 7).map { i ->
            val d = monday.plusDays(i.toLong())
            val ds = d.toString()
            val future = d.isAfter(t)
            BoardCell(ds, i, if (future) 0 else (days[ds] ?: 0).coerceAtLeast(0), d == t, future)
        }
    }

    fun weekTotal(cells: List<BoardCell>): Int = cells.sumOf { it.count }

    fun earned(c: BoardCell): Boolean = !c.isFuture && c.count > 0

    /** 칸 큰 기호: 받은 날 ★, 아직 없는 지난날·오늘 ○, 앞으로 올 날 – */
    fun glyph(c: BoardCell): String = when {
        c.isFuture -> FUTURE_TEXT
        c.count > 0 -> "★"
        else -> "○"
    }

    /** 칸 아래 글자 */
    fun countText(c: BoardCell): String = when {
        c.isFuture -> ""
        c.count > 0 -> "참여 ${c.count}번"
        else -> EMPTY_TEXT
    }

    /** 요일 머리글. 오늘은 “오늘”을 덧붙인다(색·굵기만으로 표시하지 않음) */
    /**
     * v3(2026-11-11): 칸이 좁을 때(글꼴 2.0 등) 낱말 중간에서 줄이 바뀌지 않게, 한 줄에 안 들어가면 '·'·띄어쓰기 자리에서 미리 나눈다.
     * 예: "화·오늘" → "화" / "오늘", "아직 없어요" → "아직" / "없어요". 들어가면 그대로.
     */
    fun wrapAtWords(text: String, fullWidthPx: Float, availPx: Float): String =
        if (availPx <= 0f || fullWidthPx <= availPx) text else text.replace("·", "\n").replace(" ", "\n")

    fun dayLabel(c: BoardCell): String = if (c.isToday) "${DAY_SHORT[c.dayIndex]}·$TODAY_TAG" else DAY_SHORT[c.dayIndex]

    /** 접근성 이름: “수요일 11월 12일, 오늘, 참여 2번” */
    fun a11y(c: BoardCell): String {
        val d = parse(c.date)
        val md = if (d != null) "${d.monthValue}월 ${d.dayOfMonth}일" else c.date
        val state = when {
            c.isFuture -> "아직 오지 않은 날"
            c.count > 0 -> "참여 ${c.count}번"
            else -> "참여 기록 없음"
        }
        return "${DAY_SHORT[c.dayIndex]}요일 $md${if (c.isToday) ", $TODAY_TAG" else ""}, $state"
    }

    /** 판 아래 짧은 문구(격려, 비교·순위 없음) */
    fun footer(total: Int): String = if (total <= 0) EMPTY_BOARD_NOTE else "이번 주 ${total}번 함께 확인했어요."
}
