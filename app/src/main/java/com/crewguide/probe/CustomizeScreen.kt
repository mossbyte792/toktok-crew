package com.crewguide.probe

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object CustomizeLayout {
    const val TITLE_SP = 40f
    const val PANEL_PAD_DP = 24
    const val PANEL_RADIUS_DP = 28
    const val DONE_HEIGHT_DP = 96
    const val DONE_MIN_WIDTH_DP = 240
    const val DONE_SP = 40f
    const val MARGIN_DP = 24
    /** 큰 승무원 영역이 화면 가로에서 차지하는 비율(%) */
    const val CHARACTER_PERCENT = 46
}

object CustomizeText {
    const val TITLE = "승무원 꾸미기"
    const val DONE = "완료"
}

/**
 * 독립 꾸미기 화면(가안, 사용자 설계 2026-11-07): 왼쪽에 큰 승무원, 오른쪽에 꾸미기 항목(기존 CharacterCustomizer를 그대로 펼쳐 쓴다),
 * 구석의 [완료]를 누르면 홈으로 돌아간다. 선택은 기존처럼 이 기기의 로컬 저장소에만 저장된다(CharacterCustomizer.onChange).
 * 보호자 확인 상태·판정과 무관하다.
 */
class CustomizeScreen(
    context: Context,
    optionsRoot: View,
    private val onDone: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private val night = Palette.current.night
    // 홈 화면과 같은 마스코트 파츠 이미지(2026-11-07): 여기서 고르는 모습이 홈에 그대로 보인다
    private val mascot = MascotView(context)
    private lateinit var doneButton: android.widget.Button

    init {
        val bd = backdropOf(Scene.PICK, night)
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.argb(bd.topRgb), Palette.argb(bd.bottomRgb)),
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // 왼쪽: 큰 승무원(홈과 같은 구도)
        mascot.contentDescription = "꾸미는 중인 승무원"
        mascot.setPadding(dp(16), dp(16), dp(16), dp(96))   // 아래 [완료] 버튼과 겹치지 않게
        row.addView(mascot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, CustomizeLayout.CHARACTER_PERCENT.toFloat()))

        // 오른쪽: 꾸미기 항목 판(스크롤)
        val ink = Palette.argb(StageUiTokens.settingsInk(night))
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(CustomizeLayout.PANEL_PAD_DP)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Palette.argb(StageUiTokens.settingsFill(night)))
                cornerRadius = dp(CustomizeLayout.PANEL_RADIUS_DP).toFloat()
            }
        }
        val title = TextView(context).apply {
            text = CustomizeText.TITLE
            textSize = CustomizeLayout.TITLE_SP
            typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
            setTextColor(ink)
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(title)
        (optionsRoot.parent as? ViewGroup)?.removeView(optionsRoot)
        panel.addView(optionsRoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // [완료] 버튼이 가리지 않게 판 아래에 여백을 둔다
        panel.addView(View(context), LinearLayout.LayoutParams(1, dp(CustomizeLayout.DONE_HEIGHT_DP + CustomizeLayout.MARGIN_DP * 2)))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val m = dp(CustomizeLayout.MARGIN_DP)
        row.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - CustomizeLayout.CHARACTER_PERCENT).toFloat()).apply {
            setMargins(0, m, m, m)
        })
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 오른쪽 아래 [완료]: 시작 버튼과 같은 초록 알약
        val spec = specOf(ButtonKind.PRIMARY_START)
        val textInk = Palette.argb(StageButtons.colorOf(spec.textRole, night))
        val fill = Palette.argb(StageButtons.colorOf(spec.fillRole, night))
        val edge = Palette.argb(StageButtons.edgeColorFor(spec, night))
        val done = android.widget.Button(context)
        fun rect(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(400).toFloat() }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(rect(edge), rect(fill)))
        layers.setLayerInset(1, 0, 0, 0, dp(StageUiTokens.EDGE_DP))
        done.background = layers
        done.text = CustomizeText.DONE
        done.textSize = CustomizeLayout.DONE_SP
        done.typeface = StageFonts.typeface(context, StageFonts.FontRole.BIG_BUTTON)
        done.isAllCaps = false
        done.setTextColor(textInk)
        done.gravity = Gravity.CENTER
        done.stateListAnimator = null
        done.minHeight = dp(CustomizeLayout.DONE_HEIGHT_DP); done.minimumHeight = dp(CustomizeLayout.DONE_HEIGHT_DP)
        done.minWidth = dp(CustomizeLayout.DONE_MIN_WIDTH_DP); done.minimumWidth = dp(CustomizeLayout.DONE_MIN_WIDTH_DP)
        done.setPadding(dp(StageButtons.PAD_DP), 0, dp(StageButtons.PAD_DP), dp(StageUiTokens.EDGE_DP))
        done.setOnClickListener { onDone() }
        doneButton = done
        // 승무원(왼쪽) 아래 가운데에 둔다: 오른쪽 항목을 가리지 않는다. 가로 위치는 onSizeChanged에서 정한다
        addView(done, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CustomizeLayout.DONE_HEIGHT_DP), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = m * 2
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val charW = w * CustomizeLayout.CHARACTER_PERCENT / 100
        val lp = doneButton.layoutParams as LayoutParams
        lp.leftMargin = (charW / 2 - dp(CustomizeLayout.DONE_MIN_WIDTH_DP) / 2).coerceAtLeast(dp(CustomizeLayout.MARGIN_DP))
        doneButton.layoutParams = lp
    }

    fun setStyle(style: CharacterStyle) { mascot.setStyle(style) }
}
