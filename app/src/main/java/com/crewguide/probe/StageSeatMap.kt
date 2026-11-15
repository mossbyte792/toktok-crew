package com.crewguide.probe

// 무대 오른쪽 위 '자동차 위에서 본 좌석 지도'(시안 1) 순수 로직(Android 클래스 없음): 이름→위치 매핑, 크기·위치·색 계산.
// 지도는 '고르기' 용도일 뿐 벨트 상태 표시가 아니다. 상태를 저장하지 않으며, 터치는 StageActions.onToggleSeat(name)으로만 전달된다.
// 롤백: seatSlotsFor가 항상 null을 돌려주게 하면 StageScreen이 이전 글자 칩 줄을 그대로 쓴다.

/** 좌석 한 칸의 지도 위치. row 0 = 앞줄, 1 = 뒷줄. descKo는 선택 상태를 뺀 읽기용 이름 */
data class SeatSlot(val name: String, val row: Int, val col: Int, val labelKo: String, val descKo: String)

/** 지도 색(0xRRGGBB, 알파 없음) */
data class SeatMapColors(
    val bodyFill: Int, val bodyStroke: Int,
    val tileFill: Int, val tileStroke: Int, val tileText: Int,
    val selFill: Int, val selStroke: Int, val selText: Int,
    val badgeFill: Int, val badgeCheck: Int,
)

object StageSeatMap {
    /** StageActions.seatNames()가 주는 짧은 이름 5개와 위치(순서는 앞줄 왼→오, 뒷줄 왼→가운데→오) */
    val SLOTS: List<SeatSlot> = listOf(
        SeatSlot("R1L", 0, 0, "왼쪽", "앞줄 왼쪽"),
        SeatSlot("R1R", 0, 1, "오른쪽", "앞줄 오른쪽"),
        SeatSlot("R2L", 1, 0, "왼쪽", "뒷줄 왼쪽"),
        SeatSlot("R2C", 1, 1, "가운데", "뒷줄 가운데"),
        SeatSlot("R2R", 1, 2, "오른쪽", "뒷줄 오른쪽"),
    )

    const val MAP_DESC = "자동차 좌석 지도, 앞줄 2석 뒷줄 3석"

    // ---- 크기(dp). 타일 간격 23dp = DesignRules.MIN_TARGET_GAP(예외 없음) ----
    // 롤백: TILE_DP 80, SEAT_GAP_DP 12, BODY_PAD_DP 16, WHEEL_OUT_DP 0으로 되돌리고 장식 그리기(StageSeatMapView.drawCar)를 생략한다.
    const val TILE_DP = 84
    const val SEAT_GAP_DP = 23          // 타일 사이 간격
    const val BODY_PAD_DP = 12          // 차체 안쪽 여백(좌우·위·아래)
    const val ROW_GAP_DP = 24           // 앞줄-뒷줄 간격(≥ 23)
    const val FRONT_BAND_DP = 24        // 맨 위 앞유리 띠
    const val BODY_RADIUS_DP = 44
    const val BODY_STROKE_DP = 3
    const val TILE_RADIUS_DP = 20
    const val TILE_STROKE_DP = 2
    const val SEAT_ICON_DP = 32         // 좌석 모양(등받이) 폭 32dp, 높이 28dp
    // 타일 안(84dp 높이): 위 여백 8 + 좌석 그림 28 + 간격 4 + 글자 줄 약 30 + 아래 여백 8 = 78 ≤ 84
    const val TILE_ICON_TOP_DP = 8
    const val TILE_TEXT_CENTER_DP = 55  // 글자 줄 y 40~70의 가운데
    // 장식(차체 윤곽). 바퀴는 차체 좌우 바깥으로 5dp씩 튀어나와 지도 폭에 포함된다.
    const val WHEEL_W_DP = 10
    const val WHEEL_H_DP = 36
    const val WHEEL_R_DP = 5
    const val WHEEL_OUT_DP = 5          // 바퀴 폭의 절반 = 차체 밖으로 나온 부분
    // 차체는 좌우 대칭 닫힌 Path 하나(StageSeatMapShape.bodySegments): 윗변 양옆 8dp 좁힘 + 어깨 곡선 44, 트렁크 가로 44.
    const val GLASS_STROKE_DP = 2
    const val GLASS_TOP_HALF_DP = 60    // 앞유리 윗변 반폭(중심 기준). 이전 50
    const val GLASS_BOTTOM_HALF_DP = 78 // 앞유리 아랫변 반폭. 이전 70
    const val GLASS_TOP_Y_DP = 10       // 차체 안쪽 윗선(y 3)에서 7dp 안쪽 → 윤곽 2dp 포함 6dp 이상 여백. 이전 6
    const val GLASS_BOTTOM_Y_DP = 26    // 앞줄 타일 윗변(36)과 10dp 간격. 이전 22
    const val MIRROR_W_DP = 8
    const val MIRROR_H_DP = 12
    const val MIRROR_TOP_Y_DP = 46      // 어깨 곡선이 끝나는 y(45.5) 이후의 직선 구간(y ≥ 44). 바퀴(60~96)와 y 46~58로 안 겹침. 이전 40
    const val MIRROR_CENTER_IN_DP = 6   // 사이드미러 중심 x = 차체 윤곽선 중심(6.5)에 맞춰 반올림 6. 바깥으로 약 2.5dp 나옴. 이전 8
    const val REAR_LINE_Y_DP = 230      // 뒷유리선 양끝 y(가운데는 2dp 아래로 휜다). 이전 232
    const val REAR_LINE_HALF_DP = 60    // 이전 70
    const val REAR_LINE_DIP_DP = 4      // quadTo 제어점이 양끝보다 내려가는 양(곡선 꼭짓점은 절반 2dp)
    const val GLASS_FILL = 0xD6EBFF
    const val DAY_WHEEL = 0x3B2A22
    const val NIGHT_WHEEL = 0x9E9E9E
    const val LABEL_SP = 24f
    /** 2026-11-10 M11: 큰 글꼴(1.5·2.0)에서 좌석 이름이 타일 밖으로 넘지 않게 좌우 여백 */
    const val LABEL_SIDE_PAD_DP = 6

    /**
     * 좌석 이름 글자 크기 맞춤(순수 계산). 측정 폭이 쓸 수 있는 폭보다 크면 그 비율만큼만 줄인다.
     * 사용자 글꼴 배율이 커도 타일 안에 들어가게 하는 것이 목적이며, 넘지 않으면 원래 크기 그대로.
     */
    fun fitLabelSize(baseSizePx: Float, measuredWidthPx: Float, availableWidthPx: Float): Float {
        if (baseSizePx <= 0f || measuredWidthPx <= 0f || availableWidthPx <= 0f) return baseSizePx
        return if (measuredWidthPx <= availableWidthPx) baseSizePx else baseSizePx * (availableWidthPx / measuredWidthPx)
    }
    const val BADGE_DP = 28             // 선택 체크 배지 지름
    /** v3 단계 3: 좌상단 벨트 참고 배지 지름(이전 18dp → 24dp, 1.33배). 4dp 안쪽에서 시작해 등받이(x=26..58)와 2dp만 겹친다(배지가 위에 그려짐) */
    const val REF_BADGE_DP = 24
    const val REF_BADGE_BASE_DP = 18     // 기호 좌표를 설계한 기준 지름(기호는 REF_BADGE_DP/18 배로 함께 키운다)
    fun refGlyphScale(): Float = REF_BADGE_DP.toFloat() / REF_BADGE_BASE_DP
    const val BADGE_INSET_DP = 4        // 타일 오른쪽 위 모서리에서 배지까지
    const val WINDSHIELD_DP = 4         // 앞유리 표시선 두께
    const val MARGIN_RIGHT_DP = 16      // 무대 오른쪽 여백(시스템 인셋 별도)
    const val TOP_GAP_DP = 8            // 상단 바 아래 여백
    const val MIN_TOUCH_DP = 76         // DesignRules.MIN_TOUCH와 같은 값

    // ---- 2026-11-11 UI/UX v2(U3): 좌석 지도 확대 + 범례 ----
    // 지도 내부 도형 수치(위 dp 상수)는 그대로 두고 화면에 그릴 때만 SCALE배로 키운다(타일 84 → 약 113dp, 좌석 글자 24 → 약 32sp).
    // 지도 아래에는 출처 한 줄 + 상태 범례(체결 신호 / 미체결 신호 / 확인 불가 / 신호 없음, 2×2)를 둔다.
    // 확대는 넓은 화면(폭 2200dp 이상, 예: 에뮬레이터 2560×1440 mdpi)에서만. 좁은 화면은 1배 그대로(손 끝·말풍선 자리 보존)
    const val WIDE_MIN_DP = 2200f
    const val WIDE_SCALE = 1.35f
    const val LEGEND_GAP_DP = 4
    // 출처 줄(24sp 약 33 + 위아래 4) + 8 + 칩 2줄(44 + 6) = 149 → 여유 포함 156. 1682 창에서 가슴 말풍선 위(583)보다 위에서 끝난다(572)
    const val LEGEND_DP = 156

    // 에뮬레이터 앱 창은 1682×1210(dp)이다(전체 화면 2560이 아님). 이 크기에서는 1.2배
    const val MID_MIN_DP = 1600f
    const val MID_SCALE = 1.2f
    /** v3 단계 3: 화면 폭 등급. Wide ≥ 2200dp(전체 화면), Medium ≥ 1600dp(에뮬레이터 앱 창 1682), Compact 그 아래(분할 화면 등, 미검증) */
    enum class Tier { WIDE, MEDIUM, COMPACT }
    fun tierFor(screenWDp: Float): Tier = when {
        screenWDp >= WIDE_MIN_DP -> Tier.WIDE
        screenWDp >= MID_MIN_DP -> Tier.MEDIUM
        else -> Tier.COMPACT
    }
    /** 범례 칩 열 수: Compact(배율 1, 지도 폭 약 [widthDp]dp)는 '미체결 신호' 칩 두 개(24sp 굵게 ≈ 172dp씩)가 한 줄에 안 들어가 1열(4줄) */
    fun legendColumns(tier: Tier): Int = if (tier == Tier.COMPACT) 1 else 2
    /** 범례 높이(dp): 출처 줄 + 8 + 칩 줄 수 × 50(44 + 6), 여유 15. 2열 = 156(기존 값), 1열 = 256 */
    fun legendHeightDp(tier: Tier): Int { val rows = 4 / legendColumns(tier); return 33 + 8 + rows * 50 + 15 }

    fun scaleFor(screenWDp: Float): Float = when {
        screenWDp >= WIDE_MIN_DP -> WIDE_SCALE
        screenWDp >= MID_MIN_DP -> MID_SCALE
        else -> 1f
    }

    /** 화면에 그려지는 지도 폭·높이(dp) */
    fun displayWidthDp(screenWDp: Float): Float = widthDp() * scaleFor(screenWDp)
    fun displayHeightDp(screenWDp: Float): Float = heightDp() * scaleFor(screenWDp)

    const val BACK_COLS = 3
    const val FRONT_COLS = 2

    /** 뒷줄 3칸 폭 = 3×84 + 2×23 = 298 */
    fun backRowWidthDp(): Int = BACK_COLS * TILE_DP + (BACK_COLS - 1) * SEAT_GAP_DP

    /** 앞줄 2칸 폭 = 2×84 + 23 = 191 */
    fun frontRowWidthDp(): Int = FRONT_COLS * TILE_DP + (FRONT_COLS - 1) * SEAT_GAP_DP

    /** 차체 폭 = 298 + 2×12 = 322 */
    fun bodyWidthDp(): Int = backRowWidthDp() + 2 * BODY_PAD_DP

    /** 지도 전체 폭(바퀴 포함) = 322 + 2×5 = 332 */
    fun widthDp(): Int = bodyWidthDp() + 2 * WHEEL_OUT_DP

    /** 지도 전체 높이 = 24 + 12 + 84 + 24 + 84 + 12 = 240 */
    fun heightDp(): Int = FRONT_BAND_DP + BODY_PAD_DP + TILE_DP + ROW_GAP_DP + TILE_DP + BODY_PAD_DP

    /**
     * 앞줄 왼쪽 여백(정수 규칙: 내림) = (298 − 191) / 2 = 107 / 2 = 53 (정확히는 53.5). 오른쪽 여백은 54.
     */
    fun frontInsetDp(): Int = (backRowWidthDp() - frontRowWidthDp()) / 2

    /** 타일 왼쪽 x(dp, 지도 왼쪽 기준). 뒷줄 시작 = 5 + 12 = 17, 앞줄 시작 = 17 + 53 = 70, 열 간격 107 */
    fun tileXDp(slot: SeatSlot): Int {
        val rowStart = WHEEL_OUT_DP + BODY_PAD_DP + (if (slot.row == 0) frontInsetDp() else 0)
        return rowStart + slot.col * (TILE_DP + SEAT_GAP_DP)
    }

    /** 타일 위 y(dp, 지도 위 기준): 앞줄 24 + 12 = 36, 뒷줄 36 + 84 + 24 = 144 */
    fun tileYDp(slot: SeatSlot): Int =
        FRONT_BAND_DP + BODY_PAD_DP + (if (slot.row == 0) 0 else TILE_DP + ROW_GAP_DP)

    /** 바퀴 위 y: 앞바퀴는 앞줄 가운데 높이(36 + 42 = 78) 중심 → 60, 뒷바퀴는 뒷줄 가운데(144 + 42 = 186) 중심 → 168 */
    fun wheelTopYDp(front: Boolean): Int = (if (front) FRONT_BAND_DP + BODY_PAD_DP else FRONT_BAND_DP + BODY_PAD_DP + TILE_DP + ROW_GAP_DP) + TILE_DP / 2 - WHEEL_H_DP / 2

    /** 바퀴 왼쪽 x: 왼쪽 0, 오른쪽 332 − 10 = 322 */
    fun wheelLeftXDp(left: Boolean): Int = if (left) 0 else widthDp() - WHEEL_W_DP

    /** 장식 범위 확인용 사각형(dp, 지도 왼쪽 위 기준). 선 두께의 절반을 포함한다 */
    data class DecoBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun intersects(o: DecoBox): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    fun glassBox(): DecoBox {
        val cx = widthDp() / 2f
        val s = GLASS_STROKE_DP / 2f
        return DecoBox(cx - GLASS_BOTTOM_HALF_DP - s, GLASS_TOP_Y_DP - s, cx + GLASS_BOTTOM_HALF_DP + s, GLASS_BOTTOM_Y_DP + s)
    }

    fun mirrorBox(left: Boolean): DecoBox {
        val c = if (left) MIRROR_CENTER_IN_DP.toFloat() else widthDp() - MIRROR_CENTER_IN_DP.toFloat()
        return DecoBox(c - MIRROR_W_DP / 2f, MIRROR_TOP_Y_DP.toFloat(), c + MIRROR_W_DP / 2f, (MIRROR_TOP_Y_DP + MIRROR_H_DP).toFloat())
    }

    fun rearBox(): DecoBox {
        val cx = widthDp() / 2f
        val s = GLASS_STROKE_DP / 2f
        return DecoBox(cx - REAR_LINE_HALF_DP - s, REAR_LINE_Y_DP - s, cx + REAR_LINE_HALF_DP + s, REAR_LINE_Y_DP + REAR_LINE_DIP_DP / 2f + s)
    }

    fun wheelBox(front: Boolean, left: Boolean): DecoBox {
        val x = wheelLeftXDp(left).toFloat()
        val y = wheelTopYDp(front).toFloat()
        return DecoBox(x, y, x + WHEEL_W_DP, y + WHEEL_H_DP)
    }

    fun tileBox(slot: SeatSlot): DecoBox {
        val x = tileXDp(slot).toFloat()
        val y = tileYDp(slot).toFloat()
        return DecoBox(x, y, x + TILE_DP, y + TILE_DP)
    }


    // ---- 캐릭터 그림 영역 환산(BUST_UPPER 뷰포트 x0 = −15, size = 130; CharacterRenderer.kt:36) ----
    const val VIEWPORT_X0 = -15f
    const val VIEWPORT_SIZE = 130f
    const val HAND_MAX_X = 89.5f        // 흔들기 때 손 끝 최대 x(캔버스)
    const val BODY_MAX_X = 78f          // 몸 외곽 최대 x(캔버스)

    /** 캔버스 x → 화면 x(dp) = 정사각 왼쪽 + (x + 15)/130 × side */
    fun canvasXToScreenDp(x: Float, squareLeftDp: Float, sideDp: Float): Float =
        squareLeftDp + (x - VIEWPORT_X0) / VIEWPORT_SIZE * sideDp

    /** 화면 x(dp) → 캔버스 x (위의 역함수) */
    fun screenDpToCanvasX(sx: Float, squareLeftDp: Float, sideDp: Float): Float =
        (sx - squareLeftDp) / sideDp * VIEWPORT_SIZE + VIEWPORT_X0
    /** 지도 왼쪽 끝 x(dp) = 화면 폭 − 오른쪽 여백 − 오른쪽 인셋 − 지도 폭 */
    fun leftEdgeDp(screenWDp: Float, rightInsetDp: Float = 0f): Float =
        screenWDp - MARGIN_RIGHT_DP - rightInsetDp - displayWidthDp(screenWDp)

    /** 지도 위·아래 y(dp, 화면 기준) = 상단 인셋 + 상단 바 + 8 ~ + 높이 */
    fun topEdgeDp(topInsetDp: Float): Float = topInsetDp + StageLayout.TOP_BAR_DP + TOP_GAP_DP
    fun bottomEdgeDp(topInsetDp: Float, screenWDp: Float = 0f): Float = topEdgeDp(topInsetDp) + displayHeightDp(screenWDp)
    /** 범례까지 포함한 아래 끝(말풍선 점유 계산용) */
    fun legendBottomEdgeDp(topInsetDp: Float, screenWDp: Float): Float = bottomEdgeDp(topInsetDp, screenWDp) + LEGEND_GAP_DP + legendHeightDp(tierFor(screenWDp))

    /** 접근성 설명: '앞줄 왼쪽, 선택됨' / '앞줄 왼쪽, 선택 안 됨' */
    fun description(slot: SeatSlot, selected: Boolean): String =
        if (selected) "${slot.descKo}, 선택됨" else "${slot.descKo}, 선택 안 됨"

    /**
     * 짧은 이름 목록 → 지도 위치 목록(받은 순서 유지). 정확히 이 5개 이름(순서 무관)이 아니면 null
     * (중복·누락·모르는 이름 모두 null → 호출자는 기존 글자 칩 줄을 쓴다).
     */
    fun slotsFor(names: List<String>): List<SeatSlot>? {
        if (names.size != SLOTS.size) return null
        if (names.toSet().size != SLOTS.size) return null
        val out = ArrayList<SeatSlot>(names.size)
        for (n in names) {
            val s = SLOTS.firstOrNull { it.name == n } ?: return null
            out.add(s)
        }
        return out
    }

    /**
     * 낮: 차체 흰 채움 + 갈색 윤곽, 타일 미선택 흰 채움/글자 111111, 선택 메인 파랑 2563EB + 흰 글자 + 흰 원 파랑 체크.
     * 밤: 차체 NIGHT_CHIP_BG(2A2E38) + 9E9E9E 윤곽, 타일 3A3F4B/E8EAF0, 선택 밤 파랑 60A5FA + 글자 111111 + 어두운 원 흰 체크.
     * 선택은 색만이 아니라 체크 배지·접근성 설명('선택됨')으로도 알린다.
     */
    fun colors(night: Boolean): SeatMapColors =
        if (night) {
            SeatMapColors(
                bodyFill = Palette.NIGHT_CHIP_BG, bodyStroke = Palette.NIGHT_CHIP_STROKE,
                tileFill = NIGHT_TILE_FILL, tileStroke = Palette.NIGHT_CHIP_STROKE, tileText = Palette.NIGHT_CHIP_TEXT,
                selFill = NIGHT_SEL_FILL, selStroke = Palette.NIGHT_CHIP_STROKE, selText = NIGHT_SEL_TEXT,
                badgeFill = NIGHT_BADGE_FILL, badgeCheck = NIGHT_BADGE_CHECK,
            )
        } else {
            SeatMapColors(
                bodyFill = DAY_FILL, bodyStroke = DAY_STROKE,
                tileFill = DAY_FILL, tileStroke = DAY_STROKE, tileText = Palette.TEXT,
                selFill = Palette.STAGE_YES_FILL, selStroke = DAY_STROKE, selText = Palette.STAGE_YES_TEXT,
                badgeFill = DAY_FILL, badgeCheck = Palette.STAGE_YES_FILL,
            )
        }

    /** 바퀴 색: 낮 3B2A22 / 밤 9E9E9E */
    fun wheelColor(night: Boolean): Int = if (night) NIGHT_WHEEL else DAY_WHEEL

    const val DAY_FILL = 0xFFFFFF
    const val DAY_STROKE = 0x3B2A22
    const val NIGHT_TILE_FILL = 0x3A3F4B
    // 2026-11-10 4차 결정 C(M11): 밤 좌석 선택(긍정 선택 상태) 연초록 81C784 → 밤 파랑 NIGHT_CREW_BLUE 60A5FA(낮 선택과 같은 역할 색)
    const val NIGHT_SEL_FILL = Palette.NIGHT_CREW_BLUE
    const val NIGHT_SEL_TEXT = 0x111111
    const val NIGHT_BADGE_FILL = 0x111111
    const val NIGHT_BADGE_CHECK = 0xFFFFFF
}

/** 짧은 이름 목록 → 지도 위치. 정확히 5개 이름 집합이 아니면 null */
fun seatSlotsFor(names: List<String>): List<SeatSlot>? = StageSeatMap.slotsFor(names)
