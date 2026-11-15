package com.crewguide.probe

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 2026-11-14 디자인 담당(지시 4): 이동 흐름 무대의 말풍선 칸. 지금 말풍선은 캐릭터 머리 옆(꼬리가 캐릭터 쪽), 이전 말풍선은 그 위로 쌓이고
 * 새 말풍선이 생기면 [TripBubbleRules.PUSH_MS] 동안 위로 밀려 올라가며 옅어진다. 위쪽은 세로 그라데이션 페이드 마스크라
 * 무대 카드 위 경계선 근처에서 완전히 안 보인다(글꼴 2.0으로 커져 경계에 걸려도 잘린 모양이 보이지 않음).
 * - 지금 말풍선만 화면 읽기 대상(live region). 이전 말풍선은 장식(importantForAccessibility=no, [DECOR_TAG])
 * - 기록에서 빠진 말풍선은 밀려 올라간 뒤 뷰에서 제거한다. 애니메이션 배율 0·화면 숨김·잠금이면 움직임 없이 바로 바꾼다
 * - 위치 기준(캐릭터 머리·버튼 위 한계·캐릭터 윤곽)은 [geometry]가 준다(부모가 캐릭터·버튼을 먼저 배치한 뒤 이 칸을 배치한다)
 * - 2026-11-14 겹침 수정: 말풍선 상자(꼬리·그림자 포함)는 캐릭터의 실제 보이는 부분 왼쪽에만 놓인다. 오른쪽 한계는 묶음 아랫변 높이까지의
 *   윤곽으로 정하고([Geo.rightUpTo]), 글자가 길어 아랫변이 내려가면 그 높이로 다시 잰다. 위 경계에 얇게 걸린 이전 말풍선은 그리지 않는다
 */
class TripBubbleColumn(context: Context) : ViewGroup(context) {
    /**
     * 이 칸 좌표(px): 지금 말풍선 아랫변 목표, 아래 한계(버튼 위),
     * [rightUpTo](y) = 아랫변이 y인 말풍선 묶음의 오른쪽 끝(꼬리 끝) 한계(캐릭터 윤곽·카드 안쪽 여백, [TripBubbleRules.rightLimit])
     */
    class Geo(val anchorBottom: Int, val bottomLimit: Int, val rightUpTo: (Int) -> Int)

    var geometry: (() -> Geo?)? = null

    private val d = resources.displayMetrics.density
    private val gapPx = (TripBubbleRules.GAP_DP * d).toInt()
    private val minPeekPx = (TripBubbleRules.MIN_PEEK_DP * d).toInt()
    private class Entry(val id: Long, val tv: TextView, var bg: BubbleBackground, var leaving: Boolean = false, var lastTop: Int? = null, var targetAlpha: Float = 1f,
        /** 위 경계 밖(또는 얇게 걸림)이라 그리지 않는 이전 말풍선. 밀려 올라가는 동안에는 보이고 끝나면 감춘다 */
        var hide: Boolean = false)
    private val entries = ArrayList<Entry>()   // 오래된 것 먼저(떠나는 말풍선 포함)
    private var night: Boolean = Palette.current.night
    private var animateNext = false
    private var anim: ValueAnimator? = null
    private var fadeEndPx = 0
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    init {
        clipChildren = true
        setWillNotDraw(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /**
     * 기록을 화면에 맞춘다. [animate]가 false면 위치·투명도를 바로 적용한다.
     * 같은 id의 글자가 바뀌면(이어 말한 문장) 그 자리에서 글자만 바꾼다.
     */
    fun bind(log: TripBubbleLog, nightMode: Boolean, animate: Boolean) {
        if (nightMode != night) { night = nightMode; for (e in entries) restyle(e) }
        val ids = log.items.map { it.id }.toSet()
        var changed = false
        for (e in entries) if (!e.leaving && e.id !in ids) { e.leaving = true; changed = true }
        for (item in log.items) {
            val e = entries.firstOrNull { it.id == item.id }
            if (e == null) {
                entries.add(create(item)); changed = true
            } else if (e.tv.text.toString() != item.text) {
                e.tv.text = item.text; e.tv.contentDescription = item.text; changed = true
            }
        }
        val currentId = log.current?.id
        for (e in entries) {
            val cur = e.id == currentId && !e.leaving
            e.bg.setHasTail(cur)
            if (cur) e.bg.setTail(TailDir.RIGHT, Float.NaN, Float.NaN)
            e.tv.importantForAccessibility = if (cur) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            e.tv.accessibilityLiveRegion = if (cur) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
            e.tv.tag = if (cur) null else DECOR_TAG
        }
        if (!changed) return
        animateNext = animate
        if (!animate) dropLeaving()
        requestLayout()
        invalidate()
    }

    /** 모두 지운다(홈으로·새 이동·resetBubbles) */
    fun clear() {
        anim?.cancel(); anim = null
        for (e in entries) removeView(e.tv)
        entries.clear()
        invalidate()
    }

    private fun dropLeaving() {
        val gone = entries.filter { it.leaving }
        for (e in gone) { removeView(e.tv); entries.remove(e) }
    }

    private fun create(item: TripBubble): Entry {
        val tv = TextView(context)
        tv.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        tv.gravity = Gravity.CENTER
        tv.setLineSpacing(0f, 1.05f)
        tv.breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
        DuoText.wordBreak(tv)
        val h = (24 * d).toInt(); val v = (18 * d).toInt()
        tv.setPadding(h, v, h + (StageBubble.TAIL_EXTENT_DP * d).toInt(), v + (StageBubble.BOTTOM_RESERVE_DP * d).toInt())
        tv.text = item.text
        tv.contentDescription = item.text
        val e = Entry(item.id, tv, BubbleBackground(bubbleColorsOf(night), d))
        restyle(e)
        tv.alpha = 0f   // 첫 배치에서 정한다
        addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun restyle(e: Entry) {
        val c = bubbleColorsOf(night)
        val bg = BubbleBackground(c, d)
        bg.setTail(TailDir.RIGHT, Float.NaN, Float.NaN)
        e.tv.background = bg
        e.tv.setTextColor(Palette.argb(c.textRgb))
        e.bg = bg   // 꼬리 상태는 bind가 다시 정한다
    }

    // ---- 크기·배치 ----
    private fun textPxFor(tv: TextView, w: Int, hMax: Int): Float {
        val fs = minOf(resources.configuration.fontScale, V6Tokens.BUBBLE_MAX_FONT_SCALE)
        val maxPx = V6Tokens.BUBBLE_SP * fs * d
        val minPx = 22f * d
        val textW = w - tv.paddingLeft - tv.paddingRight
        val padV = tv.paddingTop + tv.paddingBottom
        var s = maxPx
        if (textW > 0) while (s > minPx) {
            if (layoutHeight(tv, s, textW) + padV <= hMax) break
            s -= 1f
        }
        return s.coerceAtLeast(minPx)
    }

    private fun layoutHeight(tv: TextView, sizePx: Float, w: Int): Int {
        val t = tv.text.toString()
        val p = android.text.TextPaint(tv.paint); p.textSize = sizePx
        val b = StaticLayout.Builder.obtain(t, 0, t.length, p, w).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f).setIncludePad(true).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
        if (Build.VERSION.SDK_INT >= 33) b.setLineBreakConfig(android.graphics.text.LineBreakConfig.Builder()
            .setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build())
        return b.build().height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val g = geometry?.invoke()
        val limit = (g?.bottomLimit ?: h).coerceIn(1, h)
        val anchor = (g?.anchorBottom ?: limit).coerceIn(0, limit)
        measureEntries((g?.rightUpTo?.invoke(anchor) ?: w).coerceIn(1, w), limit)
    }

    private var measuredFor = 0 to 0

    /** 말풍선 크기(글자 크기 포함)를 폭·아래 한계에 맞춘다. 캐릭터·버튼 위치는 배치 뒤에야 정확해 onLayout에서 다시 부를 수 있다 */
    private fun measureEntries(maxW: Int, limit: Int) {
        measuredFor = maxW to limit
        for (e in entries) {
            val target = textPxFor(e.tv, maxW, limit)
            if (kotlin.math.abs(e.tv.textSize - target) > 0.5f) e.tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            e.tv.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 진행 중 움직임은 먼저 끝낸다(끝 처리에서 떠난 말풍선을 지우므로 배치 계산 전에)
        anim?.let { a -> anim = null; a.cancel() }
        val cw = r - l; val h = b - t
        val g = geometry?.invoke()
        val limit = (g?.bottomLimit ?: h).coerceIn(1, h)
        val anchor = (g?.anchorBottom ?: limit).coerceIn(0, limit)
        val live = entries.filter { !it.leaving }
        fun rightFor(y: Int): Int = (g?.rightUpTo?.invoke(y) ?: cw).coerceIn(1, cw)
        fun topsFor(rt: Int): List<Int> {
            if (measuredFor != (rt to limit)) measureEntries(rt, limit)
            return TripBubbleRules.tops(live.map { it.tv.measuredHeight }, anchor, limit, gapPx)
        }
        fun bottomOf(tp: List<Int>): Int = if (live.isEmpty()) anchor else tp.last() + live.last().tv.measuredHeight
        // 오른쪽 한계: 먼저 기준 높이(머리 옆)까지의 윤곽으로 잰다. 글자가 길어(글꼴 2.0) 지금 말풍선이 위 경계에 닿고 아랫변이 기준보다
        // 내려오면, 그 높이까지의 윤곽(묶은 머리·팔)을 피해 더 좁게 다시 잰다. 좁아져 더 내려오면 버튼 위 한계 높이까지 본다(여기서 끝)
        var right = rightFor(anchor)
        var tops = topsFor(right)
        val firstBottom = bottomOf(tops)
        if (firstBottom > anchor && rightFor(firstBottom) < right) {
            right = rightFor(firstBottom); tops = topsFor(right)
            if (bottomOf(tops) > firstBottom && rightFor(limit) < right) { right = rightFor(limit); tops = topsFor(right) }
        }
        // 떠나는 말풍선은 가장 오래된 말풍선 위로 이어서 쌓는다(위로 밀려 마스크에서 사라짐)
        var above = tops.firstOrNull() ?: 0
        val leavingTops = HashMap<Entry, Int>()
        for (e in entries.filter { it.leaving }.asReversed()) { above -= gapPx + e.tv.measuredHeight; leavingTops[e] = above }
        fadeEndPx = TripBubbleRules.fadeEnd(tops.lastOrNull() ?: 0, gapPx)
        val doAnim = animateNext && ValueAnimator.areAnimatorsEnabled()
        animateNext = false
        val starts = ArrayList<Triple<Entry, Float, Float>>()   // (entry, 시작 translationY, 시작 alpha)
        for (e in entries) {
            val top = if (e.leaving) leavingTops[e] ?: 0 else tops[live.indexOf(e)]
            val w = e.tv.measuredWidth
            e.tv.layout(right - w, top, right, top + e.tv.measuredHeight)
            val rank = if (e.leaving) TripBubbleRules.MAX_ITEMS else live.size - 1 - live.indexOf(e)
            val targetAlpha = if (e.leaving) 0f else TripBubbleRules.alphaOf(rank)
            val wasHidden = e.hide
            e.hide = !e.leaving && rank > 0 && !TripBubbleRules.prevShown(top, e.tv.measuredHeight, minPeekPx)
            val prevTop = e.lastTop
            e.lastTop = top
            if (doAnim) {
                val fromY = if (prevTop != null) (prevTop - top).toFloat() else gapPx * 2f
                val fromA = if (prevTop != null) e.tv.alpha else 0f
                starts += Triple(e, fromY, fromA)
                e.tv.translationY = fromY; e.tv.alpha = fromA
                e.targetAlpha = targetAlpha
                // 밀려 올라가 감춰질 말풍선은 움직이는 동안 보이고 끝에서 감춘다. 이미 감춘 것은 다시 보이지 않게 둔다
                e.tv.visibility = if (e.hide && wasHidden) View.INVISIBLE else View.VISIBLE
            } else {
                e.tv.translationY = 0f; e.tv.alpha = targetAlpha
                e.tv.visibility = if (e.hide) View.INVISIBLE else View.VISIBLE
            }
        }
        if (!doAnim) { if (entries.any { it.leaving }) post { dropLeaving(); invalidate() }; return }
        val a = ValueAnimator.ofFloat(0f, 1f).setDuration(TripBubbleRules.PUSH_MS)
        a.interpolator = android.view.animation.DecelerateInterpolator()
        a.addUpdateListener { va ->
            val f = va.animatedFraction
            for ((e, y0, a0) in starts) {
                e.tv.translationY = y0 * (1f - f)
                e.tv.alpha = a0 + (e.targetAlpha - a0) * f
            }
            invalidate()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                for ((e, _, _) in starts) { e.tv.translationY = 0f; e.tv.alpha = e.targetAlpha; if (e.hide) e.tv.visibility = View.INVISIBLE }
                dropLeaving(); invalidate()
            }
        })
        anim = a
        post { if (anim === a) a.start() }
    }

    /** 위쪽 페이드 마스크: 0(경계선)에서 완전 투명 → [fadeEndPx]에서 불투명. 지금 말풍선은 그 아래라 영향 없음 */
    override fun dispatchDraw(canvas: Canvas) {
        val fe = fadeEndPx
        if (fe <= 0 || entries.size <= 1) { super.dispatchDraw(canvas); return }
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        maskPaint.shader = LinearGradient(0f, 0f, 0f, fe.toFloat(), 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), fe.toFloat(), maskPaint)
        canvas.restoreToCount(sc)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }

    /** 미리보기·검사용: 기록에 있는 말풍선 수(떠나는 것 제외) */
    val liveCount: Int get() = entries.count { !it.leaving }
    /** 미리보기·검사용: 그중 실제로 그리는 수(위 경계 밖이라 감춘 이전 말풍선 제외) */
    val shownCount: Int get() = entries.count { !it.leaving && !it.hide }

    companion object {
        /** 이전 말풍선(장식) 표시. PreviewLayoutAudit가 잘림·대비 집계에서 뺀다 */
        const val DECOR_TAG = "bubble-decor"
    }
}
