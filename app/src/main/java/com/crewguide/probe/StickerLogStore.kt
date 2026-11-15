package com.crewguide.probe

import android.content.Context

/**
 * 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 스티커 기록 문자열 하나만 저장한다.
 * 저장 항목: 프로필 id, 날짜(yyyy-MM-dd), 개수. 시각·위치·이름·식별자는 저장하지 않는다.
 * 2026-11-08 F5·F2: 읽기 실패는 [loadOrNull]이 null로 알리고(빈 로그로 확정하지 않음), 저장은 commit() 결과를 돌려준다.
 * 지우는 방법: clearAll(context), 프로필 삭제·[모두 지우기], 앱 데이터 삭제.
 */
object StickerLogStore {
    const val FILE_NAME = "crewguide_probe_sticker_log"
    const val KEY_LOG = "sticker_log_v1"

    fun load(context: Context): StickerLog = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        StickerLogs.decodeLog(prefs.getString(KEY_LOG, null))
    } catch (e: Exception) {
        StickerLog()
    }

    /** 읽기 실패(예외·버전 불일치·손상)면 null. 저장값이 없으면 빈 로그 */
    fun loadOrNull(context: Context): StickerLog? = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        StickerLogs.decodeLogOrNull(prefs.getString(KEY_LOG, null))
    } catch (e: Exception) {
        null
    }

    /**
     * 스티커 기록은 보상 정합성에 쓰이는 중요 기록이라 commit()으로 디스크 쓰기 결과를 확인한다(짧은 문자열 하나).
     * true = 쓰기 성공 보고, false = 실패·예외. 실패는 화면 흐름을 막지 않지만 호출자가 '기록됨'으로 표시하지 않는다.
     */
    @android.annotation.SuppressLint("ApplySharedPref")   // 의도: 중요 기록의 저장 결과 확인(F2)
    fun save(context: Context, log: StickerLog): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOG, StickerLogs.encodeLog(log)).commit()
    } catch (e: Exception) {
        false
    }

    /** 2026-11-08 Unit 6 최종: 이동 세션(TripSessionRule.encode) 키. 스티커 기록과 같은 파일이라 둘을 한 번의 commit으로 쓸 수 있다 */
    const val KEY_TRIP = "trip_session_v1"

    /** 이동 세션 읽기. 저장값 없음 = 기본값, 손상·예외 = null(읽기 실패) */
    fun loadTripOrNull(context: Context): TripSession? = try {
        TripSessionRule.decodeOrNull(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY_TRIP, null))
    } catch (e: Exception) {
        null
    }

    /** 이동 세션만 저장(끝내기·주행 관측). commit 결과를 돌려준다 */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveTrip(context: Context, s: TripSession): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRIP, TripSessionRule.encode(s)).commit()
    } catch (e: Exception) {
        false
    }

    /** 2026-11-10 M06: 하차 한 줄 소진 이동 번호. 프로세스 재시작 뒤에도 같은 이동에서 다시 나오지 않게 한다 */
    const val KEY_EXIT = "exit_consumed_trip_v1"

    fun loadExitConsumedTrip(context: Context): Int = try {
        TripSessionRule.decodeExitTrip(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY_EXIT, null))
    } catch (e: Exception) {
        -1
    }

    /** commit 결과를 돌려준다(실패해도 이번 프로세스 메모리 값은 유지된다) */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveExitConsumedTrip(context: Context, tripKey: Int): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXIT, TripSessionRule.encodeExitTrip(tripKey)).commit()
    } catch (e: Exception) {
        false
    }

    /** 2026-11-13: 스티커 조건을 채운 이동 번호("s1,이동번호"). 프로세스 재시작 뒤에도 같은 이동의 조건 충족을 잃지 않는다 */
    const val KEY_EARNED = "sticker_earned_trip_v1"

    fun loadEarnedTrip(context: Context): Int = try {
        TripSessionRule.decodeEarnedTrip(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY_EARNED, null))
    } catch (e: Exception) {
        -1
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveEarnedTrip(context: Context, tripKey: Int): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_EARNED, TripSessionRule.encodeEarnedTrip(tripKey)).commit()
    } catch (e: Exception) {
        false
    }

    /** 2026-11-14 PM 결정 30: 하차 마무리(ALL_LEFT)에 도달한 이동 번호("a1,이동번호"). 앱 재시작 뒤 [시작]이 새 이동으로 가게 한다 */
    const val KEY_ALL_LEFT = "all_left_trip_v1"

    /** 없음·손상·예외 = -1(기록 없음) */
    fun loadAllLeftTrip(context: Context): Int = try {
        TripSessionRule.decodeAllLeftTrip(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY_ALL_LEFT, null))
    } catch (e: Exception) {
        -1
    }

    /** 동기 commit(이동 번호 저장과 같은 방식). 결과를 돌려준다(실패해도 이번 프로세스 메모리 값은 유지된다) */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveAllLeftTrip(context: Context, tripKey: Int): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALL_LEFT, TripSessionRule.encodeAllLeftTrip(tripKey)).commit()
    } catch (e: Exception) {
        false
    }

    /**
     * 끝내기(이동 경계) 저장: 새 이동 세션을 쓰고 지난 이동의 하차 한 줄 소진 기록을 같은 commit에서 지운다.
     * 2026-11-10 M14 재감사: 이동 번호가 손상 복구 등으로 다시 같은 번호가 됐을 때 오래된 소진 기록이 새 이동의 안내를 막지 않게.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveTripBoundary(context: Context, s: TripSession): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRIP, TripSessionRule.encode(s))
            .remove(KEY_EXIT)
            .remove(KEY_EARNED)   // 2026-11-13: 지난 이동의 스티커 조건 기록도 함께 지운다
            .remove(KEY_ALL_LEFT)   // 2026-11-14 결정 30: 지난 이동의 하차 마무리 기록도 함께 지운다(새 이동 시작)
            .commit()
    } catch (e: Exception) {
        false
    }

    /** 스티커 기록 + 이동 세션(지급 표시)을 한 번의 commit으로 저장한다. 둘 다 쓰이거나 둘 다 안 쓰인다(SharedPreferences 단일 파일 쓰기) */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveWithTrip(context: Context, log: StickerLog, s: TripSession): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOG, StickerLogs.encodeLog(log))
            .putString(KEY_TRIP, TripSessionRule.encode(s))
            .commit()
    } catch (e: Exception) {
        false
    }

    /** 스티커 기록만 지운다(이동 세션 KEY_TRIP은 남겨 같은 이동 중복 지급을 계속 막는다) */
    fun clearAll(context: Context) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply()
        } catch (e: Exception) {
            // 무시
        }
    }
}
