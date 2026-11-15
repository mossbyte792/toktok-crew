package com.crewguide.probe

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 시연 단계 안내(가안) 접이식 카드. 정적 안내만 있고 상태 연동·저장·음성은 없다.
 * 펼침 상태는 메모리에만 둔다. 유효한 P가 아니면 접고 숨긴다(예시 카드와 같은 규칙).
 */
class DemoStepsCard(
    context: Context,
    private val dp: (Int) -> Int,
    textSp: Float,
    auxSp: Float,
    titleSp: Float,
    minTouchPx: Int,
    private val textColor: Int,
) {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val title: TextView
    private var open = false

    // 굵기(bold)는 쓰지 않는다.
    private fun tv(context: Context, t: String, sp: Float) = TextView(context).apply {
        text = t; textSize = sp; setTextColor(textColor); setPadding(0, dp(4), 0, dp(4))
    }

    init {
        title = TextView(context).apply {
            textSize = titleSp; setTextColor(textColor); setPadding(0, dp(8), 0, dp(8))
            minimumHeight = maxOf(minTouchPx, dp(76)); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { setOpen(!open) }
        }
        root.addView(title)
        for (s in DemoSteps.steps) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Palette.argb(Palette.current.cardBg)); setStroke(dp(1), Palette.argb(Palette.current.cardStroke)); cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
            }
            card.addView(tv(context, "${s.number}. ${s.title}", titleSp))
            card.addView(tv(context, s.description, textSp))
            card.addView(tv(context, s.label, auxSp))
            body.addView(card)
        }
        body.addView(tv(context, DemoSteps.CAUTION, auxSp))
        body.addView(tv(context, AppName.footerLine, auxSp))
        root.addView(body)
        setOpen(false)
    }

    private fun setOpen(v: Boolean) {
        open = v
        body.visibility = if (v) View.VISIBLE else View.GONE
        title.text = (if (v) "▼ " else "▶ ") + DemoSteps.SECTION_TITLE
    }

    /** 메인 스레드. visible=유효한 P(잠금 아님). 숨겨지면 접힌 상태로 되돌린다. */
    fun update(visible: Boolean) {
        if (!visible) {
            if (open) setOpen(false)
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
    }
}
