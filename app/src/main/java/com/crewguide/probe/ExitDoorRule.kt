package com.crewguide.probe

/**
 * 2026-11-15 r6 4절(개발 D, 사용자 지시): '내렸다' 판단에 문 열림을 더한다. 순수 규칙(Android·SDK 클래스 없음).
 *
 * 사용자 말: "아이가 내리지 않고 일어났다가 앉으면 그냥 내린 걸로 판단해 버린다. 문이 열리고 좌석에 앉아 있지 않으면
 * 내린 걸로 판단하자. 문을 닫는 건 보통 어른이 하니까(닫힘은 조건에 넣지 않는다)."
 *
 * 규칙
 *  - 어떤 자리가 '내렸다' = (1) 이번 정차(유효 P) 동안 뒷문이 한 번이라도 열렸다 + (2) 그 자리 착석 신호가 비어 있다(EMPTY).
 *  - 뒷문은 왼쪽·오른쪽 어느 쪽이든 인정한다(**임시 결정 45**, 스위치 [ANY_REAR_DOOR]).
 *  - 문이 열린 적 없이 자리만 비면 '내렸다'가 아니다(마무리 없음, 지금 화면 유지). 다시 앉으면 그대로 이어진다.
 *  - 이번 정차 기록은 유효 P에서 시작한다(그때 이미 열려 있는 뒷문도 열림으로 기록). 주행(D 등 유효한 비-P 기어)이면 지운다.
 *    기어 불명·단절은 시작도 지우기도 하지 않는다(UNKNOWN을 정차·주행으로 바꾸지 않음).
 *  - 문 신호를 읽을 수 없으면(미지원·권한 거부·읽기 실패·단절 = [DoorPos.UNKNOWN]) 그때만 지금 규칙(착석만)으로 판단한다
 *    (**임시 결정 46**, 스위치 [SEAT_ONLY_WHEN_UNKNOWN]). 확인 불가를 열림·닫힘으로 바꾸지 않는다.
 *    결정 45일 때: 뒷문 중 하나라도 이번 정차에 열렸으면 문 기준, 아니면 뒷문 둘 다 읽을 수 있을 때만 '안 열림'(막음),
 *    하나라도 확인 불가면 착석만(그 문으로 내렸을 수 있으니 영원히 막지 않는다).
 *  - 닫힘은 조건에 넣지 않는다.
 *
 * 되돌리기: [USE_DOOR]=false면 예전 규칙(착석만)과 같다. 결정 45 → [ANY_REAR_DOOR]=false(자리 쪽 문만 인정, 가운데 자리는 둘 다),
 * 결정 46 → [SEAT_ONLY_WHEN_UNKNOWN]=false(문 확인 불가면 마무리 없음).
 *
 * 공개 진입점(개발 A 5절 중간 하차도 이것을 부른다): [allLeft]. 마무리했으면 [onFinale]로 기준을 남긴다(계약 exitByDoor).
 */
enum class ExitBasis {
    /** 문 신호를 썼고 이번 정차에 (해당) 뒷문이 열렸다 */
    DOOR_OPENED,
    /** 문 신호를 읽을 수 있는데 이번 정차에 열린 적 없다 → '내렸다'가 아니다 */
    DOOR_NOT_OPENED,
    /** 문 신호 확인 불가(또는 문 규칙 꺼짐) → 착석만으로 판단(결정 46) */
    SEAT_ONLY,
}

data class ExitDoorState(
    /** 유효 P 정차 중(이번 정차 기록 중) */
    val stopActive: Boolean = false,
    /** 이번 정차에 열림을 본 뒷문 */
    val openedThisStop: Set<String> = emptySet(),
    /** 문별 최근 상태(없으면 확인 불가) */
    val doors: Map<String, DoorPos> = emptyMap(),
    /** 하차 마무리 때 문 신호를 썼는가(계약 v6 exitByDoor, 마무리 화면용으로 고정) */
    val finaleByDoor: Boolean = false,
    /** r7 2절(나): 하차 마무리를 보호자 버튼으로 했는가(계약 v7 exitByGuardian, 마무리 화면용으로 고정) */
    val finaleByGuardian: Boolean = false,
    /**
     * r7 2절(나) 후속(PM): 보호자 버튼으로 끝낸 순간 뒷줄에 착석 신호가 남아 있던 자리(기준선). 이 자리는 그대로 앉아 있으면
     * '다시 앉은 신호'(reseatedAfterAllLeft)가 아니다. 비었다가(EMPTY) 다시 앉았을 때만 친다.
     * r9-1(검수 세션 위임): 자동 마무리(onFinale) 때도 같은 기준선을 잡는다(배정 안 한 뒷자리 포함). 이름은 r7 그대로 둔다(보호자·자동 공통).
     * 기준선 자리가 아직 앉아 있는 동안은 계약 v9 otherRearSeatedAfterAllLeft([otherRearSeated]).
     */
    val guardianSeatedAtFinish: Set<String> = emptySet(),
    /** 기준선 자리 중 마무리 뒤 EMPTY를 본 자리(확인 불가는 비움으로 치지 않는다) */
    val guardianVacated: Set<String> = emptySet(),
    /** r8 1절(계약 v8 guardianFinishSeatOccupied): 보호자 버튼을 누른 순간 배정 뒷자리에 착석 신호가 남아 있었다 */
    val guardianSeatOccupied: Boolean = false,
)

/** 모두 내림 판단 결과. waitingDoor = 자리는 모두 비었는데 문이 열린 적 없어 기다리는 중(로그용) */
data class ExitDoorVerdict(val allLeft: Boolean, val byDoor: Boolean, val waitingDoor: Boolean)

object ExitDoorRule {
    /** 문 열림 규칙 켬. false = 예전 규칙(착석만) */
    const val USE_DOOR = true
    /** 임시 결정 45: 뒷문은 왼쪽·오른쪽 어느 쪽이든 인정. false = 자리 쪽 문만(가운데 자리는 둘 다) */
    const val ANY_REAR_DOOR = true
    /** 임시 결정 46: 문 확인 불가면 착석만으로 판단. false = 문 확인 불가면 마무리하지 않음 */
    const val SEAT_ONLY_WHEN_UNKNOWN = true
    /**
     * r10d B(사용자 지시: "조수석도 누가 탔다면 문 열고 내리는 거 확인하고 전부 내리면 모두 내린 것 같아요로 안내. 운전석은 예외"):
     * 출발 전 확인이 끝날 때 조수석([FRONT_PASSENGER])에 앉음 신호가 있었으면 그 자리도 하차 마무리 대상([finaleTargets]).
     * 뒷자리와 같은 규칙: 이번 정차에 **조수석 문**이 열림 + 조수석 착석 EMPTY(조수석 문 확인 불가면 착석만, 결정 46과 같음).
     * 뒷자리 대상과 조수석이 모두 충족돼야 마무리. 운전석은 늘 제외. 확인 때 조수석이 비었거나 확인 불가면 대상 아님(이동 중 새로 앉아도 아님).
     * 조수석은 하차 마무리 대상일 뿐이다: 아이 배정·스티커·두고 내림 안내·이동 알림 상한(H)은 지금처럼 뒷자리만(각 규칙이 뒷줄로 거른다).
     * 출발 전 앞자리 벨트 확인·주행 중 앞자리 풀림 알림은 이 스위치와 무관하게 그대로다.
     * false = r10d 전과 같다(뒷자리만, 조수석 문 구독 안 함).
     */
    const val FRONT_PASSENGER_TARGET = true
    const val FRONT_PASSENGER = DoorSignal.FRONT_RIGHT

    /**
     * 하차 마무리 대상 자리(확인 완료 때 정해 [TripGuideState.occupiedAtConfirm]에 넣는다): 확인 때 앉음이던 배정 뒷자리 [rearOccupied]
     * + (스위치 켬, 뒷자리 대상이 하나 이상, 확인 때 조수석 앉음 신호) 조수석. 운전석은 넣지 않는다
     */
    fun finaleTargets(rearOccupied: Set<String>, passengerOcc: SeatOcc, enabled: Boolean = FRONT_PASSENGER_TARGET): Set<String> {
        val rear = rearOccupied.filter { it != SeatCheckRule.DRIVER_SEAT && it != FRONT_PASSENGER }.toSet()
        return if (enabled && rear.isNotEmpty() && passengerOcc == SeatOcc.OCCUPIED) rear + FRONT_PASSENGER else rear
    }

    /**
     * 기어 변화. driving = 유효한 비-P 기어(BeltReleaseRule.isDriving), validPark = 유효 P(BeltReleaseRule.isValidPark).
     * 주행 → 이번 정차 기록 지움. 유효 P로 처음 들어오면 기록 시작(이미 열린 뒷문은 열림으로 기록). 그 밖(불명·단절)은 그대로.
     */
    fun onGear(s: ExitDoorState, driving: Boolean, validPark: Boolean): ExitDoorState = when {
        driving -> if (!s.stopActive && s.openedThisStop.isEmpty()) s else s.copy(stopActive = false, openedThisStop = emptySet())
        validPark && !s.stopActive -> s.copy(stopActive = true,
            openedThisStop = DoorSignal.exitDoors().filter { s.doors[it] == DoorPos.OPEN }.toSet())
        else -> s
    }

    /** 문 신호 1건(이벤트·GET·확인 불가 전환). 정차 중 뒷문 열림이면 기록한다. 닫힘은 기록을 지우지 않는다 */
    fun onDoor(s: ExitDoorState, door: String, pos: DoorPos): ExitDoorState {
        val opened = if (s.stopActive && pos == DoorPos.OPEN && door in DoorSignal.exitDoors()) s.openedThisStop + door else s.openedThisStop
        return s.copy(doors = s.doors + (door to pos), openedThisStop = opened)
    }

    /** 화면 객체가 끝나 리스너를 해제했다: 문 상태는 확인 불가로(다음 화면이 다시 읽는다), 이번 정차 기록은 유지 */
    fun forgetDoors(s: ExitDoorState): ExitDoorState = s.copy(doors = emptyMap())

    /** 이 자리에 해당하는 뒷문(결정 45면 둘 다) */
    fun doorsFor(seat: String, anyRearDoor: Boolean = ANY_REAR_DOOR): List<String> = when {
        seat == FRONT_PASSENGER -> listOf(DoorSignal.FRONT_RIGHT)   // r10d B: 조수석은 조수석 문만(뒷문 열림으로 조수석을 '내렸다'로 보지 않는다)
        anyRearDoor -> DoorSignal.REAR_DOORS
        seat == DoorSignal.REAR_LEFT -> listOf(DoorSignal.REAR_LEFT)
        seat == DoorSignal.REAR_RIGHT -> listOf(DoorSignal.REAR_RIGHT)
        else -> DoorSignal.REAR_DOORS
    }

    /** 자리별 판단 기준. s == null이면 문 신호 없이(착석만) */
    fun basis(s: ExitDoorState?, seat: String, useDoor: Boolean = USE_DOOR, anyRearDoor: Boolean = ANY_REAR_DOOR,
              seatOnlyWhenUnknown: Boolean = SEAT_ONLY_WHEN_UNKNOWN): ExitBasis {
        if (!useDoor || s == null) return ExitBasis.SEAT_ONLY
        val doors = doorsFor(seat, anyRearDoor)
        if (doors.any { it in s.openedThisStop }) return ExitBasis.DOOR_OPENED
        val readable = doors.all { val p = s.doors[it]; p == DoorPos.OPEN || p == DoorPos.CLOSED }
        return if (readable || !seatOnlyWhenUnknown) ExitBasis.DOOR_NOT_OPENED else ExitBasis.SEAT_ONLY
    }

    /** 자리 하나가 '내렸다'인가: 착석 신호 EMPTY + (문 기준이면 이번 정차에 뒷문 열림). UNKNOWN 착석은 내림이 아니다 */
    fun seatLeft(s: ExitDoorState?, seat: String, occ: SeatOcc, useDoor: Boolean = USE_DOOR, anyRearDoor: Boolean = ANY_REAR_DOOR,
                 seatOnlyWhenUnknown: Boolean = SEAT_ONLY_WHEN_UNKNOWN): Boolean =
        occ == SeatOcc.EMPTY && basis(s, seat, useDoor, anyRearDoor, seatOnlyWhenUnknown) != ExitBasis.DOOR_NOT_OPENED

    /**
     * **공개 진입점: 모두 내림 판단.** seats = 배정한 뒷줄 자리(하차 마무리 대상), occ = 자리별 착석 신호(없으면 확인 불가).
     * 모든 자리가 [seatLeft]면 allLeft. 자리가 없으면 false. byDoor = 모두 내림이고 문 열림을 근거로 쓴 자리가 있음.
     * s == null이면 문 신호 없이 착석만(예전 규칙과 같음).
     */
    fun allLeft(s: ExitDoorState?, seats: Collection<String>, occ: Map<String, SeatOcc>, useDoor: Boolean = USE_DOOR,
                anyRearDoor: Boolean = ANY_REAR_DOOR, seatOnlyWhenUnknown: Boolean = SEAT_ONLY_WHEN_UNKNOWN): ExitDoorVerdict {
        if (seats.isEmpty()) return ExitDoorVerdict(false, false, false)
        val bases = seats.associateWith { basis(s, it, useDoor, anyRearDoor, seatOnlyWhenUnknown) }
        val allEmpty = seats.all { (occ[it] ?: SeatOcc.UNKNOWN) == SeatOcc.EMPTY }
        val blocked = bases.values.any { it == ExitBasis.DOOR_NOT_OPENED }
        val all = allEmpty && !blocked
        return ExitDoorVerdict(all, all && bases.values.any { it == ExitBasis.DOOR_OPENED }, allEmpty && blocked)
    }

    /**
     * 하차 마무리를 했다: 그때의 기준을 고정한다(마무리 화면의 계약 exitByDoor).
     * r9-1: seatedNow = 그 순간 뒷줄에 착석 신호(OCCUPIED)가 있던 자리(배정 안 한 자리 포함) → 기준선(비었다가 다시 앉아야 '다시 앉은 신호')
     */
    fun onFinale(s: ExitDoorState, v: ExitDoorVerdict, seatedNow: Set<String> = emptySet()): ExitDoorState =
        s.copy(finaleByDoor = v.byDoor, finaleByGuardian = false, guardianSeatedAtFinish = seatedNow, guardianVacated = emptySet(),
            guardianSeatOccupied = false)

    /** r7 2절(나): 보호자 버튼으로 마무리했다(GuardianFinishRule). 문 기준은 쓰지 않았다 */
    fun onGuardianFinish(s: ExitDoorState, seatedNow: Set<String> = emptySet(), seatOccupied: Boolean = false): ExitDoorState =
        s.copy(finaleByDoor = false, finaleByGuardian = true, guardianSeatedAtFinish = seatedNow, guardianVacated = emptySet(),
            guardianSeatOccupied = seatOccupied)

    /** 계약 v8 guardianFinishSeatOccupied 값: 보호자 버튼 마무리 화면이고 누른 순간 배정 뒷자리에 착석 신호가 남아 있었음 */
    fun guardianFinishSeatOccupied(s: ExitDoorState, allLeftSaid: Boolean): Boolean =
        exitByGuardian(s, allLeftSaid) && s.guardianSeatOccupied

    /** 하차 마무리 화면 동안 착석 신호 관찰: 기준선 자리가 EMPTY가 되면 '비었음'으로 기록(그 뒤 다시 앉으면 다시 앉은 신호로 친다) */
    fun observeAfterFinish(s: ExitDoorState, occ: Map<String, SeatOcc>): ExitDoorState {
        val vacated = s.guardianSeatedAtFinish.filter { it !in s.guardianVacated && occ[it] == SeatOcc.EMPTY }
        return if (vacated.isEmpty()) s else s.copy(guardianVacated = s.guardianVacated + vacated)
    }

    /**
     * ReseatRule에 넘길 착석 지도: 보호자로 끝낸 순간부터 계속 앉아 있는 자리(기준선 중 아직 비운 적 없는 자리)는 뺀다.
     * 기준선이 없으면 그대로다(r9-1부터 자동 마무리도 기준선을 잡는다).
     */
    fun reseatOcc(s: ExitDoorState, occ: Map<String, SeatOcc>): Map<String, SeatOcc> {
        val keep = s.guardianSeatedAtFinish - s.guardianVacated
        return if (keep.isEmpty()) occ else occ.filterKeys { it !in keep }
    }

    /**
     * r9-1(계약 v9 otherRearSeatedAfterAllLeft): 모두 내림 화면에서 기준선 자리(마무리 순간부터 계속 앉아 있던 뒷자리)가 아직 앉아 있다.
     * 다시 앉은 신호([ReseatRule.reseated], 기준선을 뺀 지도)가 있으면 그쪽이 우선이라 false. 정차 허용(화면 표시 가능)일 때만.
     */
    fun otherRearSeated(s: ExitDoorState, phase: AutoPhase?, occ: Map<String, SeatOcc>, parkAllowed: Boolean): Boolean {
        if (phase != AutoPhase.ALL_LEFT || !parkAllowed) return false
        if (ReseatRule.reseated(phase, reseatOcc(s, occ), parkAllowed)) return false
        return (s.guardianSeatedAtFinish - s.guardianVacated).any { ReseatRule.isRear(it) && occ[it] == SeatOcc.OCCUPIED }
    }

    /** 계약 v7 exitByGuardian 값: 하차 마무리 화면이고 보호자 버튼으로 끝냈을 때만 true */
    fun exitByGuardian(s: ExitDoorState, allLeftSaid: Boolean): Boolean = allLeftSaid && s.finaleByGuardian

    /** 지금 기준 exitByDoor(마무리 전): 문 규칙 켬 + 이번 정차에 뒷문 열림 */
    fun exitByDoorNow(s: ExitDoorState, useDoor: Boolean = USE_DOOR): Boolean = useDoor && s.openedThisStop.any { it in DoorSignal.REAR_DOORS }   // r10d B: 마무리 전 표시는 뒷문 기준 그대로

    /** 계약 v6 exitByDoor 값: 하차 마무리 화면이면 마무리 때 고정한 값, 아니면 지금 기준 */
    fun exitByDoor(s: ExitDoorState, allLeftSaid: Boolean): Boolean = if (allLeftSaid) s.finaleByDoor else exitByDoorNow(s)
}

/** 프로세스 보관소(Activity 재생성에도 이번 정차 문 열림 기록 유지). 메인 스레드 전용 */
object ExitDoorStore {
    var state = ExitDoorState()
}
