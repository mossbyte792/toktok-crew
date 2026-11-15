package com.crewguide.probe

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 2026-11-12 PHASE B (S03 보호자 확인·S08 하차): 장면의 '보호자가 할 일'을 큰 카드로 맨 앞에 보인다.
 * 말풍선(캐릭터가 말한 문장)은 그대로 두고, 카드는 행동 안내만 한다. 표시 전용이며 상태를 바꾸지 않는다.
 * 문구 규칙: 안전 보장·단정 표현 금지. [예]는 보호자 보고라는 의미를 유지한다.
 */
data class Headline(val kicker: String, val title: String, val note: String)

object StageHeadline {
    const val QUESTION_KICKER = "보호자 확인"
    const val QUESTION_TITLE = "직접 확인했으면 [예]\n아직이면 [아니오]"
    const val QUESTION_NOTE = "[예]는 보호자 보고예요. 앱은 벨트 착용을 확인하지 않아요."
    const val EXIT_KICKER = "내리기 전에"
    const val EXIT_TITLE = "뒷좌석을\n한 번 더 확인해 주세요"
    const val EXIT_NOTE = "차에서 내리기 전에 직접 살펴봐 주세요."

    const val KICKER_SP = 24f
    const val TITLE_SP = DuoTokens.DISPLAY_SP   // 52sp: 무대에서 가장 큰 글자(먼저 읽힘)
    const val NOTE_SP = 24f
    /** 카드 최대 폭 = 무대 폭 × 이 비율(캐릭터·말풍선이 있는 오른쪽을 가리지 않게) */
    const val MAX_WIDTH_FRACTION = 0.34f   // 글꼴 2.0에서 0.40은 카드 오른쪽이 캐릭터 머리에 닿았다(에뮬 캡처 j01)

    /** 잠금 장면에는 어떤 카드도 보이지 않는다(조작 유도 금지). 질문·하차 장면만 */
    fun of(scene: Scene): Headline? = when (scene) {
        Scene.QUESTION -> Headline(QUESTION_KICKER, QUESTION_TITLE, QUESTION_NOTE)
        Scene.EXIT_CHECK -> Headline(EXIT_KICKER, EXIT_TITLE, EXIT_NOTE)
        Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.YES, Scene.WAIT_GUARDIAN, Scene.LOCKED, Scene.TRIP_END,
        Scene.AUTO_CHECK, Scene.DEPART_READY, Scene.ARRIVED, Scene.ALL_LEFT -> null
    }
}

/** 카드 View(카드 바탕 + 주황 알약 머리말 + 큰 제목 + 보조 한 줄). DuoTokens 색 역할을 쓴다 */
class StageHeadlineView(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val kicker = TextView(context)
    private val title = TextView(context)
    private val note = TextView(context)
    private var shown: Headline? = null
    private var shownNight: Boolean? = null

    init {
        orientation = VERTICAL
        visibility = GONE
        val pad = (DuoTokens.CARD_PAD_DP * 1.5f * d).toInt()
        setPadding(pad, pad, pad, pad)
        kicker.textSize = StageHeadline.KICKER_SP
        kicker.typeface = Typeface.DEFAULT_BOLD
        val kp = (16 * d).toInt(); val kv = (6 * d).toInt()
        kicker.setPadding(kp, kv, kp, kv)
        addView(kicker, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        title.textSize = StageHeadline.TITLE_SP
        title.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)   // V6: Jua
        title.setLineSpacing(0f, 1.1f)
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * d).toInt() })
        note.textSize = StageHeadline.NOTE_SP
        addView(note, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * d).toInt() })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(h: Headline?, night: Boolean, stageWidthPx: Int) {
        if (h == null) { visibility = GONE; shown = null; return }
        if (stageWidthPx > 0) {
            val maxW = (stageWidthPx * StageHeadline.MAX_WIDTH_FRACTION).toInt()
            title.maxWidth = maxW; note.maxWidth = maxW; kicker.maxWidth = maxW
        }
        if (h != shown || night != shownNight) {
            val r = DuoTokens.roles(night)
            background = V6Panels.card(d, night)
            kicker.text = h.kicker
            kicker.setTextColor(Palette.argb(r.accentText))
            kicker.background = DuoDraw.round(r.accentFill, 999f)
            title.text = h.title
            title.setTextColor(Palette.argb(r.text))
            note.text = h.note
            note.setTextColor(Palette.argb(r.textMuted))
            contentDescription = "${h.kicker}. ${h.title.replace('\n', ' ')}. ${h.note}"
            shown = h; shownNight = night
        }
        visibility = VISIBLE
    }
}
