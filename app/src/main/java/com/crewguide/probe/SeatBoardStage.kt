package com.crewguide.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 2026-11-13 디자인 담당: 좌석 화면 하나(사용자 승인 시안, 결정 14~17). 자동 확인·출발 준비·도착·모두 내림 장면에서 무대 위를 덮는다.
 * 왼쪽 카드 = 제목·안내·아이 목록·한 줄 색 설명 | 좌석 칸 5개([SeatBoardView]) (+ 모두 내림이면 아래 스티커판),
 * 오른쪽 무대 = 캐릭터 하나(공식 원본 274×609, 확대 없음) + 말풍선 칸([TripBubbleColumn], 지시 4) + 큰 버튼,
 * 맨 아래 = 고지 한 줄. 진행 단계 줄은 무대 위쪽 바(StageScreen)가 그대로 보인다. 예정 시간("약 N분")은 그리지 않는다(결정 14).
 * 2026-11-14 지시 4: 오른쪽 무대는 이동 흐름 내내 같은 뷰·같은 자리다(확인 중 → 출발 준비 → 주행 중 잠금 → 도착 → 모두 내림).
 * 캐릭터 상자(폭 250dp, 오른쪽 아래)와 무대 카드 크기는 장면과 무관하게 같고, 고지 줄도 한 줄 고정이라 무대 높이가 장면마다 바뀌지 않는다.
 * 잠금이면 왼쪽 카드에 잠금 사유(S09 안내판)만 보이고 좌석 칸·버튼은 그리지 않는다(주행 중 새 정보·조작 없음). 캐릭터는 움직이지 않는다(정지 그림).
 * 상태를 저장하지 않는다(말풍선 기록은 StageScreen이 넘긴다). 누름은 콜백으로만 알린다(아이 배정·판정은 흐름 몫).
 * 자기 바탕은 없다(무대 배경이 그대로 보인다). 덮인 층은 StageScreen이 숨기고, 빈 곳 터치는 여기서 받아 아래로 넘기지 않는다.
 */
class SeatBoardStage(context: Context) : LinearLayout(context) {
    var onSeatTap: ((String) -> Unit)? = null
    var onGuardian: ((String) -> Unit)? = null
    var onFinish: (() -> Unit)? = null
    /** J2(결정 34): [새로 출발 확인] 탭. StageScreen이 StageActions.onNewTripFromAllLeft로 잇는다 */
    var onNewTrip: (() -> Unit)? = null
    /** r5 추가 A(계약 v5): 도착 질문 대답. StageScreen이 StageActions.onArrivalAnswer로 잇는다 */
    var onAnswer: ((Boolean) -> Unit)? = null
    /** r6(계약 v6): "내릴까요?" 대답. StageScreen이 StageActions.onAlightAnswer로 잇는다(도착 질문 대답과 섞지 않는다) */
    var onAlightAnswer: ((Boolean) -> Unit)? = null
    /** 지금 두 대답 버튼이 어느 질문의 대답인가(마지막 [bind] 값) */
    private var answerKind = AnswerKind.ARRIVAL

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val board = SeatBoardView(context)
    /** r7: 하차 안내 화면에서 도장판이 좌석 칸에 자리를 양보하는 왼쪽 카드([SeatFirstColumn]) */
    private val left = SeatFirstColumn(context)
    private val header = FitColumn(context)
    /** r5 결정 38: 긴 제목("아이가 모두 내린 것 같아요")은 한 줄에 맞게 조금 줄인다(FitLineRule, 기준 글자 줄이 숨지 않게) */
    private val title = FitLineText(context)
    private val basis = TextView(context)
    private val sub = TextView(context)
    private val how = TextView(context)
    /** r8: 부제 아래 알림 한 줄("! 뒷자리에 앉은 신호가 아직 있어요" 등). 숨김 대상(optional)이 아니다. r8b: 좁으면 짧은 판 */
    private val note = TwoStepText(context)
    private val kids = LinearLayout(context)
    private val legend = FlowRowLayout(context)
    private val stickerBox = LinearLayout(context)
    private val stage = FrameLayout(context)
    private val art = CrewArtView(context, 1.0f, bottomAlign = true)
    private val bubbles = TripBubbleColumn(context)
    private val finish = DuoButton(context, DuoTokens.Kind.NEUTRAL, DuoTokens.BUTTON_MIN_DP)
    /** J2(결정 34): 모두 내린 뒤 다시 앉은 신호가 있을 때만 [이번 이동 끝내기] 위에 보이는 주 버튼 */
    private val newTrip = DuoButton(context, DuoTokens.Kind.PRIMARY, DuoTokens.BUTTON_MIN_DP)
    /** r5 추가 A: [예, 도착했어요](주 버튼) / [아니요, 잠깐 멈췄어요](보조). 질문 중 또는 '예' 뒤 되돌리기(보조만, r10 D 글자 [↩ 도착이 아니에요]) */
    private val answerYes = DuoButton(context, DuoTokens.Kind.PRIMARY, DuoTokens.BUTTON_MIN_DP)
    private val answerNo = DuoButton(context, DuoTokens.Kind.NEUTRAL, DuoTokens.BUTTON_MIN_DP)
    /** 무대 아래 버튼 묶음(위 newTrip, 아래 finish). 배치 숫자는 [SeatStageButtons]와 같다 */
    private val actionCol = LinearLayout(context)
    private val footer = TextView(context)
    private val split = AdaptiveSplit(context)
    /** 스티커판·별 연출(모두 내림). 별은 왼쪽 카드 위에 얹혀 안내 글 아래 빈칸(좁으면 좌석 칸 영역) 가운데에서 나타나 오늘 칸으로 간다 */
    private val stickerFly = StickerFlyHost(left, stickerBox, split) { (0 until header.childCount).map { header.getChildAt(it) }.firstOrNull { it is FlexSpacer } }
    private val lockBox = FrameLayout(context)
    private val lockCard = V6LockCardView(context)
    /** r7(결정 49): 빠른 시작 대기 — 왼쪽 카드 가운데 한 줄 "차량 신호를 확인하고 있어요" */
    private val waitBox = FrameLayout(context)
    private val waitLine = FitLineText(context)
    /** r10 G(계약 v10 gearCheckPending): 왼쪽 카드 맨 위 칩 "◌ 기어 확인 중…"(점선 원 기호 + 글자). 확인 화면·대기 한 줄 어느 쪽에도 보인다 */
    private val gearChip = LinearLayout(context)
    private val gearRing = View(context)
    private val gearText = FitLineText(context)
    private var sig: String? = null

    init {
        orientation = VERTICAL
        isClickable = true   // 빈 곳 터치가 아래 무대로 새지 않게
        setPadding(dp(24), dp(4), dp(24), dp(6))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 왼쪽 카드 ----
        left.orientation = VERTICAL
        left.setPadding(dp(24), dp(20), dp(24), dp(20))
        row.addView(left, LayoutParams(0, LayoutParams.MATCH_PARENT, 1120f))
        // r10 G: 기어 확인 중 칩(평소 GONE). 글꼴 2.0에서도 한 줄(FitLineText, 넘치면 줄바꿈)
        gearChip.orientation = HORIZONTAL
        gearChip.gravity = Gravity.CENTER_VERTICAL
        gearChip.visibility = GONE
        gearChip.setPadding(dp(14), dp(6), dp(18), dp(6))
        gearChip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        gearChip.contentDescription = SeatBoardRules.GEAR_PENDING_DESC
        gearChip.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        gearRing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        gearChip.addView(gearRing, LayoutParams(dp(24), dp(24)))
        gearText.text = SeatBoardRules.GEAR_PENDING
        gearText.typeface = Typeface.DEFAULT_BOLD
        gearText.setBaseSize(DuoTokens.CAPTION_SP)
        gearText.includeFontPadding = false
        gearText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        gearChip.addView(gearText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        left.addView(gearChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        split.setParts(header, board, dp(16))
        left.addView(split, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        header.orientation = VERTICAL

        title.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        title.setBaseSize(DuoTokens.TITLE_SP)
        title.includeFontPadding = false
        header.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        basis.textSize = DuoTokens.CAPTION_SP
        header.addView(basis, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        sub.textSize = 28f; sub.typeface = Typeface.DEFAULT_BOLD; sub.setLineSpacing(0f, 1.1f)
        header.addView(sub, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        note.textSize = DuoTokens.CAPTION_SP; note.typeface = Typeface.DEFAULT_BOLD; note.visibility = GONE
        note.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        header.addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        how.textSize = DuoTokens.CAPTION_SP
        how.text = SeatBoardRules.PICK_HOW
        header.addView(how, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        kids.orientation = VERTICAL
        header.addView(kids, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        header.addView(FlexSpacer(context), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        header.optional = listOf(kids, how, basis)   // 넘치면 아이 목록(칸에도 있음) → 고르는 법 → 기준 글자 순서로 숨긴다
        legend.gapPx = dp(28); legend.lineGapPx = dp(6)
        left.addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        stickerBox.orientation = VERTICAL
        left.addView(stickerBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 잠금(이동 중 S09): 왼쪽 카드 안에 잠금 안내판만(좌석 칸·안내·색 설명은 숨김)
        lockBox.visibility = GONE
        lockBox.addView(lockCard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        left.addView(lockBox, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 빠른 시작 대기(r7 3절): 좌석 칸·안내·색 설명 대신 가운데 한 줄만. 글꼴 2.0에서 넘치면 줄바꿈(FitLineText)
        waitBox.visibility = GONE
        waitLine.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        waitLine.setBaseSize(DuoTokens.TITLE_SP)
        waitLine.gravity = Gravity.CENTER
        waitLine.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        waitBox.addView(waitLine, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        left.addView(waitBox, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        left.split = split; left.board = board; left.yieldView = stickerBox

        // ---- 오른쪽 무대(이동 흐름 내내 고정): 캐릭터 1개(오른쪽 아래) + 말풍선 칸 + 큰 버튼(아래) ----
        // 자식 순서 = 배치 순서: 캐릭터·버튼을 먼저 배치하고 말풍선 칸이 그 위치를 읽는다(geometry)
        stage.clipChildren = true
        row.addView(stage, LayoutParams(0, LayoutParams.MATCH_PARENT, 736f).apply { leftMargin = dp(16) })
        art.show(CrewArtAsset.PARKED)
        art.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        art.contentDescription = CrewArtText.descOf(CrewArtState.IDLE)
        stage.addView(art, FrameLayout.LayoutParams(dp(ART_W_DP), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(24); topMargin = dp(20); bottomMargin = dp(16)
        })
        finish.textSize = SeatStageButtons.TEXT_SP
        finish.setOnClickListener { onFinish?.invoke() }
        newTrip.textSize = SeatStageButtons.TEXT_SP
        newTrip.visibility = GONE
        newTrip.setOnClickListener { onNewTrip?.invoke() }
        actionCol.orientation = VERTICAL
        for ((b, v) in listOf(answerYes to true, answerNo to false)) {
            b.textSize = SeatStageButtons.TEXT_SP
            b.visibility = GONE
            // r6: 같은 두 버튼을 두 질문이 함께 쓴다. 지금 질문 종류([SeatBoardRules.answerKindOf])에 맞는 동작만 부른다
            b.setOnClickListener { if (answerKind == AnswerKind.ALIGHT) onAlightAnswer?.invoke(v) else onAnswer?.invoke(v) }
            // 글꼴 2.0 캡처(r5): "아니요, 잠깐 멈췄어요"가 버튼 폭을 넘어 잘렸다 → 한 줄에 맞게 글자만 줄인다(76dp 높이 유지)
            DuoText.fitOneLine(b, SeatStageButtons.TEXT_SP)
            actionCol.addView(b, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(SeatStageButtons.GAP_DP) })
        }
        actionCol.addView(newTrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(SeatStageButtons.GAP_DP) })
        actionCol.addView(finish, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stage.addView(actionCol, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(SeatStageButtons.SIDE_DP); rightMargin = dp(SeatStageButtons.SIDE_DP + ART_W_DP + SeatStageButtons.ART_GAP_DP)
            bottomMargin = dp(SeatStageButtons.BOTTOM_DP)
        })
        stage.addView(bubbles, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(24); topMargin = dp(BUBBLE_TOP_DP)
        })
        bubbles.geometry = { bubbleGeo() }
        // 캐릭터·버튼 자리가 바뀌면(버튼 보임/숨김·창 크기) 말풍선 칸도 다시 배치한다(형제 배치는 칸의 onLayout을 부르지 않는다)
        val relayout = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (l != ol || t != ot || r != or2 || b != ob) bubbles.post { bubbles.requestLayout() } }
        art.addOnLayoutChangeListener(relayout)
        actionCol.addOnLayoutChangeListener(relayout)

        footer.gravity = Gravity.CENTER
        footer.textSize = DuoTokens.CAPTION_SP - 4f
        // 한 줄 고정(넘치면 글자만 줄임): 고지 길이·글꼴 배율로 무대 높이가 장면마다 바뀌지 않게(지시 4 무대 고정)
        footer.maxLines = 1
        footer.includeFontPadding = false
        footer.setAutoSizeTextTypeUniformWithConfiguration(12, (DuoTokens.CAPTION_SP - 4f).toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, dp(FOOTER_H_DP)).apply { topMargin = dp(6) })

        board.onSeatTap = { s -> onSeatTap?.invoke(s) }
        board.onGuardian = { s -> onGuardian?.invoke(s) }
    }

    private companion object {
        /** 캐릭터 상자 폭(dp). 그림은 이 상자 안에서 비율 유지·확대 없음(최대 1.0배) */
        const val ART_W_DP = SeatStageButtons.ART_W_DP
        /** 말풍선 칸 위 여백(dp): 무대 카드 위 경계선 바로 안쪽. 페이드는 여기서 완전 투명 */
        const val BUBBLE_TOP_DP = 6
        const val FOOTER_H_DP = 30
        /** 캐릭터 그림별 보이는 부분 윤곽(한 번만 읽는다, 그림은 앱에 든 고정 PNG). 못 읽으면 null을 기억해 다시 시도하지 않는다 */
        val silhouettes = HashMap<CrewArtAsset, ArtSilhouette?>()
    }

    /**
     * 지금 그리는 캐릭터 그림의 실제 보이는 부분(알파) 왼쪽 윤곽. 그림 크기가 자산 표기와 다르면(대신 그린 그림) null → 상자 왼쪽 끝 기준(더 좁게).
     * 2026-11-14: 예전에는 눈대중 비율(얼굴 왼쪽 0.20·턱 0.40)로 놓아 묶은 머리(왼쪽 4%까지 나옴)·모자와 말풍선이 겹쳤다.
     */
    private fun silhouetteOf(a: CrewArtAsset): ArtSilhouette? {
        if (silhouettes.containsKey(a)) return silhouettes[a]
        val sil = try {
            val src = (art.drawable as? BitmapDrawable)?.bitmap
            // 하드웨어 비트맵은 픽셀을 바로 읽을 수 없어 복사본으로 읽는다
            val soft: Bitmap? = if (src != null && src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            if (soft == null || soft.width != a.widthPx || soft.height != a.heightPx) null else {
                val px = IntArray(a.widthPx * a.heightPx)
                soft.getPixels(px, 0, a.widthPx, 0, 0, a.widthPx, a.heightPx)
                if (soft !== src) soft.recycle()
                ArtSilhouette.of(a.widthPx, a.heightPx, px)
            }
        } catch (_: RuntimeException) { null }
        silhouettes[a] = sil
        return sil
    }

    /**
     * 말풍선 칸 좌표의 기준(칸 좌표 px). 캐릭터 그림이 실제로 그려진 자리(CrewArtView와 같은 계산: 가로 가운데·아래 맞춤·배율 drawnScale)에서
     * - 지금 말풍선 아랫변 = 머리 옆 높이([TripBubbleRules.anchorBottom], 위 공간이 모자란 낮은 무대는 턱 높이까지 내려옴)
     * - 아래 한계 = 버튼 위 12dp(버튼이 없으면 무대 아래 여백 위)
     * - 오른쪽 한계 = 캐릭터 보이는 부분의 왼쪽 윤곽에서 8dp 왼쪽([TripBubbleRules.rightLimit]) → 말풍선은 캐릭터와 겹치지 않는다
     * 왼쪽·위는 칸 여백(왼쪽 24dp, 위 6dp)이 카드 테두리와의 간격이다.
     */
    private fun bubbleGeo(): TripBubbleColumn.Geo? {
        if (art.width <= 0 || art.height <= 0) return null
        val a = art.asset ?: return null
        val s = art.drawnScale.takeIf { it > 0f } ?: return null
        val dw = a.widthPx * s; val dh = a.heightPx * s
        val bw = art.width - art.paddingLeft - art.paddingRight; val bh = art.height - art.paddingTop - art.paddingBottom
        val ox = bubbles.left; val oy = bubbles.top
        val artLeft = art.left + art.paddingLeft + Math.round((bw - dw) / 2f) - ox.toFloat()
        val artTop = art.top + art.paddingTop + Math.round(bh - dh) - oy.toFloat()   // bottomAlign
        val anchor = TripBubbleRules.anchorBottom(
            (artTop + dh * TripBubbleRules.ANCHOR_HEAD_FRAC).toInt(), (artTop + dh * TripBubbleRules.ANCHOR_CHIN_FRAC).toInt(), dp(TripBubbleRules.ROOM_ABOVE_DP))
        // 버튼 묶음 맨 위(새로 출발 확인이 있으면 그 위) 12dp 위까지
        val limit = (if (actionCol.visibility == VISIBLE && actionCol.height > 0) actionCol.top - dp(12) else stage.height - dp(20)) - oy
        val sil = silhouetteOf(a)
        val gap = dp(TripBubbleRules.ART_GAP_DP)
        val maxRight = stage.width - dp(TripBubbleRules.CARD_INSET_DP) - ox
        return TripBubbleColumn.Geo(anchor, limit) { y -> TripBubbleRules.rightLimit(sil, artLeft, artTop, s, y, gap, maxRight) }
    }

    /** 말풍선 기록을 그린다. [animate]는 StageScreen이 정한다(애니메이션 배율·화면 보임·잠금, [TripBubbleRules.animate]) */
    fun bindBubbles(log: TripBubbleLog, night: Boolean, animate: Boolean) = bubbles.bind(log, night, animate)

    fun clearBubbles() = bubbles.clear()

    /** debug 미리보기 캡처용: 스티커 별 연출을 [ms] 시점 그림에 세워 둔다([StickerFlyHost.freezeAt]). 실제 앱은 부르지 않는다 */
    fun stickerPreviewAt(ms: Long) = stickerFly.freezeAt(ms)

    /**
     * 계약 값 [AutoCheckUi.stickerJustGranted](스티커 연출을 지금 보여 줄지)를 넘긴다(2026-11-14 루프 r1 C5). [bind] 앞에 부른다.
     * 부르지 않으면(null) 스티커 줄 글자로 판단한다(호환). 실제로 값이 바뀔 때만 스티커 칸을 다시 그린다.
     */
    fun setStickerJustGranted(v: Boolean?) = stickerFly.setJustGranted(v)

    /** 표시 모델을 그린다. 같은 값이면 다시 만들지 않는다(render마다 불러도 된다) */
    fun bind(m: SeatBoardRules.ScreenModel, night: Boolean, stickerWeek: List<BoardCell>) {
        answerKind = m.answerKind
        val s = "$m|$night|$stickerWeek|${resources.configuration.fontScale}"
        if (s == sig) return
        sig = s
        val r = DuoTokens.roles(night)
        left.background = V6Panels.card(d, night)
        stage.background = V6Panels.stagePanel(d, night)
        // 잠금: 왼쪽은 잠금 안내판만(좌석 칸·안내·색 설명·스티커 숨김)
        val locked = m.lockText != null
        val waiting = m.waitText != null
        lockBox.visibility = if (locked) VISIBLE else GONE
        split.visibility = if (locked || waiting) GONE else VISIBLE
        waitBox.visibility = if (waiting) VISIBLE else GONE
        waitLine.text = m.waitText.orEmpty()
        waitLine.setTextColor(Palette.argb(r.text))
        // r7(1절): 하차 안내 화면의 도장판은 좌석 칸이 작아지면 숨긴다(SeatStageSticker). 모두 내림은 늘 둔다
        left.yieldWanted = m.stickerYields
        lockCard.bind(m.lockText, night, left.width)
        title.setTextColor(Palette.argb(r.text)); basis.setTextColor(Palette.argb(r.textMuted))
        sub.setTextColor(Palette.argb(r.text)); how.setTextColor(Palette.argb(r.textMuted)); footer.setTextColor(Palette.argb(r.textMuted))

        board.bind(m.cells, m.mode, night, frozen = m.gearPending)
        // r10 G: 기어 확인 중 칩(정보 칩 색, 글자 대비 8.5) + 버튼 비활성 모양(투명도 없음)
        gearChip.visibility = if (m.gearPending) VISIBLE else GONE
        if (m.gearPending) {
            val ic = DuoTokens.infoChip(night)
            gearChip.background = DuoDraw.round(ic.bg, dp(18).toFloat(), dp(2), ic.stroke)
            gearRing.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0); setStroke(dp(3), Palette.argb(ic.fg), dp(5).toFloat(), dp(3).toFloat())
            }
            gearText.setTextColor(Palette.argb(ic.fg))
        }
        if (finish.isEnabled == m.gearPending) finish.isEnabled = !m.gearPending
        title.text = m.title
        basis.text = m.basis.orEmpty()
        header.want(basis, m.basis != null)
        sub.text = m.sub
        // r8: 알림 한 줄 — 기호 "!" + 글자(색만으로 구분하지 않음), 진한 주황(눈썹 글자색, 대비 검사된 짝)
        note.setTexts(m.note?.let { "! $it" }.orEmpty(), m.noteShort?.let { "! $it" })
        note.setTextColor(Palette.argb(V6Tokens.eyebrow(night)))
        note.visibility = if (m.note != null) VISIBLE else GONE
        header.want(how, m.showHow)
        // 아이 목록: 별명 원 + "하늘 · 뒷줄 왼쪽"(칸 색을 글자로도 읽게)
        kids.removeAllViews()
        header.want(kids, m.showKids)
        // r10 J: 손님은 앉은 칸마다 한 줄("◇ 손님 · 뒷줄 오른쪽"), 등록 아이는 목록 순서(SeatBoardRules.kidLines)
        if (m.showKids) for (k in SeatBoardRules.kidLines(m.children, m.cells)) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(context).apply {
                text = k.mark; gravity = Gravity.CENTER; textSize = 22f; includeFontPadding = false
                typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
                setTextColor(Palette.argb(SeatBoardRules.CHILD_INK))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Palette.argb(SeatBoardRules.CHILD_FILL))
                    if (k.guest) setStroke(dp(3), Palette.argb(SeatBoardRules.CHILD_INK), dp(6).toFloat(), dp(4).toFloat())
                    else setStroke(dp(3), Palette.argb(SeatBoardRules.CHILD_INK))
                }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(40), dp(40)))
            line.addView(TextView(context).apply {
                text = k.text   // r10 I: "· 카시트", r10 J: "손님 · 뒷줄 …"
                textSize = DuoTokens.CAPTION_SP; setTextColor(Palette.argb(r.text))
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            kids.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        // 한 줄 색 설명(색 견본 + 글자)
        legend.removeAllViews()
        legend.visibility = if (m.legendCount > 0 && !locked) VISIBLE else GONE
        for ((col, label) in SeatBoardRules.legend(night).take(m.legendCount)) {
            val item = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            item.addView(View(context).apply { background = DuoDraw.round(col.fill, dp(8).toFloat(), dp(2), col.stroke) }, LayoutParams(dp(28), dp(28)))
            item.addView(TextView(context).apply {
                text = label.replace(' ', ' '); textSize = DuoTokens.CAPTION_SP; setTextColor(Palette.argb(r.text)); typeface = Typeface.DEFAULT_BOLD
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            legend.addView(item)
        }
        // 스티커판 + 별 연출 — 모두 내린 뒤(2026-11-14 디자인 C: 판·한 줄·연출은 StickerFlyHost가 만든다)
        stickerFly.bind(m.sticker, m.phase, parkAllowed = !locked, night = night, week = stickerWeek, children = m.children)
        val finishVis = if (m.finishLabel != null) VISIBLE else GONE
        val newTripVis = if (m.newTripLabel != null) VISIBLE else GONE
        val yesVis = if (m.answerYesLabel != null) VISIBLE else GONE
        val noVis = if (m.answerNoLabel != null) VISIBLE else GONE
        val colVis = if (finishVis == VISIBLE || newTripVis == VISIBLE || yesVis == VISIBLE || noVis == VISIBLE) VISIBLE else GONE
        if (finish.visibility != finishVis || newTrip.visibility != newTripVis || actionCol.visibility != colVis ||
            answerYes.visibility != yesVis || answerNo.visibility != noVis) {
            finish.visibility = finishVis; newTrip.visibility = newTripVis; actionCol.visibility = colVis
            answerYes.visibility = yesVis; answerNo.visibility = noVis; bubbles.requestLayout()
        }
        answerYes.text = m.answerYesLabel.orEmpty(); answerYes.restyle()
        answerNo.text = m.answerNoLabel.orEmpty(); answerNo.restyle()
        answerNo.contentDescription = SeatBoardRules.answerNoDesc(m.answerNoLabel)   // r10 D: "↩" 기호는 읽지 않게
        finish.text = m.finishLabel.orEmpty()
        finish.restyle()
        newTrip.text = m.newTripLabel.orEmpty()
        newTrip.restyle()
        footer.text = m.footer
        // 잠금 배경(주황빛)에서는 고지 글자 대비가 3.97로 모자라고(2026-11-14 캡처), 좌석 신호도 그리지 않으므로 감춘다. 높이는 유지(INVISIBLE)해 무대가 움직이지 않게
        footer.visibility = if (locked || waiting) INVISIBLE else VISIBLE
        DuoText.wordBreakTree(this)
    }
}

/**
 * 좌석 화면 무대 위 진행 단계 칩 줄의 순수 규칙(I5, 2026-11-14).
 * 도착 신호 없이 P로 멈춘 이동은 하차 한 줄(EXIT_CHECK)·이동 끝(TRIP_END) 장면이 되고 제목이 "내리기 전 확인"인데(결정 26·지시 4로 같은 무대),
 * 단계 줄 표시 조건([AutoCheckPanelRules.stepsVisible])은 좌석 패널 장면 4개만 봐서 그 장면에서 줄이 비었다.
 */
object SeatStageSteps {
    private fun exitLineScene(scene: Scene): Boolean = scene == Scene.TRIP_END || scene == Scene.EXIT_CHECK

    /** r5: 칩을 '정차'로 보일 정차인가(계약 v5 askArrived / v5.1 pausedByAnswer / r6 v6 askAlight). 중간 하차(alighting)는 아니다 */
    fun stopover(ac: AutoCheckUi?): Boolean = ac != null && !alighting(ac) && (ac.askArrived || ac.pausedByAnswer || ac.askAlight)

    /**
     * r6(계약 v6 alightingByAnswer): 중간 하차 칩 — '도착' 칸 자리를 '정차'로 바꾸고 '하차' 칸을 현재로(확인 중 · 출발 준비 · 출발 · 정차 · ▶ 하차).
     * 질문 중(askAlight·askArrived)이면 아니다(질문이 먼저).
     * 계약 v6.2: 하차 마무리(ALL_LEFT) 동안에도 true가 유지된다 → 그 화면 칩도 "● 정차 · ▶ 하차"('도착' 0). 제목·기준 줄은 ALL_LEFT 규칙 그대로(SeatBoardRules)
     */
    fun alighting(ac: AutoCheckUi?): Boolean = ac != null && ac.alightingByAnswer && !ac.askAlight && !ac.askArrived

    /**
     * 단계 줄을 보일지: 기존 조건 + 좌석 화면 무대를 쓰는 하차 한 줄·이동 끝 장면. 잠금(S09)은 그대로 숨긴다(주행 중 새 정보 없음).
     * r7(결정 49): 빠른 시작 대기 화면은 한 줄만이라 단계 줄도 숨긴다
     */
    fun visible(scene: Scene, ac: AutoCheckUi?, boardOn: Boolean): Boolean =
        ac?.quickStartWaiting != true && (AutoCheckPanelRules.stepsVisible(scene, ac) || (boardOn && ac != null && exitLineScene(scene)))

    /**
     * 칩에 넘길 단계. 2026-11-14 루프 r3 M3(결정 26 취지): 도착 신호 없이 P로 멈춘 EXITING은 도착 화면(제목 "도착했어요")으로 보이므로
     * 칩도 '도착' 칸(예전 '하차'). 하차 한 줄·이동 끝 장면도 같은 칸으로 둔다(정차 직후 값이 아직 주행이어도 '출발' 칸이 켜지지 않게).
     * '하차' 칸은 모두 내림(ALL_LEFT)만. 그 밖의 장면은 계약 값 그대로.
     */
    fun phase(scene: Scene, phase: AutoPhase?, stopover: Boolean = false, alighting: Boolean = false): AutoPhase? = when {
        phase == null -> null
        // r6: 중간 하차는 '하차' 칸이 현재(phaseIndex(EXITING) = 4). 그 앞 칸 이름은 AutoCheckPanelRules.stepNames(alighting = true)가 '정차'로 바꾼다
        alighting && !exitLineScene(scene) -> AutoPhase.EXITING
        // r5: 도착 질문 중·'아니요'(잠깐 멈춤) 뒤 정차는 '도착'이 아니라 '정차' 칸(출발 자리, AutoCheckPanelRules.stepNames)
        stopover && !exitLineScene(scene) -> AutoPhase.DRIVING
        exitLineScene(scene) -> AutoPhase.ARRIVED
        phase == AutoPhase.EXITING -> AutoPhase.ARRIVED
        else -> phase
    }
}

/**
 * r8b: 긴 판이 [SeatGuardianNote.MAX_LINES]줄을 넘으면 짧은 판으로 바꾸는 글자(폭이 정해질 때 고른다, 같은 폭이면 같은 결과).
 */
class TwoStepText(context: Context) : TextView(context) {
    private var full = ""
    private var short: String? = null

    fun setTexts(full: String, short: String?) {
        this.full = full; this.short = short
        if (text.toString() != full) text = full
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight).toFloat()
            val want = SeatGuardianNote.pick(full, short, paint.measureText(full), avail)
            if (text.toString() != want) text = want
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * r7(계약 v7 1절): 좌석 화면 왼쪽 카드. [yieldWanted]이면 [yieldView](도장판 칸)를 넣었을 때 좌석 칸 배율이
 * [SeatStageSticker.MIN_SEAT_SCALE]보다 작아지면 그 칸을 숨긴다(좌석 그림이 먼저). 숨긴 상태에서는 넣었을 때의 배율을 미리 계산해
 * 여유([SeatStageSticker.SHOW_MARGIN])가 있을 때만 다시 보인다 → 같은 크기면 같은 결정(배치가 오락가락하지 않음).
 * 좌석 칸 크기는 [board]가 받은 크기로 [SeatBoardRules.scaleFor](하차 단계라 버튼 없음)를 그대로 쓴다.
 */
class SeatFirstColumn(context: Context) : LinearLayout(context) {
    var split: View? = null
    var board: View? = null
    var yieldView: View? = null
    var yieldWanted = false
        set(v) { if (field != v) { field = v; requestLayout() } }

    private fun scaleOf(boardHeightPx: Int): Float? {
        val b = board ?: return null
        if (b.measuredWidth <= 0 || boardHeightPx <= 0) return null
        val d = resources.displayMetrics.density
        return SeatBoardRules.scaleFor(b.measuredWidth / d, boardHeightPx / d, anyButton = false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val y = yieldView
        if (y == null || !yieldWanted || split?.visibility != VISIBLE) {
            if (y != null && y.visibility == GONE) y.visibility = VISIBLE   // 양보하지 않는 장면(모두 내림 등)은 늘 보인다
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val b = board ?: return
        if (y.visibility == VISIBLE) {
            val k = scaleOf(b.measuredHeight) ?: return
            if (!SeatStageSticker.keep(visibleNow = true, seatScale = k)) {
                y.visibility = GONE
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        } else {
            // 숨김 상태: 넣으면 줄어들 높이만큼 뺀 예상 배율
            val cw = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
            y.measure(cw, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val lp = y.layoutParams as LayoutParams
            val k = scaleOf(b.measuredHeight - y.measuredHeight - lp.topMargin - lp.bottomMargin) ?: return
            if (SeatStageSticker.keep(visibleNow = false, seatScale = k)) {
                y.visibility = VISIBLE
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }
}

/**
 * 세로 공간이 모자라면 [optional]을 앞에서부터 하나씩 숨기는 세로 줄(글꼴 2.0 넘침 방지). 숨긴 내용은 칸·음성에도 있다.
 * 보일지 말지는 [want]로 정하고(장면 규칙), 이 줄은 공간 때문에만 더 숨긴다. 같은 크기면 같은 결정이라 배치가 반복되지 않는다.
 */
class FitColumn(context: Context) : LinearLayout(context) {
    var optional: List<View> = emptyList()
    private val wanted = HashSet<View>()

    fun want(v: View, on: Boolean) { if (on) wanted += v else wanted -= v; if (!on) v.visibility = GONE; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cw = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
        val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        fun h(c: View): Int { c.measure(cw, unspec); val lp = c.layoutParams as LayoutParams; return c.measuredHeight + lp.topMargin + lp.bottomMargin }
        var need = paddingTop + paddingBottom
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LayoutParams
            if (lp.weight > 0f) continue
            if (c in optional) { if (c in wanted) need += h(c) } else if (c.visibility != GONE) need += h(c)
        }
        val avail = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        for (v in optional) {
            if (v !in wanted) continue
            val show = need <= avail
            if (!show) need -= h(v)
            val vis = if (show) VISIBLE else GONE
            if (v.visibility != vis) v.visibility = vis
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * 왼쪽 카드 안 배치: 넓고 낮은 칸(1920×720)이면 [안내 | 좌석 칸] 가로, 세로로 긴 칸(에뮬레이터 앱 창 1682×1210 등)이면 [안내] 위 · [좌석 칸] 아래.
 * 2026-11-14 실제 흐름 확인: 1682×1210 창에서 가로 배치는 좌석 칸이 161dp로 작고 카드 아래 절반이 비었다.
 */
class AdaptiveSplit(context: Context) : LinearLayout(context) {
    private var vertical: Boolean? = null
    private var header: View? = null
    private var board: View? = null
    private var gapPx = 0

    /** 안내 줄과 좌석 칸을 넣는다(한 번만) */
    fun setParts(header: View, board: View, gapPx: Int) {
        this.header = header; this.board = board; this.gapPx = gapPx
        addView(header)
        addView(board)
        apply(false)
    }

    private fun apply(v: Boolean) {
        val header = header ?: return
        val board = board ?: return
        if (vertical == v) return
        vertical = v
        orientation = if (v) VERTICAL else HORIZONTAL
        header.layoutParams = if (v) LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            else LayoutParams(0, LayoutParams.MATCH_PARENT, 380f)
        board.layoutParams = if (v) LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = gapPx }
            else LayoutParams(0, LayoutParams.MATCH_PARENT, 676f).apply { leftMargin = gapPx }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w > 0 && h > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) apply(SeatBoardRules.stackHeader(w.toFloat(), h.toFloat()))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * 늘어나는 빈칸: 정해진 높이를 받을 때만 그 높이를 쓰고, 내용 높이(AT_MOST)로 잴 때는 0이다.
 * 2026-11-14 실제 흐름: 보통 View는 AT_MOST에서 받은 높이를 다 차지해, 세로로 쌓은 안내 줄이 카드를 다 덮고 좌석 칸이 0 높이가 됐다.
 */
class FlexSpacer(context: Context) : View(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }
}
