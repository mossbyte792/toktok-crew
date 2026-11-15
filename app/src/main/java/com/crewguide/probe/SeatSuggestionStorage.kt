package com.crewguide.probe

import android.content.Context

/** 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 지난번에 고른 좌석 이름만 저장한다(시간·위치·식별자·이름 없음). 실패하면 빈 집합 */
object SeatSuggestionStore {
    const val FILE_NAME = "crewguide_probe_seat_suggestion"
    const val KEY_SEATS = "last_seats_v1"

    fun load(context: Context): Set<String> = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        SeatSuggestion.decode(prefs.getString(KEY_SEATS, null))
    } catch (e: Exception) {
        emptySet()
    }

    fun save(context: Context, seats: Set<String>) {
        try {
            val encoded = SeatSuggestion.encode(seats)
            val edit = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            if (encoded.isEmpty()) edit.remove(KEY_SEATS) else edit.putString(KEY_SEATS, encoded)
            edit.apply()
        } catch (e: Exception) {
            // 저장 실패는 화면 흐름에 영향을 주지 않는다
        }
    }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY_SEATS).apply()
        } catch (e: Exception) {
            // 삭제 실패는 화면 흐름에 영향을 주지 않는다
        }
    }
}
