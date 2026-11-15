package com.crewguide.probe

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 아이별 출발 전 확인 스티커 기록(순수 로직, Android 클래스 없음, 시연용 가안).
 * 보호자 보고 기준이며 앱이 확인한 것이 아니다. 저장 항목은 프로필 id, 날짜(yyyy-MM-dd), 개수뿐이다.
 * 날짜는 호출자가 문자열로 넘긴다(이 파일은 현재 시각·위치를 읽지 않는다).
 */
data class StickerGrant(val ids: Set<Int>, val date: String)

/**
 * @param counts 프로필 id → (날짜 → 개수). 개수는 1~999만 남긴다
 * @param last 마지막 부여(되돌리기용, 메모리에서만 기억하고 저장하지 않는다)
 * @param lastSessionKey 마지막으로 부여를 시도해 처리한 세션 키(같은 세션 중복 방지, 저장하지 않는다)
 */
data class StickerLog(
    val counts: Map<Int, Map<String, Int>> = emptyMap(),
    val last: StickerGrant? = null,
    val lastSessionKey: Int? = null,
)

data class DayCount(val date: String, val count: Int)

/** 이번 주 합계와 날짜별 개수(날짜 오름차순, 개수 0인 날은 없음) */
data class WeekSummary(val total: Int, val days: List<DayCount>)

object StickerLogs {
    const val MIN_ID = 0
    const val MAX_ID = 3
    const val MAX_COUNT = 999
    const val KEEP_DAYS = 28L
    const val MAX_ENTRIES = 200
    const val MAX_LENGTH = 4096
    const val VERSION = "k1"

    // 화면 문구(가안). 순위·비교·연속·경쟁 표현은 쓰지 않는다
    const val SUB_TITLE = "이번 주 스티커(가안)"
    const val NOTE = "앱 안에서만 보여요. 이 기기에만 저장돼요."
    const val EMPTY_NOTE = "등록한 아이가 생기면 여기에 나와요."
    const val UNDO_BTN = "방금 스티커 취소"
    const val CLEAR_BTN = "스티커 기록 지우기"
    const val CLEAR_CONFIRM = "스티커 기록을 모두 지울까요?"

    fun cardCountText(total: Int): String = "스티커 ${total}개"
    fun grantNote(childCount: Int): String = "(아이 ${childCount}명에게 +1)"
    fun summaryLine(nickname: String, week: Int, today: Int): String = "$nickname · 이번 주 ${week}개 (오늘 ${today}개)"

    private val DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** yyyy-MM-dd 형식이고 실제 존재하는 날짜인지 */
    fun isValidDate(s: String?): Boolean = parse(s) != null

    private fun parse(s: String?): LocalDate? {
        if (s == null || !DATE_RE.matches(s)) return null
        return try {
            val d = LocalDate.parse(s)
            if (d.toString() == s) d else null
        } catch (e: DateTimeParseException) { null }
    }

    private fun validId(id: Int) = id in MIN_ID..MAX_ID

    /**
     * 선택된 각 프로필에 +1. 같은 세션 키에서는 두 번 부여하지 않는다(되돌린 뒤에도 같은 세션이면 다시 부여하지 않음).
     * 하루 한 아이당 개수 제한은 없다(세션당 1회 규칙만 따르며 표시 한도는 999).
     * 유효한 id가 없거나 날짜가 잘못되면 변경 없음.
     */
    fun grant(log: StickerLog, ids: Set<Int>, date: String, sessionKey: Int): StickerLog {
        if (log.lastSessionKey == sessionKey) return log
        val target = ids.filter { validId(it) }.toSet()
        if (target.isEmpty() || !isValidDate(date)) return log
        val next = log.counts.toMutableMap()
        for (id in target) {
            val days = (next[id] ?: emptyMap()).toMutableMap()
            days[date] = minOf(MAX_COUNT, (days[date] ?: 0) + 1)
            next[id] = days
        }
        return StickerLog(next, StickerGrant(target, date), sessionKey)
    }

    /** 마지막 부여를 한 번만 되돌린다. 부여가 없으면 변경 없음. 이미 지운 프로필은 건너뛴다 */
    fun undoLast(log: StickerLog): StickerLog {
        val g = log.last ?: return log
        val next = log.counts.toMutableMap()
        for (id in g.ids) {
            val days = next[id]?.toMutableMap() ?: continue
            val v = (days[g.date] ?: 0) - 1
            if (v > 0) days[g.date] = v else days.remove(g.date)
            if (days.isEmpty()) next.remove(id) else next[id] = days
        }
        return StickerLog(next, null, log.lastSessionKey)
    }

    fun removeProfile(log: StickerLog, id: Int): StickerLog {
        val g = log.last?.let { l -> (l.ids - id).let { if (it.isEmpty()) null else l.copy(ids = it) } }
        return StickerLog(log.counts - id, g, log.lastSessionKey)
    }

    /** 모든 기록을 지운다(세션 중복 방지 값은 유지해 같은 세션에서 다시 쌓이지 않게 한다) */
    fun clearAll(log: StickerLog): StickerLog = StickerLog(lastSessionKey = log.lastSessionKey)

    fun totalCount(log: StickerLog, id: Int): Int = (log.counts[id] ?: emptyMap()).values.sum()

    fun todayCount(log: StickerLog, id: Int, today: String): Int = log.counts[id]?.get(today) ?: 0

    /**
     * 이번 주 = 월요일부터 일요일까지(오늘이 속한 주). 다른 주 기록은 제외한다.
     * 오늘 날짜가 잘못되면 빈 요약.
     */
    fun weekSummary(log: StickerLog, id: Int, today: String): WeekSummary {
        val t = parse(today) ?: return WeekSummary(0, emptyList())
        val monday = t.minusDays((t.dayOfWeek.value - 1).toLong())
        val sunday = monday.plusDays(6)
        val days = (log.counts[id] ?: emptyMap()).entries
            .mapNotNull { (d, c) -> parse(d)?.let { Triple(it, d, c) } }
            .filter { !it.first.isBefore(monday) && !it.first.isAfter(sunday) && it.third > 0 }
            .sortedBy { it.first }
            .map { DayCount(it.second, it.third) }
        return WeekSummary(days.sumOf { it.count }, days)
    }

    /** 오늘로부터 28일을 넘은 날짜 기록을 지운다(28일 전 날짜까지는 남김) */
    fun prune(log: StickerLog, today: String): StickerLog {
        val t = parse(today) ?: return log
        val limit = t.minusDays(KEEP_DAYS)
        val next = HashMap<Int, Map<String, Int>>()
        for ((id, days) in log.counts) {
            val kept = days.filter { (d, _) -> parse(d)?.let { !it.isBefore(limit) } ?: false }
            if (kept.isNotEmpty()) next[id] = kept
        }
        val last = log.last?.let { l -> if (l.ids.any { next[it]?.containsKey(l.date) == true }) l else null }
        return StickerLog(next, last, log.lastSessionKey)
    }

    /** 고정 형식 "k1;id,날짜,개수;id,날짜,개수..." 예: "k1;0,2026-11-06,3;2,2026-11-05,1". 기록이 없으면 "k1" */
    fun encodeLog(log: StickerLog): String = try {
        val parts = ArrayList<String>()
        for (id in log.counts.keys.filter { validId(it) }.sorted()) {
            for ((d, c) in log.counts[id]!!.toSortedMap()) {
                if (c in 1..MAX_COUNT && isValidDate(d)) parts.add("$id,$d,$c")
            }
        }
        (listOf(VERSION) + parts.take(MAX_ENTRIES)).joinToString(";")
    } catch (e: Exception) {
        VERSION
    }

    /** 앱 시작 때 읽은 결과의 처리. readFailed면 빈 로그로 표시하되 저장하지 않는다(원본 보존). 성공했을 때만 정리 후 바뀐 경우에만 저장 */
    data class StartupPlan(val log: StickerLog, val save: Boolean, val readFailed: Boolean)

    fun startup(loaded: StickerLog?, today: String): StartupPlan {
        if (loaded == null) return StartupPlan(StickerLog(), save = false, readFailed = true)
        val pruned = prune(loaded, today)
        return StartupPlan(pruned, save = pruned != loaded, readFailed = false)
    }

    /**
     * ChildProfilePanel.grantSticker의 판단(2026-11-08 F2·F5). save는 실제 저장을 시도하고 성공 여부를 돌려준다.
     * 반환: (새 메모리 로그, 결과). 결과 = 기록한 아이 수, 0 = 대상 없음·같은 세션, -1 = 저장 실패(메모리 불변), -2 = 읽기 실패 상태(저장 시도 안 함)
     */
    fun grantAndSave(log: StickerLog, ids: Set<Int>, date: String, sessionKey: Int, readFailed: Boolean, save: (StickerLog) -> Boolean): Pair<StickerLog, Int> {
        if (ids.isEmpty()) return log to 0
        if (readFailed) return log to GRANT_READ_FAILED
        val next = grant(log, ids, date, sessionKey)
        if (next === log) return log to 0
        if (!save(next)) return log to GRANT_SAVE_FAILED
        return next to next.last!!.ids.size
    }
    const val GRANT_SAVE_FAILED = -1
    const val GRANT_READ_FAILED = -2

    /** 2026-11-08 F4: 새 프로필에 번호를 줄 때 같은 번호의 남은 기록(삭제 때 저장이 빠진 경우)을 지운다. 기록이 없으면 같은 객체 */
    fun forNewProfile(log: StickerLog, id: Int): StickerLog =
        if (log.counts.containsKey(id) || log.last?.ids?.contains(id) == true) removeProfile(log, id) else log

    /** 어떤 입력이든 예외 없이 처리한다. 하나라도 비정상이면 전체를 빈 로그로 보정한다(표시용. 저장 여부 판단은 [decodeLogOrNull]) */
    fun decodeLog(text: String?): StickerLog = decodeLogOrNull(text) ?: StickerLog()

    /**
     * 2026-11-08 F5: 저장값이 없으면(null·빈 문자열) 빈 로그, 읽을 수 없으면(버전 불일치·손상·길이 초과) null.
     * null은 "기록 없음"이 아니라 "읽기 실패"다. 호출자는 이때 빈 로그를 저장해 원본을 지우면 안 된다.
     */
    fun decodeLogOrNull(text: String?): StickerLog? {
        try {
            if (text.isNullOrEmpty()) return StickerLog()
            if (text.length > MAX_LENGTH) return null
            val parts = text.split(';')
            if (parts[0] != VERSION) return null
            val entries = parts.drop(1)
            if (entries.size > MAX_ENTRIES) return null
            val out = HashMap<Int, MutableMap<String, Int>>()
            for (e in entries) {
                val f = e.split(',')
                if (f.size != 3) return null
                if (f[0].length != 1 || f[0][0] !in '0'..'3') return null
                val id = f[0].toInt()
                if (!isValidDate(f[1])) return null
                if (f[2].isEmpty() || f[2].length > 3 || !f[2].all { it in '0'..'9' }) return null
                val c = f[2].toInt()
                if (c !in 0..MAX_COUNT) return null
                val days = out.getOrPut(id) { HashMap() }
                if (days.containsKey(f[1])) return null
                if (c > 0) days[f[1]] = c
            }
            return StickerLog(out.filterValues { it.isNotEmpty() }.mapValues { it.value.toMap() })
        } catch (e: Exception) {
            return null
        }
    }
}
