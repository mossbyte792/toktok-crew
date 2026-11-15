package com.crewguide.probe

// 무대 컨트롤 표시 규칙(순수 로직, Android 클래스 없음). 장면에 필요한 버튼만 보이게 한다.
// 상태를 바꾸지 않으며, 실제 동작은 StageActions를 통해 기존 함수에 위임한다.

data class StageControlState(
    val showYesNo: Boolean,
    val showSeats: Boolean,
    val showStart: Boolean,
    val startEnabled: Boolean,
    val showFinish: Boolean,
    val showRestart: Boolean,
) {
    val anyVisible: Boolean get() = showYesNo || showSeats || showStart || showFinish || showRestart
}

private val NONE = StageControlState(false, false, false, false, false, false)

/** when을 exhaustive로 써서 Scene 값이 늘면 컴파일 오류가 나게 한다 */
fun controlsOf(scene: Scene, canStart: Boolean): StageControlState = when (scene) {
    Scene.QUESTION -> NONE.copy(showYesNo = true)
    Scene.PICK -> NONE.copy(showSeats = true, showStart = true, startEnabled = canStart)
    Scene.READY -> NONE.copy(showSeats = true, showStart = true, startEnabled = canStart)
    Scene.ANNOUNCE -> NONE
    Scene.YES -> NONE
    Scene.WAIT_GUARDIAN -> NONE.copy(showRestart = true)
    Scene.LOCKED -> NONE
    Scene.TRIP_END -> NONE.copy(showFinish = true)
    Scene.EXIT_CHECK -> NONE
    // 2026-11-13 디자인 담당(개발 쪽 요청 반영): 좌석별 대체 버튼은 자동 확인 패널이 그린다.
    // 출발 준비·도착·모두 내림에는 actions.onFinish()로 가는 큰 버튼 하나(문구는 finishLabelOf).
    // 2026-11-14 PM 결정 25: 확인 중에도 [처음으로](출발 준비와 같은 자리·모양). 흐름이 확인 취소 → 홈으로 처리한다
    Scene.AUTO_CHECK -> NONE.copy(showFinish = true)
    Scene.DEPART_READY -> NONE.copy(showFinish = true)
    Scene.ARRIVED -> NONE.copy(showFinish = true)
    Scene.ALL_LEFT -> NONE.copy(showFinish = true)
}

/** 큰 [끝내기] 버튼 문구(장면별). 이동 끝 = 기존 "끝내기", 도착·모두 내림 = "이번 이동 끝내기", 확인 중·출발 준비 = "처음으로"(홈으로 돌아감, 결정 25) */
fun finishLabelOf(scene: Scene): String = when (scene) {
    Scene.ARRIVED, Scene.ALL_LEFT -> StageControlText.MANUAL_FINISH
    Scene.DEPART_READY, Scene.AUTO_CHECK -> StageControlText.TO_HOME
    Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.QUESTION, Scene.YES, Scene.WAIT_GUARDIAN,
    Scene.LOCKED, Scene.TRIP_END, Scene.EXIT_CHECK -> StageControlText.FINISH
}

/** [설정] 버튼은 유효한 P이고 보호자 확인이 진행 중이 아닌 장면(PICK·READY·WAIT_GUARDIAN·TRIP_END)에서만 보인다 */
fun settingsAllowed(scene: Scene): Boolean = when (scene) {
    Scene.PICK -> true
    Scene.READY -> true
    Scene.WAIT_GUARDIAN -> true
    Scene.TRIP_END -> true
    Scene.ANNOUNCE -> false
    Scene.QUESTION -> false
    Scene.YES -> false
    Scene.LOCKED -> false
    Scene.EXIT_CHECK -> false
    // 2026-11-13 디자인 담당 확정(근거): 자동 확인 중(AUTO_CHECK)·하차 안내 중(ARRIVED)은 좌석 신호를 보며 안내가 진행 중이라
    // 화면을 가리는 설정으로 빠지지 않게 막는다(질문·하차 확인 장면과 같은 기준). 모두 내림(ALL_LEFT)은 이동 끝(TRIP_END)과 같은
    // 마무리 장면이라 허용한다. 무대 자체가 유효한 P에서만 보인다
    // 2026-11-14 PM 임시 결정 33(SCREEN_EXIT_CHECK ⚠️②): 출발 준비부터는 이동이 시작된 것으로 보고 흐름이 설정을 열지 않는다
    // (GState.YES_REPORTED → openStageSettings가 바로 돌아감). 눌러도 반응 없는 톱니를 보이지 않게 숨긴다. 나가기는 [처음으로]
    Scene.AUTO_CHECK -> false
    Scene.DEPART_READY -> false
    Scene.ARRIVED -> false
    Scene.ALL_LEFT -> true
}

/**
 * 보호자용 [이번 이동 끝내기](2026-11-08 Unit 6 최종, ch 결정 1)를 둘 수 있는 장면. 안내·질문 진행 중·잠금·하차 확인 중은 제외.
 * TRIP_END는 이미 큰 [끝내기]가 있어 제외한다. 실제 노출은 MainActivity가 게이트·주행 관측까지 확인한 값과 AND로 정한다.
 */
fun manualFinishScene(scene: Scene): Boolean = when (scene) {
    Scene.PICK, Scene.READY, Scene.YES, Scene.WAIT_GUARDIAN -> true
    Scene.ANNOUNCE, Scene.QUESTION, Scene.LOCKED, Scene.TRIP_END, Scene.EXIT_CHECK,
    Scene.AUTO_CHECK, Scene.DEPART_READY, Scene.ARRIVED, Scene.ALL_LEFT -> false
}

/** 무대 좌상단 [시연 ▾] 도우미: 유효한 P이고 안내·질문 진행 중이 아닌 장면에서만 허용한다(LOCKED·ANNOUNCE·QUESTION 제외) */
fun demoHelperAllowed(scene: Scene): Boolean = when (scene) {
    Scene.PICK, Scene.READY, Scene.YES, Scene.WAIT_GUARDIAN, Scene.TRIP_END, Scene.EXIT_CHECK -> true
    Scene.ANNOUNCE, Scene.QUESTION, Scene.LOCKED,
    Scene.AUTO_CHECK, Scene.DEPART_READY, Scene.ARRIVED, Scene.ALL_LEFT -> false
}

/** 아이 선택 칩 한 개의 표시 정보(순수 데이터). label은 별명만(최대 8자) */
data class ChipInfo(val id: Int, val label: String, val selected: Boolean, val style: CharacterStyle? = null)

/** 아이 칩 줄은 준비 단계(PICK·READY)에서 등록된 프로필이 1명 이상일 때만 보인다 */
fun chipsVisible(scene: Scene, profileCount: Int): Boolean =
    (scene == Scene.PICK || scene == Scene.READY) && profileCount >= 1

/** 칩 라벨: 별명만, 코드포인트 기준 최대 8자(이미 화면에 표시되는 값을 자르기만 한다) */
fun chipLabel(nickname: String): String {
    val n = nickname.codePointCount(0, nickname.length)
    if (n <= StageControlLayout.CHIP_LABEL_MAX) return nickname
    return nickname.substring(0, nickname.offsetByCodePoints(0, StageControlLayout.CHIP_LABEL_MAX))
}

/**
 * 칩을 줄로 나눈다. slots[i]는 i번째 칩이 차지하는 칸 수(보통 1, '지난번 아이 그대로'는 2).
 * 한 줄 칸 수는 cap(기본 3)이며 순서대로 채운다. 반환값은 줄별 칩 인덱스 목록.
 */
fun chipRows(slots: List<Int>, cap: Int = StageControlLayout.CHIP_ROW_SLOTS): List<List<Int>> {
    val rows = ArrayList<MutableList<Int>>()
    var used = 0
    for ((i, s) in slots.withIndex()) {
        if (rows.isEmpty() || used + s > cap) { rows.add(ArrayList()); used = 0 }
        rows.last().add(i)
        used += s
    }
    return rows
}

/** 무대 컨트롤 문구(기존 화면 버튼 문구와 같게 둔다) */
object StageControlText {
    const val YES = "예"
    const val NO = "아니오"
    const val START = "보호자 확인 시작"
    const val RESTART = "보호자 확인 다시 시작"
    const val RESTART_SHORT = "다시 확인"
    const val FINISH = "끝내기"
    /** 2026-11-13: 출발 준비 장면의 큰 버튼(onFinish → 홈) */
    const val TO_HOME = "처음으로"
    const val YES_NOTE = "‘예’는 보호자 보고예요"   // 질문 장면에서만 보이는 작은 한 줄
    const val SETTINGS_BUTTON = "설정"
    const val BACK_BUTTON = "앱으로 돌아가기"
    const val SETTINGS_DESC = "설정 열기"
    const val ADD_CHILD_CHIP = "+ 아이 별명 등록"
    const val SETTINGS_HEADER = "보호자 설정 · 시연 도구"
    const val DEMO_TOGGLE_CLOSED = "시연 ▾"
    const val DEMO_TOGGLE_OPEN = "시연 ▴"
    const val DEMO_DESC = "시연 도우미 열기"
    const val DEMO_MOCK_START = "모의 출발"
    const val DEMO_MOCK_ARRIVE = "모의 도착"
    const val DEMO_MANUAL_FINISH = "수동 끝내기"   // 시연 도우미용 이름(현재 무대에 버튼 없음). 보호자용은 MANUAL_FINISH
    /** 보호자용 실제 이동 종료(시연 도우미가 아님). 확인 대화상자를 거친다 */
    const val MANUAL_FINISH = "이번 이동 끝내기"
    const val MANUAL_FINISH_CONFIRM = "이번 이동을 끝낼까요?" + "\n" + "내비 도착 신호와 상관없이 보호자가 끝내는 거예요. 다음 탑승은 새 이동이 돼요."
    const val MANUAL_FINISH_YES = "끝내기"
    const val MANUAL_FINISH_NO = "계속하기"
    const val DEMO_BELT_REAL = "벨트: 실제"
    const val DEMO_BELT_MOCK = "벨트: 모의"
    const val DEMO_TOGGLE_REAR_SIM = "뒷줄 모의 벨트 전환"
}

/** 무대 컨트롤이 부르는 동작. MainActivity가 기존 함수에 위임해 구현한다 */
interface StageActions {
    fun onYes()
    fun onNo()
    /** 2026-11-12 A2: [다시 듣기] 탭(질문 1회 재생). 기본 없음 */
    fun onReplay() {}
    fun onStart()
    fun onRestart()
    fun onFinish()
    /** 보호자용 [이번 이동 끝내기] 노출 조건(유효 P + 최신 UXR 허용 + 연결 정상 + 이번 이동 주행 관측 + 미종료). 기본 false */
    fun manualFinishVisible(): Boolean = false
    /** [이번 이동 끝내기] 탭: 확인 대화상자를 연다(바로 끝내지 않음) */
    fun onManualFinish() {}
    fun onToggleSeat(name: String)
    fun seatNames(): List<String>
    fun seatSelected(name: String): Boolean
    fun canStart(): Boolean
    /** 아이 선택 칩(기존 ChildProfilePanel의 프로필 목록·선택 상태를 읽음) */
    fun profileChips(): List<ChipInfo>
    fun onToggleChild(id: Int)
    /** '지난번 아이 그대로' 칩 표시 조건(기존 버튼과 같음)과 동작 */
    fun lastChipVisible(): Boolean
    fun onUseLast()
    /** [설정] 버튼: 무대를 숨기고 보호자 설정 화면을 드러낸다(MainActivity가 구현) */
    fun onOpenSettings()
    /** 등록된 아이 프로필이 없을 때 바로 별명 입력 칸을 여는 진입점 */
    fun onOpenChildRegister() { onOpenSettings() }
    /** 좌석 지도 타일의 벨트 참고 신호(실제 수신 또는 앱 내부 모의) */
    fun seatBeltRef(name: String): SeatBeltRef = SeatBeltRef.UNKNOWN
    /** 좌석 점유 참고 상태(null = 미확인) */
    fun seatOccupied(name: String): Boolean? = null
    /** 현재 벨트 참고 신호 출처가 앱 내부 모의(SIM)인지 */
    fun isBeltMock(): Boolean = false
    /** debug 벨트 주입(DEBUG_BELT) 값이 지도에 섞여 있는지(release는 항상 false) */
    fun isBeltInjected(): Boolean = false
    /** 무대 시연 도우미 동작(기존 MockNavi·모의 벨트·끝내기 함수에 위임) */
    fun onDemoMockStart() {}
    fun onDemoMockArrive() {}
    fun onDemoToggleBeltSource() {}
    fun onDemoToggleRearSimBelt() {}
    fun demoStatusLine(): String = ""
    /**
     * 2026-11-13 PM 계약: 자동 확인에서 신호 확인 불가([SeatVerdict.NEEDS_GUARDIAN]) 좌석의 보호자 확인 대체 버튼 탭.
     * 화면(디자인 담당)이 부르고, 판정 반영은 MainActivity(개발 담당)가 한다. 기본 없음
     */
    fun onGuardianConfirmSeat(seat: String) {}
    /**
     * 2026-11-13 PM 계약 v2: 좌석 화면에서 칸을 눌렀다(아이 배정 바꾸기). 화면(디자인)이 부르고, 배정 규칙·판정 반영은 흐름(개발)이 한다.
     * 유효 P + 플랫폼 허용일 때만 반영한다. 운전석·빈 자리는 무시한다(결정 15). 기본 없음
     */
    fun onSeatTap(seat: String) {}
    /** 2026-11-14 PM 계약 v4(J2): '모두 내림' 뒤 다시 앉음 → [새로 출발 확인]. 이번 이동 마무리(스티커 중복 지급 없음) → 새 이동 확인 화면. 유효 P일 때만(개발) */
    fun onNewTripFromAllLeft() {}
    /**
     * 2026-11-14 PM 계약 v5(r5 추가 지시 A): "목적지에 도착했나요?" 대답. true = [예, 도착했어요] → 지금의 도착 화면·도착 음성·하차 흐름.
     * false = [아니요, 잠깐 멈췄어요] → 이동을 그대로 잇는다(도착 안내·하차 한 줄·두고 내림 안내 없음), 내비를 다시 앞으로.
     * 화면 버튼과 음성 대답이 같은 함수를 부른다. 유효 P일 때만 반영(개발)
     */
    fun onArrivalAnswer(arrived: Boolean) {}
    /**
     * 2026-11-15 PM 계약 v6(r6 5절): "내릴까요?" 대답. true = [예, 내려요] → 그 자리에서 마무리 흐름(하차 안내 → 아이가 내리면 하차 마무리·스티커·작별 인사 → 이동 끝, '도착' 표현 없음, 내비 경로는 건드리지 않음).
     * false = [아니요, 계속 가요] → 이동 계속("잠깐 멈췄어요", 하차 한 줄·두고 내림 없음, 내비 앞으로). 버튼과 음성 대답이 같은 함수를 부른다. 유효 P일 때만(개발)
     */
    fun onAlightAnswer(alight: Boolean) {}
}

/** 컨트롤 크기 상수(DesignRules 기준 이상) */
object StageControlLayout {
    const val YESNO_HEIGHT_DP = 112   // [예][아니오] 고정 높이(둘이 같은 LayoutParams 높이를 쓴다)
    const val YESNO_SP = 40f
    const val NOTE_SP = 24f           // 질문 장면의 작은 안내 한 줄
    const val YESNO_GAP_DP = 24
    const val CHIP_MIN_DP = 76
    const val CHIP_SP = 24f
    const val CHIP_GAP_DP = 23
    const val BTN_MIN_DP = 76
    const val BTN_SP = 24f
    const val SUB_SP = 24f
    const val CHIP_LABEL_MAX = 8      // 별명 최대 글자 수(ChildProfiles.MAX_NICKNAME_CODEPOINTS와 같음)
    const val CHIP_ROW_SLOTS = 6      // 아이 칩 한 줄의 칸 수(최대 프로필 4 + 지난번 칩[2칸] = 6 → 한 줄)
    const val LAST_CHIP_SLOTS = 2
    const val CHILD_ROWS_MAX = 1
    const val START_SLOTS = 3         // 좌석 줄에서 [보호자 확인 시작]이 차지하는 칸 수(좌석 칩 5개는 1칸씩). U-2b: 2→3(32sp 글자 말줄임 방지)
}
