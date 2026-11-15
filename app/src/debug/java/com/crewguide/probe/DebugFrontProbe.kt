package com.crewguide.probe

import android.content.Context
import android.util.Log

/**
 * 2026-11-14 루프 r5 1절(debug 전용): 앱을 앞으로 올리는 방법별 시험. 앱이 뒤에 있을 때 지연 뒤에 한 방법을 실행한다.
 *   adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_FRONT --es method PENDING_INTENT --el delayMs 12000
 * method: AppFrontLauncher.Method 이름(ACTIVITY | SERVICE | PENDING_INTENT | APP_TASK).
 * 지연은 셸 방송·화면 이탈 직후 유예(10초)의 영향을 빼려고 10초 넘게 둔다. 결과는 logcat(ActivityTaskManager BAL 로그·[수명] onStart)으로 본다.
 * 전체 화면 알림(USE_FULL_SCREEN_INTENT)·moveTaskToFront(REORDER_TASKS)도 시험했으나 앞으로 오지 않아 권한과 함께 뺐다(r5 보고).
 */
object DebugFrontProbe {
    private const val TAG = "G1PROBE"

    fun run(ctx: Context, method: String) {
        Log.i(TAG, "[앞으로시험] method=$method 실행")
        val m = runCatching { AppFrontLauncher.Method.valueOf(method) }.getOrNull()
        if (m == null) { Log.w(TAG, "[앞으로시험] 모르는 방법 $method"); return }
        AppFrontLauncher.launch(ctx, m, "debug 시험")
    }
}
