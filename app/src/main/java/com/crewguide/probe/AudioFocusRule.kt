package com.crewguide.probe

/** 오디오 포커스 요청 결과(Android 상수와 분리한 순수 값). */
enum class FocusRequestResult { GRANTED, DENIED, DELAYED, FAILED }

/** 포커스 변화 이벤트. */
enum class FocusChange { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

/** 요청 결과에 따른 발화 동작. 이번 버전은 WAIT를 쓰지 않는다(뒤늦게 얻은 발화는 하지 않음). */
enum class FocusSpeakAction { SPEAK, SKIP, WAIT }

/** 포커스 변화에 따른 진행 중 발화 동작. */
enum class FocusChangeAction { CONTINUE, STOP }

/** GRANTED→SPEAK, DENIED/FAILED/DELAYED→SKIP(DELAYED는 단순화로 건너뜀). */
fun focusSpeakAction(result: FocusRequestResult): FocusSpeakAction = when (result) {
    FocusRequestResult.GRANTED -> FocusSpeakAction.SPEAK
    FocusRequestResult.DENIED, FocusRequestResult.FAILED, FocusRequestResult.DELAYED -> FocusSpeakAction.SKIP
}

/** GAIN→CONTINUE, 상실 계열(CAN_DUCK 포함)→STOP(안내가 묻히지 않게 보수적으로 중지). */
fun focusChangeAction(change: FocusChange): FocusChangeAction = when (change) {
    FocusChange.GAIN -> FocusChangeAction.CONTINUE
    FocusChange.LOSS, FocusChange.LOSS_TRANSIENT, FocusChange.LOSS_TRANSIENT_CAN_DUCK -> FocusChangeAction.STOP
}
