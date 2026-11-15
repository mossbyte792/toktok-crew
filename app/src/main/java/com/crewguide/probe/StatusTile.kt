package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/*
 * 상태 타일(2026-11-08, v7 세션). 이미 판정된 상태를 "보여 주기만" 하는 작은 View.
 * - 상태 판정·추정·변환 로직이 없다. 호출자가 StatusStyle.Kind·문구·출처를 정해 넘긴다.
 * - 색·기호 의미는 기존 토큰 StatusStyle.Kind(주간/야간, 대비 검사됨)를 그대로 쓴다.
 * - 기호는 글꼴 글리프(●▲■○, ↻, Ⅱ)가 아니라 Path로 그린다. 글자는 PNG가 아니라 TextView.
 * - 화면 연결(MainActivity·StageScreen 등)은 Unit 6 담당 세션이 한다. 이 파일은 어디에도 연결되어 있지 않다.
 */

/** 움직임 상태 표기. "줄임"(시스템 접근성 설정 때문)과 "일시 중지"(앱이 멈춘 상태)는 의미가 다르다 */
enum class TileMotion(val label: String?) {
    /** 표기 안 함 */
    NONE(null),
    /** 시스템 '애니메이션 삭제'·animator 배율 0 등 접근성 설정 때문에 움직이지 않음 */
    REDUCED_BY_SYSTEM("움직임 줄임(기기 설정)"),
    /** 앱이 움직임을 잠시 멈춤(화면 숨김·정차 아님 등). 다시 움직일 수 있음 */
    PAUSED("움직임 일시 중지"),
}

/** 타일 한 장의 입력(순수 값). 호출자가 이미 정한 값만 담는다 */
data class StatusTileState(
    val kind: StatusStyle.Kind,
    /** 원문 상태 문구. 타일은 고치지 않고 그대로 보여 준다 */
    val text: String,
    /** 신호 출처. NONE이면 출처 줄을 숨긴다. MOCK이면 반드시 '모의' 표기 */
    val source: SignalSource = SignalSource.NONE,
    val motion: TileMotion = TileMotion.NONE,
)

/** 타일 문자열 조립(순수 로직, Android 클래스 없음). 단위 테스트 대상 */
object StatusTileText {
    const val SOURCE_REAL = "차량 신호"
    const val SOURCE_MOCK = "앱 내부 모의 신호(실제 차량 아님)"

    fun sourceLabel(source: SignalSource): String? = when (source) {
        SignalSource.REAL -> SOURCE_REAL
        SignalSource.MOCK -> SOURCE_MOCK
        SignalSource.NONE -> null
    }

    /** 첫 줄: 상태 이름 + 원문. 원문이 비면 상태 이름만 */
    fun title(s: StatusTileState): String =
        if (s.text.isBlank()) s.kind.label else "${s.kind.label}: ${s.text}"

    /** 둘째 줄: 출처 · 움직임. 둘 다 없으면 null(줄 숨김) */
    fun detail(s: StatusTileState): String? =
        listOfNotNull(sourceLabel(s.source), s.motion.label).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** TalkBack 읽기 문장. 화면 글자와 같은 정보, 색·모양에 의존하지 않음 */
    fun contentDescription(s: StatusTileState): String =
        listOfNotNull(title(s), detail(s)).joinToString(". ")

    /**
     * 같은 상태·같은 주야로 다시 bind되면 아무것도 바꾸지 않는다(2026-11-08 QA).
     * 이유: 타일은 accessibilityLiveRegion=POLITE라, 값이 같아도 setText가 다시 불리면
     * TalkBack이 같은 문장을 반복해 읽을 수 있다(화면 갱신 루프에서 매번 bind하는 경우).
     */
    fun needsUpdate(prev: StatusTileState?, prevNight: Boolean?, next: StatusTileState, night: Boolean): Boolean =
        prev != next || prevNight != night
}

/** 상태 기호를 Path로 그린다. PROGRESS=채운 원, CAUTION=삼각형, REPORTED=사각형, UNKNOWN=빈 원 */
class StatusGlyphDrawable(private var kind: StatusStyle.Kind, private var argb: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun set(kind: StatusStyle.Kind, argb: Int) { this.kind = kind; this.argb = argb; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        if (side <= 0f) return
        val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = side / 2f
        val stroke = side * 0.14f
        paint.color = argb
        path.reset()
        when (kind) {
            StatusStyle.Kind.PROGRESS -> { paint.style = Paint.Style.FILL; path.addCircle(cx, cy, r * 0.8f, Path.Direction.CW) }
            StatusStyle.Kind.UNKNOWN -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
                path.addCircle(cx, cy, r * 0.8f - stroke / 2f, Path.Direction.CW)
            }
            StatusStyle.Kind.REPORTED -> {
                paint.style = Paint.Style.FILL; val h = r * 0.72f
                path.addRect(cx - h, cy - h, cx + h, cy + h, Path.Direction.CW)
            }
            StatusStyle.Kind.CAUTION -> {
                paint.style = Paint.Style.FILL
                path.moveTo(cx, cy - r * 0.85f); path.lineTo(cx + r * 0.9f, cy + r * 0.75f)
                path.lineTo(cx - r * 0.9f, cy + r * 0.75f); path.close()
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 상태 타일 View. 가로 배치: [기호] [제목 / 보조 줄]. 높이는 wrap_content라 큰 글꼴에서 늘어난다.
 * 글자는 sp 단위, 줄바꿈 허용(가로·세로 모두). 누를 수 없는 표시 전용(포커스는 TalkBack 읽기용만).
 * 사용: val tile = StatusTile(ctx); tile.bind(StatusTileState(...), night = Palette.current.night)
 */
class StatusTile(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val glyph = StatusGlyphDrawable(StatusStyle.Kind.UNKNOWN, 0)
    private val glyphView = View(context).apply {
        background = glyph
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val titleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        typeface = StageFonts.typeface(context, StageFonts.FontRole.CAPTION)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val detailView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, DETAIL_SP)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val bg = GradientDrawable().apply { cornerRadius = dp(CORNER_DP).toFloat() }

    var state: StatusTileState? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(MIN_HEIGHT_DP)
        background = bg
        isFocusable = true                 // TalkBack이 타일 하나로 읽도록
        isClickable = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        addView(glyphView, LayoutParams(dp(GLYPH_DP), dp(GLYPH_DP)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(detailView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 상태를 그대로 표시한다. night는 호출자가 Palette.current.night 등으로 정한다 */
    private var boundNight: Boolean? = null

    fun bind(s: StatusTileState, night: Boolean = Palette.current.night) {
        if (!StatusTileText.needsUpdate(state, boundNight, s, night)) return   // 반복 bind로 인한 중복 낭독 방지
        state = s
        boundNight = night
        val fg = Palette.argb(s.kind.fgFor(night))
        bg.setColor(Palette.argb(s.kind.bgFor(night)))
        bg.setStroke(dp(2), fg)
        glyph.set(s.kind, fg)
        titleView.setTextColor(fg)
        titleView.text = StatusTileText.title(s)
        val detail = StatusTileText.detail(s)
        detailView.setTextColor(fg)
        detailView.text = detail ?: ""
        detailView.visibility = if (detail == null) GONE else VISIBLE
        contentDescription = StatusTileText.contentDescription(s)
    }

    companion object {
        const val TITLE_SP = 22f
        const val DETAIL_SP = 18f
        const val GLYPH_DP = 28
        const val MIN_HEIGHT_DP = 64
        const val CORNER_DP = 16
    }
}
