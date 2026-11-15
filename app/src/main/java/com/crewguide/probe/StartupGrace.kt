package com.crewguide.probe

// 시작 직후 잠금 사유 '표시 문구' 종류 판정. Android 클래스를 쓰지 않는 순수 로직이다.
// 잠금 여부(gearLockReason)는 바꾸지 않는다. 보호자에게 보이는 사유 문구만 고른다.

/** 시작 후 유예 시간(ms). 창구 측정에서 기어 첫 값 도착이 약 3.3초였던 것에 여유를 둔 값 */
const val STARTUP_GRACE_MS = 5000L

/** gearLockReason이 돌려주는 문구(표시 종류를 문구로 구분하기 위한 상수. 값은 기존과 동일) */
const val LOCK_TEXT_SUB_FAILED = "기어 신호가 끊겼어요"
const val LOCK_TEXT_DISCONNECTED = "기어 신호를 아직 못 받았어요"
const val LOCK_TEXT_UNKNOWN = "기어 상태를 알 수 없어요"
const val LOCK_TEXT_NOT_PARK = "주차(P) 상태가 아니에요"
const val LOCK_TEXT_CHECKING = "기어 확인 중… (잠시만 기다려 주세요)"

enum class LockDisplayKind { CHECKING, DISCONNECTED, GEAR_UNKNOWN, SUB_FAILED, OTHER }

/** 기어 링크 미정상 상태의 표시 종류(CHECKING / DISCONNECTED / SUB_FAILED 중 하나) */
fun startupGraceKind(
    elapsedMs: Long,
    gearNameEverReceived: Boolean,
    subFailed: Boolean,
    graceMs: Long = STARTUP_GRACE_MS,
): LockDisplayKind = when {
    subFailed -> LockDisplayKind.SUB_FAILED
    !gearNameEverReceived && elapsedMs <= graceMs -> LockDisplayKind.CHECKING
    else -> LockDisplayKind.DISCONNECTED
}

/** gearLockReason 결과(reason)를 표시 종류로 바꾼다. null이면 잠금 아님 */
fun lockDisplayKind(
    reason: String?,
    elapsedMs: Long,
    gearNameEverReceived: Boolean,
    subFailed: Boolean,
    graceMs: Long = STARTUP_GRACE_MS,
): LockDisplayKind? = when (reason) {
    null -> null
    LOCK_TEXT_SUB_FAILED, LOCK_TEXT_DISCONNECTED -> startupGraceKind(elapsedMs, gearNameEverReceived, subFailed, graceMs)
    LOCK_TEXT_UNKNOWN -> LockDisplayKind.GEAR_UNKNOWN
    else -> LockDisplayKind.OTHER
}

/**
 * 화면 복귀 재조회 중인가(2026-11-08 Unit 6A, S00 '재진입'). 표시 문구 전용이며 잠금 판정은 바꾸지 않는다.
 * 복귀 전 값이 있었고(이름 수신·연결 정상) 복귀 뒤 새 값을 아직 못 받았으며, 복귀 뒤 유예 시간 안이면 true.
 * 이때 '기어 신호를 아직 못 받았어요'(미수신) 대신 '확인 중'을 보인다. 유예가 지나면 기존 미수신 문구로 돌아간다.
 */
fun resumeRequerying(rawNameKnown: Boolean, rawLinkOk: Boolean, freshAfterResume: Boolean, sinceResumeMs: Long,
                     graceMs: Long = STARTUP_GRACE_MS): Boolean =
    rawNameKnown && rawLinkOk && !freshAfterResume && sinceResumeMs in 0..graceMs

/** 보호자에게 보일 사유 문구. CHECKING일 때만 reason 대신 확인 중 문구를 쓴다 */
fun lockDisplayText(
    reason: String?,
    elapsedMs: Long,
    gearNameEverReceived: Boolean,
    subFailed: Boolean,
    graceMs: Long = STARTUP_GRACE_MS,
): String? =
    if (lockDisplayKind(reason, elapsedMs, gearNameEverReceived, subFailed, graceMs) == LockDisplayKind.CHECKING) LOCK_TEXT_CHECKING
    else reason

/**
 * 2026-11-14 루프 r1 K4(C4): 앱 시작 직후 기어 첫 값을 기다리는 동안(시작 유예 안, 아직 한 번도 못 받음) '기어 확인 중…' 잠금 카드 대신
 * 홈을 그대로 보이고 [시작]만 비활성 + 작은 글 [NOTE_CHECKING]. 게이트(잠금 판정)는 그대로다: 유효 P 확인 전에는 누름이 무시되고
 * (homeTapAllowed), 확인되면 활성. 유예가 지나도 못 받았거나 구독 실패·불명·D/R/N·플랫폼 제한이면 기존처럼 잠금 카드.
 * 화면 복귀 재조회(resumeRequerying)는 해당하지 않는다(복귀 전 값이 D였을 수 있어서 잠금 카드 유지).
 */
object StartupHomeRule {
    const val NOTE_CHECKING = "차량 신호 확인 중"

    /** 잠금 카드 대신 홈을 보일지(홈에 있을 상태이고 이동 단계가 없을 때만 호출자가 쓴다) */
    fun holdHome(lockReason: String?, elapsedMs: Long, gearNameEverReceived: Boolean, subFailed: Boolean,
                 graceMs: Long = STARTUP_GRACE_MS): Boolean =
        lockDisplayKind(lockReason, elapsedMs, gearNameEverReceived, subFailed, graceMs) == LockDisplayKind.CHECKING

    /** [시작] 활성: 잠금이 없을 때만(홈을 보이는 유예 중에는 비활성) */
    fun startEnabled(lockReason: String?): Boolean = lockReason == null
}
