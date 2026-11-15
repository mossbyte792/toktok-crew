package com.crewguide.probe

// 좌석 상태 모델(기획 수정본 3-2). Android 클래스·SDK 클래스를 쓰지 않는 순수 로직이다.
// 이 모델은 표시용 판정만 하며 보호자 확인 흐름(안내 → 질문 → 예)의 진행을 막거나 앞당기지 않는다.

/** 좌석 상태 4종 */
enum class SeatStatus { BUCKLED, UNBUCKLED, UNKNOWN, EXCLUDED }

/** 확인 불가 원인 */
enum class UnknownCause { UNSUPPORTED, PERMISSION_DENIED, NO_INITIAL_VALUE, READ_FAILED, LINK_LOST, INVALID }

/** 오류/권한 상태. 현재 코드에서 구분 가능한 것만 채운다 */
enum class SeatErrorState { NONE, PERMISSION_DENIED, READ_FAILED }

/**
 * 상태 계산 입력.
 * @param isTarget 보호자가 고른 확인 대상 좌석인가
 * @param supported 이 좌석이 지원 목록에 있는가
 * @param error 오류/권한 상태
 * @param latestBuckled 최신 체결 값(true=체결, false=미체결, null=값 없음)
 * @param valid 값의 유효성 판단 결과(판단 방법 미정이라 현재 호출부는 항상 true)
 * @param linkOk 연결 상태가 정상인가
 */
data class SeatInput(
    val isTarget: Boolean,
    val supported: Boolean,
    val error: SeatErrorState,
    val latestBuckled: Boolean?,
    val valid: Boolean,
    val linkOk: Boolean,
)

data class SeatResult(val status: SeatStatus, val cause: UnknownCause?)

/**
 * 규칙: 대상 아님 → EXCLUDED. 대상인데 원인이 있거나 값이 없으면 UNKNOWN+원인(정상/미체결로 바꾸지 않음).
 * 값이 유효하고 true → BUCKLED, false → UNBUCKLED.
 * 원인 우선순위: 미지원 > 권한 거부 > 연결 단절 > 읽기 실패 > 유효성 실패 > 초기값 미수신.
 */
fun computeSeatStatus(i: SeatInput): SeatResult {
    if (!i.isTarget) return SeatResult(SeatStatus.EXCLUDED, null)
    val cause: UnknownCause? = when {
        !i.supported -> UnknownCause.UNSUPPORTED
        i.error == SeatErrorState.PERMISSION_DENIED -> UnknownCause.PERMISSION_DENIED
        !i.linkOk -> UnknownCause.LINK_LOST
        i.error == SeatErrorState.READ_FAILED -> UnknownCause.READ_FAILED
        i.latestBuckled != null && !i.valid -> UnknownCause.INVALID
        i.latestBuckled == null -> UnknownCause.NO_INITIAL_VALUE
        else -> null
    }
    if (cause != null) return SeatResult(SeatStatus.UNKNOWN, cause)
    return if (i.latestBuckled == true) SeatResult(SeatStatus.BUCKLED, null) else SeatResult(SeatStatus.UNBUCKLED, null)
}

/** 완료 판단: 대상 좌석(EXCLUDED 아님)이 1개 이상이고 모두 BUCKLED일 때만 true. 대상 0개면 false(완료 안내 안 함) */
fun isAllBuckled(statuses: Collection<SeatStatus>): Boolean {
    val targets = statuses.filter { it != SeatStatus.EXCLUDED }
    return targets.isNotEmpty() && targets.all { it == SeatStatus.BUCKLED }
}

fun unknownCauseText(c: UnknownCause): String = when (c) {
    UnknownCause.UNSUPPORTED -> "미지원"
    UnknownCause.PERMISSION_DENIED -> "권한 거부"
    UnknownCause.NO_INITIAL_VALUE -> "초기값 미수신"
    UnknownCause.READ_FAILED -> "읽기 실패"
    UnknownCause.LINK_LOST -> "연결 단절"
    UnknownCause.INVALID -> "유효성 판단 실패"
}

/** 좌석별 표시 문구. 신호 범위로만 말하며 착용 상태를 보장하는 표현은 쓰지 않는다 */
fun seatStatusText(r: SeatResult): String = when (r.status) {
    SeatStatus.BUCKLED -> "체결 신호 확인"
    SeatStatus.UNBUCKLED -> "미체결 신호 확인"
    SeatStatus.UNKNOWN -> "확인 불가(${r.cause?.let { unknownCauseText(it) } ?: "원인 미상"})"
    SeatStatus.EXCLUDED -> "확인 대상 아님"
}

/**
 * 의사 점검 목록. 빌드 때 자동 실행되지 않고, 앱 화면·로그에서도 호출하지 않는다.
 * 호출하는 곳이 없으면 컴파일만 된다(빌드 통과는 점검 통과가 아니다). 각 줄은 "OK/FAIL: 설명".
 */
fun seatStatusSelfCheck(): List<String> {
    val out = mutableListOf<String>()
    fun check(name: String, ok: Boolean) { out.add((if (ok) "OK: " else "FAIL: ") + name) }
    fun input(target: Boolean = true, supported: Boolean = true, error: SeatErrorState = SeatErrorState.NONE,
              value: Boolean? = true, valid: Boolean = true, link: Boolean = true) =
        SeatInput(target, supported, error, value, valid, link)

    check("대상 0개 → 완료 아님", !isAllBuckled(emptyList()))
    check("대상 아님만 있으면 완료 아님", !isAllBuckled(listOf(SeatStatus.EXCLUDED)))
    check("한 좌석 UNKNOWN → 완료 아님", !isAllBuckled(listOf(SeatStatus.BUCKLED, SeatStatus.UNKNOWN)))
    check("한 좌석 UNBUCKLED → 완료 아님", !isAllBuckled(listOf(SeatStatus.BUCKLED, SeatStatus.UNBUCKLED)))
    check("모두 BUCKLED → 완료", isAllBuckled(listOf(SeatStatus.BUCKLED, SeatStatus.BUCKLED)))
    check("대상 아님은 집계 제외", isAllBuckled(listOf(SeatStatus.BUCKLED, SeatStatus.EXCLUDED)))
    check("값 없음 → UNKNOWN(NO_INITIAL_VALUE)", computeSeatStatus(input(value = null)) == SeatResult(SeatStatus.UNKNOWN, UnknownCause.NO_INITIAL_VALUE))
    check("대상 아님 → EXCLUDED", computeSeatStatus(input(target = false)) == SeatResult(SeatStatus.EXCLUDED, null))
    check("true → BUCKLED", computeSeatStatus(input(value = true)) == SeatResult(SeatStatus.BUCKLED, null))
    check("false → UNBUCKLED", computeSeatStatus(input(value = false)) == SeatResult(SeatStatus.UNBUCKLED, null))
    check("미지원 → UNKNOWN(UNSUPPORTED)", computeSeatStatus(input(supported = false)).cause == UnknownCause.UNSUPPORTED)
    check("읽기 실패 → UNKNOWN(READ_FAILED), 미체결로 바꾸지 않음", computeSeatStatus(input(error = SeatErrorState.READ_FAILED, value = false)) == SeatResult(SeatStatus.UNKNOWN, UnknownCause.READ_FAILED))
    check("연결 단절 → UNKNOWN(LINK_LOST), 체결로 바꾸지 않음", computeSeatStatus(input(link = false)) == SeatResult(SeatStatus.UNKNOWN, UnknownCause.LINK_LOST))
    check("권한 거부 → UNKNOWN(PERMISSION_DENIED)", computeSeatStatus(input(error = SeatErrorState.PERMISSION_DENIED)).cause == UnknownCause.PERMISSION_DENIED)
    check("유효하지 않은 값 → UNKNOWN(INVALID)", computeSeatStatus(input(valid = false)) == SeatResult(SeatStatus.UNKNOWN, UnknownCause.INVALID))
    return out
}
