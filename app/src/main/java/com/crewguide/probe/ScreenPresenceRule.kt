package com.crewguide.probe

/**
 * 2026-11-15 루프 r6a 2절(결함 P2): 다중 창(계기판·내비·홈 영역이 같이 보이는 Pleos)에서 앱 화면이 **보이는데 포커스는 다른 창**이면
 * Activity는 onPause 상태(resumed=false)로 남는다(QA: top resumed = ai.umos.drivingview, 앱을 한 번 누르면 onResume).
 * 예전에는 화면 표시 판단까지 정차 게이트의 전경 조건(resumed)을 써서, 하차 마무리 뒤 뒷줄에 다시 앉아도 [새로 출발 확인]이
 * 앱을 누를 때까지 뜨지 않았고(ReseatRule.reseated), 하차 한 줄(정차 허용 게이트)도 같은 이유로 막혔다.
 *
 * 규칙(순수)
 *  - 화면 표시·음성 판단의 '전경' = 화면에 보임(onStart~onStop). 포커스(resumed)가 없어도 된다 → [displayForeground].
 *    누름 처리(조작 허용)는 지금처럼 resumed 기준 게이트(tripParkAllowed)를 쓴다. 누르면 그 창이 포커스를 얻어 onResume이 먼저 온다.
 *  - onResume 때 기어·플랫폼 값을 '오래된 값'으로 볼지([staleOnResume]): onStop을 거친 복귀만. 보이는 채로 포커스만 돌아온 경우는
 *    리스너가 값을 계속 받았으므로 다시 조회하지 않는다(예전에는 이때 잠깐 '기어 확인 중' 잠금이 되어, 포커스를 되찾는 첫 누름이 무시됐다).
 *  - 도착 화면·도착 질문·두고 내림·앱을 앞으로 올리기는 원래 전경 조건 없이 잠금 사유(lockReason)만 본다(바꿀 것 없음).
 */
object ScreenPresenceRule {
    /** 화면 표시·음성 판단에 쓰는 전경: 보이면(started) 참. resumed는 started의 부분 집합이지만 순서가 꼬인 콜백에 대비해 둘 다 본다 */
    fun displayForeground(started: Boolean, resumed: Boolean): Boolean = started || resumed

    /**
     * r6a QA 후속(2026-11-15): 화면에 보이는 버튼을 사용자가 직접 누른 경우의 전경. 보이면 참(포커스·resumed 불필요).
     * QA: 계기판에 포커스가 있을 때 [새로 출발 확인] 첫 누름이 2/4회 "시작 무시(유효 P·허용 아님)"(누르는 순간 창이 포커스를
     * 막 받는 중이라 resumed가 아직 거짓, 가정). 누름 자체가 사용자 의도이고 버튼은 보일 때만 누를 수 있으므로 보임으로 충분하다.
     * 잠금 사유(기어 불명·D·단절·플랫폼 제한)는 정차 게이트가 그대로 막는다.
     */
    fun tapForeground(started: Boolean, resumed: Boolean): Boolean = started || resumed

    /** 수명 이벤트 */
    enum class Life { PAUSE, STOP }

    /**
     * r6 QA 후속(2026-11-15): '화면 이탈 → 확인 취소'를 할 수명 이벤트인가. 실제로 안 보이게 된 onStop만.
     * onPause만 잠깐 왔다가 onResume으로 돌아오는 경우(같은 singleTask 화면에 인텐트 재전송, 다중 창 포커스 이동)는 이탈이 아니다.
     * QA: 빠른 시작 확인 중 debug 부팅 시험이 PendingIntent로 띄우기를 다시 보냄 → onPause → "화면 이탈 → 보호자 확인 취소"
     */
    fun leftScreenOn(e: Life): Boolean = e == Life.STOP

    /** onResume에서 값을 오래된 것으로 다시 받을지: 지난 onResume 뒤 onStop이 있었을 때만 */
    fun staleOnResume(stoppedSinceLastResume: Boolean): Boolean = stoppedSinceLastResume
}

/** r6 QA 후속: 앱 화면이 지금 보이는가(MainActivity onStart~onStop, 프로세스 값). 수신기 등 Activity 밖에서 '이미 앞'을 알 때 쓴다 */
object AppVisibleStore {
    @Volatile var visible = false
}
