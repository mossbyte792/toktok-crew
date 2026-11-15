package com.crewguide.probe

import android.content.Context

/** 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 안내 음성 켜짐/꺼짐 문자열 하나만 저장한다. 실패하면 기본값(켜짐) */
object GuidanceStore {
    const val FILE_NAME = "crewguide_probe_guidance"
    const val KEY_ENABLED = "guidance_voice_enabled_v1"

    fun load(context: Context): Boolean = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        GuidanceSetting.decode(prefs.getString(KEY_ENABLED, null))
    } catch (e: Exception) {
        GuidanceSetting.DEFAULT_ENABLED
    }

    fun save(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENABLED, GuidanceSetting.encode(enabled)).apply()
        } catch (e: Exception) {
            // 저장 실패는 화면 흐름에 영향을 주지 않는다
        }
    }
}
