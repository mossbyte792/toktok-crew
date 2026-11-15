package com.crewguide.probe

import android.graphics.drawable.GradientDrawable

/**
 * V6 화면(S02~S09) 공통 패널 규격(순수 값 + 바탕 만들기). 값의 출처는 docs/design/v6 (05_SCREEN_SPECIFICATIONS, tokens).
 * 표시 전용: 상태·판정·잠금 로직은 건드리지 않는다.
 */
object V6Panels {
    const val CARD_RADIUS_DP = DuoTokens.CARD_RADIUS_DP          // 24 (카드)
    const val STAGE_RADIUS_DP = V6Tokens.STAGE_RADIUS_DP         // 32 (캐릭터 무대 패널)
    const val CARD_STROKE_DP = 2
    const val STAGE_STROKE_DP = 2
    const val STAMP_RADIUS_DP = 18
    const val STAMP_GAP_DP = 12
    const val STAMP_TODAY_STROKE_DP = 4

    /**
     * 2026-11-13 캡처 결함: 2영역 장면에서 아래 행동 띠(흰 판)가 무대 패널 윤곽(좌·우·아래 2dp)과 둥근 아래 모서리를 덮었다.
     * 행동 띠를 윤곽 두께만큼 안으로 들이고, 아래 모서리를 패널 안쪽 반경(패널 반경 − 윤곽)으로 둥글게 한다.
     */
    const val DOCK_INSET_IN_STAGE_DP = STAGE_STROKE_DP
    const val DOCK_BOTTOM_RADIUS_IN_STAGE_DP = STAGE_RADIUS_DP - STAGE_STROKE_DP

    /** 행동 띠 바탕 모서리 반경(dp, 좌상·우상·우하·좌하). 2영역이면 아래도 패널 안쪽 반경, 아니면 아래는 각진 채(화면 아래 띠에 붙음) */
    fun dockCornersDp(topRadiusDp: Int, inStagePanel: Boolean): IntArray {
        val bottom = if (inStagePanel) DOCK_BOTTOM_RADIUS_IN_STAGE_DP else 0
        return intArrayOf(topRadiusDp, topRadiusDp, bottom, bottom)
    }

    /** 카드 윤곽(비글자 대비 3:1 이상이어야 한다: DuoTokens 쌍으로 이미 검사됨) */
    fun cardFill(night: Boolean): Int = DuoTokens.roles(night).surface
    fun cardStroke(night: Boolean): Int = DuoTokens.roles(night).surfaceStroke
    /** 무대 패널 윤곽 = 카드 윤곽과 같은 색(밝은 하늘 위 구분용) */
    fun stageStroke(night: Boolean): Int = cardStroke(night)

    /** 위→아래 무대 그라데이션 색 3개(top, mid, bottom). RGB */
    fun stageGradient(night: Boolean): IntArray {
        val s = V6Tokens.stage(night)
        return intArrayOf(s.top, s.mid, s.bottom)
    }

    fun card(density: Float, night: Boolean): GradientDrawable =
        DuoDraw.round(cardFill(night), CARD_RADIUS_DP * density, (CARD_STROKE_DP * density).toInt(), cardStroke(night))

    fun stagePanel(density: Float, night: Boolean): GradientDrawable {
        val c = stageGradient(night).map { Palette.argb(it) }.toIntArray()
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c).also {
            it.shape = GradientDrawable.RECTANGLE
            it.cornerRadius = STAGE_RADIUS_DP * density
            it.setStroke((STAGE_STROKE_DP * density).toInt(), Palette.argb(stageStroke(night)))
        }
    }
}

/** V6 S09: 잠금 카드의 순수 규칙. 카드는 안내만 하며 버튼·해제 수단·터치 반응이 없다(차량 상태로만 열림) */
object V6LockCard {
    const val DASH_STROKE_DP = 3
    const val LOCK_ICON_DP = 96
    const val MAX_WIDTH_FRACTION = 0.5f
    /** 잠금 문구가 비어 있으면 카드를 보이지 않는다(문구 없이 빈 카드가 뜨지 않게) */
    fun visible(text: String?): Boolean = !text.isNullOrBlank()

    /**
     * 2026-11-14 SCREEN_EXIT_CHECK ⚠️⑦: 신호 단절·기어 불명·플랫폼 값 미수신으로 잠긴 카드에만 붙이는 할 일 한 줄(조작 없음).
     * 주행 중(P 아님)·플랫폼 제한·플랫폼 미연동(값이 다시 올 근거가 없음)·시작 직후 확인 중(이미 기다려 달라는 문구)에는 붙이지 않는다.
     */
    const val HINT_SIGNAL_RETURN = "신호가 돌아오면 자동으로 열려요"
    const val HINT_SP = DuoTokens.CAPTION_SP
    const val HINT_GAP_DP = 8
    /** 할 일 한 줄을 붙이는 잠금 문구(문구 상수로 구분한다. 잠금 판정은 바꾸지 않는다) */
    val HINT_FOR: Set<String> = setOf(LOCK_TEXT_SUB_FAILED, LOCK_TEXT_DISCONNECTED, LOCK_TEXT_UNKNOWN, LOCK_TEXT_PLATFORM_UNKNOWN)
    fun hintOf(text: String?): String? = if (text != null && text.trim() in HINT_FOR) HINT_SIGNAL_RETURN else null

    fun fill(night: Boolean): Int = DuoTokens.roles(night).disabledFill
    fun ink(night: Boolean): Int = DuoTokens.roles(night).disabledText
    fun stroke(night: Boolean): Int = DuoTokens.roles(night).disabledStroke
}

/** V6 S09 잠금 카드 뷰(표시 전용): 점선 윤곽 + 큰 자물쇠 + 잠금 문구(+ 단절·불명이면 할 일 한 줄). 클릭·포커스 없음 */
class V6LockCardView(context: android.content.Context) : android.widget.LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val label = android.widget.TextView(context)
    private val hint = android.widget.TextView(context)
    private val lockIcon = object : android.view.View(context) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.color = Palette.argb(V6LockCard.ink(Palette.current.night))
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = w * 0.09f
            c.drawArc(w * 0.27f, h * 0.06f, w * 0.73f, h * 0.62f, 180f, 180f, false, p)
            p.style = android.graphics.Paint.Style.FILL
            c.drawRoundRect(w * 0.16f, h * 0.40f, w * 0.84f, h * 0.92f, w * 0.1f, w * 0.1f, p)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        visibility = GONE
        isClickable = false; isFocusable = false
        val pad = (DuoTokens.CARD_PAD_DP * 1.5f * d).toInt()
        setPadding(pad, pad, pad, pad)
        val icon = (V6LockCard.LOCK_ICON_DP * d).toInt()
        lockIcon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(lockIcon, LayoutParams(icon, icon).apply { rightMargin = (24 * d).toInt() })
        label.textSize = DuoTokens.BODY_SP
        label.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        hint.textSize = V6LockCard.HINT_SP
        hint.typeface = StageFonts.typeface(context, StageFonts.FontRole.CAPTION)
        hint.visibility = GONE
        // 잠금 문구 아래 할 일 한 줄: 글자 열(세로)로 묶는다. 둘 다 같은 최대 폭으로 줄바꿈(글꼴 2.0에서도 잘리지 않고 줄만 늘어남)
        val texts = android.widget.LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        texts.addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (V6LockCard.HINT_GAP_DP * d).toInt() })
        addView(texts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(text: String?, night: Boolean, stageWidthPx: Int) {
        if (!V6LockCard.visible(text)) { visibility = GONE; return }
        if (stageWidthPx > 0) {
            val w = (stageWidthPx * V6LockCard.MAX_WIDTH_FRACTION).toInt()
            label.maxWidth = w; hint.maxWidth = w
        }
        background = DuoDraw.round(V6LockCard.fill(night), V6Panels.CARD_RADIUS_DP * d, (V6LockCard.DASH_STROKE_DP * d).toInt(), V6LockCard.stroke(night), dashed = true)
        label.text = text
        label.setTextColor(Palette.argb(V6LockCard.ink(night)))
        val h = V6LockCard.hintOf(text)
        hint.text = h.orEmpty()
        hint.setTextColor(Palette.argb(V6LockCard.ink(night)))   // 잠금 문구와 같은 글자색(채움 대비 4.5:1 이상, V6PanelsTest)
        hint.visibility = if (h != null) VISIBLE else GONE
        contentDescription = if (h != null) "$text. $h" else text
        lockIcon.invalidate()
        visibility = VISIBLE
    }
}
