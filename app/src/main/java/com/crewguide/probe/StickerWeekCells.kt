package com.crewguide.probe

/**
 * 2026-11-13: 무대 스티커판(StageScreen.setStickerWeek, 디자인 담당)에 넘길 이번 주 7칸(순수 로직).
 * 여러 아이를 골랐으면 아이별 [StickerBoard.week] 칸을 날짜별로 더한다. 아이를 고르지 않았으면 등록된 아이 전체를 더한다.
 * 기록은 기존 [StickerLog]만 읽는다(새 저장 항목 없음).
 */
object StickerWeekCells {
    fun combined(log: StickerLog, ids: Collection<Int>, today: String): List<BoardCell> {
        val weeks = ids.distinct().map { StickerBoard.week(log, it, today) }.filter { it.isNotEmpty() }
        if (weeks.isEmpty()) return StickerBoard.week(log, Int.MIN_VALUE, today)   // 아이 없음: 빈 7칸(날짜·오늘 표시는 유지)
        return weeks.first().indices.map { i -> weeks.first()[i].copy(count = weeks.sumOf { it[i].count }) }
    }
}
