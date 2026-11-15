package com.crewguide.probe

// 무대(배경+캐릭터+자막) 장면 선택 순수 로직(가안). Android 클래스를 쓰지 않는다.
// 보호자 확인·잠금·이동 끝 상태는 읽기만 하며, 이 파일은 그 상태를 바꾸지 않는다.

/** MainActivity.GState의 순수 복제(이름 동일). MainActivity가 한 줄 매핑으로 옮긴다 */
enum class StageGState { IDLE, ANNOUNCING, ASKING, WAITING_GUARDIAN, YES_REPORTED }

/** 무대가 읽는 현재 상태 묶음(읽기 전용 스냅샷) */
data class StageUiState(
    val g: StageGState,
    val locked: Boolean,
    val lockText: String,
    val childrenSelected: Int,
    val seatsSelected: Int,
    /**
     * 사용 안 함(2026-11-14 사용자 지시: 이동 끝 안내 제거). 아래 3개는 예전 TRIP_END 장면(안내 표시·문구·신호 출처)용이었다.
     * 지금은 어떤 값을 넣어도 장면·자막·보조 라벨에 쓰지 않는다. 자리 순서로 만드는 곳(테스트·디자인 미리보기)이 많아 칸만 남겼다
     */
    val tripPromptShown: Boolean,
    val tripPromptText: String,
    val tripSourceMock: Boolean,
    val childNoticeText: String,
    /** 하차 뒷좌석 확인 음성 요청이 나간 뒤 약 8초 동안 true(자막 표시용, 소리가 났다는 뜻이 아님) */
    val exitCheckActive: Boolean = false,
    /** 등록된 아이 프로필이 하나라도 있는지. false이면 아이 선택을 요구하지 않는다 */
    val profilesExist: Boolean = true,
    /** 보호자 '예' 응답 세션에서 참여 스티커를 보여 줄지(StickerRule 결과) */
    val stickerShown: Boolean = false,
    /** 이번 세션 스티커가 기록된 아이 수(0이면 +1 표기 생략) */
    val stickerKidCount: Int = 0,
    /** 이번 '예'에서 스티커 기록 저장·읽기 실패(참여 표시와 영구 기록 성공을 구분해 '기록 안 됨' 표기) */
    val stickerRecordFailed: Boolean = false,
    /** 모의 기어가 켜져 있는지(보조 라벨 표기용) */
    val mockGear: Boolean = false,
    /** 앱 내부 모의 내비 이동 중일 때 표시할 남은 거리 문구(없으면 빈 문자열) */
    val mockMovingText: String = "",
    /** 보호자 '예' 시점에 선택 좌석 중 미체결 참고 신호가 있을 때 덧붙이는 단정 없는 참고 문구 */
    val seatRefNote: String = "",
    /** 캐릭터 표시용 실제 음성 실행 상태(TTS onStart~종료, STT request 활성). 안전 판단에 쓰지 않는다(2026-11-08 v6.1) */
    val crewVoice: CrewVoice = CrewVoice.NONE,
    /** v3: 이번 이동에 이미 스티커를 기록해 이번 [예]에서는 새로 주지 않았는지(배지 설명 문구용, 표시 전용) */
    val stickerAlreadyThisTrip: Boolean = false,
    /** 잠금 중 유효한 비-P 기어(D/R/N)를 받았는지. 캐릭터 DRIVING/INTERRUPTED 구분용(잠금 판정 자체는 [locked]) */
    val drivingGear: Boolean = false,
    /** 보호자 안내가 끝난 방식(2026-11-08 P0-C 재감사). SPOKEN이 아니면 질문 화면에 안내 문구와 이유를 함께 보인다 */
    val announceOutcome: AnnounceOutcome? = null,
    /** 2026-11-12 A2: 질문 음성 실패 뒤 [다시 듣기]를 보일지(VoiceReplayRule). 상태를 바꾸지 않는다 */
    val replayAvailable: Boolean = false,
    /** 질문 음성이 실패했고 음성 엔진이 다시 연결 중(안내 한 줄용) */
    val voiceReconnecting: Boolean = false,
    /** 2026-11-13 PM 계약: 자동 확인·이동 안내 상태. 자동 흐름이 아니면 null([AutoCheckUi]) */
    val autoCheck: AutoCheckUi? = null,
)

enum class Scene {
    // TRIP_END: 사용 안 함(2026-11-14 사용자 지시). sceneOf가 고르지 않는다. 장면별 when(배경·컨트롤·좌석 화면 등 디자인 파일)이 이 값을
    // 다루고 있어 enum 값만 남겼다(자막은 빈 문자열)
    PICK, READY, ANNOUNCE, QUESTION, YES, WAIT_GUARDIAN, LOCKED, TRIP_END, EXIT_CHECK,
    // 2026-11-13 PM 계약(자동 확인): 아래 4개의 실제 진입 조건(sceneOf)은 개발 담당, 화면 모양은 디자인 담당이 채운다
    /** S03 대체: 좌석별 착석·벨트 신호 확인 중 */
    AUTO_CHECK,
    /** 선택 좌석 확인 완료 → 출발 준비(P) */
    DEPART_READY,
    /** 내비 도착 뒤 유효 P: 하차 안내(S08) */
    ARRIVED,
    /** 착석 신호가 모두 꺼짐: 마무리 + 스티커 연출 */
    ALL_LEFT,
}

/** 무대 화면 크기 상수(DesignRules 최소 기준 이상이어야 하며 테스트가 확인한다) */
object StageLayout {
    const val CAPTION_SP = 44f
    const val SUB_SP = 24f
    const val CLOSE_SP = 24f
    const val CLOSE_MIN_DP = 76
    const val DOT_DP = 24
    const val CAPTION_MAX_LINES = 2
    // 높이 예산(dp, 2560x1440에서 dp=px 가정). 합 540(+말풍선 꼬리 22 = 562) → 캐릭터 한 변 = 1440 − 562 = 878
    const val TOP_BAR_DP = 100          // 단계 점 + 우상단 버튼 줄
    const val CAPTION_AREA_DP = 140     // 자막 44sp 두 줄 고정 높이
    const val SUB_LABEL_DP = 40         // 맨 아래 보조 라벨 한 줄(24sp)
    const val MARGIN_DP = 40            // 캐릭터 영역 위아래 여유(가운데 놓기)
    const val CHAR_MIN_DP = 200         // 캐릭터 한 변 하한
    const val SPOTLIGHT_SCALE = 1.15f   // 스포트라이트 지름 = 캐릭터 × 1.15 (캐릭터 영역 안으로 제한)
    const val SPOTLIGHT_ALPHA = 0x99    // 스포트라이트 중심 투명도(색은 Palette 무대 배경 위쪽 색에서 파생)
    // 하단 컨트롤 영역의 최대 높이(캐릭터 쪽에서 비워 두는 값). 근거(dp):
    //   준비 단계(PICK·READY) 두 줄: 칩 76 + 줄 간격 23 + 칩 76 = 175, 하단 여백 16 → 191,
    //   버튼 실제 높이가 최소값보다 약간 커질 여유 29를 더해 220. 다른 장면은 한 줄(최대 112 + 안내 줄)이라 이 안에 든다.
    const val CONTROL_RESERVE_DP = 220
    const val CONTROL_BOTTOM_MARGIN_DP = 16   // 컨트롤 아래 여백(보조 라벨 위)
    // ---- 겹침(오버레이) 구조 ----
    const val INSET_FALLBACK_DP = 16          // 시스템 바 인셋을 못 읽거나 0일 때 상단·하단 고정 여백
    const val CONTROL_PANEL_PAD_DP = 16       // 컨트롤 바탕 위·아래 안쪽 여백
    // 컨트롤 바탕 최대 높이(PICK/READY 두 줄): 칩 76 + 간격 23 + 칩 76 + 위아래 여백 16×2 = 207
    const val CONTROL_PANEL_MAX_DP = 76 * 2 + 23 + 2 * CONTROL_PANEL_PAD_DP
    // U-2c: 컨트롤 패널 시안 값. 최소 높이 = 화면 높이의 14%(상한 CONTROL_PANEL_MAX_DP, 내용이 크면 내용 우선), 위쪽 모서리 32dp, 낮 불투명
    const val CONTROL_PANEL_PERCENT = 14
    const val CONTROL_PANEL_RADIUS_DP = 32
    const val CONTROL_PANEL_DAY_ALPHA = 0xFF
    const val CAPTION_PANEL_PAD_DP = 8       // 자막 판 위·아래 안쪽 여백
    const val CAPTION_PANEL_DP = CAPTION_AREA_DP + 2 * CAPTION_PANEL_PAD_DP   // 156
    const val CAPTION_TAIL_DP = StageBubble.TAIL_DP                            // 말풍선 꼬리 높이(롤백: 0)
    const val CAPTION_BLOCK_DP = CAPTION_PANEL_DP + CAPTION_TAIL_DP            // 178 = 꼬리 포함 자막 블록 높이
    const val FACE_BOTTOM_CANVAS_Y = 62       // 캐릭터 캔버스(0~100)에서 얼굴·상반신 윗부분의 아래 경계 y
    const val PANEL_ALPHA = 0xF2              // 자막 판·컨트롤 바탕 투명도(0xCC→0xF2, 몸 윤곽이 글자와 안 섞이게. 색은 무대 배경 아래색에서 파생)
    /** 캐릭터가 보일 캔버스 구간. 구도 답이 오면 이 한 줄만 바꾼다(예: CharacterViewport.BUST_WIDE) */
    val CHARACTER_VIEWPORT: CharacterViewport = CharacterViewport.BUST_UPPER   // 롤백: FULL로 되돌린다
}

object StageText {
    const val PICK = "누가 탔나요? 아이를 골라 주세요"
    const val PICK_NO_PROFILE = "앉은 자리를 골라 주세요"   // 프로필이 없는 기기용 가안 문구
    const val PICK_SEAT = "아이가 앉은 자리를 골라 주세요"
    const val WAIT_GUARDIAN = "준비되면 다시 시작해 주세요"
    /** 모의 도착 표기(debug 모의 내비만 이 표기를 켠다. 문구는 기존 MockText.MOCK_LABEL과 같다) */
    const val MOCK_ARRIVAL_LABEL = "앱 내부 모의 도착(실제 내비 이벤트 아님)"
    const val CLOSE_BUTTON = "미리보기 닫기"
    const val PREVIEW_BUTTON = "무대 미리보기(가안, 개발용)"

    /** 2026-11-11 U3(감사 #4): 고른 뒤에는 인사 대신 다음 행동을 알려 준다(인사는 홈 말풍선에만) */
    const val READY = "준비됐어요! 보호자 확인을 시작해 주세요"
    fun ready(): String = READY
}

/**
 * 단계 점 5개의 이름(접근성 설명). 인덱스는 dotOf 값과 같다.
 * 2026-11-13 자동 확인 흐름으로 이름을 바꿨다(이전: 아이 고르기 / 보호자 안내 / 질문 / 아이 안내 / 이동 끝내기).
 */
object StageSteps {
    val NAMES: List<String> = listOf(
        "1단계 아이·자리 고르기",
        "2단계 신호 확인",
        "3단계 출발",
        "4단계 도착",
        "5단계 하차",
    )

    /** 2차 U2: 단계 점 아래 캡슐에 보이는 짧은 이름(24sp 한 줄). 인덱스는 dotOf와 같다 */
    val SHORT: List<String> = listOf("아이·자리 고르기", "신호 확인", "출발", "도착", "하차")

    /** 현재 단계 캡슐 문구: '2/5 · 보호자 안내'. 범위 밖(잠금 -1 등)이면 빈 문자열 */
    fun currentLabel(dot: Int): String =
        if (dot in SHORT.indices) "${dot + 1}/${SHORT.size} · ${SHORT[dot]}" else ""
}

/**
 * 장면 우선순위: 잠금 → 자동 확인·이동 안내([StageUiState.autoCheck]) → 하차 확인(IDLE일 때) → 보호자 확인 상태 → 선택 수.
 * 2026-11-13 자동 흐름: 확인 중 AUTO_CHECK / 완료 뒤 정차 DEPART_READY / 도착 뒤 정차 ARRIVED / 하차 마무리 ALL_LEFT.
 * 주행 중(DEPARTING·DRIVING)은 잠금이 먼저다.
 * 2026-11-14 PM 결정 26: 도착 신호 없이 P로 멈춘 EXITING도 옛 YES·PICK·READY 장면을 쓰지 않고 도착 화면(ARRIVED, 좌석 그림)과 같은
 * 장면으로 보인다(옛 아이 안내 "우리 같이 벨트를 확인해 볼까요"가 보이지 않게). 하차 확인 한 줄(IDLE일 때)이 나오는 동안에도 도착 화면이 먼저다(루프 r2 PM 결정, 음성은 그대로).
 * 옛 장면 코드는 남겨 둔다(자동 흐름 밖 경로).
 * 2026-11-14 사용자 지시: 이동 끝 안내 장면(TRIP_END, "이번 이동이 끝난 것 같아요…")은 고르지 않는다. 이동 끝은 '모두 내렸어요' 자동 마무리
 * (ALL_LEFT)와 [이번 이동 끝내기] 버튼으로만 처리한다. 내비 도착 신호가 와도 장면은 도착 화면(ARRIVED·EXITING) 또는 원래 장면 그대로다.
 */
fun sceneOf(s: StageUiState): Scene {
    // r10 G(계약 v10 gearCheckPending, 개발 3 c181171): 기어 확인 중에는 잠금 판정(locked)이 그대로 true여도 전체 잠금 카드 대신 좌석 확인 화면.
    // 화면은 "기어 확인 중…" 칩 + 누름 없음(SeatBoardRules.gearPendingModel), 캐릭터 정지·말풍선 멈춤은 StageScreen이 잠금처럼 다룬다. 터치는 MainActivity가 막는다
    if (s.locked && s.autoCheck?.gearCheckPending != true) return Scene.LOCKED
    if (s.autoCheck?.gearCheckPending == true) return Scene.AUTO_CHECK
    // r7(계약 v7 quickStartWaiting, 결정 49): 빠른 시작 대기는 좌석 확인 화면 틀(한 줄만). 개발 B는 phase = CHECKING으로 주지만 단계 값과 무관하게 고정한다.
    // 기어 첫 값 전(시작 유예)은 위 잠금이 먼저다("기어 확인 중…" 카드 그대로). 홈·홈 인사는 개발 B가 syncHome에서 막는다
    if (s.autoCheck?.quickStartWaiting == true) return Scene.AUTO_CHECK
    when (s.autoCheck?.phase) {
        AutoPhase.CHECKING -> return Scene.AUTO_CHECK
        AutoPhase.CONFIRMED -> return Scene.DEPART_READY
        AutoPhase.ARRIVED -> return Scene.ARRIVED
        AutoPhase.ALL_LEFT -> return Scene.ALL_LEFT
        AutoPhase.DEPARTING, AutoPhase.DRIVING, AutoPhase.EXITING, null -> {}
    }
    // 결정 26: 자동 흐름의 EXITING은 도착 화면(도착 음성·[이번 이동 끝내기]는 TripGuide·도착 화면이 맡는다).
    // 2026-11-14 루프 r2 PM 결정: 하차 한 줄이 나오는 동안에도 도착 화면이 먼저다(음성은 그대로). 옛 하차 한 줄 장면은 자동 흐름 밖에서만(새 무대는 홈으로 덮음, OldSceneGuard)
    if (s.autoCheck?.phase == AutoPhase.EXITING) return Scene.ARRIVED
    if (s.exitCheckActive && s.g == StageGState.IDLE) return Scene.EXIT_CHECK
    return when (s.g) {
        StageGState.ANNOUNCING -> Scene.ANNOUNCE
        StageGState.ASKING -> Scene.QUESTION
        StageGState.YES_REPORTED -> Scene.YES
        StageGState.WAITING_GUARDIAN -> Scene.WAIT_GUARDIAN
        StageGState.IDLE ->
            if ((s.profilesExist && s.childrenSelected == 0) || s.seatsSelected == 0) Scene.PICK else Scene.READY
    }
}

fun captionOf(scene: Scene, s: StageUiState): String = when (scene) {
    // 2026-11-11 U3(감사 #4): 아이는 골랐는데 자리가 없으면 자리를 고르라고 안내(다음 행동과 맞춘다)
    Scene.PICK -> when {
        !s.profilesExist -> StageText.PICK_NO_PROFILE
        s.childrenSelected > 0 && s.seatsSelected == 0 -> StageText.PICK_SEAT
        else -> StageText.PICK
    }
    Scene.READY -> StageText.ready()
    Scene.ANNOUNCE -> UserTexts.GUARDIAN_PROMPT
    // 질문 문구는 항상 말풍선에 둔다(2줄 제한으로 잘리지 않게). 안내 음성이 완료되지 않았을 때의 안내 문구는 보조 줄([subLabelOf])에 둔다
    Scene.QUESTION -> UserTexts.GUARDIAN_QUESTION
    Scene.YES -> s.childNoticeText.ifEmpty { UserTexts.CHILD_NOTICE_A }
    Scene.WAIT_GUARDIAN -> if (s.announceOutcome == AnnounceOutcome.CANCELLED) AnnounceOutcome.CANCELLED.note else StageText.WAIT_GUARDIAN
    Scene.LOCKED -> s.lockText
    Scene.TRIP_END -> ""   // 사용 안 함(이동 끝 안내 제거). sceneOf가 고르지 않는다
    Scene.EXIT_CHECK -> ExitCheckRule.TEXT
    // 2026-11-13 자동 흐름 문구(신호 기준). 자막은 상태에서 정하고, 말한 문장(lastSpoken)과 섞지 않는다
    // PM 결정 23: 앞줄 앉은 자리도 대상 행(selected)이라, 뒷줄 배정 여부를 좌석 키로 가려 자막을 고른다
    Scene.AUTO_CHECK -> if (s.autoCheck?.quickStartWaiting == true) SeatBoardRules.QUICK_WAITING   // r7(결정 49): 목록 패널(대신 화면) 경로도 같은 한 줄
        else SeatCheckRule.captionForRows(s.autoCheck?.rows.orEmpty())
    Scene.DEPART_READY -> {
        val v = s.autoCheck?.rows.orEmpty().filter { it.selected }.mapNotNull { it.verdict }
        SeatCheckRule.completeText(SeatCheckRule.completeKind(v)) + " " + TripGuide.TEXT_READY_TO_GO
    }
    // r5: 목적지 없는 이동의 정차 질문 중·'아니요'(잠깐 멈춤) 뒤에는 도착 문장이 아니다(계약 v5 askArrived / v5.1 pausedByAnswer)
    // r6(계약 v6): "내릴까요?" 질문 중 = 개발이 준 질문 문장 그대로, 중간 하차 = '도착' 없는 하차 문장. 질문이 먼저(좌석 화면 SeatBoardRules.answerKindOf와 같은 순서)
    Scene.ARRIVED -> arrivedCaption(s.autoCheck)
    // r6(계약 v6 exitByDoor): 문 열림 신호도 썼으면 기준을 "(문·착석 신호 기준)"으로
    // r7(결정 47): 보호자 버튼으로 끝냈으면 신호 기준 문장 없이 "뒷좌석을 한 번 더 확인해 주세요."
    Scene.ALL_LEFT -> SeatBoardRules.allLeftCaptionOf(s.autoCheck)
}

/** 도착·정차 장면(ARRIVED) 자막: "내릴까요?" → 도착 질문 → 중간 하차 → 잠깐 멈춤 → 도착 문장 순서 */
fun arrivedCaption(ac: AutoCheckUi?): String = when {
    ac == null -> TripGuide.TEXT_ARRIVED
    ac.askAlight -> AlightScreenText.question(ac)
    ac.askArrived -> ArrivalAskText.QUESTION
    ac.alightingByAnswer -> AlightScreenText.CAPTION_ALIGHTING
    ac.pausedByAnswer -> ArrivalAskText.PAUSED_SUB
    else -> TripGuide.TEXT_ARRIVED
}

/**
 * 자막 아래 보조 라벨 한 줄: 앱 이름·기준 표기·스티커/모의 상태.
 * 2026-11-14: 이동 끝 장면(TRIP_END)만의 신호 출처 줄("(내비 신호 기준)"·모의 도착)은 장면을 없애면서 같이 없앴다(다른 장면과 같은 줄)
 */
fun subLabelOf(scene: Scene, s: StageUiState): String {
    val auto = s.autoCheck
    val base = DemoLabel.build(
        stickerShown = scene == Scene.YES && s.stickerShown,
        kidCount = if (scene == Scene.YES) s.stickerKidCount else 0,
        mockGear = s.mockGear,
        recordFailed = scene == Scene.YES && s.stickerRecordFailed,
        // 2026-11-13: 자동 흐름에서 보호자 대체 버튼이 쓰였으면 '보호자 확인 포함', 모의 좌석·내비 값이 섞였으면 모의 표기
        guardianIncluded = auto != null && auto.rows.any { it.verdict == SeatVerdict.GUARDIAN_CONFIRMED },
        mockSignal = auto != null && auto.anyMock,
    )
    val withMoving = if (s.mockMovingText.isNotEmpty() && scene != Scene.LOCKED) {
        s.mockMovingText + DemoLabel.SEPARATOR + base
    } else {
        base
    }
    // 질문 화면에서 안내 음성이 완료되지 않은 이유를 앞에 붙인다(모의 표기는 지우지 않음)
    val note = if (scene == Scene.QUESTION) s.announceOutcome?.takeIf { !it.spokenConfirmed }?.note.orEmpty() else ""
    return if (note.isEmpty()) withMoving else if (withMoving.isEmpty()) note else note + DemoLabel.SEPARATOR + withMoving
}

fun mouthOf(scene: Scene): MouthShape = when (scene) {
    Scene.PICK -> MouthShape.SMILE
    Scene.READY -> MouthShape.SMILE
    Scene.ANNOUNCE -> MouthShape.SMALL
    Scene.QUESTION -> MouthShape.SMALL
    Scene.YES -> MouthShape.WIDE
    Scene.WAIT_GUARDIAN -> MouthShape.CLOSED
    Scene.LOCKED -> MouthShape.CLOSED
    Scene.TRIP_END -> MouthShape.SMILE
    Scene.EXIT_CHECK -> MouthShape.SMALL
    Scene.AUTO_CHECK -> MouthShape.SMALL
    Scene.DEPART_READY -> MouthShape.WIDE
    Scene.ARRIVED -> MouthShape.SMALL
    Scene.ALL_LEFT -> MouthShape.WIDE
}

/**
 * 채울 단계 점의 인덱스(0..4). -1이면 모두 비활성.
 * 2026-11-13 새 5단계(아이·자리 고르기 / 신호 확인 / 출발 / 도착 / 하차). 예전 보호자 질문 장면(ANNOUNCE·QUESTION·WAIT_GUARDIAN·YES)은
 * 새 흐름에서 나오지 않지만 '신호 확인' 칸으로 둔다.
 */
fun dotOf(scene: Scene): Int = when (scene) {
    Scene.PICK -> 0
    Scene.READY -> 0
    Scene.ANNOUNCE -> 1
    Scene.QUESTION -> 1
    Scene.WAIT_GUARDIAN -> 1
    Scene.YES -> 1
    Scene.AUTO_CHECK -> 1
    Scene.DEPART_READY -> 2
    Scene.ARRIVED -> 3
    Scene.TRIP_END -> 3
    Scene.EXIT_CHECK -> 4
    Scene.ALL_LEFT -> 4
    Scene.LOCKED -> -1
}
