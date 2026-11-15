package com.crewguide.probe

// v6.1 공식 캐릭터 원본 PNG(VERIFIED_ORIGINAL 6종) 표시 규칙. 순수 로직(Android 클래스 없음) — CrewArtTest가 확인한다.
// 2026-11-08 ch MASTER DIRECTIVE Phase 2.
//  - 원본: toktok-assets/character/{parked,driving,listening,speaking,quiet,wave}.png → res/drawable-nodpi/crew_v61_*.png (바이트 동일, SHA는 ORIGINAL_SHA256)
//  - LEGACY_PART(hat·scarf·mouth-a), PROTOTYPE_UNAPPROVED(v5 37·v6 크롭·v6.1 눈입눈썹 18), REFERENCE_ONLY(키프레임·시트)는 앱에 넣지 않는다.
//  - 표시 상태는 안전 판단에 쓰지 않는다. 입력(장면·실제 TTS/STT 실행 상태·기어 종류)을 읽기만 한다.

/** 캐릭터 표시 상태(지시 2.5의 매핑 표) */
enum class CrewArtState { IDLE, GREETING, LISTENING, SPEAKING, QUIET, DRIVING, INTERRUPTED }

/** 앱에 들어간 공식 원본 PNG. 파일명은 res/drawable-nodpi/crew_v61_<key>.png */
enum class CrewArtAsset(val key: String, val widthPx: Int, val heightPx: Int, val sha256: String) {
    PARKED("parked", 274, 609, "7c068f8bdddc8d168fc22de8f112966f65b6ec0da6c4abb9015bc9379d23cb4a"),
    DRIVING("driving", 185, 215, "b8ecff83e5426e0c80917c4eba68dc764486828ad57ab2cb7601e165ec77c295"),
    LISTENING("listening", 185, 215, "1a6ca1f926992c34268ff92ff516301a60f1f970cc3cfc98275d4874a32db3a5"),
    SPEAKING("speaking", 185, 215, "974e7837cc22c04d165ec20b21026a1360450c576a20be8242aa70dbf399eb08"),
    QUIET("quiet", 185, 215, "709d09cd28f6e2b54e57c5526b370b83120a6dccc2ab364e4b37f04dfcb1d712"),
    /** 반신(허리 위). parked 전신 자리에 바꿔 넣지 않는다. 2026-11-14: 홈의 인사 칸은 없앴고(캐릭터는 하나만), 첫 안내 화면(FirstRun)의 그림으로만 쓴다 */
    WAVE("wave", 207, 290, "6b398c0a1106d76a101b5c2601e1316d2cb0fe66efe055f92c6d94b4ba146d99"),
    ;
    val resName: String get() = "crew_v61_$key"
}

/** 실제 음성 실행 상태. SPEAKING = TTS onStart 수신 후 종료 콜백 전, LISTENING = STT request 활성. 타이머로 만들지 않는다 */
enum class CrewVoice { NONE, SPEAKING, LISTENING }

object CrewArt {
    /** 지시 2.5 표 그대로 */
    fun assetOf(state: CrewArtState): CrewArtAsset = when (state) {
        CrewArtState.IDLE -> CrewArtAsset.PARKED
        CrewArtState.GREETING -> CrewArtAsset.WAVE
        CrewArtState.LISTENING -> CrewArtAsset.LISTENING
        CrewArtState.SPEAKING -> CrewArtAsset.SPEAKING
        CrewArtState.QUIET -> CrewArtAsset.QUIET
        CrewArtState.DRIVING -> CrewArtAsset.DRIVING
        CrewArtState.INTERRUPTED -> CrewArtAsset.QUIET
    }

    /**
     * 무대 장면 → 표시 상태.
     * - LOCKED: 유효한 비-P 기어(D/R/N)를 받았으면 DRIVING, 그 밖(불명·단절·구독 실패·플랫폼 제한)은 INTERRUPTED. 음성 상태와 무관(정지 이미지)
     * - 그 외: 실제 STT 활성이면 LISTENING, 실제 TTS 재생 중이면 SPEAKING, 보호자 응답 대기(WAIT_GUARDIAN)는 QUIET, 나머지는 IDLE
     * GREETING은 무대에서 쓰지 않는다(홈 인사 칸은 2026-11-14 제거, 그림은 첫 안내 화면에만).
     */
    fun forStage(scene: Scene, voice: CrewVoice, drivingGear: Boolean): CrewArtState = when {
        scene == Scene.LOCKED -> if (drivingGear) CrewArtState.DRIVING else CrewArtState.INTERRUPTED
        voice == CrewVoice.LISTENING -> CrewArtState.LISTENING
        voice == CrewVoice.SPEAKING -> CrewArtState.SPEAKING
        scene == Scene.WAIT_GUARDIAN -> CrewArtState.QUIET
        else -> CrewArtState.IDLE
    }

    /** 두 실행 상태가 겹치면 듣기를 앞세운다(질문 발화 뒤 STT로 넘어간 순간 TTS 종료 콜백이 늦어도 듣는 얼굴) */
    fun voiceOf(ttsSpeaking: Boolean, sttActive: Boolean): CrewVoice = when {
        sttActive -> CrewVoice.LISTENING
        ttsSpeaking -> CrewVoice.SPEAKING
        else -> CrewVoice.NONE
    }

    /** 그림을 못 읽을 때 대신 쓸 그림. 흉상 상태 → parked 전신. parked 자신과 인사 반신(wave)은 대신 그리지 않는다(빈 칸) */
    fun fallbackOf(a: CrewArtAsset): CrewArtAsset? = when (a) {
        CrewArtAsset.PARKED, CrewArtAsset.WAVE -> null
        else -> CrewArtAsset.PARKED
    }

    /** 확대 상한. 1.0 = 원본보다 크게 그리지 않음. 무대는 디자이너 미리보기(v6.1 preview.html: 흉상 215→320px, 1.49배)와 같은 수준까지 */
    const val HOME_MAX_SCALE = 1.0f
    const val STAGE_MAX_SCALE = 1.5f
    /** 무대 캐릭터 상자 높이 상한(dp). 상태 이미지가 바뀌어도 상자 크기는 같다(그림만 상자 안에서 바뀜) */
    const val STAGE_BOX_MAX_DP = 520

    /** 비율 유지(contain) 배율: 상자 안에 들어가고 상한을 넘지 않는다. 0 이하 입력은 0 */
    fun fitScale(imgW: Int, imgH: Int, boxW: Int, boxH: Int, maxScale: Float): Float {
        if (imgW <= 0 || imgH <= 0 || boxW <= 0 || boxH <= 0) return 0f
        return minOf(boxW.toFloat() / imgW, boxH.toFloat() / imgH, maxScale)
    }
}

/** 꾸미기 진입(2026-11-08 지시 2.7): 공식 원본 PNG가 꾸미기 옵션을 지원하지 않아 일반 사용자 진입을 숨긴다. 코드·저장값은 그대로 */
object CustomizeAvailability {
    const val ENTRY_VISIBLE = false
}

/**
 * v3 단계 7(2026-11-11): 무대 캐릭터 앵커 일치. 전신(parked)과 흉상(listening·speaking·quiet·driving)을 바꿔 그릴 때
 * 모자(머리) 위치·크기가 튀지 않게, 흉상을 전신의 모자 기준점에 맞춘다. 원본 PNG는 그대로(배율·위치만 계산).
 * 기준점은 원본 픽셀을 읽기만 해서 잰 값(2026-11-11, `.tmp_ascii/v3emu/measure_anchor.py`): 모자 파란 영역의 가로 범위와 윗변.
 */
object CrewAnchor {
    data class Hat(val left: Int, val right: Int, val top: Int) {
        val width: Int get() = right - left
        val centerX: Float get() = (left + right) / 2f
    }
    val PARKED_HAT = Hat(50, 197, 10)
    val BUST_HAT = Hat(34, 138, 10)          // listening·speaking·quiet·driving 공통(±1px)
    fun hatOf(a: CrewArtAsset): Hat? = when (a) {
        CrewArtAsset.PARKED -> PARKED_HAT
        CrewArtAsset.LISTENING, CrewArtAsset.SPEAKING, CrewArtAsset.QUIET, CrewArtAsset.DRIVING -> BUST_HAT
        CrewArtAsset.WAVE -> null            // 첫 안내 화면 그림 전용(무대에서 안 씀)
    }

    data class Place(val scale: Float, val dx: Float, val dy: Float)

    /** 전신 배치: 상자 안 contain + 가로 가운데 + 세로 가운데(기존 CrewArtView와 같음) */
    fun parkedPlace(boxW: Int, boxH: Int, maxScale: Float): Place {
        val a = CrewArtAsset.PARKED
        val s = CrewArt.fitScale(a.widthPx, a.heightPx, boxW, boxH, maxScale)
        return Place(s, (boxW - a.widthPx * s) / 2f, (boxH - a.heightPx * s) / 2f)
    }

    /**
     * 흉상 배치: 모자 폭이 전신 모자 폭과 같도록 배율을 정하고(상한 maxScale), 모자 윗변·가운데를 전신과 같은 자리에 둔다.
     * 상자 밖으로 나가면 상자 안으로 밀어 넣는다(잘림 방지). 앵커가 없는 그림은 null(호출자가 기존 contain 사용)
     */
    fun place(a: CrewArtAsset, boxW: Int, boxH: Int, maxScale: Float): Place? {
        val hat = hatOf(a) ?: return null
        val p = parkedPlace(boxW, boxH, maxScale)
        if (a == CrewArtAsset.PARKED) return p
        if (p.scale <= 0f) return null
        var s = p.scale * PARKED_HAT.width / hat.width
        s = minOf(s, maxScale, boxW.toFloat() / a.widthPx, boxH.toFloat() / a.heightPx)
        val anchorX = p.dx + PARKED_HAT.centerX * p.scale
        val anchorY = p.dy + PARKED_HAT.top * p.scale
        val dx = (anchorX - hat.centerX * s).coerceIn(0f, maxOf(0f, boxW - a.widthPx * s))
        val dy = (anchorY - hat.top * s).coerceIn(0f, maxOf(0f, boxH - a.heightPx * s))
        return Place(s, dx, dy)
    }

    /**
     * v3 반복 3(2026-11-11): 흉상 '초상 카드' 앵커. 모자 기준([place])은 머리는 고정되지만 흉상이 가슴에서 잘려 아래 절반이 비어 보였다.
     * 흉상 원본은 아래가 가슴에서 곧게 잘린 그림(불투명 영역 y 9..212 / 215)이라, 그 잘린 선을 둥근 카드의 아래 변과 일치시켜
     * '가까이 본 장면(클로즈업 창)'으로 읽히게 한다. 원본 픽셀은 그대로(배율·위치·잘라 그리기만).
     *  - 배율: 확대 상한(maxScale) 안에서 최대. 단 흉상 높이가 전신이 그려진 높이를 넘지 않고, 카드 여백 포함 상자 안에 들어간다
     *  - 위치: 흉상(불투명 영역)의 가운데를 전신(불투명 영역)의 가운데에 맞춘다 → 위·아래 빈 곳이 같아져 한쪽만 비지 않는다
     *  - 카드: 불투명 영역 + 여백(위·좌·우), 아래 변 = 잘린 선. 상자 밖으로 나가지 않게 민다
     */
    const val BUST_OPAQUE_LEFT = 9
    const val BUST_OPAQUE_RIGHT = 171
    const val BUST_OPAQUE_TOP = 9
    const val BUST_CUT_Y = 212
    const val PARKED_OPAQUE_TOP = 9
    const val PARKED_OPAQUE_BOTTOM = 599

    data class Card(val place: Place, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun isBust(a: CrewArtAsset): Boolean = hatOf(a) == BUST_HAT

    fun bustCard(a: CrewArtAsset, boxW: Int, boxH: Int, maxScale: Float, padPx: Float): Card? {
        if (!isBust(a)) return null
        val p = parkedPlace(boxW, boxH, maxScale)
        if (p.scale <= 0f) return null
        val opW = (BUST_OPAQUE_RIGHT - BUST_OPAQUE_LEFT).toFloat()
        val opH = (BUST_CUT_Y - BUST_OPAQUE_TOP).toFloat()
        val parkedH = (PARKED_OPAQUE_BOTTOM - PARKED_OPAQUE_TOP) * p.scale
        val s = minOf(maxScale, parkedH / opH, (boxW - 2 * padPx) / opW, (boxH - padPx) / opH)
        if (s <= 0f) return null
        // 전신 불투명 영역의 가운데
        val cx = p.dx + CrewArtAsset.PARKED.widthPx / 2f * p.scale
        val cy = p.dy + (PARKED_OPAQUE_TOP + PARKED_OPAQUE_BOTTOM) / 2f * p.scale
        var left = cx - opW * s / 2f - padPx
        var top = cy - (opH * s + padPx) / 2f
        val w = opW * s + 2 * padPx
        val h = opH * s + padPx
        left = left.coerceIn(0f, maxOf(0f, boxW - w))
        top = top.coerceIn(0f, maxOf(0f, boxH - h))
        val dx = left + padPx - BUST_OPAQUE_LEFT * s
        val dy = top + padPx - BUST_OPAQUE_TOP * s
        return Card(Place(s, dx, dy), left, top, left + w, top + h)
    }
}

/** 무대 흉상 배치 방식. HAT = v3 단계 7(머리 고정), CARD = 반복 3(초상 카드, 기본) */
enum class BustAnchorMode { HAT, CARD }
