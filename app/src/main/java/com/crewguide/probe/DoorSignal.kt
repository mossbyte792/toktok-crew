package com.crewguide.probe

/**
 * 2026-11-15 r6 4절(개발 D): 문 열림 신호 모델(순수, Android·SDK 클래스 없음).
 *
 * 읽는 신호(근거: Vehicle 2.3.2 aar javap, 에뮬·실차 미확인)
 * - `vehicle.getDoor()`(앱은 다른 API처럼 리플렉션 getter로 부름) →
 *   `checkDoorPositionCapabilities`(지원 문 영역 Map<DoorArea, AreaBounds<Int>>),
 *   `getCurrentDoorPosition(DoorArea, (Int?)->Unit, (Exception?)->Unit)`,
 *   `registerDoorPosition`/`unregisterDoorPosition(DoorArea, DoorPositionListener)`(리스너 `onPositionUpdated(Integer)`).
 *   내부 속성은 `PleosPropertyIds.DOOR_POS`. 읽기 메서드 권한 `pleos.car.permission.CAR_DOORS`(@RequiresPermission).
 *   쓰기(setDoorPosition·setDoorLock, CONTROL_CAR_DOORS)는 쓰지 않는다.
 * - 영역은 뒷문 두 개만: ROW_2_LEFT, ROW_2_RIGHT([REAR_DOORS]).
 *
 * 값의 뜻: AOSP DOOR_POS(0x16400B00, INT32) 기준 0 = 닫힘, 0보다 크면 열림. 음수는 뜻을 모르므로 확인 불가.
 * QA 에뮬 실측(2026-11-15, 플랫폼 속성 수준): VC `PUT /vehicle/body?door_rl=true` → zone 0x10(= SDK ROW_2_LEFT 16) int32 [1], 닫으면 0.
 * 문 4개 area 0x1·0x4·0x10·0x40. 극성은 AOSP 그대로(착석과 달리 뒤집지 않음). 앱 SDK 콜백으로 같은 값이 오는지는 통합 뒤 QA 확인(미검증).
 * 실차에서 반대로 나오면 [OPEN_WHEN_POSITIVE] 상수 하나만 바꾼다.
 *
 * '확인 불가'(미지원·권한 거부·읽기 실패·구독 단절·값 없음)는 열림이나 닫힘으로 바꾸지 않는다(CLAUDE.md 차량 앱 규칙).
 */
enum class DoorPos { OPEN, CLOSED, UNKNOWN }

object DoorSignal {
    /** 극성 스위치. true = 값 > 0 이면 열림(AOSP DOOR_POS). VC·실차에서 반대로 나오면 false로만 바꾼다 */
    const val OPEN_WHEN_POSITIVE = true

    /** 하차 판단에 쓰는 뒷문(결정 45: 둘 중 어느 쪽이든 인정) */
    const val REAR_LEFT = "ROW_2_LEFT"
    const val REAR_RIGHT = "ROW_2_RIGHT"
    val REAR_DOORS: List<String> = listOf(REAR_LEFT, REAR_RIGHT)
    /**
     * r10d B(사용자 지시 15:2x): 조수석 문(Vehicle 2.3.2 DoorValue.ROW_1_RIGHT, javap 확인 · AOSP area 0x4, VC `door_fr`).
     * 확인 때 조수석에 앉은 신호가 있었으면 그 자리의 '내렸다' 판단에 쓴다(ExitDoorRule.FRONT_PASSENGER_TARGET). 운전석 문은 읽지 않는다
     */
    const val FRONT_RIGHT = "ROW_1_RIGHT"
    /** 이번 정차 열림을 기록하는 문(뒷문 둘 + 스위치가 켜졌으면 조수석 문) */
    fun exitDoors(frontPassenger: Boolean = ExitDoorRule.FRONT_PASSENGER_TARGET): List<String> =
        if (frontPassenger) REAR_DOORS + FRONT_RIGHT else REAR_DOORS

    /** SDK 원시값 → 문 상태. null·음수 = 확인 불가 */
    fun fromSdk(pos: Int?, openWhenPositive: Boolean = OPEN_WHEN_POSITIVE): DoorPos = when {
        pos == null || pos < 0 -> DoorPos.UNKNOWN
        (pos > 0) == openWhenPositive -> DoorPos.OPEN
        else -> DoorPos.CLOSED
    }

    /**
     * 연결 상태까지 합친 문 상태(MainActivity 표시·판정 입력용).
     * supported = 지원 목록에 있는가(null = 아직 모름 → 미지원으로 보지 않음), subscribed = 구독 살아 있음, raw = 마지막 원시값.
     */
    fun of(supported: Boolean?, subscribed: Boolean, readFailed: Boolean, raw: Int?): DoorPos =
        if (supported == false || !subscribed || readFailed) DoorPos.UNKNOWN else fromSdk(raw)

    /** 로그 꼬리: "OPEN (SDK 3)" */
    fun logText(raw: Int?): String = "${fromSdk(raw)} (SDK $raw)"
}
