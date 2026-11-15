package com.crewguide.probe

/**
 * 2026-11-14 루프 r5 1절: 도착하면 앱이 스스로 앞으로 온다(순수 규칙, Android 클래스 없음).
 *
 * 전에는 도착 + P에서 내비 화면 뒤로 "앱을 열어 하차를 확인해 주세요."만 말해, 사용자가 독 아이콘을 누르지 않으면
 * 두고 내림 안내·하차 마무리·스티커가 소리로만 지나갔다(사용자: "내가 직접 열어야 되니까… 스티커도 못 보고").
 * 이제 도착 신호를 받은 이동이 유효 P가 되면 앱이 도착 화면을 앞으로 띄운다. 이 객체는 '띄울지'만 정하고,
 * 실제 띄우기(AppFrontLauncher)·음성은 MainActivity가 한다.
 *
 * 규칙
 *  - 유효 P + 플랫폼 허용(호출자: lockReason()==null, 주행 아님)일 때만. D/R/N·기어 불명·단절·플랫폼 제한이면 절대 띄우지 않는다.
 *  - 이번 이동이 확인 완료 + 출발했고, 지금 주행 중이 아니며, 하차 마무리 전이어야 한다.
 *  - 한 번의 정차(주행 → 유효 P)마다 한 번만. 이미 처리한 정차면 아무것도 하지 않는다
 *    (보호자가 BACK으로 내비를 다시 앞에 둔 경우 같은 정차에서 다시 끌어오지 않는다). 다시 주행했다가 서면 새 정차다.
 *    정차의 경계는 주행 관측뿐이다([AppFrontStore.handledThisStop]은 주행을 보면 지운다). [TripGuideState.parkedSinceMs]는 기어 재조회·
 *    잠깐의 잠금마다 새로 찍혀(에뮬 확인: 앱이 앞으로 올 때 onStart 재조회) 정차 구분 열쇠로 쓰지 않는다.
 *  - 이미 앱이 앞(보임)이면 띄우지 않고 그 정차를 처리한 것으로만 남긴다([Action.MARK_VISIBLE]).
 *  - 도착 신호를 받은 이동: 도착 안내가 나간 뒤(arrivedSaid) → [Reason.ARRIVED].
 *  - 목적지 없이 출발한 이동(r5 추가 A, 예전 임시 결정 36 교체): "목적지에 도착했나요?"를 묻는 중([ArrivalAskRule.askArrived])이면
 *    → [Reason.ASK_ARRIVED](질문 화면으로 앞으로). 경로 시간 값이 있는데 도착 신호가 아직 없으면(목적지 가는 중 잠깐 P) 띄우지 않는다.
 */
object AppFrontRule {
    enum class Reason { ARRIVED, ASK_ARRIVED }
    enum class Action { NONE, MARK_VISIBLE, LAUNCH }

    data class Decision(val action: Action, val reason: Reason? = null)

    private val NONE = Decision(Action.NONE)

    /**
     * @param tg 이번 이동 상태(TripGuide)
     * @param parkOk 유효 P + 플랫폼 허용(잠금 사유 없음)이고 주행 기어가 아님. 화면 전경 여부는 보지 않는다
     * @param visible 앱이 화면에 보이는가(onStart~onStop)
     * @param handledThisStop 이번 정차(마지막 주행 뒤)에서 이미 띄웠거나 보임으로 처리했는가
     * @param askPending 이 정차에서 도착 질문을 띄운 상태인가([ArrivalAskRule.askArrived])
     */
    fun decide(tg: TripGuideState, parkOk: Boolean, visible: Boolean, handledThisStop: Boolean, askPending: Boolean = false): Decision {
        if (!parkOk) return NONE
        if (!tg.confirmed || !tg.departed || tg.drivingNow || tg.allLeftSaid) return NONE
        if (tg.parkedSinceMs < 0 || handledThisStop) return NONE
        val reason = when {
            tg.arrivalSignal -> if (tg.arrivedSaid) Reason.ARRIVED else return NONE
            askPending -> Reason.ASK_ARRIVED
            else -> return NONE
        }
        return Decision(if (visible) Action.MARK_VISIBLE else Action.LAUNCH, reason)
    }

    /** 내비 경로 시간 값(실제·모의)이 있는가 = 목적지를 두고 가는 이동 */
    fun hasRoute(tg: TripGuideState): Boolean = tg.totalSec != null || tg.remainSec != null

    /**
     * 띄우기를 시도한 뒤 이 시간 안에 앱이 보이지 않으면(Android 14 백그라운드 화면 시작 제한 등) 대체 알림을 낸다.
     * 대체 알림은 기존 이동 알림(FGS 알림, 누르면 앱이 열림)의 제목을 바꾸는 것이다.
     */
    const val VERIFY_MS = 2_000L
}

/** 프로세스 보관소(Activity 재생성에도 '이 정차는 처리함' 유지). 메인 스레드 전용 */
object AppFrontStore {
    /** 이번 정차(마지막 주행 뒤)에서 이미 처리했는가. 주행을 보면 false(호출자) */
    var handledThisStop = false
    /** 마지막으로 앞으로 올리기를 시도한 이유(앱이 보이면 비움, 로그용) */
    var pending: AppFrontRule.Reason? = null
    /** 이번 띄우기에서 두 번째 방법(AppFrontLauncher.RETRY)을 이미 썼는가 */
    var retried = false
}
