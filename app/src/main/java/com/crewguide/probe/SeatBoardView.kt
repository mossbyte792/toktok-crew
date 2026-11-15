package com.crewguide.probe

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 2026-11-13 디자인 담당: 좌석 화면 하나(네모 칸 5개, 앞줄 2칸 위·뒷줄 3칸 아래). 칸의 **색과 글자**가 신호에 따라 바뀐다([SeatBoardRules]).
 * - 자동차 그림(차체·앞유리·거울·뒷선)은 없다(사용자 조건 3). 칸 밖 왼쪽에 작은 줄 이름 "앞줄"/"뒷줄"만 둔다.
 * - 칸 안에 위치 글자(왼쪽·오른쪽·가운데)·"!" 배지·범례 칩은 없다. 아이 배정은 칸 위쪽 줄(별명 첫 글자 원 + 별명).
 * - 아이를 배정한 칸 중 착석 확인 불가·벨트 불명 칸은 칸 안에 [보호자 확인] 버튼(76dp 이상, PM 결정 15).
 * - 받은 공간에 맞춰 배율을 정한다(가운데 정렬, [SeatBoardRules.MAX_SCALE] 이하). 글자는 칸 폭·줄 높이에 맞게 줄여 넘치지 않는다.
 * - 상태를 저장하지 않는다. 누름은 [onSeatTap](아이 고르기)과 [onGuardian](보호자 확인)으로만 알린다.
 */
class SeatBoardView(context: Context) : ViewGroup(context) {
    var onSeatTap: ((String) -> Unit)? = null
    var onGuardian: ((String) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var cells: List<SeatBoardCell> = emptyList()
    private var mode = SeatBoardMode.CHECK
    private var night = false
    private var anyButton = false
    private val tiles = LinkedHashMap<String, SeatBoardTile>()
    private val rowLabels = listOf(TextView(context), TextView(context))
    private var k = 1f
    private var ox = 0f
    private var oy = 0f
    private var mapW = 0f
    private var mapH = 0f
    private var tileH = 0f

    init {
        clipChildren = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for ((i, tv) in rowLabels.withIndex()) {
            tv.text = if (i == 0) SeatBoardRules.ROW_FRONT else SeatBoardRules.ROW_BACK
            tv.gravity = Gravity.CENTER
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setSingleLine(true)
            tv.includeFontPadding = false
            tv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // 칸마다 접근성 이름에 줄이 들어 있다
            addView(tv)
        }
    }

    /** [frozen] = r10 G 기어 확인 중: 칸·[보호자 확인]을 누르지 않는다(버튼은 비활성 모양, 자리는 그대로라 배치가 바뀌지 않음) */
    fun bind(cells: List<SeatBoardCell>, mode: SeatBoardMode, night: Boolean, frozen: Boolean = false) {
        this.cells = cells; this.mode = mode; this.night = night
        anyButton = cells.any { SeatBoardRules.guardianButton(it, mode) }
        val keep = cells.map { it.seat }.toSet()
        tiles.keys.filter { it !in keep }.forEach { removeView(tiles.remove(it)) }
        for (c in cells) {
            val t = tiles.getOrPut(c.seat) { SeatBoardTile(context).also { addView(it) } }
            t.bind(c, mode, night, onSeatTap, onGuardian, frozen)
        }
        val ink = DuoTokens.roles(night).textMuted
        rowLabels.forEach { it.setTextColor(Palette.argb(ink)) }
        contentDescription = "좌석 칸, 앞줄 2석 뒷줄 3석"
        requestLayout(); invalidate()
    }

    private fun px(dp: Float) = dp * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec); val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val wAvail = if (wMode == MeasureSpec.UNSPECIFIED) px(SeatBoardRules.mapWidthDp().toFloat()) else MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val hAvail = if (hMode == MeasureSpec.UNSPECIFIED) px(SeatBoardRules.mapHeightDp().toFloat()) else MeasureSpec.getSize(heightMeasureSpec).toFloat()
        k = SeatBoardRules.scaleFor(wAvail / d, hAvail / d, anyButton)
        tileH = px(SeatBoardRules.tileHeightDp(k, anyButton))
        mapW = px(SeatBoardRules.mapWidthDp() * k)
        mapH = px(SeatBoardRules.fixedHeightDp() * k) + 2 * tileH
        val tileW = px(SeatBoardRules.TILE_W_DP * k)
        val extra = px(SeatBoardRules.extraRowDp(k, anyButton))
        for (t in tiles.values) {
            t.prepare(k, tileW.toInt(), extra.toInt())
            t.measure(MeasureSpec.makeMeasureSpec(tileW.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tileH.toInt(), MeasureSpec.EXACTLY))
        }
        // 줄 이름: 칸 밖 왼쪽 열(폭 LABEL_COL × k)에 들어가게 글자를 줄인다
        val lw = px(SeatBoardRules.LABEL_COL_DP * k).toInt()
        val lh = (px(40f * k)).toInt()
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SeatBoardRules.LABEL_SP, resources.displayMetrics) * k
        for (tv in rowLabels) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, SeatBoardTile.fit(tv.text.toString(), tv.typeface, lw.toFloat(), lh.toFloat(), maxPx, 10 * d))
            tv.measure(MeasureSpec.makeMeasureSpec(lw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lh, MeasureSpec.EXACTLY))
        }
        val w = if (wMode == MeasureSpec.EXACTLY) wAvail.toInt() else kotlin.math.ceil(mapW).toInt()
        val h = if (hMode == MeasureSpec.EXACTLY) hAvail.toInt() else kotlin.math.ceil(mapH).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        ox = ((r - l) - mapW) / 2f
        oy = ((b - t) - mapH) / 2f
        val rowGap = px(SeatBoardRules.ROW_GAP_DP * k)
        for (c in cells) {
            val tile = tiles[c.seat] ?: continue
            val x = (ox + px(SeatBoardRules.tileXDp(c.row, c.col) * k)).toInt()
            val y = (oy + c.row * (tileH + rowGap)).toInt()
            tile.layout(x, y, x + tile.measuredWidth, y + tile.measuredHeight)
        }
        for ((row, tv) in rowLabels.withIndex()) {
            val cy = oy + row * (tileH + rowGap) + tileH / 2f
            val x = ox.toInt(); val y = (cy - tv.measuredHeight / 2f).toInt()
            tv.layout(x, y, x + tv.measuredWidth, y + tv.measuredHeight)
        }
    }
}

/**
 * 좌석 칸 하나(전체가 터치 영역): 위 = 아이 줄(별명 첫 글자 원 + 별명, 또는 '+ 아이 선택'), 가운데 = 상태 글자, 아래 = 보조 칸
 * (보호자 확인 버튼 / '모의 신호' 꼬리표 / '✓ 보호자 확인함'). 줄 높이는 [prepare]가 배율로 정하고, 글자는 줄 안에 들어가게 줄인다.
 */
class SeatBoardTile(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val badgeRow = LinearLayout(context)
    private val circle = TextView(context)
    private val name = TextView(context)
    private val status = TextView(context)
    private val extra = FrameLayout(context)
    private val guardian = DuoButton(context, DuoTokens.Kind.PRIMARY, DuoTokens.BUTTON_MIN_DP)
    private val tag = TextView(context)
    private var cell: SeatBoardCell? = null
    private var mode = SeatBoardMode.CHECK
    private var night = false
    private var prepKey: String? = null
    /** 꼬리표 원문(줄 나누기 전) */
    private var tagRaw: String = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        badgeRow.orientation = HORIZONTAL
        badgeRow.gravity = Gravity.CENTER
        badgeRow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val display = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        circle.gravity = Gravity.CENTER; circle.typeface = display; circle.includeFontPadding = false
        name.setSingleLine(true); name.gravity = Gravity.CENTER_VERTICAL; name.typeface = Typeface.DEFAULT_BOLD; name.includeFontPadding = false
        badgeRow.addView(circle)
        badgeRow.addView(name)
        status.setSingleLine(true); status.gravity = Gravity.CENTER; status.typeface = display; status.includeFontPadding = false
        status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        tag.gravity = Gravity.CENTER; tag.typeface = Typeface.DEFAULT_BOLD; tag.includeFontPadding = false
        tag.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        guardian.text = SeatBoardRules.GUARDIAN_BUTTON
        guardian.isSingleLine = true
        extra.addView(guardian, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        extra.addView(tag, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(badgeRow, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(extra, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    fun bind(c: SeatBoardCell, mode: SeatBoardMode, night: Boolean, onTap: ((String) -> Unit)?, onGuardian: ((String) -> Unit)?, frozen: Boolean = false) {
        cell = c; this.mode = mode; this.night = night
        val tone = SeatBoardRules.tone(c, mode)
        val col = SeatBoardRules.colors(tone, night)
        val emph = SeatBoardRules.emphasize(c, mode)
        background = DuoDraw.round(col.fill, SeatBoardRules.TILE_RADIUS_DP * d,
            ((if (emph) 6 else SeatBoardRules.TILE_STROKE_DP) * d).toInt(), if (emph) SeatBoardRules.emphasisStroke(night) else col.stroke)
        status.text = SeatBoardRules.text(tone)
        status.setTextColor(Palette.argb(col.ink))
        // 아이 줄
        val child = c.child
        val hint = SeatBoardRules.pickHint(c, mode)
        val showChild = child != null && mode == SeatBoardMode.CHECK || child != null && tone != SeatTone.EMPTY
        when {
            showChild -> {
                circle.text = SeatBoardRules.badgeMark(c)   // r10 J: 손님은 "◇"(별명 첫 글자 대신)
                circle.setTextColor(Palette.argb(SeatBoardRules.CHILD_INK))
                circle.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Palette.argb(SeatBoardRules.CHILD_FILL))
                    // r10 J: 손님 원은 점선 윤곽(등록 아이와 기호·모양으로도 구분)
                    if (c.guest) setStroke((3 * d).toInt(), Palette.argb(SeatBoardRules.CHILD_INK), 6 * d, 4 * d)
                    else setStroke((3 * d).toInt(), Palette.argb(SeatBoardRules.CHILD_INK))
                }
                name.text = child!!.nick
                name.setTextColor(Palette.argb(col.ink))
                badgeRow.visibility = VISIBLE
            }
            hint -> {
                circle.text = "+"
                circle.setTextColor(Palette.argb(col.ink))
                circle.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0); setStroke((2 * d).toInt(), Palette.argb(col.ink), 6 * d, 4 * d)
                }
                name.text = SeatBoardRules.PICK_HINT
                name.setTextColor(Palette.argb(col.ink))
                badgeRow.visibility = VISIBLE
            }
            else -> badgeRow.visibility = INVISIBLE   // 자리는 비워 둔다(줄마다 상태 글자 높이를 맞춤)
        }
        // 보조 칸
        val btn = SeatBoardRules.guardianButton(c, mode)
        guardian.visibility = if (btn) VISIBLE else GONE
        guardian.contentDescription = SeatBoardRules.guardianButtonDesc(c) + if (frozen) ". " + SeatBoardRules.GEAR_PENDING_DESC else ""   // r8b: 미체결 자리는 이유까지 읽는다
        // r10 G: 기어 확인 중이면 비활성 모양(DuoButtonLook 비활성 = 옅은 채움 + 점선 윤곽, 투명도 없음)이고 누름 없음
        val guardianOn = SeatBoardRules.guardianEnabled(c, mode, frozen)
        if (guardian.isEnabled != !frozen) guardian.isEnabled = !frozen
        guardian.setOnClickListener(if (guardianOn) View.OnClickListener { onGuardian?.invoke(c.seat) } else null)
        val done = SeatBoardRules.guardianDoneTag(c, mode)
        val mock = SeatBoardRules.mockTagText(c, mode)
        when {
            !btn && done -> {
                tagRaw = SeatBoardRules.GUARDIAN_DONE; tag.text = SeatBoardRules.GUARDIAN_DONE
                tag.setTextColor(Palette.argb(SeatBoardRules.CHILD_INK))
                tag.background = DuoDraw.round(SeatBoardRules.CHILD_FILL, 14 * d, (2 * d).toInt(), SeatBoardRules.CHILD_INK)
                tag.visibility = VISIBLE
            }
            !btn && mock != null -> {
                tagRaw = mock; tag.text = mock
                tag.setTextColor(Palette.argb(SeatBoardRules.MOCK_INK))
                tag.background = DuoDraw.round(SeatBoardRules.MOCK_FILL, 14 * d, (2 * d).toInt(), SeatBoardRules.MOCK_INK, dashed = true)
                tag.visibility = VISIBLE
            }
            // r10 I: 카시트 꼬리표(정보 칩 색, 기호 + 글자). 보조 칸이 비었을 때만
            SeatBoardRules.carSeatTag(c, mode) -> {
                val ic = DuoTokens.infoChip(night)
                tagRaw = SeatBoardRules.CAR_SEAT_TAG; tag.text = SeatBoardRules.CAR_SEAT_TAG
                tag.setTextColor(Palette.argb(ic.fg))
                tag.background = DuoDraw.round(ic.bg, 14 * d, (2 * d).toInt(), ic.stroke)
                tag.visibility = VISIBLE
            }
            else -> tag.visibility = GONE
        }
        val p = (6 * d).toInt(); tag.setPadding(p, (4 * d).toInt(), p, (4 * d).toInt())
        // 터치
        val tappable = SeatBoardRules.tappableNow(c, mode, frozen)   // r10 G: 기어 확인 중이면 누르지 않음
        isClickable = tappable
        setOnClickListener(if (tappable) View.OnClickListener { onTap?.invoke(c.seat) } else null)
        if (!tappable) isClickable = false
        contentDescription = SeatBoardRules.a11y(c, mode, frozen)
        prepKey = null
        requestLayout(); invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed && isClickable) StageButtons.PRESSED_ALPHA else 1f
    }

    /** 배율 k와 칸 폭(px)·보조 칸 높이(px)로 줄 높이와 글자 크기를 정한다(SeatBoardView.onMeasure에서 자식 측정 전에 부른다) */
    fun prepare(k: Float, tileW: Int, extraPx: Int) {
        val key = "$k|$tileW|$extraPx|${status.text}|${name.text}|${circle.text}|$tagRaw|${tag.visibility}|${guardian.visibility}|${resources.configuration.fontScale}"
        if (key == prepKey) return
        prepKey = key
        val pad = (SeatBoardRules.TILE_PAD_DP * k * d).toInt()
        setPadding(pad, pad, pad, pad)
        val inner = tileW - 2 * pad
        val gap = (SeatBoardRules.SLOT_GAP_DP * k * d).toInt()
        val badgeH = (SeatBoardRules.BADGE_ROW_DP * k * d).toInt()
        val statusH = (SeatBoardRules.STATUS_ROW_DP * k * d).toInt()
        (badgeRow.layoutParams as LayoutParams).apply { height = badgeH }
        (status.layoutParams as LayoutParams).apply { height = statusH; topMargin = gap }
        (extra.layoutParams as LayoutParams).apply { height = extraPx; topMargin = gap }
        // 아이 원 + 별명
        val cs = badgeH
        circle.layoutParams = LayoutParams(cs, cs)
        circle.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit(circle.text.toString(), circle.typeface, cs * 0.62f, cs * 0.62f, sp(SeatBoardRules.NAME_SP) * k, 8 * d))
        val nameGap = (6 * k * d).toInt()
        name.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, cs).apply { leftMargin = nameGap }
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit(name.text.toString(), name.typeface, (inner - cs - nameGap).toFloat(), cs.toFloat(), sp(SeatBoardRules.NAME_SP) * k, 8 * d))
        // 상태 글자(가장 큰 글자)
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit(status.text.toString(), status.typeface, inner.toFloat(), statusH.toFloat(), sp(SeatBoardRules.STATUS_SP) * k, 10 * d))
        // 보호자 확인 버튼(DuoButton 좌우 여백 16dp·아래 띠 6dp)
        val bp = (8 * d).toInt()
        guardian.setPadding(bp, 0, bp, (DuoTokens.EDGE_DP * d).toInt())
        guardian.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit(guardian.text.toString(), guardian.typeface, (inner - 2 * bp - 2).toFloat(), extraPx - DuoTokens.EDGE_DP * d - 8 * d, sp(SeatBoardRules.BUTTON_SP), 10 * d))
        // 꼬리표
        // 꼬리표: 한 줄이 읽기 어려울 만큼 작아지면(18dp 미만) 두 줄("벨트:" / "모의 신호")로 나눠 크게 쓴다
        val raw = tagRaw
        val tagMax = sp(SeatBoardRules.SMALL_SP) * maxOf(k, 0.8f)
        val tagW = inner - 12 * d - 4
        val one = fit(raw, tag.typeface, tagW, extraPx - 12 * d, tagMax, 9 * d)
        val two = one < 18 * d && raw.contains(": ")
        tag.text = SeatBoardRules.tagText(raw, two)
        tag.maxLines = if (two) 2 else 1
        val lines = tag.text.toString().split('\n')
        val longest = lines.maxByOrNull { it.length } ?: raw
        tag.setTextSize(TypedValue.COMPLEX_UNIT_PX,
            if (two) fit(longest, tag.typeface, tagW, (extraPx - 12 * d) / 2f, tagMax, 9 * d) else one)
    }

    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun fit(text: String, tf: Typeface?, availW: Float, availH: Float, maxPx: Float, minPx: Float): Float =
        Companion.fit(text, tf, availW, availH, maxPx, minPx)

    companion object {
        /** 폭 availW·높이 availH에 들어가는 가장 큰 글자 크기(px). maxPx에서 1px씩 줄이고 minPx 아래로는 내리지 않는다 */
        fun fit(text: String, tf: Typeface?, availW: Float, availH: Float, maxPx: Float, minPx: Float): Float {
            if (text.isEmpty()) return maxPx
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG); p.typeface = tf
            var s = maxPx
            while (s > minPx) {
                p.textSize = s
                val fm = p.fontMetrics
                if (p.measureText(text) <= availW && (fm.descent - fm.ascent) <= availH) break
                s -= 1f
            }
            return s.coerceAtLeast(minPx)
        }
    }
}
