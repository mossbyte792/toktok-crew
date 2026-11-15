package com.crewguide.probe

import android.content.Context
import android.os.Handler
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import java.util.Locale

/**
 * 보호자 확인 안내 음성(TextToSpeech, ON_DEVICE만). 모든 상태 변경은 ui(메인 스레드)에서만 한다.
 * 발화 내용은 호출자가 넘기는 고정 문장뿐이며(별명 TTS 읽기를 켜면 별명 포함), 이 클래스는 오디오를 저장하지 않는다.
 * 문장은 SDK를 거쳐 Pleos TTS 시스템 서비스로 넘어간다. 그 서비스·SDK 내부의 처리 위치·외부 통신 여부는 미확인(2026-11-11).
 * 콜백에는 발화 식별자가 없으므로, pendingGen이 -1(대기 발화 없음)이면 모든 이벤트를 무시한다.
 */
class TtsGuide(
    private val context: Context,
    private val ui: Handler,
    private val log: (String) -> Unit,
    private val onChange: () -> Unit,
    /** 질문 발화 결과(메인 스레드). [TtsEnd.DONE]만 onDone이며 나머지는 발화 종료 확인 불가(2026-11-08 종료 사유 분리) */
    private val onQuestionResult: (gen: Int, end: TtsEnd) -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var listener: TtsEventListener? = null
    private var destroyed = false
    // 포커스 요청용 오디오 속성이며 TTS SDK의 실제 재생 속성과 같다는 보장은 없다
    private val focus = AudioFocusHelper(context, ui) { onFocusLost() }

    // ---- 2026-11-14 r5 2절: 내비 안내 음성과 겹치지 않게(NaviVoiceRule, 근거는 그 파일 머리말) ----
    private var naviState = NaviVoiceState()
    private val naviWatch = NaviVoiceWatch(context, ui, log) { n -> onNaviCount(n) }
    /** 자동 안내 요청 시각과 그때의 내비 안내 음성 수(앱 자신 몫 포함, 끼어들기 판단 기준) */
    private var autoReqMs = -1L
    /** r6a: 일반 안내(speak) 중 내비가 끼어들면 멈출 발화인가(확인 완료 음성, 임시 결정 42). 자동 안내는 항상 대상 */
    private var pendingYieldable = false
    private var autoBaseline = 0
    /** 내비 때문에 멈춘 자동 안내 세대(호출자가 다시 줄에 세운다). 없으면 -1 */
    private var yieldedGen = -1
    private var naviWaitLogged = false

    private fun onNaviCount(n: Int) {
        if (destroyed) return
        val now = android.os.SystemClock.elapsedRealtime()
        val before = naviState.activeCount
        naviState = NaviVoiceRule.onCount(naviState.copy(available = naviWatch.available), n, now)
        if (before == 0 && n > 0) { log("[내비음성] 내비 안내 음성 시작(재생 ${n}개)"); onNaviStart?.invoke() }
        if (before > 0 && n == 0) log("[내비음성] 내비 안내 음성 끝 → ${NaviVoiceRule.GAP_AFTER_NAVI_MS}ms 쉰 뒤 앱 안내")
        if (pendingGen >= 0 && (pendingIsAuto || pendingYieldable) && autoReqMs >= 0) {
            val (b, yieldNow) = NaviVoiceRule.onCountWhileSpeaking(autoBaseline, n, now - autoReqMs, drivingNow())
            if (n > autoBaseline && !yieldNow && now - autoReqMs >= NaviVoiceRule.OWN_ECHO_MS) log("[내비음성] 앱 음성 중 내비 음성 시작 → 끝까지 말함(멈춤 꺼짐) 세대=$pendingGen")
            autoBaseline = maxOf(b, if (yieldNow) b else n)
            if (yieldNow) {
                val g = pendingGen
                log("[내비음성] 앱 안내 중 내비 음성 시작 → 앱 안내 멈춤, 내비가 끝나면 처음부터 다시(결정 37(사용자 확정, 11-15)) 세대=$g")
                yieldedGen = g
                stop(end = TtsEnd.APP_STOP)
            }
        }
        if (n == 0) onNaviQuiet?.invoke()
    }

    /** 내비 음성 때문에 지금 기다려야 하는 시간(ms). 0 = 바로 말해도 됨. 감시 못 하면 항상 0 */
    fun naviWaitMs(): Long = NaviVoiceRule.waitMs(naviState, android.os.SystemClock.elapsedRealtime())
    /** 내비가 말하는 중이거나 끝난 직후 쉬는 시간인가(교통 상식 보류용) */
    val naviBusy: Boolean get() = naviWaitMs() > 0
    /** 이 자동 안내 세대가 내비 때문에 멈췄는가(결정 37(사용자 확정, 11-15): 호출자가 처음부터 다시 줄에 세움) */
    fun yieldedToNavi(g: Int): Boolean = g >= 0 && g == yieldedGen
    /** 내비 음성이 끝났을 때(메인 스레드). 호출자는 쉬는 시간 뒤 대기 안내를 다시 보낸다 */
    var onNaviQuiet: (() -> Unit)? = null
    /** 내비 안내 음성이 시작될 때(메인 스레드). 도착 질문 음성 대답 듣기를 멈추는 데 쓴다 */
    var onNaviStart: (() -> Unit)? = null
    /** 지금 주행 중인가(끼어들기 멈춤은 주행 중 자동 안내에만, NaviVoiceRule.yieldApplies). 호출자가 넣는다 */
    var drivingNow: () -> Boolean = { false }

    // ---- 일반 안내(speak·speakExit)의 내비 대기(PM 지시 23:3x): 세대 번호는 바로 주고, SDK 요청은 내비가 끝나고 쉰 뒤(최대 30초) ----
    private class Deferred(val g: Int, val text: String, val tag: String, val sinceMs: Long)
    private var deferred: Deferred? = null
    private val deferredRunnable = Runnable { runDeferred() }

    /** 대기 발화로 걸어 둔다(SDK 요청은 [runDeferred]). pending 플래그는 호출자가 먼저 정한다 */
    private fun deferIfNavi(g: Int, text: String, tag: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val w = NaviVoiceRule.deferWaitMs(naviState, now, now)
        deferred = Deferred(g, text, tag, now)
        log("$tag 내비 안내 음성 중·직후 → 기다렸다가 말함(최대 ${NaviVoiceRule.MAX_DEFER_MS / 1000}초) 세대=$g")
        ui.removeCallbacks(deferredRunnable); ui.postDelayed(deferredRunnable, w.coerceAtLeast(0L))
    }

    private fun runDeferred() {
        val d = deferred ?: return
        if (destroyed || pendingGen != d.g) { deferred = null; return }
        val now = android.os.SystemClock.elapsedRealtime()
        val w = NaviVoiceRule.deferWaitMs(naviState, now, d.sinceMs)
        if (w > 0) { ui.removeCallbacks(deferredRunnable); ui.postDelayed(deferredRunnable, w); return }
        deferred = null
        val waited = now - d.sinceMs
        val t = tts
        if (!ready || t == null) { finishUnconfirmed("${d.tag} 내비 대기 뒤 음성 준비 안 됨", "음성 안내 확인 불가(음성 준비 안 됨)", TtsEnd.ERROR); return }
        if (focusSpeakAction(focus.requestFocus()) != FocusSpeakAction.SPEAK) {
            finishUnconfirmed("${d.tag} 내비 대기 뒤 포커스 거부 → 강행하지 않음", "음성 안내 건너뜀(오디오 포커스 없음)", TtsEnd.FOCUS_LOST); return
        }
        noteSpeak(d.g, d.text.length)
        autoReqMs = now; autoBaseline = naviState.activeCount
        try {
            t.speak(d.text, true)
            log("${d.tag} 내비 대기 ${waited}ms 뒤 발화요청 세대=${d.g}" + if (waited >= NaviVoiceRule.MAX_DEFER_MS) " (최대 대기 지남 → 그냥 말함)" else "")
        } catch (e: Throwable) {
            ledger = TtsCallbackLedger.onAbandon(ledger)
            finishUnconfirmed("${d.tag} 오류 ${e.javaClass.simpleName}", "음성 안내 확인 불가(오류: ${e.javaClass.simpleName})", TtsEnd.ERROR)
        }
    }

    var ready = false; private set
    /** 사용자 선택(안내 음성 사용). 메인 스레드에서만 변경 */
    var guidanceEnabled = GuidanceSetting.DEFAULT_ENABLED
    /** 대기 중인 질문 발화가 있는지 */
    val questionPending: Boolean get() = pendingGen >= 0 && pendingIsQuestion
    private var availability = "음성 안내: 준비 중"
    private var speech = ""                 // 발화 상태 문구(onDone 전에는 발화했다고 쓰지 않는다)
    private var gen = 0                     // 발화 요청 세대
    private var pendingGen = -1             // 응답을 기다리는 발화 세대, 없으면 -1
    private var pendingIsQuestion = false
    private var pendingIsExample = false    // 대기 발화가 예시 안내인지(보호자 확인 상태 정리와 독립)
    private var pendingIsDrive = false      // 대기 발화가 주행 중 벨트 안내인지(P 아님 잠금 정리로 끊지 않는다)
    private var pendingIsExit = false       // 대기 발화가 하차 확인 한 줄인지(화면 상태 문구·콜백 없이 로그만)
    private var pendingWasExample = false   // onDone 처리 중: 끝난 발화가 예시였는지
    // 2026-11-13 자동 안내(자동 확인 재안내·출발·남은 시간·도착·하차 마무리). 화면 상태 문구·질문 콜백 없이 onAutoResult로만 보고한다
    private var pendingIsAuto = false
    /** 자동 안내 중 출발·도착 안내처럼 화면 갱신·잠금 정리(stop keepDrive=true)로 끊지 않는 발화인가 */
    private var pendingAutoKeep = false
    /** 2026-11-14 PM 결정 24 ②: 이동(FGS) 중 확인 완료 음성. 화면 이탈·잠금 정리(stop keepDrive=true)로 끊지 않는다. 포커스 상실·force 정리는 그대로 */
    private var pendingTripKeep = false
    // 콜백 귀속 장부(2026-11-08 Unit 5.3, DECISION 5): 발화 번호 + onStart 순서로 종료 콜백의 주인을 정한다(TtsCallbackLedger).
    // pendingGen을 바꾸는 모든 곳에서 장부도 함께 갱신해 같은 발화를 가리키게 한다.
    private var ledger = TtsLedgerState()
    private val t0 = android.os.SystemClock.elapsedRealtime()
    private fun ms(): Long = android.os.SystemClock.elapsedRealtime() - t0
    /** 종료 콜백(완료·오류·중지)이 현재 발화(onStart를 받은)의 것이면 true. 아니면 소비하고 무시한다. 메인 스레드 */
    private fun ownTerminal(kind: String): Boolean {
        val waiting = pendingGen
        val (next, attr) = TtsCallbackLedger.onTerminal(ledger)
        ledger = next
        when (attr) {
            TtsAttribution.CURRENT -> {}
            TtsAttribution.STALE -> log("[TTS] 이전 발화의 늦은 $kind 콜백 무시(대기 세대=$waiting 시작 전, t=${ms()})")
            TtsAttribution.NO_PENDING -> log("[TTS] 대기 발화 없음 → $kind 콜백 무시(중복·늦은 콜백, t=${ms()})")
        }
        return attr == TtsAttribution.CURRENT
    }
    /** SDK stop() 호출 직후(성공 여부와 무관하게 이전 발화의 늦은 콜백이 올 수 있다) */
    private fun noteAppStop() {
        if (ledger.currentId >= 0 && !ledger.started) stoppedBeforeStart = true
        ledger = TtsCallbackLedger.onAppStop(ledger)
    }
    private var stoppedBeforeStart = false
    /** 대기 중인 발화의 글자 수(종료 상한 계산용, 2026-11-12). 문장 자체는 보관하지 않는다 */
    private var pendingTextLen = 0
    /** 현재 발화에 건 종료 상한(ms, 로그용) */
    private var pendingEndLimitMs = TtsCallbackLedger.END_TIMEOUT_MS
    /** onStart 뒤 종료 콜백이 상한 안에 오지 않음: 완료로 보지 않고 '확인 불가'로 끝낸다(남은 주행 동안 경고가 막히지 않게) */
    private fun onEndTimeout(g: Int) {
        if (destroyed || pendingGen != g) return
        val (next, timedOut) = TtsCallbackLedger.onEndTimeout(ledger, g)
        if (!timedOut) return
        ledger = next
        log("[TTS] 완료 확인 없음(${pendingEndLimitMs}ms, ${pendingTextLen}자) 세대=$g → 확인 불가로 정리")
        val t = tts
        if (t != null) { try { t.stop() } catch (e: Throwable) { log("[TTS] 오류 ${e.javaClass.simpleName}") }; noteAppStop() }
        finishUnconfirmed("[TTS] 완료 확인 없음", "음성 안내 확인 불가(완료 확인 없음)", TtsEnd.END_TIMEOUT)
    }
    /** 새 발화 요청: 번호를 장부에 올리고 onStart 대기 상한을 건다 */
    private fun noteSpeak(g: Int, textLen: Int) {
        ledger = TtsCallbackLedger.onSpeak(ledger, g)
        pendingTextLen = textLen
        pendingEndLimitMs = TtsCallbackLedger.endTimeoutMs(textLen)
        ui.postDelayed({ onStartTimeout(g) }, TtsCallbackLedger.START_TIMEOUT_MS)
    }
    /** onStart가 상한 안에 오지 않음: 완료로 보지 않고 '확인 불가'로 끝낸다(SDK에는 stop을 보내 늦은 재생을 막는다) */
    private fun onStartTimeout(g: Int) {
        if (destroyed || pendingGen != g) return
        val (next, timedOut) = TtsCallbackLedger.onStartTimeout(ledger, g)
        if (!timedOut) return
        ledger = next
        log("[TTS] 시작 확인 없음(${TtsCallbackLedger.START_TIMEOUT_MS}ms) 세대=$g → 확인 불가로 정리 [TTS-복구] 장애 감지 t=${ms()}")
        val t = tts
        if (t != null) { try { t.stop() } catch (e: Throwable) { log("[TTS] 오류 ${e.javaClass.simpleName}") }; noteAppStop() }
        finishUnconfirmed("[TTS] 시작 확인 없음", "음성 안내 확인 불가(시작 확인 없음)", TtsEnd.START_TIMEOUT)
        // v4 G4: 연속 시작 실패 = 연결 상실로 보고 다시 초기화(횟수 제한). 실패한 발화는 다시 말하지 않는다
        val (rs, reconnectNow) = TtsReconnectPolicy.onStartTimeout(reconnect)
        reconnect = rs
        if (reconnectNow) { reconnectWanted = true; ui.post { maybeReconnect() } }
    }

    /** v4 G4: 연결 회복 상태(TtsReconnectPolicy) */
    private var reconnect = TtsReconnectState()
    /** 재연결이 필요하나 아직 못 함(진행 중 발화가 끝나거나 다음 발화 요청 때 한다) */
    private var reconnectWanted = false
    /** 대기 발화가 없을 때만 재연결한다. 진행 중 발화는 끊지 않는다 */
    private fun maybeReconnect() { if (reconnectWanted && !destroyed && pendingGen < 0) reconnectEngine() }
    /** 재초기화 횟수(로그·화면 상태용) */
    var reconnectCount = 0; private set
    /** 2026-11-12 A2: 재연결이 필요하거나 재초기화 결과를 기다리는 중(메인 스레드). 이 동안 발화 요청은 건너뛰고 쌓지 않는다 */
    val reconnecting: Boolean get() = !destroyed && (reconnectWanted || reconnectInFlight)
    private var reconnectInFlight = false
    /** 재연결 시작~초기화 결과 시각(ms, 로그용). 장애 감지·재연결 완료·다음 onStart를 따로 기록한다(지시 A2 실측) */
    private var reconnectStartedMs = -1L
    private var awaitFirstStartAfterReconnect = false

    /**
     * 이전 TextToSpeech 인스턴스를 놓고 새로 초기화한다(메인 스레드). 대기 발화가 없을 때만.
     * 이전 인스턴스의 늦은 콜백은 리스너를 먼저 떼어 받지 않는다. 장부는 새로 시작한다.
     */
    private fun reconnectEngine() {
        if (destroyed) return
        if (pendingGen >= 0) { log("[TTS] 재연결 보류(진행 중 발화 있음)"); return }
        reconnectWanted = false
        reconnectCount++
        log("[TTS] 연속 시작 실패 → 음성 엔진 다시 연결(${reconnectCount}회째, 최근 성공 뒤 ${reconnect.reconnectsSinceOk}/${TtsReconnectPolicy.MAX_RECONNECTS})")
        val old = tts
        val l = listener
        tts = null; listener = null; ready = false; initFailed = false
        if (old != null) {
            l?.let { try { old.removeEventListener(it) } catch (_: Throwable) {} }
            try { old.stop() } catch (_: Throwable) {}
            try { old.release() } catch (_: Throwable) {}
        }
        ledger = TtsLedgerState()
        stoppedBeforeStart = false
        reconnectInFlight = true
        reconnectStartedMs = ms()
        log("[TTS-복구] 재연결 시작 t=${reconnectStartedMs}")
        availability = "음성 안내: 다시 연결 중"
        onChange()
        init()
    }
    /**
     * 실제로 재생 중인가: 현재 발화가 onStart를 받았고 아직 종료 콜백(완료·오류·중지·상한)이 없다.
     * 캐릭터 표시(SPEAKING) 전용. 요청만 하고 onStart가 없으면 false(타이머로 가장하지 않음). 2026-11-08 v6.1
     */
    /** 결과를 기다리는 발화가 있는지(시작 전 포함). 야간 전환 재생성 시점 판단용 */
    val busy: Boolean get() = pendingGen >= 0
    val speakingNow: Boolean get() = pendingGen >= 0 && ledger.started && ledger.currentId == pendingGen
    /** [speakingNow]가 바뀌었을 수 있을 때(메인 스레드, 다음 루프에서) 부른다. 표시 갱신 전용 */
    var onSpeakingChanged: (() -> Unit)? = null
    private fun speakingChanged() { ui.post { if (!destroyed) onSpeakingChanged?.invoke() } }
    /** 진행 중 발화가 없는지(다른 발화가 끝나기를 기다리는 호출자용) */
    val idle: Boolean get() = pendingGen < 0
    /** 발화가 끝나 대기가 비었을 때(완료·확인 불가·중지 모두) 메인 스레드에서 호출. 주행 경고 재평가용 */
    var onIdle: (() -> Unit)? = null

    /**
     * 일반 안내 발화(질문·주행·하차·예시 제외) 결과(메인 스레드). [TtsEnd.DONE]만 onDone. 2026-11-07 Unit 5.1: 아이 안내 완료(NoticeNext) 연결용.
     * 2026-11-08 P0-C 재감사: 앱 stop()도 [TtsEnd.APP_STOP]/[TtsEnd.GATE_STOP]으로 다음 메인 루프에 보고한다(기다리는 쪽이 상한까지 묶이지 않게)
     */
    var onSpeechResult: ((gen: Int, end: TtsEnd) -> Unit)? = null
    /**
     * 주행 벨트 안내 결과(메인 스레드). gen=speakDrive가 돌려준 세대, done=true는 onDone.
     * false=onError/onStop/포커스 상실/시작 확인 없음/앱 stop()(전달 완료 아님). 앱 stop()으로 인한 false는 다음 메인 루프에서 온다
     */
    var onDriveResult: ((gen: Int, done: Boolean) -> Unit)? = null
    /** 하차 한 줄 결과(세대, 종료 사유). DONE만 소진 근거. 2026-11-08 6C */
    var onExitResult: ((gen: Int, end: TtsEnd) -> Unit)? = null
    /** 주행 벨트 안내 발화가 진행 중인지 */
    val drivePending: Boolean get() = pendingGen >= 0 && pendingIsDrive

    fun statusLines(): String = availability + (if (speech.isNotEmpty()) "\n$speech" else "")
    fun clearSpeech() { speech = "" }

    /** r10 N: 초기화(첫 연결·재연결) 성공 뒤 메인 스레드에서 부른다. 다시 그린 화면의 유지 음성 이어 내기용 */
    var onReady: (() -> Unit)? = null

    /** 초기화가 실패로 끝났는가(준비 중과 구분. 주행 경고는 준비 중이면 기다리고 실패면 전달 실패로 센다) */
    var initFailed = false; private set

    private fun fail(cause: String) {
        ready = false
        initFailed = true
        if (reconnectInFlight) { reconnectInFlight = false; log("[TTS-복구] 재연결 실패 t=${ms()} 원인=$cause") }
        availability = "음성 안내: 사용 불가(원인: $cause)"
        log("[TTS] 초기화 실패 원인=$cause")
        speech = "음성 안내 불가(원인: $cause). 화면 문구로만 안내합니다"
        onChange()
    }

    fun init() {
        if (tts != null) return   // 중복 생성 방지
        if (!destroyed) { naviWatch.start(); naviState = naviState.copy(available = naviWatch.available) }   // 멱등(재연결 때 다시 등록 안 함)
        try {
            val t = TextToSpeech(context, TtsMode.ON_DEVICE)
            tts = t
            t.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    ui.post {
                        if (destroyed) return@post
                        try {
                            val l = newEventListener()
                            t.addEventListener(l)
                            listener = l
                            t.setLocale(Locale.KOREA)
                            ready = true
                            availability = "음성 안내: 준비됨(미검증)"
                            log("[TTS] 초기화 성공(ON_DEVICE)")
                            if (reconnectInFlight) {
                                reconnectInFlight = false; awaitFirstStartAfterReconnect = true
                                log("[TTS-복구] 재연결 완료 t=${ms()} (재초기화 ${ms() - reconnectStartedMs}ms) → 다시 듣기 가능")
                            }
                            onChange()
                            onReady?.invoke()   // r10 N: 다시 그린 화면이 넘겨받은 유지 음성을 이어 낸다(TtsCarryRule)
                        } catch (e: Throwable) { fail(e.javaClass.simpleName) }
                    }
                }
                override fun onFailure(p0: TtsErrorType) {
                    val name = p0.name
                    ui.post { if (!destroyed) fail(name) }
                }
            })
        } catch (e: Throwable) {
            ui.post { if (!destroyed) fail(e.javaClass.simpleName) }
        }
    }

    private fun newEventListener() = object : TtsEventListener {
        override fun onStart() {
            ui.post {
                if (destroyed) return@post
                val (next, ok) = TtsCallbackLedger.onStart(ledger)
                ledger = next
                log(if (ok) "[TTS] 시작 세대=${next.currentId} t=${ms()}" else "[TTS] 시작 콜백 무시(대기 발화 없음·이미 시작, t=${ms()})")
                // 감사 5: 시작 전에 멈춘 발화가 있었으면 이 onStart가 그 발화의 늦은 시작일 수 있다(순서 기반 귀속의 남는 전제). 기록만 남긴다
                if (ok && stoppedBeforeStart) log("[TTS] 주의: 직전에 시작 전 발화를 멈춤 → 이 시작 콜백의 귀속은 순서 전제에 의존")
                stoppedBeforeStart = false
                if (ok && awaitFirstStartAfterReconnect) { awaitFirstStartAfterReconnect = false; log("[TTS-복구] 재연결 뒤 첫 시작 t=${ms()} (재연결 시작부터 ${ms() - reconnectStartedMs}ms)") }
                if (ok) { reconnect = TtsReconnectPolicy.onStartOk(reconnect); val g = next.currentId; ui.postDelayed({ onEndTimeout(g) }, pendingEndLimitMs); speakingChanged() }
            }
        }
        override fun onReady() {}
        override fun onUpdatedRms(p0: Double) {}
        override fun onDone() {
            ui.post {
                if (destroyed) return@post
                // 현재 발화(onStart 받은)의 완료만 처리한다. 앞선 발화의 늦은 onDone·중복 콜백은 새 발화의 포커스를 놓지 않는다
                if (!ownTerminal("완료")) return@post
                focus.abandon()
                if (pendingGen < 0) return@post
                val g = pendingGen; val q = pendingIsQuestion
                val drive = pendingIsDrive
                val exit = pendingIsExit
                pendingWasExample = pendingIsExample
                pendingGen = -1
                speakingChanged()
                if (exit) { log("[하차확인] 발화 완료 보고"); onExitResult?.invoke(g, TtsEnd.DONE); onIdle?.invoke(); return@post }
                if (pendingIsAuto) { pendingIsAuto = false; log("[자동안내] 발화 완료 보고 세대=$g"); onAutoResult?.invoke(g, TtsEnd.DONE); onIdle?.invoke(); return@post }
                if (drive) {   // 화면 상태 문구는 건드리지 않는다(로그만)
                    log("[주행안내] 발화 완료 보고")
                    onDriveResult?.invoke(g, true)
                    onIdle?.invoke()
                    return@post
                }
                log("[TTS] 완료")
                speech = "발화 완료(미검증: 에뮬레이터는 소리 확인 불가)"
                onChange()
                if (q) onQuestionResult(g, TtsEnd.DONE) else if (!pendingWasExample) onSpeechResult?.invoke(g, TtsEnd.DONE)
                if (pendingGen < 0) onIdle?.invoke()
            }
        }
        override fun onError(p0: TtsErrorType) {
            val name = p0.name
            ui.post {
                if (destroyed || !ownTerminal("오류 $name")) return@post
                finishUnconfirmed("[TTS] 오류 $name", "음성 안내 확인 불가(오류: $name)", TtsEnd.ERROR)
            }
        }
        override fun onStop() {
            ui.post {
                // 앞선 발화의 늦은 onStop이면 지금 대기 중인 발화에 붙이지 않는다(Unit 5.2 N1 → Unit 5.3 순서 기반)
                if (destroyed || !ownTerminal("중지")) return@post
                finishUnconfirmed("[TTS] 중지", "음성 안내 확인 불가(중지됨)", TtsEnd.ERROR)
            }
        }
    }

    /** 포커스 상실(메인 스레드): 대기 발화가 있을 때만 로그 후 TTS stop, 질문이면 확인 불가 경로로 */
    private fun onFocusLost() {
        if (destroyed || pendingGen < 0) return
        log("[TTS] 포커스 상실 → 중지")
        val t = tts
        if (t != null) { try { t.stop() } catch (e: Throwable) { log("[TTS] 오류 ${e.javaClass.simpleName}") }; noteAppStop() }
        finishUnconfirmed("[TTS] 중지", "음성 안내 중지됨(오디오 포커스 상실)", TtsEnd.FOCUS_LOST)
    }

    private fun finishUnconfirmed(logMsg: String, text: String, end: TtsEnd) {
        focus.abandon()
        if (destroyed || pendingGen < 0) return
        val g = pendingGen; val q = pendingIsQuestion
        val drive = pendingIsDrive
        val exit = pendingIsExit
        pendingGen = -1
        speakingChanged()
        if (exit) { log(logMsg.replace("[TTS]", "[하차확인]") + "(사유=${end.label}, 소진 안 함)"); onExitResult?.invoke(g, end); onIdle?.invoke(); return }
        if (pendingIsAuto) { pendingIsAuto = false; log(logMsg.replace("[TTS]", "[자동안내]") + "(사유=${end.label})"); onAutoResult?.invoke(g, end); onIdle?.invoke(); return }
        if (drive) {   // 화면 상태 문구는 건드리지 않는다(로그만). 세션 1회는 소모하지 않는다
            log(logMsg.replace("[TTS]", "[주행안내]") + " → 세션 1회 소모 안 함")
            onDriveResult?.invoke(g, false)
            onIdle?.invoke()
            return
        }
        log(logMsg)
        log("[TTS] 음성 안내 확인 불가")
        speech = text
        onChange()
        if (q) onQuestionResult(g, end) else if (!pendingIsExample) onSpeechResult?.invoke(g, end)
        if (pendingGen < 0) onIdle?.invoke()
    }

    /** 메인 스레드. 발화 요청에 성공하면 세대 번호, 실패(예외 포함)하면 -1 */
    /** 직전 [speak]가 SDK 호출 예외로 실패했는가(음성 꺼짐·미준비·포커스 거부와 구분). 2026-11-08 */
    var lastSpeakError = false; private set

    fun speak(text: String, isQuestion: Boolean, kindLabel: String? = null, isExample: Boolean = false, tripKeep: Boolean = false,
              yieldOnNavi: Boolean = false): Int {
        lastSpeakError = false
        val t = tts
        if (!GuidanceSetting.speechAllowed(guidanceEnabled)) return -1   // 안내 음성 꺼짐: 발화·포커스 요청 모두 하지 않는다
        if (!ready || t == null || destroyed) return -1
        // 4차 감사 R4: 진행 중 발화를 stop 없이 덮어쓰지 않는다. 먼저 멈추고 장부에 기록해 그 발화의 늦은 콜백을 거른다
        if (pendingGen >= 0) { log("[TTS] 새 발화 전 진행 중 세대=$pendingGen 중지"); stop() }
        if (reconnectWanted) { maybeReconnect(); if (!ready) { log("[TTS] 다시 연결 중 → 이번 발화 건너뜀(화면 안내)"); speech = "음성 안내 다시 연결 중(화면 문구로 안내)"; onChange(); return -1 } }
        if (naviWaitMs() > 0) {   // 내비가 말하는 중·직후: 세대는 지금 주고 내비가 끝나 쉰 뒤(최대 30초) 말한다. 결과는 같은 콜백으로
            val g = ++gen
            pendingGen = g; pendingIsQuestion = isQuestion; pendingIsExample = isExample; pendingIsDrive = false; pendingIsExit = false; pendingIsAuto = false
            pendingTripKeep = tripKeep && !isQuestion && !isExample
            pendingYieldable = yieldOnNavi; autoReqMs = -1L   // 끼어들기 판단은 실제 요청(runDeferred) 뒤부터
            deferIfNavi(g, text, "[TTS] ${kindLabel ?: if (isQuestion) "질문" else "안내"}")
            speech = "발화 요청됨(완료 미확인)"
            onChange()
            return g
        }
        val res = focus.requestFocus()
        if (focusSpeakAction(res) != FocusSpeakAction.SPEAK) {
            pendingGen = -1
            log("[TTS] 포커스 거부 → 발화 건너뜀")
            speech = "음성 안내 건너뜀(오디오 포커스 없음)"
            onChange()
            return -1
        }
        val g = ++gen
        pendingGen = g; pendingIsQuestion = isQuestion; pendingIsExample = isExample; pendingIsDrive = false; pendingIsExit = false; pendingIsAuto = false
        pendingTripKeep = tripKeep && !isQuestion && !isExample
        pendingYieldable = yieldOnNavi; autoReqMs = android.os.SystemClock.elapsedRealtime(); autoBaseline = naviState.activeCount
        noteSpeak(g, text.length)
        return try {
            t.speak(text, true)
            log("[TTS] 발화요청 질문=${kindLabel ?: if (isQuestion) "질문" else "안내"} 세대=$g" + if (pendingTripKeep) " 유지=이동 중(결정 24)" else "")
            speech = "발화 요청됨(완료 미확인)"
            onChange()
            g
        } catch (e: Throwable) {
            pendingGen = -1; ledger = TtsCallbackLedger.onAbandon(ledger)
            lastSpeakError = true
            focus.abandon()
            log("[TTS] 오류 ${e.javaClass.simpleName}")
            speech = "음성 안내 확인 불가(오류: ${e.javaClass.simpleName})"
            -1
        }
    }

    /**
     * 주행 중 벨트 안내 한 줄(메인 스레드). 화면 상태 문구(speech/onChange)는 쓰지 않고 로그만 남긴다.
     * 발화 요청에 성공하면 세대 번호, 건너뛰거나 실패하면 -1(이 경우 호출자는 세션 1회를 소모하지 않는다).
     */
    fun speakDrive(text: String): Int {
        val t = tts
        if (!GuidanceSetting.speechAllowed(guidanceEnabled)) { log("[주행안내] 조건 불충족(안내 음성 꺼짐)"); return -1 }
        if (!ready || t == null || destroyed) {
            if (!initFailed && !destroyed) { log("[주행안내] 음성 준비 중 → 기다림(실패로 세지 않음)"); return DRIVE_NOT_READY }
            log("[주행안내] 조건 불충족(음성 준비 안 됨)"); return -1
        }
        if (pendingGen >= 0) { log("[주행안내] 조건 불충족(다른 발화 진행 중)"); return -1 }
        if (reconnectWanted) { maybeReconnect(); if (!ready) { log("[주행안내] 음성 다시 연결 중 → 기다림(실패로 세지 않음)"); return DRIVE_NOT_READY } }
        if (naviWaitMs() > 0) { if (!naviWaitLogged) { naviWaitLogged = true; log("[주행안내] 내비 안내 음성 중·직후 → 기다림(실패로 세지 않음)") }; return DRIVE_NOT_READY }
        naviWaitLogged = false
        val res = focus.requestFocus()
        if (focusSpeakAction(res) != FocusSpeakAction.SPEAK) {
            log("[주행안내] 포커스 거부 → 건너뜀")
            return -1
        }
        val g = ++gen
        pendingGen = g; pendingIsQuestion = false; pendingIsExample = false; pendingIsDrive = true; pendingIsExit = false; pendingIsAuto = false; pendingTripKeep = false; pendingYieldable = false
        noteSpeak(g, text.length)
        return try {
            t.speak(text, true)
            log("[주행안내] 발화요청 세대=$g")
            g
        } catch (e: Throwable) {
            pendingGen = -1; pendingIsDrive = false; ledger = TtsCallbackLedger.onAbandon(ledger)
            focus.abandon()
            log("[주행안내] 오류 ${e.javaClass.simpleName} → 세션 1회 소모 안 함")
            -1
        }
    }

    /**
     * 하차 확인 한 줄(메인 스레드). 화면 상태 문구는 쓰지 않고 로그만 남긴다.
     * 반환: 0 이상=발화 요청 성공(세대 번호, 결과는 onExitResult), EXIT_SKIPPED=조건 불충족·다른 발화 진행 중,
     * EXIT_FAILED=포커스 거부·예외(강행·재시도하지 않음). 어느 쪽도 1회 소진이 아니다(소진은 DONE만, 2026-11-08 6C).
     * 진행 중인 발화(보호자 확인 음성·질문·예시·주행 안내)는 끊지 않는다.
     */
    fun speakExit(text: String): Int {
        val t = tts
        if (!GuidanceSetting.speechAllowed(guidanceEnabled)) { log("[하차확인] 건너뜀(안내 음성 꺼짐)"); return EXIT_SKIPPED }
        if (!ready || t == null || destroyed) { log("[하차확인] 건너뜀(음성 준비 안 됨)"); return EXIT_SKIPPED }
        if (pendingGen >= 0) { log("[하차확인] 미룸(다른 발화 진행 중, 끊지 않음)"); return EXIT_BUSY }
        if (reconnectWanted) { maybeReconnect(); if (!ready) { log("[하차확인] 건너뜀(음성 다시 연결 중)"); return EXIT_SKIPPED } }
        if (naviWaitMs() > 0) {   // 내비가 말하는 중·직후: 내비가 끝나 쉰 뒤(최대 30초) 말한다. 결과는 onExitResult
            val g = ++gen
            pendingGen = g; pendingIsQuestion = false; pendingIsExample = true; pendingIsDrive = false; pendingIsExit = true; pendingIsAuto = false; pendingTripKeep = false; pendingYieldable = false
            deferIfNavi(g, text, "[하차확인]")
            return g
        }
        val res = focus.requestFocus()
        if (focusSpeakAction(res) != FocusSpeakAction.SPEAK) {
            log("[하차확인] 포커스 거부 → 강행·재시도 안 함")
            return EXIT_FAILED
        }
        val g = ++gen
        // 예시 발화와 같이 일반 화면 갱신(stop keepExample)으로 끊기지 않게 표시한다
        pendingGen = g; pendingIsQuestion = false; pendingIsExample = true; pendingIsDrive = false; pendingIsExit = true; pendingIsAuto = false; pendingTripKeep = false; pendingYieldable = false
        noteSpeak(g, text.length)
        return try {
            t.speak(text, true)
            log("[하차확인] 발화요청 세대=$g")
            g
        } catch (e: Throwable) {
            pendingGen = -1; pendingIsExit = false; ledger = TtsCallbackLedger.onAbandon(ledger)
            focus.abandon()
            log("[하차확인] 오류 ${e.javaClass.simpleName}")
            EXIT_FAILED
        }
    }

    /** 자동 안내 결과(세대, 종료 사유). DONE만 발화 완료 */
    var onAutoResult: ((gen: Int, end: TtsEnd) -> Unit)? = null
    /** 자동 안내 발화가 진행 중인지 */
    val autoPending: Boolean get() = pendingGen >= 0 && pendingIsAuto

    /**
     * 2026-11-13 자동 안내 한 줄(메인 스레드). 진행 중인 발화는 끊지 않는다(호출자가 [idle]일 때 큐에서 하나씩 보낸다).
     * [keepOnGate]=true면 화면 갱신·잠금 정리(stop keepDrive=true)·화면 이탈로 끊지 않는다(출발·남은 시간·도착·하차 마무리).
     * false면 정차 게이트 이탈 정리로 끊긴다(자동 확인 단계의 벨트 재안내·추가 착석 안내).
     * 반환: 0 이상 = 요청 성공(세대, 결과는 [onAutoResult]), [AUTO_BUSY] = 다른 발화 진행 중, [AUTO_SKIPPED] = 음성 꺼짐·준비 안 됨·포커스 거부·예외.
     */
    fun speakAuto(text: String, label: String, keepOnGate: Boolean): Int {
        val t = tts
        if (!GuidanceSetting.speechAllowed(guidanceEnabled)) { log("[자동안내] 건너뜀(안내 음성 꺼짐) 종류=$label"); return AUTO_SKIPPED }
        if (!ready || t == null || destroyed) {
            if (!initFailed && !destroyed) { log("[자동안내] 음성 준비 중 → 기다림 종류=$label"); return AUTO_NOT_READY }
            log("[자동안내] 건너뜀(음성 준비 안 됨) 종류=$label"); return AUTO_SKIPPED
        }
        if (pendingGen >= 0) return AUTO_BUSY
        if (reconnectWanted) { maybeReconnect(); if (!ready) { log("[자동안내] 음성 다시 연결 중 → 기다림 종류=$label"); return AUTO_NOT_READY } }
        // r5 2절: 내비가 말하는 중이거나 끝난 직후면 기다린다(호출자가 naviWaitMs() 뒤 다시). 감시 못 하면 막지 않는다
        if (naviWaitMs() > 0) { if (!naviWaitLogged) { naviWaitLogged = true; log("[내비음성] 내비 안내 음성 중·직후 → 앱 안내 기다림 종류=$label") }; return AUTO_NAVI_WAIT }
        naviWaitLogged = false
        val res = focus.requestFocus()
        if (focusSpeakAction(res) != FocusSpeakAction.SPEAK) { log("[자동안내] 포커스 거부 → 강행하지 않음 종류=$label"); return AUTO_SKIPPED }
        val g = ++gen
        pendingGen = g; pendingIsQuestion = false; pendingIsExample = false; pendingIsDrive = false; pendingIsExit = false; pendingTripKeep = false
        pendingIsAuto = true; pendingAutoKeep = keepOnGate; pendingYieldable = false
        autoReqMs = android.os.SystemClock.elapsedRealtime(); autoBaseline = naviState.activeCount
        noteSpeak(g, text.length)
        return try {
            t.speak(text, true)
            log("[자동안내] 발화요청 종류=$label 세대=$g 유지=$keepOnGate")
            g
        } catch (e: Throwable) {
            pendingGen = -1; pendingIsAuto = false; ledger = TtsCallbackLedger.onAbandon(ledger)
            focus.abandon()
            log("[자동안내] 오류 ${e.javaClass.simpleName}")
            AUTO_SKIPPED
        }
    }

    companion object {
        /**
         * r10 N 스위치: 화면을 다시 그릴 때(재생성) 재생 중·대기 중 유지 음성(keep=true)을 새 화면으로 넘겨 이어 낸다(TtsCarryRule).
         * false = r9b와 같음(재생성 때 줄·재생 중 문장이 사라진다)
         */
        const val KEEP_ACROSS_RECREATE = true
        /** speakAuto: 다른 발화 진행 중(끊지 않음). 호출자가 onIdle 뒤 다시 보낸다 */
        const val AUTO_BUSY = -5
        /** speakAuto: 음성 꺼짐·준비 실패·포커스 거부·예외(재시도하지 않음) */
        const val AUTO_SKIPPED = -6
        /** speakAuto: 음성 엔진 초기화·재연결 중(실패 아님, 잠시 뒤 다시) */
        const val AUTO_NOT_READY = -7
        /** speakAuto: 내비 안내 음성 중·직후(r5 2절). 실패 아님, 호출자가 [naviWaitMs] 뒤 다시 */
        const val AUTO_NAVI_WAIT = -8
        const val EXIT_SKIPPED = -1
        const val EXIT_FAILED = -2
        /** speakExit: 다른 발화가 진행 중(끊지 않음). 호출자가 그 발화가 끝난 뒤 다시 평가한다(QA RISK 3) */
        const val EXIT_BUSY = -4
        /** speakDrive: 음성 엔진이 아직 초기화 중(실패 아님, 호출자가 잠시 뒤 다시 평가) */
        const val DRIVE_NOT_READY = -3
    }

    /** 주행 안내 발화가 진행 중일 때만 멈춘다(P 복귀·onPause 등). 다른 발화는 건드리지 않는다 */
    fun stopDrive() {
        if (drivePending) stop(true)
    }

    /**
     * r10 E(개발 1): 재생 중이거나 시작 전인 교통 상식만 멈춘다. [tipGen] = 호출자가 교통 상식(VoiceQueueRule.LABEL_TIP)으로
     * [speakAuto]에서 받은 세대. 그 세대가 아직 걸린 자동 안내일 때만 멈추고 true. 다른 발화(질문·주행 경고·하차 한 줄·다른 자동 안내)는 건드리지 않는다.
     * 결과는 다른 중지와 같이 onAutoResult(APP_STOP) → onIdle로 알린다(내비 때문에 멈춘 것이 아니므로 다시 줄에 서지 않음)
     */
    fun stopTip(tipGen: Int): Boolean {
        if (tipGen < 0 || pendingGen != tipGen || !pendingIsAuto) return false
        log("[교통상식] 정차 안내가 기다려 교통 상식을 멈춤 세대=$tipGen")
        stop(end = TtsEnd.APP_STOP)
        return true
    }

    /**
     * 대기 중인 발화가 있으면 이후 이벤트를 무시하도록 먼저 비운 뒤 stop() 한다. force=true면 대기가 없어도 호출.
     * keepDrive=true면 진행 중인 주행 벨트 안내는 끊지 않는다(P 아님 잠금 정리용). 기본값은 끊는다.
     */
    fun stop(force: Boolean = false, keepExample: Boolean = false, keepDrive: Boolean = false, end: TtsEnd = TtsEnd.APP_STOP, keepExit: Boolean = false) {
        val t = tts
        // 예시(keepExample)·주행 안내와 출발·도착 자동 안내(keepDrive)·하차 한 줄(keepExit, 결정 13)은 남긴다. 판정은 TtsKeepRule(순수, 단위시험)
        val p = TtsKeepRule.Pending(pendingGen >= 0, example = pendingIsExample, drive = pendingIsDrive,
            auto = pendingIsAuto, autoKeep = pendingAutoKeep, exit = pendingIsExit, tripKeep = pendingTripKeep)
        if (TtsKeepRule.keeps(p, keepExample, keepDrive, keepExit)) return
        val had = pendingGen >= 0
        val stoppedGen = pendingGen; val stoppedDrive = had && pendingIsDrive
        // 2026-11-08 P0-C 재감사: 일반 안내·질문도 명시적 취소 사유로 보고한다(예시·하차 한 줄은 기존대로 보고하지 않음)
        val stoppedQuestion = had && pendingIsQuestion
        val stoppedExit = had && pendingIsExit   // 2026-11-08 6C: 하차 한 줄도 중단 사유를 보고한다(소진 판단은 호출자)
        val stoppedAuto = had && pendingIsAuto
        val stoppedSpeech = had && !pendingIsDrive && !pendingIsExit && !pendingIsExample && !pendingIsQuestion && !pendingIsAuto
        pendingGen = -1
        pendingIsAuto = false
        if (had) speakingChanged()
        // 기다리던 쪽에 알린다(재진입 방지: 이 stop이 끝난 뒤 메인 루프에서). 주행 안내는 '전달 안 됨'으로 세대와 함께 보고
        if (had) ui.post {
            if (!destroyed) {
                if (stoppedDrive) onDriveResult?.invoke(stoppedGen, false)
                if (stoppedQuestion) onQuestionResult(stoppedGen, end)
                if (stoppedExit) onExitResult?.invoke(stoppedGen, end)
                if (stoppedSpeech) onSpeechResult?.invoke(stoppedGen, end)
                if (stoppedAuto) onAutoResult?.invoke(stoppedGen, end)
                if (pendingGen < 0) onIdle?.invoke()
            }
        }
        if (had || force) focus.abandon()
        // 내비를 기다리던 일반 안내(SDK에 아직 안 보냄): 예약만 지우고 SDK stop·장부 기록은 하지 않는다(force면 아래 정지)
        val wasDeferred = had && deferred?.g == stoppedGen
        if (wasDeferred) { deferred = null; ui.removeCallbacks(deferredRunnable); log("[TTS] 내비 대기 중 발화 취소 세대=$stoppedGen(사유=${end.label})"); if (speech.startsWith("발화 요청됨")) speech = "음성 안내 중지됨(완료 미확인)"; if (!force) return }
        if (!ready || t == null || (!had && !force)) { if (had) ledger = TtsCallbackLedger.onAbandon(ledger); return }
        // force 정지도 SDK가 onStop을 보낼 수 있으므로 장부에 기록한다(다음 발화에 붙지 않게)
        try { t.stop() } catch (e: Throwable) { log("[TTS] 오류 ${e.javaClass.simpleName}") }
        noteAppStop()
        if (had) { log("[TTS] 중지(사유=${end.label})"); if (speech.startsWith("발화 요청됨")) speech = "음성 안내 중지됨(완료 미확인)" }
    }

    fun release() {
        destroyed = true
        onIdle = null; onDriveResult = null; onExitResult = null; onSpeechResult = null; onSpeakingChanged = null; onAutoResult = null; onNaviQuiet = null; onNaviStart = null
        onReady = null
        naviWatch.stop()
        deferred = null; ui.removeCallbacks(deferredRunnable)
        focus.abandon()
        val t = tts
        pendingGen = -1
        if (t != null) {
            try { t.stop() } catch (_: Throwable) {}
            listener?.let { l -> try { t.removeEventListener(l) } catch (_: Throwable) {} }
            try { t.release() } catch (_: Throwable) {}
        }
        listener = null; tts = null; ready = false
    }
}
