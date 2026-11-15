package com.crewguide.probe

// 홈 승무원 움직임(숨쉬기·첫 인사)의 순수 정책. Android 클래스를 쓰지 않는다.
// 2026-11-07 검토자 지시 5(작업 단위 3):
//  - 손 인사는 홈 첫 진입 1회로 유지한다.
//  - '움직임 기본 OFF'는 MainActivity 미연결 상태의 임시 조치다(최종 정책 아님).
//  - 최종 정책은 플랫폼 주행 제한(VehicleGate, 즉 유효한 P + 플랫폼 ALLOWED)과 접근성 설정(시스템 애니메이션)에 연결한다.
// 연결 방법(MainActivity 작업 단위, Codex 리뷰 후): 게이트가 바뀔 때마다 homeScreen.setMotionGate(gate)를 부른다.
// 접근성은 ValueAnimator.areAnimatorsEnabled()(개발자 옵션 animator 배율 0, 접근성 '애니메이션 삭제'가 이 값을 끈다)로 읽는다.

/** 홈에서 지금 할 움직임 */
enum class HomeMotion {
    /** 정지 포즈(숨쉬기·인사 없음) */
    STATIC,
    /** 숨쉬기만(인사는 이미 했음) */
    BREATH_ONLY,
    /** 첫 인사 1회 후 숨쉬기 */
    GREET_THEN_BREATH,
}

/** 정지인 이유. 화면·기록 점검용 */
enum class HomeMotionStop {
    /** 게이트가 아직 연결되지 않음(MainActivity 미연결). 임시 조치로 움직이지 않는다 */
    GATE_NOT_CONNECTED,
    /** 주행·플랫폼 제한·상태 불명 */
    NOT_PARK_ALLOWED,
    /** 시스템 애니메이션 꺼짐(접근성 포함) */
    ANIMATIONS_OFF,
    /** 화면이 보이지 않음 */
    HIDDEN,
}

data class HomeMotionDecision(val motion: HomeMotion, val stop: HomeMotionStop?)

object HomeMotionPolicy {
    /**
     * @param gate 연결 코드가 알려 준 차량·정책 게이트. null = 아직 연결 안 됨(임시: 움직이지 않음)
     * @param animatorsEnabled 시스템 애니메이션 허용(접근성 설정 반영)
     * @param visible 홈이 화면에 보이고 창에 붙어 있음
     * @param alreadyGreeted 이 홈 화면 수명 동안 첫 인사를 이미 했거나 시작했다가 끊겼음
     * 우선순위: 화면 없음 → 게이트 미연결 → 정차 허용 아님 → 애니메이션 꺼짐. 하나라도 걸리면 정지.
     */
    fun decide(gate: VehicleGate?, animatorsEnabled: Boolean, visible: Boolean, alreadyGreeted: Boolean): HomeMotionDecision = when {
        !visible -> HomeMotionDecision(HomeMotion.STATIC, HomeMotionStop.HIDDEN)
        gate == null -> HomeMotionDecision(HomeMotion.STATIC, HomeMotionStop.GATE_NOT_CONNECTED)
        gate != VehicleGate.PARK_ALLOWED -> HomeMotionDecision(HomeMotion.STATIC, HomeMotionStop.NOT_PARK_ALLOWED)
        !animatorsEnabled -> HomeMotionDecision(HomeMotion.STATIC, HomeMotionStop.ANIMATIONS_OFF)
        MascotWave.shouldGreet(alreadyGreeted, animatorsEnabled, motionAllowed = true) -> HomeMotionDecision(HomeMotion.GREET_THEN_BREATH, null)
        else -> HomeMotionDecision(HomeMotion.BREATH_ONLY, null)
    }
}
