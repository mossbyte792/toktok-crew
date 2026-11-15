package com.crewguide.probe

import android.content.Context

/**
 * 2026-11-10 2차 디렉티브 ③: '음성으로 답하기'(마이크 STT) 사용 설정(순수 로직).
 * 최초 설치·읽기 실패·알 수 없는 값은 [DEFAULT_ENABLED].
 * 2026-11-14 결정 40(사용자 확정, 11-15, r5 추가 지시 A): 기본 켬으로 바꿈. 듣는 때는 '목적지에 도착했나요?' 질문 뒤 5초뿐(ArrivalAnswerRule.LISTEN_MS).
 * 이미 저장한 값("on"/"off")은 그대로 따른다. 되돌리기: DEFAULT_ENABLED = false.
 * 저장은 기존 4종 파일(캐릭터·안내 음성·프로필·스티커 등)과 섞지 않도록 별도 파일·별도 키를 쓴다(마이그레이션 없음).
 */
object VoiceAnswerSetting {
    const val DEFAULT_ENABLED = true
    const val ON = "on"
    const val OFF = "off"

    fun encode(enabled: Boolean): String = if (enabled) ON else OFF

    fun decode(text: String?): Boolean = when (text) {
        ON -> true
        OFF -> false
        else -> DEFAULT_ENABLED
    }
}

/** '음성으로 답하기' 켜짐/꺼짐 1비트 저장소(이 기기의 앱 안에만) */
object VoiceAnswerStore {
    const val FILE_NAME = "crewguide_probe_voice_answer"
    const val KEY_ENABLED = "voice_answer_enabled_v1"

    fun load(context: Context): Boolean = try {
        VoiceAnswerSetting.decode(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY_ENABLED, null))
    } catch (e: Exception) {
        VoiceAnswerSetting.DEFAULT_ENABLED
    }

    /**
     * 저장 성공 여부를 돌려준다(3차 디렉티브 M04: 실패를 호출부가 로그로 남길 수 있게 apply → commit, StickerLogStore와 같은 방식).
     * 실패해도 화면 흐름은 그대로이며, 다음 시작 때는 읽기 결과(없으면 꺼짐)를 따른다. 다른 저장 파일은 건드리지 않는다.
     */
    fun save(context: Context, enabled: Boolean): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENABLED, VoiceAnswerSetting.encode(enabled)).commit()
    } catch (e: Exception) {
        false
    }
}
