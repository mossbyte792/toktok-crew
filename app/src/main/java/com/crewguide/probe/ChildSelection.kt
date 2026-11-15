package com.crewguide.probe

// 아이 프로필 1b: '누가 탔나요?' 복수 선택 조합 순수 로직(Android 클래스 없음, 화면 연결 없음).
// 모든 연산은 불변이며 예외를 던지지 않는다. 좌석-아이 자동 연결은 하지 않는다.

/** 선택 조합. ids는 프로필 id(0~3), 중복 없음, 최대 4, 오름차순 */
data class ChildSelection(val ids: List<Int> = emptyList()) {
    val size: Int get() = ids.size
    fun isEmpty(): Boolean = ids.isEmpty()
    fun contains(id: Int): Boolean = ids.contains(id)
}

object ChildSelections {
    const val MAX_SELECTED = 4

    private fun valid(id: Int) = id in 0 until MAX_SELECTED

    /** 입력을 보정해 조합으로 만든다(범위 밖·중복 제거, 최대 4) */
    fun normalize(ids: List<Int>?): ChildSelection {
        try {
            if (ids == null) return ChildSelection()
            return ChildSelection(ids.filter { valid(it) }.distinct().sorted().take(MAX_SELECTED))
        } catch (e: Exception) {
            return ChildSelection()
        }
    }

    /** 없으면 추가, 있으면 제거. book에 없는 id는 무시(변경 없음) */
    fun toggle(selection: ChildSelection, id: Int, book: ProfileBook): ChildSelection {
        try {
            if (!valid(id) || book.find(id) == null) return selection
            return if (selection.contains(id)) {
                ChildSelection(selection.ids.filter { it != id })
            } else {
                if (selection.size >= MAX_SELECTED) selection
                else ChildSelection((selection.ids + id).distinct().sorted())
            }
        } catch (e: Exception) {
            return selection
        }
    }

    /** book에 없어진 프로필을 조합에서 제거 */
    fun pruneDeleted(selection: ChildSelection, book: ProfileBook): ChildSelection {
        try {
            return ChildSelection(selection.ids.filter { book.find(it) != null })
        } catch (e: Exception) {
            return ChildSelection()
        }
    }

    /** 1명 이상이면 시작 가능 */
    fun canStart(selection: ChildSelection): Boolean = selection.ids.isNotEmpty()

    /** 현재 선택을 마지막 조합으로 기록(보정된 id 목록) */
    fun recordLast(selection: ChildSelection): ChildSelection = normalize(selection.ids)

    /** 마지막 조합이 비어 있지 않고 현재 book에 모두 있을 때만 강조 */
    fun shouldHighlightLast(last: ChildSelection, book: ProfileBook): Boolean = try {
        last.ids.isNotEmpty() && last.ids.all { book.find(it) != null }
    } catch (e: Exception) {
        false
    }

    /** 선택된 id에 해당하는 프로필 목록(id 순) */
    fun profilesOf(selection: ChildSelection, book: ProfileBook): List<ChildProfile> = try {
        selection.ids.mapNotNull { book.find(it) }
    } catch (e: Exception) {
        emptyList()
    }

    /** 선택된 아이들의 말투 결정. A가 하나라도 있으면 A, 모두 B면 B, 비면 A. 나이는 쓰지 않는다 */
    fun selectedTone(profiles: List<ChildProfile>): Tone =
        if (profiles.isNotEmpty() && profiles.all { it.tone == Tone.B }) Tone.B else Tone.A

    /** '아이 N명 · 확인 좌석 M석'. 인원이 달라도 경고하지 않는다. 0명이면 안내 문구 */
    fun summaryLabel(childCount: Int, seatCount: Int): String {
        if (childCount <= 0) return "아이를 먼저 골라 주세요"
        return "아이 ${childCount}명 · 확인 좌석 ${seatCount.coerceAtLeast(0)}석"
    }
}

fun selectedTone(profiles: List<ChildProfile>): Tone = ChildSelections.selectedTone(profiles)
fun summaryLabel(childCount: Int, seatCount: Int): String = ChildSelections.summaryLabel(childCount, seatCount)
