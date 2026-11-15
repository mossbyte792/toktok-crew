package com.crewguide.probe

// 2026-11-13 자동 확인(착석 + 벨트 신호). Android·SDK 클래스를 쓰지 않는 순수 로직이다. 시간은 인자로만 받는다.
//
// 규칙 변경(사용자 결정, 이전 → 이후):
//  - 이전: 보호자 '예'만 보고로 받고, 착석·벨트 신호는 화면 참고 표시였다(판정·진행에 쓰지 않음).
//  - 이후: 선택 좌석의 착석·벨트 신호로 판정해 버튼 없이 다음 단계로 간다. 신호를 확인할 수 없는 좌석만 보호자 대체 버튼을 쓴다.
// 판정표(PM 결정 1·2):
//   착석 \ 벨트 | 체결       | 미체결       | 확인 불가·신호 없음
//   OCCUPIED   | CONFIRMED  | WAIT_BUCKLE | NEEDS_GUARDIAN
//   EMPTY      | EMPTY_EXCLUDED (벨트 값과 무관)
//   UNKNOWN    | NEEDS_GUARDIAN (벨트 값과 무관, 빈 좌석으로 바꾸지 않음)
// PM 결정 9: 보호자 대체 확인 뒤 확정 신호(OCCUPIED + 체결/미체결)가 오면 신호 판정이 우선한다.
//   신호가 다시 확인 불가가 되면(또는 EMPTY·UNKNOWN이면) 보호자 확인(GUARDIAN_CONFIRMED)을 유지한다(되돌리지 않음).
// 완료: 선택 좌석 중 CONFIRMED/GUARDIAN_CONFIRMED가 1석 이상이고 WAIT_BUCKLE·NEEDS_GUARDIAN이 0석(EMPTY_EXCLUDED 허용),
//   그 상태가 [STABLE_MS] 동안 이어질 때. 선택 좌석이 모두 EMPTY면 완료가 아니다.
// 판정은 정차 허용 게이트(VehicleGate.PARK_ALLOWED)일 때만 한다(호출자가 parkAllowed로 넘긴다).
// 문구는 신호 기준으로만 말한다. 보증 표현(안전·착용 완료·확실·올바르게)은 쓰지 않는다(SeatCheckRuleTest 금지어 검사).
// 2026-11-13 PM 계약 v2·결정 15(좌석 화면 하나로 확인): [시작]을 누르면 선택 화면 없이 바로 확인(CHECKING)으로 간다.
//   확인 대상 = 아이가 배정된 자리([SeatCheckState.assigned], selected는 그 키 집합). 자리를 누르면 다음 아이로 바뀐다([tapAssign],
//   규칙은 디자인 SeatBoardRules.nextChild). 아이 1명 + 앉은 자리 1곳이면 자동 배정([autoAssign] = SeatBoardRules.autoAssign),
//   보호자가 한 번이라도 누르면 그 뒤로는 자동 배정하지 않는다. 운전석·빈 자리는 배정하지 않고, 확인 불가(회색) 자리는 배정할 수 있다.
// 2026-11-14 PM 결정 23(사용자 결정, 결정 15·22 범위 수정):
//   - 아이 배정은 뒷줄(ROW_2_*)만([isAssignable]). 앞줄은 눌러도 배정하지 않고, 자동 배정·저장·복원도 앞줄을 막는다([dropFrontAssignments]).
//   - 배정은 앉음 신호가 있는(또는 확인 불가인) 뒷줄 칸에서만 유효하다. 배정 칸이 EMPTY가 되면 배정을 푼다([releaseEmpty], 완료 전만).
//   - 앞줄은 배정과 무관하게 앉음 신호가 있으면 항상 벨트 확인 대상([targets]). 재안내는 이름 없이 '앞자리' 한 묶음(FrontBeltRule.TEXT_REMIND).
//     앞줄 앉은 칸이 벨트 확인 불가면 그 칸만 [보호자 확인]을 받는다(배정 없이도).
//   - 완료 = 뒷줄 배정 자리 중 확인(신호·보호자) 1석 이상 + 대상 전체에 대기·확인 불가 0 + 1.5초 유지([completeBySeat]).
//     뒷줄 배정이 없으면 앞줄만 체결이어도 완료하지 않는다.
// 2026-11-15 r8b(검수 세션 위임 판단, 사용자 아침 결정 대기 — 스위치 [GUARDIAN_FOR_UNBUCKLED]로 끔/켬):
//   배정한 뒷줄 자리의 벨트가 미체결 신호(WAIT_BUCKLE)여도 재안내 3회(최초 + 2회)가 끝나면 보호자가 직접 보고 [보호자 확인]을 누를 수 있다
//   (카시트처럼 차량 벨트를 쓰지 않는 자리, 에뮬의 뒷줄 벨트 실제 값 false). 신호를 바꿔 말하지 않는다: 마지막 재안내는
//   "벨트 체결 신호가 없어요. 카시트처럼…", 완료 문장에 "(보호자 확인)". 체결 신호가 오면 신호 우선(보호자 확인 기록을 지움).
//   앞줄(어른 자리, 경고등 기준)은 대상이 아니다. 끄면 지금과 같다(미체결 자리는 보호자 확인을 받지 않음).
// 2026-11-15 r10 I(사용자 결정, 스위치 [SeatCheckRule.CAR_SEAT_IMMEDIATE_GUARDIAN]):
//   등록·수정에서 "차량 벨트를 쓰지 않는 자리(카시트)"를 켠 아이가 배정된 뒷자리([carSeatSeats])는 미체결 신호면 재안내 3회를 기다리지 않고
//   바로 [보호자 확인]을 받는다. 재안내 음성은 1회로 줄이고(임시 결정 후보) 그 1회가 미체결 자리 안내 문장이다.
//   체결 신호가 오면 신호 우선(기존 r8b와 같음), 완료 문장도 r8b "(보호자 확인)" 문장 그대로. 켜지 않은 아이·손님은 r9b 그대로.
//   r8b 스위치([GUARDIAN_FOR_UNBUCKLED])가 꺼지면 카시트 자리도 r9b와 같다(재안내 3회, 미체결 보호자 확인 없음).

/** 좌석 하나의 신호 입력 */
data class SeatSignal(val occ: SeatOcc, val belt: SeatBeltRef, val mock: Boolean = false,
                      /** PM 결정 22: 벨트 판정 출처(앞줄 경고등 기준이면 재안내를 '앞자리' 한 번으로 묶는다) */
                      val beltBasis: BeltBasis = BeltBasis.SEAT)

/** 자동 확인 진행 상태(MainActivity가 보관). 새 확인 실행마다 [SeatCheckRule.start]로 새로 만든다 */
data class SeatCheckState(
    /** 확인 시작 때 고정한 선택 좌석(SDK 키) */
    val selected: Set<String> = emptySet(),
    /** 보호자 대체 버튼으로 확인한 좌석 */
    val guardianConfirmed: Set<String> = emptySet(),
    /** 완료 조건이 처음 성립한 시각(없으면 -1) */
    val completeSinceMs: Long = -1L,
    /** 완료를 이미 알렸는지(실행당 1회) */
    val completed: Boolean = false,
    /** 좌석별 벨트 재안내 횟수(최초 포함) */
    val reminders: Map<String, Int> = emptyMap(),
    /** 좌석별 마지막 재안내 시각 */
    val lastRemindMs: Map<String, Long> = emptyMap(),
    /** '다른 자리에도 누가 앉았어요'를 이미 말했는지(실행당 1회) */
    val extraAnnounced: Boolean = false,
    /** 계약 v2: 자리(SDK 키) → 배정한 아이 id. [selected]는 이 키 집합과 같게 유지한다 */
    val assigned: Map<String, Int> = emptyMap(),
    /** 보호자가 자리를 눌러 배정을 바꾼 적이 있는가(있으면 자동 배정하지 않는다) */
    val userTouched: Boolean = false,
    /** r8b: [guardianConfirmed] 가운데 미체결 신호 자리에서 보호자가 확인한 자리(신호를 바꿔 말하지 않기 위해 따로 기록) */
    val guardianUnbuckled: Set<String> = emptySet(),
    /**
     * r10 J: 보호자가 눌러 '없음'으로 둔 뒷자리. 손님 자동 배정([SeatCheckRule.fillGuests])이 다시 채우지 않는다.
     * 그 자리가 빈 자리(EMPTY) 신호가 되면 잊는다(다시 앉으면 다시 채운다)
     */
    val guestDeclined: Set<String> = emptySet(),
)

sealed class SeatCheckAction {
    /** 좌석 이름으로 벨트 재안내(음성) */
    data class Remind(val seat: String, val text: String) : SeatCheckAction()
    /** 선택하지 않은 좌석에 착석 신호(운전석 제외) */
    data class ExtraOccupied(val labels: List<String>, val text: String) : SeatCheckAction()
    /** 완료(1.5초 유지 뒤 1회) */
    data class Complete(val kind: SeatCheckRule.CompleteKind, val text: String) : SeatCheckAction()
}

data class SeatCheckStep(
    val state: SeatCheckState,
    /** 선택 좌석의 판정(선택 순서 무관 맵) */
    val verdicts: Map<String, SeatVerdict>,
    /** 선택하지 않았는데 착석 신호가 있는 좌석의 화면 이름(운전석 제외) */
    val extraOccupied: List<String>,
    val actions: List<SeatCheckAction>,
)

object SeatCheckRule {
    /** 완료 흔들림 방지: 완료 조건을 이만큼 유지해야 완료로 본다 */
    const val STABLE_MS = 1_500L
    /** 벨트 재안내 간격 */
    const val REMIND_INTERVAL_MS = 20_000L
    /** 최초 안내 외 재안내 최대 횟수(좌석당) */
    const val MAX_EXTRA_REMINDERS = 2
    /** 운전석. extraOccupied에서 뺀다(PM 결정 3) */
    const val DRIVER_SEAT = "ROW_1_LEFT"

    /** 좌석 SDK 키 → 화면·음성 이름 */
    val LABELS: Map<String, String> = linkedMapOf(
        "ROW_1_LEFT" to "앞줄 왼쪽",
        "ROW_1_RIGHT" to "앞줄 오른쪽",
        "ROW_2_LEFT" to "뒷줄 왼쪽",
        "ROW_2_CENTER" to "뒷줄 가운데",
        "ROW_2_RIGHT" to "뒷줄 오른쪽",
    )

    fun label(seat: String): String = LABELS[seat] ?: seat

    // ---- PM 결정 23: 앞줄 규칙 ----
    /** 아이를 배정할 수 있는 자리의 키 앞부분(뒷줄) */
    const val REAR_PREFIX = "ROW_2_"

    /** 아이를 배정할 수 있는 자리인가(뒷줄만). 앞줄·운전석은 false */
    fun isAssignable(seat: String): Boolean = seat.startsWith(REAR_PREFIX)

    /**
     * 확인 대상인가. 앞줄 = 앉음 신호가 있으면 늘 대상([frontBelt]=false인 하차 단계에서는 대상 아님), 그 밖 = 배정한 자리([selected]).
     * 화면 행(SeatCheckRow.selected·verdict)과 판정([step])이 같은 기준을 쓴다
     */
    fun isTarget(seat: String, selected: Set<String>, occ: SeatOcc, frontBelt: Boolean = true): Boolean =
        if (FrontBeltRule.isFront(seat)) frontBelt && occ == SeatOcc.OCCUPIED else seat in selected

    /** 칸에 보일 배정 아이: 뒷줄이고 빈 자리가 아닐 때만(앞줄·빈 칸에는 이름·첫 글자를 보내지 않는다) */
    fun shownChild(seat: String, occ: SeatOcc, assigned: Map<String, Int>): Int? =
        if (isAssignable(seat) && occ != SeatOcc.EMPTY) assigned[seat] else null

    /** 확인 대상 좌석(LABELS 순서): 배정한 뒷줄 자리 + 앉음 신호가 있는 앞줄 자리 */
    fun targets(s: SeatCheckState, signals: Map<String, SeatSignal>): List<String> {
        val keys = LinkedHashSet<String>()
        keys += s.selected.filterNot { FrontBeltRule.isFront(it) }
        keys += FrontBeltRule.FRONT_SEATS.filter { signals[it]?.occ == SeatOcc.OCCUPIED }
        return keys.sortedBy { LABELS.keys.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /**
     * 결정 23 정리(마이그레이션): 배정할 수 없는 자리(앞줄 등)의 배정·선택·보호자 확인을 지운다. 뒷줄로 옮기지 않는다.
     * 반환 = (정리한 상태, 지운 자리). 지울 것이 없으면 같은 객체
     */
    fun dropFrontAssignments(s: SeatCheckState): Pair<SeatCheckState, Set<String>> {
        val bad = (s.assigned.keys + s.selected).filterNot { isAssignable(it) }.toSet()
        if (bad.isEmpty()) return s to emptySet()
        return s.copy(assigned = s.assigned - bad, selected = s.selected - bad, guardianConfirmed = s.guardianConfirmed - bad,
            guardianUnbuckled = s.guardianUnbuckled - bad, completeSinceMs = -1L) to bad
    }

    /**
     * 결정 23: 배정 자리가 빈 자리(EMPTY)가 되면 배정을 푼다(표시·판정 모두, 완료 전만). 신호가 없거나 확인 불가면 그대로 둔다.
     * 앞줄 자리가 빈 자리가 되면 그 자리의 보호자 확인도 지운다(다시 앉으면 처음부터 본다).
     * 남은 배정이 하나도 없으면 자동 배정을 다시 허용한다(다시 앉으면 자동 배정·탭으로 다시).
     */
    fun releaseEmpty(s: SeatCheckState, signals: Map<String, SeatSignal>): SeatCheckState {
        if (s.completed) return s
        fun empty(seat: String) = signals[seat]?.occ == SeatOcc.EMPTY
        val gone = s.selected.filter { empty(it) }.toSet()
        val frontGone = s.guardianConfirmed.filter { FrontBeltRule.isFront(it) && empty(it) }.toSet()
        if (gone.isEmpty() && frontGone.isEmpty()) return s
        val a = s.assigned - gone
        return s.copy(assigned = a, selected = s.selected - gone, guardianConfirmed = s.guardianConfirmed - gone - frontGone,
            guardianUnbuckled = s.guardianUnbuckled - gone, reminders = s.reminders - gone, lastRemindMs = s.lastRemindMs - gone, completeSinceMs = -1L,
            userTouched = if (gone.isNotEmpty() && a.isEmpty()) false else s.userTouched)
    }

    /** 결정 23 완료 조건(유지 시간 제외): 뒷줄 확인(신호·보호자) 1석 이상 + 대상 전체에 대기·확인 불가 0 */
    fun completeBySeat(verdicts: Map<String, SeatVerdict>): Boolean {
        val rearOk = verdicts.count { (k, v) -> !FrontBeltRule.isFront(k) && (v == SeatVerdict.CONFIRMED || v == SeatVerdict.GUARDIAN_CONFIRMED) }
        val blocking = verdicts.values.count { it == SeatVerdict.WAIT_BUCKLE || it == SeatVerdict.NEEDS_GUARDIAN }
        return rearOk >= 1 && blocking == 0
    }

    /**
     * 확인 단계 무대 자막(결정 23, 좌석 키 포함). 뒷줄 배정이 없으면 '자리를 골라 주세요'가 먼저다(앞줄 재안내는 음성으로 따로 나간다).
     * 그다음 보호자 확인 필요 > 벨트 대기 > 착석 대기 > 완료 > 확인 중
     */
    fun captionBySeat(verdicts: Map<String, SeatVerdict>): String {
        val rear = verdicts.filterKeys { !FrontBeltRule.isFront(it) }
        return when {
            rear.isEmpty() -> TEXT_PICK_SEAT
            verdicts.values.any { it == SeatVerdict.NEEDS_GUARDIAN } -> TEXT_NEEDS_GUARDIAN
            verdicts.values.any { it == SeatVerdict.WAIT_BUCKLE } -> TEXT_WAIT_BUCKLE
            allEmpty(rear.values) -> TEXT_WAIT_SIT
            completeBySeat(verdicts) -> completeText(completeKind(verdicts.values))
            else -> TEXT_CHECKING
        }
    }

    /** 무대 자막용: 화면 행(계약)에서 대상 행의 판정만 모아 [captionBySeat] */
    fun captionForRows(rows: List<SeatCheckRow>): String =
        captionBySeat(rows.filter { it.selected && it.verdict != null }.associate { it.seat to it.verdict!! })

    // ---- 문구(신호 기준) ----
    const val TEXT_CHECKING = "자리마다 앉은 신호와 벨트 신호를 보고 있어요"
    const val TEXT_WAIT_SIT = "선택한 자리에 앉은 신호를 기다려요"
    const val TEXT_WAIT_BUCKLE = "벨트 체결 신호를 기다려요"
    const val TEXT_NEEDS_GUARDIAN = "신호를 받을 수 없는 자리는 보호자가 직접 봐 주세요"
    const val TEXT_EXTRA = "다른 자리에도 누가 앉았어요"
    const val TEXT_DONE_SIGNAL = "선택한 자리의 벨트 체결 신호를 받았어요."
    const val TEXT_DONE_MIXED = "벨트 체결 신호와 보호자 확인을 받았어요."
    const val TEXT_DONE_GUARDIAN = "보호자 확인을 받았어요."
    /** 계약 v2: 아직 아이를 배정한 자리가 없을 때(확인 대상 없음) 무대 자막 */
    const val TEXT_PICK_SEAT = "아이가 앉은 자리를 눌러 골라 주세요"
    /** 등록한 아이가 없을 때 쓰는 이름 없는 아이 표시(개인 기록 없음). id는 프로필 id와 겹치지 않는 음수 */
    val GUEST_CHILD = ChildTag(-1, "아이")

    // ---- 2026-11-15 r10 J: 손님 배정(사용자 결정, 스위치 [GUEST_SEATS_ENABLED]로 끔/켬) ----
    // 등록한 아이보다 뒷자리 승객이 많으면 남는 자리도 확인 대상에 넣는다.
    //  - 고르기 순서: 없음 → 등록 아이들(등록 순서) → 손님 → 없음. 손님(id [GUEST_ID])은 여러 자리에 둘 수 있다(한 자리 규칙은 등록 아이만).
    //  - 자동 배정([autoAssign] → [fillGuests]): 등록 아이 1명이고 아직 배정·누름이 없으면 앉은 첫 뒷자리(왼→가운데→오른)에 그 아이,
    //    등록 아이를 모두 배정한 뒤 남은 앉은 뒷자리(OCCUPIED만, 확인 불가 칸 제외)에는 손님. 보호자가 눌러 '없음'으로 둔 자리
    //    ([SeatCheckState.guestDeclined])는 다시 채우지 않는다(빈 자리 신호가 되면 잊음).
    //  - 손님 자리도 배정 자리(selected)라 벨트 확인·주행 중 풀림·하차 확인(ExitDoorRule)·두고 내림 대상이 그대로 된다.
    //    스티커·기록은 없다(호출자가 id < 0을 뺀다: MainActivity.onAutoComplete kidIds).
    //  - 등록 0명이면 기존 이름 없는 아이([GUEST_CHILD], 이름 "아이", 같은 id -1)가 손님 역할을 한다(PM 임시 결정: id 하나 공유).
    //    0명일 때도 여러 자리를 허용할지는 [GUEST_SEATS_NO_PROFILE].
    //  끄면 r9b와 같다(목록 끝 손님 없음, id -1은 한 자리만, 자동 배정은 아이 1명 + 앉은 자리 1곳일 때만, 행 guest=false).
    /** r10 J 스위치. false면 r9b와 같다 */
    const val GUEST_SEATS_ENABLED = true
    /** r10 J: 등록 0명일 때 이름 없는 아이("아이")도 여러 자리에 두고 남은 앉은 뒷자리를 자동으로 채운다. false면 0명 경로는 r9b와 같다 */
    const val GUEST_SEATS_NO_PROFILE = true
    /** 손님(등록 0명일 때 이름 없는 아이 포함) id. 프로필 id(0~3)와 겹치지 않는다 */
    const val GUEST_ID = -1
    /** 등록 아이가 1명 이상일 때 손님 이름(화면·접근성) */
    const val GUEST_NICK = "손님"
    /** 등록 아이가 있을 때 목록 끝에 붙는 손님 */
    val GUEST_TAG = ChildTag(GUEST_ID, GUEST_NICK)

    /** 손님(이름 없는 아이 포함) id인가 */
    fun isGuest(id: Int?): Boolean = id == GUEST_ID

    /**
     * 고르기 목록(계약 [AutoCheckUi.children]): 등록 아이(등록 순서) + 끝에 손님. 등록 아이가 없으면 [GUEST_CHILD] 하나(r9b와 같음).
     * [kids]에 이미 손님 id가 있으면(예: 호출자가 0명일 때 [GUEST_CHILD]를 넣음) 더 붙이지 않는다. 꺼지면 r9b 목록 그대로
     */
    fun withGuest(kids: List<ChildTag>, enabled: Boolean = GUEST_SEATS_ENABLED): List<ChildTag> {
        val registered = kids.filterNot { isGuest(it.id) }
        if (registered.isEmpty()) return listOf(GUEST_CHILD)
        return if (enabled) registered + GUEST_TAG else registered
    }

    /** 이 목록에서 손님을 여러 자리에 둘 수 있는가(스위치 켬 + 목록에 손님 있음 + (등록 아이 있음 또는 0명 허용)) */
    fun guestMulti(children: List<ChildTag>, enabled: Boolean = GUEST_SEATS_ENABLED, noProfile: Boolean = GUEST_SEATS_NO_PROFILE): Boolean =
        enabled && children.any { isGuest(it.id) } && (noProfile || children.any { !isGuest(it.id) })

    /** 계약 v10 [SeatCheckRow.guest] 채움: 칸에 보이는 배정이 손님이면 true. 꺼지면 늘 false(r9b) */
    fun markGuests(rows: List<SeatCheckRow>, enabled: Boolean = GUEST_SEATS_ENABLED): List<SeatCheckRow> =
        rows.map { r -> val g = enabled && isGuest(r.childId); if (r.guest == g) r else r.copy(guest = g) }

    /** 로그용 배정 이름(손님은 "손님", 없음은 "없음") */
    fun assignText(id: Int?): String = when { id == null -> "없음"; isGuest(id) -> "손님"; else -> id.toString() }

    /**
     * r10 J 자동 배정(순수). [children]은 [withGuest] 목록, [cells]는 지금 신호로 만든 칸.
     * ① 아직 누름·배정이 없고 등록 아이가 1명이면 앉은 첫 뒷자리에 그 아이.
     * ② 등록 아이를 모두 배정했으면 남은 앉은 뒷자리(OCCUPIED, 고를 수 있는 칸, [SeatCheckState.guestDeclined] 아님)에 손님.
     * 바뀐 것이 없으면 같은 객체. 완료 뒤·손님 여러 자리가 아닌 목록이면 그대로
     */
    fun fillGuests(s: SeatCheckState, cells: List<SeatBoardCell>, children: List<ChildTag>,
                   enabled: Boolean = GUEST_SEATS_ENABLED, noProfile: Boolean = GUEST_SEATS_NO_PROFILE): SeatCheckState {
        if (s.completed || !guestMulti(children, enabled, noProfile)) return s
        var st = s
        // '없음'으로 둔 자리가 빈 자리 신호가 되면 잊는다(다시 앉으면 다시 채운다)
        val emptyNow = cells.filter { it.occ == SeatOcc.EMPTY }.map { it.seat }.toSet()
        if (st.guestDeclined.any { it in emptyNow }) st = st.copy(guestDeclined = st.guestDeclined - emptyNow)
        val seated = cells.filter { isAssignable(it.seat) && it.assignable && !it.driver && it.occ == SeatOcc.OCCUPIED }.map { it.seat }
            .sortedBy { LABELS.keys.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val registered = children.filterNot { isGuest(it.id) }
        val firstSeat = seated.firstOrNull { it !in st.guestDeclined }
        if (!st.userTouched && st.assigned.isEmpty() && registered.size == 1 && firstSeat != null)
            st = assign(st, firstSeat, registered.first().id, byUser = false)
        val placed = st.assigned.values.toSet()
        if (!registered.all { it.id in placed }) return st
        for (seat in seated) if (seat !in st.assigned && seat !in st.guestDeclined) st = assign(st, seat, GUEST_ID, byUser = false)
        return st
    }

    fun remindText(seat: String): String = "${label(seat)}, 벨트 체결 신호를 기다려요. 벨트를 매 주세요"

    // ---- r8b: 미체결 신호 자리의 보호자 확인(스위치 하나로 끔/켬) ----
    /** r8b 스위치. false면 지금과 같다(미체결 자리는 [보호자 확인]을 받지 않고 재안내 문장도 그대로) */
    const val GUARDIAN_FOR_UNBUCKLED = true
    /** 미체결 자리 안내(화면 칸 안내·마지막 재안내 음성). 신호를 바꿔 말하지 않는다 */
    const val TEXT_GUARDIAN_FOR_UNBUCKLED = "벨트 체결 신호가 없어요. 카시트처럼 차량 벨트를 쓰지 않는 자리면 보호자가 직접 보고 눌러 주세요"
    /** 완료 문장(미체결 자리를 보호자가 확인한 경우). "(보호자 확인)"을 붙인다 */
    const val TEXT_DONE_GUARDIAN_UNBUCKLED = "벨트 체결 신호가 없는 자리를 보호자가 직접 봤어요(보호자 확인)."
    const val TEXT_DONE_MIXED_UNBUCKLED = "벨트 체결 신호를 받은 자리와 보호자가 직접 본 자리가 있어요(보호자 확인)."

    /** 좌석당 재안내 전체 횟수(최초 + [MAX_EXTRA_REMINDERS]) */
    const val TOTAL_REMINDERS = 1 + MAX_EXTRA_REMINDERS

    /** 마지막 재안내 음성(스위치가 켜진 뒷줄 자리): 자리 이름 + 미체결 자리 안내 */
    fun lastRemindText(seat: String): String = "${label(seat)}, $TEXT_GUARDIAN_FOR_UNBUCKLED"

    // ---- r10 I: 카시트 자리(스위치 하나로 끔/켬) ----
    /** r10 I 스위치. false면 카시트 값이 있어도 r9b와 같다([carSeatSeats]가 늘 빈 집합) */
    const val CAR_SEAT_IMMEDIATE_GUARDIAN = true
    /** 카시트 자리의 미체결 재안내 전체 횟수(임시 결정 후보: 3회 → 1회. 그 1회가 미체결 자리 안내 문장) */
    const val CAR_SEAT_REMINDERS = 1

    /**
     * r10 I: 카시트를 켠 아이([ChildTag.carSeat])가 배정된 뒷줄 자리. 스위치가 꺼지면 빈 집합.
     * 손님·이름 없는 아이(id < 0)·앞줄은 넣지 않는다. MainActivity가 [SeatCheckState.assigned]와 아이 목록으로 매번 만든다.
     */
    fun carSeatSeats(assigned: Map<String, Int>, children: List<ChildTag>, enabled: Boolean = CAR_SEAT_IMMEDIATE_GUARDIAN): Set<String> {
        if (!enabled) return emptySet()
        val ids = children.filter { it.carSeat && it.id >= 0 }.map { it.id }.toSet()
        if (ids.isEmpty()) return emptySet()
        return assigned.filter { (seat, id) -> isAssignable(seat) && id in ids }.keys
    }

    /** 이 자리의 미체결 재안내 전체 횟수: r8b 스위치가 켜진 카시트 뒷자리면 [CAR_SEAT_REMINDERS], 그 밖 [TOTAL_REMINDERS] */
    fun remindLimit(seat: String, carSeat: Set<String>, unbuckledEnabled: Boolean = GUARDIAN_FOR_UNBUCKLED): Int =
        if (unbuckledEnabled && isAssignable(seat) && seat in carSeat) CAR_SEAT_REMINDERS else TOTAL_REMINDERS

    /**
     * r8b(계약 v8 [SeatCheckRow.guardianForUnbuckled]): 이 자리에 미체결용 [보호자 확인]을 보일지(순수).
     * 스위치 켬 + 완료 전 + 배정한 뒷줄 자리 + 지금 판정이 미체결 신호(WAIT_BUCKLE) + 재안내 3회가 끝남 + 아직 보호자 확인 전.
     * 체결 신호가 오면 판정이 CONFIRMED라 false(신호 우선).
     * r10 I: [carSeat]([carSeatSeats]) 자리는 재안내 횟수를 기다리지 않는다(미체결 신호면 바로 true).
     */
    fun guardianForUnbuckled(s: SeatCheckState, seat: String, occ: SeatOcc, belt: SeatBeltRef, enabled: Boolean = GUARDIAN_FOR_UNBUCKLED,
                             carSeat: Set<String> = emptySet()): Boolean =
        enabled && !s.completed && isAssignable(seat) && seat in s.selected && seat !in s.guardianUnbuckled &&
            verdict(occ, belt) == SeatVerdict.WAIT_BUCKLE && (seat in carSeat || (s.reminders[seat] ?: 0) >= TOTAL_REMINDERS)

    /** 완료 문장: 미체결 자리를 보호자가 확인했으면 "(보호자 확인)" 문장, 아니면 [completeText] */
    fun completeTextFor(k: CompleteKind, unbuckledByGuardian: Boolean): String = when {
        !unbuckledByGuardian -> completeText(k)
        k == CompleteKind.MIXED -> TEXT_DONE_MIXED_UNBUCKLED
        else -> TEXT_DONE_GUARDIAN_UNBUCKLED
    }

    enum class CompleteKind { SIGNAL, MIXED, GUARDIAN }

    fun completeText(k: CompleteKind): String = when (k) {
        CompleteKind.SIGNAL -> TEXT_DONE_SIGNAL
        CompleteKind.MIXED -> TEXT_DONE_MIXED
        CompleteKind.GUARDIAN -> TEXT_DONE_GUARDIAN
    }

    /** 벨트 값: true 체결, false 미체결, null 확인 불가·신호 없음 */
    private fun beltValue(b: SeatBeltRef): Boolean? = when (b) {
        SeatBeltRef.BUCKLED_SIGNAL -> true
        SeatBeltRef.UNBUCKLED_SIGNAL -> false
        SeatBeltRef.UNKNOWN, SeatBeltRef.NO_SIGNAL -> null
    }

    /** 판정표 9칸(보호자 대체 없음) */
    fun verdict(occ: SeatOcc, belt: SeatBeltRef): SeatVerdict = when (occ) {
        SeatOcc.EMPTY -> SeatVerdict.EMPTY_EXCLUDED
        SeatOcc.UNKNOWN -> SeatVerdict.NEEDS_GUARDIAN
        SeatOcc.OCCUPIED -> when (beltValue(belt)) {
            true -> SeatVerdict.CONFIRMED
            false -> SeatVerdict.WAIT_BUCKLE
            null -> SeatVerdict.NEEDS_GUARDIAN
        }
    }

    /**
     * 보호자 대체 포함 판정(PM 결정 9): 확정 신호(CONFIRMED·WAIT_BUCKLE)는 신호가 우선, 그 밖은 보호자 확인 유지.
     * r8b: [unbuckledByGuardian](미체결 자리를 보호자가 확인함)이면 미체결 신호(WAIT_BUCKLE)도 보호자 확인으로 본다. 체결 신호는 그대로 우선.
     */
    fun verdict(occ: SeatOcc, belt: SeatBeltRef, guardianConfirmed: Boolean, unbuckledByGuardian: Boolean = false): SeatVerdict {
        val base = verdict(occ, belt)
        if (!guardianConfirmed) return base
        return when (base) {
            SeatVerdict.CONFIRMED -> base
            SeatVerdict.WAIT_BUCKLE -> if (unbuckledByGuardian) SeatVerdict.GUARDIAN_CONFIRMED else base
            else -> SeatVerdict.GUARDIAN_CONFIRMED
        }
    }

    /** 완료 조건(유지 시간 제외). 모두 EMPTY·빈 목록은 완료 아님 */
    fun isComplete(verdicts: Collection<SeatVerdict>): Boolean {
        val ok = verdicts.count { it == SeatVerdict.CONFIRMED || it == SeatVerdict.GUARDIAN_CONFIRMED }
        val blocking = verdicts.count { it == SeatVerdict.WAIT_BUCKLE || it == SeatVerdict.NEEDS_GUARDIAN }
        return ok >= 1 && blocking == 0
    }

    fun allEmpty(verdicts: Collection<SeatVerdict>): Boolean =
        verdicts.isNotEmpty() && verdicts.all { it == SeatVerdict.EMPTY_EXCLUDED }

    fun completeKind(verdicts: Collection<SeatVerdict>): CompleteKind {
        val sig = verdicts.any { it == SeatVerdict.CONFIRMED }
        val g = verdicts.any { it == SeatVerdict.GUARDIAN_CONFIRMED }
        return when {
            sig && g -> CompleteKind.MIXED
            g -> CompleteKind.GUARDIAN
            else -> CompleteKind.SIGNAL
        }
    }

    /** 자동 확인 단계 무대 자막(신호 기준). 우선순위: 보호자 확인 필요 > 벨트 대기 > 착석 대기 > 확인 중 */
    fun captionFor(verdicts: Collection<SeatVerdict>): String = when {
        verdicts.isEmpty() -> TEXT_PICK_SEAT   // 계약 v2: 확인 대상(아이 배정 자리)이 아직 없다
        verdicts.any { it == SeatVerdict.NEEDS_GUARDIAN } -> TEXT_NEEDS_GUARDIAN
        verdicts.any { it == SeatVerdict.WAIT_BUCKLE } -> TEXT_WAIT_BUCKLE
        allEmpty(verdicts) -> TEXT_WAIT_SIT
        isComplete(verdicts) -> completeText(completeKind(verdicts))
        else -> TEXT_CHECKING
    }

    fun start(selected: Set<String>): SeatCheckState = SeatCheckState(selected = selected)

    /**
     * 계약 v2 배정 바꾸기. childId=null이면 배정 해제. 완료 뒤·운전석은 그대로. 바뀌면 완료 유지 시각을 지우고,
     * 해제한 자리의 보호자 확인·재안내 기록을 지운다(다시 배정하면 처음부터 본다).
     */
    fun assign(s: SeatCheckState, seat: String, childId: Int?, byUser: Boolean): SeatCheckState {
        if (s.completed || seat == DRIVER_SEAT) return s
        if (childId != null && !isAssignable(seat)) return s   // 결정 23: 앞줄에는 배정하지 않는다(해제는 허용)
        val a = if (childId == null) s.assigned - seat else s.assigned + (seat to childId)
        if (a == s.assigned) return if (byUser) s.copy(userTouched = true) else s
        val removed = s.assigned.keys - a.keys
        return s.copy(assigned = a, selected = a.keys, guardianConfirmed = s.guardianConfirmed - removed, guardianUnbuckled = s.guardianUnbuckled - removed,
            reminders = s.reminders - removed, lastRemindMs = s.lastRemindMs - removed,
            completeSinceMs = -1L, userTouched = s.userTouched || byUser)
    }

    /**
     * 자리 누름(계약 v2 onSeatTap). 무시하면 null: 완료 뒤, 앞줄(결정 23, 운전석 포함), 빈 자리(착석 신호 EMPTY), 고를 아이 없음.
     * 확인 불가(UNKNOWN) 자리는 배정할 수 있다. 다음 아이는 디자인 [SeatBoardRules.nextChild](다른 자리 아이 건너뜀, 마지막 다음 '없음').
     */
    fun tapAssign(s: SeatCheckState, seat: String, occ: SeatOcc, children: List<ChildTag>,
                  guestSeats: Boolean = GUEST_SEATS_ENABLED): SeatCheckState? {
        if (s.completed || !isAssignable(seat) || occ == SeatOcc.EMPTY || children.isEmpty()) return null
        // r10 J: 손님은 여러 자리에 둘 수 있어 '다른 자리에 이미 있음'으로 건너뛰지 않는다(한 자리 규칙은 등록 아이만)
        val multi = guestMulti(children, guestSeats)
        val taken = s.assigned.filterKeys { it != seat }.values.filterNot { multi && isGuest(it) }.toSet()
        val next = SeatBoardRules.nextChild(s.assigned[seat], children, taken)
        val r = assign(s, seat, next, byUser = true)
        if (!multi) return r
        // 보호자가 '없음'으로 둔 자리는 손님 자동 배정이 다시 채우지 않는다
        val declined = if (next == null) r.guestDeclined + seat else r.guestDeclined - seat
        return if (declined == r.guestDeclined) r else r.copy(guestDeclined = declined)
    }

    /**
     * 자동 배정(디자인 [SeatBoardRules.autoAssign]: 아이 1명 + 고를 수 있는 앉은 자리 1곳). 보호자가 누른 적이 있거나 이미 배정이
     * 있으면 그대로. [cells]는 지금 신호로 만든 칸(운전석은 assignable=false). 결정 23: 앞줄 칸은 고를 수 없는 칸으로 바꿔 넘긴다
     */
    fun autoAssign(s: SeatCheckState, cells: List<SeatBoardCell>, children: List<ChildTag>,
                   guestSeats: Boolean = GUEST_SEATS_ENABLED): SeatCheckState {
        // r10 J: 목록에 손님이 있으면(스위치 켬) 손님 자동 배정 규칙. 보호자가 누른 뒤에도 '등록 아이를 다 쓴 뒤 남은 앉은 자리'는 채운다
        if (guestMulti(children, guestSeats)) return fillGuests(s, cells, children, guestSeats)
        if (s.completed || s.userTouched || s.assigned.isNotEmpty()) return s
        val rearOnly = cells.map { if (isAssignable(it.seat)) it else it.copy(assignable = false, child = null) }
        val c = SeatBoardRules.autoAssign(rearOnly, children).firstOrNull { it.child != null && isAssignable(it.seat) } ?: return s
        return assign(s, c.seat, c.child?.id, byUser = false)
    }

    /**
     * 보호자 대체 버튼: 지금 판정이 NEEDS_GUARDIAN인 확인 대상 좌석만 받는다. 그 밖은 같은 상태.
     * 결정 23: 배정한 뒷줄 자리, 또는 앉음 신호가 있는 앞줄 자리(배정과 무관)
     * r8b: 스위치가 켜져 있으면 [guardianForUnbuckled] 자리(재안내가 끝난 미체결 뒷줄 자리)도 받는다([SeatCheckState.guardianUnbuckled]에 따로 기록)
     */
    fun guardianConfirm(s: SeatCheckState, seat: String, current: SeatSignal?, unbuckledEnabled: Boolean = GUARDIAN_FOR_UNBUCKLED,
                        carSeat: Set<String> = emptySet()): SeatCheckState {
        val sig = current ?: SeatSignal(SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN)
        if (s.completed || !isTarget(seat, s.selected, sig.occ)) return s
        if (verdict(sig.occ, sig.belt) == SeatVerdict.NEEDS_GUARDIAN) return if (seat in s.guardianConfirmed) s else s.copy(guardianConfirmed = s.guardianConfirmed + seat)
        // r8b: 확인 불가 때 보호자 확인을 받은 자리에 뒤에 미체결 신호가 와도(결정 9로 WAIT_BUCKLE) 재안내가 끝나면 다시 받을 수 있다
        if (guardianForUnbuckled(s, seat, sig.occ, sig.belt, unbuckledEnabled, carSeat))   // r10 I: 카시트 자리는 재안내 전에도
            return s.copy(guardianConfirmed = s.guardianConfirmed + seat, guardianUnbuckled = s.guardianUnbuckled + seat)
        return s
    }

    /**
     * 평가 1회. signals = 지원 좌석 전체(선택·비선택)의 현재 신호. 선택 좌석인데 signals에 없으면 UNKNOWN으로 본다.
     * parkAllowed=false면 판정만 돌려주고 안내·완료는 하지 않으며 완료 유지 시각을 지운다.
     * r10 I: [carSeat]([carSeatSeats]) 뒷자리는 미체결 재안내를 [CAR_SEAT_REMINDERS]회(미체결 자리 안내 문장)로 줄인다.
     */
    fun step(s0: SeatCheckState, signals: Map<String, SeatSignal>, nowMs: Long, parkAllowed: Boolean,
             unbuckledEnabled: Boolean = GUARDIAN_FOR_UNBUCKLED, carSeat: Set<String> = emptySet()): SeatCheckStep {
        var s = releaseEmpty(s0, signals)   // 결정 23: 빈 자리가 된 배정은 판정 전에 푼다
        // r8b: 미체결 자리를 보호자가 확인한 뒤 체결 신호가 오면 신호 우선(보호자 확인 기록을 지운다). 완료 전만
        if (!s.completed && s.guardianUnbuckled.isNotEmpty()) {
            val buckled = s.guardianUnbuckled.filter { seat -> signals[seat]?.let { verdict(it.occ, it.belt) } == SeatVerdict.CONFIRMED }.toSet()
            if (buckled.isNotEmpty()) s = s.copy(guardianConfirmed = s.guardianConfirmed - buckled, guardianUnbuckled = s.guardianUnbuckled - buckled)
        }
        val verdicts = LinkedHashMap<String, SeatVerdict>()
        for (seat in targets(s, signals)) {
            val sig = signals[seat] ?: SeatSignal(SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN)
            verdicts[seat] = verdict(sig.occ, sig.belt, seat in s.guardianConfirmed, seat in s.guardianUnbuckled)
        }
        // '다른 자리' = 배정하지 않은 뒷줄(·3열) 착석. 앞줄은 늘 확인 대상이라 뺀다(결정 23, 운전석 제외 결정 3 포함)
        val extra = signals.filter { (seat, sig) -> seat !in s.selected && !FrontBeltRule.isFront(seat) && seat != DRIVER_SEAT && sig.occ == SeatOcc.OCCUPIED }
            .keys.sortedBy { LABELS.keys.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }.map { label(it) }
        if (!parkAllowed || s.completed) {
            val st = if (!parkAllowed) s.copy(completeSinceMs = -1L) else s
            return SeatCheckStep(st, verdicts, extra, emptyList())
        }
        val actions = ArrayList<SeatCheckAction>()
        var st = s
        // 벨트 재안내: WAIT_BUCKLE 좌석마다 최초 1회 + 최대 2회(20초 간격)
        val reminders = st.reminders.toMutableMap()
        val lastAt = st.lastRemindMs.toMutableMap()
        val remindedKeys = HashSet<String>()
        for ((seat, v) in verdicts) {
            if (v != SeatVerdict.WAIT_BUCKLE) continue
            // PM 결정 22·23: 앞줄 미체결은 자리 이름·아이 이름 없이 '앞자리' 한 묶음으로 재안내한다(경고등은 자리를 나눌 수 없고,
            // 앞줄에는 아이를 배정하지 않는다). 규칙·횟수는 뒷줄과 같다
            val front = FrontBeltRule.isFront(seat)
            val key = if (front) FrontBeltRule.FRONT_KEY else seat
            if (!remindedKeys.add(key)) continue
            val n = reminders[key] ?: 0
            val last = lastAt[key]
            // r10 I: 카시트 뒷자리는 전체 1회(그 밖 3회 = 최초 + MAX_EXTRA_REMINDERS). 앞줄 묶음은 늘 3회
            val limit = if (front) TOTAL_REMINDERS else remindLimit(seat, carSeat, unbuckledEnabled)
            val due = n == 0 || (n < limit && last != null && nowMs - last >= REMIND_INTERVAL_MS)
            if (due) {
                reminders[key] = n + 1; lastAt[key] = nowMs
                // r8b: 스위치가 켜져 있으면 뒷줄 마지막(3번째, 카시트 자리는 1번째) 재안내는 미체결 자리 안내로(이때부터 [보호자 확인]을 받는다)
                val last = !front && unbuckledEnabled && isAssignable(seat) && n + 1 >= limit
                actions += SeatCheckAction.Remind(key, when { front -> FrontBeltRule.TEXT_REMIND; last -> lastRemindText(seat); else -> remindText(seat) })
            }
        }
        st = st.copy(reminders = reminders, lastRemindMs = lastAt)
        // 계약 v2: 아직 아무 자리도 배정하지 않았으면 앉은 자리는 모두 '고르기 전'이라 '다른 자리' 안내를 하지 않는다
        if (extra.isNotEmpty() && !st.extraAnnounced && s.selected.any { !FrontBeltRule.isFront(it) }) {
            st = st.copy(extraAnnounced = true)
            actions += SeatCheckAction.ExtraOccupied(extra, TEXT_EXTRA)
        }
        // 완료(흔들림 방지)
        if (completeBySeat(verdicts)) {   // 결정 23: 뒷줄 배정 확인 1석 이상 + 앞줄 앉은 자리 포함 대기·확인 불가 0
            val since = if (st.completeSinceMs < 0) nowMs else st.completeSinceMs
            st = st.copy(completeSinceMs = since)
            if (nowMs - since >= STABLE_MS) {
                val k = completeKind(verdicts.values)
                st = st.copy(completed = true)
                val unbuckled = verdicts.any { (seat, v) -> v == SeatVerdict.GUARDIAN_CONFIRMED && seat in st.guardianUnbuckled }
                actions += SeatCheckAction.Complete(k, completeTextFor(k, unbuckled))
            }
        } else {
            st = st.copy(completeSinceMs = -1L)
        }
        return SeatCheckStep(st, verdicts, extra, actions)
    }
}

/**
 * 2026-11-14 PM 결정 25: 확인 중(CHECKING) [처음으로]. 받는 조건 = 확인 단계 + 유효 P·플랫폼 허용(정차 게이트) + 잠금 없음.
 * 받으면 확인 상태는 처음으로(아이 배정·보호자 확인·재안내 기록 모두 비움, 다음 [시작] 때 자동 배정·탭으로 다시).
 */
object CheckCancelRule {
    fun accept(step: BoardingStep, parkAllowed: Boolean, locked: Boolean): Boolean =
        step == BoardingStep.AUTO_CHECK && parkAllowed && !locked

    fun cleared(): SeatCheckState = SeatCheckState()
}
