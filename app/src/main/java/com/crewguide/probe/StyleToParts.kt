package com.crewguide.probe

/** 꾸미기 스타일 → 그릴 부품 목록(순수 로직, Android 클래스 없음). 가안, 개발용 */
object StyleToParts {
    private fun base(name: String): SvgPart = CharacterParts.ALL.first { it.name == name }

    /**
     * 레이어 오름차순 부품 목록. 이름 중복 없음.
     * 머리 모양 0/1/2 → back_hair/front_hair_0, back_hair_1/front_hair_1, back_hair_2/front_hair_2.
     * 차분(tone 1) → eyes_brows 대신 eyes_brows_calm(눈 세로 0.8배). 입은 mouthPart를 그대로 쓴다.
     * 소품 1=glasses, 2=ribbon_pin. 제복: UNIFORM 표의 hasScarf/hasHat/hasNameTag.
     */
    fun partsFor(style: CharacterStyle, mouth: MouthShape): List<SvgPart> {
        val s = CharacterOptions.sanitize(style)
        val uni = CharacterOptions.uniform(s)
        val out = ArrayList<SvgPart>()

        when (s.hairShapeIndex) {
            CharacterOptions.HAIR_SIDE_PART -> { out.add(CharacterExtraParts.BACK_HAIR_1); out.add(CharacterExtraParts.FRONT_HAIR_1) }
            CharacterOptions.HAIR_TIED -> { out.add(CharacterExtraParts.BACK_HAIR_2); out.add(CharacterExtraParts.FRONT_HAIR_2) }
            else -> { out.add(base("back_hair")); out.add(base("front_hair_0")) }
        }

        for (n in listOf("arm_back", "body", "neck", "ears", "face", "cheeks")) out.add(base(n))
        out.add(if (s.toneIndex == CharacterOptions.TONE_CALM) CharacterExtraParts.EYES_BROWS_CALM else base("eyes_brows"))
        out.add(CharacterParts.mouthPart(mouth))
        out.add(base("arm_front"))
        out.add(CharacterExtraParts.JACKET_DETAIL)   // 제복 선택과 무관하게 항상(흰 셔츠 깃·가슴선·단추). 레이어 35

        when (s.accessoryIndex) {
            CharacterOptions.ACC_GLASSES -> out.add(CharacterExtraParts.GLASSES)
            CharacterOptions.ACC_RIBBON -> out.add(CharacterExtraParts.RIBBON_PIN)
            else -> {}
        }
        if (uni.hasScarf) out.add(base("scarf"))
        if (uni.hasHat) out.add(CharacterExtraParts.HAT)
        if (uni.hasNameTag) out.add(CharacterExtraParts.NAME_TAG)

        return out.sortedBy { it.layer }
    }
}
