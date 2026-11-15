package com.crewguide.probe

import android.content.Context
import android.widget.LinearLayout

/**
 * debug 전용 시안(2026-11-13 디자인 담당): "좌석 화면 하나로 확인까지 끝내기" 한 화면.
 * 가짜 [AutoCheckUi](계약 v2)를 [SeatBoardRules.screenModel]로 바꿔 실제 앱과 같은 [SeatBoardStage]로 그린다(진행 단계 줄만 여기서 더함).
 * 모든 값은 가짜(모의)다. 차량·음성·저장 상태를 바꾸지 않는다.
 *
 * variant: checking(기본, 확인 중 섞임) | confirmed(확인 끝, 출발 준비) | arrived(도착) | allleft(모두 내림 + 스티커판)
 */
object SeatBoardMock {
    val CHILDREN = listOf(ChildTag(1, "하늘"), ChildTag(2, "바다"), ChildTag(3, "별"))
    private val LABEL = mapOf(
        "ROW_1_LEFT" to "앞줄 왼쪽", "ROW_1_RIGHT" to "앞줄 오른쪽", "ROW_2_LEFT" to "뒷줄 왼쪽", "ROW_2_CENTER" to "뒷줄 가운데", "ROW_2_RIGHT" to "뒷줄 오른쪽",
    )

    class Model(val scene: Scene, var ac: AutoCheckUi, val caption: String) {
        /** 자리를 누르면 다음 아이로(다른 자리에 이미 있는 아이는 건너뜀, 마지막 다음은 없음). 실제 앱에서는 흐름(개발)이 한다 */
        fun tap(seat: String) {
            val taken = ac.rows.filter { it.seat != seat }.mapNotNull { it.childId }.toSet()
            ac = ac.copy(rows = ac.rows.map { r ->
                if (r.seat != seat) r else {
                    val next = SeatBoardRules.nextChild(r.childId, ac.children, taken)
                    r.copy(childId = next, selected = next != null)
                }
            })
        }

        fun guardian(seat: String) {
            ac = ac.copy(rows = ac.rows.map { if (it.seat == seat) it.copy(verdict = SeatVerdict.GUARDIAN_CONFIRMED) else it })
        }
    }

    /** 뒷좌석 벨트만 모의(에뮬레이터 VC에 좌석별 벨트 조작이 없음). 착석은 VC 실제 신호, 앞좌석 벨트는 차량 값으로 둔다(가정) */
    private fun row(seat: String, occ: SeatOcc, belt: SeatBeltRef, childId: Int? = null, verdict: SeatVerdict? = null): SeatCheckRow {
        val beltMock = seat.startsWith("ROW_2")
        return SeatCheckRow(seat, LABEL.getValue(seat), childId != null, occ, belt, verdict, beltMock, childId = childId, occMock = false, beltMock = beltMock)
    }

    fun model(variant: String): Model = when (variant) {
        "confirmed" -> Model(Scene.DEPART_READY, AutoCheckUi(AutoPhase.CONFIRMED, listOf(
            row("ROW_1_LEFT", SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL),
            row("ROW_1_RIGHT", SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL),
            row("ROW_2_LEFT", SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, 1, SeatVerdict.CONFIRMED),
            row("ROW_2_CENTER", SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL),
            row("ROW_2_RIGHT", SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, 2, SeatVerdict.CONFIRMED),
        ), anyMock = true, children = CHILDREN.take(2)), "${SeatCheckRule.TEXT_DONE_SIGNAL} ${TripGuide.TEXT_READY_TO_GO}.")
        "arrived" -> Model(Scene.ARRIVED, AutoCheckUi(AutoPhase.ARRIVED, listOf(
            row("ROW_1_LEFT", SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL),
            row("ROW_1_RIGHT", SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL),
            row("ROW_2_LEFT", SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, 1, SeatVerdict.CONFIRMED),
            row("ROW_2_CENTER", SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL),
            row("ROW_2_RIGHT", SeatOcc.EMPTY, SeatBeltRef.UNBUCKLED_SIGNAL, 2, SeatVerdict.CONFIRMED),
        ), stickerEarned = true, anyMock = true, children = CHILDREN.take(2)), TripGuide.TEXT_ARRIVED)
        "allleft" -> Model(Scene.ALL_LEFT, AutoCheckUi(AutoPhase.ALL_LEFT,
            listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").map { row(it, SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL) },
            stickerEarned = true, stickerJustGranted = true, anyMock = true, children = CHILDREN.take(2)), TripGuide.TEXT_ALL_LEFT)
        // checking: 운전석 이용중, 조수석 확인 불가 + 별 배정 → [보호자 확인](결정 15), 뒷줄 왼쪽 하늘 벨트, 가운데 비어있음, 뒷줄 오른쪽 바다 이용중(벨트 기다림)
        else -> Model(Scene.AUTO_CHECK, AutoCheckUi(AutoPhase.CHECKING, listOf(
            row("ROW_1_LEFT", SeatOcc.OCCUPIED, SeatBeltRef.NO_SIGNAL),
            row("ROW_1_RIGHT", SeatOcc.UNKNOWN, SeatBeltRef.UNKNOWN),   // C2: 앞줄에는 아이를 배정하지 않는다(결정 23)
            row("ROW_2_LEFT", SeatOcc.OCCUPIED, SeatBeltRef.BUCKLED_SIGNAL, 1, SeatVerdict.CONFIRMED),
            row("ROW_2_CENTER", SeatOcc.EMPTY, SeatBeltRef.NO_SIGNAL),
            row("ROW_2_RIGHT", SeatOcc.OCCUPIED, SeatBeltRef.UNBUCKLED_SIGNAL, 2, SeatVerdict.WAIT_BUCKLE),
        ), anyMock = true, children = CHILDREN), SeatCheckRule.remindText("ROW_2_RIGHT"))
    }
}

/** 시안 한 화면: 진행 단계 줄 + [SeatBoardStage](실제 앱과 같은 View). 자리 누름·보호자 확인은 가짜 상태만 바꾼다 */
class SeatBoardMockScreen(context: Context, private val m: SeatBoardMock.Model) : LinearLayout(context) {
    /** 도구(레이아웃 편집기)용 기본 생성자: 확인 중 시안 */
    constructor(context: Context) : this(context, SeatBoardMock.model("checking"))

    private val night = Palette.current.night
    private val steps = AutoPhaseStepView(context)
    private val stage = SeatBoardStage(context)

    init {
        orientation = VERTICAL
        val g = V6Panels.stageGradient(night).map { Palette.argb(it) }.toIntArray()
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, g)
        val d = resources.displayMetrics.density
        setPadding(0, (12 * d).toInt(), 0, 0)
        addView(steps, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (8 * d).toInt() })
        stage.onSeatTap = { s -> m.tap(s); render() }
        stage.onGuardian = { s -> m.guardian(s); render() }
        render()
    }

    fun render() {
        steps.bind(true, m.ac.phase, night)
        val label = if (controlsOf(m.scene, false).showFinish) finishLabelOf(m.scene) else null
        val sm = SeatBoardRules.screenModel(m.scene, m.ac, m.caption, label, true) ?: return
        stage.setStickerJustGranted(m.ac.stickerJustGranted)   // 계약 값(2026-11-14 루프 r1 C5)
        stage.bind(sm, night, PreviewHooks.FAKE_WEEK)
    }
}
