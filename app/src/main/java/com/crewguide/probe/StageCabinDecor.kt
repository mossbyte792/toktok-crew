package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** 기내 창문 한 개의 위치(화면 비율 0~1, 무대 뷰 기준) */
data class CabinWindowFrac(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** 창문 색(0xRRGGBB). 알파는 frameAlpha/skyAlpha로 따로 둔다 */
data class CabinDecorColors(
    val frame: Int,
    val skyTop: Int,
    val skyBottom: Int,
    val cloud: Int,
    val frameAlpha: Int,
    val skyAlpha: Int,
)

/**
 * 2차 U1: 무대 배경 위에 그리는 은은한 '기내 창문' 장식(순수 로직, Android 클래스 없음).
 * - 좌·우 가장자리 세로 가운데쯤에 둥근 창문 2개만 둔다(좌상단 시연 버튼·우상단 좌석 지도·하단 도크와 겹치지 않는 구간).
 * - 장식일 뿐 터치·접근성 대상이 아니며 글자를 쓰지 않는다. 주행 잠금(LOCKED)에서는 더 흐리게 정지 표시한다.
 */
object StageCabinDecor {
    const val WIN_W_FRAC = 0.075f      // 창문 폭 = 무대 폭의 7.5%
    const val WIN_TOP_FRAC = 0.40f     // 창문 위 끝(무대 높이 비율)
    const val WIN_BOTTOM_FRAC = 0.66f  // 창문 아래 끝
    const val SIDE_MARGIN_FRAC = 0.03f // 화면 옆 가장자리에서 떨어진 정도
    const val FRAME_DP = 6             // 창틀 두께
    const val LOCKED_ALPHA_SCALE = 0.5f

    /** 좌·우 창문 위치. 좌우 대칭이며 폭·높이는 양수다 */
    fun windows(): List<CabinWindowFrac> {
        val l = CabinWindowFrac(SIDE_MARGIN_FRAC, WIN_TOP_FRAC, SIDE_MARGIN_FRAC + WIN_W_FRAC, WIN_BOTTOM_FRAC)
        val r = CabinWindowFrac(1f - SIDE_MARGIN_FRAC - WIN_W_FRAC, WIN_TOP_FRAC, 1f - SIDE_MARGIN_FRAC, WIN_BOTTOM_FRAC)
        return listOf(l, r)
    }

    /** 주간: 흰 창틀 + 하늘 그라데이션 + 흰 구름 / 야간: 짙은 남색 하늘 + 옅은 구름(별 대신 차분한 톤) */
    fun colors(night: Boolean): CabinDecorColors =
        if (night) CabinDecorColors(frame = 0x8796AA, skyTop = 0x0B1324, skyBottom = 0x334155, cloud = 0x94A3B8, frameAlpha = 0x55, skyAlpha = 0xB0)
        else CabinDecorColors(frame = 0xFFFFFF, skyTop = 0x56A8F5, skyBottom = 0xE6F4FE, cloud = 0xFFFFFF, frameAlpha = 0xC0, skyAlpha = 0xC8)

    /**
     * v3 반복 6(P1-3): 2영역 좌석 장면에서는 왼쪽 '고르기' 영역(0 ~ zoneRightFrac)과 겹치는 창문을 그리지 않는다.
     * 창문이 지도·범례 뒤로 비쳐 지도 왼쪽 끝과 겹쳐 보이던 것. null이면 모든 창문(기존과 같음).
     */
    fun visibleWindows(zoneRightFrac: Float?): List<CabinWindowFrac> =
        if (zoneRightFrac == null) windows() else windows().filter { it.left >= zoneRightFrac }

    /** 잠금 장면이면 알파를 절반으로 낮춘다(정지 표시) */
    fun alphaFor(base: Int, locked: Boolean): Int =
        if (locked) (base * LOCKED_ALPHA_SCALE).toInt().coerceIn(0, 255) else base.coerceIn(0, 255)
}

/** StageCabinDecor 값을 그대로 그리는 장식 뷰(터치를 받지 않음, 상태 저장 없음) */
class StageCabinDecorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cloudRect = RectF()
    private var night = Palette.current.night
    private var locked = false
    private var zoneRightFrac: Float? = null

    /** 2영역 고르기 영역 오른쪽 끝(창 폭 비율). null = 가리지 않음. 값이 바뀔 때만 다시 그린다 */
    fun setPickZone(frac: Float?) {
        if (frac == zoneRightFrac) return
        zoneRightFrac = frac
        invalidate()
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 야간·잠금 여부가 바뀔 때만 다시 그린다 */
    fun bind(isNight: Boolean, isLocked: Boolean) {
        if (isNight == night && isLocked == locked) return
        night = isNight; locked = isLocked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val c = StageCabinDecor.colors(night)
        val frameA = StageCabinDecor.alphaFor(c.frameAlpha, locked)
        val skyA = StageCabinDecor.alphaFor(c.skyAlpha, locked)
        val cloudA = StageCabinDecor.alphaFor(if (night) 0x40 else 0xD0, locked)
        for (win in StageCabinDecor.visibleWindows(zoneRightFrac)) {
            rect.set(win.left * w, win.top * h, win.right * w, win.bottom * h)
            val radius = rect.width() / 2f
            skyPaint.style = Paint.Style.FILL
            skyPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                (skyA shl 24) or c.skyTop, (skyA shl 24) or c.skyBottom, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, skyPaint)
            // 구름 두 덩이(창문 안쪽 아래)
            canvas.save()
            canvas.clipRect(rect)
            cloudPaint.style = Paint.Style.FILL
            cloudPaint.color = (cloudA shl 24) or c.cloud
            val cw = rect.width()
            cloudRect.set(rect.left - cw * 0.15f, rect.bottom - rect.height() * 0.30f, rect.left + cw * 0.65f, rect.bottom - rect.height() * 0.17f)
            canvas.drawOval(cloudRect, cloudPaint)
            cloudRect.set(rect.left + cw * 0.35f, rect.bottom - rect.height() * 0.22f, rect.right + cw * 0.15f, rect.bottom - rect.height() * 0.10f)
            canvas.drawOval(cloudRect, cloudPaint)
            canvas.restore()
            // 창틀
            framePaint.style = Paint.Style.STROKE
            framePaint.strokeWidth = StageCabinDecor.FRAME_DP * density
            framePaint.color = (frameA shl 24) or c.frame
            canvas.drawRoundRect(rect, radius, radius, framePaint)
        }
    }
}
