package com.crewguide.probe

/**
 * debug 전용 디자인 미리보기의 가짜 상태(2026-11-13 디자인 담당). 모든 값은 **모의**다(차량·내비 신호 아님).
 * 흐름 문구(말풍선)는 개발 담당 몫이라 여기 넣은 lastSpoken은 캡처용 자리표시 문장이다.
 *
 * variant(장면별):
 *  - AUTO_CHECK: mixed(기본, 5석: 확인됨·체결 기다림·보호자 확인 필요 + 비선택 2),
 *    four(네 판정 한 화면: 확인됨·체결 기다림·보호자 확인 필요·빈 좌석 제외 + 비선택 1), wait_buckle, guardian(신호 없는 자리 많음),
 *    three(지원 좌석 3석), seven(3열 7석), real(모의 아님), partmock(일부 좌석만 모의)
 *  - DEPART_READY: eta(기본, 약 25분 · 모의 내비), noeta(시간 값 없음), real
 *  - ARRIVED: someone(기본, 뒷줄 왼쪽 아직 앉음), unknown(착석 확인 불가 섞임),
 *    ask_arrived(r5 "목적지에 도착했나요?"), ask_alight(r6 "잠시 정차해요. 내릴까요?" 두 문장), ask_alight_short(r6 "내릴까요?"),
 *    alighting(r6 중간 하차 "여기서 내려요", 아이 자리 아직 앉음),
 *    sticker(r7 실제 도착 뒤 하차 안내 + 도장판·오늘 별 연출, 버튼 [다 내렸어요]), alighting_sticker(r7 "예, 내려요" 뒤 중간 하차 + 도장판·별)
 *  - ALL_LEFT: sticker(기본, 스티커 연출), nosticker, guest(등록 아이 없이 진행 = GUEST_CHILD만, 결정 19: 판·별 줄 없음),
 *    door(r6 문 열림 신호도 쓴 하차 판단 → "(문·착석 신호 기준)"), alighted(r6 계약 v6.2 중간 하차로 끝난 이동 → 칩 "정차 · 하차", 문 기준)
 *  - AUTO_CHECK 추가: quick(r6 빠른 시작으로 온 확인 화면 → 제목 아래 "다시 출발 전에 벨트를 확인할게요"),
 *    waiting(r7 빠른 시작 대기 → "차량 신호를 확인하고 있어요" 한 줄만),
 *    unbuckled(r8b 미체결 자리 보호자 확인 — wait_buckle 좌석 + 체결 기다림 자리에 [보호자 확인]과 안내 한 줄)
 *  - ALL_LEFT 추가: guardian(r7 보호자 버튼으로 끝냄 → "이번 이동을 마칠게요" + "(보호자 확인)"),
 *    guardian_seat(r8 1절 보호자 끝냄 + 뒷자리 착석 신호 남음 → "! 뒷자리에 앉은 신호가 아직 있어요"),
 *    other_rear(r9-1 배정 안 한 뒷줄 오른쪽이 계속 앉아 있음 → 부제 "다른 뒷자리에 앉은 신호가 있어요", [새로 출발 확인] 없음)
 *  - LOCKED: (기본) 주행 중 잠금 + 자동 흐름 DRIVING / lost(기어 신호 끊김, 이동 중) · unknown(기어 불명, 이동 중) · lost_home(끊김, 이동 전)
 *  - EXIT_CHECK: (기본) 옛 하차 한 줄 장면 / auto(결정 26: 자동 흐름 EXITING + 하차 한 줄, 좌석 화면 무대 "내리기 전 확인")
 */
object DesignPreviewStates {
    val SEATS = listOf("R1L", "R1R", "R2L", "R2C", "R2R")
    private val LABEL = mapOf(
        "R1L" to "앞줄 왼쪽", "R1R" to "앞줄 오른쪽", "R2L" to "뒷줄 왼쪽", "R2C" to "뒷줄 가운데", "R2R" to "뒷줄 오른쪽",
        "R3L" to "셋째 줄 왼쪽", "R3R" to "셋째 줄 오른쪽",
    )

    class Model(val scene: String, val variant: String) {
        val selectedSeats = LinkedHashSet<String>()
        var rows: MutableList<SeatCheckRow> = ArrayList()
        var auto: AutoCheckUi? = null
        var base = StageUiState(StageGState.IDLE, false, "", 0, 0, false, "", false, "")

        fun toggleSeat(n: String) { if (!selectedSeats.remove(n)) selectedSeats.add(n) }
        fun beltOf(n: String): SeatBeltRef = rows.firstOrNull { it.seat == n }?.belt ?: when (n) {
            "R2L" -> SeatBeltRef.BUCKLED_SIGNAL
            "R2C" -> SeatBeltRef.UNBUCKLED_SIGNAL
            "R2R" -> SeatBeltRef.UNKNOWN
            else -> SeatBeltRef.NO_SIGNAL
        }
        fun occOf(n: String): Boolean? = rows.firstOrNull { it.seat == n }?.let {
            when (it.occ) { SeatOcc.OCCUPIED -> true; SeatOcc.EMPTY -> false; SeatOcc.UNKNOWN -> null }
        }

        /** 좌석 화면에서 칸 누름: 다음 아이로(실제 앱에서는 흐름이 한다) */
        fun tapChild(seat: String) {
            val kids = auto?.children ?: return
            val taken = rows.filter { it.seat != seat }.mapNotNull { it.childId }.toSet()
            rows = rows.map { r ->
                if (r.seat != seat) r else SeatBoardRules.nextChild(r.childId, kids, taken).let { r.copy(childId = it, selected = it != null) }
            }.toMutableList()
            auto = auto?.copy(rows = rows.toList())
        }

        fun guardianConfirm(seat: String) {
            rows = rows.map { if (it.seat == seat && (it.verdict == SeatVerdict.NEEDS_GUARDIAN || it.childId != null)) it.copy(verdict = SeatVerdict.GUARDIAN_CONFIRMED) else it }.toMutableList()
            auto = auto?.copy(rows = rows.toList())
        }

        fun state(): StageUiState = base.copy(
            seatsSelected = selectedSeats.size,
            autoCheck = auto?.copy(rows = rows.toList()),
        )
    }

    /** 계약 v2: 뒷좌석 벨트만 모의(에뮬레이터 VC에 좌석별 벨트 조작 없음), 착석은 실제 VC로 본다 */
    private fun row(seat: String, selected: Boolean, occ: SeatOcc, belt: SeatBeltRef, verdict: SeatVerdict?, mock: Boolean = true) =
        SeatCheckRow(seat, LABEL[seat] ?: seat, selected, occ, belt, verdict, mock, beltMock = mock && seat.startsWith("R2"), occMock = false)

    /** 가짜 아이(별명만). 선택 좌석에 차례로 배정한다(운전석 제외) */
    val KIDS = listOf(ChildTag(1, "하늘"), ChildTag(2, "바다"), ChildTag(3, "별"), ChildTag(4, "달"))

    /** 미리보기 짧은 키 → 실제 SDK 좌석 키(규칙 함수는 실제 키로 판단한다) */
    private val FULL = mapOf("R1L" to "ROW_1_LEFT", "R1R" to "ROW_1_RIGHT", "R2L" to "ROW_2_LEFT", "R2C" to "ROW_2_CENTER", "R2R" to "ROW_2_RIGHT")
    fun fullKey(seat: String): String = FULL[seat] ?: seat

    /** 확인·출발·주행 단계(앞줄 벨트를 보는 단계). 도착·하차 단계는 확인 때의 판정을 그대로 둔다 */
    fun checkPhase(p: AutoPhase): Boolean = p == AutoPhase.CHECKING || p == AutoPhase.CONFIRMED || p == AutoPhase.DEPARTING || p == AutoPhase.DRIVING

    /**
     * 2026-11-14 루프 r1 C2: 미리보기 행을 실제 규칙([SeatCheckRule])에 통과시킨다(손으로 넣은 판정이 규칙과 어긋나 캡처가 실제와 달랐다).
     * - 확인 대상: 앞줄 = 앉음 신호가 있으면 늘 대상(운전석 포함, isTarget), 뒷줄 = 고른 자리 중 빈 자리가 아닌 곳(빈 칸이 되면 배정 해제, 결정 23)
     * - 판정: [SeatCheckRule.verdict](occ, belt, 보호자 확인) — 보호자 확인은 원래 GUARDIAN_CONFIRMED였던 자리만
     * - 아이 배정: 뒷줄이고 빈 자리가 아닌 대상에만(앞줄·빈 칸 배정 금지, 결정 23) — [SeatCheckRule.isAssignable]
     */
    private fun applyRules(m: Model) {
        val ac = m.auto ?: return
        if (checkPhase(ac.phase)) {
            val rear = m.rows.filter { it.selected && !FrontBeltRule.isFront(fullKey(it.seat)) && it.occ != SeatOcc.EMPTY }.map { fullKey(it.seat) }.toSet()
            m.rows = m.rows.map { r ->
                val target = SeatCheckRule.isTarget(fullKey(r.seat), rear, r.occ)
                r.copy(selected = target, verdict = if (target) SeatCheckRule.verdict(r.occ, r.belt, r.verdict == SeatVerdict.GUARDIAN_CONFIRMED) else null)
            }.toMutableList()
            m.selectedSeats.clear(); m.rows.filter { it.selected }.forEach { m.selectedSeats += it.seat }
        }
        var n = 0
        m.rows = m.rows.map {
            // 확인 단계는 빈 칸 배정 해제(결정 23). 도착·하차 단계는 확인 때 배정을 그대로 둔다(내린 뒤 빈 칸 = 스티커·두고 내림 기준)
            val kid = it.selected && SeatCheckRule.isAssignable(fullKey(it.seat)) && (!checkPhase(ac.phase) || it.occ != SeatOcc.EMPTY) && n < KIDS.size
            it.copy(childId = if (kid) KIDS[n++].id else null)
        }.toMutableList()
        // "다른 자리에도 누가 앉았어요": 대상이 아닌데 앉음 신호가 있는 자리(운전석 제외, 결정 3). 확인 중에만
        val extra = if (ac.phase == AutoPhase.CHECKING && ac.extraOccupied.isNotEmpty())
            m.rows.filter { !it.selected && it.occ == SeatOcc.OCCUPIED && fullKey(it.seat) != SeatCheckRule.DRIVER_SEAT }.map { it.label } else ac.extraOccupied
        m.auto = ac.copy(rows = m.rows.toList(), children = KIDS.take(maxOf(n, 1)), extraOccupied = extra)
    }

    fun model(scene: String, variant: String): Model {
        val m = Model(scene, variant)
        val idle = StageUiState(StageGState.IDLE, false, "", 0, 0, false, "", false, "", profilesExist = false)
        when (scene) {
            "PICK" -> { m.base = idle }
            "READY" -> { m.selectedSeats += listOf("R2L", "R2R"); m.base = idle }
            "QUESTION" -> { m.selectedSeats += "R2L"; m.base = idle.copy(g = StageGState.ASKING) }
            "EXIT_CHECK" -> {
                m.base = idle.copy(exitCheckActive = true)
                // I5(결정 26): 도착 신호 없이 P로 멈춘 이동 — 자동 흐름 상태(EXITING)가 있는 하차 한 줄 장면. 제목 "내리기 전 확인"
                if (variant == "auto") {
                    m.rows = arrivedRows("someone")
                    m.auto = AutoCheckUi(AutoPhase.EXITING, m.rows, stickerEarned = true, anyMock = true)
                }
            }
            "LOCKED" -> {
                m.base = idle.copy(locked = true, lockText = LOCK_TEXT_NOT_PARK, drivingGear = true, mockGear = true)
                // 2026-11-14 SCREEN_EXIT_CHECK ⚠️⑦: 단절·불명 잠금 카드(할 일 한 줄). lost·unknown = 이동 중 좌석 화면 무대, lost_home = 이동 전(무대 잠금 카드)
                val lostText = when (variant) { "lost", "lost_home" -> LOCK_TEXT_SUB_FAILED; "unknown" -> LOCK_TEXT_UNKNOWN; else -> null }
                if (lostText != null) m.base = idle.copy(locked = true, lockText = lostText)
                if (variant != "lost_home") {
                    m.rows = autoRows("mixed", confirmed = true)
                    m.auto = AutoCheckUi(AutoPhase.DRIVING, m.rows, eta = TripEta(25, 18, SignalSource.MOCK), anyMock = true,
                        lastSpoken = "곧 도착해요. 조금만 기다려 줘요(모의)")
                }
            }
            "AUTO_CHECK" -> {
                m.rows = autoRows(if (variant == "unbuckled") "wait_buckle" else variant.ifEmpty { "mixed" }, confirmed = false)
                // r8b(계약 v8): 체결 기다림 자리에 보호자 확인을 연다(재안내가 끝난 뒤 상태)
                if (variant == "unbuckled") m.rows = m.rows.map { it.copy(guardianForUnbuckled = it.verdict == SeatVerdict.WAIT_BUCKLE) }.toMutableList()
                m.rows.filter { it.selected }.forEach { m.selectedSeats += it.seat }
                val extra = if (variant == "three" || variant == "real" || variant == "four" || variant == "unbuckled") emptyList() else listOf("앞줄 오른쪽")
                m.auto = AutoCheckUi(AutoPhase.CHECKING, m.rows, extraOccupied = extra,
                    anyMock = variant != "real" && variant != "partmock",
                    lastSpoken = "뒷줄 가운데 친구, 벨트를 매 줄래요?(모의 문장)",
                    quickStart = variant == "quick",   // r6 계약 v6.1
                    quickStartWaiting = variant == "waiting")   // r7 계약 v7
                m.base = idle.copy(g = StageGState.IDLE)
            }
            "DEPART_READY" -> {
                m.rows = autoRows("mixed", confirmed = true)
                m.rows.filter { it.selected }.forEach { m.selectedSeats += it.seat }
                val eta = when (variant) {
                    "noeta" -> null
                    "real" -> TripEta(25, 25, SignalSource.REAL)
                    else -> TripEta(25, 25, SignalSource.MOCK)
                }
                m.auto = AutoCheckUi(AutoPhase.CONFIRMED, m.rows, eta = eta, stickerEarned = true, anyMock = variant != "real",
                    lastSpoken = "출발 준비됐어요. 도착까지 약 25분 걸려요(모의 문장)")
            }
            "ARRIVED" -> {
                // r6: 중간 하차는 아이가 아직 앉아 있는 순간(뒷줄 왼쪽 앉음)을 보인다. 질문 장면은 기본 좌석 그대로
                m.rows = arrivedRows(variant)
                val q = when (variant) { "ask_alight" -> "잠시 정차해요. 내릴까요?"; "ask_alight_short" -> "내릴까요?"; else -> "" }
                // r7: sticker = 실제 도착(phase ARRIVED) 하차 안내 + 방금 받은 스티커, alighting_sticker = 중간 하차 + 방금 받은 스티커
                val r7Sticker = variant == "sticker" || variant == "alighting_sticker" || variant == "sticker_guest"
                m.auto = AutoCheckUi(if (variant == "sticker" || variant == "sticker_guest") AutoPhase.ARRIVED else AutoPhase.EXITING, m.rows,
                    eta = TripEta(25, 0, SignalSource.MOCK), stickerEarned = true, anyMock = true,
                    lastSpoken = "도착했어요. 내리기 전에 뒷좌석을 한 번 더 봐 주세요(모의 문장)",
                    askArrived = variant == "ask_arrived",
                    askAlight = q.isNotEmpty(), askAlightText = q,
                    alightingByAnswer = variant == "alighting" || variant == "alighting_sticker",
                    stickerJustGranted = r7Sticker)
            }
            "ALL_LEFT" -> {
                m.rows = SEATS.map { row(it, it in setOf("R2L", "R2C"), SeatOcc.EMPTY, SeatBeltRef.UNBUCKLED_SIGNAL, if (it in setOf("R2L", "R2C")) SeatVerdict.EMPTY_EXCLUDED else null) }.toMutableList()
                // r9-1: 배정 안 한 뒷줄 오른쪽이 마무리 때부터 계속 앉아 있음
                if (variant == "other_rear") m.rows = m.rows.map { if (it.seat == "R2R") it.copy(occ = SeatOcc.OCCUPIED) else it }.toMutableList()
                val sticker = variant != "nosticker"
                m.auto = AutoCheckUi(AutoPhase.ALL_LEFT, m.rows, stickerEarned = sticker, stickerJustGranted = sticker, anyMock = true,
                    lastSpoken = TripGuide.TEXT_ALL_LEFT, exitByDoor = variant == "door" || variant == "alighted",   // r6 계약 v6
                    exitByGuardian = variant == "guardian" || variant == "guardian_seat",   // r7 계약 v7(결정 47)
                    guardianFinishSeatOccupied = variant == "guardian_seat",   // r8 계약 v8
                    otherRearSeatedAfterAllLeft = variant == "other_rear",   // r9 계약 v9
                    alightingByAnswer = variant == "alighted")   // r6 계약 v6.2
            }
        }
        applyRules(m)
        // 2026-11-14 루프 r1 C5: 등록 아이 없이 진행한 이동(결정 19). 배정·아이 목록이 GUEST_CHILD뿐
        // r7: 하차 안내 화면의 손님 이동(sticker_guest) — 도장판·별 줄 없어야 함
        if ((scene == "ALL_LEFT" && variant == "guest") || (scene == "ARRIVED" && variant == "sticker_guest")) {
            val g = SeatCheckRule.GUEST_CHILD
            m.rows = m.rows.map { if (it.childId != null) it.copy(childId = g.id) else it }.toMutableList()
            m.auto = m.auto?.copy(rows = m.rows.toList(), children = listOf(g))
        }

        return m
    }

    private fun autoRows(variant: String, confirmed: Boolean): MutableList<SeatCheckRow> {
        val mock = variant != "real"
        if (confirmed) return mutableListOf(
            row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null),
            row("R1R", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null),
            row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED),
            row("R2C", true, SeatOcc.EMPTY, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.EMPTY_EXCLUDED),
            row("R2R", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.GUARDIAN_CONFIRMED),
        )
        return when (variant) {
            "wait_buckle" -> mutableListOf(
                row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null),
                row("R1R", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null),
                row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE),
                row("R2C", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null),
                row("R2R", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED),
            )
            "guardian" -> mutableListOf(
                row("R1L", false, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, null),
                row("R1R", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.NEEDS_GUARDIAN),
                row("R2L", true, SeatOcc.UNKNOWN, SeatBeltRef.NO_SIGNAL, SeatVerdict.NEEDS_GUARDIAN),
                row("R2C", true, SeatOcc.OCCUPIED, SeatBeltRef.UNKNOWN, SeatVerdict.NEEDS_GUARDIAN),
                row("R2R", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.GUARDIAN_CONFIRMED),
            )
            "four" -> mutableListOf(
                row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null),
                row("R1R", true, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, SeatVerdict.EMPTY_EXCLUDED),
                row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED),
                row("R2C", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE),
                row("R2R", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.NEEDS_GUARDIAN),
            )
            "three" -> mutableListOf(
                row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null),
                row("R1R", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE),
                row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED),
            )
            "seven" -> mutableListOf(
                row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null),
                row("R1R", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null),
                row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED),
                row("R2C", true, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, SeatVerdict.EMPTY_EXCLUDED),
                row("R2R", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE),
                row("R3L", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.NEEDS_GUARDIAN),
                row("R3R", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null),
            )
            "partmock" -> mutableListOf(
                row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null, mock = false),
                row("R1R", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null, mock = false),
                row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED, mock = false),
                row("R2C", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE, mock = true),
                row("R2R", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.NEEDS_GUARDIAN, mock = false),
            )
            else -> mutableListOf(   // mixed / real
                row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null, mock),
                row("R1R", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE, mock),   // C2: 앞줄 앉음 = 늘 대상(미체결 → 체결 대기)
                row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED, mock),
                row("R2C", true, SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.WAIT_BUCKLE, mock),
                row("R2R", true, SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN, SeatVerdict.NEEDS_GUARDIAN, mock),
            )
        }
    }

    private fun arrivedRows(variant: String): MutableList<SeatCheckRow> = mutableListOf(
        row("R1L", false, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, null),
        row("R1R", false, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL, null),
        row("R2L", true, SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, SeatVerdict.CONFIRMED),
        row("R2C", true, SeatOcc.EMPTY, SeatBeltRef.UNBUCKLED_SIGNAL, SeatVerdict.EMPTY_EXCLUDED),
        row("R2R", true, if (variant == "unknown") SeatOcc.UNKNOWN else SeatOcc.EMPTY, SeatBeltRef.UNKNOWN, SeatVerdict.GUARDIAN_CONFIRMED),
    )
}

/** 무대를 만든 직후 미리보기 전용 값(스티커판 주 기록 등)을 넣는 자리. 실제 앱에서는 MainActivity(개발 담당)가 setStickerWeek를 부른다 */
object PreviewHooks {
    /** 가짜 이번 주(2026-11-09 월 ~ 11-15 일, 오늘 = 11-13 금). 오늘 칸은 방금 받은 스티커 1개가 반영된 값 */
    val FAKE_WEEK: List<BoardCell> = listOf(
        BoardCell("2026-11-09", 0, 1, false, false),
        BoardCell("2026-11-10", 1, 0, false, false),
        BoardCell("2026-11-11", 2, 2, false, false),
        BoardCell("2026-11-12", 3, 0, false, false),
        BoardCell("2026-11-13", 4, 1, true, false),
        BoardCell("2026-11-14", 5, 0, false, true),
        BoardCell("2026-11-15", 6, 0, false, true),
    )

    /**
     * 2026-11-14 지시 4: 장면 앞에 캐릭터가 말했을 문장(흐름 순서, 오래된 것 먼저). 실제 문장 상수 그대로(VOICE_BUBBLE_MAP 1절)
     */
    fun bubbleLines(scene: String): List<String> {
        val done = SeatCheckRule.TEXT_DONE_SIGNAL
        return when (scene) {
            "AUTO_CHECK" -> listOf(SeatCheckRule.TEXT_PICK_SEAT, SeatCheckRule.TEXT_NEEDS_GUARDIAN)
            "DEPART_READY" -> listOf(SeatCheckRule.TEXT_PICK_SEAT, SeatCheckRule.TEXT_WAIT_BUCKLE)
            "LOCKED" -> listOf(SeatCheckRule.TEXT_WAIT_BUCKLE, done + " " + TripGuide.TEXT_READY_TO_GO)
            "ARRIVED" -> listOf(SeatCheckRule.TEXT_WAIT_BUCKLE, done + " " + TripGuide.TEXT_READY_TO_GO)
            "ALL_LEFT" -> listOf(done + " " + TripGuide.TEXT_READY_TO_GO, TripGuide.TEXT_ARRIVED)
            "EXIT_CHECK" -> listOf(SeatCheckRule.TEXT_WAIT_BUCKLE, done + " " + TripGuide.TEXT_READY_TO_GO)
            else -> emptyList()
        }
    }

    fun afterCreate(s: StageScreen, m: DesignPreviewStates.Model) {
        // r7(계약 v7 1절): 하차 안내(도착·중간 하차) 화면에도 도장판
        if (m.scene == "ALL_LEFT" || m.scene == "ARRIVED") s.setStickerWeek(FAKE_WEEK)
    }
}
