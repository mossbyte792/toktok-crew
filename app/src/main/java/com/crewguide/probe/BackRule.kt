package com.crewguide.probe

/**
 * 2026-11-14 PM 임시 결정 32: 시스템 뒤로가기 판단(순수 규칙). MainActivity.onBackPressed가 현재 화면 상태를 넣고 결과대로 실행한다.
 *
 * 순서: 대화상자 → 별명 입력(키보드) → 처음 안내(이전 단계, 다시 보기 1/3이면 닫고 설정으로) → 설정 닫기 → 확인 중 [처음으로](결정 25)
 * → 그 밖: 이동 중(확인 완료 뒤 하차 마무리 전, 이동 FGS가 살아 있는 동안)이면 내비 앱을 앞으로(TO_NAVI), 아니면 아무것도 안 함(NONE).
 * finish하지 않는다(이동 중 음성·FGS 유지). 주행 잠금(S09) 중에도 화면 조작은 없고, 이동 중이면 내비만 앞으로 낸다.
 *
 * 루프 r2 A1(임시 결정, 사용자 확인 대기): Pleos 실기(18:55)에서 홈 Intent·moveTaskToBack 모두 효과가 없었다(앱이 계속 topResumed).
 * 그래서 '앱을 뒤로 보내기'를 없애고 이동 중에는 확인 완료 때 쓰는 내비 전환을, 그 밖(홈 등)에서는 동작 없음으로 바꿨다(앱 나가기는 설정).
 * 루프 r1 L1: 출발 준비(확인 완료)에서 BACK은 이동을 접지 않는다(이동 취소는 화면의 [처음으로]로만). LEAVE_READY는 남겨 두되 BACK에서는 쓰지 않는다.
 */
object BackRule {
    enum class Action { CLOSE_DIALOG, CLOSE_INPUT, FIRST_RUN_PREV, FIRST_RUN_CLOSE, CLOSE_SETTINGS, CANCEL_CHECK, LEAVE_READY, TO_NAVI, NONE }

    data class Input(
        /** 잠금 사유가 있음(S09) */
        val locked: Boolean,
        /** 앱 확인 대화상자(앱 나가기·알림 안내·이동 끝내기)가 열림. 보통은 대화상자가 뒤로가기를 먼저 받는다(안전망) */
        val dialogOpen: Boolean = false,
        /** 설정의 별명 입력칸이 열림 */
        val inputOpen: Boolean = false,
        /** 처음 안내 오버레이가 보임 */
        val firstRunVisible: Boolean = false,
        /** 처음 안내 단계(0 = 1/3) */
        val firstRunStep: Int = 0,
        /** 설정 [처음 안내 다시 보기]로 연 안내 */
        val firstRunReplay: Boolean = false,
        val settingsOpen: Boolean = false,
        /** 확인 중(AutoPhase.CHECKING) 좌석 화면이 보임 */
        val checking: Boolean = false,
        /** 출발 준비(AutoPhase.CONFIRMED, DEPART_READY 장면)가 보임 */
        val departReady: Boolean = false,
        /** 이동 중(확인 완료 뒤 하차 마무리 전 = 이동 FGS 조건, TripKeepAliveRule.tripActive) */
        val tripActive: Boolean = false,
    )

    fun decide(i: Input): Action = when {
        i.locked -> leave(i)
        i.dialogOpen -> Action.CLOSE_DIALOG
        i.settingsOpen && i.inputOpen -> Action.CLOSE_INPUT
        i.firstRunVisible && i.firstRunStep > 0 -> Action.FIRST_RUN_PREV
        i.firstRunVisible && i.firstRunReplay -> Action.FIRST_RUN_CLOSE
        i.firstRunVisible -> Action.NONE   // 첫 실행 1/3: 앞으로만 간다
        i.settingsOpen -> Action.CLOSE_SETTINGS
        i.checking -> Action.CANCEL_CHECK
        else -> leave(i)   // 출발 준비(L1)·주행 중 복귀·도착·모두 내림·홈
    }

    private fun leave(i: Input): Action = if (i.tripActive) Action.TO_NAVI else Action.NONE

    /** 로그 문구(동작 없음) */
    const val LOG_NONE = "[뒤로] 홈: 동작 없음(앱 나가기는 설정)"
}
