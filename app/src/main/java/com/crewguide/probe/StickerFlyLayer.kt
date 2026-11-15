package com.crewguide.probe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 2026-11-14 디자인 C: 스티커 별 연출을 그리는 얇은 층. 카드 위에 얹혀(ViewGroupOverlay) 별 하나와 강조 테두리 하나만 그린다.
 * 장식이라 읽기 대상이 아니고 터치도 받지 않는다. 값은 [StickerFlyHost]가 프레임마다 넣는다.
 * 별 = r10e 무지개 스티커: 흰(카드) 원 + 무지개 테두리(SweepGradient) + ★ 도형(시안 A 무지개 / B 진한 색, [StickerStyle]). 강조 테두리 = 메인 파랑.
 */
class StickerFlyLayer(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = StickerFly.HALO_STROKE_DP * d }
    private val starPath = Path()
    private val haloRect = RectF()
    private var cx = 0f
    private var cy = 0f
    private var size = 0f
    private var starAlpha = 0f
    private var haloAlpha = 0f
    private var haloRadius = 0f
    /** r10e: 무지개 색(ARGB)과 ★ 색(null = 무지개) */
    private var rainbow: IntArray = IntArray(0)
    private var starInk: Int? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun colors(night: Boolean) {
        val r = DuoTokens.roles(night)
        fill.color = Palette.argb(r.surface)
        rainbow = StickerStyle.argbs(night)
        ring.color = Palette.argb(r.text)
        starInk = StickerStyle.flyStarInk(night)
        ink.color = Palette.argb(starInk ?: r.text)
        haloPaint.color = Palette.argb(r.primaryFill)
    }

    /** 별: 가운데([x],[y])·지름 [sizePx]·투명도 */
    fun star(x: Float, y: Float, sizePx: Float, alpha: Float) { cx = x; cy = y; size = sizePx; starAlpha = alpha.coerceIn(0f, 1f) }

    /** 강조 테두리: 칸 네모([l],[t],[r],[b])에서 [growPx]만큼 바깥, 투명도. alpha 0이면 그리지 않는다 */
    fun halo(l: Float, t: Float, r: Float, b: Float, growPx: Float, radiusPx: Float, alpha: Float) {
        haloRect.set(l - growPx, t - growPx, r + growPx, b + growPx)
        haloRadius = radiusPx + growPx
        haloAlpha = alpha.coerceIn(0f, 1f)
    }

    fun clear() { starAlpha = 0f; haloAlpha = 0f; invalidate() }

    override fun onDraw(c: Canvas) {
        if (haloAlpha > 0f) {
            haloPaint.alpha = (haloAlpha * 255).toInt()
            c.drawRoundRect(haloRect, haloRadius, haloRadius, haloPaint)
        }
        if (starAlpha > 0f && size > 0f) {
            val a = (starAlpha * 255).toInt()
            val rad = size / 2f
            val stroke = (size * 0.08f).coerceAtLeast(3f * d)   // r10e: 무지개 테두리가 보이게 조금 굵게
            fill.alpha = a; ring.alpha = a; ink.alpha = a
            ring.strokeWidth = stroke
            if (rainbow.isNotEmpty()) ring.shader = android.graphics.SweepGradient(cx, cy, rainbow + rainbow[0], null)
            ink.shader = if (starInk == null && rainbow.isNotEmpty())
                android.graphics.LinearGradient(cx - rad, cy - rad, cx + rad, cy + rad, rainbow, null, android.graphics.Shader.TileMode.CLAMP) else null
            c.drawCircle(cx, cy, rad - stroke / 2f, fill)
            c.drawCircle(cx, cy, rad - stroke / 2f, ring)
            // ★ 도형(글꼴과 무관): 바깥 반지름 0.56, 안쪽 0.24
            starPath.rewind()
            val ro = rad * 0.56f; val ri = rad * 0.24f
            for (i in 0 until 10) {
                val ang = -PI / 2 + i * PI / 5
                val rr = if (i % 2 == 0) ro else ri
                val px = cx + (rr * cos(ang)).toFloat(); val py = cy + (rr * sin(ang)).toFloat() + rad * 0.03f
                if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
            }
            starPath.close()
            c.drawPath(starPath, ink)
        }
    }
}

/**
 * 모두 내림 화면 왼쪽 카드의 스티커 칸(주간 판 + 한 줄)과 별 연출을 맡는다. [SeatBoardStage]는 [bind]만 부른다.
 * - [box] 안에 판([StickerBoardView])과 "★ 오늘 칸에 별이 생겼어요" 줄을 만든다. 스티커 값(줄·야간·주간 칸·글꼴 배율)이 같으면 다시 만들지 않는다
 *   (말풍선 등 다른 값만 바뀐 다시 그리기가 도는 연출을 끊지 않게).
 * - 처음 본 지급이면 한 번 정한다([StickerFly.decide]): 움직일 수 있으면 [StickerFly.TOTAL_MS] 동안 재생, 아니면 결과만.
 * - 재생 중 화면이 가려지거나(앱이 뒤·무대 숨김) 뷰가 떨어지거나 잠금으로 줄이 사라지면 바로 결과 모습으로 끝낸다. 다시 재생하지 않는다.
 * 기록·음성은 건드리지 않는다(표시 전용).
 */
class StickerFlyHost(
    private val card: ViewGroup,
    private val box: LinearLayout,
    /** (예전) 별이 나타날 기본 영역. 2026-11-14 캡처 뒤로는 빈칸이 모자라면 오늘 칸 자리에서 나타나 쓰지 않는다(호환용으로 남김) */
    @Suppress("unused") private val area: View,
    /** 먼저 볼 빈 곳(안내 글 아래 빈칸). 보이고 별이 들어갈 만큼 크면 여기서 나타난다(좌석 칸을 가리지 않게). 없으면 null */
    private val blank: () -> View? = { null },
) {
    private val context: Context = box.context
    private val d = context.resources.displayMetrics.density
    private var sig: String? = null
    private var board: StickerBoardView? = null
    /** "★ 오늘 칸에 별이 생겼어요" 줄(별이 들어가기 전에는 INVISIBLE) */
    private var gotLine: TextView? = null
    private var layer: StickerFlyLayer? = null
    private var anim: ValueAnimator? = null
    private var night = false
    /** 재생을 정했지만 아직 시작 전(다음 프레임에 [start])인가. 그 사이 판이 다시 만들어져도 잃지 않는다 */
    private var wantStart = false
    private val tmp = Rect()

    init {
        // 착지 강조 때 오늘 칸이 판·카드 안쪽 여백 밖으로 몇 dp 커진다(가장자리 칸이 잘리지 않게)
        box.clipChildren = false
        card.clipChildren = false
        card.clipToPadding = false
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { wantStart = false; finish() }
        })
    }

    /** 지금 연출이 돌고 있는가(테스트·미리보기 확인용) */
    val running: Boolean get() = anim != null

    /** 마지막 [bind] 값(계약 값 [setJustGranted]가 바뀌면 같은 값으로 다시 그린다) */
    private var last: Args? = null
    private var contractJust: Boolean? = null

    private data class Args(val line: SeatBoardRules.StickerLine?, val phase: AutoPhase, val parkAllowed: Boolean, val night: Boolean,
                            val week: List<BoardCell>, val children: List<ChildTag>?)

    /**
     * 계약 값 [AutoCheckUi.stickerJustGranted]를 받는다(2026-11-14 루프 r1 C5). null이면 줄 글자로 판단(호환).
     * 실제로 '방금 받음'이 바뀔 때만 다시 그린다(같은 값이면 도는 연출을 끊지 않는다).
     */
    fun setJustGranted(v: Boolean?) {
        val before = last?.let { StickerFly.justGranted(it.line, contractJust) }
        contractJust = v
        val a = last ?: return
        if (StickerFly.justGranted(a.line, v) != before) { sig = null; bind(a.line, a.phase, a.parkAllowed, a.night, a.week, a.children) }
    }

    /**
     * [line] = 장면 모델의 스티커 줄(모두 내림 장면이 아니거나 잠금이면 null), [parkAllowed] = 주차·화면 조작 허용(잠금 아님),
     * [children] = 계약의 아이 목록(손님 이동 판단, [StickerFly.guestOnly]. 모르면 null).
     */
    fun bind(line: SeatBoardRules.StickerLine?, phase: AutoPhase, parkAllowed: Boolean, night: Boolean, week: List<BoardCell>,
             children: List<ChildTag>? = null) {
        if (StickerFly.rearm(phase)) StickerFlyStore.seen = false
        last = Args(line, phase, parkAllowed, night, week, children)
        val just = StickerFly.justGranted(line, contractJust)
        val guest = StickerFly.guestOnly(children)
        val s = "$line|$just|$guest|$parkAllowed|$night|$week|${context.resources.configuration.fontScale}"
        if (s == sig) return
        sig = s
        this.night = night
        finish()
        box.removeAllViews()
        board = null
        gotLine = null
        if (line == null) { wantStart = false; return }
        val boardOn = StickerFly.showBoard(line, guest) && week.isNotEmpty()
        val target = StickerFly.targetIndex(week, boardOn)
        // 보임·애니메이션 켜짐은 배치가 끝난 다음 프레임([start])에 다시 본다(이 시점엔 무대가 아직 숨김일 수 있다)
        val play = StickerFly.decide(just, seen = StickerFlyStore.seen && !wantStart, parkAllowed = parkAllowed,
            shown = true, animatorsEnabled = true, hasTarget = target >= 0)
        // 움직이든 결과만 보이든 이 지급은 본 것(이동당 1회). 판을 그려야 하는데 이번 주 칸이 아직 안 왔으면 기다린다
        if (StickerFly.markSeen(just, StickerFly.showBoard(line, guest), week.isNotEmpty())) StickerFlyStore.seen = true
        wantStart = play == StickerFly.Play.PLAY
        if (boardOn) {
            val b = StickerBoardView(context)
            b.bind(week, todayPending = wantStart)   // 재생할 거면 채워진 칸이 먼저 보이지 않게
            board = b
            box.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        // 줄: 실제로 판에 찍힌 경우만 "별이 생겼어요", 손님 이동은 없음([StickerFly.lineText])
        val lineText = StickerFly.lineText(line, just, guest, todayRecorded = target >= 0)
        if (lineText != null) {
            val got = TextView(context).apply {
                text = lineText; textSize = DuoTokens.CAPTION_SP; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Palette.argb(V6Tokens.brand(night)))
                // 재생할 거면 별이 칸에 들어간 뒤에 보인다(그 전엔 판의 오늘 칸이 비어 보여 글과 어긋난다). 자리는 차지해 배치가 흔들리지 않게
                if (wantStart) visibility = View.INVISIBLE
            }
            gotLine = got
            box.addView(got, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (wantStart) box.post { if (wantStart) start() }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun visibleNow(): Boolean = box.isAttachedToWindow && box.isShown && box.windowVisibility == View.VISIBLE

    private fun start() {
        wantStart = false
        val b = board
        val play = b != null && StickerFly.canMove(parkAllowed = true, shown = visibleNow(),
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(), hasTarget = b.todayCell != null && b.todayGlyph != null)
        if (!play) { finish(); return }
        ensureLayer()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = StickerFly.TOTAL_MS
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { va ->
            if (anim !== va) return@addUpdateListener
            if (!visibleNow()) finish() else apply(StickerFly.frameAt((va.animatedFraction * StickerFly.TOTAL_MS).toLong()))
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (anim === animation) finish() }
        })
        anim = a
        apply(StickerFly.frameAt(0L))
        a.start()
    }

    private fun ensureLayer(): StickerFlyLayer {
        val l = layer ?: StickerFlyLayer(context).also { layer = it; card.overlay.add(it) }
        l.colors(night)
        if (l.width != card.width || l.height != card.height) l.layout(0, 0, card.width, card.height)
        return l
    }

    /** 한 프레임을 그린다. 자리는 그때그때 실제 배치에서 읽는다(배치가 바뀌어도 어긋나지 않게). 아직 배치 전이면 별을 그리지 않는다 */
    private fun apply(f: StickerFly.Frame) {
        val b = board ?: return
        val cell = b.todayCell ?: return
        val glyph = b.todayGlyph ?: return
        val l = ensureLayer()
        b.setTodayPending(!f.landed)
        gotLine?.visibility = if (StickerFly.showGotLine(f)) View.VISIBLE else View.INVISIBLE
        cell.scaleX = f.cellScale; cell.scaleY = f.cellScale
        if (card.width <= 0 || cell.width <= 0 || glyph.height <= 0) { l.clear(); return }
        tmp.set(0, 0, glyph.width, glyph.height)
        card.offsetDescendantRectToMyCoords(glyph, tmp)
        val end = StickerFly.Pt(tmp.exactCenterX(), tmp.exactCenterY())
        val endSize = minOf(glyph.height.toFloat(), cell.width * 0.7f)
        val starPx = StickerFly.STAR_DP * d
        val pad = StickerFly.EDGE_PAD_DP * d
        val blankView = blank()?.takeIf { it.isShown && StickerFly.fits(it.width.toFloat(), it.height.toFloat(), starPx, pad) }
        val from: View = if (StickerFly.startAt(blankView != null) == StickerFly.StartAt.BLANK && blankView != null) blankView else cell
        tmp.set(0, 0, from.width, from.height)
        card.offsetDescendantRectToMyCoords(from, tmp)
        val start = StickerFly.startCenter(card.width.toFloat(), card.height.toFloat(),
            tmp.left.toFloat(), tmp.top.toFloat(), tmp.right.toFloat(), tmp.bottom.toFloat(), starPx, pad)
        val p = StickerFly.pointAt(start, end, f.travel, StickerFly.ARC_LIFT_DP * d)
        l.star(p.x, p.y, StickerFly.sizeAt(starPx, endSize, f), f.starAlpha)
        tmp.set(0, 0, cell.width, cell.height)
        card.offsetDescendantRectToMyCoords(cell, tmp)
        val haloAlpha = if (f.step == StickerFly.Step.LAND) 1f - f.halo else 0f
        l.halo(tmp.left.toFloat(), tmp.top.toFloat(), tmp.right.toFloat(), tmp.bottom.toFloat(),
            f.halo * StickerFly.HALO_GROW_DP * d, V6Panels.STAMP_RADIUS_DP * d, haloAlpha)
        l.invalidate()
    }

    /** 결과 모습으로 끝낸다(끝·취소·가려짐·다시 만들기 공통). 여러 번 불러도 된다 */
    fun finish() {
        val a = anim
        anim = null
        a?.cancel()
        gotLine?.visibility = View.VISIBLE
        board?.let { b ->
            b.setTodayPending(false)
            b.todayCell?.let { it.scaleX = 1f; it.scaleY = 1f }
        }
        layer?.let { card.overlay.remove(it) }
        layer = null
    }

    /**
     * debug 미리보기 캡처용: 도는 연출을 멈추고 [ms] 시점 그림에 세워 둔다(시작·중간·끝 장면을 정확히 찍기 위해).
     * 배치가 끝난 뒤 불러야 한다. 실제 앱 흐름에서는 부르지 않는다.
     */
    fun freezeAt(ms: Long) {
        wantStart = false
        val a = anim
        anim = null
        a?.cancel()
        val b = board ?: return
        if (b.todayCell == null) return
        StickerFlyStore.seen = true
        val f = StickerFly.frameAt(ms)
        if (f.step == StickerFly.Step.DONE) { finish(); return }
        apply(f)
    }
}
