package com.crewguide.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/**
 * 오디오 포커스 얇은 래퍼(별도 권한 불필요한 표준 API). 모든 호출은 메인 스레드에서 한다.
 * 주의: 여기서 쓰는 AudioAttributes는 "포커스 요청용"이며, Pleos TTS SDK가 실제 재생에 쓰는
 * 오디오 속성과 같다는 보장이 없다(SDK의 setAudioAttributes는 호출하지 않는다).
 * 포커스 변화는 메인 스레드 Handler로 받아 onLoss를 호출한다.
 */
class AudioFocusHelper(
    context: Context,
    private val main: Handler,
    private val onLoss: () -> Unit,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var request: AudioFocusRequest? = null

    private val changeListener = AudioManager.OnAudioFocusChangeListener { raw ->
        val change = when (raw) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LOSS_TRANSIENT_CAN_DUCK
            else -> null
        }
        if (change != null && focusChangeAction(change) == FocusChangeAction.STOP) onLoss()
    }

    /** 발화 직전 동기 호출 한 번. 보유 중인 요청이 있으면 먼저 해제한다. */
    fun requestFocus(): FocusRequestResult {
        val manager = am ?: return FocusRequestResult.FAILED
        abandon()
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(changeListener, main)
                .build()
            when (manager.requestAudioFocus(req)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { request = req; FocusRequestResult.GRANTED }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusRequestResult.DELAYED
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> FocusRequestResult.DENIED
                else -> FocusRequestResult.FAILED
            }
        } catch (e: Throwable) {
            FocusRequestResult.FAILED
        }
    }

    /** 보유한 포커스가 없으면 아무것도 하지 않는다. */
    fun abandon() {
        val req = request ?: return
        request = null
        try { am?.abandonAudioFocusRequest(req) } catch (_: Throwable) {}
    }
}
