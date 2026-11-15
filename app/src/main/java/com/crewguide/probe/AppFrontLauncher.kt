package com.crewguide.probe

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 2026-11-14 루프 r5 1절: 앱(MainActivity)을 화면 앞으로 올리는 방법 모음. 언제 올릴지는 [AppFrontRule]이 정한다.
 *
 * Android 14는 뒤에 있는 앱의 화면 시작(BAL)을 제한한다. Pleos 에뮬레이터(API 34, 2026-11-14, 이동 중 FGS 켜짐, 내비가 앞) 시험:
 *  - PENDING_INTENT·ACTIVITY·SERVICE(startActivity 계열): 대부분 "Background activity launch blocked" + (BAL_BLOCK) result code=3으로
 *    로그가 남지만 앱은 약 0.1초 뒤 onStart로 앞으로 왔다(PI 실제 흐름 3/3, ACTIVITY 3/3, SERVICE 1/1). PI 시험 1회·ACTIVITY 1회는 BAL_ALLOW_FOREGROUND.
 *    → 막힘 로그에도 앞으로 오는 것은 Pleos 다중 창 동작으로 보이며(가정) 실차·다른 빌드에서는 막힐 수 있다. 그래서 2초 뒤 확인·재시도·대체 알림을 둔다.
 *  - APP_TASK(AppTask.moveToFront)·moveTaskToFront(REORDER_TASKS)·전체 화면 알림(USE_FULL_SCREEN_INTENT): 앞으로 오지 않음 ❌
 * [DEFAULT]는 PI(보낸 쪽 BAL 허용 옵션을 명시해 플랫폼이 허용할 여지가 가장 큼), [RETRY]는 startActivity.
 * 내비 앱을 끝내거나 키 입력을 넣는 우회, SYSTEM_ALERT_WINDOW(사용자가 설정에서 따로 허용)는 쓰지 않는다.
 * 모든 방법은 예외를 삼키고 false를 돌려준다(실패하면 호출자가 대체 알림을 낸다).
 */
object AppFrontLauncher {
    private const val TAG = "G1PROBE"

    enum class Method {
        /** Activity(또는 앱) Context에서 startActivity(NEW_TASK | REORDER_TO_FRONT) */
        ACTIVITY,
        /** 이동 중 foreground service(TripKeepAliveService)에서 startActivity */
        SERVICE,
        /** PendingIntent.send + ActivityOptions.setPendingIntentBackgroundActivityStartMode(ALLOWED) */
        PENDING_INTENT,
        /** ActivityManager.AppTask.moveToFront(자기 작업) */
        APP_TASK,
    }

    /** 기본 방법(위 시험 표) */
    val DEFAULT = Method.PENDING_INTENT
    /** 기본 방법 뒤 [AppFrontRule.VERIFY_MS] 안에 앱이 보이지 않으면 한 번 더 시도하는 방법 */
    val RETRY = Method.ACTIVITY

    fun frontIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun launch(ctx: Context, method: Method, why: String): Boolean = try { tryLaunch(ctx, method, why) } catch (e: Exception) {
        Log.w(TAG, "[앞으로] ${method.name} 실패($why) ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun tryLaunch(ctx: Context, method: Method, why: String): Boolean {
        when (method) {
            Method.ACTIVITY -> ctx.startActivity(frontIntent(ctx))
            Method.SERVICE -> {
                // 서비스가 켜져 있을 때만(이동 중). 이미 켜진 서비스에 명령만 보낸다(startForegroundService 아님 → 새 서비스 시작 없음)
                if (TripKeepAlive.run != KeepAliveRun.ON) { Log.i(TAG, "[앞으로] SERVICE: 이동 서비스 꺼짐 → 시도 안 함"); return false }
                ctx.startService(Intent(ctx, TripKeepAliveService::class.java).setAction(TripKeepAliveService.ACTION_FRONT))
            }
            Method.PENDING_INTENT -> {
                val pi = PendingIntent.getActivity(ctx, REQ_FRONT, frontIntent(ctx),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val opts = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            }
            Method.APP_TASK -> {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val task = am?.appTasks?.firstOrNull()
                if (task == null) { Log.i(TAG, "[앞으로] APP_TASK: 앱 작업 없음"); return false }
                task.moveToFront()
            }
        }
        Log.i(TAG, "[앞으로] ${method.name} 호출함($why)")
        return true
    }

    private const val REQ_FRONT = 7102
}
