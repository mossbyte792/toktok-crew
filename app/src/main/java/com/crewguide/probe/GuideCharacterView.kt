package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 안내자 캐릭터 상태. 보호자 확인 상태 기준이며 소리가 났다는 뜻이 아니다 */
enum class GuideMood { IDLE, SPEAKING, LISTENING, CHILD_NOTICE, WAITING, UNKNOWN }

/**
 * 코드로만 그리는 독자 디자인의 둥글고 단순한 안내자 캐릭터(시연용 그래픽, 가안).
 * 이미지 파일·네트워크 없음, 애니메이션 없음. 모자·배지·로고 없음.
 * 완료·안전을 뜻하는 체크 표시, 박수, 별 같은 표현은 쓰지 않는다.
 * 모든 좌표는 정사각 뷰(한 변 S)에 대한 비율이다.
 */
class GuideCharacterView(context: Context, sizeDp: Int = 96) : View(context) {

    var mood: GuideMood = GuideMood.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = descriptionOf(value)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val sizePx = (sizeDp * density).toInt()

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }

    private val bgPaint = fill(COLOR_BG_CIRCLE)
    private val bodyPaint = fill(COLOR_BODY)
    private val scarfPaint = fill(COLOR_SCARF)
    private val facePaint = fill(COLOR_FACE)
    private val hairPaint = fill(COLOR_HAIR)
    private val eyePaint = fill(COLOR_INK)
    private val highlightPaint = fill(COLOR_WHITE)
    private val cheekPaint = fill(COLOR_CHEEK)
    private val nosePaint = fill(COLOR_NOSE)
    private val mouthInside = fill(COLOR_MOUTH_INSIDE)
    private val handPaint = fill(COLOR_FACE)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_INK; style = Paint.Style.STROKE; strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BODY; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    /** 둥근 안경 테(얇은 선) */
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_INK; style = Paint.Style.STROKE; strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ribbonPaint = fill(COLOR_RIBBON)
    /** 꾸미기 색(가안). 기본값이면 기존 모양과 같다. 대입 시 Paint 색만 바꾸고 다시 그린다 */
    var style: CharacterStyle = CharacterStyle.DEFAULT
        set(value) {
            val v = CharacterOptions.sanitize(value)
            if (field == v) return
            field = v
            hairPaint.color = CharacterOptions.hair(v).argb
            facePaint.color = CharacterOptions.skin(v).argb
            handPaint.color = facePaint.color
            val o = CharacterOptions.outfit(v)
            bodyPaint.color = o.bodyArgb
            armPaint.color = o.bodyArgb
            scarfPaint.color = o.scarfArgb
            invalidate()
        }

    private val rect = RectF()
    private val path = Path()
    private val signs = floatArrayOf(-1f, 1f)

    init {
        contentDescription = descriptionOf(GuideMood.IDLE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // 야간에만 뒤에 밝은 둥근 배경판을 깐다(그림 색은 그대로, 크기는 onMeasure가 고정하므로 높이 불변). 주간은 배경 없음
        Palette.current.charPlate?.let { plate ->
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Palette.argb(plate)); cornerRadius = sizePx * 0.18f
            }
        }
    }

    private fun descriptionOf(m: GuideMood): String = when (m) {
        GuideMood.IDLE -> "안내자 캐릭터, 대기 중"
        GuideMood.SPEAKING -> "안내자 캐릭터, 안내 말풍선 표시 중"
        GuideMood.LISTENING -> "안내자 캐릭터, 질문 중"
        GuideMood.CHILD_NOTICE -> "안내자 캐릭터, 아이 안내 표시 중"
        GuideMood.WAITING -> "안내자 캐릭터, 보호자 대기 중"
        GuideMood.UNKNOWN -> "안내자 캐릭터, 음성 확인 불가"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val m = mood

        // 배경: 큰 반투명 원
        canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.48f, bgPaint)

        // 상반신: 둥근 어깨 도형 하나(배경 원 안으로 잘라서 그림)
        canvas.save()
        path.reset()
        path.addCircle(s * 0.5f, s * 0.5f, s * 0.48f, Path.Direction.CW)
        canvas.clipPath(path)
        rect.set(s * 0.14f, s * 0.70f, s * 0.86f, s * 1.25f)
        canvas.drawRoundRect(rect, s * 0.22f, s * 0.22f, bodyPaint)
        canvas.restore()

        // 목 스카프: 띠 + 매듭 + 끝자락(하늘색, 로고 없음)
        rect.set(s * 0.35f, s * 0.67f, s * 0.65f, s * 0.77f)
        canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, scarfPaint)
        canvas.drawCircle(s * 0.5f, s * 0.77f, s * 0.04f, scarfPaint)
        path.reset()
        path.moveTo(s * 0.47f, s * 0.79f)
        path.lineTo(s * 0.53f, s * 0.79f)
        path.lineTo(s * 0.5f, s * 0.89f)
        path.close()
        canvas.drawPath(path, scarfPaint)

        drawHead(canvas, s, m)
        drawArm(canvas, s, m)
    }

    private fun drawHead(canvas: Canvas, s: Float, m: GuideMood) {
        val cx = s * 0.5f
        val cy = s * 0.40f
        val r = s * 0.30f   // 머리 지름 = 전체 높이의 약 60%

        canvas.save()
        if (m == GuideMood.LISTENING) canvas.rotate(8f, s * 0.5f, s * 0.70f) // 고개 기울임

        val st = style
        val calm = st.toneIndex == CharacterOptions.TONE_CALM

        // 묶은머리: 얼굴 뒤에 작은 원(머리 오른쪽 위)
        if (st.hairShapeIndex == CharacterOptions.HAIR_TIED) {
            canvas.drawCircle(cx + r * 0.95f, cy - r * 0.75f, r * 0.28f, hairPaint)
        }

        // 얼굴(윤곽선 없음)
        canvas.drawCircle(cx, cy, r, facePaint)

        // 머리카락: 머리 위쪽을 덮는 둥근 도형 하나
        rect.set(cx - r * 1.04f, cy - r * 1.04f, cx + r * 1.04f, cy + r * 1.04f)
        path.reset()
        path.arcTo(rect, 180f, 180f, false)
        if (st.hairShapeIndex == CharacterOptions.HAIR_SIDE_PART) {
            // 비대칭 앞머리: 오른쪽 가르마에서 왼쪽 아래로 쓸어내림(눈·눈썹 위에서 멈춤)
            path.quadTo(cx + r * 0.5f, cy - r * 0.75f, cx + r * 0.12f, cy - r * 0.55f)
            path.quadTo(cx - r * 0.5f, cy - r * 0.45f, cx - r * 1.04f, cy + r * 0.12f)
        } else {
            path.quadTo(cx, cy - r * 0.8f, cx - r * 1.04f, cy)
        }
        path.close()
        canvas.drawPath(path, hairPaint)

        // 소품: 작은 리본 머리핀(머리 왼쪽 위, 단순한 삼각형 둘 + 매듭)
        if (st.accessoryIndex == CharacterOptions.ACC_RIBBON) {
            val px = cx - r * 0.62f
            val py = cy - r * 0.68f
            for (sx in signs) {
                path.reset()
                path.moveTo(px, py)
                path.lineTo(px + sx * r * 0.24f, py - r * 0.13f)
                path.lineTo(px + sx * r * 0.24f, py + r * 0.13f)
                path.close()
                canvas.drawPath(path, ribbonPaint)
            }
            canvas.drawCircle(px, py, r * 0.05f, ribbonPaint)
        }

        // LISTENING: 귀 옆 물결 두 줄(손 반대쪽)
        if (m == GuideMood.LISTENING) {
            for (i in 1..2) {
                val wx = cx - r - i * r * 0.2f
                rect.set(wx - r * 0.2f, cy + r * 0.1f - r * 0.22f * i, wx + r * 0.2f, cy + r * 0.1f + r * 0.22f * i)
                canvas.drawArc(rect, 140f, 80f, false, line)
            }
        }

        // 볼 홍조
        for (sx in signs) {
            canvas.drawCircle(cx + sx * r * 0.66f, cy + r * 0.40f, r * 0.14f, cheekPaint)
        }
        // 코: 점 하나
        canvas.drawCircle(cx, cy + r * 0.30f, r * 0.04f, nosePaint)

        // 눈(눈 사이 넓게)
        val eyeY = cy + r * 0.12f
        val eyeDx = r * 0.50f
        val eyeRx = r * 0.10f
        val eyeRy = r * 0.16f * (if (calm) 0.8f else 1f)   // 차분: 눈을 약간 가늘게(웃음은 1배로 기존과 동일)
        val look = if (m == GuideMood.WAITING) r * 0.07f else 0f   // WAITING: 시선이 옆
        for (sx in signs) {
            val ex = cx + sx * eyeDx + look
            if (m == GuideMood.CHILD_NOTICE) {
                // 눈 웃음: 위로 굽은 호선
                rect.set(ex - eyeRx * 1.3f, eyeY - eyeRy * 0.6f, ex + eyeRx * 1.3f, eyeY + eyeRy * 0.6f)
                canvas.drawArc(rect, 200f, 140f, false, line)
            } else {
                rect.set(ex - eyeRx, eyeY - eyeRy, ex + eyeRx, eyeY + eyeRy)
                canvas.drawOval(rect, eyePaint)
                canvas.drawCircle(ex + eyeRx * 0.35f, eyeY - eyeRy * 0.4f, r * 0.04f, highlightPaint)
            }
        }

        // 소품: 둥근 안경 — 눈 주위 얇은 테 두 개와 코 연결선(안을 칠하지 않아 눈·하이라이트가 보인다)
        if (st.accessoryIndex == CharacterOptions.ACC_GLASSES) {
            val gr = r * 0.24f
            for (sx in signs) {
                canvas.drawCircle(cx + sx * eyeDx + look, eyeY, gr, glassPaint)
            }
            canvas.drawLine(cx + look - eyeDx + gr, eyeY, cx + look + eyeDx - gr, eyeY, glassPaint)
        }

        // 눈썹: LISTENING은 올림, UNKNOWN은 처짐
        if (m == GuideMood.LISTENING) {
            for (sx in signs) {
                val x = cx + sx * eyeDx
                canvas.drawLine(x - r * 0.14f, eyeY - r * 0.32f, x + r * 0.14f, eyeY - r * 0.36f, line)
            }
        } else if (m == GuideMood.UNKNOWN) {
            for (sx in signs) {
                canvas.drawLine(cx + sx * eyeDx * 0.55f, eyeY - r * 0.38f, cx + sx * eyeDx * 1.4f, eyeY - r * 0.20f, line)
            }
        }

        // 입
        val my = cy + r * 0.52f
        when (m) {
            GuideMood.SPEAKING -> {
                rect.set(cx - r * 0.13f, my - r * 0.10f, cx + r * 0.13f, my + r * 0.22f)
                canvas.drawOval(rect, mouthInside)
                for (sx in signs) { // 입 옆 호선
                    rect.set(cx + sx * r * 0.36f - r * 0.1f, my - r * 0.12f, cx + sx * r * 0.36f + r * 0.1f, my + r * 0.2f)
                    canvas.drawArc(rect, if (sx < 0) 110f else -70f, 140f, false, line)
                }
            }
            GuideMood.LISTENING -> { // 작게 벌린 입
                canvas.drawCircle(cx, my + r * 0.04f, r * 0.07f, eyePaint)
            }
            GuideMood.CHILD_NOTICE -> { // 큰 미소(열린 반달 모양)
                val cd = if (calm) 0.8f else 1f   // 차분: 약하게만 줄임(입은 여전히 열림)
                rect.set(cx - r * 0.30f, my - r * 0.22f * cd, cx + r * 0.30f, my + r * 0.22f * cd)
                canvas.drawArc(rect, 0f, 180f, true, mouthInside)
            }
            GuideMood.WAITING -> { // 입 다묾
                canvas.drawLine(cx - r * 0.14f, my + r * 0.04f, cx + r * 0.14f, my + r * 0.04f, line)
            }
            GuideMood.UNKNOWN -> { // 입꼬리 내림
                rect.set(cx - r * 0.18f, my + r * 0.02f, cx + r * 0.18f, my + r * 0.32f)
                canvas.drawArc(rect, 200f, 140f, false, line)
            }
            GuideMood.IDLE -> { // 편안한 미소
                val mk = if (calm) 0.6f else 1f   // 차분: 미소 곡률 감소
                rect.set(cx - r * 0.2f, my - r * 0.15f * mk, cx + r * 0.2f, my + r * 0.1f * mk)
                canvas.drawArc(rect, 30f, 120f, false, line)
            }
        }
        canvas.restore()
    }

    /** 한쪽 팔(굵은 선)과 손 원. 상태별로 손 위치만 다르다 */
    private fun drawArm(canvas: Canvas, s: Float, m: GuideMood) {
        val sx0 = s * 0.72f
        val sy0 = s * 0.84f
        val hx: Float
        val hy: Float
        when (m) {
            GuideMood.IDLE -> { hx = s * 0.80f; hy = s * 0.98f }        // 팔 내림
            GuideMood.SPEAKING -> { hx = s * 0.85f; hy = s * 0.74f }    // 손을 들어 설명
            GuideMood.LISTENING -> { hx = s * 0.83f; hy = s * 0.57f }   // 손을 귀 근처에
            GuideMood.CHILD_NOTICE -> { hx = s * 0.85f; hy = s * 0.52f } // 작게 손 흔드는 자세
            GuideMood.WAITING -> { hx = s * 0.55f; hy = s * 0.92f }     // 손을 앞으로 모음
            GuideMood.UNKNOWN -> { hx = s * 0.86f; hy = s * 0.80f }     // 어깨 으쓱
        }
        armPaint.strokeWidth = s * 0.08f
        canvas.drawLine(sx0, sy0, hx, hy, armPaint)
        canvas.drawCircle(hx, hy, s * 0.05f, handPaint)

        if (m == GuideMood.WAITING) { // 모은 두 번째 손
            canvas.drawCircle(s * 0.46f, s * 0.92f, s * 0.05f, handPaint)
        } else if (m == GuideMood.CHILD_NOTICE) { // 손 옆 짧은 호선 두 개
            for (i in 1..2) {
                val ax = hx + s * 0.04f + i * s * 0.035f
                rect.set(ax - s * 0.03f, hy - s * 0.03f * i, ax + s * 0.03f, hy + s * 0.03f * i)
                canvas.drawArc(rect, -50f, 100f, false, line)
            }
        }
    }

    private companion object {
        const val COLOR_BG_CIRCLE = 0x66BFD9EE          // 연한 중간색 반투명 배경 원
        const val COLOR_BODY = 0xFF5E7FA8.toInt()       // 어깨·팔 (차분한 청색)
        const val COLOR_SCARF = 0xFF9ED3F0.toInt()      // 목 스카프 (하늘색)
        const val COLOR_FACE = 0xFFF8D9B8.toInt()       // 살구/베이지 얼굴·손
        const val COLOR_HAIR = 0xFF2F3A5C.toInt()       // 머리카락 (남색)
        const val COLOR_INK = CharacterOptions.EYE_INK_ARGB // 눈·입선·눈썹 (진한 색)
        const val COLOR_RIBBON = 0xFFE0708A.toInt()     // 리본 머리핀 (분홍)
        const val COLOR_WHITE = 0xFFFFFFFF.toInt()      // 눈 하이라이트
        const val COLOR_CHEEK = 0xFFF4B6B6.toInt()      // 볼 홍조 (연한 분홍)
        const val COLOR_NOSE = 0xFFD9A98A.toInt()       // 코 점
        const val COLOR_MOUTH_INSIDE = 0xFF8E3B3B.toInt() // 열린 입 안쪽
    }
}
