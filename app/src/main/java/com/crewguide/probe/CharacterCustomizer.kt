package com.crewguide.probe

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 캐릭터 꾸미기 접이식 영역(2026-11-14: 화면 글자의 "(가안, 시연용)"·"(가안)" 표기를 지웠다. 진입은 CustomizeAvailability로 숨김 상태). 선택한 6개 인덱스만 이 앱의 로컬 SharedPreferences에 저장한다(CharacterStore).
 * 이름·사진·생년월일·음성 입력 없음. 보호자 확인 상태·판정·음성·TTS와 무관하다.
 * 유효한 P가 아니면(visible=false) 영역 전체를 숨기고 접힌 상태로 되돌린다.
 */
class CharacterCustomizer(
    context: Context,
    private val dp: (Int) -> Int,
    private val gapPx: Int,
    private val textSp: Float,
    private val titleSp: Float,
    private val minTouchPx: Int,
    private val textColor: Int,
    previewDp: Int,
    private val onChange: (CharacterStyle) -> Unit,
    private val onReset: () -> Unit = {},
    private val onToneChange: (Tone) -> Unit = {},
) {
    companion object {
        const val SECTION_TITLE = "캐릭터 꾸미기"
        const val NOTE = "선택은 이 기기의 앱에만 저장돼요(머리·피부·옷 색, 모양, 표정, 소품 번호만). 이름·사진은 받지 않아요. [기본값으로 되돌리기]를 누르면 저장값도 지워져요."
        const val BUSY_NOTE = "보호자 확인 중에는 바꿀 수 없어요."
        const val RESET_TEXT = "기본값으로 되돌리기"
        const val UNIFORM_LABEL = "제복 요소"
        const val UNIFORM_NOTE = "제복 요소는 시험용 캐릭터에서 보여요."
        const val TONE_LABEL = "말투"
        const val TONE_BTN_A = "말투 A: 쉬운 말"
        const val TONE_BTN_B = "말투 B: 조금 자세히"
        const val TONE_NOTE = "말투 선택만 이 기기의 앱에 저장돼요(A 또는 B). 나이는 받지 않아요."
        const val TONE_PREVIEW_PREFIX = "예 이후 아이 안내: "
        private const val PER_LINE = 3  // 한 줄에 놓을 버튼 수(넘으면 줄바꿈)
    }

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val title: TextView
    private val busyView: TextView
    private val preview = GuideCharacterView(context, previewDp)
    private val resetBtn: Button
    private val hairBtns = mutableListOf<Button>()
    private val skinBtns = mutableListOf<Button>()
    private val outfitBtns = mutableListOf<Button>()
    private val shapeBtns = mutableListOf<Button>()
    private val toneBtns = mutableListOf<Button>()
    private val accBtns = mutableListOf<Button>()
    private val uniformBtns = mutableListOf<Button>()
    private val toneBtnA: Button
    private val toneBtnB: Button
    private val tonePreview: TextView
    private var tone: Tone = ToneSetting.DEFAULT
    private var open = false
    private var enabled = true
    var style: CharacterStyle = CharacterStyle.DEFAULT
        private set

    private fun tv(context: Context, t: String, sp: Float) = TextView(context).apply {
        text = t; textSize = sp; setTextColor(textColor); setPadding(0, dp(4), 0, dp(4))
    }

    private fun luminance(argb: Int): Int =
        (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

    private fun swatchButton(context: Context, argb: Int, bigNumber: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        textSize = textSp.coerceAtLeast(titleSp)   // 24sp
        typeface = Typeface.DEFAULT
        isAllCaps = false
        val h = if (bigNumber) maxOf(minTouchPx, dp(DesignRules.MIN_TOUCH)) else minTouchPx
        minHeight = h; minimumHeight = h
        setPadding(dp(4), 0, dp(4), 0)
        val pal = Palette.current
        setTextColor(
            if (bigNumber) CharacterOptions.bestTextArgb(argb, Palette.argb(pal.swatchTextDark), Palette.argb(pal.swatchTextLight))
            else if (luminance(argb) > 140) Palette.argb(pal.swatchTextDark) else Palette.argb(pal.swatchTextLight),
        )
        background = GradientDrawable().apply { setColor(argb); setStroke(dp(2), Palette.argb(Palette.current.chipStroke)); cornerRadius = dp(8).toFloat() }
        setOnClickListener { if (enabled) onClick() }
    }

    /** 색이 없는 모양·톤·소품 선택 버튼(연한 회색 바탕) */
    private fun optionButton(context: Context, onClick: () -> Unit) = swatchButton(context, Palette.argb(Palette.current.chipBg), onClick = onClick)

    /** 버튼들을 한 줄 3개씩 줄바꿈해 넣는다(가로·세로 간격 gap) */
    private fun addWrapped(context: Context, parent: LinearLayout, buttons: List<Button>, gap: Int = gapPx) {
        var line: LinearLayout? = null
        for ((i, b) in buttons.withIndex()) {
            if (i % PER_LINE == 0) {
                line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                parent.addView(line)
            }
            line!!.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, gap, gap) })
        }
        val rest = (PER_LINE - buttons.size % PER_LINE) % PER_LINE
        repeat(rest) { line?.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }   // 폭 맞춤용 빈 자리(터치 대상 아님)
    }

    private fun addRow(context: Context, label: String, buttons: List<Button>, gap: Int = gapPx) {
        body.addView(tv(context, label, textSp))
        addWrapped(context, body, buttons, gap)
    }

    init {
        title = TextView(context).apply {
            textSize = titleSp; setTextColor(textColor); setPadding(0, dp(8), 0, dp(8))
            minimumHeight = minTouchPx; gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { setOpen(!open) }
        }
        root.addView(title)

        body.addView(tv(context, NOTE, textSp))
        busyView = tv(context, BUSY_NOTE, textSp).apply { visibility = View.GONE }
        body.addView(busyView)
        body.addView(preview)

        for (o in CharacterOptions.HAIR.indices) hairBtns.add(swatchButton(context, CharacterOptions.HAIR[o].argb) { choose(style.copy(hairColorIndex = o)) })
        for (o in CharacterOptions.SKIN.indices) skinBtns.add(swatchButton(context, CharacterOptions.SKIN[o].argb, bigNumber = true) { choose(style.copy(skinColorIndex = o)) }.apply { contentDescription = CharacterOptions.skinDescription(o) })
        for (o in CharacterOptions.OUTFIT.indices) outfitBtns.add(swatchButton(context, CharacterOptions.OUTFIT[o].bodyArgb) { choose(style.copy(outfitColorIndex = o)) })
        addRow(context, "머리색", hairBtns)
        addRow(context, "피부색", skinBtns, maxOf(gapPx, dp(DesignRules.MIN_TARGET_GAP)))   // 피부색 줄만 간격 23dp 이상(3개씩 두 줄)
        addRow(context, "옷 색", outfitBtns)

        for (o in CharacterOptions.HAIR_SHAPE_NAMES.indices) shapeBtns.add(optionButton(context) { choose(style.copy(hairShapeIndex = o)) })
        for (o in CharacterOptions.TONE_NAMES.indices) toneBtns.add(optionButton(context) { choose(style.copy(toneIndex = o)) })
        for (o in CharacterOptions.ACCESSORY_NAMES.indices) accBtns.add(optionButton(context) { choose(style.copy(accessoryIndex = o)) })
        addRow(context, "머리 모양", shapeBtns)
        addRow(context, "표정 톤", toneBtns)
        addRow(context, "소품", accBtns)

        // 제복 요소(가안): 6버튼(UNIFORM.size), 한 줄 3개씩 두 줄, 간격 23dp 이상. 그림은 시험용 캐릭터(3b)에서만 바뀐다
        for (o in CharacterOptions.UNIFORM.indices) uniformBtns.add(optionButton(context) { choose(style.copy(uniform = o)) }.apply { contentDescription = CharacterOptions.uniformDescription(o) })
        addRow(context, ReleaseCopy.t(UNIFORM_LABEL), uniformBtns, maxOf(gapPx, dp(DesignRules.MIN_TARGET_GAP)))
        body.addView(tv(context, ReleaseCopy.t(UNIFORM_NOTE), textSp))

        // 말투(가안) 행: ✔로 선택 구분, 같은 활성 조건
        body.addView(tv(context, ReleaseCopy.t(TONE_LABEL), textSp))
        toneBtnA = optionButton(context) { chooseTone(Tone.A) }
        toneBtnB = optionButton(context) { chooseTone(Tone.B) }
        val toneLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        toneLine.addView(toneBtnA, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, gapPx, gapPx) })
        toneLine.addView(toneBtnB, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 0, gapPx) })
        body.addView(toneLine)
        tonePreview = tv(context, "", textSp)
        body.addView(tonePreview)
        body.addView(tv(context, ReleaseCopy.t(TONE_NOTE), textSp))
        refreshTone()

        resetBtn = Button(context).apply {
            text = RESET_TEXT; textSize = titleSp; typeface = Typeface.DEFAULT; isAllCaps = false
            minHeight = minTouchPx; minimumHeight = minTouchPx
            setTextColor(Palette.argb(Palette.current.btnOnText))
            background = GradientDrawable().apply { setColor(Palette.argb(Palette.current.btnOnBg)); cornerRadius = dp(8).toFloat() }
            setOnClickListener { if (enabled) { choose(CharacterStyle.DEFAULT); onReset() } }
        }
        body.addView(resetBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, gapPx, 0, gapPx) })
        root.addView(body)
        refreshButtons()
        setOpen(false)
    }

    private fun choose(next: CharacterStyle) {
        if (!enabled) return
        style = CharacterOptions.sanitize(next)
        preview.style = style
        refreshButtons()
        onChange(style)
    }

    private fun chooseTone(next: Tone) {
        if (!enabled) return
        tone = next
        refreshTone()
        onToneChange(next)
    }

    /** 앱 시작 때 저장된 말투를 적용한다(저장 콜백은 부르지 않는다) */
    fun applyTone(stored: Tone) { tone = stored; refreshTone() }

    private fun refreshTone() {
        toneBtnA.text = label(tone == Tone.A, TONE_BTN_A)
        toneBtnB.text = label(tone == Tone.B, TONE_BTN_B)
        tonePreview.text = TONE_PREVIEW_PREFIX + ToneSetting.text(ToneSetting.runtime(tone, BuildMockPolicy.TONE_B_ALLOWED), ToneKind.CHILD_NOTICE)
    }

    /** 앱 시작 때 저장값을 적용한다(저장 콜백은 부르지 않는다) */
    fun applyStored(stored: CharacterStyle) {
        style = CharacterOptions.sanitize(stored)
        preview.style = style
        refreshButtons()
    }

    private fun refreshButtons() {
        for ((i, b) in hairBtns.withIndex()) b.text = label(i == style.hairColorIndex, CharacterOptions.HAIR[i].name)
        for ((i, b) in skinBtns.withIndex()) b.text = label(i == style.skinColorIndex, (i + 1).toString())   // 이름 글자 없이 번호만
        for ((i, b) in outfitBtns.withIndex()) b.text = label(i == style.outfitColorIndex, CharacterOptions.OUTFIT[i].name)
        for ((i, b) in shapeBtns.withIndex()) b.text = label(i == style.hairShapeIndex, CharacterOptions.HAIR_SHAPE_NAMES[i])
        for ((i, b) in toneBtns.withIndex()) b.text = label(i == style.toneIndex, CharacterOptions.TONE_NAMES[i])
        for ((i, b) in accBtns.withIndex()) b.text = label(i == style.accessoryIndex, CharacterOptions.ACCESSORY_NAMES[i])
        for ((i, b) in uniformBtns.withIndex()) b.text = label(i == style.uniform, CharacterOptions.UNIFORM[i].name)
    }

    private fun label(selected: Boolean, name: String) = (if (selected) "✔ " else "") + name

    private var inline = false   // 독립 꾸미기 화면 안에서 항상 펼쳐 둔다(제목 줄·작은 미리보기 숨김, 접히지 않음)

    /** 독립 꾸미기 화면용: 항상 펼친 상태로 쓴다. 제목 줄과 작은 미리보기는 큰 캐릭터가 대신하므로 숨긴다 */
    fun showInline() {
        inline = true
        title.visibility = View.GONE
        preview.visibility = View.GONE
        setOpen(true)
    }

    private fun setOpen(v: Boolean) {
        open = v
        body.visibility = if (v) View.VISIBLE else View.GONE
        title.text = (if (v) "▼ " else "▶ ") + ReleaseCopy.t(SECTION_TITLE)
    }

    /** 메인 스레드. visible=유효한 P(잠금 아님), selectable=보호자 확인 IDLE. 숨겨지면 접힌 상태로 되돌린다 */
    fun update(visible: Boolean, selectable: Boolean) {
        if (!visible) {
            if (open && !inline) setOpen(false)
            root.visibility = View.GONE
            enabled = false
            return
        }
        root.visibility = View.VISIBLE
        enabled = selectable
        for (b in hairBtns + skinBtns + outfitBtns + shapeBtns + toneBtns + accBtns + uniformBtns + resetBtn + toneBtnA + toneBtnB) { b.isEnabled = selectable; b.alpha = if (selectable) 1f else 0.4f }
        busyView.visibility = if (selectable) View.GONE else View.VISIBLE
    }
}
