package com.crewguide.probe

/** 입 모양 4종 */
enum class MouthShape { CLOSED, SMALL, WIDE, SMILE }

/**
 * 직접 그린 캐릭터 부품 라이브러리(가안, 개발용). 경로는 Kotlin 상수 문자열이며 0~100 정사각 캔버스.
 * 머리 지름 약 44, 중심 약 (50,40). 모든 좌표는 절대좌표(M/C/Q/L/Z)이고 곡선 위주라 모서리가 각지지 않는다.
 * 로고·날개 배지·실제 제복 복제·모자·이름표·소품은 없다(모자·이름표는 CharacterExtraParts). 부품 윤곽 두께는 OutlineWidths(2.4/2.2). 레이어 번호는 부품마다 다르다(작을수록 뒤).
 */
object CharacterParts {
    // 부품 윤곽 기본 두께(굵은 윤곽, 시안 1). 얼굴 선(눈썹·입)은 각 경로에 따로 적은 기존 값을 유지한다
    private const val SW = OutlineWidths.MAIN
    private const val SUB = OutlineWidths.SUB

    private fun p(d: String, fill: ColorRole, stroke: Boolean = true, w: Float = SW) = PartPath(d, fill, stroke, w)

    // 10 뒷머리: 얼굴 뒤로 넓게 보이는 머리 덩어리
    private val BACK_HAIR = SvgPart(
        "back_hair", 10,
        listOf(p("M 26 44 C 22 26 33 13 50 13 C 67 13 78 26 74 44 C 73 54 70 60 64 60 C 58 60 42 60 36 60 C 30 60 27 54 26 44 Z", ColorRole.HAIR)),
        PartGroup.HEAD,
    )

    // 20 뒤 팔(화면 왼쪽): 소매 + 손. 몸 뒤에 있어 바깥쪽만 보인다
    private val ARM_BACK = SvgPart(
        "arm_back", 20,
        listOf(
            p("M 30 70 C 22 72 17 78 16 86 C 15.5 90 17.5 91.5 20.5 91.5 C 23.5 91.5 25.5 90 26 86 C 27 80 30 76 34 73 Z", ColorRole.OUTFIT_BODY),
            p("M 16.3 95 C 16.3 92.7 18.2 90.8 20.5 90.8 C 22.8 90.8 24.7 92.7 24.7 95 C 24.7 97.3 22.8 99.2 20.5 99.2 C 18.2 99.2 16.3 97.3 16.3 95 Z", ColorRole.SKIN, true, SUB),
        ),
        PartGroup.ARM_BACK,
    )

    // 30 몸(옷): 어깨가 둥근 상반신. 옆선을 y 104에서 곧게 y 135까지 이어 닫는다(상반신 구도용 연장, 캔버스 0~100 밖).
    // 롤백: "M 22 135 L 22 104"를 "M 22 104"로, "L 78 135"를 지우고 "78 104 Z"로 되돌린다
    private val BODY = SvgPart(
        "body", 30,
        listOf(p("M 22 135 L 22 104 C 22 84 24 74 32 69 C 38 65.5 44 64.5 50 64.5 C 56 64.5 62 65.5 68 69 C 76 74 78 84 78 104 L 78 135 Z", ColorRole.OUTFIT_BODY)),
        PartGroup.BODY,
    )

    // 40 목
    private val NECK = SvgPart(
        "neck", 40,
        listOf(p("M 44 56 C 44 62 43.5 66 42 68 C 45 70 55 70 58 68 C 56.5 66 56 62 56 56 Z", ColorRole.SKIN, true, SUB)),
        PartGroup.BODY,
    )

    // 50 귀(양쪽): 얼굴 뒤에 놓여 바깥쪽만 보인다
    private val EARS = SvgPart(
        "ears", 50,
        listOf(
            p("M 25 42 C 25 39.5 26.6 37.5 28.5 37.5 C 30.4 37.5 32 39.5 32 42 C 32 44.5 30.4 46.5 28.5 46.5 C 26.6 46.5 25 44.5 25 42 Z", ColorRole.SKIN, true, SUB),
            p("M 68 42 C 68 39.5 69.6 37.5 71.5 37.5 C 73.4 37.5 75 39.5 75 42 C 75 44.5 73.4 46.5 71.5 46.5 C 69.6 46.5 68 44.5 68 42 Z", ColorRole.SKIN, true, SUB),
        ),
        PartGroup.HEAD,
    )

    // 60 얼굴: 지름 약 44의 둥근 얼굴(턱을 아주 조금 좁게)
    private val FACE = SvgPart(
        "face", 60,
        listOf(p("M 28 40 C 28 27 37.5 18 50 18 C 62.5 18 72 27 72 40 C 72 51 63 62 50 62 C 37 62 28 51 28 40 Z", ColorRole.SKIN)),
        PartGroup.HEAD,
    )

    // 70 볼(윤곽선 없음)
    private val CHEEKS = SvgPart(
        "cheeks", 70,
        listOf(
            p("M 32.3 49 C 32.3 47.5 34.2 46.2 36.5 46.2 C 38.8 46.2 40.7 47.5 40.7 49 C 40.7 50.5 38.8 51.8 36.5 51.8 C 34.2 51.8 32.3 50.5 32.3 49 Z", ColorRole.CHEEK, false),
            p("M 59.3 49 C 59.3 47.5 61.2 46.2 63.5 46.2 C 65.8 46.2 67.7 47.5 67.7 49 C 67.7 50.5 65.8 51.8 63.5 51.8 C 61.2 51.8 59.3 50.5 59.3 49 Z", ColorRole.CHEEK, false),
        ),
        PartGroup.HEAD,
    )

    // 80 눈·눈썹: 큰 둥근 눈 + 하이라이트 점 하나씩 + 얇은 눈썹
    private val EYES_BROWS = SvgPart(
        "eyes_brows", 80,
        listOf(
            p("M 36.9 43.5 C 36.9 40.9 38.5 38.9 40.5 38.9 C 42.5 38.9 44.1 40.9 44.1 43.5 C 44.1 46.1 42.5 48.1 40.5 48.1 C 38.5 48.1 36.9 46.1 36.9 43.5 Z", ColorRole.EYE, false),
            p("M 55.9 43.5 C 55.9 40.9 57.5 38.9 59.5 38.9 C 61.5 38.9 63.1 40.9 63.1 43.5 C 63.1 46.1 61.5 48.1 59.5 48.1 C 57.5 48.1 55.9 46.1 55.9 43.5 Z", ColorRole.EYE, false),
            p("M 40.4 41.6 C 40.4 40.9 40.9 40.4 41.6 40.4 C 42.3 40.4 42.8 40.9 42.8 41.6 C 42.8 42.3 42.3 42.8 41.6 42.8 C 40.9 42.8 40.4 42.3 40.4 41.6 Z", ColorRole.HIGHLIGHT, false),
            p("M 59.4 41.6 C 59.4 40.9 59.9 40.4 60.6 40.4 C 61.3 40.4 61.8 40.9 61.8 41.6 C 61.8 42.3 61.3 42.8 60.6 42.8 C 59.9 42.8 59.4 42.3 59.4 41.6 Z", ColorRole.HIGHLIGHT, false),
            p("M 36 35.5 Q 40.5 33 45 35", ColorRole.NONE, true, 1.4f),
            p("M 55 35 Q 59.5 33 64 35.5", ColorRole.NONE, true, 1.4f),
        ),
        PartGroup.HEAD,
    )

    // 90~93 입 4종(한 번에 하나만 그린다)
    private val MOUTH_CLOSED = SvgPart(
        "mouth_closed", 90,
        listOf(p("M 46 52.5 Q 50 54 54 52.5", ColorRole.NONE, true, 1.5f)),
        PartGroup.HEAD,
    )
    private val MOUTH_SMALL = SvgPart(
        "mouth_small", 91,
        listOf(p("M 47.6 52.5 C 47.6 51.2 48.7 50.2 50 50.2 C 51.3 50.2 52.4 51.2 52.4 52.5 C 52.4 53.8 51.3 54.8 50 54.8 C 48.7 54.8 47.6 53.8 47.6 52.5 Z", ColorRole.MOUTH, true, 1.4f)),
        PartGroup.HEAD,
    )
    private val MOUTH_WIDE = SvgPart(
        "mouth_wide", 92,
        listOf(p("M 44.5 51 C 44.5 50.5 45.5 50.5 50 50.5 C 54.5 50.5 55.5 50.5 55.5 51 C 55.5 56 53 58.5 50 58.5 C 47 58.5 44.5 56 44.5 51 Z", ColorRole.MOUTH, true, 1.4f)),
        PartGroup.HEAD,
    )
    private val MOUTH_SMILE = SvgPart(
        "mouth_smile", 93,
        listOf(p("M 45 51.5 Q 50 56.5 55 51.5", ColorRole.NONE, true, 1.6f)),
        PartGroup.HEAD,
    )

    // 100 앞머리(머리모양 0: 둥근 앞머리). 나머지 모양은 다음 단계
    private val FRONT_HAIR_0 = SvgPart(
        "front_hair_0", 100,
        listOf(p("M 27.5 41 C 24 25 34 13 50 13 C 66 13 76 25 72.5 41 C 70 35 66 31 61 30 C 56 32 46 32 38 30 C 33 32 29.5 36 27.5 41 Z", ColorRole.HAIR)),
        PartGroup.HEAD,
    )

    // 110 스카프: 띠 + 늘어진 끝 + 매듭(로고 없음). 키운 크기: 띠 x 36~64, 늘어진 끝 y 77~92, 매듭 지름 8 중심 (50,76)
    // 롤백(이전 값): 띠 "M 40 66 C 44 69.5 56 69.5 60 66 C 62 68 63 71 61 73 C 56 76 44 76 39 73 C 37 71 38 68 40 66 Z",
    // 끝 "M 47.5 77 C 46 81 45.5 84 47 86 C 48.5 87 51.5 87 53 86 C 54.5 84 54 81 52.5 77 Z",
    // 매듭 "M 47 75 C 47 73.3 48.3 72 50 72 C 51.7 72 53 73.3 53 75 C 53 76.7 51.7 78 50 78 C 48.3 78 47 76.7 47 75 Z"
    private val SCARF = SvgPart(
        "scarf", 110,
        listOf(
            p("M 39.5 66 C 44 70 56 70 60.5 66 C 63 68 64 71.5 62 73.5 C 56.5 77 43.5 77 38 73.5 C 36 71.5 37 68 39.5 66 Z", ColorRole.OUTFIT_SCARF, true, SUB),
            p("M 46.5 77 C 45 82 44.5 87.5 46 90.5 C 47.5 92 52.5 92 54 90.5 C 55.5 87.5 55 82 53.5 77 Z", ColorRole.OUTFIT_SCARF, true, SUB),
            p("M 54 76 C 54 78.21 52.21 80 50 80 C 47.79 80 46 78.21 46 76 C 46 73.79 47.79 72 50 72 C 52.21 72 54 73.79 54 76 Z", ColorRole.OUTFIT_SCARF, true, SUB),
        ),
        PartGroup.BODY,
    )

    // 120 앞 팔·손(화면 오른쪽): 몸 아래로 내린 기본 자세
    private val ARM_FRONT = SvgPart(
        "arm_front", 120,
        listOf(
            p("M 70 70 C 78 72 83 78 84 86 C 84.5 90 82.5 91.5 79.5 91.5 C 76.5 91.5 74.5 90 74 86 C 73 80 70 76 66 73 Z", ColorRole.OUTFIT_BODY),
            p("M 83.7 95 C 83.7 92.7 81.8 90.8 79.5 90.8 C 77.2 90.8 75.3 92.7 75.3 95 C 75.3 97.3 77.2 99.2 79.5 99.2 C 81.8 99.2 83.7 97.3 83.7 95 Z", ColorRole.SKIN, true, SUB),
        ),
        PartGroup.ARM_FRONT,
    )

    /** 모든 부품(입 4종 포함), 레이어 오름차순 */
    val ALL: List<SvgPart> = listOf(
        BACK_HAIR, ARM_BACK, BODY, NECK, EARS, FACE, CHEEKS, EYES_BROWS,
        MOUTH_CLOSED, MOUTH_SMALL, MOUTH_WIDE, MOUTH_SMILE,
        FRONT_HAIR_0, SCARF, ARM_FRONT,
    ).sortedBy { it.layer }

    private val MOUTH_NAMES: Set<String> = setOf("mouth_closed", "mouth_small", "mouth_wide", "mouth_smile")

    fun mouthPart(shape: MouthShape): SvgPart = when (shape) {
        MouthShape.CLOSED -> MOUTH_CLOSED
        MouthShape.SMALL -> MOUTH_SMALL
        MouthShape.WIDE -> MOUTH_WIDE
        MouthShape.SMILE -> MOUTH_SMILE
    }

    /** 입 모양 하나만 고른, 그릴 부품 목록(레이어 오름차순) */
    fun forMouth(shape: MouthShape): List<SvgPart> =
        (ALL.filter { it.name !in MOUTH_NAMES } + mouthPart(shape)).sortedBy { it.layer }
}
