package com.crewguide.probe

/**
 * 2026-11-14 루프 r5 추가 A(사용자 지시, 임시 결정 36 교체): 목적지 없이 출발한 이동이 유효 P가 되면 "목적지에 도착했나요?"를 묻는다(순수 규칙).
 * 화면 상태는 PM 계약 v5 [AutoCheckUi.askArrived]·[AutoCheckUi.arrivedByAnswer], 대답은 [StageActions.onArrivalAnswer](버튼·음성 공통).
 *
 * 규칙
 *  - 묻는 조건: 확인·출발한 이동 + 주행 아님 + 도착 신호 없음 + 내비 경로 시간 값 없음(목적지 없음, [AppFrontRule.hasRoute]) + 하차 마무리 전
 *    + 유효 P·플랫폼 허용(호출자 parkOk) + 이번 정차에서 아직 묻지 않음. 앱은 앞으로 오고(AppFrontRule) 화면·음성으로 묻는다.
 *    정차의 경계는 주행 관측뿐이다(주행을 보면 상태를 비운다). parkedSinceMs는 기어 재조회·잠깐의 잠금마다 새로 찍혀 쓰지 않는다
 *    (에뮬 확인: 앱이 앞으로 올 때 onStart 재조회로 두 번 물었다).
 *  - 정차 한 번에 한 번만 묻는다. 대답 없이 P가 이어지면 다시 묻지 않고 화면에 질문만 남긴다. 다시 달렸다가 P가 되면 또 묻는다. 주행 중에는 묻지 않는다.
 *  - 예: 도착 흐름(TripGuide.onArrived → 도착 화면·도착 음성·하차 흐름). 도착 화면에 [아니요, 잠깐 멈췄어요]를 남겨 되돌릴 길을 둔다.
 *  - 아니요(또는 '예' 뒤 되돌리기): 이동을 그대로 잇는다. 이번 정차에서 도착 안내·하차 한 줄·두고 내림 안내를 하지 않고 내비를 다시 앞으로.
 *  - 대답 없이 D로 바꾸면 아니요로 본다.
 *  - 목적지 없는 이동의 하차 한 줄(ExitCheckRule)은 이 질문이 대신한다([replacesExitLine]): '예'면 도착 음성에 뒷좌석 확인 문장이 들어 있고,
 *    '아니요'면 내지 않으며, 대답이 없으면 두고 내림 안내(60초)가 안전망이다(아래 결정 36-1(사용자 확정(11-15))).
 *  - **결정 36-1(2026-11-14 제안, 사용자 확정(11-15))**: 대답이 없는 동안 두고 내림 안내(TripAlerts 60초·120초)는 그대로 낸다(안전 쪽).
 *    하차 한 줄은 질문과 같은 순간이라 겹치지 않게 내지 않는다(위). 되돌리려면 [LEFT_BEHIND_WHILE_UNANSWERED]를 false로
 *    (대답 전까지 두고 내림 안내도 미룬다).
 *
 * 2026-11-15 루프 r6 5절(사용자 지시, PM 계약 v6 askAlight·askAlightText·alightingByAnswer·onAlightAnswer): "내릴까요?"
 *  - 목적지 있는 이동의 중간 정차: 출발한 이동 + 도착 신호 없음 + 내비 길 안내 중(결정 41 조회 LIVE, 호출자 routeLive) + 유효 P
 *    → 정차당 1회 "잠시 정차해요. 내릴까요?"(Kind.ALIGHT). 경로 조회 답을 기다리는 동안은 묻지 않는다.
 *  - 목적지 없는 이동에서 "목적지에 도착했나요?"에 아니요 → 바로 이어서 "내릴까요?"(Effect.ALIGHT_AFTER_NO). 첫 질문에 대답이 없으면 묻지 않는다.
 *  - "내릴까요?" 예 → 그 자리에서 이동 마무리(중간 하차, alightingByAnswer): 하차 안내 음성 → 아이가 내리면 하차 마무리·스티커·작별 인사 → 이동 끝.
 *    도착이라 말하지 않고 도착 신호도 만들지 않는다(TripGuide는 그대로). 내비 경로는 건드리지 않는다. 끝난 방식은 TripFinishStore.
 *  - "내릴까요?" 아니요 → r5 '아니요'와 같다(이동 계속, pausedByAnswer, 하차 한 줄·두고 내림 없음, 내비 앞으로). 대답 없이 D = 아니요.
 *  - 하차 한 줄은 도착 신호 없는 모든 정차에서 질문이 대신한다(replacesExitLine, B-1에서 본 '도착 전 P → 하차 한 줄' 동작을 바꿈).
 */
object ArrivalAskRule {
    /** 결정 36-1(사용자 확정(11-15)): 대답이 없는 동안 두고 내림 안내를 낸다. false = 대답 전까지 미룸 */
    const val LEFT_BEHIND_WHILE_UNANSWERED = true

    enum class Answer { YES, NO }

    /** 이번 정차의 첫 질문: 목적지 없는 이동 = 도착했나요? / 길 안내 중 이동 = 내릴까요? */
    enum class Kind { ARRIVED, ALIGHT }

    data class State(
        /** 이번 정차(마지막 주행 뒤)에서 물었는가 */
        val asked: Boolean = false,
        /** 이 정차의 "목적지에 도착했나요?" 대답(null = 아직 없음) */
        val answer: Answer? = null,
        /** '예'로 도착 흐름에 들어왔는가(되돌리기 가능 표시용) */
        val byAnswer: Boolean = false,
        /** r6: 이번 정차의 첫 질문 종류 */
        val kind: Kind = Kind.ARRIVED,
        /** r6: "내릴까요?"를 물었는가(첫 질문이거나 '도착했나요?' 아니요 뒤) */
        val alightAsked: Boolean = false,
        /** r6: "내릴까요?" 문장(계약 askAlightText) */
        val alightText: String = "",
        /** r6: "내릴까요?" 대답 */
        val alightAnswer: Answer? = null,
    )

    enum class Effect {
        NONE,
        /** 새로 묻는다: "목적지에 도착했나요?"(앱을 앞으로는 AppFrontRule) */
        ASK,
        /** r6: 새로 묻는다(길 안내 중 이동의 중간 정차): "잠시 정차해요. 내릴까요?" */
        ASK_ALIGHT,
        /** 대답 없이 주행 → 아니요로 봄(로그만) */
        DROVE_OFF,
        /** 예: 도착 흐름으로(호출자가 TripGuide.onArrived) */
        ARRIVE,
        /** 아니요(최종): 이동을 잇고 내비를 앞으로 */
        CONTINUE,
        /** '예' 뒤 아니요: 도착 상태를 되돌리고(호출자 [undoArrival]) 이동을 잇고 내비를 앞으로 */
        UNDO_ARRIVAL,
        /** r6: "도착했나요?" 아니요 → 바로 "내릴까요?"(앱은 앞에 그대로) */
        ALIGHT_AFTER_NO,
        /** r6: "내릴까요?" 예 → 중간 하차(하차 안내 음성, 이동 마무리 흐름) */
        ALIGHT,
    }

    data class Step(val state: State, val effect: Effect)

    /** 도착 신호 없이 서는 정차를 다룰 이동인가: 확인·출발, 도착 신호 없음, 하차 마무리 전 */
    fun stopTrip(tg: TripGuideState): Boolean = tg.confirmed && tg.departed && !tg.arrivalSignal && !tg.allLeftSaid

    /** 묻는 대상 이동인가(정차·게이트 조건 제외): 확인·출발, 도착 신호 없음, 목적지(경로 시간) 없음, 하차 마무리 전 */
    fun noDestTrip(tg: TripGuideState): Boolean = stopTrip(tg) && !AppFrontRule.hasRoute(tg)

    /** 도착 신호 없는 정차의 하차 한 줄은 질문("도착했나요?"·"내릴까요?")이 대신한다. 도착 신호 이동은 원래대로 도착 안내에 합쳐진다 */
    fun replacesExitLine(tg: TripGuideState): Boolean = stopTrip(tg)

    /**
     * 기어·게이트·도착 신호·경로 상태 답 뒤 평가(멱등).
     * [routeLive] = 결정 41 이번 정차 판정(LIVE면 true. GONE이면 호출자가 시간 값을 이미 지워 목적지 없는 이동이 된다. 기다리는 중·모름이면 null)
     */
    fun evaluate(st: State, tg: TripGuideState, parkOk: Boolean, routeLive: Boolean? = null): Step {
        if (tg.drivingNow) {
            // 정차가 끝났다 → 상태를 비운다(다음 P는 새 정차). 대답 없이 D = 아니요(로그용 효과)
            val drove = st.asked && ((st.kind == Kind.ARRIVED && st.answer == null) || (st.alightAsked && st.alightAnswer == null))
            return Step(State(), if (drove) Effect.DROVE_OFF else Effect.NONE)
        }
        if (!parkOk || !stopTrip(tg) || tg.parkedSinceMs < 0 || st.asked) return Step(st, Effect.NONE)
        if (!AppFrontRule.hasRoute(tg)) return Step(State(asked = true), Effect.ASK)
        if (routeLive == true) {
            return Step(State(asked = true, kind = Kind.ALIGHT, alightAsked = true, alightText = ArrivalAskText.ALIGHT_STOPOVER_Q), Effect.ASK_ALIGHT)
        }
        return Step(st, Effect.NONE)   // 길 안내 상태를 아직 모름: 답을 기다린다
    }

    /** 계약 v5 askArrived: 이 정차에서 "도착했나요?"를 물었고 대답이 없으며, 지금도 유효 P·주행 아님·목적지 없는 이동 */
    fun askArrived(st: State, tg: TripGuideState, parkOk: Boolean): Boolean =
        parkOk && !tg.drivingNow && st.asked && st.kind == Kind.ARRIVED && st.answer == null && tg.parkedSinceMs >= 0 && noDestTrip(tg)

    /** 계약 v6 askAlight: "내릴까요?"를 물었고 대답이 없으며, 지금도 유효 P·주행 아님·도착 신호 없음 */
    fun askAlight(st: State, tg: TripGuideState, parkOk: Boolean): Boolean =
        parkOk && !tg.drivingNow && st.asked && st.alightAsked && st.alightAnswer == null && tg.parkedSinceMs >= 0 && stopTrip(tg)

    /** 이번 정차의 최종 대답이 아니요인가('도착했나요?' 아니요 뒤 '내릴까요?'를 물었으면 그 대답) */
    private fun finalNo(st: State): Boolean =
        if (st.alightAsked) st.alightAnswer == Answer.NO else st.answer == Answer.NO

    /**
     * r10 K: 이번 정차에서 물었고 최종 대답이 '아니요'(계속 가요 · 잠깐 멈췄어요 · '예' 뒤 되돌리기)인가. 이동 상태는 보지 않는다
     * (하차 마무리 뒤에도 같은 정차면 남아 있다 — 상태는 주행을 볼 때만 비운다, [evaluate])
     */
    fun answeredContinue(st: State): Boolean = st.asked && finalNo(st)

    /**
     * 계약 v5.1 pausedByAnswer: 이번 정차에서 최종 '아니요'(또는 '예' 뒤 되돌리기)로 답했고 아직 유효 P·주행 아님·도착 신호 없음·하차 마무리 전.
     * askArrived·askAlight·arrivedByAnswer·alightingByAnswer와 동시에 true가 되지 않는다
     */
    fun pausedByAnswer(st: State, tg: TripGuideState, parkOk: Boolean): Boolean =
        parkOk && !tg.drivingNow && st.asked && finalNo(st) && tg.parkedSinceMs >= 0 && stopTrip(tg)

    /** 계약 v5 arrivedByAnswer: '예'로 도착 흐름에 들어온 같은 정차, 하차 마무리 전, 주행 아님 */
    fun arrivedByAnswer(st: State, tg: TripGuideState): Boolean =
        st.byAnswer && st.answer == Answer.YES && tg.arrivalSignal && !tg.allLeftSaid && !tg.drivingNow

    /**
     * 계약 v6.2 alightingByAnswer: "내릴까요?"에 예 → 중간 하차 흐름 중. 하차 마무리(ALL_LEFT) 동안에도 true를 유지한다
     * (중간 하차로 끝난 이동의 칩·문구에 '도착'이 다시 보이지 않게). 이동이 끝나 새 이동(확인 전·출발 전)이 되거나 다시 주행하면 false
     */
    fun alightingByAnswer(st: State, tg: TripGuideState): Boolean =
        st.alightAnswer == Answer.YES && tg.confirmed && tg.departed && !tg.arrivalSignal && !tg.drivingNow

    /** "목적지에 도착했나요?" 대답(버튼·음성). 유효 P가 아니면 반영하지 않는다 */
    fun onAnswer(st: State, tg: TripGuideState, arrived: Boolean, parkOk: Boolean): Step {
        if (!parkOk) return Step(st, Effect.NONE)
        if (askArrived(st, tg, parkOk)) {
            return if (arrived) Step(st.copy(answer = Answer.YES, byAnswer = true), Effect.ARRIVE)
            else Step(st.copy(answer = Answer.NO, alightAsked = true, alightText = ArrivalAskText.ALIGHT_Q), Effect.ALIGHT_AFTER_NO)
        }
        if (!arrived && arrivedByAnswer(st, tg)) return Step(st.copy(answer = Answer.NO, byAnswer = false), Effect.UNDO_ARRIVAL)
        return Step(st, Effect.NONE)
    }

    /** r6 "내릴까요?" 대답(버튼·음성, 계약 v6 onAlightAnswer). 유효 P가 아니면 반영하지 않는다 */
    fun onAlightAnswer(st: State, tg: TripGuideState, alight: Boolean, parkOk: Boolean): Step {
        if (!parkOk || !askAlight(st, tg, parkOk)) return Step(st, Effect.NONE)
        return if (alight) Step(st.copy(alightAnswer = Answer.YES), Effect.ALIGHT)
        else Step(st.copy(alightAnswer = Answer.NO), Effect.CONTINUE)
    }

    /** '예' 뒤 되돌리기: 도착 신호·도착 안내 표시를 지운다(이동·정차 시각은 그대로) */
    fun undoArrival(tg: TripGuideState): TripGuideState = tg.copy(arrivalSignal = false, arrivedSaid = false)

    /** 이 정차에서 두고 내림 안내를 멈출지: 최종 '아니요'를 받았거나, (36-1을 끈 경우) 대답을 기다리는 중 */
    fun holdsLeftBehind(st: State, tg: TripGuideState, leftBehindWhileUnanswered: Boolean = LEFT_BEHIND_WHILE_UNANSWERED): Boolean {
        if (!st.asked || tg.drivingNow) return false
        val waiting = (st.kind == Kind.ARRIVED && st.answer == null) || (st.alightAsked && st.alightAnswer == null)
        return finalNo(st) || (!leftBehindWhileUnanswered && waiting)
    }
}

/** 질문 화면·음성 문구(신호 기준, 해요체) */
object ArrivalAskText {
    const val QUESTION = "목적지에 도착했나요?"
    const val SUB = "도착했으면 [예], 잠깐 멈춘 거면 [아니요]를 눌러 주세요"
    const val YES = "예, 도착했어요"
    const val NO = "아니요, 잠깐 멈췄어요"
    /** 계약 v5.1 pausedByAnswer 화면 제목·부제(도착이 아닌 정차 표현) */
    const val PAUSED_TITLE = "잠깐 멈췄어요"
    const val PAUSED_SUB = "다시 출발하면 이어서 안내해요."
    /** 자동 안내 큐 이름(질문 음성 완료 훅 구분) */
    const val VOICE_LABEL = "도착질문"

    // ---- r6 5절: "내릴까요?"(계약 v6) ----
    /** 길 안내 중 이동의 중간 정차 질문(화면 제목·음성) */
    const val ALIGHT_STOPOVER_Q = "잠시 정차해요. 내릴까요?"
    /** "도착했나요?" 아니요 뒤 이어 묻는 질문 */
    const val ALIGHT_Q = "내릴까요?"
    const val ALIGHT_SUB = "내리면 [예], 계속 가면 [아니요]를 눌러 주세요"
    const val ALIGHT_YES = "예, 내려요"
    const val ALIGHT_NO = "아니요, 계속 가요"
    /** "내릴까요?" 예 → 하차 안내 음성(도착이라 말하지 않는다) */
    const val ALIGHT_SPOKEN = TripGuide.TEXT_ALIGHT_SPOKEN
    /** 중간 하차 화면 제목 후보(도착을 쓰지 않는다). 화면은 r6 7절 디자인 F가 그린다 */
    const val ALIGHTING_TITLE = "여기서 내려요"
    /** 자동 안내 큐 이름 */
    const val ALIGHT_VOICE_LABEL = "내릴질문"
    const val ALIGHTING_VOICE_LABEL = "중간하차"
}

/** 프로세스 보관소(Activity 재생성에도 이번 정차의 질문·대답 유지). 메인 스레드 전용 */
object ArrivalAskStore {
    var state = ArrivalAskRule.State()
}

/**
 * r6 5절: 이동이 어떻게 끝났는가(개발 B 6절 '빠른 시작' 조건). 하차 마무리(모두 내림)·이동 끝내기 때 [TripFinishStore.last]에 남긴다.
 * 프로세스 메모리만(앱이 다시 시작되면 null).
 */
enum class TripFinishKind {
    /** 내비 도착 신호를 받은 도착 */
    ARRIVED_SIGNAL,
    /** "목적지에 도착했나요?"에 예 */
    ARRIVED_BY_ANSWER,
    /** "내릴까요?"에 예 → 중간 하차로 끝남 */
    ALIGHTED_MIDWAY,
    /** 그 밖(대답 없이 모두 내림, 보호자 끝내기 등) */
    OTHER,
}

object TripFinishRule {
    /** 끝나는 순간의 이동·질문 상태로 끝난 방식을 정한다 */
    fun kindOf(tg: TripGuideState, st: ArrivalAskRule.State): TripFinishKind = when {
        st.alightAnswer == ArrivalAskRule.Answer.YES && !tg.arrivalSignal -> TripFinishKind.ALIGHTED_MIDWAY
        tg.arrivalSignal && st.byAnswer -> TripFinishKind.ARRIVED_BY_ANSWER
        tg.arrivalSignal -> TripFinishKind.ARRIVED_SIGNAL
        else -> TripFinishKind.OTHER
    }
}

object TripFinishStore {
    /** 마지막으로 끝난 이동의 끝난 방식(없으면 null) */
    var last: TripFinishKind? = null
    /** 개발 B 6절용: 마지막 이동이 중간 하차("내릴까요?" 예)로 끝났는가 */
    fun endedByAlight(): Boolean = last == TripFinishKind.ALIGHTED_MIDWAY
}
