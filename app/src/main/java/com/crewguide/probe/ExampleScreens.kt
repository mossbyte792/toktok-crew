package com.crewguide.probe

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 예시 화면(P1·P2, 미구현·미검증) 접이식 영역. 정적 예시 텍스트만 있다.
 * 녹음·저장·불러오기·구매·네트워크 기능은 없다. 접힘 상태는 메모리에만 둔다.
 * 유효한 P가 아니면(visible=false) 영역 전체를 숨기고 접힌 상태로 되돌린다.
 */
class ExampleScreens(
    context: Context,
    private val dp: (Int) -> Int,
    textSp: Float,
    titleSp: Float,
    minTouchPx: Int,
    private val textColor: Int,
    private val styleButton: (Button) -> Unit,
    private val onListen: () -> Unit,
) {
    companion object {
        const val SECTION_TITLE = "예시 화면(가안, 아직 동작하지 않아요)"
        const val SPEAK_BTN_ON = "예시 안내 듣기"
        const val SPEAK_BTN_OFF = "예시 안내 듣기 (지금은 사용할 수 없어요)"
    }

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val title: TextView
    private val listenBtn: Button
    private var open = false

    // 굵기(bold)는 쓰지 않는다. 강조는 크기(titleSp)와 색(color)으로 한다.
    private fun tv(context: Context, t: String, sp: Float, color: Int = textColor) = TextView(context).apply {
        text = t; textSize = sp; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }

    private fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Palette.argb(Palette.current.cardBg)); setStroke(dp(1), Palette.argb(Palette.current.cardStroke)); cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) }
    }

    init {
        title = TextView(context).apply {
            textSize = titleSp; setTextColor(textColor); setPadding(0, dp(8), 0, dp(8))
            minimumHeight = minTouchPx; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { setOpen(!open) }
        }
        root.addView(title)

        val a = card(context)
        a.addView(tv(context, "카드 A · 정차 예시 (가안)", titleSp))
        a.addView(tv(context, "차가 주차(P) 상태일 때, 보호자가 출발 전에 보는 화면의 예시예요. 아래 문장은 앱에 내장한 예시 텍스트이며, 실제 차량 상태나 여행 정보에서 가져온 것이 아니에요.", textSp))
        a.addView(tv(context, "• 예시 문장 1: “안내자가 벨트를 확인해 달라고 말해요”", textSp))
        a.addView(tv(context, "• 예시 문장 2: “보호자님, 준비가 되면 알려 주세요”", textSp))
        a.addView(tv(context, "• 예시 문장 3: “오늘도 천천히 준비해요”", textSp))
        a.addView(tv(context, "※ 모두 가안이고 아직 동작하지 않아요", textSp))
        body.addView(a)

        val b = card(context)
        b.addView(tv(context, "카드 B · 가족 음성 팩 예시 (자리표시, 가안)", titleSp))
        b.addView(tv(context, "보호자가 직접 정한 가족 음성으로 안내를 바꿀 수 있다면 어떨까, 하는 아이디어를 보여 주는 화면이에요.", textSp))
        b.addView(tv(context, "예시예요. 보호자 녹음은 받지 않아요", titleSp, Palette.argb(Palette.current.warnText)))
        b.addView(tv(context, "• 아이의 실명·사진은 입력받는 기능이 없어요", textSp))
        b.addView(tv(context, "• 가족 음성 파일의 취급(저장 위치, 외부 전송, 동의 철회)은 아직 결정 전이에요", textSp))
        b.addView(tv(context, "• 아래 버튼은 지금 쓰는 기본 안내 음성으로 예시 한 줄만 들려줘요(소리는 아직 확인 전이에요)", textSp))
        listenBtn = Button(context).apply {
            styleButton(this)
            text = SPEAK_BTN_OFF; isEnabled = false
            setOnClickListener { onListen() }
        }
        b.addView(listenBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(4)) })
        body.addView(b)
        root.addView(body)
        setOpen(false)
    }

    private fun setOpen(v: Boolean) {
        open = v
        body.visibility = if (v) View.VISIBLE else View.GONE
        title.text = (if (v) "▼ " else "▶ ") + SECTION_TITLE
    }

    /**
     * 메인 스레드. visible=유효한 P(잠금 아님). listenEnabled=TTS 준비 && 유효한 P && 보호자 확인 IDLE.
     * 숨겨지면 접힌 상태로 되돌린다.
     */
    fun update(visible: Boolean, listenEnabled: Boolean) {
        if (!visible) {
            if (open) setOpen(false)
            root.visibility = View.GONE
            listenBtn.isEnabled = false
            return
        }
        root.visibility = View.VISIBLE
        listenBtn.isEnabled = listenEnabled
        listenBtn.text = if (listenEnabled) SPEAK_BTN_ON else SPEAK_BTN_OFF
    }
}
