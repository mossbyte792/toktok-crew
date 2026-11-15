package com.crewguide.probe

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UI/UX v2 U4 — 스티커 획득 순간(표시 전용).
 * - 기록(지급)은 기존 StickerLogs/TripSession 규칙이 이미 끝낸 뒤의 상태(StageUiState.stickerShown·stickerKidCount)를 **읽기만** 한다.
 *   이 뷰는 아무것도 저장하지 않으므로 재생성·재시작·음성 실패가 지급 횟수에 영향을 주지 않는다.
 * - 움직임은 '보이기 시작한 순간' 한 번만(작게 튀어 오름 0.6→1.0, 350ms). 시스템 애니메이션이 꺼져 있으면 바로 최종 모습.
 * - 문구에 안전·착용 단정 표현을 쓰지 않는다. 보상은 '참여' 기준이며 보호자 보고 기준임을 함께 적는다.
 */
object StickerMoment {
    enum class Phase { HIDDEN, EARNED, SAVE_FAILED }

    fun phaseOf(sceneIsYes: Boolean, shown: Boolean, kidCount: Int, recordFailed: Boolean): Phase = when {
        !sceneIsYes || !shown -> Phase.HIDDEN
        recordFailed -> Phase.SAVE_FAILED
        else -> Phase.EARNED
    }

    /** v3(2026-11-11): 이번 이동에 이미 스티커를 기록해 새로 주지 않은 경우의 설명(이전엔 '아이를 등록하면…'으로 잘못 보였다) */
    const val ALREADY_THIS_TRIP = "이번 이동의 스티커는 이미 기록했어요"

    fun title(p: Phase, kidCount: Int, alreadyThisTrip: Boolean = false): String = when (p) {
        Phase.HIDDEN -> ""
        Phase.EARNED -> if (kidCount > 0) "참여 스티커 +1" else "오늘도 함께했어요"
        Phase.SAVE_FAILED -> "참여 스티커"
    }

    fun detail(p: Phase, kidCount: Int, alreadyThisTrip: Boolean = false): String = when (p) {
        Phase.HIDDEN -> ""
        Phase.EARNED -> when {
            kidCount > 0 -> "아이 ${kidCount}명 · 이번 주 스티커에 기록했어요"
            alreadyThisTrip -> ALREADY_THIS_TRIP
            else -> "아이를 등록하면 스티커가 기록돼요"
        }
        Phase.SAVE_FAILED -> "기록을 저장하지 못했어요. 다시 [예]를 누르면 다시 저장해요"
    }

    /**
     * v3 반복 4: 화면에 보일 '이번에 +1 받은 아이 수'. 기록을 실제로 한 '예' 보고(질문 세대 [grantGen])와
     * 지금의 '예' 보고 세대 [yesGen]가 같을 때만 [kids]를 보인다. 같은 이동의 두 번째 '예'는 기록하지 않으므로 0
     * (→ 이미 기록 설명으로 바뀜). 이전에는 첫 '예'의 아이 수가 메모리에 남아 두 번째에도 "+1 기록했어요"가 보였다.
     */
    fun displayKids(yesGen: Int, grantGen: Int, kids: Int): Int = if (yesGen >= 0 && yesGen == grantGen) kids else 0

    /** 움직임은 숨김 → 보임으로 바뀌는 순간에만 */
    fun shouldAnimate(wasVisible: Boolean, nowVisible: Boolean, animatorsEnabled: Boolean): Boolean =
        !wasVisible && nowVisible && animatorsEnabled

    const val POP_FROM = 0.6f
    const val POP_MS = 350L

    /** v4 배치·크기(2026-11-12): 화면 왼쪽 위. 위 여백은 단계 점·상단 버튼 줄(76dp) 아래 */
    const val EDGE_DP = 24
    const val TOP_DP = 112
    /** 원형 스티커 지름(v3 88dp → v4 128dp). 움직임은 기존 한 번 튀어 오름 그대로(반복·깜빡임 없음) */
    const val DISC_DP = 128
    /** 배지 테두리(v3 3dp → v4 6dp, 색은 기존 stickerStroke) */
    const val RING_DP = 6
}

/** 둥근 스티커(코드 도형, 외부 이미지 없음) + 제목·설명 두 줄 */
class StickerBadgeView(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val disc = object : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f
            val sc = Palette.current
            fill.color = Palette.argb(sc.stickerFill)
            ring.color = Palette.argb(sc.stickerStroke); ring.strokeWidth = 4 * d
            c.drawCircle(width / 2f, height / 2f, r - 3 * d, fill)
            c.drawCircle(width / 2f, height / 2f, r - 3 * d, ring)
            ink.color = Palette.argb(0x111111); ink.textSize = r * 0.62f
            val fm = ink.fontMetrics
            c.drawText("+1", width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, ink)
        }
    }
    private val titleView = TextView(context)
    private val detailView = TextView(context)
    private var visibleNow = false
    var phase = StickerMoment.Phase.HIDDEN
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        val r = DuoTokens.roles(Palette.current.night)
        background = DuoDraw.round(r.surface, DuoTokens.CARD_RADIUS_DP * d, (StickerMoment.RING_DP * d).toInt(), Palette.current.stickerStroke)
        val p = (24 * d).toInt()
        setPadding(p, (20 * d).toInt(), (32 * d).toInt(), (20 * d).toInt())
        addView(disc, LayoutParams((StickerMoment.DISC_DP * d).toInt(), (StickerMoment.DISC_DP * d).toInt()).apply { rightMargin = (24 * d).toInt() })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        titleView.textSize = DuoTokens.TITLE_SP; titleView.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE); titleView.setTextColor(Palette.argb(r.text))
        detailView.textSize = DuoTokens.CAPTION_SP; detailView.setTextColor(Palette.argb(r.textMuted))
        // v4: 배지가 말풍선 쪽으로 넓어지지 않게 설명 폭을 제한(긴 설명은 두 줄)
        detailView.maxWidth = (420 * d).toInt()
        col.addView(titleView); col.addView(detailView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        addView(col)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(p: StickerMoment.Phase, kidCount: Int, alreadyThisTrip: Boolean = false) {
        phase = p
        val show = p != StickerMoment.Phase.HIDDEN
        if (show) {
            titleView.text = StickerMoment.title(p, kidCount, alreadyThisTrip)
            detailView.text = StickerMoment.detail(p, kidCount, alreadyThisTrip)
            disc.visibility = if (p == StickerMoment.Phase.EARNED && kidCount > 0) VISIBLE else GONE
            contentDescription = "${titleView.text}. ${detailView.text}"
        }
        val animate = StickerMoment.shouldAnimate(visibleNow, show, ValueAnimator.areAnimatorsEnabled())
        visibleNow = show
        visibility = if (show) VISIBLE else GONE
        animate().cancel()
        if (animate) {
            scaleX = StickerMoment.POP_FROM; scaleY = StickerMoment.POP_FROM; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(StickerMoment.POP_MS).setInterpolator(OvershootInterpolator(2f)).start()
            announceForAccessibility(contentDescription)
        } else {
            scaleX = 1f; scaleY = 1f; alpha = 1f
        }
    }
}
