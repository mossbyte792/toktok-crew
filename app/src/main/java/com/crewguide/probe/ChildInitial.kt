package com.crewguide.probe

// 2026-11-10 4차 결정 A(M07): 아이 프로필 카드·무대 아이 카드의 아바타는 승무원 그림(옛 벡터 캐릭터)을 쓰지 않고
// 별명 첫 글자를 둥근 바탕에 넣은 중립 표시로 바꾼다. 새 마스코트·캐릭터 그림이 아니다(글자 한 개 + 원).
// 이 표시는 장식이다. 아이 구분·선택 상태는 별명 글자, 선택 표시(윤곽·체크 배지), 접근성 설명이 맡는다.
// 저장값(CharacterStore, 프로필 id, 프로필의 style)은 읽기만 하고 바꾸지 않는다. Android 클래스 없음.

object ChildInitial {
    /** 별명이 비어 있을 때 쓰는 기호(물음표는 '신호 미확인' 기호와 겹쳐 쓰지 않는다) */
    const val EMPTY_MARK = "·"

    /** 원 안 글자색: 파스텔 4색(ChildCardLayout.AVATAR_BGS) 모두와 대비 14 이상 */
    const val TEXT_RGB = 0x111111

    /** 별명의 첫 글자(앞뒤 공백 제외, 코드포인트 1개). 비어 있으면 [EMPTY_MARK] */
    fun of(nickname: String?): String {
        val t = nickname?.trim().orEmpty()
        if (t.isEmpty()) return EMPTY_MARK
        val end = t.offsetByCodePoints(0, 1)
        return t.substring(0, end).uppercase()
    }
}
