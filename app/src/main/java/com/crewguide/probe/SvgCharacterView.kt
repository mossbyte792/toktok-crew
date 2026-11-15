package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 직접 그린 SVG 부품을 레이어 순으로 그리는 캐릭터 뷰(가안, 개발용). 이미지·네트워크·저장 없음.
 * 0~100 정사각 좌표를 뷰 크기에 맞춰 스케일한다. Path는 init에서 한 번만 만들어 캐시한다.
 */
class SvgCharacterView(context: Context, private val sizeDp: Int = 360) : View(context), CharacterRenderer {

    // CharacterRenderer 구현: 기존 프로퍼티 style·mouth를 설정만 한다(eyesOpen·waveDeg·tiltDeg는 저장만, 단계 4에서 사용)
    override val view: View get() = this
    override fun applyStyle(style: CharacterStyle) { this.style = style }
    override fun setPose(pose: CharacterPose) {
        mouth = pose.mouth   // 값이 바뀌면 setter가 invalidate 한다
        val changed = eyesOpen != pose.eyesOpen || waveDeg != pose.waveDeg || tiltDeg != pose.tiltDeg
        eyesOpen = pose.eyesOpen; waveDeg = pose.waveDeg; tiltDeg = pose.tiltDeg
        if (changed) invalidate()
    }
    var eyesOpen: Float = 1f
        private set
    var waveDeg: Float = 0f
        private set
    var tiltDeg: Float = 0f
        private set

    var style: CharacterStyle = CharacterStyle.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            parts = StyleToParts.partsFor(value, mouth)
            invalidate()
        }

    var mouth: MouthShape = MouthShape.SMILE
        set(value) {
            if (field == value) return
            field = value
            parts = StyleToParts.partsFor(style, value)
            invalidate()
        }

    /** 지금 스타일·입에 맞는 그릴 부품 목록(스타일/입이 바뀔 때만 다시 만든다). 기본값은 기존 12개 부품 */
    private var parts: List<SvgPart> = StyleToParts.partsFor(CharacterStyle.DEFAULT, MouthShape.SMILE)

    private class Built(val path: Path, val fill: ColorRole, val stroke: Boolean, val strokeWidth: Float)

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 주의: init 블록보다 위에 선언한다(아래에 두면 init이 넣은 plateRgb를 초기값 null이 덮는다)
    /** 야간 배경판 색(주간은 null). fitInside일 때 onDraw가 정사각 영역 기준으로 직접 그린다 */
    private var plateRgb: Int? = null
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plateRect = android.graphics.RectF()

    /** 부품 이름 → 만든 Path 목록 */
    private val built = HashMap<String, List<Built>>()

    /** 파싱에 성공한 부품 수와 실패한 부품 수(경로 하나라도 빈 결과면 실패) */
    val parsedOk: Int
    val parsedFail: Int

    init {
        var ok = 0
        var fail = 0
        for (part in CharacterParts.ALL + CharacterExtraParts.ALL) {
            val list = ArrayList<Built>()
            var good = true
            for (pp in part.paths) {
                val cmds = SvgPathParser.parse(pp.d)
                if (cmds.isEmpty()) { good = false; continue }
                list.add(Built(toPath(cmds), pp.fill, pp.stroke, pp.strokeWidth))
            }
            built[part.name] = list
            if (good) ok++ else fail++
        }
        parsedOk = ok
        parsedFail = fail
        contentDescription = "안내자 캐릭터(가안, 개발용)"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // 야간에만 뒤에 밝은 둥근 배경판을 깐다(주간은 없음). view background는 쓰지 않고 onDraw가 그린다.
        // fitInside=true(무대)에서는 그리지 않는다
        plateRgb = Palette.current.charPlate
    }

    /**
     * false(기본): 기존 동작 — 측정은 정사각 강제, 그림은 뷰 전체, 야간 배경판은 뷰 전체에 둥근 판(onDraw).
     * true: 주어진 폭·높이를 그대로 따르고, min(width,height) 정사각을 가운데에 그린다. 배경판은 그리지 않는다.
     */
    var fitInside: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** fitInside일 때 캔버스에서 보일 구간(기본 FULL = 이전과 같은 그림). 구간 밖은 뷰의 정사각에서 잘린다 */
    var viewport: CharacterViewport = CharacterViewport.FULL
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private fun toPath(cmds: List<PathCmd>): Path {
        val p = Path()
        for (c in cmds) {
            when (c) {
                is PathCmd.MoveTo -> p.moveTo(c.x, c.y)
                is PathCmd.LineTo -> p.lineTo(c.x, c.y)
                is PathCmd.CubicTo -> p.cubicTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
                is PathCmd.QuadTo -> p.quadTo(c.x1, c.y1, c.x, c.y)
                is PathCmd.Close -> p.close()
            }
        }
        return p
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        if (fitInside) { setMeasuredDimension(w, h); return }   // 주어진 크기를 그대로 따른다
        var s = minOf(w, h)
        if (s <= 0) s = (sizeDp * density).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val st = style
        canvas.save()
        try {
            val vp = viewport
            val useVp = fitInside && vp != CharacterViewport.FULL
            val k = if (useVp) vp.scaleFor(s) else s / 100f
            if (!fitInside) {
                // 기존 배경 드로어블과 같은 모양: 뷰 전체, 모서리 반지름 sizeDp×밀도×0.18
                plateRgb?.let { rgb ->
                    platePaint.color = Palette.argb(rgb)
                    plateRect.set(0f, 0f, width.toFloat(), height.toFloat())
                    val rad = sizeDp * density * 0.18f
                    canvas.drawRoundRect(plateRect, rad, rad, platePaint)
                }
            }
            if (fitInside) {
                val offX = (width - s) / 2f
                val offY = (height - s) / 2f
                canvas.translate(offX, offY)
                if (useVp) {
                    canvas.clipRect(0f, 0f, s, s)   // 구간 밖은 정사각에서 잘린다(translate 직후 좌표 = 정사각 기준)
                    canvas.translate(vp.offsetX(s), vp.offsetY(s))
                } else {
                    // FULL(롤백 시): 몸 연장(y 100~135)이 정사각 아래로 새지 않게 아래만 자른다. 기존 부품 범위 [-5,105] 안의 그림은 그대로
                    // (위·좌우는 넉넉히 열어 둔다: 정사각 좌표에서 y ≤ 1.05·s)
                    canvas.clipRect(-s, -s, 2f * s, 1.05f * s)
                }
            }
            canvas.scale(k, k)
            val tilt = tiltDeg
            val wave = waveDeg
            val eyes = eyesOpen
            for (part in parts) {
                val list = built[part.name] ?: continue
                // 그룹별 회전: 기본값(0도)이면 save/restore 없이 기존과 같게 그린다
                val angle = when (part.group) {
                    PartGroup.HEAD -> tilt
                    PartGroup.ARM_FRONT -> wave
                    else -> 0f
                }
                val pvx = if (part.group == PartGroup.ARM_FRONT) ARM_PIVOT_X else HEAD_PIVOT_X
                val pvy = if (part.group == PartGroup.ARM_FRONT) ARM_PIVOT_Y else HEAD_PIVOT_Y
                val rotated = angle != 0f
                if (rotated) { canvas.save(); canvas.rotate(angle, pvx, pvy) }
                for (b in list) {
                    // 눈 스케일은 머리 그룹의 눈·하이라이트에만(몸의 이름표 흰색 등은 제외)
                    val squash = eyes != 1f && part.group == PartGroup.HEAD && (b.fill == ColorRole.EYE || b.fill == ColorRole.HIGHLIGHT)
                    if (squash) { canvas.save(); canvas.translate(0f, EYE_CENTER_Y); canvas.scale(1f, eyes); canvas.translate(0f, -EYE_CENTER_Y) }
                    if (b.fill != ColorRole.NONE) {
                        fillPaint.color = CharacterColors.colorFor(b.fill, st)
                        canvas.drawPath(b.path, fillPaint)
                    }
                    if (b.stroke) {
                        strokePaint.color = CharacterColors.LINE_ARGB
                        // 화면 픽셀 기준 최소 1px 보장: 캔버스 단위 두께 × 스케일 k가 1px보다 작으면 1px/k로 올린다
                        strokePaint.strokeWidth = if (k > 0f) maxOf(b.strokeWidth, 1f / k) else b.strokeWidth
                        canvas.drawPath(b.path, strokePaint)
                    }
                    if (squash) canvas.restore()
                }
                if (rotated) canvas.restore()
            }
        } catch (e: Exception) {
            // 그리기 오류가 앱을 멈추지 않게 한다(일부만 그려질 수 있음)
        } finally {
            canvas.restore()
        }
    }

    private companion object {
        const val EYE_CENTER_Y = 42f   // 눈 세로 스케일 기준(눈 중심 y, 설계값)
        const val ARM_PIVOT_X = 72f    // 앞 팔 어깨 pivot
        const val ARM_PIVOT_Y = 84f
        const val HEAD_PIVOT_X = 50f   // 머리 기울임 pivot
        const val HEAD_PIVOT_Y = 70f
    }
}
