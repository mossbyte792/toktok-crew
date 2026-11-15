package com.crewguide.probe

// 2026-11-14 PM 결정 20: 확인 중(CHECKING) 화면이 다시 만들어져도(야간·주간 전환, 구성 변경) 확인을 이어 간다.
// 예전에는 확인 흐름(BoardingState·SeatCheckState)이 Activity 필드라 재생성 때 사라져 홈으로 돌아갔다.
// 순수 규칙: 무엇을 남기고, 새 화면에서 언제 이어 갈지. 보관은 프로세스 메모리([CheckSessionStore], 저장소에 쓰지 않음).
//  - 남김: 확인 단계(AUTO_CHECK)이고 아직 완료하지 않았을 때만. 배정·보호자 확인·재안내 기록·환영 세대를 그대로 둔다.
//  - 이어 가기: 새 화면의 흐름이 홈(아직 아무것도 시작 안 함)이고, 정차 허용 게이트가 다시 열렸을 때.
//    재생성 직후에는 기어를 다시 받기 전이라 잠금이므로 [RESUME_WINDOW_MS] 안에서 기다린다. 창이 지나면 버린다(오래된 확인을 되살리지 않음).
//  - 완료 판정의 유지 시각(1.5초)은 처음부터 다시 잰다.

data class CheckSnapshot(
    val flow: BoardingState,
    val seatCheck: SeatCheckState,
    /** 환영 인사를 한 세대(다시 말하지 않게) */
    val welcomeGen: Int,
    val savedAtMs: Long,
)

enum class ResumeDecision { WAIT, RESUME, DROP, NONE }

object CheckResumeRule {
    const val RESUME_WINDOW_MS = 20_000L

    /** 재생성 직전 남길 스냅샷(남길 것이 없으면 null) */
    fun snapshotOf(flow: BoardingState, seatCheck: SeatCheckState, welcomeGen: Int, nowMs: Long): CheckSnapshot? =
        if (flow.step == BoardingStep.AUTO_CHECK && !seatCheck.completed) CheckSnapshot(flow, seatCheck, welcomeGen, nowMs) else null

    fun decide(snap: CheckSnapshot?, nowMs: Long, currentStep: BoardingStep, parkAllowed: Boolean): ResumeDecision = when {
        snap == null -> ResumeDecision.NONE
        currentStep != BoardingStep.HOME -> ResumeDecision.DROP          // 새 화면에서 이미 다른 흐름이 시작됨
        nowMs - snap.savedAtMs > RESUME_WINDOW_MS || nowMs < snap.savedAtMs -> ResumeDecision.DROP
        !parkAllowed -> ResumeDecision.WAIT
        else -> ResumeDecision.RESUME
    }

    /** 이어 갈 확인 상태: 완료 유지 시각만 다시 잰다. PM 결정 23: 보관본에 앞줄 배정이 있으면 지운다(뒷줄로 옮기지 않음) */
    fun resumedSeatCheck(snap: CheckSnapshot): SeatCheckState =
        SeatCheckRule.dropFrontAssignments(snap.seatCheck).first.copy(completeSinceMs = -1L)
}

/** 프로세스 메모리 보관소(메인 스레드 전용). 프로세스가 끝나면 사라진다 */
object CheckSessionStore {
    var saved: CheckSnapshot? = null
}
