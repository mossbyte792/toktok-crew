package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout

/**
 * 자동차 위에서 본 좌석 지도(시안 1). 보닛이 좁은 차체 + 바퀴 4 + 앞유리 + 사이드미러 2 + 뒷유리선 + 좌석 타일 5개(앞줄 2, 뒷줄 3).
 * Path/Canvas로 직접 그린다(외부 이미지·글꼴 없음). 상태를 저장하지 않고 bind로 받아 그리기만 한다.
 * 차체·타일 사이의 빈 곳은 터치를 가로채지 않는다(아래로 통과). 크기·색은 StageSeatMap가 정한다.
 */
class StageSeatMapView(
    context: Context,
    slots: List<SeatSlot>,
    private val onTap: (String) -> Unit,
) : FrameLayout(context) {

    // 2026-11-11 U3: 넓은 화면이면 지도 전체를 StageSeatMap.scaleFor배로 그린다(도형 수치는 그대로)
    // v3 반복 6(P0): 배율은 처음 값으로 시작하고, 무대가 실제 높이를 잰 뒤 [setMapScale]로 바꿀 수 있다
    private var density = resources.displayMetrics.density * seatMapScale(resources.displayMetrics)
    private fun dpf(v: Int): Float = v * density
    private fun dp(v: Int): Int = (v * density).toInt()
    /** 지금 그리는 배율(기본 도형 dp × 이 값) */
    val mapScale: Float get() = density / resources.displayMetrics.density

    /** 배율 변경: 타일 크기·위치를 다시 정한다(타일은 자기 폭으로 그림 배율을 정하므로 따로 알릴 필요 없음). 같은 값이면 아무것도 안 한다 */
    fun setMapScale(k: Float) {
        val nd = resources.displayMetrics.density * k
        if (kotlin.math.abs(nd - density) < 0.001f) return
        density = nd
        for ((slot, tile) in tiles) {
            val lp = tile.layoutParams as FrameLayout.LayoutParams
            lp.width = dp(StageSeatMap.TILE_DP); lp.height = dp(StageSeatMap.TILE_DP)
            lp.leftMargin = dp(StageSeatMap.tileXDp(slot)); lp.topMargin = dp(StageSeatMap.tileYDp(slot))
            tile.layoutParams = lp
        }
        requestLayout(); invalidate()
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyRect = RectF()
    private val tiles = ArrayList<Pair<SeatSlot, SeatTileView>>()
    private var night = false
    private val decoPaint = Paint(Paint.ANTI_ALIAS_FLAG)   // 장식(바퀴·유리·미러·뒷유리선) 공용 붓
    private val bodyPath = Path()
    private val glassPath = Path()
    private val rearPath = Path()
    private val decoRect = RectF()

    init {
        setWillNotDraw(false)
        contentDescription = StageSeatMap.MAP_DESC
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for (slot in slots) {
            val tile = SeatTileView(context, slot)
            tile.setOnClickListener { onTap(slot.name) }
            val lp = FrameLayout.LayoutParams(dp(StageSeatMap.TILE_DP), dp(StageSeatMap.TILE_DP))
            lp.leftMargin = dp(StageSeatMap.tileXDp(slot))
            lp.topMargin = dp(StageSeatMap.tileYDp(slot))
            addView(tile, lp)
            tiles.add(Pair(slot, tile))
        }
        bindNight(Palette.current.night)
    }

    private fun bindNight(isNight: Boolean) {
        night = isNight
        invalidate()
    }

    /** 선택 표시 및 좌석별 벨트 참고 신호 갱신(render마다 호출해도 되는 가벼운 갱신) */
    fun bind(
        selectedOf: (String) -> Boolean,
        isNight: Boolean,
        beltRefOf: ((String) -> SeatBeltRef)? = null,
        occupiedOf: ((String) -> Boolean?)? = null,
    ) {
        if (night != isNight) bindNight(isNight)
        for ((slot, tile) in tiles) {
            val ref = beltRefOf?.invoke(slot.name) ?: SeatBeltRef.UNKNOWN
            val occ = occupiedOf?.invoke(slot.name)
            tile.bind(selectedOf(slot.name), isNight, ref, occ, beltRefOf != null)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 그리기 순서(모두 타일보다 먼저, 터치 없음): 1 바퀴 4 → 2 사이드미러 2 → 3 차체 채움(바퀴·미러 안쪽 절반을 덮음) → 4 차체 윤곽 → 5 앞유리 → 6 뒷유리선.
        // 타일은 자식 뷰라 이 뒤에 그려진다. 모든 장식은 지도 332×240 안, 타일 5개와 겹치지 않음(StageSeatMap.*Box, 테스트로 확인).
        val c = StageSeatMap.colors(night)
        val stroke = dpf(StageSeatMap.BODY_STROKE_DP)

        // 바퀴 4개: 10×36dp 반경 5(좌표는 StageSeatMapShape.wheels). 차체보다 먼저 그려 안쪽 5dp는 차체 채움이 덮는다
        decoPaint.style = Paint.Style.FILL
        decoPaint.color = Palette.argb(StageSeatMap.wheelColor(night))
        val wr = dpf(StageSeatMap.WHEEL_R_DP)
        for (w in StageSeatMapShape.wheels()) {
            decoRect.set(w.left * density, w.top * density, w.right * density, w.bottom * density)
            canvas.drawRoundRect(decoRect, wr, wr, decoPaint)
        }

        // 사이드미러 2개: 8×12dp 타원(y 46~58 직선 구간). 차체보다 먼저 그려 바깥 3dp만 보인다
        for (m in StageSeatMapShape.mirrors()) {
            decoRect.set(m.left * density, m.top * density, m.right * density, m.bottom * density)
            canvas.drawOval(decoRect, decoPaint)
        }

        // 차체 본체: 좌우 대칭 닫힌 Path 하나(좌표는 StageSeatMapShape.bodySegments)
        bodyPath.reset()
        for (seg in StageSeatMapShape.bodySegments()) {
            when (seg) {
                is StageSeatMapShape.Seg.Move -> bodyPath.moveTo(seg.p.x * density, seg.p.y * density)
                is StageSeatMapShape.Seg.Line -> bodyPath.lineTo(seg.p.x * density, seg.p.y * density)
                is StageSeatMapShape.Seg.Cubic -> bodyPath.cubicTo(
                    seg.c1.x * density, seg.c1.y * density, seg.c2.x * density, seg.c2.y * density, seg.e.x * density, seg.e.y * density,
                )
                StageSeatMapShape.Seg.Close -> bodyPath.close()
            }
        }
        bodyPaint.style = Paint.Style.FILL
        bodyPaint.color = Palette.argb(c.bodyFill)
        canvas.drawPath(bodyPath, bodyPaint)
        bodyPaint.style = Paint.Style.STROKE
        bodyPaint.strokeWidth = stroke
        bodyPaint.strokeJoin = Paint.Join.ROUND
        bodyPaint.strokeCap = Paint.Cap.ROUND
        bodyPaint.color = Palette.argb(c.bodyStroke)
        canvas.drawPath(bodyPath, bodyPaint)

        // 앞유리: 사다리꼴(윗변이 좁다) 연한 하늘색 채움 + 2dp 윤곽
        val gp = StageSeatMapShape.glassPoints()
        glassPath.reset()
        glassPath.moveTo(gp[0].x * density, gp[0].y * density)
        for (i in 1 until gp.size) glassPath.lineTo(gp[i].x * density, gp[i].y * density)
        glassPath.close()
        decoPaint.style = Paint.Style.FILL
        decoPaint.color = Palette.argb(StageSeatMap.GLASS_FILL)
        canvas.drawPath(glassPath, decoPaint)
        decoPaint.style = Paint.Style.STROKE
        decoPaint.strokeWidth = dpf(StageSeatMap.GLASS_STROKE_DP)
        decoPaint.strokeJoin = Paint.Join.ROUND
        decoPaint.color = Palette.argb(c.bodyStroke)
        canvas.drawPath(glassPath, decoPaint)

        // 뒷유리선: 뒷줄 아래 차체 안쪽 얇은 곡선 한 줄(2dp), 꼭짓점은 양끝보다 2dp 아래
        val rl = StageSeatMapShape.rearLine()
        rearPath.reset()
        rearPath.moveTo(rl.first.x * density, rl.first.y * density)
        rearPath.quadTo(rl.second.x * density, rl.second.y * density, rl.third.x * density, rl.third.y * density)
        decoPaint.style = Paint.Style.STROKE
        decoPaint.strokeWidth = dpf(StageSeatMap.GLASS_STROKE_DP)
        decoPaint.strokeCap = Paint.Cap.ROUND
        decoPaint.color = Palette.argb(c.bodyStroke)
        canvas.drawPath(rearPath, decoPaint)
    }
}

/** 좌석 타일 한 칸(80dp 정사각 전체가 터치 영역): 위에 좌석 모양, 아래에 24sp 글자, 선택이면 오른쪽 위 체크 배지 */
class SeatTileView(context: Context, private val slot: SeatSlot) : View(context) {
    // v3 반복 6(P0): 그림 배율은 실제 타일 폭에서 구한다(지도 배율이 바뀌어 타일 크기가 달라져도 따라감). 배치 전에는 처음 배율
    private val initialUnit = resources.displayMetrics.density * seatMapScale(resources.displayMetrics)
    private val density: Float get() = if (width > 0) width / StageSeatMap.TILE_DP.toFloat() else initialUnit
    private fun dpf(v: Int): Float = v * density
    /** 24sp × 지도 배율(px) */
    private fun labelBasePx(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, StageSeatMap.LABEL_SP, resources.displayMetrics) * (density / resources.displayMetrics.density)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val seatPath = Path()
    private var badgeIcon = IconDrawable(IconKind.CHECK, Palette.argb(StageSeatMap.colors(false).badgeCheck))
    private var selected = false
    private var night = false
    private var beltRef: SeatBeltRef = SeatBeltRef.UNKNOWN
    private var occupied: Boolean? = null
    private var showRefDot = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = labelBasePx()
        contentDescription = StageSeatMap.description(slot, false)
    }

    fun bind(
        isSelected: Boolean,
        isNight: Boolean,
        ref: SeatBeltRef = SeatBeltRef.UNKNOWN,
        occ: Boolean? = null,
        hasRefSource: Boolean = false,
    ) {
        if (selected == isSelected && night == isNight && beltRef == ref && occupied == occ && showRefDot == hasRefSource) return
        selected = isSelected
        beltRef = ref
        occupied = occ
        showRefDot = hasRefSource
        if (night != isNight) {
            night = isNight
            badgeIcon = IconDrawable(IconKind.CHECK, Palette.argb(StageSeatMap.colors(isNight).badgeCheck))   // 체크 색은 낮/밤에만 달라진다
        }
        val base = StageSeatMap.description(slot, isSelected)
        contentDescription = if (hasRefSource) StageSeatSignal.tileDescription(base, ref, occ) else base
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) StageButtons.PRESSED_ALPHA else 1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = StageSeatMap.colors(night)
        val fill = if (selected) c.selFill else c.tileFill
        val ink = if (selected) c.selText else c.tileText
        val outline = if (selected) c.selStroke else c.tileStroke
        val sw = dpf(StageSeatMap.TILE_STROKE_DP)
        val half = sw / 2f
        val w = width.toFloat()
        val rad = dpf(StageSeatMap.TILE_RADIUS_DP)
        rect.set(half, half, w - half, height - half)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Palette.argb(fill)
        canvas.drawRoundRect(rect, rad, rad, fillPaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = sw
        strokePaint.color = Palette.argb(outline)
        canvas.drawRoundRect(rect, rad, rad, strokePaint)

        // 좌석 모양: 위가 둥근 등받이(지름 약 32dp) + 아래 얇은 방석
        val iconW = dpf(StageSeatMap.SEAT_ICON_DP)
        val left = (w - iconW) / 2f
        val top = dpf(StageSeatMap.TILE_ICON_TOP_DP)
        seatPath.reset()
        rect.set(left, top, left + iconW, top + dpf(20))
        val topR = iconW / 2f
        seatPath.addRoundRect(rect, floatArrayOf(topR, topR, topR, topR, dpf(4), dpf(4), dpf(4), dpf(4)), Path.Direction.CW)
        rect.set(left + dpf(2), top + dpf(22), left + iconW - dpf(2), top + dpf(28))
        seatPath.addRoundRect(rect, dpf(3), dpf(3), Path.Direction.CW)
        fillPaint.color = Palette.argb(ink)
        canvas.drawPath(seatPath, fillPaint)

        // 글자(24sp): 줄 y 40~70(가운데 55). 위 8 + 그림 28 + 간격 4 + 줄 약 30 + 아래 여백 8 = 78 ≤ 84
        textPaint.color = Palette.argb(ink)
        // M11: 큰 글꼴에서 타일 폭을 넘으면 넘지 않을 만큼만 줄인다(접근성 이름은 contentDescription으로 그대로 전달)
        val baseSize = labelBasePx()
        textPaint.textSize = baseSize
        textPaint.textSize = StageSeatMap.fitLabelSize(baseSize, textPaint.measureText(slot.labelKo), w - 2 * dpf(StageSeatMap.LABEL_SIDE_PAD_DP))
        val fm = textPaint.fontMetrics
        val cy = dpf(StageSeatMap.TILE_TEXT_CENTER_DP)
        canvas.drawText(slot.labelKo, w / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint)

        // 좌상단 벨트 참고 신호 배지(v3: 24dp 원, 기호도 같은 배율 + 형태 기호: 색만으로 구분하지 않는다, 등받이 x=26..58과 겹치지 않음)
        if (showRefDot) {
            val rd = dpf(StageSeatMap.REF_BADGE_DP)
            val k = StageSeatMap.refGlyphScale()
            fun g(v: Int): Float = dpf(v) * k   // 기호 좌표·두께를 배지 크기에 맞춘다
            val rInset = dpf(4)
            val rcx = rInset + rd / 2f
            val rcy = rInset + rd / 2f
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Palette.argb(if (night) 0x111111 else 0xFFFFFF)
            canvas.drawCircle(rcx, rcy, rd / 2f + dpf(1), fillPaint)
            val spec = StageSeatSignal.badgeSpec(beltRef)
            val stateRgb = StageSeatSignal.dotColor(beltRef, night)
            val haloRgb = if (night) 0x111111 else 0xFFFFFF
            if (spec.filled) {
                fillPaint.color = Palette.argb(stateRgb)
                canvas.drawCircle(rcx, rcy, rd / 2f, fillPaint)
            } else {
                // 속이 빈 고리: 바탕(halo) 위에 상태 색 테두리만
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = dpf(2)
                strokePaint.color = Palette.argb(stateRgb)
                canvas.drawCircle(rcx, rcy, rd / 2f - dpf(1), strokePaint)
            }
            // 기호 색: 채운 원 위에는 halo 색, 빈 고리 안에는 상태 색
            val symRgb = if (spec.filled) haloRgb else stateRgb
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = g(2)
            strokePaint.color = Palette.argb(symRgb)
            when (spec.glyph) {
                StageSeatSignal.BadgeGlyph.BUCKLE_BAR_DOT -> {
                    // 맞물린 버클 막대 기호
                    canvas.drawLine(rcx - g(4), rcy, rcx + g(4), rcy, strokePaint)
                    fillPaint.color = Palette.argb(symRgb)
                    canvas.drawCircle(rcx, rcy, g(2), fillPaint)
                }
                StageSeatSignal.BadgeGlyph.EXCLAMATION -> {
                    // 느낌표 기호(미체결 참고)
                    canvas.drawLine(rcx, rcy - g(4), rcx, rcy + g(1), strokePaint)
                    fillPaint.color = Palette.argb(symRgb)
                    canvas.drawCircle(rcx, rcy + g(4), g(1) * 1.2f, fillPaint)
                }
                StageSeatSignal.BadgeGlyph.DASH -> {
                    // 신호 없음: 빈 고리 안 가로 줄(2026-11-11 U3)
                    canvas.drawLine(rcx - g(4), rcy, rcx + g(4), rcy, strokePaint)
                }
                StageSeatSignal.BadgeGlyph.QUESTION -> {
                    // 물음표(신호 미확인): 위 반원 고리 + 짧은 세로줄 + 아래 점
                    rect.set(rcx - g(3), rcy - g(5), rcx + g(3), rcy + g(1))
                    canvas.drawArc(rect, 180f, 270f, false, strokePaint)
                    canvas.drawLine(rcx, rcy + g(1), rcx, rcy + g(2), strokePaint)
                    fillPaint.color = Palette.argb(symRgb)
                    canvas.drawCircle(rcx, rcy + g(5), g(1) * 1.2f, fillPaint)
                }
            }
        }

        // 선택 배지: 오른쪽 위 지름 28dp 원 + 체크(색만으로 구분하지 않는다)
        if (selected) {
            val d = dpf(StageSeatMap.BADGE_DP)
            val inset = dpf(StageSeatMap.BADGE_INSET_DP)
            val bx = w - inset - d
            val by = inset
            fillPaint.color = Palette.argb(c.badgeFill)
            canvas.drawCircle(bx + d / 2f, by + d / 2f, d / 2f, fillPaint)
            val pad = (d * 0.2f).toInt()
            badgeIcon.setBounds(bx.toInt() + pad, by.toInt() + pad, (bx + d).toInt() - pad, (by + d).toInt() - pad)
            badgeIcon.draw(canvas)
        }
    }
}

/** 화면 폭(dp)으로 정한 좌석 지도 배율(2026-11-11 U3) */
@Suppress("DEPRECATION")
internal fun seatMapScale(m: android.util.DisplayMetrics): Float =
    seatMapScaleFor(m.widthPixels / m.density, m.heightPixels / m.density, if (m.density > 0f) m.scaledDensity / m.density else 1f)

/** v3 반복 5: 2영역(Medium·Wide)이면 왼쪽 영역 기준 배율(StageSplit.mapScale), 아니면 기존 등급 배율. 인셋은 무대 기본값(16dp)으로 가정 */
internal fun seatMapScaleFor(wDp: Float, hDp: Float, fontScale: Float = 1f): Float =
    if (StageSplit.enabled(wDp)) StageSplit.mapScale(wDp, StageSplit.areaHeightDp(hDp, StageLayout.INSET_FALLBACK_DP.toFloat(), StageLayout.INSET_FALLBACK_DP.toFloat()), fontScale)
    else StageSeatMap.scaleFor(wDp)
