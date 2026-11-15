package com.crewguide.probe

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 새 '무대' 화면(가안): 배경 그라데이션 위에 캐릭터가 영역을 꽉 채우고, 자막 판·컨트롤 바탕·단계 점·설정·보조 라벨이 그 위에 겹친다.
 * (아래 설명 중 '중앙 캐릭터' 배치는 겹침 구조로 바뀌었다. 롤백: 이 파일만 이전 판으로 되돌리면 된다 — fitInside 기본값이 false)
 * 지금은 개발용 보기의 '무대 미리보기' 오버레이로만 쓰인다(미리보기 닫기 버튼도 미리보기 전용).
 * 상태는 render(state)로 받아 읽기만 하며, 보호자 확인·잠금 등 어떤 상태도 바꾸지 않는다. 애니메이션 없음.
 * 설정 버튼, 큰 [예][아니오], 좌석 선택, 끝내기 버튼은 S-2·S-3에서 추가한다(여기에는 없음).
 */
class StageScreen(
    context: Context,
    private val onClose: () -> Unit,
    // 2026-11-08 v6.1: 기본 = 공식 원본 PNG 렌더러. 3D(StageCharacter3DView)·벡터 코드는 보존(롤백: 이 줄을 StageCharacter3DView(context)로)
    private val renderer: CharacterRenderer = CrewPngRenderer(context),
    private val actions: StageActions? = null,
    /** 크기가 정해지거나 바뀔 때 '[무대] 화면 WxH 밀도 D 캐릭터 영역 wxh' 한 줄을 알린다(MainActivity가 log()로 남김) */
    private val onSizeLog: ((String) -> Unit)? = null,
    /** 개발용 미리보기일 때만 우상단에 [미리보기 닫기] 버튼을 함께 그린다. 기본 무대(S-4)에서는 false */
    private val showCloseButton: Boolean = true,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Int = (v * density).toInt()
    /** 무대 폭(dp). 배치된 뒤에는 실제 뷰 폭, 그 전에는 화면 정보. v3 반복 6: 작은 폭 변화는 onConfigurationChanged가 오지 않아 displayMetrics가 늦을 수 있다 */
    private fun screenWDp(): Float = if (width > 0) width / density else windowWDp()
    /**
     * 배치 전 폭(dp): 창 경계(WindowMetrics, API 30+)를 우선한다. 작은 폭 변화 뒤 다시 만들 때 displayMetrics가 이전 폭으로 남는 것을
     * 에뮬레이터에서 확인했다(1600 → 1599dp 재생성 후에도 1600으로 읽힘, 반복 6).
     */
    private fun windowWDp(): Float {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val b = wm?.currentWindowMetrics?.bounds
            if (b != null && b.width() > 0) return b.width() / density
        } catch (_: Throwable) {}
        return resources.displayMetrics.widthPixels / density
    }
    /** v3 반복 6(P0): 실제 폭이 바뀔 때마다 알린다(MainActivity가 2영역 등급 경계를 넘었는지 본다) */
    var onWidthChanged: ((Float) -> Unit)? = null
    // 이름 주의: GradientDrawable.apply 안에서 'colors'는 GradientDrawable.getColors()로 읽혀 dotOn을 못 찾는다
    private val stageColors = Palette.stage(Palette.current.night)
    /** v3 반복 5(S02): 2영역 배치(왼쪽 고르기 / 오른쪽 캐릭터·행동). Medium·Wide이고 실제 무대(actions 있음)일 때만. 롤백: StageSplit.enabled */
    private val split: Boolean = actions != null && StageSplit.enabled(screenWDp())
    private val leftColumn = LinearLayout(context)   // 2영역: 지도 → 출처·범례 → 아이 카드 줄(세로 한 열)
    private val cardsRow = LinearLayout(context)     // 2영역: 아이 카드 줄(세로 컨테이너 안에 가로 줄 1~2개, 왼쪽 열 맨 아래)
    /** v3 반복 6(P0): 왼쪽 열을 감싸는 세로 스크롤. 내용이 높이에 들어가면 가운데 정렬로 보이고 스크롤되지 않는다 */
    private val leftScroll = ScrollView(context)
    private var lastFit: StageSplit.Fit? = null
    private var lastFitKey: Triple<StageSplit.Fit, Int, Int>? = null   // 같은 결과·같은 칸 크기면 다시 적용·기록하지 않는다
    /** 생성할 때 2영역으로 만들었는지(창 폭이 등급 경계를 넘으면 MainActivity가 화면을 다시 만든다) */
    val builtSplit: Boolean get() = split
    private fun mapScaleNow(): Float = seatMapScale(resources.displayMetrics)
    /** 지금 2영역으로 그리는 중인지: 좌석 장면(PICK·READY)만. 안내·질문·잠금 등은 기존 전체 폭 배치(검증된 배치 유지, [예][아니오] 폭 보존) */
    private var splitActive = false
    /** 2영역 + 큰 글꼴(1.5배 이상): 선택 요약을 [시작] 위 별도 줄로 쌓는다(옆에 두면 3줄을 넘어 '다음' 줄이 잘림) */
    private val splitStacked: Boolean = split && resources.configuration.fontScale >= StageSplit.STACK_FONT_SCALE

    private val dots = ArrayList<View>()
    private val dotCells = ArrayList<FrameLayout>()   // 점마다 28dp 칸(현재 점의 바깥 링 배경)
    private val dotLines = ArrayList<View>()          // 점 사이 연결선 4개
    private val subLabel: TextView
    private val charView: View = renderer.view
    // v3 단계 1: CharacterHost → 공식 PNG(Sprite). Live2D는 SDK가 없어 항상 Sprite로 복귀(fallbackReason 로그). 3D 렌더러면 sink가 아무것도 안 한다
    private val spriteDriver = SpriteCharacterDriver { st -> (renderer as? CrewPngRenderer)?.setArtState(st) }
    private val characterHost = CharacterHost(spriteDriver, Live2DUnavailable()).also { h ->
        h.fallbackReason?.let { android.util.Log.i("G1PROBE", "[캐릭터] 렌더러 ${h.active.id} ($it)") }
    }
    private var hostScene: Scene? = null
    /** 직전 렌더의 스티커 지급 표시(보상 표정을 켜질 때 한 번만, 결정 51) */
    private var hostJust = false
    private val bottomStack = LinearLayout(context)   // (보조 라벨 영역 위) 컨트롤 바탕만 둔다. 말풍선은 bubbleLayer로 분리됨. 캐릭터 영역에는 영향 없음
    private val bubbleLayer = FrameLayout(context)    // 말풍선 층(캐릭터 위·컨트롤 아래, 말풍선 밖 터치는 아래로 통과). 위치는 StageBubblePlacement.slotFor
    // 대화창식 풍선 목록. 필드 초기화는 init 블록(render 호출 가능)보다 위에 있다. View 생성자 콜백에서는 bubbleReady=false라 건드리지 않는다
    private class BubbleEntry(val view: TextView, val bg: BubbleBackground)
    private val bubbleViews = LinkedHashMap<Long, BubbleEntry>()   // 풍선 id → View(최대 MAX_ITEMS개, id로 재사용)
    private var stack = BubbleStack()
    private var prevScene: Scene? = null
    private var lastPlacement: BubblePlacement? = null
    private var bubbleReady = false   // init이 끝나기 전(View 생성자가 부르는 콜백)에는 말풍선 뷰를 건드리지 않는다
    private val topBarFrame = FrameLayout(context)
    private var topPadPx = 0       // 상단 안전 여백(px): 시스템 바 인셋 또는 고정 16dp
    private var bottomPadPx = 0    // 하단 안전 여백(px)
    private var lastSizeMsg: String? = null
    private var lastScene: Scene? = null
    private var lastCaption: String? = null
    private var lastSub: String? = null
    private var lastDot = Int.MIN_VALUE
    private var lastBackdrop: Backdrop? = null   // 같은 Backdrop이면 배경을 다시 만들지 않는다
    private var subLabelReady = false            // init 첫 줄의 applyBackdrop은 subLabel이 만들어지기 전에 불리므로, 만든 뒤에만 띠 색을 건드린다

    // 하단 고정 컨트롤 영역(장면에 필요한 버튼만 보임)
    private val controls = LinearLayout(context)
    private val yesNoRow = LinearLayout(context)
    private val childBox = LinearLayout(context)   // 이제 '지난번 아이 그대로' 글자 칩 한 줄만 둔다(아이 선택은 얼굴 카드)
    private val childChips = ArrayList<Pair<Int, Button>>()   // (LAST_KEY, 버튼)
    private val cardLayer = FrameLayout(context)   // 왼쪽 위 아이 얼굴 카드 층(캐릭터 영역과 같은 구역, 캐릭터 위·자막 판 아래)
    private val seatLayer = FrameLayout(context)   // 오른쪽 위 좌석 지도 층(cardLayer와 같은 구역·같은 z 방식, 지도 밖 터치는 아래로 통과). 좌석 칸이 5개 이름과 맞을 때만 지도를 넣는다
    private var seatMap: StageSeatMapView? = null
    private var seatLegend: LinearLayout? = null   // 2026-11-11 U3: 출처 줄 + 상태 범례(지도 아래)
    private var pickSummary: TextView? = null      // v3 반복 4(S02): [시작] 왼쪽 '현재 선택 → 다음' 요약
    private val legendChips = ArrayList<StatusChipView>()
    private var seatSourceView: TextView? = null   // 좌석 지도 아래 벨트 참고 출처 한 줄(모의 / 차량 신호 수신 / 일부 신호 미확인 / 신호 미확인: StageSeatSignal.sourceCaption)
    private var rightPadPx = 0     // 오른쪽 시스템 인셋(px, 없으면 0): 지도 오른쪽 여백 16dp에 더한다
    private val childCards = ArrayList<Pair<Int, ChildCardView>>()   // (프로필 id, 카드)
    private var childSig: String? = null
    private val stickerBadge = StickerBadgeView(context)   // 2026-11-11 U4
    private val lockCard = V6LockCardView(context)   // V6 S09: 잠금 장면의 표시 전용 카드(버튼·해제 수단 없음)
    private val headlineCard = StageHeadlineView(context)   // 2026-11-12 PHASE B: 질문·하차 장면의 보호자 행동 카드(표시 전용)
    // 2026-11-13 자동 확인: 왼쪽 정보 패널(AUTO_CHECK·DEPART_READY·ARRIVED·ALL_LEFT, state.autoCheck가 있을 때만) + 상단 진행 단계
    private val autoLayer = FrameLayout(context)
    private val autoScroll = ScrollView(context)
    private val autoPanel = AutoCheckPanelView(context)
    private val phaseSteps = AutoPhaseStepView(context)
    private var stickerWeek: List<BoardCell> = emptyList()
    /**
     * 2026-11-13 좌석 화면 하나(사용자 승인 시안, 결정 14~17): 자동 확인·출발 준비·도착·모두 내림에서 무대(캐릭터·말풍선 목록·목록 패널·아래 띠)를 덮는다.
     * 좌석 배치를 알 수 없으면(3열 등) 덮지 않고 기존 목록 패널(autoLayer)을 쓴다. 롤백: render의 boardModel을 늘 null로
     */
    private val seatStage = SeatBoardStage(context)
    private var seatStageOn = false
    /**
     * 2026-11-14 지시 4: 말풍선 기록([TripBubbleStore])에 넣기 전 자막이 잠깐 그대로인지 기다린다([TripBubbleRules.SETTLE_MS]).
     * 마지막으로 본 문장·이동 단계·잠금 여부. 잠금이면 새 문장을 넣지 않는다
     */
    private var bubbleSeenLine: String? = null
    private var bubblePhase: AutoPhase? = null
    private var bubbleLocked = false
    private val bubbleSettle = Runnable { commitBubble() }
    private var boardModel: SeatBoardRules.ScreenModel? = null
    private val a11ySaved = HashMap<View, Int>()   // 좌석 화면이 덮기 전 아래 층들의 화면 읽기 설정
    private var lastState: StageUiState? = null
    private var autoPanelOn = false
    /** 2026-11-12 A2: 질문 음성 실패 뒤에만 보이는 [다시 듣기](VoiceReplayRule). 질문 상태는 바꾸지 않는다 */
    private var replayButton: Button? = null
    private var replayWanted = false
    private var reconnectNote = false
    private val yesNote = TextView(context)   // 질문 장면에서만 보이는 '‘예’는 보호자 보고예요' 한 줄
    private val cabinDecor = StageCabinDecorView(context)   // 배경 위·캐릭터 아래 기내 창문 장식(터치 없음)
    private val charHolder = FrameLayout(context)   // 캐릭터 영역(상단 바 아래 ~ 화면 맨 아래, 고정): 스포트라이트 + 캐릭터를 가운데에
    private val spotlight = View(context)
    private val zonePanel = View(context)   // V6 S02: 2영역 오른쪽 둥근 무대 패널(장식, 터치 없음)
    private var controlsBg: GradientDrawable? = null   // 행동 띠 바탕(2영역이면 무대 패널 안쪽 모양에 맞춘다, applySplitZones)
    private var controlsBgInStage: Boolean? = null
    // 스포트라이트: 무대 배경 위쪽 색에서 파생(투명도만 다름), 중심 → 가장자리 투명. 이름 주의: apply 없이 setter로 만든다
    private val spotDrawable: GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            (StageLayout.SPOTLIGHT_ALPHA shl 24) or (stageColors.bgTop and 0xFFFFFF),
            (stageColors.bgTop and 0xFFFFFF),
        ),
    ).also { it.setGradientType(GradientDrawable.RADIAL_GRADIENT); it.setShape(GradientDrawable.OVAL) }
    private val seatRow = LinearLayout(context)
    private val seatChips = LinkedHashMap<String, Button>()
    private val startButton = IconButton(context)
    private val restartButton = IconButton(context)
    private val finishButton = IconButton(context)
    private var lastStartKind: ButtonKind? = null   // [시작] 모양이 바뀔 때만 다시 입힌다
    private var settingsButton: Button? = null   // actions가 있을 때만 init에서 만든다
    private var manualFinishButton: Button? = null   // 보호자용 [이번 이동 끝내기](actions가 있을 때만)

    private companion object {
        const val LAST_KEY = -100
        const val MOTION_FRAME_MS = 33L   // 약 30fps
        const val QUIET_LINE_ALPHA = 0x66 // 아직 안 채운 연결선의 투명도(색은 dotOff)
    }   // '지난번 아이 그대로' 칩의 키(프로필 id는 0 이상)

    private fun visIf(b: Boolean): Int = if (b) View.VISIBLE else View.GONE

    private fun roundBg(fill: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(12).toFloat()
        return g
    }

    /** 칩 바탕: 미선택 낮 칩만 윤곽(StageButtons.chipOutlineDp, 색 CHIP_STROKE). 지역 이름 g는 GradientDrawable 멤버와 겹치지 않는다 */
    private fun chipBg(fill: Int, selected: Boolean): GradientDrawable {
        val g = roundBg(fill)
        val night = Palette.current.night
        val w = StageButtons.chipOutlineDp(selected, night)
        if (w > 0) g.setStroke(dp(w), Palette.argb(Palette.current.chipStroke))
        return g
    }

    /** 작고 조용한 보조 버튼: 투명 바탕 + caption 색 2dp 윤곽 알약, 24sp 보통 굵기, 높이 76dp 이상, 좌우 여백 24dp */
    private fun makeQuietButton(label: String, desc: String?, onTap: () -> Unit): Button {
        val b = Button(context)
        val ink = Palette.argb(stageColors.caption)
        val pill = GradientDrawable()
        pill.shape = GradientDrawable.RECTANGLE
        pill.setColor(Color.TRANSPARENT)
        pill.setStroke(dp(StageUiTokens.QUIET_BUTTON_STROKE_DP), ink)
        pill.cornerRadius = dp(StageUiTokens.QUIET_BUTTON_MIN_DP).toFloat()   // 높이의 절반을 넘으면 완전 둥글게 잘린다
        b.text = label
        if (desc != null) b.contentDescription = desc
        b.textSize = StageUiTokens.QUIET_BUTTON_SP.toFloat()
        b.typeface = Typeface.DEFAULT
        b.isAllCaps = false
        b.setTextColor(ink)
        b.background = pill
        b.stateListAnimator = null   // 기본 버튼의 그림자(고도) 제거
        b.minHeight = dp(StageUiTokens.QUIET_BUTTON_MIN_DP); b.minimumHeight = dp(StageUiTokens.QUIET_BUTTON_MIN_DP)
        b.minWidth = 0; b.minimumWidth = 0
        b.setPadding(dp(StageUiTokens.QUIET_BUTTON_PAD_DP), 0, dp(StageUiTokens.QUIET_BUTTON_PAD_DP), 0)
        b.setOnClickListener { onTap() }
        return b
    }

    /** 설정 아이콘 버튼: 지름 76dp 원, 글자 없음(contentDescription만), 톱니바퀴는 IconDrawable이 그린다 */
    private fun makeSettingsIconButton(desc: String, onTap: () -> Unit): IconButton {
        val btn = IconButton(context)
        val night = Palette.current.night
        val iconPx = dp(StageUiTokens.settingsIconDp())
        val gear = IconDrawable(IconKind.GEAR, Palette.argb(StageUiTokens.settingsInk(night)))
        gear.setBounds(0, 0, iconPx, iconPx)
        btn.setPill(
            Palette.argb(StageUiTokens.settingsFill(night)), 0, 0,
            dp(StageUiTokens.SETTINGS_OUTLINE_DP), Palette.argb(StageUiTokens.settingsOutline(night)),
        )
        btn.text = ""
        btn.contentDescription = desc
        btn.stateListAnimator = null
        btn.minWidth = 0; btn.minimumWidth = 0; btn.minHeight = 0; btn.minimumHeight = 0
        btn.setPadding(0, 0, 0, 0)
        btn.compoundDrawablePadding = 0
        btn.setCompoundDrawablesRelative(gear, null, null, null)
        btn.setOnClickListener { onTap() }
        return btn
    }

    private fun makeButton(label: String, sp: Float, minDp: Int, onTap: () -> Unit): Button {
        val scheme = Palette.current
        return Button(context).apply {
            text = label
            textSize = sp
            typeface = Typeface.DEFAULT
            isAllCaps = false
            minHeight = dp(minDp); minimumHeight = dp(minDp)
            setTextColor(Palette.argb(scheme.btnOnText))
            background = roundBg(Palette.argb(scheme.btnOnBg))
            setOnClickListener { onTap() }
        }
    }

    private fun setupControls() {
        val act = actions ?: run { controls.visibility = View.GONE; return }
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL   // 최소 높이가 내용보다 클 때 내용을 세로 가운데에
        controls.visibility = View.GONE

        // [예][아니오]: 한 줄을 같은 가중치·같은 고정 높이로 나눈다. 글자는 '예'/'아니오' 한 줄씩
        yesNoRow.orientation = LinearLayout.HORIZONTAL
        // 모양은 specOf(PRIMARY_YES/SECONDARY_NO): 초록 알약+원형 체크 / 회색 알약+원형 엑스, 높이 112dp 동일
        val yes = IconButton(context)
        yes.text = StageControlText.YES
        applySpec(yes, ButtonKind.PRIMARY_YES)
        yes.setOnClickListener { act.onYes() }
        val no = IconButton(context)
        no.text = StageControlText.NO
        applySpec(no, ButtonKind.SECONDARY_NO)
        no.setOnClickListener { act.onNo() }
        val yesNoHeight = dp(StageControlLayout.YESNO_HEIGHT_DP)   // 두 버튼이 이 한 값을 같이 쓴다
        yesNoRow.addView(yes, LinearLayout.LayoutParams(0, yesNoHeight, 1f).apply { rightMargin = dp(StageControlLayout.YESNO_GAP_DP) })
        yesNoRow.addView(no, LinearLayout.LayoutParams(0, yesNoHeight, 1f))
        controls.addView(yesNoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 질문 장면에서만 보이는 작은 안내 한 줄(버튼 줄 바로 아래)
        yesNote.text = StageControlText.YES_NOTE
        yesNote.textSize = StageControlLayout.NOTE_SP
        yesNote.typeface = Typeface.DEFAULT
        yesNote.gravity = Gravity.CENTER
        yesNote.maxLines = 1
        yesNote.ellipsize = TextUtils.TruncateAt.END
        yesNote.setTextColor(Palette.argb(stageColors.caption))
        yesNote.visibility = View.GONE
        controls.addView(yesNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // 2026-11-12 A2: [다시 듣기] 76dp 이상. 음성 실패 + 재연결 완료 + P에서만 보인다(그 외 GONE)
        val replay = makeButton(VoiceReplayRule.LABEL, StageControlLayout.BTN_SP, StageControlLayout.BTN_MIN_DP) { act.onReplay() }
        replay.contentDescription = VoiceReplayRule.DESC
        replay.setSingleLine(true)
        replay.visibility = View.GONE
        replayButton = replay
        controls.addView(replay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(StageControlLayout.BTN_MIN_DP)).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })

        // 1줄: 아이 칩(프로필 최대 4 + 지난번 칩). 프로필이 바뀌면 renderChildChips가 다시 만든다
        childBox.orientation = LinearLayout.VERTICAL
        childBox.visibility = View.GONE
        controls.addView(childBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(StageControlLayout.CHIP_GAP_DP) })

        // 2줄: 좌석 칩(이름·순서는 MainActivity가 정함) + [보호자 확인 시작]. 모두 같은 고정 높이
        seatRow.orientation = LinearLayout.HORIZONTAL
        val chipHeight = dp(StageControlLayout.CHIP_MIN_DP)
        val chipGap = dp(StageControlLayout.CHIP_GAP_DP)
        val names = act.seatNames()
        val slots = seatSlotsFor(names)   // null이면(이름이 5개 집합과 다르면) 기존 글자 칩 줄 그대로
        if (slots == null) {
            for (n in names) {
                val chip = makeButton(n, StageControlLayout.CHIP_SP, StageControlLayout.CHIP_MIN_DP) { act.onToggleSeat(n) }
                chip.setSingleLine(true)
                chip.ellipsize = TextUtils.TruncateAt.END
                chip.setPadding(0, 0, 0, 0)
                seatChips[n] = chip
                seatRow.addView(chip, LinearLayout.LayoutParams(0, chipHeight, 1f).apply { rightMargin = chipGap })
            }
        } else {
            // 좌석 지도: 오른쪽 위 층에 넣는다(act는 이 함수 안에서 이미 정해졌고 seatLayer는 필드라 이미 만들어져 있다)
            val map = StageSeatMapView(context, slots) { name -> act.onToggleSeat(name) }
            seatMap = map
            if (split) {
                // 2영역: 왼쪽 영역 가운데 세로 한 열. 지도 크기 = 기본 도형 × StageSplit.mapScale(StageSeatMapView도 같은 배율로 그린다)
                leftColumn.orientation = LinearLayout.VERTICAL
                leftColumn.gravity = Gravity.CENTER_HORIZONTAL
                leftColumn.gravity = Gravity.CENTER   // 스크롤 칸(fillViewport)이 늘려 줄 때 내용을 세로 가운데에
                val k = mapScaleNow()
                leftColumn.addView(map, LinearLayout.LayoutParams(dpf(StageSeatMap.widthDp() * k), dpf(StageSeatMap.heightDp() * k)))
                // 반복 6: 왼쪽 열이 높이를 넘으면 겹치거나 잘리지 않고 스크롤된다(첫 배치 뒤 실측으로 배율을 다시 맞춘다: fitLeftColumn)
                leftScroll.isFillViewport = true
                leftScroll.isVerticalScrollBarEnabled = true
                leftScroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                leftScroll.addView(leftColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                seatLayer.addView(leftScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                val mapLp = FrameLayout.LayoutParams(dpf(StageSeatMap.displayWidthDp(screenWDp())), dpf(StageSeatMap.displayHeightDp(screenWDp())), Gravity.TOP or Gravity.END)
                mapLp.topMargin = dp(StageSeatMap.TOP_GAP_DP)
                mapLp.rightMargin = dp(StageSeatMap.MARGIN_RIGHT_DP) + rightPadPx
                seatLayer.addView(map, mapLp)
            }

            // 2026-11-11 U3: 지도 아래 = 출처 한 줄(18sp) + 상태 범례 2×2(StatusChipView: 기호 + 글자 + 색). 지도 폭에 맞춘다
            val legend = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val srcTv = TextView(context).apply {
                textSize = DuoTokens.CAPTION_SP
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val padH = dp(16)
                val padV = dp(4)
                setPadding(padH, padV, padH, padV)
            }
            seatSourceView = srcTv
            legend.addView(srcTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            val states = StatusChipSpec.State.values()
            legendChips.clear()
            // v3 단계 3: Wide/Medium = 2×2, Compact = 1열 4줄(StageSeatMap.legendColumns)
            val cols = StageSeatMap.legendColumns(StageSeatMap.tierFor(screenWDp()))
            for (rowIdx in 0 until states.size / cols) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
                for (k in 0 until cols) {
                    val chip = StatusChipView(context)
                    chip.bind(states[rowIdx * cols + k], Palette.current.night)
                    legendChips.add(chip)
                    row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), dp(6)) })
                }
                legend.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            legend.contentDescription = "좌석 상태 안내: 체결 신호, 미체결 신호, 확인 불가, 신호 없음. 차량 신호는 참고용이에요"
            seatLegend = legend
            if (split) {
                // 범례 폭: 지도 폭과 같게(칩 2×2가 지도 아래 같은 축). 카드 줄은 범례 아래 별도 칸
                // 반복 6: 범례 폭은 등급 기본 배율 기준으로 고정(지도 배율이 줄어도 범례 줄바꿈·높이가 같아 맞춤 계산이 한 번에 끝난다)
                val legendW = dpf(StageSplit.legendWidthDp(screenWDp()))
                leftColumn.addView(legend, LinearLayout.LayoutParams(legendW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(StageSplit.SECTION_GAP_DP) })
                cardsRow.orientation = LinearLayout.VERTICAL   // 반복 6: 가로 줄 1개(보통) 또는 2개(폭이 모자랄 때 2×2)
                cardsRow.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                cardsRow.visibility = View.GONE
                leftColumn.addView(cardsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(StageSplit.SECTION_GAP_DP) })
                // '지난번 아이 그대로'는 아이 고르기 동작이라 카드 줄 바로 아래(왼쪽 열)로 옮긴다. 행동 띠에는 요약과 [시작]만 남는다
                controls.removeView(childBox)
                leftColumn.addView(childBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(StageSplit.SECTION_GAP_DP) })
            } else {
                val srcLp = FrameLayout.LayoutParams(dpf(StageSeatMap.displayWidthDp(screenWDp())), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                srcLp.topMargin = dp(StageSeatMap.TOP_GAP_DP + StageSeatMap.LEGEND_GAP_DP) + dpf(StageSeatMap.displayHeightDp(screenWDp()))
                srcLp.rightMargin = dp(StageSeatMap.MARGIN_RIGHT_DP) + rightPadPx
                seatLayer.addView(legend, srcLp)
            }

            // [시작]을 줄 가운데에: 왼쪽 칸 1 + [시작] 2 + 빈 칸 1 (줄 폭의 절반)
            // v3 반복 4(S02): 왼쪽 칸 = 현재 선택 → 다음 행동 요약(StagePickSummary). 줄 높이·[시작] 위치는 그대로
            val sum = TextView(context).apply {
                textSize = DuoTokens.CAPTION_SP
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                maxLines = if (splitStacked) StageSplit.STACK_SUMMARY_LINES else 3
                ellipsize = TextUtils.TruncateAt.END
                if (splitStacked) gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(if (splitStacked) 8 else 16), 0)
                DuoText.wordBreak(this)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            pickSummary = sum
            if (splitStacked) {
                seatRow.orientation = LinearLayout.VERTICAL
                seatRow.addView(sum, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            } else {
                seatRow.addView(sum, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (split) StageSplit.SUMMARY_WEIGHT else 1f))
            }
        }
        seatRow.gravity = Gravity.CENTER_VERTICAL   // 칩(76dp)은 그대로 두고 더 큰 [시작](96dp)과 가운데를 맞춘다
        startButton.text = StageControlText.START
        lastStartKind = startKind(false)
        applySpec(startButton, startKind(false))
        startButton.setOnClickListener { act.onStart() }
        startButton.setSingleLine(true)
        startButton.ellipsize = TextUtils.TruncateAt.END
        val startWeight = if (slots == null) StageControlLayout.START_SLOTS.toFloat() else if (split) StageSplit.START_WEIGHT else 2f
        if (splitStacked) seatRow.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(specOf(ButtonKind.PRIMARY_START).heightDp)))
        else seatRow.addView(startButton, LinearLayout.LayoutParams(0, dp(specOf(ButtonKind.PRIMARY_START).heightDp), startWeight))
        if (slots != null && !split) seatRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(seatRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // [다시 시작] [끝내기]: 한 줄 장면용(시작 버튼 위 별도 여백 없음)
        // 다시 시작 = PRIMARY_START(재생 아이콘), 끝내기 = FINISH(아이콘 없음: 체크는 완료·안전 단정으로 읽힐 수 있어 쓰지 않음)
        restartButton.text = StageControlText.RESTART
        applySpec(restartButton, ButtonKind.PRIMARY_START)
        restartButton.setOnClickListener { act.onRestart() }
        finishButton.text = StageControlText.FINISH
        applySpec(finishButton, ButtonKind.FINISH)
        finishButton.setOnClickListener { act.onFinish() }
        controls.addView(restartButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(specOf(ButtonKind.PRIMARY_START).heightDp)))
        controls.addView(finishButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(specOf(ButtonKind.FINISH).heightDp)))

        // 컨트롤 바탕: 반투명 판(위쪽 모서리만 둥글게). 높이는 보이는 줄 수에 맞춰 감싸고, 줄이 없으면 GONE.
        // 하단 스택(자막 판 아래 칸)에 넣는다. 캐릭터 영역은 이 판의 높이와 무관하게 고정이다
        val r = dp(StageLayout.CONTROL_PANEL_RADIUS_DP).toFloat()   // 위쪽 모서리 반경 32dp(U-2c, 이전 24dp)
        val panelBg = GradientDrawable()
        panelBg.setColor(controlPanelColor())
        panelBg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        controls.background = panelBg
        controlsBg = panelBg
        val padV = dp(StageLayout.CONTROL_PANEL_PAD_DP)
        controls.setPadding(dp(24), padV, dp(24), padV)
        bottomStack.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 반투명 판 색: 무대 배경 아래색에서 파생(알파만 다름) */
    private fun panelColor(): Int = (StageLayout.PANEL_ALPHA shl 24) or (stageColors.bgBottom and 0xFFFFFF)

    /** 컨트롤 패널 색(U-2c): 낮 = 흰색 불투명(대비 계산이 정확하고 몸 윤곽이 비치지 않음), 밤 = 기존과 같은 반투명(PANEL_ALPHA, 배경 아래색) */
    private fun controlPanelColor(): Int {
        val alpha = if (Palette.current.night) StageLayout.PANEL_ALPHA else StageLayout.CONTROL_PANEL_DAY_ALPHA
        return (alpha shl 24) or (stageColors.panelFill and 0xFFFFFF)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        fitLeftColumn()
    }

    /**
     * v3 반복 6(P0): 왼쪽 열을 실제로 잰 높이로 지도 배율을 맞춘다. 지도 외 높이(출처·범례·카드·칩·간격) = 실측, 쓸 수 있는 높이 = 스크롤 칸 높이.
     * 배율은 MIN_FIT_SCALE(1.0)까지 줄이고, 그래도 넘치면 스크롤(겹침·창 밖 없음). 값이 바뀔 때만 다음 프레임에 적용한다(레이아웃 중 변경 금지).
     */
    private fun fitLeftColumn() {
        val map = seatMap ?: return
        if (!split || leftScroll.height <= 0 || leftColumn.width <= 0 || seatLayer.visibility != View.VISIBLE) return
        var contentPx = leftColumn.paddingTop + leftColumn.paddingBottom
        for (i in 0 until leftColumn.childCount) {
            val c = leftColumn.getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as LinearLayout.LayoutParams
            contentPx += c.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        val restDp = (contentPx - map.measuredHeight) / density
        val fit = StageSplit.fit(screenWDp(), leftScroll.height / density, restDp, leftColumn.width / density)
        val key = Triple(fit, leftColumn.width, leftScroll.height)
        if (lastFitKey == key) return
        lastFitKey = key
        lastFit = fit
        android.util.Log.i("CrewGuide", "[무대] 왼쪽 열 실측 칸 ${leftColumn.width}x${leftScroll.height}px 지도 외 ${"%.0f".format(restDp)}dp → 배율 ${"%.2f".format(fit.scale)} 스크롤=${fit.scroll} 폭넘침=${fit.widthOverflow}")
        if (kotlin.math.abs(map.mapScale - fit.scale) < 0.005f) return
        post {
            map.setMapScale(fit.scale)
            val lp = map.layoutParams as LinearLayout.LayoutParams
            lp.width = dpf(StageSeatMap.widthDp() * fit.scale); lp.height = dpf(StageSeatMap.heightDp() * fit.scale)
            map.layoutParams = lp
        }
    }

    /** 화면 높이의 약 14%를 컨트롤 패널 최소 높이로 준다(내용이 더 크면 내용 우선, 상한 CONTROL_PANEL_MAX_DP) */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) {
            lastFit = null; lastFitKey = null   // 폭이 바뀌면 왼쪽 열 맞춤을 다시 잰다
            applySplitZones()
            onWidthChanged?.invoke(w / density)
        }
        controls.minimumHeight = StageLayoutMath.controlPanelMinPx(h, density)
        positionCards()   // 높이가 바뀌면 카드 열 수(1/2열)가 달라질 수 있다
        refreshBubble()   // 화면 크기가 바뀌면 풍선 위치 규칙도 다시 계산(항상 layout을 다시 계산해 뷰에 적용)
        post { refreshBubble() }   // 레이아웃 중에 바꾼 LayoutParams가 반영되도록 레이아웃이 끝난 뒤 한 번 더
    }

    /** 마지막으로 그린 장면·문구로 말풍선 위치를 다시 계산한다(크기·여백이 바뀔 때). 아직 그린 적이 없으면 아무것도 하지 않는다.
     *  force=true이면 직전 위치와 같아도 뷰에 다시 적용한다 */
    private fun refreshBubble() {
        val sc = lastScene ?: return
        if (lastCaption == null) return
        updateBubble(sc)
    }

    /**
     * 풍선 목록(stack)을 화면에 적용한다. 장면별 위치 규칙은 StageBubblePlacement.slotFor, 쌓기·가시성·알파는 BubbleStackRules.layout 한 곳.
     * 강제 갱신: 직전 결과와 같은지 비교하지 않고 매번 layout을 다시 계산해 View에 적용한다(크기·여백 변화, 첫 장면, 준비 직후).
     * 새로 생긴 id의 View만 150ms 등장(scale 0.92→1.0). 이미 있던 풍선은 위치·알파만 즉시 갱신한다(밀림 애니메이션 없음).
     */
    private fun updateBubble(scene: Scene) {
        val st = stack
        val capEmpty = st.items.isEmpty() || (lastCaption ?: "").isBlank()
        when (StageBubble.shouldApplyBubble(bubbleReady, width > 0 && height > 0, capEmpty)) {
            StageBubble.Apply.SKIP -> return
            StageBubble.Apply.HIDE -> {   // 빈 stack·빈 자막이면 모든 풍선을 숨긴다
                cancelBubbleAnim()
                for (entry in bubbleViews.values) entry.view.visibility = View.GONE
                return
            }
            StageBubble.Apply.SHOW -> Unit
        }
        val dens = density
        val geo = splitGeometry()
        val newestText = st.items.last().text
        val hasMap = seatMap != null
        val placement = StageBubblePlacement.slotFor(scene, newestText.length, geo, childCards.size, hasMap, showCloseButton)
        val obstacles = StageBubblePlacement.occupancyFor(scene, geo, childCards.size, hasMap, showCloseButton).all()
        val region = RegionBounds(
            (if (geo.split && ((hasMap && controlsOf(scene, false).showSeats) || AutoCheckPanelRules.panelScene(scene))) geo.seatZoneRightPx else geo.leftInsetPx.toFloat()) + StageBubblePlacement.GAP_DP * dens,
            StageBubblePlacement.areaTopPx(geo) + BubbleStackRules.GAP_DP * dens,
            width - geo.rightInsetPx - StageBubblePlacement.GAP_DP * dens,
        )
        val result = BubbleStackRules.layout(st, placement, BubbleMeasure(dens), region, obstacles, StageBubblePlacement.faceRect(geo))
        lastPlacement = placement
        // 목록에서 사라진 id의 View는 진행 중 애니메이션을 취소하고 제거한다
        val liveIds = result.bubbles.map { it.item.id }.toSet()
        for (id in bubbleViews.keys.filter { it !in liveIds }) removeBubble(id)
        // 1) 실제 줄바꿈·폭·높이를 먼저 잰다 2) 실제 높이로 위쪽에서 다시 쌓는다 3) 실제 폭으로 정렬한다 4) 정렬 후 사각형으로 비가림을 다시 점검한다
        val lastIdx = result.bubbles.size - 1
        val measured = result.bubbles.mapIndexed { index, sb -> measureBubble(sb, index == lastIdx, placement) }
        val restacked = BubbleStackRules.restack(
            measured.map { BubbleStackRules.BubbleSize(it.widthPx.toFloat(), it.bodyPx.toFloat()) },
            result.bubbles[lastIdx].top, BubbleStackRules.GAP_DP * dens, region.top,
        )
        val face = StageBubblePlacement.faceRect(geo)
        val finalLeft = FloatArray(measured.size)
        val finalTop = FloatArray(measured.size)
        val finalVisible = BooleanArray(measured.size)
        val finalAlpha = FloatArray(measured.size)
        var hidden = false
        var rank = 0
        for (i in lastIdx downTo 0) {
            val m = measured[i]
            val alignedPx = BubbleStackRules.alignedLeft(placement, m.sb.item.speaker, m.widthPx.toFloat())
            val topPx = restacked[i].top
            val rect = PxRect(alignedPx, topPx, alignedPx + m.widthPx, topPx + m.bodyPx)
            val newest = i == lastIdx
            val vis = newest || (!hidden && restacked[i].withinLimit && BubbleStackRules.isClear(rect, face, obstacles, region))
            if (!vis) hidden = true
            finalLeft[i] = alignedPx; finalTop[i] = topPx; finalVisible[i] = vis
            finalAlpha[i] = if (vis) BubbleStackRules.alphaForRank(rank) else 0f
            if (vis) rank++
        }
        var visibleCount = 0
        for (i in measured.indices) {
            if (applyBubble(measured[i], finalLeft[i], finalTop[i], finalVisible[i], finalAlpha[i], placement, scene)) visibleCount++
        }
        // 디버그 로그: 풍선 원문(아이 별명 포함 가능)은 남기지 않는다
        android.util.Log.d("CrewGuide", "[무대] 말풍선 장면=$scene 풍선수=${st.items.size} 보이는수=$visibleCount 위치=${placement.slot} 크기=${width}x${height}")
    }

    /** 말풍선 계산용 화면 기하. 2영역이면 캐릭터 칸 왼쪽 끝·고르기 영역 오른쪽 끝을 함께 넘긴다 */
    private fun splitGeometry(): StageGeometry {
        if (!splitActive) return StageGeometry(width, height, density, topPadPx, bottomPadPx, 0, rightPadPx)
        val wDp = width / density
        return StageGeometry(width, height, density, topPadPx, bottomPadPx, 0, rightPadPx,
            charLeftPx = StageSplit.charLeftDp(wDp) * density,
            seatZoneRightPx = StageSplit.leftZoneRightDp(wDp) * density)
    }

    private fun createBubbleEntry(speaker: Speaker): BubbleEntry {
        val bubbleColors = bubbleColorsOf(speaker, Palette.current.night)
        val bgDrawable = BubbleBackground(bubbleColors, density)
        val tv = TextView(context)   // apply 블록을 쓰지 않아 TextView 멤버와 이름이 겹치지 않는다
        tv.textSize = StageLayout.CAPTION_SP   // 44sp (StageFonts CAPTION 글꼴 적용 대상 그대로)
        tv.typeface = StageFonts.typeface(context, StageFonts.FontRole.CAPTION)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Palette.argb(bubbleColors.textRgb))   // 파랑/초록 풍선 위 흰 글자
        tv.background = bgDrawable
        bubbleLayer.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        return BubbleEntry(tv, bgDrawable)
    }

    /** 실제 측정 결과(줄바꿈 후 폭·높이). bodyPx = 줄 수×56+위아래 여백(꼬리 위 돌출·아래 여유 제외), heightPx = 뷰 높이 */
    private class MeasuredBubble(
        val sb: StackedBubble, val created: Boolean, val entry: BubbleEntry, val shown: String, val lineCount: Int,
        val widthPx: Int, val bodyPx: Int, val heightPx: Int, val withTail: Boolean,
    )

    /** 풍선 하나의 글자를 줄바꿈하고 뷰 폭(가장 긴 줄+좌우 여백+꼬리 돌출, 추정 폭 이하)과 높이를 정한다. 위치는 정하지 않는다 */
    private fun measureBubble(sb: StackedBubble, isNewest: Boolean, placement: BubblePlacement): MeasuredBubble {
        val item = sb.item
        val created = !bubbleViews.containsKey(item.id)
        val entry = bubbleViews.getOrPut(item.id) { createBubbleEntry(item.speaker) }
        val tv = entry.view
        val dens = density
        val padH = dp(StageBubblePlacement.INNER_PAD_DP)
        val padV = dp(StageBubblePlacement.PAD_V_DP)
        val reserve = dp(StageBubble.BOTTOM_RESERVE_DP)
        val tailPx = dp(StageBubble.TAIL_EXTENT_DP)
        val tailDir = placement.tail
        val withTail = sb.tail
        val sideExt = if (withTail && tailDir != TailDir.UP) tailPx else 0
        val topExt = if (withTail && tailDir == TailDir.UP) tailPx else 0
        // 폭: 꼬리가 옆에 붙는 풍선은 꼬리 돌출만큼 넓히되 slot 최대 폭을 넘기지 않는다
        val widthCapPx = min(sb.width.toInt() + sideExt, placement.maxWidth.toInt()).coerceAtLeast(1)
        tv.setPadding(
            padH + (if (withTail && tailDir == TailDir.LEFT) tailPx else 0),
            padV + topExt,
            padH + (if (withTail && tailDir == TailDir.RIGHT) tailPx else 0),
            padV + reserve,
        )
        // 글자: 공백 기준 줄바꿈(wrapByWords). 글자 폭 = 풍선 폭 − 좌우 안쪽 여백 − 꼬리 돌출. measure는 이 TextView의 Paint
        val textMaxW = (widthCapPx - 2 * padH - sideExt).coerceAtLeast(1)
        val maxLines = if (isNewest) max(1, placement.allowedLines) else StageBubblePlacement.MAX_LINES
        var wrapped = wrapByWords(item.text, textMaxW.toFloat(), { s -> tv.paint.measureText(s) }, maxLines)
        // P1-B: 큰 글꼴에서 잘리면 글자를 줄이지 않고 줄 수를 늘린다(음성 꺼짐·TTS 실패 때 화면 문장이 대체 안내라 잘리면 안 됨)
        val fontScale = context.resources.configuration.fontScale
        val largeLines = StageBubblePlacement.linesForFontScale(maxLines, fontScale, wrapped.truncated)
        if (largeLines > maxLines) wrapped = wrapByWords(item.text, textMaxW.toFloat(), { s -> tv.paint.measureText(s) }, largeLines)
        val shown = wrapped.lines.joinToString("\n")
        val lineCount = max(1, wrapped.lines.size)
        // 실제 폭 = 가장 긴 줄 + 좌우 여백 + 꼬리 돌출. 줄바꿈 폭(widthCapPx) 이하이므로 줄바꿈 결과는 달라지지 않는다. 최소 폭은 layout과 같은 MIN_WIDTH_DP(+꼬리)
        val longest = wrapped.lines.maxOfOrNull { tv.paint.measureText(it) } ?: 0f
        val contentW = ceil(longest.toDouble()).toInt() + 1 + 2 * padH + sideExt
        val minW = (BubbleStackRules.MIN_WIDTH_DP * dens).toInt() + sideExt
        val widthPx = min(widthCapPx, max(minW, contentW))
        val bodyPx = (lineCount * StageBubblePlacement.lineHeightPx(dens, tv.lineHeight) + 2 * StageBubblePlacement.PAD_V_DP * dens).toInt()
        val heightPx = bodyPx + topExt + reserve
        return MeasuredBubble(sb, created, entry, shown, lineCount, widthPx, bodyPx, heightPx, withTail)
    }

    /** 풍선 하나를 정해진 위치·가시성대로 적용한다. 보이면 true. 새로 만든 View(새 id)이고 보이면 150ms 등장 */
    private fun applyBubble(
        m: MeasuredBubble, leftPx: Float, topPx: Float, shouldShow: Boolean, alphaValue: Float, placement: BubblePlacement, scene: Scene,
    ): Boolean {
        val item = m.sb.item
        val created = m.created
        val entry = m.entry
        val tv = entry.view
        val widthPx = m.widthPx
        val heightPx = m.heightPx
        val withTail = m.withTail
        val tailDir = placement.tail
        if (tv.text.toString() != m.shown) tv.text = m.shown
        tv.maxLines = m.lineCount
        // 최신 풍선의 top은 slotFor 값 그대로, 이전 풍선은 restack 결과(실제 높이 기준)
        val viewLeft = leftPx.toInt()
        val viewTop = topPx.toInt()
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        if (lp.width != widthPx || lp.height != heightPx || lp.leftMargin != viewLeft || lp.topMargin != viewTop) {
            lp.width = widthPx; lp.height = heightPx
            lp.leftMargin = viewLeft; lp.topMargin = viewTop
            tv.layoutParams = lp
        }
        // 꼬리는 tail=true(최신 승무원 풍선)만. 목표(입)는 화면 좌표라 이 뷰의 위치를 빼서 뷰 안 좌표로 바꾼다
        entry.bg.setHasTail(withTail)
        if (withTail) entry.bg.setTail(tailDir, placement.targetX - viewLeft, placement.targetY - viewTop)
        // 크기 변화의 기준점: 꼬리 쪽 가장자리(꼬리 없으면 가운데)
        tv.pivotX = if (!withTail) widthPx / 2f else when (tailDir) {
            TailDir.LEFT -> 0f
            TailDir.RIGHT -> widthPx.toFloat()
            TailDir.UP -> widthPx / 2f
        }
        tv.pivotY = if (withTail && tailDir == TailDir.UP) 0f else heightPx / 2f
        tv.contentDescription = when (item.speaker) {
            Speaker.CREW -> item.text
            Speaker.GUARDIAN -> BubbleStackRules.GUARDIAN_TEXT
        }
        if (!shouldShow) {
            tv.animate().cancel()
            tv.visibility = View.GONE
            return false
        }
        tv.visibility = View.VISIBLE
        tv.alpha = alphaValue
        if (StageBubble.shouldAnimateIn(created, true, isShown, ValueAnimator.areAnimatorsEnabled(), scene)) {
            tv.animate().cancel()
            tv.scaleX = StageBubble.BUBBLE_IN_SCALE
            tv.scaleY = StageBubble.BUBBLE_IN_SCALE
            tv.animate().scaleX(1f).scaleY(1f).setDuration(StageBubble.BUBBLE_IN_MS).start()
        }
        return true
    }

    private fun removeBubble(id: Long) {
        val entry = bubbleViews.remove(id) ?: return
        entry.view.animate().cancel()
        bubbleLayer.removeView(entry.view)
    }

    /** 진행 중인 등장 움직임을 모두 취소하고 원래 크기로 돌린다(숨김·분리·즉시 표시) */
    private fun cancelBubbleAnim() {
        for (entry in bubbleViews.values) {
            entry.view.animate().cancel()
            entry.view.scaleX = 1f
            entry.view.scaleY = 1f
        }
    }

    /** 카드 열 감김·위치를 다시 정한다(순수 함수 ChildCardLayout.cardPositions). 카드가 없으면 아무것도 하지 않는다 */
    private fun positionCards() {
        if (childCards.isEmpty() || split) return   // 2영역: 카드는 왼쪽 열의 가로 줄(LinearLayout)이 배치한다
        val availDp = ChildCardLayout.availableHeightDp(height / density, topPadPx / density, bottomPadPx / density)
        for (p in ChildCardLayout.cardPositions(childCards.size, availDp)) {
            val card = childCards[p.index].second
            val lp = card.layoutParams as FrameLayout.LayoutParams
            if (lp.leftMargin != dp(p.xDp) || lp.topMargin != dp(p.yDp)) {
                lp.leftMargin = dp(p.xDp); lp.topMargin = dp(p.yDp)
                card.layoutParams = lp
            }
        }
    }

    /** 버튼 종류의 사양(specOf)을 입힌다: 알약 바탕(채움/윤곽), 글자, 왼쪽 아이콘(글자색과 같은 색). 문구·클릭은 건드리지 않는다 */
    private fun applySpec(b: IconButton, kind: ButtonKind) {
        val spec = specOf(kind)
        val night = Palette.current.night
        val ink = Palette.argb(StageButtons.colorOf(spec.textRole, night))
        // 알약 본체 + 아래 가장자리는 IconButton이 직접 그린다(본체 = 전체 − 가장자리, 눌림은 가장자리 두께만큼 내려감)
        val fillArgb = if (spec.fillRole == ColorRole2.NONE) 0 else Palette.argb(StageButtons.colorOf(spec.fillRole, night))
        val edgeArgb = if (spec.edgeDp > 0 && spec.fillRole != ColorRole2.NONE) Palette.argb(StageButtons.edgeColorFor(spec, night)) else 0
        val strokeDpNow = StageButtons.strokeDpFor(kind, night)   // 낮 비활성 [시작]만 윤곽 추가(U-2c)
        b.setPill(fillArgb, edgeArgb, dp(spec.edgeDp), if (strokeDpNow > 0) dp(strokeDpNow) else 0, ink)
        b.textSize = spec.textSp.toFloat()
        b.typeface = StageFonts.typeface(b.context, StageFonts.FontRole.BIG_BUTTON)   // 큰 버튼 5종(예·아니오·시작·다시 시작·끝내기)
        b.isAllCaps = false
        b.setTextColor(ink)
        b.stateListAnimator = null   // 기본 버튼의 그림자(고도) 제거
        b.minHeight = dp(spec.heightDp); b.minimumHeight = dp(spec.heightDp)
        b.minWidth = 0; b.minimumWidth = 0
        b.setPadding(dp(StageButtons.PAD_DP), 0, dp(StageButtons.PAD_DP), dp(spec.edgeDp))   // 아래 여백 = 가장자리 → 글자·아이콘이 본체 가운데
        if (spec.iconKind == IconKind.NONE) {
            b.setCompoundDrawablesRelative(null, null, null, null)
        } else {
            val size = dp(StageButtons.iconBoxDp(spec.iconKind, spec.heightDp))   // 체크·엑스는 원 지름(높이의 46%), 재생은 40%
            val icon = if (StageButtons.isCircled(spec.iconKind)) {
                // 원 안의 기호(Path). 원·기호·테두리 색은 StageButtons가 Palette에서 읽는다
                val ringRgb = StageButtons.iconRingColor(spec.iconKind, night)
                IconDrawable(
                    spec.iconKind,
                    Palette.argb(StageButtons.iconSymbolColor(spec.iconKind, night)),
                    Palette.argb(StageButtons.iconCircleColor(spec.iconKind, night)),
                    if (ringRgb != 0) Palette.argb(ringRgb) else 0,
                )
            } else {
                IconDrawable(spec.iconKind, ink)   // 재생: 현재 모양·글자색 유지
            }
            icon.setBounds(0, 0, size, size)
            b.setCompoundDrawablesRelative(icon, null, null, null)
            b.compoundDrawablePadding = dp(StageButtons.ICON_GAP_DP)
        }
        b.invalidate()
    }

    /** 아이 칩: PICK·READY에서 프로필이 1명 이상일 때만 보인다. 칩 목록이 바뀔 때만 다시 만들고, 선택 표시는 매번 갱신한다 */
    private fun renderChildChips(scene: Scene, act: StageActions) {
        val chips = act.profileChips()
        val show = chipsVisible(scene, chips.size)
        cardLayer.visibility = visIf(show && !split)
        if (split) cardsRow.visibility = visIf(show)
        if (!show) { childBox.visibility = View.GONE; return }
        val showLast = act.lastChipVisible()
        childBox.visibility = visIf(showLast)
        val sig = chips.joinToString("|") { "${it.id}:${it.label}" } + (if (showLast) "|L" else "")
        if (sig != childSig) {
            childSig = sig
            // 얼굴 카드: 목록(id·별명)이 바뀔 때만 다시 만든다. 카드 순서가 파스텔 배경 순서
            cardLayer.removeAllViews()
            cardsRow.removeAllViews()
            childCards.clear()
            for ((i, ch) in chips.withIndex()) {
                val key = ch.id
                val card = ChildCardView(context, ChildCardLayout.avatarBg(i), split) { act.onToggleChild(key) }
                childCards.add(Pair(key, card))
                if (split) {
                    // 반복 6: 카드 폭·줄 수는 왼쪽 영역 폭에 맞춘다(StageSplit.cardWidthDp/cardsPerRow). 1600dp 아이 4명 = 163dp × 4, 양옆 16dp
                    val zoneW = StageSplit.leftZoneWidthDp(screenWDp())
                    val perRow = StageSplit.cardsPerRow(zoneW, chips.size)
                    val col = i % perRow
                    if (col == 0) {
                        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP }
                        cardsRow.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (i > 0) topMargin = dp(StageSplit.CARD_GAP_DP)
                        })
                    }
                    val line = cardsRow.getChildAt(cardsRow.childCount - 1) as LinearLayout
                    line.addView(card, LinearLayout.LayoutParams(dp(StageSplit.cardWidthDp(zoneW, chips.size)), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (col > 0) leftMargin = dp(StageSplit.CARD_GAP_DP)
                    })
                } else {
                    cardLayer.addView(card, FrameLayout.LayoutParams(dp(ChildCardLayout.CARD_W_DP), dp(ChildCardLayout.CARD_H_DP)))
                }
            }
            positionCards()
            // '지난번 아이 그대로': 기존 글자 칩 그대로 하단 컨트롤 패널에 둔다(한 줄, 폭은 6칸 중 2칸)
            childBox.removeAllViews()
            childChips.clear()
            if (showLast) {
                val line = LinearLayout(context)
                line.orientation = LinearLayout.HORIZONTAL
                if (!split) line.weightSum = StageControlLayout.CHIP_ROW_SLOTS.toFloat()
                // v3 반복 3(P1-6): 왼쪽에 치우쳐 아래 [보호자 확인 시작](가운데)과 축이 어긋나 보이던 것 → 가운데 정렬로 한 축에 둔다
                line.gravity = Gravity.CENTER_HORIZONTAL
                val chip = makeButton(ProfileUi.LAST_BTN, StageControlLayout.CHIP_SP, StageControlLayout.CHIP_MIN_DP) { act.onUseLast() }
                chip.setSingleLine(true)
                chip.ellipsize = TextUtils.TruncateAt.END
                chip.setPadding(0, 0, 0, 0)
                childChips.add(Pair(LAST_KEY, chip))
                if (split) {
                    // 왼쪽 열: 글자 폭만큼(큰 글꼴에서도 잘리지 않게), 좌우 여백 24dp
                    chip.setPadding(dp(24), 0, dp(24), 0)
                    line.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(StageControlLayout.CHIP_MIN_DP)))
                } else {
                    line.addView(chip, LinearLayout.LayoutParams(0, dp(StageControlLayout.CHIP_MIN_DP), StageControlLayout.LAST_CHIP_SLOTS.toFloat()))
                }
                childBox.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val scheme = Palette.current
        // 선택 표시는 매번 갱신한다(4차 결정 A: 카드에 꾸밈 그림을 그리지 않으므로 ChipInfo.style은 쓰지 않는다)
        for ((key, card) in childCards) {
            val info = chips.firstOrNull { it.id == key } ?: continue
            card.bind(info.label, info.selected, scheme.night)
        }
        for ((_, chip) in childChips) {
            chip.background = chipBg(Palette.argb(scheme.btnOffBg), false)
            chip.setTextColor(Palette.argb(scheme.btnOffText))
        }
    }

    private fun renderControls(scene: Scene) {
        val act = actions ?: return
        val c = controlsOf(scene, act.canStart())
        val scheme = Palette.current
        renderChildChips(scene, act)
        controls.visibility = visIf(c.anyVisible)
        yesNoRow.visibility = visIf(c.showYesNo)
        yesNote.visibility = visIf(c.showYesNo)
        replayButton?.let { b -> b.visibility = visIf(c.showYesNo && replayWanted); b.isEnabled = replayWanted }
        if (c.showYesNo) yesNote.text = if (reconnectNote) "음성 다시 연결 중 · 터치로 답할 수 있어요" else StageControlText.YES_NOTE
        seatRow.visibility = visIf(c.showSeats)
        seatLayer.visibility = visIf(c.showSeats && seatMap != null)   // 지도는 좌석 줄과 같은 장면(controlsOf.showSeats)에서만
        startButton.visibility = visIf(c.showStart)
        startButton.isEnabled = c.startEnabled
        val sk = startKind(c.startEnabled)   // 비활성은 흐린 색(알파 대신 DISABLED_START 색, 글자 대비 유지)
        if (sk != lastStartKind) { lastStartKind = sk; applySpec(startButton, sk) }
        startButton.alpha = 1f
        restartButton.visibility = visIf(c.showRestart)
        finishButton.visibility = visIf(c.showFinish)
        if (c.showFinish) {
            // r7(결정 47): 하차 안내 화면(도착·중간 하차)만 [다 내렸어요]
            val label = SeatBoardRules.finishLabelFor(scene, lastState?.autoCheck, finishLabelOf(scene)) ?: finishLabelOf(scene)
            if (finishButton.text.toString() != label) { finishButton.text = label; finishButton.contentDescription = label }
        }
        if (c.showSeats) {
            val isMock = act.isBeltMock()
            seatSourceView?.let { tv ->
                tv.text = StageSeatSignal.sourceCaption(isMock, act.seatNames().map { act.seatBeltRef(it) }, act.isBeltInjected())
                // 2026-11-11 U3: 18sp·굵게, 불투명 카드 바탕(무대 배경 그라데이션 위에서도 대비 유지)
                val roles = DuoTokens.roles(scheme.night)
                tv.setTextColor(Palette.argb(roles.text))
                tv.background = DuoDraw.round(roles.surface, dp(16).toFloat(), dp(2), roles.surfaceStroke)
                for (chip in legendChips) chip.state?.let { chip.bind(it, scheme.night) }
            }
            pickSummary?.let { tv ->
                val chips = act.profileChips()
                val seatDescs = act.seatNames().filter { act.seatSelected(it) }.map { n -> StageSeatMap.SLOTS.firstOrNull { it.name == n }?.descKo ?: n }
                tv.text = StagePickSummary.text(seatDescs, chips.count { it.selected }, chips.isNotEmpty(), c.startEnabled, mapOnLeft = split)
                tv.setTextColor(Palette.argb(stageColors.caption))
            }
            seatMap?.bind(
                selectedOf = { n -> act.seatSelected(n) },
                isNight = scheme.night,
                beltRefOf = { n -> act.seatBeltRef(n) },
                occupiedOf = { n -> act.seatOccupied(n) },
            )
            for ((n, chip) in seatChips) {
                val on = act.seatSelected(n)
                chip.text = if (on) "✔$n" else n
                chip.background = chipBg(Palette.argb(if (on) scheme.btnOnBg else scheme.btnOffBg), on)
                chip.setTextColor(Palette.argb(if (on) scheme.btnOnText else scheme.btnOffText))
            }
        }
    }

    init {
        applyBackdrop(Scene.PICK)   // 첫 render 전 기본 배경(PICK 값 = 이전과 같음). 이후는 render가 장면이 바뀔 때만 갱신
        isClickable = true   // 오버레이 아래 화면으로 터치가 새지 않게 한다

        // 겹침 구조(z 순서 아래→위): ① 배경 그라데이션(이 뷰의 background) ①-B 기내 창문 장식 ② 캐릭터 영역(스포트라이트+캐릭터)
        // ③ 컨트롤 바탕 ④ 자막 판(③ 바로 위, 같은 하단 스택, 라벨 띠 바로 위) ⑤ 상단 바 ⑥ 보조 라벨 띠(맨 아래, 불투명, 겹치는 것 없음).
        // 캐릭터 영역은 상단 바 아래~라벨 띠 위로 고정이라 장면이 바뀌어도 움직이지 않고, 나머지는 위에 겹치기만 한다.
        cabinDecor.bind(Palette.current.night, false)
        addView(cabinDecor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        zonePanel.visibility = View.GONE
        zonePanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(zonePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        charHolder.addView(spotlight, FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER))
        spotlight.background = spotDrawable
        // fitInside: 뷰는 영역을 꽉 채우고(MATCH_PARENT) 그림만 안에서 min(w,h) 정사각으로 가운데에 맞춘다(크기 계산식 없음)
        (renderer as? SvgCharacterView)?.fitInside = true
        (renderer as? SvgCharacterView)?.viewport = StageLayout.CHARACTER_VIEWPORT
        charHolder.addView(charView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(charHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 아이 얼굴 카드 층: 캐릭터 영역과 같은 구역(여백은 applySafeArea), 캐릭터 위·자막 판/컨트롤 아래. 카드 밖 터치는 아래로 통과한다
        cardLayer.visibility = View.GONE
        addView(cardLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 좌석 지도 층: 카드 층과 같은 구역(오른쪽 위), 지도는 setupControls가 넣는다. 지도 밖 터치는 아래로 통과한다
        seatLayer.visibility = View.GONE
        addView(seatLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 자동 확인 패널 층: 좌석 지도 층과 같은 구역(2영역 왼쪽). 카드 바탕 안에서 넘치면 세로 스크롤(글꼴 2.0·7석)
        autoScroll.isFillViewport = false
        autoScroll.isVerticalScrollBarEnabled = true
        autoScroll.isScrollbarFadingEnabled = false   // 넘칠 때 스크롤 막대가 계속 보여 '아래에 더 있음'을 알린다
        autoScroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        autoScroll.addView(autoPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val autoPad = dp(DuoTokens.CARD_PAD_DP)
        autoLayer.setPadding(autoPad, autoPad, dp(12), autoPad)
        autoLayer.addView(autoScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        autoLayer.visibility = View.GONE
        addView(autoLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        charView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> onCharAreaLaidOut(r - l, b - t) }

        // 풍선 View(TextView + BubbleBackground)는 render → updateBubble이 id별로 만들어 bubbleLayer에 넣는다(여기서는 층만 붙인다).
        // 롤백: 풍선 View 목록을 단일 captionPanel(WRAP_CONTENT, gravity별 마진)로 되돌리고 BubbleBackground를 이전 판으로 되돌린다
        addView(bubbleLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))   // 카드·지도 층 위, 컨트롤(bottomStack) 아래
        bottomStack.orientation = LinearLayout.VERTICAL

        // 보조 라벨: 화면 맨 아래 전용 불투명 띠(높이 SUB_LABEL_DP + 하단 인셋, 배경 = 무대 아래색 알파 FF) 안 한 줄.
        // 글자색은 자막과 같은 stageColors.caption(대비 4.5 이상이 확인된 색). 띠는 컨트롤 유무와 무관하게 항상 보인다
        subLabel = TextView(context).apply {
            textSize = StageLayout.SUB_SP
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 2026-11-13 글꼴 2.0 캡처: 글자 높이 44px > 띠 안쪽 40px(위아래 잘림) → 글꼴 여백을 빼고, 그래도 넘치면 띠 높이에 맞게 글자를 줄인다
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(12, StageLayout.SUB_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)   // 하한 12sp
            setTextColor(Palette.argb(stageColors.caption))
            // 띠 색 = 현재 장면 배경에서 고른 색(bandRgbOf). 이후 장면이 바뀌면 applyBackdrop이 갱신한다.
            // 롤백: 아래 줄을 setBackgroundColor(Palette.argb(stageColors.bgBottom))로 되돌리고 applyBackdrop의 띠 갱신 줄을 지운다
            setBackgroundColor(Palette.argb(bandRgbOf(lastBackdrop ?: backdropOf(Scene.PICK, Palette.current.night))))
        }
        subLabelReady = true

        setupControls()   // 컨트롤 바탕을 하단 스택(자막 판 아래 칸)에 넣는다
        addView(bottomStack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // 2026-11-11 U4: 스티커 획득 순간 배지. 표시 전용
        // v4(2026-11-12): 왼쪽 아래(컨트롤 패널 위, 작고 시선 밖) → 왼쪽 위(말풍선과 같은 높이대, 시선이 먼저 가는 자리)로 옮기고 크게.
        // 지급 데이터는 그대로(StickerMoment가 상태를 읽기만 함). 롤백: Gravity.BOTTOM·bottomMargin 원래 값으로
        addView(stickerBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(StickerMoment.EDGE_DP); topMargin = dp(StickerMoment.TOP_DP)
        })
        addView(lockCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(StickerMoment.EDGE_DP); topMargin = dp(StickerMoment.TOP_DP)
        })
        addView(headlineCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(StickerMoment.EDGE_DP); topMargin = dp(StickerMoment.TOP_DP)
        })
        // 좌석 화면 하나: 위쪽 바(진행 단계·설정) 아래 전체를 덮는다. 누름은 계약 동작으로만 보낸다
        seatStage.visibility = View.GONE
        seatStage.onSeatTap = { seat -> actions?.onSeatTap(seat) }
        seatStage.onGuardian = { seat -> actions?.onGuardianConfirmSeat(seat) }
        seatStage.onFinish = { actions?.onFinish() }
        seatStage.onAnswer = { arrived -> actions?.onArrivalAnswer(arrived) }   // r5 추가 A(계약 v5): 도착 질문 대답
        seatStage.onAlightAnswer = { alight -> actions?.onAlightAnswer(alight) }   // r6(계약 v6): "내릴까요?" 대답
        seatStage.onNewTrip = { actions?.onNewTripFromAllLeft() }   // J2(결정 34): 모두 내림 + 다시 앉음 → [새로 출발 확인]
        addView(seatStage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 우상단 [미리보기 닫기] (미리보기 전용): 작고 조용한 윤곽선 알약
        val close = makeQuietButton(StageText.CLOSE_BUTTON, null) { onClose() }
        if (StageFlags.NEW_STAGE && !showCloseButton) close.visibility = View.GONE   // 기본 무대에서는 닫을 수 없다(예전 화면이 드러나지 않게)
        // 우상단 줄: [설정](정차 P·준비/대기/이동 끝에서만) + [미리보기 닫기]. 겹치지 않게 한 줄 안에 나란히 둔다
        val topBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val act = actions
        if (act != null) {
            val mf = makeQuietButton(StageControlText.MANUAL_FINISH, null) { act.onManualFinish() }
            mf.visibility = View.GONE
            manualFinishButton = mf
            val mfLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            mfLp.rightMargin = dp(StageUiTokens.QUIET_BUTTON_GAP_DP)
            topBar.addView(mf, mfLp)
            val st = makeSettingsIconButton(StageControlText.SETTINGS_DESC) { act.onOpenSettings() }
            st.visibility = View.GONE
            settingsButton = st
            val sizePx = dp(StageUiTokens.SETTINGS_BUTTON_DP)
            val stLp = LinearLayout.LayoutParams(sizePx, sizePx)
            stLp.rightMargin = dp(StageUiTokens.QUIET_BUTTON_GAP_DP)
            topBar.addView(st, stLp)
        }
        topBar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 상단 바(투명): 높이 TOP_BAR_DP 고정, 진행 표시(점 5개+연결선)는 가운데, [설정]·[미리보기 닫기]는 오른쪽
        val dotRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val cellPx = dp(StageUiTokens.DOT_CURRENT_RING_DP)
        for ((i, name) in StageSteps.NAMES.withIndex()) {
            val cell = FrameLayout(context)   // 현재 점의 바깥 링은 이 칸의 배경으로 그린다(28dp)
            val d = View(context)
            d.contentDescription = name
            dots.add(d)
            dotCells.add(cell)
            val dotPx = dp(StageUiTokens.DOT_DP)
            cell.addView(d, FrameLayout.LayoutParams(dotPx, dotPx, Gravity.CENTER))
            dotRow.addView(cell, LinearLayout.LayoutParams(cellPx, cellPx))
            if (i < StageSteps.NAMES.size - 1) {
                val ln = View(context)
                dotLines.add(ln)
                val llp = LinearLayout.LayoutParams(dp(StageUiTokens.DOT_LINE_DP), dp(StageUiTokens.DOT_LINE_THICK_DP))
                llp.gravity = Gravity.CENTER_VERTICAL
                dotRow.addView(ln, llp)
            }
        }
        topBarFrame.addView(dotRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        if (StageFlags.NEW_STAGE) dotRow.visibility = View.GONE   // 단계 점은 앱 전체에서 쓰지 않는다(사용자 결정 2026-11-07)
        // 2026-11-13: 비어 있던 상단 바 가운데에 자동 흐름 진행 단계(자동 패널 장면에서만, 잠금에서는 숨김)
        topBarFrame.addView(phaseSteps, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        topBarFrame.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(16) })
        addView(topBarFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(StageLayout.TOP_BAR_DP), Gravity.TOP))
        addView(subLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(StageLayout.SUB_LABEL_DP), Gravity.BOTTOM))

        // 안전 영역: 시스템 바 인셋을 읽되, 분할창(Pleos [853,90][2535,1300] 등)에서 이미 창 위치가 시스템 바 안쪽이면 이중 차감하지 않는다
        applySafeArea(0, 0)
        setOnApplyWindowInsetsListener { _, insets ->
            try {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val dm = resources.displayMetrics
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                val bounds = try { wm?.currentWindowMetrics?.bounds } catch (_: Throwable) { null }
                val realMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                try { wm?.defaultDisplay?.getRealMetrics(realMetrics) } catch (_: Throwable) {}
                val dispW = max(max(dm.widthPixels, bounds?.width() ?: 0), realMetrics.widthPixels)
                val dispH = max(max(dm.heightPixels, bounds?.height() ?: 0), realMetrics.heightPixels)
                val winTop = loc[1]
                val winBottom = if (height > 0) loc[1] + height else 0
                val winRight = if (width > 0) loc[0] + width else 0
                val (effTop, effBottom) = StageLayoutMath.effectiveSystemInsets(winTop, winBottom, dispH, bars.top, bars.bottom)
                val effRight = StageLayoutMath.effectiveRightInset(winRight, dispW, bars.right)
                applySafeArea(effTop, effBottom, effRight)
            } catch (e: Exception) {
                applySafeArea(0, 0)
            }
            insets
        }
        bubbleReady = true   // 풍선 목록·층이 모두 만들어진 뒤에만 updateBubble이 동작한다
        refreshBubble()      // 준비 직후 현재 장면으로 적용(아직 그린 장면이 없으면 아무것도 하지 않음)
    }

    /** 상단·하단 안전 여백을 정해 상단 바·캐릭터 영역 위쪽·보조 라벨·하단 스택에 반영한다(값이 같으면 건드리지 않음) */
    private fun applySafeArea(topInset: Int, bottomInset: Int, rightInset: Int = 0) {
        val t = if (topInset > 0) topInset else dp(StageLayout.INSET_FALLBACK_DP)
        val b = if (bottomInset > 0) bottomInset else dp(StageLayout.INSET_FALLBACK_DP)
        val rt = if (rightInset > 0) rightInset else 0
        if (t == topPadPx && b == bottomPadPx && rt == rightPadPx) return
        topPadPx = t; bottomPadPx = b; rightPadPx = rt
        val barLp = topBarFrame.layoutParams as FrameLayout.LayoutParams
        barLp.topMargin = t
        topBarFrame.layoutParams = barLp
        val holderLp = charHolder.layoutParams as FrameLayout.LayoutParams
        val bandPx = dp(StageLayout.SUB_LABEL_DP) + b   // 라벨 띠 높이(장면과 무관한 고정값)
        holderLp.topMargin = t + dp(StageLayout.TOP_BAR_DP)
        holderLp.bottomMargin = bandPx                   // 캐릭터 영역은 띠 위에서 끝난다
        charHolder.layoutParams = holderLp
        val cardLp = cardLayer.layoutParams as FrameLayout.LayoutParams
        cardLp.topMargin = holderLp.topMargin
        cardLp.bottomMargin = bandPx
        cardLayer.layoutParams = cardLp
        val seatLp = seatLayer.layoutParams as FrameLayout.LayoutParams
        seatLp.topMargin = holderLp.topMargin
        seatLp.bottomMargin = bandPx
        seatLayer.layoutParams = seatLp   // 2영역 왼쪽 영역 폭·위치는 applySplitZones가 현재 폭으로 정한다(반복 6)
        val autoLp = autoLayer.layoutParams as FrameLayout.LayoutParams
        autoLp.topMargin = holderLp.topMargin
        autoLp.bottomMargin = bandPx
        autoLayer.layoutParams = autoLp
        val boardLp = seatStage.layoutParams as FrameLayout.LayoutParams
        boardLp.topMargin = holderLp.topMargin
        boardLp.bottomMargin = b          // 아래 꼬리 띠(subLabel) 자리까지 덮고, 고지 한 줄은 좌석 화면이 직접 쓴다
        boardLp.rightMargin = rt
        seatStage.layoutParams = boardLp
        val mapView = seatMap
        if (mapView != null && !split) {
            val mapLp = mapView.layoutParams as FrameLayout.LayoutParams
            mapLp.rightMargin = dp(StageSeatMap.MARGIN_RIGHT_DP) + rt   // 오른쪽 여백 16dp + 시스템 인셋
            mapView.layoutParams = mapLp
        }
        val srcView = seatLegend
        if (srcView != null && !split) {
            val srcLp = srcView.layoutParams as FrameLayout.LayoutParams
            srcLp.rightMargin = dp(StageSeatMap.MARGIN_RIGHT_DP) + rt
            srcView.layoutParams = srcLp
        }
        positionCards()
        val labelLp = subLabel.layoutParams as FrameLayout.LayoutParams
        labelLp.height = bandPx
        subLabel.layoutParams = labelLp
        subLabel.setPadding(dp(24), 0, dp(24), b)
        val stackLp = bottomStack.layoutParams as FrameLayout.LayoutParams
        stackLp.bottomMargin = dp(StageLayout.SUB_LABEL_DP) + b
        bottomStack.layoutParams = stackLp
        applySplitZones()
        refreshBubble()   // 안전 여백이 바뀌면 얼굴·카드·지도 위치가 달라지므로 말풍선 위치도 다시 계산
    }

    /**
     * 2영역 여백 적용(값이 같으면 건드리지 않음): splitActive이면 캐릭터 칸 = charLeftDp ~ 오른쪽 끝, 행동 띠 = 오른쪽 영역 폭.
     * 아니면 둘 다 전체 폭(반복 4와 같음). 고르기 층(seatLayer)은 좌석 장면에서만 보이므로 늘 왼쪽 영역에 둔다
     */
    private fun applySplitZones() {
        if (split) {
            // 반복 6(P0): 고르기 층(왼쪽 영역)·범례 폭을 '지금' 폭으로. 예전에는 처음 폭에 고정돼 창이 줄면 영역 경계를 넘었다
            val seatLp = seatLayer.layoutParams as? FrameLayout.LayoutParams
            if (seatLp != null) {
                val wantLeft = dpf(StageSplit.leftZoneLeftDp())
                val wantW = dpf(StageSplit.leftZoneWidthDp(screenWDp()))
                if (seatLp.leftMargin != wantLeft || seatLp.width != wantW || seatLp.gravity != (Gravity.TOP or Gravity.START)) {
                    seatLp.leftMargin = wantLeft; seatLp.width = wantW; seatLp.gravity = Gravity.TOP or Gravity.START
                    seatLayer.layoutParams = seatLp
                }
            }
            val lg = seatLegend
            val lgLp = lg?.layoutParams as? LinearLayout.LayoutParams
            if (lg != null && lgLp != null) {
                val wantLg = dpf(StageSplit.legendWidthDp(screenWDp()))
                if (lgLp.width != wantLg) { lgLp.width = wantLg; lg.layoutParams = lgLp }
            }
        }
        // 2026-11-13 자동 확인 패널: 2영역이면 왼쪽 영역(지도와 같은 자리·같은 카드), 아니면 왼쪽 위 절반(좁은 창 폴백, 미검증 크기)
        val autoLp = autoLayer.layoutParams as? FrameLayout.LayoutParams
        if (autoLp != null) {
            val wantLeft = if (split) dpf(StageSplit.leftZoneLeftDp()) else dp(StageLayout.INSET_FALLBACK_DP)
            val wantW = if (split) dpf(StageSplit.leftZoneWidthDp(screenWDp())) else dpf(screenWDp() / 2f)
            if (autoLp.leftMargin != wantLeft || autoLp.width != wantW || autoLp.gravity != (Gravity.TOP or Gravity.START)) {
                autoLp.leftMargin = wantLeft; autoLp.width = wantW; autoLp.gravity = Gravity.TOP or Gravity.START
                autoLayer.layoutParams = autoLp
            }
            autoLayer.background = V6Panels.card(density, Palette.current.night)
        }
        // 반복 6(P1-3): 좌석 장면(2영역)에서는 고르기 영역 뒤 창문 장식을 그리지 않는다
        cabinDecor.setPickZone(if (splitActive) StageSplit.leftZoneRightDp(screenWDp()) / screenWDp() else null)
        // V6(S02): 2영역 좌석 장면에서 왼쪽 = 카드, 오른쪽 캐릭터 = 둥근 무대 패널(표시만, 배치·터치 불변)
        seatLayer.background = if (splitActive) V6Panels.card(density, Palette.current.night) else null
        // 둥근 무대 패널은 오른쪽 영역 전체(행동 띠와 같은 폭)를 덮는 장식 뷰. 캐릭터·말풍선 배치는 그대로
        zonePanel.visibility = visIf(splitActive)
        if (splitActive) {
            zonePanel.background = V6Panels.stagePanel(density, Palette.current.night)
            val zLp = zonePanel.layoutParams as FrameLayout.LayoutParams
            val hLp = charHolder.layoutParams as FrameLayout.LayoutParams
            val wantL = dpf(StageSplit.rightZoneLeftDp(screenWDp())); val wantR = dpf(StageSplit.OUTER_DP.toFloat()) + rightPadPx
            if (zLp.leftMargin != wantL || zLp.rightMargin != wantR || zLp.topMargin != hLp.topMargin || zLp.bottomMargin != hLp.bottomMargin) {
                zLp.leftMargin = wantL; zLp.rightMargin = wantR; zLp.topMargin = hLp.topMargin; zLp.bottomMargin = hLp.bottomMargin
                zonePanel.layoutParams = zLp
            }
        }
        val holderLp = charHolder.layoutParams as FrameLayout.LayoutParams
        val wantHolderLeft = if (splitActive) dpf(StageSplit.charLeftDp(screenWDp())) else 0
        if (holderLp.leftMargin != wantHolderLeft) {
            holderLp.leftMargin = wantHolderLeft
            charHolder.layoutParams = holderLp
        }
        val stackLp = bottomStack.layoutParams as FrameLayout.LayoutParams
        // 2영역: 행동 띠를 무대 패널 윤곽 안쪽으로(윤곽·둥근 아래 모서리를 덮지 않게, V6Panels.DOCK_INSET_IN_STAGE_DP)
        val inset = if (splitActive) dp(V6Panels.DOCK_INSET_IN_STAGE_DP) else 0
        val wantStackLeft = if (splitActive) dpf(StageSplit.rightZoneLeftDp(screenWDp())) + inset else 0
        val wantStackRight = if (splitActive) dpf(StageSplit.OUTER_DP.toFloat()) + rightPadPx + inset else 0
        val wantStackBottom = dp(StageLayout.SUB_LABEL_DP) + bottomPadPx + inset
        if (stackLp.leftMargin != wantStackLeft || stackLp.rightMargin != wantStackRight || stackLp.bottomMargin != wantStackBottom) {
            stackLp.leftMargin = wantStackLeft
            stackLp.rightMargin = wantStackRight
            stackLp.bottomMargin = wantStackBottom
            bottomStack.layoutParams = stackLp
        }
        val bg = controlsBg
        if (bg != null && controlsBgInStage != splitActive) {
            controlsBgInStage = splitActive
            val c = V6Panels.dockCornersDp(StageLayout.CONTROL_PANEL_RADIUS_DP, splitActive).map { dp(it).toFloat() }
            bg.cornerRadii = floatArrayOf(c[0], c[0], c[1], c[1], c[2], c[2], c[3], c[3])
        }
    }

    /** 캐릭터 영역이 배치될 때(크기가 바뀔 때만) 스포트라이트를 맞추고 실측 로그를 한 줄 알린다 */
    private fun onCharAreaLaidOut(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val side = min(w, h)
        val spot = StageLayoutMath.spotlightSize(side, h, w)
        val spotLp = spotlight.layoutParams as FrameLayout.LayoutParams
        if (spotLp.width != spot || spotLp.height != spot) {
            spotLp.width = spot; spotLp.height = spot
            spotlight.layoutParams = spotLp
            spotDrawable.setGradientRadius(spot / 2f)
        }
        val msg = "[무대] 화면 ${width}x${height} 밀도 $density 캐릭터 영역 ${w}x${h}"
        if (msg != lastSizeMsg) {
            lastSizeMsg = msg
            onSizeLog?.invoke(msg)
        }
    }

    // ---- 동작 구동(단계 4): 이 한 곳에서만 Handler를 쓴다. 롤백: 이 블록과 render의 두 줄 삭제 ----
    private val motionHandler = Handler(Looper.getMainLooper())
    private var sceneStartMs = 0L
    private var motionRunning = false
    private val motionTick = object : Runnable {
        override fun run() {
            motionRunning = false
            if (!shouldAnimate()) return   // 보이지 않거나 LOCKED면 다시 예약하지 않는다
            applyPoseNow()
            motionRunning = true
            motionHandler.postDelayed(this, MOTION_FRAME_MS)
        }
    }

    /** r10 G: 지금 장면이 기어 확인 중(계약 v10 gearCheckPending)인가. render 맨 앞에서 정한다(캐릭터 정지 판단용) */
    private var lastGearPending = false

    /** r10 G: 캐릭터를 멈출 장면(잠금 또는 기어 확인 중) */
    private fun stillNow(sc: Scene): Boolean = sc == Scene.LOCKED || lastGearPending

    private fun shouldAnimate(): Boolean {
        val sc = lastScene ?: return false
        if (renderer is CrewPngRenderer) return false   // 정지 이미지: 동작 틱 불필요
        return isAttachedToWindow && isShown && !stillNow(sc)
    }

    private fun applyPoseNow() {
        val sc = lastScene ?: return
        val now = SystemClock.elapsedRealtime()
        val still = stillNow(sc)
        val elapsed = if (still) 0L else (now - sceneStartMs).coerceAtLeast(0L)
        (renderer as? StageCharacter3DView)?.setSceneTiming(sc, elapsed, if (still) 0L else now)
        if (still) renderer.setPose(CharacterPose(mouth = mouthOf(sc)))   // 잠금·기어 확인 중: 정지 자세
        else renderer.setPose(poseFor(sc, elapsed, now))
    }

    private fun updateMotionRunning() {
        if (shouldAnimate()) {
            if (!motionRunning) { motionRunning = true; motionHandler.post(motionTick) }
        } else {
            stopMotion()
        }
    }

    /** 콜백을 제거해 멈춘다(MainActivity onPause/onDestroy, GONE, detach, LOCKED에서 호출) */
    fun stopMotion() {
        motionHandler.removeCallbacks(motionTick)
        motionRunning = false
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotionRunning() }
    override fun onDetachedFromWindow() {
        stopMotion()
        if (bubbleReady) cancelBubbleAnim()   // 말풍선 크기 변화가 View 해제 뒤에 남지 않게 한다
        removeCallbacks(bubbleSettle)   // 지시 4: 기다리던 말풍선 넣기 취소(기록은 TripBubbleStore에 남는다)
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (bubbleReady && visibility != View.VISIBLE) cancelBubbleAnim()
        updateMotionRunning()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (bubbleReady && visibility != View.VISIBLE) cancelBubbleAnim()
        updateMotionRunning()
    }

    /** 장면 배경(시안 2). Backdrop이 이전과 같으면 아무것도 하지 않는다. 전환 애니메이션 없이 즉시 바꾼다.
     *  이름 주의: apply 블록을 쓰지 않고 지역 이름(dir, next)만 쓴다 */
    private fun applyBackdrop(scene: Scene) {
        val next = backdropOf(scene, Palette.current.night)
        cabinDecor.bind(Palette.current.night, scene == Scene.LOCKED)
        if (next == lastBackdrop) return
        lastBackdrop = next
        val dir = if (next.horizontal) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.TOP_BOTTOM
        background = GradientDrawable(dir, intArrayOf(Palette.argb(next.topRgb), Palette.argb(next.bottomRgb)))
        val spotRgb = spotRgbOf(next)
        spotDrawable.setColors(intArrayOf((StageLayout.SPOTLIGHT_ALPHA shl 24) or spotRgb, spotRgb))
        // 하단 보조 라벨 띠: Backdrop이 바뀐 때만 여기까지 오므로 장면이 같으면 다시 칠하지 않는다
        if (subLabelReady) subLabel.setBackgroundColor(Palette.argb(bandRgbOf(next)))
    }

    /** 렌더러 스타일은 호출자가 넘긴다 */
    fun setStyle(style: CharacterStyle) { renderer.applyStyle(style) }

    /**
     * 2026-11-13 PM 결정: 하차 끝(ALL_LEFT) 패널의 이번 주 스티커판 칸(StickerBoard.week 결과). MainActivity(개발 담당)가 부른다.
     * 표시 전용: 기록을 바꾸지 않는다. 빈 목록이면 판 없이 '스티커를 받았어요' 한 줄만 보인다.
     */
    fun setStickerWeek(cells: List<BoardCell>) {
        if (cells == stickerWeek) return
        stickerWeek = cells
        val st = lastState ?: return
        val sc = lastScene ?: return
        val ac = st.autoCheck
        if (seatStageOn) boardModel?.let { seatStage.bind(it, Palette.current.night, stickerWeek) }
        else if (autoPanelOn && ac != null) autoPanel.bind(sc, ac, Palette.current.night, stickerWeek) { seat -> actions?.onGuardianConfirmSeat(seat) }
    }

    /**
     * debug 디자인 미리보기 전용 장면 지정(2026-11-13). 새 장면의 실제 진입 조건(sceneOf)은 개발 담당 몫이라, 미리보기는 이 값으로 장면을 고정해 그린다.
     * 실제 앱(MainActivity)은 쓰지 않으며 기본값 null이면 언제나 sceneOf(state)를 따른다.
     */
    internal var previewScene: Scene? = null

    /**
     * 좌석 화면이 덮는 동안 아래 무대(캐릭터·말풍선 목록·컨트롤·카드)는 화면 읽기에서도 숨긴다(덮개는 불투명·터치 흡수).
     * 위쪽 바(진행 단계·설정)와 좌석 화면 자신은 그대로 둔다.
     */
    private fun hideUnderBoard(on: Boolean) {
        if (!on) { for ((v, mode) in a11ySaved) v.importantForAccessibility = mode; a11ySaved.clear(); return }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c === seatStage || c === topBarFrame) continue
            if (c !in a11ySaved) a11ySaved[c] = c.importantForAccessibility
            c.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    /**
     * 2026-11-14 지시 4: 이동 흐름 무대의 말풍선 기록을 비운다(홈으로 돌아갈 때·새 이동을 시작할 때).
     * 흐름 쪽 연결(MainActivity.cancelCheckToHome의 PM-INTEGRATE 자리)은 PM이 맞춘다. 무대가 스스로도 이동 흐름 상태가 없어지거나
     * 확인 중으로 새로 들어오면 비운다([TripBubbleRules.shouldClear]).
     */
    fun resetBubbles() {
        removeCallbacks(bubbleSettle)
        bubbleSeenLine = null
        TripBubbleStore.log = TripBubbleRules.cleared(TripBubbleStore.log)
        seatStage.clearBubbles()
    }

    /** debug 미리보기 전용: 이전 말풍선 기록을 넣어 둔다(쌓인 모습 캡처). 실제 앱은 쓰지 않는다 */
    internal fun previewBubbles(phase: AutoPhase?, lines: List<String>) {
        var log = TripBubbleLog(phase = phase)
        for (l in lines) log = TripBubbleRules.step(log, phase, l)
        TripBubbleStore.log = log
    }

    /**
     * 말풍선 기록 갱신(render마다). 이동 단계가 바뀌어 비울 때는 바로 비운다. 좌석 화면 무대가 아니거나 잠금이면 새 문장을 넣지 않는다(주행 중 새 정보 없음).
     * - 계약 v3 순번([AutoCheckUi.bubbleSeq] > 0)이 있으면 그 문장([AutoCheckUi.bubbleLine])을 순번이 바뀔 때 바로 쌓는다(실제로 말한 문장)
     * - 없으면(0) 장면 자막 문장([line])을 [TripBubbleRules.SETTLE_MS] 동안 그대로일 때 넣는다(대신 쓰는 경로)
     */
    private fun updateTripBubbles(scene: Scene, ac: AutoCheckUi?, line: String, boardOn: Boolean) {
        val phase = ac?.phase
        val before = TripBubbleStore.log
        // I3: 이동 흐름 상태가 잠깐 비면(화면 재생성 직후) 버리지 않고 보류, 순번이 이어지면 그대로 이어 간다(TripBubbleRules.enter)
        val after = TripBubbleRules.enter(before, phase, ac?.bubbleSeq ?: 0)
        TripBubbleStore.log = after
        if (before.items.isNotEmpty() && after.items.isEmpty()) seatStage.clearBubbles()
        bubblePhase = phase
        bubbleLocked = SeatBoardRules.stillScene(scene, ac)   // r10 G: 기어 확인 중에도 새 말풍선 없음
        if (ac != null && ac.bubbleSeq > 0) {
            removeCallbacks(bubbleSettle); bubbleSeenLine = null
            val show = boardOn && !bubbleLocked
            val next = TripBubbleRules.stepSpoken(after, phase, ac.bubbleLine.trim(), ac.bubbleSeq, show)
            TripBubbleStore.log = next
            if (boardOn) seatStage.bindBubbles(next, Palette.current.night,
                next.items != after.items && TripBubbleRules.animate(ValueAnimator.areAnimatorsEnabled(), isShown, bubbleLocked))
            return
        }
        if (!boardOn || bubbleLocked || line.isBlank()) {
            removeCallbacks(bubbleSettle); bubbleSeenLine = null
        } else if (line != bubbleSeenLine) {
            bubbleSeenLine = line
            removeCallbacks(bubbleSettle)
            postDelayed(bubbleSettle, TripBubbleRules.SETTLE_MS)
        }
        if (boardOn) seatStage.bindBubbles(TripBubbleStore.log, Palette.current.night, animate = false)
    }

    private fun commitBubble() {
        val line = bubbleSeenLine ?: return
        if (!seatStageOn || bubbleLocked) return
        val before = TripBubbleStore.log
        val next = TripBubbleRules.step(before, bubblePhase, line)
        if (next == before) return
        TripBubbleStore.log = next
        seatStage.bindBubbles(next, Palette.current.night, TripBubbleRules.animate(ValueAnimator.areAnimatorsEnabled(), isShown, bubbleLocked))
    }

    /** 현재 상태를 자막·보조 라벨·점·입 모양에 반영한다(값이 같으면 다시 쓰지 않음) */
    fun render(state: StageUiState) {
        val scene = previewScene ?: sceneOf(state)
        val gearPendingNow = SeatBoardRules.gearPending(scene, state.autoCheck)   // r10 G
        val pendingChanged = gearPendingNow != lastGearPending
        lastGearPending = gearPendingNow
        val still = SeatBoardRules.stillScene(scene, state.autoCheck)
        val cap = captionOf(scene, state)
        val sub = ReleaseCopy.t(subLabelOf(scene, state))
        val dot = dotOf(scene)
        applyBackdrop(SeatBoardRules.backdropSceneOf(scene, state.autoCheck))   // r10 G: 기어 확인 중이면 잠금색 대신 확인 화면 배경
        // 그림 기준은 그대로 CrewArt.forStage. 호스트는 표시 조건·장면 이벤트만 받는다(보호자 확인·잠금 상태를 바꾸지 않음)
        spriteDriver.setBase(CrewArt.forStage(scene, state.crewVoice, state.drivingGear))
        characterHost.updateConditions(parkConfirmed = !still, uxRestricted = still,   // r10 G: 기어 확인 중도 잠금처럼(캐릭터 정지)
            visible = isShown, reducedMotion = !ValueAnimator.areAnimatorsEnabled())
        // r7 임시 결정 51: 보상 표정은 하차 안내 화면에서 지급 표시가 새로 켜질 때(장면이 같아도). 모두 내림은 새로 내지 않음
        val just = state.autoCheck?.stickerJustGranted == true
        StageCharacterEvents.of(hostScene, scene, just, hostJust)?.let { characterHost.onEvent(it) }
        hostScene = scene
        hostJust = just
        lastCaption = cap
        // 풍선 목록은 bubbleReady·크기와 무관하게 항상 먼저 갱신한다(순수 로직). View 적용은 updateBubble이 준비되면 한다
        stack = BubbleStackRules.reduce(stack, prevScene, scene, cap)
        prevScene = scene
        if (sub != lastSub) { lastSub = sub; subLabel.text = sub }
        if (scene != lastScene || pendingChanged) {
            lastScene = scene
            sceneStartMs = SystemClock.elapsedRealtime()   // 장면이 바뀔 때만 갱신(r10 G: 기어 확인 중이 풀릴 때도)
            applyPoseNow()
        }
        updateMotionRunning()
        lastState = state
        val ac = state.autoCheck
        val panelOn = AutoCheckPanelRules.panelVisible(scene, ac)
        autoPanelOn = panelOn
        // 좌석 화면 하나(결정 14~17): 좌석 배치를 알면 무대를 덮는다.
        // 2026-11-14 지시 4: 이동 흐름 상태가 있으면 잠금·이동 끝·하차 한 줄 장면도 같은 무대(캐릭터 고정 + 말풍선 기록)를 쓴다
        val finishLabel = if (controlsOf(scene, false).showFinish) finishLabelOf(scene) else null
        val model = if (ac != null) SeatBoardRules.screenModel(scene, ac, cap, finishLabel, stickerBoardAvailable = true) else null
        boardModel = model
        val boardOn = model != null
        // 좌석 화면은 무대 배경(Backdrop) 위에 투명하게 얹는다 → 2영역 장식 패널은 쓰지 않는다
        val wantSplit = split && (controlsOf(scene, false).showSeats || (panelOn && !boardOn))
        if (wantSplit != splitActive) { splitActive = wantSplit; applySplitZones() }
        // 2026-11-14 PM 통합(루프 r1 C5, 디자인 C 69e56a6): 스티커 '방금 받음'은 계약 값을 쓴다(null이면 문구 끝맺음 호환 경로)
        // r7(계약 v7 1절): 연출은 하차 안내 때. 모두 내림 화면은 새 연출 없음(false)
        seatStage.setStickerJustGranted(SeatBoardRules.stickerJustFor(scene, ac))
        if (model != null) seatStage.bind(model, Palette.current.night, stickerWeek)
        seatStage.visibility = visIf(boardOn)
        subLabel.visibility = if (boardOn) View.INVISIBLE else View.VISIBLE
        // 덮인 층은 그리지도 않는다(INVISIBLE: 배치 크기는 유지해 말풍선 위치 계산이 흔들리지 않게). 캐릭터·지난 말풍선·아래 버튼이 겹쳐 남지 않는다
        val under = if (boardOn) View.INVISIBLE else View.VISIBLE
        charHolder.visibility = under; bubbleLayer.visibility = under; bottomStack.visibility = under; cabinDecor.visibility = under
        if (boardOn != seatStageOn) { seatStageOn = boardOn; hideUnderBoard(boardOn) }
        updateTripBubbles(scene, ac, model?.bubble.orEmpty(), boardOn)
        autoLayer.visibility = visIf(panelOn && !boardOn)
        if (scene != Scene.ALL_LEFT) autoPanel.resetSticker()   // 연출은 하차 끝 장면 진입마다 1회
        if (panelOn && !boardOn && ac != null) autoPanel.bind(scene, ac, Palette.current.night, stickerWeek) { seat -> actions?.onGuardianConfirmSeat(seat) }
        // I5: 좌석 화면 무대를 쓰는 하차 한 줄·이동 끝 장면("내리기 전 확인")에도 단계 줄을 보인다
        val stopover = SeatStageSteps.stopover(ac)   // r5: 도착 질문 중·잠깐 멈춤 → 칩 '정차'(r6: "내릴까요?" 질문 중도)
        val alighting = SeatStageSteps.alighting(ac)   // r6: 중간 하차 → '도착' 칸 자리 '정차', 현재 칸 '하차'
        phaseSteps.bind(SeatStageSteps.visible(scene, ac, boardOn), SeatStageSteps.phase(scene, ac?.phase, stopover, alighting), Palette.current.night, stopover, alighting)
        replayWanted = state.replayAvailable && scene == Scene.QUESTION
        reconnectNote = state.voiceReconnecting && scene == Scene.QUESTION
        renderControls(scene)
        // I5: 좌석 화면이 덮는 동안에는 아래 하차 안내 카드를 그리지 않는다(왼쪽 카드 위로 윤곽이 비쳐 보였다). 같은 안내는 좌석 화면 제목·부제에 있다
        headlineCard.bind(if (boardOn) null else StageHeadline.of(scene), Palette.current.night, width)
        lockCard.bind(if (scene == Scene.LOCKED && !boardOn) cap else null, Palette.current.night, width)   // 이동 중 잠금은 좌석 화면 무대 왼쪽에 그린다
        stickerBadge.bind(StickerMoment.phaseOf(scene == Scene.YES, state.stickerShown, state.stickerKidCount, state.stickerRecordFailed), state.stickerKidCount, state.stickerAlreadyThisTrip)
        settingsButton?.visibility = visIf(settingsAllowed(scene))
        manualFinishButton?.visibility = visIf(manualFinishScene(scene) && actions?.manualFinishVisible() == true)
        updateBubble(scene)   // 카드·지도 표시(renderControls) 뒤에 위치를 정한다(항상 layout 다시 계산 → 첫 장면도 강제 적용됨)
        if (dot != lastDot) {
            lastDot = dot
            // 이름 주의: apply 안에서는 GradientDrawable 멤버와 겹치지 않는 지역 이름(onArgb 등)을 쓴다
            val onArgb = Palette.argb(stageColors.dotOn)
            val offArgb = Palette.argb(stageColors.dotOff)
            val ringArgb = Palette.argb(stageColors.dotRing)
            val strokePx = dp(StageUiTokens.DOT_STROKE_DP)
            val states = dotStates(dot)
            for ((i, d) in dots.withIndex()) {
                val st = states[i]
                d.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (st == DotState.TODO) {
                        setColor(Color.TRANSPARENT)
                        setStroke(strokePx, offArgb)
                    } else {
                        setColor(onArgb)
                    }
                }
                dotCells[i].background = if (st == DotState.CURRENT) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(strokePx, ringArgb)
                    }
                } else null
            }
            val lineOffArgb = (QUIET_LINE_ALPHA shl 24) or (stageColors.dotOff and 0xFFFFFF)
            for ((i, ln) in dotLines.withIndex()) {
                ln.setBackgroundColor(if (lineFilled(i, dot)) onArgb else lineOffArgb)
            }
        }
    }
}
