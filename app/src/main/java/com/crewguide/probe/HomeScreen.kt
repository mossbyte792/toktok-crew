package com.crewguide.probe

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

/** 홈 화면 상수(순수 값). 간격·크기는 여기 한곳에 둔다 */
object HomeLayout {
    const val TITLE_SP = 72f
    const val BUBBLE_SP = 36f
    /** 보호자 알림 카드 글자 크기(Unit 5.3) */
    const val NOTES_SP = 24f
    const val START_HEIGHT_DP = 154
    const val START_MIN_WIDTH_DP = 520
    const val START_SP = 56f
    const val MARGIN_DP = 24
    const val START_BOTTOM_DP = 72
    /** 제목이 차지하는 위쪽 높이. 마스코트는 이 아래, [시작] 위 빈 영역의 한가운데에 놓인다 */
    const val TITLE_BLOCK_DP = 140
    /** 마스코트 한 변 = 홈 화면 높이의 이 비율(%). 사용자 요청 2026-11-07: 화면의 50% */
    const val MASCOT_PERCENT = 50
    /** 마스코트와 제목·[시작] 사이 최소 여백(dp). 이보다 좁아지면 마스코트를 줄인다 */
    const val MASCOT_MIN_GAP_DP = 48
    /** 말풍선 위치: 마스코트 한 변 기준 오른쪽·위쪽 비율(%) */
    const val BUBBLE_X_PERCENT = 24
    const val BUBBLE_Y_PERCENT = 8
    const val UI_FADE_MS = 300L
    const val FRAME_MS = 33L
    /** 구름 말풍선: 돌출 반지름, 왼쪽 생각 방울 자리, 테두리, 글자 둘레 여유(모두 dp) */
    const val CLOUD_BUMP_DP = 30
    const val CLOUD_TAIL_DP = 56
    const val CLOUD_STROKE_DP = 6
    /** 듀오링고풍 입체감(2026-11-07): [시작] 하단 띠 두께, 톱니 원형 버튼의 윤곽·하단 띠(dp) */
    const val START_EDGE_DP = 22
    const val GEAR_OUTLINE_DP = 6
    const val GEAR_EDGE_DP = 8
    const val TITLE_SHADOW_DP = 4
    const val CLOUD_TEXT_EXTRA_DP = 4
}

/** 홈 화면 색 역할(순수 값 0xRRGGBB). 기존 팔레트 상수만 조합한다. 대비는 HomeScreenTest가 확인 */
object HomeColors {
    /** 짙은 남색: 제목·구름 윤곽·톱니 윤곽·[시작] 띠. 밤에는 밝은 파랑 */
    fun navy(night: Boolean): Int = if (night) Palette.NIGHT_CREW_BLUE else Palette.CREW_BLUE
    fun title(night: Boolean): Int = navy(night)
    /** 구름 말풍선: 낮 순백 바탕 + 남색 윤곽 + 차콜 글자 */
    fun bubble(night: Boolean): BubbleColors =
        if (night) BubbleColors(Palette.NIGHT_CARD_BG, navy(true), Palette.NIGHT_TEXT)
        else BubbleColors(0xFFFFFF, navy(false), 0x111111)
    fun gearFill(night: Boolean): Int = if (night) Palette.NIGHT_BTN_OFF_BG else 0xFFFFFF
    fun gearInk(night: Boolean): Int = navy(night)
    fun gearOutline(night: Boolean): Int = navy(night)
    fun gearEdge(night: Boolean): Int = navy(night)
    /** [시작] 아래 띠: 낮은 짙은 남색(채움 2563EB과 구분이 확실), 밤은 기존 계산 값 */
    fun startEdge(night: Boolean, fallback: Int): Int = if (night) fallback else Palette.CREW_BLUE
}

/** 홈 화면 배치 계산(순수 값, px). 마스코트는 제목 아래와 [시작] 위 사이 빈 영역의 가운데에 둔다 */
object HomeLayoutMath {
    /** 마스코트 한 변과 위쪽 좌표. 한 변 = min(화면 높이 × 비율, 빈 영역 − 2 × 최소 여백), 0 아래로 내려가지 않는다 */
    fun mascot(heightPx: Int, titleBottomPx: Int, startTopPx: Int, percent: Int, minGapPx: Int): Pair<Int, Int> {
        val gap = (startTopPx - titleBottomPx).coerceAtLeast(0)
        val side = minOf(heightPx * percent / 100, (gap - 2 * minGapPx).coerceAtLeast(0))
        val top = titleBottomPx + (gap - side) / 2
        return Pair(side, top)
    }
}

object HomeText {
    const val BUBBLE = "안녕하세요, ${AppName.CHARACTER}이에요"
    const val START = "시작"
    /** 꾸미기 진입이 숨겨진 동안(CustomizeAvailability)의 설명 */
    const val CHARACTER_DESC = AppName.CHARACTER
    const val CHARACTER_DESC_CUSTOMIZE = "승무원. 누르면 꾸미기로 이동해요"

    /**
     * v3 단계 4(2026-11-11): 한 줄에 다 안 들어가면 쉼표 뒤에서 미리 줄을 나눈다.
     * 한국어는 글자 단위로 줄이 바뀌어 큰 글꼴(2.0)에서 "승무원이에 / 요"처럼 한 글자만 넘어가던 문제(P3) 대응.
     * @param fullWidthPx 한 줄로 쟀을 때 글자 폭, @param availPx 말풍선 안쪽에 쓸 수 있는 폭
     */
    fun bubbleText(fullWidthPx: Float, availPx: Float): String =
        if (availPx <= 0f || fullWidthPx <= availPx) BUBBLE else twoLineBubble()

    /** 쉼표 뒤에서 나눈 두 줄 문장(홈 V6 말풍선이 큰 글자로 쓸 때도 같은 자리에서 나눈다). 문구는 [BUBBLE] 그대로 */
    fun twoLineBubble(): String = BUBBLE.replaceFirst(", ", ",\n")
}

/**
 * 홈 화면(가안, 사용자 설계 2026-11-07): 위에 제목, 배경에 큰 승무원, 승무원 아래 큰 [시작], 오른쪽 위 구석 [설정].
 * 시작 → 탑승자 선택, 설정 → 설정, 배경(승무원)을 누르면 제목과 버튼이 사라지며 꾸미기 화면으로 넘어간다.
 * 승무원은 숨쉬기만 한다(손 인사 그림은 2026-11-14 제거). 보호자 확인·잠금 등 어떤 상태도 바꾸지 않는다(콜백만 부른다).
 * 롤백: StageFlags.NEW_STAGE의 홈 연결(MainActivity.openHome)을 끄면 이전처럼 곧바로 무대가 나온다.
 */
class HomeScreenLegacy(
    context: Context,
    private val onStart: () -> Unit,
    private val onSettings: () -> Unit,
    private val onCustomize: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private val night = Palette.current.night

    // 2026-11-08 v6.1: 홈 캐릭터 = 공식 원본 parked.png(1.0배 상한). 이전 벡터 MascotView는 코드 보존(사용 안 함)
    private val mascot = CrewArtView(context, CrewArt.HOME_MAX_SCALE)
    private val uiGroup = FrameLayout(context)   // 제목·시작·설정·말풍선: 승무원을 누르면 사라진다
    private val title = TextView(context)
    private val bubble = TextView(context)
    /** 보호자 알림(미전달 경고·감시 끊김·모의 기어). 없으면 숨김. 터치 동작 없음 */
    private val notes = TextView(context)
    private val startBtn = IconButton(context)
    private val gearBtn = IconButton(context)
    private val handler = Handler(Looper.getMainLooper())
    private var leaving = false
    private var motionOn = false
    private var motionStartMs = 0L
    /**
     * 연결 코드가 알려 준 차량·정책 게이트([HomeMotionPolicy] 입력). null = 아직 연결 안 됨.
     * null일 때 움직이지 않는 것은 MainActivity 미연결 상태의 **임시 조치**다(2026-11-07 검토자 지시 5).
     * 최종 정책은 게이트(유효한 P + 플랫폼 허용)와 시스템 애니메이션(접근성) 설정으로 정한다.
     */
    private var motionGate: VehicleGate? = null

    init {
        val bd = backdropOf(Scene.PICK, night)
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.argb(bd.topRgb), Palette.argb(bd.bottomRgb)),
        )

        // 마스코트: 공식 원본 parked.png. 크기·위치는 onSizeChanged에서 정한다(화면 높이의 50% 칸, 원본보다 크게 그리지 않음)
        mascot.show(CrewArtAsset.PARKED)
        val desc = if (CustomizeAvailability.ENTRY_VISIBLE) HomeText.CHARACTER_DESC_CUSTOMIZE else HomeText.CHARACTER_DESC
        mascot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        mascot.contentDescription = desc
        if (CustomizeAvailability.ENTRY_VISIBLE) {
            mascot.setOnClickListener { goCustomize() }
            // 배경 어디를 눌러도 꾸미기로: 화면 전체를 덮는 터치 층
            val tapLayer = View(context)
            tapLayer.setOnClickListener { goCustomize() }
            tapLayer.contentDescription = desc
            addView(tapLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // 꾸미기 숨김(2026-11-08 지시 2.7): 진입 경로만 막는다. 꾸미기 코드·저장값(character_style_v1)은 그대로
        addView(mascot, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(uiGroup, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val ink = Palette.argb(HomeColors.title(night))
        title.text = AppName.NAME
        title.textSize = HomeLayout.TITLE_SP
        title.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        title.setTextColor(ink)
        title.paint.isFakeBoldText = true   // Jua에 굵은 글꼴이 없어 한 겹 더 굵게(둥근 볼드체 느낌)
        // 아래로 떨어지는 단단한 그림자(번짐 없음): 글자가 살짝 떠 보이는 입체감
        title.setShadowLayer(1f, 0f, dp(HomeLayout.TITLE_SHADOW_DP).toFloat(), Palette.argb(if (night) 0x000000 else 0xFFFFFF))
        title.gravity = Gravity.CENTER
        uiGroup.addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(HomeLayout.MARGIN_DP + 8)
        })

        // 말풍선: 승무원 머리 오른쪽, 꼬리는 머리 쪽(왼쪽)을 향한다. 위치는 onSizeChanged에서 정한다
        val bubbleColors = HomeColors.bubble(night)
        bubble.text = HomeText.BUBBLE
        bubble.textSize = HomeLayout.BUBBLE_SP
        bubble.typeface = StageFonts.typeface(context, StageFonts.FontRole.CAPTION)
        bubble.setTextColor(Palette.argb(bubbleColors.textRgb))
        bubble.gravity = Gravity.CENTER
        // 둥근 구름 모양(2026-11-07): 본체 가장자리에 동그라미를 줄지어 합친 윤곽 + 왼쪽(머리 쪽) 생각 방울 3개
        val bump = dp(HomeLayout.CLOUD_BUMP_DP).toFloat()
        val tailPx = dp(HomeLayout.CLOUD_TAIL_DP).toFloat()
        bubble.background = CloudBubbleDrawable(
            Palette.argb(bubbleColors.fillRgb), Palette.argb(bubbleColors.outlineRgb),
            dp(HomeLayout.CLOUD_STROKE_DP).toFloat(), bump, tailPx,
        )
        val inset = CloudShape.textInsetPx(bump, dp(HomeLayout.CLOUD_TEXT_EXTRA_DP).toFloat()).toInt()
        bubble.setPadding(tailPx.toInt() + inset, inset, inset, inset)
        bubble.contentDescription = HomeText.BUBBLE
        uiGroup.addView(bubble, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))

        // 보호자 알림 카드(2026-11-08 Unit 5.3): 왼쪽 위, 흰 카드 + 진한 글자(대비 18:1). 내용이 없으면 숨긴다
        notes.textSize = HomeLayout.NOTES_SP
        notes.typeface = StageFonts.typeface(context, StageFonts.FontRole.CAPTION)
        notes.setTextColor(Palette.argb(if (night) 0xFFFFFF else 0x111111))
        notes.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Palette.argb(if (night) 0x111827 else 0xFFFFFF))
            setStroke(dp(3), Palette.argb(HomeColors.navy(night)))
        }
        notes.setPadding(dp(16), dp(12), dp(16), dp(12))
        notes.visibility = View.GONE
        uiGroup.addView(notes, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(HomeLayout.MARGIN_DP); bottomMargin = dp(HomeLayout.MARGIN_DP)
        })

        // 큰 [시작]: 알약 + 아래 두툼한 가장자리(시작 버튼 색 규칙 그대로)
        val spec = specOf(ButtonKind.PRIMARY_START)
        val textInk = Palette.argb(StageButtons.colorOf(spec.textRole, night))
        val fill = Palette.argb(StageButtons.colorOf(spec.fillRole, night))
        val edge = Palette.argb(HomeColors.startEdge(night, StageButtons.edgeColorFor(spec, night)))
        // 알약 본체 + 아주 두꺼운 아래 띠. 누르면 본체가 띠 두께만큼 쑥 내려가 띠가 가려진다(IconButton이 처리)
        startBtn.setPill(fill, edge, dp(HomeLayout.START_EDGE_DP), 0, 0)
        startBtn.text = HomeText.START
        startBtn.textSize = HomeLayout.START_SP
        startBtn.typeface = StageFonts.typeface(context, StageFonts.FontRole.BIG_BUTTON)
        startBtn.isAllCaps = false
        startBtn.setTextColor(textInk)
        startBtn.gravity = Gravity.CENTER
        startBtn.stateListAnimator = null
        startBtn.minHeight = dp(HomeLayout.START_HEIGHT_DP); startBtn.minimumHeight = dp(HomeLayout.START_HEIGHT_DP)
        startBtn.minWidth = dp(HomeLayout.START_MIN_WIDTH_DP); startBtn.minimumWidth = dp(HomeLayout.START_MIN_WIDTH_DP)
        startBtn.setPadding(dp(StageButtons.PAD_DP), 0, dp(StageButtons.PAD_DP), dp(HomeLayout.START_EDGE_DP))
        startBtn.setOnClickListener { if (!leaving) onStart() }
        uiGroup.addView(startBtn, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(HomeLayout.START_HEIGHT_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(HomeLayout.START_BOTTOM_DP)
        })

        // 오른쪽 위 구석 설정: 흰 바탕 + 두꺼운 남색 윤곽 + 아래 단단한 띠의 원형 버튼(글자 없음)
        // 본체는 지름 76dp 정사각(=원), 아래 띠 높이만큼 뷰가 더 길다
        val g = dp(StageUiTokens.SETTINGS_BUTTON_DP)
        val gEdge = dp(HomeLayout.GEAR_EDGE_DP)
        val iconPx = dp(StageUiTokens.settingsIconDp())
        val gear = IconDrawable(IconKind.GEAR, Palette.argb(HomeColors.gearInk(night)))
        gear.setBounds(0, 0, iconPx, iconPx)
        gearBtn.setPill(
            Palette.argb(HomeColors.gearFill(night)), Palette.argb(HomeColors.gearEdge(night)), gEdge,
            dp(HomeLayout.GEAR_OUTLINE_DP), Palette.argb(HomeColors.gearOutline(night)),
        )
        gearBtn.text = ""
        gearBtn.contentDescription = StageControlText.SETTINGS_DESC
        gearBtn.stateListAnimator = null
        gearBtn.minWidth = 0; gearBtn.minimumWidth = 0; gearBtn.minHeight = 0; gearBtn.minimumHeight = 0
        gearBtn.setPadding(0, 0, 0, gEdge)
        gearBtn.compoundDrawablePadding = 0
        gearBtn.setCompoundDrawablesRelative(gear, null, null, null)
        gearBtn.setOnClickListener { if (!leaving) onSettings() }
        uiGroup.addView(gearBtn, LayoutParams(g, g + gEdge, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(HomeLayout.MARGIN_DP); rightMargin = dp(HomeLayout.MARGIN_DP)
        })
    }

    /** 꾸미기에서 돌아왔거나 처음 보일 때: 제목과 버튼을 다시 보이게 한다 */
    fun resetUi() {
        leaving = false
        uiGroup.animate().cancel()
        uiGroup.alpha = 1f
        uiGroup.visibility = View.VISIBLE
    }

    private fun goCustomize() {
        if (leaving) return
        leaving = true
        val animated = android.animation.ValueAnimator.areAnimatorsEnabled()
        if (!animated) { uiGroup.visibility = View.INVISIBLE; onCustomize(); return }
        uiGroup.animate().alpha(0f).setDuration(HomeLayout.UI_FADE_MS).withEndAction {
            uiGroup.visibility = View.INVISIBLE
            onCustomize()
        }.start()
    }

    /** 꾸미기 상태가 바뀌면 파츠 구성을 바로 다시 쌓는다(MainActivity가 CharacterStyleState를 구독해 부른다) */
    fun setStyle(@Suppress("UNUSED_PARAMETER") style: CharacterStyle) { /* 공식 원본 PNG는 꾸미기 미지원: 저장값만 유지, 그리지 않음 */ }

    /** 보호자 알림 문구(줄바꿈으로 여러 줄). null이면 숨긴다 */
    fun setNotes(text: String?) {
        if (text.isNullOrEmpty()) { notes.visibility = View.GONE; notes.text = ""; return }
        if (notes.text.toString() != text) { notes.text = text; notes.contentDescription = text }
        notes.visibility = View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 레이아웃 도중에 자식의 크기를 바꾸면 다시 배치되지 않아(0×0으로 남음) 끝난 뒤에 적용한다
        post { placeMascotAndBubble(w, h) }
    }

    private fun placeMascotAndBubble(w: Int, h: Int) {
        // 제목 아래 ~ [시작] 위 사이 가운데에 마스코트(높이의 50%, 여백이 모자라면 줄임)
        val startTop = h - dp(HomeLayout.START_BOTTOM_DP) - dp(HomeLayout.START_HEIGHT_DP)
        val (side, top) = HomeLayoutMath.mascot(h, dp(HomeLayout.TITLE_BLOCK_DP), startTop, HomeLayout.MASCOT_PERCENT, dp(HomeLayout.MASCOT_MIN_GAP_DP))
        val mlp = mascot.layoutParams as LayoutParams
        mlp.width = side; mlp.height = side; mlp.topMargin = top
        mascot.layoutParams = mlp
        // 말풍선: 마스코트 오른쪽 위
        val lp = bubble.layoutParams as LayoutParams
        lp.leftMargin = w / 2 + side * HomeLayout.BUBBLE_X_PERCENT / 100
        lp.topMargin = top + side * HomeLayout.BUBBLE_Y_PERCENT / 100
        bubble.maxWidth = (w - lp.leftMargin - dp(HomeLayout.MARGIN_DP)).coerceAtLeast(dp(240))
        // P3: 한 줄에 안 들어가면 쉼표 뒤에서 줄바꿈(글자 하나만 다음 줄로 넘어가지 않게). 화면 읽기 설명은 한 줄 문장 그대로
        val avail = (bubble.maxWidth - bubble.paddingLeft - bubble.paddingRight).toFloat()
        val t = HomeText.bubbleText(bubble.paint.measureText(HomeText.BUBBLE), avail)
        if (bubble.text?.toString() != t) bubble.text = t
        notes.maxWidth = (w * 0.27f).toInt().coerceAtLeast(dp(240))   // [시작](가운데 아래, 폭 520dp 이상)과 겹치지 않게 왼쪽 안쪽만
        bubble.layoutParams = lp
    }

    // ---- 숨쉬기(마스코트·말풍선이 함께 위아래로 아주 조금) ----
    private val tick = object : Runnable {
        override fun run() {
            if (!motionOn) return
            val now = SystemClock.uptimeMillis()
            // 시스템 애니메이션이 꺼져 있으면 움직이지 않는다
            val dy = if (android.animation.ValueAnimator.areAnimatorsEnabled())
                -breathUnit(now - motionStartMs) * dp(CharacterMotion.BREATH_AMP_DP.toInt()) else 0f
            val iy = Math.round(dy).toFloat()   // 정수 px 이동만(재샘플 번짐 없음)
            mascot.translationY = iy
            bubble.translationY = iy
            handler.postDelayed(this, HomeLayout.FRAME_MS)
        }
    }

    private fun startMotion() {
        if (motionOn) return
        // 게이트 미연결·정차 허용 아님·애니메이션 꺼짐·안 보임이면 정지(HomeMotionPolicy)
        // 2026-11-14: 손 인사 그림(wave.png 인사 칸)을 없앴다 → 숨쉬기만(alreadyGreeted = true)
        val d = HomeMotionPolicy.decide(motionGate, android.animation.ValueAnimator.areAnimatorsEnabled(),
            visibility == View.VISIBLE && isAttachedToWindow, alreadyGreeted = true)
        if (d.motion == HomeMotion.STATIC) return
        motionOn = true
        motionStartMs = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun stopMotion() {
        motionOn = false
        handler.removeCallbacks(tick)
        mascot.translationY = 0f
        bubble.translationY = 0f
    }

    /**
     * 차량·정책 게이트 반영(최종 연결 지점). PARK_ALLOWED가 아니면(주행·플랫폼 제한·불명) 숨쉬기·인사를 바로 멈추고,
     * PARK_ALLOWED로 돌아오면 숨쉬기만 다시 한다(첫 인사를 이미 했거나 도중에 끊겼으면 다시 흔들지 않는다).
     * 호출 연결은 MainActivity 작업 단위에서 한다(아직 미연결).
     */
    fun setMotionGate(gate: VehicleGate) {
        if (motionGate == gate) return
        motionGate = gate
        if (gate != VehicleGate.PARK_ALLOWED) stopMotion() else startMotion()
    }

    /** 이전 단위 호환용. true = PARK_ALLOWED, false = RESTRICTED로 [setMotionGate]에 넘긴다 */
    fun setMotionAllowed(allowed: Boolean) = setMotionGate(if (allowed) VehicleGate.PARK_ALLOWED else VehicleGate.RESTRICTED)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) { if (visibility == View.VISIBLE && isAttachedToWindow) startMotion() else stopMotion() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) startMotion()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        uiGroup.animate().cancel()
        super.onDetachedFromWindow()
    }
}
