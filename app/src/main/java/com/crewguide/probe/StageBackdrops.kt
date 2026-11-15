package com.crewguide.probe

// 시안 2: 장면마다 다른 무대 배경색(순수 로직, Android 클래스 금지). 값은 0xRRGGBB(알파 없음).
// 롤백: backdropOf가 항상 PICK 값(낮 8EC8FA→D8EEFB, 밤 1A2230→121212)을 돌려주게 하면 이전 화면과 같다.

/** 배경 그라데이션 양 끝색. horizontal이면 왼쪽(top)→오른쪽(bottom), 아니면 위(top)→아래(bottom) */
data class Backdrop(val topRgb: Int, val bottomRgb: Int, val horizontal: Boolean)

object StageBackdropColors {
    // 낮: 질문 계열 연보라 → 분홍 (가로)
    const val DAY_QUESTION_LEFT = 0xD4C0E8
    const val DAY_QUESTION_RIGHT = 0xF9A29E
    // 낮: 이동 끝 계열 산호 단색. 근거: 점(0D47A1) 대비가 가장 낮은 곳이 위쪽 띠인데 단색이든 약한 그라데이션이든
    // 최악값(산호 F97B66 위 3.31)이 같고, 단색이 시안 2와 같으며 장면 안 색 변화가 없어 단순하다
    const val DAY_CORAL = 0xF97B66
    // 2026-11-11 U4: 이동 끝·하차 확인은 경고처럼 보이던 산호 대신 차분한 저녁 하늘(위 연한 남청 → 아래 연한 살구). 잠금(LOCKED)은 산호 유지
    const val DAY_EVENING_TOP = 0xC9D6F2
    const val DAY_EVENING_BOTTOM = 0xF3E4D7
    // 밤: 질문 계열 어두운 보라 가로, 이동 끝 계열 어두운 산호/갈색 세로
    const val NIGHT_QUESTION_LEFT = 0x2A2140
    const val NIGHT_QUESTION_RIGHT = 0x3A2233
    const val NIGHT_TRIP_TOP = 0x4A2A26
    const val NIGHT_TRIP_BOTTOM = 0x2E1B19
}

/** 장면·낮밤별 배경. when에 else가 없어 Scene 값이 늘면 컴파일러가 누락을 알려 준다 */
fun backdropOf(scene: Scene, night: Boolean): Backdrop = if (night) nightBackdropOf(scene) else dayBackdropOf(scene)

// 2026-11-13: 자동 확인(AUTO_CHECK)·출발 준비(DEPART_READY)는 보호자에게 묻는 장면이 아니므로 질문색(보라·분홍) 대신 고르기와 같은 하늘색.
// 왼쪽 카드(흰 바탕)와 오른쪽 무대 패널이 S02와 같은 배경 위에 놓여 화면 전환이 덜 튄다. 도착·모두 내림은 저녁 하늘 유지
private fun dayBackdropOf(scene: Scene): Backdrop = when (scene) {
    Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.AUTO_CHECK, Scene.DEPART_READY ->
        Backdrop(Palette.STAGE_BG_TOP, Palette.STAGE_BG_BOTTOM, false)
    Scene.QUESTION, Scene.YES, Scene.WAIT_GUARDIAN ->
        Backdrop(StageBackdropColors.DAY_QUESTION_LEFT, StageBackdropColors.DAY_QUESTION_RIGHT, true)
    Scene.TRIP_END, Scene.EXIT_CHECK, Scene.ARRIVED, Scene.ALL_LEFT ->
        Backdrop(StageBackdropColors.DAY_EVENING_TOP, StageBackdropColors.DAY_EVENING_BOTTOM, false)
    Scene.LOCKED ->
        Backdrop(StageBackdropColors.DAY_CORAL, StageBackdropColors.DAY_CORAL, false)
}

private fun nightBackdropOf(scene: Scene): Backdrop = when (scene) {
    Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.LOCKED, Scene.AUTO_CHECK, Scene.DEPART_READY ->
        Backdrop(Palette.NIGHT_STAGE_BG_TOP, Palette.NIGHT_STAGE_BG_BOTTOM, false)
    Scene.QUESTION, Scene.YES, Scene.WAIT_GUARDIAN ->
        Backdrop(StageBackdropColors.NIGHT_QUESTION_LEFT, StageBackdropColors.NIGHT_QUESTION_RIGHT, true)
    Scene.TRIP_END, Scene.EXIT_CHECK, Scene.ARRIVED, Scene.ALL_LEFT ->
        Backdrop(StageBackdropColors.NIGHT_TRIP_TOP, StageBackdropColors.NIGHT_TRIP_BOTTOM, false)
}

/**
 * 하단 보조 라벨 띠 색(0xRRGGBB). 세로 배경은 아래쪽 색(= 띠가 놓이는 곳의 배경색, PICK 값은 이전 bgBottom과 같다),
 * 가로 배경은 글자가 가운데에 있으므로 양 끝색의 채널 평균(spotRgbOf와 같은 규칙).
 */
fun bandRgbOf(b: Backdrop): Int {
    if (!b.horizontal) return b.bottomRgb and 0xFFFFFF
    return spotRgbOf(b)
}

/**
 * 스포트라이트(캐릭터 뒤 원형 빛) 기준색. 세로 배경은 기존처럼 위쪽 색(PICK 값 불변),
 * 가로 배경은 양 끝색의 채널 평균(정수 나눗셈)이라 파란 빛이 보라·분홍 위에 뜨지 않는다.
 */
fun spotRgbOf(b: Backdrop): Int {
    if (!b.horizontal) return b.topRgb and 0xFFFFFF
    fun avg(shift: Int): Int = ((((b.topRgb shr shift) and 0xFF) + ((b.bottomRgb shr shift) and 0xFF)) / 2)
    return (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
}
