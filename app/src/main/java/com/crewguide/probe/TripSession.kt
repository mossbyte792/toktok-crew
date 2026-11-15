package com.crewguide.probe

// 이동 단위(2026-11-08 ch 결정 1·3). 순수 로직 + 보관소(Android 클래스 없음). 저장 입출력은 StickerLogStore(KEY_TRIP)가 한다.
//
// 이동 번호(tripKey)는 '이번 이동 끝내기'(신호 기반 또는 수동, TripEndRule.finish)를 마칠 때만 1 증가한다.
// - 기어 D↔P, 잠깐 정차, UXR 제한, 화면 이탈(onPause)·복귀, Activity 재생성은 이동 번호를 바꾸지 않는다.
// - 경로 취소·도착 신호만으로는 바뀌지 않는다(보호자의 끝내기 탭이 필요).
// - 끝내기 뒤의 탑승 확인은 다음 이동의 것이다(끝내기 → 같은 P에서 다음 탑승 → 출발 순서).
// - 2026-11-08 Unit 6 최종(ch 결정 2): 프로세스 재시작은 새 이동이 아니다. 이동 번호·지급 이동·주행 관측을
//   스티커 기록과 같은 SharedPreferences 파일에 저장해 재시작 뒤 같은 이동을 이어간다.
//   저장값을 읽지 못하면(손상) '이번 이동은 이미 지급함'으로 보수적으로 시작한다(자동 새 보상보다 명시적 끝내기 우선).
//
// 이 번호로 묶는 것: 참여 스티커(이동당 최대 1회, 실제 기록 저장 성공 때만 표시), 하차 한 줄(이동당 최대 1회, DONE 기준),
// 보호자용 [이번 이동 끝내기] 노출 근거(moved: 이번 이동에서 유효한 주행 기어를 실제로 받았는지).
// 안전 판단·도착·보상 저장의 근거가 아니다.

/**
 * stickerTrip: 스티커 기록을 실제로 저장한 이동 번호(없으면 -1).
 * moved: 이번 이동에서 주행(링크 정상·구독 정상·P 아닌 유효 기어)을 본 적이 있는지. 끝내기에서 false로 돌아간다.
 */
data class TripSession(val tripKey: Int = 0, val stickerTrip: Int = -1, val moved: Boolean = false)

object TripSessionRule {
    /** 끝내기 완료: 다음 이동으로 넘어간다(주행 관측도 비움) */
    fun finish(s: TripSession): TripSession = s.copy(tripKey = s.tripKey + 1, moved = false)

    /** 이번 이동에서 이미 스티커를 기록했는지 */
    fun stickerGranted(s: TripSession): Boolean = s.stickerTrip == s.tripKey

    /** 이번 이동에 스티커 기록 표시 */
    fun markSticker(s: TripSession): TripSession = s.copy(stickerTrip = s.tripKey)

    /** 주행 관측 기록(이미 기록했으면 같은 객체) */
    fun onMotion(s: TripSession, driving: Boolean): TripSession =
        if (driving && !s.moved) s.copy(moved = true) else s

    /**
     * 보호자용 [이번 이동 끝내기] 노출 조건(ch 결정 1). 정차 허용 게이트(유효 P + 최신 UXR ALLOWED + 연결 정상)가 열려 있고,
     * 이번 이동에서 실제 주행을 봤고, 아직 끝내기를 하지 않았으며, 보호자 안내·질문 진행 중이 아닐 때만.
     * P만으로 도착이라고 보지 않는다(버튼은 '도착'이 아니라 보호자의 끝내기 선택이다).
     */
    fun manualFinishAllowed(s: TripSession, gateOpen: Boolean, alreadyFinished: Boolean, flowBusy: Boolean): Boolean =
        gateOpen && s.moved && !alreadyFinished && !flowBusy

    /**
     * 스티커 판정 1회(refreshGuardian에서 호출). 세션 값은 바꾸지 않는다. 실제 기록이 저장된 뒤에만 호출자가 [markSticker]를 반영한다.
     * attemptedThisYes: 이번 '예' 보고에서 이미 기록을 시도했는지(실패해도 같은 '예'에서는 자동 재시도하지 않음).
     * 반환 newlyGranted = 지금 기록 저장을 시도해야 함. show = 참여 표시(보호자 보고 기준, 영구 기록 성공과는 별개).
     */
    fun stickerStep(s: TripSession, locked: Boolean, targetCount: Int, yesReported: Boolean, attemptedThisYes: Boolean): StickerResult =
        StickerRule.evaluate(stickerGranted(s) || attemptedThisYes, targetCount, yesReported && !locked)

    // ---- 저장 형식: "t1,이동번호,지급이동,주행관측(0/1)". 시각·위치·이름·식별자 없음 ----
    const val VERSION = "t1"

    fun encode(s: TripSession): String = "$VERSION,${s.tripKey},${s.stickerTrip},${if (s.moved) 1 else 0}"

    // ---- 2026-11-10 M06: 하차 한 줄을 DONE으로 소진한 이동 번호(별도 키 "e1,이동번호"). t1 형식은 바꾸지 않는다 ----
    const val EXIT_VERSION = "e1"

    fun encodeExitTrip(tripKey: Int): String = "$EXIT_VERSION,$tripKey"

    /** 없음·형식 오류·음수 = -1(소진 기록 없음 → 하차 한 줄 허용 쪽). 소진 기록은 보상이 아니라 안내 중복 억제용이라 읽기 실패 때 안내를 막지 않는다 */
    fun decodeExitTrip(text: String?): Int {
        val f = text?.split(',') ?: return -1
        if (f.size != 2 || f[0] != EXIT_VERSION) return -1
        val k = f[1].toIntOrNull() ?: return -1
        return if (k < 0) -1 else k
    }

    // ---- 2026-11-13: 스티커 조건 충족(자동 확인 완료 또는 보호자 대체 버튼)을 기록한 이동 번호(별도 키 "s1,이동번호"). t1 형식은 바꾸지 않는다 ----
    // 조건 충족과 지급(기록 저장·스티커판·연출)을 나눈다: 지급은 하차 마무리 또는 이동 끝내기 때(이동당 1회, stickerTrip).
    const val EARNED_VERSION = "s1"

    fun encodeEarnedTrip(tripKey: Int): String = "$EARNED_VERSION,$tripKey"

    /** 없음·형식 오류·음수 = -1(조건 기록 없음). 읽기 실패면 스티커를 주지 않는 쪽(보수적) */
    fun decodeEarnedTrip(text: String?): Int {
        val f = text?.split(',') ?: return -1
        if (f.size != 2 || f[0] != EARNED_VERSION) return -1
        val k = f[1].toIntOrNull() ?: return -1
        return if (k < 0) -1 else k
    }

    /** 이번 이동에서 스티커 조건을 채웠는가 */
    fun stickerEarned(s: TripSession, earnedTrip: Int): Boolean = earnedTrip >= 0 && earnedTrip == s.tripKey

    /**
     * 지급 판정(하차 마무리·이동 끝내기 때 1회): 조건을 채웠고 아직 이번 이동에 지급하지 않았으면 true.
     * attempted: 이번 이동에서 이미 지급을 시도했는가(실패해도 같은 이동에서는 자동 재시도하지 않음).
     */
    fun shouldGrant(s: TripSession, earnedTrip: Int, attempted: Boolean): Boolean =
        stickerEarned(s, earnedTrip) && !stickerGranted(s) && !attempted

    /**
     * 2026-11-14 PM 결정 29: 하차 마무리(ALL_LEFT)에 도달한 이동은 끝난 이동이다. 그 뒤 [시작]하면 이동 번호를 넘겨 새 이동으로 시작한다.
     * [allLeftTrip] = 하차 마무리를 말한 이동 번호(없으면 -1, 결정 30: 저장값이라 앱 재시작 뒤에도 남는다). [이번 이동 끝내기]를 이미 눌렀으면
     * 번호가 이미 넘어가 있어(기록도 지워짐) 다시 늘리지 않는다.
     * 스티커 이동당 1회(stickerTrip)는 그대로다.
     */
    fun startsNewTripOnStart(allLeftTrip: Int, currentTripKey: Int): Boolean =
        allLeftTrip >= 0 && allLeftTrip == currentTripKey

    /**
     * 2026-11-15 루프 r8 3절: [시작]·빠른 시작 때 새 이동으로 넘길지(하차 마무리 도달 [startsNewTripOnStart] 또는 **저장된 스티커 지급 기록**).
     * r7부터 스티커를 하차 안내 때 주므로, 하차 안내 → 시동 끔(프로세스 종료) → 다시 [시작]이면 하차 마무리 기록 없이 같은 이동 번호가 되어
     * 이동당 1회 규칙([shouldGrant])이 다음 이동의 스티커를 막았다. 지금 이동 번호에 이미 스티커를 기록했으면 그 이동은 끝난 이동으로 본다.
     * 판단은 메모리가 아니라 저장값([stored] = StickerLogStore.loadTripOrNull)으로 한다. 읽지 못하면(null) 예전처럼 넘기지 않는다
     * (저장 손상 때는 보수적으로 같은 이동을 잇고 보호자의 끝내기를 기다린다 — restore의 '이미 지급함' 처리와 같은 방향).
     */
    fun startsNewTripOnStartStored(allLeftTrip: Int, currentTripKey: Int, stored: TripSession?): Boolean =
        startsNewTripOnStart(allLeftTrip, currentTripKey) ||
            (stored != null && stored.tripKey == currentTripKey && stored.stickerTrip >= 0 && stored.stickerTrip == stored.tripKey)

    // ---- 2026-11-14 PM 결정 30: 하차 마무리 도달 이동 번호를 저장한다(별도 키 "a1,이동번호"). 예전에는 메모리뿐이라 앱 프로세스가
    // 다시 시작되면 다음 [시작]이 같은 이동으로 이어져 스티커가 지급되지 않았다. 이동 경계를 넘기면 지운다(StickerLogStore.saveTripBoundary) ----
    const val ALL_LEFT_VERSION = "a1"

    fun encodeAllLeftTrip(tripKey: Int): String = "$ALL_LEFT_VERSION,$tripKey"

    /** 없음·형식 오류·음수 = -1(기록 없음). 읽기 실패는 '기록 없음'으로 본다(같은 이동을 이어 감, 자동으로 새 이동을 만들지 않는 쪽) */
    fun decodeAllLeftTrip(text: String?): Int {
        val f = text?.split(',') ?: return -1
        if (f.size != 2 || f[0] != ALL_LEFT_VERSION) return -1
        val k = f[1].toIntOrNull() ?: return -1
        return if (k < 0) -1 else k
    }

    /** null·빈 값 = 저장 없음(새 설치, 기본값). 형식 오류·범위 밖 = 읽기 실패(null 반환) */
    fun decodeOrNull(text: String?): TripSession? {
        if (text.isNullOrEmpty()) return TripSession()
        val f = text.split(',')
        if (f.size != 4 || f[0] != VERSION) return null
        val key = f[1].toIntOrNull() ?: return null
        val st = f[2].toIntOrNull() ?: return null
        if (key < 0 || st < -1 || st > key) return null
        val moved = when (f[3]) { "0" -> false; "1" -> true; else -> return null }
        return TripSession(key, st, moved)
    }

    /**
     * 시작 때 복원. 읽기 실패면 '이번 이동은 이미 지급함'(자동 새 보상 차단, 주행 관측 없음)으로 시작하고 second=true.
     * 원본은 보호자의 끝내기 전까지 덮어쓰지 않는다(호출자 책임).
     */
    fun restore(loaded: TripSession?): Pair<TripSession, Boolean> =
        if (loaded == null) TripSession(tripKey = 0, stickerTrip = 0, moved = false) to true else loaded to false
}

/** 프로세스 보관소(Activity 재생성에도 유지). 메인 스레드 전용. 시작 때 MainActivity가 StickerLogStore.loadTripOrNull로 한 번 복원한다 */
object TripSessionStore {
    var state = TripSession()
    /** 이번 프로세스에서 저장값 복원을 시도했는지 */
    var restored = false
    /** 저장값을 읽지 못해 보수적으로 시작했는지(끝내기 저장 성공·스티커 저장 성공 때 해제) */
    var readFailed = false
    /** 하차 한 줄을 전달 완료(DONE)한 이동 번호. Activity 재생성 뒤에도 같은 이동에서 다시 나오지 않게 한다(2026-11-10 M06: 프로세스 재시작 대비 저장도 함) */
    var exitConsumedTrip = -1
    /** 2026-11-13: 스티커 조건(자동 확인 완료·보호자 대체 버튼)을 채운 이동 번호. 프로세스 재시작 대비 저장(StickerLogStore.KEY_EARNED) */
    var earnedTrip = -1
    /** 2026-11-14 PM 결정 29·30: 하차 마무리(ALL_LEFT)를 말한 이동 번호(없으면 -1). 저장(StickerLogStore.KEY_ALL_LEFT)하고 시작 때 복원한다 */
    var allLeftTrip = -1
}
