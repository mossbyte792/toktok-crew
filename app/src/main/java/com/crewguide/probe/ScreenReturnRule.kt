package com.crewguide.probe

/**
 * 2026-11-13 PM 결정 13(ISSUE-06): 화면 이탈·복귀 때 어떤 화면을 보일지 정하는 순수 규칙.
 *
 * 차량 헤드유닛은 여러 창(TaskView)을 쓰므로 Activity가 "보이지만 resume 안 됨"(paused·started) 상태로 남을 수 있다
 * (예: 후방 카메라가 늦게 뜨거나 닫히는 동안). 예전에는 onPause가 무대·홈을 GONE으로 감추고 onResume만 되살려,
 * 그 사이 바닥의 설정 레이어가 머리 막대 없이 드러났다. 이제 onPause는 보이는 층을 바꾸지 않고,
 * 화면 복원은 onStart(보이기 시작)에서 한 번 하고, 같은 시작 주기의 onResume은 다시 하지 않는다.
 * 잠금(S09)은 복원 뒤 무대가 그린다: 기어·플랫폼 판정(VehicleGateRule·lockReason)은 이 규칙이 건드리지 않는다.
 */
object ScreenReturnRule {
    enum class Event { START, RESUME }

    enum class Restore { NONE, HOME, KEEP_STAGE }

    /** 화면 맨 위에 보이는 층. BARE_SETTINGS(머리 막대 없는 설정 바닥)는 사용자에게 보이면 안 된다 */
    enum class Layer { CUSTOMIZE, HOME, SETTINGS, LOCK, STAGE, BARE_SETTINGS }

    /**
     * onStart·onResume에서 화면 복원을 할지.
     * - START: 항상 복원한다(재생성 직후 한 번은 무대 유지, 그 밖은 홈. onResume이 하던 결정과 같다).
     * - RESUME: 이번 시작 주기에 onStart가 이미 복원했으면 하지 않는다(홈으로 두 번 튕기거나 무대 유지 표시가 사라지지 않게).
     *   onStop 없이 onPause→onResume만 온 경우는 예전처럼 복원한다.
     */
    fun restoreOn(event: Event, restoredThisStart: Boolean, keepStageOnce: Boolean): Restore = when {
        event == Event.RESUME && restoredThisStart -> Restore.NONE
        keepStageOnce -> Restore.KEEP_STAGE
        else -> Restore.HOME
    }

    /** 실제 뷰 표시 상태로 맨 위 층을 판정한다(덮는 순서: 꾸미기 > 홈 > 설정 > 무대). 무대는 잠금이면 S09 잠금 장면을 그린다 */
    fun layer(stageVisible: Boolean, homeVisible: Boolean, customizeVisible: Boolean, settingsOpen: Boolean, locked: Boolean): Layer = when {
        customizeVisible -> Layer.CUSTOMIZE
        homeVisible -> Layer.HOME
        settingsOpen -> Layer.SETTINGS
        stageVisible -> if (locked) Layer.LOCK else Layer.STAGE
        else -> Layer.BARE_SETTINGS
    }

    /** onPause는 보이는 층을 바꾸지 않는다(동작·그리기만 멈춤). 보이지만 resume 안 된 동안에도 직전 화면이 그대로 보인다 */
    const val PAUSE_CHANGES_LAYER = false

    /** 설정에서 외부(알림 권한 창·시스템 알림 설정)로 나간 뒤 이 시간 안에 돌아오면 설정을 다시 연다 */
    const val EXTERNAL_RETURN_WINDOW_MS = 120_000L

    /**
     * 2026-11-14 SCREEN_EXIT_CHECK ④: 설정에서 알림 권한 창이나 시스템 알림 설정에 다녀오면 홈이 아니라 설정으로 돌아온다.
     * leftAtMs = 설정에서 외부로 나간 시각(없으면 음수). 복원은 여전히 홈을 먼저 보이고(잠금 우선, 재조회 중 "기어 확인 중"),
     * 유효 P·플랫폼 허용이 되면 설정을 다시 연다(호출자: 기존 재생성 설정 복원 경로 maybeRestoreSettings). 시간이 지났으면 홈.
     */
    fun reopenSettingsAfterExternal(leftAtMs: Long, nowMs: Long, windowMs: Long = EXTERNAL_RETURN_WINDOW_MS): Boolean =
        leftAtMs >= 0 && nowMs >= leftAtMs && nowMs - leftAtMs <= windowMs
}

/**
 * 2026-11-13 PM 결정 13(ISSUE-06): TtsGuide.stop()에서 진행 중인 발화를 남길지 정하는 순수 규칙.
 * 화면 이탈 정리는 onPause가 아니라 onStop에서 하고, 이때 하차 한 줄(keepExit)·주행 벨트 안내·출발/도착 자동 안내(keepDrive)는 남긴다.
 * 오디오 포커스 규칙(포커스 없으면 재생 안 함, 차량 경고 우선)은 TtsGuide·AudioFocusRule이 그대로 지킨다.
 */
object TtsKeepRule {
    data class Pending(
        val active: Boolean,
        val example: Boolean = false,
        val drive: Boolean = false,
        val auto: Boolean = false,
        val autoKeep: Boolean = false,
        val exit: Boolean = false,
        /** 2026-11-14 PM 결정 24 ②: 이동(FGS) 중 확인 완료 음성(일반 안내로 낸다) */
        val tripKeep: Boolean = false,
    )

    fun keeps(p: Pending, keepExample: Boolean, keepDrive: Boolean, keepExit: Boolean): Boolean {
        if (!p.active) return false
        if (keepExample && p.example) return true      // 예시 발화는 일반 화면 갱신으로 끊지 않는다
        if (keepDrive && p.drive) return true          // 주행 안내는 P 아님 잠금 정리로 끊지 않는다
        if (keepDrive && p.auto && p.autoKeep) return true   // 출발·도착 자동 안내도 같은 정리로 끊지 않는다(2026-11-13)
        if (keepDrive && p.tripKeep) return true       // 결정 24 ②: 이동 중 확인 완료 음성도 화면 이탈·잠금 정리로 끊지 않는다
        if (keepExit && p.exit) return true            // 하차 한 줄은 화면 이탈(onStop)로 끊지 않는다(결정 13)
        return false
    }

    /**
     * 화면 갱신(잠금 정리)에서 하차 한 줄을 남길지: 지금 잠금(freshLock)이 있지만 마지막으로 받은 값(rawLock)으로는 잠금이 아니면
     * 복귀 재조회 대기 중일 뿐이므로 남긴다. 실제 잠금 사유가 있거나 잠금이 없으면(일반 정리) false
     */
    fun keepExitOnRecheckLock(freshLock: String?, rawLock: String?): Boolean = freshLock != null && rawLock == null

    /** onStop 화면 이탈 정리에 쓰는 값: 예시·질문·일반 안내는 끊고, 주행·자동(유지)·하차 한 줄은 남긴다 */
    const val ON_STOP_KEEP_EXAMPLE = false
    const val ON_STOP_KEEP_DRIVE = true
    const val ON_STOP_KEEP_EXIT = true
}
