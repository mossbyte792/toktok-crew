package com.crewguide.probe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.util.TypedValue

/**
 * UI/UX v2(U1) 공통 컴포넌트. 색·크기는 모두 [DuoTokens]에서 읽는다(화면마다 따로 칠하지 않는다).
 * 상태(보호자 확인·잠금)는 바꾸지 않는 표시 전용 뷰이며, 클릭 처리는 호출자가 넘긴다.
 */
internal object DuoDraw {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun round(fill: Int, radiusPx: Float, strokePx: Int = 0, stroke: Int = 0, dashed: Boolean = false): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(Palette.argb(fill))
        g.cornerRadius = radiusPx
        if (strokePx > 0) {
            if (dashed) g.setStroke(strokePx, Palette.argb(stroke), strokePx * 3f, strokePx * 2f)
            else g.setStroke(strokePx, Palette.argb(stroke))
        }
        return g
    }

    /** 하단 띠 버튼 바탕: 눌리지 않음 = 띠 위에 본체 / 눌림 = 본체가 띠만큼 내려감 / 비활성 = 띠 없는 평면 + 점선 윤곽 */
    fun edgeButton(ctx: Context, fill: Int, edge: Int, disabledFill: Int, disabledStroke: Int): Drawable {
        val r = dp(ctx, DuoTokens.BUTTON_RADIUS_DP).toFloat()
        val e = dp(ctx, DuoTokens.EDGE_DP)
        val normal = LayerDrawable(arrayOf(round(edge, r), round(fill, r))).apply { setLayerInset(1, 0, 0, 0, e) }
        val pressed = LayerDrawable(arrayOf(round(fill, r))).apply { setLayerInset(0, 0, e, 0, 0) }
        val disabled = LayerDrawable(arrayOf(round(disabledFill, r, dp(ctx, 2), disabledStroke, dashed = true))).apply { setLayerInset(0, 0, 0, 0, e) }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }
}

/**
 * 주(파랑) / 강조(주황) / 보조(연회색) 하단 띠 버튼. 76dp 이상, 비활성은 평면 + 점선 윤곽 + 대비 4.5 이상 글자.
 * v3 단계 2(2026-11-11): 무대 버튼과 같은 그리기 엔진([IconButton])을 쓴다 — 하단 띠·눌림(본체가 띠만큼 내려감)·글자 가운데 정렬이 한 곳에서 처리된다.
 * 무대 버튼과 다른 점은 모서리(16dp, 무대는 알약)와 색 역할(DuoTokens)뿐이다. 색·크기 값은 바꾸지 않았다.
 */
class DuoButton(context: Context, kind: DuoTokens.Kind = DuoTokens.Kind.PRIMARY, minDp: Int = DuoTokens.BUTTON_MIN_DP) : IconButton(context) {
    var kind: DuoTokens.Kind = kind
        set(v) { field = v; restyle() }
    private val edgePx = DuoDraw.dp(context, DuoTokens.EDGE_DP)

    init {
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        textSize = DuoTokens.BODY_SP
        gravity = Gravity.CENTER
        val m = DuoDraw.dp(context, minDp)
        minHeight = m; minimumHeight = m
        stateListAnimator = null   // 기본 그림자 제거(띠가 깊이를 표현)
        cornerPx = DuoDraw.dp(context, DuoTokens.BUTTON_RADIUS_DP).toFloat()
        restyle()
    }

    fun restyle() {
        val r = DuoTokens.roles(Palette.current.night)
        val look = DuoButtonLook.of(r, kind, isEnabled)
        outlineDashed = look.dashed
        setPill(Palette.argb(look.fill), if (look.edge == 0) 0 else Palette.argb(look.edge), edgePx,
            if (look.strokeDp > 0) DuoDraw.dp(context, look.strokeDp) else 0, Palette.argb(look.stroke))
        setTextColor(Palette.argb(look.ink))
        val h = DuoDraw.dp(context, 16)
        setPadding(h, 0, h, edgePx)   // 글자는 본체 가운데. 눌림 이동은 IconButton이 그릴 때 처리
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = enabled != isEnabled
        super.setEnabled(enabled)
        if (changed) restyle()
    }
}

/** DuoButton 모양 값(순수, 단위 테스트용). 비활성 = 띠 없음(edge 0) + 점선 윤곽 2dp + 비활성 색 */
data class DuoButtonLook(val fill: Int, val edge: Int, val ink: Int, val strokeDp: Int, val stroke: Int, val dashed: Boolean) {
    companion object {
        fun of(r: DuoTokens.Roles, k: DuoTokens.Kind, enabled: Boolean): DuoButtonLook =
            if (enabled) DuoButtonLook(r.fill(k), r.edge(k), r.ink(k), 0, 0, false)
            else DuoButtonLook(r.disabledFill, 0, r.disabledText, 2, r.disabledStroke, true)
    }
}

/** 공통 카드: 반지름 24dp, 2dp 윤곽, 안쪽 여백 24dp. [title]이 있으면 맨 위에 제목 줄을 둔다. 내용은 addView로 */
class DuoCard(context: Context, title: String? = null, subtitle: String? = null) : LinearLayout(context) {
    val titleView: TextView?
    init {
        orientation = VERTICAL
        val r = DuoTokens.roles(Palette.current.night)
        val p = DuoDraw.dp(context, DuoTokens.CARD_PAD_DP)
        setPadding(p, p, p, p)
        background = DuoDraw.round(r.surface, DuoDraw.dp(context, DuoTokens.CARD_RADIUS_DP).toFloat(), DuoDraw.dp(context, DuoTokens.CARD_STROKE_DP), r.surfaceStroke)
        titleView = if (title != null) TextView(context).apply {
            text = title; textSize = DuoTokens.TITLE_SP; typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
            setTextColor(Palette.argb(r.text))
            isFocusable = false
            accessibilityHeading()
        } else null
        titleView?.let { addView(it) }
        if (subtitle != null) addView(DuoText.caption(context, subtitle).apply { setPadding(0, DuoDraw.dp(context, 4), 0, 0) })
        if (title != null || subtitle != null) addView(View(context), LayoutParams(1, DuoDraw.dp(context, DuoTokens.GRID_DP * 2)))
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
}

/** 글자 도우미: 본문 24sp / 보조 18sp(보조 글자색) / 제목 32sp */
object DuoText {
    fun body(ctx: Context, t: String): TextView = TextView(ctx).apply {
        text = t; textSize = DuoTokens.BODY_SP; setTextColor(Palette.argb(DuoTokens.roles(Palette.current.night).text))
        setLineSpacing(0f, 1.15f); wordBreak(this)
    }
    fun caption(ctx: Context, t: String): TextView = TextView(ctx).apply {
        text = t; textSize = DuoTokens.CAPTION_SP; setTextColor(Palette.argb(DuoTokens.roles(Palette.current.night).textMuted))
        setLineSpacing(0f, 1.15f); wordBreak(this)
    }
    /**
     * v3 반복 3(P3): 한글이 낱말 중간("있어/요")에서 끊기지 않게 어절 단위 줄바꿈(Android 13+ 구절 단위 줄바꿈).
     * 12 이하에서는 기존 동작 그대로. 글자·크기·색은 바꾸지 않는다.
     */
    fun wordBreak(tv: TextView) {
        if (android.os.Build.VERSION.SDK_INT >= 33) tv.lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }

    /**
     * v3 반복 6(P1-2): 화면 전체 한 글자·어절 중간 줄바꿈 정리. 트리 안의 모든 TextView(버튼 포함)에 어절 단위 줄바꿈을 건다.
     * 이미 걸려 있으면 건드리지 않는다(다시 배치하지 않음). API 33 미만은 아무것도 하지 않는다(플랫폼 기능 없음).
     */
    fun wordBreakTree(root: android.view.View) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val phrase = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        if (root is TextView) { if (root.lineBreakWordStyle != phrase) root.lineBreakWordStyle = phrase }
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) wordBreakTree(root.getChildAt(i))
    }
    /**
     * v4.1(2026-11-12): 별명(최대 8자)처럼 띄어쓰기 없는 짧은 이름을 한 줄에 맞춘다.
     * 원인(에뮬, 글꼴 2.0 + 아이 4명 + 8자 별명): 칸이 좁아 "Haneul0/1", "Yuna888/8"처럼 글자 중간에서 줄이 꺾였다.
     * 한 줄 + 배치 뒤 실제 폭을 재서 글자 크기를 줄인다(가장 큰 크기 = 원래 크기, 가장 작은 크기 = [NameFit.minSp]). 그래도 안 들어가면 끝을 …로 줄인다.
     * 플랫폼 자동 크기(autoSize)는 높이가 wrap_content인 칸에서 동작하지 않아(에뮬 확인: 줄지 않고 바로 …) 직접 잰다.
     */
    fun fitOneLine(tv: TextView, maxSp: Float) {
        tv.setSingleLine(true)
        tv.maxLines = 1
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        val dm = tv.resources.displayMetrics
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, dm)
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, NameFit.minSp(maxSp), dm)
        val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm)
        tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val t = v as TextView
            val avail = t.width - t.totalPaddingLeft - t.totalPaddingRight
            if (avail <= 0) return@addOnLayoutChangeListener
            val target = NameFit.fitPx(t.text.toString(), avail.toFloat(), maxPx, minPx, stepPx) { size ->
                val p = android.text.TextPaint(t.paint); p.textSize = size; p.measureText(t.text.toString())
            }
            if (kotlin.math.abs(t.textSize - target) > 0.5f) t.post { t.setTextSize(TypedValue.COMPLEX_UNIT_PX, target) }
        }
    }
    fun title(ctx: Context, t: String): TextView = TextView(ctx).apply {
        text = t; textSize = DuoTokens.TITLE_SP; typeface = StageFonts.typeface(ctx, StageFonts.FontRole.TITLE)
        setTextColor(Palette.argb(DuoTokens.roles(Palette.current.night).text))
    }
}

/**
 * 상태를 바꿀 때 알림을 주는 스위치(기존 android.widget.Switch 타입을 그대로 쓰는 곳과 호환).
 * 기본 보라색 대신 브랜드 색 트랙·손잡이를 입힌다.
 */
class DuoSwitch(context: Context) : Switch(context) {
    var onStateShown: ((Boolean) -> Unit)? = null
    init { tint() }
    fun tint() {
        val r = DuoTokens.roles(Palette.current.night)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.argb(r.disabledStroke), Palette.argb(r.thumb), Palette.argb(r.thumb)))
        trackTintList = ColorStateList(states, intArrayOf(Palette.argb(r.disabledFill), Palette.argb(r.trackOn), Palette.argb(r.trackOff)))
        trackTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        scaleX = 1.4f; scaleY = 1.4f   // 손잡이를 크게(터치는 행 전체가 받는다)
    }
    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        onStateShown?.invoke(checked)
    }
}

/**
 * 설정 한 줄: 제목 + 현재 상태(켜짐/꺼짐 글자) + 설명, 오른쪽에 브랜드 스위치. 행 전체를 누르면 스위치를 바꾼다(스위치의 기존 리스너가 저장·잠금 판단).
 * 접근성: 행을 하나의 스위치로 읽히게 contentDescription에 제목·상태를 넣는다.
 */
class DuoToggleRow(context: Context, private val label: String, desc: String?, val switch: DuoSwitch) : LinearLayout(context) {
    private val stateView: TextView
    private val titleView: TextView
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = DuoDraw.dp(context, DuoTokens.BUTTON_MIN_DP)
        val v = DuoDraw.dp(context, 12)
        setPadding(0, v, 0, v)
        isClickable = true; isFocusable = true
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        titleView = DuoText.body(context, label).apply { typeface = Typeface.DEFAULT_BOLD }
        stateView = TextView(context).apply { textSize = DuoTokens.CAPTION_SP; typeface = Typeface.DEFAULT_BOLD }
        col.addView(titleView); col.addView(stateView)
        if (desc != null) col.addView(DuoText.caption(context, desc))
        addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        switch.text = ""
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // 행이 대신 읽힌다
        switch.isFocusable = false
        val sp = DuoDraw.dp(context, 24)
        addView(switch, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(sp, 0, sp / 2, 0) })
        setOnClickListener { if (switch.isEnabled) switch.toggle() }
        switch.onStateShown = { show(it) }
        show(switch.isChecked)
    }

    fun show(on: Boolean) {
        val r = DuoTokens.roles(Palette.current.night)
        stateView.text = if (on) "● 켜짐" else "○ 꺼짐"
        stateView.setTextColor(Palette.argb(if (on) r.stateOnText else r.textMuted))
        contentDescription = "$label, ${if (on) "켜짐" else "꺼짐"}${if (!isEnabled || !switch.isEnabled) ", 지금은 바꿀 수 없음" else ""}"
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        switch.isEnabled = enabled
        alpha = 1f
        show(switch.isChecked)
    }
}

/** 상태 칩: 기호 + 글자 + 색(색만으로 구분하지 않음). 신호 없음은 점선 윤곽 */
class StatusChipView(context: Context) : TextView(context) {
    var state: StatusChipSpec.State? = null
        private set
    init {
        textSize = DuoTokens.CAPTION_SP
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        minHeight = DuoDraw.dp(context, DuoTokens.CHIP_MIN_DP)
        val h = DuoDraw.dp(context, 14)
        setPadding(h, DuoDraw.dp(context, 4), h, DuoDraw.dp(context, 4))
        DuoText.wordBreak(this)   // v3 반복 4(P1-5): 글꼴 2.0 범례에서 "체결 신/호"·"신호 없/음" 한 글자 줄바꿈 → 어절 단위
    }
    fun bind(s: StatusChipSpec.State, night: Boolean = Palette.current.night) {
        state = s
        val c = StatusChipSpec.colors(s, night)
        text = StatusChipSpec.text(s)
        setTextColor(Palette.argb(c.fg))
        background = DuoDraw.round(c.bg, DuoDraw.dp(context, DuoTokens.CHIP_RADIUS_DP).toFloat(), DuoDraw.dp(context, 2), c.stroke, c.dashed)
        contentDescription = StatusChipSpec.a11y(s)
    }
}

/** v4.1: 한 줄 별명 자동 크기의 하한(순수 값). 원래 크기의 60%, 단 14sp 아래로는 줄이지 않는다(차량 화면 가독성) */
object NameFit {
    const val FLOOR_SP = 14f
    const val RATIO = 0.6f
    fun minSp(maxSp: Float): Float = maxOf(FLOOR_SP, maxSp * RATIO).coerceAtMost(maxSp)

    /** 폭 [avail]에 들어가는 가장 큰 크기(px). [measure]는 크기별 글자 폭. 하한까지 줄여도 넘치면 하한(나머지는 …) */
    fun fitPx(text: String, avail: Float, maxPx: Float, minPx: Float, stepPx: Float, measure: (Float) -> Float): Float {
        if (text.isEmpty() || avail <= 0f) return maxPx
        var size = maxPx
        while (size > minPx && measure(size) > avail) size -= stepPx
        return size.coerceAtLeast(minPx)
    }
}
