package com.crewguide.probe

// 2026-11-14 사용자 지시(P1): 설정의 [앱 나가기]와 "이동 안내 알림" 항목. 순수 규칙·문구(Android 클래스 없음). 화면·시스템 호출은 MainActivity.
// 문구 원칙: 신호·사실 기준, 보증 표현(안전합니다·착용 완료·확실·올바르게·모두 안전해요·완료) 금지(AppExitRuleTest가 검사).

/**
 * [앱 나가기]: 유효 P + 플랫폼 허용일 때만. 이동 중(이동 서비스 켜짐·켜지는 중, 또는 이동 안내 진행 중)이면 "이동 안내도 함께 끝나요" 확인 한 번,
 * 아니면 바로 종료. 종료 = 음성 중지 → 이동 안내 상태 비움 → 서비스·알림 종료 → 리스너 해제(onDestroy) → 작업 목록에서 제거.
 * 위치·모양: 설정 본문 맨 아래 별도 카드 "앱 나가기" 안의 주의 모양 버튼(DuoTokens.Kind.DANGER: 중립 바탕 + 주의색 글자, 지우기 버튼과 같은 모양).
 * [앱으로 돌아가기](화면 아래 고정 띠 오른쪽의 파랑 버튼)와 자리·색이 다르다.
 */
object AppExitRule {
    const val CARD_TITLE = "앱 나가기"
    const val BUTTON = "앱 나가기"
    const val NOTE = "앱을 끝내고 내비나 홈 화면으로 가요. 주차(P) 상태에서만 할 수 있어요. 아이 별명·스티커 기록·설정은 그대로 남아요."
    const val CONFIRM_TRIP = "이동 안내도 함께 끝나요. 앱을 나갈까요?"
    const val CONFIRM_YES = "나가기"
    const val CONFIRM_NO = "계속 쓰기"

    enum class Step { REFUSE, CONFIRM, EXIT }

    fun allowed(parkAllowed: Boolean, locked: Boolean): Boolean = parkAllowed && !locked

    /** 이동 중이면 확인이 필요하다 */
    fun needsConfirm(keepAliveOn: Boolean, tripActive: Boolean): Boolean = keepAliveOn || tripActive

    /** 버튼 누름 */
    fun onTap(parkAllowed: Boolean, locked: Boolean, keepAliveOn: Boolean, tripActive: Boolean): Step = when {
        !allowed(parkAllowed, locked) -> Step.REFUSE
        needsConfirm(keepAliveOn, tripActive) -> Step.CONFIRM
        else -> Step.EXIT
    }

    /** 확인 창의 [나가기]: 그사이 P가 풀렸으면 끝내지 않는다 */
    fun onConfirm(parkAllowed: Boolean, locked: Boolean): Step = if (allowed(parkAllowed, locked)) Step.EXIT else Step.REFUSE

    fun all(): List<String> = listOf(CARD_TITLE, BUTTON, NOTE, CONFIRM_TRIP, CONFIRM_YES, CONFIRM_NO)
}

/** 알림 표시 상태(권한 + 시스템의 앱 알림 켜짐) */
enum class NotifState { ON, OFF }

/** "이동 안내 알림" 버튼을 눌렀을 때 할 일 */
enum class NotifAction {
    /** 이미 허용됨 */
    NONE,
    /** 시스템 권한 요청 창을 띄운다 */
    REQUEST,
    /** 다시 물을 수 없음(영구 거부·시스템에서 앱 알림 끔) → 시스템의 앱 알림 설정을 연다 */
    OPEN_SETTINGS,
    /** 유효 P·플랫폼 허용이 아니거나 요청이 진행 중 */
    REFUSE,
}

/**
 * 설정의 "이동 안내 알림" 항목(2026-11-14). 예전에는 권한 요청이 설치당 1회뿐이라 한 번 거부·무시하면 다시 허용할 길이 없었다.
 * - 상태: Android 13 이상은 POST_NOTIFICATIONS 허용 + 시스템 앱 알림 켜짐, 그 아래는 시스템 앱 알림 켜짐만.
 * - 버튼: 꺼짐일 때만. 아직 물을 수 있으면(한 번도 안 물었거나 시스템이 다시 물어도 된다고 함) 권한 요청, 아니면 시스템 앱 알림 설정 열기.
 * - 유효 P + 플랫폼 허용일 때만 받는다. 알림이 없어도 이동 음성 안내는 그대로 나온다(서비스는 권한과 무관하게 돈다).
 */
object NotifSettingRule {
    const val CARD_TITLE = "이동 안내 알림"
    /** 왜 필요한지 한 줄(설정 카드와 첫 요청 직전 안내에 같이 쓴다) */
    const val WHY = "이동 중 안내가 켜져 있다는 것을 알림으로 보여 드려요. 꺼도 음성 안내는 나와요."
    const val STATE_ON = "지금: 허용됨"
    const val STATE_OFF = "지금: 꺼짐"
    const val BUTTON_ALLOW = "알림 허용"
    const val BUTTON_OPEN_SETTINGS = "알림 설정 열기"
    const val NOTE_OPEN_FAILED = "이 기기에서는 알림 설정 화면을 열 수 없어요. 차량 설정의 앱 알림에서 켜 주세요."
    /** 첫 요청 직전 안내 창 버튼 */
    const val ASK_YES = "알림 허용"
    const val ASK_LATER = "나중에"

    fun state(sdk: Int, permissionGranted: Boolean, enabledInSystem: Boolean): NotifState =
        if ((sdk < 33 || permissionGranted) && enabledInSystem) NotifState.ON else NotifState.OFF

    /**
     * [askedBefore] = 이 설치에서 권한 요청 창을 띄운 적이 있는가. [canAskAgain] = 시스템이 다시 물어도 된다고 하는가
     * (shouldShowRequestPermissionRationale: 한 번 거부한 뒤 true, 영구 거부·아직 안 물음이면 false).
     */
    fun action(sdk: Int, permissionGranted: Boolean, enabledInSystem: Boolean, askedBefore: Boolean, canAskAgain: Boolean,
               parkAllowed: Boolean, inFlight: Boolean): NotifAction = when {
        !parkAllowed || inFlight -> NotifAction.REFUSE
        state(sdk, permissionGranted, enabledInSystem) == NotifState.ON -> NotifAction.NONE
        sdk >= 33 && !permissionGranted -> if (!askedBefore || canAskAgain) NotifAction.REQUEST else NotifAction.OPEN_SETTINGS
        else -> NotifAction.OPEN_SETTINGS
    }

    fun stateText(s: NotifState): String = if (s == NotifState.ON) STATE_ON else STATE_OFF

    /** 버튼 글자(허용됨이면 버튼 없음 = null). 잠금·진행 중이어도 글자는 같은 기준으로 보인다 */
    fun buttonText(sdk: Int, permissionGranted: Boolean, enabledInSystem: Boolean, askedBefore: Boolean, canAskAgain: Boolean): String? =
        when (action(sdk, permissionGranted, enabledInSystem, askedBefore, canAskAgain, parkAllowed = true, inFlight = false)) {
            NotifAction.REQUEST -> BUTTON_ALLOW
            NotifAction.OPEN_SETTINGS -> BUTTON_OPEN_SETTINGS
            NotifAction.NONE, NotifAction.REFUSE -> null
        }

    fun all(): List<String> = listOf(CARD_TITLE, WHY, STATE_ON, STATE_OFF, BUTTON_ALLOW, BUTTON_OPEN_SETTINGS, NOTE_OPEN_FAILED, ASK_YES, ASK_LATER)
}
