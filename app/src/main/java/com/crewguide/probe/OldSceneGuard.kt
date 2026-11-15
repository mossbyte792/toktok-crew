package com.crewguide.probe

/**
 * 2026-11-14 루프 r2 A2·A3: 새 무대(자동 흐름)에서는 옛 흐름 장면이 사용자에게 보이면 안 된다(순수 규칙).
 * 옛 장면 = 자동 흐름이 쓰지 않는 Scene(아이·자리 고르기 PICK·READY, 보호자 확인 ANNOUNCE·QUESTION·YES·WAIT_GUARDIAN,
 * 이동 끝 TRIP_END, 옛 하차 확인 한 줄 장면 EXIT_CHECK). 코드는 지우지 않고 닿지 않게만 한다:
 * 무대가 이 장면을 고르면 홈으로 덮는다(MainActivity.syncHome, 로그 `[옛장면]`). 하차 한 줄 음성 자체는 그대로 나간다(화면만 홈).
 * 설정·꾸미기가 열려 있으면 그쪽이 위라 덮지 않는다. debug 시연(시나리오) 중에는 덮지 않는다(release에는 없음).
 */
object OldSceneGuard {
    val OLD: Set<Scene> = setOf(Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.QUESTION, Scene.YES, Scene.WAIT_GUARDIAN,
        Scene.TRIP_END, Scene.EXIT_CHECK)

    fun isOld(scene: Scene): Boolean = scene in OLD

    /** 홈으로 덮을지. 잠금(LOCKED)·자동 흐름 장면(AUTO_CHECK·DEPART_READY·ARRIVED·ALL_LEFT)은 덮지 않는다 */
    fun coverWithHome(newStage: Boolean, scene: Scene, settingsOpen: Boolean, customizeOpen: Boolean, debugScenario: Boolean): Boolean =
        newStage && isOld(scene) && !settingsOpen && !customizeOpen && !debugScenario
}
