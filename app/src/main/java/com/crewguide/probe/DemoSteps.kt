package com.crewguide.probe

/**
 * 시연 단계 안내(가안)의 정적 데이터. Android 클래스를 쓰지 않는 순수 데이터다.
 * 상태 연동·저장·음성은 없다. 문구는 해요체이고 단정·보장 표현을 쓰지 않는다.
 */
object DemoSteps {
    const val SECTION_TITLE = "시연 단계 안내(가안)"
    const val LABEL_DIRECT = "앱에서 직접 해 볼 수 있어요"
    const val LABEL_VEHICLE = "차량 신호가 필요해요(모의 입력으로 대신할 수 있어요)"
    const val CAUTION = "뒷좌석 벨트 신호는 참고용이에요."

    data class Step(val number: Int, val title: String, val description: String, val label: String)

    val steps: List<Step> = listOf(
        Step(1, "아이 고르기", "화면에서 오늘 탈 아이를 골라요. 실제 화면에서 해 볼 수 있어요.", LABEL_DIRECT),
        Step(2, "보호자 확인", "보호자에게 물어보는 흐름이에요. 화면은 그대로 해 볼 수 있고, 음성은 환경에 따라 달라요.", LABEL_DIRECT),
        Step(3, "'예' 후 아이 안내", "'예'를 누르면 아이에게 보여 주는 안내 화면이 나와요. 소리는 아직 확인 전이에요.", LABEL_DIRECT),
        Step(4, "주행 중 잠금", "차가 움직이는 신호가 오면 화면이 잠겨요. 실차 신호나 모의 입력이 필요해요.", LABEL_VEHICLE),
        // 2026-11-14 사용자 지시: 내비 신호로 "이동이 끝난 것 같아요"라고 알리던 안내는 없앴다 → 지금 동작으로 고침
        // 2026-11-15 r7 4절: 1절(스티커는 하차 안내 때)·2절(모두 내려야 끝, [다 내렸어요])·3절(빠른 시작 대기 화면)에 맞춤
        // r10d: 조수석(출발 전 확인 때 앉아 있던 사람)도 마무리 대상, 운전석 제외
        Step(5, "이동 끝", "배정한 아이와 조수석에 앉았던 사람이 모두 내리면(문·착석 신호 기준, 운전석 제외) \"모두 내린 것 같아요\"로 마무리해요. 문이 열린 적이 없으면 하차 화면의 [다 내렸어요]로 끝내요(보호자 확인).", LABEL_VEHICLE),
        Step(6, "내릴 때 뒷좌석 한 번 더 확인", "하차 안내가 나올 때 참여 스티커를 주고, 뒷좌석을 한 번 더 보라고 음성으로 알려 줘요.", LABEL_VEHICLE),
        // r8 6절: 빠른 시작 조건(지난 이동이 끝난 지 60분 안)에 맞춤
        Step(7, "빠른 시작", "지난 이동이 끝난 지 60분 안에 앱을 다시 열면 홈 대신 ‘차량 신호를 확인하고 있어요’를 보이고 바로 좌석 확인을 해요(15초 안에 신호가 없으면 홈).", LABEL_VEHICLE),
    )

    /** 화면과 테스트가 함께 쓰는 전체 문구 목록. */
    fun allTexts(): List<String> =
        listOf(SECTION_TITLE, CAUTION) + steps.flatMap { listOf(it.title, it.description, it.label) }
}
