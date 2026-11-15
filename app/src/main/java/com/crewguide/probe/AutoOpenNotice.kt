package com.crewguide.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 2026-11-15 루프 r8 7절(임시 결정 50 후속): 자동 열기 대체 알림 "톡톡크루 · 눌러서 출발 전 확인을 시작해요"를 **화면 위 배너(heads-up)**로 띄워 보는 시험.
 * r7까지는 이동 채널(trip_keepalive, IMPORTANCE_LOW)의 FGS 알림 제목만 바꿔서 화면에 아무것도 안 보였다(검수 세션 캡처).
 *
 * - 대체 알림만 새 채널 [CHANNEL_ID](IMPORTANCE_HIGH, 소리·진동 없음)과 따로 번호 [NOTIF_ID]로 낸다. FGS 알림("다시 출발 준비 중")은 이동 채널 그대로.
 * - 누르면 앱이 열린다(사용자 누름이라 뒤 화면 시작 제한 대상 아님). 누르면 사라지고(autoCancel), [TIMEOUT_MS] 뒤 저절로 사라진다.
 * - 앱이 보이거나 주행 기어를 받거나 빠른 시작 조건이 사라지면 서비스가 [cancel]한다.
 * - 알림 권한(POST_NOTIFICATIONS)이 거부됐거나 사용자가 이 채널을 껐으면 보이지 않는다(로그만).
 * 되돌리기: [AutoOpenRule.FALLBACK_HEADS_UP] = false → r7 방식(FGS 알림 제목만 바꿈, 알림 센터에서만 보임).
 */
object AutoOpenNotice {
    private const val TAG = "G1PROBE"
    const val CHANNEL_ID = "quick_restart_notice"
    const val CHANNEL_NAME = "다시 출발 전 확인 알림"
    /** 이동 알림(7101)과 다른 번호. 7102는 AppFrontLauncher PendingIntent 요청 번호 */
    const val NOTIF_ID = 7103
    /** 저절로 사라지는 시간(자동 열기 상한과 같게) */
    const val TIMEOUT_MS = AutoOpenRule.MAX_RUN_MS

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "다시 탄 지 60분 안에 차를 켰을 때, 출발 전 확인을 여는 알림이에요. 소리와 진동이 없어요."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun build(ctx: Context): Notification {
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, REQ_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(AutoOpenCopy.NOTICE_FALLBACK)   // 한 줄(Pleos 알림 둘째 줄은 흐림, r1 K1)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
    }

    /** 대체 알림을 띄운다. 띄웠으면 true(권한 거부·채널 꺼짐이면 false, 로그) */
    fun show(ctx: Context, why: String): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            ensureChannel(ctx)
            val allowed = nm.areNotificationsEnabled()
            val chOn = nm.getNotificationChannel(CHANNEL_ID)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } ?: false
            nm.notify(NOTIF_ID, build(ctx))
            Log.i(TAG, "[자동열기] 대체 알림 띄움($why) 채널=$CHANNEL_ID(HIGH) 알림허용=$allowed 채널켜짐=$chOn")
            allowed && chOn
        } catch (e: Exception) {
            Log.w(TAG, "[자동열기] 대체 알림 실패($why) ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun cancel(ctx: Context, why: String) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        Log.i(TAG, "[자동열기] 대체 알림 지움($why)")
    }

    private const val REQ_OPEN = 7104
}
