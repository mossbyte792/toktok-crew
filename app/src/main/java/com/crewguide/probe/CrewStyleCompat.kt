package com.crewguide.probe

// 기존 꾸미기 저장값(CharacterCodec v1: h/s/o/hs/t/a/u)과 새 2D 리그의 호환 규칙. 순수 로직.
// 원칙(현황대조 6장 저장값 호환 계약):
//  - 저장 형식·키·인덱스 의미를 바꾸지 않는다. 새 아트 전환 때 저장값을 다시 쓰거나 초기화하지 않는다.
//  - 색 0번 = 그림 원래 색(tint 없음). 1번부터 바꾼 색.
//  - 새 아트에 없는 옵션(머리 모양·표정 톤)은 저장값을 그대로 두고 화면만 기본 파츠로 대체한다.
//    꾸미기 화면은 해당 옵션을 선택 가능한 것처럼 보이지 않게 한다.
//  - GuideStyleMigration(guide_style_migrated_v1)은 이미 한 번 실행된 이전 규칙이며 새 아트 전환 때 재실행하지 않는다.

/** 꾸미기 항목. 키는 CharacterCodec v1의 키와 같다 */
enum class StyleField(val codecKey: String) {
    HAIR_COLOR("h"), SKIN_COLOR("s"), OUTFIT_COLOR("o"), HAIR_SHAPE("hs"), TONE("t"), ACCESSORY("a"), UNIFORM("u"),
}

/** 새 렌더러에서 항목을 어떻게 다루는가 */
enum class FieldSupport {
    /** 화면에 반영된다 */
    SHOWN,
    /** 저장값은 보존하지만 현재 아트에 대응 파츠가 없어 화면에 반영하지 않는다(옵션 비활성 표시) */
    PRESERVED_ONLY,
}

object CrewStyleCompat {
    /**
     * 렌더러별 항목 지원. 기존 마스코트(MascotRecipe)와 새 리그 모두 머리 모양·표정 톤 그림이 하나뿐이다.
     * 새 리그의 표정은 꾸미기 저장값(t)이 아니라 화면 단계의 모션 정책(CrewMotionPolicy)이 정한다.
     * 머리 모양 세트가 납품되면 HAIR_SHAPE만 SHOWN으로 바꾸고 기존 인덱스(0 둥근, 1 옆 가르마, 2 묶음)에 대응시킨다.
     */
    fun support(renderer: CrewRenderer, field: StyleField): FieldSupport = when (field) {
        StyleField.HAIR_SHAPE, StyleField.TONE -> FieldSupport.PRESERVED_ONLY
        else -> FieldSupport.SHOWN
    }

    fun shownFields(renderer: CrewRenderer): List<StyleField> =
        StyleField.values().filter { support(renderer, it) == FieldSupport.SHOWN }

    /** 화면에만 쓰는 값. 저장값은 바꾸지 않는다(반환값을 저장하지 말 것) */
    fun displayStyle(stored: CharacterStyle, renderer: CrewRenderer): CharacterStyle {
        var s = CharacterOptions.sanitize(stored)
        if (support(renderer, StyleField.HAIR_SHAPE) == FieldSupport.PRESERVED_ONLY) s = s.copy(hairShapeIndex = CharacterStyle.DEFAULT.hairShapeIndex)
        if (support(renderer, StyleField.TONE) == FieldSupport.PRESERVED_ONLY) s = s.copy(toneIndex = CharacterStyle.DEFAULT.toneIndex)
        return s
    }

    /** 사용자가 꾸미기에서 지원 항목만 바꿨을 때 저장할 값: 미지원 항목은 기존 저장값을 그대로 유지한다 */
    fun merge(stored: CharacterStyle, edited: CharacterStyle, renderer: CrewRenderer): CharacterStyle {
        var out = edited
        if (support(renderer, StyleField.HAIR_SHAPE) == FieldSupport.PRESERVED_ONLY) out = out.copy(hairShapeIndex = stored.hairShapeIndex)
        if (support(renderer, StyleField.TONE) == FieldSupport.PRESERVED_ONLY) out = out.copy(toneIndex = stored.toneIndex)
        return out
    }
}
