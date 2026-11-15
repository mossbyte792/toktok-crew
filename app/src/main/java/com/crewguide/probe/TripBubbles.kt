package com.crewguide.probe

// 2026-11-14 디자인 담당(지시 4): 이동 흐름 무대의 말풍선 기록. 순수 규칙(Android 클래스 없음) + 프로세스 안 보관(화면 재생성 대비).
// - 말풍선 하나 = 캐릭터가 말한 문장 하나. 입력 우선순위: PM 계약 v3 `AutoCheckUi.bubbleLine`/`bubbleSeq`(순번 > 0, 개발이 TTS 요청 때 채움)
//   → 없으면(순번 0) 장면 자막(captionOf)을 [lineOf]로 고친 값(VOICE_BUBBLE_MAP 1절의 '말풍선' 열과 같은 문장).
// - 새 문장은 아래(캐릭터 머리 옆)에 생기고, 이전 문장은 위로 밀려 무대 카드 위쪽에서 사라진다(그리기는 TripBubbleColumn).
// - 장면이 바뀌어도 비우지 않는다. 비우는 때 = 홈으로 갈 때(이동 흐름 상태 없음), 새 이동을 시작할 때(확인 중으로 새로 들어올 때), [StageScreen.resetBubbles].
// - 잠금(주행 중·기어 불명·단절)에는 새 문장을 넣지 않는다(주행 중 화면에 새 정보 없음, 결정 8).
// ISSUE-10(같은 자리에 흐리게 겹쳐 남는 지난 말풍선 금지)은 그대로 지킨다: 이전 문장은 위로 밀린 자리에서만 보인다.

/** 기록 한 줄. id는 화면 View 재사용 키(같은 문장이 늘어나 바뀐 경우 id 유지) */
data class TripBubble(val id: Long, val text: String)

/** 오래된 것 먼저, 마지막이 지금 말풍선. [phase]는 마지막으로 본 이동 단계(새 이동 판단용) */
data class TripBubbleLog(
    val items: List<TripBubble> = emptyList(),
    val nextId: Long = 1L,
    val phase: AutoPhase? = null,
    /** 마지막으로 본 계약 v3 순번([AutoCheckUi.bubbleSeq]). 0이면 아직 순번 입력 없음 */
    val seq: Int = 0,
    /**
     * 보류(I3, 2026-11-14): 이동 흐름 상태가 잠깐 없어진 동안(글꼴·야간 변경으로 화면을 다시 만든 직후, 확인을 이어 가기 전) 기록을 버리지 않고 들고 있다.
     * 보류 중에는 좌석 화면 무대가 없어 그리지 않는다. 풀리는 조건은 [TripBubbleRules.enter]
     */
    val held: Boolean = false,
) {
    val current: TripBubble? get() = items.lastOrNull()
}

object TripBubbleRules {
    /** 지금 1개 + 이전 최대 2개 */
    const val MAX_ITEMS = 3
    /** 이전 말풍선 투명도(순서대로 바로 위, 그 위). 위쪽 페이드 마스크와 함께 쓴다(장식, 읽기 대상 아님) */
    const val ALPHA_PREV_1 = 0.7f
    const val ALPHA_PREV_2 = 0.4f
    /** 밀려 올라가는 시간(지시 4: 200~300ms) */
    const val PUSH_MS = 260L
    /** 말풍선 사이 간격(dp) */
    const val GAP_DP = 12
    /** 자막이 이 시간 동안 그대로일 때만 기록에 넣는다(자동 배정처럼 곧바로 바뀌는 자막은 말하지 않은 문장이라 쌓지 않는다) */
    const val SETTLE_MS = 500L

    /**
     * 장면 자막 → 말한 문장. 잠금 사유(S09 안내판)는 말풍선이 아니라 null.
     * 모두 내림 자막은 화면 제목과 같은 문장이라 마무리 안내 문장(TripGuide.TEXT_ALL_LEFT)을 쓴다(VOICE_BUBBLE_MAP B8).
     */
    fun lineOf(scene: Scene, caption: String): String = when {
        scene == Scene.LOCKED -> ""
        // r6: 문 기준 자막(SeatBoardRules.allLeftCaption(true))도 같은 마무리 문장으로
        scene == Scene.ALL_LEFT && (caption == TripGuide.TITLE_ALL_LEFT || caption == SeatBoardRules.allLeftCaption(true)) -> TripGuide.TEXT_ALL_LEFT
        // r10d A 공통 문구(스위치 끔 r10c: 손님이 함께 탄 이동 자막)는 음성과 같은 '아이가' 없는 문장으로
        scene == Scene.ALL_LEFT && (caption == SeatBoardRules.allLeftCaption(false, guest = true) ||
            caption == SeatBoardRules.allLeftCaption(true, guest = true)) -> TripGuide.TEXT_ALL_LEFT_COMMON
        else -> caption.trim()
    }

    /**
     * 기록을 비울지: 이동 흐름 상태가 없으면(홈·옛 흐름) 비운다. 확인 중(CHECKING)으로 새로 들어오면(직전 단계가 확인 중이 아님) 새 이동이라 비운다.
     * 같은 확인 중 반복·화면 재생성(보관한 단계가 같음)은 비우지 않는다.
     */
    fun shouldClear(prev: AutoPhase?, phase: AutoPhase?): Boolean =
        phase == null || (phase == AutoPhase.CHECKING && prev != AutoPhase.CHECKING)

    /**
     * 화면 갱신 한 번의 첫 단계: 기록을 비울지·보류할지·이어 갈지 정한다. [seq]는 계약 v3 순번(없으면 0).
     * - 이동 흐름 상태가 없음([phase] == null): 순번으로 쌓은 기록(기록의 순번 > 0)이면 버리지 않고 보류한다([TripBubbleLog.held]).
     *   글꼴·야간 변경으로 화면을 다시 만들면 확인을 이어 가기 전 잠깐 상태가 비는데(결정 20), 그때 비우면 쌓인 말풍선이 사라지고
     *   이어 간 뒤 마지막 문장 1개만 다시 생겼다(I3). 순번 없이 자막으로 쌓은 기록은 같은 이동인지 알 수 없어 예전처럼 비운다.
     * - 보류 중 상태가 돌아옴: 순번이 이어지면([seq] >= 보류한 순번) 같은 이동이라 그대로 이어 간다.
     *   순번이 처음부터 다시면(홈 복귀·새 이동 때 개발이 0으로 돌림) 다른 이동이라 비운다.
     * - 그 밖은 [shouldClear] 그대로. 홈 복귀·확인 취소·이동 끝내기·새 확인은 흐름이 [StageScreen.resetBubbles]로도 비운다([cleared]).
     */
    fun enter(log: TripBubbleLog, phase: AutoPhase?, seq: Int): TripBubbleLog {
        if (phase == null) return if (log.items.isNotEmpty() && log.seq > 0) log.copy(held = true) else TripBubbleLog(emptyList(), log.nextId, null, 0)
        val l = if (!log.held) log else if (seq >= log.seq) log.copy(held = false) else cleared(log)
        return if (shouldClear(l.phase, phase)) TripBubbleLog(emptyList(), l.nextId, phase, 0) else l.copy(phase = phase)
    }

    /**
     * 한 번의 화면 갱신(자막 경로, 순번 없음). [line]이 빈 문자열이면(잠금 등) 단계만 기록한다. 비우기·보류는 [enter].
     * - 직전 문장과 같으면 그대로(같은 문장 연달아 중복 없음, 재안내 반복 포함)
     * - 직전 문장을 그대로 앞에 두고 이어 말한 문장이면(확인 완료 "…받았어요." → 출발 준비 "…받았어요. 출발하면 안내할게요") 지금 말풍선을 바꾼다
     *   (음성은 한 문장이라 말풍선도 하나, VOICE_BUBBLE_MAP B5·B6)
     * - 그 밖은 새 말풍선. 최대 [MAX_ITEMS]개, 오래된 것부터 버린다
     */
    fun step(log: TripBubbleLog, phase: AutoPhase?, line: String): TripBubbleLog {
        val base = enter(log, phase, 0)
        if (phase == null || line.isBlank()) return base
        val last = base.items.lastOrNull()
        if (last != null) {
            if (last.text == line) return base
            if (line.startsWith(last.text)) return base.copy(items = base.items.dropLast(1) + last.copy(text = line))
        }
        val added = base.items + TripBubble(base.nextId, line)
        return base.copy(items = added.takeLast(MAX_ITEMS), nextId = base.nextId + 1)
    }

    /**
     * 계약 v3 입력(순번이 있는 말한 문장) 한 번. [seq]가 기록의 순번과 다를 때만 새 문장으로 본다.
     * - [show]=false(잠금·좌석 화면 아님): 순번만 기록하고 쌓지 않는다(주행 중 말한 문장은 화면에 올리지 않는다, 결정 8)
     * - 같은 문장이 연달아 오면(재안내 반복) 새 말풍선을 만들지 않는다(지시 4 '같은 문장 연달아 중복 금지'). 계약 주석은
     *   '순번이 바뀌면 같은 문장도 새 말풍선'이지만 화면 규칙을 앞세웠다 — 바꾸려면 [STACK_REPEATED_SEQ]만 true로
     * 비우기·보류·이어 가기는 [enter](순번 기준).
     */
    fun stepSpoken(log: TripBubbleLog, phase: AutoPhase?, line: String, seq: Int, show: Boolean): TripBubbleLog {
        val base = enter(log, phase, seq)
        if (phase == null || seq == base.seq) return base
        val withSeq = base.copy(seq = seq)
        if (!show || line.isBlank()) return withSeq
        val last = withSeq.items.lastOrNull()
        if (last != null && last.text == line && STACK_REPEATED_SEQ) {
            val added = withSeq.items + TripBubble(withSeq.nextId, line)
            return withSeq.copy(items = added.takeLast(MAX_ITEMS), nextId = withSeq.nextId + 1)
        }
        return step(withSeq, phase, line)
    }

    /** 순번이 바뀐 같은 문장을 새 말풍선으로 쌓을지(계약 v3 주석). 기본 false = 지시 4 중복 금지 */
    const val STACK_REPEATED_SEQ = false

    /** 비우기([StageScreen.resetBubbles]): id는 이어서 쓴다(지난 View와 섞이지 않게) */
    fun cleared(log: TripBubbleLog): TripBubbleLog = TripBubbleLog(emptyList(), log.nextId, null, 0)

    /** 보이는 순서(0 = 지금)에 따른 투명도. 지금 말풍선은 1(대비 4.5:1 검사 대상) */
    fun alphaOf(rank: Int): Float = when (rank) {
        0 -> 1f
        1 -> ALPHA_PREV_1
        else -> ALPHA_PREV_2
    }

    /**
     * 세로 배치(px). [heights]는 오래된 것 먼저(마지막이 지금). 결과 = 같은 순서의 윗변 y.
     * 지금 말풍선: 아랫변을 [anchorBottom](캐릭터 머리 옆, 꼬리가 머리를 가리킴)에 맞춘다. 위가 0보다 위로 나가면 0으로 내리고,
     * 아래가 [bottomLimit](버튼 위)를 넘으면 그 위로 올린다(그래도 넘치면 호출 측이 글자를 줄인다).
     * 이전 말풍선은 바로 아래 말풍선 윗변 − [gap] − 자기 높이(위로 쌓임, 0보다 위로 나간 부분은 페이드 마스크로 보이지 않는다).
     */
    fun tops(heights: List<Int>, anchorBottom: Int, bottomLimit: Int, gap: Int): List<Int> {
        if (heights.isEmpty()) return emptyList()
        val out = IntArray(heights.size)
        val n = heights.size - 1
        val h = heights[n]
        var top = anchorBottom - h
        if (top + h > bottomLimit) top = bottomLimit - h
        if (top < 0) top = 0
        out[n] = top
        for (i in n - 1 downTo 0) out[i] = out[i + 1] - gap - heights[i]
        return out.toList()
    }

    // ---- 캐릭터와 겹치지 않는 자리(2026-11-14 사용자 지적: 지금·이전 말풍선이 캐릭터 머리·모자와 겹침) ----
    /** 말풍선(꼬리 끝·그림자 포함 상자)과 캐릭터 보이는 부분 사이에 두는 틈(dp). 가로·세로 같은 값 */
    const val ART_GAP_DP = 8
    /** 무대 카드 오른쪽 테두리 안쪽 여백(dp). 왼쪽 여백(말풍선 칸 leftMargin 24dp)과 같다 */
    const val CARD_INSET_DP = 24
    /**
     * 지금 말풍선 아랫변(상자 기준)이 닿는 높이 = 그림 높이의 이 비율(눈썹 높이, 묶은 머리가 왼쪽으로 튀어나오기 직전).
     * 예전 0.40(턱)보다 위다: 몸체가 얼굴을 가리지 않고 모자·머리 왼쪽 빈 곳에 놓이고, 꼬리(오른쪽 아래)가 머리를 가리킨다.
     * 이 값은 보기 좋은 자리만 정한다. 겹침 여부는 [rightLimit]이 실제 윤곽으로 막는다(값을 바꿔도 겹치지 않는다).
     */
    const val ANCHOR_HEAD_FRAC = 0.25f
    /** 무대가 낮아(1920×720) 위 공간이 모자랄 때 내려갈 수 있는 가장 낮은 높이 = 턱(예전 기준). 꼬리가 머리 밖을 가리키지 않게 */
    const val ANCHOR_CHIN_FRAC = 0.40f
    /**
     * 지금 말풍선 위로 두고 싶은 공간(dp): 두 줄 말풍선 하나(약 115) + 간격 + 이전 말풍선이 반쯤 보일 높이.
     * 낮은 무대에서 [ANCHOR_HEAD_FRAC] 자리에 두면 이전 말풍선이 전부 위 경계 밖으로 나가 쌓임이 보이지 않아, 이만큼은 남긴다.
     */
    const val ROOM_ABOVE_DP = 190
    /** 이전 말풍선이 위 경계 아래로 이만큼(dp)도 안 남으면 그리지 않는다(경계선에 얇은 띠만 걸리는 모양 방지) */
    const val MIN_PEEK_DP = 28

    /**
     * 지금 말풍선 아랫변 목표 y(칸 좌표 px). [headY]·[chinY] = 그림의 [ANCHOR_HEAD_FRAC]·[ANCHOR_CHIN_FRAC] 높이, [room] = [ROOM_ABOVE_DP](px).
     * 위 공간이 넉넉하면 머리 높이, 모자라면 [room]만큼 내려오되 턱보다 아래로는 가지 않는다. 무대 크기로만 정해져 말풍선 수·글자와 무관하게 같은 자리다.
     */
    fun anchorBottom(headY: Int, chinY: Int, room: Int): Int = if (chinY <= headY) headY else room.coerceIn(headY, chinY)

    /**
     * 말풍선 상자 오른쪽 끝(꼬리 끝)이 갈 수 있는 한계 x(칸 좌표 px). 아랫변이 [bottomY]인 말풍선 묶음(그 위로 쌓인 이전 말풍선 포함)이
     * 캐릭터 보이는 부분과 [gap] 이상 떨어지도록, 그림 맨 위부터 [bottomY]+[gap] 높이까지의 윤곽 중 가장 왼쪽을 본다.
     * [artLeft]·[artTop]·[scale] = 그림이 그려진 자리(칸 좌표)와 배율, [maxRight] = 카드 안쪽 오른쪽 한계.
     * 윤곽을 모르면([sil] == null) 그림 상자 왼쪽 끝을 쓴다(더 좁게, 겹치지 않는 쪽).
     */
    fun rightLimit(sil: ArtSilhouette?, artLeft: Float, artTop: Float, scale: Float, bottomY: Int, gap: Int, maxRight: Int): Int {
        if (scale <= 0f) return maxRight
        // 그림 행 r은 화면 y = artTop + r×scale 에서 시작한다 → (bottomY + gap)보다 위에서 시작하는 마지막 행
        val row = kotlin.math.ceil(((bottomY + gap) - artTop) / scale).toInt() - 1
        if (row < 0) return maxRight   // 묶음 전체가 그림보다 위
        val left = if (sil == null) 0 else sil.leftUpTo(row)
        if (sil != null && left >= sil.width) return maxRight   // 그 높이까지 보이는 픽셀 없음
        return minOf(maxRight, kotlin.math.floor(artLeft + left * scale).toInt() - gap)
    }

    /** 이전 말풍선을 그릴지: 위 경계(0) 아래로 남는 높이가 [minPeek] 이상일 때만. 지금 말풍선은 늘 그린다 */
    fun prevShown(top: Int, height: Int, minPeek: Int): Boolean = top + height >= minPeek

    /** 위쪽 페이드가 끝나는 y(px): 지금 말풍선 윗변보다 [gap]/2 위. 지금 말풍선은 페이드를 받지 않는다 */
    fun fadeEnd(currentTop: Int, gap: Int): Int = (currentTop - gap / 2).coerceAtLeast(0)

    /** 화면 연출(밀어 올림)을 할지: 시스템 애니메이션 켜짐 + 화면이 보임 + 잠금 아님 */
    fun animate(animatorsEnabled: Boolean, shown: Boolean, locked: Boolean): Boolean = animatorsEnabled && shown && !locked
}

/**
 * 캐릭터 그림에서 실제로 보이는 부분의 왼쪽 윤곽(그림 px, 순수). [prefixLeft]`[y]` = 0..y 행에서 가장 왼쪽 보이는 x(없으면 [width]).
 * 위에서부터 누적한 값이라 "아랫변이 y인 말풍선 묶음"이 피해야 할 가장 왼쪽 x를 바로 준다([TripBubbleRules.rightLimit]).
 */
class ArtSilhouette(val width: Int, val height: Int, private val prefixLeft: IntArray) {
    /** 0..[row] 행의 가장 왼쪽 보이는 x. [row]가 그림 아래면 전체, 0보다 위면 [width](없음) */
    fun leftUpTo(row: Int): Int = if (row < 0 || height == 0) width else prefixLeft[row.coerceAtMost(height - 1)]

    companion object {
        /** 이 알파(0~255)를 넘는 픽셀을 '보인다'고 본다(가장자리 번짐 제외) */
        const val ALPHA_MIN = 16

        /** [argb] = 행 우선 ARGB 픽셀([width]×[height]) */
        fun of(width: Int, height: Int, argb: IntArray): ArtSilhouette {
            val out = IntArray(height)
            var best = width
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until minOf(best, width)) if ((argb[base + x] ushr 24) > ALPHA_MIN) { best = x; break }
                out[y] = best
            }
            return ArtSilhouette(width, height, out)
        }
    }
}

/**
 * 프로세스 안 말풍선 기록 보관(야간·주간 전환 등 무대 재생성 뒤에도 같은 기록을 이어 그린다). 저장소에 쓰지 않는다(문장에 아이 별명이 들어갈 수 있음).
 * 쓰는 곳은 StageScreen 하나다.
 */
object TripBubbleStore {
    @Volatile var log: TripBubbleLog = TripBubbleLog()
}
