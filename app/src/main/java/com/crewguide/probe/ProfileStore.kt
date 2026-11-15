package com.crewguide.probe

import android.content.Context

/**
 * 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 프로필 목록 문자열 하나만 저장한다.
 * 저장 항목: 별명, 꾸미기 6개 인덱스(+제복 요소), 말투, 이름 불러주기 여부, 카시트 켬/끔(r10 I, 0|1), 마지막 사용 id.
 * 실명·사진·생년월일·음성·위치·식별자는 저장하지 않는다. 실패하면 빈 ProfileBook.
 * 지우는 방법: clearAll(context) 또는 앱 데이터 삭제.
 */
object ProfileStore {
    const val FILE_NAME = "crewguide_probe_profiles"
    const val KEY_BOOK = "profiles_v1"

    fun load(context: Context): ProfileBook = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        ProfileCodec.decodeBook(prefs.getString(KEY_BOOK, null))
    } catch (e: Exception) {
        ProfileBook()
    }

    fun save(context: Context, book: ProfileBook) {
        try {
            // 2026-11-14 PM 지시 8: apply(비동기)는 등록 직후 기기·에뮬레이터가 강제로 꺼지면 기록이 사라질 틈이 있다. 등록·수정은 드물어 commit(동기)으로 쓴다
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_BOOK, ProfileCodec.encodeBook(book)).commit()
        } catch (e: Exception) {
            // 저장 실패는 화면 흐름에 영향을 주지 않는다
        }
    }

    fun clearAll(context: Context) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY_BOOK).apply()
        } catch (e: Exception) {
            // 무시
        }
    }
}
