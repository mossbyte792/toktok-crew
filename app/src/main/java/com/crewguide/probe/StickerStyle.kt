package com.crewguide.probe

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * 2026-11-15 r10e 디자인(사용자 15:58 "스티커 색깔이 주황색인데 이거 시트 색이랑 겹쳐서 무지개 색 같은걸로 할 수 없나").
 * 도장판에서 스티커를 받은 칸(오늘 칸 포함)·날아가는 별(StickerFly)·목록 패널 별의 모양. 좌석 '이용중' 주황·포인트 주황은 그대로 둔다.
 *
 * 공통: 칸 바탕은 카드 바탕(낮 흰색, 밤 카드색) 한 가지 → 글자("월·오늘", "참여 N번")는 늘 한 색 바탕 위 본문색(대비 18.9 / 13.4).
 * 여러 색 위에 글자를 올리지 않는다. 색만으로 구분하지 않는다(★ 기호 + "참여 N번" 글자 그대로).
 * 무지개 색은 [Palette.stickerRainbow] 한 곳(colors.xml·design-tokens.json 사본, 토큰 테스트). 여섯 색 모두 바탕 대비 3:1 이상(비글자).
 *
 * 시안 두 개 — [VARIANT] 한 줄로 바꾼다:
 *  - [Variant.RING] (A) 흰 바탕 + 칸 테두리 무지개 그라데이션(4dp, 오늘 6dp) + ★도 무지개 그라데이션
 *  - [Variant.BAND] (B) 흰 바탕 + 칸 아래쪽 무지개 띠(6색 줄무늬 10dp, 듀오 '하단 띠'와 같은 자리) + 진한 ★(본문색). 테두리는 예전 규칙(오늘 = 파랑 굵은 윤곽)
 * 날아가는 별: 흰 원 + 무지개 테두리(둘 다), ★는 A 무지개 / B 진한 색.
 */
object StickerStyle {
    enum class Variant { RING, BAND }

    /** 시안 고르기(PM 캡처용): RING = A, BAND = B. 권장 기본 = RING(스티커 자체가 무지개로 보여 사용자 말에 가장 가깝다) */
    val VARIANT: Variant = Variant.RING

    /** A: 받은 칸 무지개 테두리 두께(dp). 오늘 칸은 [RING_TODAY_DP] */
    const val RING_DP = 4
    const val RING_TODAY_DP = 6
    /** B: 칸 아래 무지개 띠 높이(dp) */
    const val BAND_DP = 10
    /** 칸 기본 안쪽 여백(StickerBoardView 예전 값) */
    const val BASE_SIDE_PAD_DP = 6
    const val BASE_VERT_PAD_DP = 8

    /** 칸 좌우 안쪽 여백: 무지개 테두리 안쪽에 글자가 닿지 않게(테두리 + 2dp 이상) */
    fun sidePadDp(v: Variant = VARIANT): Int = if (v == Variant.RING) maxOf(BASE_SIDE_PAD_DP, RING_TODAY_DP + 2) else BASE_SIDE_PAD_DP

    /** 칸 위·아래 안쪽 여백. B는 아래 띠 높이만큼 더 둔다(모든 칸 같게 → 칸 높이가 들쭉날쭉하지 않음) */
    fun topPadDp(v: Variant = VARIANT): Int = if (v == Variant.RING) maxOf(BASE_VERT_PAD_DP, RING_TODAY_DP + 2) else BASE_VERT_PAD_DP
    fun bottomPadDp(v: Variant = VARIANT): Int = if (v == Variant.RING) maxOf(BASE_VERT_PAD_DP, RING_TODAY_DP + 2) else BASE_VERT_PAD_DP + BAND_DP

    /** 받은 칸의 모양 값(순수) */
    data class EarnedLook(
        val fill: Int,
        /** 요일·횟수 글자색(늘 [fill] 한 색 위) */
        val ink: Int,
        /** ★ 색. null이면 무지개 그라데이션 */
        val starInk: Int?,
        /** 테두리를 무지개로 그릴지(A) */
        val rainbowRing: Boolean,
        /** 무지개 테두리·단색 테두리 두께(dp) */
        val strokeDp: Int,
        /** 단색 테두리 색(B, [rainbowRing] false일 때) */
        val stroke: Int,
        /** 아래 무지개 띠 높이(dp, 0이면 없음, B) */
        val bandDp: Int,
    )

    fun earned(night: Boolean, today: Boolean, v: Variant = VARIANT): EarnedLook {
        val r = DuoTokens.roles(night)
        return when (v) {
            Variant.RING -> EarnedLook(r.surface, r.text, null, true, if (today) RING_TODAY_DP else RING_DP, r.surfaceStroke, 0)
            Variant.BAND -> EarnedLook(r.surface, r.text, r.text, false, if (today) V6Panels.STAMP_TODAY_STROKE_DP else 2,
                if (today) r.primaryEdge else r.surfaceStroke, BAND_DP)
        }
    }

    /** 날아가는 별(흰 원 + 무지개 테두리)의 ★ 색. null = 무지개 */
    fun flyStarInk(night: Boolean, v: Variant = VARIANT): Int? = if (v == Variant.RING) null else DuoTokens.roles(night).text

    fun rainbow(night: Boolean): List<Int> = Palette.stickerRainbow(night)

    /** 대비 검사 대상(StickerStyleTest): 글자 / 칸 바탕, ★(단색) / 바탕, 무지개 각 색 / 바탕(비글자 3:1) */
    fun pairs(night: Boolean, v: Variant): List<DesignRules.ColorPair> {
        val t = DesignRules.Kind.TEXT
        val n = DesignRules.Kind.NON_TEXT
        val tag = if (night) "밤" else "낮"
        val e = earned(night, today = false, v = v)
        val out = arrayListOf(DesignRules.ColorPair("$tag 받은 칸 글자 / 바탕", e.ink, e.fill, t))
        if (e.starInk != null) out += DesignRules.ColorPair("$tag ★ / 바탕", e.starInk, e.fill, t)
        for ((i, c) in rainbow(night).withIndex()) out += DesignRules.ColorPair("$tag 무지개 ${i + 1} / 바탕", c, e.fill, n)
        return out
    }

    fun argbs(night: Boolean): IntArray = rainbow(night).map { Palette.argb(it) }.toIntArray()

    /** A: TextView 글자(★)를 무지개 그라데이션으로. null이면 셰이더를 걷는다. 크기가 바뀔 때마다 다시 맞춘다 */
    /** 글자별 크기 감시(메인 스레드 전용, 약한 참조라 View가 사라지면 같이 사라진다) */
    private val textListeners = java.util.WeakHashMap<TextView, android.view.View.OnLayoutChangeListener>()

    fun rainbowText(tv: TextView, night: Boolean?) {
        textListeners.remove(tv)?.let { tv.removeOnLayoutChangeListener(it) }
        if (night == null) { tv.paint.shader = null; tv.invalidate(); return }
        val colors = argbs(night)
        fun apply(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            tv.paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP)
            tv.invalidate()
        }
        val l = android.view.View.OnLayoutChangeListener { v, left, top, right, bottom, ol, ot, or2, ob ->
            if (right - left != or2 - ol || bottom - top != ob - ot) apply(v.width, v.height)
        }
        tv.addOnLayoutChangeListener(l)
        textListeners[tv] = l
        apply(tv.width, tv.height)
    }
}

/**
 * 받은 칸 바탕: 둥근 네모 채움 + (A) 무지개 그라데이션 테두리 또는 단색 테두리 + (B) 아래 무지개 띠(6색 줄무늬, 둥근 모서리 안으로 자름).
 * 글자는 이 위에 [StickerStyle.EarnedLook.fill] 한 색 부분에만 놓인다(띠는 칸 아래 여백 안).
 */
class RainbowCellDrawable(
    private val fill: Int,
    private val radiusPx: Float,
    private val colors: IntArray,
    private val rainbowRing: Boolean,
    private val strokePx: Float,
    private val stroke: Int,
    private val bandPx: Float,
    private val dashed: Boolean = false,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.argb(fill) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = strokePx; color = Palette.argb(stroke)
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(strokePx * 3f, strokePx * 2f), 0f)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        if (rainbowRing && colors.isNotEmpty() && bounds.width() > 0)
            strokePaint.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
                colors, null, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val half = strokePx / 2f
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        c.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
        if (bandPx > 0f && colors.isNotEmpty()) {
            clip.rewind(); clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
            val save = c.save()
            c.clipPath(clip)
            val top = rect.bottom - bandPx
            val w = rect.width() / colors.size
            for ((i, col) in colors.withIndex()) {
                bandPaint.color = col
                c.drawRect(rect.left + i * w, top, if (i == colors.lastIndex) rect.right else rect.left + (i + 1) * w, rect.bottom, bandPaint)
            }
            c.restoreToCount(save)
        }
        if (strokePx > 0f) c.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; strokePaint.alpha = alpha; bandPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter; strokePaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
