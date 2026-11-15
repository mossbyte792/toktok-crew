package com.crewguide.probe

// 톡톡크루 2.5D 캐릭터의 2D 파츠 리그(rig) 정의와 모션 정책. 순수 로직(Android 클래스 없음).
// 목표 모습: docs/시안_2.5D/톡톡크루 2D 캐릭터 디자인 보드.png (표정 5종·제스처 3종·LAYERS 줄).
// 그 보드는 한 장의 RGB PNG라 앱에서 바로 움직일 수 있는 레이어가 아니다. 아래 파츠는 '제작해야 할 목록'이며,
// 2026-11-07 현재 모든 파츠의 아트 상태는 NOT_PREPARED다(분리 투명 레이어 미제작). 준비 전에는 기존 MascotView
// (res/drawable/mascot_*.xml, 이전 아트)로 그린다 → [CrewRig.renderer].
// 3D 모델·3D 엔진은 쓰지 않는다(사용자 확정).

/** 파츠 아트 준비 상태. 시안 보드 자체는 어떤 파츠의 READY 근거도 아니다 */
enum class PartArtStatus { NOT_PREPARED, DRAFT, REVIEWED }

/** 꾸미기 저장값 중 무엇으로 색을 바꾸는가. [MascotTint]와 같은 의미(색 0번 = 그림 원래 색, tint 없음) */
enum class CrewTintRole { NONE, SKIN, HAIR, OUTFIT, OUTFIT_SHADE, SCARF }

/** 보이기 조건 */
enum class CrewVisibility { ALWAYS, HAT, SCARF, NAME_TAG, GLASSES, RIBBON }

/**
 * 파츠 하나. 모든 파츠는 같은 논리 캔버스([CrewRig.CANVAS] 정사각) 좌표를 쓴다. 투명 여백을 잘라 납품해도
 * 원래 캔버스 위치를 잃지 않도록 trim 정보는 아트 매니페스트에 따로 둔다(에셋 계획 3장 공통 manifest 필드).
 * [pivotX]/[pivotY]: 회전 기준(캔버스 좌표). [parent]: 부모 파츠의 변환을 따라간다(예: 손 → 아래팔 → 위팔 → 몸).
 * [z]: 그리는 순서(작을수록 아래). 값은 제작 시작 가안이며 아트 납품 후 실측으로 고친다.
 */
enum class CrewRigPart(
    val z: Int,
    val parent: CrewRigPart?,
    val pivotX: Int,
    val pivotY: Int,
    val tint: CrewTintRole = CrewTintRole.NONE,
    val visibility: CrewVisibility = CrewVisibility.ALWAYS,
) {
    SHADOW(0, null, 512, 980),
    BODY(10, null, 512, 700),                                        // 몸통·치마·다리·신발(보드 LAYERS 2번째)
    BODY_SHADE(11, BODY, 512, 700, CrewTintRole.OUTFIT_SHADE),
    BODY_OUTFIT(12, BODY, 512, 700, CrewTintRole.OUTFIT),
    NAME_TAG(13, BODY, 560, 560, visibility = CrewVisibility.NAME_TAG),
    ARM_R_UPPER(14, BODY, 400, 470, CrewTintRole.OUTFIT),            // 화면 왼쪽 팔(차려 자세 유지 팔)
    ARM_R_HAND(15, ARM_R_UPPER, 380, 690, CrewTintRole.SKIN),
    NECK(20, BODY, 512, 440, CrewTintRole.SKIN),
    HAIR_BACK(21, NECK, 512, 300, CrewTintRole.HAIR),   // 뒷머리·쪽머리(보드 LAYERS 1번째)
    HEAD(22, NECK, 512, 420, CrewTintRole.SKIN),    // 얼굴·귀 바탕(눈·입 없음, 보드 LAYERS 3번째)
    CHEEKS(23, HEAD, 512, 330),
    EYE_L(24, HEAD, 450, 300),                                       // 흰자·눈동자·속눈썹(보드 LAYERS 4번째)
    EYE_R(25, HEAD, 574, 300),
    EYELID_L(26, EYE_L, 450, 285, CrewTintRole.SKIN),                // 깜빡임용. 열림/반/감김 3단
    EYELID_R(27, EYE_R, 574, 285, CrewTintRole.SKIN),
    BROWS(28, HEAD, 512, 255, CrewTintRole.HAIR),
    MOUTH(29, HEAD, 512, 370),                                       // 입 상태 교체(보드 LAYERS 5번째)
    HAIR_FRONT(30, HEAD, 512, 220, CrewTintRole.HAIR),
    GLASSES(31, HEAD, 512, 300, visibility = CrewVisibility.GLASSES),
    HAT(32, HEAD, 512, 150, CrewTintRole.OUTFIT, CrewVisibility.HAT), // 보드 LAYERS 8번째
    RIBBON(33, HEAD, 600, 200, visibility = CrewVisibility.RIBBON),
    SCARF(34, BODY, 512, 450, CrewTintRole.SCARF, CrewVisibility.SCARF), // 보드 LAYERS 9번째
    ARM_L_UPPER(40, BODY, 624, 470, CrewTintRole.OUTFIT),            // 화면 오른쪽 팔: 손 흔들기·가리키기(보드 LAYERS 6·7번째)
    ARM_L_FORE(41, ARM_L_UPPER, 690, 600, CrewTintRole.OUTFIT),      // 팔꿈치 기준
    ARM_L_HAND(42, ARM_L_FORE, 740, 520, CrewTintRole.SKIN),         // 손 모양 교체: 중립/인사/가리킴
    ;
}

/** 눈 상태(눈꺼풀 파츠 교체) */
enum class EyeState { OPEN, HALF, CLOSED, HAPPY_ARC }

/** 입 상태(입 파츠 교체). 실제 음소 립싱크가 아니다 */
enum class MouthState { SMILE_CLOSED, NEUTRAL, SMALL_OPEN, WIDE_OPEN, ROUND_O }

/** 손 모양(손 파츠 교체) */
enum class HandShape { RELAXED, WAVE_OPEN, POINT }

/** 보드 EXPRESSIONS 줄 5종(왼쪽부터) */
enum class CrewExpression(val eyes: EyeState, val mouth: MouthState) {
    SMILE(EyeState.OPEN, MouthState.SMILE_CLOSED),        // 1: 기본 미소
    JOY(EyeState.HAPPY_ARC, MouthState.WIDE_OPEN),        // 2: 눈웃음 활짝
    CALM(EyeState.CLOSED, MouthState.SMILE_CLOSED),       // 3: 눈 감은 차분한 미소
    TALK(EyeState.OPEN, MouthState.SMALL_OPEN),           // 4: 밝게 말하기
    LISTEN(EyeState.OPEN, MouthState.ROUND_O),            // 5: 귀 기울임(작은 'o' 입)
}

/** 보드 GESTURES 줄 3종 */
enum class CrewGesture(val hand: HandShape) {
    STAND(HandShape.RELAXED),
    WAVE(HandShape.WAVE_OPEN),
    POINT(HandShape.POINT),
}

/** 모션 계획 결과. 반복 여부는 idle 호흡·깜빡임만 가질 수 있고, 제스처는 항상 1회다 */
data class CrewMotionPlan(
    val expression: CrewExpression,
    val gesture: CrewGesture,
    /** 제스처를 1회 재생할지(false면 해당 포즈를 정적으로 표시) */
    val playGestureOnce: Boolean,
    val idleBreath: Boolean,
    val blink: Boolean,
    /** TTS 발화 중 입 열기·닫기 순환(onStart~onDone/onError 범위) */
    val mouthCycle: Boolean,
) {
    val isStatic: Boolean get() = !playGestureOnce && !idleBreath && !blink && !mouthCycle

    companion object {
        val STATIC_NEUTRAL = CrewMotionPlan(CrewExpression.SMILE, CrewGesture.STAND, false, false, false, false)
    }
}

object CrewMotionPolicy {
    // 초깃값 제안(에셋 계획 4장). 실측·사용자 검토 후 조정한다.
    const val BREATH_PERIOD_MS = 4000L
    const val BREATH_SCALE_PERCENT = 1.5f
    const val BLINK_MIN_MS = 2500L
    const val BLINK_MAX_MS = 5500L
    const val BLINK_CLOSE_MS = 70L
    const val BLINK_HOLD_MS = 40L
    const val BLINK_OPEN_MS = 110L
    const val WAVE_MS = 1400L
    const val POINT_IN_MS = 300L
    const val POINT_OUT_MS = 300L
    const val MOUTH_STEP_MS = 140L
    const val POSITIVE_MS = 600L
    const val BUTTON_PRESS_MS = 100L
    const val BUTTON_RELEASE_MS = 260L
    const val BUBBLE_IN_MS = 220L

    /**
     * 화면 단계 → 표정·제스처. 아래 조건 중 하나라도 맞으면 정적 중립 포즈(모든 반복·예약 모션 중단):
     * 정차 허용 아님(주행·불명·제한), 화면이 보이지 않음, 시스템 애니메이션 꺼짐(animator scale 0 / 동작 줄이기).
     * 주행 중 반복 애니메이션은 제공하지 않는다(사용자 확정).
     */
    fun plan(step: BoardingStep, gate: VehicleGate, visible: Boolean, animationsEnabled: Boolean, speaking: Boolean, firstVisit: Boolean): CrewMotionPlan {
        if (gate != VehicleGate.PARK_ALLOWED || !visible) return CrewMotionPlan.STATIC_NEUTRAL
        val (expr, gesture, once) = when (step) {
            BoardingStep.HOME ->   // 첫 인사 1회만 손 흔들기. 반복 방문·창 전환 뒤에는 자동 반복하지 않는다
                if (firstVisit) Triple(CrewExpression.JOY, CrewGesture.WAVE, true) else Triple(CrewExpression.SMILE, CrewGesture.STAND, false)
            BoardingStep.CHILD_SELECT, BoardingStep.SEAT_SELECT -> Triple(CrewExpression.SMILE, CrewGesture.STAND, false)
            BoardingStep.ANNOUNCING -> Triple(CrewExpression.TALK, CrewGesture.POINT, true)   // 안내 손짓
            BoardingStep.ASKING -> Triple(CrewExpression.LISTEN, CrewGesture.STAND, false)    // 재촉 모션 없음
            BoardingStep.WAITING_GUARDIAN -> Triple(CrewExpression.CALM, CrewGesture.STAND, false)
            BoardingStep.AUTO_CHECK -> Triple(CrewExpression.LISTEN, CrewGesture.STAND, false)   // 2026-11-13 신호 확인 중(재촉 모션 없음)
            BoardingStep.CHILD_NOTICE -> Triple(CrewExpression.JOY, CrewGesture.WAVE, true)
            BoardingStep.FINISHED -> Triple(CrewExpression.SMILE, CrewGesture.STAND, false)
        }
        if (!animationsEnabled) return CrewMotionPlan(expr, gesture, false, false, false, false)   // 상태별 정적 포즈
        val talkExpr = if (speaking && expr != CrewExpression.CALM) CrewExpression.TALK else expr
        return CrewMotionPlan(talkExpr, gesture, once, idleBreath = true, blink = true, mouthCycle = speaking)
    }

    /** 다음 깜빡임까지 시간. [unit]은 0..1 난수(테스트에서 고정). 너무 규칙적이지 않게 범위 안에서 고른다 */
    fun nextBlinkDelayMs(unit: Double): Long {
        val u = unit.coerceIn(0.0, 1.0)
        return BLINK_MIN_MS + ((BLINK_MAX_MS - BLINK_MIN_MS) * u).toLong()
    }

    /** 발화 중 입 상태 순환(닫힘→작게→넓게→작게). 음소 동기화 아님 */
    private val MOUTH_CYCLE = listOf(MouthState.NEUTRAL, MouthState.SMALL_OPEN, MouthState.WIDE_OPEN, MouthState.SMALL_OPEN)

    fun mouthAt(elapsedMs: Long, base: MouthState): MouthState =
        if (elapsedMs < 0) base else MOUTH_CYCLE[((elapsedMs / MOUTH_STEP_MS) % MOUTH_CYCLE.size).toInt()]

    /**
     * 쫀득한 눌림·복귀용 오버슈트 곡선(0..1 → 0..1, 끝에서 살짝 넘었다 돌아옴). Android OvershootInterpolator와
     * 같은 식(tension 2): t' = t-1; t'^2 * ((T+1) t' + T) + 1.
     */
    fun overshoot(t: Float, tension: Float = 2f): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        return x * x * ((tension + 1f) * x + tension) + 1f
    }
}

/** 렌더러 선택 */
enum class CrewRenderer {
    /** 새 2D 파츠 리그(모든 필수 파츠가 검수 완료일 때만) */
    RIG_2D,
    /** 기존 마스코트(res/drawable/mascot_*.xml, 이전 아트). 새 시안 화풍이 아님 */
    LEGACY_MASCOT,
}

object CrewRig {
    const val CANVAS = 1024

    /** 리그 렌더에 반드시 필요한 파츠(소품·조건부 파츠 제외) */
    val REQUIRED: Set<CrewRigPart> = CrewRigPart.values().filter { it.visibility == CrewVisibility.ALWAYS }.toSet()

    /** 2026-11-07 현재 아트 상태. 파츠가 납품·검수되면 여기만 바꾼다 */
    val ART_STATUS: Map<CrewRigPart, PartArtStatus> = CrewRigPart.values().associateWith { PartArtStatus.NOT_PREPARED }

    fun renderer(status: Map<CrewRigPart, PartArtStatus> = ART_STATUS): CrewRenderer =
        if (REQUIRED.all { status[it] == PartArtStatus.REVIEWED }) CrewRenderer.RIG_2D else CrewRenderer.LEGACY_MASCOT

    /** 그리는 순서(아래 → 위) */
    fun drawOrder(): List<CrewRigPart> = CrewRigPart.values().sortedBy { it.z }

    /** 꾸미기 상태에서 보이는 파츠. 기존 [CharacterOptions.uniform]·액세서리 인덱스 의미를 그대로 쓴다 */
    fun visibleParts(style: CharacterStyle): List<CrewRigPart> {
        val s = CharacterOptions.sanitize(style)
        val u = CharacterOptions.uniform(s)
        return drawOrder().filter {
            when (it.visibility) {
                CrewVisibility.ALWAYS -> true
                CrewVisibility.HAT -> u.hasHat
                CrewVisibility.SCARF -> u.hasScarf
                CrewVisibility.NAME_TAG -> u.hasNameTag
                CrewVisibility.GLASSES -> s.accessoryIndex == CharacterOptions.ACC_GLASSES
                CrewVisibility.RIBBON -> s.accessoryIndex == CharacterOptions.ACC_RIBBON
            }
        }
    }

    /** 부모 사슬(자신 제외, 가까운 순). 순환이 있으면 예외 대신 끊는다 */
    fun ancestors(p: CrewRigPart): List<CrewRigPart> {
        val out = ArrayList<CrewRigPart>()
        var cur = p.parent
        while (cur != null && cur !in out && cur != p) { out.add(cur); cur = cur.parent }
        return out
    }
}
