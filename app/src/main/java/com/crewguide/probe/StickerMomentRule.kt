package com.crewguide.probe

/**
 * 2026-11-15 루프 r7 1절(사용자 지시, PM 계약 v7 stickerJustGranted): 스티커를 **내리기 전에**(하차 안내가 나올 때) 준다(순수 규칙).
 *
 * 예전: 하차 안내 → 아이가 내림 → 마무리 음성 + 스티커 + 작별 인사.
 * 지금: 하차 안내 → 스티커 한 줄("오늘 칸에 별이 생겼어요", 화면 도장판·별은 디자인 F) → 아이가 모두 내리면 마무리 멘트 + 작별 인사만(새 연출 없음).
 *
 * 하차 안내 시점([Moment.EXIT_GUIDE]) 세 가지
 *  1. 실제 내비 도착 신호 뒤 유효 P(도착 음성, TripSayKind.ARRIVED)
 *  2. 목적지 없는 이동 "목적지에 도착했나요?"에 예(같은 도착 음성)
 *  3. "내릴까요?"에 예(중간 하차 안내 음성)
 * 그 밖(질문에 대답 없이 아이가 모두 내림 등)은 예전처럼 하차 마무리([Moment.ALL_LEFT])·이동 끝내기([Moment.FINISH])에 준다.
 * 지급 조건(StickerRule·TripSessionRule.shouldGrant)은 그대로, 이동당 1회. 시점만 바뀐다.
 * **임시 결정 48(사용자 확인 대기)**: "예, 도착했어요"를 되돌려도 준 스티커는 거두지 않고 같은 이동에서 다시 주지 않는다(이동당 1회 기록이 그대로 남음).
 * "아니요"로 계속 가면 하차 안내가 없으므로 스티커도 없다.
 */
object StickerMomentRule {
    enum class Moment { EXIT_GUIDE, ALL_LEFT, FINISH }

    /** 이번 이동에서 아직 안 줬으면 이 시점에 준다(이동당 1회, 되돌리기로 거두지 않음 — 결정 48) */
    fun grantsAt(@Suppress("UNUSED_PARAMETER") m: Moment, alreadyGrantedThisTrip: Boolean): Boolean = !alreadyGrantedThisTrip

    /** 스티커 한 줄 음성을 낼지: 이번에 새로 줬고 기록한 아이가 있을 때(손님 이동·기록 실패는 말하지 않음, 판도 숨김) */
    fun speaksLine(newlyGranted: Boolean, kidsRecorded: Int): Boolean = newlyGranted && kidsRecorded > 0

    /**
     * 계약 v7 stickerJustGranted: 지금 스티커 연출을 보여 줄지.
     * 하차 안내 때 줬으면 하차 안내 화면(도착 ARRIVED, 또는 중간 하차 중 EXITING)에서만 true, 모두 내림 화면에는 새 연출 없음.
     * 하차 마무리 때 준 경우(대답 없이 모두 내림 등)는 예전처럼 모두 내림 화면에서 true.
     */
    fun justGrantedVisible(phase: AutoPhase?, alighting: Boolean, grantedAt: Moment?, withinShow: Boolean): Boolean {
        if (!withinShow) return false
        return when (grantedAt) {
            Moment.EXIT_GUIDE -> phase == AutoPhase.ARRIVED || (alighting && phase == AutoPhase.EXITING)
            Moment.ALL_LEFT -> phase == AutoPhase.ALL_LEFT
            Moment.FINISH, null -> false
        }
    }

    /** 스티커 한 줄 음성 문장(기존 스티커 화면 문장과 같은 말) */
    const val LINE = AutoCheckText.STICKER_GOT
}
