package com.crewguide.probe

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

/**
 * 2026-11-14 r5 2절: 다른 앱(내비)이 지금 안내 음성을 내는지 본다. 표준 API(AudioManager.registerAudioPlaybackCallback, 권한 불필요).
 * 일반 앱에는 재생 목록이 익명(앱 구분 없음)·재생 중인 것만 오지만 용도(usage)·종류(content type)는 보인다.
 * 판정은 [NaviVoiceRule.isNaviVoice]. 콜백은 메인 스레드 Handler로 받는다. 등록에 실패하면 [available]=false(막지 않고 지금처럼 말함).
 * 재생 내용·앱 이름은 읽지 않고 저장하지 않는다(개수만).
 */
class NaviVoiceWatch(
    context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onCount: (Int) -> Unit,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    var available = false; private set
    private var registered = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (registered) onCount(count(configs))
        }
    }

    /** 감시 시작(멱등). 지금 값을 바로 한 번 알린다 */
    fun start() {
        if (registered) return
        val m = am
        if (m == null) { log("[내비음성] 재생 감시 불가(AudioManager 없음) → 기다리지 않고 지금처럼 말함"); return }
        try {
            m.registerAudioPlaybackCallback(callback, main)
            registered = true
            available = true
            val now = count(m.activePlaybackConfigurations)
            log("[내비음성] 재생 감시 시작(지금 내비 안내 음성 ${now}개)")
            onCount(now)
        } catch (e: Throwable) {
            available = false
            log("[내비음성] 재생 감시 불가(${e.javaClass.simpleName}) → 기다리지 않고 지금처럼 말함")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        available = false
        try { am?.unregisterAudioPlaybackCallback(callback) } catch (_: Throwable) {}
    }

    private fun count(configs: List<AudioPlaybackConfiguration>?): Int =
        configs?.count { c ->
            val a = try { c.audioAttributes } catch (_: Throwable) { null }
            a != null && NaviVoiceRule.isNaviVoice(a.usage, a.contentType)
        } ?: 0
}
