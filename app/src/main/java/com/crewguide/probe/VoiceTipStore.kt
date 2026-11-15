package com.crewguide.probe

import android.content.Context
import kotlin.random.Random

/**
 * 2026-11-13 1(d): '이동 중 교통 상식' 켜짐/꺼짐 1비트를 이 앱의 SharedPreferences에 저장한다. 기본 켜짐, 읽기 실패도 기본값.
 * 2026-11-14 사용자 지시: 상식 섞은 순서와 위치(TripTips 덱 문자열)도 같은 파일에 저장한다(동기 commit). 상식 번호와 문장 지문만 두고
 * 아이 정보·위치는 두지 않는다. 읽기 실패·깨진 값이면 새로 섞는다.
 */
object VoiceTipStore {
    const val FILE_NAME = "crewguide_probe_voice_tips"
    const val KEY_ON = "trip_tips_on_v1"
    const val KEY_DECK = "trip_tips_deck_v1"
    const val DEFAULT_ON = true

    fun load(context: Context): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ON, DEFAULT_ON)
    } catch (e: Exception) { DEFAULT_ON }

    fun save(context: Context, on: Boolean): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).commit()
    } catch (e: Exception) { false }

    /** 다음 교통 상식 문장("톡톡크루 상식이에요. …"). 무작위 순서·한 바퀴 중복 없음·주제 섞임은 [TripTips], 여기서는 읽고 쓰기만 */
    fun nextTipText(context: Context, rnd: Random = Random.Default): String {
        val saved = try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY_DECK, null)
        } catch (e: Exception) { null }
        val (idx, deck) = TripTips.next(saved, TIP_TOPICS, TIP_FP, rnd)
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DECK, deck).commit()
        } catch (e: Exception) { /* 저장 실패: 이번 문장은 내고, 다음에 새로 섞는다 */ }
        return VoiceLines.tipText(idx)
    }

    private val TIP_TOPICS: List<TipTopic> by lazy { VoiceLines.TIPS.map { it.topic } }
    private val TIP_FP: Int by lazy { TripTips.fingerprint(VoiceLines.TIPS.map { it.text }) }
}
