package com.crewguide.probe

import kotlin.math.PI
import kotlin.math.sin

/**
 * 2026-11-14 디자인 C: 모두 내림(ALL_LEFT) 화면의 스티커 별 연출 — 순수 규칙(시간표·단계·재생 조건). Android 클래스 없음.
 *
 * 흐름: 별이 나타남([APPEAR_MS]) → 잠깐 멈춤([HOLD_MS]) → 오늘 칸으로 날아감([MOVE_MS]) → 칸에 들어가 칸이 강조됨([LAND_MS]).
 * 전체 [TOTAL_MS] = 2.7초. 이전 연출(약 1.1초)은 아이가 따라 보기 어려웠다(00_인수인계 6절 ②).
 * 그리는 쪽은 [StickerFlyHost]·[StickerFlyLayer](StickerFlyLayer.kt), 판은 [StickerBoardView].
 *
 * 조건(지시 3):
 * - 주차(P)·화면 조작 허용 + 화면이 보임 + 시스템 애니메이션 켜짐 + 오늘 칸에 별이 있을 때만 움직인다. 아니면 결과만 바로 보인다.
 * - 이동당 1회: 지급 표시([AutoCheckUi.stickerJustGranted])를 처음 본 때 한 번만 정한다(움직였든 결과만 보였든 다시 재생하지 않는다).
 *   무대가 다시 만들어져도(야간 전환 등) [StickerFlyStore]가 프로세스 안에 남아 결과 상태로 보인다.
 * - 음성(작별 인사 등)과 무관하게 화면에서만 돈다. 기록·지급 횟수를 바꾸지 않는다(표시 전용).
 *
 * 깜박임: 밝기가 바뀌는 것은 오늘 칸이 채워지는 1회, 크기 맥동도 1회([LAND_MS] 동안)라 초당 3회 미만이다([FLASHES_PER_SEC_MAX]).
 */
object StickerFly {
    const val APPEAR_MS = 500L
    const val HOLD_MS = 400L
    const val MOVE_MS = 1000L
    const val LAND_MS = 800L
    const val TOTAL_MS = APPEAR_MS + HOLD_MS + MOVE_MS + LAND_MS

    /** 나타날 때 한 번 커졌다 돌아오는 크기(1.0 = 제 크기)와 그 시점(나타남 구간의 비율) */
    const val APPEAR_PEAK = 1.15f
    const val APPEAR_PEAK_AT = 0.7f
    /** 나타남 구간의 이 비율까지 투명도 0 → 1 */
    const val APPEAR_FADE_UNTIL = 0.3f
    /** 착지 뒤 날아온 별(위에 얹은 그림)이 사라지는 시간. 그 자리에 칸의 ★ 글자가 남는다 */
    const val LAND_STAR_FADE_MS = 160L
    /** 착지 강조: 오늘 칸이 한 번 커졌다 돌아오는 폭(1.0 + 이 값) */
    const val CELL_PULSE = 0.10f

    /** 날아가는 별(주황 원 + ★) 지름(dp). 장식이라 글꼴 배율을 따르지 않는다 */
    const val STAR_DP = 112
    /** 날아가는 길이 위로 휘는 높이(dp) */
    const val ARC_LIFT_DP = 44
    /** 착지 강조 테두리: 굵기·바깥으로 퍼지는 거리(dp) */
    const val HALO_STROKE_DP = 6
    const val HALO_GROW_DP = 12
    /** 별이 카드 가장자리에서 떨어져 있어야 하는 여백(dp) */
    const val EDGE_PAD_DP = 8

    /** 연출 전체에서 밝기·크기가 번쩍 바뀌는 횟수(칸 채움 1 + 칸 맥동 1)와 허용 한계(초당) */
    const val FLASH_COUNT = 2
    const val FLASHES_PER_SEC_MAX = 3f

    enum class Step { APPEAR, HOLD, MOVE, LAND, DONE }

    fun stepAt(ms: Long): Step = when {
        ms < APPEAR_MS -> Step.APPEAR
        ms < APPEAR_MS + HOLD_MS -> Step.HOLD
        ms < APPEAR_MS + HOLD_MS + MOVE_MS -> Step.MOVE
        ms < TOTAL_MS -> Step.LAND
        else -> Step.DONE
    }

    /**
     * 한 순간의 그림 값.
     * [starScale] 별 크기 배율(나타남 구간의 튀어 오름), [starAlpha] 별 투명도, [travel] 0 = 나타난 자리 · 1 = 오늘 칸(별 크기도 이 값으로 줄어든다),
     * [landed] 오늘 칸을 결과 모습(주황 채움 + ★ + 참여 N번)으로 그릴지 — false면 칸은 아직 비어 보인다,
     * [cellScale] 오늘 칸 크기 배율, [halo] 강조 테두리 진행(0 = 없음 … 1 = 다 퍼져 사라짐).
     */
    data class Frame(
        val step: Step,
        val starScale: Float,
        val starAlpha: Float,
        val travel: Float,
        val landed: Boolean,
        val cellScale: Float,
        val halo: Float,
    )

    /** 끝난 뒤(결과) 값: 별 없음, 칸은 결과 모습·제 크기 */
    val RESULT = Frame(Step.DONE, 1f, 0f, 1f, landed = true, cellScale = 1f, halo = 0f)

    /** 부드럽게 출발·도착(0..1 → 0..1) */
    fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    private fun easeOut(t: Float): Float { val x = 1f - t.coerceIn(0f, 1f); return 1f - x * x }

    fun frameAt(ms: Long): Frame {
        val t = ms.coerceAtLeast(0L)
        return when (stepAt(t)) {
            Step.APPEAR -> {
                val u = t.toFloat() / APPEAR_MS
                val scale = if (u < APPEAR_PEAK_AT) APPEAR_PEAK * easeOut(u / APPEAR_PEAK_AT)
                    else APPEAR_PEAK + (1f - APPEAR_PEAK) * ((u - APPEAR_PEAK_AT) / (1f - APPEAR_PEAK_AT))
                Frame(Step.APPEAR, scale, (u / APPEAR_FADE_UNTIL).coerceAtMost(1f), 0f, landed = false, cellScale = 1f, halo = 0f)
            }
            Step.HOLD -> Frame(Step.HOLD, 1f, 1f, 0f, landed = false, cellScale = 1f, halo = 0f)
            Step.MOVE -> {
                val u = (t - APPEAR_MS - HOLD_MS).toFloat() / MOVE_MS
                Frame(Step.MOVE, 1f, 1f, ease(u), landed = false, cellScale = 1f, halo = 0f)
            }
            Step.LAND -> {
                val into = t - APPEAR_MS - HOLD_MS - MOVE_MS
                val u = into.toFloat() / LAND_MS
                Frame(Step.LAND, 1f, (1f - into.toFloat() / LAND_STAR_FADE_MS).coerceIn(0f, 1f), 1f, landed = true,
                    cellScale = 1f + CELL_PULSE * sin(PI * u).toFloat(), halo = u)
            }
            Step.DONE -> RESULT
        }
    }

    /** 초당 번쩍임 횟수(가장 빽빽한 구간 = 착지 강조). 3 미만이어야 한다 */
    fun flashesPerSec(): Float = FLASH_COUNT * 1000f / LAND_MS

    // ---- 위치(카드 좌표, px) ----
    data class Pt(val x: Float, val y: Float)

    /** 별이 가장 커졌을 때([APPEAR_PEAK])도 여백 [padPx]를 두고 들어가는 빈 곳인가(폭·높이 px) */
    fun fits(areaW: Float, areaH: Float, starPx: Float, padPx: Float): Boolean {
        val need = starPx * APPEAR_PEAK + 2f * padPx
        return areaW >= need && areaH >= need
    }

    /**
     * 별이 나타나는 자리: 주어진 영역(카드 좌표)의 가운데. 영역은 안내 글 아래 빈 곳([fits]일 때), 아니면 좌석 칸 영역이다.
     * 가장 커졌을 때([APPEAR_PEAK])도 카드 밖으로 나가지 않게 [padPx]만큼 안쪽으로 당긴다.
     */
    fun startCenter(cardW: Float, cardH: Float, areaLeft: Float, areaTop: Float, areaRight: Float, areaBottom: Float, starPx: Float, padPx: Float): Pt {
        val half = starPx * APPEAR_PEAK / 2f + padPx
        val x = ((areaLeft + areaRight) / 2f).coerceIn(minOf(half, cardW / 2f), maxOf(cardW - half, cardW / 2f))
        val y = ((areaTop + areaBottom) / 2f).coerceIn(minOf(half, cardH / 2f), maxOf(cardH - half, cardH / 2f))
        return Pt(x, y)
    }

    /** 별이 나타나는 곳의 종류: 안내 글 아래 빈칸 / 오늘 칸 자리(빈칸이 모자랄 때) */
    enum class StartAt { BLANK, ON_TARGET }

    /**
     * 빈칸에 별이 들어가면 빈칸, 아니면 오늘 칸 자리에서 나타난다.
     * 2026-11-14 캡처(글꼴 2.0, 1920×720): 빈칸이 모자라 좌석 칸 영역 가운데에서 나타났더니 날아가는 별이 "뒷줄" 글자·좌석 칸을 잠깐 가렸다.
     * 좌석 신호 정보 위로는 지나가지 않게, 모자라면 오늘 칸 위에서 나타나 제자리에 내려앉는다.
     */
    fun startAt(blankFits: Boolean): StartAt = if (blankFits) StartAt.BLANK else StartAt.ON_TARGET

    /** "★ 오늘 칸에 별이 생겼어요" 줄을 보일지: 별이 칸에 들어간 뒤(결과 포함)에만. 그 전에는 자리만 차지(높이 유지) */
    fun showGotLine(f: Frame): Boolean = f.landed

    /** [travel](0..1)만큼 간 자리. 가운데에서 [liftPx]만큼 위로 휜다(양 끝에서는 정확히 [start]·[end]) */
    fun pointAt(start: Pt, end: Pt, travel: Float, liftPx: Float): Pt {
        val e = travel.coerceIn(0f, 1f)
        return Pt(start.x + (end.x - start.x) * e, start.y + (end.y - start.y) * e - liftPx * sin(PI * e).toFloat())
    }

    /** 별 지름: 나타난 크기 [startPx]에서 칸 안 ★ 자리 크기 [endPx]로 줄어든다 */
    fun sizeAt(startPx: Float, endPx: Float, f: Frame): Float = (startPx + (endPx - startPx) * f.travel.coerceIn(0f, 1f)) * f.starScale

    // ---- 재생 조건 ----
    /**
     * 방금 받은 지급인가. [contract] = 계약 값 [AutoCheckUi.stickerJustGranted](무대가 [SeatBoardStage.setStickerJustGranted]로 넘김, 2026-11-14 루프 r1 C5).
     * 넘겨받지 못했을 때(null)만 예전처럼 줄 글자 끝맺음([AutoCheckText.STICKER_GOT])을 본다(호환용).
     */
    fun justGranted(line: SeatBoardRules.StickerLine?, contract: Boolean? = null): Boolean =
        line != null && (contract ?: line.text.endsWith(AutoCheckText.STICKER_GOT))

    /**
     * 등록한 아이 없이 진행한 이동인가(PM 결정 19: 등록 아이가 없으면 이름 없는 [SeatCheckRule.GUEST_CHILD]로 진행, **개인 기록 없음**).
     * 이때 스티커는 어느 판에도 찍히지 않는다(MainActivity: 고른 아이 없음 → 기록 0). 아이 목록을 모르면(null) 손님이 아니라고 본다.
     */
    fun guestOnly(children: List<ChildTag>?): Boolean = children != null && children.all { it.id == SeatCheckRule.GUEST_CHILD.id }

    /** 스티커판을 보일지: 장면이 판을 원하고(모두 내림 + 조건 충족) 손님 이동이 아님. 손님 이동의 판은 모든 날이 "아직 없어요"라 지급과 어긋난다 */
    fun showBoard(line: SeatBoardRules.StickerLine?, guestOnly: Boolean): Boolean = line != null && line.showBoard && !guestOnly

    /**
     * 판 아래 한 줄(없으면 null).
     * - 손님 이동: 없음(찍힌 별이 없다).
     * - 방금 받음: 오늘 칸에 실제 기록이 있을 때만 "★ 오늘 칸에 별이 생겼어요". 기록이 없으면(저장·읽기 실패, 판 없음) 없음.
     * - 이미 받은 이동(방금 받음 아님): "★ 이번 이동 스티커는 받았어요"(기존 그대로).
     */
    fun lineText(line: SeatBoardRules.StickerLine?, justGranted: Boolean, guestOnly: Boolean, todayRecorded: Boolean): String? = when {
        line == null || guestOnly -> null
        justGranted -> if (todayRecorded) "★ ${AutoCheckText.STICKER_GOT}" else null
        else -> "★ ${AutoCheckText.STICKER_EARNED}"
    }

    /**
     * 이 지급을 '본 것'(이동당 1회)으로 표시할지: 방금 받음이고, 판을 그리지 않거나 이번 주 칸이 이미 왔을 때.
     * 판을 그려야 하는데 칸이 아직 안 왔으면(무대 생성 직후) 표시하지 않고 칸이 오기를 기다린다(연출을 잃지 않게).
     */
    fun markSeen(justGranted: Boolean, boardWanted: Boolean, weekKnown: Boolean): Boolean = justGranted && (!boardWanted || weekKnown)

    /** 별이 들어갈 칸: 판이 보이고 오늘 칸에 기록이 있을 때 그 칸 번호, 없으면 -1(등록 없이 진행해 기록이 없으면 날아갈 곳이 없다 → 결과만) */
    fun targetIndex(cells: List<BoardCell>, showBoard: Boolean): Int =
        if (!showBoard) -1 else cells.indexOfFirst { it.isToday && StickerBoard.earned(it) }

    /** 움직여도 되는가: 주차·조작 허용(잠금 아님) + 화면이 보임(앱이 앞) + 시스템 애니메이션 켜짐 + 들어갈 칸 있음 */
    fun canMove(parkAllowed: Boolean, shown: Boolean, animatorsEnabled: Boolean, hasTarget: Boolean): Boolean =
        parkAllowed && shown && animatorsEnabled && hasTarget

    enum class Play { NONE, RESULT_ONLY, PLAY }

    /** 이번 그리기에서 할 일: 방금 받음이 아니거나 이미 본 지급이면 NONE(그대로), 처음 본 지급이면 조건에 따라 PLAY / RESULT_ONLY */
    fun decide(justGranted: Boolean, seen: Boolean, parkAllowed: Boolean, shown: Boolean, animatorsEnabled: Boolean, hasTarget: Boolean): Play = when {
        !justGranted || seen -> Play.NONE
        canMove(parkAllowed, shown, animatorsEnabled, hasTarget) -> Play.PLAY
        else -> Play.RESULT_ONLY
    }

    /**
     * '이미 봄' 표시를 지울 때: 새 이동의 확인 단계(CHECKING)에 들어왔을 때만.
     * 지급 표시가 잠깐 꺼졌다 켜져도(모두 내림 뒤 잠금 → 다시 P 등) 같은 이동에서는 다시 재생하지 않는다.
     */
    fun rearm(phase: AutoPhase): Boolean = phase == AutoPhase.CHECKING

    // ---- 오늘 칸 모습 ----
    /** 판 칸의 모습. PENDING = 별이 아직 도착하지 않은 오늘 칸(비어 보임: 카드 바탕 + 요일 글자만, ★·횟수 글자는 자리만 차지) */
    enum class CellLook { FUTURE, EARNED, EMPTY, PENDING }

    fun lookOf(c: BoardCell, todayPending: Boolean): CellLook = when {
        c.isFuture -> CellLook.FUTURE
        c.isToday && todayPending && StickerBoard.earned(c) -> CellLook.PENDING
        StickerBoard.earned(c) -> CellLook.EARNED
        else -> CellLook.EMPTY
    }
}

/**
 * 프로세스 안 '이번 지급 연출을 이미 봤는가' 표시(무대 재생성 뒤 다시 재생하지 않게). 저장소에 쓰지 않는다.
 * 프로세스가 다시 시작되면 지급 표시(stickerJustGranted, MainActivity 메모리 10초)도 함께 사라지므로 다시 재생되지 않는다.
 * 쓰는 곳은 [StickerFlyHost] 하나다.
 */
object StickerFlyStore {
    @Volatile var seen: Boolean = false
}
