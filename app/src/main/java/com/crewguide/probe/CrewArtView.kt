package com.crewguide.probe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 공식 원본 PNG 한 장을 비율 그대로(contain) 그리는 뷰. 배율은 [CrewArt.fitScale]의 상한을 넘지 않는다(비정상 확대 금지).
 * drawable-nodpi라 기기 밀도에 따른 재샘플이 없고, 알파는 그대로 그린다. 상자 가운데(가로), 세로는 [bottomAlign]이면 아래, 아니면 가운데.
 * 리소스를 못 읽으면 아무것도 그리지 않는다(빈 칸 폴백, 앱은 계속 동작).
 */
class CrewArtView(context: Context, private val maxScale: Float, private val bottomAlign: Boolean = false) : ImageView(context) {
    var asset: CrewArtAsset? = null; private set
    /** v3 단계 7: true면 흉상을 전신 모자 기준점에 맞춰 그린다(무대 전용, [CrewAnchor]) */
    var anchorToParked = false
        set(v) { field = v; applyMatrix() }
    /** 반복 3: 흉상 배치 방식(anchorToParked일 때만). CARD = 초상 카드, HAT = 단계 7 머리 고정. 되돌리기 = HAT */
    var bustMode = BustAnchorMode.CARD
        set(v) { field = v; applyMatrix() }
    /** 지금 그리는 초상 카드(없으면 null). 로그·검증용 */
    var card: CrewAnchor.Card? = null; private set
    private val cardFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val cardStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val cardPath = android.graphics.Path()
    private val dens get() = resources.displayMetrics.density

    init {
        scaleType = ScaleType.MATRIX
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // 설명은 부모가 단다
    }

    @SuppressLint("DiscouragedApi")   // 이름으로 찾는 이유: 상태→파일 매핑을 순수 enum 하나에 두기 위해
    fun show(a: CrewArtAsset) {
        if (asset == a) return
        asset = a
        var id = resources.getIdentifier(a.resName, "drawable", context.packageName)
        if (id == 0) {
            // v3 폴백: 상태 그림이 없으면 기본 전신(parked)으로, 그것도 없으면 빈 칸(앱은 계속 동작)
            val fb = CrewArt.fallbackOf(a)
            id = if (fb == null) 0 else resources.getIdentifier(fb.resName, "drawable", context.packageName)
            android.util.Log.w("G1PROBE", "[캐릭터] ${a.resName} 없음 → ${fb?.resName ?: "빈 칸"}")
        }
        if (id == 0) { setImageDrawable(null); return }
        setImageResource(id)
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMatrix()
    }

    /** 실제 그려지는 배율(로그·검증용). 그림이 없으면 0 */
    var drawnScale = 0f; private set

    private fun applyMatrix() {
        val d = drawable ?: return
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        val bw = width - paddingLeft - paddingRight
        val bh = height - paddingTop - paddingBottom
        // 폴백으로 다른 그림이 들어온 경우(크기가 다름)는 앵커를 쓰지 않는다
        val same = asset?.takeIf { it.widthPx == iw && it.heightPx == ih }
        card = if (anchorToParked && bustMode == BustAnchorMode.CARD && same != null) CrewAnchor.bustCard(same, bw, bh, maxScale, CARD_PAD_DP * dens) else null
        val anchored = card?.place ?: if (anchorToParked) same?.let { CrewAnchor.place(it, bw, bh, maxScale) } else null
        val s = anchored?.scale ?: CrewArt.fitScale(iw, ih, bw, bh, maxScale)
        drawnScale = s
        val m = Matrix()
        m.setScale(s, s)
        val dx = paddingLeft + (anchored?.dx ?: ((bw - iw * s) / 2f))
        val dy = paddingTop + (anchored?.dy ?: if (bottomAlign) (bh - ih * s) else (bh - ih * s) / 2f)
        // 정수 px에 맞춰 반 픽셀 번짐을 줄인다
        m.postTranslate(Math.round(dx).toFloat(), Math.round(dy).toFloat())
        imageMatrix = m
        card?.let { c ->
            val r = CARD_RADIUS_DP * dens
            cardPath.reset()
            // 아래 변은 흉상 잘린 선과 같다 → 아래 모서리는 덜 둥글게(잘린 선이 모서리에서 비지 않게)
            val rb = CARD_BOTTOM_RADIUS_DP * dens
            cardPath.addRoundRect(android.graphics.RectF(paddingLeft + c.left, paddingTop + c.top, paddingLeft + c.right, paddingTop + c.bottom),
                floatArrayOf(r, r, r, r, rb, rb, rb, rb), android.graphics.Path.Direction.CW)
        }
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val c = card
        if (c == null) { super.onDraw(canvas); return }
        val night = Palette.current.night
        cardFill.color = Palette.argb(if (night) CARD_FILL_NIGHT else CARD_FILL_DAY)
        cardStroke.color = Palette.argb(if (night) CARD_STROKE_NIGHT else CARD_STROKE_DAY)
        cardStroke.strokeWidth = CARD_STROKE_DP * dens
        canvas.drawPath(cardPath, cardFill)
        canvas.save(); canvas.clipPath(cardPath); super.onDraw(canvas); canvas.restore()
        canvas.drawPath(cardPath, cardStroke)
    }

    companion object {
        const val CARD_PAD_DP = 20
        const val CARD_RADIUS_DP = 28
        const val CARD_BOTTOM_RADIUS_DP = 12
        const val CARD_STROKE_DP = 6
        /** 낮: 흰 카드 + 남색 윤곽(홈 구름 말풍선과 같은 짝). 밤: 어두운 카드 + 밝은 파랑 윤곽 */
        const val CARD_FILL_DAY = 0xFFFFFF
        const val CARD_STROKE_DAY = Palette.CREW_BLUE
        const val CARD_FILL_NIGHT = 0x1E293B
        const val CARD_STROKE_NIGHT = 0x93C5FD
    }
}

/**
 * 무대용 렌더러: 공식 원본 PNG를 장면·실제 음성 상태에 맞춰 바꿔 그린다(정지 이미지, 자체 애니메이션 없음).
 * 상자 높이는 [CrewArt.STAGE_BOX_MAX_DP] 이하로 고정해 상태가 바뀌어도 상자는 그대로다.
 * 꾸미기 스타일·자세(입 모양 등)는 공식 원본이 지원하지 않으므로 받기만 하고 그리지 않는다(임의 합성 금지).
 */
class CrewPngRenderer(context: Context) : FrameLayout(context), CharacterRenderer {
    override val view: View get() = this
    private val art = CrewArtView(context, CrewArt.STAGE_MAX_SCALE)
    var state: CrewArtState = CrewArtState.IDLE; private set

    init {
        clipChildren = true
        art.anchorToParked = true   // v3 단계 7: 전신↔흉상 전환 때 머리 위치·크기 유지
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        art.show(CrewArt.assetOf(state))
        contentDescription = CrewArtText.descOf(state)
    }

    fun setArtState(s: CrewArtState) {
        if (s == CrewArtState.GREETING) return   // 무대에서는 인사 반신을 쓰지 않는다
        if (state != s) android.util.Log.i("G1PROBE", "[캐릭터] 무대 상태 $state -> $s (${CrewArt.assetOf(s).key}.png)")
        state = s
        art.show(CrewArt.assetOf(s))
        contentDescription = CrewArtText.descOf(s)
    }

    val drawnAsset: CrewArtAsset? get() = art.asset
    val drawnScale: Float get() = art.drawnScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 상자 높이 상한: 영역이 더 커도 그림 상자는 STAGE_BOX_MAX_DP. 남는 위아래는 비운다(가운데)
        val cap = (CrewArt.STAGE_BOX_MAX_DP * resources.displayMetrics.density).toInt()
        val h = measuredHeight
        val lp = art.layoutParams as LayoutParams
        val want = if (h > cap) cap else LayoutParams.MATCH_PARENT
        if (lp.height != want) {
            lp.height = want
            lp.gravity = android.view.Gravity.CENTER
            art.layoutParams = lp
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun applyStyle(style: CharacterStyle) { /* 공식 원본은 꾸미기 옵션 미지원: 저장값은 건드리지 않고 무시 */ }
    override fun setPose(pose: CharacterPose) { /* 정지 이미지: 자세 무시 */ }
}

object CrewArtText {
    fun descOf(s: CrewArtState): String = when (s) {
        CrewArtState.IDLE -> "톡톡크루 승무원"
        CrewArtState.GREETING -> "톡톡크루 승무원이 손을 흔들어요"
        CrewArtState.LISTENING -> "톡톡크루 승무원이 듣고 있어요"
        CrewArtState.SPEAKING -> "톡톡크루 승무원이 말하고 있어요"
        CrewArtState.QUIET, CrewArtState.INTERRUPTED -> "톡톡크루 승무원이 조용히 기다려요"
        CrewArtState.DRIVING -> "톡톡크루 승무원(주행 중)"
    }
}
