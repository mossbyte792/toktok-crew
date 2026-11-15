package com.crewguide.probe

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * v3 반복 6(P1 STT): Pleos STT(ON_DEVICE) 이벤트 경로 측정 — debug 빌드 전용, 앱 흐름(보호자 확인)과 분리된 별도 인스턴스.
 * adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_STT_BENCH
 *
 * 입력: 공식 SDK의 sendAudio(ByteArray) + completeAudioSend() (문서: ON_DEVICE 전용, "차량 마이크 대신 앱이 준 PCM을 인식").
 *  - 마이크·RECORD_AUDIO를 쓰지 않는다. 파일 = 앱 내부 폴더(filesDir/stt)의 *.pcm(16kHz·16bit·mono, 시험자가 adb run-as로 넣음, 시험 뒤 지움).
 *  - 3,200바이트(100ms)씩 실시간 속도로 보낸다(문서 예시와 같음).
 * 기록(태그 STTBENCH): 파일별 전송 시작·전송 완료(completeAudioSend) 시각 기준 onStartedRecognition / onUpdated(중간·최종, 글자) /
 *  onEndedRecognition / onError, 최종 결과 → 앱 판정(decideSdkFinalText) 소요 시간.
 * 주의: 합성 음성 파일 입력이다. 사람 발화·차량 마이크·실내 소음 조건의 인식률이 아니다. 음성·결과 원문은 파일로 저장하지 않는다(로그에만).
 */
class SttBench(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var feeder: HandlerThread? = null
    private var stt: SpeechToText? = null
    private var files: List<File> = emptyList()
    private var idx = -1
    private var sendAt = 0L
    private var completeAt = 0L
    private var running = false
    private var settled = false

    private fun now() = SystemClock.elapsedRealtime()
    private fun log(s: String) = Log.i("STTBENCH", s)
    private fun cur() = files.getOrNull(idx)?.name ?: "?"
    private fun t(): String = "전송시작+${now() - sendAt}ms" + if (completeAt > 0) " 전송완료+${now() - completeAt}ms" else " (전송 중)"

    fun run() {
        if (running) { log("이미 실행 중"); return }
        // 앱 내부 files/stt(시험자가 debug run-as로 넣음. user 10 외부 저장소는 adb 셸이 접근 못 함 — 에뮬레이터 확인)
        val dir = java.io.File(context.filesDir, "stt")
        files = dir?.listFiles { f -> f.name.endsWith(".pcm") }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { log("입력 파일 없음: ${dir?.absolutePath}"); return }
        running = true
        feeder = HandlerThread("stt-feed").also { it.start() }
        val t0 = now()
        log("초기화 시작 mode=ON_DEVICE 파일 ${files.size}개")
        try {
            val s = SpeechToText(context, Mode.ON_DEVICE)
            stt = s
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() { ui.post { log("초기화 성공 ${now() - t0}ms"); runCatching { s.setLocale(Locale.KOREA) }; s.addListener(listener); next() } }
                override fun onFailure(p0: ErrorType) { ui.post { log("초기화 실패 ${p0.name} ${now() - t0}ms"); finish() } }
            })
        } catch (e: Throwable) { log("초기화 예외 ${e.javaClass.simpleName}"); finish() }
    }

    private val listener = object : ResultListener {
        override fun onUpdated(p0: String, p1: Boolean) {
            val at = now()
            ui.post {
                if (!p1) { log("onUpdated(중간) ${cur()} '${p0}' ${t()}"); return@post }
                val d0 = System.nanoTime()
                val d = decideSdkFinalText(p0)
                val us = (System.nanoTime() - d0) / 1000
                log("onUpdated(최종) ${cur()} '${p0}' ${t()} → 판정 $d (판정 ${us}us, 콜백→판정완료 ${now() - at}ms)")
                settle()
            }
        }
        override fun onUpdatedEpdData(p0: Long, p1: Long) { ui.post { log("onUpdatedEpdData ${cur()} $p0,$p1 ${t()}") } }
        override fun onStartedRecognition() { ui.post { log("onStartedRecognition ${cur()} ${t()}") } }
        override fun onEndedRecognition() { ui.post { log("onEndedRecognition ${cur()} ${t()}"); settle() } }
        override fun onError(p0: ErrorType) { ui.post { log("onError ${cur()} ${p0.name} ${t()}"); settle() } }
        override fun onReady() { ui.post { log("onReady ${cur()} ${t()}") } }
        override fun onUpdatedRms(p0: Double) {}
    }

    /** 최종·종료·오류 중 첫 신호 뒤 1.5초 더 기다렸다가(늦은 콜백 기록) 다음 파일 */
    private fun settle() {
        if (settled) return
        settled = true
        val my = idx
        ui.postDelayed({ if (idx == my && running) next() }, 1500)
    }

    private fun next() {
        val s = stt ?: return finish()
        idx++
        val f = files.getOrNull(idx) ?: return finish()
        settled = false; completeAt = 0L
        val pcm = runCatching { f.readBytes() }.getOrNull() ?: run { log("읽기 실패 ${f.name}"); return next() }
        sendAt = now()
        log("전송 시작 ${f.name} ${pcm.size}B (${pcm.size / 32}ms 분량)")
        val h = Handler(feeder!!.looper)
        h.post {
            try {
                var off = 0
                while (off < pcm.size) {
                    val end = minOf(off + 3200, pcm.size)
                    s.sendAudio(pcm.copyOfRange(off, end))
                    off = end
                    Thread.sleep(100L)
                }
                s.completeAudioSend()
                ui.post { completeAt = now(); log("completeAudioSend ${f.name} 전송시작+${completeAt - sendAt}ms") }
            } catch (e: Throwable) { ui.post { log("전송 예외 ${f.name} ${e.javaClass.simpleName}"); settle() } }
        }
        val my = idx
        ui.postDelayed({ if (idx == my && running && !settled) { log("상한 20초 신호 없음 ${cur()}"); next() } }, 20_000)
    }

    private fun finish() {
        log("끝 (${idx.coerceAtLeast(0)}/${files.size})")
        runCatching { stt?.removeListener(listener) }
        runCatching { stt?.release() }
        feeder?.quitSafely(); feeder = null
        stt = null; running = false; idx = -1
    }
}
