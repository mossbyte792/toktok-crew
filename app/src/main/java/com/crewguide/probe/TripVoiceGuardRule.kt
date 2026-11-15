package com.crewguide.probe

// 2026-11-15 루프 r8(개발 B, 검수 세션 지시) 순수 규칙. Android 클래스 없음.
//  2절 [BeltRearmRule]: 정차(유효 P)로 주행 벨트 감시가 끝난 뒤, 하차 마무리 없이 이동이 이어져 다시 주행하면 같은 자리로 감시를 다시 켠다
//       ("아니요, 계속 가요" · 대답 없이 D · 되돌리기 뒤). 다시 출발 순간 이미 미체결 신호인 배정 자리는 한 번 알린다(기존 문장·경고음·간격·횟수 그대로).
//  4절 [ParkVoiceRule]: 도착·중간 하차 안내와 스티커 한 줄은 줄에서 실제로 보낼 때 유효 P + 허용을 다시 본다.
//       아니면(1.8초 지켜보기 사이 R/D 등) 버리고 소진하지 않는다 → 다음 유효 P에서 다시 말한다.

object BeltRearmRule {
    /**
     * 다시 주행할 때 감시를 다시 켤까.
     * [sessionActive] = 지금 감시 세션이 켜져 있음, [tripContinues] = 확인 완료 && 하차 마무리 전(TripKeepAliveRule.tripActive),
     * [driving] = 유효한 비-P 기어(링크 정상), [seats] = 이번 이동에서 감시하던 자리.
     */
    fun shouldRearm(sessionActive: Boolean, tripContinues: Boolean, driving: Boolean, seats: Set<String>): Boolean =
        !sessionActive && tripContinues && driving && seats.isNotEmpty()

    /**
     * 다시 출발하는 순간 이미 미체결 신호(false)인 감시 자리. 확인 불가(null)·체결은 빼고, 순서는 좌석 이름순(로그·음성 순서 고정).
     * r8 검수 지적: 앞줄은 좌석별 값이 아니라 경고등 기준이다(결정 22, 에뮬 앞줄 좌석별 값은 늘 false) → 여기서 빼고
     * 기존 주행 중 앞줄 경고등 경로(FrontBeltRule.releaseEdge)에 맡긴다. 뒷줄은 좌석별 값·모의(좌석 확인과 같은 출처).
     */
    fun unbuckledAtDepart(seats: Set<String>, latest: Map<String, Boolean?>,
                          guardianUnbuckled: Set<String> = emptySet(), buckledSeen: Set<String> = emptySet()): List<String> =
        seats.filter { !FrontBeltRule.isFront(it) && latest[it] == false && !quietUnbuckled(it, guardianUnbuckled, buckledSeen) }.sorted()

    /**
     * r8b 검수 지적 2: 확인 때 미체결 신호 자리를 보호자가 확인했으면(SeatCheckState.guardianUnbuckled → TripGuideState.guardianUnbuckledSeats)
     * 그 자리는 계속 미체결 신호다 → 다시 출발 때 '이미 미체결' 안내를 하지 않는다. 이번 이동에서 체결 신호가 한 번 오면([buckledSeen]) 보통 자리처럼.
     * 주행 중 풀림 감시는 원래 '체결 → 미체결' 변화에만 알리므로(BeltWarningPolicy.observe·BeltReleaseAlertRule.onChange) 그 자리는 체결 전까지 알리지 않는다.
     */
    fun quietUnbuckled(seat: String, guardianUnbuckled: Set<String>, buckledSeen: Set<String>): Boolean =
        seat in guardianUnbuckled && seat !in buckledSeen

    /** 이번 이동에서 체결 신호를 본 보호자 확인 미체결 자리 기록(값이 true일 때만 더함) */
    fun onBeltValue(buckledSeen: Set<String>, seat: String, value: Boolean?, guardianUnbuckled: Set<String>): Set<String> =
        if (value == true && seat in guardianUnbuckled) buckledSeen + seat else buckledSeen
}

object ParkVoiceRule {
    /** 유효 P에서만 내보내는 자동 안내 이름(도착 안내 · 중간 하차 안내 · 스티커 한 줄) */
    const val LABEL_ARRIVED = VoiceQueueRule.LABEL_ARRIVED
    const val LABEL_ALIGHTING = ArrivalAskText.ALIGHTING_VOICE_LABEL
    const val LABEL_STICKER = "스티커"

    fun parkOnly(label: String): Boolean = label == LABEL_ARRIVED || label == LABEL_ALIGHTING || label == LABEL_STICKER

    /** 보낼 때 정차 확인: 유효 P + 허용(잠금 사유 없음)이고 주행 아님이면 보낸다. 유효 P 전용이 아닌 안내는 늘 true */
    fun sendable(label: String, voicePark: Boolean, driving: Boolean): Boolean = !parkOnly(label) || (voicePark && !driving)

    /**
     * 버린 도착 안내를 소진하지 않게: 도착 안내를 말한 것으로 적힌 상태를 되돌린다(도착 신호는 남김) → 다음 유효 P에서 TripGuide.onGear가 다시 낸다.
     * 하차 마무리 뒤·도착 신호가 없으면 그대로.
     */
    fun unsayArrival(tg: TripGuideState): TripGuideState =
        if (tg.arrivalSignal && tg.arrivedSaid && !tg.allLeftSaid) tg.copy(arrivedSaid = false) else tg

    /** 다음 유효 P에서 다시 낼 안내(중간 하차·스티커 한 줄. 도착은 TripGuide가 다시 냄) */
    data class Retry(val alighting: Boolean = false, val sticker: Boolean = false)

    fun onDropped(r: Retry, label: String): Retry = when (label) {
        LABEL_ALIGHTING -> r.copy(alighting = true)
        LABEL_STICKER -> r.copy(sticker = true)
        else -> r
    }

    /**
     * 유효 P가 되었을 때 다시 낼 것. 반환 (남길 Retry, 낼 이름 목록 — 중간 하차 → 스티커 순).
     * 중간 하차는 아직 중간 하차 중일 때만(그 사이 이동이 바뀌었으면 버림). 유효 P가 아니면 아무것도 내지 않는다.
     */
    fun onPark(r: Retry, voicePark: Boolean, driving: Boolean, stillAlighting: Boolean): Pair<Retry, List<String>> {
        if (!voicePark || driving) return r to emptyList()
        val out = ArrayList<String>()
        if (r.alighting && stillAlighting) out += LABEL_ALIGHTING
        if (r.sticker) out += LABEL_STICKER
        return Retry() to out
    }
}

/**
 * r8 4절 후속(검수 재현 r7 d1e9278 04:40): 말풍선 줄(계약 v3 bubbleLine·bubbleSeq)은 **실제로 보낸** 안내로만 갱신한다.
 * 줄에 세울 때·버릴 때(주행 중이라 버린 도착 안내 등)는 갱신하지 않고, 다음 유효 P에서 다시 보낼 때 갱신한다.
 * 그래서 지난 질문("내릴까요?")이 최신 줄로 남아 있어도 다시 낸 도착 안내가 나갈 때 바뀐다.
 * 내비 때문에 멈췄다 다시 말하는 문장(resaid > 0)은 처음 보낼 때 이미 갱신했으므로 다시 갱신하지 않는다. 홈 인사는 말풍선에 넣지 않는다(기존).
 */
object BubblePushRule {
    const val LABEL_HOME_GREET = "홈인사"

    fun pushes(label: String, sentGen: Int, resaid: Int): Boolean = sentGen >= 0 && resaid == 0 && label != LABEL_HOME_GREET
}
