package com.crewguide.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 2026-11-15 루프 r7 5절(임시 결정 50, 마켓 정책 미확인): 차를 다시 켰을 때(부팅 완료) 빠른 시작 조건이면 '자동 열기' 모드 서비스를 시작한다.
 * 받는 방송: BOOT_COMPLETED(보호 방송이라 시스템만 보낸다. 사용자 10 잠금 해제 뒤 — 에뮬 측정 부팅 뒤 33~35초).
 * LOCKED_BOOT_COMPLETED는 쓰지 않는다(그때는 앱 저장소를 읽을 수 없어 조건을 판단할 수 없다).
 * debug 빌드만: adb 셸 시험용 [ACTION_DEBUG_BOOT](release에서는 무시 — BuildMockPolicy).
 * r8 7절 debug 빌드만: [ACTION_DEBUG_NOTICE] = 조건·FGS 없이 대체 알림(AutoOpenNotice, 배너 시험)만 바로 띄운다. `--ez cancel true`면 지운다.
 * 여기서는 판단과 서비스 시작만 한다(수신기 처리 상한 안). 기다리기·띄우기는 서비스의 AutoOpenWatcher가 한다.
 * 부팅 완료 수신은 Android 12+의 '백그라운드에서 포그라운드 서비스 시작 금지'의 예외다(specialUse 타입, Android 14).
 */
class AutoOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_DEBUG_NOTICE && BuildMockPolicy.MOCK_ALLOWED) {
            if (intent.getBooleanExtra("cancel", false)) AutoOpenNotice.cancel(context.applicationContext, "debug 방송")
            else AutoOpenNotice.show(context.applicationContext, "debug 방송, 앱보임=${AppVisibleStore.visible}")
            return
        }
        val accepted = action == Intent.ACTION_BOOT_COMPLETED || (action == ACTION_DEBUG_BOOT && BuildMockPolicy.MOCK_ALLOWED)
        if (!accepted) return
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val rec = QuickStartStore.load(app)
        val (trip, readFailed) = TripSessionRule.restore(StickerLogStore.loadTripOrNull(app))
        val allLeft = if (readFailed) -1 else StickerLogStore.loadAllLeftTrip(app)
        val quick = QuickStartRule.decide(rec, now, trip.tripKey, allLeft, readFailed)
        val settingOn = AutoOpenStore.load(app)
        val visible = AppVisibleStore.visible
        val start = AutoOpenRule.bootStart(AutoOpenRule.ENABLED, settingOn, quick, visible)
        Log.i(TAG, "[자동열기] $action 받음 부팅후=${SystemClock.elapsedRealtime() / 1000}초 기록=${rec?.kind ?: "없음"}" +
            (rec?.let { " ${(now - it.atWallMs) / 60_000L}분 전" } ?: "") + " 빠른시작=${quick.name} 스위치=${AutoOpenRule.ENABLED}/$settingOn 앱보임=$visible → " +
            if (start) "자동 열기 서비스 시작" else "아무것도 안 함")
        if (!start) return
        try {
            context.startForegroundService(Intent(app, TripKeepAliveService::class.java).setAction(TripKeepAliveService.ACTION_AUTO_OPEN))
        } catch (e: Exception) {   // 시작 제한 등: 자동 열기 없음(사용자가 열면 빠른 시작은 그대로)
            Log.w(TAG, "[자동열기] 서비스 시작 실패 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "G1PROBE"
        /** debug 셸 시험: adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_BOOT -n com.crewguide.probe/.AutoOpenReceiver */
        const val ACTION_DEBUG_BOOT = "com.crewguide.probe.DEBUG_BOOT"
        /** r8 7절 debug 셸 시험: adb shell am broadcast --user 10 -a com.crewguide.probe.DEBUG_NOTICE -n com.crewguide.probe/.AutoOpenReceiver */
        const val ACTION_DEBUG_NOTICE = "com.crewguide.probe.DEBUG_NOTICE"
    }
}
