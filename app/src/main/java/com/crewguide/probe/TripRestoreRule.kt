package com.crewguide.probe

// 2026-11-15 r10 M(개발 7): 이동 상태 저장·복원(근본 조치). 순수 규칙(Android 없음, 단위 테스트). 저장 입출력은 [TripStateStore].
//
// 문제: 이동 안내 상태(TripGuideStore·ArrivalAskStore·TripGuideStore.alerts)는 프로세스 메모리뿐이라 앱이 갑자기 꺼지면(에뮬 강제 종료·
// 시동 끔·시스템 정리) 다시 켰을 때 출발·도착·하차 안내·벨트 풀림 감시가 모두 사라지고, 빠른 시작(QuickStartRule)이 좌석 확인부터 다시 열었다.
// 주행 중(D)에 다시 켜지면 빠른 시작은 유효 P를 15초 기다리다 기록을 지우고 홈으로 갔다(임시 결정 49).
//
// 저장(확인 완료부터 이동 끝까지, 바뀔 때만): 이동 번호, 단계(AutoPhase), 배정 자리·아이 번호, 보호자 확인 자리, 스티커 지급 여부, 도착·대답 상태,
// 두고 내림 안내 횟수, 마지막 갱신 시각(벽시계 ms). 목적지 이름·주소·좌표·도착까지 시간·거리는 저장하지 않는다(내비에서 다시 읽는다).
// 이미 따로 저장하던 것(TripSessionStore: 이동 번호·스티커 지급 이동·주행 관측, 하차 한 줄 소진·스티커 조건·하차 마무리 이동)은 그대로 두고
// 여기서는 '같은 이동인가' 검사(이동 번호)와 로그용 스티커 지급 여부만 겹쳐 둔다. 지급 판단의 단일 출처는 계속 TripSessionStore다.
//
// 복원(프로세스당 1회, onCreate): 기록이 있고, 같은 이동(이동 번호 같음, 하차 마무리 기록 없음)이고, 출발한 이동(departed)이고,
// 하차 마무리 전이고, 하차 안내 전이고(아래 r10 수정), 마지막 갱신에서 [WINDOW_MS](10분) 안이면 이동 상태를 되살린다. 시간 값은 비운 채로(경로가 살아 있으면 내비가 다시 준다).
//  - 첫 기어가 D/R/N(주행): 잠금 화면(S09) + 음성만. 출발 안내는 다시 하지 않고(departed), 벨트 풀림 감시는 기존 '다시 주행' 경로
//    (BeltRearmRule, 확인한 자리 tg.selected)로 다시 켜지며, 도착 신호가 오면 기존 도착 흐름(P까지 미룸)을 탄다. → [RestoreRoute.DRIVE_VOICE]
//  - 첫 기어가 유효 P: 정차 처리. 기존 TripGuide.onGear가 정차 시각을 잡고
//      · 도착 신호를 받았고 아직 도착 안내 전 → 도착 안내(하차 안내·스티커) [RestoreRoute.PARK_ARRIVAL]
//      · 이미 도착 안내·'예, 도착했어요'·'예, 내려요' 뒤 → 그 하차 안내 화면 그대로(두고 내림 감시·[다 내렸어요]) [RestoreRoute.PARK_EXIT]
//      · 정차 질문 대답 대기·질문 전 → 정차 질문을 다시 묻는다(목적지 없는 이동 → "목적지에 도착했나요?") [RestoreRoute.PARK_ASK]
//      · '아니요, 계속 가요'·'잠깐 멈췄어요' 뒤 → 그 화면 그대로(다시 출발하면 이어서) [RestoreRoute.PARK_PAUSED]
// r10 수정(PM 지시, 검수 세션): 실차에서는 도착해 P에 세우고 시동을 끄는 게 보통이다. 하차 안내가 나간 이동은 끝난 이동으로 보고 복원하지 않는다
// (기존 빠른 시작·새 이동 규칙에 맡김, r8 3절 '스티커 준 이동은 끝난 이동'과 같은 방향): 도착 안내를 냈다(arrivedSaid), '예, 내려요'(중간 하차, 하차 안내 음성)
// 뒤다, 스티커를 줬다(stickerGranted) → [TripRestoreDecision.EXIT_GUIDED]. 단 정차 중 저장일 때만(새 구간 주행 중 저장은 복원, r10 수정 2). 그래서 위 PARK_EXIT 경로는 복원으로는 오지 않는다(route 판단만 남김).
// 출발 전(확인 완료 뒤 아직 주행 안 함) 기록은 복원하지 않는다: 오래 서 있었을 수 있어 벨트를 다시 보는 빠른 시작(좌석 확인부터)에 맡긴다(임시 결정 후보).
//
// 빠른 시작과의 우선순위(고정): 복원하면 빠른 시작 판단을 건너뛴다(15초 대기 → 기록 지우고 홈 분기를 쓰지 않음). 빠른 시작 기록(ACTIVE)은 지우지 않고
// 이동 서비스 판단(syncQuickStartMark)이 이어서 쓴다. 복원하지 않으면 이 기록은 지우고 빠른 시작이 예전대로 판단한다.

/** 저장 형식에 담는 이동 상태(목적지·시간·거리 없음) */
data class TripStateRecord(
    val tripKey: Int,
    val phase: AutoPhase,
    val confirmed: Boolean,
    val byGuardian: Boolean,
    val selected: Set<String>,
    val guardianSeats: Set<String>,
    val guardianUnbuckledSeats: Set<String>,
    /** 자리 → 아이 번호(프로필 순번, 손님·이름 없는 아이는 음수). 별명은 저장하지 않는다 */
    val assigned: Map<String, Int>,
    val occupiedAtConfirm: Set<String>,
    val occUnknownAtConfirm: Boolean,
    val departed: Boolean,
    val drivingNow: Boolean,
    val departSpoken: Boolean,
    /** 안내 진행 표시(시간 값이 아니라 '이미 말했나'만): 예정 시간·5분·1분 */
    val etaSaid: Boolean,
    val seenAbove5: Boolean,
    val said5: Boolean,
    val seenAbove1: Boolean,
    val said1: Boolean,
    val arrivalSignal: Boolean,
    val arrivedSaid: Boolean,
    val allLeftSaid: Boolean,
    /** 정차 질문 상태(ArrivalAskRule.State, 질문 문장은 종류로 다시 만든다) */
    val asked: Boolean,
    val answer: ArrivalAskRule.Answer?,
    val byAnswer: Boolean,
    val askKind: ArrivalAskRule.Kind,
    val alightAsked: Boolean,
    val alightAnswer: ArrivalAskRule.Answer?,
    /** 이번 이동에 스티커를 지급했는가(로그·검사용 사본, 지급 판단은 TripSessionStore) */
    val stickerGranted: Boolean,
    /** 이번 정차 두고 내림 안내 횟수(LeftBehindState.said) */
    val leftBehindSaid: Int,
    /** 마지막 갱신 시각(벽시계 ms) */
    val updatedAtWallMs: Long,
)

enum class TripRestoreDecision {
    /** 되살린다 */
    RESTORE,
    /** 기록 없음 */
    NONE,
    /** 스위치 꺼짐 */
    OFF,
    /** 이동 번호 읽기 실패·다른 이동·하차 마무리까지 간 이동 */
    OTHER_TRIP,
    /** 출발 전 기록(빠른 시작에 맡김) */
    NOT_DEPARTED,
    /** 끝난 이동(확인 전·하차 마무리) */
    ENDED,
    /** r10 수정: 하차 안내가 나간 이동(도착 안내·'예, 내려요'·스티커 지급) = 끝난 이동으로 봄 */
    EXIT_GUIDED,
    /** 10분 지남·시계 역행 */
    EXPIRED,
    /** 이 프로세스에 이미 이동 상태가 있음(복원 불필요) */
    IN_MEMORY,
}

/** 복원 뒤 첫 유효 기어에서 가는 길(로그·QA용, 동작은 기존 흐름이 한다) */
enum class RestoreRoute { DRIVE_VOICE, PARK_ARRIVAL, PARK_EXIT, PARK_ASK, PARK_PAUSED }

/** 복원할 메모리 상태 묶음 */
data class TripRestored(val guide: TripGuideState, val ask: ArrivalAskRule.State, val leftBehind: LeftBehindState)

object TripRestoreRule {
    /** M 끄는 스위치. false면 저장·복원 모두 하지 않는다(r9b와 같음: 빠른 시작만). PrivacyNotice.TRIP_STATE_IN_NOTICE와 같게 둔다 */
    const val ENABLED = true
    /** 마지막 갱신에서 이 시간 안이면 복원. r10 수정(PM 지시): 3시간 → 10분(갑자기 꺼졌다 바로 다시 켜진 경우만) */
    const val WINDOW_MS = 10 * 60_000L
    /** 내용이 그대로여도 마지막 갱신 시각을 다시 쓰는 간격(긴 주행 중 갑자기 꺼져도 창 계산이 맞게, 빠른 시작 시각 갱신과 같은 1분) */
    const val TOUCH_MS = 60_000L

    /** 저장할 이동인가: 확인 완료 && 하차 마무리 전(TripKeepAliveRule.tripActive와 같은 뜻) */
    fun shouldStore(tg: TripGuideState): Boolean = tg.confirmed && !tg.allLeftSaid

    /** 메모리 상태 → 저장 기록. 시간 값(totalSec·remainSec)·정차 시각(parkedSinceMs)은 넣지 않는다 */
    fun snapshot(tripKey: Int, tg: TripGuideState, ask: ArrivalAskRule.State, stickerGranted: Boolean, leftBehindSaid: Int, nowWallMs: Long): TripStateRecord =
        TripStateRecord(
            tripKey = tripKey, phase = TripGuide.phase(tg) ?: AutoPhase.CONFIRMED,
            confirmed = tg.confirmed, byGuardian = tg.byGuardian, selected = tg.selected, guardianSeats = tg.guardianSeats,
            guardianUnbuckledSeats = tg.guardianUnbuckledSeats, assigned = tg.assigned, occupiedAtConfirm = tg.occupiedAtConfirm,
            occUnknownAtConfirm = tg.occUnknownAtConfirm, departed = tg.departed, drivingNow = tg.drivingNow, departSpoken = tg.departSpoken,
            etaSaid = tg.etaSaid, seenAbove5 = tg.seenAbove5, said5 = tg.said5, seenAbove1 = tg.seenAbove1, said1 = tg.said1,
            arrivalSignal = tg.arrivalSignal, arrivedSaid = tg.arrivedSaid, allLeftSaid = tg.allLeftSaid,
            asked = ask.asked, answer = ask.answer, byAnswer = ask.byAnswer, askKind = ask.kind, alightAsked = ask.alightAsked, alightAnswer = ask.alightAnswer,
            stickerGranted = stickerGranted, leftBehindSaid = leftBehindSaid.coerceIn(0, LeftBehindRule.MAX_SAYS), updatedAtWallMs = nowWallMs,
        )

    /** 시각을 뺀 내용이 같은가(바뀔 때만 commit, 같으면 [TOUCH_MS]마다 시각만 apply) */
    fun sameContent(a: TripStateRecord?, b: TripStateRecord?): Boolean =
        a != null && b != null && a.copy(updatedAtWallMs = 0L) == b.copy(updatedAtWallMs = 0L)

    /**
     * r10 수정: 하차 안내가 나간 뒤 **정차 중(P)에** 저장된 기록인가(도착 안내·'예, 내려요'·스티커 지급). 복원하지 않는다.
     * r10 수정 2(QA 에뮬 1db7e08): L(도착 뒤 다시 출발 = 새 구간)로 다시 주행 중(DEPARTING·DRIVING, drivingNow)에 저장된 기록은
     * 지난 구간의 스티커 지급이 남아 있어도 복원한다(주행 중 음성·벨트 감시를 잇는다). 스티커는 TripSessionStore(stickerTrip)가 이동당 1회를 지켜 다시 주지 않는다.
     * 그 뒤 다시 P로 서서 하차 안내가 나가면 정차 중 저장이 되어 다시 여기서 걸린다.
     */
    fun exitGuided(rec: TripStateRecord): Boolean =
        !rec.drivingNow && (rec.arrivedSaid || rec.alightAnswer == ArrivalAskRule.Answer.YES || rec.stickerGranted)

    /** 창 안인가. 시계 역행(지금이 기록보다 앞)이면 false */
    fun withinWindow(rec: TripStateRecord, nowWallMs: Long): Boolean =
        nowWallMs >= rec.updatedAtWallMs && nowWallMs - rec.updatedAtWallMs <= WINDOW_MS

    /**
     * 프로세스 시작 때 1회. [tripKey]·[allLeftTrip]·[tripReadFailed]는 TripSessionStore 복원 값, [memoryActive] = 이 프로세스의 TripGuideStore가 이미 이동 중.
     * 순서: 스위치 → 메모리 → 기록 없음 → 같은 이동 → 끝난 이동 → 하차 안내 뒤(r10 수정) → 출발 전 → 창.
     */
    fun decide(rec: TripStateRecord?, nowWallMs: Long, tripKey: Int, allLeftTrip: Int, tripReadFailed: Boolean, memoryActive: Boolean,
               enabled: Boolean = ENABLED): TripRestoreDecision = when {
        !enabled -> TripRestoreDecision.OFF
        memoryActive -> TripRestoreDecision.IN_MEMORY
        rec == null -> TripRestoreDecision.NONE
        tripReadFailed || rec.tripKey != tripKey || allLeftTrip == tripKey -> TripRestoreDecision.OTHER_TRIP
        !rec.confirmed || rec.allLeftSaid || rec.phase == AutoPhase.ALL_LEFT -> TripRestoreDecision.ENDED
        exitGuided(rec) -> TripRestoreDecision.EXIT_GUIDED
        !rec.departed -> TripRestoreDecision.NOT_DEPARTED
        !withinWindow(rec, nowWallMs) -> TripRestoreDecision.EXPIRED
        else -> TripRestoreDecision.RESTORE
    }

    /**
     * 기록 → 메모리 상태. [nowMs] = 지금 elapsedRealtime(두고 내림 두 번째 안내 간격 기준).
     *  - 시간 값·정차 시각은 비운다(parkedSinceMs = -1: 첫 유효 P에서 TripGuide.onGear가 새로 잡는다).
     *  - 출발했으면 출발 안내는 이미 한 것으로 본다(departSpoken = true, '출발할게요'를 다시 하지 않고 DEPARTING에 멈추지 않게).
     *  - 주행 중 저장이었으면 정차 질문·두고 내림은 비운다(다음 P = 새 정차, 기존 규칙과 같음).
     *  - 정차 중 "목적지에 도착했나요?" 대답 전이었으면 질문을 비워 다시 묻는다(음성·5초 듣기를 다시 하려고). 그 밖의 대답 상태는 그대로.
     *  - 두고 내림은 횟수만 잇고, 이미 한 번 말했으면 두 번째 안내 간격을 지금부터 센다.
     */
    fun restore(rec: TripStateRecord, nowMs: Long): TripRestored {
        val tg = TripGuideState(
            confirmed = rec.confirmed, byGuardian = rec.byGuardian, selected = rec.selected, guardianSeats = rec.guardianSeats,
            guardianUnbuckledSeats = rec.guardianUnbuckledSeats, assigned = rec.assigned, occupiedAtConfirm = rec.occupiedAtConfirm,
            occUnknownAtConfirm = rec.occUnknownAtConfirm, departed = rec.departed, drivingNow = rec.drivingNow, parkedSinceMs = -1L,
            totalSec = null, remainSec = null, etaSource = SignalSource.NONE,
            etaSaid = rec.etaSaid, seenAbove5 = rec.seenAbove5, said5 = rec.said5, seenAbove1 = rec.seenAbove1, said1 = rec.said1,
            departSpoken = rec.departSpoken || rec.departed,
            arrivalSignal = rec.arrivalSignal, arrivedSaid = rec.arrivedSaid, allLeftSaid = rec.allLeftSaid,
        )
        val savedAsk = ArrivalAskRule.State(asked = rec.asked, answer = rec.answer, byAnswer = rec.byAnswer, kind = rec.askKind,
            alightAsked = rec.alightAsked, alightText = alightText(rec), alightAnswer = rec.alightAnswer)
        val unansweredArrivedQ = rec.asked && rec.askKind == ArrivalAskRule.Kind.ARRIVED && rec.answer == null
        val ask = if (rec.drivingNow || unansweredArrivedQ) ArrivalAskRule.State() else savedAsk
        val said = if (rec.drivingNow) 0 else rec.leftBehindSaid.coerceIn(0, LeftBehindRule.MAX_SAYS)
        val lb = LeftBehindState(firstAtMs = if (said > 0) nowMs else -1L, said = said, pending = false)
        return TripRestored(tg, ask, lb)
    }

    /** "내릴까요?" 문장: 첫 질문이 길 안내 중 정차였으면 정차 문장, '도착했나요?' 아니요 뒤였으면 짧은 문장. 묻지 않았으면 빈 값 */
    fun alightText(rec: TripStateRecord): String = when {
        !rec.alightAsked -> ""
        rec.askKind == ArrivalAskRule.Kind.ALIGHT -> ArrivalAskText.ALIGHT_STOPOVER_Q
        else -> ArrivalAskText.ALIGHT_Q
    }

    /**
     * 복원 뒤 첫 유효 기어에서 어디로 가는가(판단만, 동작은 기존 TripGuide.onGear·ArrivalAskRule.evaluate가 한다).
     * [driving] = 유효한 비-P 기어, 아니면 유효 P + 플랫폼 허용으로 본다(잠금·불명이면 호출자가 부르지 않는다).
     */
    fun route(r: TripRestored, driving: Boolean): RestoreRoute {
        if (driving) return RestoreRoute.DRIVE_VOICE
        val tg = r.guide
        val a = r.ask
        return when {
            tg.arrivalSignal && !tg.arrivedSaid -> RestoreRoute.PARK_ARRIVAL
            tg.arrivalSignal || a.alightAnswer == ArrivalAskRule.Answer.YES -> RestoreRoute.PARK_EXIT
            a.asked && (if (a.alightAsked) a.alightAnswer == ArrivalAskRule.Answer.NO else a.answer == ArrivalAskRule.Answer.NO) -> RestoreRoute.PARK_PAUSED
            else -> RestoreRoute.PARK_ASK
        }
    }

    /** 복원 뒤 첫 유효 기어를 기존 기어 규칙에 넣은 결과(테스트·로그용). 정차 질문은 호출자의 evaluateArrivalAsk가 이어서 한다 */
    fun firstGear(r: TripRestored, driving: Boolean, nowMs: Long): TripGuideStep =
        TripGuide.onGear(r.guide, driving, parkAllowed = !driving, nowMs = nowMs)

    // ---- 저장 형식 "m1|이동번호|갱신시각|단계|플래그|선택|보호자확인|보호자확인미체결|배정|확인때착석|질문종류|대답|내릴대답|두고내림횟수" ----
    // 플래그 = 0/1 문자열(아래 FLAG 순서). 자리 이름은 [A-Za-z0-9_]만, 묶음은 '+', 배정은 "자리:번호". 이름·목적지·시간·위치 없음.
    const val VERSION = "m1"
    private const val FIELDS = 14
    private val SEAT = Regex("[A-Za-z0-9_]+")

    private fun b(v: Boolean) = if (v) '1' else '0'
    private fun ans(a: ArrivalAskRule.Answer?) = when (a) { null -> "-"; ArrivalAskRule.Answer.YES -> "Y"; ArrivalAskRule.Answer.NO -> "N" }
    private fun seats(s: Set<String>) = s.sorted().joinToString("+")

    fun encode(r: TripStateRecord): String {
        val flags = charArrayOf(
            b(r.confirmed), b(r.byGuardian), b(r.occUnknownAtConfirm), b(r.departed), b(r.drivingNow), b(r.departSpoken),
            b(r.etaSaid), b(r.seenAbove5), b(r.said5), b(r.seenAbove1), b(r.said1),
            b(r.arrivalSignal), b(r.arrivedSaid), b(r.allLeftSaid),
            b(r.asked), b(r.byAnswer), b(r.alightAsked), b(r.stickerGranted),
        ).let { String(it) }
        val assigned = r.assigned.entries.sortedBy { it.key }.joinToString("+") { "${it.key}:${it.value}" }
        return listOf(VERSION, r.tripKey.toString(), r.updatedAtWallMs.toString(), r.phase.name, flags,
            seats(r.selected), seats(r.guardianSeats), seats(r.guardianUnbuckledSeats), assigned, seats(r.occupiedAtConfirm),
            if (r.askKind == ArrivalAskRule.Kind.ALIGHT) "L" else "A", ans(r.answer), ans(r.alightAnswer), r.leftBehindSaid.toString(),
        ).joinToString("|")
    }

    private const val FLAG_COUNT = 18

    /** 없음·형식 오류·범위 밖 = null(복원 안 함, 보수적) */
    fun decode(text: String?): TripStateRecord? {
        val f = text?.split('|') ?: return null
        if (f.size != FIELDS || f[0] != VERSION) return null
        val key = f[1].toIntOrNull() ?: return null
        val at = f[2].toLongOrNull() ?: return null
        if (key < 0 || at <= 0L) return null
        val phase = AutoPhase.values().firstOrNull { it.name == f[3] } ?: return null
        val fl = f[4]
        if (fl.length != FLAG_COUNT || fl.any { it != '0' && it != '1' }) return null
        fun flag(i: Int) = fl[i] == '1'
        fun seatSet(s: String): Set<String>? {
            if (s.isEmpty()) return emptySet()
            val parts = s.split('+')
            return if (parts.all { SEAT.matches(it) }) parts.toSet() else null
        }
        val selected = seatSet(f[5]) ?: return null
        val guardian = seatSet(f[6]) ?: return null
        val guardianUnbuckled = seatSet(f[7]) ?: return null
        val assigned = HashMap<String, Int>()
        if (f[8].isNotEmpty()) for (e in f[8].split('+')) {
            val kv = e.split(':')
            if (kv.size != 2 || !SEAT.matches(kv[0])) return null
            assigned[kv[0]] = kv[1].toIntOrNull() ?: return null
        }
        val occ = seatSet(f[9]) ?: return null
        val kind = when (f[10]) { "A" -> ArrivalAskRule.Kind.ARRIVED; "L" -> ArrivalAskRule.Kind.ALIGHT; else -> return null }
        fun answer(s: String): Pair<Boolean, ArrivalAskRule.Answer?> = when (s) {
            "-" -> true to null; "Y" -> true to ArrivalAskRule.Answer.YES; "N" -> true to ArrivalAskRule.Answer.NO; else -> false to null
        }
        val (okA, a) = answer(f[11]); if (!okA) return null
        val (okL, l) = answer(f[12]); if (!okL) return null
        val lb = f[13].toIntOrNull() ?: return null
        if (lb < 0 || lb > LeftBehindRule.MAX_SAYS) return null
        return TripStateRecord(
            tripKey = key, phase = phase,
            confirmed = flag(0), byGuardian = flag(1), selected = selected, guardianSeats = guardian, guardianUnbuckledSeats = guardianUnbuckled,
            assigned = assigned, occupiedAtConfirm = occ, occUnknownAtConfirm = flag(2), departed = flag(3), drivingNow = flag(4), departSpoken = flag(5),
            etaSaid = flag(6), seenAbove5 = flag(7), said5 = flag(8), seenAbove1 = flag(9), said1 = flag(10),
            arrivalSignal = flag(11), arrivedSaid = flag(12), allLeftSaid = flag(13),
            asked = flag(14), answer = a, byAnswer = flag(15), askKind = kind, alightAsked = flag(16), alightAnswer = l,
            stickerGranted = flag(17), leftBehindSaid = lb, updatedAtWallMs = at,
        )
    }
}
