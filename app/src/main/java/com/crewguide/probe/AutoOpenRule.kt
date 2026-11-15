package com.crewguide.probe

import android.content.Context

// 2026-11-15 루프 r7 5절, 임시 결정 50(사용자 지시 "자동 켜기를 가능하도록 시도", 마켓 정책 미확인): 차를 다시 켜면 앱이 스스로 앞으로 와서
// 빠른 시작(r6 6절, QuickStartRule) 좌석 확인을 연다. 조건이 아니면(기록 없음·60분 지남·정상 종료·스위치 꺼짐) 아무것도 하지 않는다.
//
// 흐름: 부팅 완료(BOOT_COMPLETED, 사용자 10 잠금 해제 뒤. LOCKED_BOOT는 앱 저장소를 못 읽어 쓰지 않음) → [AutoOpenReceiver]가 조건 확인
//  → 이동 알림 서비스(TripKeepAliveService)를 '자동 열기' 모드로 시작(부팅 수신은 백그라운드 FGS 시작 예외) → 서비스 안에서 [AutoOpenWatcher]가
//  [TICK_MS]마다 내비 경로 상태·기어·플랫폼 제한을 보고 [step]으로 판단 → 띄우기(AppFrontLauncher: PendingIntent BAL 허용 → 안 보이면 startActivity)
//  → 그래도 안 보이면 서비스 알림 제목을 "눌러서 출발 전 확인을 시작해요"로(대체). 앱이 보이면(누가 열었든) 서비스는 자동 열기를 끝낸다.
// 내비 '경로 안내 재시작 [계속 안내]/[안내 종료]' 창(부팅 약 18초 뒤)을 가리지 않는다: 길 안내 중(naviMode ROUTE_GUIDING·SIMULATION_GUIDING)이
//  보이거나, 부팅 뒤 [NAV_DIALOG_WAIT_MS]가 지난 뒤에만 띄운다. 유효 P + 플랫폼 허용(앱이 앞이라고 가정한 게이트)일 때만 띄운다. 주행(D/R/N)을 보면 끝낸다.
// 저장하는 값: 스위치 하나(on/off). 빠른 시작 기록은 QuickStartStore(r6) 그대로 읽기만 한다.
// 되돌리기: [ENABLED] = false(수신기가 아무것도 안 함) 또는 설정 '차를 켜면 자동으로 열기' 끔. 완전히 빼려면 매니페스트의 수신기·RECEIVE_BOOT_COMPLETED를 지운다.

object AutoOpenRule {
    /**
     * 임시 결정 50 스위치(false면 부팅 수신기가 아무것도 하지 않는다).
     * 2026-11-15 r10 B(사용자 결정): 자동 켜기를 끈다 = false. 매니페스트에서 부팅 수신기·RECEIVE_BOOT_COMPLETED도 뺐다(코드는 남김).
     * 되돌리기: 이 값·[SETTING_VISIBLE]·PrivacyNotice.AUTO_OPEN_ENABLED_IN_NOTICE를 true로 + 매니페스트 권한·수신기 줄 복원(커밋 8f87871)
     */
    const val ENABLED = false
    /** r10 B: 설정의 '차를 다시 켜면 출발 전 확인 알림' 항목을 보일지(false = 숨김, 저장값 AutoOpenStore는 그대로 둔다) */
    const val SETTING_VISIBLE = false
    /** 길 안내 중이 보이지 않을 때 기다리는 시간(부팅 뒤 elapsedRealtime 기준). 내비 창이 스스로 사라지는 시간은 QA가 잰다(처음 값 90초) */
    const val NAV_DIALOG_WAIT_MS = 90_000L
    /** 판단 간격(경로 상태 조회·기어 조회) */
    const val TICK_MS = 2_000L
    /** Vehicle 초기화 뒤 첫 조회까지(MainActivity와 같은 2초) */
    const val VEHICLE_SETTLE_MS = 2_000L
    /** 띄운 뒤 앱이 보이는지 기다리는 시간. 지나도 안 보이면 다음 방법 */
    const val VERIFY_MS = 2_000L
    /** 띄우기 시도 수(1 = PendingIntent + BAL 허용, 2 = 서비스에서 startActivity). 다 쓰면 대체 알림 */
    const val MAX_LAUNCHES = 2
    /** 자동 열기 모드 상한(서비스 시작부터). 지나면 알림과 함께 끝낸다 */
    const val MAX_RUN_MS = 10 * 60_000L
    /**
     * r8 7절: 대체 알림을 새 채널(quick_restart_notice, IMPORTANCE_HIGH, 소리·진동 없음)·따로 번호로 내서 화면 위 배너(heads-up)를 시험한다(AutoOpenNotice).
     * false = r7 방식(이동 채널 FGS 알림 제목만 바꿈 → 알림 센터에서만 보임)
     */
    const val FALLBACK_HEADS_UP = false   // PM r8: QA 에뮬에서 HIGH 채널도 배너(heads-up)로 뜨지 않음(알림 센터에서만) → 검수 지시대로 r7 방식(FGS 알림 제목 바꿈)으로 되돌림

    enum class Step { WAIT, LAUNCH, FALLBACK, STOP }

    /** 부팅 수신 때 서비스를 시작할지: 스위치 둘 다 켜짐 + 빠른 시작 조건 + 앱이 아직 안 보임 */
    fun bootStart(enabled: Boolean, settingOn: Boolean, quick: QuickStartDecision, appVisible: Boolean): Boolean =
        enabled && settingOn && quick == QuickStartDecision.QUICK_START && !appVisible

    /** 앱이 앞이라고 가정한 정차 게이트(유효 P + 플랫폼 허용). 모의 입력은 쓰지 않는다 */
    fun gateFor(gearName: String?, gearOk: Boolean, platform: PlatformUx): VehicleGate =
        VehicleGateRule.evaluate(GateInput(gearName, gearOk, false, SignalOrigin.SDK, platform, foreground = true, mockAllowed = false))

    /** 주행 기어(P 아닌 유효 기어)를 받았는가. 받았으면 이번 자동 열기는 끝낸다(주행 중 띄우지 않음, 나중 정차에 갑자기 뜨지 않게) */
    fun driving(gearName: String?, gearOk: Boolean): Boolean = gearOk && gearLockReason(gearName, true, false) == LOCK_TEXT_NOT_PARK

    /** 내비 창을 가리지 않는 때인가: 길 안내 중이 보였거나 부팅 뒤 충분히 지남 */
    fun naviClear(routeGuiding: Boolean, sinceBootMs: Long): Boolean = routeGuiding || sinceBootMs >= NAV_DIALOG_WAIT_MS

    /**
     * 한 번의 판단. 우선순위: 끝낼 이유(조건 아님·앱이 보임·상한·주행) → 시도 소진(대체 알림 유지) → 방금 띄움(확인 대기) → 내비 창 → 게이트 → 띄움.
     * [sinceLaunchMs]: 마지막 띄우기부터 지난 시간(없으면 아무 값, launches=0이면 보지 않음)
     */
    fun step(quickDue: Boolean, appVisible: Boolean, runMs: Long, drivingSeen: Boolean, launches: Int, sinceLaunchMs: Long,
             routeGuiding: Boolean, sinceBootMs: Long, gate: VehicleGate): Step = when {
        !quickDue -> Step.STOP
        appVisible -> Step.STOP
        runMs > MAX_RUN_MS -> Step.STOP
        drivingSeen -> Step.STOP
        launches > 0 && sinceLaunchMs < VERIFY_MS -> Step.WAIT
        launches >= MAX_LAUNCHES -> Step.FALLBACK
        !naviClear(routeGuiding, sinceBootMs) -> Step.WAIT
        gate != VehicleGate.PARK_ALLOWED -> Step.WAIT
        else -> Step.LAUNCH
    }

    /**
     * r8 7절: 자동 열기를 끝낼 때 대체 알림을 남길지. 앱이 보임·주행·조건 사라짐이면 지운다(주행 중 배너 금지, 이미 열림).
     * 상한(10분)으로 끝나면 남긴다(알림이 스스로 [AutoOpenNotice.TIMEOUT_MS] 뒤 사라짐)
     */
    fun keepNoticeOnStop(quickDue: Boolean, appVisible: Boolean, drivingSeen: Boolean): Boolean = quickDue && !appVisible && !drivingSeen

    /** 끝낼 이유(로그용, [step]이 STOP일 때) */
    fun stopReason(quickDue: Boolean, appVisible: Boolean, runMs: Long, drivingSeen: Boolean): String = when {
        !quickDue -> "빠른 시작 조건 아님(기록 없음·60분 지남·앱이 기록을 씀)"
        appVisible -> "앱이 보임"
        runMs > MAX_RUN_MS -> "상한 ${MAX_RUN_MS / 60_000L}분 지남"
        drivingSeen -> "주행 기어를 받음(주행 중에는 띄우지 않음)"
        else -> "기타"
    }

    // ---- 설정 스위치 '차를 켜면 자동으로 열기'(기본 켬). "on"/"off", 그 밖은 기본값 ----
    const val DEFAULT_ON = true
    fun decodeSetting(text: String?): Boolean = when (text) { "on" -> true; "off" -> false; else -> DEFAULT_ON }
    fun encodeSetting(on: Boolean): String = if (on) "on" else "off"
}

/** 문구(신호 기준·짧게). 고지 한 줄(AUTO_OPEN_LINE)은 개발 C 담당 */
object AutoOpenCopy {
    const val SWITCH_TITLE = "차를 다시 켜면 출발 전 확인 알림"   // PM r7: 에뮬에서 앞으로 오기는 막히고 알림이 대신함 → 사실대로
    const val SWITCH_DESC = "지난 이동이 끝난 지 60분 안에 차를 다시 켜면, P일 때 출발 전 확인을 여는 알림을 띄워요."
    /** 서비스 알림: 기다리는 동안 */
    const val NOTICE_WAIT = "톡톡크루 · 다시 출발 준비 중 · 누르면 열려요"
    /** 서비스 알림: 띄우기를 못 했을 때(대체) */
    const val NOTICE_FALLBACK = "톡톡크루 · 눌러서 출발 전 확인을 시작해요"
}

/** 스위치 1비트 저장소(이 기기의 앱 안에만). 읽기 실패 = 기본값(켬) */
object AutoOpenStore {
    const val FILE_NAME = "crewguide_probe_auto_open"
    const val KEY = "auto_open_v1"

    fun load(context: Context): Boolean = try {
        AutoOpenRule.decodeSetting(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY, null))
    } catch (e: Exception) { AutoOpenRule.DEFAULT_ON }

    fun save(context: Context, on: Boolean): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putString(KEY, AutoOpenRule.encodeSetting(on)).commit()
    } catch (e: Exception) { false }
}
