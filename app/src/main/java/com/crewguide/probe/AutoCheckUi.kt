package com.crewguide.probe

// 2026-11-13 PM 계약(자동 확인 + 내비 안내). 개발 담당과 디자인 담당이 함께 쓰는 순수 데이터 타입이다.
// - 값을 만드는 쪽: 개발 담당(SeatCheckRule·BoardingFlow·TripGuide·MainActivity).
// - 그리는 쪽: 디자인 담당(StageScreen·V6Panels·새 View).
// - 이 파일의 타입·필드 이름을 바꾸려면 PM에게 먼저 알린다(두 작업 공간이 동시에 쓰는 계약).
// 문구 원칙: 신호 기준으로만 말한다("벨트 체결 신호를 받았어요"). "안전", "착용 완료" 같은 보증 표현은 쓰지 않는다.

/** 좌석 착석(점유) 신호. 미지원·단절·읽기 실패·값 없음은 UNKNOWN(빈 좌석으로 바꾸지 않는다) */
enum class SeatOcc { OCCUPIED, EMPTY, UNKNOWN }

/** 선택 좌석 하나의 자동 확인 판정 */
enum class SeatVerdict {
    /** 착석 신호 + 체결 신호 */
    CONFIRMED,
    /** 착석 신호 + 미체결 신호: 좌석 이름으로 재안내, 체결 신호가 오면 자동 재판정 */
    WAIT_BUCKLE,
    /** 착석 신호 없음(빈 좌석): 벨트를 요구하지 않고 확인 대상에서 뺀다 */
    EMPTY_EXCLUDED,
    /** 신호 확인 불가·값 없음: 이 좌석만 보호자 확인 버튼(대체 경로)을 보인다 */
    NEEDS_GUARDIAN,
    /** 대체 버튼으로 보호자가 확인함(보호자 보고, 신호 아님) */
    GUARDIAN_CONFIRMED,
}

/** 좌석 한 칸의 표시 묶음. 차량이 지원한다고 알려 온 좌석만 만든다(좌석 수 = 차량 신호 기준) */
data class SeatCheckRow(
    /** SDK 좌석 키(ROW_1_LEFT, ROW_2_LEFT 등) */
    val seat: String,
    /** 화면 이름(예: "뒷줄 왼쪽") */
    val label: String,
    /**
     * 이 자리가 자동 확인의 판정 대상인가. 2026-11-14 PM 결정 23부터: 아이를 배정한 뒷줄 자리(앉음 신호 있음)
     * **또는** 앉음 신호가 있는 앞줄 자리(어른 벨트도 항상 확인, 아이 배정과 무관). 결정 15의 "selected = childId != null"은 뒷줄에만 해당.
     */
    val selected: Boolean,
    val occ: SeatOcc,
    val belt: SeatBeltRef,
    /** 선택 좌석의 판정. 선택하지 않은 좌석은 null */
    val verdict: SeatVerdict?,
    /** 이 좌석 값에 앱 내부 모의(debug)가 쓰였는가(= [occMock] || [beltMock], 기존 호환). 화면 꼬리표는 [occMock]/[beltMock]을 따로 본다 */
    val mock: Boolean,
    /**
     * 2026-11-13 PM 계약 v2(좌석 화면 하나로 확인, 사용자 승인 시안): 이 자리에 배정된 아이([AutoCheckUi.children]의 id). 없으면 null.
     * PM 결정 15: 확인 대상 = 아이가 배정된 자리([selected] = childId != null). 확인 불가(UNKNOWN) 자리에도 배정할 수 있고,
     * 보호자 확인 버튼은 아이가 배정된 확인 불가 자리에만 보인다. 운전석(ROW_1_LEFT)에는 배정하지 않는다(결정 3·11).
     * 2026-11-14 PM 결정 23: 배정은 앉음 신호가 있는 **뒷줄(ROW_2_*)만** — 앞줄과 빈 칸에서는 늘 null.
     * 앞줄 앉은 자리는 배정 없이도 판정 대상이고, 벨트 확인 불가면 그 칸에 보호자 확인 버튼을 보인다(verdict == NEEDS_GUARDIAN).
     */
    val childId: Int? = null,
    /** 착석 값이 앱 내부 모의인가(ISSUE-11: 실제 VC 착석에는 '모의 신호'를 붙이지 않는다) */
    val occMock: Boolean = false,
    /** 벨트 값이 앱 내부 모의인가(뒷좌석 벨트는 에뮬레이터에서 모의뿐). '모의 신호' 꼬리표는 벨트 표시에만 붙인다 */
    val beltMock: Boolean = false,
    /**
     * 2026-11-15 PM 계약 v8(r8b, 검수 세션 위임 판단·사용자 아침 결정): [verdict] == WAIT_BUCKLE(미체결 신호)인데 재안내가 끝나
     * 보호자가 직접 보고 [보호자 확인]을 누를 수 있다. true면 이 칸에 보호자 확인 버튼 + "벨트 체결 신호가 없어요…" 안내(신호를 바꿔 말하지 않음).
     * 스위치로 끄면 늘 false(지금과 같음). 체결 신호가 오면 false(신호 우선).
     */
    val guardianForUnbuckled: Boolean = false,
    /**
     * 2026-11-15 PM 계약 v10(r10 J, 사용자 결정): 이 자리에 **손님**이 배정됐다([childId] == [SeatCheckRule.GUEST_CHILD] id).
     * 손님 자리도 벨트 확인·주행 중 풀림·하차 확인·두고 내림 대상이고 스티커는 없다. 화면은 칸에 "손님"(기호+글자)을 보인다.
     * 등록 아이가 0명일 때의 기존 손님 이동(이름 "아이")에서도 true.
     */
    val guest: Boolean = false,
    /**
     * v10(r10 I, 사용자 결정): 이 자리에 배정된 아이가 "차량 벨트를 쓰지 않는 자리(카시트)"로 등록돼 있다.
     * true + 미체결 신호면 재안내를 기다리지 않고 바로 [guardianForUnbuckled] = true(체결 신호가 오면 신호 우선). 화면은 칸에 "카시트" 표시(기호+글자)를 보여도 된다.
     */
    val carSeat: Boolean = false,
)

/**
 * 이번 이동에 데려갈 수 있는 아이(별명만, 실명·사진 없음). 좌석 칸의 아이 표시·고르기 순서에 쓴다.
 * v10(r10 I): [carSeat] = 등록·수정에서 켠 "차량 벨트를 쓰지 않는 자리(카시트)" 표시(기기에만 저장).
 * v10(r10 J): 목록 끝에 손님([SeatCheckRule.GUEST_CHILD], id -1)이 들어갈 수 있다(고르기 순서 없음 → 아이들 → 손님 → 없음).
 */
data class ChildTag(val id: Int, val nick: String, val carSeat: Boolean = false)

/** 자동 진행 단계(확인 중 → 완료 → 출발 → 주행 → 도착 → 하차 → 모두 내림) */
enum class AutoPhase { CHECKING, CONFIRMED, DEPARTING, DRIVING, ARRIVED, EXITING, ALL_LEFT }

/** 내비 시간 값. 숫자만 다룬다(목적지 이름·주소·좌표는 읽지도 저장하지도 않는다). 값이 없으면 null */
data class TripEta(
    /** 경로 시작 때 전체 예상 시간(분, 올림) */
    val totalMin: Int?,
    /** 현재 남은 시간(분, 올림) */
    val remainMin: Int?,
    /** 출처: REAL = NaviHelper 수신, MOCK = debug 모의 내비 */
    val source: SignalSource,
)

/** 무대가 읽는 자동 확인·이동 안내 상태(읽기 전용 스냅샷). 자동 흐름이 아니면 [StageUiState.autoCheck]는 null */
data class AutoCheckUi(
    val phase: AutoPhase,
    /** 지원 좌석 전체(선택·비선택 모두). 화면은 이 순서대로 그린다 */
    val rows: List<SeatCheckRow>,
    /** 선택하지 않았는데 착석 신호가 있는 좌석의 화면 이름("다른 자리에도 누가 앉았어요" 안내용) */
    val extraOccupied: List<String> = emptyList(),
    val eta: TripEta? = null,
    /** 이번 이동에서 스티커 조건(자동 확인 완료 또는 대체 버튼)을 채웠는가 */
    val stickerEarned: Boolean = false,
    /**
     * 스티커 획득 연출을 지금 보여 줄지(이동당 1회).
     * 2026-11-15 PM 계약 v7(r7 1절, 사용자 지시): 지급 시점이 '모두 내린 뒤'에서 **하차 안내가 나올 때**(실제 도착 뒤 P / "예, 도착했어요" / "예, 내려요")로 바뀌었다.
     * 하차 안내 화면(도착·중간 하차)에서 true → 화면은 주간 도장판 + 오늘 별 연출. 모두 내림(ALL_LEFT) 화면에는 새 연출 없음(도장판은 남겨도 됨).
     */
    val stickerJustGranted: Boolean = false,
    /** 좌석·기어·내비 중 하나라도 앱 내부 모의 값이면 true(화면에 '모의 신호' 표시 유지) */
    val anyMock: Boolean = false,
    /** 마지막 안내 문구(말풍선·자막용). 소리가 났다는 뜻은 아니다 */
    val lastSpoken: String = "",
    /** 2026-11-13 계약 v2: 고를 수 있는 아이 목록(등록 순서). 좌석 칸을 누르면 이 순서로 배정이 바뀐다(개발 담당이 처리) */
    val children: List<ChildTag> = emptyList(),
    /**
     * 2026-11-14 PM 계약 v3(말풍선 이어 쌓기, 지시 v3 4절): 승무원이 **실제로 말한 문장 하나**를 화면 말풍선용으로 준다(음성 문장과 같음).
     * 결정 14에 따라 예정 시간("도착까지 약 N분…") 문장은 넣지 않는다. 말한 것이 없으면 "".
     * 화면은 [bubbleSeq]가 바뀔 때만 새 말풍선을 쌓는다(순번이 같으면 다시 그리지 않음). 같은 문장이 연달아 오면 순번이 바뀌어도 새로 쌓지 않는다(지시 v3 '연달아 중복 금지', 디자인 `TripBubbleRules.STACK_REPEATED_SEQ = false`).
     * 값을 채우는 쪽: 개발(TTS 발화 요청 시점). 그리는 쪽: 디자인(StageScreen 말풍선 칸).
     */
    val bubbleLine: String = "",
    /** [bubbleLine]의 순번. 이동마다 0부터 늘고, 새 이동·홈 복귀 때 0으로 돌아간다 */
    val bubbleSeq: Int = 0,
    /**
     * 2026-11-14 PM 계약 v4(루프 r1 J2, 임시 결정 34·사용자 확인 대기): '모두 내림'(ALL_LEFT) 화면에서 **뒷줄 착석 신호가 다시 왔음**.
     * true면 화면은 제목은 그대로, 부제만 "다시 앉은 신호가 있어요"로 바꾸고 [새로 출발 확인] 버튼을 보인다(누르면 [StageActions.onNewTripFromAllLeft]).
     * 자동 전환·음성 없음. 개발이 채우는 조건: phase == ALL_LEFT + 뒷줄 착석(앉음) 1석 이상 + 유효 P·플랫폼 허용. 다시 비면 false.
     */
    val reseatedAfterAllLeft: Boolean = false,
    /**
     * 2026-11-14 PM 계약 v5(r5 추가 지시 A, 임시 결정 36 교체 — 사용자 지시): **도착 신호가 없는 이동**(내비 목적지 없음)이 출발 뒤 유효 P가 됐다 → "목적지에 도착했나요?"를 묻는 중.
     * true면 화면은 질문 + [예, 도착했어요]([StageActions.onArrivalAnswer] true) / [아니요, 잠깐 멈췄어요](false) 두 버튼(76dp 이상)을 보인다.
     * 개발이 채우는 조건: 출발한 이동 + 도착 신호 없음 + 유효 P·플랫폼 허용 + 이번 정차에서 아직 대답 없음. 대답하거나 D로 바뀌면 false(대답 없이 D = 아니요).
     * 대답 없이 P가 이어지면 true 유지(다시 묻지 않음, 화면에 질문만 남김). 정차 한 번에 한 번만.
     */
    val askArrived: Boolean = false,
    /**
     * v5: 질문에 '예'(버튼 또는 음성)로 도착 흐름에 들어간 뒤, 잘못 알아들었을 때 되돌릴 길. true면 도착 화면에 [아니요, 잠깐 멈췄어요]를 함께 보인다
     * (누르면 [StageActions.onArrivalAnswer] false → 이동을 잇고 내비를 앞으로). 하차 마무리(ALL_LEFT)가 되거나 D로 바뀌면 false.
     */
    val arrivedByAnswer: Boolean = false,
    /**
     * 2026-11-14 PM 계약 v5.1(개발 A 제안): 이번 정차에서 "목적지에 도착했나요?"에 **아니요**(버튼·음성·대답 없이 D 전의 아니요)로 답했고 아직 P다.
     * true면 화면은 도착 장면 대신 제목 "잠깐 멈췄어요"(부제·단계 칩도 도착이 아닌 정차 표현)로 그린다. 도착 음성·하차 한 줄·두고 내림은 없다.
     * 개발이 채우는 조건: 아니요 대답 + 유효 P. D로 바뀌거나 이동이 끝나면 false. [askArrived]·[arrivedByAnswer]와 동시에 true가 되지 않는다.
     */
    val pausedByAnswer: Boolean = false,
    /**
     * 2026-11-15 PM 계약 v6(r6 5절, 사용자 지시): **"내릴까요?"를 묻는 중.** 두 경우:
     *  (1) 출발한 이동 + 도착 신호 없음 + 내비 길 안내 중(결정 41 조회) + 유효 P → "잠시 정차해요. 내릴까요?"
     *  (2) 목적지 없는 이동에서 "목적지에 도착했나요?"에 아니요 → 바로 이어서 "내릴까요?"
     * 질문 문장은 [askAlightText](개발이 채움, 화면은 그대로 그림). 버튼 [예, 내려요]([StageActions.onAlightAnswer] true) / [아니요, 계속 가요](false), 76dp 이상.
     * [askArrived]와 동시에 true가 되지 않는다. 대답하거나 D로 바뀌면 false(대답 없이 D = 아니요). 정차당 1회.
     */
    val askAlight: Boolean = false,
    /** v6: [askAlight] 질문 문장("잠시 정차해요. 내릴까요?" 또는 "내릴까요?"). 말한 문장과 같다 */
    val askAlightText: String = "",
    /**
     * v6: "내릴까요?"에 **예** → 그 자리에서 이동을 마무리하는 흐름(중간 하차) 중. 화면 제목·칩에 '도착'을 쓰지 않는다(제목 문구는 개발·디자인이 정해 보고, 예 "잠깐 내려요").
     * v6.2(디자인 F 지적): 하차 마무리(ALL_LEFT) 화면 동안에도 **true를 유지**한다(중간 하차로 끝난 이동의 칩·문구에 '도착'이 다시 보이지 않게). 이동이 끝나거나 새 이동이면 false.
     * 화면은 phase로 질문 뒤 하차 안내 중인지 / 하차 마무리인지 구분한다. [arrivedByAnswer]·[pausedByAnswer]와 동시에 true가 되지 않는다.
     */
    val alightingByAnswer: Boolean = false,
    /**
     * v6(r6 4절): 이번 하차 판단에 **문 열림 신호를 썼다**(문 신호 읽기 가능 + 이번 정차에 뒷문이 한 번 이상 열림). 화면 기준 줄은 true면 "(문·착석 신호 기준)", false면 "(착석 신호 기준)".
     * 문 신호가 확인 불가라 착석만으로 판단했으면 false.
     */
    val exitByDoor: Boolean = false,
    /**
     * v6.1(r6 6절, 사용자 지시): 이번 확인 화면은 **빠른 시작**으로 왔다(지난 이동이 중간 하차로 끝났거나 끝내지 못한 채 앱이 종료됐고 60분 이내 → 홈·홈 인사 건너뜀).
     * true면 화면은 확인 화면 그대로 + 짧은 한 줄(임시 결정 43 "다시 출발 전에 벨트를 확인할게요", 문구는 개발이 [lastSpoken]으로도 줌)을 보여도 된다. 새 이동이다(벨트 다시 확인, 스티커 새 기준).
     * 확인 완료·[처음으로]·이동 끝이면 false.
     */
    val quickStart: Boolean = false,
    /**
     * v7(r7 2절 나): 이번 이동을 **보호자 버튼**으로 끝냈다(뒷문이 열린 적 없어 자동으로 안 끝난 경우 등). true면 마무리 화면 기준 줄 "(보호자 확인)",
     * 신호 기준 문장("내린 것 같아요")을 쓰지 않는다. [exitByDoor]보다 우선.
     */
    val exitByGuardian: Boolean = false,
    /**
     * v7(r7 3절): 빠른 시작으로 판단했고 유효 P·플랫폼 허용을 기다리는 중. true면 화면은 홈·홈 인사 말풍선 대신 좌석 확인 화면 틀(또는 "차량 신호 확인 중" 한 줄만 있는 빈 무대)을 그린다.
     * 신호가 오면 false(확인 시작), 정한 시간 안에 안 오면 false(홈). P 아님·기어 불명이면 잠금(S09)이 우선.
     */
    val quickStartWaiting: Boolean = false,
    /**
     * v8(r8 1절, 검수 세션 위임 판단): 보호자 버튼([다 내렸어요])을 누른 순간 배정 뒷자리에 착석 신호(OCCUPIED)가 남아 있었다.
     * true면 마무리 화면에 "뒷자리에 앉은 신호가 아직 있어요" 뜻의 한 줄을 더 보인다(기준 줄 "(보호자 확인)"·작별은 그대로). [exitByGuardian]일 때만 의미.
     */
    val guardianFinishSeatOccupied: Boolean = false,
    /**
     * v9(r9-1, 검수 세션 위임 지시): 모두 내림(ALL_LEFT) 화면에서, 마무리 순간부터 계속 앉아 있던 뒷자리(배정 안 한 자리 포함, 기준선)가 아직 앉아 있다.
     * true면 부제를 "다른 뒷자리에 앉은 신호가 있어요"로 보이고 [새로 출발 확인]은 띄우지 않는다(제목·기준 줄·음성은 그대로).
     * 기준선 자리가 비었다가 다시 앉으면 이 값 대신 [reseatedAfterAllLeft]가 true. 두 값은 동시에 true가 되지 않는다([reseatedAfterAllLeft] 우선).
     */
    val otherRearSeatedAfterAllLeft: Boolean = false,
    /**
     * v10(r10 G): 빠른 시작 대기 중 기어 첫 값(0.5초 안정화)을 아직 받지 못했거나 유효 P를 확인하기 전이다.
     * true면 화면은 전체 잠금 카드 대신 좌석 확인 화면을 그리고 "기어 확인 중…" 표시만 붙인다. 터치는 막는다(유효 P 전 조작 금지 규칙 유지).
     * 기어 값이 P가 아님(D/R/N)으로 확정되면 false → 잠금(S09). 유효 P면 false.
     */
    val gearCheckPending: Boolean = false,
)
// 계약 v2 메모: 화면은 [eta]를 그리지 않는다(PM 결정 14, 예정 시간은 음성으로만). 좌석 칸 색은 occ·belt·phase로 화면이 계산한다
// (확인 단계: 빈 자리 흰색 '비어있음' / 앉음 주황 '이용중' / 앉음+체결 파랑 '벨트' / 확인 불가 회색 '확인 불가'. 도착·하차는 착석만).
// 운전석도 같은 색 규칙(결정 16), 확인 대상 판정에서만 빠진다.
