package com.crewguide.probe

// 탑승 준비 흐름(통합설계 v1.1 S01~S06)의 순수 상태 기계. Android·SDK 클래스를 쓰지 않는다.
// 화면·TTS·STT는 이 기계가 돌려주는 효과(BoardingEffect)를 실행만 하고, 상태 판단은 여기서만 한다.
// 기존 MainActivity의 GState 흐름(재안내 2회, 질문 세대, 잠금 시 취소)과 같은 규칙을 옮긴 것이다.
// 2026-11-07 Unit 5.1(ch DECISION 3 P0-4): MainActivity가 보호자 확인 전이를 이 기계로만 하도록 연결했다([reduce] GateInput 판).
//
// 고정 원칙(사용자 결정, 통합설계 v1.1 1장):
//  - 아이와 확인 좌석은 별도 선택. 좌석 신호로 아이를 특정하지 않는다.
//  - 보호자 '예'는 보호자 보고(GuardianReport)일 뿐 착용 확인·안전 보증·출발 허가가 아니다.
//  - 정차 허용(VehicleGate.PARK_ALLOWED)이 아니면 조작 이벤트를 받지 않고, 진행 중 확인은 폐기한다.

/** 차량·정책 게이트. 단일 boolean으로 합치지 않는다(통합설계 5장) */
enum class VehicleGate { UNKNOWN, PARK_ALLOWED, RESTRICTED }

/** 신호 출처. 차량/에뮬레이터 SDK 수신과 앱 내부 모의를 섞지 않는다.
 *  앱은 실차와 에뮬레이터를 SDK만으로 구별할 수 없으므로 둘은 SDK 하나로 묶고, 화면·기록에서 따로 표기하지 않는다. */
enum class SignalOrigin(val label: String) {
    SDK("차량/에뮬레이터 SDK 수신"),
    APP_MOCK("앱 내부 모의(실제 차량 아님)"),
}

/**
 * 플랫폼 주행 제한(CarUxRestrictions 등) 상태. 현재 앱은 연동하지 않았다(현황대조 C20).
 * 2026-11-07 검토자 지시(작업 단위 1 피드백 3): 미연동·불명이면 정차 조작을 허용하지 않는다(보수적).
 */
enum class PlatformUx {
    /** 플랫폼이 조작을 허용한다고 알려 옴 */
    ALLOWED,
    /** 플랫폼이 조작을 제한함 */
    RESTRICTED,
    /** 연동은 했으나 현재값을 아직 못 받았거나 읽기 실패·연결 끊김 */
    UNKNOWN,
    /** 앱이 플랫폼 제한을 아직 연동하지 않음 */
    NOT_INTEGRATED,
}

/** 게이트 판정 입력. 기어 이름 판정은 기존 [gearLockReason]을 그대로 쓴다 */
data class GateInput(
    val gearName: String?,
    val linkOk: Boolean,
    val subFailed: Boolean,
    /** 기어 신호 출처 */
    val origin: SignalOrigin,
    val platform: PlatformUx,
    val foreground: Boolean,
    /** 모의 기어·모의 플랫폼 허용을 쓸 수 있는 빌드인가(개발·시연 빌드만 true). 출시 빌드의 잠금 우회를 막는다 */
    val mockAllowed: Boolean,
    /** 플랫폼 제한 값의 출처. 앱 내부 모의 ALLOWED는 모의 허용 빌드에서만 인정한다 */
    val platformOrigin: SignalOrigin = SignalOrigin.SDK,
) {
    companion object {
        /**
         * 연결 코드가 쓸 생성자. [mockAllowed]를 호출부가 고르지 못하게 빌드 종류([BuildMockPolicy], src/debug·src/release)에서만 가져온다.
         * release 빌드는 항상 false라 모의 기어·모의 플랫폼 입력이 정차 허용이 되지 않는다(BuildMockPolicyReleaseTest).
         */
        fun forBuild(gearName: String?, linkOk: Boolean, subFailed: Boolean, origin: SignalOrigin, platform: PlatformUx,
                     foreground: Boolean, platformOrigin: SignalOrigin = SignalOrigin.SDK): GateInput =
            GateInput(gearName, linkOk, subFailed, origin, platform, foreground, BuildMockPolicy.MOCK_ALLOWED, platformOrigin)
    }
}

object VehicleGateRule {
    /**
     * 유효한 P **그리고** 플랫폼 주행 제한이 허용할 때만 PARK_ALLOWED. 그 밖은 아래처럼 나눈다.
     * - 모의 허용이 아닌 빌드의 모의 입력(기어·플랫폼 어느 쪽이든) → RESTRICTED
     * - P가 아님, 플랫폼 제한, 앱이 전경이 아님 → RESTRICTED
     * - 기어 불명·미수신·단절·구독 실패 → UNKNOWN(정차로 단정하지 않음)
     * - 플랫폼 미연동(NOT_INTEGRATED)·불명(UNKNOWN) → UNKNOWN(기어만으로 허용하지 않음)
     * 따라서 플랫폼 제한을 실제로 연동하기 전까지 SDK 기어만으로는 정차 조작이 열리지 않는다.
     * 개발·시연 빌드는 플랫폼 값을 앱 내부 모의 ALLOWED로 줄 수 있고, 이때 화면·기록에 모의임을 표시한다([usesMock]).
     * 실제 플랫폼 연동과 동작은 별도 검증 대상이다(미검증).
     */
    fun evaluate(i: GateInput): VehicleGate {
        // 2026-11-07 검토자 P0-1(작업 단위 5): 생성자에 mockAllowed=true를 넣어도 빌드 종류가 허용하지 않으면 모의 입력은 인정하지 않는다
        val mockOk = i.mockAllowed && BuildMockPolicy.MOCK_ALLOWED
        if (!mockOk && (i.origin == SignalOrigin.APP_MOCK || i.platformOrigin == SignalOrigin.APP_MOCK)) return VehicleGate.RESTRICTED
        val lock = gearLockReason(i.gearName, i.linkOk, i.subFailed)
        if (lock == LOCK_TEXT_NOT_PARK) return VehicleGate.RESTRICTED
        if (i.platform == PlatformUx.RESTRICTED) return VehicleGate.RESTRICTED
        if (!i.foreground) return VehicleGate.RESTRICTED
        if (lock != null) return VehicleGate.UNKNOWN
        if (i.platform != PlatformUx.ALLOWED) return VehicleGate.UNKNOWN
        return VehicleGate.PARK_ALLOWED
    }

    /** 플랫폼 제한을 실제로 확인했는가(연동했고 값이 있음) */
    fun platformChecked(i: GateInput): Boolean = i.platform == PlatformUx.ALLOWED || i.platform == PlatformUx.RESTRICTED

    /** 판정에 앱 내부 모의 값이 하나라도 쓰였는가. true면 화면·기록에 '앱 내부 모의'를 표시한다 */
    fun usesMock(i: GateInput): Boolean = i.origin == SignalOrigin.APP_MOCK || i.platformOrigin == SignalOrigin.APP_MOCK

    /**
     * 정차 허용이 아닐 때 진행 중 확인을 멈춘 이유(화면 문구). PARK_ALLOWED면 null.
     * 2026-11-07 Unit 5.1(N4): 'P 이탈' 문구는 기어가 실제로 P가 아니거나 플랫폼이 조작을 제한할 때만 쓴다.
     *  - 앱이 전경이 아님 → LEFT_APP(화면 이탈)
     *  - 허용되지 않는 빌드의 모의 입력(release 모의 거절) → GEAR_UNKNOWN(실제 상태를 확인하지 못함)
     *  - 기어 P 아님·플랫폼 제한 → LEFT_PARK
     *  - 그 밖(불명·단절·구독 실패·플랫폼 미연동/불명) → GEAR_UNKNOWN
     */
    fun interruptionFor(i: GateInput): Interruption? {
        if (evaluate(i) == VehicleGate.PARK_ALLOWED) return null
        if (!i.foreground) return Interruption.LEFT_APP
        val mockOk = i.mockAllowed && BuildMockPolicy.MOCK_ALLOWED
        if (!mockOk && usesMock(i)) return Interruption.GEAR_UNKNOWN
        if (gearLockReason(i.gearName, i.linkOk, i.subFailed) == LOCK_TEXT_NOT_PARK) return Interruption.LEFT_PARK
        if (i.platform == PlatformUx.RESTRICTED) return Interruption.LEFT_PARK
        return Interruption.GEAR_UNKNOWN
    }
}

enum class BoardingStep {
    /** S01 홈 */
    HOME,
    /** S02 오늘 탄 아이 선택 */
    CHILD_SELECT,
    /** S03 확인 대상 좌석 선택 */
    SEAT_SELECT,
    /** S04 보호자 직접 확인 안내 */
    ANNOUNCING,
    /** S05 확인 질문 */
    ASKING,
    /** S05 재안내 소진 후 보호자 대기. 자동 반복 없음 */
    WAITING_GUARDIAN,
    /**
     * 2026-11-13 자동 확인: 선택 좌석의 착석·벨트 신호를 보는 중(SeatCheckRule). 보호자 '예' 질문 없이 신호로 진행한다.
     * 진행 중 단계라 [BoardingFlow]의 취소 규칙(뒤로·홈·게이트 이탈 = 공통 취소, 멈춤 안내)을 그대로 따른다.
     */
    AUTO_CHECK,
    /** S06 아이 안내·참여 피드백 */
    CHILD_NOTICE,
    /** 준비 흐름 종료(브리핑 S15는 선택·내비 정보가 있을 때만, 이 기계 밖) */
    FINISHED,
}

enum class ResponseSource(val label: String) {
    TOUCH("터치"),
    VOICE("음성"),
    /** 2026-11-13 자동 확인: 착석·벨트 신호로 완료(보호자 응답 아님) */
    SIGNAL("차량 신호"),
    /** 2026-11-13 자동 확인: 신호 확인 불가 좌석을 보호자 대체 버튼으로 확인(신호 아님) */
    GUARDIAN_BUTTON("보호자 확인 버튼"),
}

/** 보호자 보고. 앱이 착용을 확인했다는 뜻이 아니다 */
data class GuardianReport(val source: ResponseSource, val generation: Int, val seats: Set<String>, val children: Set<String>)

/**
 * 이미 흐름 밖으로 내보낸(참여 피드백 효과로 전달한) 보호자 보고의 처리 상태.
 * 2026-11-07 검토자 지시 4(작업 단위 3): 진행 중 확인·응답은 P 이탈·상태 불명·화면 이탈 때 무효화하지만,
 * 이미 내보낸 보고는 지우거나 되돌리지 않고 처리 상태만 따로 관리한다.
 * 이 값은 기록·표시용이며 화면 단계나 [BoardingState.report]를 복원하는 근거로 쓰지 않는다.
 */
enum class ReportDisposition {
    /** 보고를 내보냈고 아이 안내 중 */
    CHILD_NOTICE_PENDING,
    /** 아이 안내가 끝나기 전에 P 이탈·상태 불명·화면 이탈·홈 이동으로 멈춤. 보고 자체는 취소되지 않음 */
    CHILD_NOTICE_INTERRUPTED,
    /** 아이 안내까지 마침 */
    CHILD_NOTICE_DONE,
}

/** 내보낸 보고와 처리 상태. 다음 보고가 생길 때까지 남는다(새 실행이 시작돼도 지우지 않지만, 새 실행의 근거도 아니다) */
data class DeliveredReport(val report: GuardianReport, val disposition: ReportDisposition)

/** 진행 중 확인이 멈춘 이유(화면 안내용). 보고를 만들지 않는다 */
enum class Interruption(val text: String) {
    LEFT_PARK("정차 상태가 아니어서 확인을 멈췄어요. 정차 후 처음부터 다시 시작해 주세요"),
    GEAR_UNKNOWN("차량 상태를 확인할 수 없어 확인을 멈췄어요"),
    LEFT_APP("화면을 벗어나 확인을 멈췄어요. 다시 시작해 주세요"),
}

data class BoardingState(
    val step: BoardingStep = BoardingStep.HOME,
    val children: Set<String> = emptySet(),
    val seats: Set<String> = emptySet(),
    /** 확인 시작 시점에 고정한 좌석(선택 스냅샷) */
    val seatSnapshot: Set<String> = emptySet(),
    /** 확인 세대. 시작·재안내·취소마다 증가하며 늦은 콜백은 세대가 다르면 버린다 */
    val generation: Int = 0,
    /** 최초 안내 외 재안내 횟수 */
    val reannounce: Int = 0,
    /** 현재 세대의 보호자 보고. 세대가 바뀌거나 흐름이 취소되면 비운다 */
    val report: GuardianReport? = null,
    val interruption: Interruption? = null,
    /** 음성 응답을 확정하지 못했을 때의 안내(질문 상태 유지) */
    val voiceUnsure: Boolean = false,
    /**
     * [children]이 이번 실행에서 보호자가 고른 것이 아니라 지난 선택을 유지한 것인가.
     * true면 화면은 '지난번 선택'으로 표시하고, 보호자가 [다음]을 누르거나 다시 고르기 전에는 오늘 탑승의 근거로 쓰지 않는다.
     * 아이 선택을 유지해도 좌석은 매번 다시 고른다(좌석은 유지하지 않음).
     */
    val childrenFromPrevious: Boolean = false,
    /**
     * D02 '이번에는 등록 없이 진행'(2026-11-07 검토자 채택). true면 아이별 성장 기록만 저장하지 않는다.
     * 안내·질문·재안내·응답 대기·정차 게이트·중단 처리 같은 안전 기능과 필수 안내는 등록 여부와 관계없이 같다.
     */
    val withoutProfile: Boolean = false,
    /** 이미 내보낸 보호자 보고의 처리 상태. [interrupt]·취소가 지우지 않는다(지시 4). [report]와 달리 진행 중 확인이 아니다 */
    val delivered: DeliveredReport? = null,
    /**
     * 현재 세대 안내(S04)가 끝난 방식(2026-11-08 P0-C 재감사). null=아직 안내 중이거나 해당 없음.
     * SPOKEN만 안내 음성 완료이며, 나머지는 질문 화면에 안내 문구와 [AnnounceOutcome.note]를 함께 보인다. 보고(예)를 만들지 않는다.
     */
    val announceOutcome: AnnounceOutcome? = null,
) {
    /** 이번 실행에서 보고·기록에 쓰는 아이. D02 임시 세션이면 빈 집합이고 [children](지난 선택)은 그대로 남는다 */
    val sessionChildren: Set<String> get() = if (withoutProfile) emptySet() else children
}

sealed class BoardingEvent {
    object Start : BoardingEvent()
    data class ToggleChild(val id: String) : BoardingEvent()
    object ChildrenNext : BoardingEvent()
    /** D02(2026-11-07 채택): 아이 등록·선택 없이 이번 실행만 진행. 아이 선택을 비우고 좌석 선택으로 간다 */
    object ProceedWithoutProfile : BoardingEvent()
    data class ToggleSeat(val seat: String) : BoardingEvent()
    object SeatsStart : BoardingEvent()
    /**
     * 2026-11-13 자동 확인 시작(새 흐름의 유일한 시작 이벤트). [SeatsStart](안내→질문→예)는 기존 테스트·되돌리기용으로 남겨 두며
     * 앱(MainActivity.startGuardian)은 더 이상 보내지 않는다(예/아니오·STT 경로는 새 흐름에서 도달하지 않음).
     */
    object SeatsStartAuto : BoardingEvent()
    /**
     * 2026-11-13 PM 계약 v2·결정 15: 좌석 화면 하나로 확인. 홈(또는 지난 확인을 마친 뒤)에서 아이·좌석 선택 화면 없이 바로 자동 확인으로 간다.
     * 확인 대상(아이 배정 자리)은 확인 중에 정해지므로 좌석은 비운 채 시작하고, 완료 때 [AutoCheckComplete.seats]로 받는다
     */
    object StartCheck : BoardingEvent()
    /**
     * 자동 확인 완료(SeatCheckRule이 1.5초 유지 확인 뒤 1회). 세대가 다르면 버린다.
     * 계약 v2: [seats]·[children]이 있으면 확인 중에 배정한 자리·아이로 보고를 만든다([withoutProfile]=등록 아이 없이 진행)
     */
    data class AutoCheckComplete(val generation: Int, val source: ResponseSource, val seats: Set<String>? = null,
                                 val children: Set<String>? = null, val withoutProfile: Boolean = false) : BoardingEvent()
    /** 뒤로. 진행 중 확인은 취소된다 */
    object Back : BoardingEvent()
    /**
     * 안내 발화가 끝남/실패/상한 대기 경과. 고정 타이머로 자르지 않는다(통합설계 S04).
     * [outcome]은 끝난 방식이며 SPOKEN이 아니면 '안내 완료'가 아니다(2026-11-08 P0-C 재감사). CANCELLED는 [AnnounceCancelled]와 같다.
     */
    data class AnnounceFinished(val generation: Int, val outcome: AnnounceOutcome) : BoardingEvent()
    /** 흐름은 그대로인데 앱이 안내 발화를 멈춤(2026-11-08). 질문으로 넘기지 않고 보호자 대기 + 명시적 [다시 시작] */
    data class AnnounceCancelled(val generation: Int) : BoardingEvent()
    data class Answer(val yes: Boolean, val source: ResponseSource, val generation: Int) : BoardingEvent()
    /** 음성 결과가 불확실(부분·혼합·미등록 단어). 진행 차단, 질문 유지 */
    data class VoiceUnsure(val generation: Int) : BoardingEvent()
    data class Timeout(val generation: Int) : BoardingEvent()
    /** 보호자 대기에서 명시적 [다시 시작] */
    object Restart : BoardingEvent()
    /** 아이 안내 발화 완료·[다음]. 2026-11-07 검토자 P1-3: 세대를 붙여 이전 세션의 TTS·타이머 콜백을 버린다 */
    data class NoticeNext(val generation: Int) : BoardingEvent()
    object ToHome : BoardingEvent()
    /** 앱이 화면에서 벗어남(onPause 등) */
    object LeftForeground : BoardingEvent()
}

sealed class BoardingEffect {
    data class SpeakAnnounce(val generation: Int) : BoardingEffect()
    data class SpeakQuestion(val generation: Int) : BoardingEffect()
    data class ArmResponseTimeout(val generation: Int) : BoardingEffect()
    data class SpeakChildNotice(val generation: Int) : BoardingEffect()
    /** 2026-11-13 자동 확인 시작: 좌석 신호 평가·재안내 타이머를 시작한다(세대 포함) */
    data class StartAutoCheck(val generation: Int, val seats: Set<String>) : BoardingEffect()
    /** 진행 중 TTS·STT·타이머·모션을 모두 멈춘다 */
    object StopAll : BoardingEffect()
    /** 다음 실행 추천용으로만 저장(자동 선택 아님). 기존 SeatSuggestionStore·ChildSelection 기록과 같은 시점 */
    data class RecordSuggestion(val seats: Set<String>, val children: Set<String>) : BoardingEffect()
    /**
     * 참여 피드백(스티커). 벨트값·TTS 성공·안전 효과와 무관.
     * [savePersonalRecord]가 false면(선택 아이 없음·등록 없이 진행) 화면 피드백만 하고 아이별 성장 기록은 저장하지 않는다.
     */
    data class ParticipationFeedback(val report: GuardianReport, val savePersonalRecord: Boolean) : BoardingEffect()
    /** 내보낸 보고의 처리 상태가 바뀜(기록 갱신용). 화면 상태 복원에 쓰지 않는다 */
    data class ReportDispositionChanged(val delivered: DeliveredReport) : BoardingEffect()
}

object BoardingFlow {
    /** 최초 안내 외 재안내 최대 횟수(사용자 확정: 2회) */
    const val MAX_REANNOUNCE = 2

    private val IN_PROGRESS = setOf(BoardingStep.ANNOUNCING, BoardingStep.ASKING, BoardingStep.WAITING_GUARDIAN, BoardingStep.AUTO_CHECK)

    fun reduce(s: BoardingState, gate: VehicleGate, e: BoardingEvent): Pair<BoardingState, List<BoardingEffect>> {
        if (e == BoardingEvent.LeftForeground) return interrupt(s, Interruption.LEFT_APP)
        // 정차 허용이 아니면 어떤 조작도 받지 않는다(늦은 예·타이머 포함).
        // 2026-11-07 검토자 P1-4: 이벤트를 버리기만 하지 않고, 홈이 아니면 게이트 변화와 같은 공통 취소 경로로 정리한다
        if (gate != VehicleGate.PARK_ALLOWED) return if (s.step == BoardingStep.HOME) s to emptyList() else onGate(s, gate)
        return when (e) {
            // 새 실행: 좌석은 비우고(지난 좌석은 오늘 탑승 근거가 아님), 아이 선택은 '지난번 선택'으로만 남긴다
            BoardingEvent.Start -> when (s.step) {
                BoardingStep.HOME, BoardingStep.FINISHED ->
                    s.copy(step = BoardingStep.CHILD_SELECT, interruption = null, report = null, seats = emptySet(),
                        seatSnapshot = emptySet(), childrenFromPrevious = s.children.isNotEmpty(), withoutProfile = false) to emptyList()
                else -> s to emptyList()
            }
            is BoardingEvent.ToggleChild ->
                if (s.step != BoardingStep.CHILD_SELECT) s to emptyList()
                else s.copy(children = s.children.toggle(e.id), childrenFromPrevious = false, withoutProfile = false) to emptyList()
            // 선택 아이 0명도 진행 가능(기존 코드와 같음). [다음]을 누르면 화면에 보인 선택을 이번 실행의 보호자 선택으로 본다
            BoardingEvent.ChildrenNext ->
                if (s.step != BoardingStep.CHILD_SELECT) s to emptyList()
                else s.copy(step = BoardingStep.SEAT_SELECT, childrenFromPrevious = false, withoutProfile = false) to emptyList()
            // D02(채택): 등록 없이 진행하는 임시 세션. 이후 안내·질문·게이트 규칙은 일반 흐름과 같다.
            // 2026-11-07 검토자 P1-5: 기존 아이 선택([children])은 지우지 않고 그대로 둔다(이번 실행에서는 [sessionChildren]이 빈 집합).
            // 이번 실행에서는 추천 기록(RecordSuggestion)을 내보내지 않아 지난 선택이 빈 목록으로 덮이지 않는다.
            BoardingEvent.ProceedWithoutProfile ->
                if (s.step != BoardingStep.CHILD_SELECT) s to emptyList()
                else s.copy(step = BoardingStep.SEAT_SELECT, withoutProfile = true) to emptyList()
            is BoardingEvent.ToggleSeat ->
                if (s.step != BoardingStep.SEAT_SELECT) s to emptyList()
                else s.copy(seats = s.seats.toggle(e.seat)) to emptyList()
            BoardingEvent.SeatsStart -> {
                if (s.step != BoardingStep.SEAT_SELECT || s.seats.isEmpty()) s to emptyList()   // A01: 0석이면 시작 안 함
                else {
                    val gen = s.generation + 1
                    val next = s.copy(step = BoardingStep.ANNOUNCING, seatSnapshot = s.seats, generation = gen, reannounce = 0,
                        report = null, interruption = null, voiceUnsure = false, announceOutcome = null)
                    // D02 임시 세션은 영속 추천 기록을 남기지 않는다(빈 아이 목록·임시 좌석이 지난 선택을 덮지 않게)
                    next to (if (s.withoutProfile) listOf(BoardingEffect.SpeakAnnounce(gen))
                             else listOf(BoardingEffect.RecordSuggestion(s.seats, s.children), BoardingEffect.SpeakAnnounce(gen)))
                }
            }
            BoardingEvent.SeatsStartAuto -> {
                if (s.step != BoardingStep.SEAT_SELECT || s.seats.isEmpty()) s to emptyList()   // 0석이면 시작 안 함
                else {
                    val gen = s.generation + 1
                    val next = s.copy(step = BoardingStep.AUTO_CHECK, seatSnapshot = s.seats, generation = gen, reannounce = 0,
                        report = null, interruption = null, voiceUnsure = false, announceOutcome = null)
                    val start = BoardingEffect.StartAutoCheck(gen, s.seats)
                    next to (if (s.withoutProfile) listOf(start) else listOf(BoardingEffect.RecordSuggestion(s.seats, s.children), start))
                }
            }
            BoardingEvent.StartCheck -> when (s.step) {
                BoardingStep.HOME, BoardingStep.FINISHED -> {
                    val gen = s.generation + 1
                    s.copy(step = BoardingStep.AUTO_CHECK, interruption = null, report = null, seats = emptySet(), seatSnapshot = emptySet(),
                        generation = gen, reannounce = 0, voiceUnsure = false, announceOutcome = null, childrenFromPrevious = false,
                        withoutProfile = false) to listOf(BoardingEffect.StartAutoCheck(gen, emptySet()))
                }
                else -> s to emptyList()
            }
            is BoardingEvent.AutoCheckComplete ->
                if (s.step != BoardingStep.AUTO_CHECK || e.generation != s.generation) s to emptyList()
                else if (e.seats != null) {
                    // 계약 v2: 확인 중 배정한 자리·아이로 보고(추천 기록도 이때 남긴다. 등록 없이 진행이면 남기지 않음)
                    val s2 = s.copy(seats = e.seats, seatSnapshot = e.seats, children = e.children ?: s.children, withoutProfile = e.withoutProfile)
                    val (n, fx) = reduce(s2, VehicleGate.PARK_ALLOWED, e.copy(seats = null))
                    n to (if (e.withoutProfile || e.seats.isEmpty()) fx else listOf(BoardingEffect.RecordSuggestion(e.seats, s2.children)) + fx)
                } else {
                    // '예' 응답과 같은 효과 목록을 쓴다(아이 안내는 새 세대). 보고 출처는 신호 또는 보호자 대체 버튼
                    val r = GuardianReport(e.source, s.generation, s.seatSnapshot, s.sessionChildren)
                    val d = DeliveredReport(r, ReportDisposition.CHILD_NOTICE_PENDING)
                    val gen = s.generation + 1
                    s.copy(step = BoardingStep.CHILD_NOTICE, report = r, voiceUnsure = false, delivered = d, generation = gen) to
                        listOf(BoardingEffect.StopAll, BoardingEffect.SpeakChildNotice(gen),
                            BoardingEffect.ParticipationFeedback(r, savePersonalRecord = !s.withoutProfile && s.sessionChildren.isNotEmpty()),
                            BoardingEffect.ReportDispositionChanged(d))
                }
            BoardingEvent.Back -> when (s.step) {
                BoardingStep.CHILD_SELECT -> s.copy(step = BoardingStep.HOME) to emptyList()
                BoardingStep.SEAT_SELECT -> s.copy(step = BoardingStep.CHILD_SELECT, withoutProfile = false) to emptyList()
                in IN_PROGRESS -> cancelTo(s, BoardingStep.SEAT_SELECT)
                else -> s to emptyList()
            }
            is BoardingEvent.AnnounceFinished ->
                if (s.step != BoardingStep.ANNOUNCING || e.generation != s.generation) s to emptyList()
                else if (e.outcome == AnnounceOutcome.CANCELLED) announceCancelled(s)
                else s.copy(step = BoardingStep.ASKING, voiceUnsure = false, announceOutcome = e.outcome) to
                    listOf(BoardingEffect.SpeakQuestion(s.generation), BoardingEffect.ArmResponseTimeout(s.generation))
            is BoardingEvent.AnnounceCancelled ->
                if (s.step != BoardingStep.ANNOUNCING || e.generation != s.generation) s to emptyList()
                else announceCancelled(s)
            is BoardingEvent.Answer -> {
                if (s.step != BoardingStep.ASKING || e.generation != s.generation) s to emptyList()
                else if (e.yes) {
                    // 보고에는 질문 세대를 남기고, 아이 안내는 새 세대로 시작한다(2026-11-07 Unit 5.1 N2).
                    // 질문 세대의 늦은 콜백(타이머·음성·발화 완료)이 아이 안내 단계에 닿지 않게 경계를 나눈다.
                    val r = GuardianReport(e.source, s.generation, s.seatSnapshot, s.sessionChildren)
                    val d = DeliveredReport(r, ReportDisposition.CHILD_NOTICE_PENDING)
                    val gen = s.generation + 1
                    s.copy(step = BoardingStep.CHILD_NOTICE, report = r, voiceUnsure = false, delivered = d, generation = gen) to
                        listOf(BoardingEffect.StopAll, BoardingEffect.SpeakChildNotice(gen),
                            BoardingEffect.ParticipationFeedback(r, savePersonalRecord = !s.withoutProfile && s.sessionChildren.isNotEmpty()),
                            BoardingEffect.ReportDispositionChanged(d))
                } else noOrTimeout(s)
            }
            is BoardingEvent.VoiceUnsure ->
                if (s.step != BoardingStep.ASKING || e.generation != s.generation) s to emptyList()
                else s.copy(voiceUnsure = true) to emptyList()
            is BoardingEvent.Timeout ->
                if (s.step != BoardingStep.ASKING || e.generation != s.generation) s to emptyList()
                else noOrTimeout(s)
            BoardingEvent.Restart ->
                if (s.step != BoardingStep.WAITING_GUARDIAN) s to emptyList()
                else {
                    val gen = s.generation + 1
                    s.copy(step = BoardingStep.ANNOUNCING, generation = gen, reannounce = 0, voiceUnsure = false, announceOutcome = null) to
                        listOf(BoardingEffect.StopAll, BoardingEffect.SpeakAnnounce(gen))
                }
            is BoardingEvent.NoticeNext ->
                if (s.step != BoardingStep.CHILD_NOTICE || e.generation != s.generation) s to emptyList()
                else settle(s.copy(step = BoardingStep.FINISHED), ReportDisposition.CHILD_NOTICE_DONE, listOf(BoardingEffect.StopAll))
            BoardingEvent.ToHome ->
                if (s.step in IN_PROGRESS) cancelTo(s, BoardingStep.HOME)
                // 2026-11-07 검토자 P1-4: 아이 안내 중 홈 이동은 P 이탈·화면 이탈과 같은 공통 취소 경로(세대 증가·보고 비움)
                else if (s.step == BoardingStep.CHILD_NOTICE) interrupt(s, null)
                // 2026-11-07 Unit 5.1: 종료 후 홈 이동은 진행 중 확인 데이터(보고·좌석·스냅샷)를 남기지 않는다. 내보낸 보고(delivered)는 유지
                else if (s.step == BoardingStep.FINISHED)
                    s.copy(step = BoardingStep.HOME, report = null, seats = emptySet(), seatSnapshot = emptySet(), voiceUnsure = false) to emptyList()
                else s.copy(step = BoardingStep.HOME) to emptyList()
            BoardingEvent.LeftForeground -> s to emptyList()   // 위에서 처리
        }
    }

    /**
     * 게이트 변화. 정차 허용을 벗어나면 [interrupt] 규칙을 따른다.
     * 정차 허용으로 돌아와도 자동으로 질문을 재개하거나 예 응답을 복구하지 않는다(처음부터 다시 시작).
     */
    fun onGate(s: BoardingState, next: VehicleGate): Pair<BoardingState, List<BoardingEffect>> {
        if (next == VehicleGate.PARK_ALLOWED) return s to emptyList()
        val why = if (next == VehicleGate.UNKNOWN) Interruption.GEAR_UNKNOWN else Interruption.LEFT_PARK
        return interrupt(s, why)
    }

    /**
     * 게이트 입력까지 알 때의 게이트 변화. 앱이 전경이 아니어서 RESTRICTED가 된 경우 '정차 아님'이 아니라
     * '화면 이탈' 문구를 쓴다(2026-11-07 검토자 P1-4, 화면 이탈 문구). 그 밖은 [onGate]와 같다.
     */
    fun onGate(s: BoardingState, input: GateInput): Pair<BoardingState, List<BoardingEffect>> {
        val why = VehicleGateRule.interruptionFor(input) ?: return s to emptyList()
        return interrupt(s, why)
    }

    /**
     * 앱 연결용 진입점(2026-11-07 Unit 5.1). 게이트 입력 전체를 받아 멈춤 이유를 정확히 고른다(N4).
     * 정차 허용이 아니면 [onGate](GateInput)로 공통 취소하고, 허용이면 [reduce](VehicleGate)와 같다.
     */
    fun reduce(s: BoardingState, input: GateInput, e: BoardingEvent): Pair<BoardingState, List<BoardingEffect>> {
        if (e == BoardingEvent.LeftForeground) return interrupt(s, Interruption.LEFT_APP)
        val gate = VehicleGateRule.evaluate(input)
        if (gate != VehicleGate.PARK_ALLOWED) return if (s.step == BoardingStep.HOME) s to emptyList() else onGate(s, input)
        return reduce(s, gate, e)
    }

    /**
     * P 이탈·상태 불명·화면 이탈의 공통 처리(2026-11-07 검토자 지시 4).
     *  - 어느 단계든 홈으로 가고 좌석·좌석 스냅샷·보고를 비운다. 세대를 올려 늦은 콜백(예·타이머·발화 완료)을 버린다.
     *  - 진행 중 확인·선택 화면이면 '멈춤' 안내를 띄우고, 확인이 끝난 뒤(아이 안내·종료)면 안내 없이 홈으로 간다.
     *  - 아이 선택은 유지하되 '지난번 선택'으로 표시해 현재 탑승 확인으로 취급하지 않는다. 좌석은 다시 고른다.
     *  - 홈에서는 기존 안내(interruption)를 덮어쓰지 않는다.
     *  - 이미 내보낸 보고([BoardingState.delivered])는 지우지 않는다. 아이 안내 중이었다면 처리 상태만 '중단'으로 바꾼다(지시 4, 작업 단위 3).
     *  - [why]가 null이면 보호자가 직접 홈으로 간 경우라 멈춤 안내를 띄우지 않는다(아이 안내 중 [BoardingEvent.ToHome]).
     */
    private fun interrupt(s: BoardingState, why: Interruption?): Pair<BoardingState, List<BoardingEffect>> {
        val cleared = s.copy(step = BoardingStep.HOME, seats = emptySet(), seatSnapshot = emptySet(), generation = s.generation + 1,
            reannounce = 0, report = null, voiceUnsure = false, childrenFromPrevious = s.children.isNotEmpty(), announceOutcome = null)
        return when (s.step) {
            BoardingStep.HOME -> cleared.copy(interruption = s.interruption) to emptyList()
            BoardingStep.CHILD_NOTICE ->
                settle(cleared.copy(interruption = null), ReportDisposition.CHILD_NOTICE_INTERRUPTED, listOf(BoardingEffect.StopAll))
            BoardingStep.FINISHED -> cleared.copy(interruption = null) to listOf(BoardingEffect.StopAll)
            else -> cleared.copy(interruption = why) to listOf(BoardingEffect.StopAll)
        }
    }

    /** 내보낸 보고가 아이 안내 중(PENDING)일 때만 처리 상태를 [to]로 바꾸고 기록 효과를 붙인다. 이미 끝난 처리 상태는 바꾸지 않는다 */
    private fun settle(s: BoardingState, to: ReportDisposition, effects: List<BoardingEffect>): Pair<BoardingState, List<BoardingEffect>> {
        val d = s.delivered
        if (d == null || d.disposition != ReportDisposition.CHILD_NOTICE_PENDING) return s to effects
        val nd = d.copy(disposition = to)
        return s.copy(delivered = nd) to effects + BoardingEffect.ReportDispositionChanged(nd)
    }

    private fun cancelTo(s: BoardingState, step: BoardingStep): Pair<BoardingState, List<BoardingEffect>> =
        s.copy(step = step, seatSnapshot = emptySet(), generation = s.generation + 1, reannounce = 0, report = null,
            voiceUnsure = false, announceOutcome = null) to listOf(BoardingEffect.StopAll)

    /**
     * 안내 발화가 앱 중지로 끊김(흐름은 그대로): 질문으로 넘기지 않는다. 세대를 올려 남은 콜백·타이머를 버리고
     * 보호자 대기로 가서 명시적 [다시 시작]만 받는다(자동 재개·자동 완료 없음).
     */
    private fun announceCancelled(s: BoardingState): Pair<BoardingState, List<BoardingEffect>> =
        s.copy(step = BoardingStep.WAITING_GUARDIAN, generation = s.generation + 1, voiceUnsure = false,
            announceOutcome = AnnounceOutcome.CANCELLED) to listOf(BoardingEffect.StopAll)

    private fun noOrTimeout(s: BoardingState): Pair<BoardingState, List<BoardingEffect>> =
        if (s.reannounce < MAX_REANNOUNCE) {
            val gen = s.generation + 1
            s.copy(step = BoardingStep.ANNOUNCING, generation = gen, reannounce = s.reannounce + 1, voiceUnsure = false, announceOutcome = null) to
                listOf(BoardingEffect.StopAll, BoardingEffect.SpeakAnnounce(gen))
        } else {
            s.copy(step = BoardingStep.WAITING_GUARDIAN, voiceUnsure = false, announceOutcome = null) to listOf(BoardingEffect.StopAll)
        }

    private fun Set<String>.toggle(x: String): Set<String> = if (x in this) this - x else this + x
}
