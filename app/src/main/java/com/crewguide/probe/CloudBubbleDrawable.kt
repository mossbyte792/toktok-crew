package com.crewguide.probe

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 구름 말풍선 바탕: [CloudShape]의 본체 + 돌출 동그라미를 합친 윤곽을 채우고 테두리를 두른다. 색은 0xAARRGGBB */
class CloudBubbleDrawable(
    private val fillArgb: Int,
    private val outlineArgb: Int,
    private val strokePx: Float,
    private val bumpRadiusPx: Float,
    private val tailPx: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillArgb }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = outlineArgb; strokeWidth = strokePx; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloud = Path()
    private val dots = ArrayList<CloudShape.Circle>()
    private val tmp = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        cloud.reset(); dots.clear()
        val w = bounds.width().toFloat() - strokePx
        val h = bounds.height().toFloat() - strokePx
        if (w <= 0f || h <= 0f) return
        val ox = bounds.left + strokePx / 2f
        val oy = bounds.top + strokePx / 2f
        val b = CloudShape.body(w, h, bumpRadiusPx, tailPx)
        cloud.addRoundRect(RectF(ox + b.left, oy + b.top, ox + b.right, oy + b.bottom), b.radius, b.radius, Path.Direction.CW)
        for (c in CloudShape.bumps(w, h, bumpRadiusPx, tailPx)) {
            tmp.reset(); tmp.addCircle(ox + c.x, oy + c.y, c.r, Path.Direction.CW)
            cloud.op(tmp, Path.Op.UNION)
        }
        for (c in CloudShape.tailDots(h, bumpRadiusPx, tailPx)) dots.add(CloudShape.Circle(ox + c.x, oy + c.y, c.r))
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(cloud, fillPaint)
        canvas.drawPath(cloud, strokePaint)
        for (d in dots) {
            dotPaint.style = Paint.Style.FILL; dotPaint.color = fillArgb
            canvas.drawCircle(d.x, d.y, d.r, dotPaint)
            dotPaint.style = Paint.Style.STROKE; dotPaint.color = outlineArgb; dotPaint.strokeWidth = strokePx
            canvas.drawCircle(d.x, d.y, d.r, dotPaint)
        }
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; strokePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter; strokePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
