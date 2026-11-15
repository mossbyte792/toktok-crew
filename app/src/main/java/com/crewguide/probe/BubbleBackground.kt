package com.crewguide.probe

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 말풍선 배경(아이폰 문자 스타일, Path/Canvas로 직접 그림, 외부 이미지 없음, setShadowLayer/블러 없음).
 * - 몸체: 색이 꽉 찬 둥근 사각형, 반경 min(CORNER_DP 20dp, 높이/2). 아랫변은 직선. setHasTail(false)이면 꼬리 없이 몸체만(보호자·이전 풍선).
 * - 꼬리: 몸체의 한 모서리를 각지게 두고 거기서 작게 휘어 나오는 곡선 두 개(quadTo). RIGHT_OF_HEAD(TailDir.LEFT)는 아래 왼쪽,
 *   LEFT_OF_HEAD(TailDir.RIGHT)는 아래 오른쪽 모서리. CHEST_CENTER(TailDir.UP)는 위쪽 모서리 중 입(목표) 쪽에 가까운 쪽
 *   (아래 모서리는 캐릭터와 반대편이라 방향이 맞지 않아 윗쪽 모서리를 택함).
 * - 몸체와 꼬리는 하나의 닫힌 Path. 외곽선은 StageBubble.OUTLINE_ENABLED가 true일 때만 같은 Path를 한 번에 그린다(기본 끔).
 * - 그림자: 같은 Path를 아래로 SHADOW_DY_DP 옮긴 검정 알파 SHADOW_ALPHA 채움을 먼저 그린다.
 * 뷰는 꼬리 쪽에 TAIL_EXTENT_DP, 아래쪽에 BOTTOM_RESERVE_DP만큼 안쪽 여백을 비워 둬야 한다(StageScreen.applyBubble). 꼬리 없는 풍선은 꼬리 여백이 필요 없다.
 * 색은 만들 때 받은 값으로 고정. 꼬리 방향·목표만 setTail로 바뀐다(뷰 안의 좌표, px).
 * 롤백: 이 파일과 bubbleColorsOf를 이전 판으로 되돌리면 흰 채움 + 3dp 외곽선 + 물방울 꼬리가 된다.
 */
class BubbleBackground(private val colors: BubbleColors, private val density: Float) : Drawable() {
    private enum class Corner { TL, TR, BR, BL }

    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.argb(colors.fillRgb)
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = StageBubble.OUTLINE_DP_THIN * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Palette.argb(colors.outlineRgb)
    }
    private val bubbleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF shl 24   // 검정, 알파는 draw에서 shadowAlpha로 지정
    }
    private val bubblePath = Path()
    private val arcBox = RectF()
    private var tailDir = TailDir.UP
    private var targetX = Float.NaN   // NaN이면 목표 없음
    private var targetY = Float.NaN
    private var shadowAlpha = StageBubble.SHADOW_ALPHA

    private var tailOn = true

    /** 꼬리를 그릴지(기본 true). false이면 꼬리 여백 없이 둥근 사각형 몸체만 그린다. 같은 값이면 아무것도 하지 않는다 */
    fun setHasTail(value: Boolean) {
        if (value == tailOn) return
        tailOn = value
        rebuild(bounds)
        invalidateSelf()
    }

    /** 꼬리 방향과 목표(입) 위치(이 Drawable이 붙은 뷰 안의 px 좌표). 같은 값이면 아무것도 하지 않는다 */
    fun setTail(dir: TailDir, tx: Float, ty: Float) {
        if (dir == tailDir && tx == targetX && ty == targetY) return
        tailDir = dir; targetX = tx; targetY = ty
        rebuild(bounds)
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild(bounds)
    }

    /**
     * 꼬리 점 10개(px): p0, c1, tip, c2, e의 (x,y). 모서리 C 기준, u = 꼬리가 나가는 바깥 단위 방향, v = 꼬리가 올라가는 변의 단위 방향(몸체 안쪽으로).
     * p0 = C − 4u, c1 = C + 0.25W·u, tip = C + W·u + 1·v, c2 = C + 0.3H·v, e = C + H·v. 아래 왼쪽 모서리면 u=(−1,0), v=(0,−1).
     */
    private fun tailPts(cx: Float, cy: Float, ux: Float, uy: Float, vx: Float, vy: Float): FloatArray {
        val d = density
        val w = StageBubble.TAIL_W_DP * d
        val h = StageBubble.TAIL_H_DP * d
        val base = StageBubble.TAIL_BASE_DP * d
        return floatArrayOf(
            cx - base * ux, cy - base * uy,
            cx + 0.25f * w * ux, cy + 0.25f * w * uy,
            cx + w * ux + d * vx, cy + w * uy + d * vy,
            cx + 0.3f * h * vx, cy + 0.3f * h * vy,
            cx + h * vx, cy + h * vy,
        )
    }

    /** 경로 진행 방향(시계)으로 p0→tip→e 순서로 낸다 */
    private fun tailForward(p: FloatArray) {
        bubblePath.lineTo(p[0], p[1])
        bubblePath.quadTo(p[2], p[3], p[4], p[5])
        bubblePath.quadTo(p[6], p[7], p[8], p[9])
    }

    /** 경로 진행 방향(시계)이 e→tip→p0 순서인 모서리용. 현재 점은 이미 e 근처로 와 있어야 한다 */
    private fun tailBackward(p: FloatArray) {
        bubblePath.lineTo(p[8], p[9])
        bubblePath.quadTo(p[6], p[7], p[4], p[5])
        bubblePath.quadTo(p[2], p[3], p[0], p[1])
    }

    private fun rebuild(bnd: Rect) {
        bubblePath.reset()
        if (bnd.isEmpty) return
        val d = density
        val inset = if (StageBubble.OUTLINE_ENABLED) StageBubble.OUTLINE_DP_THIN * d / 2f else 0f   // 외곽선 절반이 잘리지 않게
        val ext = if (tailOn) StageBubble.TAIL_EXTENT_DP * d else 0f
        val l = bnd.left + inset + (if (tailDir == TailDir.LEFT) ext else 0f)
        val r = bnd.right - inset - (if (tailDir == TailDir.RIGHT) ext else 0f)
        val t = bnd.top + inset + (if (tailDir == TailDir.UP) ext else 0f)
        val bot = bnd.bottom - inset - StageBubble.BOTTOM_RESERVE_DP * d
        if (r - l <= 0f || bot - t <= 0f) return
        val tailH = StageBubble.TAIL_H_DP * d
        // 몸체 반경 = min(20dp, 높이/2) (이전 알약 규칙 cornerRadiusPx는 쓰지 않음)
        val bodyRad = min(StageBubble.bodyRadiusPx(bot - t, d), (r - l) / 2f)
        if (!tailOn) {
            arcBox.set(l, t, r, bot)
            bubblePath.addRoundRect(arcBox, bodyRad, bodyRad, Path.Direction.CW)
            return
        }
        val rad = min(bodyRad, (bot - t - tailH).coerceAtLeast(0f))
        val corner = when (tailDir) {
            TailDir.LEFT -> Corner.BL
            TailDir.RIGHT -> Corner.BR
            TailDir.UP -> if (!targetX.isNaN() && targetX >= (l + r) / 2f) Corner.TR else Corner.TL
        }
        val dia = rad * 2f
        // 꼬리 점: 모서리마다 (C, u, v)
        val tp = when (corner) {
            Corner.BL -> tailPts(l, bot, -1f, 0f, 0f, -1f)
            Corner.BR -> tailPts(r, bot, 1f, 0f, 0f, -1f)
            Corner.TL -> tailPts(l, t, 0f, -1f, 1f, 0f)
            Corner.TR -> tailPts(r, t, 0f, -1f, -1f, 0f)
        }

        // 시작점과 윗변
        if (corner == Corner.TL) bubblePath.moveTo(tp[8], tp[9]) else bubblePath.moveTo(l + rad, t)
        if (corner == Corner.TR) {
            tailBackward(tp)      // 윗변 → e(r−14, t) → 끝 → p0(r, t+4)
        } else {
            bubblePath.lineTo(r - rad, t)
            if (rad > 0f) { arcBox.set(r - dia, t, r, t + dia); bubblePath.arcTo(arcBox, 270f, 90f) }
        }
        // 오른쪽 변
        if (corner == Corner.BR) {
            bubblePath.lineTo(r, bot - tailH)
            tailBackward(tp)      // e(r, bot−14) → 끝 → p0(r−4, bot)
        } else {
            bubblePath.lineTo(r, bot - rad)
            if (rad > 0f) { arcBox.set(r - dia, bot - dia, r, bot); bubblePath.arcTo(arcBox, 0f, 90f) }
        }
        // 아랫변(직선)
        if (corner == Corner.BL) {
            tailForward(tp)       // p0(l+4, bot) → 끝 → e(l, bot−14)
        } else {
            bubblePath.lineTo(l + rad, bot)
            if (rad > 0f) { arcBox.set(l, bot - dia, l + dia, bot); bubblePath.arcTo(arcBox, 90f, 90f) }
        }
        // 왼쪽 변
        if (corner == Corner.TL) {
            tailForward(tp)       // p0(l, t+4) → 끝 → e(l+14, t) = 시작점
        } else {
            bubblePath.lineTo(l, t + rad)
            if (rad > 0f) { arcBox.set(l, t, l + dia, t + dia); bubblePath.arcTo(arcBox, 180f, 90f) }
        }
        bubblePath.close()
    }

    override fun draw(canvas: Canvas) {
        bubbleShadowPaint.alpha = shadowAlpha
        canvas.save()
        canvas.translate(0f, StageBubble.SHADOW_DY_DP * density)
        canvas.drawPath(bubblePath, bubbleShadowPaint)
        canvas.restore()
        canvas.drawPath(bubblePath, bubbleFillPaint)
        if (StageBubble.OUTLINE_ENABLED) canvas.drawPath(bubblePath, bubbleStrokePaint)
    }

    override fun setAlpha(alpha: Int) {
        bubbleFillPaint.alpha = alpha
        bubbleStrokePaint.alpha = alpha
        shadowAlpha = StageBubble.SHADOW_ALPHA * alpha / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bubbleFillPaint.colorFilter = colorFilter
        bubbleStrokePaint.colorFilter = colorFilter
        bubbleShadowPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable 규약상 구현 필요")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
