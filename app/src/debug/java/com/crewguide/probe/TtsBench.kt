package com.crewguide.probe

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * v3 반복 5(§7): Pleos 기본 TTS(ON_DEVICE) 콜백 지연 측정 — debug 빌드 전용, 앱 흐름과 분리된 별도 인스턴스.
 * adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_TTS_BENCH
 *
 * 기록(태그 TTSBENCH): 초기화 시간, 문장별 요청→onStart(콜백 지연), onStart→onDone(콜백 기준 재생 길이), 오류·중지 콜백.
 * 측정하지 않는 것: 실제 소리 출력 시점(첫 PCM). 콜백 지연을 청각 지연으로 쓰지 않는다.
 * 오디오 포커스는 요청하지 않는다(앱 TtsGuide 경로는 FocusController가 따로 요청). 음성·오디오는 저장하지 않는다.
 * 문장에는 실제 아이 이름을 쓰지 않는다(시험용 가상 별명).
 */
class TtsBench(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var idx = -1
    private var reqAt = 0L
    private var startAt = 0L
    private var running = false
    private var stopTestPending = false
    // v4 G4: 진행 신호(onUpdatedRms) 실측. 개수·시작 후 첫 신호·최대 간격·마지막 신호→종료 콜백 간격(값 자체는 기록하지 않음)
    private var rmsCount = 0
    private var rmsFirst = 0L
    private var rmsLast = 0L
    private var rmsMaxGap = 0L
    private fun rmsReset() { rmsCount = 0; rmsFirst = 0L; rmsLast = 0L; rmsMaxGap = 0L }
    private fun rmsSummary(): String {
        val n = now()
        return "RMS ${rmsCount}회 시작→첫신호 ${if (rmsFirst > 0 && startAt > 0) rmsFirst - startAt else -1}ms 최대간격 ${rmsMaxGap}ms 마지막신호→지금 ${if (rmsLast > 0) n - rmsLast else -1}ms"
    }

    private data class Item(val id: String, val text: String, val stopAfterMs: Long = -1)

    private val items = listOf(
        Item("S01_짧은인사", "안녕하세요"),
        Item("S02_안내", "뒷좌석 벨트를 함께 확인해 주세요"),
        Item("S03_질문", "아이의 안전벨트가 제대로 착용됐는지 직접 확인하셨나요?"),
        Item("S04_별명", "하늘이, 출발 전에 벨트를 확인해요"),
        Item("S05_별명받침", "윤슬이 친구, 오늘도 함께 가요"),
        Item("S06_받침", "닭갈비, 읽기, 넓은 길, 맑은 하늘"),
        Item("S07_겹받침", "값, 삯, 몫, 흙, 앉다"),
        Item("S08_하차", "내리기 전에 뒷좌석을 한 번 더 확인해 주세요."),
        Item("S09_숫자", "아이 3명, 2026년 11월 11일"),
        Item("S10_영문혼합", "TOK TOK CREW 승무원이에요"),
        Item("S11_예", "예"),
        Item("S12_아니오", "아니오"),
        Item("S13_장문", "오늘은 조금 먼 길을 가요. 가는 동안 벨트는 꼭 맨 채로 앉아 있어요. 궁금한 것이 있으면 보호자에게 말해 주세요. 도착하면 다시 알려 줄게요."),
        Item("S14_반복1", "뒷좌석 벨트를 함께 확인해 주세요"),
        Item("S15_반복2", "뒷좌석 벨트를 함께 확인해 주세요"),
        Item("S16_반복3", "뒷좌석 벨트를 함께 확인해 주세요"),
        Item("S17_중지시험", "이 문장은 중간에 멈추는 시험이에요. 끝까지 읽지 않아도 괜찮아요. 멈춘 뒤에 다음 문장이 바로 나와야 해요.", stopAfterMs = 800),
        Item("S18_중지후복구", "다시 말할 수 있어요"),
    )

    private fun now() = SystemClock.elapsedRealtime()
    private fun log(s: String) = Log.i("TTSBENCH", s)

    fun run() {
        if (running) { log("이미 실행 중"); return }
        running = true
        val t0 = now()
        log("초기화 시작 mode=ON_DEVICE")
        try {
            val t = TextToSpeech(context, TtsMode.ON_DEVICE)
            tts = t
            t.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    ui.post {
                        log("초기화 성공 ${now() - t0}ms")
                        t.addEventListener(listener)
                        t.setLocale(Locale.KOREA)
                        next()
                    }
                }
                override fun onFailure(p0: TtsErrorType) { ui.post { log("초기화 실패 ${p0.name} ${now() - t0}ms"); finish() } }
            })
        } catch (e: Throwable) { log("초기화 예외 ${e.javaClass.simpleName}"); finish() }
    }

    private val listener = object : TtsEventListener {
        override fun onStart() { ui.post { startAt = now(); log("onStart ${cur()} 요청→시작 ${startAt - reqAt}ms") } }
        override fun onReady() { ui.post { log("onReady ${cur()} +${now() - reqAt}ms") } }
        override fun onUpdatedRms(p0: Double) {
            val at = now()
            ui.post {
                if (rmsLast > 0) rmsMaxGap = maxOf(rmsMaxGap, at - rmsLast)
                if (rmsCount == 0) rmsFirst = at
                rmsLast = at; rmsCount++
            }
        }
        override fun onDone() { ui.post { log("onDone ${cur()} 시작→완료 ${if (startAt > 0) now() - startAt else -1}ms 요청→완료 ${now() - reqAt}ms ${rmsSummary()}"); ui.postDelayed({ next() }, 300) } }
        override fun onError(p0: TtsErrorType) { ui.post { log("onError ${cur()} ${p0.name} +${now() - reqAt}ms ${rmsSummary()}"); ui.postDelayed({ next() }, 300) } }
        override fun onStop() { ui.post { log("onStop ${cur()} +${now() - reqAt}ms ${rmsSummary()}"); if (stopTestPending) { stopTestPending = false; ui.postDelayed({ next() }, 0) } } }
    }

    private fun cur(): String = items.getOrNull(idx)?.let { "${it.id}(${it.text.length}자)" } ?: "?"

    private fun next() {
        val t = tts ?: return finish()
        idx++
        val it = items.getOrNull(idx) ?: return finish()
        startAt = 0L
        rmsReset()
        reqAt = now()
        try {
            t.speak(it.text, true)
            log("요청 ${cur()}")
        } catch (e: Throwable) { log("요청 예외 ${cur()} ${e.javaClass.simpleName}"); ui.postDelayed({ next() }, 300); return }
        if (it.stopAfterMs > 0) {
            ui.postDelayed({ stopTestPending = true; log("stop() 호출 ${cur()} +${now() - reqAt}ms"); runCatching { t.stop() } }, it.stopAfterMs)
        }
        // 콜백이 끝내 없으면 15초 뒤 다음으로(기록만)
        val myIdx = idx
        ui.postDelayed({ if (idx == myIdx && running) { log("상한 15초 콜백 없음 ${cur()} ${rmsSummary()}"); next() } }, 15000)
    }

    private fun finish() {
        log("끝 (${idx.coerceAtLeast(0)}/${items.size})")
        runCatching { tts?.removeEventListener(listener) }
        runCatching { tts?.release() }
        tts = null; running = false; idx = -1
    }
}
