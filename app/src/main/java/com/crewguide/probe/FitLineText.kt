package com.crewguide.probe

import android.content.Context
import android.util.TypedValue
import android.widget.TextView

/**
 * 2026-11-14 r5 결정 38: 하차 마무리 제목이 "아이가 모두 내린 것 같아요"로 길어져(Jua 약 10.1em), 1920×720 좌석 화면 안내 칸(폭 약 380dp)에서
 * 한 줄을 넘는다. 두 줄이 되면 안내 칸 세로가 모자라 FitColumn이 기준 글자 "(착석 신호 기준)"을 숨긴다(글꼴 2.0 캡처 여유 약 36px < 한 줄 약 47px).
 * 그래서 제목은 한 줄에 들어가도록 글자를 [FitLineRule.MIN_FRACTION]까지만 줄이고, 그래도 안 들어가면 원래 크기로 줄바꿈한다.
 * 짧은 제목(지금 들어가는 것)은 크기가 그대로다.
 */
object FitLineRule {
    /** 원래 크기의 몇 배까지 줄일 수 있나(그 아래로는 줄이지 않고 줄바꿈) */
    const val MIN_FRACTION = 0.75f

    /**
     * 한 줄에 들어갈 글자 크기(px). [textWidthAtBase] = 원래 크기([basePx])에서 잰 글 폭, [availPx] = 쓸 수 있는 폭.
     * 들어가면 [basePx] 그대로, 줄여서 들어가면 줄인 크기(0.5px 내림), 하한보다 작아야 하면 [basePx](줄바꿈).
     */
    fun sizePx(basePx: Float, textWidthAtBase: Float, availPx: Float): Float {
        if (availPx <= 0f || textWidthAtBase <= availPx) return basePx
        val need = basePx * (availPx / textWidthAtBase)
        if (need < basePx * MIN_FRACTION) return basePx
        return Math.floor(need * 2.0).toFloat() / 2f
    }
}

/** [FitLineRule]로 한 줄에 맞추는 제목 글자. 기준 크기는 [setBaseSize]로 정한다(글꼴 배율은 sp가 반영) */
class FitLineText(context: Context) : TextView(context) {
    private var basePx = 0f

    fun setBaseSize(sp: Float) {
        basePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, basePx)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (basePx > 0f && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight).toFloat()
            val p = android.text.TextPaint(paint).apply { textSize = basePx }
            val want = FitLineRule.sizePx(basePx, p.measureText(text.toString()), avail)
            // 같은 값이면 setTextSize가 다시 배치를 부르지 않는다(배치 반복 없음)
            if (want != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, want)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
