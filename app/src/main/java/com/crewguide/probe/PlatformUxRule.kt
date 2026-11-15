package com.crewguide.probe

// 플랫폼 주행 제한(CarUxRestrictions) 값 → PlatformUx 변환과 연결 수명주기 추적(순수 로직). Android·SDK 클래스를 쓰지 않는다.
// 2026-11-07 Unit 5.1, ch 22:50 승인 조건:
//  ① 기어 P와 UXR 해제는 서로 독립된 조건이다. UXR 해제로 P를 추론하지 않는다(게이트는 둘 다 요구, VehicleGateRule).
//     isRequiresDistractionOptimization()·getActiveRestrictions()는 공식 의미대로 본다:
//     DO 필요=true → 제한(RESTRICTED). DO 필요=false이고 활성 제한이 BASELINE(0)일 때만 ALLOWED.
//     DO 필요=false인데 활성 제한이 0이 아니면 앞뒤가 맞지 않으므로 UNKNOWN.
//  ② 초기 연결 실패·null·권한·서비스 단절·값 미수신·이전 연결의 값은 UNKNOWN(또는 NOT_INTEGRATED)이며 게이트를 열지 않는다.
//  ③ 연결 세대(session): 연결·단절마다 바뀌고, 이전 세대의 콜백은 버린다. 현재값 조회 도중 변화 이벤트가 오면 이벤트가 우선한다.

object PlatformUxRule {
    /** CarUxRestrictions.UX_RESTRICTIONS_BASELINE(공식 상수 0): 제한 없음 */
    const val BASELINE = 0

    /** 공식 의미대로 변환. 값 객체가 없으면(requiresDO=null) UNKNOWN */
    fun fromRestrictions(requiresDO: Boolean?, activeRestrictions: Int?): PlatformUx = when {
        requiresDO == null || activeRestrictions == null -> PlatformUx.UNKNOWN
        requiresDO -> PlatformUx.RESTRICTED
        activeRestrictions == BASELINE -> PlatformUx.ALLOWED
        else -> PlatformUx.UNKNOWN
    }

    /** 이전 단위 호환(활성 제한 값을 모를 때). requiresDO=false만으로는 허용하지 않으려면 [fromRestrictions]를 쓴다 */
    fun fromRequiresDO(requiresDO: Boolean?): PlatformUx = when (requiresDO) {
        null -> PlatformUx.UNKNOWN
        true -> PlatformUx.RESTRICTED
        false -> PlatformUx.ALLOWED
    }

    /** 기어는 유효한 P지만 플랫폼 값 때문에 잠글 때의 보호자 문구. 잠금이 아니면 null */
    fun lockText(p: PlatformUx): String? = when (p) {
        PlatformUx.ALLOWED -> null
        PlatformUx.RESTRICTED -> LOCK_TEXT_PLATFORM_RESTRICTED
        PlatformUx.UNKNOWN -> LOCK_TEXT_PLATFORM_UNKNOWN
        PlatformUx.NOT_INTEGRATED -> LOCK_TEXT_PLATFORM_UNAVAILABLE
    }
}

const val LOCK_TEXT_PLATFORM_RESTRICTED = "차량이 지금은 화면 조작을 제한하고 있어요"
const val LOCK_TEXT_PLATFORM_UNKNOWN = "차량의 화면 조작 허용 상태를 확인하는 중이에요"
const val LOCK_TEXT_PLATFORM_UNAVAILABLE = "차량의 화면 조작 허용 상태를 확인할 수 없어요"

/** 플랫폼 제한 연결 상태. session은 연결·단절마다 증가, readToken은 현재값 조회마다 증가 */
data class PlatformUxTrack(
    val session: Int = 0,
    val connected: Boolean = false,
    val value: PlatformUx = PlatformUx.UNKNOWN,
    val readToken: Int = 0,
    /** 마지막 현재값 조회를 시작한 뒤 변화 이벤트가 왔는가(왔으면 늦게 끝난 현재값은 버린다) */
    val eventSinceRead: Boolean = false,
)

object PlatformUxTracker {
    /** 서비스 연결됨: 새 세대, 값은 아직 UNKNOWN(현재값 조회 전) */
    fun connected(t: PlatformUxTrack): PlatformUxTrack =
        PlatformUxTrack(session = t.session + 1, connected = true, value = PlatformUx.UNKNOWN, readToken = t.readToken)

    /** 단절·초기 연결 실패·권한 오류: 새 세대(이전 콜백 무효), UNKNOWN */
    fun disconnected(t: PlatformUxTrack): PlatformUxTrack =
        PlatformUxTrack(session = t.session + 1, connected = false, value = PlatformUx.UNKNOWN, readToken = t.readToken)

    /** 라이브러리·서비스 없음: 새 세대, NOT_INTEGRATED(게이트는 UNKNOWN과 같이 닫힘) */
    fun unavailable(t: PlatformUxTrack): PlatformUxTrack =
        PlatformUxTrack(session = t.session + 1, connected = false, value = PlatformUx.NOT_INTEGRATED, readToken = t.readToken)

    /** 현재값 조회 시작(연결 직후·화면 복귀 재조회). 돌려준 토큰을 [current]에 넘긴다 */
    fun readStarted(t: PlatformUxTrack): PlatformUxTrack = t.copy(readToken = t.readToken + 1, eventSinceRead = false)

    /** 현재값 조회 결과. 다른 세대·다른 조회·그 사이 이벤트가 있었으면 버린다 */
    fun current(t: PlatformUxTrack, session: Int, token: Int, requiresDO: Boolean?, active: Int?): PlatformUxTrack =
        if (!t.connected || session != t.session || token != t.readToken || t.eventSinceRead) t
        else t.copy(value = PlatformUxRule.fromRestrictions(requiresDO, active))

    /** 변화 이벤트. 다른 세대(단절 전 리스너)면 버린다 */
    fun event(t: PlatformUxTrack, session: Int, requiresDO: Boolean?, active: Int?): PlatformUxTrack =
        if (!t.connected || session != t.session) t
        else t.copy(value = PlatformUxRule.fromRestrictions(requiresDO, active), eventSinceRead = true)
}

/**
 * 게이트 입력의 신선도(ch 승인 조건 ④: P와 ALLOWED가 서로 다른 시점의 오래된 값이면 허용하지 않음).
 * 앱은 화면 복귀마다 epoch를 올리고, 기어·플랫폼 값은 받은 시점의 epoch를 기록한다.
 * 현재 epoch에 다시 받은 값만 게이트에 쓴다. 오래된 기어는 '미수신', 오래된 플랫폼 값은 UNKNOWN으로 본다.
 */
object GateFreshness {
    fun gear(g: GearSnapshot, gearEpoch: Int, epoch: Int): GearSnapshot =
        if (gearEpoch == epoch) g else GearSnapshot(null, false, g.subFailed)

    fun platform(p: PlatformUx, platformEpoch: Int, epoch: Int): PlatformUx =
        if (platformEpoch == epoch) p else if (p == PlatformUx.NOT_INTEGRATED) p else PlatformUx.UNKNOWN
}
