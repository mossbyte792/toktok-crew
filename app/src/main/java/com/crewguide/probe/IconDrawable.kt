package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.Button

/**
 * 버튼 아이콘(체크·엑스·재생 삼각형)을 Path/Canvas로 직접 그린다. 외부 이미지·글꼴 기호 없음.
 * 크기는 호출자가 setBounds로 정한다(정사각 가정, 짧은 변 기준). 선 두께 = 한 변의 12%, 둥근 끝.
 */
class IconDrawable(
    private val kind: IconKind,
    colorInt: Int,
    /** 0이 아니면 CHECK/CROSS를 이 색(ARGB) 원 안에 그린다. 0이면 기존처럼 기호만(ChildCardView 배지 등) */
    private val circleArgb: Int = 0,
    /** 0이 아니면 원 둘레에 이 색 테두리(원 지름의 6%) */
    private val ringArgb: Int = 0,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val circlePath = Path()
    private val inCircle: Boolean get() = circleArgb != 0 && (kind == IconKind.CHECK || kind == IconKind.CROSS)

    init { paint.color = colorInt }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        if (side <= 0f || kind == IconKind.NONE) return
        val x0 = b.left + (b.width() - side) / 2f
        val y0 = b.top + (b.height() - side) / 2f
        if (inCircle) {
            // 원(Path): 채움, 그 위에 선택적 테두리. 기호는 아래에서 원 안쪽 좌표로 그린다
            val ring = if (ringArgb != 0) side * 0.06f else 0f
            circlePath.reset()
            circlePath.addCircle(x0 + side / 2f, y0 + side / 2f, side / 2f - ring / 2f, Path.Direction.CW)
            circlePaint.style = Paint.Style.FILL
            circlePaint.color = circleArgb
            circlePaint.alpha = paint.alpha
            canvas.drawPath(circlePath, circlePaint)
            if (ring > 0f) {
                circlePaint.style = Paint.Style.STROKE
                circlePaint.strokeWidth = ring
                circlePaint.color = ringArgb
                circlePaint.alpha = paint.alpha
                canvas.drawPath(circlePath, circlePaint)
            }
        }
        path.reset()
        when (kind) {
            IconKind.CHECK -> {
                paint.style = Paint.Style.STROKE
                if (inCircle) {
                    path.moveTo(x0 + side * 0.28f, y0 + side * 0.52f)
                    path.lineTo(x0 + side * 0.44f, y0 + side * 0.67f)
                    path.lineTo(x0 + side * 0.72f, y0 + side * 0.34f)
                } else {
                    path.moveTo(x0 + side * 0.14f, y0 + side * 0.54f)
                    path.lineTo(x0 + side * 0.40f, y0 + side * 0.78f)
                    path.lineTo(x0 + side * 0.86f, y0 + side * 0.24f)
                }
            }
            IconKind.CROSS -> {
                paint.style = Paint.Style.STROKE
                val lo = if (inCircle) 0.32f else 0.22f
                val hi = if (inCircle) 0.68f else 0.78f
                path.moveTo(x0 + side * lo, y0 + side * lo)
                path.lineTo(x0 + side * hi, y0 + side * hi)
                path.moveTo(x0 + side * hi, y0 + side * lo)
                path.lineTo(x0 + side * lo, y0 + side * hi)
            }
            IconKind.PLAY -> {
                paint.style = Paint.Style.FILL
                path.moveTo(x0 + side * 0.26f, y0 + side * 0.12f)
                path.lineTo(x0 + side * 0.26f, y0 + side * 0.88f)
                path.lineTo(x0 + side * 0.90f, y0 + side * 0.50f)
                path.close()
            }
            IconKind.GEAR -> {
                // 몸통 원(반지름 0.30) + 둥근 사각 이빨 8개(45도씩) 합집합에서 가운데 구멍(반지름 0.13)을 뺀다
                paint.style = Paint.Style.FILL
                val cx = x0 + side * 0.5f
                val cy = y0 + side * 0.5f
                path.addCircle(cx, cy, side * 0.30f, Path.Direction.CW)
                val toothPath = Path()
                val toothMatrix = Matrix()
                for (i in 0 until 8) {
                    toothPath.reset()
                    toothPath.addRoundRect(
                        RectF(cx - side * 0.08f, cy - side * 0.46f, cx + side * 0.08f, cy - side * 0.24f),
                        side * 0.04f, side * 0.04f, Path.Direction.CW,
                    )
                    toothMatrix.setRotate(i * 45f, cx, cy)
                    toothPath.transform(toothMatrix)
                    path.op(toothPath, Path.Op.UNION)
                }
                val holePath = Path()
                holePath.addCircle(cx, cy, side * 0.13f, Path.Direction.CW)
                path.op(holePath, Path.Op.DIFFERENCE)
            }
            IconKind.NONE -> return
        }
        paint.strokeWidth = side * StageButtons.STROKE_PERCENT / 100f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; circlePaint.alpha = alpha; invalidateSelf() }

    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; circlePaint.colorFilter = colorFilter; invalidateSelf() }

    @Deprecated("Drawable.getOpacity is deprecated in the framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 왼쪽 아이콘 + 글자를 가운데로 묶어 그리는 알약 버튼(U-2b).
 * - [setPill]로 바탕색·가장자리 색·가장자리 두께(px)·윤곽을 받으면 본체 알약(높이 = 전체 − 가장자리)과
 *   그 아래 같은 모양의 가장자리를 직접 그린다. 전체 높이(터치 영역)는 그대로다.
 * - 눌리면 본체와 글자·아이콘이 가장자리 두께만큼 아래로 내려가고 보이는 가장자리는 그만큼 줄어 0이 된다.
 *   가장자리가 없는 버튼(QUIET 등)만 이전처럼 눌린 동안 알파 0.85를 쓴다.
 * - 글자·아이콘 묶음은 본체 가운데(아래 여백 = 가장자리 두께로 맞춘다. 호출자가 paddingBottom을 edgePx로 준다).
 * (TextView의 왼쪽 compound drawable은 가장자리에 붙으므로, 그릴 때 묶음 너비만큼 가운데로 옮긴다)
 */
open class IconButton(context: Context) : Button(context) {
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRect = RectF()
    private var pillFillArgb = 0        // 0이면 채우지 않음(투명)
    private var pillEdgeArgb = 0
    private var pillEdgePx = 0
    private var pillOutlinePx = 0
    private var pillOutlineArgb = 0
    private var pillOn = false
    /** v3 단계 2: 모서리 반지름(px). 음수 = 알약(본체 높이의 절반, 무대 버튼 기본). DuoButton은 16dp */
    var cornerPx: Float = -1f
        set(v) { field = v; invalidate() }
    /** v3 단계 2: 윤곽을 점선으로(비활성 표시용) */
    var outlineDashed: Boolean = false
        set(v) { field = v; invalidate() }

    init { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    /** fillArgb 0 = 채움 없음. edgePx 0 = 가장자리 없음. outlinePx 0 = 윤곽 없음 */
    fun setPill(fillArgb: Int, edgeArgb: Int, edgePx: Int, outlinePx: Int, outlineArgb: Int) {
        pillFillArgb = fillArgb; pillEdgeArgb = edgeArgb; pillEdgePx = edgePx
        pillOutlinePx = outlinePx; pillOutlineArgb = outlineArgb
        pillOn = true
        background = null
        invalidate()
    }

    /** 지금 눌려 내려간 거리(px): 가장자리가 있고 눌린 활성 버튼이면 가장자리 두께, 아니면 0 */
    private fun pressShiftPx(): Int = if (pillEdgePx > 0 && isPressed && isEnabled) pillEdgePx else 0

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        // 가장자리가 있으면 알파 대신 눌림 이동을 쓴다. 없으면 기존 알파 0.85
        alpha = if (pressed && isEnabled && pillEdgePx == 0) StageButtons.PRESSED_ALPHA else 1f
        invalidate()
    }

    private fun drawPill(canvas: Canvas, shift: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val e = pillEdgePx.toFloat()
        val bodyH = h - e
        if (bodyH <= 0f) return
        val r = if (cornerPx >= 0f) minOf(cornerPx, bodyH / 2f) else StageUiTokens.pillRadius(bodyH)
        if (pillEdgePx > 0 && pillFillArgb != 0) {
            // 가장자리: 본체와 같은 모양을 e만큼 아래로 민 알약(전체 높이의 맨 아래에서 끝난다)
            pillPaint.style = Paint.Style.FILL
            pillPaint.color = pillEdgeArgb
            pillRect.set(0f, e, w, h)
            canvas.drawRoundRect(pillRect, r, r, pillPaint)
        }
        val top = shift.toFloat()
        if (pillFillArgb != 0) {
            pillPaint.style = Paint.Style.FILL
            pillPaint.color = pillFillArgb
            pillRect.set(0f, top, w, top + bodyH)
            canvas.drawRoundRect(pillRect, r, r, pillPaint)
        }
        if (pillOutlinePx > 0) {
            val half = pillOutlinePx / 2f
            pillPaint.style = Paint.Style.STROKE
            pillPaint.strokeWidth = pillOutlinePx.toFloat()
            pillPaint.color = pillOutlineArgb
            pillPaint.pathEffect = if (outlineDashed) android.graphics.DashPathEffect(floatArrayOf(pillOutlinePx * 3f, pillOutlinePx * 2f), 0f) else null
            pillRect.set(half, top + half, w - half, top + bodyH - half)
            canvas.drawRoundRect(pillRect, r - half, r - half, pillPaint)
            pillPaint.pathEffect = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val shiftY = pressShiftPx()
        if (pillOn) {
            // 뷰가 스크롤돼 있어도(gravity CENTER 등으로 TextView가 scrollX를 갖는 경우) 바탕은 뷰 영역에 그린다(2026-11-07)
            val saved0 = canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            drawPill(canvas, shiftY)
            canvas.restoreToCount(saved0)
        }
        val icon = compoundDrawablesRelative[0]
        val text0 = text?.toString() ?: ""
        var shiftX = 0f
        var iconDy = 0
        if (icon != null && text0.isEmpty()) {
            // 글자 없는 아이콘 버튼(설정 톱니): 뷰 패딩과 무관하게 본체(아래 띠 제외) 정중앙에 그린다.
            // TextView는 왼쪽 그림을 x = paddingLeft, y = 위 패딩 + (안쪽 높이 − 그림 높이)/2 에 그린다 → 그 자리와 정중앙의 차이만큼 옮긴다.
            // (2026-11-14: 홈 V6는 왼쪽 패딩으로 한 번, 아래 묶음 가운데 맞춤으로 한 번 더 밀려 8dp 오른쪽에 그려졌다)
            val drawnTop = compoundPaddingTop + (height - compoundPaddingTop - compoundPaddingBottom - icon.bounds.height()) / 2
            shiftX = (StageUiTokens.iconOnlyLeft(width, icon.bounds.width()) - paddingLeft).toFloat()
            iconDy = StageUiTokens.iconOnlyTop(height, pillEdgePx, icon.bounds.height()) - drawnTop
        } else if (icon != null) {
            val iconW = icon.bounds.width()
            val gap = compoundDrawablePadding
            val avail = width - paddingLeft - paddingRight - iconW - gap
            val textW = minOf(paint.measureText(text0), avail.toFloat().coerceAtLeast(0f))
            shiftX = ((avail - textW) / 2f).coerceAtLeast(0f)
        }
        val saved = canvas.save()
        canvas.translate(shiftX, (shiftY + iconDy).toFloat())
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
    }
}
