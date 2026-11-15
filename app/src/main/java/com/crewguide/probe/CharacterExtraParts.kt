package com.crewguide.probe

/**
 * 꾸미기 연결용으로 직접 그린 추가 부품(가안, 개발용). 경로는 Kotlin 상수 문자열, 0~100 좌표계, 선 색은 고정 남색(뷰가 칠함), 외곽선 1.8.
 * CharacterParts.ALL(기본 12개 + 입 4종)은 바꾸지 않고 여기에 따로 둔다. 로고·날개 배지·항공사 상징·글자는 없다.
 * 레이어는 기존 부품과 겹치지 않는 번호를 쓴다(작을수록 뒤).
 */
object CharacterExtraParts {
    // 머리카락·모자 윤곽은 굵게(OutlineWidths.MAIN). 안경·핀·눈썹 선은 각 경로의 기존 값을 유지한다
    private const val SW = OutlineWidths.MAIN

    private fun p(d: String, fill: ColorRole, stroke: Boolean = true, w: Float = SW) = PartPath(d, fill, stroke, w)

    // 11 뒷머리(옆가르마): 기본 뒷머리와 비슷하되 화면 오른쪽 머리끝이 조금 길다
    val BACK_HAIR_1 = SvgPart(
        "back_hair_1", 11,
        listOf(p("M 26 44 C 22 26 33 13 50 13 C 67 13 78 26 74 44 C 74 56 72 64 66 64 C 58 64 42 60 36 60 C 30 60 27 54 26 44 Z", ColorRole.HAIR)),
        PartGroup.HEAD,
    )

    // 12 뒷머리(묶은머리): 기본 머리 덩어리 + 오른쪽 위 작은 묶음 (72,22) 반경 7
    val BACK_HAIR_2 = SvgPart(
        "back_hair_2", 12,
        listOf(
            p("M 26 44 C 22 26 33 13 50 13 C 67 13 78 26 74 44 C 73 54 70 60 64 60 C 58 60 42 60 36 60 C 30 60 27 54 26 44 Z", ColorRole.HAIR),
            p("M 79 22 C 79 25.87 75.87 29 72 29 C 68.13 29 65 25.87 65 22 C 65 18.13 68.13 15 72 15 C 75.87 15 79 18.13 79 22 Z", ColorRole.HAIR),
        ),
        PartGroup.HEAD,
    )

    // 81 차분한 눈·눈썹: 기본 눈(eyes_brows)을 중심 y 43.5 기준 세로 0.8배로 줄인 경로, 눈썹은 같음
    val EYES_BROWS_CALM = SvgPart(
        "eyes_brows_calm", 81,
        listOf(
            p("M 36.9 43.5 C 36.9 41.42 38.5 39.82 40.5 39.82 C 42.5 39.82 44.1 41.42 44.1 43.5 C 44.1 45.58 42.5 47.18 40.5 47.18 C 38.5 47.18 36.9 45.58 36.9 43.5 Z", ColorRole.EYE, false),
            p("M 55.9 43.5 C 55.9 41.42 57.5 39.82 59.5 39.82 C 61.5 39.82 63.1 41.42 63.1 43.5 C 63.1 45.58 61.5 47.18 59.5 47.18 C 57.5 47.18 55.9 45.58 55.9 43.5 Z", ColorRole.EYE, false),
            p("M 40.4 41.98 C 40.4 41.42 40.9 41.02 41.6 41.02 C 42.3 41.02 42.8 41.42 42.8 41.98 C 42.8 42.54 42.3 42.94 41.6 42.94 C 40.9 42.94 40.4 42.54 40.4 41.98 Z", ColorRole.HIGHLIGHT, false),
            p("M 59.4 41.98 C 59.4 41.42 59.9 41.02 60.6 41.02 C 61.3 41.02 61.8 41.42 61.8 41.98 C 61.8 42.54 61.3 42.94 60.6 42.94 C 59.9 42.94 59.4 42.54 59.4 41.98 Z", ColorRole.HIGHLIGHT, false),
            p("M 36 35.5 Q 40.5 33 45 35", ColorRole.NONE, true, 1.4f),
            p("M 55 35 Q 59.5 33 64 35.5", ColorRole.NONE, true, 1.4f),
        ),
        PartGroup.HEAD,
    )

    // 101 앞머리(옆가르마): 가르마 (36,24), 앞머리를 화면 오른쪽으로 넘김. 머리선 y 24(왼쪽)~30(오른쪽)
    val FRONT_HAIR_1 = SvgPart(
        "front_hair_1", 101,
        listOf(
            p("M 27.5 41 C 24 25 34 13 50 13 C 66 13 76 25 72.5 41 C 70 36 67 32 62 30 C 54 33 44 31 36 24 C 33 28 30 34 27.5 41 Z", ColorRole.HAIR),
            p("M 38 14.5 Q 36 19 36 24", ColorRole.NONE, true, 1.3f),
        ),
        PartGroup.HEAD,
    )

    // 102 앞머리(묶은머리): 이마를 드러내는 짧은 모양. 머리선 y 약 25
    val FRONT_HAIR_2 = SvgPart(
        "front_hair_2", 102,
        listOf(p("M 27.5 41 C 24 25 34 13 50 13 C 66 13 76 25 72.5 41 C 70 33 66 26 58 24.5 C 52 26 48 26 42 24.5 C 34 26 30 33 27.5 41 Z", ColorRole.HAIR)),
        PartGroup.HEAD,
    )

    // 105 안경: 눈 (40,43),(60,43) 둘레 반경 6.6의 두 원 + 코다리. 선만(채움 없음)
    val GLASSES = SvgPart(
        "glasses", 105,
        listOf(
            p("M 46.6 43 C 46.6 46.645 43.645 49.6 40 49.6 C 36.355 49.6 33.4 46.645 33.4 43 C 33.4 39.355 36.355 36.4 40 36.4 C 43.645 36.4 46.6 39.355 46.6 43 Z", ColorRole.NONE, true, 1.5f),
            p("M 66.6 43 C 66.6 46.645 63.645 49.6 60 49.6 C 56.355 49.6 53.4 46.645 53.4 43 C 53.4 39.355 56.355 36.4 60 36.4 C 63.645 36.4 66.6 39.355 66.6 43 Z", ColorRole.NONE, true, 1.5f),
            p("M 46.6 42.5 Q 50 40.5 53.4 42.5", ColorRole.NONE, true, 1.5f),
        ),
        PartGroup.HEAD,
    )

    // 106 리본 핀: 머리 왼쪽 위 (38,22). 양쪽 고리 + 가운데 매듭, 채움은 볼 색(CHEEK)
    val RIBBON_PIN = SvgPart(
        "ribbon_pin", 106,
        listOf(
            p("M 38 22 C 34 18 30 19.5 31 22.5 C 32 25.5 35 25.5 38 22 Z", ColorRole.CHEEK, true, 1.4f),
            p("M 38 22 C 42 18 46 19.5 45 22.5 C 44 25.5 41 25.5 38 22 Z", ColorRole.CHEEK, true, 1.4f),
            p("M 39.8 22 C 39.8 23 38.9 23.8 38 23.8 C 37.1 23.8 36.2 23 36.2 22 C 36.2 21 37.1 20.2 38 20.2 C 38.9 20.2 39.8 21 39.8 22 Z", ColorRole.CHEEK, true, 1.4f),
        ),
        PartGroup.HEAD,
    )

    // 108 모자: 챙 없는 작은 원통형(필박스). 아래 가장자리 y 24 x 35~65, 윗면 y 12.5 x 37.5~62.5(약간 둥근 사다리꼴).
    // 몸 색(OUTFIT_BODY) 채움 + 아래쪽 밝은 줄 한 개(OUTFIT_SCARF, 윤곽 없음, x 37~63 y 20.8~22.6). 로고·배지 없음
    // 롤백: 이전 모자는 윗부분 "M 29 25 C 28 14 37 10 50 10 C 63 10 72 14 71 25 Z"(몸 색)와 챙 "M 24 25.5 C 24 22 36 21 50 21 C 64 21 76 22 76 25.5 C 76 29 64 29.5 50 29.5 C 36 29.5 24 29 24 25.5 Z"(스카프 색)
    val HAT = SvgPart(
        "hat", 108,
        listOf(
            p("M 35 24 L 37.5 15 C 38 12.8 40 12.5 42 12.5 L 58 12.5 C 60 12.5 62 12.8 62.5 15 L 65 24 Z", ColorRole.OUTFIT_BODY),
            p("M 37 20.8 L 63 20.8 L 62.8 22.6 L 37.2 22.6 Z", ColorRole.OUTFIT_SCARF, false),
        ),
        PartGroup.HEAD,
    )

    // 115 빈 이름표: 중심 (34,82), 14x9 (x 27~41, y 77.5~86.5), 둥근 모서리, 글자 없음, 노랑 채움 + 진갈색 윤곽
    // 롤백: 이전 경로 "M 29.2 79 L 38.8 79 Q 40 79 40 80.2 L 40 83.8 Q 40 85 38.8 85 L 29.2 85 Q 28 85 28 83.8 L 28 80.2 Q 28 79 29.2 79 Z"(HIGHLIGHT, 1.4)
    val NAME_TAG = SvgPart(
        "name_tag", 115,
        listOf(p("M 28.8 77.5 L 39.2 77.5 Q 41 77.5 41 79.3 L 41 84.7 Q 41 86.5 39.2 86.5 L 28.8 86.5 Q 27 86.5 27 84.7 L 27 79.3 Q 27 77.5 28.8 77.5 Z", ColorRole.NAME_TAG_YELLOW, true, OutlineWidths.TAG)),
        PartGroup.BODY,
    )

    // 35 재킷 디테일(항상 그림): 몸(30) 바로 위, 목(40)·스카프(110)·팔 아래. 그래서 목과 스카프를 가리지 않는다.
    // 흰 셔츠 깃(좌우 작은 삼각 두 개, x 40.5~59.5 y 66.5~76) + 가슴 앞 트임 선(x 50, y 77~104) + 금색 단추 둘(지름 2.6, 중심 (50,88)·(50,96)).
    // 단추 윤곽은 지름보다 굵으면 금색이 사라져 얇게(1.0) 둔다. 이름표(x 27~41, y 77.5~86.5)와 겹치지 않는다. 롤백: ALL과 StyleToParts에서 이 부품을 뺀다
    val JACKET_DETAIL = SvgPart(
        "jacket_detail", 35,
        listOf(
            p("M 40.5 66.5 L 49.5 70 L 43 76 Z", ColorRole.HIGHLIGHT, true, OutlineWidths.SUB),
            p("M 59.5 66.5 L 50.5 70 L 57 76 Z", ColorRole.HIGHLIGHT, true, OutlineWidths.SUB),
            p("M 50 77 L 50 104", ColorRole.NONE, true, OutlineWidths.SUB),
            p("M 51.3 88 C 51.3 88.72 50.72 89.3 50 89.3 C 49.28 89.3 48.7 88.72 48.7 88 C 48.7 87.28 49.28 86.7 50 86.7 C 50.72 86.7 51.3 87.28 51.3 88 Z", ColorRole.NAME_TAG_YELLOW, true, 1.0f),
            p("M 51.3 96 C 51.3 96.72 50.72 97.3 50 97.3 C 49.28 97.3 48.7 96.72 48.7 96 C 48.7 95.28 49.28 94.7 50 94.7 C 50.72 94.7 51.3 95.28 51.3 96 Z", ColorRole.NAME_TAG_YELLOW, true, 1.0f),
        ),
        PartGroup.BODY,
    )

    /** 모든 추가 부품, 레이어 오름차순 */
    val ALL: List<SvgPart> = listOf(
        BACK_HAIR_1, BACK_HAIR_2, EYES_BROWS_CALM, FRONT_HAIR_1, FRONT_HAIR_2, GLASSES, RIBBON_PIN, HAT, NAME_TAG, JACKET_DETAIL,
    ).sortedBy { it.layer }
}
