package com.crewguide.probe

import android.content.Context

/**
 * r10 M(개발 7): 이동 중 상태 저장소(이 기기 앱 안, MODE_PRIVATE SharedPreferences, allowBackup=false). 형식·판단은 [TripRestoreRule].
 * 내용이 바뀐 저장·지우기는 commit()(에뮬 강제 종료 직후 apply() 값이 사라진 일이 있었다 — 확인 완료·단계 전환 같은 중요 시점),
 * 내용이 같고 시각만 새로 쓸 때는 apply(). 읽기 실패·깨진 값 = 기록 없음(복원 안 함).
 */
object TripStateStore {
    const val FILE_NAME = "crewguide_probe_trip_state"
    const val KEY = "trip_state_m1"

    fun load(context: Context): TripStateRecord? = try {
        TripRestoreRule.decode(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getString(KEY, null))
    } catch (e: Exception) { null }

    /** [important] = 내용이 바뀜(commit, 결과를 돌려준다). false면 시각만 갱신(apply, 늘 true) */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun save(context: Context, rec: TripStateRecord, important: Boolean): Boolean = try {
        val e = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putString(KEY, TripRestoreRule.encode(rec))
        if (important) e.commit() else { e.apply(); true }
    } catch (e: Exception) { false }

    @android.annotation.SuppressLint("ApplySharedPref")
    fun clear(context: Context): Boolean = try {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    } catch (e: Exception) { false }
}

/** 프로세스 상태(메인 스레드 전용). Activity 재생성에도 유지되고 프로세스가 끝나면 사라진다 */
object TripRestoreSession {
    /** 이 프로세스에서 복원 판단을 했는가(판단 전에는 저장·지우기를 하지 않는다 — 시작 직후 빈 메모리로 지난 기록을 지우지 않게) */
    var checked = false
    /** 이 프로세스 시작 때 이동 상태를 되살렸는가(빠른 시작 판단을 건너뛴다) */
    var restored = false
    /**
     * r10 수정 2(QA 관찰②): 되살린 뒤 첫 유효 기어를 볼 때까지 저장을 막는다(지우기는 막지 않음). 되살린 기록을 그대로 둬서
     * 그 사이 다시 꺼져도 같은 기록으로 판단한다. noteRestoredGear가 푼다
     */
    var holdSave = false
    /** 되살린 상태(첫 유효 기어 경로 로그용, 기어를 본 뒤 null) */
    var awaitingGear: TripRestored? = null
    /** 마지막으로 쓴 기록(없으면 null = 저장소에 기록 없음으로 본다) */
    var lastSaved: TripStateRecord? = null
}
