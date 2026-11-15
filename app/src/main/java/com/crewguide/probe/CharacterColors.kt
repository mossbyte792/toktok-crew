package com.crewguide.probe

/** 색 역할 → ARGB. 머리·피부·옷 색은 CharacterOptions에서 읽는다(하드코딩 없음). Android 클래스 없음 */
object CharacterColors {
    /** 선 색: 진갈색 #3B2A22(부품 윤곽선). 눈 색(EYE_INK_ARGB)과는 따로 둔다 */
    const val LINE_ARGB: Int = 0xFF3B2A22.toInt()
    const val HIGHLIGHT_ARGB: Int = 0xFFFFFFFF.toInt()
    /** 빈 이름표 배지 노랑 #F2B84B */
    const val NAME_TAG_YELLOW_ARGB: Int = 0xFFF2B84B.toInt()
    const val CHEEK_ARGB: Int = 0xFFF4B6B6.toInt()
    const val MOUTH_ARGB: Int = 0xFF8E3B3B.toInt()

    /** NONE은 칠하지 않는 역할이라 0(투명)을 돌려준다 */
    fun colorFor(role: ColorRole, style: CharacterStyle = CharacterStyle.DEFAULT): Int = when (role) {
        ColorRole.SKIN -> CharacterOptions.skin(style).argb
        ColorRole.HAIR -> CharacterOptions.hair(style).argb
        ColorRole.OUTFIT_BODY -> CharacterOptions.outfit(style).bodyArgb
        ColorRole.OUTFIT_SCARF -> CharacterOptions.outfit(style).scarfArgb
        ColorRole.EYE -> CharacterOptions.EYE_INK_ARGB
        ColorRole.CHEEK -> CHEEK_ARGB
        ColorRole.MOUTH -> MOUTH_ARGB
        ColorRole.LINE -> LINE_ARGB
        ColorRole.HIGHLIGHT -> HIGHLIGHT_ARGB
        ColorRole.NAME_TAG_YELLOW -> NAME_TAG_YELLOW_ARGB
        ColorRole.NONE -> 0
    }
}
