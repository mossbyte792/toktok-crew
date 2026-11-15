package com.crewguide.probe

import android.content.Context

/** 저장할 선택 설정. 기본: generic, 읽기 꺼짐, 빈 조합 */
data class SelectionSettings(
    val mode: AddressMode = AddressMode.GENERIC,
    val speakNicknames: Boolean = false,
    val last: ChildSelection = ChildSelection(),
)

/**
 * 저장 형식(고정 s1): "s1;m=<names|generic|none>;v=<0|1>;c=<id를 '-'로 연결, 없으면 빈 값>"
 * 예: "s1;m=names;v=0;c=0-2". 순수 로직(Android 클래스 없음).
 * 저장 항목은 호칭 방식, 별명 TTS 읽기 여부, 마지막 조합(id)뿐이다.
 */
object SelectionCodec {
    const val VERSION = "s1"
    const val MAX_LENGTH = 512

    fun encode(s: SelectionSettings): String = try {
        val mode = when (s.mode) { AddressMode.NAMES -> "names"; AddressMode.GENERIC -> "generic"; AddressMode.NONE -> "none" }
        val ids = ChildSelections.normalize(s.last.ids).ids.joinToString("-")
        "$VERSION;m=$mode;v=${if (s.speakNicknames) 1 else 0};c=$ids"
    } catch (e: Exception) {
        "$VERSION;m=generic;v=0;c="
    }

    /** 어떤 입력이든 예외 없이 보정해 돌려준다. 전체가 비정상이면 기본값 */
    fun decode(text: String?): SelectionSettings {
        try {
            if (text.isNullOrEmpty() || text.length > MAX_LENGTH) return SelectionSettings()
            val parts = text.split(';')
            if (parts[0] != VERSION) return SelectionSettings()
            var mode = AddressMode.GENERIC
            var speak = false
            var last = ChildSelection()
            for (p in parts.drop(1)) {
                when {
                    p.startsWith("m=") -> mode = when (p.substring(2)) {
                        "names" -> AddressMode.NAMES
                        "none" -> AddressMode.NONE
                        else -> AddressMode.GENERIC
                    }
                    p.startsWith("v=") -> speak = p.substring(2) == "1"
                    p.startsWith("c=") -> last = decodeIds(p.substring(2))
                }
            }
            return SelectionSettings(mode, speak, last)
        } catch (e: Exception) {
            return SelectionSettings()
        }
    }

    // 범위 밖 id·중복·5개 이상·숫자 아닌 값이 있으면 조합 전체를 빈 조합으로 되돌린다
    private fun decodeIds(raw: String): ChildSelection {
        if (raw.isEmpty()) return ChildSelection()
        val toks = raw.split('-')
        if (toks.size > ChildSelections.MAX_SELECTED) return ChildSelection()
        val ids = ArrayList<Int>()
        for (t in toks) {
            if (t.length != 1 || t[0] !in '0'..'3') return ChildSelection()
            val id = t.toInt()
            if (ids.contains(id)) return ChildSelection()
            ids.add(id)
        }
        return ChildSelections.normalize(ids)
    }
}

/**
 * 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 문자열 하나만 저장한다.
 * 이름·사진·생년월일·음성·위치·식별자는 저장하지 않는다. 실패하면 기본값.
 * 지우는 방법: clearAll(context) 또는 앱 데이터 삭제.
 */
object SelectionStore {
    const val FILE_NAME = "crewguide_probe_selection"
    const val KEY_SETTINGS = "selection_v1"

    fun load(context: Context): SelectionSettings = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        SelectionCodec.decode(prefs.getString(KEY_SETTINGS, null))
    } catch (e: Exception) {
        SelectionSettings()
    }

    fun save(context: Context, settings: SelectionSettings) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SETTINGS, SelectionCodec.encode(settings)).apply()
        } catch (e: Exception) {
            // 저장 실패는 화면 흐름에 영향을 주지 않는다
        }
    }

    fun clearAll(context: Context) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY_SETTINGS).apply()
        } catch (e: Exception) {
            // 무시
        }
    }
}
