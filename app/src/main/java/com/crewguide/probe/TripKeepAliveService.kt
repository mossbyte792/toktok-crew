package com.crewguide.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 2026-11-13 ISSUE-07(사용자 결정 D-1): 이동 중(확인 완료 ~ 이동 끝)에만 켜는 foreground service.
 *
 * 하는 일은 프로세스를 '포그라운드 서비스 보유' 상태로 올려 Android 14 cached-apps freezer 동결을 막는 것뿐이다.
 * 차량·내비·TTS 리스너의 주인은 지금처럼 MainActivity이고, 이 서비스는 SDK를 부르지 않는다. 위치·녹음·네트워크도 쓰지 않는다.
 * 타입은 specialUse(매니페스트 PROPERTY_SPECIAL_USE_FGS_SUBTYPE에 정직한 설명). mediaPlayback이 아닌 이유는 이 앱이 미디어를
 * 재생하지 않고(안내 음성은 Pleos TTS 서비스가 다른 프로세스에서 낸다) 대부분 조용히 기다리기 때문이다.
 *
 * 알림: 중요도 낮은 채널(소리·진동·heads-up 없음), 상시. 누르면 앱이 열린다. 문구는 신호 기준·짧게.
 * 시작·종료 판단은 [TripKeepAliveRule](순수), 호출은 MainActivity.syncKeepAlive.
 *
 * 2026-11-15 루프 r7 5절(임시 결정 50): '자동 열기' 모드([ACTION_AUTO_OPEN]). 부팅 수신기([AutoOpenReceiver])가 빠른 시작 조건일 때만 시작한다.
 * 이 모드에서는 [AutoOpenWatcher]가 기어·플랫폼 제한·내비 경로 상태를 읽고(읽기만) 앱을 앞으로 띄운다. 이동 모드(TripKeepAlive.run)와 따로 센다:
 * 이동 모드 시작 명령이 오면 감시자를 놓고 이동 알림으로 바뀐다. 감시자가 끝나면 이동 모드가 아닐 때만 서비스를 끝낸다.
 * r10 B: 부팅 수신기를 매니페스트에서 빼서 자동 열기 모드는 시작되지 않는다(코드만 남음).
 * r10 H: 상한(확인 완료 뒤 출발 없음 10분·정차 30분, [TripCapRule])은 MainActivity가 재고 이 서비스를 끝낸다(서비스에는 시계 없음).
 */
class TripKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 2026-11-14 루프 r5 1절: 이미 포그라운드인 서비스에 '앱을 앞으로' 명령만 온 경우(AppFrontLauncher.Method.SERVICE)
        if (intent?.action == ACTION_FRONT) {
            if (TripKeepAlive.run == KeepAliveRun.ON) {
                runCatching { startActivity(AppFrontLauncher.frontIntent(this)) }
                    .onSuccess { Log.i(TAG, "[앞으로] 서비스에서 startActivity 호출함") }
                    .onFailure { Log.w(TAG, "[앞으로] 서비스 startActivity 실패 ${it.javaClass.simpleName}: ${it.message}") }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_AUTO_OPEN) return startAutoOpen()
        // 이동 모드 시작: 자동 열기 감시자가 돌고 있으면 놓는다(이동 알림이 같은 번호로 대신한다)
        stopAutoOpenWatcher("이동 모드 시작")
        // startForegroundService 뒤에는 무조건 startForeground를 먼저 부른다(늦거나 빠지면 플랫폼이 앱을 강제 종료한다)
        val ok = try {
            ensureChannel(this)
            val n = buildNotification(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
            true
        } catch (e: Exception) {
            Log.w(TAG, "[FGS] startForeground 실패 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!ok) { TripKeepAlive.run = KeepAliveRun.OFF; stopSelf(); return START_NOT_STICKY }
        TripKeepAlive.run = KeepAliveRun.ON
        if (!TripKeepAlive.wanted) {   // 시작 중에 종료가 요청됐다: startForeground 뒤에 스스로 끝낸다(알림 남기지 않음)
            Log.i(TAG, "[FGS] stop 사유=시작 중 종료 요청")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "[FGS] 서비스 포그라운드 진입(specialUse)")
        // 프로세스가 죽으면 다시 살리지 않는다(이동 상태는 프로세스 메모리라 되살려도 할 일이 없다)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 사용자가 최근 앱에서 앱을 지우면 서비스·알림도 남기지 않는다
        Log.i(TAG, "[FGS] stop 사유=작업 제거")
        TripKeepAlive.wanted = false
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ---- r7 5절 자동 열기 모드 ----
    private var autoOpen: AutoOpenWatcher? = null

    private fun startAutoOpen(): Int {
        // startForegroundService로 왔으므로 어떤 경우에도 startForeground를 먼저 부른다
        val ok = try {
            ensureChannel(this)
            val n = buildNotification(this, AutoOpenCopy.NOTICE_WAIT)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
            true
        } catch (e: Exception) {
            Log.w(TAG, "[자동열기] startForeground 실패 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!ok) { if (TripKeepAlive.run == KeepAliveRun.OFF) stopSelf(); return START_NOT_STICKY }
        if (TripKeepAlive.run != KeepAliveRun.OFF || TripKeepAlive.wanted) {
            // 이미 이동 모드다(드묾): 이동 알림으로 되돌리고 자동 열기는 하지 않는다
            Log.i(TAG, "[자동열기] 이동 모드 중 → 하지 않음")
            runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(this)) }
            return START_NOT_STICKY
        }
        if (autoOpen != null) { Log.i(TAG, "[자동열기] 이미 진행 중"); return START_NOT_STICKY }
        Log.i(TAG, "[자동열기] 서비스 포그라운드 진입(specialUse, 알림=${AutoOpenCopy.NOTICE_WAIT})")
        autoOpen = AutoOpenWatcher(this,
            onStop = { why, keepNotice -> if (!keepNotice) AutoOpenNotice.cancel(this, why); finishAutoOpen(why) },
            onFallback = { showAutoOpenFallback() },
        ).also { it.start() }
        return START_NOT_STICKY
    }

    /**
     * 대체 알림. r8 7절: [AutoOpenRule.FALLBACK_HEADS_UP]면 새 채널(HIGH, 소리·진동 없음)·따로 번호로 배너를 시험하고(FGS 알림은 '다시 출발 준비 중' 그대로),
     * 아니면 r7처럼 FGS 알림 제목만 바꾼다(알림 센터에서만 보임)
     */
    private fun showAutoOpenFallback() {
        if (AutoOpenRule.FALLBACK_HEADS_UP) AutoOpenNotice.show(this, "띄우기 실패 대체")
        else runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(this, AutoOpenCopy.NOTICE_FALLBACK)) }
    }

    private fun stopAutoOpenWatcher(why: String) {
        val w = autoOpen ?: return
        autoOpen = null
        w.release()
        Log.i(TAG, "[자동열기] 감시 끝($why)")
    }

    /** 감시자가 끝낼 때: 이동 모드가 아니면 서비스·알림도 끝낸다(이동 모드면 알림은 이동 알림이 그대로) */
    private fun finishAutoOpen(why: String) {
        stopAutoOpenWatcher(why)
        if (TripKeepAlive.run == KeepAliveRun.OFF && !TripKeepAlive.wanted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            Log.i(TAG, "[자동열기] stop 사유=$why")
        }
    }

    override fun onDestroy() {
        stopAutoOpenWatcher("서비스 종료")
        TripKeepAlive.run = KeepAliveRun.OFF
        arrivedNoticeShown = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }   // 알림이 남지 않게(서비스 종료로도 지워지지만 명시)
        Log.i(TAG, "[FGS] 서비스 종료(알림 제거)")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "G1PROBE"
        const val CHANNEL_ID = "trip_keepalive"
        const val NOTIF_ID = 7101
        /**
         * 알림 문구(신호 기준, 짧게). 보증 표현 없음.
         * 2026-11-14 루프 r1 K1: Pleos 알림의 둘째 줄(본문)이 아주 흐려 읽기 어려워 둘째 줄을 없애고 제목 한 줄에 담는다
         */
        const val TEXT_TITLE = "톡톡크루 이동 안내 중 · 누르면 열려요"
        const val CHANNEL_NAME = "이동 안내 중 표시"
        /** 루프 r5 1절: 이미 켜진 서비스에 앱을 앞으로 올리라는 명령 */
        const val ACTION_FRONT = "com.crewguide.probe.action.TRIP_FRONT"
        /** r7 5절(임시 결정 50): 부팅 수신기가 보내는 '자동 열기' 모드 시작 명령(외부 노출 없음, exported=false 서비스) */
        const val ACTION_AUTO_OPEN = "com.crewguide.probe.action.AUTO_OPEN"
        /**
         * 루프 r5 1절 대체 알림: 앱을 앞으로 올리지 못했을 때(뒤 화면 시작 제한 등) 같은 이동 알림의 제목만 바꾼다(누르면 앱이 열림).
         * 기존 채널(소리·진동 없음)을 그대로 쓴다. 신호 기준 문구
         */
        const val TEXT_TITLE_ARRIVED = "톡톡크루 · 정차 신호를 받았어요 · 누르면 하차 확인 화면이 열려요"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "이동 중에만 보이는 알림이에요. 소리와 진동이 없어요."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        /** 대체 알림이 지금 떠 있는가(앱이 보이면 원래 제목으로 되돌린다). 메인 스레드 전용 */
        var arrivedNoticeShown = false; private set

        /**
         * 대체 알림: 서비스가 켜져 있으면 이동 알림 제목을 [TEXT_TITLE_ARRIVED](arrived=true) 또는 원래 [TEXT_TITLE]로 바꾼다.
         * 바꿨으면 true. 원래 제목으로 되돌리기는 대체 알림이 떠 있을 때만 한다
         */
        fun showNotice(ctx: Context, arrived: Boolean): Boolean {
            if (TripKeepAlive.run != KeepAliveRun.ON) { arrivedNoticeShown = false; return false }
            if (!arrived && !arrivedNoticeShown) return false
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
            val ok = runCatching { nm.notify(NOTIF_ID, buildNotification(ctx, if (arrived) TEXT_TITLE_ARRIVED else TEXT_TITLE)); true }.getOrDefault(false)
            if (ok) arrivedNoticeShown = arrived
            return ok
        }

        fun buildNotification(ctx: Context, title: String = TEXT_TITLE): Notification {
            val open = Intent(ctx, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_trip)
                .setContentTitle(title)   // K1: 본문(둘째 줄) 없음
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
