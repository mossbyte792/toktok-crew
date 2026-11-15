package com.crewguide.probe

import android.content.Context

/** 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 말투 'a' 또는 'b' 한 글자만 저장한다. 실패하면 기본값 A */
object ToneStore {
    const val FILE_NAME = "crewguide_probe_tone"
    const val KEY_TONE = "tone_v1"

    fun load(context: Context): Tone = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        ToneSetting.decode(prefs.getString(KEY_TONE, null))
    } catch (e: Exception) {
        ToneSetting.DEFAULT
    }

    fun save(context: Context, tone: Tone) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TONE, ToneSetting.encode(tone)).apply()
        } catch (e: Exception) {
            // 저장 실패는 화면 흐름에 영향을 주지 않는다
        }
    }
}
