package com.crewguide.probe

/**
 * 주행 벨트 감시 세션의 프로세스 단위 보관소(2026-11-08 Unit 5.3, ch DECISION 4: UI 수명주기와 분리).
 * 에뮬레이터에서 내비를 띄웠다가 앱으로 돌아오면 같은 프로세스에서 Activity가 다시 만들어졌다(onCreate 재실행).
 * Activity 객체에만 두면 그때 세션과 미전달 기록이 사라지므로, onDestroy에서 넘기고 새 Activity가 이어받는다.
 * - 메모리만(디스크 저장 안 함). 프로세스가 끝나면 사라진다 = 앱 종료 후에는 감시가 이어지지 않는다(한계, 화면에 감시 중이라고 표시하지 않음).
 * - Activity가 없는 동안에는 차량 리스너도 없으므로 그 구간은 '감시 끊김'으로 기록한다.
 * - 진행 중이던 경고 발화는 TTS가 해제되며 끊겼으므로 전달 완료가 아니다(실패 1회로 이어받는다).
 */
data class BeltSessionSnapshot(
    val state: BeltWarnState,
    val moved: Boolean,
    val undelivered: List<UndeliveredRecord>,
    val gap: String?,
    val savedAtMs: Long,
)

object BeltSessionStore {
    /** 이보다 오래 보관된 세션은 이어받지 않는다(재감사 2차: 오래된 감시 세션 부활 방지). 기록은 넘긴다 */
    const val MAX_AGE_MS = 10 * 60_000L
    private var saved: BeltSessionSnapshot? = null

    /** 보관 시간이 지났는가 */
    fun expired(s: BeltSessionSnapshot, nowMs: Long): Boolean = nowMs - s.savedAtMs > MAX_AGE_MS

    /** 넘길 것이 있을 때만 보관한다(세션 진행 중이거나 미전달·끊김 기록이 남은 경우) */
    fun save(s: BeltSessionSnapshot) {
        saved = if (s.state.active || s.undelivered.isNotEmpty() || s.gap != null) s else null
    }

    /** 한 번만 꺼낸다 */
    fun take(): BeltSessionSnapshot? { val s = saved; saved = null; return s }

    /** 이어받을 때의 상태: 진행 중 경고는 끊긴 것으로 보고 실패 1회(완료 아님) */
    fun resume(s: BeltSessionSnapshot, nowMs: Long): BeltWarnState =
        if (s.state.inFlight != null) BeltWarningPolicy.rejected(s.state, nowMs) else s.state
}
