package com.crewguide.probe

/** 단계 점 상태 결정(순수 로직, Android 클래스 금지) */
enum class DotState { DONE, CURRENT, TODO }

/** 점 5개의 상태. current -1(잠금)이면 모두 TODO, 0..4면 앞쪽 DONE·current CURRENT·뒤쪽 TODO */
fun dotStates(current: Int): List<DotState> = List(StageSteps.NAMES.size) { i ->
    when {
        current < 0 -> DotState.TODO
        i < current -> DotState.DONE
        i == current -> DotState.CURRENT
        else -> DotState.TODO
    }
}

/** 점 index와 index+1 사이 연결선이 채워지는지(현재 점까지). current -1이면 항상 false */
fun lineFilled(index: Int, current: Int): Boolean = current >= 0 && index < current
