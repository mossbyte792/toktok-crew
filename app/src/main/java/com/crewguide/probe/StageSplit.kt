package com.crewguide.probe

/**
 * v3 반복 5(P1, S02): 무대 2영역 배치 규칙(순수 계산, Android 클래스 없음, 단위 dp).
 *
 * - 왼쪽 약 48% = '고르기' 영역: 좌석 지도 → 벨트 참고 출처 → 4상태 범례 → 아이 카드 줄 → [지난번 아이 그대로](세로 한 열, 영역 가운데).
 * - 오른쪽 약 52% = '안내·행동' 영역: 공식 캐릭터, 말풍선(머리 왼쪽, 오른쪽 영역 안), 하단 행동 띠(선택 요약 + [보호자 확인 시작]).
 * - 지도·범례·카드와 말풍선은 서로 다른 영역에 있어 겹쳐서 해결하지 않는다. 행동 띠는 오른쪽 영역 폭만 쓴다.
 * - 캐릭터는 모든 장면에서 같은 자리(오른쪽 영역 안 [charLeftDp] ~ 오른쪽 끝의 가운데)에 둔다: [시작]을 눌러 장면이 바뀌어도 움직이지 않는다.
 *
 * 적용 범위: Wide(≥2200dp)·Medium(≥1600dp, 에뮬레이터 앱 창 1682×1210). Compact는 기존 겹침 배치를 그대로 쓴다(미검증 크기).
 * 롤백: [enabled]가 항상 false를 돌려주면 반복 4 배치로 돌아간다(이 파일 외 코드는 split=false 경로를 그대로 가진다).
 */
object StageSplit {
    const val LEFT_PERCENT = 48
    const val OUTER_DP = 24            // 화면 바깥 여백
    const val ZONE_GAP_DP = 24         // 두 영역 사이 간격(경계 양쪽 12씩)
    const val SECTION_GAP_DP = 16      // 왼쪽 열 안 지도·범례·카드 사이 간격
    /** 캐릭터 칸은 오른쪽 영역 폭의 이 비율 지점에서 시작한다(왼쪽 30%는 말풍선 자리) */
    const val CHAR_OFFSET_PERCENT = 30
    const val MEDIUM_MAP_SCALE = 1.6f  // 타일 84 → 134dp
    const val WIDE_MAP_SCALE = 1.9f    // 타일 84 → 160dp
    /** 반복 5의 하한(참고용). 반복 6부터 이 값을 지키려고 높이 예산을 넘기지 않는다 — 실제 하한은 [MIN_FIT_SCALE] */
    const val MIN_MAP_SCALE = 1.2f
    /**
     * v3 반복 6(P0): 공간이 모자랄 때 줄일 수 있는 하한. 1.0 = 타일 84dp(≥ 터치 76), 타일 간격 23dp(= DesignRules.MIN_TARGET_GAP).
     * 이 배율로도 왼쪽 열이 높이에 안 들어가면 배율을 더 줄이지 않고 왼쪽 열을 세로 스크롤로 바꾼다(겹치거나 창 밖으로 나가지 않게).
     */
    const val MIN_FIT_SCALE = 1.0f

    // 아이 카드(2영역 전용): 폭을 넓히고 별명을 두 줄까지 보여 준다(반복 4: 132dp 한 줄 → 8자 별명 말줄임)
    const val CARD_W_DP = 168
    const val CARD_MIN_H_DP = ChildCardLayout.CARD_H_DP
    const val CARD_AVATAR_DP = 88
    const val CARD_NAME_LINES = 2
    const val CARD_BOTTOM_PAD_DP = 10
    const val CARD_GAP_DP = 16

    // 왼쪽 열 높이 예산(글꼴 1.0 기준, dp): 범례(출처 줄 + 칩 2줄) 156, 카드 한 줄 최소 164 + 별명 둘째 줄 32
    const val LEGEND_BUDGET_DP = 156
    const val CARD_ROW_BUDGET_DP = CARD_MIN_H_DP + 32

    fun enabled(screenWDp: Float): Boolean = StageSeatMap.tierFor(screenWDp) != StageSeatMap.Tier.COMPACT

    /** v3 반복 6: 만들 때의 2영역 여부와 지금 창 폭의 등급이 다르면 무대를 다시 만들어야 한다(Medium↔Wide는 배율만 다르므로 실측 맞춤으로 충분) */
    fun needsRebuild(builtSplit: Boolean, screenWDp: Float): Boolean = builtSplit != enabled(screenWDp)

    fun splitXDp(screenWDp: Float): Float = screenWDp * LEFT_PERCENT / 100f
    fun leftZoneLeftDp(): Float = OUTER_DP.toFloat()
    fun leftZoneRightDp(screenWDp: Float): Float = splitXDp(screenWDp) - ZONE_GAP_DP / 2f
    fun leftZoneWidthDp(screenWDp: Float): Float = leftZoneRightDp(screenWDp) - leftZoneLeftDp()
    fun rightZoneLeftDp(screenWDp: Float): Float = splitXDp(screenWDp) + ZONE_GAP_DP / 2f
    fun rightZoneRightDp(screenWDp: Float): Float = screenWDp - OUTER_DP
    fun rightZoneWidthDp(screenWDp: Float): Float = rightZoneRightDp(screenWDp) - rightZoneLeftDp(screenWDp)

    /** 캐릭터 칸 왼쪽 끝(dp). 캐릭터 칸 = 여기 ~ 화면 오른쪽 끝, 그림은 칸 가운데 */
    fun charLeftDp(screenWDp: Float): Float =
        rightZoneLeftDp(screenWDp) + rightZoneWidthDp(screenWDp) * CHAR_OFFSET_PERCENT / 100f

    /**
     * 지도 배율: 등급 기본값(Medium 1.6, Wide 1.9)을 쓰되, 왼쪽 열 전체(지도 + 범례 + 카드 줄 + 간격 2)가
     * 캐릭터 영역 높이 안에 들어가도록 줄이고(하한 1.2), 지도 폭이 왼쪽 영역 폭을 넘지 않게 한다.
     */
    fun mapScale(screenWDp: Float, areaHeightDp: Float, fontScale: Float = 1f): Float {
        if (!enabled(screenWDp)) return StageSeatMap.scaleFor(screenWDp)
        // 반복 6: 글꼴 배율만큼 글자 칸(범례·카드·칩) 예산을 키워 첫 배치를 잡는다(보수적). 실제 높이는 화면에서 다시 재서 [fit]으로 맞춘다
        val f = fontScale.coerceAtLeast(1f)
        val fixed = (LEGEND_BUDGET_DP + CARD_ROW_BUDGET_DP + LAST_CHIP_BUDGET_DP) * f + 3 * SECTION_GAP_DP
        return fit(screenWDp, areaHeightDp, fixed, leftZoneWidthDp(screenWDp)).scale
    }

    /** 등급 기본 배율(Medium 1.6, Wide 1.9). Compact는 2영역이 아니므로 기존 등급 배율 */
    fun baseScale(screenWDp: Float): Float = when (StageSeatMap.tierFor(screenWDp)) {
        StageSeatMap.Tier.WIDE -> WIDE_MAP_SCALE
        StageSeatMap.Tier.MEDIUM -> MEDIUM_MAP_SCALE
        StageSeatMap.Tier.COMPACT -> StageSeatMap.scaleFor(screenWDp)
    }

    /**
     * v3 반복 6(P0) 실측 맞춤 결과.
     * @property scale 지도 배율([MIN_FIT_SCALE] ~ 등급 기본값)
     * @property scroll 하한 배율로도 왼쪽 열이 높이에 안 들어가 세로 스크롤로 보여 줘야 함
     * @property widthOverflow 하한 배율의 지도도 왼쪽 영역 폭보다 넓음(2영역을 쓰면 안 되는 크기)
     */
    data class Fit(val scale: Float, val scroll: Boolean, val widthOverflow: Boolean)

    /**
     * 지도 배율 맞춤(순수). restHeightDp = 왼쪽 열에서 지도를 뺀 나머지(출처·범례·카드·칩·간격) 높이 — 화면에서는 실측값,
     * 첫 배치에서는 예산값. 지도 외 높이는 배율과 무관해야 한다(범례 폭은 [legendWidthDp]로 배율과 무관하게 고정).
     */
    fun fit(screenWDp: Float, viewportHDp: Float, restHeightDp: Float, zoneWDp: Float): Fit {
        val base = baseScale(screenWDp)
        val byHeight = (viewportHDp - restHeightDp) / StageSeatMap.heightDp()
        val byWidth = zoneWDp / StageSeatMap.widthDp()
        val k = minOf(base, byHeight, byWidth).coerceIn(minOf(MIN_FIT_SCALE, base), base)
        val colH = StageSeatMap.heightDp() * k + restHeightDp
        return Fit(k, colH > viewportHDp + 0.5f, StageSeatMap.widthDp() * k > zoneWDp + 0.5f)
    }

    /** 범례 폭(dp): 등급 기본 배율의 지도 폭, 단 왼쪽 영역보다 넓지 않게. 지도 배율이 줄어도 범례 줄바꿈(=높이)이 바뀌지 않게 고정 */
    fun legendWidthDp(screenWDp: Float): Float =
        minOf(StageSeatMap.widthDp() * baseScale(screenWDp), leftZoneWidthDp(screenWDp))

    // ---- v3 반복 6(P0): 아이 카드 폭 맞춤 ----
    /**
     * 카드 줄 양옆 최소 여백(영역 경계·간격 띠에 카드가 붙지 않게). 처음 16으로 두었더니 1600dp에서 카드가 163dp로 줄어
     * 글꼴 2.0의 8자 별명이 "Kid2Lon/g"처럼 단어 중간에서 끊겼다(에뮬레이터, 반복 6) → 6으로: 1600dp 4명 = 168 × 4 + 16 × 3 = 720 ≤ 732 − 12.
     * 영역 경계 밖에는 따로 간격 띠 12dp·바깥 여백 24dp가 있어 카드가 화면 끝·오른쪽 영역에 붙지 않는다.
     */
    const val CARD_SIDE_PAD_DP = 6
    /** 카드 최소 폭 = 반복 4 카드(132dp). 이보다 좁아져야 하면 두 줄(2×2)로 감는다 */
    const val CARD_MIN_W_DP = 132

    /** 한 줄에 둘 카드 수: 최소 폭으로도 한 줄에 안 들어가면 두 줄(한 줄에 ceil(n/2)) */
    fun cardsPerRow(zoneWDp: Float, count: Int): Int {
        if (count <= 1) return count.coerceAtLeast(0)
        val need = count * CARD_MIN_W_DP + (count - 1) * CARD_GAP_DP + 2 * CARD_SIDE_PAD_DP
        return if (need <= zoneWDp) count else (count + 1) / 2
    }

    /** 카드 폭(dp): 기본 168, 줄 폭이 (영역 − 양옆 여백)을 넘으면 넘지 않을 만큼만 줄인다(하한 [CARD_MIN_W_DP]) */
    fun cardWidthDp(zoneWDp: Float, count: Int): Int {
        val perRow = cardsPerRow(zoneWDp, count)
        if (perRow <= 0) return CARD_W_DP
        val avail = zoneWDp - 2 * CARD_SIDE_PAD_DP - (perRow - 1) * CARD_GAP_DP
        return minOf(CARD_W_DP, (avail / perRow).toInt()).coerceAtLeast(CARD_MIN_W_DP)
    }

    /** 캐릭터 영역 높이(dp) = 창 높이 − 상단 인셋 − 상단 바 − (라벨 띠 + 하단 인셋) */
    fun areaHeightDp(screenHDp: Float, topInsetDp: Float, bottomInsetDp: Float): Float =
        screenHDp - topInsetDp - StageLayout.TOP_BAR_DP - StageLayout.SUB_LABEL_DP - bottomInsetDp

    /** 왼쪽 열 높이(글꼴 1.0 예산, dp). 아이가 없으면 카드 줄과 그 간격이 빠진다 */
    fun leftColumnHeightDp(mapScale: Float, cardCount: Int): Float =
        StageSeatMap.heightDp() * mapScale + SECTION_GAP_DP + LEGEND_BUDGET_DP +
            (if (cardCount > 0) SECTION_GAP_DP + CARD_ROW_BUDGET_DP + SECTION_GAP_DP + LAST_CHIP_BUDGET_DP else 0)

    /** 아이 카드 한 줄 폭(dp) = n × 168 + (n−1) × 16 */
    fun cardRowWidthDp(count: Int): Float =
        if (count <= 0) 0f else count * CARD_W_DP + (count - 1) * CARD_GAP_DP.toFloat()

    /** 하단 행동 띠 안 비율: 선택 요약 1 : [보호자 확인 시작] 1.3 (2영역에서는 오른쪽 빈 칸을 두지 않는다) */
    const val SUMMARY_WEIGHT = 1f
    const val START_WEIGHT = 1.3f
    /** 글꼴 배율이 이 값 이상이면 요약을 [시작] 위 별도 줄(전체 폭, 최대 4줄)로 쌓는다 */
    const val STACK_FONT_SCALE = 1.5f
    const val STACK_SUMMARY_LINES = 4
    /** '지난번 아이 그대로' 칩(76dp)이 왼쪽 열 카드 줄 아래로 옮겨 온 만큼의 높이 예산 */
    const val LAST_CHIP_BUDGET_DP = 76
}

/** v3 반복 6(P0): 등급 경계 재생성을 같은 폭에서 두 번 하지 않기 위한 프로세스 메모리(저장 안 함) */
object StageRebuildGuard {
    @Volatile var lastWidthDp: Int = -1
}
