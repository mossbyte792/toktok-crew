package com.crewguide.probe

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 잠금 사유 로그의 "아직 기록 전" 표지(실제 사유 문자열과 겹치지 않음)
private const val LOCK_UNSET = "\u0000unset"

// 로그 뷰 전체 텍스트 상한(문자 수). 넘으면 앞부분을 줄 단위로 잘라낸다
private const val LOG_MAX_CHARS = 20000

/** 구독 실패 자동 재연결 상수(여기 값만 바꾸면 조정된다) */
private object LinkRetry {
    const val INTERVAL_MS = ReconnectRule.FAST_INTERVAL_MS   // 빠른 재시도 간격
    const val MAX_RETRIES = ReconnectRule.FAST_MAX            // 빠른 재시도 횟수(그 뒤는 느린 재시도, SCREEN_EXIT_CHECK ⑦)
}

/** 보호자 확인 영역 전용 크기(질문 문구·예/아니오 버튼). 다른 화면에는 쓰지 않는다. DesignRules.currentSizes와 수동 동기화 */
private object GuardLayout {
    const val CORE_SP = 32f        // 질문 문구·예/아니오 글자(굵기 없음)
    const val GAP_DP = 24          // 버튼 사이·질문-버튼 사이 간격
    const val BUTTON_MIN_DP = 96   // 예/아니오 버튼 최소 높이
}

/** 차량 화면 가독성 상수(여기 값만 바꾸면 조정된다). 개발용 로그·모의 영역은 14sp(별도 지정) */
private object Readability {
    const val CORE_SP = 24f            // 질문·안내·응답 출처·'보호자가 예라고 답함' 문구
    const val BUBBLE_SP = 24f          // 말풍선
    const val NOTICE_BODY_SP = 20f     // 음성 사용 안내(가안) 펼침 본문(보조 정보)
    const val AUX_SP = 20f             // 그 밖의 보조 문구(보조 정보)
    const val CONTROL_SP = 24f         // 예/아니오/시작/좌석 버튼 글자
    const val SWITCH_SP = 24f          // 음성 스위치·고지 제목 글자
    const val MIN_TOUCH_DP = 76        // 터치 영역 최소 높이
    const val GAP_DP = 8               // 버튼 사이 간격
    const val CHARACTER_DP = 96        // 캐릭터 크기(아바타 표준 96dp, 꾸미기 미리보기용)
    const val HOME_CHARACTER_DP = 128  // 시작 화면 안내자 캐릭터 크기(시각적 중심)
    const val BUBBLE_MIN_DP = 64       // 말풍선 최소 높이(글자 크기는 BUBBLE_SP 그대로)
    const val DEV_LOG_HEIGHT_DP = 220  // 개발용 로그 뷰 높이
    // 색 값의 출처는 현재 팔레트(Palette.current, 주간/야간). 여기 이름은 사용처 유지용 별칭이다.
    // 야간 값을 담아야 해서 const를 쓰지 않고 읽을 때마다 현재 팔레트를 가리킨다(onCreate에서 Palette.select 뒤에 읽는다).
    val TEXT_COLOR: Int get() = Palette.argb(Palette.current.text)          // 주간: 흰 배경 위 검정 계열
    val BG_COLOR: Int get() = Palette.argb(Palette.current.screenBg)
    val BTN_BG: Int get() = Palette.argb(Palette.current.btnOnBg)           // 주간: 진한 파랑 + 흰 글자
    val BTN_TEXT: Int get() = Palette.argb(Palette.current.btnOnText)
    val BTN_OFF_BG: Int get() = Palette.argb(Palette.current.btnOffBg)      // 비활성: 연한 회색 배경 + 진한 글자(문구로도 구분)
    val BTN_OFF_TEXT: Int get() = Palette.argb(Palette.current.btnOffText)
}

/**
 * G1 탐침 앱: 좌석별 벨트 체결 / 점유 / 미체결 경고 / 기어 신호를
 * 에뮬레이터에서 실제로 수신하는지 확인한다. 판단 로직은 넣지 않는다.
 * 모든 줄은 logcat 태그 G1PROBE 로도 남긴다.
 */
class MainActivity : Activity() {

    private val tag = "G1PROBE"
    private val ui = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var vehicle: Vehicle? = null

    // Vehicle의 seat/safety/gear 속성이 Kotlin 메타데이터상 private이라 public getter를 리플렉션으로 호출한다
    private fun <T> Vehicle.api(getter: String): T {
        @Suppress("UNCHECKED_CAST")
        return this.javaClass.getMethod(getter).invoke(this) as T
    }
    private val Vehicle.seatApi: ai.pleos.playground.vehicle.api.Seat get() = api("getSeat")
    private val Vehicle.safetyApi: ai.pleos.playground.vehicle.api.Safety get() = api("getSafety")
    private val Vehicle.gearApi: ai.pleos.playground.vehicle.api.Gear get() = api("getGear")
    /** r6 4절: 문 위치 읽기(getCurrentDoorPosition·register/unregisterDoorPosition만, set 없음) */
    private val Vehicle.doorApi: ai.pleos.playground.vehicle.api.Door get() = api("getDoor")

    private val seats: List<Pair<String, SeatValue>> = listOf(
        "ROW_1_LEFT" to SeatValue.ROW_1_LEFT,
        "ROW_1_RIGHT" to SeatValue.ROW_1_RIGHT,
        "ROW_2_LEFT" to SeatValue.ROW_2_LEFT,
        "ROW_2_CENTER" to SeatValue.ROW_2_CENTER,
        "ROW_2_RIGHT" to SeatValue.ROW_2_RIGHT,
    )

    // 좌석 버튼 라벨용 짧은 이름(상수 맵, 보이는 문구는 기존 replace 결과와 같다)
    private val seatShort: Map<String, String> = seats.associate { (n, _) ->
        n to n.replace("ROW_", "R").replace("_", "").replace("LEFT", "L").replace("RIGHT", "R").replace("CENTER", "C")
    }
    // guardView 최소 줄 수(24sp). 일상 흐름 최악 조합(줄바꿈 없을 때): '예' 이후 = 예라고 답함 + 아이 안내 + 응답 출처 + 음성 안내 확인 불가 = 4줄.
    // 잠금 사유·재연결 문구 같은 예외 상태나 좁은 폭에서 줄바꿈이 생기면 4줄을 넘어 버튼 줄이 밀릴 수 있다(maxLines는 걸지 않음).
    private val GUARD_MIN_LINES = 4
    private val NEXT_MIN_LINES = 2    // nextView 최소 줄 수('다음 단계…' + '보호자가 예라고 답함')
    private var lastGuardText: String? = null    // guardView에 마지막으로 넣은 텍스트
    private var lastBubbleText: String? = null   // bubbleView에 마지막으로 넣은 텍스트

    // ---- 좌석 상태판: 실제(SDK) 값과 모의(SIM) 값을 출처와 함께 따로 보관한다 ----
    private val realBelt = mutableMapOf<String, Boolean?>()
    private val simBelt = mutableMapOf<String, Boolean>()
    private val occupied = mutableMapOf<String, Boolean?>()
    // false=실제 값 사용, true=벨트 값만 모의로 대체. release 빌드는 setter가 항상 false로 둔다(DevMockGuard, 지시 P0-1)
    private var useSim = false
        set(v) { field = DevMockGuard.admit(v) }
    private lateinit var boardView: TextView
    private lateinit var modeButton: Button

    // SDK 콜백은 다른 스레드에서 올 수 있으므로 맵은 항상 메인 스레드에서만 수정한다(ui.post 안에서 기록)
    // 신선도 규칙: 변화 이벤트가 GET 요청 이후에 도착했으면 그 GET 응답은 버린다(이벤트가 더 최신).
    // 번호 증가·비교·상태 변경은 모두 메인 스레드(ui.post 안)에서만 한다.
    private var readSeq = 0                                   // readAll 호출마다 1 증가(요청 번호)
    private val beltEventSeq = mutableMapOf<String, Int>()    // 좌석별 벨트 이벤트 수신 번호
    private val occEventSeq = mutableMapOf<String, Int>()     // 좌석별 점유 이벤트 수신 번호
    private var gearEventSeq = 0                              // 기어 이벤트(변화/실패) 수신 번호

    // 좌석 상태 모델 입력용: 지원 좌석 목록(null=아직 모름, 모를 때는 미지원으로 보지 않는다)과 읽기 실패 좌석
    private var supportedBelt: Set<String>? = null
    private var supportedOcc: Set<String>? = null   // 2026-11-13 점유 지원 좌석(null = 아직 모름)
    private val beltReadFailed = mutableSetOf<String>()

    // TODO: PERMISSION_DENIED 원인은 현재 코드에서 구분할 수 없어 연결하지 않았다(오류 콜백이 권한 거부를 따로 알려주지 않음).
    // TODO: INVALID 원인(유효성 판단 실패)은 판단 규칙·시간이 미정이라 연결하지 않았다. valid는 항상 true.
    private fun seatInputOf(name: String): SeatInput {
        if (useSim) {
            // 모의 값은 앱 내부 값이라 SDK 원인(지원·읽기 실패·연결)을 적용하지 않는다. 값이 없으면 NO_INITIAL_VALUE
            return SeatInput(name in targetSeats, true, SeatErrorState.NONE, simBelt[name], true, true)
        }
        return SeatInput(
            isTarget = name in targetSeats,
            supported = supportedBelt?.contains(name) ?: true,
            error = if (name in beltReadFailed) SeatErrorState.READ_FAILED else SeatErrorState.NONE,
            latestBuckled = realBelt[name],
            valid = true,
            // 기어·해당 좌석 벨트·점유 구독 실패/단절이면 LINK_LOST (등록 성공 시 해제되고 현재값 재조회 전까지 NO_INITIAL_VALUE)
            linkOk = !gear.subFailed && name !in beltSubFailed && name !in occSubFailed,
        )
    }

    // 벨트·점유 구독 상태(메인 스레드에서만 변경, 좌석별). 등록 호출이 예외 없이 끝나면 true,
    // 등록 예외·onFailed면 false. 등록 호출 성공은 실제 연결 확인이 아니다(미검증).
    // *SubFailed에 든 좌석은 실제 모드에서 UNKNOWN(LINK_LOST)로 표시한다(등록 시도 전에는 비어 있어 NO_INITIAL_VALUE).
    private val beltSubscribed = mutableMapOf<String, Boolean>()
    private val occSubscribed = mutableMapOf<String, Boolean>()
    private val beltSubFailed = mutableSetOf<String>()
    private val occSubFailed = mutableSetOf<String>()
    private val beltRegistered = mutableSetOf<String>()   // 현재 SDK에 리스너가 등록된 좌석(해제 후 등록용)
    private val occRegistered = mutableSetOf<String>()
    private var lastBoardText: String? = null             // 판정판은 내용이 바뀔 때만 다시 그린다

    /** 벨트 변화 이벤트. 이벤트 번호는 값이 같아도 올리고, 값이 바뀔 때만 로그·갱신한다 */
    private fun onBeltEvent(name: String, b: Boolean?) {
        ui.post {
            if (destroyed) return@post
            beltEventSeq[name] = (beltEventSeq[name] ?: 0) + 1
            beltWatch[name]?.let { beltWatch[name] = GearWatchdog.eventSeen(it, SystemClock.elapsedRealtime()) }   // 살아 있다는 근거
            val changed = !realBelt.containsKey(name) || realBelt[name] != b
            if (!changed) return@post
            log("[EVT] $name 벨트 체결 -> $b")
            realBelt[name] = b; if (b != null) beltReadFailed.remove(name); refreshBoard()
            renderStageOnly()   // 좌석 지도 배지·벨트 출처 문구를 새 값으로(판정·상태 변경 없음, ISSUE B 재수신 반영)
            beltWarnObserve(name, b)   // 화면 갱신 뒤, 주행 벨트 경고 판정(음성만, Unit 5.3 정책)
        }
    }

    // ---- 주행 벨트 경고(가안). 화면 표시 없음, 음성·로그만. 모든 상태는 메인 스레드에서만 변경 ----
    // 2026-11-08 Unit 5.3(ch DECISION 3·4): 해제 이벤트와 경고 전달을 분리(BeltWarningPolicy).
    // 세션 = 보호자 '예'(arm) ~ 명확한 종료(무장 후 주행을 본 뒤 유효한 P, [이동 끝내기], 새 '예', 앱 종료).
    // 화면 이탈(내비 전경 등)로는 끝내지 않는다. 앱 프로세스가 살아 있는 동안만 감시된다(서비스·오버레이 없음).
    // 주행 중 백그라운드 TTS 발화가 플랫폼 정책으로 허용되는지는 문서로 확인되지 않았다. 막히면 '전달 안 됨'으로 남고 완료 처리하지 않는다.
    private var beltWarn = BeltWarnState()
    private var beltMoved = false   // 이번 감시 세션(보호자 '예' 이후)에 주행(MOVING)을 본 적이 있는지
    /** 미전달 기록(메모리만). 화면 복귀 때 정차 화면에 보호자용 문구로 알린다. 새 '예'에서 비운다 */
    private val beltUndelivered = mutableListOf<UndeliveredRecord>()
    /** 감시 세션 중 신호 감시가 끊긴 적이 있는지(벨트·기어 수신 단절). 새 '예'에서 비운다 */
    private var beltMonitorGap: String? = null
    private val beltEvalRunnable = Runnable { runBeltEval() }
    /** 이동 후 P에서 경고가 진행 중이라 세션 종료를 그 결과 뒤로 미룸 */
    private var endAfterWarning = false
    /** debug 주입을 받은 좌석(실제 GET 무시). release는 주입 경로가 막혀 항상 비어 있다 */
    private val debugBeltHeld = mutableSetOf<String>()

    private fun driveContext(): DriveContext { val g = gear; return DriveContext(g.name, g.linkOk, g.subFailed, useSim) }

    private fun applyBeltStep(step: BeltWarnStep) {
        beltWarn = step.state
        for (n in step.notes) log("[주행안내] $n")
        if (step.records.isNotEmpty()) {
            beltUndelivered += step.records
            for (r in step.records) log("[주행안내] 미전달 기록 사유=${r.reason} 좌석=${r.seats.size}석")
            syncHomeNotes()
        }
    }

    /** 벨트 변화 이벤트(실제 수신): 해제 이벤트·재체결 반영 후 평가 */
    private fun beltWarnObserve(name: String, b: Boolean?) {
        guardianBuckledSeen = BeltRearmRule.onBeltValue(guardianBuckledSeen, name, b, TripGuideStore.state.guardianUnbuckledSeats)   // r8b
        if (b == true) releaseRebuckled(name, name)   // 루프 r2 L4: 다시 체결 즉시 다시 알림 멈춤(로그)
        if (!beltWarn.active) return
        val ctx = driveContext()
        // 2026-11-13 2단계 B: 자동 확인으로 시작한 이동에서 주행 중 체결 → 미체결은 좌석 이름 짧은 음성(BeltReleaseAlertRule)이 맡는다.
        // 이 경우 기존 정책에는 값만 기록(track)해 같은 풀림으로 주행 경고(세션 1회 문구)가 겹치지 않게 한다.
        // 재체결·확인 불가·정차·상태 불명(중립 경고)은 기존 정책 경로 그대로다.
        val driving = BeltReleaseRule.isDriving(ctx.gearName, ctx.linkOk, ctx.subFailed)
        if (TripGuideStore.state.confirmed && !ctx.simMode && driving && b == false && name in beltWarn.lastBuckled) {
            val now = SystemClock.elapsedRealtime()
            val (st, speak) = BeltReleaseAlertRule.onChange(TripGuideStore.alerts.release, name, name in beltWarn.armed, true, b, true, now)
            TripGuideStore.alerts = TripGuideStore.alerts.copy(release = st)
            beltWarn = BeltWarningPolicy.track(beltWarn, name, b)
            if (speak) {
                log("[벨트풀림] $name ${st.counts[name]}회째(주행 중, 음성만)")
                enqueueSay(BeltReleaseAlertRule.text(name), "벨트풀림", keep = true, setCaption = false, chime = true) {
                    // 보낼 때 다시 본다: 아직 주행 중이고 그 좌석이 여전히 미체결 신호일 때만
                    tripDriving() && realBelt[name] == false
                }
            } else log("[벨트풀림] $name 간격·횟수 제한으로 지금 말하지 않음(${st.counts[name] ?: 0}/${BeltReleaseAlertRule.MAX_PER_SEAT}, 풀림이 이어지면 간격 뒤 다시 알림)")
            scheduleReleaseRepeat()   // PM 임시 결정 31
            return
        }
        applyBeltStep(BeltWarningPolicy.observe(beltWarn, name, b, ctx, SystemClock.elapsedRealtime()))
        runBeltEval()
    }

    /** 미처리 해제 이벤트 평가 1회. 기어 변화·벨트 변화·발화 결과·발화 유휴·타이머·화면 복귀에서 부른다(멱등) */
    private fun runBeltEval() {
        if (destroyed) return
        ui.removeCallbacks(beltEvalRunnable)
        if (!beltWarn.active) return
        val now = SystemClock.elapsedRealtime()
        val t = tts
        val step = BeltWarningPolicy.evaluate(beltWarn, driveContext(), t != null && !t.idle, now)
        applyBeltStep(step)
        when (val a = step.action) {
            is BeltWarnAction.Speak -> {
                val text = if (a.kind == WarningKind.MOVING) ChildNotice.driveText(noticeTone()) else BeltWarningPolicy.NEUTRAL_TEXT
                val g = t?.speakDrive(text) ?: TtsGuide.DRIVE_NOT_READY
                if (g == TtsGuide.DRIVE_NOT_READY) {
                    ui.postDelayed(beltEvalRunnable, 1_000L)   // 음성 엔진 초기화 중: 실패로 세지 않고 다시 평가(재감사 2차)
                } else if (g >= 0) {
                    beltWarn = BeltWarningPolicy.requested(beltWarn, a.kind, a.seats, g)
                    log("[주행안내] ${BeltWarningPolicy.label(a.kind)} 요청 세대=$g 좌석=${a.seats.size}석 화면=${if (resumed) "앞" else "이탈"}")
                } else {
                    beltWarn = BeltWarningPolicy.rejected(beltWarn, now)
                    log("[주행안내] ${BeltWarningPolicy.label(a.kind)} 요청 안 됨 → 완료 처리 안 함(실패 ${beltWarn.failures}회), ${BeltWarningPolicy.RETRY_DELAY_MS}ms 뒤 재평가")
                    ui.postDelayed(beltEvalRunnable, BeltWarningPolicy.RETRY_DELAY_MS)
                }
            }
            is BeltWarnAction.RecheckAt -> ui.postDelayed(beltEvalRunnable, (a.atMs - now).coerceAtLeast(0L))
            BeltWarnAction.WaitTtsIdle -> ui.postDelayed(beltEvalRunnable, 1_000L)   // onIdle이 먼저 부르는 것이 보통. 안전망
            BeltWarnAction.None -> {}
        }
    }

    /** 변화 이벤트가 아닌 갱신(GET 응답·구독 단절): 값만 기록하고 이벤트를 만들지 않는다 */
    private fun beltReleaseTrack(name: String, b: Boolean?) {
        if (!beltWarn.active) return
        if (b == null && name in beltWarn.armed && name in beltWarn.lastBuckled) noteMonitorGap("$name 벨트 값 확인 불가")
        beltWarn = BeltWarningPolicy.track(beltWarn, name, b)
    }

    /** 감시 세션 중 신호 감시 끊김 기록(화면 복귀 때 알림). 같은 세션에서는 첫 사유만 남긴다 */
    private fun noteMonitorGap(why: String) {
        if (!beltWarn.active || beltMonitorGap != null) return
        beltMonitorGap = why
        log("[주행안내] 감시 끊김 기록: $why (이 구간의 벨트 변화는 알 수 없음)")
        syncHomeNotes()
    }

    /** r8b: 이번 이동에서 체결 신호를 본 '보호자 확인 미체결' 자리(메모리만) */
    private var guardianBuckledSeen: Set<String> = emptySet()
    /** r8 2절: 이번 이동에서 벨트를 감시하던 자리(확인 완료 때 정한 자리, 없으면 확인한 선택 좌석) */
    private fun tripBeltSeats(): Set<String> = flow.report?.seats?.takeIf { it.isNotEmpty() } ?: TripGuideStore.state.selected

    /**
     * r8 2절: 유효 P로 감시가 끝난 뒤 하차 마무리 없이 이동이 이어져(아니요·대답 없이 D·되돌리기 뒤) 다시 주행하면 같은 자리로 감시를 다시 켠다.
     * 다시 출발하는 순간 이미 미체결 신호인 자리는 기존 벨트 풀림 안내(문장·경고음·30초 간격·자리당 3회)로 한 번 알린다. 화면 변화 없음
     */
    private fun maybeRearmBeltSession(driving: Boolean) {
        val seats = tripBeltSeats()
        if (!BeltRearmRule.shouldRearm(beltWarn.active, TripKeepAliveRule.tripActive(TripGuideStore.state), driving, seats)) return
        beltWarn = BeltWarningPolicy.arm(seats, if (useSim) emptyMap() else realBelt)
        beltMoved = false; endAfterWarning = false
        log("[주행안내] 다시 주행 → 감시 다시 시작 대상=${beltWarn.armed.size}석 체결 감시=${beltWarn.lastBuckled.size}석(r8 2절)")
        if (useSim) return
        val now = SystemClock.elapsedRealtime()
        // r8b: 보호자가 확인한 미체결 신호 자리는 이번 이동에서 체결 신호를 한 번 보기 전까지 '이미 미체결' 안내에서 뺀다
        for (seat in BeltRearmRule.unbuckledAtDepart(seats, realBelt, TripGuideStore.state.guardianUnbuckledSeats, guardianBuckledSeen)) {
            val (st, speak) = BeltReleaseAlertRule.onChange(TripGuideStore.alerts.release, seat, armed = true, wasBuckled = true, newValue = false, driving = true, nowMs = now)
            TripGuideStore.alerts = TripGuideStore.alerts.copy(release = st)
            if (speak) {
                log("[벨트풀림] $seat 다시 출발 때 이미 미체결 신호 → ${st.counts[seat]}회째(주행 중, 음성만, r8 2절)")
                enqueueSay(BeltReleaseAlertRule.text(seat), VoiceQueueRule.LABEL_BELT, keep = true, setCaption = false, chime = true) {
                    tripDriving() && realBelt[seat] == false
                }
            } else log("[벨트풀림] $seat 다시 출발 때 미체결이나 간격·횟수 제한으로 지금 말하지 않음")
        }
        scheduleReleaseRepeat()
    }

    /** 세션 종료(명확한 종료 조건에서만): 미처리 이벤트 정리·기록, 진행 중 경고 발화 중지 */
    private fun endBeltReleaseSession(why: String) {
        endAfterWarning = false
        val had = beltWarn.active
        if (beltWarn.inFlight != null) { tts?.stopDrive(); beltWarn = BeltWarningPolicy.cancelled(beltWarn) }
        applyBeltStep(BeltWarningPolicy.end(beltWarn, SystemClock.elapsedRealtime()))
        ui.removeCallbacks(beltEvalRunnable)
        beltMoved = false
        if (had) log("[주행안내] 세션 종료($why)")
        if (had && (nightPending || layoutPending) && !destroyed) ui.post { maybeApplyNight() }   // 보류한 야간 전환 재평가(안전 조건 그대로)
    }

    /** 홈(정차 화면)에 보일 보호자용 알림: 미전달 경고·감시 끊김·앱 내부 모의 기어 */
    private fun homeNotes(): String? {
        val lines = mutableListOf<String>()
        if (beltUndelivered.isNotEmpty()) lines += BeltNotice.undelivered(beltUndelivered.size)
        beltMonitorGap?.let { lines += BeltNotice.GAP }
        if (mockGearOn) lines += BeltNotice.MOCK_GEAR
        if (devTools.scenarioRunning()) lines += SCENARIO_NOTE
        if (startupHoldHome()) lines += StartupHomeRule.NOTE_CHECKING   // 루프 r1 K4: [시작] 비활성 이유(작은 글)
        // P1-7: 보호자 확인이 멈춘 이유(정차 이탈·화면 이탈·상태 불명). 보고 후 감시 세션이 이어지는 중이면 '다시 시작' 안내가 오해를 주므로 뺀다
        flow.interruption?.let { if (!beltWarn.active) lines += it.text }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    private fun syncHomeNotes() { if (!destroyed) homeScreen?.setNotes(homeNotes()) }

    /** 같은 프로세스에서 다시 만들어진 Activity: 이전 화면의 감시 세션·미전달 기록을 이어받는다(onCreate) */
    private fun restoreBeltSession() {
        val snap = BeltSessionStore.take() ?: return
        val now = SystemClock.elapsedRealtime()
        if (snap.state.active && BeltSessionStore.expired(snap, now)) {
            beltUndelivered.clear(); beltUndelivered += snap.undelivered; beltMonitorGap = snap.gap ?: "이전 화면의 감시 세션이 오래되어 이어받지 않음"
            log("[주행안내] 보관된 감시 세션이 ${BeltSessionStore.MAX_AGE_MS}ms를 넘어 이어받지 않음(기록만 이어받음)")
            return
        }
        beltWarn = BeltSessionStore.resume(snap, now)
        beltMoved = snap.moved
        beltUndelivered.clear(); beltUndelivered += snap.undelivered
        beltMonitorGap = snap.gap
        if (beltWarn.active) {
            if (snap.state.inFlight != null) log("[주행안내] 이전 화면에서 진행 중이던 경고는 끊김 → 전달 안 됨(실패 1회)")
            noteMonitorGap("앱 화면이 다시 만들어지는 동안(${now - snap.savedAtMs}ms+)")
            log("[주행안내] 감시 세션 이어받음 대상=${beltWarn.armed.size}석 체결 감시=${beltWarn.lastBuckled.size}석 미처리=${beltWarn.pending.size}건 주행관측=$beltMoved")
        } else if (beltUndelivered.isNotEmpty() || beltMonitorGap != null) log("[주행안내] 이전 화면의 미전달·끊김 기록 이어받음")
    }

    /** 벨트 구독 실패(onFailed): 플래그 false, 값 비움, LINK_LOST. 이벤트 번호를 올려 진행 중 GET을 버리게 한다 */
    private fun onBeltSubFailed(name: String, why: String) {
        ui.post {
            if (destroyed) return@post
            beltEventSeq[name] = (beltEventSeq[name] ?: 0) + 1
            if (beltSubscribed[name] == false && name in beltSubFailed) return@post   // 이미 단절 표시됨: 반복 로그 없음
            beltSubscribed[name] = false; beltSubFailed.add(name); beltRegistered.remove(name); realBelt[name] = null
            beltReleaseTrack(name, null)
            log("[LINK] $name 벨트 구독 단절($why) → 확인 불가(연결 단절)")
            refreshBoard()
        }
    }
    private fun onOccSubFailed(name: String, why: String) {
        ui.post {
            if (destroyed) return@post
            occEventSeq[name] = (occEventSeq[name] ?: 0) + 1
            if (occSubscribed[name] == false && name in occSubFailed) return@post
            occSubscribed[name] = false; occSubFailed.add(name); occRegistered.remove(name); occupied[name] = null
            log("[LINK] $name 점유 구독 단절($why) → 확인 불가(연결 단절)")
            refreshBoard()
        }
    }
    private fun onBeltGetFailed(name: String, reqRead: Int) {
        ui.post { if (destroyed || reqRead != readSeq) return@post; beltReadFailed.add(name); refreshBoard() }
    }
    private fun onBeltCaps(names: Set<String>) {
        ui.post { if (destroyed) return@post; supportedBelt = names; refreshBoard() }
    }
    /** 2026-11-13: 점유(착석) 지원 좌석. 자동 확인 행(지원 좌석만)·착석 확인 불가 판정에 쓴다 */
    private fun onOccCaps(names: Set<String>) {
        ui.post { if (destroyed) return@post; supportedOcc = names; refreshBoard() }
    }
    /** b는 의미값(true = 착석 신호). SDK 값은 콜백에서 SeatOccPolarity로 바꿔 넘긴다. raw는 로그용 원래 값 표기 */
    private fun onOccEvent(name: String, b: Boolean?, raw: String? = null) {
        ui.post {
            if (destroyed) return@post
            occEventSeq[name] = (occEventSeq[name] ?: 0) + 1
            val changed = !occupied.containsKey(name) || occupied[name] != b
            if (!changed) return@post
            log("[EVT] $name 점유 -> $b" + (raw?.let { " ($it)" } ?: ""))
            occupied[name] = b; refreshBoard()
        }
    }
    /** GET 응답: reqRead/reqEvent는 요청 시점(메인 스레드)에 기록한 값. 그 뒤 이벤트나 새 읽기가 있었으면 버린다 */
    private fun onBeltGet(name: String, reqRead: Int, reqEvent: Int, b: Boolean?) {
        ui.post {
            if (destroyed || reqRead != readSeq || (beltEventSeq[name] ?: 0) != reqEvent) { Log.i(tag, "오래된 벨트 GET 버림 $name"); return@post }
            if (name in debugBeltHeld) { Log.i(tag, "[디버그주입] 주입 중인 좌석 $name 의 GET 무시"); return@post }
            // 구독이 죽은 채 GET 한 번으로 정상으로 돌아가지 않게, 구독 플래그가 true일 때만 반영한다
            if (beltSubscribed[name] != true) { Log.i(tag, "벨트 구독 단절 중 GET 무시 $name"); return@post }
            if (realBelt.containsKey(name) && realBelt[name] == b) { if (b != null) beltReadFailed.remove(name); return@post }
            realBelt[name] = b; if (b != null) beltReadFailed.remove(name)
            // Unit 5.3: 재조회 값과 감시 기록을 합친다. 체결로 감시 중이던 좌석이 새 GET에서 미체결이면 그 사이의 해제로 보고 이벤트로 처리
            if (b == false && name in beltWarn.lastBuckled) { log("[주행안내] $name 재조회 값 미체결(그 사이 해제) → 해제 이벤트로 처리"); beltWarnObserve(name, b) }
            else beltReleaseTrack(name, b)
            refreshBoard()
        }
    }
    private fun onOccGet(name: String, reqRead: Int, reqEvent: Int, b: Boolean?) {
        ui.post {
            if (destroyed || reqRead != readSeq || (occEventSeq[name] ?: 0) != reqEvent) { Log.i(tag, "오래된 점유 GET 버림 $name"); return@post }
            if (name in debugOccHeld) { Log.i(tag, "[디버그주입] 주입 중인 좌석 $name 의 점유 GET 무시"); return@post }
            if (occSubscribed[name] != true) { Log.i(tag, "점유 구독 단절 중 GET 무시 $name"); return@post }
            if (occupied.containsKey(name) && occupied[name] == b) return@post
            occupied[name] = b; refreshBoard()
        }
    }

    /** 판정 규칙(수정본 3-2): 점유된 좌석만 대상, 대상이 없거나 값이 없으면 완료를 말하지 않는다 */
    private fun refreshBoard() {
        drawBoard()
        renderStageOnly()   // ISSUE B(QA 2026-11-08): 좌석 보드가 바뀌면 무대 지도·출처 표기도 같이 갱신(보일 때만 그림)
        evaluateLink()   // 구독 실패 상태가 바뀌었을 수 있으므로 재시도 예약 여부를 점검한다(멱등)
        requestAutoEval()   // 2026-11-13: 착석·벨트 값이 바뀌었을 수 있다 → 자동 확인·하차 마무리 재평가(다음 루프, 중복 예약 없음)
    }

    // ---- 구독 실패 자동 재연결(제한된 재시도). 모든 상태는 메인 스레드에서만 변경한다 ----
    private var subAttempted = false          // registerAll 이후에만 true(등록 시도 전 상태를 실패로 보지 않음)
    private var resumed = false               // onResume~onPause 사이에만 예약
    /** r6a 2절: 지난 onResume 뒤 onStop이 있었는가(처음은 true). onResume 끝에서 false, onStop에서 true */
    private var stoppedSinceResume = true
    private var linkRetryCount = 0
    private var linkRetryPending = false
    private var linkExhausted = false
    private var linkEpisode = false           // 이번 실패 구간에서 재연결 로그를 남겼는지(성공 로그 1회용)
    private var inLinkRetry = false           // 재시도·수동 재읽기 실행 중에는 재진입 점검을 건너뛴다
    private val linkRetryRunnable = Runnable { runLinkRetry() }

    /** 기어·벨트·점유 중 하나라도 구독 실패 상태인지(등록 호출 기준, 실제 연결 확인 아님) */
    private fun linkFailed(): Boolean =
        subAttempted && vehicle != null && (!gearSubscribed || beltSubFailed.isNotEmpty() || occSubFailed.isNotEmpty())

    private fun gearLinkFailed(): Boolean = subAttempted && vehicle != null && !gearSubscribed

    private fun linkRetryText(): String? {
        if (!linkFailed()) return null
        if (useSim) return "신호 연결이 끊겼어요(자동 재연결 안 함)"
        if (linkExhausted) return "신호 연결이 끊겼어요. ${ReconnectRule.SLOW_INTERVAL_MS / 1000}초마다 다시 연결해 봐요"
        return "신호 연결이 끊겼어요. 다시 연결하는 중이에요($linkRetryCount/${LinkRetry.MAX_RETRIES})"
    }

    private fun cancelLinkRetryPending() {
        ui.removeCallbacks(linkRetryRunnable); linkRetryPending = false
    }

    private fun redrawLink() { refreshGuardian(); drawBoard() }

    /** 메인 스레드 전용. 실패면 재시도를 한 번만 예약하고, 복구되면 카운터·예약을 지운다 */
    private fun evaluateLink() {
        if (destroyed || inLinkRetry) return
        if (!linkFailed()) {
            cancelLinkRetryPending()
            val wasActive = linkEpisode || linkRetryCount > 0 || linkExhausted
            linkRetryCount = 0; linkExhausted = false; linkEpisode = false
            if (wasActive) {
                log("[LINK] 구독 복구 표시(등록 호출 기준, 실제 수신 확인 아님)")
                redrawLink()
            }
            return
        }
        // Unit 5.3 감사 1: 감시 세션 중에는 화면 밖에서도 재연결한다(화면 복귀를 기다리면 남은 주행 동안 감시가 사실상 끊김)
        if (useSim || !(resumed || beltWarn.active)) { cancelLinkRetryPending(); return }
        if (linkRetryPending) return
        if (ReconnectRule.slow(linkRetryCount) && !linkExhausted) {
            // 2026-11-14 SCREEN_EXIT_CHECK ⑦: 빠른 재시도 상한 뒤에도 멈추지 않고 느린 재시도로 계속한다(화면이 보일 때만)
            linkExhausted = true; linkEpisode = true
            log("[LINK] 빠른 재연결 ${LinkRetry.MAX_RETRIES}회 상한 → ${ReconnectRule.SLOW_INTERVAL_MS}ms 간격 느린 재시도로 계속(화면이 보일 때만)")
            redrawLink()
            if (linkRetryPending || destroyed) return   // redrawLink 안에서 이미 예약했으면 두 번 걸지 않는다
        }
        val delay = ReconnectRule.linkDelay(linkRetryCount, visible = started, watchActive = beltWarn.active) ?: return
        linkRetryPending = true
        ui.postDelayed(linkRetryRunnable, delay)
    }

    private fun runLinkRetry() {
        linkRetryPending = false
        if (destroyed || !(resumed || beltWarn.active) || useSim || !linkFailed()) { evaluateLink(); return }
        linkRetryCount++
        if (linkRetryCount == 1) { linkEpisode = true; log("[LINK] 구독 자동 재연결 시작(간격 ${LinkRetry.INTERVAL_MS}ms, 빠른 재시도 ${LinkRetry.MAX_RETRIES}회 뒤 ${ReconnectRule.SLOW_INTERVAL_MS}ms 간격)") }
        else if (linkRetryCount > LinkRetry.MAX_RETRIES) log("[LINK] 느린 재시도 ${linkRetryCount - LinkRetry.MAX_RETRIES}회째(간격 ${ReconnectRule.SLOW_INTERVAL_MS}ms, 전체 ${linkRetryCount}회)")
        inLinkRetry = true
        try { readAll() } finally { inLinkRetry = false }   // 재등록이 먼저, 그다음 재조회(readAll 안의 순서)
        redrawLink()
        evaluateLink()
    }

    /** '다시 읽기' 버튼: 카운터를 0으로 되돌리고 재등록·재조회만 한다(보호자 확인 진행·질문 없음) */
    private fun manualReread() {
        if (lockReason() != null && !gearLinkFailed()) { refreshGuardian(); return }   // 구독 실패가 원인이 아닌 잠금
        rereadGear()
    }

    /** 재등록·재조회 본체('다시 읽기'와 모의 기어 끄기가 함께 쓴다) */
    private fun rereadGear() {
        cancelLinkRetryPending()
        linkRetryCount = 0; linkExhausted = false
        inLinkRetry = true
        try { readAll() } finally { inLinkRetry = false }
        redrawLink()
        evaluateLink()
    }

    private fun drawBoard() {
        val sb = StringBuilder()
        sb.append(if (useSim) "벨트 출처: [모의 SIM] (점유·기어는 실제)\n" else "벨트 출처: [실제 SDK]\n")
        val results = mutableListOf<SeatResult>()
        for ((name, _) in seats) {
            val r = computeSeatStatus(seatInputOf(name))
            results.add(r)
            val occ = occupied[name]
            val mark = if (occ == true) "탑승" else if (occ == false) "빈 좌석" else "점유 불명"
            // 점유 신호는 참고 표시일 뿐 대상 여부에 쓰지 않는다(대상은 보호자가 고른 좌석만)
            sb.append(String.format("%-13s %s  (점유 참고: %s)\n", "$name:", seatStatusText(r), mark))
        }
        val targets = results.count { it.status != SeatStatus.EXCLUDED }
        val unknown = results.count { it.status == SeatStatus.UNKNOWN }
        val unbuckled = results.count { it.status == SeatStatus.UNBUCKLED }
        val verdict = when {
            targets == 0 -> "보호자가 고른 대상 좌석 없음 → 완료 안내 안 함"
            unknown > 0 -> "확인 불가 ${unknown}석 → 완료 안내 안 함"
            isAllBuckled(results.map { it.status }) -> "선택한 ${targets}석의 체결 신호를 확인했어요(착용 상태 보장 아님)"
            else -> "미체결 신호 ${unbuckled}석 → 묶음 안내 필요"
        }
        sb.append("판정: ").append(beltSourceLabel()).append(' ').append(verdict)
        lockShownText()?.let { sb.append("\n잠금: ").append(it).append(" → 조작 버튼 사용 불가(읽기 전용)"); linkRetryText()?.let { sb.append('\n').append(it) } }
        val text = sb.toString()
        if (text == lastBoardText) return   // 변화가 있을 때만 다시 그린다
        lastBoardText = text
        boardView.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 보호자 조작 버튼 공통 모양: 큰 글자, 최소 높이, 진한 배경+흰 글자(비활성은 연회색+진한 글자) */
    private fun styleControl(b: Button, sp: Float, minPx: Int) {
        b.textSize = sp
        b.typeface = Typeface.DEFAULT   // 굵기는 기본(bold 지양)
        b.isAllCaps = false
        b.minHeight = minPx; b.minimumHeight = minPx
        b.setPadding(dp(4), 0, dp(4), 0)
        b.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Readability.BTN_OFF_TEXT, Readability.BTN_TEXT)))
        val off = android.graphics.drawable.GradientDrawable().apply { setColor(Readability.BTN_OFF_BG); cornerRadius = dp(8).toFloat() }
        val on = android.graphics.drawable.GradientDrawable().apply { setColor(Readability.BTN_BG); cornerRadius = dp(8).toFloat() }
        b.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), off)
            addState(intArrayOf(), on)
        }
    }

    private fun beltSourceLabel(): String = if (useSim) "[앱 내부 모의]" else "[실제 수신]"

    /** [보호자확인] 로그: 벨트 값 출처와 대상 좌석 수를 함께 남긴다(인식 원문 없음) */
    private fun guardLog(msg: String) {
        log("[보호자확인] $msg | 벨트 값 출처=${if (useSim) "모의" else "실제"}, 대상 좌석 ${targetSeats.size}석")
    }

    private fun toggleSim(name: String) {
        if (!DevMockGuard.allowed) { log(DevMockGuard.BLOCKED_LOG + "(모의 벨트)"); return }
        if (lockReason() != null) { refreshGuardian(); return }
        val next = !(simBelt[name] ?: false)
        simBelt[name] = next
        log("[SIM] $name 벨트 체결 -> $next  (※ 모의 신호, 실제 수신 아님)")
        refreshBoard()
    }

    // ---- 경고등(Telltale) 읽기: 콘솔의 Seat Belt Off/On/Blink가 SDK로 오는지 확인 ----
    private var telltaleListener: ai.pleos.playground.vehicle.listener.TelltaleInfoListener? = null
    private var lastBeltTelltale: String? = null

    private fun logTelltales(src: String, t: ai.pleos.playground.vehicle.api.model.telltale.Telltales?) {
        val list = t?.telltales
        if (list == null) { log("[$src] 경고등 목록 null"); return }
        val belt = list.filter { it.toString().contains("BELT", ignoreCase = true) }
        val summary = if (belt.isEmpty()) "벨트 항목 없음" else belt.joinToString(" | ") { it.toString() }
        if (src == "TT-EVT" || summary != lastBeltTelltale) log("[$src] 경고등 ${list.size}개, 벨트 관련: $summary")
        lastBeltTelltale = summary
        // PM 결정 22: SEAT_BELT ON/BLINK = 앞줄 미체결, 항목 없음(또는 OFF) = 앞줄 체결
        val on = list.any {
            it.type == ai.pleos.playground.vehicle.constant.telltale.TelltaleType.SEAT_BELT &&
                (it.state == ai.pleos.playground.vehicle.constant.telltale.TelltaleState.ON ||
                    it.state == ai.pleos.playground.vehicle.constant.telltale.TelltaleState.BLINK)
        }
        ui.post { onTelltaleBelt(on, src) }
    }
    // ================= 보호자 확인 흐름 (모의, 터치 응답만) =================
    // 잠정 기본값. 사용자 미결정 항목이라 여기 상수만 바꾸면 되게 분리했다.
    private object GuardianDefaults {
        const val RESPONSE_BY_TOUCH = true          // 응답 방식: 터치만. 음성 응답은 미구현(아래 TODO)
        const val MAX_REANNOUNCE = 2                // 재안내 최대 횟수 (최초 1회 제외)
        /**
         * 안내 발화 종료 콜백을 기다리는 마지막 안전망(교착 방지 전용, TtsGuide 시작 6초 + 종료 20초 상한보다 길게: 콜백 경로가 먼저 정리한다).
         * 여기 도달은 '안내 완료'가 아니라 UNCONFIRMED이며, 남은 발화는 명시적으로 멈춘다(2026-11-08 P0-C 재감사)
         */
        const val ANNOUNCE_MAX_MS = 30000L
        const val RESPONSE_TIMEOUT_MS = 15000L      // 질문 후 무응답 시간 초과
        // P로 인정하는 기어 이름. 대소문자 무시 정확 일치로만 비교한다(VehicleGear 상수 직접 비교 안 함).
        // 'GEAR_PARK'는 00_인수인계.md:366의 기록에 근거한 것이며 SDK 원본으로 확인한 값이 아니다.
        // 에뮬레이터 로그의 "[EVT] 기어 -> <문자열>"로 실제 이름을 확인해야 한다(미검증).
        // 실제 값은 GearLock.kt의 PARK_GEAR_NAMES로 옮겼다(판정 로직을 순수 함수로 분리).
        const val ANNOUNCE_TEXT = UserTexts.GUARDIAN_PROMPT
        const val QUESTION_TEXT = UserTexts.GUARDIAN_QUESTION
        // 아이 안내 문구(가안, 원문 대조 전)는 ToneSetting.kt의 말투 A/B 상수에서 발화·표시 시점에 고른다.
        const val CHILD_NOTICE_LABEL = "아이 안내"   // 2026-11-14: "(가안)" 표기 지움(옛 흐름 화면 글자)
        const val VOICE_YES_SOURCE = "음성으로 예(보호자 보고, 앱이 확인한 것 아님)"
    }

    private enum class GState { IDLE, ANNOUNCING, ASKING, WAITING_GUARDIAN, YES_REPORTED }

    // ---- 보호자 확인 단계의 유일한 원천: BoardingFlow 상태 기계(2026-11-07 Unit 5.1, ch DECISION 3 P0-4) ----
    // 화면·TTS·STT·타이머는 BoardingFlow.reduce가 돌려준 효과를 실행만 한다. GState는 기존 화면 코드용 파생 값이다.
    private var flow = BoardingState()
    private val gState: GState get() = gStateOf(flow.step)
    private fun gStateOf(step: BoardingStep): GState = when (step) {
        BoardingStep.HOME, BoardingStep.CHILD_SELECT, BoardingStep.SEAT_SELECT -> GState.IDLE
        // 2026-11-13: 자동 확인 중은 '진행 중'으로 본다(홈 숨김·설정/꾸미기 차단·끝내기 숨김·야간 재생성 보류가 그대로 맞음)
        BoardingStep.ANNOUNCING, BoardingStep.AUTO_CHECK -> GState.ANNOUNCING
        BoardingStep.ASKING -> GState.ASKING
        BoardingStep.WAITING_GUARDIAN -> GState.WAITING_GUARDIAN
        BoardingStep.CHILD_NOTICE, BoardingStep.FINISHED -> GState.YES_REPORTED
    }
    // 잠금 사유 이전 값(메인 스레드에서만 비교·갱신). 첫 기록 전에는 LOCK_UNSET
    private var prevLockLogged: String? = LOCK_UNSET

    // ---- 플랫폼 주행 제한(CarUxRestrictionsManager). 값을 받기 전에는 UNKNOWN(정차 조작을 열지 않음) ----
    private var platformUx = PlatformUx.UNKNOWN
    private var platformSource: PlatformUxSource? = null

    private fun onPlatformUx(p: PlatformUx, why: String) {
        if (destroyed) return
        val wasFresh = platformEpoch == gateEpoch
        if (p == platformUx && wasFresh) return
        log("[플랫폼] 주행 제한 ${platformUx.name} -> ${p.name} ($why)" + gateLogSuffix())
        platformUx = p
        platformEpoch = gateEpoch   // 이번 화면 구간에서 받은 값
        onGateInputsChanged()
    }

    // ---- 게이트 입력 신선도(ch 승인 조건 ④). 화면 복귀마다 epoch를 올리고, 그 뒤 다시 받은 기어·플랫폼 값만 게이트에 쓴다 ----
    private var gateEpoch = 0
    /** M01: 게이트 로그 꼬리표(출처·세대). 표시 전용, 판정에 쓰지 않는다 */
    private fun gateLogSuffix(): String = GateLogTag.suffix(GateLogTag.gearSource(mockGearOn, gear.name != null), gateEpoch, gearEpoch, platformEpoch)
    private var gearEpoch = -1
    private var platformEpoch = -1

    /** 게이트·잠금 판단에 쓰는 기어(오래되면 미수신으로 본다) */
    private fun freshGear(g: GearState = gear): GearState {
        val s = GateFreshness.gear(GearSnapshot(g.name, g.linkOk, g.subFailed), gearEpoch, gateEpoch)
        return GearState(s.name, s.linkOk, s.subFailed)
    }

    private fun freshPlatform(): PlatformUx = GateFreshness.platform(platformUx, platformEpoch, gateEpoch)

    /**
     * 게이트 입력. 모의 값은 debug 빌드의 모의 기어를 켰을 때만 쓰고(출처 APP_MOCK), 그때 플랫폼 값도 앱 내부 모의 허용으로 표시한다.
     * release는 mockGearOn이 항상 false이고 GateInput.forBuild가 모의 입력을 RESTRICTED로 본다(이중 차단).
     */
    private fun gateInput(g: GearState = freshGear()): GateInput {
        val mock = mockGearOn
        val origin = if (mock) SignalOrigin.APP_MOCK else SignalOrigin.SDK
        // r6 QA 후속: 정차 게이트의 전경 = 화면에 보임(onStart~onStop, ScreenPresenceRule). 다중 창 포커스 이동·같은 화면에 인텐트를
        // 다시 받을 때의 짧은 onPause는 이탈이 아니다(QA: 빠른 시작 확인 중 PendingIntent 재전송 → onPause → '화면 이탈' 확인 취소)
        return GateInput.forBuild(g.name, g.linkOk, g.subFailed, origin, if (mock) PlatformUx.ALLOWED else freshPlatform(),
            ScreenPresenceRule.displayForeground(started, resumed), origin)
    }

    private fun currentGate(): VehicleGate = VehicleGateRule.evaluate(gateInput())

    /** 기어 이름만으로 본 잠금(움직임 판단용: 이동 끝·주행 안내 세션). 화면 조작 허용에는 [lockReason]을 쓴다 */
    private fun gearOnlyLock(): String? = gearLockReason(gear.name, gear.linkOk, gear.subFailed)

    /** 메인 스레드 전용. 상태 기계 결과를 반영하고 효과를 실행한다. 단계가 바뀔 때만 로그(인식 원문 없음) */
    private fun commitFlow(next: BoardingState, effects: List<BoardingEffect>, reason: String) {
        val prev = gState
        val prevStep = flow.step
        val prevInterruption = flow.interruption
        flow = next
        val now = gState
        if (prev != now) log("[보호자상태] $prev -> $now | 사유=$reason")
        if (prev != now && now != GState.IDLE) scrollOuterToTop()
        if (next.interruption != null && next.interruption != prevInterruption) log("[보호자상태] 멈춤 안내: ${next.interruption.name}")
        for (e in effects) runFlowEffect(e, reason)
        // 2026-11-13 계약 v2: 좌석 화면 확인이 취소돼 HOME이 되면(P 이탈·화면 이탈 등) 옛 선택 화면(PICK)이 아니라 홈을 보인다
        if (prevStep == BoardingStep.AUTO_CHECK && next.step == BoardingStep.HOME && StageFlags.NEW_STAGE) {
            homeActive = true
            log("[자동확인] 확인 취소($reason) → 홈")
        }
        refreshGuardian()
        if (prevStep == BoardingStep.AUTO_CHECK && next.step == BoardingStep.HOME) syncHome()
    }

    private fun dispatchFlow(e: BoardingEvent, reason: String) {
        val (next, effects) = BoardingFlow.reduce(flow, gateInput(), e)
        commitFlow(next, effects, reason)
    }

    /** 게이트가 정차 허용이 아니면 공통 취소(P 이탈·불명·화면 이탈 구분은 BoardingFlow.interruptionFor) */
    private fun cancelFlowForGate() {
        if (flow.step == BoardingStep.HOME) return
        val input = gateInput()
        // 결정 24 ②③: 이동(FGS) 중 확인 완료 상태는 '앱이 전경이 아님'만으로 취소하지 않는다(실제 P 이탈·불명·제한은 아래대로 취소)
        if (lockReason() == null && TripLeaveRule.keepOnGate(flow.step, keepAliveOn, input)) return
        val (next, effects) =
            if (VehicleGateRule.evaluate(input) != VehicleGate.PARK_ALLOWED) BoardingFlow.onGate(flow, input)
            else BoardingFlow.onGate(flow, VehicleGate.UNKNOWN)   // 화면 잠금과 게이트가 어긋나면 보수적으로 불명 취급
        guardLog("잠금: ${lockReason() ?: currentGate().name} → 진행 상태 초기화")
        commitFlow(next, effects, "잠금취소")
    }

    private val cancelReasons = setOf("잠금취소", "화면이탈", "대상변경")

    /** BoardingEffect 실행기. 상태 판단은 하지 않는다(BoardingFlow가 이미 함) */
    private fun runFlowEffect(e: BoardingEffect, reason: String) {
        when (e) {
            is BoardingEffect.StopAll -> stopFlowWork(reason)
            is BoardingEffect.SpeakAnnounce -> speakAnnounceFor(e.generation)
            is BoardingEffect.SpeakQuestion -> speakQuestionFor(e.generation)
            is BoardingEffect.ArmResponseTimeout -> {
                val gen = e.generation
                ui.removeCallbacks(askTimeout)
                askTimeout = Runnable { onGuardianNoOrTimeout("시간 초과(무응답)", timeoutGen = gen) }
                ui.postDelayed(askTimeout, GuardianDefaults.RESPONSE_TIMEOUT_MS)
            }
            is BoardingEffect.SpeakChildNotice -> {
                guideCycle = e.generation
                val gen = e.generation
                // 아이 안내 발화(포커스 요청 포함)는 한 번 미룬다. 단계·세대·잠금이 바뀌었으면 발화하지 않는다
                ui.post { speakChildNoticeAfterAuto(gen, 0L) }
            }
            is BoardingEffect.RecordSuggestion -> {
                // 확인이 시작되는 시점의 대상 좌석 이름만 저장한다(다음 실행의 추천 표시용, 자동 선택 아님)
                // 결정 23: 아이 자리(뒷줄)만 남긴다(앞줄은 추천 기록에도 넣지 않는다)
                SeatSuggestionStore.save(this, SeatSuggestion.rearOnly(e.seats.mapNotNull { seatShort[it] }.toSet()))
                suggestedSeats = SeatSuggestionStore.load(this)
                if (::childPanel.isInitialized) childPanel.recordLast()   // 선택한 아이 조합도 같은 시점에 저장(필수 조건 아님)
            }
            is BoardingEffect.StartAutoCheck -> startAutoCheck(e.generation, e.seats)
            is BoardingEffect.ParticipationFeedback ->
                log("[참여] 보호자 보고 세대=${e.report.generation} 출처=${e.report.source.label} 좌석=${e.report.seats.size}석 개인기록=${e.savePersonalRecord}")
            is BoardingEffect.ReportDispositionChanged -> log("[보고처리] ${e.delivered.disposition.name} (세대 ${e.delivered.report.generation})")
        }
    }

    /**
     * 아이 안내(자동 확인이면 확인 완료 음성) 발화. 단계·세대·잠금이 바뀌었으면 발화하지 않는다.
     * ISSUE-08: 진행 중인 자동 안내(예: '다른 자리에도 누가 앉았어요', 재안내)를 끊지 않고 끝날 때까지 기다린다(최대
     * [CHILD_NOTICE_WAIT_MAX_MS]). 예전에는 speakChildNotice의 tts.stop()이 1.8초 만에 앞 안내를 끊었다. 남은 확인 단계 대기열은 완료 때 이미 버렸다
     */
    private fun speakChildNoticeAfterAuto(gen: Int, waitedMs: Long) {
        if (destroyed || flow.step != BoardingStep.CHILD_NOTICE || flow.generation != gen || lockReason() != null) {
            // 결정 24 ①: 완료 음성을 낼 수 없게 됐으면(흐름 취소·잠금) 기다림을 풀고 넘기기 조건을 다시 본다
            if (completeVoicePending && !destroyed) { completeVoicePending = false; ui.post { evaluateHandoff("완료 음성 취소") } }
            confirmEtaWait = null   // r10 F: 예정 시간 기다림도 끝(늦게 온 값은 예전처럼 따로 안내)
            return
        }
        if (holdForConfirmEta(gen, waitedMs)) return   // r10 F: 예정 시간 값이 없으면 다시 읽고 최대 1초 기다린 뒤 완료 음성을 만든다
        val busyAuto = (autoSayGen >= 0 && tts?.idle == false) || chime?.playing == true
        if (busyAuto && waitedMs < CHILD_NOTICE_WAIT_MAX_MS) {
            if (waitedMs == 0L) log("[아이안내] 앞 자동 안내가 끝날 때까지 기다림(끊지 않음)")
            ui.postDelayed({ speakChildNoticeAfterAuto(gen, waitedMs + CHILD_NOTICE_WAIT_STEP_MS) }, CHILD_NOTICE_WAIT_STEP_MS)
            return
        }
        speakChildNotice()
        if (childNoticeTtsGen < 0) completeVoicePending = false   // 결정 24 ①: 말하지 못함 → 아래 짧은 지연 뒤 판단
        // 2026-11-13 B: 완료 음성을 내지 못했으면(음성 꺼짐·준비 안 됨·포커스 거부) 확인 화면을 잠깐 보여 준 뒤 내비로 넘긴다
        if (handoffArmed && childNoticeTtsGen < 0) {
            ui.removeCallbacks(handoffRunnable); ui.postDelayed(handoffRunnable, NaviHandoffRule.NO_SPEECH_DELAY_MS)
            log("[내비전환] 완료 음성 없음 → ${NaviHandoffRule.NO_SPEECH_DELAY_MS}ms 뒤 판단")
        }
        refreshGuardian()
    }

    /** StopAll: 진행 중 음성 인식·질문 타이머를 멈춘다. 취소(잠금·화면 이탈·좌석 변경)면 안내 발화와 응답 표시도 정리한다 */
    private fun stopFlowWork(reason: String) {
        val cancel = reason in cancelReasons
        stopVoice(if (reason == "화면이탈") "화면 이탈" else "취소($reason)")
        ui.removeCallbacks(askTimeout); ui.removeCallbacks(askStep); announceTtsGen = -1
        dropCheckPhaseSays(reason)   // 2026-11-13: 자동 확인 단계 재안내는 버린다(출발·도착 안내는 유지)
        if (cancel) {
            // 잠금 정리·화면 이탈은 주행 벨트 경고 발화를 끊지 않는다(Unit 5.3 감사 2: 화면 수명주기와 안전 경고 분리)
            tts?.stop(true, keepDrive = reason == "잠금취소" || reason == "화면이탈", end = TtsEnd.GATE_STOP); tts?.clearSpeech()
            voiceNotice = ""; responseSource = ""
            nextView.text = ""
        }
    }

    /** 메인 스레드 전용. 잠금 사유가 이전 값과 달라질 때만 한 줄 남긴다 */
    private fun logLockTransition() {
        val cur = lockShownText()   // 표시 종류가 바뀔 때만 기록(확인 중 → 단절 전환 포함)
        if (prevLockLogged != LOCK_UNSET && prevLockLogged == cur) return
        val prevText = if (prevLockLogged == LOCK_UNSET) "초기" else (prevLockLogged ?: "없음")
        prevLockLogged = cur
        log("[잠금전이] 이전=$prevText -> 현재=${cur ?: "없음"} | 기어=${gear.name ?: "미수신"} | 보호자확인=$gState | 대상좌석=${targetSeats.size}석" + gateLogSuffix())
    }
    // 기어 이름과 링크 상태를 하나의 불변 객체로 묶어 한 번에 교체한다(두 값이 어긋나게 읽히지 않도록)
    private data class GearState(val name: String?, val linkOk: Boolean, val subFailed: Boolean = false)
    @Volatile private var gear = GearState(null, false)
    // 기어 구독 상태 플래그(메인 스레드에서만 변경). 등록 성공 시 true, 등록 예외·연결 실패(onFailed) 시 false.
    // 등록 호출이 예외 없이 끝난 것만 근거이며 실제 연결 확인은 아니다(미검증).
    private var gearSubscribed = false
    private val targetSeats = mutableSetOf<String>()  // 보호자가 직접 고른 확인 대상 좌석
    private lateinit var guardView: TextView
    private lateinit var guardDetailView: TextView
    private var lastDetailText: String? = null
    private var lastStatusText: String? = null
    private lateinit var guardStatusView: TextView   // 보호자용 세 줄('상태 안내' 접이식 본문)
    private lateinit var statusTitle: TextView
    private lateinit var characterBox: LinearLayout
    private lateinit var characterView: GuideCharacterView
    private lateinit var bubbleView: TextView
    private lateinit var startBtn: Button
    private lateinit var yesBtn: Button
    private lateinit var noBtn: Button
    private lateinit var nextView: TextView
    private lateinit var stickerRow: LinearLayout
    // 2026-11-08 F1(ch 결정 3): 스티커 1회 기준은 이동 번호(TripSessionStore, 프로세스 메모리). onPause·잠금으로 지우지 않는다
    private var stickerSessionKey = 0     // 새 부여가 허용될 때마다 1 증가하는 세션 번호(메모리만)
    private var stickerKids = 0           // 이번 세션에 +1을 받은 아이 수(메모리만, 보조 표시용)
    private var stickerKidsShown = 0      // v3 반복 4: 지금 '예' 보고가 실제로 기록한 경우에만 stickerKids, 아니면 0(StickerMoment.displayKids)
    private var stickerAttemptGen = -1    // 스티커 기록을 시도한 '예' 보고의 질문 세대(같은 '예'에서 자동 재시도 금지)
    private var stickerShowNow = false        // 마지막 판정의 참여 표시(무대 보조 라벨용, 읽기 전용 사본)
    private var stickerRecordFailed = false   // 이번 '예'에서 기록 저장·읽기 실패(보조 라벨에 '기록 안 됨' 표기)
    private lateinit var stickerLabel: TextView
    private lateinit var auxLabel: TextView
    private val seatButtons = mutableMapOf<String, Button>()
    private var suggestedSeats: Set<String> = emptySet()   // 지난번에 고른 좌석 이름(추천 표시 전용, 대상 좌석 아님)
    private lateinit var readBtn: Button
    private val simButtons = mutableListOf<Button>()
    private var voiceNotice = ""          // 음성 미확정 안내 문구
    private var responseSource = ""       // 마지막 응답 출처: "터치" 또는 "음성(미검증)"
    // 늦은 타이머는 세대가 다르면 BoardingFlow가 버린다(예약 시점 세대를 함께 넘김)
    // 2026-11-07 Unit 5.1(감사 F3): 예약할 때마다 그 시점 세대를 담은 Runnable을 새로 만든다(늦은 타이머는 BoardingFlow가 버림)
    private var askTimeout: Runnable = Runnable {}
    private var askStep: Runnable = Runnable {}
    /** 보호자 브리핑 안내 발화의 TTS 세대(없으면 -1)와 그때의 흐름 세대. 종료 콜백으로 질문을 시작한다(2026-11-08 P0-C) */
    private var announceTtsGen = -1
    private var announceFlowGen = -1
    /** 현재 질문 발화의 TTS 세대(없으면 -1). 질문 발화 결과는 이 세대와 같을 때만 반영한다(2026-11-08) */
    private var questionTtsGen = -1
    /** 2026-11-12 A2: 이번 질문의 음성이 실패했는가([다시 듣기] 노출 조건). 질문 단계를 벗어나면 지운다 */
    private var questionVoiceFailed = false

    /** [다시 듣기] 노출 여부(VoiceReplayRule). 메인 스레드 */
    private fun replayAvailableNow(): Boolean {
        val t = tts
        return VoiceReplayRule.available(
            asking = gState == GState.ASKING,
            lockedOrNotPark = lockReason() != null || currentGate() != VehicleGate.PARK_ALLOWED,
            questionVoiceFailed = questionVoiceFailed,
            guidanceOn = GuidanceSetting.speechAllowed(guidanceEnabled),
            ttsReady = t != null && t.ready && !t.reconnecting,
            ttsBusy = t == null || t.busy || questionTtsGen >= 0,
            sttActive = voiceActive,
        )
    }

    /** [다시 듣기] 탭: 현재 질문을 한 번만 재생한다. 질문 상태·[예] 판정은 바꾸지 않는다(자동 재질문 아님) */
    private fun onReplayQuestion() {
        if (destroyed) return
        if (!replayAvailableNow()) { log("[다시듣기] 조건 불충족 → 무시(연타·잠금·준비 안 됨)"); refreshGuardian(); return }
        val t = tts ?: return
        val g = t.speak(GuardianDefaults.QUESTION_TEXT, true, "다시듣기")
        if (g >= 0) {
            questionTtsGen = g
            questionVoiceFailed = false
            voiceNotice = ""
            log("[다시듣기] 질문 1회 재생 요청 세대=$g")
        } else {
            log("[다시듣기] 발화 요청 실패 → 화면 질문·터치 유지")
        }
        refreshGuardian()
    }
    private var voiceFlowGen = -1   // 음성 인식을 시작한 질문 세대(음성 결과는 이 세대로 보고)

    /** 화면 조작 잠금 사유: 기어(유효한 P) 다음 플랫폼 주행 제한. debug 모의 기어 중에는 플랫폼 값도 모의 허용으로 본다 */
    private fun lockReasonOf(g: GearState): String? {
        val f = freshGear(g)
        return gearLockReason(f.name, f.linkOk, f.subFailed) ?: if (mockGearOn) null else PlatformUxRule.lockText(freshPlatform())
    }

    private fun lockReason(): String? = lockReasonOf(gear)

    /**
     * 2026-11-13 PM 결정 13(ISSUE-06): 복귀(onStart/onResume) 재조회 때문에 기어·플랫폼 값을 '오래된 값'으로 돌려 생긴 잠금인지.
     * 마지막으로 받은 값이 유효한 P·허용이면 재조회 결과를 기다리는 동안 하차 한 줄(음성만)을 끊지 않는다. 화면 잠금(S09)·게이트 판정은 그대로다.
     * 실제 D/R/N·단절·플랫폼 제한 값이 오면 rawLock이 생겨 예전처럼 끊는다
     */
    private fun keepExitOnRecheck(lock: String?): Boolean {
        val rawLock = gearLockReason(gear.name, gear.linkOk, gear.subFailed) ?: if (mockGearOn) null else PlatformUxRule.lockText(platformUx)
        return TtsKeepRule.keepExitOnRecheckLock(lock, rawLock)
    }

    // 시작 직후 유예: 잠금 판정은 그대로, 보호자에게 보이는 사유 문구만 바꾼다
    private var startElapsedMs = SystemClock.elapsedRealtime()   // onCreate에서 다시 기록
    @Volatile private var gearNameEverReceived = false
    private val graceEndRefresh = Runnable {
        if (destroyed) return@Runnable
        logLockTransition(); refreshGuardian(); refreshBoard()
    }

    private fun lockShownText(g: GearState = gear): String? {
        val reason = lockReasonOf(g)
        // r10 G: 첫 기어 값을 안정화하며 붙잡는 동안은 '미수신' 대신 '확인 중'(시작 유예가 지난 뒤 첫 값이 와도 같은 문구, 잠금은 그대로)
        if (reason == LOCK_TEXT_DISCONNECTED && g === gear && firstGearHolding()) return LOCK_TEXT_CHECKING
        // 2026-11-08 Unit 6A: 화면 복귀 뒤 재조회 중이면 '미수신' 대신 '확인 중'(잠금은 그대로, 문구만)
        if (reason == LOCK_TEXT_DISCONNECTED && resumeRequerying(g.name != null, g.linkOk, gearEpoch == gateEpoch,
                SystemClock.elapsedRealtime() - resumeElapsedMs)) return LOCK_TEXT_CHECKING
        return lockDisplayText(reason, SystemClock.elapsedRealtime() - startElapsedMs, gearNameEverReceived, g.subFailed)
    }
    /** 마지막 화면 복귀 시각(재조회 중 표시용). 복귀 전에는 아주 과거 값 */
    private var resumeElapsedMs = Long.MIN_VALUE / 2

    // ---- 하차 시 '뒷좌석 한 번 더 확인' 음성 한 줄(가안). 화면 표시 없음, 로그만. 메인 스레드 전용 ----
    // 세션 = 앱 실행 1회(메모리만, 저장 안 함). 주행 관측 후 유효한 P + 정차 허용 게이트(UXR 허용)에서 세션당 1회만.
    // 2026-11-08 6C(QA 인계 bb4d44b3): 평가는 게이트 입력(기어·UXR)이 바뀔 때마다(onGateInputsChanged 끝) 한다.
    //   소진은 onDone(DONE)에서만. 요청만으로는 소진하지 않는다. DONE이 아닌 결과·건너뜀·포커스 거부·예외는
    //   소진하지 않고 driveSeen을 내려 같은 P에서 자동 재생하지 않는다(다음 주행 → 유효 P + 허용에서만 다시).
    //   다른 발화(보호자 확인·질문·예시·주행 안내)는 끊지 않는다. P 복귀·이 결과는 이동 종료·도착 근거가 아니다.
    // Activity 재생성(야간 전환·회전)에도 이번 이동의 하차 한 줄 소진을 잃지 않게 보관소 값으로 시작한다(프로세스 재시작은 restoreTripSession이 저장값으로 맞춘다, 2026-11-10 M06)
    private var exitCheck = ExitCheckState(consumed = TripSessionStore.exitConsumedTrip == TripSessionStore.state.tripKey)

    // r6a 2절: 하차 한 줄의 정차 허용 게이트 전경 조건은 '화면이 보임'(다중 창에서 포커스가 계기판에 있어도). ScreenPresenceRule
    private fun exitGateAllowed(): Boolean = displayParkAllowed()

    /**
     * r6a 2절(결함 P2): 화면 표시·음성 판단용 유효 P + 플랫폼 허용. 전경 = 화면에 보임(started). 조작(누름) 허용은 [tripParkAllowed](resumed) 그대로
     */
    /** r6a QA 후속: 화면 버튼 누름 처리용 게이트 입력(전경 = 보임, ScreenPresenceRule.tapForeground). 잠금 판정은 그대로 */
    private fun tapGateInput(): GateInput = gateInput().copy(foreground = ScreenPresenceRule.tapForeground(started, resumed))

    /** r6a QA 후속: 화면 버튼 누름의 유효 P + 플랫폼 허용(보임 기준). 포커스가 계기판에 있어도 첫 누름이 무시되지 않게 */
    private fun tapParkAllowed(): Boolean = VehicleGateRule.evaluate(tapGateInput()) == VehicleGate.PARK_ALLOWED && lockReason() == null

    private fun displayParkAllowed(): Boolean =
        VehicleGateRule.evaluate(gateInput().copy(foreground = ScreenPresenceRule.displayForeground(started, resumed))) == VehicleGate.PARK_ALLOWED &&
            lockReason() == null

    private fun exitDrivingNow(): Boolean = gear.let { BeltReleaseRule.isDriving(it.name, it.linkOk, it.subFailed) }

    private fun exitCheckEvaluate() {
        val g = gear
        val graceOver = SystemClock.elapsedRealtime() - startElapsedMs > STARTUP_GRACE_MS
        val (next, allow) = ExitCheckRule.onGear(
            exitCheck,
            ExitCheckContext(g.name, g.linkOk, g.subFailed, GuidanceSetting.speechAllowed(guidanceEnabled), graceOver, exitGateAllowed()),
        )
        exitCheck = next
        if (allow && TripGuide.suppressExitLine(TripGuideStore.state)) {
            // 2026-11-13: 내비 도착 신호가 있었던 이동은 도착 안내에 뒷좌석 확인 문장을 합쳐 말한다 → 같은 P에서 하차 한 줄을 다시 내지 않는다(소진 아님)
            exitCheck = ExitCheckRule.onNotRequested(exitCheck, exitDrivingNow())
            log("[하차확인] 이번 이동은 도착 안내에 합침 → 하차 한 줄 생략")
            return
        }
        if (allow && ArrivalAskRule.replacesExitLine(TripGuideStore.state)) {
            // r5 추가 A: 목적지 없는 이동은 "목적지에 도착했나요?"가 하차 한 줄을 대신한다(예 = 도착 음성에 뒷좌석 확인, 아니요 = 없음, 대답 없음 = 두고 내림 안내)
            exitCheck = ExitCheckRule.onNotRequested(exitCheck, exitDrivingNow())
            log("[하차확인] 목적지 없는 이동 → 도착 질문이 대신함, 하차 한 줄 생략")
            return
        }
        if (!allow) {
            // QA RISK 3: 미뤄 둔 안내는 게이트가 닫히면(주행·UXR 제한·단절) 취소한다(나중에 오래된 안내를 재생하지 않음)
            if (exitCheck.waitingIdle && !exitGateAllowed()) {
                exitCheck = ExitCheckRule.cancelWaiting(exitCheck, exitDrivingNow())
                log("[하차확인] 미룬 안내 취소(정차 허용 해제) → 미전달, 소진 안 함")
            }
            return
        }
        val t = tts
        // 보호자 안내·질문이 진행 중(음성 대기 포함)이면 TTS가 쉬고 있어도 끼어들지 않는다
        val exitResult = when {
            t == null -> { log("[하차확인] 건너뜀(음성 준비 안 됨)"); TtsGuide.EXIT_SKIPPED }
            gState != GState.IDLE && gState != GState.YES_REPORTED && gState != GState.WAITING_GUARDIAN -> { log("[하차확인] 미룸(보호자 확인 진행 중)"); TtsGuide.EXIT_BUSY }
            else -> t.speakExit(ExitCheckRule.TEXT)
        }
        if (exitResult == TtsGuide.EXIT_BUSY) {
            val (s2, waiting) = ExitCheckRule.onBusy(exitCheck, exitDrivingNow())
            exitCheck = s2
            log(if (waiting) "[하차확인] 다른 발화 종료 뒤 재평가 예정(${s2.busyWaits}/${ExitCheckRule.MAX_BUSY_WAITS})"
                else "[하차확인] 미룸 상한 초과 → 미전달로 끝냄(소진 안 함, 같은 P 재시도 안 함)")
            return
        }
        if (exitResult < 0) {
            exitCheck = ExitCheckRule.onNotRequested(exitCheck, exitDrivingNow())
            log("[하차확인] 요청 안 됨(${if (exitResult == TtsGuide.EXIT_FAILED) "포커스 거부·예외" else "건너뜀"}) → 소진 안 함, 같은 P 재시도 안 함")
            return
        }
        exitCheck = ExitCheckRule.onRequested(exitCheck, exitResult)
        pushBubble(ExitCheckRule.TEXT, "하차확인")   // 계약 v3
        // 발화 요청이 나갔을 때 무대 자막 시각을 기록(소리가 났다는 뜻 아님). DONE이 아닌 결과가 오면 즉시 지운다
        stageExitUntilMs = SystemClock.elapsedRealtime() + 8000L
        ui.postDelayed({ if (!destroyed) refreshGuardian() }, 8000L)   // 8초 뒤 무대 갱신 한 번
        // 2026-11-11 U4(에뮬레이터 발견): 홈이 덮여 있으면 하차 안내 장면이 보이지 않았다. 다음 틱에 홈 표시를 다시 정해 무대를 앞에 둔다
        ui.post { if (!destroyed) { syncHome(); renderStageOnly() } }
    }

    /** TtsGuide.onExitResult(메인 스레드). 기다리던 세대가 아니면 규칙이 무시한다 */
    private fun onExitResult(g: Int, end: TtsEnd) {
        if (destroyed) return
        val before = exitCheck
        exitCheck = ExitCheckRule.onResult(exitCheck, g, end, exitDrivingNow())
        if (exitCheck.consumed && TripSessionStore.exitConsumedTrip != TripSessionStore.state.tripKey) {
            TripSessionStore.exitConsumedTrip = TripSessionStore.state.tripKey
            val ok = StickerLogStore.saveExitConsumedTrip(applicationContext, TripSessionStore.state.tripKey)
            if (!ok) log("[하차확인] 소진 기록 저장 실패(이번 실행 동안은 유지, 재시작 뒤 다시 나올 수 있음)")
        }
        if (exitCheck == before) { log("[하차확인] 결과 무시(세대=$g, 대기 세대=${before.pendingGen})"); return }
        log("[하차확인] 결과 세대=$g 사유=${end.label} → 소진=${exitCheck.consumed} 주행관측=${exitCheck.driveSeen}")
        if (end != TtsEnd.DONE && stageExitUntilMs != 0L) { stageExitUntilMs = 0L; renderStageOnly() }
    }

    /** 메인 스레드 전용. 기어 상태를 교체하고 잠금이면 진행 상태를 초기화한다 */
    private fun applyGear(name: String?, ok: Boolean, subFailed: Boolean = false) {
        if (name == null && FirstGearHold.holding(firstGear)) holdFirstGear(GearState(null, ok, subFailed))   // r10 G: 등록 실패·연결 없음 등 다른 경로의 '값 없음'도 붙잡던 첫 값을 버린다
        if (name != null) gearNameEverReceived = true
        gear = GearState(name, ok, subFailed)
        gearEpoch = gateEpoch   // 이번 화면 구간에서 받은 값
        logLockTransition()
        val tripWasFinished = tripEnd.finished
        tripEnd = TripEndRule.onMotion(tripEnd, BeltReleaseRule.isDriving(name, ok, subFailed))   // 끝내기 후 주행 확인 = 새 이동(F7: 불명은 아님)
        noteTripMotion(BeltReleaseRule.isDriving(name, ok, subFailed))
        doorGear(name, ok, subFailed)   // r6 4절: 주행이면 이번 정차 문 열림 기록 지움, 유효 P면 기록 시작
        if (tripWasFinished && !tripEnd.finished) { signalSource = SignalSource.NONE; doneByMock = false }   // 새 이동: 모의 출처 표기 초기화
        // 유효한 P 복귀 = 이번 이동 종료: 주행 벨트 안내 세션(armed·안내 기록)을 초기화하고 그 발화도 멈춘다
        // 2026-11-07 Unit 5.1(감사 F1): 세션 종료는 '무장 후 주행(MOVING)을 본 뒤 유효한 P'일 때만. 주차 중 같은 P 재수신·재연결로는 끝내지 않는다
        val ctx = DriveContext(name, ok, subFailed, useSim)
        maybeRearmBeltSession(BeltReleaseRule.isDriving(name, ok, subFailed))   // r8 2절: 정차 뒤 이동이 이어져 다시 주행하면 감시 다시 켬
        beltMoved = BeltReleaseRule.movedAfter(beltMoved, beltWarn.active, ctx)
        // 2026-11-08 Unit 5.3(N2): 유효한 P면 진행 중 경고만 멈추고(세션은 유지) 미처리 이벤트를 재평가(정리)한다.
        // 세션 종료는 '무장 후 주행을 본 뒤 유효한 P'(이번 이동 종료)일 때만. 경고 발화 중 P 복귀로 세션을 초기화하지 않는다
        // 재감사 2차(8번): 진행 중 경고는 P에서도 끊지 않는다(끊으면 대기 중이던 onDone이 버려져 전달된 경고가 미전달로 기록됨).
        // 그 경고의 결과(onDone·실패·20초 상한)가 온 뒤 세션을 끝낸다
        if (!ok || subFailed) noteMonitorGap("기어 신호 확인 불가")
        runBeltEval()
        if (BeltReleaseRule.endsSession(beltMoved, ctx)) {
            if (beltWarn.inFlight != null) { endAfterWarning = true; log("[주행안내] 이동 후 P 복귀 → 진행 중 경고 결과를 받은 뒤 세션 종료") }
            else endBeltReleaseSession("이동 후 P 복귀")
        }
        exitCheck = ExitCheckRule.onGear(exitCheck,
            ExitCheckContext(name, ok, subFailed, guidanceEnabled = false, graceOver = false, gateAllowed = false)).first   // 주행 관측만 원시 기어로 기록(허용 판정은 게이트 처리 끝에서)
        onGateInputsChanged()
    }

    /** 이번 이동의 실제 주행 관측(보호자용 끝내기 노출 근거). 처음 볼 때만 저장한다. 읽기 실패 상태면 원본을 덮지 않는다 */
    private fun noteTripMotion(driving: Boolean) {
        val cur = TripSessionStore.state
        val next = TripSessionRule.onMotion(cur, driving)
        if (next === cur) return
        TripSessionStore.state = next
        val saved = if (TripSessionStore.readFailed) false else StickerLogStore.saveTrip(applicationContext, next)
        log("[이동] 이동 ${next.tripKey} 주행 관측 " + when {
            TripSessionStore.readFailed -> "(저장 안 함: 저장값 읽기 실패 상태, 원본 보존)"
            saved -> "(저장됨)"
            else -> "(저장 실패: 재시작하면 주행 관측이 사라져 끝내기 버튼이 다음 주행까지 안 보임)"
        })
    }

    /** 같은 기어 값을 이번 화면 구간에 다시 받음: 오래된 값이었다면 신선도만 갱신하고 게이트를 다시 본다 */
    private fun refreshGearEpoch() {
        if (gearEpoch == gateEpoch) return
        gearEpoch = gateEpoch
        onGateInputsChanged()
    }

    /** 기어·플랫폼 값이 바뀐 뒤의 공통 처리. 정차 허용이 아니면 진행 중 확인을 BoardingFlow 공통 취소로 정리한다 */
    private fun onGateInputsChanged() {
        logLockTransition()
        if (currentGate() != VehicleGate.PARK_ALLOWED || lockReason() != null) cancelFlowForGate()
        // 지난 선택은 현재 탑승의 증거가 아니므로 잠금 때 대상 좌석을 비운다
        if (lockReason() != null) targetSeats.clear()
        // 2026-11-14 SCREEN_EXIT_CHECK ③: 잠금(D/R/N·불명·단절·플랫폼 제한)이면 열린 확인 대화상자(앱 나가기·알림 안내)를 닫는다(주행 중 조작 금지).
        // 이동 끝내기 확인 창은 refreshGuardian이 조건 해제로 닫는다. 닫기만 하고 [나중에]·[계속 쓰기] 처리는 하지 않는다(다음에 다시 물을 수 있음)
        if (lockReason() != null && devADialog != null) { closeDevADialog(); log("[대화상자] 잠금 → 확인 창 닫음(${lockReason()})") }
        refreshGuardian()
        refreshBoard()
        syncGl()   // 3D 시험: 잠금이면 렌더 중지
        syncHome()
        exitCheckEvaluate()   // 6C: 게이트(기어·UXR)가 열린 뒤에만 하차 한 줄을 허용한다(잠금 정리 뒤에 평가)
        tripGuideGear()   // 2026-11-13: 출발·도착 안내는 잠금 정리(tts.stop) 뒤에 평가한다
        maybeResumeCheck()   // PM 결정 20: 재생성 전 확인 중이었으면 게이트가 열린 뒤 이어 간다
        maybeQuickStart()   // r6 6절: 빠른 시작 대기 중이면 유효 P + 플랫폼 허용이 된 순간 좌석 확인을 연다(잠금 규칙 우선)
        noteRestoredGear()   // r10 M: 되살린 이동의 첫 유효 기어 경로 로그(동작은 위 tripGuideGear가 이미 했다)
    }

    /**
     * PM 결정 23 마이그레이션: 앞줄 배정이 남은 곳을 모두 정리한다(뒷줄로 옮기지 않음, 지운 것만 로그).
     * - 지난 좌석 추천(SharedPreferences, 좌석 이름만): 앞줄 이름(R1L·R1R) 지움
     * - 이동 상태(TripGuideStore, 프로세스 메모리): 배정·선택 좌석에서 앞줄 지움
     * - 보관한 확인(CheckSessionStore, 결정 20, 프로세스 메모리): 배정·선택·보호자 확인에서 앞줄 지움(복원 때도 한 번 더 거른다)
     * 아이·자리 짝은 디스크에 저장하지 않는다(확인 [CheckSessionStore]·이동 상태는 메모리뿐, 앱 업데이트 때 프로세스가 새로 뜬다)
     */
    private fun migrateDecision23() {
        val saved = SeatSuggestionStore.load(this)
        val kept = SeatSuggestion.rearOnly(saved)
        if (kept != saved) {
            SeatSuggestionStore.save(this, kept)
            log("[마이그레이션] 결정 23: 지난 좌석 추천에서 앞줄 ${(saved - kept).joinToString()} 지움")
        }
        val tg = TripGuideStore.state
        val tg2 = TripGuide.dropFrontAssignments(tg)
        if (tg2 != tg) {
            TripGuideStore.state = tg2
            log("[마이그레이션] 결정 23: 이동 상태의 앞줄 배정 ${(tg.assigned.keys + tg.selected - tg2.assigned.keys - tg2.selected).joinToString()} 지움")
        }
        CheckSessionStore.saved?.let { snap ->
            val (sc, dropped) = SeatCheckRule.dropFrontAssignments(snap.seatCheck)
            if (dropped.isNotEmpty()) {
                CheckSessionStore.saved = snap.copy(seatCheck = sc)
                log("[마이그레이션] 결정 23: 보관한 확인(CheckSession)의 앞줄 배정 ${dropped.joinToString()} 지움")
            }
        }
    }

    /** PM 결정 20: 재생성 전 보관한 확인 상태를 정차 허용이 되면 이어 간다(창 안에서만). 판단은 CheckResumeRule */
    private fun maybeResumeCheck() {
        val snap = CheckSessionStore.saved ?: return
        when (CheckResumeRule.decide(snap, SystemClock.elapsedRealtime(), flow.step, tripParkAllowed())) {
            ResumeDecision.NONE, ResumeDecision.WAIT -> return
            ResumeDecision.DROP -> {
                CheckSessionStore.saved = null
                // 2026-11-14 SCREEN_EXIT_CHECK ⑥: 복원 대기 동안 무대를 유지하느라 homeActive=false였다 → 버리면 홈으로(옛 선택 화면이 드러나지 않게)
                // 새 화면에서 이미 다른 흐름(확인)을 시작했거나 이동 단계가 있으면 그 화면을 그대로 둔다
                val toHome = StageFlags.NEW_STAGE && flow.step == BoardingStep.HOME && currentAutoPhase() == null
                if (toHome) homeActive = true
                log("[복원] 보관한 확인 버림(시간 지남·다른 흐름 시작)${if (toHome) " → 홈" else ""}")
                syncHome(); renderStageOnly()
            }
            ResumeDecision.RESUME -> {
                CheckSessionStore.saved = null
                flow = snap.flow
                seatCheck = CheckResumeRule.resumedSeatCheck(snap)
                SeatCheckRule.dropFrontAssignments(snap.seatCheck).second.takeIf { it.isNotEmpty() }?.let {
                    log("[마이그레이션] 결정 23: 보관한 확인(CheckSession)의 앞줄 배정 ${it.joinToString()} 지움(뒷줄로 옮기지 않음)")
                }
                welcomeGen = snap.welcomeGen
                if (StageFlags.NEW_STAGE) homeActive = false
                ui.removeCallbacks(autoTick); ui.postDelayed(autoTick, AUTO_TICK_MS)
                log("[복원] 화면 재생성 뒤 확인 이어 감(배정 ${seatCheck.assigned.size}석, 보호자 확인 ${seatCheck.guardianConfirmed.size}석)")
                refreshGuardian(); requestAutoEval(); renderStageOnly(); syncHome()
            }
        }
    }

    /** 기어 변화 이벤트. 구독 플래그가 false면 링크 정상으로 인정하지 않는다 */
    private fun onGearEvent(name: String?) {
        ui.post {
            if (destroyed) return@post
            if (!realGearAccepted()) return@post   // 모의 기어 중에는 실제 기어 수신을 무시한다
            gearEventSeq++   // 오래된 GET 판정용 번호는 값이 같아도 올린다
            gearWatch = GearWatchdog.eventSeen(gearWatch, SystemClock.elapsedRealtime())   // 이벤트 도착 = 서비스 응답 있음
            val next = GearState(name, name != null && gearSubscribed, !gearSubscribed)
            if (holdFirstGear(next)) return@post   // r10 G: 첫 기어 값은 0.5초 안정화 뒤 반영(그 사이 새 값이면 마지막 값)
            if (next == gear) { refreshGearEpoch(); return@post }   // 이름·링크 상태가 이전과 같으면 갱신·로그 생략(신선도만 갱신)
            log("[EVT] 기어 -> $name" + gateLogSuffix())
            applyGear(next.name, next.linkOk, next.subFailed)
        }
    }

    /** 기어 연결 실패 이벤트(onFailed): 구독 플래그를 false로 하고 잠근다 */
    private fun onGearFailedEvent() {
        ui.post {
            if (destroyed) return@post
            if (!realGearAccepted()) return@post   // 모의 기어 중에는 실제 기어 수신을 무시한다
            gearEventSeq++
            gearSubscribed = false
            holdFirstGear(GearState(null, false, true))   // r10 G: 붙잡던 첫 값이 있으면 버린다(더 새로운 값이 실패)
            applyGear(null, false, true)
        }
    }

    /** 기어 GET 응답: 요청 이후 이벤트·새 읽기가 있었으면 버린다. 링크 정상은 구독 플래그가 true일 때만 */
    private fun onGearGet(reqRead: Int, reqEvent: Int, name: String?, ok: Boolean) {
        ui.post {
            if (destroyed || reqRead != readSeq || reqEvent != gearEventSeq) { Log.i(tag, "오래된 기어 GET 버림"); return@post }
            if (!realGearAccepted()) return@post   // 모의 기어 중에는 실제 기어 수신을 무시한다
            val next = GearState(name, ok && gearSubscribed, !gearSubscribed)
            if (holdFirstGear(next)) return@post   // r10 G: 첫 기어 값은 0.5초 안정화 뒤 반영
            if (next == gear) { refreshGearEpoch(); return@post }   // 같은 값 재수신(감시 요청 등)은 반영하지 않는다(감사 F1). 신선도만 갱신
            applyGear(next.name, next.linkOk, next.subFailed)
        }
    }

    /** 기어 GET 실패: 값을 알 수 없으므로 잠근다(구독 플래그는 건드리지 않음) */
    private fun onGearGetFailed(reqRead: Int, reqEvent: Int) {
        ui.post {
            if (destroyed || reqRead != readSeq || reqEvent != gearEventSeq) return@post
            if (!realGearAccepted()) return@post   // 모의 기어 중에는 실제 기어 수신을 무시한다
            holdFirstGear(GearState(null, false, !gearSubscribed))   // r10 G: 붙잡던 첫 값이 있으면 버린다(더 새로운 값이 실패)
            if (gear == GearState(null, false, !gearSubscribed)) { refreshGearEpoch(); return@post }   // 같은 실패 반복은 반영하지 않는다
            applyGear(null, false, !gearSubscribed)
        }
    }

    // ---- 2026-11-15 r10 G(개발 3): 앱 시작 직후 첫 기어 값 0.5초 안정화(판단 FirstGearHold, GearLock.kt). 메인 스레드 전용 ----
    // 강제 종료 직후 첫 이벤트가 D, 30 ms 뒤 P로 와서 잠금 카드가 '주차(P) 상태가 아니에요'로 깜빡이던 문제. 붙잡는 동안 gear는 그대로(미수신 = 잠금)라
    // 허용만 늦어지고 잠금이 풀리지는 않는다. 첫 값을 반영한 뒤로는 바로 반영(주행 중 D/R/N 잠금 반응 그대로). Activity마다 새로 시작한다.
    private var firstGear = FirstGearHoldState()
    private val firstGearFlush = Runnable { if (!destroyed) flushFirstGear() }

    /** 실제 기어 값 하나(이벤트·GET·실패). true = 붙잡았으니 호출자는 반영하지 않는다. false = 지금처럼 바로 반영 */
    private fun holdFirstGear(next: GearState): Boolean {
        val wasHolding = FirstGearHold.holding(firstGear)
        val (s, act) = FirstGearHold.onValue(firstGear, GearSnapshot(next.name, next.linkOk, next.subFailed), SystemClock.elapsedRealtime())
        firstGear = s
        return when (act) {
            FirstGearAction.APPLY_NOW -> {
                if (wasHolding) { ui.removeCallbacks(firstGearFlush); log("[기어첫값] 붙잡던 값 버림(새 값이 신호 확인 불가) → 바로 반영") }
                false
            }
            FirstGearAction.HOLD_START -> {
                ui.removeCallbacks(firstGearFlush); ui.postDelayed(firstGearFlush, FirstGearHold.HOLD_MS)
                log("[기어첫값] ${next.name} 받음 → ${FirstGearHold.HOLD_MS}ms 안정화 뒤 반영(그 사이 새 값이 오면 마지막 값)")
                true
            }
            FirstGearAction.HOLD_REPLACE -> { log("[기어첫값] 안정화 중 새 값 ${next.name} → 마지막 값으로 바꿈"); true }
        }
    }

    /** 안정화 시각: 붙잡은 마지막 값을 반영한다(그 뒤로는 바로 반영). 모의 기어 중이면 버린다 */
    private fun flushFirstGear() {
        val now = SystemClock.elapsedRealtime()
        val (s, v) = FirstGearHold.flush(firstGear, now)
        if (v == null) {
            if (FirstGearHold.holding(s)) { ui.removeCallbacks(firstGearFlush); ui.postDelayed(firstGearFlush, FirstGearHold.remainingMs(s, now).coerceAtLeast(1L)) }
            return
        }
        firstGear = s
        if (!realGearAccepted()) { log("[기어첫값] 안정화 끝, 모의 기어 중 → 버림"); return }
        val next = GearState(v.name, v.linkOk, v.subFailed)
        log("[기어첫값] 안정화 끝 → ${v.name} 반영" + gateLogSuffix())
        if (next == gear) { refreshGearEpoch(); return }
        applyGear(next.name, next.linkOk, next.subFailed)
    }

    /** 첫 기어 값을 붙잡는 중인가(표시 문구·gearCheckPending용) */
    private fun firstGearHolding(): Boolean = FirstGearHold.holding(firstGear)

    /** 메인 스레드 전용. 같은 리스너를 먼저 해제한 뒤 재등록하므로 중복 등록되지 않는다 */
    private fun registerGear() {
        // 재연결(재등록) 때도 지난 선택은 현재 탑승의 증거가 아니므로 비운다
        targetSeats.clear()
        val v = vehicle
        if (v == null) { gearSubscribed = false; if (realGearAccepted()) applyGear(null, false); return }
        val l = gearListener ?: object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) { onGearEvent(p0?.name) }   // [EVT] 로그는 중복 판정 뒤 onGearEvent 안에서 남긴다
            override fun onFailed(e: Exception) { log("[EVT-ERR] 기어 리스너 실패: ${e.javaClass.simpleName}: ${e.message}"); onGearFailedEvent() }
        }.also { gearListener = it }
        if (gearRegistered) { runCatching { v.gearApi.unregisterGearSelection(l) }; gearRegistered = false }
        try {
            v.gearApi.registerGearSelection(l)
            gearRegistered = true
            gearSubscribed = true
            log("[REG] 기어 구독 등록 호출 완료(예외 없음, 연결 확인은 아님)")
        } catch (t: Throwable) {
            gearSubscribed = false
            log("[REG] 기어 구독 예외: ${t.javaClass.simpleName}: ${t.message}")
            if (realGearAccepted()) applyGear(null, false, true)
        }
    }
    private var gearRegistered = false

    /**
     * 보호자 확인 시작(기존 [시작]·[다시 시작] 버튼과 무대 [시작]). 화면의 아이·좌석 선택을 BoardingFlow 이벤트로 옮겨 넣고
     * 마지막에 SeatsStart로 확인을 시작한다. 단계·게이트 판정은 모두 BoardingFlow가 한다.
     */
    private fun startGuardian() {
        // 기존 시작 버튼 활성 조건(IDLE/WAITING_GUARDIAN/YES_REPORTED)과 같게 유지한다
        if (gState != GState.IDLE && gState != GState.WAITING_GUARDIAN && gState != GState.YES_REPORTED) return
        if (lockReason() != null || targetSeats.isEmpty()) return
        nextView.text = ""; voiceNotice = ""; responseSource = ""
        if (flow.step == BoardingStep.WAITING_GUARDIAN) {
            dispatchFlow(BoardingEvent.Restart, "다시 시작")
            // 이전 동작 유지(감사 F6): 다시 시작할 때도 대상 좌석·아이 조합을 추천 기록으로 저장한다(D02 임시 세션 제외)
            if (flow.step == BoardingStep.ANNOUNCING && !flow.withoutProfile) runFlowEffect(BoardingEffect.RecordSuggestion(flow.seatSnapshot, flow.children), "다시 시작")
            return
        }
        val input = gateInput()
        var s = flow
        val effects = ArrayList<BoardingEffect>()
        fun step(e: BoardingEvent) { val (n, f) = BoardingFlow.reduce(s, input, e); s = n; effects += f }
        if (s.step == BoardingStep.CHILD_NOTICE) step(BoardingEvent.NoticeNext(s.generation))   // 아이 안내를 마치고 새 실행
        if (s.step == BoardingStep.CHILD_SELECT || s.step == BoardingStep.SEAT_SELECT) step(BoardingEvent.ToHome)
        step(BoardingEvent.Start)
        if (s.step != BoardingStep.CHILD_SELECT) { commitFlow(s, effects, "시작불가"); return }
        val want = selectedChildren().map { it.id.toString() }.toSet()
        for (id in (s.children - want) + (want - s.children)) step(BoardingEvent.ToggleChild(id))
        step(BoardingEvent.ChildrenNext)
        for (seat in targetSeats.toList()) step(BoardingEvent.ToggleSeat(seat))
        // 2026-11-13 규칙 변경: 보호자 '예' 질문(SeatsStart → 안내 → 질문 → 예/아니오·STT) 대신 착석·벨트 신호 자동 확인으로 시작한다.
        // 예전 경로(SeatsStart)는 기존 테스트·되돌리기용으로 BoardingFlow에 남아 있지만 앱은 더 이상 보내지 않는다.
        step(BoardingEvent.SeatsStartAuto)
        commitFlow(s, effects, "시작")
    }

    // ---- 안내 음성(TTS). 질문 세대(guideCycle)당 안내 1회, 질문 1회만 발화 요청한다 ----
    private var tts: TtsGuide? = null
    private var guideCycle = 0
    private var spokenKey = ""
    // 안내 음성 사용 스위치(사용자 선택). 1비트만 로컬 SharedPreferences에 저장, 시작 때 복원
    private var guidanceEnabled = GuidanceSetting.DEFAULT_ENABLED
    private lateinit var guidanceSwitch: android.widget.Switch
    private lateinit var guidanceNote: TextView

    private fun speakGuide(text: String, isQuestion: Boolean): Int {
        val t = tts ?: return -1
        val key = "$guideCycle:${if (isQuestion) "Q" else "A"}"
        if (!GuidanceSetting.speechAllowed(guidanceEnabled)) return -1
        if (!t.ready || lockReason() != null || key == spokenKey) return -1
        spokenKey = key
        return t.speak(text, isQuestion)
    }

    /** YES_REPORTED 진입당 1회(질문 세대당 1회 키). 준비·잠금(P 아님 포함) 조건을 만족할 때만 요청한다. 이후 동작 없음 */
    private fun speakChildNotice() {
        val t = tts ?: return
        val key = "$guideCycle:C"
        if (!GuidanceSetting.speechAllowed(guidanceEnabled)) return
        if (!t.ready || lockReason() != null || key == spokenKey) return
        spokenKey = key
        t.stop()   // 남은 질문 발화가 있으면 먼저 정리(대기 세대 무효화)
        val sel = selectedChildren()
        // 2026-11-13 B: 자동 확인 완료면 완료 음성만 낸다("…신호를 받았어요. 출발하면 안내할게요." + 결정 14 예정 시간).
        // 예전에는 아이 안내("…같이 벨트를 확인해 볼까요")를 이어 붙여 확인 뒤에 다시 확인을 권하는 문장이 됐다. 환영 인사는 다음 작업.
        val prefix = autoCompletePrefix; autoCompletePrefix = ""
        val text = prefix.ifEmpty { ChildNotice.spokenText(noticeTone(), noticeMode(), sel.map { it.nickname }, noticeSpeakNames()) }
        // 결정 24 ②: 이동(FGS)이 시작된 뒤의 확인 완료 음성은 화면 이탈·잠금 정리로 끊지 않는다(포커스 손실·차량 경고는 그대로)
        val tripKeep = prefix.isNotEmpty() && keepAliveOn
        // r6a 임시 결정 42: 확인 완료 음성은 내비가 끼어들면 멈추고 내비가 끝난 뒤 한 번만 다시 말한다(onSpeechResult)
        val ttsGen = t.speak(text, false, if (prefix.isNotEmpty()) "확인완료" else "아이안내", tripKeep = tripKeep, yieldOnNavi = prefix.isNotEmpty())
        if (ttsGen >= 0) {
            childNoticeTtsGen = ttsGen; childNoticeFlowGen = flow.generation; pushBubble(text, "확인완료"); renderStageOnly()
            childNoticeText = text; childNoticeTripKeep = tripKeep; childNoticeResaid = 0
        }
    }
    /** 임시 결정 42: 다시 말할 확인 완료 문장(메모리에만, 저장하지 않음)과 다시 말한 횟수 */
    private var childNoticeText = ""
    private var childNoticeTripKeep = false
    private var childNoticeResaid = 0

    // 2026-11-07 Unit 5.1(감사 F4): 아이 안내 발화가 실제로 끝났을 때만 NoticeNext(보고 처리 상태 DONE). 오류·중지·음성 꺼짐이면 보내지 않는다
    private var childNoticeTtsGen = -1
    private var childNoticeFlowGen = -1
    private fun onSpeechResult(ttsGen: Int, end: TtsEnd) {
        // 2026-11-08 P0-C 재감사: 안내 발화 종료 사유를 나눠 흐름에 넘긴다. DONE만 안내 완료이며,
        // 오류·상한·포커스 상실은 '확인 불가'로 질문 화면(안내 문구 유지·터치), 앱 중지는 보호자 대기, 게이트 중단은 흐름이 이미 취소함
        if (!destroyed && ttsGen >= 0 && ttsGen == announceTtsGen) {
            announceTtsGen = -1
            ui.removeCallbacks(askStep)
            val fg = announceFlowGen
            val ev = AnnounceEndRule.eventFor(end, fg)
            val outcome = when (ev) {
                is BoardingEvent.AnnounceFinished -> ev.outcome
                else -> AnnounceOutcome.CANCELLED
            }
            log(AnnounceEndRule.trace(end.label, outcome))
            // TTS 콜백이 끝난 뒤 다음 루프에서 넘긴다(감사 4: 콜백 안 재진입 회피). 세대가 바뀌었으면 BoardingFlow가 버린다
            ui.post { if (!destroyed) dispatchFlow(ev, "안내 발화 종료(${end.label})") }
            return
        }
        if (destroyed || ttsGen != childNoticeTtsGen) return
        childNoticeTtsGen = -1
        val t0 = tts
        if (t0 != null && childNoticeText.isNotEmpty() && NaviVoiceRule.confirmResay(t0.yieldedToNavi(ttsGen), childNoticeResaid)) {
            // 임시 결정 42: 내비가 끼어들어 멈춘 확인 완료 음성 → 내비가 끝나고 1.2초 뒤(speak의 내비 대기) 처음부터 한 번 더. 내비 전환은 그 뒤
            childNoticeResaid++
            val g2 = t0.speak(childNoticeText, false, "확인완료(다시)", tripKeep = childNoticeTripKeep, yieldOnNavi = false)
            if (g2 >= 0) {
                childNoticeTtsGen = g2
                log("[내비음성] 확인 완료 음성을 내비 뒤에 다시 말함(${childNoticeResaid}/${NaviVoiceRule.MAX_CONFIRM_RESAY}, 임시 결정 42) 세대=$g2")
                return   // completeVoicePending 유지 → 다시 말한 음성이 끝난 뒤 내비 전환(안전망 시간은 그대로)
            }
            log("[내비음성] 확인 완료 음성 다시 말하기 요청 실패 → 그대로 진행")
        }
        completeVoicePending = false   // 결정 24 ①: 완료 콜백(끝·오류·중지) = 넘기기 판단 시점
        if (end == TtsEnd.DONE) dispatchFlow(BoardingEvent.NoticeNext(childNoticeFlowGen), "아이안내 완료")
        else log("[아이안내] 발화 종료 사유=${end.label} → 완료로 보지 않음")
        // 2026-11-13 B: 확인 완료 음성이 끝났으면(사유 무관, 앱이 아직 보일 때만) 내비로 넘긴다. TTS 콜백 밖에서 판단
        if (handoffArmed) ui.post { evaluateHandoff("완료 음성 끝(${end.label})") }
    }

    // ---- 아이 프로필 3단계: 선택한 아이들의 말투·호칭(발화·표시 시점마다 읽음, 선택이 없으면 기기 단위 말투) ----
    private fun selectedChildren(): List<ChildProfile> =
        if (::childPanel.isInitialized) childPanel.selectedProfiles() else emptyList()
    // 4차 결정 B: 아이용 화면·음성 문구의 유일한 말투 출처. release는 저장값이 B여도 A로 쓴다(저장값 불변)
    private fun noticeTone(): Tone =
        ToneSetting.runtime(ChildNotice.effectiveTone(selectedChildren(), tone), BuildMockPolicy.TONE_B_ALLOWED)
    private fun noticeMode(): AddressMode = if (::childPanel.isInitialized) childPanel.addressMode() else Greeting.DEFAULT_MODE
    private fun noticeSpeakNames(): Boolean = ::childPanel.isInitialized && childPanel.speakNicknames()
    private fun childNoticeScreen(): String =
        ChildNotice.screenText(noticeTone(), noticeMode(), selectedChildren().map { it.nickname })

    /** 질문 발화 결과(메인 스레드). onDone일 때만 STT를 시작한다. 오류·중지는 터치 우선으로 STT 미시작 */
    private fun onQuestionSpeech(gen: Int, end: TtsEnd) {
        // 이전 질문 발화의 늦은 결과는 버린다(2026-11-08: 발화 식별자 연결)
        if (destroyed || gen != questionTtsGen) { log("[TTS] 이전 질문 발화 결과 무시(세대=$gen, 사유=${end.label})"); return }
        questionTtsGen = -1
        if (gState != GState.ASKING || lockReason() != null) return
        if (end == TtsEnd.DONE) startVoice()
        else {
            questionVoiceFailed = VoiceReplayRule.isVoiceFailure(end)
            voiceNotice = "음성 안내를 확인하지 못했어요. 터치로 확인해 주세요"
            refreshGuardian()
        }
    }

    /** SpeakAnnounce 효과: 안내 발화 요청과 안내→질문 타이머(세대 포함) */
    private fun speakAnnounceFor(gen: Int) {
        guideCycle = gen
        tts?.stop()   // 아이 안내 등 남은 발화 정리(대기 세대 무효화)
        tts?.clearSpeech()
        // 포커스 요청·발화는 화면을 그린 뒤로 한 번 미룬다. 그 사이 단계·잠금·세대가 바뀌었으면 발화하지 않는다
        ui.post {
            if (destroyed || flow.step != BoardingStep.ANNOUNCING || flow.generation != gen || lockReason() != null) return@post
            val g = speakGuide(GuardianDefaults.ANNOUNCE_TEXT, false)
            if (g >= 0) {
                // 발화 요청 성공: 질문은 안내 발화 종료 콜백(onSpeechResult)에서 사유별로. 콜백이 끝내 없을 때만 마지막 안전망(완료 아님)
                announceTtsGen = g; announceFlowGen = gen
                ui.removeCallbacks(askStep)
                askStep = Runnable {
                    if (destroyed || announceTtsGen != g) return@Runnable
                    announceTtsGen = -1
                    log("[보호자확인] 안내 종료 콜백 없음(${GuardianDefaults.ANNOUNCE_MAX_MS}ms 안전망) → 완료 아님, 발화 중지 후 확인 불가로 질문")
                    tts?.stop(keepDrive = true, end = TtsEnd.END_TIMEOUT)   // 남은 안내 발화를 명시적으로 멈춘다(보고는 announceTtsGen=-1이라 무시됨). 주행 경고는 끊지 않는다(감사 LOW 3)
                    dispatchFlow(BoardingEvent.AnnounceFinished(gen, AnnounceOutcome.UNCONFIRMED), "안내 안전망 상한(완료 아님)")
                }
                ui.postDelayed(askStep, GuardianDefaults.ANNOUNCE_MAX_MS)
            } else {
                // 발화하지 않음(안내 음성 꺼짐·준비 안 됨·포커스 거부): 시간 경과로 넘기지 않고 바로 화면 읽기·터치 확인 경로(질문 화면에 안내 문구 유지)
                // 발화 요청 예외는 '음성 없음'이 아니라 '확인 불가'(감사 LOW 2)
                // 사용자 끔(VOICE_OFF, 오류 아님)과 엔진 미준비(NOT_READY)를 구분한다(2026-11-08 ch 조건)
                val outcome = NoSpeechRule.outcome(GuidanceSetting.speechAllowed(guidanceEnabled), tts?.ready == true, tts?.lastSpeakError == true)
                log(AnnounceEndRule.trace("발화 없음", outcome) + " (시간 경과로 완료 처리 안 함)")
                dispatchFlow(BoardingEvent.AnnounceFinished(gen, outcome), "안내 발화 없음")
                return@post
            }
            refreshGuardian()   // 발화 요청·지움이 바꾼 상태 문구 반영(내용이 같으면 대입 생략)
        }
        announceTtsGen = -1
        ui.removeCallbacks(askStep)
    }

    /** SpeakQuestion 효과: 질문 발화(가능하면) 후 음성 인식. 발화 불가면 바로 인식(기존과 같음) */
    private fun speakQuestionFor(gen: Int) {
        voiceNotice = ""
        questionVoiceFailed = false
        // 안내 발화가 남아 있으면 먼저 멈춰 그 종료 이벤트가 질문 것으로 오인되지 않게 한다
        tts?.stop()
        // 2026-11-12 A2: 음성 엔진 재연결 중이면 질문을 쌓지 않고 음성 실패로 둔다(터치 확인 + 재연결 뒤 [다시 듣기]). 인식도 시작하지 않는다
        fun reconnectingFail(): Boolean {
            if (tts?.reconnecting != true || !GuidanceSetting.speechAllowed(guidanceEnabled)) return false
            log("[TTS] 음성 엔진 다시 연결 중 → 질문 발화 건너뜀(화면 질문·터치, 재연결 뒤 다시 듣기)")
            questionVoiceFailed = true
            voiceNotice = "음성 안내를 확인하지 못했어요. 터치로 확인해 주세요"
            refreshGuardian()
            return true
        }
        if (reconnectingFail()) return
        if (tts?.ready != true || !GuidanceSetting.speechAllowed(guidanceEnabled)) {
            // 음성 안내 불가(준비 안 됨·tts 아직 없음 또는 사용자가 끔): 질문 상태가 반영된 뒤 즉시 인식을 시작한다
            ui.post { if (!destroyed && flow.step == BoardingStep.ASKING && flow.generation == gen) startVoice() }
            return
        }
        ui.post {
            if (destroyed || flow.step != BoardingStep.ASKING || flow.generation != gen || lockReason() != null) return@post
            if (reconnectingFail()) return@post
            if (tts?.ready != true || !GuidanceSetting.speechAllowed(guidanceEnabled)) { startVoice(); return@post }   // 그 사이 준비 해제·끔: 발화 없음 경로
            val spoke = speakGuide(GuardianDefaults.QUESTION_TEXT, true)
            questionTtsGen = spoke
            if (spoke < 0) {
                questionVoiceFailed = GuidanceSetting.speechAllowed(guidanceEnabled)
                log("[TTS] 음성 안내 확인 불가(발화 요청 실패) → 음성 인식 시작 안 함, 터치 확인")
                voiceNotice = "음성 안내를 확인하지 못했어요. 터치로 확인해 주세요"
            }
            refreshGuardian()
        }
        // 발화 요청 성공이면 질문 발화의 onDone(onQuestionSpeech)에서 STT를 시작한다
    }

    // ---- 음성 인식(SpeechToText, ON_DEVICE만). 인식 원문은 로그·화면에 남기지 않는다 ----
    private var stt: SpeechToText? = null
    private var sttReady = false          // initialize 성공
    private var sttDisabledText = ""      // 초기화 실패 시 화면 문구
    private var voiceActive = false       // request 후 stop 전까지 true (중복 request 방지)
        set(v) { if (field != v) { field = v; ui.post { renderStageOnly() } } }   // 2026-11-08 v6.1: 캐릭터 듣기 표시 갱신(상태 변경 없음)
    private var destroyed = false

    // 요청 세대: request마다 1씩 증가. 콜백은 자기 요청의 세대를 캡처하고,
    // 메인 스레드(ui.post) 안에서 현재 세대·voiceActive와 같을 때만 처리한다.
    // 규칙 표(콜백 도착 시):
    //   세대 불일치 또는 voiceActive=false 또는 destroyed → 무시
    //   onUpdated(최종) → 판정 후 stopVoice, 결과 반영
    //   onUpdated(중간) → 무시
    //   onError → stopVoice, 오류 종류만 안내 (터치 안내)
    //   onEndedRecognition(최종 결과 없음) → 안내만 표시, 상태 ASKING 유지, 재request 없음
    private var requestGen = 0
    private var activeListener: ResultListener? = null

    private fun newResultListener(gen: Int) = object : ResultListener {
        override fun onUpdated(p0: String, p1: Boolean) {
            if (!p1) return   // 중간 결과 무시
            ui.post {
                if (destroyed || gen != requestGen || !voiceActive) return@post
                val d = decideSdkFinalText(p0)
                stopVoice("음성 결과-" + when (d) { VoiceDecision.YES -> "예"; VoiceDecision.NO -> "아니오"; VoiceDecision.UNSURE -> "불확실" })   // 최종 결과 1개만 사용
                onVoiceResult(d)
            }
        }
        override fun onUpdatedEpdData(p0: Long, p1: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {
            ui.post {
                if (destroyed || gen != requestGen || !voiceActive || gState != GState.ASKING) return@post
                log("[음성] 최종 결과 없이 종료")
                voiceNotice = "음성 응답이 끝났어요. 터치로 확인해 주세요"
                refreshGuardian()
            }
        }
        override fun onError(p0: ErrorType) {
            val name = p0.name
            ui.post {
                if (destroyed || gen != requestGen || !voiceActive) return@post
                stopVoice("오류")
                log("[음성] 오류 종류=$name")
                voiceNotice = "음성 응답을 확정하지 못했어요. 터치로 확인해 주세요"
                refreshGuardian()
            }
        }
        override fun onReady() {}
        override fun onUpdatedRms(p0: Double) {}
    }

    private fun detachListener() {
        val l = activeListener ?: return
        activeListener = null
        runCatching { stt?.removeListener(l) }
    }

    private fun initStt() {
        if (stt != null) return   // 재초기화 중복 방지
        try {
            val s = SpeechToText(applicationContext, Mode.ON_DEVICE)
            stt = s
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ui.post {
                        if (destroyed) return@post
                        runCatching {
                            s.setLocale(Locale.KOREA)
                        }.onFailure {
                            sttDisabledText = "음성 사용 불가(원인: ${it.javaClass.simpleName}). 터치로 응답해 주세요"
                            log("[음성] 리스너/로케일 설정 예외 ${it.javaClass.simpleName}")
                            refreshGuardian(); return@post
                        }
                        sttReady = true
                        log("[음성] 초기화 성공(ON_DEVICE)")
                        refreshGuardian()
                    }
                }
                override fun onFailure(p0: ErrorType) {
                    val name = p0.name
                    ui.post {
                        if (destroyed) return@post
                        sttReady = false
                        sttDisabledText = "음성 사용 불가(원인: $name). 터치로 응답해 주세요"
                        log("[음성] 초기화 실패 원인=$name")
                        refreshGuardian()
                    }
                }
            })
        } catch (t: Throwable) {
            sttReady = false
            sttDisabledText = "음성 사용 불가(원인: ${t.javaClass.simpleName}). 터치로 응답해 주세요"
            log("[음성] 초기화 예외 ${t.javaClass.simpleName}")
            refreshGuardian()
        }
    }

    // 음성 응답 사용 스위치(철회). 2026-11-10 2차 디렉티브 ③: 최초 설치 기본 꺼짐, 보호자가 켜거나 끈 값을 VoiceAnswerStore(별도 파일)에 저장
    private var voiceEnabled = VoiceAnswerSetting.DEFAULT_ENABLED
    private lateinit var voiceSwitch: android.widget.Switch
    private lateinit var noticeTitle: TextView
    private lateinit var noticeBody: TextView
    private lateinit var noticeBox: LinearLayout      // 안내 본문 + 안내 음성 저장 설명(접이식 안에 함께 접힘)
    private lateinit var guardianHeader: TextView        // '보호자 설정' 구분 머리글(잠금 시 숨김)
    private lateinit var switchRow: LinearLayout     // 음성 응답·안내 음성 스위치 한 줄
    private lateinit var detailTitle: TextView        // 상세 상태(개발용) 접이식 제목

    private fun startVoice() {
        val s = stt ?: return
        if (!voiceEnabled) return   // 꺼짐: request를 시작하지 않는다(터치만)
        if (!sttReady || voiceActive || gState != GState.ASKING || lockReason() != null) return
        requestGen++
        voiceFlowGen = flow.generation
        val listener = newResultListener(requestGen)
        voiceActive = true
        try {
            s.addListener(listener)
            activeListener = listener
            s.request()
            log("[음성] 인식 시작 요청")
        } catch (t: Throwable) {
            // 이번 질문의 음성을 중단하고 터치 안내로 넘긴다(예외 클래스 이름만 노출, 자동 재시도 없음)
            voiceActive = false
            requestGen++   // 이미 등록된 콜백이 오더라도 무시
            if (activeListener == null) runCatching { s.removeListener(listener) } else detachListener()
            log("[음성] request 예외 ${t.javaClass.simpleName}")
            voiceNotice = "음성 응답을 확정하지 못했어요. 터치로 확인해 주세요"
            refreshGuardian()
        }
    }

    /** reason은 고정된 사유 문구만 받는다(인식 텍스트·발화 내용 금지). 로그만 추가하며 동작은 그대로다 */
    private fun stopVoice(reason: String = "기타") {
        if (!voiceActive) return
        voiceActive = false
        detachListener()
        if (stt != null) log("[음성] 인식 중지(사유=$reason)")   // 실제 stop 호출 직전에만 기록
        try { stt?.stop() } catch (t: Throwable) { log("[음성] stop 예외 ${t.javaClass.simpleName}") }
    }

    // 음성 결과 진입점. 인식 원문은 받지 않고 판정값만 받는다(SDK 연결은 다음 단계 TODO).
    // 어느 스레드에서 불려도 메인 스레드에서 처리한다.
    fun onVoiceResult(decision: VoiceDecision) {
        ui.post {
            log("[음성] 판정 결과=" + when (decision) { VoiceDecision.YES -> "예"; VoiceDecision.NO -> "아니오"; VoiceDecision.UNSURE -> "불확실" })
            if (gState != GState.ASKING) {
                // 늦게 온 결과: 화면 안내 없이 로그(위 판정 결과)만 남기고 무시
                return@post
            }
            val ok = lockReason() == null
            if (decision == VoiceDecision.UNSURE || !ok) {
                voiceNotice = "음성 응답을 확정하지 못했어요. 터치로 확인해 주세요"
                refreshGuardian()
                return@post
            }
            voiceNotice = ""
            if (decision == VoiceDecision.YES) onGuardianYes(GuardianDefaults.VOICE_YES_SOURCE, voiceFlowGen)
            else { responseSource = "음성(미검증)"; onGuardianNoOrTimeout("음성 '아니오' (미검증)", answerGen = voiceFlowGen) }
        }
    }

    /** gen: 이 응답이 답한 질문 세대(터치는 지금 화면의 세대, 음성은 인식을 시작한 세대) */
    private fun onGuardianYes(source: String = "터치", gen: Int = flow.generation) {
        if (gState != GState.ASKING || lockReason() != null) return
        val askedGen = gen
        val src = if (source.startsWith("음성")) ResponseSource.VOICE else ResponseSource.TOUCH
        responseSource = source
        dispatchFlow(BoardingEvent.Answer(true, src, askedGen), "응답예")
        if (flow.step != BoardingStep.CHILD_NOTICE || flow.report?.generation != askedGen) { responseSource = ""; return }   // 게이트가 막음
        // 보호자 '예' 순간: 대상 좌석을 armed로 복사(그 시점 체결로 유효한 좌석만 감시). 모의 모드 값은 감시값으로 쓰지 않는다
        if (beltWarn.active) endBeltReleaseSession("새 '예' 응답")
        beltWarn = BeltWarningPolicy.arm(flow.report?.seats ?: emptySet(), if (useSim) emptyMap() else realBelt)
        beltMoved = false
        beltUndelivered.clear(); beltMonitorGap = null; syncHomeNotes()
        log("[주행안내] 감시 시작 대상=${beltWarn.armed.size}석 체결 감시=${beltWarn.lastBuckled.size}석")
        guardLog("보호자가 '예' 응답, 출처=$source (보호자 보고)")
        refreshGuardian()
    }

    /** 아니오·시간 초과. timeoutGen은 시간 초과 타이머가 예약될 때의 세대(늦은 타이머는 BoardingFlow가 버림) */
    private fun onGuardianNoOrTimeout(why: String, timeoutGen: Int? = null, answerGen: Int = flow.generation) {
        if (gState != GState.ASKING) return
        guardLog("$why → 다음 단계로 진행하지 않음")
        val e = if (timeoutGen != null) BoardingEvent.Timeout(timeoutGen)
                else BoardingEvent.Answer(false, if (responseSource.startsWith("음성")) ResponseSource.VOICE else ResponseSource.TOUCH, answerGen)
        val reannounce = flow.reannounce < GuardianDefaults.MAX_REANNOUNCE
        dispatchFlow(e, if (reannounce) "재안내" else if (timeoutGen != null) "시간초과" else "응답아니오")
    }

    private fun toggleTarget(name: String) {
        if (lockReason() != null) { refreshGuardian(); refreshBoard(); return }
        if (gState != GState.IDLE) { guardLog("대상 좌석 변경 → 진행 중 확인 취소"); dispatchFlow(BoardingEvent.ToHome, "대상변경") }
        if (!targetSeats.add(name)) targetSeats.remove(name)
        refreshGuardian(); refreshBoard()
    }

    private fun setBubble(text: String) {
        if (text == lastBubbleText) return
        lastBubbleText = text
        bubbleView.text = text
    }

    private fun refreshGuardian() {
        val g = gear
        val lock = lockReasonOf(g)
        if (gState != GState.ASKING || lock != null) questionVoiceFailed = false   // A2: [다시 듣기]는 이번 질문 단계 안에서만(잠금 뒤 복구해도 자동 재개 없음)
        // 질문 상태를 벗어났거나 잠금이면 인식을 즉시 멈춘다(멱등)
        if (gState != GState.ASKING || lock != null || !voiceEnabled) stopVoice(
            when {
                lock != null -> "잠금"
                gState == GState.YES_REPORTED -> "예 응답"
                gState == GState.WAITING_GUARDIAN -> "아니오·시간초과"
                gState == GState.ASKING -> "음성 스위치 끔"
                gState == GState.ANNOUNCING -> "재안내"
                else -> "그 외(상태=${gState.name})"
            }
        )
        // 안내·질문 상태를 벗어났거나 잠금이면 음성 안내도 즉시 멈춘다(대기 발화가 있을 때만 호출)
        if ((gState != GState.ANNOUNCING && gState != GState.ASKING && gState != GState.YES_REPORTED) || lock != null) tts?.stop(lock != null, keepExample = lock == null && gState == GState.IDLE, keepDrive = true, end = if (lock != null) TtsEnd.GATE_STOP else TtsEnd.APP_STOP, keepExit = keepExitOnRecheck(lock))   // 결정 13: 복귀 재조회만으로 생긴 잠금은 하차 한 줄을 끊지 않는다. IDLE·잠금 없음이면 예시 발화 유지. 주행 벨트 경고는 감시 세션이 관리하므로 화면 갱신으로 끊지 않는다(Unit 5.3: P 복귀 때 끊으면 onDone 유실)
        // 잠금 상태에서는 어떤 진행 상태도 남기지 않는다(주행 중 질문·응답·다음 단계 비노출)
        if ((lock != null || currentGate() != VehicleGate.PARK_ALLOWED) && gState != GState.IDLE && resumed) {
            cancelFlowForGate()   // 공통 취소 후 commitFlow가 다시 그린다
            return
        }
        val sb = StringBuilder()
        // 상세 줄은 순수 함수(DetailLines)로 두 묶음 분배: 보호자용 세 줄(상태 안내) / 나머지(개발용). 문구는 그대로
        // (안내 음성 꺼짐은 사용자 선택이며 화면 문구는 음성 안내를 대신하지 않는다. tts가 아직 없으면 기존 초기 문구 유지)
        val detailSplit = DetailLines.split(voiceEnabled, sttDisabledText, sttReady, guidanceEnabled, tts?.statusLines(), g.linkOk, g.name, targetSeats.toList())
        val statusText = detailSplit.guardian
        val detailText = detailSplit.dev
        when {
            lock != null && gState == GState.IDLE -> {
                if (lockShownText(g) == LOCK_TEXT_CHECKING) sb.append(LOCK_TEXT_CHECKING).append('\n')
                else sb.append("잠김: $lock → 질문·응답 사용 불가\n")
                linkRetryText()?.let { sb.append(it).append('\n') }
            }
            gState == GState.IDLE -> {
                flow.interruption?.let { sb.append(it.text).append('\n') }   // 진행 중 확인을 멈춘 이유(P 이탈·불명·화면 이탈 구분)
                sb.append(if (targetSeats.isEmpty()) "대상 좌석을 먼저 골라 주세요\n" else "시작 대기\n")
            }
            gState == GState.ANNOUNCING -> sb.append("안내: ${GuardianDefaults.ANNOUNCE_TEXT}\n")
            gState == GState.ASKING -> {
                // 안내 음성이 완료되지 않았으면(확인 불가·음성 없음) 안내 문구를 질문과 함께 남기고 이유를 보인다(2026-11-08)
                flow.announceOutcome?.takeIf { !it.spokenConfirmed }?.let { sb.append(it.note).append('\n') }   // note에 안내 문구 포함
                sb.append("질문: ${GuardianDefaults.QUESTION_TEXT}  (보호자 응답 대기)\n")
            }
            gState == GState.WAITING_GUARDIAN ->
                sb.append(if (flow.announceOutcome == AnnounceOutcome.CANCELLED) AnnounceOutcome.CANCELLED.note else "응답이 없어 멈췄어요. 다시 시작할 수 있어요").append('\n')
            gState == GState.YES_REPORTED -> {
                sb.append("보호자가 예라고 답함 (보호자 보고, 앱이 확인한 것 아님)\n")
                // 보조 표시. 음성 안내가 불가해도 이 문구가 음성을 대신한다는 뜻이 아니다
                sb.append(ReleaseCopy.t(GuardianDefaults.CHILD_NOTICE_LABEL)).append(": ").append(childNoticeScreen()).append('\n')
            }
        }
        if (responseSource.isNotEmpty()) sb.append("응답 출처: ").append(responseSource).append('\n')
        if (voiceNotice.isNotEmpty()) sb.append(voiceNotice).append('\n')
        val guardText = sb.toString()
        if (guardText != lastGuardText) { lastGuardText = guardText; guardView.text = guardText }
        if (detailText != lastDetailText) { lastDetailText = detailText; guardDetailView.text = detailText }
        if (statusText != lastStatusText) { lastStatusText = statusText; guardStatusView.text = statusText }
        // 캐릭터: 보호자 확인 상태 기준(TTS 요청 상태가 아님). 잠금이면 캐릭터와 말풍선을 숨기고 IDLE로 되돌려 둔다
        if (lock != null) {
            characterBox.visibility = android.view.View.GONE
            characterView.mood = GuideMood.IDLE
            setBubble("")
        } else {
            characterBox.visibility = android.view.View.VISIBLE
            // 안내 음성을 사용자가 끈 경우는 오류가 아니므로 UNKNOWN으로 만들지 않고 보호자 확인 상태 기준 모양을 유지한다
            val voiceUnavailable = (guidanceEnabled && tts?.ready != true) || voiceNotice.isNotEmpty()
            characterView.mood = if (voiceUnavailable) GuideMood.UNKNOWN else when (gState) {
                GState.IDLE -> GuideMood.IDLE
                GState.ANNOUNCING -> GuideMood.SPEAKING
                GState.ASKING -> GuideMood.LISTENING
                GState.YES_REPORTED -> GuideMood.CHILD_NOTICE
                GState.WAITING_GUARDIAN -> GuideMood.WAITING
            }
            setBubble(when (gState) {
                GState.ANNOUNCING -> GuardianDefaults.ANNOUNCE_TEXT
                GState.ASKING -> GuardianDefaults.QUESTION_TEXT
                GState.YES_REPORTED -> childNoticeScreen()
                else -> ""
            })
        }
        val asking = gState == GState.ASKING && lock == null
        yesBtn.visibility = if (asking) android.view.View.VISIBLE else android.view.View.GONE
        noBtn.visibility = yesBtn.visibility
        yesBtn.isEnabled = asking; noBtn.isEnabled = asking
        startBtn.visibility = if (lock == null) android.view.View.VISIBLE else android.view.View.GONE
        startBtn.isEnabled = lock == null && targetSeats.isNotEmpty() && (gState == GState.IDLE || gState == GState.WAITING_GUARDIAN || gState == GState.YES_REPORTED)
        startBtn.text = if (gState == GState.WAITING_GUARDIAN && lock == null) "보호자 확인 다시 시작" else "보호자 확인 시작"
        if (gState == GState.YES_REPORTED && lock == null) {
            nextView.text = "다음 단계는 준비 중이에요" + "\n보호자가 예라고 답함"
            nextView.visibility = android.view.View.VISIBLE
        } else {
            nextView.text = ""
            nextView.visibility = android.view.View.INVISIBLE   // GONE이면 아래 캐릭터가 밀리므로 자리를 유지한다
        }
        // 참여 스티커: 이동당 최대 1회(TripSessionRule). 잠금(D·UXR·단절)·화면 이탈은 지급하지 않을 뿐 이력을 지우지 않는다.
        // 벨트 신호·좌석 상태 모델은 입력 아님. 자리는 INVISIBLE로 유지
        // 2026-11-08 Unit 6 최종(ch 결정 3): 세션 '지급함' 표시는 실제 기록 저장 성공(아이 1명 이상)과 같은 commit에서만 반영한다.
        // 아이 0명·저장 실패·읽기 실패는 세션을 소진하지 않고, 같은 '예' 보고(질문 세대)에서는 자동 재시도하지 않는다(다시 '예'로 재시도).
        // 참여 표시(show)는 보호자 보고 기준 화면 표시일 뿐 영구 기록 성공과 별개다
        // 2026-11-13: 이 블록은 예전 보호자 '예'(터치·음성) 보고에서만 동작한다. 자동 확인 흐름의 스티커는 grantStickerIfEarned(하차 마무리·이동 끝내기)
        val legacyYes = gState == GState.YES_REPORTED && flow.report?.source.let { it == ResponseSource.TOUCH || it == ResponseSource.VOICE }
        val yesGen = if (legacyYes) flow.report?.generation ?: -1 else -1
        val sticker = TripSessionRule.stickerStep(TripSessionStore.state, lock != null, targetSeats.size,
            legacyYes, attemptedThisYes = yesGen >= 0 && yesGen == stickerAttemptGen)
        if (sticker.newlyGranted) {
            stickerAttemptGen = yesGen
            stickerSessionKey += 1
            val cur = TripSessionStore.state
            val after = TripSessionRule.markSticker(cur)
            val r = if (::childPanel.isInitialized) childPanel.grantSticker(stickerSessionKey, after) else 0
            if (r > 0) { TripSessionStore.state = after; TripSessionStore.readFailed = false }
            stickerKids = maxOf(0, r)
            stickerRecordFailed = r < 0
            log(when {
                r == ChildProfilePanel.Grant.SAVE_FAILED -> "[스티커] 이동 ${cur.tripKey} — 기록 저장 실패(기록·세션 모두 안 바뀜, 다시 '예'로 재시도 가능)"
                r == ChildProfilePanel.Grant.READ_FAILED -> "[스티커] 이동 ${cur.tripKey} — 기존 기록을 읽지 못해 저장하지 않음(원본 보존, 세션 소진 안 함)"
                r == 0 -> "[스티커] 이동 ${cur.tripKey} — 선택된 아이 없음: 참여 표시만(영구 기록 없음, 세션 소진 안 함)"
                else -> "[스티커] 이동 ${cur.tripKey} 기록 저장(보호자 보고 기준, 아이 ${r}명, 세션 지급 표시 함께 저장)"
            })
        }
        if (gState != GState.YES_REPORTED && SystemClock.elapsedRealtime() >= stickerJustGrantedUntil) stickerRecordFailed = false
        stickerKidsShown = StickerMoment.displayKids(yesGen, stickerAttemptGen, stickerKids)   // v3 반복 4: 두 번째 '예'에 이전 +1이 다시 보이지 않게
        if (::auxLabel.isInitialized) {
            auxLabel.text = ReleaseCopy.t(DemoLabel.build(sticker.show, stickerKidsShown, mockGearOn, stickerRecordFailed))   // '(아이 N명에게 +1)'은 스티커가 보일 때(YES_REPORTED)만
        }
        stickerShowNow = sticker.show
        stickerRow.visibility = if (sticker.show) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (finishConfirm != null && !manualFinishAllowedNow()) closeManualFinishConfirm("조건 해제(주행·UXR·연결·진행 중 확인)")
        if (nightPending || layoutPending) ui.post { maybeApplyNight() }   // P1-A: 미뤄 둔 야간 전환(안전 시점에만)
        // 잠금이면 조작 버튼을 숨긴다(판정 표시·로그는 읽기 전용으로 유지). 해제되면 다시 보인다
        val ctl = if (lock == null) android.view.View.VISIBLE else android.view.View.GONE
        // '다시 읽기'는 개발용 SDK 구독 복구 버튼: 기어 구독 실패로 잠긴 경우에도 보이고, P 아님(정상 수신) 잠금에서는 숨긴다
        readBtn.visibility = if (lock == null || gearLinkFailed()) android.view.View.VISIBLE else android.view.View.GONE
        modeButton.visibility = if (DevMockGuard.allowed) ctl else android.view.View.GONE   // release: 벨트 모의 전환 숨김
        voiceSwitch.visibility = ctl; voiceSwitch.isEnabled = lock == null
        guidanceSwitch.visibility = ctl; guidanceSwitch.isEnabled = lock == null; switchRow.visibility = ctl
        if (::guardianHeader.isInitialized) guardianHeader.visibility = ctl
        for (b in simButtons) b.visibility = if (DevMockGuard.allowed) ctl else android.view.View.GONE   // release: 모의 벨트 버튼 숨김
        for ((_, b) in seatButtons) { b.visibility = ctl; b.isEnabled = lock == null }
        // 추천은 대상 좌석이 비어 있을 때만 글자("추천")로 붙이고 자동 선택하지 않는다. 한 줄 고정으로 버튼 높이를 바꾸지 않는다
        for ((n, b) in seatButtons) {
            val short = seatShort[n] ?: n
            b.text = when {
                n in targetSeats -> "✔$short"
                targetSeats.isEmpty() && short in suggestedSeats -> "지난번 $short"
                else -> short
            }
        }
        refreshExamples()
        refreshTripCard()
        if (settingsOpen && (lock != null || gState != GState.IDLE)) closeStageSettings()   // 설정은 정차 P·IDLE에서만: 아니면 무대(잠금 자막 등)로 복귀
        hideOldFlowViews()
        syncHome()
        stageScreen?.let { if (it.visibility == android.view.View.VISIBLE) it.render(buildStageState()) }   // 무대 미리보기(읽기 전용)
    }

    // ---- 무대 미리보기(가안, 개발용). 롤백: StageFlags/CharacterRenderer/StageScene/StageScreen.kt 삭제 + 이 블록·setupStagePreview 호출·refreshGuardian 끝 한 줄·onPause/onDestroy의 stageScreen 줄 삭제 ----
    private var stageScreen: StageScreen? = null

    /** 무대만 다시 그린다(상태·판정 변경 없음). 캐릭터 말하기·듣기 표시 갱신용 */
    private fun renderStageOnly() {
        if (destroyed) return
        syncTripState("화면 갱신")   // r10 M: 이동 상태가 바뀌었으면 저장(같으면 아무것도 안 함). PM-INTEGRATE(r10 M): 화면 갱신 = 상태 변화 지점 모음
        stageScreen?.let {
            if (it.visibility == android.view.View.VISIBLE) {
                it.render(buildStageState())   // 2026-11-14: 이동 끝 말풍선 음성(TRIP_END)은 장면과 함께 없앴다(사용자 지시)
            }
        }
    }

    // ---- 새 무대가 기본 화면일 때(StageFlags.NEW_STAGE) 예전 흐름 화면·개발용 도구를 숨긴다. 롤백: NEW_STAGE=false ----
    private var oldFlowViews: List<android.view.View> = emptyList()
    private var devToggleView: android.view.View? = null
    private var devBoxView: android.view.View? = null
    // 설정 막대를 길게 누르면 켜지는 점검용 도구(메모리에만 유지, 저장 안 함). release 빌드는 setter가 항상 false로 둔다(DevMockGuard)
    private var devUnlocked = false
        set(v) { field = DevMockGuard.admit(v) }

    private fun hideOldFlowViews() {
        if (!StageFlags.NEW_STAGE) return
        for (v in oldFlowViews) v.visibility = android.view.View.GONE
        devToggleView?.visibility = if (devUnlocked) android.view.View.VISIBLE else android.view.View.GONE
        if (!devUnlocked) devBoxView?.visibility = android.view.View.GONE
    }
    private var stageExitUntilMs = 0L   // 하차 확인 음성 요청 후 자막을 보일 때까지의 시각(elapsedRealtime). 메인 스레드 전용

    private fun stageG(g: GState): StageGState = StageGState.valueOf(g.name)   // 두 enum의 이름은 같다

    /** 읽기 전용: 현재 상태를 무대용 스냅샷으로 만든다(새 상태 변수·부작용 없음) */
    private fun buildStageState(): StageUiState {
        val validP = lockReason() == null
        return StageUiState(
            g = stageG(gState),
            locked = !validP,
            lockText = lockShownText() ?: lockReason() ?: "",
            childrenSelected = selectedChildren().size,
            seatsSelected = targetSeats.size,
            tripPromptShown = false,   // 사용 안 함(2026-11-14 사용자 지시: 이동 끝 안내 제거). 아래 tripPromptText·tripSourceMock도 같다
            stickerShown = stickerShowNow,
            stickerKidCount = stickerKidsShown,
            stickerRecordFailed = stickerRecordFailed,
            stickerAlreadyThisTrip = stickerShowNow && stickerKidsShown == 0 && !stickerRecordFailed &&
                TripSessionRule.stickerGranted(TripSessionStore.state),
            tripPromptText = "",
            tripSourceMock = false,
            childNoticeText = childNoticeScreen(),
            exitCheckActive = SystemClock.elapsedRealtime() < stageExitUntilMs,
            profilesExist = stageProfiles().isNotEmpty(),
            // 모의 표기(Unit 5.2, N4): 새 무대 보조 라벨에 '앱 내부 모의 기어'·모의 이동 문구를 넣는다. release는 둘 다 꺼짐/빈 값
            mockGear = mockGearOn,
            mockMovingText = devTools.movingLine() ?: "",
            // 2026-11-08 v6.1 캐릭터 표시(읽기 전용): 실제 TTS 재생·STT 활성, 유효한 비-P 기어 수신 여부
            crewVoice = CrewArt.voiceOf(tts?.speakingNow == true, voiceActive),
            drivingGear = freshGear().let { gearLockReason(it.name, it.linkOk, it.subFailed) == LOCK_TEXT_NOT_PARK },
            announceOutcome = flow.announceOutcome,
            replayAvailable = replayAvailableNow(),
            voiceReconnecting = gState == GState.ASKING && questionVoiceFailed && tts?.reconnecting == true,
            autoCheck = buildAutoCheck(),
        )
    }

    private fun stageProfiles(): List<ChildProfile> =
        if (::childPanel.isInitialized) childPanel.profileList() else emptyList()

    /** 무대 컨트롤 → 기존 함수 위임(새 상태·판정 없음). 좌석은 짧은 이름(R1L 등)으로 주고받는다 */
    private val stageActions = object : StageActions {
        private fun seatKey(short: String): String? = seatShort.entries.firstOrNull { it.value == short }?.key
        override fun onYes() { onGuardianYes("터치") }
        override fun onNo() { noBtn.performClick() }   // 기존 아니오 버튼의 클릭 처리 그대로
        override fun onReplay() { onReplayQuestion() }
        // 2026-11-13 계약 v2: 무대의 시작·다시 시작도 좌석 화면 확인으로 바로 간다(옛 아이·좌석 선택 화면 경로는 쓰지 않는다)
        override fun onStart() { startSeatBoardCheck("무대 시작") }
        override fun onRestart() { startSeatBoardCheck("다시 시작") }
        override fun onSeatTap(seat: String) { onSeatTapAssign(seat) }
        override fun onFinish() {
            // 2026-11-14 PM 결정 25: 확인 중(CHECKING) [처음으로] = 확인 취소 → 홈(디자인이 출발 준비 화면과 같은 자리·모양의 버튼을 이 액션에 붙인다)
            if (currentAutoPhase() == AutoPhase.CHECKING) { cancelCheckToHome(); return }
            // 2026-11-13: 확인을 마쳤지만 아직 출발하지 않은 상태(DEPART_READY)에서는 이동 끝내기가 아니라 확인을 접고 홈으로 간다(주행 근거가 없어 finishTrip은 거절됨)
            if (currentAutoPhase() == AutoPhase.CONFIRMED) { leaveConfirmedToHome(); return }
            // r7 2절(나): 하차 안내 화면(도착·중간 하차)의 [다 내렸어요] = 보호자 버튼 끝내기(멘트 + 작별 인사 → 마무리 화면). 조건이 아니면 기존 끝내기
            if (guardianFinish("하차 안내 화면 버튼")) return
            if (finishTrip("내비 신호 끝내기") && StageFlags.NEW_STAGE) { homeActive = true; syncHome() }
        }
        override fun manualFinishVisible(): Boolean = manualFinishAllowedNow()
        override fun onManualFinish() { openManualFinishConfirm() }
        override fun onNewTripFromAllLeft() { newTripFromAllLeft() }   // 계약 v4(임시 결정 34)
        override fun onArrivalAnswer(arrived: Boolean) { this@MainActivity.onArrivalAnswer(arrived) }   // 계약 v5(r5 추가 A)
        override fun onAlightAnswer(alight: Boolean) { this@MainActivity.onAlightAnswer(alight) }   // 계약 v6(r6 5절)
        override fun onToggleSeat(name: String) { seatKey(name)?.let { toggleTarget(it) } }
        override fun seatNames(): List<String> = seats.map { seatShort[it.first] ?: it.first }
        override fun seatSelected(name: String): Boolean = seatKey(name)?.let { it in targetSeats } ?: false
        override fun canStart(): Boolean = startBtn.isEnabled   // refreshGuardian이 만든 값을 읽기만 함
        override fun profileChips(): List<ChipInfo> =
            stageProfiles().map { ChipInfo(it.id, chipLabel(it.nickname), childPanel.isChildSelected(it.id), it.style) }
        // 기존 프로필 카드 클릭과 같은 처리(toggle/chooseLast). 패널은 선택 변경 후 무대를 다시 그리지 않으므로 기존 refreshGuardian으로 갱신한다
        override fun onToggleChild(id: Int) { if (::childPanel.isInitialized) { childPanel.toggle(id); refreshGuardian() } }
        override fun lastChipVisible(): Boolean = ::childPanel.isInitialized && childPanel.lastChoiceVisible()
        override fun onUseLast() { if (::childPanel.isInitialized) { childPanel.chooseLast(); refreshGuardian() } }
        override fun onOpenSettings() { openStageSettings() }
        override fun seatBeltRef(name: String): SeatBeltRef {
            val key = seatKey(name) ?: return SeatBeltRef.UNKNOWN
            val inp = seatInputOf(key)
            return StageSeatSignal.beltRefOf(
                supported = inp.supported,
                linkOk = inp.linkOk,
                readFailed = inp.error == SeatErrorState.READ_FAILED,
                latestBuckled = inp.latestBuckled,
                // v3 D1: 값 없는 이벤트를 받은 좌석은 '확인 불가'(표시 전용, 판정 입력 seatInputOf는 그대로)
                emptyValueReceived = !useSim && realBelt.containsKey(key) && realBelt[key] == null,
            )
        }
        override fun seatOccupied(name: String): Boolean? {
            val key = seatKey(name) ?: return null
            return if (useSim) true else occupied[key]
        }
        override fun isBeltMock(): Boolean = useSim
        override fun isBeltInjected(): Boolean = DevMockGuard.allowed && debugBeltHeld.isNotEmpty()
        override fun onDemoMockStart() { devTools.startMockNavi() }
        override fun onDemoMockArrive() { devTools.forceArriveMockNavi() }
        override fun onDemoToggleBeltSource() {
            if (lockReason() != null) return
            useSim = !useSim
            refreshBoard()
        }
        override fun onDemoToggleRearSimBelt() {
            toggleSim("ROW_2_LEFT")
            toggleSim("ROW_2_RIGHT")
        }
        override fun demoStatusLine(): String =
            "모의내비: ${devTools.naviStateName()} | 벨트출처: ${if (useSim) "모의" else "실제"}"
        /** 2026-11-13 PM 계약: 신호 확인 불가 좌석의 보호자 대체 버튼. 좌석은 SDK 키(ROW_2_LEFT) 또는 짧은 이름(R2L) 모두 받는다 */
        override fun onGuardianConfirmSeat(seat: String) { onGuardianConfirmSeatTap(if (seat in seatShort.keys) seat else seatKey(seat) ?: seat) }
    }

    // ---- 2026-11-11 UI/UX v2 U2: 보호자 설정 화면 레이아웃(공통 카드). 기능 뷰(아이 관리·스위치·지우기)는 기존 객체를 그대로 옮겨 담는다 ----
    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun buildSettingsLayout(
        kids: android.view.View, voiceRows: android.view.View,
        suggestNote: TextView, suggestClear: android.view.View, suggestConfirm: android.view.View,
    ): LinearLayout {
        val roles = DuoTokens.roles(Palette.current.night)
        val gapPx = dp(DuoTokens.CARD_GAP_DP)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.argb(roles.bg)); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        // v3 반복 4: 제목 줄은 본문 밖 고정 머리(settingsHeader)로, [무대로 돌아가기]는 본문 밖 하단 띠(settingsFooter)로 옮겼다
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cardLp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gapPx } }

        // 루프 r2 설정 정리(디자인 D): 카드마다 제목 + 한 줄 설명 + 조작만(SettingsCopy)
        val kidsCard = DuoCard(this, SettingsCopy.KIDS_TITLE, SettingsCopy.KIDS_DESC)
        kidsCard.addView(kids)
        left.addView(kidsCard, cardLp())

        val voiceCard = DuoCard(this, SettingsCopy.VOICE_TITLE)
        voiceCard.addView(voiceRows)   // 루프 r2: 마이크(음성으로 답하기) 안내 줄은 뺐다(지금 흐름에서 쓰지 않음)
        // 루프 r2: 별명 읽기·호칭 방식(아이 관리 패널의 접이식)을 안내 음성 카드로 옮긴다
        for (v in childPanel.detachAddressViews()) voiceCard.addView(v)
        right.addView(voiceCard, cardLp())

        // 2026-11-14 사용자 지시(P1): 이동 안내 알림을 다시 허용할 수 있는 길(상태 + 버튼). 판단은 NotifSettingRule
        val notifCard = DuoCard(this, NotifSettingRule.CARD_TITLE)
        // 루프 r3: 본문 크기를 다른 카드 설명과 같은 보조 단계(CAPTION_SP)로. 상태 줄은 본문 색으로 읽히게
        notifCard.addView(DuoText.caption(this, NotifSettingRule.WHY))
        notifStateView = DuoText.caption(this, NotifSettingRule.STATE_OFF).apply { setPadding(0, dp(8), 0, 0); setTextColor(Palette.argb(roles.text)) }
        notifCard.addView(notifStateView)
        notifButton = DuoButton(this, DuoTokens.Kind.NEUTRAL).apply {
            text = NotifSettingRule.BUTTON_ALLOW
            setOnClickListener { onNotifSettingTap() }
        }
        notifCard.addView(notifButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        notifNoteView = DuoText.caption(this, NotifSettingRule.NOTE_OPEN_FAILED).apply { setPadding(0, dp(8), 0, 0); visibility = android.view.View.GONE }
        notifCard.addView(notifNoteView)
        right.addView(notifCard, cardLp())

        // 루프 r2: 이번 주 스티커를 아이 관리에서 떼어 따로 카드(④). 접이식 제목 줄이 카드 머리 역할
        val stickerCard = DuoCard(this, SettingsCopy.STICKER_TITLE)
        for (v in childPanel.detachStickerViews()) stickerCard.addView(v)
        left.addView(stickerCard, cardLp())   // 루프 r3: 왼쪽 열 = 아이 관리 → 스티커 → 기록(고정 배치)

        val recCard = DuoCard(this, SettingsCopy.RECORD_TITLE)
        suggestNote.textSize = DuoTokens.CAPTION_SP
        suggestNote.setTextColor(Palette.argb(roles.textMuted))
        DuoText.wordBreak(suggestNote)   // v3 반복 4(P3): 글꼴 1.0에서도 "않아/요." 한 글자 줄바꿈이 남던 것
        recCard.addView(suggestNote)
        recCard.addView(suggestClear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        recCard.addView(suggestConfirm)
        left.addView(recCard, cardLp())

        val infoCard = DuoCard(this, PrivacyNotice.CARD_TITLE)
        // 루프 r3: 고지 문장 크기는 보조 단계로(다른 카드 설명과 같게). 앱 이름·버전·핵심 고지(신호·보장 아님 두 문단)는 본문 색
        infoCard.addView(DuoText.caption(this, "${AppName.NAME} (${AppName.NAME_EN}) · 버전 ${appVersionName()}").apply { setTextColor(Palette.argb(roles.text)) })
        // 2026-11-14 r5 4절(사용자 지시): 고지를 지금 앱 동작에 맞게 짧은 문단 6개로(문구·코드 근거 PrivacyNotice), 검토 표시 줄은 없앴다
        PrivacyNotice.SETTINGS_LINES.forEachIndexed { i, line ->
            infoCard.addView(DuoText.caption(this, line).apply { setPadding(0, dp(8), 0, 0); if (i < 3) setTextColor(Palette.argb(roles.text)) })   // r6: 신호·문·보장 아님 세 문단은 본문 색
        }
        infoCard.addView(DuoButton(this, DuoTokens.Kind.NEUTRAL).apply {
            text = "처음 안내 다시 보기"
            setOnClickListener { if (lockReason() == null && gState == GState.IDLE) { firstRunReplay = true; homeActive = true; closeStageSettings() } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // 루프 r3 QA P2: 앱 정보·개인정보가 길어 왼쪽 열 아래에 큰 빈칸이 생겼다 → 2단 밖, 앱 나가기 바로 위 전체 폭 카드(⑥)

        // 2026-11-14 사용자 지시(P1): [앱 나가기]. 본문 맨 아래 별도 카드 + 주의 모양 버튼([앱으로 돌아가기]는 아래 고정 띠의 파랑 버튼)
        // 루프 r3: 제목 "앱 나가기" + 버튼 "앱 나가기"로 같은 말이 두 번 → 제목 없이 한 줄 설명 + 버튼
        val exitCard = DuoCard(this)
        exitCard.addView(DuoText.caption(this, SettingsCopy.EXIT_DESC))
        exitCard.addView(DuoButton(this, DuoTokens.Kind.DANGER).apply {
            text = AppExitRule.BUTTON
            setOnClickListener { onAppExitTap() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        cols.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = gapPx })
        cols.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(cols)
        wrap.addView(infoCard, cardLp())   // ⑥ 앱 정보·개인정보(전체 폭). 읽는 순서: 2단(①④⑤ / ②③) → ⑥ → 구분선 → ⑦
        // 루프 r2: [앱 나가기]는 2단 아래 맨 끝에 따로(열 균형 이동 대상 아님). 위 카드들과 구분선으로 나눈다
        wrap.addView(android.view.View(this).apply { setBackgroundColor(Palette.argb(roles.disabledStroke)); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = gapPx; bottomMargin = gapPx })
        wrap.addView(exitCard, cardLp())
        SettingsLook.applyJua(wrap)   // 루프 r2: 설정 화면 버튼 글꼴 Jua
        // 루프 r3: 고정 배치(왼쪽 아이 관리·스티커·기록 / 오른쪽 안내 음성·알림·앱 정보). 자동 열 균형은 옮길 카드가 없어 순서를 바꾸지 않는다
        settingsCols = SettingsColRefs(left, right, kidsCard, SettingsLayout.MOVABLE_NONE, gapPx)
        return wrap
    }

    /** v3 반복 6(P1-1): 설정 2열 균형용 참조(오른쪽 카드 순서 = 원래 읽기 순서) */
    private class SettingsColRefs(
        val left: LinearLayout, val right: LinearLayout, val kids: android.view.View,
        val movable: List<android.view.View>, val gapPx: Int,
    )
    private var settingsCols: SettingsColRefs? = null

    /**
     * 설정을 열 때 한 번: 오른쪽 카드를 원래 자리로 되돌린 뒤 실측 높이로 SettingsColumns.moveCount만큼 아래 카드를 왼쪽 열 아래로 옮긴다.
     * 열려 있는 동안(입력·펼침으로 높이가 바뀌어도) 다시 옮기지 않는다 — 누르려던 카드가 자리를 바꾸지 않게.
     */
    private fun balanceSettingsColumns() {
        val c = settingsCols ?: return
        if (c.movable.isEmpty()) return   // 루프 r3: 고정 배치(읽는 순서 유지)
        if (c.left.width <= 0 || c.right.width <= 0) return
        for (v in c.movable) (v.parent as? ViewGroup)?.removeView(v)
        for (v in c.movable) c.right.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = c.gapPx })
        fun h(v: android.view.View, w: Int): Int {
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            return v.measuredHeight
        }
        val lw = c.left.width; val rw = c.right.width
        val k = SettingsColumns.moveCount(h(c.kids, lw), c.movable.map { h(it, rw) }, c.movable.map { h(it, lw) }, c.gapPx)
        for (v in c.movable.takeLast(k)) {
            c.right.removeView(v)
            c.left.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = c.gapPx })
        }
        log("[설정] 2열 균형: 오른쪽 아래 카드 ${k}장을 왼쪽 열로(글꼴 ${resources.configuration.fontScale})")
    }

    /**
     * v3 반복 4(P1): 설정 화면 틀 = 고정 머리 | 스크롤 본문(outerScroll) | 고정 하단 띠.
     * 예전에는 [무대로 돌아가기]를 addContentView로 본문 위에 띄워 스크롤된 카드 글자를 가렸다.
     * 이제 머리·띠는 root(세로 LinearLayout)의 형제라 본문 높이 = 창 - 머리 - 띠(SettingsFrame)이고, 마지막 항목이 버튼 뒤로 숨지 않는다.
     * 둘 다 settingsOpen일 때만 보인다. 롤백: 이 함수·onCreate의 root.addView 두 줄·openStageSettings/hideSettingsBar의 header/footer 줄 삭제 후 이전 막대 복원.
     */
    private fun buildSettingsChrome(root: LinearLayout) {
        val roles = DuoTokens.roles(Palette.current.night)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.argb(roles.bg))
            setPadding(dp(16), dp(8), dp(16), dp(DuoTokens.CARD_GAP_DP)); visibility = android.view.View.GONE
        }
        head.addView(DuoText.title(this, "보호자 설정").apply { textSize = DuoTokens.DISPLAY_SP; if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        head.addView(DuoText.caption(this, SettingsCopy.HEADER_NOTE))   // 루프 r2: 머리말 한 줄
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.argb(roles.bg)); visibility = android.view.View.GONE
        }
        // 본문과 띠의 경계선(장식): 스크롤 끝이 띠 위에서 끝난다는 것을 보이게 한다
        foot.addView(android.view.View(this).apply { setBackgroundColor(Palette.argb(roles.textMuted)); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SettingsFrame.DIVIDER_DP)))
        val back = DuoButton(this, DuoTokens.Kind.PRIMARY).apply {
            text = SETTINGS_BACK_TEXT   // 2026-11-13 PM 지시 5: "앱으로 돌아가기"(StageControlText.BACK_BUTTON은 계약 파일이라 그대로 두고 여기서만 바꿈)
            setOnClickListener { closeStageSettings() }
            // 점검용: 길게 누르면 개발용 도구(모의 내비·모의 기어·로그)를 켜고 끈다(예전 막대와 같은 동작)
            setOnLongClickListener { devUnlocked = !devUnlocked; hideOldFlowViews(); true }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(SettingsFrame.FOOTER_PAD_DP), dp(16), dp(SettingsFrame.FOOTER_PAD_DP))
        }
        back.minWidth = dp(SettingsFrame.BACK_MIN_WIDTH_DP); back.minimumWidth = dp(SettingsFrame.BACK_MIN_WIDTH_DP)   // 글꼴이 커지면 폭이 늘어난다(고정 폭 아님)
        row.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        foot.addView(row)
        settingsHeader = head; settingsFooter = foot; settingsBackBar = back
        root.addView(head, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---- 2026-11-11 UI/UX v2 U0: 첫 실행 안내. 홈이 보일 수 있을 때(정차 허용·대기)만 덮는다. 잠금이면 syncHome이 숨긴다 ----
    private var firstRunScreen: FirstRunScreen? = null
    private var firstRunAcked = 0
    private var firstRunFinishing = false   // '확인했어요' 뒤 3단계(시작 방법 고르기)를 보이는 중
    private var firstRunReplay = false      // 설정 [처음 안내 다시 보기]
    private var firstRunRestoreStep = -1    // Activity 재생성 복원용

    private fun firstRunWanted(): Boolean =
        firstRunFinishing || firstRunReplay || FirstRunPolicy.shouldShow(firstRunAcked, true)

    private fun endFirstRun(why: String) {
        firstRunFinishing = false; firstRunReplay = false
        firstRunScreen?.visibility = android.view.View.GONE
        log("[첫 안내] 닫음: $why")
    }

    /**
     * 2026-11-14 SCREEN_EXIT_CHECK ⑧: 설정 [처음 안내 다시 보기]의 [닫기](디자인이 FirstRunScreen에 붙인다)·뒤로가기(1/3).
     * 다시 보기일 때만 닫고 설정으로 돌아간다(정차 P·IDLE이 아니면 홈). 확인 기록(FirstRunStore)은 바꾸지 않는다
     */
    private fun onFirstRunClose() {
        if (destroyed) return
        if (!firstRunReplay) { log("[첫 안내] 닫기 무시(다시 보기가 아님, 첫 실행은 앞으로만)"); return }
        if (!tripParkAllowed() || gState != GState.IDLE) { log("[첫 안내] 닫기 무시(유효 P·플랫폼 허용·대기 아님)"); return }
        endFirstRun("다시 보기 닫기")   // 확인 기록(FirstRunStore)은 바꾸지 않는다
        openStageSettings()   // 설정에서 열었으므로 설정으로 돌아간다
    }

    private fun ensureFirstRun(): FirstRunScreen = firstRunScreen ?: FirstRunScreen(this,
        onAck = {
            firstRunAcked = FirstRunPolicy.VERSION
            val ok = FirstRunStore.save(this, FirstRunPolicy.VERSION)
            firstRunFinishing = true
            log("[첫 안내] 확인했어요" + if (ok) "" else " (저장 실패: 다음 시작 때 다시 보임)")
        },
        onRegister = {
            endFirstRun("아이 등록하기")
            if (lockReason() == null && gState == GState.IDLE) {
                openStageSettings()
                if (::childPanel.isInitialized) childPanel.expandForRegister()
            } else syncHome()
        },
        onGuest = { endFirstRun("등록 없이 시작"); syncHome() },
        onSettings = { endFirstRun("설정 먼저 보기"); if (lockReason() == null && gState == GState.IDLE) openStageSettings() else syncHome() },
        onClose = { onFirstRunClose() },   // SCREEN_EXIT_CHECK ⑧: 다시 보기 [닫기](디자인 23b43e4)
    ).also {
        firstRunScreen = it
        it.visibility = android.view.View.GONE
        addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** syncHome에서 호출: 홈이 보일 때만 첫 안내를 덮고, 아니면(잠금·설정·보호자 확인 중) 숨긴다 */
    private fun syncFirstRun(homeShown: Boolean) {
        if (destroyed) return
        val want = homeShown && firstRunWanted()
        if (!want) { firstRunScreen?.visibility = android.view.View.GONE; return }
        val f = ensureFirstRun()
        if (f.visibility != android.view.View.VISIBLE) {
            val start = when {
                firstRunRestoreStep >= 0 -> firstRunRestoreStep
                firstRunFinishing -> 2
                else -> 0
            }
            firstRunRestoreStep = -1
            f.show(start, firstRunReplay)   // 디자인 23b43e4: 다시 보기이면 [닫기]가 보인다
            f.visibility = android.view.View.VISIBLE
            log("[첫 안내] 표시(${start + 1}단계)")
        }
        f.bringToFront()
    }

    // ---- 설정 진입(S-3): 무대를 숨겨 아래의 기존 화면을 드러내고 [무대로 돌아가기] 막대를 띄운다. 롤백: 이 블록·stageActions.onOpenSettings·refreshGuardian의 settingsOpen 줄·onPause/onDestroy의 설정 줄 삭제 ----
    private var settingsOpen = false
    private var settingsBackBar: android.view.View? = null   // v3 반복 4: 하단 띠 안의 [무대로 돌아가기](떠 있는 막대 아님)
    private var settingsHeader: android.view.View? = null
    private var settingsFooter: android.view.View? = null

    private fun openStageSettings() {
        if (destroyed) return
        val s = stageScreen ?: return
        if (lockReason() != null || gState != GState.IDLE) return   // 정차 P·IDLE에서만 연다
        s.visibility = android.view.View.GONE
        homeScreen?.visibility = android.view.View.GONE   // 홈에서 연 설정: 홈도 가린다
        if (::childPanel.isInitialized) childPanel.setExpanded(true)   // 2026-11-11 U2: 설정의 아이 관리 카드는 펼친 채로 보인다
        settingsOpen = true
        settingsHeader?.visibility = android.view.View.VISIBLE
        settingsFooter?.visibility = android.view.View.VISIBLE
        refreshNotifSetting()
        outerScroll?.post { if (settingsOpen && !destroyed) balanceSettingsColumns() }   // v3 반복 6(P1-1): 펼친 상태로 배치된 뒤 실측
    }

    /** 머리·하단 띠 숨김 + 설정 모드 해제. 무대 표시는 호출자가 정한다 */
    private fun hideSettingsBar() {
        settingsOpen = false
        settingsHeader?.visibility = android.view.View.GONE
        settingsFooter?.visibility = android.view.View.GONE
    }

    /** 설정 닫고 무대로 복귀(막대 누름·잠금/IDLE 아님 안전 장치) */
    private fun closeStageSettings() {
        // 2026-11-14 SCREEN_EXIT_CHECK ⑤: 별명 입력칸이 열린 채 닫으면 홈 위에 키보드가 남았다 → 입력칸을 닫고 키보드를 숨긴다(입력 글자는 버림)
        if (::childPanel.isInitialized && childPanel.closeInputIfOpen()) log("[설정] 닫으면서 별명 입력칸·키보드 닫음")
        hideSettingsBar()
        stageScreen?.let { it.visibility = android.view.View.VISIBLE; it.render(buildStageState()) }
        syncHome()   // 홈에서 왔으면 홈이 다시 보인다
    }

    private fun ensureStage(): StageScreen {
        return stageScreen ?: StageScreen(this, onClose = { hideSettingsBar(); stageScreen?.visibility = android.view.View.GONE }, actions = stageActions, onSizeLog = { msg -> log(msg) },
            showCloseButton = !StageFlags.NEW_STAGE,   // 2026-11-09 v7 QA P1: 기본 무대에서는 [미리보기 닫기]를 숨긴다(흐름이 화면 없이 계속되던 문제)
        ).also {
            stageScreen = it
            it.onWidthChanged = { wDp -> checkStageTier(wDp) }   // v3 반복 6(P0): 실제 폭 기준 등급 경계 감시
            ui.post { if (!destroyed) pushStickerWeek("무대 생성") }   // 2026-11-13: 이번 주 스티커판(PM-INTEGRATE 지점은 pushStickerWeek 안)
            styleUnsubs += styleState.observe { st -> it.setStyle(st) }
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun openStagePreview() {
        if (destroyed) return
        hideSettingsBar()
        val s = ensureStage()
        if (::customizer.isInitialized) s.setStyle(customizer.style)
        s.visibility = android.view.View.VISIBLE
        s.render(buildStageState())
    }

    // ---- 홈 화면·꾸미기 화면(사용자 설계 2026-11-07). 무대 위에 덮는 화면이며 보호자 확인 상태는 건드리지 않는다. 롤백: StageFlags.NEW_STAGE 홈 연결 줄 삭제 ----
    // 꾸미기 상태의 단일 보관소: 꾸미기에서 바꾸면 홈·무대·꾸미기 화면이 구독한 콜백으로 바로 받는다(ViewModel 역할)
    private val styleState = CharacterStyleState()
    private val styleUnsubs = ArrayList<() -> Unit>()
    private var homeScreen: HomeScreen? = null
    private var customizeScreen: CustomizeScreen? = null
    private var homeActive = true      // 홈에 있는 상태(시작을 누르면 false, 이동 끝내기·앱 복귀 때 true)
    private var customizeOpen = false

    private fun ensureHome(): HomeScreen {
        return homeScreen ?: HomeScreen(this,
            // ISSUE-06 남은 위험(2026-11-13): 보이지만 resume 안 된 동안(후방 카메라가 닫히는 중 등) 홈 조작이 들어오지 않게 화면 조작 게이트로 막는다
            onStart = { if (homeTapAllowed("시작")) { homeActive = false; syncHome(); startSeatBoardCheck("홈 시작") } },
            onSettings = { if (homeTapAllowed("설정")) openStageSettings() },
            onCustomize = { if (homeTapAllowed("꾸미기")) showCustomize() }).also {
            homeScreen = it
            styleUnsubs += styleState.observe { st -> it.setStyle(st) }
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** 홈 누름 허용 = 화면 조작 게이트(유효 P + 플랫폼 허용 + resume됨, [ScreenTapGate]). 막으면 로그만 남긴다 */
    private fun homeTapAllowed(what: String): Boolean {
        val ok = ScreenTapGate.allowed(resumed, currentGate(), lockReason())
        if (!ok && startupHoldHome()) log("[홈] $what 누름: 차량 신호 확인 중이라 무시(K4, 흐린 버튼)")   // 루프 r2 L5
        if (!ok) log("[홈] $what 누름 무시(resume=$resumed 게이트=${currentGate().name} 잠금=${lockReason() != null})")
        return ok
    }

    /** 앱 시작·복귀: 무대를 아래에 준비하고 홈을 덮는다 */
    private fun showHome() {
        if (destroyed) return
        if (::childPanel.isInitialized) childPanel.closeInputIfOpen()   // ⑤: 설정이 가려질 때 입력칸·키보드도 닫는다
        hideSettingsBar()
        customizeOpen = false
        customizeScreen?.visibility = android.view.View.GONE
        homeActive = true
        openStagePreview()    // 무대는 아래에서 항상 최신 상태로 렌더된다(잠금·이동 끝 장면은 홈보다 앞선다)
        ensureHome().resetUi()
        syncHome()
    }

    /** 홈을 보일지 정한다: 홈에 있고, 설정·꾸미기가 아니며, 정차 대기(PICK/READY)일 때만. 잠금·이동 끝·하차 확인·보호자 확인 중에는 무대가 앞선다 */
    private fun syncHome() {
        if (!StageFlags.NEW_STAGE || destroyed) return
        maybeRestoreSettings()   // 2026-11-11 U5
        val h = homeScreen ?: return
        val scene = sceneOf(buildStageState())
        if (customizeOpen && (lockReason() != null || gState != GState.IDLE)) {   // 꾸미기는 정차 P·대기에서만 열린다
            customizeOpen = false
            customizeScreen?.visibility = android.view.View.GONE
        }
        // 루프 r1 K4: 시작 직후 기어 첫 값을 기다리는 동안(시작 유예)은 '기어 확인 중' 잠금 카드 대신 홈을 보이고 [시작]만 비활성(게이트는 그대로)
        val holdHome = scene == Scene.LOCKED && currentAutoPhase() == null && startupHoldHome()
        // 루프 r2 A2·A3: 옛 흐름 장면(PICK·READY·보호자 확인·옛 하차 한 줄 EXIT_CHECK·TRIP_END)은 어떤 경로로 골라져도 홈으로 덮는다(OldSceneGuard)
        val coverOld = OldSceneGuard.coverWithHome(StageFlags.NEW_STAGE, scene, settingsOpen, customizeOpen, devTools.scenarioRunning())
        // homeActive는 바꾸지 않는다([시작]을 누른 직후 잠깐 PICK이 골라져도 흐름이 AUTO_CHECK가 되면 바로 무대가 보인다)
        val normalHome = homeActive && gState == GState.IDLE && (scene == Scene.PICK || scene == Scene.READY)
        if (coverOld && !normalHome) {
            val key = "${scene.name}/$homeActive/${gState.name}"
            if (key != lastOldSceneLog) { lastOldSceneLog = key; log("[옛장면] ${scene.name} 골라짐(홈 상태=$homeActive, 흐름=${gState.name}, 단계=${currentAutoPhase()}) → 홈으로 덮음") }
        } else if (!coverOld) lastOldSceneLog = ""
        // r7 3절: 빠른 시작 대기 중에는 홈(홈 말풍선 포함)을 그리지 않는다 — 시작 유예의 '잠금 대신 홈'(holdHome)보다 앞선다.
        // 무대는 잠금이면 잠금 화면(S09), 아니면 계약 v7 quickStartWaiting 화면(디자인 F)
        val show = !settingsOpen && !customizeOpen && (normalHome || coverOld || (homeActive && gState == GState.IDLE && holdHome)) &&
            QuickStartRule.homeAllowed(quickStartWaitingNow())
        h.setNotes(homeNotes())   // 미전달 경고·감시 끊김·모의 기어(Unit 5.3, P1-6)
        h.setStartWaiting(!StartupHomeRule.startEnabled(lockReason()))
        val next = if (show) android.view.View.VISIBLE else android.view.View.GONE
        // 2026-11-07 Unit 5.1: 홈 움직임은 정차 허용 게이트에만 연결한다(주행·불명·플랫폼 제한·화면 이탈이면 정지)
        h.setMotionGate(currentGate())
        if (h.visibility != next) {
            if (show) h.resetUi()
            h.visibility = next
        }
        syncFirstRun(show && !holdHome)   // 2026-11-11 U0: 첫 안내는 홈이 보일 때만(잠금·설정·확인 중이면 숨김). K4: 신호 확인 중에는 아직 안 띄움
        maybeHomeGreet(show)
    }

    private var lastOldSceneLog = ""   // r2 A3: 같은 옛 장면 덮음 로그를 되풀이하지 않는다

    /** K4: 시작 유예 안에서 기어 첫 값을 아직 못 받아 생긴 잠금인지(StartupHomeRule). 복귀 재조회는 해당 없음 */
    private fun startupHoldHome(): Boolean =
        StartupHomeRule.holdHome(lockReason(), SystemClock.elapsedRealtime() - startElapsedMs, gearNameEverReceived, gear.subFailed) ||
            (firstGearHolding() && lockReason() == LOCK_TEXT_DISCONNECTED)   // r10 G: 첫 값 안정화 중도 '신호 확인 중'(홈 유지, [시작] 비활성)

    /** 지시 3: 홈 말풍선 "안녕하세요, 톡톡크루 승무원이에요"를 음성으로도(유효 P·홈 진입 때, 이번 앱 실행에서 1회) */
    private fun maybeHomeGreet(homeShown: Boolean) {
        if (!BubbleVoiceRule.homeGreetDue(HomeGreetStore.said, homeShown, resumed, tripParkAllowed() && lockReason() == null)) return
        HomeGreetStore.said = true
        log("[홈인사] 1회(유효 P·홈 진입)")
        enqueueSay(BubbleVoiceRule.HOME_GREET, "홈인사", keep = true, setCaption = false) {
            homeActive && homeScreen?.visibility == android.view.View.VISIBLE && tripParkAllowed() && flow.step == BoardingStep.HOME
        }
    }

    /**
     * 지시 3: 음성이 없던 무대 말풍선(확인 단계 '자리 골라 주세요'·'보호자가 직접 봐 주세요')을 1회 말한다. 판단은 BubbleVoiceRule.
     * 2026-11-14 사용자 지시: 이동 끝 말풍선("이번 이동이 끝난 것 같아요…")과 그 음성은 없앴다
     */
    private val bubbleVoiceSaid = HashSet<String>()
    private fun maybeBubbleVoice(scene: Scene, caption: String) {
        if (quickStartWaitingNow()) return   // r7 3절: 빠른 시작 대기 화면(틀만)의 확인 단계 자막은 말하지 않는다
        val (key, text) = BubbleVoiceRule.forStage(scene, caption) ?: return
        if (!bubbleVoiceSaid.add(BubbleVoiceRule.onceKey(key, flow.generation))) return
        log("[말풍선음성] ${key.name} 1회")
        // 확인 단계 문장: 게이트를 벗어나면 버리고(keep=false), 보낼 때 자막이 아직 같은지 본다(자동 배정·보호자 확인으로 바뀌었으면 버림)
        enqueueSay(text, "말풍선-${key.name}", keep = false, setCaption = false) { flow.step == BoardingStep.AUTO_CHECK && lastCheckCaption == text }
    }
    /** 확인 단계 무대 자막(마지막 평가 기준). 말풍선 음성의 '아직 유효한가' 확인용 */
    private var lastCheckCaption = ""

    private fun showCustomize() {
        // 2026-11-08 지시 2.7: 공식 원본 PNG 적용 동안 일반 사용자 꾸미기 진입 숨김(코드·저장값 보존)
        if (!CustomizeAvailability.ENTRY_VISIBLE) { homeScreen?.resetUi(); return }
        if (destroyed || lockReason() != null || gState != GState.IDLE) { homeScreen?.resetUi(); return }
        val c = customizeScreen ?: CustomizeScreen(this, customizer.root, onDone = { closeCustomize() }).also {
            customizeScreen = it
            styleUnsubs += styleState.observe { st -> it.setStyle(st) }
            customizer.showInline()
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        c.setStyle(customizer.style)
        customizeOpen = true
        c.visibility = android.view.View.VISIBLE
        c.bringToFront()
        syncHome()
        refreshExamples()    // 꾸미기 항목을 켠다(유효한 P·대기 확인)
    }

    private fun closeCustomize() {
        customizeOpen = false
        customizeScreen?.visibility = android.view.View.GONE
        homeScreen?.resetUi()
        syncHome()
    }

    private fun setupStagePreview(devBox: LinearLayout) {
        val b = Button(this).apply { text = StageText.PREVIEW_BUTTON; setOnClickListener { openStagePreview() } }
        styleControl(b, Readability.CONTROL_SP, dp(Readability.MIN_TOUCH_DP))
        devBox.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---- 이동 끝 신호 기록(tripEnd)과 옛 화면의 '이번 이동 끝내기' 카드. 메모리에서만 유지, 저장·전송 없음. 보호자 확인 상태 전이와 분리 ----
    // 2026-11-14 사용자 지시: 내비 신호 뒤 "이번 이동이 끝난 것 같아요…"라고 알리던 안내 줄·[끝내기](신호 기준) 버튼·무대 TRIP_END 말풍선·음성은 없앴다.
    // 이동 끝은 '모두 내렸어요' 자동 마무리와 무대 [이번 이동 끝내기](onFinish·onManualFinish → finishTrip)로만 처리한다.
    // tripCard(내비 연결 상태 줄·수동 끝내기·마침 줄)는 옛 흐름 화면(oldFlowViews)이라 새 무대(StageFlags.NEW_STAGE)에서는 늘 숨김 = 사용 안 함.
    private var tripEnd = TripEndState()
    private var naviLink = NaviLink.UNKNOWN
    private var naviCause: String? = null
    private var naviTripEnd: NaviTripEnd? = null
    private var tripCard: LinearLayout? = null
    private lateinit var tripStatusView: TextView
    private lateinit var tripManualBtn: Button
    private lateinit var tripDoneView: TextView

    // 앱 내부 모의 내비·모의 기어 패널은 빌드 종류별 DevTools로 옮겼다(2026-11-07 Unit 5.1 P0-1).
    // debug = src/debug/DevToolsFactory.kt(모의 구현), release = src/release/DevToolsFactory.kt(아무것도 하지 않음, 모의 클래스 없음)
    private var signalSource = SignalSource.NONE
    private var doneByMock = false          // 모의 도착으로 끝냈음(마침 문구 표기용, 새 이동에서 초기화)
    private val devHost = object : DevToolsHost {
        override fun devLog(msg: String) = log(msg)
        override fun isDestroyed(): Boolean = destroyed
        override fun styleDevButton(b: Button) = styleControl(b, Readability.CONTROL_SP, dp(Readability.MIN_TOUCH_DP))
        override fun devTextColor(): Int = Readability.TEXT_COLOR
        override fun devTitleSp(): Float = Readability.SWITCH_SP
        override fun devAuxSp(): Float = Readability.AUX_SP
        override fun devMinTouchPx(): Int = dp(Readability.MIN_TOUCH_DP)
        override fun devPx(dp: Int): Int = this@MainActivity.dp(dp)
        override fun requestMockGear(on: Boolean, parkName: String) = setMockGear(on, parkName)
        override fun requestMockGearInput(name: String, label: String) = mockGearInput(name, label)
        override fun mockGearOn(): Boolean = mockGearOn
        override fun currentGearName(): String? = gear.name
        override fun onMockArrival() = onMockNaviEvent()
        override fun refreshTripViews() = refreshTripCard()
        override fun devContext(): android.content.Context = applicationContext
        override fun injectBeltEvent(seat: String, value: Boolean?) {
            if (destroyed || !DevMockGuard.allowed) return
            log("[디버그주입] $seat 벨트 -> $value (실제 차량 신호 아님, 화면=${if (resumed) "앞" else "이탈"})")
            debugBeltHeld += seat   // 이후 실제 GET 값이 주입값을 덮지 않게(debug 전용, 앱 재시작 때 풀림)
            onBeltEvent(seat, value)
        }
        override fun clearBeltDebug(seat: String) {
            if (destroyed || !DevMockGuard.allowed) return
            log("[디버그주입] $seat 벨트 받은 값 지움 → 신호 없음 시험(실제 차량 신호 아님)")
            debugBeltHeld += seat
            realBelt.remove(seat); beltReadFailed.remove(seat)
            refreshBoard(); renderStageOnly()
        }
        // ---- 2026-11-13 자동 확인·내비 안내 시연(debug 전용, release에는 이 호출을 하는 코드가 없다) ----
        override fun injectOccEvent(seat: String, value: Boolean?) {
            if (destroyed || !DevMockGuard.allowed) return
            log("[디버그주입] $seat 착석 -> $value (실제 차량 신호 아님, 화면=${if (resumed) "앞" else "이탈"})")
            debugOccHeld += seat   // 이후 실제 점유 GET 값이 주입값을 덮지 않게(debug 전용, 앱 재시작 때 풀림)
            onOccEvent(seat, value)
        }
        override fun clearOccDebug(seat: String) {
            if (destroyed || !DevMockGuard.allowed) return
            debugOccHeld -= seat
            log("[디버그주입] $seat 착석 주입 해제 → 실제 값 다시 읽기")
            readAll(); requestAutoEval(); renderStageOnly()
        }
        override fun onMockRouteStarted(totalSec: Int) {
            if (destroyed || !DevMockGuard.allowed) return
            onNaviTime(NaviTime(NaviTimeKind.ROUTE_STARTED, totalSec), SignalSource.MOCK)
        }
        override fun onMockRemain(sec: Int) {
            if (destroyed || !DevMockGuard.allowed) return
            onNaviTime(NaviTime(NaviTimeKind.REMAIN, sec), SignalSource.MOCK)
        }
        override fun scenarioLog(msg: String) = log(msg)
        override fun scenarioHomeStart() { if (!destroyed) { closeStageSettings(); homeActive = false; syncHome(); renderStageOnly() } }
        override fun scenarioPickFirstChild(): Boolean {
            if (destroyed || !::childPanel.isInitialized) return false
            val list = stageProfiles()
            val first = list.firstOrNull() ?: return false
            for (p in list) if (p.id != first.id && childPanel.isChildSelected(p.id)) childPanel.toggle(p.id)
            if (!childPanel.isChildSelected(first.id)) childPanel.toggle(first.id)
            refreshGuardian()
            return true
        }
        override fun scenarioSelectOnly(seat: String) {
            if (destroyed) return
            for (x in targetSeats.toList()) if (x != seat) toggleTarget(x)
            if (seat !in targetSeats) toggleTarget(seat)
        }
        // 2026-11-13 계약 v2: 시연 시나리오도 좌석 화면 확인으로 시작하고, 고른 자리(targetSeats)에 첫 아이를 배정한다(보호자 누름과 같은 배정)
        override fun scenarioStartCheck() {
            if (destroyed) return
            val want = targetSeats.toList()
            startSeatBoardCheck("시연 시나리오")
            if (flow.step != BoardingStep.AUTO_CHECK) return
            val kid = boardChildren().first()
            for (seat in want) seatCheck = SeatCheckRule.assign(seatCheck, seat, kid.id, byUser = true)
            log("[SCENARIO] 자리 배정 ${want.joinToString()} → 아이 ${kid.id}")
            requestAutoEval(); renderStageOnly()
        }
        override fun scenarioTtsIdle(): Boolean = autoQueue.isEmpty() && tts?.idle != false
        override fun scenarioPhase(): String? = currentAutoPhase()?.name
        override fun setLeftBehindDemoFast(on: Boolean) {
            if (destroyed || !DevMockGuard.allowed) return
            leftBehindDemoFast = on
            log("[두고내림] 시연용 대기 ${if (leftBehindDemoFast) "단축(${LEFT_BEHIND_DEMO_FIRST_MS / 1000}초/${LEFT_BEHIND_DEMO_SECOND_MS / 1000}초)" else "기본(${LeftBehindRule.FIRST_DELAY_MS / 1000}초/${LeftBehindRule.SECOND_DELAY_MS / 1000}초)"}")
            leftBehindEvaluate()
        }
        override fun leftBehindDemoFast(): Boolean = leftBehindDemoFast
        // r5 3절 debug 전용: 교통 상식 간격 단축(QA 클립 ③). release DevTools는 부르지 않고 DevMockGuard로 한 번 더 막는다
        override fun debugSetTipTiming(t: TipTiming?) {
            if (destroyed || !DevMockGuard.allowed) return
            tipTiming = t ?: SafetyTipRule.DEFAULT
            TripGuideStore.alerts = TripGuideStore.alerts.copy(tips = SafetyTipRule.retime(TripGuideStore.alerts.tips, kotlin.random.Random.Default, tipTiming))
            val tt = tipTiming
            log("[교통상식] [디버그주입] 시간 값 ${if (t == null) "기본" else "단축"}: 첫 ${tt.firstDelayMs / 1000}초 · 간격 ${tt.gapMinMs / 1000}~${tt.gapMaxMs / 1000}초 · 조용 ${tt.quietMs / 1000}초")
            val tg = TripGuideStore.state
            if (tg.confirmed && tg.departed && !tg.arrivalSignal && !tg.allLeftSaid) { ui.removeCallbacks(tipRunnable); ui.postDelayed(tipRunnable, 500L) }
        }
        override fun debugTipTimingFast(): Boolean = tipTiming != SafetyTipRule.DEFAULT
        // 2026-11-14(지시 6) debug 전용 스티커 기록 초기화(아이 등록은 남김). release DevTools는 부르지 않고 DevMockGuard로 한 번 더 막는다
        override fun debugResetStickers() {
            if (destroyed || !DevMockGuard.allowed) return
            if (::childPanel.isInitialized) childPanel.clearStickersForTest() else StickerLogStore.clearAll(applicationContext)
            log("[디버그주입] 스티커 기록 지움(아이 등록 ${stageProfiles().size}명은 남김, 시험·녹화 전 초기화)")
            pushStickerWeek("디버그 초기화"); syncHome(); renderStageOnly()
        }
        // r10 H debug 전용: 이동 알림 상한 시간 단축(DEBUG_TRIP_CAP_FAST). release DevTools는 부르지 않고 DevMockGuard로 한 번 더 막는다
        override fun debugSetTripCapTiming(t: TripCapTiming?) {
            if (destroyed || !DevMockGuard.allowed) return
            tripCapTiming = t ?: TripCapRule.DEFAULT
            log("[이동상한] [디버그주입] 시간 값 ${if (t == null) "기본" else "단축"}: 출발 대기 ${tripCapTiming.departWaitMs / 1000}초 · 정차 대기 ${tripCapTiming.exitWaitMs / 1000}초 · 점검 ${tripCapTiming.tickMs / 1000}초")
            scheduleTripCap()
        }
        override fun debugTripCapFast(): Boolean = tripCapTiming != TripCapRule.DEFAULT
    }
    private val devTools: DevTools by lazy { DevToolsFactory.create(devHost) }

    // ---- 모의 기어(가안, 개발용). 메모리만, 기본 꺼짐. applyGear에 P/D 이름만 넣고 판정 규칙은 기존 경로가 그대로 한다 ----
    // 롤백: GearSource.kt 삭제 + 이 블록·realGearAccepted 호출 6곳·DemoLabel 호출 인자·setupMockGearPanel 호출·rereadGear 분리 되돌리기
    // release 빌드는 setter가 항상 false로 둔다(DevMockGuard, 지시 P0-1)
    private var mockGearOn = false
        set(v) { field = DevMockGuard.admit(v) }
    private var mockGearIgnoreLogged = false

    /** 실제 기어 수신 진입부 판정. 모의 중이면 false이며 무시 로그는 모의를 켠 뒤 첫 한 번만 남긴다 */
    private fun realGearAccepted(): Boolean {
        if (DevMockGuard.acceptRealGear(mockGearOn)) return true   // release는 항상 실제 수신을 받는다
        if (!mockGearIgnoreLogged) { mockGearIgnoreLogged = true; log("[모의기어] 실제 기어 수신 무시") }
        return false
    }

    private fun setMockGear(on: Boolean, parkName: String) {
        if (destroyed || on == mockGearOn) return
        if (on && !DevMockGuard.allowed) { log(DevMockGuard.BLOCKED_LOG + "(모의 기어)"); return }
        mockGearOn = on
        mockGearIgnoreLogged = false
        if (on) {
            log("[모의기어] 켜짐")
            applyGear(parkName, true, false)   // 시작 상태 P. 5초 유예·잠금 문구는 기존 경로를 그대로 따른다
        } else {
            log("[모의기어] 꺼짐")
            // 2026-11-07 Unit 5.1 N1: 모의 P가 남지 않게 먼저 '확인 불가'로 잠근 뒤 실제 기어를 다시 읽는다.
            // 실제 GET·이벤트가 오기 전까지는 기어 불명(잠금)이며 이전 모의 P로 조작을 열지 않는다
            RealGearReturn.AFTER_MOCK_OFF.let { applyGear(it.name, it.linkOk, it.subFailed) }
            if (vehicle != null) rereadGear()
        }
        refreshMockGearPanel()
    }

    private fun mockGearInput(name: String, label: String) {
        if (destroyed || !mockGearOn || !DevMockGuard.allowed) return
        log("[모의기어] $label")
        applyGear(name, true, false)
        refreshMockGearPanel()
    }

    private fun refreshMockGearPanel() {
        devTools.refreshGearPanel()
        if (::auxLabel.isInitialized) refreshGuardian()   // 보조 라벨의 모의 기어 표기 갱신
    }

    /** 모의 도착: 출처를 MOCK으로 기록하고 실제 도착 이벤트와 같은 경로(onNaviEvent→TripEndRule.onEvent→TripGuide.onArrived)로 합류한다 */
    private fun onMockNaviEvent() {
        if (destroyed || !DevMockGuard.allowed) return
        if (!tripEnd.finished) signalSource = devTools.onMockSignal(signalSource)
        onNaviEvent(NaviEvent.DESTINATION_ARRIVED, fromMock = true)
    }

    private fun onNaviEvent(e: NaviEvent, fromMock: Boolean = false) {
        if (destroyed) return
        if (TripEndRule.ignoresRouteEnded(tripEnd, e)) {   // PM 결정 18(에뮬 내비는 경로 시작 직후 onRouteEnded를 보낸다)
            log("[이동끝] 경로 시작 뒤 주행 전 경로 종료 신호 → 이동 끝으로 쓰지 않음(결정 18)")
            return
        }
        if (!fromMock && TripEndRule.isEndSignal(e) && !tripEnd.finished) signalSource = SignalSource.REAL
        // r5 5절: 출발 전(이동 경계 뒤 새 확인 등)에 온 도착·경로 종료 신호 = 남겨 둔 시간 값은 끝난 경로의 값 → 비운다
        if (TripEndRule.isEndSignal(e)) clearTimesIfNoRoute("이동 끝 신호")
        tripEnd = TripEndRule.onEvent(tripEnd, e)
        refreshTripCard()
        // 2026-11-13: 도착 신호(실제 onDestinationArrived 또는 debug 모의 도착)는 같은 TripGuide 입력으로 들어간다. 주행 중이면 P까지 미룸
        if (e == NaviEvent.DESTINATION_ARRIVED) {
            if (fromMock) etaMockUsed = true
            applyTrip(TripGuide.onArrived(TripGuideStore.state, tripVoicePark(), tripDriving(), SystemClock.elapsedRealtime()), "도착 신호" + if (fromMock) "(모의)" else "")
            evaluateAppFront("도착 신호" + if (fromMock) "(모의)" else "")   // 루프 r5 1절: 이미 유효 P면 바로 앞으로
        }
    }

    private fun onNaviLink(l: NaviLink, cause: String?) {
        if (destroyed) return
        naviLink = l; naviCause = cause
        refreshTripCard()
        if (l == NaviLink.READY) requestEtaOnNaviReady()   // r10 F: 확인 중에 내비가 늦게 연결되면 남은 시간을 그때 다시 읽는다
    }

    // ---- 2026-11-15 r10 F(개발 3): 확인 완료 음성 직전 예정 시간 다시 읽기(판단 ConfirmEtaWaitRule, QuickStartRule.kt). 메인 스레드 전용 ----
    // 빠른 시작이 내비 연결보다 먼저 확인을 열면 확인 시작 때의 남은 시간 요청(startAutoCheck)이 나가지 못해 완료 음성에 예정 시간이 빠졌다.
    // 완료 순간 값이 없으면 남은 시간을 다시 요청하고 최대 1초 기다린다. 그동안 온 시간 값은 따로 말하지 않고(holdEtaForConfirm) 완료 음성 끝에 붙인다.
    // 완료 음성은 아직 요청 전이라 내비 전환(결정 24 ①: completeVoicePending 유지)·내비 음성 겹침(결정 42)·앞 자동 안내 기다림(ISSUE-08) 순서는 그대로.
    private data class ConfirmEtaWait(val noticeGen: Int, val doneText: String, val startMs: Long, val deadlineMs: Long, val requested: Boolean)
    private var confirmEtaWait: ConfirmEtaWait? = null

    /** 확인 완료(onAutoComplete) 뒤, 예정 시간이 없을 때. 기다릴 조건이면 기다림을 시작한다(완료 음성은 speakChildNoticeAfterAuto가 기다린 뒤 만든다) */
    private fun beginConfirmEtaWait(checkGen: Int, noticeGen: Int, doneText: String) {
        confirmEtaWait = null
        val quick = QuickStartSession.quickGen >= 0 && QuickStartSession.quickGen == checkGen
        if (!ConfirmEtaWaitRule.shouldWait(hasEta = false, etaSaid = TripGuideStore.state.etaSaid, quickStart = quick, naviLink = naviLink)) return
        val ready = naviLink == NaviLink.READY
        if (ready) naviTripEnd?.requestDestinationInfo()
        val now = SystemClock.elapsedRealtime()
        confirmEtaWait = ConfirmEtaWait(noticeGen, doneText, now, now + ConfirmEtaWaitRule.WAIT_MAX_MS, requested = ready)
        log("[예정시간] 확인 완료 순간 시간 값 없음(빠른 시작=${quick}, 내비=${naviLink.name}) → 남은 시간 ${if (ready) "다시 읽기" else "내비 연결 기다림"}, 최대 ${ConfirmEtaWaitRule.WAIT_MAX_MS}ms")
    }

    /** true = 아직 기다린다(다시 부름 예약). false = 기다림 없음·끝남 → 완료 음성 진행(값이 왔으면 끝에 예정 시간을 붙여 둠) */
    private fun holdForConfirmEta(noticeGen: Int, waitedMs: Long): Boolean {
        val w = confirmEtaWait ?: return false
        if (w.noticeGen != noticeGen) { confirmEtaWait = null; return false }
        var cur = w
        if (!cur.requested && naviLink == NaviLink.READY) {
            naviTripEnd?.requestDestinationInfo()
            cur = cur.copy(requested = true); confirmEtaWait = cur
            log("[예정시간] 기다리는 중 내비 연결됨 → 남은 시간 다시 읽기")
        }
        val now = SystemClock.elapsedRealtime()
        if (naviLink != NaviLink.FAILED && ConfirmEtaWaitRule.keepWaiting(ConfirmEtaWaitRule.hasValue(TripGuideStore.state), cur.deadlineMs, now)) {
            ui.postDelayed({ if (!destroyed) speakChildNoticeAfterAuto(noticeGen, waitedMs) }, ConfirmEtaWaitRule.STEP_MS)
            return true
        }
        confirmEtaWait = null
        val step = TripGuide.onConfirmEta(TripGuideStore.state)
        TripGuideStore.state = step.state
        val say = step.says.firstOrNull()
        when {
            say != null && autoCompletePrefix.isNotEmpty() -> {
                autoCompletePrefix = TripGuide.confirmSpoken(cur.doneText, say)
                lastSpoken = autoCompletePrefix
                log("[이동안내] ETA_TOTAL (확인 완료 음성 직전 다시 읽음, ${now - cur.startMs}ms 기다림)")
            }
            say != null -> enqueueSay(say.text, "이동안내-${say.kind.name}", keep = true, trip = say.kind)   // 완료 음성 문장이 이미 없으면 예전처럼 따로
            else -> log("[예정시간] ${now - cur.startMs}ms 기다려도 시간 값 없음 → 예정 시간 없이 완료 음성")
        }
        return false
    }

    /** 기다리는 동안 온 시간 값: 예정 시간 안내(ETA_TOTAL)를 따로 내지 않고 '아직 말하지 않음'으로 둔다(값은 저장). 기다림이 아니면 그대로 */
    private fun holdEtaForConfirm(step: TripGuideStep): TripGuideStep {
        if (confirmEtaWait == null || step.says.none { it.kind == TripSayKind.ETA_TOTAL }) return step
        log("[예정시간] 완료 음성 기다리는 중 시간 값 도착 → 완료 음성 끝에 붙임")
        return TripGuideStep(step.state.copy(etaSaid = false), step.says.filter { it.kind != TripSayKind.ETA_TOTAL })
    }

    /** 내비가 연결된 순간 확인 중이고 시간 값이 없으면 남은 시간을 한 번 읽는다(startAutoCheck 때 내비가 아직 없었을 수 있음) */
    private fun requestEtaOnNaviReady() {
        if (!ConfirmEtaWaitRule.ENABLED || flow.step != BoardingStep.AUTO_CHECK || ConfirmEtaWaitRule.hasValue(TripGuideStore.state)) return
        naviTripEnd?.requestDestinationInfo()
        log("[예정시간] 확인 중 내비 연결됨 → 남은 시간 다시 읽기")
    }

    /**
     * 끝내기·수동 끝내기 공통: 이동 끝 기록과 보호자 확인 세션의 메모리 상태(스티커 받음, 무장 좌석, 주행 안내 기록)를 초기화한다.
     * 2026-11-08 Unit 6 최종(ch 결정 1·2): 유효 P + 정차 허용 게이트에서만, 이동당 정확히 1회(이미 끝낸 이동이면 무시).
     * 반환 true = 이번 호출에서 끝내기를 처리함. 이동 경계(tripKey)는 저장하며, 저장 실패는 로그로 남기고 성공으로 적지 않는다.
     */
    private fun finishTrip(source: String): Boolean {
        if (lockReason() != null || !exitGateAllowed()) { log("[이동끝] $source 거절(유효 P·정차 허용 아님)"); refreshTripCard(); refreshGuardian(); return false }
        if (tripEnd.finished) { log("[이동끝] $source 무시(이미 끝낸 이동, 중복 처리 안 함)"); refreshTripCard(); return false }
        if (!tripEnd.signalReceived && !TripSessionStore.state.moved) { log("[이동끝] $source 거절(이번 이동에서 주행을 본 근거 없음)"); refreshTripCard(); return false }
        closeManualFinishConfirm(null)
        // 지난 이동의 '예'(YES_REPORTED 등)가 새 이동의 스티커 판정에 쓰이지 않게, 이동 번호를 넘기기 전에 흐름을 홈으로 정리한다(E2E 발견 결함)
        if (gState != GState.IDLE) { guardLog("이동 끝내기 → 보호자 확인 정리"); dispatchFlow(BoardingEvent.ToHome, "이동끝") }
        val bySignal = tripEnd.signalReceived
        val byMock = bySignal && signalSource == SignalSource.MOCK
        if (!TripGuideStore.state.allLeftSaid) TripFinishStore.last = TripFinishRule.kindOf(TripGuideStore.state, ArrivalAskStore.state)   // r6 5절: 끝난 방식
        advanceTripBoundary(source, "이동 끝내기")
        doneByMock = byMock
        log(if (byMock) "[이동끝] 끝내기(앱 내부 모의 도착 기준)" else if (bySignal) "[이동끝] 끝내기(내비 신호 기준)" else "[이동끝] 내비 신호 없이 보호자가 끝냄(도착 판정 아님)")
        refreshGuardian()
        return true
    }

    /**
     * r7 2절(나), 임시 결정 47: 뒷문이 열린 적 없어(예: 앞문으로 내림) 자동 마무리가 안 된 하차 안내 화면에서 보호자가 끝낸다.
     * 하차 마무리(ALL_LEFT)와 같은 길(applyTrip ALL_LEFT: 끝난 방식·끝난 이동 저장·스티커 처리·작별 인사)로 가고, 음성은 "뒷좌석을 한 번 더 확인해 주세요."
     * (신호 기준 문장 "내린 것 같아요"를 쓰지 않음). 계약 v7 exitByGuardian = true. 자동 마무리가 먼저 났거나 조건이 아니면 false(호출자가 기존 동작).
     * 원래 [finishTrip]은 멘트 없이 이동 경계만 넘기고(advanceTripBoundary: 두고 내림 등 알림 초기화) 무대는 홈으로 갔다.
     */
    private fun guardianFinish(source: String): Boolean {
        val tg = TripGuideStore.state
        val alighting = ArrivalAskRule.alightingByAnswer(ArrivalAskStore.state, tg)
        // r8 1절(계약 v8): 누른 순간 배정 뒷자리에 착석 신호가 남았으면 멘트를 두고 내림 문구로(작별 인사는 그대로)
        val seatOccupied = GuardianFinishRule.seatStillOccupied(tg, tg.occupiedAtConfirm.associateWith { occSignal(it).first })
        val step = GuardianFinishRule.finish(tg, alighting, tripParkAllowed(), seatOccupied) ?: return false
        closeManualFinishConfirm("보호자 버튼 끝내기")
        // r7 2(나) 후속(PM): 지금 뒷줄에 앉은 신호가 있는 자리는 기준선(그대로 앉아 있으면 '다시 앉은 신호'가 아님)
        val seatedNow = reseatOcc().filter { (k, v) -> ReseatRule.isRear(k) && v == SeatOcc.OCCUPIED }.keys
        ExitDoorStore.state = ExitDoorRule.onGuardianFinish(ExitDoorStore.state, seatedNow, seatOccupied)
        val left = tg.occupiedAtConfirm.filter { ReseatRule.isRear(it) && occSignal(it).first == SeatOcc.OCCUPIED }   // r10d B: 조수석은 두고 내림 문구 근거 아님
        log("[이동끝] $source → 보호자 버튼 끝내기(결정 47, ${if (alighting) "중간 하차" else "도착"})" +
            if (left.isEmpty()) "" else " | 배정 자리 착석 신호 남음 ${left.joinToString()} → 두고 내림 문구로 말함(r8 1절)")
        applyTrip(step, "보호자 버튼")
        renderStageOnly()
        return true
    }

    /**
     * 이동 경계 넘기기(끝내기·결정 29 공통): 조건을 채운 이동이면 지급(이동당 1회) → 이동 안내·두고 내림·벨트 풀림·말풍선·FGS·내비 전환 정리 →
     * 이동 끝 기록 끝냄 → 이동 번호 +1 저장 → 하차 한 줄·주행 감시 세션 새로. [why]는 FGS·정리 로그 사유
     */
    private fun advanceTripBoundary(source: String, why: String) {
        grantStickerIfEarned(why)   // 2026-11-13: 조건을 채운 이동이면 이동 번호를 넘기기 전에 지급(이동당 1회)
        // r5 5절: 내비 경로가 살아 있으면(도착·경로 종료 신호 없음) 마지막 유효 시간 값만 다음 이동으로 넘긴다(새 확인 완료 음성 끝 예정 시간)
        // PM 통합(r5, 결정 41(사용자 확정(11-15))): 이번 정차의 경로 상태 조회 답이 있으면 routeAlive로 넘긴다(경로 없음이면 옛 값을 버림). 답이 없으면 null(새 확인 시작 때 다시 읽기로 보정)
        val rq = RouteLiveStore.query
        val routeAlive = if (RouteLiveRule.ENABLED && rq is RouteLiveRule.Query.Answered) rq.routing else null
        val carry = TripGuide.nextTrip(TripGuideStore.state, endSignal = tripEnd.signalReceived, routeAlive = routeAlive)
        TripGuideStore.state = carry; resetTripAlerts(why); etaMockUsed = false; autoQueue.clear()
        log("[이동안내] 이동 경계: 내비 시간 값 " + if (carry.totalSec != null || carry.remainSec != null) "넘김(경로 진행 중)" else "비움")
        resetBubbleFeed(why)
        disarmHandoff(why); syncKeepAlive(why)
        tripEnd = TripEndRule.finish(tripEnd)
        signalSource = SignalSource.NONE
        // 2026-11-08 ch 결정 1·3: 끝내기 = 이동 경계. 다음 탑승의 스티커·하차 한 줄은 새 이동 기준(하차 한 줄은 다음 주행 뒤에만)
        TripSessionStore.state = TripSessionRule.finish(TripSessionStore.state); stickerKids = 0; stickerAttemptGen = -1; stickerRecordFailed = false
        val saved = StickerLogStore.saveTripBoundary(applicationContext, TripSessionStore.state)   // 지난 이동의 하차 한 줄 소진 기록도 같이 지움(M14)
        if (saved) TripSessionStore.readFailed = false
        TripSessionStore.allLeftTrip = -1   // 결정 30: 새 이동 = 하차 마무리 기록 없음(저장값은 saveTripBoundary가 같은 commit에서 지움)
        exitCheck = ExitCheckRule.onNewTrip(exitCheck)
        log("[이동끝] $source → 다음 이동 번호 ${TripSessionStore.state.tripKey} " +
            if (saved) "(이동 경계 저장됨)" else "(이동 경계 저장 실패: 앱을 다시 켜면 이전 이동으로 복구되며 새 스티커가 자동 지급되지 않음)")
        endBeltReleaseSession(why)
    }

    /** 보호자용 [이번 이동 끝내기] 노출: 정차 허용 게이트 + 이번 이동 주행 관측 + 미종료 + 안내·질문 진행 중 아님 */
    private fun manualFinishAllowedNow(): Boolean =
        TripSessionRule.manualFinishAllowed(TripSessionStore.state, lockReason() == null && exitGateAllowed(), tripEnd.finished,
            gState == GState.ANNOUNCING || gState == GState.ASKING)

    private var finishConfirm: android.app.AlertDialog? = null

    /** 확인 대화상자(76dp 버튼). '끝내기'를 누른 순간 조건을 다시 확인한 뒤에만 finishTrip을 부른다 */
    private fun openManualFinishConfirm() {
        if (destroyed || finishConfirm != null) return
        if (!manualFinishAllowedNow()) { log("[이동끝] 수동 끝내기 거절(조건 아님)"); renderStageOnly(); return }
        // 루프 r1 K2: 앱 모양 대화상자(AppDialogs)
        val d = AppDialogs.confirm(this, StageControlText.MANUAL_FINISH_CONFIRM, StageControlText.MANUAL_FINISH_YES, StageControlText.MANUAL_FINISH_NO,
            onYes = {
                finishConfirm = null
                if (!manualFinishAllowedNow()) { log("[이동끝] 확인 순간 조건 해제 → 끝내지 않음"); renderStageOnly() }
                else if (finishTrip("보호자 수동 끝내기") && StageFlags.NEW_STAGE) { homeActive = true; syncHome() }
            },
            onNo = { finishConfirm = null; log("[이동끝] 수동 끝내기 취소(계속하기)") },
            onCancel = { finishConfirm = null })
        finishConfirm = d
        log("[이동끝] 수동 끝내기 확인 열기")
        d.show()
    }

    private fun closeManualFinishConfirm(why: String?) {
        val d = finishConfirm ?: return
        finishConfirm = null
        try { d.dismiss() } catch (e: Exception) { }
        if (why != null) log("[이동끝] 확인 창 닫음: $why")
    }

    /** 프로세스 시작 때 1회: 저장된 이동 세션 복원(재시작은 새 이동이 아님). 읽기 실패면 자동 새 보상 차단으로 시작 */
    private fun restoreTripSession() {
        if (!TripSessionStore.restored) {
            TripSessionStore.restored = true
            val (st, failed) = TripSessionRule.restore(StickerLogStore.loadTripOrNull(applicationContext))
            TripSessionStore.state = st; TripSessionStore.readFailed = failed
            // M06: 같은 이동에서 이미 하차 한 줄을 들었으면 재시작 뒤에도 다시 내지 않는다(읽기 실패면 -1 = 기록 없음)
            TripSessionStore.exitConsumedTrip = if (failed) -1 else StickerLogStore.loadExitConsumedTrip(applicationContext)
            // 2026-11-13: 스티커 조건 충족(자동 확인 완료·대체 버튼)도 재시작 뒤 같은 이동에서 유지(읽기 실패면 기록 없음 = 지급 안 함)
            TripSessionStore.earnedTrip = if (failed) -1 else StickerLogStore.loadEarnedTrip(applicationContext)
            // PM 결정 30: 하차 마무리 도달 기록도 복원(읽기 실패면 기록 없음 = 같은 이동을 이어 감)
            TripSessionStore.allLeftTrip = if (failed) -1 else StickerLogStore.loadAllLeftTrip(applicationContext)
            val allLeftDone = TripSessionRule.startsNewTripOnStart(TripSessionStore.allLeftTrip, st.tripKey)
            ui.post { if (!destroyed && allLeftDone) log("[이동] 복원: 이동 ${st.tripKey}은 하차 마무리까지 간 이동 → 다음 [시작]은 새 이동(결정 30)") }
            val exitDone = TripSessionStore.exitConsumedTrip >= 0 && TripSessionStore.exitConsumedTrip == st.tripKey
            if (exitDone && !exitCheck.consumed) exitCheck = exitCheck.copy(consumed = true)
            ui.post { if (!destroyed && exitDone) log("[하차확인] 복원: 이동 ${st.tripKey}에서 이미 전달됨 → 이번 이동에는 다시 내지 않음") }
            ui.post { if (!destroyed) log(if (failed) "[이동] 저장된 이동 정보를 읽지 못함 → 이번 이동 스티커 자동 지급 안 함(보호자가 끝내기 하면 새 이동)"
                else "[이동] 복원: 이동 ${st.tripKey}, 스티커 기록=${TripSessionRule.stickerGranted(st)}, 주행 관측=${st.moved}") }
        }
        // 끝낸 뒤 아직 주행하지 않은 이동(번호 > 0, 주행 관측 없음)이면 '끝낸 상태'로 이어 간다(재시작·재생성 직후 같은 P에서 다시 끝내기 없음)
        val st = TripSessionStore.state
        if (!TripSessionStore.readFailed && st.tripKey > 0 && !st.moved) tripEnd = TripEndState(finished = true)
    }

    // ---- 2026-11-15 r6 6절(가) 다시 탔을 때 빠른 시작. 판단 QuickStartRule(순수), 저장 QuickStartStore, 프로세스 상태 QuickStartSession ----
    // 시작 구역: onCreate에서 판단(decideQuickStart) → 게이트가 유효 P + 허용이 되면(onGateInputsChanged 끝) maybeQuickStart가 좌석 확인을 연다.
    // 기록 구역: 이동 서비스 판단(syncKeepAlive)과 같은 시점에 syncQuickStartMark가 이동 중 표시·중간 하차·지우기를 한다.

    /**
     * onCreate에서 새로 열릴 때만([fresh] = 저장 상태 없음. 야간 전환 등 재생성 때는 안 함 — 확인 이어 가기는 CheckResumeRule).
     * 프로세스가 새로 시작됐거나 [앱 나가기] 뒤 다시 열린 경우다. 하기로 하면 홈 인사를 막고 유효 P를 기다린다. 안 하면 지난 기록을 지운다
     */
    private fun decideQuickStart(fresh: Boolean) {
        if (!fresh) {
            // r7 3절: 대기 중 화면 재생성(야간 전환 등)이면 상한 점검만 새 화면에 다시 건다
            if (QuickStartSession.pending != null && QuickStartSession.waitStartMs >= 0) {
                val left = (QuickStartSession.waitStartMs + QuickStartRule.WAIT_MAX_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                ui.removeCallbacks(quickWaitTimeout); ui.postDelayed(quickWaitTimeout, left + 50L)
            }
            return
        }
        // r10 M(우선순위 고정): 이동 상태를 되살렸으면 빠른 시작(15초 대기 → 기록 지우고 홈)을 쓰지 않는다. 빠른 시작 기록은 지우지 않고 이동 서비스 판단이 이어 쓴다
        // PM-INTEGRATE(r10 M·F): 개발 3이 이 함수(빠른 시작 대기)를 고치면 이 줄은 판단 맨 앞(fresh 판단 뒤)에 그대로 둔다
        if (TripRestoreSession.restored) { ui.post { if (!destroyed) log("[빠른시작] 시작 판단 건너뜀: 이동 상태를 되살림(r10 M)") }; return }
        QuickStartSession.markedActive = false   // 새 앱 세션: 지난 이동 중 표시는 아래 판단에만 쓴다(첫 '이동 아님'으로 지우지 않게)
        QuickStartSession.quickGen = -1
        val rec = QuickStartStore.load(applicationContext)
        val now = System.currentTimeMillis()
        val st = TripSessionStore.state
        val d = QuickStartRule.decide(rec, now, st.tripKey, TripSessionStore.allLeftTrip, TripSessionStore.readFailed)
        if (d == QuickStartDecision.QUICK_START && rec != null) {
            QuickStartSession.pending = rec
            HomeGreetStore.said = true   // 홈 인사("안녕하세요, 톡톡크루 승무원이에요")를 건너뛴다(빠른 시작을 버리면 되돌린다)
            // r7 3절: 대기하는 동안 홈·홈 말풍선을 그리지 않는다(syncHome). 임시 결정 49: 15초 안에 유효 P·허용이 안 오면 홈
            QuickStartSession.waitStartMs = SystemClock.elapsedRealtime()
            ui.removeCallbacks(quickWaitTimeout); ui.postDelayed(quickWaitTimeout, QuickStartRule.WAIT_MAX_MS + 50L)
        } else {
            QuickStartSession.waitStartMs = -1L
            if (rec != null) QuickStartStore.clear(applicationContext)   // 쓰지 않는 지난 기록은 남기지 않는다
        }
        val line = if (rec == null) "[빠른시작] 시작 판단: 기록 없음 → 홈"
            else "[빠른시작] 시작 판단: 기록=${rec.kind.name} 이동 ${rec.tripKey}(지금 ${st.tripKey}) ${(now - rec.atWallMs) / 60_000L}분 전 → " +
                if (d == QuickStartDecision.QUICK_START) "빠른 시작(유효 P 대기, 홈 인사 생략)" else "홈(60분 지남·시계 역행·정상 종료·이동 바뀜·읽기 실패 중 하나)"
        ui.post { if (!destroyed) log(line) }
    }

    /**
     * 빠른 시작 대기 중이면 게이트 변화마다 본다(QuickStartRule.onGate). 유효 P + 플랫폼 허용이 아니면 기다린다(잠금 화면 규칙이 우선).
     * 시작: 기록을 지우고 → 새 이동(끝내지 못한 이동은 경계를 넘김, 중간 하차로 하차 마무리까지 간 이동은 startSeatBoardCheck가 결정 29·30대로 넘김)
     * → 홈을 건너뛰고 좌석 확인 → 탑승 환영 대신 임시 결정 43 한 줄.
     */
    private fun maybeQuickStart() {
        val rec = QuickStartSession.pending ?: return
        if (destroyed) return
        val atHome = flow.step == BoardingStep.HOME && gState == GState.IDLE && currentAutoPhase() == null
        when (QuickStartRule.onWait(rec, System.currentTimeMillis(), QuickStartSession.waitStartMs, SystemClock.elapsedRealtime(), atHome, tripParkAllowed())) {
            QuickGateDecision.WAIT -> return
            QuickGateDecision.TIMEOUT -> {
                // 임시 결정 49: 15초 안에 유효 P·플랫폼 허용이 안 옴 → 홈(잠금이면 잠금 화면이 앞선다). 홈 인사는 생략한 채로 둔다(개발 B 판단)
                QuickStartSession.pending = null; QuickStartSession.waitStartMs = -1L; ui.removeCallbacks(quickWaitTimeout)
                QuickStartStore.clear(applicationContext)
                if (QuickStartRule.restoreGreetOnTimeout()) HomeGreetStore.said = false
                log("[빠른시작] 대기 ${QuickStartRule.WAIT_MAX_MS / 1000}초 넘음(유효 P·허용 없음, 잠금=${lockReason() ?: "없음"}) → 홈(홈 인사 ${if (HomeGreetStore.said) "생략 유지" else "1회"}, 임시 결정 49)")
                syncHome(); renderStageOnly()
            }
            QuickGateDecision.DROP -> {
                QuickStartSession.waitStartMs = -1L; ui.removeCallbacks(quickWaitTimeout)
                QuickStartSession.pending = null
                QuickStartStore.clear(applicationContext)
                HomeGreetStore.said = false   // 평소처럼 홈 인사 1회
                log("[빠른시작] 버림(60분 지남·시계 역행·다른 흐름이 먼저 시작됨) → 평소 홈")
                syncHome()
            }
            QuickGateDecision.START -> {
                QuickStartSession.waitStartMs = -1L; ui.removeCallbacks(quickWaitTimeout)   // r7 3절: quickStartWaiting = false
                QuickStartSession.pending = null
                QuickStartStore.clear(applicationContext)   // 한 번 쓰고 지운다(새 이동이 확인 완료되면 다시 이동 중 표시)
                val st = TripSessionStore.state
                if (!TripSessionRule.startsNewTripOnStart(TripSessionStore.allLeftTrip, st.tripKey) && st.moved) {
                    // 끝내지 못한 채 꺼진 이동: 끝내기와 같은 경계(조건을 채운 이동이면 이동당 1회 지급 규칙대로 지급)
                    log("[빠른시작] 끝내지 못한 이동 ${st.tripKey} → 경계를 넘겨 새 이동으로")
                    advanceTripBoundary("빠른 시작", "빠른 시작 새 이동"); doneByMock = false
                }
                if (StageFlags.NEW_STAGE) { homeActive = false; syncHome() }
                startSeatBoardCheck("빠른 시작")
                if (flow.step != BoardingStep.AUTO_CHECK) {
                    HomeGreetStore.said = false
                    log("[빠른시작] 좌석 확인을 열지 못함 → 평소 홈")
                    syncHome(); return
                }
                QuickStartSession.quickGen = flow.generation   // 계약 v6.1 quickStart
                welcomeGen = flow.generation   // 이번 확인의 탑승 환영 인사(maybeWelcome)는 하지 않는다
                log("[빠른시작] 홈·홈 인사 건너뛰고 좌석 확인(새 이동 ${TripSessionStore.state.tripKey}, 세대 ${flow.generation}, 기록=${rec.kind.name})")
                enqueueSay(QuickStartRule.RESTART_LINE, "빠른시작", keep = false)   // 임시 결정 43, 화면 lastSpoken에도 같은 문장
                renderStageOnly()
            }
        }
    }

    /**
     * 중간 하차로 끝났는가(r6 5절 개발 A의 끝난 방식).
     * PM-INTEGRATE(r6 6절): 개발 A(98d7e95) 통합 뒤 `TripFinishStore.endedByAlight()`로 바꾼다. 그 전에는 false
     * (중간 하차도 정상 마무리처럼 기록을 지움 → 빠른 시작은 '끝내지 못한 종료'만 동작).
     * A는 하차 마무리(applyTrip ALL_LEFT) 때 TripFinishStore.last를 정한 뒤 syncKeepAlive("하차 마무리")를 부르므로 순서가 맞다.
     */
    private fun quickEndedByAlight(): Boolean = TripFinishStore.endedByAlight()   // PM 통합(r6): 개발 A 끝난 방식 연결

    private val quickHeartbeat = Runnable { if (!destroyed) quickHeartbeatTick() }
    /** r7 3절: 빠른 시작 대기 상한(임시 결정 49) 점검 */
    private val quickWaitTimeout = Runnable { if (!destroyed) maybeQuickStart() }

    /** 계약 v7 `quickStartWaiting`: 빠른 시작으로 판단했고 유효 P·허용을 기다리는 중(상한 전). 이 동안 홈·홈 인사를 그리지 않는다 */
    private fun quickStartWaitingNow(): Boolean =
        QuickStartRule.waiting(QuickStartSession.pending != null, QuickStartSession.waitStartMs, SystemClock.elapsedRealtime())

    /** 이동 서비스 판단(syncKeepAlive)마다: 이동 중이 되면 표시, 끝나면 중간 하차 기록 또는 지움(QuickStartRule.markAction) */
    private fun syncQuickStartMark(why: String) {
        val tg = TripGuideStore.state
        val active = TripKeepAliveRule.tripActive(tg)
        val key = TripSessionStore.state.tripKey
        when (QuickStartRule.markAction(active, QuickStartSession.markedActive, tg.allLeftSaid, quickEndedByAlight())) {
            QuickMarkAction.WRITE_ACTIVE -> {
                QuickStartSession.markedActive = true
                val ok = QuickStartStore.save(applicationContext, QuickStartRecord(QuickEndKind.ACTIVE, key, System.currentTimeMillis()))
                log("[빠른시작] 이동 $key 이동 중 표시($why)" + if (ok) " 저장됨" else " (저장 실패: 갑자기 꺼지면 다음에 홈부터)")
            }
            QuickMarkAction.WRITE_ALIGHTED -> {
                QuickStartSession.markedActive = false
                val ok = QuickStartStore.save(applicationContext, QuickStartRecord(QuickEndKind.ALIGHTED, key, System.currentTimeMillis()))
                log("[빠른시작] 이동 $key 중간 하차로 끝남 기록($why)" + if (ok) " 저장됨" else " (저장 실패)")
            }
            QuickMarkAction.CLEAR -> {
                QuickStartSession.markedActive = false
                val ok = QuickStartStore.clear(applicationContext)
                log("[빠른시작] 이동 $key 정상 종료 → 기록 지움($why)" + if (ok) "" else " (지우기 실패: 다음 시작 판단이 이동 번호·하차 마무리 기록으로 거름)")
            }
            QuickMarkAction.NONE -> {}
        }
        if (!QuickStartSession.markedActive) ui.removeCallbacks(quickHeartbeat)
        else if (!ui.hasCallbacks(quickHeartbeat)) ui.postDelayed(quickHeartbeat, QuickStartRule.HEARTBEAT_MS)
    }

    /** 이동 중 [QuickStartRule.HEARTBEAT_MS]마다 시각만 새로 쓴다(갑자기 꺼졌을 때 '끝난 시각' = 마지막으로 살아 있던 시각) */
    private fun quickHeartbeatTick() {
        if (QuickStartSession.markedActive && TripKeepAliveRule.tripActive(TripGuideStore.state)) {
            QuickStartStore.save(applicationContext, QuickStartRecord(QuickEndKind.ACTIVE, TripSessionStore.state.tripKey, System.currentTimeMillis()))
            syncTripState("시각 갱신")   // r10 M: 내용이 같아도 TripRestoreRule.TOUCH_MS마다 마지막 갱신 시각(apply)
            ui.postDelayed(quickHeartbeat, QuickStartRule.HEARTBEAT_MS)
        } else syncQuickStartMark("시각 갱신 중 이동 끝 확인")
    }

    // ---- 2026-11-15 r10 M(개발 7): 이동 상태 저장·복원. 판단 TripRestoreRule(순수), 저장 TripStateStore, 프로세스 상태 TripRestoreSession ----
    // 시작 구역: onCreate에서 restoreTripState(빠른 시작 판단 전) → 되살리면 decideQuickStart를 건너뛴다.
    // 이후 흐름은 기존 그대로: 첫 기어가 D면 잠금 화면 + 음성(벨트 풀림 감시는 maybeRearmBeltSession이 tg.selected로 다시 켬, 도착은 P까지 미룸),
    // 유효 P면 tripGuideGear(TripGuide.onGear)가 정차를 잡고 evaluateAppFront가 정차 질문·도착 안내를 이어 간다(TripRestoreRule.route).
    // PM-INTEGRATE(r10 M·G): 개발 3의 기어 첫 값 0.5초 안정화(gearCheckPending)가 들어오면 noteRestoredGear의 '첫 유효 기어'도 안정화 뒤 값으로 본다.
    // 지금은 lockReason()이 null(유효 P + 허용)이거나 tripDriving()일 때만 경로를 정한다(불명·단절이면 기다림).

    /** 프로세스 시작 때 1회(Activity 재생성은 TripRestoreSession.checked로 건너뜀). restoreTripSession 뒤, decideQuickStart 앞 */
    private fun restoreTripState() {
        if (TripRestoreSession.checked) return
        TripRestoreSession.checked = true
        if (!TripRestoreRule.ENABLED) return   // 스위치 끔 = r9b와 같음(저장소를 읽지도 쓰지도 않음)
        val rec = TripStateStore.load(applicationContext)
        val now = System.currentTimeMillis()
        val st = TripSessionStore.state
        val d = TripRestoreRule.decide(rec, now, st.tripKey, TripSessionStore.allLeftTrip, TripSessionStore.readFailed,
            memoryActive = TripKeepAliveRule.tripActive(TripGuideStore.state))
        val age = rec?.let { "${(now - it.updatedAtWallMs) / 60_000L}분 전" } ?: "-"
        when (d) {
            TripRestoreDecision.RESTORE -> {
                val r = TripRestoreRule.restore(rec!!, SystemClock.elapsedRealtime())
                TripGuideStore.state = r.guide
                ArrivalAskStore.state = r.ask
                TripGuideStore.alerts = TripGuideStore.alerts.copy(leftBehind = r.leftBehind)
                TripRestoreSession.restored = true; TripRestoreSession.awaitingGear = r; TripRestoreSession.lastSaved = rec
                TripRestoreSession.holdSave = true   // r10 수정 2: 첫 유효 기어(복원 판단 끝)까지 저장 막음
                HomeGreetStore.said = true   // 이어 가는 이동: 홈 인사를 하지 않는다
                // 확인 완료 때(onAutoComplete)와 같이 이번 이동의 아이를 고른다(스티커 지급 대상). 아이 번호만, 별명은 프로필에서
                val kidIds = r.guide.assigned.values.filter { it >= 0 }.toSet()
                confirmWithoutProfile = kidIds.isEmpty()
                TripKidsStore.saved = TripKidsRule.of(rec.tripKey, r.guide.assigned)   // r10 수정(J): 화면 재생성에도 스티커 대상 유지
                ui.post { if (!destroyed && ::childPanel.isInitialized) childPanel.selectForTrip(kidIds) }
                log("[이동복원] 이동 ${rec.tripKey} 되살림(마지막 갱신 $age, 단계=${rec.phase.name}, 배정 ${r.guide.assigned.size}석, " +
                    "스티커 지급=${rec.stickerGranted}, 두고 내림 ${r.leftBehind.said}회, 시간 값은 내비에서 다시 받음) → 첫 유효 기어를 기다림(D=잠금+음성, P=정차 처리), 그때까지 저장 안 함")   // r10 수정 2: 로그 순서가 저장보다 앞서게 바로 남김
            }
            TripRestoreDecision.IN_MEMORY -> TripRestoreSession.lastSaved = rec
            else -> {
                TripRestoreSession.lastSaved = null
                val raw = try { getSharedPreferences(TripStateStore.FILE_NAME, MODE_PRIVATE).contains(TripStateStore.KEY) } catch (e: Exception) { false }
                if (rec != null || raw) {
                    TripStateStore.clear(applicationContext)   // 쓰지 않는 기록(깨진 값 포함)은 남기지 않는다
                    log("[이동복원] 기록 버림(${d.name}, 마지막 갱신 $age) → 빠른 시작 판단으로")
                }
            }
        }
    }

    /** 이동 상태가 바뀔 수 있는 지점마다(화면 갱신·이동 서비스 판단·시각 갱신·앱 나가기). 바뀌었으면 commit, 같으면 1분마다 시각만 apply, 끝났으면 지움 */
    private fun syncTripState(why: String) {
        if (!TripRestoreRule.ENABLED || !TripRestoreSession.checked) return
        val tg = TripGuideStore.state
        if (!TripRestoreRule.shouldStore(tg)) {
            if (TripRestoreSession.lastSaved != null) {
                TripRestoreSession.lastSaved = null; TripRestoreSession.awaitingGear = null; TripRestoreSession.holdSave = false
                val ok = TripStateStore.clear(applicationContext)
                log("[이동복원] 이동 끝·취소 → 이동 중 상태 지움($why)" + if (ok) "" else " (지우기 실패: 다음 시작 판단이 이동 번호·하차 마무리 기록으로 거름)")
            }
            return
        }
        if (TripRestoreSession.holdSave) return   // r10 수정 2: 되살린 뒤 첫 유효 기어 전에는 저장하지 않는다(지우기는 위에서 함)
        val st = TripSessionStore.state
        val now = System.currentTimeMillis()
        val rec = TripRestoreRule.snapshot(st.tripKey, tg, ArrivalAskStore.state, TripSessionRule.stickerGranted(st),
            TripGuideStore.alerts.leftBehind.said, now)
        val last = TripRestoreSession.lastSaved
        val same = TripRestoreRule.sameContent(last, rec)
        if (same && last != null && now >= last.updatedAtWallMs && now - last.updatedAtWallMs < TripRestoreRule.TOUCH_MS) return
        val ok = TripStateStore.save(applicationContext, rec, important = !same)
        if (ok) TripRestoreSession.lastSaved = rec
        if (!same) log("[이동복원] 이동 ${st.tripKey} 상태 저장 단계=${rec.phase.name}($why)" + if (ok) "" else " (저장 실패: 갑자기 꺼지면 빠른 시작으로)")
    }

    /** 되살린 이동의 첫 유효 기어(주행 또는 유효 P + 허용)에서 가는 길을 한 번 남긴다. 기어 불명·단절·플랫폼 제한이면 기다린다 */
    private fun noteRestoredGear() {
        val r = TripRestoreSession.awaitingGear ?: return
        if (destroyed) return
        val driving = tripDriving()
        if (!driving && lockReason() != null) return
        TripRestoreSession.awaitingGear = null
        TripRestoreSession.holdSave = false   // r10 수정 2: 복원 판단 끝 → 이제부터 저장
        val route = TripRestoreRule.route(r, driving)
        log("[이동복원] 첫 유효 기어=${if (driving) "주행" else "P"} → ${route.name}" + when (route) {
            RestoreRoute.DRIVE_VOICE -> "(잠금 화면 + 음성만: 벨트 풀림 감시 다시 켬, 도착은 P에서)"
            RestoreRoute.PARK_ARRIVAL -> "(도착 신호 받은 이동 → 도착·하차 안내)"
            RestoreRoute.PARK_EXIT -> "(하차 안내 화면 이어 감)"
            RestoreRoute.PARK_ASK -> "(정차 질문 다시)"
            RestoreRoute.PARK_PAUSED -> "(잠깐 멈춤 화면 이어 감)"
        })
        syncTripState("복원 뒤 첫 유효 기어")   // r10 수정 2: 저장 막음을 풀고 지금 상태를 한 번 저장
    }

    private fun refreshTripCard() {
        val card = tripCard ?: return
        val validP = lockReason() == null
        val o = TripEndRule.evaluate(tripEnd, validP)
        card.visibility = if (validP) android.view.View.VISIBLE else android.view.View.GONE
        val linkKind = if (naviLink == NaviLink.READY) StatusStyle.Kind.PROGRESS else StatusStyle.Kind.UNKNOWN
        val moving = devTools.movingLine()
        val mockLine = when {
            moving != null -> "\n" + moving
            signalSource == SignalSource.MOCK || (doneByMock && o.showDone) -> "\n" + StageText.MOCK_ARRIVAL_LABEL
            else -> ""
        }
        tripStatusView.text = StatusStyle.withIcon(linkKind, TripEndRule.linkLabel(naviLink, naviCause)) + mockLine
        tripStatusView.setTextColor(0xFF000000.toInt() or linkKind.currentFg)
        tripStatusView.setBackgroundColor(0xFF000000.toInt() or linkKind.currentBg)
        val v = { b: Boolean -> if (b) android.view.View.VISIBLE else android.view.View.GONE }
        tripManualBtn.visibility = v(o.showManual)
        tripDoneView.visibility = v(o.showDone)
        tripDoneView.text = if (tripEnd.endedBySignal) devTools.doneFor(doneByMock, TripEndRule.DONE_TEXT) else TripEndRule.DONE_MANUAL_TEXT
        hideOldFlowViews()
    }

    // ---- 예시 화면(P1·P2, 미구현·미검증). 보호자 확인 상태는 건드리지 않는다 ----
    private lateinit var examples: ExampleScreens
    private lateinit var demoSteps: DemoStepsCard

    private fun exampleListenAllowed(): Boolean = lockReason() == null && gState == GState.IDLE && tts?.ready == true && GuidanceSetting.speechAllowed(guidanceEnabled)

    private fun refreshExamples() {
        if (!::examples.isInitialized) return
        examples.update(lockReason() == null, exampleListenAllowed())
        if (::demoSteps.isInitialized) demoSteps.update(lockReason() == null)
        // 캐릭터 꾸미기: 유효한 P에서만 보이고, 보호자 확인 IDLE일 때만 선택 가능
        if (::customizer.isInitialized) customizer.update(lockReason() == null, gState == GState.IDLE)
        // 누가 탔나요?: 유효한 P에서만 보이고, 보호자 확인 IDLE일 때만 조작 가능
        if (::childPanel.isInitialized) childPanel.update(lockReason() == null, gState == GState.IDLE, targetSeats.size)
        hideOldFlowViews()
    }

    // 아이 프로필 선택·등록·삭제·호칭 설정(2단계, 가안). 선택 결과는 말풍선·TTS에 아직 적용하지 않는다(3단계)
    private lateinit var childPanel: ChildProfilePanel

    // ---- 캐릭터 꾸미기(가안, 시연용). 메모리에만 두며 보호자 확인 상태·TTS·스크롤과 무관하다 ----
    private lateinit var customizer: CharacterCustomizer
    // 말투(가안). 'a'/'b' 한 글자만 로컬 저장. 문구는 발화·표시 시점에 이 값으로 고른다
    private var tone: Tone = ToneSetting.DEFAULT

    /** 기존 TtsGuide.speak 경로 사용. 보호자 확인 상태·spokenKey·guideCycle은 바꾸지 않는다 */
    private fun speakExample() {
        if (!exampleListenAllowed()) { refreshExamples(); return }
        // 발화(포커스 요청 포함)는 한 번 미루고, 그 사이 조건이 바뀌었으면 발화하지 않는다
        ui.post {
            if (destroyed || !exampleListenAllowed()) { if (!destroyed) refreshExamples(); return@post }
            tts?.speak(ChildNotice.exampleText(noticeTone()), false, "예시안내", isExample = true)
            refreshExamples()
        }
    }

    // TODO(범위 밖): 후석 신호 교차 확인은 onGuardianYes() 직후, 다음 단계로 넘기기 전에 realBelt[대상 좌석]을 대조하는 위치에 둔다.

    // 해제용으로 보관
    private val beltListeners = mutableMapOf<String, SeatBeltBuckledStateListener>()
    private val occListeners = mutableMapOf<String, SeatOccupancyListener>()
    private val warnListeners = mutableMapOf<String, SeatBeltNotBuckledWarningListener>()
    private var gearListener: GearSelectionListener? = null

    // ---- 2026-11-11 U5: 구성 변경(글꼴 배율 등)으로 Activity가 다시 만들어질 때 보호자 화면 위치를 복원한다 ----
    // 원인(감사 #16): Manifest configChanges에 fontScale이 없어 재생성되며, 저장하는 화면 상태가 없어 onCreate가 홈부터 다시 시작했다.
    // 복원은 '화면 위치'만: 설정 열림·첫 안내 단계. 보호자 확인 진행은 복원하지 않는다(재생성 뒤에는 다시 묻기 원칙 유지).
    // 설정은 정차 P·대기 확인이 끝난 뒤에만 다시 연다(재생성 직후는 기어 확인 중 잠금). 10초 안에 조건이 안 되면 포기한다.
    private var pendingSettingsRestoreUntil = 0L
    private var settingsOpenAtPause = false   // onPause 직전 설정이 열려 있었는지(onResume에서 지운다)
    private var foldsAtPause = 0              // v3(P2): onPause 직전 설정 안 접이식 펼침
    private var pendingFoldsRestore = 0       // v3(P2): 설정을 다시 열 때 되돌릴 펼침

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 재생성(isChangingConfigurations)일 때만 설정 위치를 넘긴다. 다른 앱으로 나갔다 온 경우는 기존 설계대로 홈
        outState.putBoolean(UiRestore.KEY_SETTINGS, isChangingConfigurations && (settingsOpen || settingsOpenAtPause))
        outState.putInt(UiRestore.KEY_FIRST_RUN_STEP, firstRunScreen?.let { if (it.visibility == android.view.View.VISIBLE) it.step else -1 } ?: -1)
        outState.putBoolean(UiRestore.KEY_FIRST_RUN_FINISHING, firstRunFinishing)
        outState.putBoolean(UiRestore.KEY_FIRST_RUN_REPLAY, firstRunReplay)
        val folds = if (settingsOpen && ::childPanel.isInitialized) childPanel.foldsMask() else foldsAtPause
        outState.putInt(UiRestore.KEY_FOLDS, if (isChangingConfigurations) folds else 0)
        // v4.1: 재생성 때만 '무대에 있었음'을 넘긴다(다른 앱으로 나갔다 온 경우는 기존 설계대로 홈)
        outState.putBoolean(UiRestore.KEY_ON_STAGE, UiRestore.keepStageAfterRecreate(isChangingConfigurations, !homeActive, settingsOpen || settingsOpenAtPause))
    }

    /** v4.1: 재생성 직후 첫 onResume에서 홈 대신 무대를 유지한다(한 번만) */
    private var keepStageOnce = false

    private fun applyUiRestore(b: Bundle?) {
        if (b == null) return
        val plan = UiRestore.plan(b.getBoolean(UiRestore.KEY_SETTINGS, false), b.getInt(UiRestore.KEY_FIRST_RUN_STEP, -1))
        firstRunFinishing = b.getBoolean(UiRestore.KEY_FIRST_RUN_FINISHING, false)
        firstRunReplay = b.getBoolean(UiRestore.KEY_FIRST_RUN_REPLAY, false)
        firstRunRestoreStep = plan.firstRunStep
        if (plan.reopenSettings) pendingSettingsRestoreUntil = SystemClock.elapsedRealtime() + UiRestore.SETTINGS_WINDOW_MS
        pendingFoldsRestore = UiRestore.foldsToRestore(plan.reopenSettings, b.getInt(UiRestore.KEY_FOLDS, 0))
        keepStageOnce = b.getBoolean(UiRestore.KEY_ON_STAGE, false) && !plan.reopenSettings
        log("[복원] 재생성: 설정 다시 열기=${plan.reopenSettings}, 첫 안내 단계=${plan.firstRunStep}, 접이식=${pendingFoldsRestore}, 무대 유지=$keepStageOnce")
    }

    /** syncHome 앞에서: 복원 대기 중이고 정차 P·대기면 설정을 다시 연다(조건이 안 되면 열지 않고, 시간이 지나면 포기) */
    // ---- 2026-11-14 SCREEN_EXIT_CHECK ④: 설정에서 외부(알림 권한 창·시스템 알림 설정)로 나갔다 오면 설정 유지(ScreenReturnRule) ----
    private var settingsExternalAtMs = -1L
    private var settingsExternalFolds = 0

    private fun markSettingsExternal(what: String) {
        if (!settingsOpen) { settingsExternalAtMs = -1L; return }   // 홈 첫 안내에서 연 권한 창은 홈으로 돌아오면 된다
        settingsExternalAtMs = SystemClock.elapsedRealtime()
        settingsExternalFolds = if (::childPanel.isInitialized) childPanel.foldsMask() else 0
        log("[설정] 외부로 나감($what) → 돌아오면 설정 다시 열기")
    }

    private fun consumeSettingsExternal() {
        val left = settingsExternalAtMs
        if (left < 0) return
        settingsExternalAtMs = -1L
        val now = SystemClock.elapsedRealtime()
        if (!ScreenReturnRule.reopenSettingsAfterExternal(left, now)) { log("[복원] 외부에서 늦게 돌아옴(${(now - left) / 1000}초) → 홈"); return }
        pendingSettingsRestoreUntil = now + UiRestore.SETTINGS_WINDOW_MS
        pendingFoldsRestore = settingsExternalFolds
        log("[복원] 설정에서 외부에 다녀옴 → 유효 P·허용이 되면 설정 다시 열기(${UiRestore.SETTINGS_WINDOW_MS / 1000}초 안)")
    }

    private fun maybeRestoreSettings() {
        val until = pendingSettingsRestoreUntil
        if (until == 0L) return
        if (SystemClock.elapsedRealtime() > until) { pendingSettingsRestoreUntil = 0L; log("[복원] 설정 다시 열기 포기(시간 초과)"); return }
        if (settingsOpen) { pendingSettingsRestoreUntil = 0L; return }
        if (stageScreen == null || lockReason() != null || gState != GState.IDLE) return
        pendingSettingsRestoreUntil = 0L
        log("[복원] 설정 다시 열기")
        openStageSettings()
        if (pendingFoldsRestore != 0 && settingsOpen && ::childPanel.isInitialized) {
            childPanel.restoreFolds(pendingFoldsRestore)   // 잠금이면 패널이 무시한다
            log("[복원] 설정 접이식 펼침=${pendingFoldsRestore}")
        }
        pendingFoldsRestore = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 시스템 야간 설정으로 팔레트를 정한다(뷰를 만들기 전에 한 번). 설정이 바뀌면 Activity가 다시 만들어지며 여기서 다시 정해진다
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        Palette.select(night)
        startElapsedMs = SystemClock.elapsedRealtime()   // 재생성 시에도 새로 기록
        restoreBeltSession()
        restoreTripSession()
        restoreTripState()   // r10 M: 이동 중 기록이 있고 10분 안이고 하차 안내 전이면 이동 상태를 되살린다(되살리면 아래 빠른 시작 판단을 건너뜀)
        decideQuickStart(fresh = savedInstanceState == null)   // r6 6절: 지난 이동이 중간 하차·끝내지 못한 종료이고 60분 안이면 홈 인사 없이 유효 P에서 바로 좌석 확인(재생성 때는 안 함)
        gearNameEverReceived = false
        ui.removeCallbacks(graceEndRefresh)
        ui.postDelayed(graceEndRefresh, STARTUP_GRACE_MS + 1)   // 유예 종료 시 한 번만 갱신(경계는 '이하'가 확인 중)
        val gap = dp(Readability.GAP_DP)
        val minTouch = dp(Readability.MIN_TOUCH_DP)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Readability.BG_COLOR) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }   // 개발용(로그·모의) 영역: 화면 아래쪽
        val row =LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        readBtn = Button(this).apply { text = "다시 읽기"; setOnClickListener { manualReread() } }
        row.addView(readBtn)
        row.addView(Button(this).apply { text = "지우기"; setOnClickListener { logView.text = "" } })
        modeButton = Button(this).apply {
            text = "벨트 출처: 실제"
            setOnClickListener { if (lockReason() != null) { refreshGuardian(); return@setOnClickListener }; useSim = !useSim; text = if (useSim) "벨트 출처: 모의" else "벨트 출처: 실제"; log(if (useSim) "[모드] 벨트를 모의 값으로 대체" else "[모드] 벨트를 실제 SDK 값으로"); refreshBoard() }
        }
        row.addView(modeButton)
        devBox.addView(TextView(this).apply { text = "── 개발용 영역(보호자 확인 아님) ──"; textSize = 14f; setTextColor(Readability.TEXT_COLOR) })
        devBox.addView(row)
        val simRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, _) in seats) {
            val sb2 = Button(this).apply { text = seatShort[name] ?: name; textSize = 14f; setOnClickListener { toggleSim(name) } }
            simButtons.add(sb2); simRow.addView(sb2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        devBox.addView(simRow)
        boardView = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(0, 12, 0, 12); setTextColor(Readability.TEXT_COLOR) }
        devBox.addView(boardView)
        // 확인 대상 좌석 선택(보호자) + 보호자 확인 모의 흐름
        // 좌석 5개: 76dp·24sp가 한 줄에서 잘리지 않도록 3개+2개 두 줄로 나눈다(버튼 사이 가로·세로 간격 8dp)
        val targetRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val seatRows = listOf(
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL },
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL },
        )
        for ((i, pair) in seats.toList().withIndex()) {
            val name = pair.first
            val b = Button(this).apply { setOnClickListener { toggleTarget(name) } }
            styleControl(b, Readability.CONTROL_SP, minTouch)
            b.setSingleLine(true)   // 추천 글자가 붙어도 줄 수·높이가 변하지 않게 한 줄 고정
            seatButtons[name] = b
            seatRows[if (i < 3) 0 else 1].addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, gap, gap) })
        }
        // 둘째 줄은 버튼 폭을 첫 줄과 맞추기 위해 빈 자리를 둔다(터치 대상 아님)
        for (k in seats.size until 6) {
            seatRows[1].addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f))
        }
        for (r in seatRows) targetRow.addView(r)
        val seatLabel = TextView(this).apply { text = "확인 대상 좌석 선택(보호자):"; textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, gap, 0, gap) }
        // 안내자 캐릭터(시연용 그래픽): 잠금이면 refreshGuardian이 통째로 숨긴다
        characterView = GuideCharacterView(this, Readability.HOME_CHARACTER_DP)
        bubbleView = TextView(this).apply {
            textSize = Readability.BUBBLE_SP; setTextColor(Readability.TEXT_COLOR); setPadding(16, 0, 0, 0)
            minimumHeight = dp(Readability.BUBBLE_MIN_DP); gravity = android.view.Gravity.CENTER_VERTICAL   // 빈 말풍선도 자리 유지
        }
        // 보호자·심사자용 표기를 모은 작은 보조 라벨(한 줄 고정, 굵은 글씨 없음, 기존 보조 글자 색 역할). 높이는 이전 라벨과 같다(20sp 한 줄)
        auxLabel = TextView(this).apply {
            text = ReleaseCopy.t(DemoLabel.build(false, 0)); textSize = Readability.AUX_SP
            setTextColor(Palette.argb(Palette.current.btnOffText))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val charLabel = auxLabel
        val charRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        charRow.addView(characterView)
        // 캐릭터는 왼쪽, 말풍선(위)과 스티커 행(아래)은 오른쪽 세로 묶음(스티커는 INVISIBLE로 자리 확보)
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        rightCol.addView(bubbleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        charRow.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        characterBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        characterBox.addView(charRow)
        characterBox.addView(charLabel)
        guardianHeader = TextView(this).apply {
            text = ReleaseCopy.t(GuardianHeader.TEXT); textSize = GuardianHeader.SP
            setTextColor(Palette.argb(Palette.current.btnOffText))   // 기존 보조 글자 역할(주간·야간 모두 화면 배경 대비 높음)
            setPadding(0, dp(GuardianHeader.TOP_DP), 0, dp(GuardianHeader.BOTTOM_DP))
        }
        firstRunAcked = FirstRunStore.load(this)   // 2026-11-11 U0: 첫 안내 확인 기록(없으면 0 → 홈에서 안내)
        voiceEnabled = VoiceAnswerStore.load(this)   // 스위치를 만들기 전에 저장값(없으면 꺼짐)을 읽는다
        voiceSwitch = DuoSwitch(this).apply {   // 2026-11-11 U1: 브랜드 색 스위치(기본 보라색 제거). 저장·잠금 처리는 그대로
            text = "음성으로 답하기"
            textSize = Readability.SWITCH_SP; setTextColor(Readability.TEXT_COLOR); minimumHeight = minTouch
            isChecked = voiceEnabled
            setOnCheckedChangeListener { _, checked ->
                if (lockReason() != null) { isChecked = voiceEnabled; return@setOnCheckedChangeListener }
                if (checked == voiceEnabled) return@setOnCheckedChangeListener
                voiceEnabled = checked
                val saved = VoiceAnswerStore.save(this@MainActivity, checked)
                log((if (checked) "[음성설정] 켜짐" else "[음성설정] 꺼짐") + if (saved) "" else " (저장 실패: 다음 시작 때 저장된 값 또는 꺼짐)")
                if (!checked) { voiceNotice = ""; stopVoice("음성 스위치 끔"); stopArrivalListen("음성 스위치 끔") }   // 진행 중 인식은 즉시 멈춘다
                refreshGuardian()
            }
        }
        guidanceEnabled = GuidanceStore.load(this)
        guidanceSwitch = DuoSwitch(this).apply {
            text = "안내 음성 사용"
            textSize = Readability.SWITCH_SP; setTextColor(Readability.TEXT_COLOR); minimumHeight = minTouch
            isChecked = guidanceEnabled
            setOnCheckedChangeListener { _, checked ->
                if (lockReason() != null) { isChecked = guidanceEnabled; return@setOnCheckedChangeListener }
                if (checked == guidanceEnabled) return@setOnCheckedChangeListener
                guidanceEnabled = checked
                GuidanceStore.save(this@MainActivity, checked)
                log(if (checked) "[음성설정] 안내 음성 켜짐" else "[음성설정] 안내 음성 꺼짐")
                val t = tts
                if (t != null) {
                    t.guidanceEnabled = checked
                    if (!checked) {
                        val questionWasPending = t.questionPending
                        t.stop(true); t.clearSpeech()   // 진행 중 발화 중지
                        // 질문 발화 완료를 기다리던 중이면 발화 없음 경로와 같게 즉시 STT 시작(startVoice 내부 조건 유지)
                        if (questionWasPending && gState == GState.ASKING) startVoice()
                    }
                }
                refreshGuardian()
            }
        }
        // 2026-11-13 1(d): 이동 중 교통 상식(아이 대상, 음성만) 켜기/끄기. 기본 켬
        tipsEnabled = VoiceTipStore.load(this)
        val tipsSwitch = DuoSwitch(this).apply {
            text = "이동 중 교통 상식"
            textSize = Readability.SWITCH_SP; setTextColor(Readability.TEXT_COLOR); minimumHeight = minTouch
            isChecked = tipsEnabled
            setOnCheckedChangeListener { _, checked ->
                if (lockReason() != null) { isChecked = tipsEnabled; return@setOnCheckedChangeListener }
                if (checked == tipsEnabled) return@setOnCheckedChangeListener
                tipsEnabled = checked
                val saved = VoiceTipStore.save(this@MainActivity, checked)
                log((if (checked) "[음성설정] 교통 상식 켜짐" else "[음성설정] 교통 상식 꺼짐") + if (saved) "" else " (저장 실패)")
            }
        }
        guidanceNote = TextView(this).apply {
            text = "이 선택은 이 기기의 앱에만 저장돼요(켜짐/꺼짐만)."
            textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 0, 0, 8)
        }
        migrateDecision23()   // 저장·메모리에 남은 앞줄 배정을 지운다(화면을 만들 때 1회, 같은 값이면 아무것도 안 함)
        suggestedSeats = SeatSuggestionStore.load(this)
        val suggestNote = TextView(this).apply {
            text = SettingsCopy.RECORD_DESC
            textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 8, 0, 8)
        }
        // 2026-11-11 U2: 지난 좌석 추천 지우기 = 보조 버튼 + 확인 줄(바로 지우지 않음)
        val suggestConfirm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        val suggestClearBtn = DuoButton(this, DuoTokens.Kind.DANGER).apply {   // v3: 지우기 = 주의 모양
            text = SettingsLook.dangerLabel("지난 좌석 추천 지우기")   // 루프 r2: 지우기 = 빨강 글자 + 기호
            // 루프 r2 설정 정리: 인라인 확인 줄 대신 앱 모양 확인 창(AppDialogs.confirm). [지우기] 순간 잠금을 다시 본다
            setOnClickListener {
                if (lockReason() != null) return@setOnClickListener
                AppDialogs.confirm(this@MainActivity, "지난번에 고른 좌석 이름을 지울까요? 아이 별명·스티커 기록은 그대로예요.", "지우기", "취소",
                    onYes = {
                        if (lockReason() == null) {
                            SeatSuggestionStore.clear(this@MainActivity)
                            suggestedSeats = emptySet()
                            log("[설정] 지난 좌석 추천 지움")
                            refreshBoard(); refreshGuardian()
                        }
                    },
                    onNo = {}, onCancel = {}).show()
            }
        }
        suggestConfirm.addView(DuoText.body(this, "지난번에 고른 좌석 이름을 지울까요? 아이 별명·스티커 기록은 그대로예요.").apply { setPadding(0, dp(12), 0, dp(8)) })
        val suggestConfirmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestConfirmRow.addView(DuoButton(this, DuoTokens.Kind.ACCENT).apply {
            text = "지우기"
            setOnClickListener {
                if (lockReason() != null) { suggestConfirm.visibility = android.view.View.GONE; return@setOnClickListener }
                SeatSuggestionStore.clear(this@MainActivity)
                suggestedSeats = emptySet()
                suggestConfirm.visibility = android.view.View.GONE
                log("[설정] 지난 좌석 추천 지움")
                refreshBoard(); refreshGuardian()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
        suggestConfirmRow.addView(DuoButton(this, DuoTokens.Kind.NEUTRAL).apply {
            text = "취소"
            setOnClickListener { suggestConfirm.visibility = android.view.View.GONE }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        suggestConfirm.addView(suggestConfirmRow)
        // 2026-11-11 U1/U2: 음성 스위치 두 개를 브랜드 토글 행(행 전체 터치, 켜짐/꺼짐 글자)으로. switchRow는 잠금 때 숨김 대상(기존 refreshGuardian)
        switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(DuoToggleRow(this@MainActivity, SettingsCopy.VOICE_SWITCH, "승무원이 소리 내어 안내해요.", guidanceSwitch as DuoSwitch))   // 루프 r2: 카드 제목과 겹치지 않는 이름 + 한 줄 설명
            addView(android.view.View(this@MainActivity).apply { setBackgroundColor(Palette.argb(DuoTokens.roles(Palette.current.night).disabledStroke)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            // 2026-11-13 PM 지시 4: '음성으로 답하기'(STT)는 예/아니오 질문 경로에서만 쓰고, 자동 확인 흐름에는 질문이 없어 설정에서 숨긴다(코드·저장값은 남김).
            // 그 자리에 이동 중 교통 상식 스위치를 둔다
            addView(DuoToggleRow(this@MainActivity, "이동 중 교통 상식", "달리는 동안 짧은 상식을 들려줘요(약 10분마다 하나씩).", tipsSwitch))   // 루프 r2: 한 줄 설명
            // 결정 40(사용자 확정, 11-15, r5 추가 지시 A): 도착 질문 뒤 5초만 듣는 음성 대답 스위치를 다시 보인다(기본 켬, 저장값이 있으면 그 값)
            addView(android.view.View(this@MainActivity).apply { setBackgroundColor(Palette.argb(DuoTokens.roles(Palette.current.night).disabledStroke)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(DuoToggleRow(this@MainActivity, "음성으로 답하기", "도착 질문에 '네/아니요'로 답할 수 있어요.", voiceSwitch as DuoSwitch))
            // r7 5절(임시 결정 50): 다시 탔을 때 차를 켜면 자동으로 열기(기본 켬). 저장 AutoOpenStore, 동작 AutoOpenReceiver·AutoOpenWatcher
            // r10 B(사용자 결정): 자동 켜기를 꺼서 이 항목(구분선 포함)을 숨긴다. 되돌리기 AutoOpenRule.SETTING_VISIBLE = true
            if (AutoOpenRule.SETTING_VISIBLE) {
                addView(android.view.View(this@MainActivity).apply { setBackgroundColor(Palette.argb(DuoTokens.roles(Palette.current.night).disabledStroke)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                addView(DuoToggleRow(this@MainActivity, AutoOpenCopy.SWITCH_TITLE, AutoOpenCopy.SWITCH_DESC, autoOpenSwitch()))
            }
        }
        noticeTitle = TextView(this).apply {
            text = "▶ 음성 사용 안내"; textSize = Readability.SWITCH_SP; setPadding(0, 8, 0, 8)   // 2026-11-14: "(가안)" 표기 지움
            setTextColor(Readability.TEXT_COLOR); minimumHeight = minTouch; gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                val open = noticeBox.visibility != android.view.View.VISIBLE
                noticeBox.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = if (open) "▼ 음성 사용 안내" else "▶ 음성 사용 안내"
            }
        }
        noticeBody = TextView(this).apply {
            textSize = Readability.NOTICE_BODY_SP; setTextColor(Readability.TEXT_COLOR)
            // 2026-11-14 r5 4절: 옛 화면(NEW_STAGE=false)에서만 붙는 접이식. 지금 동작(앱이 듣기를 시작하는 경로 없음)에 맞추고 '검토 중인 초안' 줄은 없앴다
            text = ReleaseCopy.t("[이 앱의 동작]\n" +
                FirstRunCopy.STEP2_VOICE.joinToString("\n") { "• $it" } + "\n" +
                "• " + FirstRunCopy.STEP2_NOT_SAVED.last())   // r6a 3절: '확인 전' → SDK '전송될 수 있어요'(처음 안내와 같은 문장)
        }
        // 상태와 무관하게 높이 고정: 일상 흐름 최악 조합 기준 최소 GUARD_MIN_LINES줄(상수 옆 주석 참고)
        guardView = TextView(this).apply { textSize = GuardLayout.CORE_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 8, 0, dp(GuardLayout.GAP_DP)); minLines = GUARD_MIN_LINES }
        noticeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE
            addView(TextView(this@MainActivity).apply { text = AppName.aboutLine; textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 0, 0, 8) })   // ④ 앱 이름·이전 표시명
            addView(guidanceNote); addView(noticeBody)
            if (!StageFlags.NEW_STAGE) { addView(suggestNote); addView(suggestClearBtn) }   // 새 무대: 설정 「기록·초기화」 카드로 옮긴다(한 부모만 가능)
        }
        guardDetailView = TextView(this).apply { textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 8, 0, 8); visibility = android.view.View.GONE }
        detailTitle = TextView(this).apply {
            text = "▶ ${DetailLines.DEV_TITLE}"; textSize = Readability.SWITCH_SP; setPadding(0, 8, 0, 8)
            setTextColor(Readability.TEXT_COLOR); minimumHeight = minTouch; gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                val open = guardDetailView.visibility != android.view.View.VISIBLE
                guardDetailView.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = if (open) "▼ ${DetailLines.DEV_TITLE}" else "▶ ${DetailLines.DEV_TITLE}"
            }
        }
        guardStatusView = TextView(this).apply { textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 8, 0, 8); visibility = android.view.View.GONE }
        statusTitle = TextView(this).apply {
            text = ReleaseCopy.t("▶ ${DetailLines.GUARDIAN_TITLE}"); textSize = Readability.SWITCH_SP; setPadding(0, 8, 0, 8)
            setTextColor(Readability.TEXT_COLOR); minimumHeight = minTouch; gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                val open = guardStatusView.visibility != android.view.View.VISIBLE
                guardStatusView.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = ReleaseCopy.t(if (open) "▼ ${DetailLines.GUARDIAN_TITLE}" else "▶ ${DetailLines.GUARDIAN_TITLE}")
            }
        }
        val guardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { setOnClickListener { startGuardian() } }
        yesBtn = Button(this).apply { text = "예"; setOnClickListener { onGuardianYes("터치") } }
        noBtn = Button(this).apply { text = "아니오"; setOnClickListener { if (lockReason() != null) { refreshGuardian(); return@setOnClickListener }; responseSource = "터치"; onGuardianNoOrTimeout("보호자가 '아니오' 터치") } }
        // 보호자 확인 영역 전용 크기(전역 Readability 상수는 바꾸지 않음): 예/아니오 32sp·높이 96dp·버튼 사이 24dp
        val guardGap = dp(GuardLayout.GAP_DP)
        styleControl(startBtn, Readability.CONTROL_SP, minTouch)
        guardRow.addView(startBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, guardGap, 0) })
        for (b in listOf(yesBtn, noBtn)) {
            styleControl(b, GuardLayout.CORE_SP, dp(GuardLayout.BUTTON_MIN_DP))
            val last = b === noBtn
            guardRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, if (last) 0 else guardGap, 0) })
        }
        nextView = TextView(this).apply { textSize = Readability.CORE_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 4, 0, 8); minLines = NEXT_MIN_LINES; visibility = android.view.View.INVISIBLE }
        // 참여 스티커: 코드로 그린 둥근 도형 + 문구(외부 이미지·체크·별 없음). 보이지 않을 때도 자리 유지(INVISIBLE)
        val badge = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.argb(Palette.current.stickerFill)); setStroke(dp(3), Palette.argb(Palette.current.stickerStroke)) }
        }
        val stickerText = TextView(this).apply { text = StickerRule.CHILD_TEXT; textSize = Readability.CORE_SP; setTextColor(Readability.TEXT_COLOR); minLines = 2 }
        // 이전 보조 라벨이 차지하던 한 줄 높이를 그대로 두는 보이지 않는 자리(표기는 캐릭터 행 아래 보조 라벨로 이동). 좌석 버튼 y 유지용
        stickerLabel = TextView(this).apply {
            text = " "; textSize = Readability.AUX_SP; maxLines = 1
            visibility = android.view.View.INVISIBLE
        }
        val stickerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(stickerText); addView(stickerLabel) }
        stickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0); visibility = android.view.View.INVISIBLE   // 캐릭터 행 안(가로 자리만 확보)이라 세로 여백 없음
            addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, 0, dp(12), 0) })
            addView(stickerCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        (charRow.getChildAt(1) as LinearLayout).addView(stickerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 옛 화면의 이번 이동 끝내기 카드(새 무대에서는 oldFlowViews로 늘 숨김 = 사용 안 함): 내비 연결 상태 줄·수동 끝내기·마침 줄만 남겼다.
        // 2026-11-14: 이동 끝 안내 줄("이번 이동이 끝난 것 같아요…")과 신호 기준 [끝내기] 버튼은 지웠다
        tripStatusView = TextView(this).apply { textSize = Readability.AUX_SP; setTextColor(Readability.TEXT_COLOR) }
        tripManualBtn = Button(this).apply { text = "이번 이동 끝내기(내비 신호 없이)"; setOnClickListener { finishTrip("수동 끝내기(옛 화면)") }; visibility = android.view.View.GONE }
        styleControl(tripManualBtn, Readability.CONTROL_SP, minTouch)
        tripDoneView = TextView(this).apply { textSize = Readability.CORE_SP; setTextColor(Readability.TEXT_COLOR); setPadding(0, 4, 0, 4); visibility = android.view.View.GONE }
        tripCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, gap, 0, gap); visibility = android.view.View.GONE
            addView(TextView(this@MainActivity).apply { text = TripEndRule.TITLE; textSize = Readability.SWITCH_SP; setTextColor(Readability.TEXT_COLOR) })
            addView(tripStatusView)
            addView(tripDoneView)
            addView(tripManualBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        examples = ExampleScreens(
            this, { dp(it) }, Readability.AUX_SP, Readability.SWITCH_SP, minTouch, Readability.TEXT_COLOR,
            { styleControl(it, Readability.CONTROL_SP, minTouch) },
            { speakExample() },
        )
        demoSteps = DemoStepsCard(
            this, { dp(it) }, Readability.CORE_SP, Readability.AUX_SP, Readability.SWITCH_SP, minTouch, Readability.TEXT_COLOR,
        )
        customizer = CharacterCustomizer(
            this, { dp(it) }, gap, Readability.AUX_SP, Readability.SWITCH_SP, minTouch, Readability.TEXT_COLOR,
            Readability.CHARACTER_DP,
            onChange = { characterView.style = it; CharacterStore.save(this, it); styleState.set(it) },
            onReset = { CharacterStore.clear(this) },
            onToneChange = { tone = it; ToneStore.save(this, it); refreshGuardian() },
        )
        childPanel = ChildProfilePanel(
            this, { dp(it) }, gap, Readability.AUX_SP, Readability.SWITCH_SP, minTouch, Readability.TEXT_COLOR,
            currentStyle = { customizer.style },
            currentTone = { tone },
        )
        // r10 수정(J + 화면 재생성): 이동 중 Activity가 다시 만들어졌으면 이번 이동의 아이 선택·'등록 없이 진행'을 보관소에서 되살린다
        TripKidsRule.forTrip(TripKidsStore.saved, TripSessionStore.state.tripKey)?.let { k ->
            confirmWithoutProfile = k.withoutProfile
            ui.post { if (!destroyed && ::childPanel.isInitialized) childPanel.selectForTrip(k.kidIds) }
        }
        tone = ToneStore.load(this)
        customizer.applyTone(tone)
        val storedStyle = CharacterStore.load(this)
        characterView.style = storedStyle
        customizer.applyStored(storedStyle)
        styleState.set(storedStyle)   // 저장된 꾸밈을 보관소에 넣는다(이후 만들어지는 홈·무대·꾸미기 화면이 구독하면서 바로 받는다)
        refreshGuardian()
        // 로그 뷰는 선택 불가·포커스 불가: 선택 가능 텍스트는 포커스를 가져가 바깥 ScrollView를 따라 움직이게 한다
        logView = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; isFocusable = false; isFocusableInTouchMode = false; setTextColor(Readability.TEXT_COLOR) }
        scroll = ScrollView(this).apply {
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(logView)
        }
        devBox.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Readability.DEV_LOG_HEIGHT_DP)))
        devBox.addView(detailTitle)      // '상세 상태(가안, 개발용)' 접이식은 개발용 보기 안(꺼지면 안 보임)
        devBox.addView(guardDetailView)
        setupGlTest(devBox)   // 3D 시험(가안, 개발용): 개발용 보기 안 한 곳 연결
        setupSvgCharTest(devBox)   // 캐릭터 시험(가안, 개발용): 개발용 보기 안 한 곳 연결
        setupStagePreview(devBox)   // 무대 미리보기(가안, 개발용): 개발용 보기 안에서만 보임
        // 모의 내비·모의 기어 패널은 debug 빌드에서만 만든다(release는 진입점 자체가 없음, 주입 경로도 DevMockGuard가 막음)
        if (DevMockGuard.allowed) devTools.attachPanels(devBox)   // debug만 모의 패널을 붙인다(release DevTools는 아무것도 하지 않음)
        // 위에서 아래로: 질문·안내 → 예/아니오/시작 → 다음 단계 → 캐릭터 → 좌석 선택 → 음성 스위치·안내 → 상세 상태 → 개발용
        content.addView(guardView)
        content.addView(guardRow)
        content.addView(nextView)
        content.addView(characterBox)
        if (StageFlags.NEW_STAGE) {
            // 2026-11-11 U2: 보호자 설정 = 제목 + 2열 카드(아이 관리 | 음성·기록·앱 정보). 예전 흐름 화면은 아래에 숨긴 채 둔다
            content.addView(buildSettingsLayout(childPanel.root, switchRow, suggestNote, suggestClearBtn, suggestConfirm))
        } else {
            content.addView(childPanel.root)
        }
        content.addView(seatLabel)
        content.addView(targetRow)
        content.addView(guardianHeader)
        if (!StageFlags.NEW_STAGE) {
            content.addView(switchRow)
            content.addView(noticeTitle)
            content.addView(noticeBox)
        }
        content.addView(statusTitle)
        content.addView(guardStatusView)
        content.addView(tripCard)
        if (!StageFlags.NEW_STAGE) content.addView(customizer.root)   // 새 무대에서는 독립 꾸미기 화면이 이 패널을 쓴다
        // 2026-11-10 2차 디렉티브 ②: 예시 화면·시연 단계 안내 카드는 debug 빌드에서만 붙인다(release 화면에 없음)
        if (BuildMockPolicy.SHOW_DRAFT_MARKS) { content.addView(examples.root); content.addView(demoSteps.root) }
        // 개발용 영역은 기본 숨김(메모리에만 유지, 저장 안 함). 로그는 숨김 중에도 logView에 계속 쌓인다
        devBox.visibility = android.view.View.GONE
        val devToggle = TextView(this).apply {
            text = DevToggle.label(false); textSize = 24f; setTextColor(Readability.TEXT_COLOR)
            minimumHeight = maxOf(minTouch, dp(76)); gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                val open = devBox.visibility != android.view.View.VISIBLE
                devBox.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = DevToggle.label(open)
                if (open) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }   // 켜면 최근 로그가 보이게
            }
        }
        content.addView(devToggle)
        content.addView(devBox)
        // 새 무대가 기본일 때 숨길 예전 흐름 화면(프로필·음성·꾸미기·음성 안내만 설정으로 남긴다)
        oldFlowViews = listOf(guardRow, guardView, nextView, characterBox, seatLabel, targetRow, guardianHeader, statusTitle, guardStatusView, tripCard!!, examples.root, demoSteps.root)
        devToggleView = devToggle
        devBoxView = devBox
        // 작은 화면에서 잘리지 않도록 전체를 스크롤 안에 둔다(질문·예/아니오는 맨 위)
        outerScroll = ScrollView(this).apply {
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(content)
        }
        root.addView(outerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (StageFlags.NEW_STAGE) buildSettingsChrome(root)   // v3 반복 4: 설정 고정 머리(맨 위)·하단 띠(맨 아래), 평소 GONE
        setContentView(root)
        // v3 반복 6(P1-2): 나중에 붙는 화면(무대·홈·처음 안내·말풍선)까지 어절 단위 줄바꿈. 이미 걸린 뷰는 건너뛴다
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { if (!destroyed) DuoText.wordBreakTree(window.decorView) }
        scrollOuterToTop()

        log("앱 시작. 첫 화면 뒤에 Vehicle → STT → TTS 순서로 초기화")
        refreshBoard()
        refreshTripCard()
        // 새 무대가 기본 화면: 앱을 켜는 순간부터 무대가 덮고, 예전 흐름 화면·개발용 도구는 숨긴다
        applyUiRestore(savedInstanceState)   // 2026-11-11 U5: showHome 전에 복원 계획을 정한다
        if (StageFlags.NEW_STAGE) { hideOldFlowViews(); showHome() }
        // 무거운 초기화는 첫 프레임 뒤로 미룬다(decorView.post는 첫 레이아웃·그리기 뒤에 실행된다)
        window.decorView.post(deferredInit)
    }

    private var initDone = false   // 지연 초기화가 끝났는지(메인 스레드 전용)

    // ---- 2026-11-14 SCREEN_EXIT_CHECK ❌: Vehicle 초기화 실패(vehicle == null)면 화면이 보이는 동안 10초 간격으로 다시 초기화(ReconnectRule) ----
    private var vehicleRetryCount = 0
    private val vehicleRetryRunnable = Runnable { runVehicleRetry() }

    /** Vehicle 만들기·초기화 1회. 성공(예외 없음)이면 true. 잠금(S09)은 신호를 받을 때까지 그대로다 */
    private fun initVehicle(why: String): Boolean = try {
        vehicle = Vehicle(applicationContext).also { it.initialize() }
        log("Vehicle.initialize() 호출 완료 (예외 없음, $why)")
        true
    } catch (t: Throwable) {
        vehicle = null
        log("Vehicle 초기화 예외($why): ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun scheduleVehicleRetry() {
        ui.removeCallbacks(vehicleRetryRunnable)
        val delay = ReconnectRule.vehicleDelay(initDone, vehicle != null, started, destroyed) ?: return
        ui.postDelayed(vehicleRetryRunnable, delay)
    }

    private fun runVehicleRetry() {
        if (destroyed || vehicle != null || !started) return
        vehicleRetryCount++
        log("[VEHICLE] 다시 초기화 ${vehicleRetryCount}회째(간격 ${ReconnectRule.VEHICLE_INTERVAL_MS}ms, 화면이 보일 때만)")
        if (!initVehicle("다시 초기화 ${vehicleRetryCount}회째")) { scheduleVehicleRetry(); return }
        log("[VEHICLE] 다시 초기화 성공(${vehicleRetryCount}회째) → 2초 뒤 구독·현재값 재조회")
        vehicleRetryCount = 0
        // 첫 초기화와 같은 순서: 서비스 바인딩을 잠시 기다린 뒤 등록 → 조회. 값이 오기 전에는 잠금 그대로
        ui.postDelayed({ if (!destroyed && vehicle != null) { registerAll(); readAll(); evaluateLink(); scheduleGearWatch(); onGateInputsChanged() } }, 2000)
    }

    // 첫 프레임 뒤 한 번만 실행. 순서: Vehicle → STT → TTS, 그다음 2초 뒤 등록·조회, 3초 뒤 NaviHelper 시작(둘 다 초기화 끝난 시점 기준)
    private val deferredInit = Runnable {
        if (destroyed || initDone) return@Runnable
        initDone = true
        if (!initVehicle("첫 초기화")) scheduleVehicleRetry()
        // 서비스 바인딩은 비동기일 수 있어 잠시 뒤 읽는다
        initStt()
        tts = TtsGuide(applicationContext, ui, { log(it) }, { if (!destroyed) refreshGuardian() }, { g, end -> onQuestionSpeech(g, end) }).also {
            it.guidanceEnabled = guidanceEnabled
            // 발화가 실제로 끝났을 때만 세션 1회를 소모한다(오류·중지·포커스 상실은 소모 안 함)
            // 발화가 실제로 끝났을 때(onDone)만 전달 완료. 세대가 다르면(취소된 이전 경고) 무시한다
            it.onExitResult = { g, end -> onExitResult(g, end) }
            it.onDriveResult = { g, done ->
                if (!destroyed) {
                    applyBeltStep(BeltWarningPolicy.result(beltWarn, g, done, SystemClock.elapsedRealtime()))
                    if (endAfterWarning && beltWarn.inFlight == null) endBeltReleaseSession("이동 후 P 복귀(경고 결과 뒤)") else runBeltEval()
                }
            }
            it.onIdle = {
                if (!destroyed && beltWarn.pending.isNotEmpty()) runBeltEval()
                if (!destroyed && (nightPending || layoutPending)) ui.post { maybeApplyNight() }
                // QA RISK 3: 미뤄 둔 하차 한 줄은 다른 발화가 끝난 뒤 게이트(유효 P + 최신 UXR 허용 + 연결)를 다시 보고 평가한다
                if (!destroyed && exitCheck.waitingIdle) ui.post { if (!destroyed && (started || resumed) && exitCheck.waitingIdle) { log("[하차확인] 다른 발화 종료 → 재평가"); exitCheckEvaluate() } }
                if (!destroyed && autoQueue.isNotEmpty()) ui.post { pumpAuto() }   // 2026-11-13: 대기 중인 자동 안내
            }
            it.onSpeechResult = { g, end -> onSpeechResult(g, end) }
            it.onAutoResult = { g, end -> onAutoSayResult(g, end) }
            // r5 2절: 내비 음성이 끝나면 쉬는 시간 뒤 기다리던 안내를 다시 보낸다
            it.onNaviQuiet = { if (!destroyed && autoQueue.isNotEmpty()) { ui.removeCallbacks(pumpRunnable); ui.postDelayed(pumpRunnable, NaviVoiceRule.GAP_AFTER_NAVI_MS + 50L) } }
            it.drivingNow = { !destroyed && tripDriving() }
            it.onNaviStart = { if (arrivalListening) { stopArrivalListen("내비 안내 음성 시작"); log("[도착질문] 내비 음성과 섞일 수 있어 듣기 멈춤 → 버튼만") } }   // 결정 37(사용자 확정, 11-15): 끼어들기 멈춤은 주행 중 자동 안내에만
            it.onSpeakingChanged = { renderStageOnly() }   // 2026-11-08 v6.1: 캐릭터 말하기 표시(실제 onStart~종료)
            it.onReady = { if (!destroyed) adoptCarriedVoice("음성 준비됨") }   // r10 N: 다시 그리기 전 화면이 넘긴 유지 음성 이어 내기
            it.init()
        }
        refreshBoard()
        ui.postDelayed({ if (!destroyed) { registerAll(); readAll(); evaluateLink(); scheduleGearWatch(); devTools.startInjector() } }, 2000)   // 등록 먼저, 조회는 그다음
        // 플랫폼 주행 제한 구독(2026-11-07 Unit 5.1). 값이 오기 전·연동 불가면 잠금 유지
        platformSource = PlatformUxSource(applicationContext, ui) { p, why -> onPlatformUx(p, why) }.also { it.start() }
        // 내비 이벤트 수신은 지연 초기화(실패해도 다른 기능에 영향 없음)
        naviTripEnd = NaviTripEnd(applicationContext, ui, { log(it) }, { e -> onNaviEvent(e) }, { l, c -> onNaviLink(l, c) },
            onTime = { t -> onNaviTime(t, SignalSource.REAL) })   // 2026-11-13: 경로 시작·남은 시간(숫자만, 초 가정)
        naviTripEnd?.onRouteState = { routing, note -> onRouteStateAnswer(routing, note) }   // 결정 41(사용자 확정(11-15)): 정차 때 경로 안내 중인지
        ui.postDelayed({ if (!destroyed) naviTripEnd?.start() }, 3000)
        refreshTripCard()
        refreshGuardian()   // tts 객체가 생겼으므로 상태 문구 반영
    }

    private var outerScroll: ScrollView? = null   // 화면 전체를 감싸는 바깥 스크롤(로그용 scroll과 다름)

    /** 바깥 스크롤을 레이아웃 완료 뒤 맨 위로 보낸다. 시작·onResume·보호자 확인 상태 전환에서만 호출하고 로그 갱신에서는 호출하지 않는다 */
    private fun scrollOuterToTop() {
        // 별명 입력 중에는 맨 위로 보내지 않는다(입력을 방해하지 않게)
        outerScroll?.let { s -> s.post { if (!(::childPanel.isInitialized && childPanel.isEditing())) s.scrollTo(0, 0) } }
    }

    // ── 3D 시험(가안, 개발용). 롤백: StageGlView.kt 삭제 + 이 블록·setupGlTest 호출·onPause/onResume/onDestroy/applyGear의 glView 줄 삭제
    private var glView: StageGlView? = null
    private var glHost: LinearLayout? = null
    private var glStatus: TextView? = null
    private var glOpen = false

    private fun glShouldRun(): Boolean = glOpen && lockReason() == null && resumed

    private fun syncGl() {
        val v = glView ?: return
        if (glShouldRun()) v.onResume() else v.onPause()
    }

    private fun setupGlTest(devBox: LinearLayout) {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        val status = TextView(this).apply { text = "3D 시험: 펼치면 시작해요"; textSize = 20f; setTextColor(Readability.TEXT_COLOR) }
        host.addView(status)
        glHost = host; glStatus = status
        val title = TextView(this).apply {
            text = "▶ 3D 시험(가안, 개발용)"; textSize = 24f; setTextColor(Readability.TEXT_COLOR)
            minimumHeight = dp(76); gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                glOpen = !glOpen
                text = if (glOpen) "▼ 3D 시험(가안, 개발용)" else "▶ 3D 시험(가안, 개발용)"
                host.visibility = if (glOpen) android.view.View.VISIBLE else android.view.View.GONE
                if (glOpen && glView == null && !destroyed) {
                    val gv = StageGlView(
                        this@MainActivity,
                        onShaderReady = { log("[3D] 셰이더 준비") },
                        onFirstFrame = { log("[3D] 첫 프레임 그림"); glStatus?.text = "3D 시험: 그리기 요청 완료(화면 확인은 별도)" },
                        onError = { m -> log("[3D] 오류($m)"); glStatus?.text = "3D 시험: 사용할 수 없어요(원인은 로그 참고)" },
                        onGlVersion = { s -> log("[3D] GL=$s") },
                        onInfo = { s -> log("[3D] $s") },
                    )
                    glView = gv
                    host.addView(gv, LinearLayout.LayoutParams(dp(480), dp(480)))
                }
                syncGl()
            }
        }
        devBox.addView(title)
        devBox.addView(host)
    }

    // ── 캐릭터 시험(가안, 개발용). 롤백: SvgPathParser/SvgPart/CharacterParts/CharacterColors/SvgCharacterView.kt 삭제 + 이 블록·setupSvgCharTest 호출·onDestroy의 svgView 줄 삭제
    private var svgView: SvgCharacterView? = null
    private var svgStatus: TextView? = null
    private var svgOpen = false

    private fun refreshSvgStatus() {
        val v = svgView ?: return
        svgStatus?.text = "캐릭터 시험: 부품 ${CharacterParts.forMouth(v.mouth).size}개 · 입 ${v.mouth.name}"
    }

    private fun setupSvgCharTest(devBox: LinearLayout) {
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        val status = TextView(this).apply { text = "캐릭터 시험: 펼치면 그려요"; textSize = 20f; setTextColor(Readability.TEXT_COLOR) }
        svgStatus = status
        val title = TextView(this).apply {
            text = "▶ 캐릭터 시험(가안, 개발용)"; textSize = 24f; setTextColor(Readability.TEXT_COLOR)
            minimumHeight = dp(76); gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                svgOpen = !svgOpen
                text = if (svgOpen) "▼ 캐릭터 시험(가안, 개발용)" else "▶ 캐릭터 시험(가안, 개발용)"
                host.visibility = if (svgOpen) android.view.View.VISIBLE else android.view.View.GONE
                if (svgOpen && svgView == null && !destroyed) {
                    val sv = SvgCharacterView(this@MainActivity, 360)
                    svgView = sv
                    host.addView(sv, 0, LinearLayout.LayoutParams(dp(360), dp(360)))
                    log("[캐릭터] 부품 ${sv.parsedOk}개 파싱 성공/실패 ${sv.parsedFail}개")
                    refreshSvgStatus()
                }
            }
        }
        val minPx = maxOf(dp(Readability.MIN_TOUCH_DP), dp(76))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shapes = listOf("닫힘" to MouthShape.CLOSED, "작게" to MouthShape.SMALL, "크게" to MouthShape.WIDE, "미소" to MouthShape.SMILE)
        for ((label, shape) in shapes) {
            val b = Button(this).apply {
                text = label
                setOnClickListener { svgView?.mouth = shape; refreshSvgStatus() }
            }
            styleControl(b, Readability.CONTROL_SP, minPx)
            btnRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        host.addView(btnRow)
        host.addView(status)
        devBox.addView(title)
        devBox.addView(host)
    }

    private var scrollPending = false  // 메인 스레드 전용: 스크롤 요청이 이미 예약돼 있으면 다시 예약하지 않는다

    private fun log(msg: String) {
        val now = System.currentTimeMillis()   // 시각만 콜백 스레드에서 읽고, 포맷은 메인 스레드(ui.post)에서 한다
        Log.i(tag, msg)
        ui.post {
            val line = "${fmt.format(Date(now))} $msg"
            logView.append(line + "\n")
            trimLog()
            if (!scrollPending) {
                scrollPending = true
                scroll.post { scrollPending = false; scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    /** 메인 스레드 전용. 로그 뷰 전체 텍스트가 상한을 넘으면 앞부분을 줄 단위로 잘라낸다 */
    private fun trimLog() {
        val t = logView.text
        val len = t.length
        if (len <= LOG_MAX_CHARS) return
        val nl = t.indexOf('\n', len - LOG_MAX_CHARS)
        val cut = if (nl < 0) len else nl + 1
        if (t is android.text.Editable) t.delete(0, cut) else logView.text = t.subSequence(cut, len)
    }

    private fun area(v: SeatValue) = SeatArea(v)

    private fun readAll() {
        val v = vehicle ?: return log("vehicle 없음")
        // 구독이 죽어 있으면 재등록을 먼저 하고 그다음에 조회한다(메인 스레드에서 호출됨)
        if (!gearSubscribed) registerGear()
        var reSubscribed = false
        for ((name, sv) in seats) {
            if (beltSubscribed[name] != true) { registerBeltSeat(name, sv); reSubscribed = true }
            if (occSubscribed[name] != true) { registerOccSeat(name, sv); reSubscribed = true }
        }
        if (reSubscribed) refreshBoard()
        val rid = ++readSeq
        val gearReq = gearEventSeq
        log("--- 현재값 읽기 ---")
        v.seatApi.checkSeatBeltBuckledCapabilities(
            { areas: List<SeatArea>? ->
                log("[CAP] 벨트 체결 지원 좌석 = ${areas?.map { a -> a.values.joinToString { it.name } }}")
                if (areas != null) onBeltCaps(areas.flatMap { a -> a.values.map { it.name } }.toSet())
            },
            { e: Exception? -> log("[CAP] 벨트 체결 지원 확인 실패: ${e?.javaClass?.simpleName}: ${e?.message}") }
        )
        v.seatApi.checkSeatOccupancyCapabilities(
            { areas: List<SeatArea>? ->
                log("[CAP] 점유 지원 좌석 = ${areas?.map { a -> a.values.joinToString { it.name } }}")
                if (areas != null) onOccCaps(areas.flatMap { a -> a.values.map { it.name } }.toSet())
            },
            { e: Exception? -> log("[CAP] 점유 지원 확인 실패: ${e?.javaClass?.simpleName}: ${e?.message}") }
        )
        for ((name, sv) in seats) {
            val beltReq = beltEventSeq[name] ?: 0
            val occReq = occEventSeq[name] ?: 0
            v.seatApi.getCurrentSeatBeltBuckledState(area(sv),
                { b: Boolean? -> log("[GET] $name 벨트 체결 = $b"); onBeltGet(name, rid, beltReq, b) },
                { e: Exception? -> log("[GET] $name 벨트 체결 실패: ${e?.javaClass?.simpleName}: ${e?.message}"); onBeltGetFailed(name, rid) })
            v.seatApi.getCurrentSeatOccupancy(area(sv),
                { b: Boolean? -> log("[GET] $name 점유 = ${SeatOccPolarity.logText(b)}"); onOccGet(name, rid, occReq, SeatOccPolarity.fromSdk(b)) },
                { e: Exception? -> log("[GET] $name 점유 실패: ${e?.javaClass?.simpleName}: ${e?.message}") })
            v.safetyApi.hasSeatBeltNotBuckledWarning(area(sv),
                { b: Boolean? -> log("[GET] $name 미체결 경고 = $b") },
                { e: Exception? -> log("[GET] $name 미체결 경고 실패: ${e?.javaClass?.simpleName}: ${e?.message}") })
        }
        v.safetyApi.checkTelltalesCapabilities(
            { c: ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig? -> log("[CAP] 경고등 지원: $c") },
            { e: Exception? -> log("[CAP] 경고등 지원 확인 실패: ${e?.javaClass?.simpleName}: ${e?.message}"); ui.post { onTelltaleFailed("지원 확인 실패") } })
        v.safetyApi.getTelltalesInfo(
            { tt: ai.pleos.playground.vehicle.api.model.telltale.Telltales? -> logTelltales("TT-GET", tt) },
            { e: Exception? -> log("[TT-GET] 실패: ${e?.javaClass?.simpleName}: ${e?.message}") })
        readDoors(v, rid)   // r6 4절: 뒷문 구독 재등록·지원 확인·현재값 재조회
        v.gearApi.getCurrentGearSelectionState(
            { g: VehicleGear? -> log("[GET] 기어 = ${g?.name}" + GateLogTag.request(rid, gearReq)); onGearGet(rid, gearReq, g?.name, g != null) },
            { e: Exception? -> log("[GET] 기어 실패: ${e?.javaClass?.simpleName}: ${e?.message}" + GateLogTag.request(rid, gearReq)); onGearGetFailed(rid, gearReq) })
    }

    /** 메인 스레드 전용. 같은 리스너를 먼저 해제한 뒤 등록하므로 중복 등록되지 않는다. 예외면 플래그 false·LINK_LOST */
    private fun registerBeltSeat(name: String, sv: SeatValue) {
        val v = vehicle ?: return
        val l = beltListeners[name] ?: object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) { onBeltEvent(name, p0) }
            override fun onFailed(e: Exception) { log("[EVT-ERR] $name 벨트 리스너 실패: ${e.javaClass.simpleName}: ${e.message}"); onBeltSubFailed(name, "onFailed") }
        }.also { beltListeners[name] = it }
        if (name in beltRegistered) { runCatching { v.seatApi.unregisterSeatBeltBuckledState(area(sv), l) }; beltRegistered.remove(name) }
        try {
            v.seatApi.registerSeatBeltBuckledState(area(sv), l)
            beltRegistered.add(name); beltSubscribed[name] = true
            if (beltSubFailed.remove(name)) { realBelt.remove(name); log("[REG] $name 벨트 구독 재등록(현재값 재조회 대기)") }
        } catch (t: Throwable) {
            beltSubscribed[name] = false; beltSubFailed.add(name); realBelt[name] = null
            log("[REG] $name 벨트 구독 예외: ${t.javaClass.simpleName}: ${t.message} → 확인 불가(연결 단절)")
        }
    }

    private fun registerOccSeat(name: String, sv: SeatValue) {
        val v = vehicle ?: return
        val l = occListeners[name] ?: object : SeatOccupancyListener {
            // ISSUE-01: SDK 2.3.2는 VACANT(1)를 true로 준다 → SDK 경계에서만 뒤집는다(SeatOccPolarity 참고)
            override fun onSeatOccupancyUpdated(p0: Boolean?) { onOccEvent(name, SeatOccPolarity.fromSdk(p0), "SDK $p0") }
            override fun onFailed(e: Exception) { log("[EVT-ERR] $name 점유 리스너 실패: ${e.javaClass.simpleName}: ${e.message}"); onOccSubFailed(name, "onFailed") }
        }.also { occListeners[name] = it }
        if (name in occRegistered) { runCatching { v.seatApi.unregisterSeatOccupancy(area(sv), l) }; occRegistered.remove(name) }
        try {
            v.seatApi.registerSeatOccupancy(area(sv), l)
            occRegistered.add(name); occSubscribed[name] = true
            if (occSubFailed.remove(name)) { occupied.remove(name); log("[REG] $name 점유 구독 재등록(현재값 재조회 대기)") }
        } catch (t: Throwable) {
            occSubscribed[name] = false; occSubFailed.add(name); occupied[name] = null
            log("[REG] $name 점유 구독 예외: ${t.javaClass.simpleName}: ${t.message} → 확인 불가(연결 단절)")
        }
    }

    // ---- 2026-11-15 r6 4절(개발 D): 뒷문 열림 신호(읽기만: check·get·register, set 없음). 판정은 ExitDoorRule ----
    // 권한 pleos.car.permission.CAR_DOORS(매니페스트). 미지원·권한 거부·읽기 실패·구독 단절이면 그 문은 확인 불가(DoorPos.UNKNOWN)
    // → 결정 46: 착석만으로 판단. 확인 불가를 열림·닫힘으로 바꾸지 않는다. 문 구독 실패는 잠금·재연결 판단(evaluateLink)에 넣지 않는다
    // (권한이 없는 환경에서 재시도가 끝없이 돌지 않게). 구독·재연결 때 readAll이 현재값을 다시 읽고, onDestroy에서 해제한다.
    private val doors: List<Pair<String, ai.pleos.playground.vehicle.constant.area.DoorValue>> = listOf(
        DoorSignal.REAR_LEFT to ai.pleos.playground.vehicle.constant.area.DoorValue.ROW_2_LEFT,
        DoorSignal.REAR_RIGHT to ai.pleos.playground.vehicle.constant.area.DoorValue.ROW_2_RIGHT,
    ) + (if (ExitDoorRule.FRONT_PASSENGER_TARGET)   // r10d B: 조수석 문(DoorValue.ROW_1_RIGHT, VC door_fr) — 확인 때 조수석에 앉은 신호가 있었던 이동의 하차 마무리 판단용
        listOf(DoorSignal.FRONT_RIGHT to ai.pleos.playground.vehicle.constant.area.DoorValue.ROW_1_RIGHT) else emptyList())
    private val doorRaw = mutableMapOf<String, Int?>()                 // 마지막 SDK 원시값(메인 스레드 전용)
    private val doorSubscribed = mutableMapOf<String, Boolean>()
    private val doorRegistered = mutableSetOf<String>()
    private val doorReadFailed = mutableSetOf<String>()
    private val doorEventSeq = mutableMapOf<String, Int>()
    private val doorListeners = mutableMapOf<String, ai.pleos.playground.vehicle.listener.DoorPositionListener>()
    private var supportedDoor: Set<String>? = null                     // null = 아직 모름(미지원으로 보지 않음)

    private fun doorArea(dv: ai.pleos.playground.vehicle.constant.area.DoorValue) = ai.pleos.playground.vehicle.api.model.area.DoorArea(dv)

    /** 문 하나의 지금 상태(연결·지원·읽기 실패 포함) */
    private fun doorSignal(name: String): DoorPos =
        if (vehicle == null) DoorPos.UNKNOWN
        else DoorSignal.of(supportedDoor?.contains(name), doorSubscribed[name] == true, name in doorReadFailed, doorRaw[name])

    /** 문 상태를 판정 보관소에 넣는다(바뀌었을 때만 로그). 열림 기록이 생기면 하차 마무리를 다시 본다(자리가 먼저 비고 문이 나중에 열리는 경우) */
    private fun syncDoor(name: String, why: String) {
        val before = ExitDoorStore.state
        val pos = doorSignal(name)
        ExitDoorStore.state = ExitDoorRule.onDoor(before, name, pos)
        if (before.doors[name] != pos) log("[문] $name = ${pos.name} ($why)" + if (ExitDoorStore.state.openedThisStop != before.openedThisStop) " → 이번 정차 뒷문 열림 기록" else "")
        if (ExitDoorStore.state.openedThisStop != before.openedThisStop) renderStageOnly()
        requestAutoEval()
    }

    private fun onDoorEvent(name: String, raw: Int?) {
        ui.post {
            if (destroyed) return@post
            doorEventSeq[name] = (doorEventSeq[name] ?: 0) + 1
            doorRaw[name] = raw; doorReadFailed.remove(name)
            syncDoor(name, "이벤트 ${DoorSignal.logText(raw)}")
        }
    }
    private fun onDoorGet(name: String, reqRead: Int, reqEvent: Int, raw: Int?) {
        ui.post {
            if (destroyed || reqRead != readSeq || (doorEventSeq[name] ?: 0) != reqEvent) { Log.i(tag, "오래된 문 GET 버림 $name"); return@post }
            if (doorSubscribed[name] != true) { Log.i(tag, "문 구독 단절 중 GET 무시 $name"); return@post }
            doorRaw[name] = raw; doorReadFailed.remove(name)
            syncDoor(name, "현재값 ${DoorSignal.logText(raw)}")
        }
    }
    private fun onDoorGetFailed(name: String, reqRead: Int) {
        ui.post { if (destroyed || reqRead != readSeq) return@post; doorReadFailed.add(name); syncDoor(name, "읽기 실패") }
    }
    private fun onDoorSubFailed(name: String, why: String) {
        ui.post {
            if (destroyed) return@post
            doorSubscribed[name] = false; doorRegistered.remove(name); doorRaw[name] = null
            syncDoor(name, "구독 끊김($why) → 확인 불가")
        }
    }
    private fun onDoorCaps(names: Set<String>) {
        ui.post { if (destroyed) return@post; supportedDoor = names; for ((n, _) in doors) syncDoor(n, "지원 목록") }
    }

    /** 메인 스레드 전용. 같은 리스너를 먼저 해제한 뒤 등록(중복 없음). 예외(권한 거부 포함)면 그 문은 확인 불가 */
    private fun registerDoor(name: String, dv: ai.pleos.playground.vehicle.constant.area.DoorValue) {
        val v = vehicle ?: return
        val l = doorListeners[name] ?: object : ai.pleos.playground.vehicle.listener.DoorPositionListener {
            override fun onPositionUpdated(p0: Int?) { onDoorEvent(name, p0) }
            override fun onFailed(e: Exception) { log("[EVT-ERR] $name 문 리스너 실패: ${e.javaClass.simpleName}: ${e.message}"); onDoorSubFailed(name, "onFailed") }
        }.also { doorListeners[name] = it }
        if (name in doorRegistered) { runCatching { v.doorApi.unregisterDoorPosition(doorArea(dv), l) }; doorRegistered.remove(name) }
        try {
            v.doorApi.registerDoorPosition(doorArea(dv), l)
            doorRegistered.add(name); doorSubscribed[name] = true
        } catch (t: Throwable) {
            doorSubscribed[name] = false; doorRaw[name] = null
            log("[REG] $name 문 구독 예외: ${t.javaClass.simpleName}: ${t.message} → 확인 불가(결정 46: 착석만으로 판단)")
        }
        syncDoor(name, "구독")
    }

    /** readAll에서 부름: 끊긴 문 구독은 다시 등록하고, 지원 확인·현재값을 다시 읽는다 */
    private fun readDoors(v: Vehicle, rid: Int) {
        for ((name, dv) in doors) if (doorSubscribed[name] != true) registerDoor(name, dv)
        try {
            v.doorApi.checkDoorPositionCapabilities(
                { m: Map<ai.pleos.playground.vehicle.api.model.area.DoorArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>? ->
                    log("[CAP] 문 위치 지원 = ${m?.map { (a, b) -> a.values.joinToString { it.name } + "(${b.minValue}~${b.maxValue})" }}")
                    if (m != null) onDoorCaps(m.keys.flatMap { a -> a.values.map { it.name } }.toSet())
                },
                { e: Exception? -> log("[CAP] 문 위치 지원 확인 실패: ${e?.javaClass?.simpleName}: ${e?.message}") })
        } catch (t: Throwable) { log("[CAP] 문 위치 지원 확인 예외: ${t.javaClass.simpleName}: ${t.message}") }
        for ((name, dv) in doors) {
            val req = doorEventSeq[name] ?: 0
            try {
                v.doorApi.getCurrentDoorPosition(doorArea(dv),
                    { p: Int? -> log("[GET] $name 문 위치 = ${DoorSignal.logText(p)}"); onDoorGet(name, rid, req, p) },
                    { e: Exception? -> log("[GET] $name 문 위치 실패: ${e?.javaClass?.simpleName}: ${e?.message}"); onDoorGetFailed(name, rid) })
            } catch (t: Throwable) {
                log("[GET] $name 문 위치 예외: ${t.javaClass.simpleName}: ${t.message} → 확인 불가")
                doorReadFailed.add(name); syncDoor(name, "읽기 예외")
            }
        }
    }

    /** onDestroy에서 부름: 문 리스너 해제, 보관소의 문 상태는 확인 불가로(이번 정차 열림 기록은 유지) */
    private fun releaseDoors(v: Vehicle) {
        for ((name, dv) in doors) doorListeners[name]?.let { runCatching { v.doorApi.unregisterDoorPosition(doorArea(dv), it) } }
        doorRegistered.clear()
        ExitDoorStore.state = ExitDoorRule.forgetDoors(ExitDoorStore.state)
    }

    /** 기어 변화(applyGear): 주행이면 이번 정차 문 열림 기록 지움, 유효 P로 들어오면 기록 시작 */
    private fun doorGear(name: String?, ok: Boolean, subFailed: Boolean) {
        val before = ExitDoorStore.state
        ExitDoorStore.state = ExitDoorRule.onGear(before, BeltReleaseRule.isDriving(name, ok, subFailed), BeltReleaseRule.isValidPark(name, ok, subFailed))
        val after = ExitDoorStore.state
        if (before.stopActive != after.stopActive || before.openedThisStop != after.openedThisStop)
            log("[문] 정차 기록 ${if (after.stopActive) "시작(유효 P)" else "지움(주행)"} 열림=${after.openedThisStop}")
    }

    /**
     * **r6 4절 공개 진입점(개발 A 5절 중간 하차도 이것을 부른다)**: 배정한 뒷줄 자리 [seatsToCheck]가 모두 '내렸다'인가.
     * '내렸다' = 착석 신호 EMPTY + 이번 정차 뒷문 열림(결정 45: 어느 쪽이든). 문 확인 불가면 착석만(결정 46). 판정은 [ExitDoorRule.allLeft].
     * 마무리를 실제로 했으면 [ExitDoorRule.onFinale]로 기준을 남긴다(계약 exitByDoor).
     */
    /** '자리는 비었는데 문 열림 없음' 로그를 상태가 바뀔 때만 남기기 위한 표시 */
    private var lastExitDoorWait = false

    private fun exitAllLeft(seatsToCheck: Collection<String>): ExitDoorVerdict =
        ExitDoorRule.allLeft(ExitDoorStore.state, seatsToCheck, seatsToCheck.associateWith { occSignal(it).first })

    // ---- 기어 수신 감시(2026-11-07 Unit 5.1 N3: 조용한 단절·응답 시간 초과). 메인 스레드 전용 ----
    private var gearWatch = GearWatchState()
    private val gearWatchRunnable = Runnable { gearWatchTick() }

    private fun scheduleGearWatch() {
        ui.removeCallbacks(gearWatchRunnable)
        if (!destroyed && (resumed || beltWarn.active)) ui.postDelayed(gearWatchRunnable, GearWatchdog.INTERVAL_MS)
    }

    private fun stopGearWatch() {
        ui.removeCallbacks(gearWatchRunnable)
        gearWatch = GearWatchdog.stop(gearWatch)
    }

    private fun gearWatchTick() {
        if (destroyed || !(resumed || beltWarn.active)) return
        val v = vehicle
        if (v == null || !subAttempted || mockGearOn) { gearWatch = GearWatchdog.stop(gearWatch); scheduleGearWatch(); return }
        val now = SystemClock.elapsedRealtime()
        val (next, action) = GearWatchdog.tick(gearWatch, now)
        gearWatch = next
        when (action) {
            GearWatchAction.NONE -> {}
            GearWatchAction.SEND_PROBE -> {
                gearWatch = GearWatchdog.sent(gearWatch, now)
                val token = gearWatch.lastToken
                val rid = readSeq
                val ev = gearEventSeq
                try {
                    v.gearApi.getCurrentGearSelectionState(
                        { g: VehicleGear? -> ui.post { if (!destroyed) gearWatch = GearWatchdog.answered(gearWatch, token) }; onGearGet(rid, ev, g?.name, g != null) },
                        { _: Exception? -> ui.post { if (!destroyed) gearWatch = GearWatchdog.answered(gearWatch, token) }; onGearGetFailed(rid, ev) })
                } catch (t: Throwable) {
                    log("[LINK] 기어 감시 요청 예외: ${t.javaClass.simpleName}")
                }
            }
            GearWatchAction.TIMED_OUT -> {
                log("[LINK] 기어 응답 없음(${GearWatchdog.TIMEOUT_MS}ms) → 수신 끊김으로 보고 잠금, 재연결 시도")
                gearEventSeq++   // 진행 중 GET 응답을 버리게 한다
                gearSubscribed = false
                if (realGearAccepted()) applyGear(null, false, true)
                evaluateLink()
            }
        }
        beltWatchTick(v, now)
        scheduleGearWatch()
    }

    // ---- 벨트 구독 조용한 단절 감시(2026-11-08 Unit 5.3 P1-4). 감시 세션 중 armed 좌석만, 기어 감시와 같은 주기·규칙(GearWatchdog) ----
    // 응답(성공·실패)이 상한 안에 없으면 그 좌석 구독을 단절로 보고(LINK_LOST, 감시 끊김 기록) 기존 재연결 경로를 탄다.
    // 벨트 이벤트 도착도 살아 있다는 근거로 인정한다. 응답 값은 onBeltGet(재조회 합치기 규칙)으로 들어간다.
    private val beltWatch = mutableMapOf<String, GearWatchState>()

    private fun beltWatchTick(v: Vehicle, now: Long) {
        if (!beltWarn.active || useSim) { if (beltWatch.isNotEmpty()) beltWatch.replaceAll { _, w -> GearWatchdog.stop(w) }; return }
        for (name in beltWarn.armed) {
            val sv = seats.firstOrNull { it.first == name }?.second ?: continue
            if (beltSubscribed[name] != true) continue   // 이미 단절 표시: 재연결 경로가 맡는다
            val (next, action) = GearWatchdog.tick(beltWatch[name] ?: GearWatchState(), now)
            beltWatch[name] = next
            when (action) {
                GearWatchAction.NONE -> {}
                GearWatchAction.SEND_PROBE -> {
                    val w = GearWatchdog.sent(next, now); beltWatch[name] = w
                    val token = w.lastToken; val rid = readSeq; val ev = beltEventSeq[name] ?: 0
                    try {
                        v.seatApi.getCurrentSeatBeltBuckledState(area(sv),
                            { b: Boolean? -> ui.post { if (!destroyed) beltWatch[name]?.let { beltWatch[name] = GearWatchdog.answered(it, token) } }; onBeltGet(name, rid, ev, b) },
                            { _: Exception? -> ui.post { if (!destroyed) beltWatch[name]?.let { beltWatch[name] = GearWatchdog.answered(it, token) } }; onBeltGetFailed(name, rid) })
                    } catch (t: Throwable) { log("[LINK] $name 벨트 감시 요청 예외: ${t.javaClass.simpleName}") }
                }
                GearWatchAction.TIMED_OUT -> {
                    log("[LINK] $name 벨트 응답 없음(${GearWatchdog.TIMEOUT_MS}ms) → 구독 단절로 보고 재연결 시도")
                    onBeltSubFailed(name, "응답 없음")
                }
            }
        }
    }

    private fun registerAll() {
        val v = vehicle ?: return
        subAttempted = true
        log("--- 변화 구독 등록 ---")
        for ((name, sv) in seats) {
            val warn = object : SeatBeltNotBuckledWarningListener {
                override fun onSeatBeltNotBuckledWarningUpdated(p0: Boolean?) { log("[EVT] $name 미체결 경고 -> $p0") }
                override fun onFailed(e: Exception) { log("[EVT-ERR] $name 경고 리스너 실패: ${e.javaClass.simpleName}: ${e.message}") }
            }
            warnListeners[name] = warn
            registerBeltSeat(name, sv)
            registerOccSeat(name, sv)
            runCatching { v.safetyApi.registerSeatBeltNotBuckledWarning(area(sv), warn) }.onFailure { log("[REG] $name 경고 구독 예외: ${it.message}") }
        }
        refreshBoard()
        val tl = object : ai.pleos.playground.vehicle.listener.TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(p0: ai.pleos.playground.vehicle.api.model.telltale.Telltales?) { logTelltales("TT-EVT", p0) }
            override fun onFailed(e: Exception) { log("[TT-EVT-ERR] ${e.javaClass.simpleName}: ${e.message}"); ui.post { onTelltaleFailed("구독 실패") } }
        }
        telltaleListener = tl
        runCatching { v.safetyApi.registerTelltales(tl) }.onFailure { log("[REG] 경고등 구독 예외: ${it.message}"); ui.post { onTelltaleFailed("구독 예외") } }
        for ((name, dv) in doors) registerDoor(name, dv)   // r6 4절: 뒷문 열림(읽기만)
        registerGear()
        log("구독 등록 요청 완료. 콘솔에서 값을 바꾸면 [EVT] 줄이 나타나야 한다")
    }

    /** 화면을 떠나면 보호자 확인을 취소한다(타이머·STT 정리). 돌아와도 이전 질문은 부활하지 않는다 */
    // ---- 2026-11-08 P1-A 실행 중 야간 모드 전환 ----
    // Manifest가 uiMode를 직접 처리(재생성 안 함)하는데 색은 화면 생성 시점에 고정돼 있어 전환이 반영되지 않았다.
    // 최소 수정: 야간 값이 실제로 바뀐 경우에만, 안전한 시점(유효 P + 정차 허용, 보호자 확인 IDLE, TTS·STT·주행 경고·하차 한 줄 진행 없음)에
    // Activity를 한 번 다시 만든다. 주행·발화 중이면 미뤘다가 조건이 되면 적용한다(주행 경고·안내를 끊지 않음).
    // 다시 만들어도 벨트 감시 세션(BeltSessionStore)·이동 세션(TripSessionStore)은 유지되고, 보호자 확인은 자동 재개하지 않는다.
    private var nightPending = false
    /**
     * v3 반복 6(P0): 창 폭이 무대 2영역 경계(1600dp)를 넘어 바뀌었는데 무대가 이전 배치로 만들어져 있음.
     * screenSize 변경은 Activity를 다시 만들지 않으므로(configChanges) 야간 전환과 같은 안전 시점 규칙으로 한 번 다시 만든다.
     */
    private var layoutPending = false

    /**
     * v3 반복 6(P0): 무대 폭이 2영역 등급 경계(1600dp)를 넘었는지 본다. 넘었으면 안전한 시점에 Activity를 다시 만든다.
     * 실제 무대 폭(onSizeChanged)과 구성 변경 두 경로에서 부른다(작은 폭 변화는 구성 변경이 전달되지 않을 수 있음).
     */
    private fun checkStageTier(wDp: Float, applyNow: Boolean = true) {
        val st = stageScreen ?: return
        // 재생성 직후 첫 확인에서만 '같은 폭 재불일치(반복)'를 본다. 한 번 보면 지운다(나중에 같은 폭을 다시 지나는 것은 정상 재생성)
        val guardW = StageRebuildGuard.lastWidthDp
        StageRebuildGuard.lastWidthDp = -1
        if (StageSplit.needsRebuild(st.builtSplit, wDp)) {
            // 같은 폭으로 이미 한 번 다시 만들었는데 또 어긋나면(화면 정보가 늦게 오는 경우) 반복하지 않는다: 현재 배치 + 실측 맞춤(스크롤)으로 유지
            if (guardW == wDp.toInt()) { if (!layoutPending) log("[무대] 창 폭 ${wDp.toInt()}dp 재생성 후에도 등급 불일치 → 반복하지 않고 현재 배치 유지"); return }
            if (!layoutPending) log("[무대] 창 폭 ${wDp.toInt()}dp: 2영역 ${if (st.builtSplit) "해제" else "적용"} 필요 → 안전한 시점에 화면 다시 만들기")
            layoutPending = true
            if (applyNow) ui.post { maybeApplyNight() }
        } else if (layoutPending) {
            layoutPending = false; log("[무대] 창 폭이 원래 등급으로 돌아옴 → 다시 만들기 취소")
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        checkStageTier(dm.widthPixels / dm.density, applyNow = false)
        val night = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!NightSwitchRule.pendingAfterConfig(night, Palette.current.night)) {
            if (nightPending) { nightPending = false; log("[야간] 원래 값으로 돌아옴 → 적용 취소") }
            if (layoutPending) maybeApplyNight()
            return
        }
        nightPending = true
        log("[야간] 시스템 ${if (night) "야간" else "주간"} 전환 감지 → 안전한 시점에 화면 다시 그림" +
            if (beltWarn.active) " (벨트 감시 중: 세션 종료 뒤 적용)" else "")
        maybeApplyNight()
    }

    /** 미뤄 둔 야간 전환을 지금 적용해도 되는지 보고, 되면 Activity를 다시 만든다 */
    private fun maybeApplyNight() {
        if (!(nightPending || layoutPending) || destroyed || isFinishing) return
        val t = tts
        val safe = NightSwitchRule.canApply(NightSwitchRule.Inputs(
            pending = nightPending || layoutPending, destroyed = destroyed, finishing = isFinishing, resumed = resumed,
            locked = lockReason() != null, parkAllowed = currentGate() == VehicleGate.PARK_ALLOWED,
            guardianIdle = gState == GState.IDLE, voiceActive = voiceActive, ttsBusy = t != null && t.busy,
            beltSessionActive = beltWarn.active,   // 2026-11-09: 감시 세션 중에는 재생성 보류(경고 발화가 없어도)
            beltWarningInFlight = beltWarn.inFlight != null, exitLinePending = exitCheck.pendingGen >= 0,
            confirmOpen = finishConfirm != null, customizeOpen = customizeOpen,
        ))
        if (!safe) return
        if (layoutPending) StageRebuildGuard.lastWidthDp = stageScreen?.let { (it.width / resources.displayMetrics.density).toInt() } ?: -1
        log("[${if (nightPending) "야간" else "무대"}] 적용: 화면 다시 만들기(세션 유지, 보호자 확인 자동 재개 없음)")
        nightPending = false
        layoutPending = false
        recreate()
    }

    override fun onStart() {
        super.onStart()
        started = true
        AppVisibleStore.visible = true   // r6 QA 후속: debug 부팅 시험 등이 '이미 보임'을 알 수 있게(프로세스 값)
        if (destroyed) return
        // 2026-11-13 PM 결정 13(ISSUE-06): 보이기 시작하면(onStart) 기어 재조회와 화면 복원을 한다.
        // 차량 화면은 '보이지만 resume 안 됨'(후방 카메라가 닫히는 동안 등)으로 오래 남을 수 있어 onResume만 기다리지 않는다.
        // 잠금(S09)은 그대로 이긴다: 재조회 전에는 기어를 오래된 값으로 보고 잠금, D/R/N·불명이면 무대가 잠금 장면을 그린다
        refreshGateOnReturn()
        restoreScreen(ScreenReturnRule.Event.START)
        if (initDone && vehicle == null) { log("[VEHICLE] 화면 보임 → Vehicle 없음, 다시 초기화 시도"); ui.removeCallbacks(vehicleRetryRunnable); ui.post(vehicleRetryRunnable) }   // ❌
        syncKeepAlive("화면 보임")   // 2026-11-13 ISSUE-07: 이동 중인데 서비스가 꺼져 있으면(앞에서 시작 못 함) 보일 때 시작
        if (TripKeepAliveService.showNotice(this, arrived = false)) log("[앞으로] 앱 보임 → 이동 알림 제목 되돌림")   // 루프 r5 1절 대체 알림 정리
        scheduleReleaseRepeat()   // 결정 31: 재생성 뒤에도 남은 다시 알림을 이어 간다(멱등)
    }

    /** ch 승인 조건 ③④: 화면 복귀 때 이전 기어·플랫폼 값은 오래된 것으로 보고 다시 받는다(받기 전에는 잠금) */
    private fun refreshGateOnReturn() {
        gateEpoch++
        resumeElapsedMs = SystemClock.elapsedRealtime()
        ui.removeCallbacks(graceEndRefresh); ui.postDelayed(graceEndRefresh, STARTUP_GRACE_MS + 1)   // 재조회 유예가 끝나면 문구 한 번 갱신
        if (vehicle != null && subAttempted && !inLinkRetry) readAll()
        platformSource?.requery()
        onGateInputsChanged()
    }

    /** onStart~onStop 사이에 onStart가 화면을 이미 복원했는지(같은 주기의 onResume이 홈으로 한 번 더 튕기지 않게) */
    private var restoredThisStart = false

    /** 결정 13: 복귀 화면 복원(무대를 아래에 두고 홈 또는 무대 유지). 어떤 경우에도 머리 막대 없는 설정 바닥을 남기지 않는다 */
    private fun restoreScreen(event: ScreenReturnRule.Event) {
        if (destroyed || !StageFlags.NEW_STAGE) return
        val plan = ScreenReturnRule.restoreOn(event, restoredThisStart, keepStageOnce)
        if (plan == ScreenReturnRule.Restore.NONE) return
        consumeSettingsExternal()   // ④: 설정에서 알림 권한·시스템 알림 설정에 다녀왔으면 잠금이 풀린 뒤 설정을 다시 연다
        hideOldFlowViews()
        if (plan == ScreenReturnRule.Restore.KEEP_STAGE) {
            // v4.1: 야간 전환 등 구성 변경 재생성 직후 → 홈으로 튕기지 않고 무대(좌석 화면)를 유지. 보호자 확인은 IDLE(다시 묻기)
            keepStageOnce = false
            // 2026-11-13 계약 v2: 확인 흐름은 재생성 때 사라지므로(HOME) 이동 단계가 없으면 옛 선택 화면 대신 홈을 보인다(다시 [시작])
            val keep = currentAutoPhase() != null || CheckSessionStore.saved != null   // 결정 20: 확인을 이어 갈 예정이면 무대 유지
            showHome(); if (keep) homeActive = false; syncHome()
            log(if (keep) "[복원] 재생성 후 무대 유지(이동 단계 화면)" else "[복원] 재생성 후 확인 흐름 없음 → 홈")
        } else showHome()
        if (event == ScreenReturnRule.Event.START) restoredThisStart = true
        var layer = currentLayer()
        if (layer == ScreenReturnRule.Layer.BARE_SETTINGS) {   // 안전망: 무대가 아직 없거나 숨겨져 있으면 무대를 연다(잠금이면 잠금 장면)
            openStagePreview()
            layer = currentLayer()
        }
        log("[수명] 화면 복원(${if (event == ScreenReturnRule.Event.START) "onStart" else "onResume"}) → 층=$layer")
    }

    private fun currentLayer(): ScreenReturnRule.Layer = ScreenReturnRule.layer(
        stageVisible = stageScreen?.visibility == android.view.View.VISIBLE,
        homeVisible = homeScreen?.visibility == android.view.View.VISIBLE,
        customizeVisible = customizeScreen?.visibility == android.view.View.VISIBLE,
        settingsOpen = settingsOpen,
        locked = lockReason() != null,
    )

    override fun onResume() {
        super.onResume()
        resumed = true
        foldsAtPause = 0   // v3(P2)
        settingsOpenAtPause = false   // 2026-11-11 U5: 지난 이탈의 설정 상태가 다음 재생성에 잘못 쓰이지 않게
        if (!destroyed) devTools.onResume()   // debug 모의 이동 이어서(release는 없음)
        if (!destroyed && settingsOpen) refreshNotifSetting()   // 시스템 알림 설정·권한 창에서 돌아왔을 때 상태 글자 갱신
        syncGl()  // 3D 시험: 펼쳐져 있고 잠금이 아닐 때만 재개
        scrollOuterToTop()
        if (!destroyed) evaluateLink()  // 아직 실패 상태면 다시 예약
        if (!destroyed) scheduleGearWatch()   // 조용한 단절 감시 재개
        if (!destroyed) {
            // 결정 13: 같은 시작 주기에 onStart가 이미 재조회했으면 다시 오래된 값으로 돌리지 않는다(전경 여부만 바뀌었으니 게이트만 다시 평가)
            // r6a 2절: 보이는 채로 포커스만 돌아온 경우(onStop 없음)는 값을 오래된 것으로 돌리지 않는다(첫 누름이 '기어 확인 중'에 막히지 않게)
            if (restoredThisStart || !ScreenPresenceRule.staleOnResume(stoppedSinceResume)) onGateInputsChanged() else refreshGateOnReturn()
        }
        if (!destroyed && beltWarn.active) { log("[주행안내] 화면 복귀: 미처리 ${beltWarn.pending.size}건 진행중=${beltWarn.inFlight != null}"); runBeltEval() }
        restoreScreen(ScreenReturnRule.Event.RESUME)   // onStop 없이 onPause→onResume만 온 경우는 예전처럼 홈으로 복원
        restoredThisStart = false
        stoppedSinceResume = false   // r6a 2절
        syncHomeNotes()   // 무대가 기본 화면: 복귀하면 홈으로(예전 화면이 드러나지 않게)
    }

    override fun onStop() {
        log("[수명] onStop 감시세션=${beltWarn.active}")
        started = false
        AppVisibleStore.visible = false
        // r6 QA 후속: 예전 onPause에 있던 '화면 이탈' 처리. 화면이 실제로 안 보이게 된 때(onStop)만 확인 취소·하차 미룬 안내 취소
        // PM 결정 20: 재생성(야간·주간 전환·구성 변경)으로 떠나는 것이면, 아래 '화면 이탈' 취소 전에 확인 중 상태를 보관해 새 화면이 이어 가게 한다
        if (!destroyed && isChangingConfigurations) {
            CheckSessionStore.saved = CheckResumeRule.snapshotOf(flow, seatCheck, welcomeGen, SystemClock.elapsedRealtime())
            CheckSessionStore.saved?.let { log("[복원] 확인 중 상태 보관(배정 ${it.seatCheck.assigned.size}석, 재생성 뒤 이어 가기)") }
        }
        if (!destroyed && TripLeaveRule.keepOnLeave(flow.step, keepAliveOn)) {
            // 결정 24 ②③: 이동 중(FGS) 확인 완료 상태는 유지한다. 확인 완료 음성·이동 음성은 뒤에서 계속(포커스 손실·차량 경고는 TtsGuide 규칙대로)
            log("[수명] 화면 이탈 → 이동 중이라 확인 완료 상태 유지(단계=${flow.step}, 음성 계속, 결정 24)")
            if (exitCheck.waitingIdle) { exitCheck = ExitCheckRule.cancelWaiting(exitCheck, exitDrivingNow()); log("[하차확인] 화면 이탈 → 미룬 안내 취소(미전달, 소진 안 함)") }
            homeScreen?.setMotionGate(currentGate())
        } else if (!destroyed) {
            val wasIdle = gState == GState.IDLE
            if (!wasIdle) guardLog("화면 이탈 → 보호자 확인 취소")
            dispatchFlow(BoardingEvent.LeftForeground, "화면이탈")   // BoardingFlow 공통 취소(문구: 화면 이탈)
            if (exitCheck.waitingIdle) { exitCheck = ExitCheckRule.cancelWaiting(exitCheck, exitDrivingNow()); log("[하차확인] 화면 이탈 → 미룬 안내 취소(미전달, 소진 안 함)") }
            // 결정 13: 음성 정리(stopVoice·tts.stop)는 onStop으로 옮겼다(하차 한 줄 유지). 보이는 동안의 pause는 음성을 끊지 않는다
            homeScreen?.setMotionGate(currentGate())
        }
        stoppedSinceResume = true   // r6a 2절: 다음 onResume은 값을 다시 받는다
        ui.removeCallbacks(vehicleRetryRunnable)   // ❌ 다시 초기화는 화면이 보일 때만(onStart에서 다시)
        restoredThisStart = false
        if (!destroyed && gState == GState.IDLE) {
            // 2026-11-13 PM 결정 13(ISSUE-06): 화면 이탈 음성 정리는 onPause가 아니라 onStop에서 한다(보이는 동안은 이탈이 아니다).
            // 하차 한 줄·주행 벨트 안내·출발/도착 자동 안내는 남긴다(음성만, 오디오 포커스 규칙은 TtsGuide가 그대로 지킨다)
            stopVoice("화면 이탈"); stopArrivalListen("화면 이탈")
            tts?.stop(true, keepExample = TtsKeepRule.ON_STOP_KEEP_EXAMPLE, keepDrive = TtsKeepRule.ON_STOP_KEEP_DRIVE, keepExit = TtsKeepRule.ON_STOP_KEEP_EXIT)
            ui.removeCallbacks(askTimeout); ui.removeCallbacks(askStep); announceTtsGen = -1
        }
        super.onStop()
    }

    override fun onPause() {
        resumed = false
        devTools.onPause()   // debug 모의 내비 틱 취소(상태는 유지, onResume에서 이어서)
        glView?.onPause()   // 3D 시험: 그리기 중지
        stageScreen?.stopMotion()   // 무대 동작 콜백 제거
        homeScreen?.stopMotion()
        // 2026-11-13 PM 결정 13(ISSUE-06): 무대·홈·꾸미기·설정 머리 막대를 감추지 않는다. 차량 화면에서는 paused인 채로 보일 수 있고,
        // 감추면 바닥의 설정 레이어가 머리 막대 없이 드러났다. 복원은 onStart/onResume(restoreScreen)이 한다
        foldsAtPause = if (settingsOpen && ::childPanel.isInitialized) childPanel.foldsMask() else 0   // v3(P2)
        settingsOpenAtPause = settingsOpen   // 2026-11-11 U5: 재생성 복원용
        if (!beltWarn.active) cancelLinkRetryPending()   // 감시 세션 중에는 화면 밖에서도 재연결 예약 유지(재감사 2차)
        // 2026-11-08 F1: 화면 이탈은 스티커 이력을 지우지 않는다(같은 이동에서 복귀·재확인 시 중복 지급 방지, TripSessionStore)
        // 2026-11-08 Unit 5.3(N3): 화면 이탈은 주행 벨트 감시 세션을 끝내지 않는다(UI 수명주기와 분리).
        // 감시는 앱 프로세스가 살아 있는 동안만 이어진다(서비스 없음). 기어 수신 감시도 세션 중에는 계속한다
        if (!destroyed && beltWarn.active) log("[주행안내] 화면 이탈 → 감시 세션 유지(앱 프로세스가 살아 있는 동안만)")
        if (!beltWarn.active) stopGearWatch()
        // r6 QA 후속: '화면 이탈 → 확인 취소'는 onStop(실제로 안 보이게 됨)으로 옮겼다(ScreenPresenceRule.leftScreenOn). 보이는 채 잠깐 오는 onPause는 이탈이 아니다
        super.onPause()
    }

    override fun onDestroy() {
        log("[수명] onDestroy 종료중=$isFinishing 구성변경=$isChangingConfigurations 감시세션=${beltWarn.active}")
        // r10 N: 다시 그림(재생성)이면 tts.release() 전에 재생 중·대기 중 유지 음성을 새 화면으로 넘긴다. 끄면(스위치) r9b와 같다
        if (isChangingConfigurations && TtsGuide.KEEP_ACROSS_RECREATE) handOverKeepVoice()
        closeDevADialog()   // 알림 안내·앱 나가기 확인 창이 떠 있으면 닫는다(창 누수 방지)
        // Unit 5.3(N3): 같은 프로세스에서 Activity가 다시 만들어지면 새 Activity가 세션·미전달 기록을 이어받는다(BeltSessionStore).
        // 감사 3: 사용자가 앱을 끝내면(isFinishing) 세션도 끝낸다(다음 실행에 지난 이동의 감시가 살아나지 않게)
        // PM 결정 20: 보관은 onPause(재생성 판단 시점, 화면 이탈 취소 전)에서 한다. 앱 종료면 비운다
        if (isFinishing && !isChangingConfigurations) CheckSessionStore.saved = null
        if (isFinishing && !isChangingConfigurations) {
            if (beltWarn.active) endBeltReleaseSession("앱 종료")
            BeltSessionStore.take()
            stopKeepAlive("앱 종료")   // 2026-11-13 ISSUE-07: 앱을 끝내면 서비스·알림을 남기지 않는다
        } else {
            BeltSessionStore.save(BeltSessionSnapshot(beltWarn, beltMoved, beltUndelivered.toList(), beltMonitorGap, SystemClock.elapsedRealtime()))
            if (beltWarn.active) log("[주행안내] 화면 객체 종료 → 차량 리스너 해제, 이 프로세스 안의 다음 화면이 이어받음(그 사이 감시 끊김)")
        }
        destroyed = true
        glView?.onPause(); glView = null; glHost = null; glStatus = null   // 3D 시험 참조 정리
        svgView = null; svgStatus = null   // 캐릭터 시험 참조 정리
        stageScreen?.stopMotion()   // 무대 동작 콜백 제거
        stageScreen = null   // 무대 미리보기 참조 정리
        homeScreen = null; customizeScreen = null
        styleUnsubs.forEach { it() }; styleUnsubs.clear()   // 꾸미기 상태 구독 해제
        settingsBackBar = null; settingsHeader = null; settingsFooter = null; settingsOpen = false   // 설정 머리·하단 띠 참조 정리
        window.decorView.removeCallbacks(deferredInit)   // 예약된 지연 초기화 제거(실행돼도 destroyed 검사로 무시)
        ui.removeCallbacks(vehicleRetryRunnable)
        cancelLinkRetryPending()
        ui.removeCallbacks(gearWatchRunnable)
        platformSource?.release(); platformSource = null
        ui.removeCallbacks(graceEndRefresh)
        ui.removeCallbacks(leftBehindRunnable)   // 2026-11-13 2단계 D 타이머
        ui.removeCallbacks(releaseRepeatRunnable)   // 결정 31 다시 알림(같은 프로세스의 다음 화면이 onStart에서 다시 예약)
        devTools.onDestroy()
        stopVoice("종료"); stopArrivalListen("종료")
        chime?.release(); chime = null   // 2026-11-13 1(c) 차임(재생 중이면 멈추고 포커스 반환)
        naviTripEnd?.release(); naviTripEnd = null   // removeListener → release
        tts?.release()   // stop → removeEventListener → release
        tts = null
        stt?.let { s ->
            runCatching { s.release() }
        }
        stt = null; sttReady = false
        ui.removeCallbacksAndMessages(null)
        val v = vehicle
        if (v != null) {
            for ((name, sv) in seats) {
                beltListeners[name]?.let { runCatching { v.seatApi.unregisterSeatBeltBuckledState(area(sv), it) } }
                occListeners[name]?.let { runCatching { v.seatApi.unregisterSeatOccupancy(area(sv), it) } }
                warnListeners[name]?.let { runCatching { v.safetyApi.unregisterSeatBeltNotBuckledWarning(area(sv), it) } }
            }
            gearListener?.let { runCatching { v.gearApi.unregisterGearSelection(it) } }
            telltaleListener?.let { runCatching { v.safetyApi.unregisterTelltales(it) } }
            releaseDoors(v)   // r6 4절: 문 리스너 해제
            runCatching { v.release() }
        }
        super.onDestroy()
    }

    // ---- r10 N: 화면을 다시 그려도 유지 음성이 끊기지 않게(TtsCarryRule, 끄기 TtsGuide.KEEP_ACROSS_RECREATE) ----
    // 넘김: 재생성으로 끝나는 onDestroy(위). 받음: 새 화면의 TTS 준비(onReady) 뒤, 기어·플랫폼 첫 값을 받으면(최대 TtsCarryRule.GATE_WAIT_MAX_MS).
    // 이어 낸 문장도 같은 자동 안내 줄(pumpAuto → speakAuto)로 나가므로 오디오 포커스·내비 음성 겹침·차량 경고 우선·유효 P 전용 검사는 그대로다.
    // PM-INTEGRATE(r10 M): 이 보관소는 메모리만이라 프로세스 재시작 복원(TripStateStore)과 겹치지 않는다. 새 화면이 새 확인·새 이동으로
    //   autoQueue.clear()를 부르면 넘겨받은 음성도 함께 지워진다(의도). 받는 시점은 onReady 뒤라 onCreate 복원보다 늦다.
    // PM-INTEGRATE(r10 E): 개발 1의 '재생 중 교통 상식 끊기'는 그대로 쓴다. 넘겨받은 교통 상식은 보낼 때 tripDriving()을 다시 본다.

    /** onDestroy(재생성)에서 tts.release() 전에 부른다. 재생 중이던 자동 안내는 처음부터 다시 내도록 맨 앞에 넘긴다 */
    private fun handOverKeepVoice() {
        val now = SystemClock.elapsedRealtime()
        fun cand(s: AutoSay) = CarryCandidate(s.text, s.label, s.keep, s.trip, s.stillValid != null, s.chime, s.atMs, s.resaid)
        val speakingItem = autoSayItem?.takeIf { autoSayGen >= 0 && tts?.autoPending == true }
        val pack = TtsCarryRule.pack(TtsCarryStore.state, speakingItem?.let { cand(it) }, autoQueue.map { cand(it) }, now, TtsGuide.KEEP_ACROSS_RECREATE)
        TtsCarryStore.state = pack.state
        if (pack.dropped.isNotEmpty()) log("[TTS] 다시 그림 → 넘기지 않음: " + pack.dropped.joinToString { "${it.first}(${it.second.label})" })
        log("[TTS] 다시 그림 → 유지 음성 ${pack.carried}개 넘김(재생 중이던 ${pack.restarted}개는 처음부터 다시, 보관 ${pack.state.items.size}개)")
    }

    private val carryAdoptRunnable = Runnable { if (!destroyed) adoptCarriedVoice("기어·플랫폼 첫 값 기다린 뒤") }

    /** 새 화면: 넘겨받은 유지 음성을 자동 안내 줄 맨 앞에 넣고 보낸다(한 번만, 같은 문장이 이미 있으면 넣지 않음) */
    private fun adoptCarriedVoice(why: String) {
        if (destroyed || !TtsGuide.KEEP_ACROSS_RECREATE) return
        val t = tts
        val st = TtsCarryStore.state
        val now = SystemClock.elapsedRealtime()
        // 재생성 직후에는 기어를 다시 받기 전이라 잠금이다. 첫 값을 받은 뒤 넣어야 유효 P 전용 안내·정차 질문이 보낼 때 검사에서 버려지지 않는다
        val settled = mockGearOn || ((gearNameEverReceived || gear.subFailed) && freshPlatform() != PlatformUx.UNKNOWN)
        when (TtsCarryRule.adoptAction(st.items.isNotEmpty(), t?.ready == true, t?.initFailed == true, settled, now - startElapsedMs)) {
            AdoptAction.NONE, AdoptAction.WAIT_TTS -> return
            AdoptAction.WAIT_GATE -> { ui.removeCallbacks(carryAdoptRunnable); ui.postDelayed(carryAdoptRunnable, TtsCarryRule.ADOPT_RECHECK_MS); return }
            AdoptAction.DROP -> {
                log("[TTS] 다시 그림 뒤 음성 사용 불가 → 넘겨받은 유지 음성 ${st.items.size}개 버림(화면 문구로만)")
                TtsCarryStore.state = TtsCarryRule.discard(st)
                return
            }
            AdoptAction.ADOPT -> {}
        }
        ui.removeCallbacks(carryAdoptRunnable)
        val existing = (autoQueue.toList() + listOfNotNull(autoSayItem)).map { it.label to it.text }.toSet()
        val r = TtsCarryRule.adopt(st, now, existing)
        TtsCarryStore.state = r.state
        if (r.skippedOld.isNotEmpty()) log("[TTS] 다시 그림 뒤 너무 오래 보관돼 버림: ${r.skippedOld.joinToString()}")
        if (r.skippedDup.isNotEmpty()) log("[TTS] 다시 그림 뒤 같은 문장이 이미 줄에 있어 넣지 않음: ${r.skippedDup.joinToString()}")
        for (c in r.items.asReversed()) {
            autoQueue.addFirst(AutoSay(c.text, c.label, keep = true, trip = c.trip,
                stillValid = if (c.recheck) carriedCheck(c.label) else null, chime = c.chime, atMs = c.atMs, resaid = c.resaid))
        }
        val restarted = r.items.count { it.wasSpeaking }
        log("[TTS] 다시 그림 뒤 유지 음성 ${r.items.size}개 이어 냄(재생 중이던 ${restarted}개 처음부터, ${why}, 기어·플랫폼 첫 값 ${if (settled) "받음" else "못 받음(최대 대기 지남)"})")
        if (r.items.isNotEmpty()) pumpAuto()
    }

    /** 넘겨받은 안내의 '보낼 때 다시 볼 조건'(TtsCarryRule.RECHECK_LABELS). enqueueSay 호출부의 조건과 같은 식이다 */
    private fun carriedCheck(label: String): () -> Boolean = when (label) {
        VoiceQueueRule.LABEL_TIP -> { { tripDriving() } }
        ArrivalAskText.VOICE_LABEL -> { { ArrivalAskRule.askArrived(ArrivalAskStore.state, TripGuideStore.state, lockReason() == null && !tripDriving()) } }
        ArrivalAskText.ALIGHT_VOICE_LABEL -> { { ArrivalAskRule.askAlight(ArrivalAskStore.state, TripGuideStore.state, lockReason() == null && !tripDriving()) } }
        else -> { { false } }   // 규칙 밖 이름은 넘기지 않지만, 혹시 오면 보내지 않는다(보수적으로)
    }
    // =====================================================================================================
    // 2026-11-13 자동 확인(착석 + 벨트 신호) + 내비 출발·도착 음성 안내. 판단은 순수 로직(SeatCheckRule·TripGuide)이 하고 여기서는 연결만 한다.
    // 규칙 변경(사용자 결정): 보호자 '예' 보고 → 신호 자동 확인(확인 불가 좌석만 보호자 대체 버튼). 예전 질문 경로는 도달하지 않는다.
    // =====================================================================================================
    private var seatCheck = SeatCheckState()
    /** debug 주입 착석 좌석(실제 GET 무시, 출처 모의). release는 주입 경로가 막혀 항상 비어 있다 */
    private val debugOccHeld = mutableSetOf<String>()
    private var autoEvalPosted = false
    private val autoTick = object : Runnable {
        override fun run() {
            if (destroyed || flow.step != BoardingStep.AUTO_CHECK) return
            autoEvaluate()
            ui.postDelayed(this, AUTO_TICK_MS)
        }
    }
    /** 완료 문구를 아이 안내 앞에 붙이기 위한 1회 값 */
    private var autoCompletePrefix = ""
    /** 마지막으로 보낸(또는 보내려 한) 자동 안내 문구. 소리가 났다는 뜻은 아니다 */
    private var lastSpoken = ""
    /** 확인 완료 때 '등록 없이 진행'이었는가(스티커 개인 기록 저장 안 함) */
    private var confirmWithoutProfile = false
    /** 모의 내비 시간·모의 도착이 이번 이동에 쓰였는가(모의 표기) */
    private var etaMockUsed = false
    private var stickerGrantAttemptTrip = -1
    private var stickerJustGrantedUntil = 0L
    /** r7 1절: 이번 스티커를 어느 시점에 줬는가(계약 v7 stickerJustGranted를 하차 안내 화면에서만 켜기 위해) */
    private var stickerGrantedAt: StickerMomentRule.Moment? = null

    private fun requestAutoEval() {
        if (autoEvalPosted || destroyed) return
        autoEvalPosted = true
        ui.post { autoEvalPosted = false; if (!destroyed) autoEvaluate() }
    }

    /** SeatsStartAuto 효과: 새 확인 실행(좌석 스냅샷 고정, 이동 안내 상태는 시간 값만 남기고 비움) */
    private fun startAutoCheck(gen: Int, seatsSnap: Set<String>) {
        seatCheck = SeatCheckRule.start(seatsSnap)   // 계약 v2 좌석 화면: 빈 집합으로 시작하고 자리를 눌러(또는 자동) 배정한다
        resetBubbleFeed("새 확인(새 이동)")
        TripGuideStore.state = TripGuide.keepTimes(TripGuideStore.state)
        naviTripEnd?.requestDestinationInfo()   // r5 5절: 남겨 둔 값이 오래됐을 수 있어 확인 중에 현재 남은 시간을 한 번 다시 읽는다(경로 없으면 응답 없음·0은 무시)
        resetTripAlerts("새 확인")
        disarmHandoff("새 확인"); syncKeepAlive("새 확인")
        autoCompletePrefix = ""
        autoQueue.clear()
        log("[자동확인] 시작 세대=$gen 좌석=${seatsSnap.size}석 지원 좌석=${autoRowsSeats().size}석")
        ui.removeCallbacks(autoTick)
        ui.postDelayed(autoTick, AUTO_TICK_MS)
        requestAutoEval()
    }

    /** 지원 좌석 = 벨트 지원 ∪ 점유 지원 ∪ 선택 좌석 ∪ debug 주입 좌석. 지원 목록을 아직 모르면 5석 모두 */
    private fun autoRowsSeats(): List<String> {
        val b = supportedBelt; val o = supportedOcc
        val base: Set<String> = if (b == null && o == null) seats.map { it.first }.toSet() else b.orEmpty() + o.orEmpty()
        val sel = if (flow.step == BoardingStep.AUTO_CHECK) seatCheck.selected else TripGuideStore.state.selected
        val all = base + sel + debugOccHeld + debugBeltHeld
        return seats.map { it.first }.filter { it in all }
    }

    /** 좌석 착석 신호. 미지원·구독 단절·값 없음 = UNKNOWN(빈 좌석으로 바꾸지 않음). debug 주입 좌석은 지원·연결 검사를 건너뛰고 모의로 표시 */
    private fun occSignal(name: String): Pair<SeatOcc, Boolean> {
        fun of(v: Boolean?) = SeatOccPolarity.toOcc(v)
        if (name in debugOccHeld) return of(occupied[name]) to true
        if (supportedOcc?.contains(name) == false) return SeatOcc.UNKNOWN to false
        if (occSubscribed[name] != true || name in occSubFailed) return SeatOcc.UNKNOWN to false
        return of(occupied[name]) to false
    }

    /** 좌석 벨트 신호(무대 지도와 같은 규칙). debug 주입·모의(SIM)는 출처 모의 */
    private fun beltSignal(name: String): Pair<SeatBeltRef, Boolean> = beltSignalWithBasis(name).let { it.first to it.second }

    /** 벨트 신호 + 모의 여부 + 판정 출처. 앞줄은 PM 결정 22(FrontBeltRule: 좌석별 체결 → 아니면 경고등 기준) */
    private fun beltSignalWithBasis(name: String): Triple<SeatBeltRef, Boolean, BeltBasis> {
        if (name in debugBeltHeld) {
            val ref = if (!realBelt.containsKey(name)) SeatBeltRef.NO_SIGNAL
                else when (realBelt[name]) { true -> SeatBeltRef.BUCKLED_SIGNAL; false -> SeatBeltRef.UNBUCKLED_SIGNAL; null -> SeatBeltRef.UNKNOWN }
            return Triple(ref, true, BeltBasis.SEAT)
        }
        if (useSim) return Triple(simBelt[name]?.let { if (it) SeatBeltRef.BUCKLED_SIGNAL else SeatBeltRef.UNBUCKLED_SIGNAL } ?: SeatBeltRef.NO_SIGNAL, true, BeltBasis.SEAT)
        val inp = seatInputOf(name)
        val seatRef = StageSeatSignal.beltRefOf(inp.supported, inp.linkOk, inp.error == SeatErrorState.READ_FAILED, inp.latestBuckled,
            emptyValueReceived = realBelt.containsKey(name) && realBelt[name] == null)
        if (!FrontBeltRule.isFront(name)) return Triple(seatRef, false, BeltBasis.SEAT)
        val f = FrontBeltRule.resolve(seatRef, frontTelltaleOn())
        return Triple(f.ref, false, f.basis)   // 경고등 기준도 실제 차량 신호(모의 아님)
    }

    private fun signalOf(name: String): SeatSignal {
        val (o, om) = occSignal(name); val (b, bm, basis) = beltSignalWithBasis(name)
        return SeatSignal(o, b, om || bm, basis)
    }

    // ---- PM 결정 22: 앞줄 벨트 경고등(Telltale SEAT_BELT). 좌석을 구분하지 않는다 ----
    /** 경고등을 받고 있는가(지원·구독 정상, 한 번 이상 받음). 실패·미지원이면 false */
    private var telltaleOk = false
    private var telltaleFailed = false
    /** 마지막 경고등 SEAT_BELT 상태(ON/BLINK = true, 항목 없음 = false) */
    private var telltaleBeltOn: Boolean? = null

    /** 판정에 쓰는 경고등 값: 받고 있고 차량 연결이 정상일 때만, 아니면 null(좌석별 값 그대로) */
    private fun frontTelltaleOn(): Boolean? = if (telltaleOk && !telltaleFailed && vehicle != null && gear.linkOk) telltaleBeltOn else null

    private fun onTelltaleBelt(on: Boolean, src: String) {
        if (destroyed) return
        val prev = frontTelltaleOn()
        telltaleOk = true; telltaleFailed = false
        val changed = telltaleBeltOn != on
        telltaleBeltOn = on
        if (changed) log("[벨트] 앞줄 경고등 ${if (on) "켜짐(미체결)" else "꺼짐(체결)"} → 앞줄 벨트 판정은 경고등 기준($src)")
        if (FrontBeltRule.releaseEdge(prev, on)) frontReleaseCheck()
        if (!on) releaseRebuckled(FrontBeltRule.FRONT_KEY, "앞줄(경고등 기준)")   // 루프 r2 L4
        requestAutoEval(); renderStageOnly()
    }

    private fun onTelltaleFailed(why: String) {
        if (destroyed) return
        if (!telltaleFailed) log("[벨트] 경고등을 받을 수 없음($why) → 앞줄 벨트는 좌석별 값만(없으면 확인 불가)")
        telltaleFailed = true
        requestAutoEval(); renderStageOnly()
    }

    /**
     * 결정 22·23: 주행 중 경고등 꺼짐 → 켜짐 = 앞줄 벨트 풀림. 확인을 마친 이동에서 앞줄에 앉음 신호가 있으면(확인 대상 여부·배정과 무관),
     * 30초 간격·3회(BeltReleaseAlertRule). '앞줄을 확인 대상으로 고른 이동에서만' 조건은 결정 23으로 지웠다
     */
    private fun frontReleaseCheck() {
        val tg = TripGuideStore.state
        val armed = FrontBeltRule.releaseArmed(tg.confirmed,FrontBeltRule.FRONT_SEATS.associateWith { occSignal(it).first })
        val (st, speak) = BeltReleaseAlertRule.onChange(TripGuideStore.alerts.release, FrontBeltRule.FRONT_KEY, armed, true, false,
            tripDriving(), SystemClock.elapsedRealtime())
        TripGuideStore.alerts = TripGuideStore.alerts.copy(release = st)
        scheduleReleaseRepeat()   // PM 임시 결정 31
        if (!speak) { if (armed && tripDriving()) log("[벨트풀림] 앞줄(경고등 기준) 간격·횟수 제한으로 지금 말하지 않음"); return }
        log("[벨트풀림] 앞줄 경고등 기준 ${st.counts[FrontBeltRule.FRONT_KEY]}회째(주행 중, 음성만)")
        enqueueSay(FrontBeltRule.TEXT_RELEASE, "벨트풀림", keep = true, setCaption = false, chime = true) { tripDriving() && frontTelltaleOn() == true }
    }

    /** 루프 r2 L4: 다시 체결 신호 → 그 좌석 다시 알림을 바로 멈추고 로그(이동당 횟수는 그대로) */
    private fun releaseRebuckled(seat: String, label: String) {
        val (st, stopped) = BeltReleaseAlertRule.onRebuckle(TripGuideStore.alerts.release, seat)
        if (!stopped) return
        TripGuideStore.alerts = TripGuideStore.alerts.copy(release = st)
        log("[벨트풀림] $label 다시 체결 신호 → 다시 알림 멈춤(이번 이동 ${st.counts[seat] ?: 0}/${BeltReleaseAlertRule.MAX_PER_SEAT}회 유지, 결정 31)")
        scheduleReleaseRepeat()
    }

    // ---- 2026-11-14 PM 임시 결정 31: 주행 중 벨트 풀림이 이어지면 30초 간격 다시 알림(자리당 이동당 최대 3회, 첫 안내 포함). 판단은 BeltReleaseAlertRule.onRepeat ----
    private val releaseRepeatRunnable = Runnable { releaseRepeatTick() }

    private fun scheduleReleaseRepeat() {
        ui.removeCallbacks(releaseRepeatRunnable)
        if (destroyed) return
        val due = BeltReleaseAlertRule.nextRepeatAt(TripGuideStore.alerts.release) ?: return
        ui.postDelayed(releaseRepeatRunnable, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    /** 다시 알림 평가 1회. 차임 + 음성만(화면 변화 없음, 주행 중 S09 유지). 보낼 때 조건을 다시 본다 */
    private fun releaseRepeatTick() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val driving = tripDriving()
        val tg = TripGuideStore.state
        for (seat in TripGuideStore.alerts.release.repeat.toList()) {
            val front = seat == FrontBeltRule.FRONT_KEY
            val armed = if (front) FrontBeltRule.releaseArmed(tg.confirmed, FrontBeltRule.FRONT_SEATS.associateWith { occSignal(it).first })
                else tg.confirmed && !useSim && beltWarn.active && seat in beltWarn.armed
            val still = if (front) frontTelltaleOn() == true else realBelt[seat] == false
            val (st, speak) = BeltReleaseAlertRule.onRepeat(TripGuideStore.alerts.release, seat, armed, still, driving, now)
            TripGuideStore.alerts = TripGuideStore.alerts.copy(release = st)
            if (speak) {
                log("[벨트풀림] ${if (front) "앞줄(경고등 기준)" else seat} 풀림 유지 → 다시 알림 ${BeltReleaseAlertRule.countText(st.counts[seat] ?: 0)}(주행 중, 음성만, 결정 31)")
                val text = if (front) FrontBeltRule.TEXT_RELEASE else BeltReleaseAlertRule.text(seat)
                enqueueSay(text, "벨트풀림", keep = true, setCaption = false, chime = true) {
                    tripDriving() && (if (front) frontTelltaleOn() == true else realBelt[seat] == false)
                }
            } else if (seat !in st.repeat) log("[벨트풀림] $seat 다시 알림 멈춤(다시 체결·확인 불가·주행 아님·감시 아님·상한 ${st.counts[seat] ?: 0}/${BeltReleaseAlertRule.MAX_PER_SEAT})")
        }
        scheduleReleaseRepeat()
    }

    /** 자동 확인·하차 마무리 평가(멱등). 정차 허용 게이트일 때만 판정한다 */
    private fun autoEvaluate() {
        if (destroyed) return
        val park = tripParkAllowed()
        if (flow.step == BoardingStep.AUTO_CHECK) {
            if (park) { autoAssignIfOne(); maybeWelcome() }
            val signals = autoRowsSeats().associateWith { signalOf(it) }
            val step = SeatCheckRule.step(seatCheck, signals, SystemClock.elapsedRealtime(), park, carSeat = carSeatSeatsNow())   // r10 I: 카시트 자리 재안내 1회
            val before = seatCheck
            seatCheck = step.state
            lastCheckCaption = SeatCheckRule.captionBySeat(step.verdicts)
            if (park && !seatCheck.completed) maybeBubbleVoice(Scene.AUTO_CHECK, lastCheckCaption)
            // 결정 23: 빈 자리가 된 배정은 푼다(다시 앉으면 자동 배정·탭으로 다시)
            val released = before.assigned.keys - seatCheck.assigned.keys
            if (released.isNotEmpty()) { log("[자동확인] 배정 해제 ${released.joinToString()} (빈 자리 신호, 결정 23)"); renderStageOnly() }
            for (a in step.actions) when (a) {
                is SeatCheckAction.Remind -> {
                    val basis = if (a.seat == FrontBeltRule.FRONT_KEY) " (앞자리 묶음, ${if (frontTelltaleOn() != null) "경고등 기준" else "좌석별 신호"})" else ""
                    log("[자동확인] 벨트 재안내 ${a.seat} ${seatCheck.reminders[a.seat]}회째$basis")
                    enqueueSay(a.text, "벨트재안내", keep = false)
                }
                is SeatCheckAction.ExtraOccupied -> { log("[자동확인] 선택하지 않은 자리 착석 신호 ${a.labels.size}석"); enqueueSay(a.text, "추가착석", keep = false) }
                is SeatCheckAction.Complete -> onAutoComplete(a.kind, a.text)
            }
            if (before.completeSinceMs != seatCheck.completeSinceMs || step.actions.isNotEmpty()) renderStageOnly()
        }
        // 하차 마무리(유효 P, 출발한 이동)
        val tg = TripGuideStore.state
        if (tg.confirmed && tg.departed && !tg.allLeftSaid) {
            val occ = tg.occupiedAtConfirm.associateWith { occSignal(it).first }
            // 2026-11-13 B: 하차 마무리는 음성 판단이라 앱이 내비 뒤에 있어도 유효 P면 한다(서비스 종료 조건이기도 하다)
            // r6 4절: '내렸다' = 착석 EMPTY + 이번 정차 뒷문 열림(결정 45), 문 확인 불가면 착석만(결정 46) — ExitDoorRule.allLeft
            val door = ExitDoorStore.state
            val verdict = exitAllLeft(tg.occupiedAtConfirm)
            if (verdict.waitingDoor && tripVoicePark() && !lastExitDoorWait) log("[문] 배정 자리는 모두 비었지만 이번 정차에 뒷문 열림 없음 → 마무리 보류(일어났다 앉기일 수 있음)")
            lastExitDoorWait = verdict.waitingDoor && tripVoicePark()
            // r10d A: 마무리 음성은 모든 이동에서 "모두 내린 것 같아요. 확인해 주세요."(TripGuide.FINISH_TEXT_ALL).
            // 스위치를 끄면 r10c: 등록 아이 1명 이상 + 마무리 대상 자리에 손님인 이동만 '아이가'를 뺀다
            val guestRide = !TripGuide.FINISH_TEXT_ALL && TripGuide.guestRide(tg, SeatBoardRules.hasRegistered(assignChildren()))
            val step = TripGuide.onOccupancy(tg, occ, tripVoicePark(), door, guestRide = guestRide)
            if (!tg.allLeftSaid && step.state.allLeftSaid) {
                if (guestRide) log("[이동안내] (r10c) 손님이 함께 탄 이동(손님 자리 ${TripGuide.guestFinishSeats(tg).joinToString()}) → 마무리 문구 '아이가' 뺌")
                // r9-1: 마무리 순간 뒷줄에 앉아 있던 자리(배정 안 한 자리 포함)는 기준선 — 그대로 앉아 있으면 '다시 앉은 신호'가 아님
                val seatedAtFinale = reseatOcc().filter { (k, v) -> ReseatRule.isRear(k) && v == SeatOcc.OCCUPIED }.keys
                ExitDoorStore.state = ExitDoorRule.onFinale(ExitDoorStore.state, verdict, seatedAtFinale)
                if (seatedAtFinale.isNotEmpty()) log("[문] 마무리 순간 뒷줄 착석 신호 ${seatedAtFinale.joinToString()} → 기준선(다른 뒷자리 앉음)")
                log("[문] 하차 마무리 기준 = ${if (verdict.byDoor) "문·착석 신호" else "착석 신호만(문 확인 불가 또는 문 규칙 꺼짐)"}")
            }
            applyTrip(step, if (verdict.byDoor) "문·착석 신호" else "착석 신호")
        }
        // r7 2(나) 후속·r9-1: 마무리(보호자·자동) 뒤 기준선 자리가 비었는지 기록(그 뒤 다시 앉아야 '다시 앉은 신호')
        if (TripGuideStore.state.allLeftSaid && ExitDoorStore.state.guardianSeatedAtFinish.isNotEmpty()) {
            val obs = ExitDoorRule.observeAfterFinish(ExitDoorStore.state, reseatOcc())
            if (obs != ExitDoorStore.state) { log("[문] 마무리 뒤 비운 자리 ${obs.guardianVacated - ExitDoorStore.state.guardianVacated}"); ExitDoorStore.state = obs; renderStageOnly() }
        }
        maybeReviveAfterContinue()   // r10 K: '계속 가요' 정차에서 마무리 뒤 배정 자리에 모두 다시 앉음 → [새로 출발 확인] 없이 이어 감
        leftBehindEvaluate()
    }

    /** 자동 확인 완료(1.5초 유지 뒤 1회): 이동 안내 시작 + 스티커 조건 + 아이 안내(완료 문구 앞에 붙임) + 주행 벨트 감시 시작 */
    private fun onAutoComplete(kind: SeatCheckRule.CompleteKind, text: String) {
        val gen = flow.generation
        // 결정 23: 이동의 아이 자리(하차 마무리·두고 내림·스티커·추천 기록) = 배정한 뒷줄 자리만. 앞줄은 벨트 확인 대상일 뿐이다
        val sel = seatCheck.selected.filter { SeatCheckRule.isAssignable(it) }.toSet()
        val occ = sel.associateWith { occSignal(it).first }
        // r10d B: 확인 때 조수석에 앉은 신호가 있었으면 하차 마무리 대상에 더한다(ExitDoorRule.finaleTargets, 운전석 제외). 배정·스티커·두고 내림은 sel 그대로
        val finale = ExitDoorRule.finaleTargets(occ.filterValues { it == SeatOcc.OCCUPIED }.keys, occSignal(ExitDoorRule.FRONT_PASSENGER).first)
        if (ExitDoorRule.FRONT_PASSENGER in finale) log("[문] 확인 때 조수석 앉은 신호 → 조수석도 하차 마무리 대상(조수석 문 열림 + 착석 비움, r10d B)")
        TripGuideStore.state = TripGuide.onConfirmed(
            occupied = finale, occUnknown = occ.values.any { it == SeatOcc.UNKNOWN },
            byGuardian = kind != SeatCheckRule.CompleteKind.SIGNAL, prev = TripGuideStore.state,
            selected = sel, guardianSeats = seatCheck.guardianConfirmed, assigned = seatCheck.assigned.filterKeys { SeatCheckRule.isAssignable(it) },
            guardianUnbuckledSeats = seatCheck.guardianUnbuckled)   // r8b: 미체결 자리 보호자 확인(화면 행 판정용)
        // 계약 v2: 자리에 배정한 아이 = 이번 이동의 아이(스티커·호칭·추천 기록). 등록 아이가 없으면(이름 없는 아이) 개인 기록 없이 진행
        val kidIds = seatCheck.assigned.filterKeys { SeatCheckRule.isAssignable(it) }.values.filter { it >= 0 }.toSet()
        val guestOnly = kidIds.isEmpty()
        if (::childPanel.isInitialized) childPanel.selectForTrip(kidIds)
        // PM 결정 14: 목적지를 먼저 설정해 유효한 예정 시간이 있으면 완료 음성 끝에 1회 붙인다
        val etaStep = TripGuide.onConfirmEta(TripGuideStore.state)
        TripGuideStore.state = etaStep.state
        val etaSay = etaStep.says.firstOrNull()
        if (etaSay != null) log("[이동안내] ETA_TOTAL (확인 완료 직후, 목적지 먼저 설정)")
        confirmWithoutProfile = guestOnly
        // r10 수정(J + 화면 재생성): 스티커 대상(배정된 등록 아이만)을 프로세스 보관소에 남긴다(Activity 필드·패널 선택은 재생성 때 사라짐)
        TripKidsStore.saved = TripKidsRule.of(TripSessionStore.state.tripKey, seatCheck.assigned)
        markStickerEarned()
        dropCheckPhaseSays("완료")
        val spoken = TripGuide.confirmSpoken(text, etaSay)
        autoCompletePrefix = spoken
        lastSpoken = spoken
        log("[자동확인] 완료 종류=${kind.name} 좌석=${sel.size}석 하차마무리가능=${TripGuide.finalePossible(TripGuideStore.state)}")
        // 2026-11-13 ISSUE-07: 이동 시작 = 서비스 시작(앱이 앞에 있을 때). B: 완료 음성이 끝나면 내비로 넘기기 예약
        syncKeepAlive("확인 완료(${kind.name})")
        armHandoff()
        completeVoicePending = true   // 결정 24 ①: 완료 음성(+예정 시간)이 끝날 때까지 내비로 넘기지 않는다
        dispatchFlow(BoardingEvent.AutoCheckComplete(gen,
            if (kind == SeatCheckRule.CompleteKind.GUARDIAN) ResponseSource.GUARDIAN_BUTTON else ResponseSource.SIGNAL,
            seats = sel, children = kidIds.map { it.toString() }.toSet(), withoutProfile = guestOnly), "자동확인완료")
        if (flow.step != BoardingStep.CHILD_NOTICE) {   // 게이트가 막음: 완료 음성 없음. 예정 시간 안내만 자동 안내로 보낸다
            autoCompletePrefix = ""
            completeVoicePending = false
            disarmHandoff("게이트")
            etaSay?.let { enqueueSay(it.text, "이동안내-${it.kind.name}", keep = true, trip = it.kind) }
            return
        }
        if (etaSay == null) beginConfirmEtaWait(checkGen = gen, noticeGen = flow.generation, doneText = text)   // r10 F: 완료 음성 직전 예정 시간 다시 읽기
        // 예전 '예' 순간과 같이 주행 벨트 감시를 시작한다(2단계 B: 주행 중 체결→미체결 짧은 음성은 이 감시 세션 위에 얹는다)
        if (beltWarn.active) endBeltReleaseSession("새 자동 확인 완료")
        beltWarn = BeltWarningPolicy.arm(flow.report?.seats ?: emptySet(), if (useSim) emptyMap() else realBelt)
        beltMoved = false
        beltUndelivered.clear(); beltMonitorGap = null; syncHomeNotes()
        log("[주행안내] 감시 시작 대상=${beltWarn.armed.size}석 체결 감시=${beltWarn.lastBuckled.size}석")
        refreshGuardian()
    }

    /** 보호자 대체 버튼(NEEDS_GUARDIAN 좌석만, 정차 허용·자동 확인 중일 때만) */
    private fun onGuardianConfirmSeatTap(seat: String) {
        if (destroyed || flow.step != BoardingStep.AUTO_CHECK || !tapParkAllowed()) { log("[자동확인] 보호자 확인 버튼 무시(조건 아님)"); return }
        val before = seatCheck
        seatCheck = SeatCheckRule.guardianConfirm(seatCheck, seat, signalOf(seat), carSeat = carSeatSeatsNow())   // r10 I: 카시트 자리는 재안내 전에도
        log(when {
            seatCheck === before -> "[자동확인] 보호자 확인 버튼 무시($seat: 확인 불가·재안내 끝난 미체결 좌석 아님)"
            seat in seatCheck.guardianUnbuckled && seat in carSeatSeatsNow() -> "[자동확인] 보호자 확인 $seat (카시트 자리, 미체결 신호, 보호자 확인, 신호 아님 — r10 I)"
            seat in seatCheck.guardianUnbuckled -> "[자동확인] 보호자 확인 $seat (미체결 신호 자리, 보호자 확인, 신호 아님 — r8b)"
            else -> "[자동확인] 보호자 확인 $seat (보호자 확인, 신호 아님)"
        })
        requestAutoEval(); renderStageOnly()
    }

    /** 확인 완료 뒤 출발 전(DEPART_READY)에서 홈으로: 이동 안내 상태만 접는다(스티커 조건 기록·이동 번호는 그대로) */
    private fun leaveConfirmedToHome() {
        if (destroyed || lockReason() != null) return
        log("[자동확인] 출발 전 홈으로(확인 접음)")
        resetBubbleFeed("홈으로")
        TripGuideStore.state = TripGuide.keepTimes(TripGuideStore.state)
        resetTripAlerts("홈으로")
        disarmHandoff("홈으로"); syncKeepAlive("흐름 취소(홈으로)")
        dropCheckPhaseSays("홈으로")
        if (gState != GState.IDLE) dispatchFlow(BoardingEvent.ToHome, "홈")
        if (StageFlags.NEW_STAGE) { homeActive = true; syncHome() }
        renderStageOnly()
    }

    /**
     * PM 결정 25: 확인 중(CHECKING) [처음으로]. 유효 P + 플랫폼 허용일 때만 받는다. 확인 취소 → 홈, 재안내·환영 음성 중지(대기열 포함),
     * 아이 배정 초기화(다음 [시작] 때 자동 배정·탭으로 다시), 결정 20 보관본 버림, FGS 정리(흐름 취소), 말풍선 비움(lastSpoken="").
     * 무대 말풍선 기록(BubbleStack)은 디자인 쪽 비우기 메서드가 생기면 여기서 부른다(PM-INTEGRATE, 보고서의 디자인 요청).
     */
    private fun cancelCheckToHome() {
        if (destroyed) return
        if (!CheckCancelRule.accept(flow.step, tapParkAllowed(), lockReason() != null)) { log("[자동확인] [처음으로] 무시(확인 중·유효 P·플랫폼 허용 아님, 결정 25)"); return }
        log("[자동확인] 확인 취소 → 홈([처음으로], 결정 25) 배정 ${seatCheck.assigned.size}석 초기화")
        ui.removeCallbacks(autoTick)
        val queued = autoQueue.size
        autoQueue.clear()   // 재안내·환영·추가 착석 대기 비움
        if (queued > 0) log("[자동안내] 확인 단계 안내 ${queued}건 버림(확인 취소)")
        // 지금 나가는 재안내·환영(자동 안내, 유지 아님)을 멈춘다. 주행 경고·유지 안내(keepDrive)는 건드리지 않는다
        if (autoSayGen >= 0) tts?.stop(true, keepDrive = true, end = TtsEnd.APP_STOP)
        seatCheck = CheckCancelRule.cleared()
        CheckSessionStore.saved = null
        lastSpoken = ""
        autoCompletePrefix = ""
        TripGuideStore.state = TripGuide.keepTimes(TripGuideStore.state)
        resetTripAlerts("확인 취소")
        disarmHandoff("확인 취소"); syncKeepAlive("흐름 취소(홈으로)")
        dispatchFlow(BoardingEvent.ToHome, "확인 취소")   // AUTO_CHECK → HOME(세대 증가로 늦은 콜백 버림). commitFlow가 홈을 보인다
        resetBubbleFeed("확인 취소")   // 계약 v3 순번 0 + (디자인 통합 뒤) 무대 말풍선 비우기
        if (StageFlags.NEW_STAGE) { homeActive = true; syncHome() }
        renderStageOnly()
    }

    /** 표시용 자동 진행 단계(없으면 null) */
    private fun currentAutoPhase(): AutoPhase? =
        if (flow.step == BoardingStep.AUTO_CHECK) AutoPhase.CHECKING else TripGuide.phase(TripGuideStore.state)

    /** 무대 스냅샷용 자동 확인 상태(읽기 전용) */
    private fun buildAutoCheck(): AutoCheckUi? {
        // r7 3절: 빠른 시작 대기 중에는 흐름이 아직 홈이어도 좌석 확인 화면 틀(CHECKING + quickStartWaiting)을 준다(홈 대신)
        val quickWaiting = quickStartWaitingNow()
        val phase = currentAutoPhase() ?: (if (quickWaiting) AutoPhase.CHECKING else return null)
        val checking = phase == AutoPhase.CHECKING
        val tg = TripGuideStore.state
        val selected = if (checking) seatCheck.selected else tg.selected
        val guardian = if (checking) seatCheck.guardianConfirmed else tg.guardianSeats
        val unbuckledGuardian = if (checking) seatCheck.guardianUnbuckled else tg.guardianUnbuckledSeats   // r8b
        val assigned = if (checking) seatCheck.assigned else tg.assigned
        // 결정 23: 앞줄 앉은 자리는 벨트를 보는 단계(확인~주행)에서 늘 대상 행. 도착·하차 단계는 배정한 뒷줄만
        val rows = boardRows(selected, guardian, assigned, frontBelt = SeatBoardRules.modeOf(phase) == SeatBoardMode.CHECK,
            unbuckledGuardian = unbuckledGuardian, checkState = if (checking) seatCheck else null)
        val extra = rows.filter { !it.selected && !FrontBeltRule.isFront(it.seat) && it.seat != SeatCheckRule.DRIVER_SEAT && it.occ == SeatOcc.OCCUPIED }.map { it.label }
        val eta = TripGuide.eta(tg)
        val now = SystemClock.elapsedRealtime()
        return AutoCheckUi(
            // r10 J: 계약 v10 guest 채움(손님 배정 칸). 스위치 끔(r10c)일 때만: 마무리 화면은 빈 칸이라 손님 자리 행에 guest만 남긴다(제목 '아이가' 판단용)
            phase = phase, rows = TripGuide.markFinishGuests(SeatCheckRule.markGuests(rows), tg, allLeft = phase == AutoPhase.ALL_LEFT), extraOccupied = extra, eta = eta,
            stickerEarned = TripSessionRule.stickerEarned(TripSessionStore.state, TripSessionStore.earnedTrip),
            // 계약 v7(r7 1절): 하차 안내 때 줬으면 하차 안내 화면에서, 하차 마무리 때 줬으면(대답 없이 모두 내림 등) 모두 내림 화면에서
            stickerJustGranted = StickerMomentRule.justGrantedVisible(phase, ArrivalAskRule.alightingByAnswer(ArrivalAskStore.state, tg),
                stickerGrantedAt, now < stickerJustGrantedUntil),
            anyMock = rows.any { it.mock } || mockGearOn || etaMockUsed || eta?.source == SignalSource.MOCK,
            lastSpoken = lastSpoken,
            children = assignChildren(),   // r10 J: 등록 아이 + 끝에 손님(등록 0명이면 이름 없는 아이 하나)
            // 계약 v3: 승무원이 실제로 말한 문장(예정 시간 문장 뺌)과 순번
            bubbleLine = BubbleFeedStore.feed.line,
            bubbleSeq = BubbleFeedStore.feed.seq,
            // 계약 v4(임시 결정 34): 모두 내림 뒤 뒷줄에 다시 앉은 신호(유효 P·허용일 때만, 다시 비면 false)
            reseatedAfterAllLeft = ReseatRule.reseated(phase, ExitDoorRule.reseatOcc(ExitDoorStore.state, reseatOcc()), displayParkAllowed()),   // r7 2(나) 후속: 보호자 끝내기 때 앉아 있던 자리는 비었다가 다시 앉아야   // r6a 2절: 포커스가 다른 창이어도 보이면 바로
            // 계약 v9(r9-1): 마무리 순간부터 앉아 있던 뒷자리(기준선)가 아직 앉아 있음 → 부제만(새로 출발 확인 없음). reseated가 우선
            otherRearSeatedAfterAllLeft = ExitDoorRule.otherRearSeated(ExitDoorStore.state, phase, reseatOcc(), displayParkAllowed()) &&
                !ExitDoorRule.guardianFinishSeatOccupied(ExitDoorStore.state, tg.allLeftSaid),   // PM r9(QA 5b): 보호자 끝냄 때 배정 자리가 남았으면 "! 뒷자리에 앉은 신호가 아직 있어요" 줄이 대신 알린다("다른" 부제는 틀린 말)
            // 계약 v5(r5 추가 A): 목적지 없는 이동의 정차 질문 / '예' 뒤 되돌리기
            askArrived = ArrivalAskRule.askArrived(ArrivalAskStore.state, tg, lockReason() == null && !tripDriving()),
            arrivedByAnswer = ArrivalAskRule.arrivedByAnswer(ArrivalAskStore.state, tg),
            pausedByAnswer = ArrivalAskRule.pausedByAnswer(ArrivalAskStore.state, tg, lockReason() == null && !tripDriving()),   // 계약 v5.1
            // 계약 v6(r6 5절): "내릴까요?" 묻는 중 / 중간 하차 중
            askAlight = ArrivalAskRule.askAlight(ArrivalAskStore.state, tg, lockReason() == null && !tripDriving()),
            askAlightText = ArrivalAskStore.state.alightText,
            alightingByAnswer = ArrivalAskRule.alightingByAnswer(ArrivalAskStore.state, tg),
            // 계약 v6(r6 4절): 문 신호를 썼고 이번 정차에 뒷문이 열렸음(마무리 화면은 마무리 때 고정한 값)
            exitByDoor = ExitDoorRule.exitByDoor(ExitDoorStore.state, tg.allLeftSaid),
            // 계약 v7(r7 2절 나, 결정 47): 보호자 버튼으로 마무리함(마무리 화면 기준 줄 "(보호자 확인)", exitByDoor보다 우선)
            exitByGuardian = ExitDoorRule.exitByGuardian(ExitDoorStore.state, tg.allLeftSaid),
            // 계약 v8(r8 1절): 보호자 버튼을 누른 순간 배정 뒷자리에 착석 신호가 남아 있었음(마무리 화면 한 줄은 디자인 F)
            guardianFinishSeatOccupied = ExitDoorRule.guardianFinishSeatOccupied(ExitDoorStore.state, tg.allLeftSaid),
            // 계약 v6.1(r6 6절): 빠른 시작으로 연 확인이 진행 중(확인 완료·[처음으로]·이동 끝·새 확인이면 false)
            quickStart = QuickStartRule.contractFlag(QuickStartSession.quickGen, flow.generation, checking, seatCheck.completed),
            // 계약 v7(r7 3절): 빠른 시작 대기 중(유효 P·허용 대기, 상한 15초 전). 확인이 열리거나 상한이면 false
            quickStartWaiting = quickWaiting && currentAutoPhase() == null,
            // 계약 v10(r10 G): 빠른 시작 대기 + 기어 값을 아직 모름(첫 값 안정화 중·시작 유예 안 미수신) → 잠금 카드 대신 좌석 화면 + "기어 확인 중…"(터치는 잠금 규칙대로 막힘)
            gearCheckPending = gearCheckPendingNow(quickWaiting && currentAutoPhase() == null),
        )
    }

    /**
     * r10 G(개발 3): 계약 v10 `gearCheckPending` 값(판단 QuickStartRule.gearCheckPending). 잠금 판정(lockReason)은 바꾸지 않는다 —
     * 무대 상태의 locked는 그대로 true이고, 화면이 이 값을 보고 잠금 카드 대신 좌석 화면을 고른다(디자인). D/R/N·불명·구독 실패·유예 지남·플랫폼 제한이면 false(S09)
     */
    private fun gearCheckPendingNow(waiting: Boolean): Boolean {
        val reason = lockReason()
        val v = QuickStartRule.gearCheckPending(
            waiting = waiting, locked = reason != null,
            firstGearHolding = firstGearHolding() && reason == LOCK_TEXT_DISCONNECTED,
            startupChecking = lockDisplayKind(reason, SystemClock.elapsedRealtime() - startElapsedMs, gearNameEverReceived, gear.subFailed) == LockDisplayKind.CHECKING)
        if (v != lastGearCheckPending) { lastGearCheckPending = v; log("[빠른시작] 기어 확인 중 표시=${v}(잠금=${reason ?: "없음"})") }
        return v
    }
    private var lastGearCheckPending = false

    private fun reseatOcc(): Map<String, SeatOcc> = SeatCheckRule.LABELS.keys.associateWith { occSignal(it).first }

    /**
     * 2026-11-14 PM 임시 결정 34(J2, 계약 v4 onNewTripFromAllLeft): 모두 내림 화면의 [새로 출발 확인].
     * 누른 순간 조건(ALL_LEFT + 뒷줄 앉음 + 유효 P·허용)을 다시 본다 → 결정 29·30 경로로 이번 이동을 끝난 이동으로 넘기고(스티커는 이동당 1회 규칙으로 중복 0)
     * 새 이동(번호 +1)의 확인(CHECKING, 자동 배정)을 시작한다. 자동 전환·음성 없음(버튼을 눌렀을 때만)
     */
    private fun newTripFromAllLeft() {
        if (destroyed) return
        val phase = currentAutoPhase()
        if (!ReseatRule.acceptNewTrip(phase, ExitDoorRule.reseatOcc(ExitDoorStore.state, reseatOcc()), displayParkAllowed())) {
            log("[이동] [새로 출발 확인] 무시(모두 내림·뒷줄 앉음·유효 P·허용 아님, 단계=$phase)")
            renderStageOnly(); return
        }
        log("[이동] 모두 내림 뒤 다시 앉은 신호 → [새로 출발 확인](임시 결정 34) 이동 ${TripSessionStore.state.tripKey}")
        // 하차 마무리 기록이 메모리에 없으면(드묾) 여기서 경계를 넘긴다. 있으면 startSeatBoardCheck가 결정 29·30대로 넘긴다(두 번 넘기지 않음)
        if (!TripSessionRule.startsNewTripOnStart(TripSessionStore.allLeftTrip, TripSessionStore.state.tripKey)) {
            advanceTripBoundary("모두 내림 뒤 새로 출발 확인", "모두 내림 뒤 새 이동"); doneByMock = false
        }
        if (StageFlags.NEW_STAGE) { homeActive = false; syncHome() }
        startSeatBoardCheck("모두 내림 뒤 새로 출발 확인")
    }

    /** r10 K(ContinueReseatRule): 하차 마무리 순간(applyTrip ALL_LEFT). '계속 가요'로 답한 정차였으면 무장한다(아니면 풀린 상태로 덮어씀) */
    private fun armContinueReseat(tg: TripGuideState) {
        val s = ContinueReseatRule.onAllLeft(ArrivalAskStore.state, tg, TripSessionStore.state.tripKey, SystemClock.elapsedRealtime(),
            byGuardian = ExitDoorStore.state.finaleByGuardian, prev = ContinueReseatStore.state)
        ContinueReseatStore.state = s
        if (s.revivedThisStop) log("[다시탐] 이번 정차는 이미 자동으로 이어 갔음 → 이번 마무리는 무장하지 않음(정차당 1회, 다시 앉으면 기존 [새로 출발 확인])")
        if (s.armed) log("[다시탐] '계속 가요' 정차의 하차 마무리 → ${ContinueReseatRule.WINDOW_MS / 60_000L}분 안에 같은 정차에서 배정 자리에 모두 다시 앉으면 자동으로 이어 감(r10 K)" +
            if (ContinueReseatRule.KEEP_FGS_IN_WINDOW) ", 그동안 이동 서비스 유지" else "")
        // r10 수정 2: 창 끝 점검(서비스 종료는 그 안의 syncKeepAlive). 이 함수 뒤 applyTrip이 syncKeepAlive("하차 마무리")를 부르며 유지를 판단한다
        ui.removeCallbacks(reseatWindowEnd)
        ContinueReseatRule.windowLeftMs(s, SystemClock.elapsedRealtime())?.let { ui.postDelayed(reseatWindowEnd, it + 100L) }
    }

    /** r10 수정 2: 되살림 창 끝 → 무장 풀기(maybeReviveAfterContinue의 EXPIRED) → 서비스 판단 */
    private val reseatWindowEnd = Runnable { if (!destroyed) maybeReviveAfterContinue() }

    /**
     * r10 K: 무장한 마무리 뒤 같은 정차에서 배정 자리(확인 때 착석)에 모두 다시 앉으면 [새로 출발 확인] 없이 이동을 되살린다.
     * 되돌리는 기록: 이동 상태 allLeftSaid, 끝난 방식(TripFinishStore), 하차 마무리 도달 기록(allLeftTrip, 저장값 → -1), 이동 서비스 꼬리 시각,
     * 마무리 화면용 문 기준·기준선. 이동 서비스·빠른 시작 '이동 중' 표시는 syncKeepAlive가 다시 켠다(tripActive).
     * 되돌리지 않는 기록: 스티커(결정 48, 이동당 1회 — 다음 마무리에서 다시 주지 않음), 이동 번호(경계를 넘기지 않았다).
     */
    private fun maybeReviveAfterContinue() {
        val s = ContinueReseatStore.state
        if (destroyed) return
        if (!s.armed) {   // r10 수정: 주행 = 정차 경계 → '이번 정차 되살림 씀' 표시를 지운다(다음 정차는 다시 1회)
            if (s.revivedThisStop && (tripDriving() || TripGuideStore.state.drivingNow)) ContinueReseatStore.state = ContinueReseatRule.onDrive(s)
            return
        }
        val tg = TripGuideStore.state
        val now = SystemClock.elapsedRealtime()
        val driving = tripDriving()
        val occ = ExitDoorRule.reseatOcc(ExitDoorStore.state, reseatOcc())   // r9 기준선(마무리 때부터 앉아 있던 뒷자리)은 뺀 지도
        when (ContinueReseatRule.evaluate(s, tg, ArrivalAskStore.state, occ, lockReason() == null && !driving, driving,
            TripSessionStore.state.tripKey, now)) {
            ContinueReseatRule.Verdict.NONE -> return
            ContinueReseatRule.Verdict.EXPIRED -> {
                ContinueReseatStore.state = ContinueReseatRule.State()
                ui.removeCallbacks(reseatWindowEnd)
                val reason = ContinueReseatRule.disarmReason(s, now)
                log("[다시탐] 자동 이어 가기 조건 끝(주행·${ContinueReseatRule.WINDOW_MS / 60_000L}분 지남·이동 바뀜 중 하나, $reason) → 다시 앉으면 기존 [새로 출발 확인]")
                syncKeepAlive(reason)   // r10 수정 2: 창 동안 미뤄 둔 서비스 종료를 지금 한다(이동 중이면 그대로)
                return
            }
            ContinueReseatRule.Verdict.REVIVE -> {}
        }
        ContinueReseatStore.state = ContinueReseatRule.afterRevive(s)   // r10 수정: 정차당 1회
        ui.removeCallbacks(reseatWindowEnd)
        val key = TripSessionStore.state.tripKey
        TripGuideStore.state = ContinueReseatRule.revive(tg)
        TripFinishStore.last = null
        if (TripSessionStore.allLeftTrip == key) {
            TripSessionStore.allLeftTrip = -1
            val ok = StickerLogStore.saveAllLeftTrip(applicationContext, -1)   // -1 = 기록 없음(decodeAllLeftTrip)
            log("[다시탐] 이동 $key 하차 마무리 도달 기록 되돌림" + if (ok) " 저장됨" else " (저장 실패: 앱을 다시 켜면 이 이동을 끝난 이동으로 봄)")
        }
        TripKeepAlive.allLeftAtMs = -1L
        ExitDoorStore.state = ContinueReseatRule.reviveDoor(ExitDoorStore.state)
        // 아직 못 낸 마무리 음성·작별 인사는 버린다(지금 말하는 중인 것은 끊지 않는다)
        autoQueue.removeAll { it.trip == TripSayKind.ALL_LEFT || it.label == "작별인사" }
        // PM-INTEGRATE(r10 M): 개발 7 이동 상태 저장(TripStateStore)이 하차 마무리에서 '끝난 이동'으로 기록했으면 여기서 이동 중으로 되돌린다
        log("[다시탐] '계속 가요' 정차 · 마무리 뒤 ${(now - s.allLeftAtMs) / 1000}초 · 배정 자리 모두 다시 앉음 → 이동 $key 되살림([새로 출발 확인] 없음, 스티커 그대로, r10 K)")
        enqueueSay(ContinueReseatRule.TEXT, ContinueReseatRule.VOICE_LABEL, keep = true, setCaption = true)
        if (ContinueReseatRule.RECHECK_BELT) {
            val tgNow = TripGuideStore.state
            val belts = tgNow.occupiedAtConfirm.associateWith { beltSignal(it).first }
            val unbuckled = ContinueReseatRule.unbuckledAfterReseat(tgNow.occupiedAtConfirm, belts, tgNow.guardianUnbuckledSeats)
            for (seat in unbuckled) {
                log("[다시탐] $seat 미체결 신호 → 재안내 1회(다시 주행하면 r8 2절 감시 재개)")
                enqueueSay(SeatCheckRule.remindText(seat), "벨트재안내", keep = true) { lockReason() == null && !tripDriving() && beltSignal(seat).first == SeatBeltRef.UNBUCKLED_SIGNAL }
            }
        }
        leftBehindEvaluate()
        syncKeepAlive("다시 탐")
        renderStageOnly(); syncHome()
    }

    /** 계약 v3: 발화 요청이 나간 순간 말풍선 문장을 쌓는다(BubbleFeedRule). 홈 인사는 홈 말풍선이 따로 있어 넣지 않는다 */
    private fun pushBubble(spoken: String, label: String) {
        if (label == "홈인사") return
        val before = BubbleFeedStore.feed
        BubbleFeedStore.feed = BubbleFeedRule.onSpoken(before, spoken)
        if (BubbleFeedStore.feed !== before) log("[말풍선] #${BubbleFeedStore.feed.seq} $label")
    }

    /** 계약 v3: 새 이동·홈 복귀 때 말풍선 순번을 0으로. 무대에 쌓인 말풍선 비우기는 디자인 통합 뒤 연결 */
    private fun resetBubbleFeed(why: String) {
        if (BubbleFeedStore.feed != BubbleFeed()) log("[말풍선] 순번 0으로($why)")
        BubbleFeedStore.feed = BubbleFeedRule.reset()
        // 2026-11-14 PM 통합(계약 v3·결정 25): 무대에 쌓인 말풍선도 함께 비운다(확인 취소·출발 전 홈 복귀·이동 끝내기·새 이동 시작)
        stageScreen?.resetBubbles()
    }

    /** 좌석 행(계약 v2): 판정·배정 아이·착석/벨트 출처(ISSUE-11, 실제 VC 착석은 occMock=false, 모의 뒷좌석 벨트는 beltMock=true) */
    // 결정 23: 앞줄 칸의 childId는 늘 null(이름·첫 글자 없음), 빈 칸에도 이름을 보내지 않는다(SeatCheckRule.shownChild).
    // 앞줄 앉은 칸은 [frontBelt]일 때 배정과 무관하게 대상 행(selected=true + 판정) → 확인 불가면 그 칸의 [보호자 확인] 근거가 된다
    // r8b: [unbuckledGuardian] = 미체결 신호 자리를 보호자가 확인한 자리(판정을 보호자 확인으로), [checkState]가 있으면(확인 중)
    // 계약 v8 guardianForUnbuckled(재안내가 끝난 미체결 뒷줄 자리의 [보호자 확인]) 채움. 스위치가 꺼지면 늘 false
    private fun boardRows(selected: Set<String>, guardian: Set<String>, assigned: Map<String, Int>, frontBelt: Boolean = true,
                          unbuckledGuardian: Set<String> = emptySet(), checkState: SeatCheckState? = null): List<SeatCheckRow> =
        autoRowsSeats().map { seat ->
            val (occ, occMock) = occSignal(seat)
            val (belt, beltMock) = beltSignal(seat)
            val sel = SeatCheckRule.isTarget(seat, selected, occ, frontBelt)
            SeatCheckRow(seat, SeatCheckRule.label(seat), sel, occ, belt,
                if (sel) SeatCheckRule.verdict(occ, belt, seat in guardian, seat in unbuckledGuardian) else null, occMock || beltMock,
                childId = SeatCheckRule.shownChild(seat, occ, assigned), occMock = occMock, beltMock = beltMock,
                guardianForUnbuckled = sel && checkState != null && SeatCheckRule.guardianForUnbuckled(checkState, seat, occ, belt, carSeat = carSeatSeatsNow(assigned)),
                // r10 I(계약 v10): 카시트를 켠 아이가 배정된 뒷자리(빈 칸·앞줄 제외, 스위치가 꺼지면 false)
                carSeat = seat in carSeatSeatsNow(assigned) && SeatCheckRule.shownChild(seat, occ, assigned) != null)
        }

    /** 고를 수 있는 아이(등록 순서, 별명만). 등록한 아이가 없으면 이름 없는 아이 하나(개인 기록 없음) */
    private fun boardChildren(): List<ChildTag> =
        stageProfiles().sortedBy { it.id }.map { ChildTag(it.id, it.nickname, carSeat = it.carSeat) }.ifEmpty { listOf(SeatCheckRule.GUEST_CHILD) }

    /** r10 I: 카시트를 켠 아이가 배정된 뒷자리(SeatCheckRule.carSeatSeats, 스위치 CAR_SEAT_IMMEDIATE_GUARDIAN). 기본 = 지금 확인 배정 */
    private fun carSeatSeatsNow(assigned: Map<String, Int> = seatCheck.assigned): Set<String> =
        SeatCheckRule.carSeatSeats(assigned, boardChildren())

    // ---- 2026-11-13 PM 계약 v2·결정 15: 좌석 화면 하나로 확인(시작 → 바로 CHECKING, 자리 누름으로 아이 배정) ----
    /** 홈·무대 [시작]: 아이·좌석 선택 화면 없이 바로 자동 확인을 시작한다. 유효 P + 플랫폼 허용에서만 */
    private fun startSeatBoardCheck(why: String) {
        if (destroyed) return
        // 시작하지 못하면 옛 선택 화면(PICK)이 드러나지 않게 홈으로 둔다(잠금이면 잠금 장면이 앞선다)
        fun refuse(msg: String) { log("[자동확인] 시작 무시($why, $msg)"); if (StageFlags.NEW_STAGE) homeActive = true; renderStageOnly(); syncHome() }
        if (gState != GState.IDLE && gState != GState.WAITING_GUARDIAN && gState != GState.YES_REPORTED) { refuse("상태=$gState"); return }
        if (lockReason() != null || !tapParkAllowed()) { refuse("유효 P·허용 아님"); return }   // r6a QA 후속: 누름은 보임 기준(포커스 불필요)
        // PM 결정 29·30: 하차 마무리(ALL_LEFT)로 끝난 이동 뒤의 [시작]은 새 이동(끝내기를 이미 눌렀으면 번호가 이미 넘어가 있고 기록도 지워져 그대로).
        // 기록은 저장값이라 앱 프로세스가 다시 시작된 뒤에도 같다(메모리의 이동 안내 상태에 기대지 않는다)
        // r8 3절: 하차 안내 때 스티커를 이미 준 이동(저장값)도 끝난 이동으로 본다(하차 안내 → 앱 꺼짐 → 다시 [시작]·빠른 시작)
        val storedTrip = if (TripSessionStore.readFailed) null else StickerLogStore.loadTripOrNull(applicationContext)
        if (TripSessionRule.startsNewTripOnStartStored(TripSessionStore.allLeftTrip, TripSessionStore.state.tripKey, storedTrip)) {
            log("[이동] 모두 내림 또는 스티커를 이미 준 이동 ${TripSessionStore.state.tripKey} 뒤 [시작] → 새 이동으로 시작(결정 29·30, r8 3절)")
            advanceTripBoundary("모두 내림 뒤 [시작]", "모두 내림 뒤 새 이동"); doneByMock = false
        }
        nextView.text = ""; voiceNotice = ""; responseSource = ""
        val input = tapGateInput()   // r6a QA 후속: 흐름 게이트도 누름 기준(보임). 잠금 사유는 그대로 막힌다
        var s = flow
        val effects = ArrayList<BoardingEffect>()
        fun step(e: BoardingEvent) { val (n, f) = BoardingFlow.reduce(s, input, e); s = n; effects += f }
        if (s.step == BoardingStep.CHILD_NOTICE) step(BoardingEvent.NoticeNext(s.generation))   // 지난 확인 완료 안내를 마치고 새 확인
        if (s.step != BoardingStep.HOME && s.step != BoardingStep.FINISHED) step(BoardingEvent.ToHome)
        step(BoardingEvent.StartCheck)
        targetSeats.clear()
        commitFlow(s, effects, if (s.step == BoardingStep.AUTO_CHECK) "시작($why)" else "시작불가($why)")
        if (flow.step != BoardingStep.AUTO_CHECK) { refuse("흐름이 시작을 받지 않음"); return }
        renderStageOnly()
    }

    /** 자리 누름(계약 v2 onSeatTap): 유효 P + 허용 + 확인 중일 때만. 운전석·빈 자리는 무시, 회색(확인 불가)은 배정 가능 */
    private fun onSeatTapAssign(seat: String) {
        if (destroyed) return
        if (flow.step != BoardingStep.AUTO_CHECK || !tapParkAllowed()) { log("[자동확인] 자리 누름 무시($seat, 확인 중·정차 허용 아님)"); return }
        // 결정 23: 앞줄은 아이 고르기가 열리지 않는다(앞줄 앉은 칸의 벨트 확인 불가는 칸의 [보호자 확인] 버튼으로만)
        if (!SeatCheckRule.isAssignable(seat)) { log("[자동확인] 자리 누름 무시($seat: 앞줄은 아이 배정 안 함, 결정 23)"); return }
        val next = SeatCheckRule.tapAssign(seatCheck, seat, occSignal(seat).first, assignChildren())   // r10 J: 없음 → 아이들 → 손님 → 없음
        if (next == null) { log("[자동확인] 자리 누름 무시($seat: 빈 자리·완료 뒤·아이 없음)"); return }
        val before = seatCheck.assigned[seat]
        seatCheck = next
        log("[자동확인] 자리 배정 $seat: ${SeatCheckRule.assignText(before)} → ${SeatCheckRule.assignText(seatCheck.assigned[seat])} (보호자 누름)")
        requestAutoEval(); renderStageOnly()
    }

    /**
     * 자동 배정(디자인 SeatBoardRules.autoAssign: 아이 1명 + 앉은 자리 1곳). 보호자가 누른 적이 있으면 하지 않는다.
     * r10 J(스위치 SeatCheckRule.GUEST_SEATS_ENABLED): 목록에 손님이 있으면 SeatCheckRule.fillGuests — 등록 아이 1명이면 앉은 첫 뒷자리에 아이,
     * 등록 아이를 모두 배정한 뒤 남은 앉은 뒷자리에 손님(보호자가 누른 뒤에도, 보호자가 '없음'으로 둔 자리는 빼고)
     */
    private fun autoAssignIfOne() {
        val st = seatCheck
        if (st.completed) return
        val kids = assignChildren()
        val guestRule = SeatCheckRule.guestMulti(kids)
        if (!guestRule && (st.userTouched || st.assigned.isNotEmpty())) return
        val cells = SeatBoardRules.cellsOf(AutoCheckUi(AutoPhase.CHECKING, boardRows(emptySet(), emptySet(), emptyMap()), children = kids)) ?: return
        val next = SeatCheckRule.autoAssign(st, cells, kids)
        if (next === st) return
        seatCheck = next
        if (next.assigned != st.assigned) {
            val added = next.assigned.filterKeys { st.assigned[it] != next.assigned[it] }
            log("[자동확인] 자동 배정 ${added.entries.joinToString { "${it.key}=${SeatCheckRule.assignText(it.value)}" }} " +
                if (guestRule) "(r10 J: 아이 1명이면 첫 자리, 등록 아이를 다 쓴 뒤 남은 앉은 뒷자리는 손님)" else "(아이 1명·앉은 뒷줄 자리 1곳)")
            renderStageOnly()
        }
    }

    /**
     * r10 J: 자리 고르기·자동 배정·계약 children에 쓰는 목록 = 등록 아이(등록 순서) + 끝에 손님(SeatCheckRule.GUEST_TAG "손님").
     * 등록 0명이면 boardChildren()의 이름 없는 아이(GUEST_CHILD "아이", 같은 id -1) 하나. 스위치를 끄면 boardChildren()과 같다
     */
    private fun assignChildren(): List<ChildTag> = SeatCheckRule.withGuest(boardChildren())
    // PM-INTEGRATE(r10 J): 디자인 SeatBoardStage 아이 목록은 m.children을 그대로 그려 손님이 "손님 · (첫 손님 자리 또는 자리 고르기 전)" 한 줄로 보인다.
    //   손님 줄 처리(여러 자리 표시·숨김)는 디자인 J. 개발 4의 carSeat는 boardChildren()의 ChildTag 줄에만 더하면 여기로 그대로 온다

    // ---- 자동 안내 큐: TtsGuide는 대기 발화가 1개뿐이고 speak()가 진행 중 발화를 끊으므로, 쉬는 중일 때만 하나씩 보낸다 ----
    private data class AutoSay(val text: String, val label: String, val keep: Boolean, val trip: TripSayKind?,
                               /** 보낼 때 다시 확인할 조건(없으면 항상). false면 오래된 안내로 보고 버린다 */
                               val stillValid: (() -> Boolean)? = null,
                               /** 2026-11-13 1(c): 말하기 전에 짧은 2음 차임(포커스를 얻은 경우에만) */
                               val chime: Boolean = false,
                               /** r5 2절: 줄에 선 시각(유효 시간 판단)과 내비 때문에 다시 말한 횟수 */
                               val atMs: Long = SystemClock.elapsedRealtime(),
                               val resaid: Int = 0) {
        val q: QueuedSay get() = VoiceQueueRule.of(label, atMs)
    }
    private val autoQueue = ArrayDeque<AutoSay>()
    private var autoSayGen = -1
    private var autoSayKind: TripSayKind? = null
    /** r8 4절: 주행 중이라 버린 유효 P 전용 안내(다음 유효 P에서 다시) */
    private var parkVoiceRetry = ParkVoiceRule.Retry()
    /** 지금 말하는 자동 안내(내비 때문에 멈추면 다시 줄에 세운다, 결정 37(사용자 확정, 11-15)) */
    private var autoSayItem: AutoSay? = null
    /** 내비 기다림 로그를 이미 남긴 항목(같은 항목 반복 로그 막기) */
    private var naviWaitLoggedFor: AutoSay? = null
    /** 지금 나가는 자동 안내의 이름(r5 추가 A: 질문 음성 완료 훅 구분) */
    private var autoSayLabel: String? = null
    private val pumpRunnable = Runnable { pumpAuto() }

    /** keep=true: 출발·남은 시간·도착·하차 마무리(화면 갱신·잠금 정리로 끊지 않음). false: 자동 확인 단계 재안내(게이트 이탈 때 버림) */
    private fun enqueueSay(text: String, label: String, keep: Boolean, trip: TripSayKind? = null, setCaption: Boolean = true,
                           chime: Boolean = false, stillValid: (() -> Boolean)? = null) {
        if (setCaption) lastSpoken = text   // 주행 중 벨트 풀림(B)은 화면에 새 정보를 올리지 않는다(S09 유지)
        autoQueue.addLast(AutoSay(text, label, keep, trip, stillValid, chime))
        pumpAuto()
    }

    /** 차임을 이미 울린 큐 항목(같은 항목에 두 번 울리지 않게) */
    private var chimedFor: AutoSay? = null
    private var chime: ChimePlayer? = null
    /** 마지막 자동 안내가 끝난 시각(교통 상식의 빈 시간 판단, 없으면 -1) */
    private var lastAutoSayEndMs = -1L
    /** 지금 자동 안내·차임·확인 완료 음성이 나가는 중인가 */
    private fun autoSpeaking(): Boolean = autoQueue.isNotEmpty() || tts?.idle == false || chime?.playing == true || childNoticeTtsGen >= 0

    private fun dropCheckPhaseSays(why: String) {
        val n = autoQueue.count { !it.keep }
        if (n == 0) return
        autoQueue.removeAll { !it.keep }
        log("[자동안내] 확인 단계 안내 ${n}건 버림($why)")
    }

    private fun pumpAuto() {
        if (destroyed) return
        val t = tts ?: return
        if (arrivalListening) return   // r5 추가 A: 도착 질문 음성 대답을 듣는 동안(최대 5초) 앱 음성을 내지 않는다. 끝나면 stopArrivalListen이 다시 부른다
        while (autoQueue.isNotEmpty()) {
            // r5 2절: 순서 = 벨트 풀림 → 도착·예정 시간 등 → 교통 상식, 같은 등급은 줄 순서. 때를 놓친 5분·1분 전·상식은 버린다(VoiceQueueRule)
            val now = SystemClock.elapsedRealtime()
            val stale = autoQueue.filter { VoiceQueueRule.expired(it.q, now) }
            if (stale.isNotEmpty()) {
                autoQueue.removeAll { it in stale }
                for (x in stale) { log("[자동안내] 너무 밀려 때를 놓침 → 버림 종류=${x.label}(${(now - x.atMs) / 1000}초 대기)"); x.trip?.let { TripGuideStore.state = TripGuide.onSpoken(TripGuideStore.state, it) } }
                continue
            }
            val idx = VoiceQueueRule.pick(autoQueue.map { it.q }, now) ?: return
            val s = autoQueue[idx]
            if (!s.keep && (flow.step != BoardingStep.AUTO_CHECK || !tripParkAllowed())) { autoQueue.removeAt(idx); log("[자동안내] 확인 단계가 아니라 버림 종류=${s.label}"); continue }
            if (t.idle && s.stillValid?.invoke() == false) { autoQueue.removeAt(idx); log("[자동안내] 조건이 바뀌어 버림 종류=${s.label}"); continue }
            if (!t.idle) { cutTipForParkSay(t); return }   // r10 E(개발 1): 유효 P 정차 안내가 기다리면 교통 상식만 끊는다(끝나면 onIdle이 다시 부른다)
            if (!ParkVoiceRule.sendable(s.label, tripVoicePark(), tripDriving())) {
                // r8 4절: 도착·중간 하차 안내·스티커 한 줄은 보낼 때 유효 P + 허용을 다시 본다. 아니면 버리고 소진하지 않는다(다음 유효 P에서 다시)
                autoQueue.removeAt(idx)
                if (s.label == ParkVoiceRule.LABEL_ARRIVED) TripGuideStore.state = ParkVoiceRule.unsayArrival(TripGuideStore.state)
                parkVoiceRetry = ParkVoiceRule.onDropped(parkVoiceRetry, s.label)
                log("[이동안내] 보낼 때 유효 P 아님(기어=${gear.name ?: "불명"}, 잠금=${lockReason() ?: "없음"}) → 버림, 다음 유효 P에서 다시 종류=${s.label}")
                continue
            }
            val hold = VoiceQueueRule.holdMs(s.q, now)   // r6a 1절: 내비 신호 직후에는 내비가 말을 시작하는지 잠깐 지켜본다
            if (hold > 0) {
                ui.removeCallbacks(pumpRunnable); ui.postDelayed(pumpRunnable, hold)
                if (naviWaitLoggedFor !== s) { naviWaitLoggedFor = s; log("[내비음성] 내비 신호 직후 → ${hold}ms 동안 내비 음성 시작을 지켜본 뒤 말함 종류=${s.label}") }
                return
            }
            if (chime?.playing != true) {   // r5 2절: 내비가 말하는 중·직후면 차임·음성 모두 기다린다(감시 못 하면 0 = 막지 않음)
                val wait = t.naviWaitMs()
                if (wait > 0) { ui.removeCallbacks(pumpRunnable); ui.postDelayed(pumpRunnable, wait); if (naviWaitLoggedFor !== s) { naviWaitLoggedFor = s; log("[내비음성] 내비 안내 음성 중·직후 → 앱 안내 기다림 종류=${s.label}(${wait}ms 뒤 다시)") }; return }
            }
            if (s.chime && chimedFor !== s) {   // 1(c) 벨트 풀림: 좌석 이름 음성 앞 차임. 포커스가 없으면 차임 없이 바로 음성으로
                val c = chime ?: ChimePlayer(applicationContext, ui) { log(it) }.also { chime = it }
                if (c.playing) return
                chimedFor = s
                c.play { ok -> if (ok) log("[차임] 벨트 풀림 차임 재생(${Chime.TOTAL_MS}ms)"); ui.post { pumpAuto() } }
                return
            }
            if (chime?.playing == true) return
            val g = t.speakAuto(s.text, s.label, s.keep)
            when {
                g >= 0 -> { autoQueue.removeAt(idx); autoSayGen = g; autoSayKind = s.trip; autoSayItem = s; autoSayLabel = s.label; if (BubblePushRule.pushes(s.label, g, s.resaid)) pushBubble(s.text, s.label); renderStageOnly(); return }   // r8 4절: 말풍선은 실제로 보낼 때만
                g == TtsGuide.AUTO_BUSY -> return
                g == TtsGuide.AUTO_NOT_READY -> { ui.removeCallbacks(pumpRunnable); ui.postDelayed(pumpRunnable, 1_000L); return }
                g == TtsGuide.AUTO_NAVI_WAIT -> { ui.removeCallbacks(pumpRunnable); ui.postDelayed(pumpRunnable, t.naviWaitMs().coerceAtLeast(100L)); return }
                else -> {   // 음성 꺼짐·포커스 거부·예외: 다시 시도하지 않는다(문구는 화면에 남는다)
                    autoQueue.removeAt(idx)
                    s.trip?.let { TripGuideStore.state = TripGuide.onSpoken(TripGuideStore.state, it) }
                    lastAutoSayEndMs = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun onAutoSayResult(g: Int, end: TtsEnd) {
        if (destroyed || g != autoSayGen) return
        val k = autoSayKind
        val item = autoSayItem
        val label = autoSayLabel
        autoSayGen = -1; autoSayKind = null; autoSayItem = null; autoSayLabel = null
        if (item != null && tts?.yieldedToNavi(g) == true) {
            // 결정 37(사용자 확정, 11-15): 내비 음성 때문에 멈춘 문장은 내비가 끝난 뒤 처음부터 다시(같은 등급 맨 앞, 유효 시간·횟수 상한 안에서)
            if (item.resaid < NaviVoiceRule.MAX_RESAY) {
                autoQueue.addFirst(item.copy(resaid = item.resaid + 1, chime = false))
                log("[내비음성] 멈춘 안내를 다시 줄에 세움 종류=${item.label}(${item.resaid + 1}/${NaviVoiceRule.MAX_RESAY}번째 다시)")
            } else {
                log("[내비음성] 다시 말하기 상한 → 버림 종류=${item.label}")
                if (k != null) TripGuideStore.state = TripGuide.onSpoken(TripGuideStore.state, k)
            }
            lastAutoSayEndMs = SystemClock.elapsedRealtime()
            renderStageOnly()
            return   // onIdle이 다시 보낸다(내비가 끝날 때까지 naviWaitMs로 기다림)
        }
        if (label == ArrivalAskText.VOICE_LABEL) ui.post { onArrivalQuestionSpoken(end == TtsEnd.DONE) }
        if (label == ArrivalAskText.ALIGHT_VOICE_LABEL) ui.post { onArrivalQuestionSpoken(end == TtsEnd.DONE, alight = true) }
        if (label == LeftBehindRule.VOICE_LABEL) ui.post { onLeftBehindSayResult(end == TtsEnd.DONE, end.label) }   // r8 5절(개발 D): 두고 내림 횟수는 나간 것만   // r6 5절: 내릴까요 질문도 같은 STT 훅
        if (k != null) TripGuideStore.state = TripGuide.onSpoken(TripGuideStore.state, k)
        lastAutoSayEndMs = SystemClock.elapsedRealtime()
        log("[자동안내] 결과 세대=$g 사유=${end.label}")
        renderStageOnly()
        ui.post { if (!destroyed) { evaluateHandoff("자동 안내 끝"); syncKeepAlive("하차 마무리 음성 끝") } }
    }

    // ---- 2026-11-13 음성 1(a) 탑승 환영 · 1(d) 교통 상식 ----
    /** 환영 인사를 한 확인 세대(확인마다 1회) */
    private var welcomeGen = -1

    /** 확인 시작 뒤 첫 평가(자동 배정 다음)에서 1회. 배정된 아이 별명은 '별명 TTS 읽기'가 켜졌을 때만 */
    private fun maybeWelcome() {
        if (welcomeGen == flow.generation) return
        welcomeGen = flow.generation
        val kids = boardChildren()
        val names = seatCheck.assigned.values.mapNotNull { id -> kids.firstOrNull { it.id == id && it.id >= 0 }?.nick }
        val text = VoiceLines.welcome(noticeMode(), names, noticeSpeakNames())
        log("[탑승환영] 1회(별명 ${if (noticeSpeakNames() && names.isNotEmpty()) "읽기" else "안 읽기"})")
        enqueueSay(text, "탑승환영", keep = false, setCaption = false)
    }

    private var tipsEnabled = VoiceTipStore.DEFAULT_ON
    private val tipRunnable = Runnable { tipTick() }

    /** 교통 상식 시간 값. 기본 SafetyTipRule 상수, debug 개발자 패널만 짧게 바꾼다(DevMockGuard로 release 차단) */
    private var tipTiming = SafetyTipRule.DEFAULT

    /** 교통 상식 점검(SafetyTipRule, r5 3절): 주행 중·빈 시간에만, 횟수 제한 없이 무작위 간격. 화면에는 올리지 않는다(주행 중 음성만) */
    private fun tipTick() {
        if (destroyed) return
        val tg = TripGuideStore.state
        if (!tg.confirmed || !tg.departed || tg.arrivalSignal || tg.allLeftSaid) return   // 이동이 끝났으면 멈춤
        val now = SystemClock.elapsedRealtime()
        val st = TripGuideStore.alerts.tips
        val naviBusy = tts?.naviBusy == true   // r5 2절: 내비가 말하는 중·직후면 내지 않는다
        // r10 E(개발 1): P에서는 교통 상식을 시작하지 않는다(TipCutRule.startAllowed, 스위치 PARK_START_BLOCK_ENABLED)
        if (tipStartAllowed() && SafetyTipRule.due(st, tg, tipsEnabled, autoSpeaking(), naviBusy, lastAutoSayEndMs, now, tipTiming)) {
            val tip = VoiceTipStore.nextTipText(this)   // 2026-11-14: 무작위 순서(TripTips), 순서·위치 저장
            val next = SafetyTipRule.onSaid(st, now, t = tipTiming)
            TripGuideStore.alerts = TripGuideStore.alerts.copy(tips = next)
            log("[교통상식] 이번 이동 ${next.said}번째(주행 중, 음성만) · 다음은 ${SafetyTipRule.gapText(next.nextGapMs)} 뒤 이후")
            enqueueSay(tip, VoiceQueueRule.LABEL_TIP, keep = true, setCaption = false) { tipStartAllowed() }   // r10 E: 보낼 때도 주행 중 + P 아님
        }
        ui.removeCallbacks(tipRunnable); ui.postDelayed(tipRunnable, tipTiming.tickMs)
    }

    /** r10 E(개발 1): 교통 상식을 시작·보내도 되나(주행 중 + 기어 P 아님). 판단은 TipCutRule.startAllowed */
    private fun tipStartAllowed(): Boolean = TipCutRule.startAllowed(tripDriving(), gear.name?.uppercase()?.let { it in PARK_GEAR_NAMES } == true)

    /**
     * r10 E(개발 1): pumpAuto가 다른 발화 때문에 기다릴 때 부른다. 유효 P(잠금 사유 없음 + 주행 아님)에서 정차 안내(도착 질문·내릴까요·
     * 중간 하차·도착 안내·하차 마무리·두고 내림, TipCutRule.PARK_URGENT_LABELS)가 줄에 서 있고 지금 걸린 자동 안내가 교통 상식이면 그것만 끊는다.
     * 줄에 선 교통 상식도 지운다. 끊긴 상식은 다시 틀지 않는다(다음 상식 간격은 끊긴 상식을 낸 시각부터, SafetyTipState 그대로).
     * 끊은 뒤에는 TtsGuide.stop → onAutoResult(APP_STOP) → onIdle → pumpAuto로 정차 안내가 나간다(내비 대기·차량 경고 규칙은 그대로 거침)
     */
    private fun cutTipForParkSay(t: TtsGuide) {
        val tipActive = autoSayGen >= 0 && autoSayLabel == VoiceQueueRule.LABEL_TIP
        if (!TipCutRule.shouldCut(tipActive, autoQueue.map { it.label }, tripVoicePark(), tripDriving())) return
        val waiting = autoQueue.firstOrNull { TipCutRule.urgent(it.label) }?.label
        val dropped = autoQueue.count { TipCutRule.dropQueuedTip(it.label) }
        autoQueue.removeAll { TipCutRule.dropQueuedTip(it.label) }
        if (t.stopTip(autoSayGen)) log("[교통상식] 유효 P + 정차 안내 대기(종류=${waiting}) → 교통 상식 끊음(다시 틀지 않음)" + if (dropped > 0) ", 줄의 상식 ${dropped}건 버림" else "")
    }

    // ---- 이동 안내(TripGuide) 연결 ----
    private fun tripParkAllowed(): Boolean = currentGate() == VehicleGate.PARK_ALLOWED && lockReason() == null
    /**
     * 2026-11-13 B: 음성만 내는 이동 안내(도착·하차 마무리·두고 내림)의 정차 판단. 유효 P + 플랫폼 허용(잠금 사유 없음)이면 앱이 화면 뒤
     * (내비가 앞)여도 된다. 화면 조작 허용([tripParkAllowed])과 달리 전경 조건을 보지 않는다. 화면에는 새 정보·조작을 넣지 않는다
     */
    private fun tripVoicePark(): Boolean = lockReason() == null
    private fun tripDriving(): Boolean = gear.let { BeltReleaseRule.isDriving(it.name, it.linkOk, it.subFailed) }

    private fun applyTrip(step: TripGuideStep, why: String) {
        val before = TripGuideStore.state
        TripGuideStore.state = step.state
        for (say in step.says) {
            // 루프 r5 1절: 도착 음성은 앱이 뒤에 있어도 같은 문장("앱을 열어…" 없앰). 앱은 evaluateAppFront가 앞으로 올린다
            val text = say.text
            log("[이동안내] ${say.kind.name} ($why)" + if (say.kind == TripSayKind.ARRIVED && !started) " 앱 화면 뒤 → 앱을 앞으로 올림" else "")
            enqueueSay(text, "이동안내-${say.kind.name}", keep = true, trip = say.kind, setCaption = true)
            // r7 1절: 하차 안내(실제 도착 뒤 P · "예, 도착했어요")가 나올 때 스티커를 준다(음성 순서: 도착 안내 → 스티커 한 줄)
            if (say.kind == TripSayKind.ARRIVED) grantStickerIfEarned("하차 안내(도착)", StickerMomentRule.Moment.EXIT_GUIDE)
            if (say.kind == TripSayKind.ALL_LEFT) {
                // r6 5절: 끝난 방식(개발 B 6절 빠른 시작 조건: TripFinishStore.endedByAlight())
                TripFinishStore.last = TripFinishRule.kindOf(step.state, ArrivalAskStore.state)
                log("[이동] 끝난 방식=${TripFinishStore.last}")
                armContinueReseat(step.state)   // r10 K: '계속 가요' 정차의 마무리면 다시 앉았을 때 자동으로 이어 간다
                // 결정 29·30: 이 이동은 끝난 이동. 스티커 지급보다 먼저 저장한다(지급 저장 전에 앱이 꺼져도 다음 [시작]이 새 이동으로 가며 그때 지급)
                TripSessionStore.allLeftTrip = TripSessionStore.state.tripKey
                val allLeftSaved = StickerLogStore.saveAllLeftTrip(applicationContext, TripSessionStore.allLeftTrip)
                log("[이동] 이동 ${TripSessionStore.allLeftTrip} 하차 마무리 도달 기록" + if (allLeftSaved) " 저장됨" else " (저장 실패: 앱을 다시 켜면 같은 이동으로 이어짐)")
                // r7 1절: 하차 안내 때 이미 줬으면 여기서는 아무것도 하지 않는다(멘트·작별 인사만). 대답 없이 모두 내린 이동 등은 예전처럼 여기서
                TripKeepAlive.allLeftAtMs = SystemClock.elapsedRealtime(); grantStickerIfEarned("하차 마무리")
                // 1(b) 작별 인사: 보호자 문장 바로 뒤 1회(아이 대상). 화면 문구는 바꾸지 않는다
                enqueueSay(VoiceLines.FAREWELL, "작별인사", keep = true, setCaption = false)
            }
        }
        if (before != step.state) { renderStageOnly(); syncHome() }
        if (before.allLeftSaid != step.state.allLeftSaid) syncKeepAlive("하차 마무리")
        else if (before.departed != step.state.departed || before.parkedSinceMs != step.state.parkedSinceMs) {   // r10 H: 출발(D)·정차 시작이면 상한 시계를 바로 다시 정한다
            if (!tripCapLift()) scheduleTripCap()   // PM r10 수정 2: 상한 뒤 주행이면 바로 풀고 서비스를 다시 켠다
        }
    }

    /** 기어·게이트 변화 뒤(onGateInputsChanged 끝): 출발·미뤄 둔 도착 안내, 주행 중 남은 시간 조회 켜기/끄기 */
    private fun tripGuideGear() {
        if (destroyed) return
        val driving = tripDriving()
        if (driving || lockReason() != null) stopArrivalListen(if (driving) "주행(D)" else "잠금")   // r5 추가 A
        naviTripEnd?.setPolling(driving)
        if (driving && TripGuideStore.alerts.leftBehind != LeftBehindState()) {   // 2단계 D: 주행 재개 = 두고 내림 확인 취소(다시 정차하면 새로 센다)
            TripGuideStore.alerts = TripGuideStore.alerts.copy(leftBehind = LeftBehindRule.reset())
            ui.removeCallbacks(leftBehindRunnable)
            log("[두고내림] 주행 재개 → 취소")
        }
        if (driving) maybeStartLegAfterArrival()   // r10 L: 도착 뒤 배정 자리에 앉은 채 다시 출발 = 같은 이동의 새 구간(onGear 전에)
        applyTrip(TripGuide.onGear(TripGuideStore.state, driving, tripVoicePark() && !driving, SystemClock.elapsedRealtime()), "기어")
        if (driving) maybeReviveAfterContinue()   // r10 K: 주행 = 정차 경계 → 자동 이어 가기 무장 풀림(이후 기존 [새로 출발 확인])
        // r8 4절: 주행 중이라 버렸던 중간 하차 안내·스티커 한 줄을 유효 P에서 다시(도착 안내는 위 onGear가 다시 냄 — 순서: 도착 → 중간 하차 → 스티커)
        val (retryLeft, again) = ParkVoiceRule.onPark(parkVoiceRetry, tripVoicePark(), driving,
            ArrivalAskRule.alightingByAnswer(ArrivalAskStore.state, TripGuideStore.state))
        parkVoiceRetry = retryLeft
        for (l in again) {
            log("[이동안내] 유효 P → 버렸던 안내 다시 종류=$l")
            when (l) {
                ParkVoiceRule.LABEL_ALIGHTING -> enqueueSay(ArrivalAskText.ALIGHT_SPOKEN, l, keep = true)
                ParkVoiceRule.LABEL_STICKER -> enqueueSay(StickerMomentRule.LINE, l, keep = true, setCaption = false)
            }
        }
        // r8 2절: 하차 마무리 없이 이동이 이어져 다시 주행하면 같은 자리로 벨트 감시를 다시 켠다(applyGear에서)
        evaluateAppFront("기어")   // 루프 r5 1절: 도착(또는 결정 36 목적지 없는 이동) 뒤 유효 P면 앱을 앞으로
        if (driving && TripGuideStore.state.departed) {   // 1(d) 교통 상식: 이번 이동의 첫 주행 시각을 남기고 점검 타이머를 건다
            TripGuideStore.alerts = TripGuideStore.alerts.copy(tips = SafetyTipRule.onDrive(TripGuideStore.alerts.tips, SystemClock.elapsedRealtime()))
            ui.removeCallbacks(tipRunnable); ui.postDelayed(tipRunnable, tipTiming.tickMs)
        }
        requestAutoEval()
        maybeAskNotifPermission()
    }

    /**
     * r10 L(스위치 TripGuide.NEW_LEG_AFTER_ARRIVAL): 도착 안내가 나간 뒤 하차 마무리 전에 배정 자리에 착석 신호가 남은 채 주행을 보면 새 구간.
     * 도착 표시·5분·1분·예정 시간 소진을 지우고(TripGuide.onNewLeg) 이동 끝 신호 기록도 비운다(앞 구간 도착은 끝난 경로의 것 —
     * 남겨 두면 이동 경계 때 새 경로의 시간 값을 버린다, TripGuide.nextTrip). 이동 번호·스티커(이동당 1회)·벨트 감시 자리는 그대로이고,
     * 벨트 감시는 r8 2절(applyGear → maybeRearmBeltSession, tripActive)이 이미 다시 켠다. 다음 P의 질문·경로 판정은 기존 흐름이 한다
     * (경로 없음 → "목적지에 도착했나요?", 길 안내 중 → "내릴까요?"). 내비 길 안내 조회 상태(결정 41)는 주행에서 checkRouteLive가 비운다.
     */
    private fun maybeStartLegAfterArrival() {
        val tg = TripGuideStore.state
        val occ = TripGuide.legSeats(tg).associateWith { occSignal(it).first }
        if (!TripGuide.newLegDue(tg, driving = true, occ = occ)) return
        val step = TripGuide.onNewLeg(tg)
        tripEnd = tripEnd.copy(signalReceived = false)
        autoQueue.removeAll { it.trip == TripSayKind.ARRIVED }   // 아직 못 낸 도착 안내는 버린다(주행 중이라 어차피 못 냄)
        val seated = occ.filterValues { it == SeatOcc.OCCUPIED }.keys
        log("[이동안내] 도착 뒤 다시 출발(배정 자리 착석 신호 ${seated.joinToString()}) → 새 구간(r10 L), 이동 ${TripSessionStore.state.tripKey} 그대로, " +
            if (step.state.totalSec != null || step.state.remainSec != null) "도착 뒤 시작한 경로 시간 값 남김" else "도착한 경로 시간 값 버림")
        // PM-INTEGRATE(r10 M): 개발 7 이동 상태 저장(TripStateStore)이 TripGuideState를 저장하면 여기 바뀐 값(arrivalSignal=false 등)을 다시 저장
        applyTrip(step, "도착 뒤 다시 출발")
    }

    // ---- 2026-11-14 루프 r5 1절: 도착하면 앱이 스스로 앞으로(언제 = AppFrontRule, 방법 = AppFrontLauncher) ----
    private val appFrontVerify = Runnable { verifyAppFront() }

    /** 기어·도착 신호 뒤에 부른다(멱등, 정차당 1회). 유효 P + 플랫폼 허용(잠금 사유 없음)·주행 아님일 때만 띄운다 */
    private fun evaluateAppFront(why: String) {
        if (destroyed) return
        val parkOk = lockReason() == null && !tripDriving()
        val routeLive = checkRouteLive(parkOk, why)   // 결정 41(사용자 확정(11-15)): 도착 신호 없는 정차면 지금 경로가 있는지 보고, 없으면 옛 시간 값을 지운다
        evaluateArrivalAsk(parkOk, why, routeLive)   // r5 추가 A·r6 5절: 목적지 없는 이동이면 이번 정차에서 "목적지에 도착했나요?"(앞으로 올리기는 아래 공통)
        val askPending = ArrivalAskRule.askArrived(ArrivalAskStore.state, TripGuideStore.state, parkOk) ||
            ArrivalAskRule.askAlight(ArrivalAskStore.state, TripGuideStore.state, parkOk)   // r6 5절: "내릴까요?"도 앱을 앞으로
        if (TripGuideStore.state.drivingNow) AppFrontStore.handledThisStop = false   // 주행 = 정차 경계
        val d = AppFrontRule.decide(TripGuideStore.state, parkOk, started, AppFrontStore.handledThisStop, askPending)
        when (d.action) {
            AppFrontRule.Action.NONE -> return
            AppFrontRule.Action.MARK_VISIBLE -> {
                AppFrontStore.handledThisStop = true
                log("[앞으로] 이미 앱이 보임 → 띄우지 않음(${d.reason?.name}, $why)")
            }
            AppFrontRule.Action.LAUNCH -> {
                AppFrontStore.handledThisStop = true
                AppFrontStore.pending = d.reason; AppFrontStore.retried = false
                disarmHandoff("앱을 앞으로")
                val ok = AppFrontLauncher.launch(this, AppFrontLauncher.DEFAULT, why)
                log("[앞으로] ${d.reason?.name} 유효 P → 앱을 앞으로 ${AppFrontLauncher.DEFAULT.name} ${if (ok) "호출함" else "실패"}($why)")
                ui.removeCallbacks(appFrontVerify); ui.postDelayed(appFrontVerify, AppFrontRule.VERIFY_MS)
            }
        }
        // 결정 39 취소(사용자, 11-15): 앱을 앞으로 당길 뿐 내비 경로 안내는 끝내지 않는다(cancelRoute 호출 없음)
    }

    // ---- 2026-11-14 루프 r5 결정 41(사용자 확정(11-15)): 도착 신호 없는 정차의 '목적지 있는 이동' 판정(RouteLiveRule) ----
    private val routeQueryTimeout = Runnable { if (!destroyed) evaluateAppFront("경로 상태 조회 시간 초과") }

    /**
     * 반환: 이번 정차의 길 안내 판정(r6 5절 "내릴까요?" 조건). true = 길 안내 중(LIVE, 앱 내부 모의 경로 포함),
     * null = 아직 모름(조회 답 기다림)·판정 대상 아님. 경로가 없으면(GONE) 시간 값을 지우고 null(목적지 없는 이동 → "도착했나요?")
     */
    private fun checkRouteLive(parkOk: Boolean, why: String): Boolean? {
        val tg = TripGuideStore.state
        if (tg.drivingNow) {   // 주행 = 정차 경계: 다음 정차에서 새로 조회
            if (RouteLiveStore.query != RouteLiveRule.Query.None) { RouteLiveStore.query = RouteLiveRule.Query.None; ui.removeCallbacks(routeQueryTimeout) }
            return null
        }
        // r6 5절: 앱 내부 모의 경로(debug DEBUG_NAVI·모의 경로)는 길 안내 중으로 본다(실제 조회 대상 아님)
        if (parkOk && ArrivalAskRule.stopTrip(tg) && tg.parkedSinceMs >= 0 && AppFrontRule.hasRoute(tg) && tg.etaSource == SignalSource.MOCK) return true
        if (!RouteLiveRule.needsCheck(tg, parkOk)) return null
        val now = SystemClock.elapsedRealtime()
        if (RouteLiveStore.query == RouteLiveRule.Query.None) {
            val sent = naviTripEnd?.requestRouteState() == true
            RouteLiveStore.query = if (sent) RouteLiveRule.Query.Pending(now) else RouteLiveRule.Query.Failed
            log("[경로판정] 도착 신호 없는 정차 + 남은 시간 값 있음 → 내비 경로 상태 ${if (sent) "조회" else "조회 불가(시간 값 갱신 시각으로 판단)"}($why)")
            if (sent) { ui.removeCallbacks(routeQueryTimeout); ui.postDelayed(routeQueryTimeout, RouteLiveRule.QUERY_TIMEOUT_MS + 50L) }
        }
        return when (RouteLiveRule.verdict(tg, RouteLiveStore.query, RouteLiveStore.lastTimeAtMs, now)) {
            RouteLiveRule.Verdict.WAIT -> null
            RouteLiveRule.Verdict.LIVE -> true
            RouteLiveRule.Verdict.GONE -> {
                TripGuideStore.state = TripGuide.onRouteCleared(tg)
                log("[경로판정] 경로 없음(${RouteLiveStore.query.javaClass.simpleName}) → 옛 남은 시간 값을 버림(목적지 없는 이동, 결정 41)")
                null
            }
        }
    }

    /** getRouteStateInfo 답(결정 41). 기다리던 조회가 있을 때만 반영하고 다시 평가한다 */
    private fun onRouteStateAnswer(routing: Boolean, note: String) {
        if (destroyed) return
        if (RouteLiveStore.query !is RouteLiveRule.Query.Pending) { log("[경로판정] 경로 상태 답(안내 중=$routing, $note) — 기다리던 조회 없음, 무시"); return }
        ui.removeCallbacks(routeQueryTimeout)
        RouteLiveStore.query = RouteLiveRule.Query.Answered(routing)
        log("[경로판정] 내비 경로 상태 답: 안내 중=$routing ($note)")
        evaluateAppFront("경로 상태 답")
    }

    // ---- 2026-11-14 루프 r5 추가 A: "목적지에 도착했나요?"(판단 ArrivalAskRule, 화면 계약 v5 askArrived·arrivedByAnswer) ----
    private fun evaluateArrivalAsk(parkOk: Boolean, why: String, routeLive: Boolean? = null) {
        val st = ArrivalAskRule.evaluate(ArrivalAskStore.state, TripGuideStore.state, parkOk, routeLive)
        val changed = st.state != ArrivalAskStore.state
        ArrivalAskStore.state = st.state
        when (st.effect) {
            ArrivalAskRule.Effect.ASK -> {
                log("[도착질문] 목적지 없는 이동 + 유효 P → 묻기(정차당 1회, $why)")
                autoQueue.removeAll { it.label == ArrivalAskText.VOICE_LABEL }
                enqueueSay(ArrivalAskText.QUESTION, ArrivalAskText.VOICE_LABEL, keep = true) {
                    ArrivalAskRule.askArrived(ArrivalAskStore.state, TripGuideStore.state, lockReason() == null && !tripDriving())
                }
                leftBehindEvaluate()   // 결정 36-1(사용자 확정(11-15)) 스위치를 끈 경우 대답 전까지 두고 내림 대기를 멈춘다
            }
            ArrivalAskRule.Effect.ASK_ALIGHT -> {
                log("[내릴질문] 길 안내 중 이동 + 도착 신호 없음 + 유효 P → \"${st.state.alightText}\"(정차당 1회, $why)")
                askAlightVoice(st.state.alightText)
                leftBehindEvaluate()
            }
            ArrivalAskRule.Effect.DROVE_OFF -> { stopArrivalListen("주행(D)"); log("[도착질문] 대답 없이 주행 → 아니요로 봄(이동 계속)") }
            else -> {}
        }
        if (changed) renderStageOnly()
    }

    /**
     * 질문 음성이 끝났을 때 부르는 훅(onAutoSayResult). spoken=false면 끝까지 말하지 못함(중지·포커스 거부 등).
     * 개발 B가 음성 대답(STT)을 붙일 자리: 여기서 듣기를 시작하고, 인식 결과로 [onArrivalAnswer](true/false)를 부른다.
     * 지금은 로그만 남긴다
     */
    private fun onArrivalQuestionSpoken(spoken: Boolean, alight: Boolean = false) {
        if (destroyed) return
        val parkOk = lockReason() == null && !tripDriving()
        // r6 5절: "내릴까요?"도 같은 훅·같은 5초 듣기·같은 설정 스위치('음성으로 답하기')
        val asking = if (alight) ArrivalAskRule.askAlight(ArrivalAskStore.state, TripGuideStore.state, parkOk)
            else ArrivalAskRule.askArrived(ArrivalAskStore.state, TripGuideStore.state, parkOk)
        arrivalListenAlight = alight
        log("[도착질문] 질문 음성 끝(${if (spoken) "다 말함" else "끝까지 못 함"}, 대답 대기=$asking)")
        // r5 추가 A(개발 B): 음성 대답은 덧붙임. 막히면 버튼만(로그 한 줄). 판정은 ArrivalAnswerRule
        val block = ArrivalAnswerRule.listenBlock(voiceEnabled, sttReady && stt != null, spoken, asking, parkOk, tts?.naviBusy == true)
            ?: if (voiceActive || arrivalListening) "다른 음성 인식 진행 중" else null
        if (block != null) { log("[도착질문] 음성 대답 듣지 않음($block) → 버튼만"); return }
        startArrivalListen()
    }

    // ---- r5 추가 A(개발 B): 도착 질문 음성 대답(Pleos STT ON_DEVICE). 질문 음성이 끝난 뒤 [ArrivalAnswerRule.LISTEN_MS]만 듣는다 ----
    // 인식 원문은 로그·화면·저장소에 남기지 않는다(판정값만). 듣는 동안 앱 TTS 대기 줄은 멈춘다(pumpAuto).
    private var arrivalListening = false
    /** r6 5절: 지금 듣는 대답이 "내릴까요?"의 것인가(아니면 "도착했나요?") */
    private var arrivalListenAlight = false
    private var arrivalListenGen = 0
    private var arrivalListener: ResultListener? = null
    private val arrivalListenTimeout = Runnable { if (arrivalListening) { stopArrivalListen("${ArrivalAnswerRule.LISTEN_MS / 1000}초 안에 대답 없음"); log("[도착질문] 음성 대답 없음 → 버튼만") } }

    private fun startArrivalListen() {
        val s = stt ?: return
        arrivalListenGen++
        val gen = arrivalListenGen
        val l = object : ResultListener {
            override fun onUpdated(p0: String, p1: Boolean) {
                if (!p1) return   // 중간 결과 무시
                val forAlight = arrivalListenAlight
                val a = if (forAlight) ArrivalAnswerRule.decideAlight(p0) else ArrivalAnswerRule.decide(p0)   // 원문은 여기서만 쓰고 버린다
                ui.post {
                    if (destroyed || gen != arrivalListenGen || !arrivalListening) return@post
                    stopArrivalListen("음성 결과-${a.name}")
                    when (a) {
                        ArrivalAnswer.ARRIVED -> { log("[${if (forAlight) "내릴질문" else "도착질문"}] 음성 대답 = 예"); if (forAlight) onAlightAnswer(true) else onArrivalAnswer(true) }
                        ArrivalAnswer.NOT_YET -> { log("[${if (forAlight) "내릴질문" else "도착질문"}] 음성 대답 = 아니요"); if (forAlight) onAlightAnswer(false) else onArrivalAnswer(false) }
                        ArrivalAnswer.NONE -> log("[도착질문] 음성 대답 못 알아들음 → 버튼만")
                    }
                }
            }
            override fun onUpdatedEpdData(p0: Long, p1: Long) {}
            override fun onStartedRecognition() {}
            override fun onEndedRecognition() {}   // 최종 결과가 이 콜백 뒤에 오기도 한다(에뮬 측정) → 끝내지 않고 시간 상한으로
            override fun onError(p0: ErrorType) {
                val name = p0.name
                ui.post {
                    if (destroyed || gen != arrivalListenGen || !arrivalListening) return@post
                    stopArrivalListen("오류 $name"); log("[도착질문] 음성 인식 오류 → 버튼만")
                }
            }
            override fun onReady() {}
            override fun onUpdatedRms(p0: Double) {}
        }
        arrivalListening = true
        try {
            s.addListener(l); arrivalListener = l
            s.request()
            log("[도착질문] 음성 대답 듣기 시작(${ArrivalAnswerRule.LISTEN_MS / 1000}초, 원문은 남기지 않음)")
            ui.removeCallbacks(arrivalListenTimeout); ui.postDelayed(arrivalListenTimeout, ArrivalAnswerRule.LISTEN_MS)
        } catch (t: Throwable) {
            arrivalListening = false; arrivalListenGen++
            runCatching { s.removeListener(l) }; arrivalListener = null
            log("[도착질문] 음성 듣기 시작 실패 ${t.javaClass.simpleName} → 버튼만")
        }
    }

    /** 듣기 멈춤(멱등). 버튼 대답·주행(D)·잠금·화면 이탈·종료·스위치 끔에서 부른다 */
    private fun stopArrivalListen(reason: String) {
        if (!arrivalListening) return
        arrivalListening = false; arrivalListenGen++
        ui.removeCallbacks(arrivalListenTimeout)
        arrivalListener?.let { l -> runCatching { stt?.removeListener(l) } }; arrivalListener = null
        try { stt?.stop() } catch (t: Throwable) { log("[도착질문] stop 예외 ${t.javaClass.simpleName}") }
        log("[도착질문] 음성 대답 듣기 끝(사유=$reason)")
        if (autoQueue.isNotEmpty()) ui.post { pumpAuto() }
    }

    /** r6 5절: "내릴까요?" 질문 음성(자동 안내 큐, 내비 음성 중·직후면 TtsGuide가 기다렸다가). 완료 콜백에서 음성 대답을 듣는다 */
    private fun askAlightVoice(text: String) {
        autoQueue.removeAll { it.label == ArrivalAskText.VOICE_LABEL || it.label == ArrivalAskText.ALIGHT_VOICE_LABEL }
        enqueueSay(text, ArrivalAskText.ALIGHT_VOICE_LABEL, keep = true) {
            ArrivalAskRule.askAlight(ArrivalAskStore.state, TripGuideStore.state, lockReason() == null && !tripDriving())
        }
    }

    /**
     * r6 5절 계약 v6 StageActions.onAlightAnswer: "내릴까요?" 대답(버튼·음성 공통). 유효 P·플랫폼 허용일 때만 반영.
     * 예 = 중간 하차(하차 안내 음성 → 아이가 내리면 하차 마무리·스티커·작별 인사 → 이동 끝, 내비 경로는 건드리지 않음).
     * 아니요 = 이동 계속("잠깐 멈췄어요", 하차 한 줄·두고 내림 없음, 내비를 앞으로)
     */
    private fun onAlightAnswer(alight: Boolean) {
        if (destroyed) return
        stopArrivalListen("대답 반영(내릴까요?)")
        val parkOk = lockReason() == null && !tripDriving()
        val st = ArrivalAskRule.onAlightAnswer(ArrivalAskStore.state, TripGuideStore.state, alight, parkOk)
        if (st.effect == ArrivalAskRule.Effect.NONE) { log("[내릴질문] 대답 무시(${if (alight) "예" else "아니요"}, 묻는 중 아님 또는 유효 P 아님)"); return }
        ArrivalAskStore.state = st.state
        autoQueue.removeAll { it.label == ArrivalAskText.ALIGHT_VOICE_LABEL }
        when (st.effect) {
            ArrivalAskRule.Effect.ALIGHT -> {
                log("[내릴질문] 예 → 중간 하차(하차 안내, 아이가 내리면 하차 마무리·이동 끝, 도착 아님)")
                enqueueSay(ArrivalAskText.ALIGHT_SPOKEN, ArrivalAskText.ALIGHTING_VOICE_LABEL, keep = true)
                grantStickerIfEarned("하차 안내(중간 하차)", StickerMomentRule.Moment.EXIT_GUIDE)   // r7 1절: 하차 안내 → 스티커 한 줄
                leftBehindEvaluate()
                // PM-INTEGRATE(r6 4절): 아이가 내렸는지는 개발 D의 하차 판정(문 열림 + 착석 비움, 문 신호 확인 불가면 착석만)이
                // TripGuide 모두 내림 진입점(지금 onOccupancy)에서 정한다. 중간 하차도 같은 진입점을 타므로 여기서 따로 부르지 않는다
            }
            ArrivalAskRule.Effect.CONTINUE -> {
                log("[내릴질문] 아니요 → 이동 계속(이번 정차 하차 한 줄·두고 내림 안내 없음)")
                leftBehindEvaluate()
                if (!launchNavi("내릴까요 아니요")) log("[내릴질문] 내비 실행 실패 → 화면 유지")
            }
            else -> {}
        }
        renderStageOnly(); syncHome()
    }

    /** 계약 v5 StageActions.onArrivalAnswer: 버튼·음성 대답 공통. 유효 P·플랫폼 허용일 때만 반영 */
    private fun onArrivalAnswer(arrived: Boolean) {
        if (destroyed) return
        stopArrivalListen("대답 반영(버튼·음성)")
        val parkOk = lockReason() == null && !tripDriving()
        val tg = TripGuideStore.state
        val st = ArrivalAskRule.onAnswer(ArrivalAskStore.state, tg, arrived, parkOk)
        if (st.effect == ArrivalAskRule.Effect.NONE) { log("[도착질문] 대답 무시(${if (arrived) "예" else "아니요"}, 묻는 중·되돌리기 아님 또는 유효 P 아님)"); return }
        ArrivalAskStore.state = st.state
        autoQueue.removeAll { it.label == ArrivalAskText.VOICE_LABEL }
        when (st.effect) {
            ArrivalAskRule.Effect.ARRIVE -> {
                log("[도착질문] 예 → 도착 흐름(도착 화면·도착 음성·하차)")
                applyTrip(TripGuide.onArrived(tg, parkAllowed = true, driving = false, nowMs = SystemClock.elapsedRealtime()), "도착 질문 예")
                leftBehindEvaluate()
            }
            ArrivalAskRule.Effect.ALIGHT_AFTER_NO -> {
                // r6 5절: "목적지에 도착했나요?" 아니요 → 바로 이어서 "내릴까요?"(앱은 앞에 그대로, 내비로 넘기지 않음)
                log("[도착질문] 아니요 → 이어서 \"${st.state.alightText}\"")
                askAlightVoice(st.state.alightText)
            }
            ArrivalAskRule.Effect.CONTINUE, ArrivalAskRule.Effect.UNDO_ARRIVAL -> {
                if (st.effect == ArrivalAskRule.Effect.UNDO_ARRIVAL) {
                    TripGuideStore.state = ArrivalAskRule.undoArrival(TripGuideStore.state)
                    autoQueue.removeAll { it.trip == TripSayKind.ARRIVED }
                    log("[도착질문] 예 뒤 아니요 → 도착 되돌림, 이동 계속")
                } else log("[도착질문] 아니요 → 이동 계속(이번 정차 도착 안내·하차 한 줄·두고 내림 안내 없음)")
                leftBehindEvaluate()   // 이번 정차 두고 내림 대기 취소
                if (!launchNavi("도착 질문 아니요")) log("[도착질문] 내비 실행 실패 → 화면 유지")
            }
            else -> {}
        }
        renderStageOnly(); syncHome()
    }

    /**
     * 띄우기 뒤 [AppFrontRule.VERIFY_MS]: 앱이 보이지 않으면 [AppFrontLauncher.RETRY]로 한 번 더, 그래도 안 보이면
     * (뒤 화면 시작 제한 등) 이동 알림 제목을 바꿔 대체 안내(음성은 그대로). 그 사이 주행·잠금이 되면 멈춘다
     */
    private fun verifyAppFront() {
        if (destroyed) return
        val r = AppFrontStore.pending ?: return
        if (started) { AppFrontStore.pending = null; AppFrontStore.retried = false; log("[앞으로] 앱 보임 확인(${r.name})"); return }
        if (lockReason() != null || tripDriving()) { AppFrontStore.pending = null; AppFrontStore.retried = false; log("[앞으로] 확인 전 유효 P 아님 → 그만"); return }
        if (!AppFrontStore.retried) {
            AppFrontStore.retried = true
            val ok = AppFrontLauncher.launch(this, AppFrontLauncher.RETRY, "다시 시도")
            log("[앞으로] ${AppFrontRule.VERIFY_MS}ms 안에 앱이 보이지 않음 → ${AppFrontLauncher.RETRY.name}로 다시 ${if (ok) "호출함" else "실패"}")
            ui.removeCallbacks(appFrontVerify); ui.postDelayed(appFrontVerify, AppFrontRule.VERIFY_MS)
            return
        }
        AppFrontStore.pending = null; AppFrontStore.retried = false
        val shown = TripKeepAliveService.showNotice(this, arrived = true)
        log("[앞으로] ${AppFrontRule.VERIFY_MS}ms 안에 앱이 보이지 않음 → 대체 알림 ${if (shown) "표시" else "못 함(이동 서비스 꺼짐)"}")
    }

    /** 내비 시간 값(실제 NaviHelper 또는 debug 모의). 숫자만 받는다 */
    private fun onNaviTime(raw: NaviTime, source: SignalSource) {
        if (destroyed) return
        if (source == SignalSource.MOCK) etaMockUsed = true
        // PM 결정 12(ISSUE-05): 0 이하는 '값 없음'. 실제·모의 모두 여기서 거른다
        NaviTimeRule.ignoredLog(raw)?.let { log("$it(${source.name})") }
        val t = NaviTimeRule.sanitize(raw)
        // r5 5절: 남은 시간 0 이하 = 경로 없음(결정 12). 출발 전이면 남겨 둔 옛 시간 값을 비운다(새 확인 시작 때 다시 읽은 응답 등)
        if (t.kind == NaviTimeKind.REMAIN && t.seconds == null) { if (raw.seconds != null) clearTimesIfNoRoute("남은 시간 0 이하"); return }
        if (source == SignalSource.REAL && t.kind != NaviTimeKind.CLEARED && t.seconds != null) RouteLiveStore.lastTimeAtMs = SystemClock.elapsedRealtime()   // 결정 41 대체 판단용
        val tg = TripGuideStore.state
        when (t.kind) {
            NaviTimeKind.ROUTE_STARTED -> {
                log("[이동안내] 경로 시작 총 ${t.seconds?.let { "${it}초" } ?: "값 없음"}(${source.name})")
                tripEnd = TripEndRule.onRouteStarted(tripEnd)   // PM 결정 18: 이 경로에서 주행을 볼 때까지 경로 종료 신호는 이동 끝이 아니다
                applyTrip(holdEtaForConfirm(TripGuide.onRouteStarted(tg, t.seconds, source)), "경로 시작")   // r10 F: 완료 음성 대기 중이면 따로 말하지 않음
            }
            NaviTimeKind.REMAIN -> applyTrip(holdEtaForConfirm(TripGuide.onRemain(tg, t.seconds, source)), "남은 시간")   // r10 F
            NaviTimeKind.CLEARED -> { log("[이동안내] 경로 취소 → 시간 값 비움"); TripGuideStore.state = TripGuide.onRouteCleared(tg); renderStageOnly() }
        }
    }

    /** r5 5절: 출발 전 + 시간 값이 있을 때만 '경로 없음' 근거로 시간 값을 비운다(TripGuide.onNoRoute). 출발한 이동은 그대로 */
    private fun clearTimesIfNoRoute(why: String) {
        val s = TripGuideStore.state
        if (s.departed || (s.totalSec == null && s.remainSec == null)) return
        TripGuideStore.state = TripGuide.onNoRoute(s)
        log("[이동안내] 출발 전 경로 없음($why) → 남겨 둔 시간 값 비움")
    }

    // ---- 2026-11-13 ISSUE-07(사용자 결정 D-1): 이동 중에만 foreground service(동결 방지). 판단은 TripKeepAliveRule ----
    /** onStart~onStop: 앱이 화면에 보이는가(FGS 시작·내비 전환·도착 음성 문구 판단) */
    private var started = false
    /** 결정 24 ②: 이동 서비스(FGS)가 켜졌거나 켜지는 중인가. 이때 이동 음성·확인 완료 상태를 화면 이탈로 끊지 않는다 */
    private val keepAliveOn: Boolean get() = TripKeepAlive.run != KeepAliveRun.OFF

    private fun keepAliveTail(): Boolean = TripKeepAliveRule.tailHold(TripGuideStore.state.allLeftSaid,
        speaking = autoSpeaking(), allLeftAtMs = TripKeepAlive.allLeftAtMs, nowMs = SystemClock.elapsedRealtime())

    private val keepAliveTailCheck = Runnable { if (!destroyed) syncKeepAlive("하차 마무리 음성 상한") }

    /** 이동 상태가 바뀔 때마다 부른다(멱등). START는 앱이 보일 때만, STOP은 하차 마무리 음성 꼬리를 기다린 뒤 */
    private fun syncKeepAlive(why: String) {
        if (destroyed) return
        syncTripState(why)   // r10 M: 이동 중이면 저장, 끝·취소면 지움
        syncQuickStartMark(why)   // r6 6절: 이동 중 표시·중간 하차 기록·정상 종료 지우기(같은 '이동 중' 판단, QuickStartRule.markAction)
        val tg = TripGuideStore.state
        // r10 수정 2(K): 무장한 하차 마무리는 되살림 창 동안 서비스를 끄지 않는다(꼬리 대기와 같은 자리로 STOP만 미룸)
        val reseatHold = ContinueReseatRule.holdsKeepAlive(ContinueReseatStore.state, tg, SystemClock.elapsedRealtime())
        if (reseatHold) {   // 화면 재생성으로 창 끝 점검이 사라졌을 수 있어 다시 건다
            ui.removeCallbacks(reseatWindowEnd)
            ContinueReseatRule.windowLeftMs(ContinueReseatStore.state, SystemClock.elapsedRealtime())?.let { ui.postDelayed(reseatWindowEnd, it + 100L) }
        }
        val tail = keepAliveTail()
        // r10 H: 이번 정차에서 상한으로 끝냈으면 앱이 보여도 다시 켜지 않는다(새 정차·이동 끝이면 풀림)
        when (TripKeepAliveRule.decide(TripKeepAliveRule.tripActive(tg), TripKeepAlive.run, started, tail || reseatHold, capped = tripCapCapped())) {
            KeepAliveAction.START -> startKeepAlive(why)
            KeepAliveAction.STOP -> stopKeepAlive(if (why == ContinueReseatRule.STOP_WINDOW_END || why == ContinueReseatRule.STOP_DISARMED) why
                else TripKeepAliveRule.stopReason(tg, why))
            KeepAliveAction.NONE -> if (tail) {   // 마무리 음성이 끝나는 콜백이 없을 때를 위한 상한 재확인
                ui.removeCallbacks(keepAliveTailCheck)
                ui.postDelayed(keepAliveTailCheck, TripKeepAliveRule.TAIL_MAX_MS + 100L)
            }
        }
        scheduleTripCap()   // r10 H: 확인 완료 뒤 출발 대기·정차 대기 시계
    }

    // ---- 2026-11-15 r10 H: 이동 알림 상한(TripCapRule). ① 확인 완료 뒤 출발 대기 ② 하차 안내 뒤(출발 뒤 유효 P) 정차 대기 ----
    // 시계는 이 화면의 메인 스레드 Handler다. 이동 상태(TripGuideStore)·차량 리스너의 주인이 Activity이고, 이동 서비스(FGS)가 켜진 동안은
    // 앱이 내비 뒤에 있어도 프로세스가 동결되지 않아 돈다. 동결됐다 풀리면 elapsedRealtime 기준이라 늦은 만큼 바로 처리한다.
    // 서비스(TripKeepAliveService)는 SDK·이동 상태를 갖지 않으므로 시계를 두지 않는다. 대기 시작 시각·상한 표시는 TripKeepAlive(프로세스 메모리).
    /** 상한 시간 값(기본 TripCapRule 상수, debug만 DEBUG_TRIP_CAP_FAST로 짧게) */
    private var tripCapTiming = TripCapRule.DEFAULT
    private val tripCapRunnable = Runnable { if (!destroyed) tripCapTick() }

    private fun tripCapCapped(): Boolean = TripCapRule.capped(TripKeepAlive.cappedStopMs, TripGuideStore.state)

    /** syncKeepAlive 끝·점검 뒤: 출발 대기 시작 시각을 맞추고 다음 점검을 예약한다(볼 것이 없으면 취소 = D·마무리·이동 끝) */
    private fun scheduleTripCap() {
        if (destroyed) return
        val tg = TripGuideStore.state
        val now = SystemClock.elapsedRealtime()
        val before = TripKeepAlive.departWaitSinceMs
        TripKeepAlive.departWaitSinceMs = TripCapRule.departMark(before, tg, now)
        if (TripCapRule.DEPART_WAIT_ENABLED && before < 0 && TripKeepAlive.departWaitSinceMs >= 0)
            log("[이동상한] 확인 완료 → 출발 대기 시작(${tripCapTiming.departWaitMs / 1000}초 안에 출발 신호가 없으면 이동 끝내기와 같게 정리)")
        if (before >= 0 && TripKeepAlive.departWaitSinceMs < 0) log("[이동상한] 출발 대기 끝(" + (if (tg.departed) "주행 신호 받음" else "확인 접음·이동 끝") + ")")
        ui.removeCallbacks(tripCapRunnable)
        val d = TripCapRule.nextCheckMs(tg, TripKeepAlive.departWaitSinceMs, tripCapCapped(), now, tripCapTiming) ?: return
        ui.postDelayed(tripCapRunnable, d + 50L)
    }

    /**
     * 상한 뒤 다시 주행해 새로 섰거나 이동이 끝났으면 표시를 풀고 서비스 판단을 다시 한다(다음 점검도 그 안에서 예약). 풀었으면 true.
     * PM r10 수정 2: 주행(D)으로 풀렸고 서비스가 꺼져 있으면 앱이 안 보여도 시작을 시도한다(Android 14 백그라운드 시작 제한이면 실패 로그,
     * 화면이 보일 때 다시). 서비스가 꺼진 동안 프로세스가 동결되면 D 자체를 늦게 받을 수 있다(미검증)
     */
    private fun tripCapLift(): Boolean {
        val wasCapped = TripKeepAlive.cappedStopMs >= 0
        val capped = tripCapCapped()
        if (!wasCapped || capped) return false
        TripKeepAlive.cappedStopMs = -1L
        val tg = TripGuideStore.state
        log("[이동상한] 새 정차·주행·이동 끝 → 상한 표시 풂")
        if (TripCapRule.restartOnDrive(wasCapped, capped, tg, TripKeepAlive.run)) {
            startKeepAlive("상한 뒤 주행(앱이 ${if (started) "보임" else "안 보임"})")
            scheduleTripCap()
        } else syncKeepAlive("이동 알림 상한 풂")
        return true
    }

    private fun tripCapTick() {
        val tg = TripGuideStore.state
        val now = SystemClock.elapsedRealtime()
        if (tripCapLift()) return
        val capped = tripCapCapped()
        // PM r10 수정 1·2: 배정 뒷자리가 모두 '비어 있음' 신호일 때만 정리·종료(앉음·확인 불가면 계속 기다림)
        val rearEmpty = TripCapRule.rearAllEmpty(tg, TripCapRule.rearTargets(tg).associateWith { occSignal(it).first })
        when (TripCapRule.decide(tg, TripKeepAlive.departWaitSinceMs, tripVoicePark(), rearEmpty, capped, now, tripCapTiming)) {
            TripCapRule.Action.END_UNDEPARTED -> {
                if (currentAutoPhase() == AutoPhase.CONFIRMED) {
                    log("[이동상한] 확인 완료 뒤 ${tripCapTiming.departWaitMs / 1000}초 동안 출발 신호 없음·배정 뒷자리 모두 빈 자리 신호 → 이동 끝내기와 같게 정리(확인 접고 홈, 스티커 없음, 이동 알림 끝)")
                    leaveConfirmedToHome()   // [이번 이동 끝내기](onFinish, CONFIRMED)와 같은 길. 그 안의 syncKeepAlive가 FGS를 끝낸다(아래 예약은 대상 없음 → 취소)
                } else log("[이동상한] 출발 대기 기한 지남, 지금 단계 ${currentAutoPhase()} → 정리 안 함(다음 점검)")
            }
            TripCapRule.Action.STOP_KEEPALIVE -> {
                TripKeepAlive.cappedStopMs = tg.parkedSinceMs
                log("[이동상한] 정차(유효 P) ${tripCapTiming.exitWaitMs / 1000}초 동안 하차 마무리 없음·배정 뒷자리 모두 빈 자리 신호(두고 내림 ${TripGuideStore.alerts.leftBehind.said}/${LeftBehindRule.MAX_SAYS}회)" +
                    " → 이동 서비스·알림만 끝냄(화면·이동 상태 그대로, [다 내렸어요] 가능)")
                stopKeepAlive("이동 알림 상한(정차 ${tripCapTiming.exitWaitMs / 1_000L}초)")
                // PM-INTEGRATE(r10 M): 이동 상태 저장(TripStateStore)이 들어오면, 서비스가 꺼진 뒤 프로세스가 정리돼도 앱을 열 때 하차 화면으로 복원되는지 확인
            }
            TripCapRule.Action.NONE -> {}
        }
        scheduleTripCap()
    }

    /**
     * r7 5절(임시 결정 50): 설정 '차를 켜면 자동으로 열기' 스위치(기본 켬). 바꾸면 바로 저장(AutoOpenStore), 다음 부팅부터 적용.
     * 잠금(유효 P·허용 아님)이면 바꾸지 않는다(다른 음성 스위치와 같은 규칙, switchRow는 잠금 때 숨김)
     */
    private fun autoOpenSwitch(): DuoSwitch {
        var on = AutoOpenStore.load(this)
        return DuoSwitch(this).apply {
            text = AutoOpenCopy.SWITCH_TITLE
            textSize = Readability.SWITCH_SP; setTextColor(Readability.TEXT_COLOR); minimumHeight = dp(Readability.MIN_TOUCH_DP)
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                if (lockReason() != null) { isChecked = on; return@setOnCheckedChangeListener }
                if (checked == on) return@setOnCheckedChangeListener
                on = checked
                val saved = AutoOpenStore.save(this@MainActivity, checked)
                log((if (checked) "[자동열기설정] 켜짐" else "[자동열기설정] 꺼짐") + if (saved) "" else " (저장 실패: 다음 부팅 때 저장된 값 또는 켬)")
            }
        }
    }

    private fun startKeepAlive(why: String) {
        TripKeepAlive.wanted = true
        TripKeepAlive.allLeftAtMs = -1L
        try {
            startForegroundService(android.content.Intent(this, TripKeepAliveService::class.java))
            TripKeepAlive.run = KeepAliveRun.STARTING
            log("[FGS] start 사유=$why")
        } catch (e: Exception) {   // Android 14 백그라운드 시작 제한 등: 다음에 화면이 보일 때(onStart) 다시 판단
            TripKeepAlive.run = KeepAliveRun.OFF
            log("[FGS] start 실패 사유=$why ${e.javaClass.simpleName} → 화면이 보일 때 다시 시도")
        }
    }

    /** 즉시 종료(판단 없이). 시작 중이면 서비스가 startForeground 뒤 스스로 끝낸다(TripKeepAlive.wanted=false) */
    private fun stopKeepAlive(why: String) {
        ui.removeCallbacks(keepAliveTailCheck)
        TripKeepAlive.wanted = false
        TripKeepAlive.allLeftAtMs = -1L
        when (TripKeepAlive.run) {
            KeepAliveRun.OFF -> return
            KeepAliveRun.STARTING -> log("[FGS] stop 사유=$why (시작 중 → 서비스가 포그라운드 진입 직후 스스로 종료)")
            KeepAliveRun.ON -> {
                runCatching { stopService(android.content.Intent(this, TripKeepAliveService::class.java)) }
                TripKeepAlive.run = KeepAliveRun.OFF
                log("[FGS] stop 사유=$why")
            }
        }
    }

    // ---- 2026-11-13 B: 확인 완료 음성이 끝나면 내비 앱을 앞으로(앱은 뒤에서 음성만). 판단은 NaviHandoffRule ----
    private var handoffArmed = false
    private val handoffRunnable = Runnable { evaluateHandoff("안전망") }
    /** 결정 24 ①: 확인 완료 음성(+예정 시간)의 완료 콜백 전인가(말하기 전 대기 포함). true면 내비로 넘기지 않는다(안전망 시간 뒤는 예외) */
    private var completeVoicePending = false
    /** 넘기기를 예약한 시각(안전망 시간 계산) */
    private var handoffArmedAtMs = 0L

    private fun armHandoff() {
        handoffArmed = true
        handoffArmedAtMs = SystemClock.elapsedRealtime()
        ui.removeCallbacks(handoffRunnable)
        ui.postDelayed(handoffRunnable, NaviHandoffRule.FALLBACK_MS)
    }

    private fun disarmHandoff(why: String) {
        ui.removeCallbacks(handoffRunnable)
        completeVoicePending = false
        if (!handoffArmed) return
        handoffArmed = false
        log("[내비전환] 취소($why)")
    }

    private fun evaluateHandoff(why: String) {
        if (destroyed || !handoffArmed) return
        val speaking = autoSpeaking()
        val appMock = mockGearOn || devTools.scenarioRunning()
        val fallback = SystemClock.elapsedRealtime() - handoffArmedAtMs >= NaviHandoffRule.FALLBACK_MS
        when (NaviHandoffRule.decide(handoffArmed, TripKeepAliveRule.tripActive(TripGuideStore.state), speaking, started, appMock,
            completeVoicePending = completeVoicePending, fallbackElapsed = fallback)) {
            HandoffDecision.SKIP -> disarmHandoff("조건 아님: $why, 보임=$started" + if (appMock) ", 앱 내부 모의 기어·시연 중" else "")
            HandoffDecision.WAIT -> {
                if (completeVoicePending && !speaking && why != "안전망") log("[내비전환] 확인 완료 음성이 끝나기 전 → 기다림($why, 결정 24)")
                ui.removeCallbacks(handoffRunnable); ui.postDelayed(handoffRunnable, HANDOFF_RECHECK_MS)
            }
            HandoffDecision.LAUNCH -> {
                if (completeVoicePending) log("[내비전환] 안전망 시간 지남 → 완료 음성 콜백 없이 넘김")
                handoffArmed = false; ui.removeCallbacks(handoffRunnable); launchNavi(why)
            }
        }
    }

    /** Pleos 내비(패키지 실행 intent) → 없으면 지도 앱 기본(APP_MAPS). 실패하면 앱 화면을 그대로 둔다 */
    /** 내비 앱을 앞으로. 실행했으면 true(확인 완료 뒤 전환·뒤로가기 TO_NAVI 공용) */
    private fun launchNavi(why: String): Boolean {
        val byPkg = runCatching { packageManager.getLaunchIntentForPackage(NaviHandoffRule.NAVI_PACKAGE) }.getOrNull()
        val i = (byPkg ?: android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_MAPS))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
            log("[내비전환] 내비 앱을 앞으로($why, ${if (byPkg != null) "패키지 실행" else "APP_MAPS"}) → 앱은 뒤에서 음성만")
            return true
        } catch (e: Exception) {
            log("[내비전환] 실패 ${e.javaClass.simpleName} → 앱 화면 유지")
            return false
        }
    }

    // ---- 알림 권한(POST_NOTIFICATIONS): 유효 P + 정차 허용 + 보이는 홈(흐름 시작 전)에서 설치당 1회만 묻는다. 거부해도 서비스는 돈다 ----
    private var notifAskInFlight = false

    private fun maybeAskNotifPermission() {
        if (destroyed) return
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk < 33) return
        val granted = checkSelfPermission(NOTIF_PERM) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PERM_PREFS, MODE_PRIVATE)
        val homeIdle = resumed && homeActive && flow.step == BoardingStep.HOME && gState == GState.IDLE && !settingsOpen
        // 2026-11-14: 자동 안내는 설치당 1회(안내 창을 본 적이 있거나 이미 요청했으면 다시 띄우지 않는다). 그 뒤에는 설정 "이동 안내 알림"에서
        val introDone = prefs.getBoolean(KEY_NOTIF_ASKED, false) || prefs.getBoolean(KEY_NOTIF_INTRO, false)
        if (!NotifPermissionRule.shouldAsk(sdk, granted, introDone, tripParkAllowed(), homeIdle, notifAskInFlight)) return
        if (devADialog != null) return
        prefs.edit().putBoolean(KEY_NOTIF_INTRO, true).apply()
        // 시스템 권한 창 직전에 왜 필요한지 한 줄을 먼저 보인다(꺼도 음성 안내는 나온다는 것 포함)
        log("[알림권한] 안내 창(홈, 유효 P): 왜 필요한지 먼저 보임")
        showDevADialog(NotifSettingRule.WHY, NotifSettingRule.ASK_YES, NotifSettingRule.ASK_LATER,
            onYes = {
                if (tripParkAllowed() && lockReason() == null) requestNotifPermission("홈 첫 안내")
                else log("[알림권한] 안내 창 확인 순간 유효 P·허용 아님 → 요청하지 않음(설정에서 다시 가능)")
            },
            onNo = { log("[알림권한] 나중에 → 설정의 이동 안내 알림에서 다시 허용할 수 있음") })
    }

    // ---- 2026-11-14 사용자 지시(P1): 설정 "이동 안내 알림"·[앱 나가기]. 판단은 NotifSettingRule·AppExitRule(AppExit.kt) ----
    private var notifStateView: TextView? = null
    private var notifButton: DuoButton? = null
    private var notifNoteView: TextView? = null
    /** 개발 A 확인 창(알림 안내·앱 나가기 확인) 하나만 */
    private var devADialog: android.app.AlertDialog? = null

    private fun notifPermissionGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(NOTIF_PERM) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun notifEnabledInSystem(): Boolean =
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled() }.getOrDefault(false)
    private fun notifAskedBefore(): Boolean = getSharedPreferences(PERM_PREFS, MODE_PRIVATE).getBoolean(KEY_NOTIF_ASKED, false)
    private fun notifCanAskAgain(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(NOTIF_PERM)

    /** 설정 카드의 상태 글자·버튼 갱신(설정을 열 때, 권한 결과·시스템 설정에서 돌아올 때) */
    private fun refreshNotifSetting() {
        val sv = notifStateView ?: return
        val sdk = android.os.Build.VERSION.SDK_INT
        val g = notifPermissionGranted(); val en = notifEnabledInSystem()
        val st = NotifSettingRule.state(sdk, g, en)
        sv.text = NotifSettingRule.stateText(st)
        val label = NotifSettingRule.buttonText(sdk, g, en, notifAskedBefore(), notifCanAskAgain())
        notifButton?.let { b ->
            b.visibility = if (label == null) android.view.View.GONE else android.view.View.VISIBLE
            if (label != null) b.text = label
        }
        if (st == NotifState.ON) notifNoteView?.visibility = android.view.View.GONE
    }

    private fun onNotifSettingTap() {
        if (destroyed) return
        val sdk = android.os.Build.VERSION.SDK_INT
        val park = tripParkAllowed() && lockReason() == null
        when (NotifSettingRule.action(sdk, notifPermissionGranted(), notifEnabledInSystem(), notifAskedBefore(), notifCanAskAgain(), park, notifAskInFlight)) {
            NotifAction.REFUSE -> log("[알림설정] 무시(유효 P·플랫폼 허용 아님 또는 요청 진행 중)")
            NotifAction.NONE -> refreshNotifSetting()
            NotifAction.REQUEST -> requestNotifPermission("설정")
            NotifAction.OPEN_SETTINGS -> openNotifSystemSettings()
        }
    }

    private fun requestNotifPermission(from: String) {
        getSharedPreferences(PERM_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        notifAskInFlight = true
        markSettingsExternal("알림 권한 창")
        log("[알림권한] 요청($from) — 이동 중 알림 표시용, 거부해도 이동 음성 안내는 동작")
        requestPermissions(arrayOf(NOTIF_PERM), REQ_NOTIF)
    }

    /** 다시 물을 수 없을 때: 시스템의 앱 알림 설정(없으면 앱 정보 화면)을 연다. 둘 다 없으면 안내 글만 */
    private fun openNotifSystemSettings() {
        val tries = listOf(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")),
        )
        for ((i, intent) in tries.withIndex()) {
            try {
                startActivity(intent)
                log("[알림설정] 시스템 ${if (i == 0) "앱 알림 설정" else "앱 정보 화면"} 열기")
                markSettingsExternal("시스템 알림 설정")
                return
            } catch (e: Exception) {
                log("[알림설정] 화면 열기 실패(${e.javaClass.simpleName})")
            }
        }
        notifNoteView?.visibility = android.view.View.VISIBLE
        log("[알림설정] 이 기기에는 열 수 있는 알림 설정 화면이 없음 → 안내 글만 보임")
    }

    private fun showDevADialog(message: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
        if (destroyed || devADialog != null) return
        // 루프 r1 K2: 앱 모양 대화상자(AppDialogs). 동작은 예전과 같다(뒤로·바깥 = 취소 = onNo)
        val d = AppDialogs.confirm(this, message, yes, no,
            onYes = { devADialog = null; onYes() },
            onNo = { devADialog = null; onNo() },
            onCancel = { devADialog = null; onNo() })
        devADialog = d
        d.show()
    }

    private fun closeDevADialog() {
        val d = devADialog ?: return
        devADialog = null
        try { d.dismiss() } catch (e: Exception) { }
    }

    // ---- 2026-11-14 PM 임시 결정 32: 시스템 뒤로가기(판단은 BackRule). finish하지 않는다(이동 중 음성·FGS 유지) ----
    // MainActivity는 android.app.Activity이고 Manifest에 enableOnBackInvokedCallback이 없어 targetSdk 34에서도 onBackPressed가 불린다.
    @Deprecated("Activity.onBackPressed (no OnBackInvokedCallback opt-in)")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (destroyed) { log("[뒤로] 화면 종료 중: 동작 없음"); return }
        val fr = firstRunScreen?.takeIf { it.visibility == android.view.View.VISIBLE }
        val onStage = currentLayer() == ScreenReturnRule.Layer.STAGE
        val phase = currentAutoPhase()
        val input = BackRule.Input(
            locked = lockReason() != null,
            dialogOpen = devADialog != null || finishConfirm != null,
            inputOpen = ::childPanel.isInitialized && childPanel.isInputOpen(),
            firstRunVisible = fr != null,
            firstRunStep = fr?.step ?: 0,
            firstRunReplay = firstRunReplay,
            settingsOpen = settingsOpen,
            checking = onStage && phase == AutoPhase.CHECKING,
            departReady = onStage && phase == AutoPhase.CONFIRMED,
            tripActive = TripKeepAliveRule.tripActive(TripGuideStore.state),
        )
        val action = BackRule.decide(input)
        log("[뒤로] $action (잠금=${input.locked} 설정=${input.settingsOpen} 단계=$phase)")
        when (action) {
            BackRule.Action.CLOSE_DIALOG -> {
                // 뒤로가기 = 취소(대화상자의 취소 처리와 같게: 알림 안내는 [나중에], 앱 나가기는 [계속 쓰기])
                devADialog?.let { d -> try { d.cancel() } catch (e: Exception) { devADialog = null } }
                closeManualFinishConfirm("뒤로가기")
            }
            BackRule.Action.CLOSE_INPUT -> if (::childPanel.isInitialized) childPanel.closeInputIfOpen()
            BackRule.Action.FIRST_RUN_PREV -> fr?.let { it.show(it.step - 1, firstRunReplay); log("[첫 안내] 뒤로 → ${it.step + 1}단계") }
            BackRule.Action.FIRST_RUN_CLOSE -> onFirstRunClose()
            BackRule.Action.CLOSE_SETTINGS -> closeStageSettings()
            BackRule.Action.CANCEL_CHECK -> cancelCheckToHome()
            BackRule.Action.LEAVE_READY -> leaveConfirmedToHome()
            // 루프 r2 A1(임시 결정, 사용자 확인 대기): 이동 중이면 확인 완료 때와 같은 내비 전환(이동·음성·FGS 유지), 실패면 화면 유지
            BackRule.Action.TO_NAVI -> { if (!launchNavi("뒤로가기")) log("[뒤로] 내비 실행 실패 → 화면 유지") }
            BackRule.Action.NONE -> log(BackRule.LOG_NONE)
        }
    }

    /** 설정 [앱 나가기]: 유효 P + 플랫폼 허용일 때만. 이동 중이면 확인 한 번 */
    private fun onAppExitTap() {
        if (destroyed) return
        when (AppExitRule.onTap(tripParkAllowed(), lockReason() != null, keepAliveOn, TripKeepAliveRule.tripActive(TripGuideStore.state))) {
            AppExitRule.Step.REFUSE -> log("[앱나가기] 무시(유효 P·플랫폼 허용 아님)")
            AppExitRule.Step.EXIT -> exitApp("이동 중 아님")
            AppExitRule.Step.CONFIRM -> {
                log("[앱나가기] 이동 중 → 확인 창")
                showDevADialog(AppExitRule.CONFIRM_TRIP, AppExitRule.CONFIRM_YES, AppExitRule.CONFIRM_NO,
                    onYes = {
                        if (AppExitRule.onConfirm(tripParkAllowed(), lockReason() != null) == AppExitRule.Step.EXIT) exitApp("이동 중 확인함")
                        else log("[앱나가기] 확인 순간 유효 P·허용 아님 → 끝내지 않음")
                    },
                    onNo = { log("[앱나가기] 취소(계속 쓰기)") })
            }
        }
    }

    /**
     * 앱 끝내기: 음성 중지 → 이동 안내 상태 비움(프로세스가 남아도 다음 실행이 지난 이동 안내를 이어 가지 않게) → 서비스·알림 종료 →
     * 작업 목록에서 제거. 차량·음성 리스너 해제는 onDestroy(isFinishing)가 한다. 이동 번호·스티커·하차 마무리 기록(저장값)은 건드리지 않는다
     */
    private fun exitApp(why: String) {
        log("[앱나가기] 종료($why): 음성 중지 → 이동 안내 끝 → 서비스·알림 정리 → 작업 목록에서 제거")
        closeManualFinishConfirm(null); closeDevADialog()
        stopVoice("앱 나가기"); stopArrivalListen("앱 나가기")
        ui.removeCallbacks(autoTick); ui.removeCallbacks(pumpRunnable); ui.removeCallbacks(tipRunnable)
        autoQueue.clear()
        chime?.stop()
        tts?.stop(true)   // 유지 대상(이동 음성·확인 완료 음성)까지 모두 멈춘다
        if (beltWarn.active) endBeltReleaseSession("앱 나가기")
        BeltSessionStore.take()
        TripGuideStore.state = TripGuideState(); resetTripAlerts("앱 나가기"); resetBubbleFeed("앱 나가기")
        syncTripState("앱 나가기")   // r10 M: 이동 안내를 끝냈으니 이동 중 상태도 지운다(다음 실행은 복원하지 않음, 빠른 시작 기록은 예전대로)
        disarmHandoff("앱 나가기")
        CheckSessionStore.saved = null
        HomeGreetStore.said = false   // 다음 실행은 새 세션(홈 인사 다시 1회)
        stopKeepAlive("앱 나가기")
        finishAndRemoveTask()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        notifAskInFlight = false
        val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        log("[알림권한] 결과=${if (ok) "허용" else "거부(서비스는 알림 없이 동작, 설정의 이동 안내 알림에서 다시 허용 가능)"}")
        refreshNotifSetting()
    }

    // ---- 스티커(2026-11-13): 조건 = 자동 확인 완료 또는 대체 버튼(이동 1회), 지급 = 하차 마무리 또는 이동 끝내기 ----
    private fun markStickerEarned() {
        val key = TripSessionStore.state.tripKey
        if (TripSessionStore.earnedTrip == key) return
        TripSessionStore.earnedTrip = key
        val ok = StickerLogStore.saveEarnedTrip(applicationContext, key)
        log("[스티커] 이동 $key 조건 충족(신호 확인 기준)" + if (ok) " 저장됨" else " (저장 실패: 재시작하면 조건 기록이 사라짐)")
    }

    /**
     * 조건을 채운 이동이면 스티커를 준다(이동당 1회). r7 1절: [moment] = 하차 안내(EXIT_GUIDE, 도착 음성·중간 하차 안내 뒤) / 하차 마무리 / 이동 끝내기.
     * 하차 안내 때는 안내 음성 뒤에 스티커 한 줄을 말한다(기록한 아이가 있을 때만). 이미 줬으면(되돌리기 뒤 포함, 임시 결정 48) 아무것도 하지 않는다
     */
    private fun grantStickerIfEarned(why: String, moment: StickerMomentRule.Moment = StickerMomentRule.Moment.ALL_LEFT) {
        val st = TripSessionStore.state
        if (!TripSessionRule.shouldGrant(st, TripSessionStore.earnedTrip, stickerGrantAttemptTrip == st.tripKey)) return
        stickerGrantAttemptTrip = st.tripKey
        stickerSessionKey += 1
        val after = TripSessionRule.markSticker(st)
        // r10 수정(J + 화면 재생성): 이번 이동 보관값이 있으면 그 아이만 기록한다(재생성된 패널의 지난 선택으로 기록하지 않음)
        val tripIds = TripKidsRule.grantIds(TripKidsStore.saved, st.tripKey)
        val noProfile = TripKidsRule.withoutProfile(TripKidsStore.saved, st.tripKey, confirmWithoutProfile)
        if (tripIds != null && ::childPanel.isInitialized && !noProfile) childPanel.selectForTrip(tripIds)
        val r = if (!noProfile && ::childPanel.isInitialized) childPanel.grantSticker(stickerSessionKey, after) else 0
        if (r > 0) { TripSessionStore.state = after; TripSessionStore.readFailed = false }
        stickerKids = maxOf(0, r)
        stickerKidsShown = stickerKids
        stickerRecordFailed = r < 0
        stickerJustGrantedUntil = SystemClock.elapsedRealtime() + STICKER_SHOW_MS
        stickerGrantedAt = moment
        ui.postDelayed({ if (!destroyed) renderStageOnly() }, STICKER_SHOW_MS + 100L)
        // r7 1절: 하차 안내 → 스티커 한 줄(손님 이동·기록 실패는 말하지 않음, 판도 숨김)
        if (moment == StickerMomentRule.Moment.EXIT_GUIDE && StickerMomentRule.speaksLine(newlyGranted = true, kidsRecorded = r)) {
            enqueueSay(StickerMomentRule.LINE, "스티커", keep = true, setCaption = false)
        }
        log("[스티커] 이동 ${st.tripKey} 지급($why): " + when {
            r == ChildProfilePanel.Grant.SAVE_FAILED -> "기록 저장 실패"
            r == ChildProfilePanel.Grant.READ_FAILED -> "기존 기록을 읽지 못해 저장 안 함"
            r == 0 -> "선택된 아이 없음·등록 없이 진행 → 화면 표시만"
            else -> "아이 ${r}명 기록(신호 확인 기준)"
        })
        pushStickerWeek(why)
        renderStageOnly()
    }

    // ---- 2026-11-13 2단계 D: 두고 내림 방지(LeftBehindRule). 실제 착석 신호(VC 착석 포함)와 debug 모의 착석이 같은 경로(occSignal) ----
    private val leftBehindRunnable = Runnable { if (!destroyed) leftBehindEvaluate() }
    /** debug 시연용 대기 단축(release는 DevMockGuard가 막아 항상 상수값) */
    private var leftBehindDemoFast = false
        set(v) { field = DevMockGuard.admit(v) }

    private fun leftBehindEvaluate() {
        if (destroyed) return
        val tg = TripGuideStore.state
        if (!tg.confirmed) { ui.removeCallbacks(leftBehindRunnable); return }
        if (ArrivalAskRule.holdsLeftBehind(ArrivalAskStore.state, tg)) {
            // r5 추가 A: 이번 정차에 '아니요'(잠깐 멈춤)를 받았으면 두고 내림 안내를 하지 않는다(결정 36-1(사용자 확정(11-15))을 끄면 대답 전에도)
            if (TripGuideStore.alerts.leftBehind != LeftBehindState()) log("[두고내림] 도착 질문 대답(아니요) → 이번 정차 안내 안 함")
            TripGuideStore.alerts = TripGuideStore.alerts.copy(leftBehind = LeftBehindRule.reset())
            ui.removeCallbacks(leftBehindRunnable)
            return
        }
        val occ = tg.occupiedAtConfirm.associateWith { occSignal(it).first }
        val first = if (leftBehindDemoFast) LEFT_BEHIND_DEMO_FIRST_MS else LeftBehindRule.FIRST_DELAY_MS
        val second = if (leftBehindDemoFast) LEFT_BEHIND_DEMO_SECOND_MS else LeftBehindRule.SECOND_DELAY_MS
        val now = SystemClock.elapsedRealtime()
        val (st, d) = LeftBehindRule.step(TripGuideStore.alerts.leftBehind, tg, occ, now, first, second)
        TripGuideStore.alerts = TripGuideStore.alerts.copy(leftBehind = st)
        ui.removeCallbacks(leftBehindRunnable)
        when (d) {
            is LeftBehindDecision.RecheckAt -> ui.postDelayed(leftBehindRunnable, (d.atMs - now).coerceAtLeast(0L) + 50L)
            is LeftBehindDecision.Speak -> {
                log("[두고내림] 정차 뒤 뒷자리 착석 신호 남음 ${d.seats.size}석 → 안내 ${st.said}/${LeftBehindRule.MAX_SAYS}(나가면 확정)" + if (leftBehindDemoFast) " (시연용 대기 단축)" else "")
                enqueueSay(d.text, LeftBehindRule.VOICE_LABEL, keep = true) {
                    // 보낼 때 다시 본다: 아직 정차 허용이고 확인 때 착석이던 뒷좌석 중 착석 신호가 남아 있을 때만
                    tripVoicePark() && TripGuideStore.state.occupiedAtConfirm.any { LeftBehindRule.isRear(it) && occSignal(it).first == SeatOcc.OCCUPIED }
                }
                // r8 5절: 줄에 세운 즉시 버려졌으면(포커스 거부·음성 꺼짐·조건 바뀜) 결과 콜백이 없다 → 바로 되돌림. 아니면 결과를 기다린다
                if (!leftBehindVoiceWaiting()) leftBehindNotDelivered("줄에 세우자마자 버려짐(포커스 거부·음성 꺼짐·조건 바뀜)")
                else { ui.removeCallbacks(leftBehindPendingCheck); ui.postDelayed(leftBehindPendingCheck, LeftBehindRule.PENDING_CHECK_MS) }
            }
            LeftBehindDecision.None -> if (st.pending) { ui.removeCallbacks(leftBehindPendingCheck); ui.postDelayed(leftBehindPendingCheck, LeftBehindRule.PENDING_CHECK_MS) }
        }
    }

    // ---- r8 5절: 두고 내림 안내는 실제로 나갔을 때(TTS 완료)만 횟수를 확정한다. 음성 줄(개발 B 구역)은 읽기만 하고 결과는 onAutoSayResult가 알린다 ----
    private val leftBehindPendingCheck = Runnable { leftBehindPendingTick() }

    /** 두고 내림 안내가 아직 줄에 있거나 재생 중인가 */
    private fun leftBehindVoiceWaiting(): Boolean =
        autoQueue.any { it.label == LeftBehindRule.VOICE_LABEL } || autoSayLabel == LeftBehindRule.VOICE_LABEL

    /** onAutoSayResult에서 부름(라벨이 두고 내림일 때). done = TtsEnd.DONE */
    private fun onLeftBehindSayResult(done: Boolean, why: String) {
        if (destroyed || !TripGuideStore.alerts.leftBehind.pending) return
        if (!done) { leftBehindNotDelivered("결과 $why"); return }
        ui.removeCallbacks(leftBehindPendingCheck)
        TripGuideStore.alerts = TripGuideStore.alerts.copy(leftBehind = LeftBehindRule.onDelivered(TripGuideStore.alerts.leftBehind))
        log("[두고내림] 안내가 나감 → ${TripGuideStore.alerts.leftBehind.said}/${LeftBehindRule.MAX_SAYS}회 확정")
        leftBehindEvaluate()   // 다음 안내 시각 예약
    }

    /** 못 나감: 미리 센 횟수를 되돌리고 [LeftBehindRule.RETRY_MS] 뒤 다시 평가(그 사이 착석 변화가 오면 더 일찍) */
    private fun leftBehindNotDelivered(why: String) {
        ui.removeCallbacks(leftBehindPendingCheck)
        TripGuideStore.alerts = TripGuideStore.alerts.copy(leftBehind = LeftBehindRule.onNotDelivered(TripGuideStore.alerts.leftBehind))
        log("[두고내림] 안내가 나가지 못함($why) → 횟수 되돌림 ${TripGuideStore.alerts.leftBehind.said}/${LeftBehindRule.MAX_SAYS}, ${LeftBehindRule.RETRY_MS / 1000}초 뒤 다시 시도")
        ui.removeCallbacks(leftBehindRunnable)
        ui.postDelayed(leftBehindRunnable, LeftBehindRule.RETRY_MS)
    }

    private fun leftBehindPendingTick() {
        if (destroyed || !TripGuideStore.alerts.leftBehind.pending) return
        if (leftBehindVoiceWaiting()) { ui.postDelayed(leftBehindPendingCheck, LeftBehindRule.PENDING_CHECK_MS); return }   // 아직 줄·재생 중(내비 음성 기다림 등)
        leftBehindNotDelivered("${LeftBehindRule.PENDING_CHECK_MS / 1000}초 동안 결과 없음(포커스 거부·때 놓침·버림)")
    }

    /** 이동 경계(새 확인·이동 끝내기·홈으로): D·B 상태와 타이머를 비운다 */
    private fun resetTripAlerts(why: String) {
        TripGuideStore.alerts = TripAlertState()
        guardianBuckledSeen = emptySet()   // r8b: 이동 경계에서 비운다
        parkVoiceRetry = ParkVoiceRule.Retry()   // r8 4절: 다시 낼 안내도 이동 경계에서 비운다
        ui.removeCallbacks(leftBehindRunnable)
        ui.removeCallbacks(releaseRepeatRunnable)   // 결정 31 다시 알림도 이동 경계에서 멈춘다
        ui.removeCallbacks(tipRunnable)
        log("[이동안내] 두고 내림·벨트 풀림 상태 초기화($why)")
    }

    /** 이번 주 스티커판 칸(선택한 아이, 없으면 등록된 아이 전체) */
    private fun stickerWeekCells(): List<BoardCell> {
        val ids = selectedChildren().map { it.id }.ifEmpty { stageProfiles().map { it.id } }
        return StickerWeekCells.combined(StickerLogStore.load(applicationContext), ids, java.time.LocalDate.now().toString())
    }

    /** 무대 스티커판 갱신(무대 생성·스티커 지급 직후). 2026-11-13 PM 통합: 디자인 StageScreen.setStickerWeek에 연결 */
    private fun pushStickerWeek(why: String) {
        if (destroyed) return
        val stage = stageScreen ?: return
        val cells = stickerWeekCells()
        stage.setStickerWeek(cells)
        log("[스티커판] 이번 주 ${StickerBoard.weekTotal(cells)}개($why)")
    }
}

/** 자동 확인 평가 주기(완료 흔들림 방지·재안내 간격 확인) */
private const val AUTO_TICK_MS = 500L
/** 설정 하단 띠 버튼 문구(PM 지시 5, 2026-11-13) */
private const val SETTINGS_BACK_TEXT = "앱으로 돌아가기"
/** ISSUE-08: 확인 완료 음성이 앞 자동 안내를 기다리는 최대 시간·점검 간격 */
private const val CHILD_NOTICE_WAIT_MAX_MS = 8_000L
private const val CHILD_NOTICE_WAIT_STEP_MS = 200L
/** 내비 전환을 기다리는 중(아직 말하는 중) 다시 볼 간격 */
private const val HANDOFF_RECHECK_MS = 1_000L
/** 알림 권한 요청 기록(설치당 1회) */
private const val PERM_PREFS = "toktok_permissions"
private const val KEY_NOTIF_ASKED = "notif_asked"
/** 2026-11-14: 홈의 알림 안내 창(왜 필요한지)을 보였는가. 시스템 권한 창 요청 여부([KEY_NOTIF_ASKED])와 따로 둔다 */
private const val KEY_NOTIF_INTRO = "notif_intro_shown"
/** android.Manifest.permission.POST_NOTIFICATIONS(API 33). 문자열로 둬 낮은 minSdk에서도 상수 참조 경고가 없게 한다 */
private const val NOTIF_PERM = "android.permission.POST_NOTIFICATIONS"
private const val REQ_NOTIF = 7102
/** 스티커 획득 연출을 보이는 시간 */
private const val STICKER_SHOW_MS = 10_000L
/** debug 시연용 두고 내림 대기(첫 안내·두 번째 안내). 기본값은 LeftBehindRule 상수 */
private const val LEFT_BEHIND_DEMO_FIRST_MS = 5_000L
private const val LEFT_BEHIND_DEMO_SECOND_MS = 10_000L
/** 시연 시나리오 중 홈 알림 */
private const val SCENARIO_NOTE = "모의 시연 중(앱 내부 모의 신호, 실제 차량 아님)"

