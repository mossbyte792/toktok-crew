package com.crewguide.probe

/** 캐릭터 꾸미기 옵션 모델(가안, 시연용). Android 클래스를 쓰지 않는 순수 로직. 저장하지 않는다 */

/** 색 하나: 한국어 이름 + ARGB 값 */
data class ColorOption(val name: String, val argb: Int)

/** 옷 색: 몸·팔 색과 짝이 되는 스카프 색 */
data class OutfitOption(val name: String, val bodyArgb: Int, val scarfArgb: Int)

/** 제복 요소 한 항목: 표시 이름 + 스카프·모자·빈 이름표 유무 */
data class UniformOption(val name: String, val hasScarf: Boolean, val hasHat: Boolean, val hasNameTag: Boolean)

/** 선택된 옵션의 인덱스. 범위를 벗어난 값은 sanitize로 기본값(0)으로 보정한다 */
data class CharacterStyle(
    val hairColorIndex: Int = 0,
    val skinColorIndex: Int = 0,
    val outfitColorIndex: Int = 0,
    val hairShapeIndex: Int = 0,
    val toneIndex: Int = 0,
    val accessoryIndex: Int = 0,
    val uniform: Int = 0,
) {
    companion object {
        // 새 프로필·기본 안내자는 시안 모습(모자+스카프+이름표). 클래스 기본 파라미터 uniform=0은 그대로라 옛 저장 파싱은 바뀌지 않는다
        // 롤백: CharacterStyle()로 되돌린다
        val DEFAULT = CharacterStyle(uniform = CharacterOptions.UNIFORM_FULL)
    }
}

object CharacterOptions {
    /** 눈·입선·눈썹의 진한 색. GuideCharacterView가 같은 값을 쓴다 */
    const val EYE_INK_ARGB: Int = 0xFF2A2630.toInt()

    // 머리 모양: 0=둥근 앞머리(1단계 모양), 1=짧은 옆가르마 느낌의 비대칭 앞머리, 2=작은 뒷머리 원(묶은머리 느낌)
    const val HAIR_ROUND = 0
    const val HAIR_SIDE_PART = 1
    const val HAIR_TIED = 2
    val HAIR_SHAPE_NAMES: List<String> = listOf("둥근 앞머리", "옆가르마", "묶은머리")

    // 표정 톤: 0=웃음(기본), 1=차분(입 곡률 감소, 눈을 약간 가늘게)
    const val TONE_SMILE = 0
    const val TONE_CALM = 1
    val TONE_NAMES: List<String> = listOf("웃음", "차분")

    // 소품: 0=없음, 1=둥근 안경, 2=작은 리본 머리핀 (모자·날개 배지·로고·브랜드 상징 없음)
    const val ACC_NONE = 0
    const val ACC_GLASSES = 1
    const val ACC_RIBBON = 2
    val ACCESSORY_NAMES: List<String> = listOf("없음", "둥근 안경", "리본 핀")

    // 제복 요소(번호 고정): 0=스카프만(현재 모양), 1=없음, 2=스카프+모자, 3=이름표만, 4=모자만, 5=모자+스카프+이름표(새 기본).
    // 이름표는 글자가 없는 빈 이름표만 뜻한다. 0~4의 뜻과 모양은 바꾸지 않는다
    const val UNIFORM_SCARF_ONLY = 0
    const val UNIFORM_FULL = 5
    val UNIFORM: List<UniformOption> = listOf(
        UniformOption("스카프만", hasScarf = true, hasHat = false, hasNameTag = false),
        UniformOption("없음", hasScarf = false, hasHat = false, hasNameTag = false),
        UniformOption("스카프+모자", hasScarf = true, hasHat = true, hasNameTag = false),
        UniformOption("이름표만", hasScarf = false, hasHat = false, hasNameTag = true),
        UniformOption("모자만", hasScarf = false, hasHat = true, hasNameTag = false),
        UniformOption("모자+스카프+이름표", hasScarf = true, hasHat = true, hasNameTag = true),
    )

    /** 제복 요소 버튼의 접근성 설명(번호는 1~6) */
    fun uniformDescription(index: Int) = "${index + 1}번 제복 요소 ${UNIFORM.getOrNull(index)?.name ?: ""}"

    fun uniform(s: CharacterStyle): UniformOption = UNIFORM[fix(s.uniform, UNIFORM.size)]

    // 0번이 기본값이며 GuideCharacterView의 기존 색 상수와 같은 값이다
    val HAIR: List<ColorOption> = listOf(
        ColorOption("남색", 0xFF2F3A5C.toInt()),
        ColorOption("갈색", 0xFF6B4A2F.toInt()),
        ColorOption("검정", 0xFF1E1E24.toInt()),
        ColorOption("밤색", 0xFF4A2A22.toInt()),
        ColorOption("밝은 갈색", 0xFF9A6B3E.toInt()),
    )

    // 눈·윤곽 진한 색(0xFF2A2630)과의 휘도 차이는 0~3번은 100 이상, 4번(짙은 갈색)은 그보다 작다(테스트 참고)
    val SKIN: List<ColorOption> = listOf(
        ColorOption("살구색", 0xFFF8D9B8.toInt()),
        ColorOption("밝은 분홍", 0xFFFBE3D3.toInt()),
        ColorOption("황갈색", 0xFFE8B98F.toInt()),
        ColorOption("중간 갈색", 0xFFC68E5E.toInt()),
        // 인덱스 4·5는 6가지 확장으로 추가(기존 0~3 값·순서 불변). 버튼에는 이름 대신 번호(1~6)만 표시한다
        ColorOption("짙은 갈색", 0xFF9A5C2E.toInt()),
        // 6번은 기존 4번(C68E5E)과의 채널 차이 합이 57(<60)이라 CF9686에서 D29498로 조정(인덱스 5만 변경)
        ColorOption("장밋빛 갈색", 0xFFD29498.toInt()),
    )

    /** 피부색 견본 버튼의 접근성 설명(번호는 1~6) */
    fun skinDescription(index: Int) = "피부색 ${index + 1}번"

    /** 색 견본 위 번호 글자색: 어두운/밝은 두 후보 중 견본 배경과 대비비가 높은 쪽 */
    fun bestTextArgb(bgArgb: Int, darkArgb: Int, lightArgb: Int): Int {
        val bg = bgArgb and 0xFFFFFF
        val d = DesignRules.contrastRatio(darkArgb and 0xFFFFFF, bg)
        val l = DesignRules.contrastRatio(lightArgb and 0xFFFFFF, bg)
        return if (d >= l) darkArgb else lightArgb
    }

    val OUTFIT: List<OutfitOption> = listOf(
        OutfitOption("파랑", 0xFF4A6FA5.toInt(), 0xFFA9D4EE.toInt()),   // 0번: 남색 유니폼 + 하늘색 스카프(시안 1)
        OutfitOption("초록", 0xFF4F8A63.toInt(), 0xFFA8DDB5.toInt()),
        OutfitOption("보라", 0xFF7D62A8.toInt(), 0xFFCBB8EC.toInt()),
        OutfitOption("주황", 0xFFD9822B.toInt(), 0xFFF6C98E.toInt()),
        OutfitOption("회색", 0xFF757B85.toInt(), 0xFFCDD2D8.toInt()),
    )

    private fun fix(i: Int, size: Int) = if (i in 0 until size) i else 0

    /** 범위를 벗어난 인덱스를 기본값(0)으로 보정한다 */
    fun sanitize(s: CharacterStyle): CharacterStyle =
        CharacterStyle(
            fix(s.hairColorIndex, HAIR.size), fix(s.skinColorIndex, SKIN.size), fix(s.outfitColorIndex, OUTFIT.size),
            fix(s.hairShapeIndex, HAIR_SHAPE_NAMES.size), fix(s.toneIndex, TONE_NAMES.size), fix(s.accessoryIndex, ACCESSORY_NAMES.size),
            fix(s.uniform, UNIFORM.size),
        )

    /** 다음 옵션으로 순환(마지막 다음은 처음). 범위 밖 입력은 먼저 보정한다 */
    fun nextHair(s: CharacterStyle): CharacterStyle {
        val c = sanitize(s); return c.copy(hairColorIndex = (c.hairColorIndex + 1) % HAIR.size)
    }
    fun nextSkin(s: CharacterStyle): CharacterStyle {
        val c = sanitize(s); return c.copy(skinColorIndex = (c.skinColorIndex + 1) % SKIN.size)
    }
    fun nextOutfit(s: CharacterStyle): CharacterStyle {
        val c = sanitize(s); return c.copy(outfitColorIndex = (c.outfitColorIndex + 1) % OUTFIT.size)
    }

    fun nextHairShape(s: CharacterStyle): CharacterStyle {
        val c = sanitize(s); return c.copy(hairShapeIndex = (c.hairShapeIndex + 1) % HAIR_SHAPE_NAMES.size)
    }
    fun nextTone(s: CharacterStyle): CharacterStyle {
        val c = sanitize(s); return c.copy(toneIndex = (c.toneIndex + 1) % TONE_NAMES.size)
    }
    fun nextAccessory(s: CharacterStyle): CharacterStyle {
        val c = sanitize(s); return c.copy(accessoryIndex = (c.accessoryIndex + 1) % ACCESSORY_NAMES.size)
    }

    fun hair(s: CharacterStyle): ColorOption = HAIR[fix(s.hairColorIndex, HAIR.size)]
    fun skin(s: CharacterStyle): ColorOption = SKIN[fix(s.skinColorIndex, SKIN.size)]
    fun outfit(s: CharacterStyle): OutfitOption = OUTFIT[fix(s.outfitColorIndex, OUTFIT.size)]
}
