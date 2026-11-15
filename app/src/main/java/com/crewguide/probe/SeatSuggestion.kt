package com.crewguide.probe

/**
 * 지난번에 고른 좌석 이름 집합의 직렬화·복원(순수 로직, Android 클래스 사용 없음).
 * 형식은 고정: "v1;R2L,R2C". 좌석 이름 외의 값은 담지 않는다.
 * 복원 결과는 추천 표시용일 뿐 현재 탑승의 증거가 아니며 자동 선택에 쓰지 않는다.
 */
object SeatSuggestion {
    const val PREFIX = "v1;"
    const val MAX_LENGTH = 64   // 정상 최대 길이("v1;R1L,R1R,R2L,R2C,R2R")보다 넉넉한 상한
    val ALLOWED: List<String> = listOf("R1L", "R1R", "R2L", "R2C", "R2R")

    /** 2026-11-14 PM 결정 23: 아이 자리는 뒷줄만. 추천 기록에서 앞줄 이름(R1L·R1R)을 뺀다(뒷줄로 옮기지 않음) */
    fun rearOnly(seats: Set<String>): Set<String> = seats.filter { it.startsWith("R2") }.toSet()

    /** 허용된 이름만 고정 순서(ALLOWED 순서)로 직렬화한다. 빈 집합이면 빈 문자열 */
    fun encode(seats: Set<String>): String {
        val kept = ALLOWED.filter { it in seats }
        return if (kept.isEmpty()) "" else PREFIX + kept.joinToString(",")
    }

    /** 예외 없이 복원한다. null·빈 값·너무 긴 값·접두어 불일치는 빈 집합, 알 수 없는 이름·중복·빈 항목은 걸러낸다 */
    fun decode(raw: String?): Set<String> {
        if (raw == null || raw.isEmpty() || raw.length > MAX_LENGTH) return emptySet()
        if (!raw.startsWith(PREFIX)) return emptySet()
        val body = raw.substring(PREFIX.length)
        if (body.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for (part in body.split(",")) {
            if (part in ALLOWED) result.add(part)
        }
        return result
    }
}
