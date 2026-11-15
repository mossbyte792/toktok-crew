package com.crewguide.probe

// 2026-11-13 디자인 담당 시안: "좌석 화면 하나로 확인까지 끝내기"(사용자 확정안, 00_인수인계 3절).
// 순수 규칙(Android 클래스 없음). 화면은 SeatBoardView가 그린다.
// **시안 단계**: 실제 흐름(MainActivity·StageScreen)에서 아직 부르지 않는다. 사용자 승인 뒤 PM 계약(AutoCheckUi)과 연결한다.
// 문구 원칙: 신호 기준으로만 말한다. 안전·완료·착용을 보증하는 표현은 쓰지 않는다(SeatBoardTest 금지어 검사).

/** 칸 색 상태. 색과 글자를 함께 바꾼다(색만으로 구분하지 않음). 초록은 쓰지 않는다 */
enum class SeatTone {
    /** 착석 신호 없음(빈 자리): 흰색 "비어있음" */
    EMPTY,
    /** 착석 신호(벨트 체결 신호 없음): 주황 "이용중" */
    SEATED,
    /** 착석 + 벨트 체결 신호: 파랑 "벨트" */
    BELTED,
    /** 착석 신호 확인 불가(미지원·단절·읽기 실패): 회색 "확인 불가" */
    UNKNOWN,
}

/** 화면 단계: 출발 전 확인(벨트까지 본다) / 도착·하차(앉은 신호만 본다) */
enum class SeatBoardMode { CHECK, EXIT }

/** 칸에 보이는 아이 표시: 별명 첫 글자(원 안). [ChildTag]는 PM 계약 v2(AutoCheckUi.kt) */
val ChildTag.initial: String get() = nick.trim().take(1).ifEmpty { "?" }

/**
 * 좌석 한 칸의 입력(시안 전용 표시 모델). 구현 때 PM 계약으로 옮길 후보 필드는 SeatBoardRules 주석 참고.
 * row 0 = 앞줄(2석), 1 = 뒷줄(3석). col은 줄 안에서 왼쪽부터.
 */
data class SeatBoardCell(
    val seat: String,
    /** 읽기용 이름(접근성). 칸 안에는 위치 글자를 그리지 않는다 */
    val label: String,
    val row: Int,
    val col: Int,
    val occ: SeatOcc,
    val belt: SeatBeltRef,
    /** 벨트 값이 앱 내부 모의(debug)인가. 앉은 칸에서만 '모의 신호' 꼬리표를 붙인다(ISSUE-11) */
    val beltMock: Boolean = false,
    /** 착석 값이 앱 내부 모의인가(뒷좌석 착석은 VC 실제 신호라 보통 false) */
    val occMock: Boolean = false,
    val child: ChildTag? = null,
    /** 눌러서 아이를 고를 수 있는 자리인가. PM 결정 23: 뒷줄만 true(앞줄 두 칸은 false) */
    val assignable: Boolean = true,
    /** 보호자 확인 대체 버튼을 이미 눌렀는가 */
    val guardianDone: Boolean = false,
    /** 운전석 여부(결정 3·11: 하차 강조·추가 착석 안내에서 뺀다) */
    val driver: Boolean = false,
    /** 흐름 판정이 '보호자 확인 필요'(SeatVerdict.NEEDS_GUARDIAN)인가. 앞줄은 아이 배정 없이 이 값으로 [보호자 확인]을 보인다(결정 23) */
    val needsGuardian: Boolean = false,
    /**
     * r8b(계약 v8 [SeatCheckRow.guardianForUnbuckled]): 미체결 신호(WAIT_BUCKLE) 자리인데 재안내가 끝나 보호자가 직접 보고 누를 수 있다.
     * 칸 색·글자는 신호 그대로(주황 '이용중'), 보조 칸에 [보호자 확인]만 더한다
     */
    val guardianUnbuckled: Boolean = false,
    /**
     * r10 I(계약 v10 [SeatCheckRow.carSeat]): 배정된 아이가 "차량 벨트를 쓰지 않는 자리(카시트)"로 등록됐다. 뒷줄 배정 칸에서만 true.
     * 칸 색·글자는 신호 그대로이고, 보조 칸이 비었을 때만 작은 꼬리표 [SeatBoardRules.CAR_SEAT_TAG]를 보인다([SeatBoardRules.carSeatTag])
     */
    val carSeat: Boolean = false,
    /**
     * r10 J(계약 v10 [SeatCheckRow.guest]): 이 칸에 손님이 배정됐고 등록 아이도 있는 이동이다 → 아이 줄에 "◇ 손님"(원 안 기호 + 글자).
     * 등록 아이가 0명인 기존 손님 이동(이름 "아이")은 false — 예전처럼 "아이"로 보인다(디자인 판단, [SeatBoardRules.guestShown])
     */
    val guest: Boolean = false,
)

/** 칸 색(0xRRGGBB) */
data class SeatToneColors(val fill: Int, val ink: Int, val stroke: Int)

object SeatBoardRules {
    // ---- 글자 ----
    const val T_EMPTY = "비어있음"
    const val T_SEATED = "이용중"
    const val T_BELTED = "벨트"
    const val T_UNKNOWN = "확인 불가"
    const val GUARDIAN_BUTTON = "보호자 확인"
    const val GUARDIAN_DONE = "✓ 보호자 확인함"
    const val MOCK_TAG = "모의 신호"
    /** 꼬리표는 모의인 값 이름과 함께 쓴다(2026-11-13 사용자 조건 1: 칸 전체가 아니라 벨트 값만 모의임을 보인다) */
    const val MOCK_BELT = "벨트: 모의 신호"
    const val MOCK_OCC = "착석: 모의 신호"
    const val ROW_FRONT = "앞줄"
    const val ROW_BACK = "뒷줄"
    const val PICK_HINT = "아이 선택"
    const val LEGEND_SEATED = "주황 이용중"
    const val LEGEND_BELTED = "파랑 벨트"
    const val LEGEND_UNKNOWN = "회색 확인 불가"
    const val CHILD_NO_SEAT = "자리 고르기 전"
    const val PICK_HOW = "자리를 누를 때마다 아이가 바뀌어요"

    const val TITLE_CHECK = "출발 전 확인"
    /** 결정 23: 아이 배정은 뒷줄만이라 '뒷자리'로 쓴다 */
    const val SUB_CHECK = "뒷자리를 눌러 아이를 골라요"
    const val TITLE_READY = "출발 준비됐어요"
    const val SUB_READY = "고른 자리에서 벨트 체결 신호를 받았어요"
    const val SUB_READY_GUARDIAN = "벨트 체결 신호와 보호자 확인을 받았어요"
    /** 보호자 확인만으로 끝났을 때(체결 신호 없음). 흐름 문구 SeatCheckRule.TEXT_DONE_GUARDIAN과 같은 뜻 */
    const val SUB_READY_GUARDIAN_ONLY = "보호자 확인을 받았어요"
    const val SUB_ARRIVED_NONE = "내리기 전에 뒷좌석을 한 번 더 봐 주세요"
    const val TITLE_ARRIVED = "도착했어요"
    const val SUB_ARRIVED = "주황 자리에 아직 앉은 신호가 있어요"
    /**
     * 하차 마무리 제목(신호로 끝난 마무리). 하차 마무리 음성과 같은 말. 기준 글자는 [BASIS_ALL_LEFT] 줄로 따로.
     * r10d A(사용자 지시 "운전석 빼고 다 내리면 모두 내린것 같아요로 하자"): 모든 이동에서 [TITLE_ALL_LEFT_COMMON].
     * 스위치 [TripGuide.FINISH_TEXT_ALL]을 끄면 r10c(결정 38 [TITLE_ALL_LEFT_KID], 손님이 함께 탄 이동만 공통)
     */
    const val TITLE_ALL_LEFT_COMMON = "모두 내린 것 같아요"
    /** 결정 38(사용자 확정, 2026-11-14) 제목('아이가' 있음). 스위치 끔(r10c)에서만 */
    const val TITLE_ALL_LEFT_KID = "아이가 모두 내린 것 같아요"
    val TITLE_ALL_LEFT: String = if (TripGuide.FINISH_TEXT_ALL) TITLE_ALL_LEFT_COMMON else TITLE_ALL_LEFT_KID
    /** 이동 끝·하차 한 줄 장면(도착 신호 없이 끝난 이동)의 제목. 도착했다고 말하지 않는다 */
    const val TITLE_EXIT = "내리기 전 확인"
    const val BASIS_ALL_LEFT = "(착석 신호 기준)"
    /** r6(계약 v6 exitByDoor): 이번 하차 판단에 문 열림 신호를 썼을 때의 기준 글자 */
    const val BASIS_ALL_LEFT_DOOR = "(문·착석 신호 기준)"
    /**
     * r6(계약 v6.1 quickStart, 임시 결정 43): 빠른 시작으로 온 확인 화면의 제목 아래 작은 한 줄. 제목 아래 기준 글자 자리(작은 회색 글자)를 같이 쓴다.
     * 개발이 같은 뜻을 [AutoCheckUi.lastSpoken]으로 말하면 말풍선에도 나온다
     */
    const val QUICK_START_LINE = "다시 출발 전에 벨트를 확인할게요"

    // ---- r7(계약 v7) ----
    /** 임시 결정 47: 보호자 버튼으로 끝낸 이동(exitByGuardian)의 마무리 제목. 신호 기준 문장("내린 것 같아요")을 쓰지 않는다 */
    const val TITLE_GUARDIAN_END = "이번 이동을 마칠게요"
    /** 결정 47: 보호자 버튼으로 끝낸 이동의 기준 줄([BASIS_ALL_LEFT_DOOR]보다 우선) */
    const val BASIS_GUARDIAN = "(보호자 확인)"
    /** 결정 47: 보호자 끝냄 마무리 장면 자막(말풍선 대체 경로, 음성 "뒷좌석을 한 번 더 확인해 주세요."와 같은 말) */
    const val CAPTION_GUARDIAN_END = "뒷좌석을 한 번 더 확인해 주세요."
    /** 결정 47: 하차 안내 화면(도착·중간 하차)에서만 [이번 이동 끝내기] 대신 쓰는 버튼 이름 */
    const val ALL_OFF_BUTTON = "다 내렸어요"
    /** 결정 49(계약 v7 quickStartWaiting): 빠른 시작 대기 화면의 한 줄 */
    const val QUICK_WAITING = "차량 신호를 확인하고 있어요"
    /** r8 1절(계약 v8 guardianFinishSeatOccupied): 보호자 버튼으로 끝냈는데 배정 뒷자리에 착석 신호가 남아 있던 이동의 마무리 화면 한 줄 */
    const val GUARDIAN_END_SEAT_OCCUPIED = "뒷자리에 앉은 신호가 아직 있어요"
    /** r8b: 미체결 자리 보호자 확인 안내(왼쪽 안내 칸, 넓을 때). 신호를 바꿔 말하지 않는다 */
    const val UNBUCKLED_GUARDIAN_NOTE = "벨트 체결 신호가 없어요. 카시트처럼 차량 벨트를 쓰지 않는 자리면 보호자가 직접 보고 눌러 주세요"
    /** r8b: 같은 안내의 짧은 판(안내 칸이 좁아 긴 판이 [SeatGuardianNote.MAX_LINES]줄을 넘을 때) */
    const val UNBUCKLED_GUARDIAN_NOTE_SHORT = "벨트 체결 신호가 없어요. 보호자가 보고 눌러 주세요"
    const val SUB_ALL_LEFT = "뒷좌석을 한 번 더 둘러봐 주세요"
    /** 2026-11-14 임시 결정 34(J2, 계약 v4 reseatedAfterAllLeft): 모두 내린 뒤 다시 앉은 신호 — 제목은 그대로, 부제만 바꾼다 */
    const val SUB_RESEATED = "다시 앉은 신호가 있어요"
    const val NEW_TRIP_BUTTON = "새로 출발 확인"
    /** r9-1(계약 v9 otherRearSeatedAfterAllLeft): 마무리 순간부터 계속 앉아 있던 다른 뒷자리 — 부제만 바꾸고 [새로 출발 확인]은 없음 */
    const val SUB_OTHER_REAR = "다른 뒷자리에 앉은 신호가 있어요"
    /**
     * r10 D(사용자 결정): 질문에 '예'로 들어간 도착 화면(계약 v5 arrivedByAnswer)의 되돌리기 버튼 글자. 동작은 그대로 [StageActions.onArrivalAnswer] false.
     * 질문 화면(askArrived)의 [아니요, 잠깐 멈췄어요]([ArrivalAskText.NO])는 그대로 둔다. 기호 "↩"는 읽기 이름에서 뺀다([UNDO_ARRIVAL_DESC])
     */
    const val UNDO_MARK = "↩"
    const val UNDO_ARRIVAL = "$UNDO_MARK 도착이 아니에요"
    const val UNDO_ARRIVAL_DESC = "도착이 아니에요. 누르면 이동을 이어요"

    // ---- r10 I(계약 v10 carSeat) ----
    /** 좌석 칸 보조 칸 꼬리표(기호 ◎ + 글자). 보호자 확인 버튼·확인함·모의 꼬리표가 먼저다 */
    const val CAR_SEAT_TAG = "◎ 카시트"
    /** 아이 목록 줄 뒤에 붙는 말("하늘 · 뒷줄 왼쪽 · 카시트") */
    const val CAR_SEAT_WORD = "카시트"
    /** 읽기 이름 */
    const val CAR_SEAT_DESC = "카시트, 차량 벨트를 쓰지 않는 자리"

    // ---- r10 J(계약 v10 guest) ----
    /** 손님 칸 아이 줄의 글자와 원 안 기호(별명 첫 글자 대신). 등록 아이와 섞이지 않게 원 윤곽은 점선 */
    const val GUEST_NAME = "손님"
    const val GUEST_MARK = "◇"

    // ---- r10 G(계약 v10 gearCheckPending) ----
    /** 좌석 확인 화면 위 칩 글자. 칩 앞에 점선 원 기호를 화면이 그린다(기호 + 글자, 색만으로 구분하지 않음) */
    const val GEAR_PENDING = "기어 확인 중…"
    /** 기어 확인 중 좌석 확인 화면의 부제(누를 수 없는 이유). 신호 기준 */
    const val GEAR_PENDING_SUB = "주차(P) 신호가 오면 누를 수 있어요"
    /** 칩·칸 읽기 이름에 붙이는 말 */
    const val GEAR_PENDING_DESC = "기어 확인 중, 주차(P) 신호를 받기 전에는 누를 수 없어요"
    const val FOOTER_BASIS = "차량 신호 기준 표시"
    const val FOOTER_MOCK = "‘모의 신호’는 앱이 만든 값(실제 차량 아님)"

    // ---- 크기(dp, 배율 1 기준). 자동차 그림 없이 네모 칸 5개(사용자 조건 3) + 칸 밖 왼쪽 줄 이름("앞줄"/"뒷줄").
    // 칸 = 위 여백 12 + 아이 줄 48 + 6 + 상태 글자 56 + 6 + 보조 칸 76 + 아래 여백 12 = 216 ----
    const val TILE_W_DP = 186
    const val TILE_PAD_DP = 12
    const val BADGE_ROW_DP = 48
    const val STATUS_ROW_DP = 56
    const val SLOT_GAP_DP = 6
    /** 보조 칸(보호자 확인 버튼 76dp 이상 / 모의 꼬리표 / 보호자 확인함) */
    const val EXTRA_ROW_DP = 76
    const val TILE_H_DP = TILE_PAD_DP * 2 + BADGE_ROW_DP + STATUS_ROW_DP + SLOT_GAP_DP * 2 + EXTRA_ROW_DP
    /** 칸 사이 간격 = DesignRules.MIN_TARGET_GAP(23) 이상 */
    const val SEAT_GAP_DP = 24
    const val ROW_GAP_DP = 24
    /** 칸 밖 왼쪽 줄 이름 칸 폭과 칸까지 간격 */
    const val LABEL_COL_DP = 56
    const val LABEL_GAP_DP = 12
    const val LABEL_SP = 24f
    const val TILE_RADIUS_DP = 20
    const val TILE_STROKE_DP = 3
    const val STATUS_SP = 36f
    const val NAME_SP = 24f
    const val SMALL_SP = 24f
    const val BUTTON_SP = 24f
    const val MIN_TOUCH_DP = 76
    /** 칸을 이보다 크게 키우지 않는다 */
    const val MAX_SCALE = 1.25f

    fun backRowWidthDp(): Int = 3 * TILE_W_DP + 2 * SEAT_GAP_DP
    fun frontRowWidthDp(): Int = 2 * TILE_W_DP + SEAT_GAP_DP
    fun mapWidthDp(): Int = LABEL_COL_DP + LABEL_GAP_DP + backRowWidthDp()
    /** 칸 높이를 뺀 세로 고정 부분(배율 적용) = 줄 사이 간격 */
    fun fixedHeightDp(): Int = ROW_GAP_DP
    fun mapHeightDp(): Int = fixedHeightDp() + 2 * TILE_H_DP

    /**
     * 배율 k: 폭·높이에 들어가는 가장 큰 값(MAX_SCALE 이하). 버튼이 있으면 보조 칸은 배율과 무관하게 76dp 이상이라
     * 높이식 = (고정 + 2×(여백·아이 줄·상태 줄)) × k + 2 × 76 으로 푼다.
     */
    fun scaleFor(availWDp: Float, availHDp: Float, anyButton: Boolean): Float {
        if (availWDp <= 0f || availHDp <= 0f) return 1f
        val kw = availWDp / mapWidthDp()
        val scaled = (fixedHeightDp() + 2 * (TILE_H_DP - EXTRA_ROW_DP)).toFloat()
        // 버튼이 있으면 k ≤ 1 구간에서 보조 칸이 76 고정이고, k > 1이면 함께 커진다(그때는 버튼 없는 식과 같다)
        val k1 = (availHDp - 2 * EXTRA_ROW_DP) / scaled
        val kh = if (anyButton && k1 <= 1f) k1 else availHDp / mapHeightDp()
        return minOf(kw, kh, MAX_SCALE).coerceAtLeast(0.1f)
    }

    /** 왼쪽 카드 안을 [안내] 위 · [좌석 칸] 아래로 쌓을지: 높이가 폭의 0.7배를 넘는 세로로 긴 칸(1920×720 카드는 약 0.44) */
    fun stackHeader(widthPx: Float, heightPx: Float): Boolean = widthPx > 0f && heightPx > widthPx * 0.7f

    /**
     * 모의 꼬리표 줄 나눔: 한 줄로 넣으면 글자가 [minPx]보다 작아질 때 "벨트:" / "모의 신호" 두 줄로 쓴다(2026-11-14 실제 흐름: 한 줄 약 12px로 읽기 어려움).
     */
    private const val NL = "\n"
    fun tagText(text: String, twoLines: Boolean): String = if (twoLines) text.replaceFirst(": ", ":" + NL) else text

    /** 보조 칸 높이(dp): 버튼이 있으면 76 이상 */
    fun extraRowDp(k: Float, anyButton: Boolean): Float = if (anyButton) maxOf(EXTRA_ROW_DP.toFloat(), EXTRA_ROW_DP * k) else EXTRA_ROW_DP * k

    fun tileHeightDp(k: Float, anyButton: Boolean): Float =
        (TILE_H_DP - EXTRA_ROW_DP) * k + extraRowDp(k, anyButton)

    /** 칸 왼쪽 x(dp, 배율 1). 앞줄은 가운데 맞춤 */
    fun tileXDp(row: Int, col: Int): Float {
        val start = LABEL_COL_DP + LABEL_GAP_DP + (if (row == 0) (backRowWidthDp() - frontRowWidthDp()) / 2f else 0f)
        return start + col * (TILE_W_DP + SEAT_GAP_DP)
    }

    // ---- 상태 ----
    /**
     * 신호 → 칸 상태.
     * CHECK: 착석 불명 = 확인 불가 / 빈 자리 / 앉음 + 체결 = 벨트 / 앉음 + 그 밖(미체결·벨트 불명·값 없음) = 이용중.
     * EXIT: 벨트는 보지 않는다. 앉음 = 이용중(주황).
     * 운전석도 같은 색 규칙이다(사용자 조건 2). 운전석은 아이 배정·하차 강조 윤곽에서만 빠진다(결정 3·11).
     */
    fun tone(c: SeatBoardCell, mode: SeatBoardMode): SeatTone = when (c.occ) {
        SeatOcc.UNKNOWN -> SeatTone.UNKNOWN
        SeatOcc.EMPTY -> SeatTone.EMPTY
        SeatOcc.OCCUPIED -> when {
            mode == SeatBoardMode.EXIT -> SeatTone.SEATED
            c.belt == SeatBeltRef.BUCKLED_SIGNAL -> SeatTone.BELTED
            else -> SeatTone.SEATED
        }
    }

    fun text(t: SeatTone): String = when (t) {
        SeatTone.EMPTY -> T_EMPTY
        SeatTone.SEATED -> T_SEATED
        SeatTone.BELTED -> T_BELTED
        SeatTone.UNKNOWN -> T_UNKNOWN
    }

    /**
     * 보호자 확인 버튼. 이미 눌렀거나 하차 단계면 보이지 않는다.
     * - 뒷줄: **아이를 배정한 칸**에서만(PM 결정 15). 착석 확인 불가(회색) 칸, 또는 앉음 + 벨트 불명·값 없음 칸(결정 2).
     *   배정하지 않은 회색 칸은 '확인 불가' 표시만 하고 완료 판정에 넣지 않는다.
     * - 앞줄(배정 불가 칸, 결정 23): 아이 없이 흐름 판정이 '보호자 확인 필요'([SeatBoardCell.needsGuardian])일 때.
     *   실차에서 앞줄 벨트를 확인할 수 없을 때 완료로 갈 길을 열어 둔다.
     */
    fun guardianButton(c: SeatBoardCell, mode: SeatBoardMode): Boolean {
        if (mode != SeatBoardMode.CHECK || c.guardianDone) return false
        // r8b(계약 v8): 미체결 신호 자리 + 흐름이 보호자 확인을 열어 둠. 체결 신호가 오면 열지 않는다(신호 우선)
        if (c.guardianUnbuckled && c.occ == SeatOcc.OCCUPIED && c.belt != SeatBeltRef.BUCKLED_SIGNAL) return true
        if (!c.assignable) return c.needsGuardian
        if (c.child == null) return false
        if (c.occ == SeatOcc.UNKNOWN) return true
        return c.occ == SeatOcc.OCCUPIED && (c.belt == SeatBeltRef.UNKNOWN || c.belt == SeatBeltRef.NO_SIGNAL)
    }

    fun guardianDoneTag(c: SeatBoardCell, mode: SeatBoardMode): Boolean = mode == SeatBoardMode.CHECK && c.guardianDone

    /**
     * '모의 신호' 꼬리표 글자(ISSUE-11, 사용자 조건 1): **모의인 값 이름과 함께**, 그 값이 칸에 보일 때만. 없으면 null.
     * - 파랑 '벨트' 칸 + 벨트 값 모의 → "벨트: 모의 신호"(확인 단계만)
     * - 착석 값 모의(debug 착석 주입) → "착석: 모의 신호"(확인 불가 칸 제외)
     * 착석만 있는 주황 '이용중' 칸(착석은 VC 실제 신호)에는 붙지 않는다.
     */
    fun mockTagText(c: SeatBoardCell, mode: SeatBoardMode): String? {
        val t = tone(c, mode)
        return when {
            mode == SeatBoardMode.CHECK && t == SeatTone.BELTED && c.beltMock -> MOCK_BELT
            t != SeatTone.UNKNOWN && c.occMock -> MOCK_OCC
            else -> null
        }
    }

    fun mockTag(c: SeatBoardCell, mode: SeatBoardMode): Boolean = mockTagText(c, mode) != null

    /**
     * r10 I: 칸 보조 칸에 "◎ 카시트" 꼬리표를 보일지. 확인 단계 + 카시트 칸 + 보조 칸이 비었을 때만
     * (보호자 확인 버튼 > 보호자 확인함 > 모의 꼬리표가 먼저, 한 칸에 하나라 넘침·겹침 없음). 아이 목록 줄에는 늘 붙는다([childLine])
     */
    fun carSeatTag(c: SeatBoardCell, mode: SeatBoardMode): Boolean =
        mode == SeatBoardMode.CHECK && c.carSeat && c.child != null && !guardianButton(c, mode) && !guardianDoneTag(c, mode) && mockTagText(c, mode) == null

    /** '아이 선택' 안내(+ 원): 앉은 신호가 있고 아이를 아직 고르지 않은 자리(뒷줄만, 결정 23) */
    fun pickHint(c: SeatBoardCell, mode: SeatBoardMode): Boolean {
        val t = tone(c, mode)
        return mode == SeatBoardMode.CHECK && c.assignable && c.child == null && (t == SeatTone.SEATED || t == SeatTone.BELTED)
    }

    /**
     * 칸을 누를 수 있는가(아이 고르기). 하차 단계·고를 수 없는 자리·빈 자리는 누르지 않는다.
     * [보호자 확인] 버튼이 보이는 동안은 칸 자체를 누르지 않는다(한 칸에 누를 곳 하나, 버튼과 칸 누름이 겹치지 않게). 버튼을 누른 뒤에는 고를 수 있다.
     */
    fun tappable(c: SeatBoardCell, mode: SeatBoardMode): Boolean =
        mode == SeatBoardMode.CHECK && c.assignable && c.occ != SeatOcc.EMPTY && !guardianButton(c, mode)

    /** r10 G: 기어 확인 중([ScreenModel.gearPending] = [frozen])이면 칸도 [보호자 확인]도 누르지 않는다(유효 P 전 조작 금지). 모양은 그대로, 버튼은 비활성 모양 */
    fun tappableNow(c: SeatBoardCell, mode: SeatBoardMode, frozen: Boolean): Boolean = !frozen && tappable(c, mode)
    fun guardianEnabled(c: SeatBoardCell, mode: SeatBoardMode, frozen: Boolean): Boolean = !frozen && guardianButton(c, mode)

    /** 하차 단계 굵은 윤곽(아직 앉은 신호). 운전석은 색은 같게 보이되 윤곽 강조는 하지 않는다(결정 11) */
    fun emphasize(c: SeatBoardCell, mode: SeatBoardMode): Boolean = mode == SeatBoardMode.EXIT && !c.driver && tone(c, mode) == SeatTone.SEATED

    /**
     * 자리를 누를 때 다음 아이: 지금 아이 다음 순서로, 다른 자리에 이미 배정된 아이는 건너뛴다. 마지막 다음은 '없음'(null).
     * 아이 1명이면 [그 아이 ↔ 없음]을 오간다.
     */
    fun nextChild(current: Int?, children: List<ChildTag>, takenElsewhere: Set<Int>): Int? {
        val free = children.filter { it.id !in takenElsewhere || it.id == current }
        if (free.isEmpty()) return null
        if (current == null) return free.first().id
        val i = free.indexOfFirst { it.id == current }
        return if (i < 0 || i == free.lastIndex) null else free[i + 1].id
    }

    /** 아이가 1명이고 고를 수 있는 앉은 자리가 하나뿐이면 그 자리에 자동 배정한다(아니면 그대로) */
    fun autoAssign(cells: List<SeatBoardCell>, children: List<ChildTag>): List<SeatBoardCell> {
        if (children.size != 1 || cells.any { it.child != null }) return cells
        val seated = cells.filter { it.assignable && it.occ == SeatOcc.OCCUPIED }
        if (seated.size != 1) return cells
        return cells.map { if (it.seat == seated[0].seat) it.copy(child = children[0]) else it }
    }

    /** 접근성 이름: '뒷줄 왼쪽, 벨트 체결 신호, 하늘, 모의 신호, 눌러서 아이 고르기' */
    fun a11y(c: SeatBoardCell, mode: SeatBoardMode, frozen: Boolean = false): String {
        val sig = when (tone(c, mode)) {
            SeatTone.EMPTY -> "빈 자리"
            SeatTone.SEATED -> if (mode == SeatBoardMode.EXIT) "아직 앉은 신호" else "앉은 신호, 벨트 체결 신호 없음"
            SeatTone.BELTED -> "앉은 신호와 벨트 체결 신호"
            SeatTone.UNKNOWN -> "착석 신호 확인 불가"
        }
        val parts = arrayListOf(c.label, sig)
        c.child?.let { parts += it.nick }
        if (c.carSeat && c.child != null) parts += CAR_SEAT_DESC   // r10 I
        mockTagText(c, mode)?.let { parts += it }
        if (guardianDoneTag(c, mode)) parts += "보호자 확인함"
        if (tappableNow(c, mode, frozen)) parts += "눌러서 아이 고르기"
        if (frozen) parts += GEAR_PENDING_DESC   // r10 G
        return parts.joinToString(", ")
    }

    // ---- 색 ----
    // 낮: 빈 흰 FFFFFF / 주황 F97316(글자 111111) / 파랑 2563EB(흰 글자) / 회색 CBD5E1(글자 111111). 윤곽 334155 3dp.
    // 밤: 빈 0F172A(밝은 글자) / 주황 FB923C / 파랑 60A5FA(글자 111111) / 회색 434F61(밝은 글자, 밤 파랑 버튼과 3.27). 윤곽 CBD5E1.
    const val DAY_GRAY = 0xCBD5E1
    const val NIGHT_GRAY = 0x434F61
    const val DAY_TILE_STROKE = 0x334155
    const val NIGHT_TILE_STROKE = 0xCBD5E1
    const val DAY_EMPHASIS = 0x9A3412     // 하차 '아직 앉음' 굵은 윤곽(진한 주황)
    const val NIGHT_EMPHASIS = 0xFDBA74
    const val NIGHT_EMPTY_FILL = 0x0F172A
    const val NIGHT_INK = 0xF1F5F9
    /** 아이 원(흰 바탕 + 남색 글자·윤곽, 칸 색과 무관하게 같은 짝) */
    const val CHILD_FILL = 0xFFFFFF
    const val CHILD_INK = Palette.CREW_BLUE
    /** 모의 꼬리표(흰 바탕 + 진한 주황 글자·점선) */
    const val MOCK_FILL = 0xFFFFFF
    const val MOCK_INK = 0x9A3412

    fun colors(t: SeatTone, night: Boolean): SeatToneColors = if (night) when (t) {
        SeatTone.EMPTY -> SeatToneColors(NIGHT_EMPTY_FILL, NIGHT_INK, NIGHT_TILE_STROKE)
        SeatTone.SEATED -> SeatToneColors(Palette.NIGHT_CREW_ORANGE_FILL, Palette.NIGHT_CREW_ORANGE_TEXT, NIGHT_TILE_STROKE)
        SeatTone.BELTED -> SeatToneColors(Palette.NIGHT_CREW_BLUE, Palette.NIGHT_CREW_BLUE_TEXT, NIGHT_TILE_STROKE)
        SeatTone.UNKNOWN -> SeatToneColors(NIGHT_GRAY, NIGHT_INK, NIGHT_TILE_STROKE)
    } else when (t) {
        SeatTone.EMPTY -> SeatToneColors(0xFFFFFF, 0x111111, DAY_TILE_STROKE)
        SeatTone.SEATED -> SeatToneColors(Palette.CREW_ORANGE_FILL, Palette.CREW_ORANGE_TEXT, DAY_TILE_STROKE)
        SeatTone.BELTED -> SeatToneColors(Palette.MAIN_BLUE, 0xFFFFFF, DAY_TILE_STROKE)
        SeatTone.UNKNOWN -> SeatToneColors(DAY_GRAY, 0x111111, DAY_TILE_STROKE)
    }

    fun emphasisStroke(night: Boolean): Int = if (night) NIGHT_EMPHASIS else DAY_EMPHASIS

    /** 한 줄 설명(색 견본 + 글자). 빈 자리(흰색)는 칸 글자 "비어있음"으로 충분해 넣지 않는다 */
    fun legend(night: Boolean): List<Pair<SeatToneColors, String>> = listOf(
        colors(SeatTone.SEATED, night) to LEGEND_SEATED,
        colors(SeatTone.BELTED, night) to LEGEND_BELTED,
        colors(SeatTone.UNKNOWN, night) to LEGEND_UNKNOWN,
    )

    /** 대비 검사 대상(SeatBoardTest): 칸 글자/칸, 칸 윤곽/카드 바탕(자동차 그림 없음), 아이 원, 모의 꼬리표, 보호자 버튼/회색 칸 */
    fun pairs(night: Boolean): List<DesignRules.ColorPair> {
        val t = DesignRules.Kind.TEXT
        val n = DesignRules.Kind.NON_TEXT
        val tag = if (night) "밤" else "낮"
        val r = DuoTokens.roles(night)
        val card = r.surface
        return SeatTone.values().flatMap { tone ->
            val c = colors(tone, night)
            listOf(
                DesignRules.ColorPair("$tag ${text(tone)} 글자 / 칸", c.ink, c.fill, t),
                DesignRules.ColorPair("$tag ${text(tone)} 윤곽 / 카드", c.stroke, card, n),
            )
        } + listOf(
            DesignRules.ColorPair("$tag 아이 원 글자", CHILD_INK, CHILD_FILL, t),
            DesignRules.ColorPair("$tag 모의 꼬리표 글자", MOCK_INK, MOCK_FILL, t),
            DesignRules.ColorPair("$tag 보호자 버튼 글자", r.primaryText, r.primaryFill, t),
            DesignRules.ColorPair("$tag 보호자 버튼 / 회색 칸", r.primaryFill, colors(SeatTone.UNKNOWN, night).fill, n),
            DesignRules.ColorPair("$tag 하차 강조 윤곽 / 카드", emphasisStroke(night), card, n),
            DesignRules.ColorPair("$tag 줄 이름 글자 / 카드", r.textMuted, card, t),
        )
    }

    /** 아이 목록 한 줄: "하늘 · 뒷줄 왼쪽" / 자리를 아직 안 골랐으면 "하늘 · 자리 고르기 전" */
    fun childLine(nick: String, seatLabel: String?, carSeat: Boolean = false): String =
        "$nick · ${seatLabel ?: CHILD_NO_SEAT}" + if (carSeat) " · $CAR_SEAT_WORD" else ""   // r10 I: 카시트 아이는 끝에 "· 카시트"

    /** 금지어 검사용 전체 문구 */
    fun all(): List<String> = listOf(
        T_EMPTY, T_SEATED, T_BELTED, T_UNKNOWN, GUARDIAN_BUTTON, GUARDIAN_DONE, MOCK_TAG, MOCK_BELT, MOCK_OCC, ROW_FRONT, ROW_BACK, PICK_HINT,
        LEGEND_SEATED, LEGEND_BELTED, LEGEND_UNKNOWN, CHILD_NO_SEAT, PICK_HOW, TITLE_CHECK, SUB_CHECK, TITLE_READY, SUB_READY,
        TITLE_ARRIVED, SUB_ARRIVED, TITLE_ALL_LEFT, BASIS_ALL_LEFT, SUB_ALL_LEFT, FOOTER_BASIS, FOOTER_MOCK,
        SUB_READY_GUARDIAN, SUB_READY_GUARDIAN_ONLY, SUB_ARRIVED_NONE, TITLE_EXIT, BASIS_ALL_LEFT_DOOR, QUICK_START_LINE,
        TITLE_GUARDIAN_END, BASIS_GUARDIAN, CAPTION_GUARDIAN_END, ALL_OFF_BUTTON, QUICK_WAITING, GUARDIAN_END_SEAT_OCCUPIED,
        UNBUCKLED_GUARDIAN_NOTE, UNBUCKLED_GUARDIAN_NOTE_SHORT, SUB_RESEATED, NEW_TRIP_BUTTON, SUB_OTHER_REAR,
        UNDO_ARRIVAL, UNDO_ARRIVAL_DESC, GEAR_PENDING, GEAR_PENDING_SUB, GEAR_PENDING_DESC,
        CAR_SEAT_TAG, CAR_SEAT_WORD, CAR_SEAT_DESC, GUEST_NAME, TITLE_ALL_LEFT_COMMON, TITLE_ALL_LEFT_KID,
    ) + AlightScreenText.all()

    /**
     * 모두 내림(ALL_LEFT) 부제: 다시 앉음(계약 v4 reseatedAfterAllLeft, 우선) → [SUB_RESEATED],
     * 계속 앉아 있던 다른 뒷자리(계약 v9 otherRearSeatedAfterAllLeft) → [SUB_OTHER_REAR], 그 밖 → [SUB_ALL_LEFT]. 제목·기준 줄은 따로(그대로)
     */
    fun allLeftSub(ac: AutoCheckUi): String = when {
        ac.reseatedAfterAllLeft -> SUB_RESEATED
        ac.otherRearSeatedAfterAllLeft -> SUB_OTHER_REAR
        else -> SUB_ALL_LEFT
    }

    /**
     * r8 1절: 제목·부제 아래 덧붙이는 알림 한 줄(없으면 null). 보호자 버튼으로 끝낸 마무리 화면 + 그 순간 배정 뒷자리 착석 신호가 남아 있었음
     * → [GUARDIAN_END_SEAT_OCCUPIED]. 제목("이번 이동을 마칠게요")·기준 줄("(보호자 확인)")은 그대로
     */
    fun noteLine(scene: Scene, ac: AutoCheckUi): String? = when {
        scene == Scene.ALL_LEFT && ac.exitByGuardian && ac.guardianFinishSeatOccupied -> GUARDIAN_END_SEAT_OCCUPIED
        // r8b: 확인 중 + 미체결 자리에 [보호자 확인]이 보이는 동안(이미 누른 자리는 빼고)
        scene == Scene.AUTO_CHECK && cellsOf(ac)?.any { it.guardianUnbuckled && guardianButton(it, SeatBoardMode.CHECK) } == true -> UNBUCKLED_GUARDIAN_NOTE
        else -> null
    }

    /** r8b: [noteLine]의 짧은 판(좁은 안내 칸용). 짧은 판이 없는 알림은 null(긴 판 그대로) */
    fun noteShortOf(note: String?): String? = if (note == UNBUCKLED_GUARDIAN_NOTE) UNBUCKLED_GUARDIAN_NOTE_SHORT else null

    /** 보호자 확인 버튼 읽기 이름: 미체결 자리는 "벨트 체결 신호가 없어…", 확인 불가 자리는 기존 뜻 */
    fun guardianButtonDesc(c: SeatBoardCell): String =
        if (c.guardianUnbuckled) "${c.label} $GUARDIAN_BUTTON. 벨트 체결 신호가 없어 보호자가 직접 보고 누르는 버튼이에요"
        else "${c.label} $GUARDIAN_BUTTON"

    // ---- r7(계약 v7) 화면 규칙 ----
    /**
     * 하차 마무리 제목: 보호자 버튼으로 끝냈으면 [TITLE_GUARDIAN_END](결정 47), 아니면 [TITLE_ALL_LEFT](r10d A 공통).
     * 스위치를 끈 r10c에서는 손님이 함께 탄 이동만 [TITLE_ALL_LEFT_COMMON]
     */
    fun allLeftTitle(ac: AutoCheckUi): String = when {
        ac.exitByGuardian -> TITLE_GUARDIAN_END
        guestFinish(ac) -> TITLE_ALL_LEFT_COMMON
        else -> TITLE_ALL_LEFT
    }

    /**
     * (r10c, 스위치 [TripGuide.FINISH_TEXT_ALL]를 껐을 때만) 손님이 함께 탄 이동인가 = 등록 아이 1명 이상([hasRegistered]) + 뒷줄 행에 손님(계약 guest).
     * 마무리 화면 행의 손님 표시는 개발이 마무리 대상 손님 자리에 남긴다(TripGuide.markFinishGuests). 등록 0명(이름 '아이')은 false.
     * 스위치 켬(공통 문구)이면 늘 false(나눌 필요 없음)
     */
    fun guestFinish(ac: AutoCheckUi?, all: Boolean = TripGuide.FINISH_TEXT_ALL): Boolean =
        !all && ac != null && hasRegistered(ac.children) &&
            ac.rows.any { it.guest && (SeatCheckRule.isAssignable(it.seat) || it.seat in REAR_SHORT_KEYS) }

    /** 하차 마무리 기준 줄: 보호자 끝냄 "(보호자 확인)" > 문 "(문·착석 신호 기준)" > 착석 "(착석 신호 기준)" */
    fun allLeftBasisOf(ac: AutoCheckUi): String = if (ac.exitByGuardian) BASIS_GUARDIAN else allLeftBasis(ac.exitByDoor)

    /** 하차 마무리 장면 자막: 보호자 끝냄은 신호 기준 문장 없이 [CAPTION_GUARDIAN_END], 아니면 [allLeftCaption] */
    fun allLeftCaptionOf(ac: AutoCheckUi?): String = when {
        ac?.exitByGuardian == true -> CAPTION_GUARDIAN_END
        else -> allLeftCaption(ac?.exitByDoor == true, guestFinish(ac))
    }

    /** 계약 v5.1 '잠깐 멈춤' 화면인가(질문 중·도착 예·중간 하차가 아닌 정차) */
    fun pausedScreen(scene: Scene, ac: AutoCheckUi): Boolean =
        scene == Scene.ARRIVED && answerKindOf(scene, ac) == null && !ac.arrivedByAnswer && !alighting(scene, ac) && ac.pausedByAnswer

    /** r7: 하차 안내 화면(도착·중간 하차)인가 — 도착 장면에서 질문 중도 잠깐 멈춤도 아닌 때 */
    fun exitGuidance(scene: Scene, ac: AutoCheckUi): Boolean =
        scene == Scene.ARRIVED && answerKindOf(scene, ac) == null && !pausedScreen(scene, ac)

    /**
     * r7(결정 47): 큰 버튼 이름. 하차 안내 화면(도착·중간 하차)이면 [ALL_OFF_BUTTON]("다 내렸어요"), 다른 화면은 [base] 그대로
     * (StageControls.finishLabelOf는 계약 파일이라 화면 쪽에서 바꾼다). 버튼이 없는 장면([base] == null)은 그대로 null
     */
    fun finishLabelFor(scene: Scene, ac: AutoCheckUi?, base: String?): String? =
        if (base != null && ac != null && exitGuidance(scene, ac)) ALL_OFF_BUTTON else base

    /**
     * r7(계약 v7 1절): 하차 안내 화면에 주간 도장판 + 오늘 별을 보일지. 스티커를 주는 하차 안내 세 경우(실제 도착 뒤 P = phase ARRIVED,
     * "예, 도착했어요" = arrivedByAnswer, "예, 내려요" = alightingByAnswer)만. 도착 신호 없는 정차 화면(질문 전·잠깐 멈춤)에는 없다(아직 주지 않음)
     */
    fun stickerAtExit(scene: Scene, ac: AutoCheckUi): Boolean =
        exitGuidance(scene, ac) && ac.stickerEarned && (ac.phase == AutoPhase.ARRIVED || ac.arrivedByAnswer || ac.alightingByAnswer)

    /**
     * r7: 스티커 연출 '방금 받음' 값([SeatBoardStage.setStickerJustGranted]에 넘김). 모두 내림(ALL_LEFT) 화면은 새 연출을 넣지 않으므로 늘 false
     * (도장판은 결과 모습으로 남는다). 그 밖은 계약 값 그대로. 계약이 없으면 null(호환 경로)
     */
    fun stickerJustFor(scene: Scene, ac: AutoCheckUi?): Boolean? = when {
        ac == null -> null
        scene == Scene.ALL_LEFT -> false
        else -> ac.stickerJustGranted
    }

    /** r7(결정 49): 빠른 시작 대기 화면인가(확인 장면 + 계약 quickStartWaiting). 잠금은 장면 선택(sceneOf)에서 먼저 걸러진다 */
    fun quickWaiting(scene: Scene, ac: AutoCheckUi): Boolean = scene == Scene.AUTO_CHECK && ac.quickStartWaiting

    /** r6(계약 v6 exitByDoor): 하차 마무리 기준 글자. 문 열림 신호를 썼으면 "(문·착석 신호 기준)", 아니면(문 신호 확인 불가 포함) "(착석 신호 기준)" */
    fun allLeftBasis(exitByDoor: Boolean): String = if (exitByDoor) BASIS_ALL_LEFT_DOOR else BASIS_ALL_LEFT

    /**
     * r6: 하차 마무리 장면 자막(제목 + 기준 한 문자열). 문 기준이 아니면 개발 쪽 [TripGuide.TITLE_ALL_LEFT] 그대로(말풍선 줄 규칙이 이 값을 본다),
     * 문 기준이면 같은 제목 + "(문·착석 신호 기준)". TripGuide는 개발 파일이라 화면 쪽에서 고른다
     */
    fun allLeftCaption(exitByDoor: Boolean, guest: Boolean = false): String = when {
        guest -> TITLE_ALL_LEFT_COMMON + allLeftBasis(exitByDoor)   // r10c(스위치 끔): 손님이 함께 탄 이동(기준 줄은 그대로)
        exitByDoor -> TITLE_ALL_LEFT + BASIS_ALL_LEFT_DOOR
        else -> TripGuide.TITLE_ALL_LEFT
    }

    /** 제목 아래 작은 줄: 모두 내림 = 기준 글자, 빠른 시작으로 온 확인 중 = [QUICK_START_LINE], 그 밖 = 없음 */
    fun basisLine(scene: Scene, ac: AutoCheckUi): String? = when {
        scene == Scene.ALL_LEFT -> allLeftBasisOf(ac)   // r7: 보호자 끝냄 > 문 > 착석
        scene == Scene.AUTO_CHECK && ac.quickStart -> QUICK_START_LINE
        else -> null
    }

    /**
     * r6: 오른쪽 아래 두 대답 버튼이 어느 질문의 대답인가(없으면 null). 도착·정차 장면(ARRIVED)에서만(잠금 장면이면 없다).
     * "내릴까요?"(계약 v6 askAlight)를 "목적지에 도착했나요?"(v5 askArrived)보다 먼저 본다(둘은 동시에 오지 않지만, 아니요 → 바로 "내릴까요?"로 넘어가는 순간에도 새 질문이 보이게)
     */
    fun answerKindOf(scene: Scene, ac: AutoCheckUi): AnswerKind? = when {
        scene != Scene.ARRIVED -> null
        ac.askAlight -> AnswerKind.ALIGHT
        ac.askArrived -> AnswerKind.ARRIVAL
        else -> null
    }

    /** r6(계약 v6 alightingByAnswer): "내릴까요?"에 예 → 중간 하차 화면인가(질문 중이 아니고 도착·정차 장면) */
    fun alighting(scene: Scene, ac: AutoCheckUi): Boolean = scene == Scene.ARRIVED && ac.alightingByAnswer && answerKindOf(scene, ac) == null

    // ---- r10 J: 손님 표시 ----
    /** 등록 아이가 한 명이라도 있는 이동인가(아이 목록에 손님 말고 다른 id가 있음) */
    fun hasRegistered(children: List<ChildTag>): Boolean = children.any { it.id != SeatCheckRule.GUEST_CHILD.id }

    /**
     * 이 행을 "손님"으로 보일지: 계약 guest + 뒷줄(배정 칸) + 등록 아이가 있는 이동.
     * 디자인 판단(PM 임시 결정 보고): 등록 아이 0명의 기존 손님 이동은 그 아이가 가족의 아이라 "손님"이라 부르지 않고 예전 이름("아이") 그대로 둔다
     */
    fun guestShown(r: SeatCheckRow, children: List<ChildTag>): Boolean =
        r.guest && r.childId != null &&   // 사용자 결정 11-15: 마무리 화면의 빈 손님 자리(childId 없음, guest만 남김)에는 "손님"을 그리지 않는다
            (SeatCheckRule.isAssignable(r.seat) || r.seat in REAR_SHORT_KEYS) && hasRegistered(children)

    /** 칸 아이 줄의 원 안 글자: 손님은 [GUEST_MARK], 그 밖은 별명 첫 글자 */
    fun badgeMark(c: SeatBoardCell): String = if (c.guest) GUEST_MARK else c.child?.initial ?: "?"

    /** 아이 목록 한 줄(왼쪽 안내 칸). [mark] = 원 안 글자 */
    data class KidLine(val mark: String, val text: String, val guest: Boolean)

    /**
     * 아이 목록 줄들: 등록 아이는 목록 순서대로 한 줄씩("하늘 · 뒷줄 왼쪽 · 카시트"), 손님은 손님이 앉은 칸마다 한 줄("손님 · 뒷줄 오른쪽").
     * 손님이 여러 칸이어도 한 칸에 한 줄이라 겹치지 않는다(같은 id -1이 여러 칸에 있어도 칸 이름으로 나눈다). 배정 안 된 손님 줄은 없다.
     * 등록 아이 0명(기존 손님 이동)은 예전 그대로 목록의 모든 항목을 한 줄씩.
     */
    fun kidLines(children: List<ChildTag>, cells: List<SeatBoardCell>): List<KidLine> {
        if (!hasRegistered(children)) return children.map { c ->
            KidLine(c.initial, childLine(c.nick, cells.firstOrNull { it.child?.id == c.id }?.label, c.carSeat), false)
        }
        val kids = children.filter { it.id != SeatCheckRule.GUEST_CHILD.id }.map { c ->
            val seat = cells.firstOrNull { !it.guest && it.child?.id == c.id }
            KidLine(c.initial, childLine(c.nick, seat?.label, c.carSeat), false)
        }
        val guests = cells.filter { it.guest }.sortedWith(compareBy({ it.row }, { it.col }))
            .map { KidLine(GUEST_MARK, childLine(GUEST_NAME, it.label), true) }
        return kids + guests
    }

    /**
     * 스티커 줄을 둘지: 손님만 배정된 이동(배정 행이 모두 손님)이면 두지 않는다(손님 자리에는 스티커·도장판 연출 없음).
     * 등록 아이 0명의 기존 손님 이동도 같다(StickerFly.guestOnly와 같은 뜻). 배정 정보가 없는 행(하차 뒤 비움)이면 계약 값대로
     */
    fun stickerAllowed(ac: AutoCheckUi): Boolean {
        val assigned = ac.rows.filter { it.childId != null }
        return assigned.isEmpty() || !assigned.all { it.guest }
    }

    // ---- 계약(AutoCheckUi) → 좌석 화면 표시 모델 ----
    /** 좌석 키 → (줄, 칸). SDK 키(ROW_1_LEFT 등)와 미리보기 짧은 키(R1L 등)를 함께 받는다. 여기 없는 자리(3열 등)는 좌석 화면을 쓰지 않는다 */
    private val SLOTS: Map<String, Pair<Int, Int>> = mapOf(
        "ROW_1_LEFT" to (0 to 0), "ROW_1_RIGHT" to (0 to 1),
        "ROW_2_LEFT" to (1 to 0), "ROW_2_CENTER" to (1 to 1), "ROW_2_RIGHT" to (1 to 2),
        "R1L" to (0 to 0), "R1R" to (0 to 1), "R2L" to (1 to 0), "R2C" to (1 to 1), "R2R" to (1 to 2),
    )
    private val DRIVER_KEYS = setOf("ROW_1_LEFT", "R1L")
    /** 미리보기 짧은 키의 뒷줄(SDK 키는 SeatCheckRule.isAssignable이 본다) */
    private val REAR_SHORT_KEYS = setOf("R2L", "R2C", "R2R")

    fun modeOf(phase: AutoPhase): SeatBoardMode = when (phase) {
        AutoPhase.ARRIVED, AutoPhase.EXITING, AutoPhase.ALL_LEFT -> SeatBoardMode.EXIT
        AutoPhase.CHECKING, AutoPhase.CONFIRMED, AutoPhase.DEPARTING, AutoPhase.DRIVING -> SeatBoardMode.CHECK
    }

    /**
     * 계약 행 → 칸. 알 수 없는 좌석 키가 있거나 같은 자리가 두 번 나오면 null(호출 측은 기존 목록 패널을 쓴다).
     * 아이 = [AutoCheckUi.children]에서 childId로 찾는다. 보호자 확인함 = 판정 GUARDIAN_CONFIRMED.
     * PM 결정 23: 아이를 고를 수 있는 칸은 뒷줄만([SeatCheckRule.isAssignable]). 앞줄 칸에는 이름·첫 글자·"+ 아이 선택"이 없고 눌러도 고르지 않는다.
     */
    fun cellsOf(ac: AutoCheckUi): List<SeatBoardCell>? {
        if (ac.rows.isEmpty()) return null
        val used = HashSet<Pair<Int, Int>>()
        return ac.rows.map { r ->
            val pos = SLOTS[r.seat] ?: return null
            if (!used.add(pos)) return null
            val driver = r.seat in DRIVER_KEYS
            val assignable = SeatCheckRule.isAssignable(r.seat) || r.seat in REAR_SHORT_KEYS
            val guest = guestShown(r, ac.children)   // r10 J
            SeatBoardCell(
                seat = r.seat, label = r.label, row = pos.first, col = pos.second, occ = r.occ, belt = r.belt,
                beltMock = r.beltMock, occMock = r.occMock,
                child = when {
                    !assignable -> null
                    guest -> ChildTag(r.childId ?: SeatCheckRule.GUEST_CHILD.id, GUEST_NAME)   // r10 J: 손님 칸은 "손님"
                    else -> ac.children.firstOrNull { it.id == r.childId }
                },
                assignable = assignable, guardianDone = r.verdict == SeatVerdict.GUARDIAN_CONFIRMED, driver = driver,
                needsGuardian = r.verdict == SeatVerdict.NEEDS_GUARDIAN,
                guardianUnbuckled = r.guardianForUnbuckled,
                carSeat = assignable && r.carSeat && !guest,   // r10 I(손님은 등록 정보가 없어 카시트 표시 없음)
                guest = guest,
            )
        }
    }

    /**
     * 출발 준비 부제: 흐름의 완료 종류(SeatCheckRule.completeKind)와 같은 기준.
     * 체결 신호만 = SUB_READY / 체결 신호 + 보호자 확인 = SUB_READY_GUARDIAN / 보호자 확인만 = SUB_READY_GUARDIAN_ONLY
     */
    fun readySub(verdicts: Collection<SeatVerdict>): String = when (SeatCheckRule.completeKind(verdicts)) {
        SeatCheckRule.CompleteKind.SIGNAL -> SUB_READY
        SeatCheckRule.CompleteKind.MIXED -> SUB_READY_GUARDIAN
        SeatCheckRule.CompleteKind.GUARDIAN -> SUB_READY_GUARDIAN_ONLY
    }

    /** 왼쪽 카드 아래 스티커 줄(모두 내림 장면만) */
    data class StickerLine(val showBoard: Boolean, val text: String)

    /** 좌석 화면 한 장의 표시 값(순수). [SeatBoardStage]가 그린다 */
    data class ScreenModel(
        val phase: AutoPhase,
        val mode: SeatBoardMode,
        val cells: List<SeatBoardCell>,
        val children: List<ChildTag>,
        val title: String,
        /** 제목 아래 작은 줄(모두 내림의 기준 글자 "(착석 신호 기준)"/"(문·착석 신호 기준)", 빠른 시작 확인 중의 한 줄), 없으면 null */
        val basis: String?,
        val sub: String,
        /** "자리를 누를 때마다 아이가 바뀌어요"(확인 중 장면만. PM 결정 28: 출발 준비부터는 탭 안내를 감춘다) */
        val showHow: Boolean,
        /**
         * 아이 목록("하늘 · 뒷줄 왼쪽"). 확인 단계에서만 보인다(2026-11-14 디자인 판단: 도착·하차 단계는 빈 칸에 이름을 보내지 않아
         * 내린 아이가 "자리 고르기 전"으로 보였다 → 목록을 감춘다. 아직 앉은 아이는 칸에 이름이 보인다)
         */
        val showKids: Boolean,
        /** 한 줄 색 설명 항목 수: 확인 3(주황·파랑·회색) / 도착 1(주황) / 모두 내림 0 */
        val legendCount: Int,
        /** 이 장면에서 캐릭터가 말하는 문장(말풍선 기록에 넣을 값, [TripBubbleRules.lineOf]). 잠금이면 빈 문자열 */
        val bubble: String,
        /** 오른쪽 아래 큰 버튼 글자(없으면 null) */
        val finishLabel: String?,
        val sticker: StickerLine?,
        val footer: String,
        /**
         * 이동 중 잠금(S09, 주행·기어 불명·단절)일 때 왼쪽 카드에 보일 잠금 사유. null이면 잠금 아님.
         * 잠금이면 좌석 칸·버튼·안내를 그리지 않고(주행 중 새 정보·조작 없음) 오른쪽 무대(캐릭터·말풍선 기록)는 그대로 둔다(지시 4 무대 고정)
         */
        val lockText: String? = null,
        /** J2(결정 34): [새로 출발 확인] 버튼 글자. null이면 버튼 없음(모두 내림 + 다시 앉음 + 잠금 아님일 때만) */
        val newTripLabel: String? = null,
        /** r5 추가 A(계약 v5 askArrived): [예, 도착했어요] 글자. null이면 버튼 없음(질문 중일 때만) */
        val answerYesLabel: String? = null,
        /** r5 추가 A: 질문 중이면 [아니요, 잠깐 멈췄어요], '예' 뒤 되돌리기(arrivedByAnswer)면 r10 D [↩ 도착이 아니에요]([UNDO_ARRIVAL]). 그 밖은 null */
        val answerNoLabel: String? = null,
        /** r6: 두 대답 버튼이 부를 곳(ARRIVAL = onArrivalAnswer, ALIGHT = onAlightAnswer). 버튼이 없으면 의미 없음 */
        val answerKind: AnswerKind = AnswerKind.ARRIVAL,
        /**
         * r7(결정 49): 빠른 시작 대기 한 줄. null이 아니면 왼쪽 카드에 이 한 줄만(좌석 칸·색 설명·버튼·고지·단계 칩 없음), 오른쪽 무대는 캐릭터만
         */
        val waitText: String? = null,
        /**
         * r7(1절): 하차 안내 화면의 도장판은 좌석 그림에 자리를 양보한다. true면 좌석 칸이 [SeatStageSticker.MIN_SEAT_SCALE]보다 작아질 때
         * 도장판 칸을 숨긴다(아이가 내리는 것을 보는 좌석 그림이 먼저). 모두 내림은 false(좌석이 비어 도장판을 그대로 둔다)
         */
        val stickerYields: Boolean = false,
        /** r8: 부제 아래 알림 한 줄(앞에 "!" 기호, 진한 주황 글자). 공간이 모자라도 숨기지 않는다. 없으면 null */
        val note: String? = null,
        /** r8b: [note]의 짧은 판. 안내 칸이 좁아 긴 판이 [SeatGuardianNote.MAX_LINES]줄을 넘으면 이것을 쓴다([SeatGuardianNote.pick]) */
        val noteShort: String? = null,
        /**
         * r10 G(계약 v10 gearCheckPending): 기어 첫 값·유효 P 확인 전. true면 잠금 카드 대신 좌석 확인 화면 + 위쪽 칩 "기어 확인 중…"(점선 원 + 글자),
         * 좌석 칸·[보호자 확인]·[처음으로]는 누르지 않는다(버튼은 비활성 모양 = 옅은 채움 + 점선 윤곽, 투명도 없음)
         */
        val gearPending: Boolean = false,
    )

    /** 좌석 화면(+ 같은 무대)을 쓰는 장면. 이동 흐름 상태가 있을 때 잠금·이동 끝·하차 한 줄 장면도 같은 무대에 둔다(지시 4) */
    fun boardScene(scene: Scene): Boolean = when (scene) {
        Scene.AUTO_CHECK, Scene.DEPART_READY, Scene.ARRIVED, Scene.ALL_LEFT, Scene.LOCKED, Scene.TRIP_END, Scene.EXIT_CHECK -> true
        Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.QUESTION, Scene.YES, Scene.WAIT_GUARDIAN -> false
    }

    /**
     * 장면 + 계약 → 표시 모델. 좌석 화면을 쓰지 않는 장면이거나 좌석 배치를 알 수 없으면 null.
     * [caption]은 무대 자막(captionOf). 말풍선은 이 문장을 기록([TripBubbleRules])에 쌓아 위로 밀어 올린다(ISSUE-10 대체, 지시 4).
     * 2026-11-14 지시 4: 이동 흐름([ac]가 있음) 동안 잠금(LOCKED)·이동 끝(TRIP_END)·하차 한 줄(EXIT_CHECK)도 같은 무대를 쓴다.
     * 잠금은 왼쪽에 잠금 사유만([ScreenModel.lockText]), 이동 끝·하차 한 줄은 하차 단계 좌석 그림.
     */
    fun screenModel(scene: Scene, ac: AutoCheckUi, caption: String, finishLabel: String?, stickerBoardAvailable: Boolean): ScreenModel? {
        if (!boardScene(scene)) return null
        // r10 G(계약 v10): 기어 확인 중이면 전체 잠금 카드 대신 좌석 확인 화면(누름 없음)
        if (gearPending(scene, ac)) return gearPendingModel(ac)
        // r7(결정 49): 빠른 시작 대기 — 좌석 신호가 아직 없어도(행 0) 좌석 화면 틀에 한 줄만. 홈·홈 인사 말풍선 대신
        if (quickWaiting(scene, ac)) return ScreenModel(
            phase = ac.phase, mode = SeatBoardMode.CHECK, cells = emptyList(), children = ac.children,
            title = "", basis = null, sub = "", showHow = false, showKids = false, legendCount = 0,
            bubble = "", finishLabel = null, sticker = null, footer = "", waitText = QUICK_WAITING,
        )
        val cells = cellsOf(ac) ?: return null
        val exitScene = scene == Scene.ARRIVED || scene == Scene.ALL_LEFT || scene == Scene.TRIP_END || scene == Scene.EXIT_CHECK
        val mode = if (exitScene) SeatBoardMode.EXIT else modeOf(ac.phase)
        val locked = scene == Scene.LOCKED
        val chosen = ac.rows.filter { it.childId != null || it.selected }
        val exitSub = if (cells.any { emphasize(it, mode) }) SUB_ARRIVED else SUB_ARRIVED_NONE
        // r6(계약 v6): "내릴까요?"에 예 → 중간 하차. 제목·부제·칩에 '도착'을 쓰지 않는다. 좌석 그림은 하차 단계 그대로(아직 앉은 칸 강조)
        val alightingNow = !locked && alighting(scene, ac)
        // 계약 v5.1: '아니요'(잠깐 멈춤) 뒤 앱으로 돌아오면 도착이 아닌 정차 표현(제목·부제). 칸·[이번 이동 끝내기]는 그대로
        val paused = !locked && pausedScreen(scene, ac)
        val (title, sub) = when (scene) {
            Scene.DEPART_READY -> TITLE_READY to readySub(chosen.mapNotNull { it.verdict })
            Scene.ARRIVED -> when {
                alightingNow -> AlightScreenText.TITLE to AlightScreenText.SUB_ALIGHTING
                paused -> ArrivalAskText.PAUSED_TITLE to ArrivalAskText.PAUSED_SUB
                else -> TITLE_ARRIVED to exitSub
            }
            Scene.TRIP_END, Scene.EXIT_CHECK -> TITLE_EXIT to exitSub
            // r7(결정 47): 보호자 버튼으로 끝냈으면 "이번 이동을 마칠게요"('내린 것 같아요' 없음)
            // r9-1: 부제는 다시 앉음 > 다른 뒷자리 계속 앉음 > 기본([allLeftSub]). [새로 출발 확인]은 다시 앉음일 때만(아래 newTripLabel)
            Scene.ALL_LEFT -> allLeftTitle(ac) to allLeftSub(ac)
            Scene.LOCKED -> "" to ""
            else -> TITLE_CHECK to SUB_CHECK
        }
        // r7(계약 v7 1절): 스티커는 하차 안내 때(도장판 + 오늘 별). 모두 내림에는 도장판만 남기고 새 연출 없음(줄은 "이번 이동 스티커는 받았어요")
        val atExit = !locked && stickerAtExit(scene, ac)
        // r10 J: 손님만 배정된 이동이면 스티커 줄·도장판 없음
        val sticker = if (stickerAllowed(ac) && ((scene == Scene.ALL_LEFT && ac.stickerEarned) || atExit))
            StickerLine(stickerBoardAvailable, "★ " + if (stickerJustFor(scene, ac) == true) AutoCheckText.STICKER_GOT else AutoCheckText.STICKER_EARNED) else null
        val anyMockShown = mockShown(cells, mode, locked)
        // r5 추가 A(계약 v5): 목적지 없는 이동의 정차 질문은 도착 화면 위에 제목·부제만 바꾸고 두 버튼을 보인다([이번 이동 끝내기]는 대답 뒤에)
        // r6(계약 v6): "내릴까요?"도 같은 모양(질문 문장 = 개발이 준 askAlightText 그대로, 버튼 [예, 내려요]/[아니요, 계속 가요], 칩 '정차')
        val askKind = if (locked) null else answerKindOf(scene, ac)
        val undoable = !locked && scene == Scene.ARRIVED && askKind == null && ac.arrivedByAnswer
        if (askKind != null) {
            val q = askScreen(askKind, ac)
            return ScreenModel(
                phase = ac.phase, mode = mode, cells = cells, children = ac.children,
                title = q.title, basis = null, sub = q.sub,
                showHow = false, showKids = false, legendCount = 1,
                bubble = TripBubbleRules.lineOf(scene, caption), finishLabel = null, sticker = null,
                footer = if (anyMockShown) "$FOOTER_BASIS · $FOOTER_MOCK" else FOOTER_BASIS,
                answerYesLabel = q.yes, answerNoLabel = q.no, answerKind = askKind,
            )
        }
        return ScreenModel(
            phase = ac.phase, mode = mode, cells = cells, children = ac.children,
            title = title, basis = if (locked) null else basisLine(scene, ac), sub = sub,
            showHow = scene == Scene.AUTO_CHECK && ac.children.isNotEmpty(),   // 결정 28
            showKids = !locked && mode == SeatBoardMode.CHECK && ac.children.isNotEmpty(),
            legendCount = when { locked || scene == Scene.ALL_LEFT -> 0; mode == SeatBoardMode.EXIT -> 1; else -> 3 },
            bubble = TripBubbleRules.lineOf(scene, caption),
            finishLabel = if (locked) null else finishLabelFor(scene, ac, finishLabel), sticker = sticker, stickerYields = atExit,
            footer = if (anyMockShown) "$FOOTER_BASIS · $FOOTER_MOCK" else FOOTER_BASIS,
            lockText = if (locked) caption else null,
            note = if (locked) null else noteLine(scene, ac),
            noteShort = if (locked) null else noteShortOf(noteLine(scene, ac)),
            newTripLabel = if (reseated(scene, ac)) NEW_TRIP_BUTTON else null,
            answerNoLabel = if (undoable) UNDO_ARRIVAL else null,   // r10 D: 되돌리기 글자(동작은 그대로 onArrivalAnswer(false), answerKind = ARRIVAL)
        )
    }

    /**
     * r10 G: 기어 확인 중 화면인가. 개발이 잠금(StageUiState.locked)을 그대로 두면 장면은 LOCKED, 잠금을 풀어 주면 AUTO_CHECK — 어느 쪽이든 같은 화면.
     * 기어가 P가 아님(D/R/N)으로 확정되면 개발이 gearCheckPending = false → 기존 잠금(S09)
     */
    fun gearPending(scene: Scene, ac: AutoCheckUi?): Boolean =
        ac != null && ac.gearCheckPending && (scene == Scene.LOCKED || scene == Scene.AUTO_CHECK)

    /** r10 G: 캐릭터·말풍선을 잠금처럼 멈출 장면인가(잠금 장면 또는 기어 확인 중). 캐릭터는 유효 P에서만 움직인다 */
    fun stillScene(scene: Scene?, ac: AutoCheckUi?): Boolean = scene == Scene.LOCKED || (scene != null && gearPending(scene, ac))

    /** r10 G: 무대 배경을 고를 장면. 기어 확인 중이면 잠금색(코랄) 대신 확인 화면 배경(고지 줄 대비 유지) */
    fun backdropSceneOf(scene: Scene, ac: AutoCheckUi?): Scene = if (gearPending(scene, ac)) Scene.AUTO_CHECK else scene

    /**
     * r10 G: 기어 확인 중 화면 모델. 좌석 신호가 있으면 확인 화면(제목 "출발 전 확인", 부제 = 누를 수 없는 이유, 버튼 [처음으로]는 비활성),
     * 좌석 신호가 아직 없으면(행 0) 빠른 시작 대기와 같은 한 줄. 어느 쪽이든 위쪽 칩 "기어 확인 중…". 말풍선에 새 문장을 넣지 않는다
     */
    fun gearPendingModel(ac: AutoCheckUi): ScreenModel {
        val cells = cellsOf(ac) ?: return ScreenModel(
            phase = ac.phase, mode = SeatBoardMode.CHECK, cells = emptyList(), children = ac.children,
            title = "", basis = null, sub = "", showHow = false, showKids = false, legendCount = 0,
            bubble = "", finishLabel = null, sticker = null, footer = "", waitText = QUICK_WAITING, gearPending = true,
        )
        val mode = SeatBoardMode.CHECK
        return ScreenModel(
            phase = ac.phase, mode = mode, cells = cells, children = ac.children,
            title = TITLE_CHECK, basis = if (ac.quickStart) QUICK_START_LINE else null, sub = GEAR_PENDING_SUB,
            showHow = false, showKids = ac.children.isNotEmpty(), legendCount = 3,
            bubble = "", finishLabel = StageControlText.TO_HOME, sticker = null,
            footer = if (mockShown(cells, mode, false)) "$FOOTER_BASIS · $FOOTER_MOCK" else FOOTER_BASIS,
            gearPending = true,
        )
    }

    /** 질문 화면 한 장의 글자(제목·부제·두 버튼) */
    data class AskScreen(val title: String, val sub: String, val yes: String, val no: String)

    /** r6: 질문 종류 → 질문 화면 글자. "내릴까요?" 문장은 개발이 준 [AutoCheckUi.askAlightText] 그대로(비었으면 "내릴까요?") */
    fun askScreen(kind: AnswerKind, ac: AutoCheckUi): AskScreen = when (kind) {
        AnswerKind.ALIGHT -> AskScreen(AlightScreenText.question(ac), AlightScreenText.SUB, AlightScreenText.YES, AlightScreenText.NO)
        AnswerKind.ARRIVAL -> AskScreen(ArrivalAskText.QUESTION, ArrivalAskText.SUB, ArrivalAskText.YES, ArrivalAskText.NO)
    }

    /** r10 D: 보조 대답 버튼 읽기 이름. 되돌리기 버튼은 기호 없이 [UNDO_ARRIVAL_DESC], 그 밖은 글자 그대로(null이면 null) */
    fun answerNoDesc(label: String?): String? = if (label == UNDO_ARRIVAL) UNDO_ARRIVAL_DESC else label

    /** J2(결정 34): 모두 내림 화면에서 다시 앉은 신호가 왔는가(계약 v4. 개발이 유효 P·플랫폼 허용까지 확인해 채운다) */
    fun reseated(scene: Scene, ac: AutoCheckUi): Boolean = scene == Scene.ALL_LEFT && ac.reseatedAfterAllLeft

    /**
     * 2026-11-14 C1: 아래 고지의 '모의 신호' 설명은 **지금 화면 칸에 모의 표시가 실제로 보일 때만** 붙인다.
     * 예전에는 [AutoCheckUi.anyMock](기어·내비 모의 포함)만으로도 붙어, 칸에 모의 표시가 없는데 설명이 보였다.
     * 잠금이면 칸을 그리지 않으므로 붙이지 않는다. release는 모의 값이 막혀(BuildMockPolicy) 칸에 모의 표시가 없다 → 항상 "차량 신호 기준 표시"만.
     */
    fun mockShown(cells: List<SeatBoardCell>, mode: SeatBoardMode, locked: Boolean): Boolean =
        !locked && cells.any { mockTag(it, mode) }
}

/** r8b: 안내 칸 알림 줄의 긴 판/짧은 판 고르기(순수). 긴 판이 [MAX_LINES]줄 안에 들면 긴 판 */
object SeatGuardianNote {
    const val MAX_LINES = 2

    /** [fullWidthPx] = 긴 판 한 줄 폭, [availPx] = 안내 칸 폭. 짧은 판이 없으면 긴 판 */
    fun pick(full: String, short: String?, fullWidthPx: Float, availPx: Float): String {
        if (short == null || availPx <= 0f) return full
        val lines = Math.ceil((fullWidthPx / availPx).toDouble()).toInt()
        return if (lines <= MAX_LINES) full else short
    }
}

/**
 * 2026-11-15 r7 디자인 F(계약 v7 1절): 하차 안내 화면 도장판 배치(1920×720 기준).
 * 왼쪽 카드 = [안내 | 좌석 칸](가로, 그대로) 아래 한 줄 색 설명 → 그 아래 도장판 + "★" 줄(모두 내림 화면과 같은 자리·같은 모양).
 * 좌석 칸은 남은 높이에 맞춰 배율로 줄어든다([SeatBoardRules.scaleFor]). 아이가 내리는 것을 봐야 하므로 좌석 칸이 [MIN_SEAT_SCALE]보다 작아지면
 * (글꼴 2.0 등) 도장판 칸을 숨기고 좌석 칸을 먼저 보인다. 숨겨진 동안 별 연출은 결과만(StickerFlyHost: 안 보이면 재생 안 함, 이동당 1회).
 * 오른쪽 무대(캐릭터·말풍선·버튼)는 건드리지 않는다.
 */
object SeatStageSticker {
    /** 좌석 칸 배율 하한(1.0 = 칸 186×216dp). 0.55면 약 102×119dp, 상태 글자 약 20sp */
    const val MIN_SEAT_SCALE = 0.55f
    /** 숨긴 뒤 다시 보일 때의 여유(배치가 오락가락하지 않게) */
    const val SHOW_MARGIN = 0.03f

    /** 도장판을 둘지: 지금 보이면 배율이 하한 이상일 때, 지금 숨김이면 하한 + 여유 이상일 때(예상 배율) */
    fun keep(visibleNow: Boolean, seatScale: Float): Boolean =
        if (visibleNow) seatScale >= MIN_SEAT_SCALE else seatScale >= MIN_SEAT_SCALE + SHOW_MARGIN
}

/** r6: 좌석 화면 두 대답 버튼이 어느 질문의 대답인가 */
enum class AnswerKind {
    /** "목적지에 도착했나요?"(계약 v5) → [StageActions.onArrivalAnswer] */
    ARRIVAL,
    /** "내릴까요?"(계약 v6) → [StageActions.onAlightAnswer] */
    ALIGHT,
}

/**
 * 2026-11-15 r6 디자인 F: "내릴까요?" 질문(계약 v6 askAlight)·중간 하차(alightingByAnswer) 화면 문구. 신호 기준, 해요체.
 * 중간 하차는 목적지에 닿은 것이 아니라 그 자리에서 이동을 마무리하는 흐름이라 '도착'을 쓰지 않는다(AlightScreenTest).
 * 질문 문장 자체는 개발이 준다([AutoCheckUi.askAlightText], 말한 문장과 같음). 여기 [QUESTION_FALLBACK]은 비었을 때만.
 * PM-INTEGRATE(r6 7절): 개발 A(98d7e95)가 같은 문구를 ArrivalAskText(ALIGHTING_TITLE 등)에 두었다. 그 브랜치가 local/v6-dev에 아직 없어
 * 이 브랜치만으로 컴파일되게 같은 문자열을 여기 따로 둔다. A 통합 뒤 원하면 [TITLE] = ArrivalAskText.ALIGHTING_TITLE 로 잇는다(AlightScreenTest가 같은 말인지 본다).
 */
object AlightScreenText {
    const val QUESTION_FALLBACK = "내릴까요?"
    const val SUB = "내리면 [예], 계속 가면 [아니요]를 눌러 주세요"
    const val YES = "예, 내려요"
    const val NO = "아니요, 계속 가요"
    /**
     * 중간 하차 제목(상수 하나). 개발 A 결정(ArrivalAskText.ALIGHTING_TITLE): "여기서 내려요" — '잠깐'은 다시 탈 것처럼 읽히는데 이 흐름은 이동이 그대로 끝난다.
     * 다른 문구로 바뀌면 이 한 줄만 바꾼다
     */
    const val TITLE = "여기서 내려요"
    const val SUB_ALIGHTING = "내리기 전에 벨트를 풀고 물건을 챙겨요"
    /** 중간 하차 장면 자막(말풍선 대체 경로용 — 계약 v3 bubbleLine이 있으면 개발이 말한 문장이 먼저다) */
    const val CAPTION_ALIGHTING = "$TITLE. $SUB_ALIGHTING"
    /** 중간 하차 단계 칩: '도착' 칸 자리를 '정차'로 바꾸고 현재 칸은 '하차'(AutoCheckPanelRules.stepNames) */
    const val STEP_STOP = "정차"

    fun question(ac: AutoCheckUi): String = ac.askAlightText.trim().ifEmpty { QUESTION_FALLBACK }

    fun all(): List<String> = listOf(QUESTION_FALLBACK, SUB, YES, NO, TITLE, SUB_ALIGHTING, CAPTION_ALIGHTING, STEP_STOP)
}

/**
 * 2026-11-14 J2(결정 34): 좌석 화면 오른쪽 무대 아래 버튼 묶음의 순수 배치(dp). [SeatBoardStage]와 같은 숫자를 쓴다.
 * 위 = [새로 출발 확인](주 버튼), 아래 = 장면 큰 버튼([이번 이동 끝내기] 등). 둘 다 캐릭터 상자 왼쪽, 무대 아래 여백 위.
 * 글자 폭이 버튼 안쪽보다 길면 줄을 늘린다(말줄임 없음) → 높이 = max(76, 줄 수 × 줄 높이 + 아래 띠).
 */
object SeatStageButtons {
    const val SIDE_DP = 24
    const val ART_W_DP = 250
    const val ART_GAP_DP = 4
    const val BOTTOM_DP = 20
    const val GAP_DP = 12
    const val PAD_H_DP = 16
    const val TEXT_SP = 28f
    /** 한글 한 글자 폭 ≈ 글자 크기(1em), 띄어쓰기 ≈ 0.35em(보수적으로 크게 잡는다) */
    private const val SPACE_EM = 0.35f
    private const val LINE_EM = 1.25f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    data class Placed(val label: String, val box: Box, val lines: Int)

    fun buttonWidth(stageW: Float): Float = stageW - SIDE_DP - (SIDE_DP + ART_W_DP + ART_GAP_DP)
    fun textWidth(label: String, fontScale: Float): Float {
        val em = TEXT_SP * fontScale
        return label.sumOf { if (it == ' ') SPACE_EM.toDouble() else 1.0 }.toFloat() * em
    }
    fun lines(label: String, width: Float, fontScale: Float): Int {
        val inner = width - PAD_H_DP * 2
        if (inner <= 0f) return Int.MAX_VALUE
        return Math.max(1, Math.ceil((textWidth(label, fontScale) / inner).toDouble()).toInt())
    }
    fun height(lines: Int, fontScale: Float): Float =
        Math.max(DuoTokens.BUTTON_MIN_DP.toFloat(), lines * TEXT_SP * fontScale * LINE_EM + DuoTokens.EDGE_DP)

    /** 아래에서 위로 쌓는다: labels[0]이 맨 아래. null 글자는 건너뛴다 */
    fun place(stageW: Float, stageH: Float, fontScale: Float, bottomUp: List<String?>): List<Placed> {
        val w = buttonWidth(stageW)
        var bottom = stageH - BOTTOM_DP
        val out = ArrayList<Placed>()
        for (l in bottomUp) {
            if (l == null) continue
            val n = lines(l, w, fontScale)
            val h = height(n, fontScale)
            out += Placed(l, Box(SIDE_DP.toFloat(), bottom - h, SIDE_DP + w, bottom), n)
            bottom -= h + GAP_DP
        }
        return out
    }

    /** 캐릭터 상자(오른쪽, 위 20·아래 16 여백) */
    fun artBox(stageW: Float, stageH: Float): Box = Box(stageW - SIDE_DP - ART_W_DP, 20f, stageW - SIDE_DP, stageH - 16f)
}
