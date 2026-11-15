package com.crewguide.probe

/** 2026-11-14 DEBUG_OCC 값 해석(debug 전용, 순수). true=앉음, false=빈 자리, unknown=확인 불가(null), clear=주입 해제 */
enum class DebugOccValue(val value: Boolean?) {
    OCCUPIED(true), EMPTY(false), UNKNOWN(null), CLEAR(null), INVALID(null);

    companion object {
        fun parse(raw: String?): DebugOccValue = when (raw?.trim()?.lowercase()) {
            "true" -> OCCUPIED
            "false" -> EMPTY
            "unknown", "null" -> UNKNOWN
            "clear" -> CLEAR
            else -> INVALID
        }
    }
}
