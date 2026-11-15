package com.crewguide.probe

/**
 * 직접 그린 SVG 부품의 데이터 모델(가안, 개발용). Android 클래스를 쓰지 않는 순수 로직.
 * 좌표계는 0~100 정사각 캔버스.
 */

/** 채움 색의 역할. HIGHLIGHT는 눈 하이라이트용 고정 흰색(요청 목록 밖의 최소 추가), NONE은 채우지 않음 */
enum class ColorRole { SKIN, HAIR, OUTFIT_BODY, OUTFIT_SCARF, EYE, CHEEK, MOUTH, LINE, HIGHLIGHT, NAME_TAG_YELLOW, NONE }

/** 부품 윤곽 두께(캔버스 단위, 0~100). 얼굴 선(눈썹·입)은 이 값을 쓰지 않고 기존 1.4~1.6을 유지한다 */
object OutlineWidths {
    /** 몸·팔·얼굴·머리카락·모자 윤곽 */
    const val MAIN: Float = 2.4f
    /** 목·귀·손·스카프처럼 작은 부품 윤곽 */
    const val SUB: Float = 2.2f
    /** 빈 이름표 윤곽(작은 배지라 얇게) */
    const val TAG: Float = 1.8f
}

enum class PartGroup { BODY, HEAD, ARM_FRONT, ARM_BACK, ACCESSORY }

/** 경로 하나. fill이 NONE이면 채우지 않고, stroke가 true면 고정 선 색으로 윤곽을 그린다 */
data class PartPath(
    val d: String,
    val fill: ColorRole,
    val stroke: Boolean,
    val strokeWidth: Float = 1.8f,
)

/** 부품 하나. layer가 작을수록 먼저(뒤에) 그린다 */
data class SvgPart(
    val name: String,
    val layer: Int,
    val paths: List<PartPath>,
    val group: PartGroup,
)
