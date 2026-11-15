package com.crewguide.probe

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * debug 전용 디자인 미리보기(2026-11-13 디자인 담당). 가짜 [StageUiState]·[AutoCheckUi]로 무대([StageScreen])나 홈([HomeScreen])을 띄워
 * 캡처한다. 차량·음성·저장 상태를 바꾸지 않는다(홈은 저장값을 읽기만 한다). 화면 왼쪽 위에 "디자인 미리보기(모의)"를 항상 표시한다.
 *
 * adb 예(앱은 user 10):
 *   am start --user 10 -n com.crewguide.probe/.DesignPreviewActivity --es scene AUTO_CHECK --es variant mixed
 *   고정 틀 1920x720 + PNG 저장: 위 명령에 --es frame 1920x720 --es snap after_AUTO_CHECK_day --ei snapMs 2500
 *   → run-as com.crewguide.probe --user 10 cat files/design_shots/after_AUTO_CHECK_day.png
 * scene: FIRSTRUN(variant 1|2|3, 다시 보기 r1|r2|r3), KIDS(설정 아이 관리 카드, variant input = 별명 입력 열림), HOME, PICK, READY, QUESTION, EXIT_CHECK, LOCKED, AUTO_CHECK, DEPART_READY, ARRIVED, ALL_LEFT,
 *        SEATBOARD(시안, variant checking|confirmed|arrived|allleft — [SeatBoardMock])
 * variant: [DesignPreviewStates] 참고.
 * 말풍선 기록(지시 4): --ei bubbles 2 → 장면별 앞선 문장 2개를 넣고 지금 장면 문장을 0.5초 뒤 쌓는다(캡처는 snapMs 3000 이상).
 * 스티커 별 연출(2026-11-14 디자인 C, ALL_LEFT sticker): 그냥 띄우면 실제처럼 한 번 재생된다(약 2.7초, [StickerFly]).
 *   --ei stickerAt <ms> → 재생을 멈추고 그 시점 그림에 세워 둔다(시작·중간·끝 캡처용, 예 250·700·1400·2100·2700). snapMs는 1500 이상.
 *
 * 고정 틀(frame) 이유: Pleos 에뮬레이터는 앱을 홈 런처의 분할 창(약 1682x1210)에 띄우고, `wm size 1920x720`으로 바꿔도
 * 앱 창이 1920x720이 되지 않았다(2026-11-13 확인). 그래서 앱 창 안에 정확히 1920x720 px(density 1.0이면 dp와 같음) 틀을 만들고
 * 그 안에 실제 View를 배치·그린 결과를 그대로 PNG로 저장한다. 틀은 창에 맞게 축소해 보여 주기만 하고(배치는 1920x720 그대로),
 * PNG는 축소 전 원래 크기로 그린다. 시스템 인셋은 틀 안으로 넘기지 않는다(무대의 고정 여백 16dp 경로).
 */
class DesignPreviewActivity : Activity() {
    private var stage: StageScreen? = null
    private var model: DesignPreviewStates.Model? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Palette.select(night)
        val scene = intent.getStringExtra("scene") ?: "AUTO_CHECK"
        val variant = intent.getStringExtra("variant") ?: ""
        val frameSpec = intent.getStringExtra("frame")
        val snapName = intent.getStringExtra("snap")
        val snapMs = intent.getIntExtra("snapMs", 2500).toLong()
        // 2026-11-14 지시 4: 이전 말풍선 수(0~2). 장면별 앞선 문장을 기록에 넣어 쌓인 모습을 캡처한다(PreviewHooks.bubbleLines)
        val prevBubbles = intent.getIntExtra("bubbles", 0)
        // 2026-11-14 디자인 C: 스티커 별 연출을 이 시점(ms)에 세워 둔다(없으면 -1 = 실제처럼 재생)
        val stickerAt = intent.getIntExtra("stickerAt", -1)

        val root = FrameLayout(this)
        // 고정 틀: 정확한 px 크기. 없으면 창 전체
        val frameWH = frameSpec?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        val frame = FrameLayout(this)
        if (frameWH != null) {
            frame.setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
            frame.pivotX = 0f; frame.pivotY = 0f
            root.addView(frame, FrameLayout.LayoutParams(frameWH[0], frameWH[1], Gravity.TOP or Gravity.START))
            root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val k = minOf(1f, (r - l).toFloat() / frameWH[0], (b - t).toFloat() / frameWH[1])
                frame.scaleX = k; frame.scaleY = k
            }
        } else {
            root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        if (scene == "FIRSTRUN") {
            // 첫 실행 안내 1/3·2/3·3/3(variant = 1|2|3). 버튼은 아무것도 하지 않는다(확인 기록·화면 전환 없음, 저장값 불변)
            // 2026-11-14: variant r1|r2|r3 = 설정 [처음 안내 다시 보기]로 연 안내(맨 위 줄 [닫기]). [닫기]도 아무것도 하지 않는다
            val fr = FirstRunScreen(this, onAck = {}, onRegister = {}, onGuest = {}, onSettings = {}, onClose = {})
            val replay = variant.startsWith("r")
            fr.show(((variant.removePrefix("r").toIntOrNull() ?: 1) - 1).coerceIn(0, 2), replay)
            frame.addView(fr, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (scene == "SEATBOARD") {
            // 2026-11-13 좌석 화면 하나 시안(variant = checking|confirmed|arrived|allleft). 자리 누름·보호자 확인은 가짜 상태만 바꾼다
            val sb = SeatBoardMockScreen(this, SeatBoardMock.model(variant.ifEmpty { "checking" }))
            frame.addView(sb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (scene == "KIDS") {
            // 2026-11-14 루프 r4: 설정 '아이 관리' 카드만(설정 전체는 MainActivity.buildSettingsLayout이 만들어 미리보기에 없다).
            // 설정과 같은 2단 폭(왼쪽 1.15 : 오른쪽 1)의 왼쪽 열에 둔다. variant input = [새 아이 등록]을 눌러 별명 입력 칸이 열린 상태.
            // 저장값(등록한 아이)은 읽기만 한다. 입력 칸에서 [등록]을 누르면 실제로 저장되므로 캡처용으로만 쓴다
            val dpf = { v: Int -> DuoDraw.dp(this, v) }
            // MainActivity와 같은 값(Readability는 MainActivity.kt 안 private이라 숫자를 옮겨 적음: 간격 8, 보조 20sp, 스위치 24sp, 터치 76dp)
            val panel = ChildProfilePanel(this, dpf, dpf(8), 20f, 24f,
                dpf(DesignRules.MIN_TOUCH), Palette.argb(Palette.current.text), currentStyle = { CharacterStyle.DEFAULT }, currentTone = { ToneSetting.DEFAULT })
            val card = DuoCard(this, SettingsCopy.KIDS_TITLE, SettingsCopy.KIDS_DESC)
            card.addView(panel.root)
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpf(8), dpf(8), dpf(8), dpf(8)) }
            cols.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, SettingsLayout.LEFT_WEIGHT).apply { rightMargin = dpf(DuoTokens.CARD_GAP_DP) })
            cols.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, SettingsLayout.RIGHT_WEIGHT))
            val scroll = android.widget.ScrollView(this).apply { setBackgroundColor(Palette.argb(DuoTokens.roles(Palette.current.night).bg)); addView(cols) }
            frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            panel.update(true, true, 0)
            if (variant == "input") panel.expandForRegister()
        } else if (scene == "HOME") {
            val home = HomeScreen(this, onStart = {}, onSettings = {}, onCustomize = {})
            frame.addView(home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            home.post { home.setMotionGate(VehicleGate.PARK_ALLOWED) }
            // 2026-11-14 홈 말풍선 실측: 캡처와 같은 때 말풍선·캐릭터 경계(겹침·틈·여백·캐릭터 수)를 <snap>.home.txt로 남긴다
            if (snapName != null) handler.postDelayed({
                try {
                    val dir = File(filesDir, "design_shots").apply { mkdirs() }
                    File(dir, "$snapName.home.txt").writeText(home.layoutReport(), Charsets.UTF_8)
                } catch (e: Exception) { android.util.Log.w("DesignPreview", "home report failed $snapName", e) }
            }, snapMs)
        } else {
            val m = DesignPreviewStates.model(scene, variant)
            model = m
            val s = StageScreen(this, onClose = {}, actions = PreviewActions(m) { render() }, showCloseButton = false)
            stage = s
            // 개발 담당의 sceneOf가 새 장면으로 아직 들어가지 않으므로(PM 자리표시) 미리보기는 장면을 고정한다. 기존 장면도 같은 방식
            s.previewScene = try { Scene.valueOf(scene) } catch (_: IllegalArgumentException) { null }
            frame.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            PreviewHooks.afterCreate(s, m)
            if (prevBubbles > 0) s.previewBubbles(m.auto?.phase, PreviewHooks.bubbleLines(scene).takeLast(prevBubbles))
            s.post { render() }
        }
        val tag = TextView(this).apply {
            text = "디자인 미리보기(모의) · $scene${if (variant.isNotEmpty()) " · $variant" else ""}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * resources.displayMetrics.density)   // 글꼴 배율과 무관(캡처 표시용)
            typeface = Typeface.DEFAULT_BOLD
            val r = DuoTokens.roles(night)
            setTextColor(Palette.argb(r.text))
            background = DuoDraw.round(r.surface, 12f * resources.displayMetrics.density, (2 * resources.displayMetrics.density).toInt(), r.surfaceStroke)
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(tag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            // 왼쪽 위: 무대 아래 띠(모의 신호 표시)와 겹치지 않게 한다(2026-11-13 캡처에서 겹침 확인 후 이동)
            val m = (4 * resources.displayMetrics.density).toInt(); leftMargin = m; topMargin = m
        })
        setContentView(root)
        if (stickerAt >= 0) handler.postDelayed({ findSeatStage(frame)?.stickerPreviewAt(stickerAt.toLong()) }, STICKER_FREEZE_DELAY_MS)
        if (snapName != null) handler.postDelayed({ snap(frame, snapName) }, snapMs)
    }

    /** 틀 안에서 좌석 화면 무대([SeatBoardStage])를 찾는다(StageScreen이 안에 갖고 있어 밖에서 직접 닿지 않는다) */
    private fun findSeatStage(v: View): SeatBoardStage? {
        if (v is SeatBoardStage) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSeatStage(v.getChildAt(i))?.let { return it }
        return null
    }

    /** 틀을 원래 크기(축소 전)로 그려 files/design_shots/<name>.png에 저장한다. 실패하면 로그만 남긴다 */
    private fun snap(v: View, name: String) {
        try {
            if (v.width <= 0 || v.height <= 0) return
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            val dir = File(filesDir, "design_shots").apply { mkdirs() }
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$name.txt").writeText(PreviewLayoutAudit.audit(v, resources.displayMetrics.density, bmp), Charsets.UTF_8)
            bmp.recycle()
            tmp.renameTo(File(dir, "$name.png"))   // 다 쓴 뒤에만 이름을 바꿔 adb가 반쯤 쓴 파일을 가져가지 않게 한다
            android.util.Log.i("DesignPreview", "snap saved $name ${v.width}x${v.height}")
        } catch (e: Exception) {
            android.util.Log.w("DesignPreview", "snap failed $name", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        /** 첫 그리기·배치가 끝난 뒤 연출을 세운다(ms). 실제 재생은 그 전에 시작되지만 세우는 순간 그 시점 그림으로 바뀐다 */
        const val STICKER_FREEZE_DELAY_MS = 900L
    }

    private fun render() {
        val s = stage ?: return
        val m = model ?: return
        s.render(m.state())
    }

    /** 미리보기용 가짜 동작: 좌석 탭·보호자 확인 대체 버튼만 가짜 상태를 바꾸고 다시 그린다. 나머지는 아무것도 하지 않는다 */
    private class PreviewActions(private val m: DesignPreviewStates.Model, private val redraw: () -> Unit) : StageActions {
        override fun onYes() {}
        override fun onNo() {}
        override fun onStart() {}
        override fun onRestart() {}
        override fun onFinish() {}
        override fun onToggleSeat(name: String) { m.toggleSeat(name); redraw() }
        override fun seatNames(): List<String> = DesignPreviewStates.SEATS
        override fun seatSelected(name: String): Boolean = name in m.selectedSeats
        override fun canStart(): Boolean = m.selectedSeats.isNotEmpty()
        override fun profileChips(): List<ChipInfo> = emptyList()
        override fun onToggleChild(id: Int) {}
        override fun lastChipVisible(): Boolean = false
        override fun onUseLast() {}
        override fun onOpenSettings() {}
        override fun seatBeltRef(name: String): SeatBeltRef = m.beltOf(name)
        override fun seatOccupied(name: String): Boolean? = m.occOf(name)
        override fun isBeltMock(): Boolean = true
        override fun onGuardianConfirmSeat(seat: String) { m.guardianConfirm(seat); redraw() }
        override fun onSeatTap(seat: String) { m.tapChild(seat); redraw() }
    }
}
