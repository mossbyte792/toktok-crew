package com.crewguide.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 2026-11-13 PM 지시 1(c): 주행 중 벨트 풀림 좌석 음성 앞의 짧은 2음 차임. 차량 벨트 경고음(높고 반복되는 단음)과 다르게
// 낮은 쪽으로 내려가는 부드러운 두 음(A5 880 Hz → E5 659 Hz), 각 음 앞뒤를 코사인으로 서서히 키우고 줄인다. 전체 0.4초, 최대 진폭 25%.
// 앱 안에서 만든다(파일·외부 음원 없음). 오디오 포커스를 얻은 경우에만 내고, 못 얻으면 차임 없이 넘어간다(차량 경고 우선).

object Chime {
    const val SAMPLE_RATE = 22_050
    const val NOTE1_HZ = 880.0
    const val NOTE2_HZ = 659.25
    const val NOTE_MS = 170
    const val GAP_MS = 30
    const val TAIL_MS = 30
    /** 최대 진폭(전체 크기의 비율). 절제한다 */
    const val PEAK = 0.25
    /** 앞뒤 서서히(ms) */
    const val FADE_MS = 25

    val TOTAL_MS: Int get() = NOTE_MS * 2 + GAP_MS + TAIL_MS

    /** 16비트 모노 PCM(순수, 테스트 가능) */
    fun pcm(rate: Int = SAMPLE_RATE): ShortArray {
        val n1 = rate * NOTE_MS / 1000
        val gap = rate * GAP_MS / 1000
        val tail = rate * TAIL_MS / 1000
        val out = ShortArray(n1 * 2 + gap + tail)
        fun note(start: Int, hz: Double) {
            val fade = rate * FADE_MS / 1000
            for (i in 0 until n1) {
                val env = when {
                    i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                    i >= n1 - fade -> 0.5 - 0.5 * cos(PI * (n1 - 1 - i) / fade)
                    else -> 1.0
                }
                val v = sin(2.0 * PI * hz * i / rate) * env * PEAK
                out[start + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        note(0, NOTE1_HZ)
        note(n1 + gap, NOTE2_HZ)
        return out
    }
}

/**
 * 차임 재생기. [play]는 포커스를 따로 잠깐 얻고(MAY_DUCK, 안내 음성과 같은 용도), 못 얻으면 바로 onDone(false).
 * 재생이 끝나면(또는 실패·상한 시간) 포커스를 놓고 onDone을 한 번 부른다. 메인 스레드 전용.
 */
class ChimePlayer(private val context: Context, private val main: Handler, private val log: (String) -> Unit) {
    private val focus = AudioFocusHelper(context, main) { stop() }
    private var track: AudioTrack? = null
    private var pending: ((Boolean) -> Unit)? = null
    private val finish = Runnable { done(true) }

    val playing: Boolean get() = pending != null

    fun play(onDone: (Boolean) -> Unit) {
        if (pending != null) { onDone(false); return }
        val res = focus.requestFocus()
        if (focusSpeakAction(res) != FocusSpeakAction.SPEAK) { log("[차임] 포커스 없음 → 차임 생략"); onDone(false); return }
        val data = Chime.pcm()
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Chime.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build().also { it.write(data, 0, data.size); it.play() }
        } catch (e: Throwable) {
            log("[차임] 재생 실패 ${e.javaClass.simpleName} → 생략")
            focus.abandon(); onDone(false); return
        }
        track = t
        pending = onDone
        main.postDelayed(finish, Chime.TOTAL_MS + 80L)   // 정적 버퍼라 길이로 끝을 안다
    }

    fun stop() { if (pending != null) done(false) }

    private fun done(ok: Boolean) {
        main.removeCallbacks(finish)
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        focus.abandon()
        val cb = pending; pending = null
        cb?.invoke(ok)
    }

    fun release() { stop() }
}
