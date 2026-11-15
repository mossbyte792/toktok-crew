package com.crewguide.probe

/**
 * 무대 좌석 지도용 벨트 참고 신호 모델(순수 로직, Android·SDK 클래스 없음).
 * - 보호자 확인 여부의 판정·차단에 쓰지 않으며, 화면에 '참고 신호'와 출처(실제 수신 / 앱 내부 모의)만 표시한다.
 * - 안전·착용 완료를 단정하는 금지 표현("안전", "확실", "완료", "올바르게", "벨트 체결", "체결됨", "매고 있")을 포함하지 않는다.
 */
enum class SeatBeltRef {
    BUCKLED_SIGNAL,
    UNBUCKLED_SIGNAL,
    UNKNOWN,
    /** 2026-11-11 UI/UX v2(U3): 지원·연결·읽기는 정상인데 아직 받은 값이 없음(UNKNOWN = 미지원·단절·읽기 실패와 구분) */
    NO_SIGNAL,
}

object StageSeatSignal {
    // 출처(모의 여부)와 유효값 수신 여부를 따로 판정한다. isMock==false만으로 '수신'이라고 쓰지 않는다.
    const val SOURCE_REAL_RECEIVED = "벨트 참고: 차량 신호 수신"
    const val SOURCE_REAL_PARTIAL = "벨트 참고: 일부 신호 미확인"
    const val SOURCE_REAL_NONE = "벨트 참고: 신호 미확인"
    const val SOURCE_MOCK = "벨트 참고: 앱 내부 모의 신호"
    // 2026-11-10 M05: debug 주입(DEBUG_BELT) 값이 하나라도 섞이면 실제 수신으로 쓰지 않는다(release에는 주입 경로 없음)
    const val SOURCE_DEBUG_INJECT = "벨트 참고: 디버그 주입 포함(차량 신호 아님)"

    const val REF_BUCKLED_SHORT = "체결 신호"
    const val REF_UNBUCKLED_SHORT = "미체결 신호"
    const val REF_UNKNOWN_SHORT = "확인 불가"
    const val REF_NO_SIGNAL_SHORT = "신호 없음"

    const val UNBUCKLED_REF_BASE = "선택 좌석에 미체결 참고 신호가 있어요. 직접 한 번 더 살펴봐 주세요"

    // 주/야간 타일 좌상단 참고 점 색(모양 기호와 함께 써서 색만으로 구분하지 않는다)
    const val DAY_BUCKLED_DOT = 0x1B873F
    const val DAY_UNBUCKLED_DOT = 0xD96B16
    const val DAY_UNKNOWN_DOT = 0x7A8494

    const val NIGHT_BUCKLED_DOT = 0x66BB6A
    const val NIGHT_UNBUCKLED_DOT = 0xFFB74D
    const val NIGHT_UNKNOWN_DOT = 0x90A4AE

    /** 선택 여부와 무관하게 각 좌석의 현재 벨트 참고 신호를 계산한다 */
    fun beltRefOf(
        supported: Boolean,
        linkOk: Boolean,
        readFailed: Boolean,
        latestBuckled: Boolean?,
        /** v3 D1(2026-11-11): 이벤트는 왔지만 값이 비어 있음(읽을 수 없는 값). '아직 받은 신호 없음'이 아니라 '확인 불가'로 보인다 */
        emptyValueReceived: Boolean = false,
    ): SeatBeltRef {
        if (!supported || !linkOk || readFailed || emptyValueReceived) return SeatBeltRef.UNKNOWN
        if (latestBuckled == null) return SeatBeltRef.NO_SIGNAL
        return if (latestBuckled) SeatBeltRef.BUCKLED_SIGNAL else SeatBeltRef.UNBUCKLED_SIGNAL
    }

    /**
     * 좌석 지도 아래에 표시할 출처 캡션 한 줄.
     * refs = 지도에 보이는 좌석들의 현재 참고 신호(단절·미지원·읽기 실패는 beltRefOf에서 이미 UNKNOWN).
     * 모의 → 모의 표기, 실제 출처면 유효값이 모두/일부/하나도 없음을 구분한다. 빈 목록은 '신호 미확인'.
     * 게이트·보호자 확인 판정에는 쓰지 않는다(표시 전용).
     */
    fun sourceCaption(isMock: Boolean, refs: Collection<SeatBeltRef>, injected: Boolean = false): String {
        if (isMock) return SOURCE_MOCK
        if (injected) return SOURCE_DEBUG_INJECT
        val valid = refs.count { it != SeatBeltRef.UNKNOWN && it != SeatBeltRef.NO_SIGNAL }
        return when {
            refs.isEmpty() || valid == 0 -> SOURCE_REAL_NONE
            valid == refs.size -> SOURCE_REAL_RECEIVED
            else -> SOURCE_REAL_PARTIAL
        }
    }

    /** 참고 배지 기호(모양만으로 세 상태가 구분되어야 한다: 색에 의존하지 않음) */
    enum class BadgeGlyph { BUCKLE_BAR_DOT, EXCLAMATION, QUESTION, DASH }

    /** 배지 모양 명세: filled=원 안을 상태 색으로 채움, false=속이 빈 고리(테두리만) */
    data class BadgeSpec(val glyph: BadgeGlyph, val filled: Boolean)

    fun badgeSpec(ref: SeatBeltRef): BadgeSpec = when (ref) {
        SeatBeltRef.BUCKLED_SIGNAL -> BadgeSpec(BadgeGlyph.BUCKLE_BAR_DOT, filled = true)
        SeatBeltRef.UNBUCKLED_SIGNAL -> BadgeSpec(BadgeGlyph.EXCLAMATION, filled = true)
        // 신호 미확인: 속이 빈 고리 + 물음표(채운 원·가로줄과 실루엣부터 다르다)
        SeatBeltRef.UNKNOWN -> BadgeSpec(BadgeGlyph.QUESTION, filled = false)
        // 신호 없음: 속이 빈 고리 + 가로 줄(물음표와 기호가 다르다)
        SeatBeltRef.NO_SIGNAL -> BadgeSpec(BadgeGlyph.DASH, filled = false)
    }

    /** 참고 신호 짧은 읽기 이름 */
    fun refLabel(ref: SeatBeltRef): String = when (ref) {
        SeatBeltRef.BUCKLED_SIGNAL -> REF_BUCKLED_SHORT
        SeatBeltRef.UNBUCKLED_SIGNAL -> REF_UNBUCKLED_SHORT
        SeatBeltRef.UNKNOWN -> REF_UNKNOWN_SHORT
        SeatBeltRef.NO_SIGNAL -> REF_NO_SIGNAL_SHORT
    }

    /** StageSeatMap.description 결과 뒤에 참고 신호·점유 참고를 덧붙인다(기본 설명 함수는 건드리지 않음) */
    fun tileDescription(baseDesc: String, ref: SeatBeltRef, occupied: Boolean? = null): String {
        val occPart = when (occupied) {
            true -> ", 탑승 감지"
            false -> ", 빈 좌석"
            null -> ""
        }
        return "$baseDesc, ${refLabel(ref)}$occPart"
    }

    /** 타일 좌상단 참고 배지 채움 색(0xRRGGBB) */
    fun dotColor(ref: SeatBeltRef, night: Boolean): Int = when (ref) {
        SeatBeltRef.BUCKLED_SIGNAL -> if (night) NIGHT_BUCKLED_DOT else DAY_BUCKLED_DOT
        SeatBeltRef.UNBUCKLED_SIGNAL -> if (night) NIGHT_UNBUCKLED_DOT else DAY_UNBUCKLED_DOT
        SeatBeltRef.UNKNOWN, SeatBeltRef.NO_SIGNAL -> if (night) NIGHT_UNKNOWN_DOT else DAY_UNKNOWN_DOT
    }

    /**
     * 보호자가 '예'로 답했을 때, 선택한 대상 좌석 중 미체결 참고 신호가 하나라도 있으면
     * 진행을 막지 않고 덧붙여 보여 줄 단정 없는 참고 안내 문구를 돌려준다(없으면 빈 문자열).
     */
    fun unbuckledNoticeForTargets(targetRefs: Collection<SeatBeltRef>, isMock: Boolean): String {
        if (targetRefs.none { it == SeatBeltRef.UNBUCKLED_SIGNAL }) return ""
        val src = if (isMock) "(앱 내부 모의 참고)" else "(실제 수신 참고)"
        return "$UNBUCKLED_REF_BASE $src"
    }
}
