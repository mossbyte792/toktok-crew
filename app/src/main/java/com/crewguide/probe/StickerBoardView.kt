package com.crewguide.probe

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UI/UX v3 S05: 아이 한 명의 이번 주 도장판(월~일 7칸). 표시 전용 — 기록을 바꾸지 않는다.
 * 색은 [DuoTokens]의 이미 대비 검사된 짝만 쓴다: 받은 날 = 무지개 스티커(r10e [StickerStyle], 흰 바탕 + 무지개 테두리/띠 + 진한 글자), 없는 날 = 카드 바탕 + 보조 글자,
 * 앞으로 올 날 = 비활성 짝, 오늘 = 주버튼 띠색 굵은 윤곽 + 글자 “오늘”(색만으로 구분하지 않음).
 *
 * 2026-11-14 디자인 C(스티커 연출): [setTodayPending]이 true인 동안 오늘 칸은 '별이 아직 도착하지 않은' 모습이다
 * (카드 바탕 + 요일 글자만. ★·“참여 N번” 글자는 자리만 차지하고 보이지 않는다 → 틀린 횟수를 보이지 않는다).
 * 별이 도착하면 [StickerFlyHost]가 false로 돌려 결과 모습이 된다. 읽기 이름(contentDescription)은 처음부터 결과(기록은 이미 끝났다).
 */
class StickerBoardView(context: Context) : LinearLayout(context) {
    private var cells: List<BoardCell> = emptyList()
    private var todayIndex = -1
    private var todayTexts: List<TextView> = emptyList()

    /** 오늘 칸이 '별 도착 전' 모습인가 */
    var todayPending = false
        private set

    /** 오늘 칸(없으면 null)과 그 안의 ★ 글자(별이 들어갈 자리) */
    val todayCell: View? get() = if (todayIndex in 0 until childCount) getChildAt(todayIndex) else null
    val todayGlyph: View? get() = todayTexts.getOrNull(1)

    init {
        orientation = HORIZONTAL
        isFocusable = false
        clipChildren = false   // 착지 강조 때 오늘 칸이 살짝 커진다(이웃 칸 사이 틈 안)
    }

    fun bind(cells: List<BoardCell>, todayPending: Boolean = false) {
        removeAllViews()
        this.cells = cells
        this.todayPending = todayPending
        todayIndex = cells.indexOfFirst { it.isToday }
        todayTexts = emptyList()
        val gap = DuoDraw.dp(context, V6Panels.STAMP_GAP_DP)
        for ((i, c) in cells.withIndex()) {
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // r10e: 무지개 테두리(A)·아래 띠(B)에 글자가 닿지 않게 여백을 시안별로(모든 칸 같게)
                val p = DuoDraw.dp(context, StickerStyle.sidePadDp())
                setPadding(p, DuoDraw.dp(context, StickerStyle.topPadDp()), p, DuoDraw.dp(context, StickerStyle.bottomPadDp()))
                minimumHeight = DuoDraw.dp(context, DuoTokens.BUTTON_MIN_DP + 24)
                isFocusable = true
                contentDescription = StickerBoard.a11y(c)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            fun line(t: String, sp: Float, bold: Boolean) = WordWrapText(context, t).apply {
                textSize = sp; gravity = Gravity.CENTER
                if (bold) typeface = Typeface.DEFAULT_BOLD
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val texts = ArrayList<TextView>()
            texts += line(StickerBoard.dayLabel(c), DuoTokens.CAPTION_SP, true)
            texts += line(StickerBoard.glyph(c), DuoTokens.TITLE_SP, true)
            val ct = StickerBoard.countText(c)
            if (ct.isNotEmpty()) texts += line(ct, DuoTokens.CAPTION_SP - 4f, false)
            for (t in texts) cell.addView(t)
            if (i == todayIndex) todayTexts = texts
            style(cell, texts, c)
            addView(cell, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = gap
            })
        }
    }

    /** 오늘 칸을 '별 도착 전'(true) / 결과(false) 모습으로. 칸 크기·자리는 바뀌지 않는다(글자는 INVISIBLE) */
    fun setTodayPending(pending: Boolean) {
        if (pending == todayPending) return
        todayPending = pending
        val cell = todayCell as? LinearLayout ?: return
        style(cell, todayTexts, cells[todayIndex])
    }

    /** 칸 바탕·윤곽·글자색. 글자 순서 = 요일, 큰 기호, (횟수) */
    private fun style(cell: LinearLayout, texts: List<TextView>, c: BoardCell) {
        val night = Palette.current.night
        val r = DuoTokens.roles(night)
        val look = StickerFly.lookOf(c, todayPending)
        val radius = DuoDraw.dp(context, V6Panels.STAMP_RADIUS_DP).toFloat()
        if (look == StickerFly.CellLook.EARNED) {
            // r10e(사용자 지시): 받은 칸은 주황 채움 대신 무지개 스티커. 글자는 흰(카드) 바탕 한 색 위
            val e = StickerStyle.earned(night, c.isToday)
            cell.background = RainbowCellDrawable(e.fill, radius, StickerStyle.argbs(night), e.rainbowRing,
                DuoDraw.dp(context, e.strokeDp).toFloat(), e.stroke, DuoDraw.dp(context, e.bandDp).toFloat())
            for ((i, t) in texts.withIndex()) {
                t.setTextColor(Palette.argb(e.ink))
                t.visibility = VISIBLE
                if (i == 1) {
                    if (e.starInk == null) StickerStyle.rainbowText(t, night) else { StickerStyle.rainbowText(t, null); t.setTextColor(Palette.argb(e.starInk)) }
                }
            }
            return
        }
        val fill = when (look) { StickerFly.CellLook.FUTURE -> r.disabledFill; else -> r.surface }
        val ink = when (look) { StickerFly.CellLook.FUTURE -> r.disabledText; else -> r.textMuted }
        val strokeColor = if (c.isToday) r.primaryEdge else if (c.isFuture) r.disabledStroke else r.surfaceStroke
        val strokePx = DuoDraw.dp(context, if (c.isToday) V6Panels.STAMP_TODAY_STROKE_DP else 2)
        cell.background = DuoDraw.round(fill, radius, strokePx, strokeColor, dashed = c.isFuture)
        for ((i, t) in texts.withIndex()) {
            if (i == 1) StickerStyle.rainbowText(t, null)   // 받은 모습에서 돌아올 때 무지개 글자 걷기
            t.setTextColor(Palette.argb(ink))
            t.visibility = if (look == StickerFly.CellLook.PENDING && i > 0) INVISIBLE else VISIBLE
        }
    }
}

/** 폭이 정해질 때 [StickerBoard.wrapAtWords]로 낱말 경계에서만 줄을 나누는 글자(도장판 칸 전용) */
private class WordWrapText(context: Context, private val raw: String) : TextView(context) {
    init { text = raw }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && w > 0) {
            val t = StickerBoard.wrapAtWords(raw, paint.measureText(raw), (w - paddingLeft - paddingRight).toFloat())
            if (text.toString() != t) text = t
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
