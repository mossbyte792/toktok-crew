package com.crewguide.probe

import kotlin.math.max
import kotlin.math.min

// 대화창식 풍선 목록 순수 로직(Android 클래스 없음, 화면 연결은 다음 작업). 단위: 위치·크기는 화면 px(상수는 dp×밀도).
// 같은 입력이면 같은 결과다. 저장·네트워크 없음.
// 불변식: reduce가 돌려주는 items의 마지막(최신)은 항상 CREW다(GUARDIAN은 반드시 CREW 앞에 붙는다). layout은 이를 전제로 한다.
// 롤백: 이 파일과 BubbleStackTest.kt를 삭제한다(다른 파일은 이 파일을 참조하지 않는다).

enum class Speaker { CREW, GUARDIAN }

data class BubbleItem(val id: Long, val speaker: Speaker, val text: String)

/** 최신이 리스트 마지막 */
data class BubbleStack(val items: List<BubbleItem> = emptyList(), val nextId: Long = 1L)

/** 글자 크기 추정에 필요한 화면 밀도 */
data class BubbleMeasure(val density: Float)

/** 풍선이 있어야 하는 영역(px). top = 상단 바 아래 + 8dp. left/right = 화면 좌우 안전 여백 안쪽 */
data class RegionBounds(val left: Float, val top: Float, val right: Float)

data class StackedBubble(
    val item: BubbleItem,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val tail: Boolean,
    val visible: Boolean,
)

/** bubbles는 items와 같은 순서(오래된 것 먼저). tailDir은 꼬리가 붙는 풍선(tail=true)의 방향 */
data class StackLayout(val bubbles: List<StackedBubble>, val tailDir: TailDir)

object BubbleStackRules {
    const val MAX_ITEMS = 3
    /** 보호자가 [예]를 누른 '보고'일 뿐이다. 소리가 났다/아이가 확인했다/벨트가 채워졌다는 뜻이 아니다 */
    const val GUARDIAN_TEXT = "보호자 보고: 예"
    const val ALPHA_NEWEST = 1.0f
    const val ALPHA_SECOND = 0.45f
    const val ALPHA_THIRD = 0.25f
    const val GAP_DP = 8
    const val CORNER_DP = 20
    const val MIN_WIDTH_DP = 120    // 작은 문구의 최소 풍선 폭

    private fun isPrep(s: Scene?): Boolean = s == Scene.PICK || s == Scene.READY

    /**
     * 규칙 한 곳. (b) PICK/READY로 들어올 때(이전이 PICK/READY가 아님, null 포함)와 LOCKED로 들어올 때 기록을 비운다
     * (같은 LOCKED 반복 호출은 비우지 않아 id가 흔들리지 않는다). 비운 뒤 caption이 비어 있으면 빈 목록이다.
     * (a) caption이 비어 있으면 추가하지 않는다. (c) 같은 장면: 같은 문구 무변화, 다른 문구는 마지막 CREW 교체.
     * (d) 장면 변경: CREW 추가. (e) QUESTION→YES만 GUARDIAN을 CREW 앞에 추가. (f) 최대 3개, 오래된 것부터 버림.
     */
    fun reduce(stack: BubbleStack, prevScene: Scene?, scene: Scene, caption: String): BubbleStack {
        val reset = (isPrep(scene) && !isPrep(prevScene)) || (scene == Scene.LOCKED && prevScene != Scene.LOCKED)
        val base = if (reset) stack.copy(items = emptyList()) else stack
        if (caption.isBlank()) return base
        if (!reset && prevScene == scene) {
            val last = base.items.lastOrNull()
            if (last != null && last.speaker == Speaker.CREW) {
                return if (last.text == caption) base
                else base.copy(items = base.items.dropLast(1) + last.copy(text = caption))
            }
        }
        var id = base.nextId
        val added = ArrayList<BubbleItem>(2)
        if (prevScene == Scene.QUESTION && scene == Scene.YES) added.add(BubbleItem(id++, Speaker.GUARDIAN, GUARDIAN_TEXT))
        added.add(BubbleItem(id++, Speaker.CREW, caption))
        return BubbleStack((base.items + added).takeLast(MAX_ITEMS), id)
    }

    /** 보이는 순서(최신 0)에 따른 알파 */
    fun alphaForRank(rank: Int): Float = when (rank) {
        0 -> ALPHA_NEWEST
        1 -> ALPHA_SECOND
        else -> ALPHA_THIRD
    }

    /** 영역 안(위쪽·좌우 한계)이고 얼굴·점유 사각형과 겹치지 않으면 true. 변만 닿는 것은 겹침이 아니다 */
    fun isClear(rect: PxRect, face: PxRect, obstacles: List<PxRect>, region: RegionBounds): Boolean =
        rect.top >= region.top && rect.left >= region.left && rect.right <= region.right &&
            !rect.intersects(face) && obstacles.none { rect.intersects(it) }

    /**
     * 정렬: 슬롯 안에서 풍선 왼쪽 좌표. widthPx는 실제 그려질 풍선 폭(꼬리 돌출 포함, slot.maxWidth 이하).
     * GUARDIAN은 슬롯 종류와 무관하게 슬롯 오른쪽 끝에 붙인다. CREW: RIGHT_OF_HEAD 왼쪽 끝(꼬리가 왼쪽 변),
     * LEFT_OF_HEAD 오른쪽 끝(꼬리가 오른쪽 변), CHEST_CENTER 가로 가운데.
     */
    fun alignedLeft(slot: BubblePlacement, speaker: Speaker, widthPx: Float): Float {
        val right = slot.left + slot.maxWidth
        return when (speaker) {
            Speaker.GUARDIAN -> right - widthPx
            Speaker.CREW -> when (slot.slot) {
                BubbleSlot.RIGHT_OF_HEAD -> slot.left
                BubbleSlot.LEFT_OF_HEAD -> right - widthPx
                BubbleSlot.CHEST_CENTER -> slot.left + (slot.maxWidth - widthPx) / 2f
            }
        }
    }

    /** 실제 측정한 풍선 몸체 크기(px). height는 꼬리 위 돌출·아래 그림자 여유를 뺀 몸체 높이 */
    data class BubbleSize(val width: Float, val height: Float)

    /** top: 몸체 윗변. withinLimit: 위쪽 한계 이상이며 더 새로운 풍선도 모두 한계 안(최신은 항상 true) */
    data class RestackedBubble(val top: Float, val withinLimit: Boolean)

    /**
     * 실제 높이로 위쪽에서 다시 쌓기. sizes는 오래된 것 먼저(마지막이 최신), 결과도 같은 순서.
     * 최신 top = newestTop 그대로, 나머지는 바로 아래(더 새로운) 풍선의 top − gap − 자기 높이.
     */
    fun restack(sizes: List<BubbleSize>, newestTop: Float, gap: Float, topLimit: Float): List<RestackedBubble> {
        if (sizes.isEmpty()) return emptyList()
        val out = ArrayList<RestackedBubble>(sizes.size)
        var prevTop = newestTop
        var ok = true
        for (i in sizes.indices.reversed()) {
            val newest = i == sizes.size - 1
            val top = if (newest) newestTop else prevTop - gap - sizes[i].height
            if (!newest) ok = ok && top >= topLimit
            out.add(RestackedBubble(top, ok))
            prevTop = top
        }
        out.reverse()
        return out
    }

    private fun linesOf(chars: Int, slot: BubblePlacement, d: Float): Int =
        StageBubblePlacement.estimateLines(chars, slot.maxWidth - StageBubble.TAIL_MAX_DP * d, d)
            .coerceIn(1, StageBubblePlacement.MAX_LINES)

    /**
     * 최신(마지막) 풍선은 slot.left/top 그대로, 나머지는 그 위로 GAP만큼 띄워 쌓는다. 보이는 순서대로 알파 1.0/0.45/0.25.
     * 위쪽 한계·좌우 한계 밖이거나 얼굴·점유 사각형(obstacles)과 겹치면 그 풍선과 더 오래된 풍선은 visible=false(alpha 0).
     * 눈 영역은 얼굴 사각형 안에 있으므로 face 하나로 덮인다(필요하면 obstacles에 눈 사각형을 더 넣어도 된다).
     */
    fun layout(
        stack: BubbleStack,
        slot: BubblePlacement,
        measure: BubbleMeasure,
        region: RegionBounds,
        obstacles: List<PxRect>,
        face: PxRect,
    ): StackLayout {
        val items = stack.items
        if (items.isEmpty()) return StackLayout(emptyList(), slot.tail)
        val d = measure.density
        val out = ArrayList<StackedBubble>(items.size)
        var prevTop = slot.top
        var hidden = false
        var rank = 0
        for (i in items.indices.reversed()) {
            val item = items[i]
            val newest = i == items.size - 1
            var lines = linesOf(item.text.length, slot, d)
            if (newest) lines = min(lines, max(1, slot.allowedLines))
            val estW = (item.text.length * StageBubblePlacement.TEXT_DP * StageBubblePlacement.EM_FACTOR +
                2 * StageBubblePlacement.INNER_PAD_DP) * d
            val width = if (lines > 1) slot.maxWidth else min(slot.maxWidth, max(MIN_WIDTH_DP * d, estW))
            val height = (lines * StageBubblePlacement.LINE_H_DP + 2 * StageBubblePlacement.PAD_V_DP) * d
            val left = if (item.speaker == Speaker.GUARDIAN) slot.left + slot.maxWidth - width else slot.left
            val top = if (newest) slot.top else prevTop - GAP_DP * d - height
            prevTop = top
            val rect = PxRect(left, top, left + width, top + height)
            val visible = newest || (!hidden && isClear(rect, face, obstacles, region))
            if (!visible) hidden = true
            val alpha = if (!visible) 0f else alphaForRank(rank)
            if (visible) rank++
            out.add(StackedBubble(item, left, top, width, height, alpha, newest && item.speaker == Speaker.CREW, visible))
        }
        out.reverse()
        return StackLayout(out, slot.tail)
    }
}
