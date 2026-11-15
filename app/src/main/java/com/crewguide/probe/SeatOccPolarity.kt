package com.crewguide.probe

/**
 * 2026-11-13 ISSUE-01: Pleos Vehicle SDK 2.3.2 좌석 점유 Boolean의 극성 보정(SDK 경계 전용 순수 함수).
 *
 * 근거
 * - SDK 바이트코드(javap): GET `SeatImpl$getCurrentSeatOccupancy$1`는 `toIntOrNull(s) == 1 → true`,
 *   이벤트 디스패처 `ai.pleos.playground.vehicle.callback.a`는 `Integer.parseInt(s) == 1 → true`로
 *   `onSeatOccupancyUpdated(Boolean)`에 넘긴다(그 밖의 값·null·숫자 아님 → false).
 * - 플랫폼 속성은 AOSP 표준 SEAT_OCCUPANCY 0x15400BB0(`dumpsys car_service`, 읽기 전용·ON_CHANGE,
 *   area 1/4/16/32/64). 표준 값은 VehicleSeatOccupancyState UNKNOWN=0, VACANT=1, OCCUPIED=2.
 *   즉 SDK의 true는 VACANT(빈 좌석)다.
 * - 에뮬레이터 관찰(demo/20261113-1600_9a10a36/logcat_app.txt): VC 4석 false일 때 5석 모두 true(23:01:40,
 *   VC 조작이 없는 ROW_2_CENTER 포함), VC true → false(23:03:09~11), VC false → true(23:03:13~15).
 *
 * 한계
 * - SDK가 UNKNOWN(0)·숫자 아닌 값을 OCCUPIED(2)와 같은 false로 합친다. 뒤집은 뒤에는 UNKNOWN 좌석이
 *   '앉음'으로 읽힌다. 이는 보수적이다(벨트 신호나 보호자 확인을 요구하고, 거짓 "모두 내렸어요"를 만들지 않는다).
 *   그러나 점유의 '확인 불가'는 이 SDK로는 분리할 수 없다(구독 실패·미지원은 앱이 따로 UNKNOWN 처리).
 * - 실차(VehicleService가 값을 정규화해 보낼 가능성)는 미검증. 실차에서 반대로 나오면 [INVERT_SDK]만 바꾼다.
 * - 2026-11-14 재확인(PM 지시 7): Vehicle 2.3.2·vehicle-data 1.1.11·vehicle-aidl 1.1.1 AAR 안에 착석 값의 의미(VACANT/OCCUPIED)
 *   상수·문서가 없다(`PleosPropertyIds.SEAT_OCCUPANCY` 이름과 `onSeatOccupancyUpdated(Boolean)`뿐, javadoc·sources 없음).
 *   공개 문서(document.pleos.ai Vehicle API)는 화면이 스크립트로 그려져 본문을 읽지 못했다. 근거는 위 바이트코드·AOSP 정의·에뮬 관찰뿐이다.
 *   **실차 미확인. 되돌릴 때는 [INVERT_SDK]=false 상수 하나만 바꾼다.** 정리: docs/개발기록/autocheck_2026-11-13/PM/DECISION10_EVIDENCE.md
 *
 * 적용 위치: MainActivity의 SDK 콜백 두 곳(getCurrentSeatOccupancy 응답, onSeatOccupancyUpdated)만.
 * onOccGet/onOccEvent 안에서는 뒤집지 않는다(debug 주입은 이미 의미값이므로).
 */
object SeatOccPolarity {
    const val INVERT_SDK = true

    /** SDK Boolean → 의미값(true = 착석 신호). null은 그대로 둔다 */
    fun fromSdk(b: Boolean?, invert: Boolean = INVERT_SDK): Boolean? = if (b == null) null else if (invert) !b else b

    /** 로그 꼬리: "변환값 (SDK 원래값)" */
    fun logText(sdk: Boolean?, invert: Boolean = INVERT_SDK): String = "${fromSdk(sdk, invert)} (SDK $sdk)"

    /** 의미값 → 화면 판정. true = 착석, false = 빈 좌석, null = 확인 불가 */
    fun toOcc(v: Boolean?): SeatOcc = when (v) { true -> SeatOcc.OCCUPIED; false -> SeatOcc.EMPTY; null -> SeatOcc.UNKNOWN }
}
