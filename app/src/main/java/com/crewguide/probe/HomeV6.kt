package com.crewguide.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * V6 디자인 토큰 중 DuoTokens에 없던 값(순수 값). 단일 출처는 docs/design/v6/tokens/design-tokens.json이고,
 * DesignTokensJsonTest가 JSON(테스트 리소스 사본)과 이 값·DuoTokens·StatusChipSpec이 같은지 검사한다(수동 이중 입력 감시).
 * 방향: C. Hybrid Family Experience (2026-11-12 D1 자체 평가로 선택).
 */
object V6Tokens {
    // 간격·크기(dp)
    const val SCREEN_EDGE_DP = 40
    const val HEADER_DP = 104
    const val HERO_BUTTON_DP = 104
    const val HERO_EDGE_DP = 12
    const val STAGE_RADIUS_DP = 32
    const val BUBBLE_RADIUS_DP = 28
    const val BUBBLE_STROKE_DP = 4
    const val GEAR_EDGE_DP = 6
    const val SPACE_XL_DP = 24
    const val SPACE_XXL_DP = 32
    const val SPACE_LG_DP = 16
    const val COMPACT_BREAKPOINT_DP = 1100

    // 글자(sp)
    const val HERO_TITLE_SP = 56f
    const val BRAND_SP = 44f
    const val BODY_SMALL_SP = 28f
    const val BUBBLE_SP = 36f
    const val BUTTON_SP = 40f
    const val STAT_SP = 44f
    /** 말풍선은 장식(같은 내용을 카드가 글자로 준다)이라 글꼴 배율을 이 값까지만 따른다. 카드 본문은 배율을 그대로 따른다 */
    const val BUBBLE_MAX_FONT_SCALE = 1.3f

    // ---- 홈 인사 말풍선(2026-11-14 사용자 지시: 캐릭터 카드 폭 대부분·가로 가운데·머리 위로 충분히 떨어지게, 글자도 크게) ----
    /** 홈 말풍선 글자 상한(sp, 제목과 같은 크기). 실제 크기는 말풍선 폭에 맞춘 값과 이 값 중 작은 쪽, 하한은 [BUBBLE_SP] */
    const val HOME_BUBBLE_MAX_SP = 56f
    /** 말풍선 좌우 여백(무대 카드 안쪽, dp) = 카드 폭에서 이만큼만 남긴다 */
    const val HOME_BUBBLE_SIDE_DP = 24
    /** 말풍선 위 여백(무대 카드 위 경계선에서, dp) */
    const val HOME_BUBBLE_TOP_DP = 16
    /** 꼬리 끝 ↔ 캐릭터 그림 윗변 사이 빈 틈(dp). 자리가 남으면 [HOME_BUBBLE_GAP_MAX_DP]까지 늘린다 */
    const val HOME_BUBBLE_GAP_DP = 16
    const val HOME_BUBBLE_GAP_MAX_DP = 32
    /** 꼬리 길이(dp). 몸체 아랫변에서 캐릭터 쪽으로 */
    const val HOME_BUBBLE_TAIL_DP = 22
    const val HOME_BUBBLE_PAD_H_DP = 28
    const val HOME_BUBBLE_PAD_V_DP = 16
    /** 캐릭터 아래 여백(dp) */
    const val HOME_ART_BOTTOM_DP = 8

    class Stage(val top: Int, val mid: Int, val bottom: Int, val bubbleFill: Int, val bubbleStroke: Int, val bubbleText: Int)
    val STAGE_DAY = Stage(0xBFDBFE, 0xDBEAFE, 0xEFF6FF, 0xFFFFFF, 0x1E3A8A, 0x111111)
    val STAGE_NIGHT = Stage(0x1E3A8A, 0x172554, 0x0F172A, 0x1E293B, 0x93C5FD, 0xF1F5F9)
    fun stage(night: Boolean) = if (night) STAGE_NIGHT else STAGE_DAY

    /** 브랜드 남색(제목·P 배지) / 주황 머리말 / 은은한 바탕(통계 칸) */
    fun brand(night: Boolean) = if (night) 0x93C5FD else 0x1E3A8A
    fun eyebrow(night: Boolean) = if (night) 0xFDBA74 else 0xC2410C
    fun surfaceSubtle(night: Boolean) = if (night) 0x0F172A else 0xF1F5F9
}

/** 홈 '오늘 출발 준비' 요약(순수). 저장값을 읽기만 한다(새 저장 없음) */
data class HomeSummary(val children: Int, val weekStickers: Int) {
    companion object {
        fun of(book: ProfileBook, log: StickerLog, today: String): HomeSummary {
            val week = book.profiles.sumOf { p -> StickerBoard.weekTotal(StickerBoard.week(log, p.id, today)) }
            return HomeSummary(book.profiles.size, week.coerceAtLeast(0))
        }
    }
}

object HomeV6Text {
    const val EYEBROW = "오늘 출발 준비"
    const val TITLE = "출발 전에\n아이 자리를 확인해요"
    const val BODY = "자리를 고르면 착석·벨트 신호를 보고 다음 단계로 가요."
    const val STAT_CHILDREN = "등록한 아이"
    const val STAT_STICKERS = "이번 주 스티커"
    const val NONE = "아직 없어요"
    const val EMPTY_HINT = "아이를 등록하면 이름으로 불러 줘요. 등록하지 않아도 바로 쓸 수 있어요."
    const val CHIP_PARKED = "주차 중 · 조작할 수 있어요"
    const val NOTICE = "차량의 벨트 신호는 참고 정보예요. 보호자가 직접 확인해 주세요."
    /**
     * 사용 순서. 2026-11-14 루프 r3: 지금 흐름(뒷자리 착석 신호로 자동 배정, 여럿이면 자리를 눌러 바꿈)에 맞췄다.
     * 예전 1번 "[시작]을 누르고 아이가 앉은 자리를 골라요"는 옛 고르기 흐름 문구. 2·3번은 지금 흐름과 같아 그대로
     */
    val STEPS = listOf("[시작]을 누르면 뒷자리 착석 신호로 아이 자리를 정해요", "착석·벨트 신호를 받으면 다음 단계로 가요", if (SeatCheckRule.GUARDIAN_FOR_UNBUCKLED) "신호를 못 받거나 벨트 신호가 없는 자리는 [보호자 확인]을 눌러요" else "신호를 못 받는 자리만 [보호자 확인]을 눌러요")   // r8b(PM): 미체결 자리 보호자 확인이 켜지면 '만'이 사실과 달라져 바꾼다
    fun children(n: Int) = if (n <= 0) NONE else "${n}명"
    fun stickers(n: Int) = if (n <= 0) NONE else "${n}개"
    fun statDesc(s: HomeSummary) = "$STAT_CHILDREN ${children(s.children)}, $STAT_STICKERS ${stickers(s.weekStickers)}"
}

/**
 * 2026-11-13 글꼴 2.0 넘침 수정(S01): 카드 내용이 카드 높이를 넘으면 덜 중요한 것부터 줄인다(잘린 반쪽 글자가 보이지 않게).
 * 순서: ① 사용 순서 3단계 숨김(첫 안내에 같은 내용) ② 통계 두 칸 → 한 줄 요약 ③ 설명 한 줄 숨김. 그래도 넘치면 카드 안 스크롤(기존).
 * 제목·[시작]·통계 값은 줄이지 않는다.
 */
object HomeV6Fit {
    /** HIDE_HINT(2026-11-13 글꼴 2.0 캡처): 세 단계를 다 해도 넘쳐 '아이를 등록하면…' 보조 줄이 스크롤 경계에서 반쯤 잘려 보였다. 통계 줄이 이미 "등록한 아이 아직 없어요"를 말하므로 마지막에 숨긴다 */
    enum class Step { HIDE_STEPS, COMPACT_STATS, HIDE_BODY, HIDE_HINT }
    val ORDER = listOf(Step.HIDE_STEPS, Step.COMPACT_STATS, Step.HIDE_BODY, Step.HIDE_HINT)

    /** 높이 [measure](지금까지 적용한 단계 목록 → 내용 높이)로 [availPx] 안에 들 때까지 단계를 하나씩 더한다 */
    fun plan(availPx: Int, measure: (List<Step>) -> Int): List<Step> {
        val applied = ArrayList<Step>()
        if (measure(applied) <= availPx) return applied
        for (s in ORDER) {
            applied += s
            if (measure(applied) <= availPx) break
        }
        return applied
    }

    fun statLine(s: HomeSummary): String = "${HomeV6Text.STAT_CHILDREN} ${HomeV6Text.children(s.children)} · ${HomeV6Text.STAT_STICKERS} ${HomeV6Text.stickers(s.weekStickers)}"
}

/** 홈 V6 배치(순수, px). 왼쪽 정보 카드 + 오른쪽 캐릭터 무대 */
object HomeV6Layout {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int) { val right get() = left + width; val bottom get() = top + height }
    data class Plan(val card: Box, val stage: Box, val compact: Boolean)

    /**
     * @param w,h 홈 전체 px, @param d density. 넓은 창: 카드 52% / 무대 나머지. 좁은 창(폭 < 1100dp): 카드 60%.
     * 무대 폭이 캐릭터 원본 폭보다 좁아지지 않게(원본 확대 금지와 같은 이유로 축소도 최소화) 카드 쪽을 줄인다.
     */
    fun plan(w: Int, h: Int, d: Float, noticeH: Int): Plan {
        fun dp(v: Int) = (v * d).toInt()
        val edge = dp(V6Tokens.SCREEN_EDGE_DP)
        val gap = dp(V6Tokens.SPACE_XXL_DP)
        val top = dp(V6Tokens.HEADER_DP) + dp(V6Tokens.SPACE_XL_DP)
        val bottom = h - noticeH - dp(V6Tokens.SPACE_LG_DP)
        val inner = (w - 2 * edge - gap).coerceAtLeast(0)
        val compact = w < dp(V6Tokens.COMPACT_BREAKPOINT_DP)
        var cardW = (inner * (if (compact) 0.60f else 0.52f)).toInt()
        val minStage = (CrewArtAsset.PARKED.widthPx + dp(2 * V6Tokens.SPACE_XL_DP))
        if (inner - cardW < minStage) cardW = (inner - minStage).coerceAtLeast(inner / 2)
        val hgt = (bottom - top).coerceAtLeast(0)
        return Plan(Box(edge, top, cardW, hgt), Box(edge + cardW + gap, top, (inner - cardW).coerceAtLeast(0), hgt), compact)
    }

    /**
     * 무대 카드 안 배치(무대 좌표 px): 말풍선은 위, 캐릭터는 아래. 둘은 겹치거나 닿지 않는다.
     * [bubble]은 꼬리까지 포함한 상자(아랫변 = 꼬리 끝), [art]는 캐릭터 그림이 그려지는 상자(원본 274×609 비율, 확대 없음).
     * [tailX]는 꼬리 끝의 가로 위치(모자 가운데), [gap]은 꼬리 끝과 그림 윗변 사이 빈 틈.
     */
    data class StagePlan(val bubble: Box, val art: Box, val tailX: Int, val gap: Int, val artScale: Float) {
        /** 말풍선 폭 안에서 꼬리 위치 비율(0..1) */
        val tailXFraction: Float get() = if (bubble.width <= 0) 0.5f else ((tailX - bubble.left).toFloat() / bubble.width).coerceIn(0.15f, 0.85f)
    }

    /** 말풍선 폭 = 무대 카드 폭 − 좌우 여백(카드 폭 대부분). 아주 좁은 무대에서도 0 아래로 가지 않는다 */
    fun bubbleWidth(stageW: Int, d: Float): Int = (stageW - 2 * (V6Tokens.HOME_BUBBLE_SIDE_DP * d).toInt()).coerceAtLeast(0)

    /**
     * @param sw,sh 무대 카드 크기, @param bubbleH 말풍선 높이(꼬리 포함, 폭 [bubbleWidth]로 잰 값).
     * 캐릭터는 말풍선·틈을 뺀 나머지 높이에 맞춰 줄인다(확대 없음, 비율 유지) → 낮은 화면(1920×720)에서는 캐릭터가 작아진다.
     * 높이가 남으면(키 큰 창) 캐릭터는 아래에 붙고 말풍선은 머리 바로 위(틈 [V6Tokens.HOME_BUBBLE_GAP_DP]~[V6Tokens.HOME_BUBBLE_GAP_MAX_DP])에 둔다.
     */
    fun stagePlan(sw: Int, sh: Int, d: Float, bubbleH: Int): StagePlan {
        fun dp(v: Int) = (v * d).toInt()
        val a = CrewArtAsset.PARKED
        val topPad = dp(V6Tokens.HOME_BUBBLE_TOP_DP)
        val minGap = dp(V6Tokens.HOME_BUBBLE_GAP_DP)
        val bottomPad = dp(V6Tokens.HOME_ART_BOTTOM_DP)
        val bw = bubbleWidth(sw, d)
        val availH = (sh - topPad - bubbleH - minGap - bottomPad).coerceAtLeast(0)
        val availW = (sw - 2 * dp(V6Tokens.SPACE_XL_DP)).coerceAtLeast(0)
        val scale = CrewArt.fitScale(a.widthPx, a.heightPx, availW, availH, CrewArt.HOME_MAX_SCALE)
        // 내림: 그림 상자가 쓸 수 있는 높이를 1px도 넘지 않게(틈이 줄지 않게)
        val artW = (a.widthPx * scale).toInt(); val artH = (a.heightPx * scale).toInt()
        val artTop = sh - bottomPad - artH
        val artLeft = (sw - artW) / 2
        // 남는 높이의 1/3까지 틈을 더 벌린다(상한 있음)
        val spare = (artTop - minGap - bubbleH - topPad).coerceAtLeast(0)
        val gap = minGap + minOf(spare / 3, dp(V6Tokens.HOME_BUBBLE_GAP_MAX_DP - V6Tokens.HOME_BUBBLE_GAP_DP))
        val bubble = Box((sw - bw) / 2, artTop - gap - bubbleH, bw, bubbleH)
        val tailX = artLeft + Math.round(CrewAnchor.PARKED_HAT.centerX * scale)
        return StagePlan(bubble, Box(artLeft, artTop, artW, artH), tailX, gap, scale)
    }

    /**
     * 말풍선 글자 크기(px): 가장 긴 줄이 [availPx]에 들어가는 가장 큰 크기([minPx]..[maxPx]).
     * @param emWidth 글자 크기 1px일 때 가장 긴 줄의 폭(= measureText / textSize)
     */
    fun bubbleTextPx(emWidth: Float, availPx: Float, minPx: Float, maxPx: Float): Float {
        if (emWidth <= 0f || availPx <= 0f) return minPx
        val fit = kotlin.math.floor(availPx * 0.98f / emWidth)   // 2% 여유(반올림·자간 차이로 한 글자가 넘어가지 않게)
        return fit.coerceIn(minPx, maxOf(minPx, maxPx))
    }

    /** 크기 [px]에서 가장 긴 줄이 [availPx]에 다 들어가는가 */
    fun fitsLine(emWidth: Float, px: Float, availPx: Float): Boolean = emWidth * px <= availPx

    /**
     * 두 줄(쉼표 뒤 줄바꿈)로 쓸지: 한 줄이 하한 크기로도 안 들어가면 두 줄. 그 밖에는 두 줄이 글자가 더 크고
     * 캐릭터를 더 줄이지 않을 때만(키 큰 창) 두 줄 — 낮은 화면에서는 캐릭터 크기를 지키려고 한 줄로 둔다.
     */
    fun useTwoLines(oneFits: Boolean, onePx: Float, twoPx: Float, oneScale: Float, twoScale: Float): Boolean =
        !oneFits || (twoPx > onePx && twoScale >= oneScale - 0.001f)
}

/** 말풍선: 둥근 사각형 + 아래쪽 꼬리(캐릭터 머리 쪽). 채움·윤곽 색, 꼬리 x 위치(왼쪽에서 비율) */
class SpeechBubbleDrawable(private val fill: Int, private val stroke: Int, private val strokePx: Float, private val radiusPx: Float, private val tailPx: Float) : Drawable() {
    var tailXFraction = 0.5f
        set(v) { field = v; invalidateSelf() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val half = strokePx / 2f
        r.set(b.left + half, b.top + half, b.right - half, b.bottom - tailPx - half)
        path.reset()
        path.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW)
        val tx = b.left + (b.width() * tailXFraction)
        val tail = Path().apply {
            // 2026-11-14: 말풍선이 머리 바로 위 가운데라 꼬리는 곧게 아래(모자 쪽)를 가리킨다(예전: 왼쪽으로 기운 꼬리)
            moveTo(tx - tailPx * 0.7f, r.bottom - 1f); lineTo(tx, b.bottom.toFloat() - half); lineTo(tx + tailPx * 0.7f, r.bottom - 1f); close()
        }
        path.op(tail, Path.Op.UNION)
        p.style = Paint.Style.FILL; p.color = Palette.argb(fill)
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = strokePx; p.color = Palette.argb(stroke); p.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * 홈 화면 V6(C. Hybrid): 위 머리띠(브랜드·차량 상태 칩·톱니) / 왼쪽 '오늘 출발 준비' 정보 카드([시작]) / 오른쪽 캐릭터 무대 / 아래 고지.
 * 공개 API·콜백은 이전 홈([HomeScreenLegacy])과 같다. 보호자 확인·잠금 상태를 바꾸지 않고, 저장값은 읽기만 한다.
 * 롤백: 이전 홈은 [HomeScreenLegacy](HomeScreen.kt)로 코드 보존. 이 파일의 HomeScreen 클래스를 지우고 Legacy 이름을 되돌리면 이전 홈이다.
 */
class HomeScreen(
    context: Context,
    private val onStart: () -> Unit,
    private val onSettings: () -> Unit,
    private val onCustomize: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private val night = Palette.current.night
    private val roles = DuoTokens.roles(night)
    private val stageColors = V6Tokens.stage(night)

    private val brand = TextView(context)
    private val chip = LinearLayout(context)
    private val gearBtn = IconButton(context)
    private val card = LinearLayout(context)
    private val cardScroll = ScrollView(context)
    private val cardBody = LinearLayout(context)
    private val statChildren = TextView(context)
    private val statStickers = TextView(context)
    private val statRow = LinearLayout(context)
    private val statLine = TextView(context)   // 2026-11-13: 좁을 때 통계 두 칸 대신 쓰는 한 줄 요약(HomeV6Fit)
    private val bodyText = TextView(context)
    private val steps = LinearLayout(context)
    private var lastSummary = HomeSummary(0, 0)
    private val emptyHint = TextView(context)
    private val notes = TextView(context)
    private val startBtn = IconButton(context)
    private val stage = FrameLayout(context)
    private val bubble = TextView(context)
    private val mascot = CrewArtView(context, CrewArt.HOME_MAX_SCALE, bottomAlign = true)
    private val notice = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var leaving = false
    private var motionOn = false
    private var motionStartMs = 0L
    private var motionGate: VehicleGate? = null

    private fun jua() = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
    private fun text(tv: TextView, sp: Float, rgb: Int, display: Boolean = false, bold: Boolean = false) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.setTextColor(Palette.argb(rgb))
        if (display) tv.typeface = jua() else if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.includeFontPadding = true
    }

    init {
        setBackgroundColor(Palette.argb(roles.bg))
        clipChildren = false

        // ---- 머리띠 ----
        brand.text = AppName.NAME
        text(brand, V6Tokens.BRAND_SP, V6Tokens.brand(night), display = true)
        addView(brand, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(V6Tokens.HEADER_DP), Gravity.TOP or Gravity.START).apply { leftMargin = dp(V6Tokens.SCREEN_EDGE_DP) })
        brand.gravity = Gravity.CENTER_VERTICAL

        // 차량 상태 칩: 홈은 '유효한 P + 플랫폼 허용'일 때만 보인다. 그래도 게이트가 PARK_ALLOWED일 때만 칩을 켠다(추정 표시 금지)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setPadding(dp(14), 0, dp(24), 0)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(30).toFloat(); setColor(Palette.argb(roles.surface)); setStroke(dp(2), Palette.argb(roles.surfaceStroke))
        }
        val pBadge = TextView(context).apply {
            text = "P"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Palette.argb(if (night) 0x0B1220 else 0xFFFFFF))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Palette.argb(if (night) 0x60A5FA else 0x1E3A8A)) }
        }
        chip.addView(pBadge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val chipLabel = TextView(context).apply { text = HomeV6Text.CHIP_PARKED; maxLines = 1 }
        text(chipLabel, 24f, roles.text, bold = true)
        chip.addView(chipLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        chip.contentDescription = "차량 상태: ${HomeV6Text.CHIP_PARKED}"
        chip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        chip.visibility = View.GONE
        addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(60), Gravity.TOP or Gravity.END).apply {
            topMargin = dp((V6Tokens.HEADER_DP - 60) / 2); rightMargin = dp(V6Tokens.SCREEN_EDGE_DP + StageUiTokens.SETTINGS_BUTTON_DP + 20)
        })

        val g = dp(StageUiTokens.SETTINGS_BUTTON_DP)
        val gEdge = dp(V6Tokens.GEAR_EDGE_DP)
        val iconPx = dp(StageUiTokens.settingsIconDp())
        val gear = IconDrawable(IconKind.GEAR, Palette.argb(V6Tokens.brand(night)))
        gear.setBounds(0, 0, iconPx, iconPx)
        gearBtn.setPill(Palette.argb(roles.surface), Palette.argb(if (night) 0x0B1220 else 0x1E3A8A), gEdge, dp(4), Palette.argb(V6Tokens.brand(night)))
        gearBtn.text = ""
        gearBtn.contentDescription = StageControlText.SETTINGS_DESC
        gearBtn.stateListAnimator = null
        gearBtn.minWidth = 0; gearBtn.minimumWidth = 0; gearBtn.minHeight = 0; gearBtn.minimumHeight = 0
        gearBtn.setPadding((g - iconPx) / 2, 0, 0, gEdge)
        gearBtn.compoundDrawablePadding = 0
        gearBtn.setCompoundDrawablesRelative(gear, null, null, null)
        gearBtn.setOnClickListener { if (!leaving) onSettings() }
        addView(gearBtn, LayoutParams(g, g + gEdge, Gravity.TOP or Gravity.END).apply {
            topMargin = dp((V6Tokens.HEADER_DP - StageUiTokens.SETTINGS_BUTTON_DP) / 2); rightMargin = dp(V6Tokens.SCREEN_EDGE_DP)
        })

        // ---- 왼쪽 정보 카드 ----
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            cornerRadius = dp(DuoTokens.CARD_RADIUS_DP).toFloat(); setColor(Palette.argb(roles.surface))
            setStroke(dp(DuoTokens.CARD_STROKE_DP), Palette.argb(if (night) 0x475569 else 0xCBD5E1))
        }
        card.setPadding(dp(36), dp(32), dp(36), dp(36))
        cardBody.orientation = LinearLayout.VERTICAL
        val eyebrow = TextView(context).apply { text = HomeV6Text.EYEBROW }
        text(eyebrow, DuoTokens.CAPTION_SP, V6Tokens.eyebrow(night), bold = true)
        cardBody.addView(eyebrow)
        val title = TextView(context).apply { text = HomeV6Text.TITLE; setLineSpacing(0f, 1.1f) }
        text(title, V6Tokens.HERO_TITLE_SP, roles.text, display = true)
        cardBody.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        bodyText.text = HomeV6Text.BODY; bodyText.setLineSpacing(0f, 1.3f)
        text(bodyText, V6Tokens.BODY_SMALL_SP, roles.textMuted)
        cardBody.addView(bodyText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        statRow.orientation = LinearLayout.HORIZONTAL
        statRow.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        statRow.isBaselineAligned = false   // 두 칸 높이를 같게(캡처 비교: 값 글자 크기가 다르면 칸 높이가 어긋났다)
        statRow.addView(statCell(HomeV6Text.STAT_CHILDREN, statChildren), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        statRow.addView(statCell(HomeV6Text.STAT_STICKERS, statStickers), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(16) })
        cardBody.addView(statRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
        text(statLine, V6Tokens.BODY_SMALL_SP, V6Tokens.brand(night), bold = true)
        statLine.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Palette.argb(V6Tokens.surfaceSubtle(night))) }
        statLine.setPadding(dp(20), dp(10), dp(20), dp(10))
        statLine.visibility = View.GONE
        cardBody.addView(statLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        emptyHint.text = HomeV6Text.EMPTY_HINT
        text(emptyHint, DuoTokens.CAPTION_SP, roles.textMuted)
        emptyHint.visibility = View.GONE
        cardBody.addView(emptyHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        // 사용 순서 3단계(D1 C-1 개선, 2026-11-12 캡처 비교: 카드 가운데가 비어 보여 흐름 안내로 채움). 장식 아님 — 화면 읽기도 한 문장으로 읽는다
        steps.orientation = LinearLayout.VERTICAL
        steps.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        steps.contentDescription = "사용 순서: " + HomeV6Text.STEPS.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(", ")
        HomeV6Text.STEPS.forEachIndexed { i, s ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val n = TextView(context).apply {
                text = "${i + 1}"; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); typeface = jua()
                setTextColor(Palette.argb(if (night) 0x0B1220 else 0xFFFFFF))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.argb(V6Tokens.brand(night))) }
            }
            row.addView(n, LinearLayout.LayoutParams(dp(44), dp(44)))
            val l = TextView(context).apply { this.text = s }
            text(l, V6Tokens.BODY_SMALL_SP, roles.text)
            row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(16) })
            steps.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(12) })
        }
        cardBody.addView(steps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })

        // 보호자 알림(미전달 경고·감시 끊김·모의 기어). 없으면 숨김. 주황 머리말 띠가 있는 카드
        text(notes, DuoTokens.CAPTION_SP, roles.text)
        notes.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(Palette.argb(if (night) 0x111827 else 0xFFFFFF)); setStroke(dp(3), Palette.argb(V6Tokens.brand(night)))
        }
        notes.setPadding(dp(16), dp(12), dp(16), dp(12))
        notes.visibility = View.GONE
        cardBody.addView(notes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        cardScroll.isFillViewport = false
        cardScroll.isVerticalScrollBarEnabled = true
        cardScroll.addView(cardBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(cardScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // [시작]: 메인 파랑 + 아래 띠 12dp(누르면 본체가 띠만큼 내려간다). 카드 맨 아래 고정이라 글꼴이 커져도 잘리지 않는다
        val spec = specOf(ButtonKind.PRIMARY_START)
        startBtn.setPill(Palette.argb(roles.primaryFill), Palette.argb(roles.primaryEdge), dp(V6Tokens.HERO_EDGE_DP), 0, 0)
        startBtn.cornerPx = dp(20).toFloat()
        startBtn.text = HomeText.START
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, V6Tokens.BUTTON_SP)
        startBtn.typeface = StageFonts.typeface(context, StageFonts.FontRole.BIG_BUTTON)
        startBtn.isAllCaps = false
        startBtn.setTextColor(Palette.argb(StageButtons.colorOf(spec.textRole, night)))
        startBtn.gravity = Gravity.CENTER
        startBtn.stateListAnimator = null
        startBtn.maxLines = 1
        startBtn.minHeight = dp(V6Tokens.HERO_BUTTON_DP); startBtn.minimumHeight = dp(V6Tokens.HERO_BUTTON_DP)
        startBtn.setPadding(dp(24), 0, dp(24), dp(V6Tokens.HERO_EDGE_DP))
        startBtn.setOnClickListener { if (!leaving) onStart() }
        card.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(V6Tokens.HERO_BUTTON_DP + V6Tokens.HERO_EDGE_DP)).apply { topMargin = dp(20) })
        addView(card, LayoutParams(0, 0, Gravity.TOP or Gravity.START))

        // ---- 오른쪽 캐릭터 무대 ----
        stage.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.argb(stageColors.top), Palette.argb(stageColors.mid), Palette.argb(stageColors.bottom))).apply { cornerRadius = dp(V6Tokens.STAGE_RADIUS_DP).toFloat() }
        stage.clipToOutline = true
        stage.clipChildren = true
        // PM 결정 17(2026-11-13): 장식 구름 2개 제거 — 구름 모양이 아니라 빈 흰 알약(빈 버튼·입력칸)처럼 보였다
        mascot.show(CrewArtAsset.PARKED)
        val desc = if (CustomizeAvailability.ENTRY_VISIBLE) HomeText.CHARACTER_DESC_CUSTOMIZE else HomeText.CHARACTER_DESC
        mascot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        mascot.contentDescription = desc
        if (CustomizeAvailability.ENTRY_VISIBLE) mascot.setOnClickListener { goCustomize() }
        stage.addView(mascot, LayoutParams(CrewArtAsset.PARKED.widthPx, CrewArtAsset.PARKED.heightPx, Gravity.TOP or Gravity.START))
        // 2026-11-14 사용자 지시: 왼쪽 아래 작은 손 흔드는 반신(wave.png 인사 칸)을 없앴다. 무대의 캐릭터는 이 전신 하나뿐이다

        // 2026-11-14 사용자 지시: 말풍선은 무대 카드 폭 대부분·가로 가운데·캐릭터 머리 위(꼬리만 캐릭터 쪽). 크기·위치는 place()
        bubble.text = HomeText.BUBBLE
        bubble.typeface = jua()
        bubble.setTextColor(Palette.argb(stageColors.bubbleText))
        bubble.gravity = Gravity.CENTER
        bubble.includeFontPadding = false
        bubble.setLineSpacing(0f, 1.1f)
        val tail = dp(V6Tokens.HOME_BUBBLE_TAIL_DP)
        val bubbleBg = SpeechBubbleDrawable(stageColors.bubbleFill, stageColors.bubbleStroke, dp(V6Tokens.BUBBLE_STROKE_DP).toFloat(), dp(V6Tokens.BUBBLE_RADIUS_DP).toFloat(), tail.toFloat())
        bubbleBg.tailXFraction = 0.5f
        bubble.background = bubbleBg
        bubble.setPadding(dp(V6Tokens.HOME_BUBBLE_PAD_H_DP), dp(V6Tokens.HOME_BUBBLE_PAD_V_DP), dp(V6Tokens.HOME_BUBBLE_PAD_H_DP), dp(V6Tokens.HOME_BUBBLE_PAD_V_DP) + tail)
        bubble.contentDescription = HomeText.BUBBLE
        stage.addView(bubble, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        addView(stage, LayoutParams(0, 0, Gravity.TOP or Gravity.START))

        // ---- 아래 고지 ----
        notice.text = HomeV6Text.NOTICE
        text(notice, DuoTokens.CAPTION_SP, roles.textMuted)
        notice.gravity = Gravity.CENTER
        notice.maxLines = 2
        notice.ellipsize = TextUtils.TruncateAt.END
        addView(notice, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(V6Tokens.SCREEN_EDGE_DP); rightMargin = dp(V6Tokens.SCREEN_EDGE_DP); bottomMargin = dp(12)
        })
        refreshSummary()
        DuoText.wordBreakTree(this)   // 2026-11-13: 글꼴 2.0 기본 창에서 "눌/러요"처럼 어절 중간 줄바꿈 → 어절 단위(API 33+)
    }

    private fun statCell(label: String, value: TextView): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(14), dp(20), dp(16))
        box.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Palette.argb(V6Tokens.surfaceSubtle(night))) }
        val l = TextView(context).apply { text = label }
        text(l, DuoTokens.CAPTION_SP, roles.textMuted)
        box.addView(l)
        text(value, V6Tokens.STAT_SP, V6Tokens.brand(night), display = true)
        box.addView(value)
        box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return box
    }

    /** 저장된 아이 수·이번 주 스티커를 다시 읽는다(읽기 전용). 실패하면 '아직 없어요'로 둔다(가짜 숫자 금지) */
    private fun refreshSummary() {
        val s = try {
            HomeSummary.of(ProfileStore.load(context), StickerLogStore.load(context), java.time.LocalDate.now().toString())
        } catch (e: Exception) { HomeSummary(0, 0) }
        statChildren.text = HomeV6Text.children(s.children)
        statStickers.text = HomeV6Text.stickers(s.weekStickers)
        // 빈 값('아직 없어요')은 숫자처럼 크게 보이지 않게 보조 글자색·본문 크기로(캡처 비교 개선)
        for ((tv, n) in listOf(statChildren to s.children, statStickers to s.weekStickers)) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (n > 0) V6Tokens.STAT_SP else V6Tokens.BODY_SMALL_SP)
            tv.setTextColor(Palette.argb(if (n > 0) V6Tokens.brand(night) else roles.textMuted))
        }
        statRow.contentDescription = HomeV6Text.statDesc(s)
        lastSummary = s
        statLine.text = HomeV6Fit.statLine(s)
        statLine.contentDescription = HomeV6Text.statDesc(s)
        emptyHint.visibility = if (s.children == 0) View.VISIBLE else View.GONE
        if (width > 0 && height > 0) post { place(width, height) }
    }

    /** 2026-11-13 글꼴 2.0: 카드 내용이 [availH]를 넘으면 HomeV6Fit 순서대로 줄인다. 제목·[시작]·통계 값은 그대로 */
    private fun fitCard(innerW: Int, availH: Int) {
        fun apply(st: List<HomeV6Fit.Step>) {
            steps.visibility = if (HomeV6Fit.Step.HIDE_STEPS in st) View.GONE else View.VISIBLE
            val compact = HomeV6Fit.Step.COMPACT_STATS in st
            statRow.visibility = if (compact) View.GONE else View.VISIBLE
            statLine.visibility = if (compact) View.VISIBLE else View.GONE
            bodyText.visibility = if (HomeV6Fit.Step.HIDE_BODY in st) View.GONE else View.VISIBLE
            emptyHint.visibility = if (lastSummary.children == 0 && HomeV6Fit.Step.HIDE_HINT !in st) View.VISIBLE else View.GONE
        }
        if (innerW <= 0 || availH <= 0) return
        val chosen = HomeV6Fit.plan(availH) { st ->
            apply(st)
            cardBody.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            cardBody.measuredHeight
        }
        apply(chosen)
    }

    fun resetUi() {
        leaving = false
        refreshSummary()
        alpha = 1f
    }

    private fun goCustomize() {
        if (leaving) return
        leaving = true
        onCustomize()
    }

    fun setStyle(@Suppress("UNUSED_PARAMETER") style: CharacterStyle) { /* 공식 원본 PNG는 꾸미기 미지원 */ }

    /**
     * 2026-11-14 루프 r1 K4(개발): 앱 시작 직후 차량 신호(기어 첫 값)를 기다리는 동안 [시작]을 비활성으로 둔다.
     * 이유 글("차량 신호 확인 중")은 setNotes로 함께 보인다(색만으로 구분하지 않음). 누름 게이트는 MainActivity.homeTapAllowed가 그대로 막는다
     */
    fun setStartWaiting(waiting: Boolean) {
        // 루프 r2 L5: isEnabled를 끄면 누름이 아예 안 와 로그가 남지 않았다 → 눌림은 받고(MainActivity.homeTapAllowed가 무시·로그) 보이기만 흐리게
        val a = if (waiting) 0.5f else 1f
        if (startBtn.alpha == a) return
        startBtn.alpha = a
        startBtn.stateDescription = if (waiting) StartupHomeRule.NOTE_CHECKING else null
    }

    fun setNotes(text: String?) {
        if (text.isNullOrEmpty()) { notes.visibility = View.GONE; notes.text = ""; return }
        if (notes.text.toString() != text) { notes.text = text; notes.contentDescription = text }
        notes.visibility = View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        post { place(w, h) }
    }

    private fun place(w: Int, h: Int) {
        notice.measure(MeasureSpec.makeMeasureSpec(w - dp(2 * V6Tokens.SCREEN_EDGE_DP), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        val plan = HomeV6Layout.plan(w, h, density, notice.measuredHeight + dp(12))
        (card.layoutParams as LayoutParams).apply { leftMargin = plan.card.left; topMargin = plan.card.top; width = plan.card.width; height = plan.card.height }
        // 카드 안 내용 칸 = 카드 − 위아래 여백 − [시작](띠 포함) − 그 위 여백
        fitCard(plan.card.width - card.paddingLeft - card.paddingRight,
            plan.card.height - card.paddingTop - card.paddingBottom - dp(V6Tokens.HERO_BUTTON_DP + V6Tokens.HERO_EDGE_DP) - dp(20))
        card.requestLayout()
        (stage.layoutParams as LayoutParams).apply { leftMargin = plan.stage.left; topMargin = plan.stage.top; width = plan.stage.width; height = plan.stage.height }
        stage.requestLayout()

        // 말풍선(위) + 캐릭터(아래). 말풍선 폭 = 카드 폭 − 좌우 여백, 글자는 그 폭에 맞춰 키운다(상한 56sp × 글꼴 배율 1.3까지, 하한 36dp).
        // 캐릭터는 말풍선·틈을 뺀 높이에 맞춘다(확대 없음). 꼬리 끝과 그림 윗변 사이에 틈을 둬 닿지 않는다(HomeV6Layout.stagePlan)
        val sw = plan.stage.width; val sh = plan.stage.height
        val bw = HomeV6Layout.bubbleWidth(sw, density)
        val avail = (bw - bubble.paddingLeft - bubble.paddingRight).toFloat()
        val fs = resources.configuration.fontScale.coerceAtMost(V6Tokens.BUBBLE_MAX_FONT_SCALE)
        val minPx = V6Tokens.BUBBLE_SP * density
        val maxPx = V6Tokens.HOME_BUBBLE_MAX_SP * density * fs
        val one = HomeText.BUBBLE
        val two = HomeText.twoLineBubble()
        val probe = android.text.TextPaint(bubble.paint).apply { textSize = 100f }
        val em1 = probe.measureText(one) / 100f
        val em2 = two.split('\n').maxOf { probe.measureText(it) } / 100f
        val px1 = HomeV6Layout.bubbleTextPx(em1, avail, minPx, maxPx)
        val px2 = HomeV6Layout.bubbleTextPx(em2, avail, minPx, maxPx)
        fun heightOf(t: String, px: Float): Int {
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            if (bubble.text?.toString() != t) bubble.text = t
            bubble.measure(MeasureSpec.makeMeasureSpec(bw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return bubble.measuredHeight
        }
        val sp1 = HomeV6Layout.stagePlan(sw, sh, density, heightOf(one, px1))
        val sp2 = HomeV6Layout.stagePlan(sw, sh, density, heightOf(two, px2))
        val twoLines = HomeV6Layout.useTwoLines(HomeV6Layout.fitsLine(em1, px1, avail), px1, px2, sp1.artScale, sp2.artScale)
        val sp = if (twoLines) sp2 else sp1
        if (!twoLines) heightOf(one, px1)   // 마지막으로 잰 것이 두 줄이므로 한 줄 값으로 되돌린다
        stagePlan = sp
        (mascot.layoutParams as LayoutParams).apply { width = sp.art.width; height = sp.art.height; leftMargin = sp.art.left; topMargin = sp.art.top }
        mascot.requestLayout()
        (bubble.layoutParams as LayoutParams).apply { width = sp.bubble.width; height = sp.bubble.height; leftMargin = sp.bubble.left; topMargin = sp.bubble.top }
        (bubble.background as? SpeechBubbleDrawable)?.tailXFraction = sp.tailXFraction
        bubble.requestLayout()
    }

    private var stagePlan: HomeV6Layout.StagePlan? = null

    /**
     * 실측 점검용(debug 미리보기가 캡처와 함께 저장): 무대 카드 안 말풍선·캐릭터의 실제 View 경계로 겹침·틈·여백을 적는다.
     * 캐릭터 상자는 그림이 실제로 그려지는 영역(CrewArtView 배율 반영)이다. 좌표는 무대 카드 기준 px.
     */
    fun layoutReport(): String {
        val sb = StringBuilder()
        val sw = stage.width; val sh = stage.height
        sb.append("HOME stage ${sw}x${sh}px density=$density fontScale=${resources.configuration.fontScale}\n")
        val bl = bubble.left; val bt = bubble.top; val br = bubble.right; val bb = bubble.bottom
        val a = mascot.asset
        val s = mascot.drawnScale
        val dw = (a?.widthPx ?: 0) * s; val dh = (a?.heightPx ?: 0) * s
        val al = mascot.left + (mascot.width - dw) / 2f; val at = mascot.bottom - dh
        val ar = al + dw; val ab = at + dh
        sb.append("BUBBLE rect=$bl,$bt,$br,$bb (${br - bl}x${bb - bt}px, 꼬리 포함) widthShare=${"%.2f".format(if (sw > 0) (br - bl).toFloat() / sw else 0f)}\n")
        sb.append("BUBBLE margins left=$bl right=${sw - br} top=$bt centerOffset=${(bl + br) / 2 - sw / 2}\n")
        val lay = bubble.layout
        val ell = if (lay == null) 0 else (0 until lay.lineCount).count { lay.getEllipsisCount(it) > 0 }
        val innerH = bubble.height - bubble.paddingTop - bubble.paddingBottom
        val innerW = bubble.width - bubble.paddingLeft - bubble.paddingRight
        val widest = if (lay == null) 0f else (0 until lay.lineCount).maxOf { lay.getLineWidth(it) }
        sb.append("BUBBLE text=${"%.0f".format(bubble.textSize)}px lines=${lay?.lineCount ?: 0} ellipsis=$ell textH=${lay?.height ?: 0}px innerH=${innerH}px textW=${"%.0f".format(widest)}px innerW=${innerW}px\n")
        sb.append("ART rect=${"%.0f".format(al)},${"%.0f".format(at)},${"%.0f".format(ar)},${"%.0f".format(ab)} scale=${"%.3f".format(s)} asset=${a?.key}\n")
        val ix = minOf(br.toFloat(), ar) - maxOf(bl.toFloat(), al); val iy = minOf(bb.toFloat(), ab) - maxOf(bt.toFloat(), at)
        val overlap = ix > 0f && iy > 0f
        sb.append("GAP bubbleBottom(꼬리 끝)→artTop=${"%.0f".format(at - bb)}px (${"%.0f".format((at - bb) / density)}dp) tailX=${stagePlan?.tailX} artX=${"%.0f".format(al)}..${"%.0f".format(ar)}\n")
        val outside = bl < 0 || bt < 0 || br > sw || bb > sh || al < 0f || at < 0f || ar > sw || ab > sh
        val textClip = lay != null && (lay.height > innerH + 1 || widest > innerW + 1 || ell > 0)
        val artViews = (0 until stage.childCount).count { stage.getChildAt(it) is CrewArtView && stage.getChildAt(it).visibility == View.VISIBLE }
        sb.append("SUMMARY bubbleArtOverlap=${if (overlap) 1 else 0} touching=${if (!overlap && at - bb < 1f) 1 else 0} outsideStage=${if (outside) 1 else 0} textClip=${if (textClip) 1 else 0} characters=$artViews\n")
        return sb.toString()
    }

    // ---- 숨쉬기(캐릭터·말풍선이 함께 정수 px로 아주 조금) ----
    private val tick = object : Runnable {
        override fun run() {
            if (!motionOn) return
            val now = SystemClock.uptimeMillis()
            val dy = if (android.animation.ValueAnimator.areAnimatorsEnabled())
                -breathUnit(now - motionStartMs) * dp(CharacterMotion.BREATH_AMP_DP.toInt()) else 0f
            val iy = Math.round(dy).toFloat()
            mascot.translationY = iy
            bubble.translationY = iy
            handler.postDelayed(this, HomeLayout.FRAME_MS)
        }
    }
    private fun startMotion() {
        if (motionOn) return
        // 손 인사 그림(wave)은 없앴다(2026-11-14). 인사는 말풍선·음성이 맡고, 여기서는 숨쉬기만 한다(alreadyGreeted = true)
        val d = HomeMotionPolicy.decide(motionGate, android.animation.ValueAnimator.areAnimatorsEnabled(),
            visibility == View.VISIBLE && isAttachedToWindow, alreadyGreeted = true)
        if (d.motion == HomeMotion.STATIC) return
        motionOn = true
        motionStartMs = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun stopMotion() {
        motionOn = false
        handler.removeCallbacks(tick)
        mascot.translationY = 0f
        bubble.translationY = 0f
    }

    /** 차량·정책 게이트. PARK_ALLOWED일 때만 상태 칩과 움직임을 켠다. 그 밖에는 칩을 숨기고 바로 멈춘다 */
    fun setMotionGate(gate: VehicleGate) {
        chip.visibility = if (gate == VehicleGate.PARK_ALLOWED) View.VISIBLE else View.GONE
        if (motionGate == gate) return
        motionGate = gate
        if (gate != VehicleGate.PARK_ALLOWED) stopMotion() else startMotion()
    }

    fun setMotionAllowed(allowed: Boolean) = setMotionGate(if (allowed) VehicleGate.PARK_ALLOWED else VehicleGate.RESTRICTED)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            if (visibility == View.VISIBLE && isAttachedToWindow) { refreshSummary(); startMotion() } else stopMotion()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) startMotion()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }
}
