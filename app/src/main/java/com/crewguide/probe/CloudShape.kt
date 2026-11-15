package com.crewguide.probe

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * 구름 말풍선 기하(순수 로직, Android 클래스 없음). 본체 둥근 사각형 가장자리 위에 같은 반지름의 동그라미를 줄지어 놓고
 * 합치면 구름 윤곽이 된다. 모든 동그라미는 주어진 영역(0..w, 0..h) 안에 들어간다.
 * 왼쪽 [tailPx]는 머리 쪽으로 작아지는 생각 방울(꼬리) 자리다.
 */
object CloudShape {
    data class Circle(val x: Float, val y: Float, val r: Float)
    /** 본체 사각형(둥근 모서리 반지름 = 돌출 반지름) */
    data class Body(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)

    /** 돌출 동그라미 간격 = 반지름 × 이 값 (작을수록 촘촘) */
    const val SPACING = 1.75f

    fun body(w: Float, h: Float, rb: Float, tailPx: Float): Body = Body(tailPx + rb, rb, w - rb, h - rb, rb)

    /** 가장자리 돌출 동그라미: 위·아래·왼·오른쪽 변을 따라 같은 간격으로 */
    fun bumps(w: Float, h: Float, rb: Float, tailPx: Float): List<Circle> {
        val b = body(w, h, rb, tailPx)
        val out = ArrayList<Circle>()
        fun along(x0: Float, y0: Float, x1: Float, y1: Float) {
            val len = max(Math.abs(x1 - x0), Math.abs(y1 - y0))
            val n = max(2, ceil(len / (rb * SPACING)).toInt())
            for (i in 0..n) {   // 양 끝(모서리) 포함
                val t = i.toFloat() / n
                out.add(Circle(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, rb))
            }
        }
        along(b.left, b.top, b.right, b.top)
        along(b.left, b.bottom, b.right, b.bottom)
        along(b.left, b.top, b.left, b.bottom)
        along(b.right, b.top, b.right, b.bottom)
        return out
    }

    /** 꼬리 생각 방울 3개: 본체 왼쪽 아래에서 머리 쪽(왼쪽 아래)으로 작아진다. 영역 안에 들어간다 */
    fun tailDots(h: Float, rb: Float, tailPx: Float): List<Circle> {
        val r1 = rb * 0.4f; val r2 = rb * 0.28f; val r3 = rb * 0.18f
        return listOf(
            Circle(tailPx * 0.78f, h * 0.70f, r1),
            Circle(tailPx * 0.45f, h * 0.80f, r2),
            Circle(tailPx * 0.18f + r3, h * 0.88f, r3),
        )
    }

    /** 사각 영역 안에서 글자를 놓을 수 있는 안쪽 여백의 최소값(px): 돌출 반지름 + 여유 */
    fun textInsetPx(rb: Float, extra: Float): Float = rb + extra
}

/** 숨쉬기: 위아래로 아주 조금 움직이는 단위 값 -1..1 (사인, 주기 [CharacterMotion.BREATH_PERIOD_MS]) */
fun breathUnit(tMs: Long): Float {
    val t = if (tMs < 0L) 0L else tMs
    val phase = (t % CharacterMotion.BREATH_PERIOD_MS).toDouble() / CharacterMotion.BREATH_PERIOD_MS
    return sin(2.0 * PI * phase).toFloat()
}
