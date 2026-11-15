package com.crewguide.probe

/**
 * 마스코트 파츠 구성(순수 로직, Android 클래스 없음). 꾸미기 상태([CharacterStyle])를 "어떤 파츠를 어떤 색으로 쌓을지"의
 * 목록으로 바꾼다. 선언 순서가 그리는 순서(아래 → 위)이며, res/drawable/mascot_<이름>.xml 한 장이 파츠 하나다
 * (design/split_mascot.py가 SVG를 나눠 만든다. 이름·순서가 바뀌면 둘을 같이 고친다 → MascotRecipeTest가 확인).
 */
enum class MascotTint { NONE, SKIN, HAIR, OUTFIT, OUTFIT_SHADE, SCARF, SCARF_SHADE }

/**
 * [arm]이 true인 파츠는 몸 위의 별도 View(손 흔드는 왼팔)에 그려진다. 순서는 같은 종류 안에서만 뜻이 있다
 * (몸 파츠는 선언 순서대로 아래 → 위, 팔 파츠는 소매 → 손 → 손 그늘).
 */
enum class MascotPart(val resName: String, val tint: MascotTint = MascotTint.NONE, val arm: Boolean = false) {
    DECOR("mascot_decor"),
    PROP_BACK("mascot_prop_back"),
    SKIN_LIMBS("mascot_skin_limbs", MascotTint.SKIN),
    LEGS_GAP("mascot_legs_gap"),
    PROP_FRONT("mascot_prop_front"),
    SHOES("mascot_shoes"),
    OUTFIT("mascot_outfit", MascotTint.OUTFIT),
    OUTFIT_SHADE("mascot_outfit_shade", MascotTint.OUTFIT_SHADE),
    SHIRT("mascot_shirt"),
    OUTFIT_FRONT("mascot_outfit_front", MascotTint.OUTFIT),
    BELT("mascot_belt"),
    NAME_TAG("mascot_name_tag"),
    BUTTONS("mascot_buttons"),
    SKIN_HEAD("mascot_skin_head", MascotTint.SKIN),
    HAIR("mascot_hair", MascotTint.HAIR),
    HAT_MAIN("mascot_hat_main", MascotTint.OUTFIT),
    HAT_TRIM("mascot_hat_trim"),
    FACE("mascot_face"),
    SCARF("mascot_scarf", MascotTint.SCARF),
    SCARF_SHADE("mascot_scarf_shade", MascotTint.SCARF_SHADE),
    SCARF_DETAIL("mascot_scarf_detail"),
    ACC_GLASSES("mascot_acc_glasses"),
    ACC_RIBBON("mascot_acc_ribbon"),
    ARM_SLEEVE("mascot_arm_sleeve", MascotTint.OUTFIT, arm = true),
    ARM_HAND("mascot_arm_hand", MascotTint.SKIN, arm = true),
    ARM_HAND_SHADE("mascot_arm_hand_shade", arm = true),
}

/**
 * 손 흔들기(순수 값). 팔 View를 어깨 기준으로 회전시키는 키프레임: (시간 비율, 각도). 0도에서 시작해 0도로 끝난다(인사 주기를 이어 붙여도 끊기지 않음).
 * 각도는 화면 기준 시계 방향이 +(안드로이드 rotation). 사용자 지정 예: 0 → 15 → -10 → 0.
 * 어깨 기준점은 SVG 1024 좌표 (422, 575): 소매를 자른 선(x=422)의 가운데 높이다(design/split_mascot.py의 ARM_CUT_X와 같은 값).
 */
object MascotWave {
    val KEYS: List<Pair<Float, Float>> = listOf(0f to 0f, 0.30f to 15f, 0.70f to -10f, 1f to 0f)
    const val PERIOD_MS = 1600L
    const val ARM_CUT_X = 422
    const val PIVOT_X_FRACTION = ARM_CUT_X / 1024f
    const val PIVOT_Y_FRACTION = 575f / 1024f

    /**
     * 첫 진입 인사에서 흔드는 횟수(2026-11-07 검토자 지시 6: 무한 반복 대신 첫 진입 짧은 인사 1회 → 정적 대기).
     * 한 번의 인사 = [PERIOD_MS] 주기를 이만큼 반복(약 3.2초). 끝나면 팔은 0도로 멈춘다(숨쉬기만 남음).
     */
    const val GREET_CYCLES = 2

    /**
     * 인사를 지금 시작해도 되는가(순수 규칙). 이미 인사했으면(이 홈 화면 수명 동안 1회), 시스템 애니메이션이 꺼져 있으면,
     * 주행 제한·상태 불명으로 움직임이 허용되지 않으면 시작하지 않는다.
     */
    fun shouldGreet(alreadyGreeted: Boolean, animatorsEnabled: Boolean, motionAllowed: Boolean): Boolean =
        !alreadyGreeted && animatorsEnabled && motionAllowed
}

/** 쌓을 파츠 하나. [tintRgb]가 null이면 그림 원래 색 그대로, 아니면 0xRRGGBB로 칠한다 */
data class MascotLayer(val part: MascotPart, val tintRgb: Int?)

object MascotRecipe {
    /**
     * 그림 원래 색(=각 색 목록의 0번)은 tint를 쓰지 않는다. 그림의 기본 모습은 남색 제복·주황 스카프·살구색 피부·남색 머리다.
     * 그래서 피부·머리·옷 색의 0번은 "원래 색", 1번부터가 바꾼 색이다(예전 코드 캐릭터의 0번 색값과는 다르다).
     * 이 마스코트에 없는 것: 머리 모양(hairShapeIndex)·표정 톤(toneIndex)은 그림이 하나뿐이라 반영하지 않는다.
     */
    fun layers(style: CharacterStyle): List<MascotLayer> {
        val s = CharacterOptions.sanitize(style)
        val uniform = CharacterOptions.uniform(s)
        val skin = if (s.skinColorIndex == 0) null else rgb(CharacterOptions.SKIN[s.skinColorIndex].argb)
        val hair = if (s.hairColorIndex == 0) null else rgb(CharacterOptions.HAIR[s.hairColorIndex].argb)
        val outfit = if (s.outfitColorIndex == 0) null else rgb(CharacterOptions.OUTFIT[s.outfitColorIndex].bodyArgb)
        val outfitShade = outfit?.let { darken(it, 82) }
        val scarf = if (s.outfitColorIndex == 0) null else rgb(CharacterOptions.OUTFIT[s.outfitColorIndex].scarfArgb)
        val scarfShade = scarf?.let { darken(it, 88) }
        fun tintOf(t: MascotTint): Int? = when (t) {
            MascotTint.NONE -> null
            MascotTint.SKIN -> skin
            MascotTint.HAIR -> hair
            MascotTint.OUTFIT -> outfit
            MascotTint.OUTFIT_SHADE -> outfitShade
            MascotTint.SCARF -> scarf
            MascotTint.SCARF_SHADE -> scarfShade
        }
        fun visible(p: MascotPart): Boolean = when (p) {
            MascotPart.NAME_TAG -> uniform.hasNameTag
            MascotPart.HAT_MAIN, MascotPart.HAT_TRIM -> uniform.hasHat
            MascotPart.SCARF, MascotPart.SCARF_SHADE, MascotPart.SCARF_DETAIL -> uniform.hasScarf
            MascotPart.ACC_GLASSES -> s.accessoryIndex == CharacterOptions.ACC_GLASSES
            MascotPart.ACC_RIBBON -> s.accessoryIndex == CharacterOptions.ACC_RIBBON
            else -> true
        }
        return MascotPart.values().filter { visible(it) }.map { MascotLayer(it, tintOf(it.tint)) }
    }

    private fun rgb(argb: Int): Int = argb and 0xFFFFFF

    /** 각 채널을 percent%로 줄인 어두운 색(0xRRGGBB) */
    fun darken(rgb: Int, percent: Int): Int {
        fun ch(shift: Int) = ((((rgb shr shift) and 0xFF) * percent + 50) / 100).coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
