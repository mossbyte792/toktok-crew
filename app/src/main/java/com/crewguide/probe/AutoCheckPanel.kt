package com.crewguide.probe

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 2026-11-13 디자인 담당: 자동 확인·출발·도착·하차 장면의 왼쪽 정보 패널(S03 대체·S08 하차).
 * 화면은 [AutoCheckUi](PM 계약)를 읽기만 한다. 판정·상태를 바꾸지 않고, 보호자 확인 대체 버튼은 [StageActions.onGuardianConfirmSeat]만 부른다.
 * 문구 원칙: 신호 기준으로만 말한다. 안전·완료·착용을 보증하는 표현은 쓰지 않는다(AutoCheckPanelTest 금지어 검사).
 */
object AutoCheckText {
    const val TITLE_CHECK = "좌석별 신호"
    const val OCC_SEATED = "앉음"
    const val OCC_EMPTY = "빈 자리"
    const val OCC_UNKNOWN = "확인 불가"
    const val NOT_SELECTED = "선택 안 함"
    const val GUARDIAN_BUTTON = "보호자 확인"
    const val MOCK_SIGNAL = "모의 신호"
    const val MOCK_NAVI = "모의 내비"
    const val EXTRA_OCCUPIED = "다른 자리에도 누가 앉았어요"
    const val V_CONFIRMED = "신호 받음"
    const val V_WAIT = "벨트 기다림"
    const val V_EXCLUDED = "확인 제외"
    const val V_GUARDIAN_DONE = "보호자 확인함"
    /** 접근성 이름 전용(화면에서는 버튼이 대신한다) */
    const val V_NEEDS = "신호 확인 불가, 보호자 확인 필요"

    /** 진행 단계 5칸. '완료'는 금지어 목록에 있어 쓰지 않는다(확인 끝 = 출발 준비) */
    val PHASES = listOf("확인 중", "출발 준비", "출발", "도착", "하차")

    const val DEPART_TITLE = "출발 준비됐어요"
    fun eta(min: Int): String = "도착까지 약 ${min}분"
    const val ARRIVED_TITLE = "도착했어요"
    const val ARRIVED_NOTE = "내리기 전에 뒷좌석을 한 번 더 봐 주세요"
    const val ARRIVED_SEATS = "착석 신호"
    val ALL_LEFT_TITLE: String = SeatBoardRules.TITLE_ALL_LEFT   // r10d A: "모두 내린 것 같아요"(스위치 끔이면 결정 38), SeatBoardRules.TITLE_ALL_LEFT와 같은 말
    const val ALL_LEFT_BASIS = SeatBoardRules.BASIS_ALL_LEFT
    /** r6(계약 v6 exitByDoor): 문 열림 신호도 쓴 하차 판단 */
    const val ALL_LEFT_BASIS_DOOR = SeatBoardRules.BASIS_ALL_LEFT_DOOR
    const val ALL_LEFT_NOTE = "뒷좌석을 한 번 더 둘러봐 주세요"
    const val STICKER_GOT = "오늘 칸에 별이 생겼어요"
    const val STICKER_EARNED = "이번 이동 스티커는 받았어요"

    /** 금지어 검사용 전체 문구 */
    fun all(): List<String> = listOf(
        TITLE_CHECK, OCC_SEATED, OCC_EMPTY, OCC_UNKNOWN, NOT_SELECTED, GUARDIAN_BUTTON, MOCK_SIGNAL, MOCK_NAVI, EXTRA_OCCUPIED,
        V_CONFIRMED, V_WAIT, V_EXCLUDED, V_GUARDIAN_DONE, V_NEEDS, DEPART_TITLE, eta(25), ARRIVED_TITLE, ARRIVED_NOTE, ARRIVED_SEATS,
        ALL_LEFT_TITLE, ALL_LEFT_BASIS, ALL_LEFT_BASIS_DOOR, ALL_LEFT_NOTE, STICKER_GOT, STICKER_EARNED,
    ) + PHASES + AlightScreenText.all()
}

/** 칩 한 개의 표시 값(순수): 기호 + 글자 + 색. 색만으로 구분하지 않는다 */
data class AutoChip(val glyph: String, val label: String, val colors: StatusChipSpec.Colors) {
    val text: String get() = "$glyph $label"
}

/** 자동 확인 패널 규칙(순수, Android 클래스 없음). 단위 테스트 대상 */
object AutoCheckPanelRules {
    // 크기(dp/sp): 상태 칩이 패널에서 가장 큰 글자(3초 안에 읽기), 좌석 이름·머리말은 보조
    const val STATUS_SP = 28f
    const val NAME_SP = 24f
    const val HEAD_SP = 24f
    const val TITLE_SP = DuoTokens.DISPLAY_SP   // 출발·도착·하차 제목(52)
    const val ETA_SP = DuoTokens.TITLE_SP       // 남은 시간(40)
    const val ROW_PAD_DP = 6
    const val ROW_GAP_DP = 8
    const val ROW_RADIUS_DP = 20
    const val SELECTED_STROKE_DP = 3
    const val CHIP_GAP_DP = 12
    // 열 맞춤(글꼴 1.0 기준 최소 폭). 넘치면 다음 줄로 감긴다(FlowRow)
    const val NAME_COL_DP = 168
    const val OCC_COL_DP = 176
    const val BELT_COL_DP = 224
    const val GUARDIAN_BUTTON_DP = 76
    /** 좌석이 이만큼 많으면(3열 등) 줄 위아래 여백·간격을 줄여 1920x720 카드 안에 더 들게 한다. 그래도 넘치면 스크롤 막대가 보인다 */
    /** 진행 단계 칸 최소 높이(표시 전용, 터치 없음). 글꼴이 커지면 칸이 글자에 맞춰 커진다(고정 48dp였을 때 2.0에서 잘림) */
    const val STEP_MIN_DP = 48
    const val COMPACT_ROWS = 6
    const val COMPACT_ROW_PAD_DP = 3
    const val COMPACT_ROW_GAP_DP = 6
    fun rowPadDp(rowCount: Int): Int = if (rowCount >= COMPACT_ROWS) COMPACT_ROW_PAD_DP else ROW_PAD_DP
    fun rowGapDp(rowCount: Int): Int = if (rowCount >= COMPACT_ROWS) COMPACT_ROW_GAP_DP else ROW_GAP_DP

    /** 왼쪽 패널을 쓰는 장면. 잠금(S09)에는 어떤 패널도 없다 */
    fun panelScene(scene: Scene): Boolean = when (scene) {
        Scene.AUTO_CHECK, Scene.DEPART_READY, Scene.ARRIVED, Scene.ALL_LEFT -> true
        Scene.PICK, Scene.READY, Scene.ANNOUNCE, Scene.QUESTION, Scene.YES, Scene.WAIT_GUARDIAN,
        Scene.LOCKED, Scene.TRIP_END, Scene.EXIT_CHECK -> false
    }

    /** 패널을 실제로 보일지: 패널 장면이고 자동 흐름 상태가 있을 때만 */
    fun panelVisible(scene: Scene, ac: AutoCheckUi?): Boolean = ac != null && panelScene(scene)

    fun occChip(occ: SeatOcc, night: Boolean): AutoChip = when (occ) {
        SeatOcc.OCCUPIED -> AutoChip("◉", AutoCheckText.OCC_SEATED, DuoTokens.infoChip(night))
        SeatOcc.EMPTY -> StatusChipSpec.colors(StatusChipSpec.State.NO_SIGNAL, night).let {
            AutoChip("○", AutoCheckText.OCC_EMPTY, StatusChipSpec.Colors(it.fg, it.bg, it.stroke, false))   // 빈 자리는 받은 신호라 실선
        }
        SeatOcc.UNKNOWN -> AutoChip("?", AutoCheckText.OCC_UNKNOWN, StatusChipSpec.colors(StatusChipSpec.State.UNAVAILABLE, night))
    }

    fun beltChip(belt: SeatBeltRef, night: Boolean): AutoChip {
        val s = StatusChipSpec.of(belt)
        return AutoChip(s.glyph, s.label, StatusChipSpec.colors(s, night))
    }

    /** 판정 칩. NEEDS_GUARDIAN은 칩 대신 [보호자 확인] 버튼을 그리므로 null */
    fun verdictChip(v: SeatVerdict, night: Boolean): AutoChip? = when (v) {
        SeatVerdict.CONFIRMED -> AutoChip("●", AutoCheckText.V_CONFIRMED, StatusChipSpec.colors(StatusChipSpec.State.BUCKLED, night))
        SeatVerdict.WAIT_BUCKLE -> AutoChip("!", AutoCheckText.V_WAIT, StatusChipSpec.colors(StatusChipSpec.State.UNBUCKLED, night))
        SeatVerdict.EMPTY_EXCLUDED -> AutoChip("–", AutoCheckText.V_EXCLUDED, StatusChipSpec.colors(StatusChipSpec.State.NO_SIGNAL, night))
        SeatVerdict.GUARDIAN_CONFIRMED -> AutoChip("◆", AutoCheckText.V_GUARDIAN_DONE, DuoTokens.infoChip(night))
        SeatVerdict.NEEDS_GUARDIAN -> null
    }

    /**
     * 비선택 좌석 칩: 비활성 짝(글자 대비 4.5 이상, 알파 흐림 금지). 기호·글자는 그대로라 구분된다.
     * 윤곽은 disabledStroke(비활성 바탕 위 2.34)가 3:1에 못 미쳐 카드 윤곽색(surfaceStroke, 낮 4.34 / 밤 6.0 이상)을 쓴다
     */
    fun muted(c: AutoChip, night: Boolean): AutoChip {
        val r = DuoTokens.roles(night)
        return c.copy(colors = StatusChipSpec.Colors(r.disabledText, r.disabledFill, r.surfaceStroke, c.colors.dashed))
    }

    /** 비선택 좌석이라도 '앉음'은 원래 색을 유지한다(선택 안 한 자리에 누가 앉았음을 놓치지 않게) */
    fun occChipFor(row: SeatCheckRow, night: Boolean): AutoChip {
        val c = occChip(row.occ, night)
        return if (row.selected || row.occ == SeatOcc.OCCUPIED) c else muted(c, night)
    }

    fun beltChipFor(row: SeatCheckRow, night: Boolean): AutoChip {
        val c = beltChip(row.belt, night)
        return if (row.selected) c else muted(c, night)
    }

    fun showGuardianButton(row: SeatCheckRow): Boolean =
        row.selected && (row.verdict == SeatVerdict.NEEDS_GUARDIAN ||
            // r8b(계약 v8): 미체결 자리 보호자 확인(체결 신호가 오면 열지 않는다)
            (row.guardianForUnbuckled && row.verdict == SeatVerdict.WAIT_BUCKLE && row.belt != SeatBeltRef.BUCKLED_SIGNAL))

    /** 보호자 확인 버튼 읽기 이름(r8b: 미체결 자리는 이유를 다르게) */
    fun guardianButtonDesc(row: SeatCheckRow): String = "${row.label} ${AutoCheckText.GUARDIAN_BUTTON}. " +
        if (row.guardianForUnbuckled && row.verdict == SeatVerdict.WAIT_BUCKLE) "벨트 체결 신호가 없어 보호자가 직접 보고 누르는 버튼이에요"
        else "신호를 확인할 수 없어 보호자가 직접 보고 누르는 버튼이에요"

    /** 머리 배지 '모의 신호': 앱 내부 모의 값이 하나라도 있으면 */
    fun headMock(ac: AutoCheckUi): Boolean = ac.anyMock || ac.rows.any { it.mock }

    /** 줄마다 '모의 신호' 표시: 일부 좌석만 모의일 때만(전부 모의면 머리 배지 하나로 충분) */
    fun rowMock(ac: AutoCheckUi, row: SeatCheckRow): Boolean = row.mock && !ac.rows.all { it.mock }

    fun extraLine(ac: AutoCheckUi): String? =
        if (ac.extraOccupied.isEmpty()) null else "${AutoCheckText.EXTRA_OCCUPIED}: ${ac.extraOccupied.joinToString(", ")}"

    /** 남은 시간 문구. 시간 값이 없으면 null(숫자를 지어내지 않는다) */
    fun etaText(eta: TripEta?, useRemain: Boolean = false): String? {
        val m = if (useRemain) eta?.remainMin else eta?.totalMin
        return if (m == null || m < 0) null else AutoCheckText.eta(m)
    }

    fun naviMock(eta: TripEta?): Boolean = eta?.source == SignalSource.MOCK

    /** 진행 단계 칸 인덱스(0..4). 주행 중(DRIVING)은 잠금이라 표시하지 않지만 값은 '출발'로 둔다 */
    fun phaseIndex(p: AutoPhase): Int = when (p) {
        AutoPhase.CHECKING -> 0
        AutoPhase.CONFIRMED -> 1
        AutoPhase.DEPARTING, AutoPhase.DRIVING -> 2
        AutoPhase.ARRIVED -> 3
        AutoPhase.EXITING, AutoPhase.ALL_LEFT -> 4
    }

    /** r5: 도착 질문 중·'아니요'(잠깐 멈춤) 뒤 정차의 칩 글자('출발' 칸 자리에 보임, 도착 칸은 아직 켜지 않음) */
    const val STEP_STOPOVER = "정차"

    /**
     * 칩 5칸 글자. 정차(stopover)면 '출발' 칸을 [STEP_STOPOVER]로 바꾼다.
     * r6 중간 하차(alighting, 계약 v6 alightingByAnswer)면 '도착' 칸을 [AlightScreenText.STEP_STOP]('정차')로 바꾼다 → "출발 · 정차 · ▶ 하차"('도착' 글자가 없다)
     */
    fun stepNames(stopover: Boolean, alighting: Boolean = false): List<String> = when {
        alighting -> AutoCheckText.PHASES.mapIndexed { i, n -> if (i == phaseIndex(AutoPhase.ARRIVED)) AlightScreenText.STEP_STOP else n }
        stopover -> AutoCheckText.PHASES.mapIndexed { i, n -> if (i == phaseIndex(AutoPhase.DRIVING)) STEP_STOPOVER else n }
        else -> AutoCheckText.PHASES
    }

    /** r6: 하차 마무리 기준 글자(계약 v6 exitByDoor). 좌석 화면과 같은 규칙([SeatBoardRules.allLeftBasis]) */
    fun allLeftBasis(ac: AutoCheckUi): String = SeatBoardRules.allLeftBasisOf(ac)   // r7: 보호자 끝냄 "(보호자 확인)"이 먼저

    /**
     * r6: 목록 패널(좌석 배치를 알 수 없는 차량의 대신 화면) 도착 장면 제목. 좌석 화면과 같은 우선순서:
     * "내릴까요?" 질문 → 도착 질문 → 중간 하차("여기서 내려요", '도착' 없음) → 잠깐 멈춤 → "도착했어요".
     * 목록 패널에는 두 대답 버튼이 없다(음성 대답·[이번 이동 끝내기]로 진행). 질문 문장은 제목으로만 보인다
     */
    fun arrivedTitle(ac: AutoCheckUi): String = when {
        ac.askAlight -> AlightScreenText.question(ac)
        ac.askArrived -> ArrivalAskText.QUESTION
        ac.alightingByAnswer -> AlightScreenText.TITLE
        ac.pausedByAnswer -> ArrivalAskText.PAUSED_TITLE
        else -> AutoCheckText.ARRIVED_TITLE
    }

    /** r6: 목록 패널 도착 장면 안내 한 줄(제목과 짝) */
    fun arrivedNote(ac: AutoCheckUi): String = when {
        ac.askAlight -> AlightScreenText.SUB
        ac.askArrived -> ArrivalAskText.SUB
        ac.alightingByAnswer -> AlightScreenText.SUB_ALIGHTING
        ac.pausedByAnswer -> ArrivalAskText.PAUSED_SUB
        else -> AutoCheckText.ARRIVED_NOTE
    }

    /** 단계 표시 여부: 자동 흐름 상태가 있고 패널 장면일 때만(잠금 S09에서는 숨김: 새 정보 추가 금지) */
    fun stepsVisible(scene: Scene, ac: AutoCheckUi?): Boolean = panelVisible(scene, ac)

    /** 확인 중일 때만 현재 칸에 작은 움직임(시스템 애니메이션이 꺼져 있으면 호출 측이 멈춘다) */
    fun stepsAnimate(ac: AutoCheckUi?): Boolean = ac?.phase == AutoPhase.CHECKING

    enum class StepState { DONE, CURRENT, TODO }
    fun stepState(i: Int, current: Int): StepState = when {
        i < current -> StepState.DONE
        i == current -> StepState.CURRENT
        else -> StepState.TODO
    }
    fun stepGlyph(s: StepState): String = when (s) { StepState.DONE -> "●"; StepState.CURRENT -> "▶"; StepState.TODO -> "○" }

    /** 글꼴이 이만큼 커지면 단계 표시를 '현재 칸 글자 + 점 5개'로 줄인다(상단 바 폭 안에 들게) */
    const val STEPS_COMPACT_FONT = 1.5f

    /**
     * 하차 장면: 아직 착석 신호가 있어 강조할 좌석 이름.
     * PM 결정 11(2026-11-13): 운전석([SeatCheckRule.DRIVER_SEAT])은 **선택하지 않았으면 뺀다**(도착 때 운전자는 늘 앉아 있어 강조가 소음. 결정 3의 extraOccupied와 같은 기준).
     * 운전석을 아이 자리로 골랐으면 넣는다. 그 밖의 선택하지 않은 좌석(아무도 고르지 않은 뒷좌석 등)은 아이가 앉아 있을 수 있어 그대로 강조한다.
     * 칩("◉ 앉음")은 신호 그대로 보이고, 이 규칙은 줄 강조(파란 테두리)에만 쓴다.
     */
    fun isStillSeated(row: SeatCheckRow): Boolean =
        row.occ == SeatOcc.OCCUPIED && (row.selected || row.seat != SeatCheckRule.DRIVER_SEAT)

    fun stillSeated(ac: AutoCheckUi): List<String> = ac.rows.filter { isStillSeated(it) }.map { it.label }

    /** 스티커 획득 연출을 지금 시작할지: 하차 끝 장면 + 이번 연출 요청 + 아직 이 장면에서 보여 주지 않음 */
    fun stickerAnimate(scene: Scene, ac: AutoCheckUi?, alreadyPlayed: Boolean): Boolean =
        scene == Scene.ALL_LEFT && ac?.stickerJustGranted == true && !alreadyPlayed

    /** 접근성 한 줄: "뒷줄 왼쪽, 선택함, 앉음, 체결 신호, 신호 받음" */
    fun rowA11y(row: SeatCheckRow): String {
        val parts = ArrayList<String>()
        parts += row.label
        parts += if (row.selected) "확인 대상" else AutoCheckText.NOT_SELECTED
        parts += occChip(row.occ, false).label
        parts += StatusChipSpec.of(row.belt).label
        row.verdict?.let { v -> parts += verdictChip(v, false)?.label ?: AutoCheckText.V_NEEDS }
        if (row.mock) parts += AutoCheckText.MOCK_SIGNAL
        return parts.joinToString(", ")
    }

    /** 다시 그릴지 판단용 서명(값이 같으면 View를 다시 만들지 않는다) */
    fun signature(scene: Scene, ac: AutoCheckUi, night: Boolean, fontScale: Float, cells: List<BoardCell>): String =
        "$scene|$night|$fontScale|${ac.phase}|${ac.rows}|${ac.extraOccupied}|${ac.eta}|${ac.stickerEarned}|${ac.anyMock}|$cells" +
            "|${ac.askArrived}|${ac.pausedByAnswer}|${ac.askAlight}|${ac.askAlightText}|${ac.alightingByAnswer}|${ac.exitByDoor}" +
            "|${ac.exitByGuardian}|${ac.quickStartWaiting}|${ac.guardianFinishSeatOccupied}|${ac.reseatedAfterAllLeft}|${ac.otherRearSeatedAfterAllLeft}"
}

/** 칩 View: 기호 + 글자 + 색 + 윤곽(점선 가능). [StatusChipView]와 같은 모양이고 크기만 받는다 */
class AutoChipView(context: Context, sp: Float) : TextView(context) {
    init {
        textSize = sp
        typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)   // V6: 칩 상태 글자는 Jua
        gravity = Gravity.CENTER_VERTICAL
        minHeight = DuoDraw.dp(context, DuoTokens.CHIP_MIN_DP)
        val h = DuoDraw.dp(context, 14)
        setPadding(h, DuoDraw.dp(context, 4), h, DuoDraw.dp(context, 4))
        maxLines = 2
        DuoText.wordBreak(this)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO   // 줄 전체가 한 문장으로 읽힌다
    }
    fun bind(c: AutoChip) {
        text = c.text
        setTextColor(Palette.argb(c.colors.fg))
        background = DuoDraw.round(c.colors.bg, DuoDraw.dp(context, DuoTokens.CHIP_RADIUS_DP).toFloat(), DuoDraw.dp(context, 2), c.colors.stroke, c.colors.dashed)
    }
}

/** 가로로 채우다 넘치면 다음 줄로 감는 단순 흐름 배치(글꼴 2.0에서 겹치지 않게). 줄 안에서는 세로 가운데 정렬 */
class FlowRowLayout(context: Context) : ViewGroup(context) {
    var gapPx = 0
    var lineGapPx = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var lineH = 0; var total = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val cw = c.measuredWidth
            if (x > 0 && x + gapPx + cw > maxW) { total += lineH + lineGapPx; x = 0; lineH = 0 }
            x += (if (x > 0) gapPx else 0) + cw
            widest = maxOf(widest, x)
            lineH = maxOf(lineH, c.measuredHeight)
        }
        total += lineH
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, total + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        // 1) 줄 나누기 2) 줄마다 세로 가운데
        val lines = ArrayList<MutableList<View>>()
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (lines.isEmpty() || (x > 0 && x + gapPx + c.measuredWidth > maxW)) { lines.add(ArrayList()); x = 0 }
            lines.last().add(c)
            x += (if (x > 0) gapPx else 0) + c.measuredWidth
        }
        var y = paddingTop
        for (line in lines) {
            val lh = line.maxOf { it.measuredHeight }
            var cx = paddingLeft
            for (c in line) {
                val cy = y + (lh - c.measuredHeight) / 2
                c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight)
                cx += c.measuredWidth + gapPx
            }
            y += lh + lineGapPx
        }
    }
}

/**
 * 왼쪽 정보 패널 View. [bind]가 장면별 내용을 다시 만든다(서명이 같으면 그대로). 바탕 카드는 호출 측(StageScreen)이 입힌다.
 * 스티커 연출: 하차 끝(ALL_LEFT) + stickerJustGranted일 때 별이 튀어나와(0→1.2→1) 오늘 칸으로 날아가 칸이 한 번 튕긴다. 애니메이션이 꺼져 있으면 결과만.
 */
class AutoCheckPanelView(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val star = TextView(context)
    private var sig: String? = null
    private var board: StickerBoardView? = null
    private var stickerAnim: AnimatorSet? = null
    /** 이번 ALL_LEFT 진입에서 연출을 이미 시작했는지(장면이 바뀌면 StageScreen이 [resetSticker]로 초기화) */
    var stickerPlayed = false
        private set

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        star.text = "★"
        star.gravity = Gravity.CENTER
        star.visibility = GONE
        star.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(star, LayoutParams(dp(88), dp(88), Gravity.TOP or Gravity.START))
        clipChildren = false
    }

    fun resetSticker() {
        stickerAnim?.cancel(); stickerAnim = null
        star.visibility = GONE
        stickerPlayed = false
    }

    fun bind(scene: Scene, ac: AutoCheckUi, night: Boolean, cells: List<BoardCell>, onGuardian: (String) -> Unit) {
        val s = AutoCheckPanelRules.signature(scene, ac, night, resources.configuration.fontScale, cells)
        if (s != sig) {
            sig = s
            content.removeAllViews()
            board = null
            when (scene) {
                Scene.AUTO_CHECK -> buildCheck(ac, night, onGuardian)
                Scene.DEPART_READY -> buildDepart(ac, night)
                Scene.ARRIVED -> buildArrived(ac, night)
                Scene.ALL_LEFT -> buildAllLeft(ac, night, cells)
                else -> Unit
            }
            DuoText.wordBreakTree(content)
        }
        if (AutoCheckPanelRules.stickerAnimate(scene, ac, stickerPlayed)) {
            stickerPlayed = true
            post { playSticker(night) }
        }
    }

    private fun roles(night: Boolean) = DuoTokens.roles(night)

    private fun text(t: String, sp: Float, rgb: Int, jua: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        text = t; textSize = sp; setTextColor(Palette.argb(rgb))
        typeface = if (jua) StageFonts.typeface(context, StageFonts.FontRole.TITLE) else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        DuoText.wordBreak(this)
    }

    private fun badge(label: String, night: Boolean): TextView = text(label, DuoTokens.CAPTION_SP, V6Tokens.eyebrow(night), bold = true).apply {
        val r = roles(night)
        background = DuoDraw.round(r.surface, dp(999).toFloat(), dp(2), V6Tokens.eyebrow(night), dashed = true)
        setPadding(dp(14), dp(4), dp(14), dp(4))
        gravity = Gravity.CENTER
        maxLines = 1
    }

    /** 머리 줄: 제목(왼쪽) + 배지들(오른쪽). 좁으면 감긴다 */
    private fun header(title: TextView, badges: List<TextView>) {
        val row = FlowRowLayout(context).apply { gapPx = dp(16); lineGapPx = dp(8) }
        row.addView(title)
        for (b in badges) row.addView(b)
        content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun chip(c: AutoChip, minDp: Int = 0): AutoChipView = AutoChipView(context, AutoCheckPanelRules.STATUS_SP).apply {
        bind(c)
        if (minDp > 0) minWidth = dp(minDp)
    }

    private fun seatRow(ac: AutoCheckUi, row: SeatCheckRow, night: Boolean, withBelt: Boolean, withVerdict: Boolean, onGuardian: ((String) -> Unit)?): View {
        val r = roles(night)
        val flow = FlowRowLayout(context).apply { gapPx = dp(AutoCheckPanelRules.CHIP_GAP_DP); lineGapPx = dp(6) }
        val pad = dp(AutoCheckPanelRules.rowPadDp(ac.rows.size))
        flow.setPadding(dp(16), pad, dp(12), pad)
        val name = text(row.label, AutoCheckPanelRules.NAME_SP, if (row.selected) r.text else r.disabledText, bold = row.selected).apply {
            minWidth = dp(AutoCheckPanelRules.NAME_COL_DP); maxLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        flow.addView(name)
        flow.addView(chip(AutoCheckPanelRules.occChipFor(row, night), AutoCheckPanelRules.OCC_COL_DP))
        if (withBelt) flow.addView(chip(AutoCheckPanelRules.beltChipFor(row, night), AutoCheckPanelRules.BELT_COL_DP))
        if (withVerdict) {
            val v = row.verdict
            when {
                !row.selected -> flow.addView(text("– ${AutoCheckText.NOT_SELECTED}", AutoCheckPanelRules.NAME_SP, r.disabledText).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                AutoCheckPanelRules.showGuardianButton(row) && onGuardian != null -> {
                    val b = DuoButton(context, DuoTokens.Kind.PRIMARY, AutoCheckPanelRules.GUARDIAN_BUTTON_DP)
                    b.text = AutoCheckText.GUARDIAN_BUTTON
                    b.textSize = AutoCheckPanelRules.STATUS_SP
                    b.typeface = StageFonts.typeface(context, StageFonts.FontRole.BIG_BUTTON)
                    b.maxLines = 1
                    b.contentDescription = AutoCheckPanelRules.guardianButtonDesc(row)
                    val seat = row.seat
                    b.setOnClickListener { onGuardian(seat) }
                    flow.addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                v != null -> AutoCheckPanelRules.verdictChip(v, night)?.let { flow.addView(chip(it)) }
            }
        }
        if (AutoCheckPanelRules.rowMock(ac, row)) flow.addView(badge(AutoCheckText.MOCK_SIGNAL, night))
        flow.background = if (row.selected) DuoDraw.round(r.surface, dp(AutoCheckPanelRules.ROW_RADIUS_DP).toFloat(), dp(AutoCheckPanelRules.SELECTED_STROKE_DP), r.primaryFill)
            else DuoDraw.round(r.disabledFill, dp(AutoCheckPanelRules.ROW_RADIUS_DP).toFloat())
        flow.contentDescription = AutoCheckPanelRules.rowA11y(row)
        flow.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        return flow
    }

    private fun addRows(ac: AutoCheckUi, rows: List<SeatCheckRow>, night: Boolean, withBelt: Boolean, withVerdict: Boolean, onGuardian: ((String) -> Unit)?) {
        for ((i, row) in rows.withIndex()) {
            content.addView(seatRow(ac, row, night, withBelt, withVerdict, onGuardian), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (i == 0) 8 else AutoCheckPanelRules.rowGapDp(rows.size))
            })
        }
    }

    private fun buildCheck(ac: AutoCheckUi, night: Boolean, onGuardian: (String) -> Unit) {
        val r = roles(night)
        val title = text(AutoCheckText.TITLE_CHECK, AutoCheckPanelRules.HEAD_SP, r.textMuted, bold = true).apply { accessibilityHeading(this) }
        header(title, if (AutoCheckPanelRules.headMock(ac)) listOf(badge(AutoCheckText.MOCK_SIGNAL, night)) else emptyList())
        addRows(ac, ac.rows, night, withBelt = true, withVerdict = true, onGuardian = onGuardian)
        AutoCheckPanelRules.extraLine(ac)?.let { line ->
            content.addView(text("! $line", AutoCheckPanelRules.NAME_SP, V6Tokens.eyebrow(night), bold = true).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun bigTitle(t: String, night: Boolean): TextView = text(t, AutoCheckPanelRules.TITLE_SP, roles(night).text, jua = true).apply {
        setLineSpacing(0f, 1.05f); accessibilityHeading(this)
    }

    private fun buildDepart(ac: AutoCheckUi, night: Boolean) {
        val badges = ArrayList<TextView>()
        if (AutoCheckPanelRules.headMock(ac)) badges += badge(AutoCheckText.MOCK_SIGNAL, night)
        header(bigTitle(AutoCheckText.DEPART_TITLE, night), badges)
        // PM 결정 14(2026-11-13): 예정 시간("도착까지 약 N분")은 화면에 그리지 않고 음성으로만 알린다
        // 확인한 좌석 요약(선택 좌석만): "뒷줄 왼쪽 · ● 신호 받음"
        val sel = ac.rows.filter { it.selected }
        if (sel.isNotEmpty()) {
            val flow = FlowRowLayout(context).apply { gapPx = dp(12); lineGapPx = dp(10) }
            for (row in sel) {
                val v = row.verdict?.let { AutoCheckPanelRules.verdictChip(it, night) } ?: AutoCheckPanelRules.occChip(row.occ, night)
                flow.addView(AutoChipView(context, AutoCheckPanelRules.STATUS_SP).apply {
                    bind(v.copy(label = "${row.label} · ${v.label}"))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = AutoCheckPanelRules.rowA11y(row)
                })
            }
            content.addView(flow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        }
        AutoCheckPanelRules.extraLine(ac)?.let { line ->
            content.addView(text("! $line", AutoCheckPanelRules.NAME_SP, V6Tokens.eyebrow(night), bold = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun buildArrived(ac: AutoCheckUi, night: Boolean) {
        val r = roles(night)
        header(bigTitle(AutoCheckPanelRules.arrivedTitle(ac), night), if (AutoCheckPanelRules.headMock(ac)) listOf(badge(AutoCheckText.MOCK_SIGNAL, night)) else emptyList())
        content.addView(text(AutoCheckPanelRules.arrivedNote(ac), DuoTokens.CAPTION_SP, r.text, bold = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        content.addView(text(AutoCheckText.ARRIVED_SEATS, DuoTokens.CAPTION_SP, r.textMuted),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // 하차는 누가 아직 앉아 있는지가 핵심이라 착석 칩만(벨트·판정 칩 없음). 선택 여부와 무관하게 원래 색
        for ((i, row) in ac.rows.withIndex()) {
            val flow = FlowRowLayout(context).apply { gapPx = dp(AutoCheckPanelRules.CHIP_GAP_DP); lineGapPx = dp(6) }
            val pad = dp(AutoCheckPanelRules.rowPadDp(ac.rows.size))
            flow.setPadding(dp(16), pad, dp(12), pad)
            flow.addView(text(row.label, AutoCheckPanelRules.NAME_SP, r.text, bold = true).apply { minWidth = dp(AutoCheckPanelRules.NAME_COL_DP) })
            flow.addView(chip(AutoCheckPanelRules.occChip(row.occ, night), AutoCheckPanelRules.OCC_COL_DP))
            if (AutoCheckPanelRules.rowMock(ac, row)) flow.addView(badge(AutoCheckText.MOCK_SIGNAL, night))
            val seated = AutoCheckPanelRules.isStillSeated(row)   // 결정 11: 선택 안 한 운전석은 강조하지 않음(칩은 신호 그대로)
            flow.background = if (seated) DuoDraw.round(r.surface, dp(AutoCheckPanelRules.ROW_RADIUS_DP).toFloat(), dp(AutoCheckPanelRules.SELECTED_STROKE_DP), r.primaryFill)
                else DuoDraw.round(r.disabledFill, dp(AutoCheckPanelRules.ROW_RADIUS_DP).toFloat())
            flow.contentDescription = "${row.label}, ${AutoCheckText.ARRIVED_SEATS} ${AutoCheckPanelRules.occChip(row.occ, night).label}"
            flow.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            content.addView(flow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (i == 0) 8 else AutoCheckPanelRules.rowGapDp(ac.rows.size))
            })
        }
    }

    private fun buildAllLeft(ac: AutoCheckUi, night: Boolean, cells: List<BoardCell>) {
        val r = roles(night)
        header(bigTitle(SeatBoardRules.allLeftTitle(ac), night), if (AutoCheckPanelRules.headMock(ac)) listOf(badge(AutoCheckText.MOCK_SIGNAL, night)) else emptyList())
        content.addView(text(AutoCheckPanelRules.allLeftBasis(ac), DuoTokens.CAPTION_SP, r.textMuted),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        // r9-1: 부제는 좌석 화면과 같은 규칙(다시 앉음 > 다른 뒷자리 계속 앉음 > 기본 "뒷좌석을 한 번 더 둘러봐 주세요")
        content.addView(text(SeatBoardRules.allLeftSub(ac), DuoTokens.CAPTION_SP, r.text, bold = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // r8 1절: 보호자 끝냄 + 뒷자리 착석 신호 남음 → 알림 한 줄(좌석 화면과 같은 규칙)
        SeatBoardRules.noteLine(Scene.ALL_LEFT, ac)?.let { n ->
            content.addView(text("! $n", DuoTokens.CAPTION_SP, V6Tokens.eyebrow(night), bold = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (ac.stickerEarned) {
            if (cells.isNotEmpty()) {
                val b = StickerBoardView(context)
                b.bind(cells)
                board = b
                content.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
            }
            content.addView(text("★ ${if (ac.stickerJustGranted) AutoCheckText.STICKER_GOT else AutoCheckText.STICKER_EARNED}", DuoTokens.CAPTION_SP, V6Tokens.brand(night), bold = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    /** 별이 판 위에서 튀어나와(0→1.2→1) 오늘 칸으로 날아가고, 칸이 한 번 튕긴다. 1회. 애니메이션 꺼짐이면 아무것도 하지 않는다(결과만) */
    private fun playSticker(night: Boolean) {
        if (!ValueAnimator.areAnimatorsEnabled() || !isShown) return
        val b = board ?: return
        val today = (0 until b.childCount).map { b.getChildAt(it) }.firstOrNull { it.contentDescription?.contains(StickerBoard.TODAY_TAG) == true } ?: return
        val r = roles(night)
        star.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 52f * d)   // 장식(글자 아님): 88dp 원 안에 맞게 글꼴 배율과 무관
        // r10e: 무지개 스티커(흰 원 + 무지개 테두리, ★ 시안 A 무지개 / B 진한 색) — 도장판·날아가는 별과 같은 모양
        val starInk = StickerStyle.flyStarInk(night)
        star.setTextColor(Palette.argb(starInk ?: r.text))
        StickerStyle.rainbowText(star, if (starInk == null) night else null)
        star.background = RainbowCellDrawable(r.surface, dp(44).toFloat(), StickerStyle.argbs(night), true, dp(StickerStyle.RING_TODAY_DP).toFloat(), r.text, 0f)
        // 좌표: 이 패널 기준
        val size = dp(88)
        val startX = (width - size) / 2f
        val startY = (b.top - size - dp(8)).coerceAtLeast(0).toFloat()
        val endX = b.left + today.left + (today.width - size) / 2f
        val endY = b.top + today.top + (today.height - size) / 2f
        star.translationX = startX; star.translationY = startY
        star.scaleX = 0f; star.scaleY = 0f; star.alpha = 1f
        star.visibility = VISIBLE
        val pop = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(star, View.SCALE_X, 0f, 1.2f, 1f), ObjectAnimator.ofFloat(star, View.SCALE_Y, 0f, 1.2f, 1f))
            duration = 360; interpolator = OvershootInterpolator()
        }
        val fly = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(star, View.TRANSLATION_X, startX, endX),
                ObjectAnimator.ofFloat(star, View.TRANSLATION_Y, startY, endY),
                ObjectAnimator.ofFloat(star, View.SCALE_X, 1f, 0.6f),
                ObjectAnimator.ofFloat(star, View.SCALE_Y, 1f, 0.6f),
            )
            duration = 520; startDelay = 200; interpolator = DecelerateInterpolator()
        }
        val fade = ObjectAnimator.ofFloat(star, View.ALPHA, 1f, 0f).apply { duration = 120 }
        val bounce = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(today, View.SCALE_X, 1f, 1.15f, 1f), ObjectAnimator.ofFloat(today, View.SCALE_Y, 1f, 1.15f, 1f))
            duration = 280
        }
        val all = AnimatorSet()
        all.playSequentially(pop, fly, AnimatorSet().apply { playTogether(fade, bounce) })
        all.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { star.visibility = GONE; today.scaleX = 1f; today.scaleY = 1f }
            override fun onAnimationCancel(animation: android.animation.Animator) { star.visibility = GONE; today.scaleX = 1f; today.scaleY = 1f }
        })
        stickerAnim = all
        all.start()
    }

    override fun onDetachedFromWindow() {
        stickerAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun accessibilityHeading(tv: TextView) { if (android.os.Build.VERSION.SDK_INT >= 28) tv.isAccessibilityHeading = true }
}

/**
 * 상단 바 가운데 진행 단계: 확인 중 → 출발 준비 → 출발 → 도착 → 하차(기호 + 글자, 현재 칸 강조).
 * 확인 중이면 현재 칸 옆 점 3개가 차례로 커졌다 작아진다(ValueAnimator). 시스템 애니메이션 꺼짐·안 보임·잠금이면 멈춘다.
 * 글꼴 1.5배 이상이면 '현재 칸 글자 + 점 5개'로 줄인다.
 */
class AutoPhaseStepView(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var sig: String? = null
    private var dotsView: PulseDots? = null
    private var animator: ValueAnimator? = null
    private var wantAnim = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun bind(visible: Boolean, phase: AutoPhase?, night: Boolean, stopover: Boolean = false, alighting: Boolean = false) {
        if (!visible || phase == null) { visibility = GONE; stop(); return }
        val compact = resources.configuration.fontScale >= AutoCheckPanelRules.STEPS_COMPACT_FONT
        val cur = AutoCheckPanelRules.phaseIndex(phase)
        val s = "$cur|$night|$compact|$phase|$stopover|$alighting"
        if (s != sig) {
            sig = s
            removeAllViews(); dotsView = null
            val r = DuoTokens.roles(night)
            val names = AutoCheckPanelRules.stepNames(stopover, alighting)
            for ((i, name) in names.withIndex()) {
                val st = AutoCheckPanelRules.stepState(i, cur)
                if (compact && st != AutoCheckPanelRules.StepState.CURRENT) {
                    // 줄인 모양: 기호만(현재가 아닌 칸)
                    addView(TextView(context).apply {
                        text = AutoCheckPanelRules.stepGlyph(st)
                        textSize = DuoTokens.CAPTION_SP
                        setTextColor(Palette.argb(if (st == AutoCheckPanelRules.StepState.DONE) r.stateOnText else r.textMuted))
                        gravity = Gravity.CENTER
                        background = DuoDraw.round(r.surface, dp(999).toFloat(), dp(2), r.surfaceStroke)
                        setPadding(dp(10), 0, dp(10), 0)
                        includeFontPadding = false   // 2026-11-13 글꼴 2.0 캡처: 글자 높이 53px > 칸 48px(위아래 잘림) → 글꼴 여백 빼고 칸은 최소 48dp
                        minHeight = dp(AutoCheckPanelRules.STEP_MIN_DP); minimumHeight = dp(AutoCheckPanelRules.STEP_MIN_DP)
                    }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(8); gravity = Gravity.CENTER_VERTICAL })
                    continue
                }
                val pill = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val tv = TextView(context).apply {
                    text = "${AutoCheckPanelRules.stepGlyph(st)} $name"
                    textSize = DuoTokens.CAPTION_SP
                    typeface = if (st == AutoCheckPanelRules.StepState.CURRENT) StageFonts.typeface(context, StageFonts.FontRole.TITLE) else Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false   // 글꼴 2.0에서 칸(최소 48dp) 안에 글자가 다 들게(위 주석과 같은 이유)
                    setTextColor(Palette.argb(when (st) {
                        AutoCheckPanelRules.StepState.CURRENT -> r.primaryText
                        AutoCheckPanelRules.StepState.DONE -> r.stateOnText
                        AutoCheckPanelRules.StepState.TODO -> r.textMuted
                    }))
                }
                pill.addView(tv)
                if (st == AutoCheckPanelRules.StepState.CURRENT && AutoCheckPanelRules.stepsAnimate(AutoCheckUi(phase, emptyList()))) {
                    val pd = PulseDots(context, Palette.argb(r.primaryText))
                    dotsView = pd
                    pill.addView(pd, LayoutParams(dp(36), dp(16)).apply { leftMargin = dp(6) })
                }
                pill.background = when (st) {
                    AutoCheckPanelRules.StepState.CURRENT -> DuoDraw.round(r.primaryFill, dp(999).toFloat(), dp(3), r.primaryEdge)
                    AutoCheckPanelRules.StepState.DONE -> DuoDraw.round(r.surface, dp(999).toFloat(), dp(2), r.stateOnText)
                    AutoCheckPanelRules.StepState.TODO -> DuoDraw.round(r.surface, dp(999).toFloat(), dp(2), r.surfaceStroke, dashed = true)
                }
                pill.setPadding(dp(16), dp(2), dp(16), dp(2))
                pill.minimumHeight = dp(AutoCheckPanelRules.STEP_MIN_DP)
                addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(8); gravity = Gravity.CENTER_VERTICAL })
            }
            contentDescription = "진행 단계 ${cur + 1}/${names.size}: ${names[cur]}"
        }
        visibility = VISIBLE
        wantAnim = phase == AutoPhase.CHECKING
        updateAnim()
    }

    private fun updateAnim() {
        val pd = dotsView
        val run = wantAnim && pd != null && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
        if (!run) { stop(); return }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pd?.phase = it.animatedValue as Float }
            start()
        }
    }

    fun stop() {
        animator?.cancel(); animator = null
        dotsView?.phase = -1f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnim() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (dotsView != null) updateAnim() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (dotsView != null) updateAnim() }

    /** 점 3개(장식, 화면 읽기 제외). phase 0..1에서 차례로 커진다. phase < 0이면 가만히 같은 크기 */
    private class PulseDots(context: Context, private val color: Int) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var phase = -1f
            set(v) { field = v; invalidate() }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            p.color = color
            val r0 = height / 2f * 0.5f
            for (i in 0 until 3) {
                val k = if (phase < 0f) 0.7f else {
                    val t = ((phase * 3f - i) % 3f + 3f) % 3f   // 0..3
                    if (t < 1f) 0.6f + 0.4f * kotlin.math.sin(t * Math.PI).toFloat() else 0.6f
                }
                c.drawCircle(width * (i + 0.5f) / 3f, height / 2f, r0 * k * 1.6f, p)
            }
        }
    }
}
