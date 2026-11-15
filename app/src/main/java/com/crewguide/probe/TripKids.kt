package com.crewguide.probe

// 2026-11-15 r10 수정(J + 화면 재생성, 검수 세션 지시): 이번 이동의 스티커 대상 아이를 프로세스 단위로 보관한다.
// 예전에는 MainActivity 필드(confirmWithoutProfile)와 ChildProfilePanel 선택에만 있어, 야간·글꼴 전환으로 Activity가 다시 만들어지면
// 사라지고 새 패널의 선택(지난 조합 등)으로 스티커가 기록될 수 있었다(예: 등록 아이를 '손님'으로 바꿔 배정한 이동 → 타지 않은 아이에게 기록).
// 확인 완료(onAutoComplete)·이동 복원(restoreTripState) 때 [TripKidsStore]에 남기고, 지급 때 [TripKidsRule.grantIds]로 다시 고른다.
// 순수 로직(Android 클래스 없음). 아이 번호(프로필 id)만 담고 별명·이름은 담지 않는다. 기기 밖으로 보내지 않는다.

/** 이번 이동의 스티커 대상. [kidIds] = 배정한 뒷줄 자리의 등록 아이(손님·이름 없는 아이 id < 0 제외) */
data class TripKids(val tripKey: Int, val kidIds: Set<Int>) {
    /** 등록 아이 없이 진행한 이동(손님만·이름 없는 아이) → 개인 기록 없음 */
    val withoutProfile: Boolean get() = kidIds.isEmpty()
}

object TripKidsRule {
    /** 스위치. false면 r10 후보와 같다(Activity 필드·패널 선택만 씀) */
    const val ENABLED = true

    /** 배정(자리 → 아이 id)에서 이번 이동의 스티커 대상을 만든다. 앞줄·손님(id < 0)은 뺀다 */
    fun of(tripKey: Int, assigned: Map<String, Int>): TripKids =
        TripKids(tripKey, assigned.filterKeys { SeatCheckRule.isAssignable(it) }.values.filter { it >= 0 && !SeatCheckRule.isGuest(it) }.toSet())

    /** 보관값이 지금 이동의 것이면 그 값, 아니면 null(지난 이동 값은 쓰지 않는다) */
    fun forTrip(saved: TripKids?, tripKey: Int, enabled: Boolean = ENABLED): TripKids? =
        if (enabled && saved != null && saved.tripKey == tripKey) saved else null

    /**
     * 지급 때 기록할 아이 id. 보관값이 이번 이동 것이면 배정된 등록 아이만(손님만이면 빈 집합 = 기록 없음).
     * 보관값이 없으면 null → 호출자가 기존 경로(Activity 필드·패널 선택)를 쓴다
     */
    fun grantIds(saved: TripKids?, tripKey: Int, enabled: Boolean = ENABLED): Set<Int>? =
        forTrip(saved, tripKey, enabled)?.kidIds

    /** 개인 기록 없이 진행한 이동인가. 보관값이 없으면 [fallback](Activity 필드) */
    fun withoutProfile(saved: TripKids?, tripKey: Int, fallback: Boolean, enabled: Boolean = ENABLED): Boolean =
        forTrip(saved, tripKey, enabled)?.withoutProfile ?: fallback
}

/** 프로세스 단위 보관소(Activity 재생성에도 남는다). 프로세스가 끝나면 개발 7의 이동 복원(restoreTripState)이 다시 채운다 */
object TripKidsStore {
    var saved: TripKids? = null
}
