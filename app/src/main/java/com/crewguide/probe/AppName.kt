package com.crewguide.probe

/** 앱 이름 상수 한 곳(순수 로직, Android 클래스 없음). 이름은 가안이다.
 *  NAME은 AndroidManifest.xml의 application label과 같은 값이다. 바꿀 때 두 곳을 함께 변경한다. */
object AppName {
    const val NAME = "톡톡크루"
    /** 2026-11-10 ch 결정: 앱 이름 톡톡크루(TOK TOK CREW). 안내 캐릭터는 "톡톡크루 승무원"으로 소개한다 */
    const val CHARACTER = "톡톡크루 승무원"
    /** 2026-11-10 2차 디렉티브 ④: 영문 표기와 이전 표시명(같은 프로젝트). 설정의 안내 접이식 맨 위 한 줄 */
    const val NAME_EN = "TOK TOK CREW"
    const val FORMER_NAME = "톡톡 승무원"
    val aboutLine: String get() = "$NAME ($NAME_EN) · 이전 표시명 '$FORMER_NAME'과 같은 앱이에요"
    const val DRAFT_NOTE = "이름은 가안이에요. 상표·중복 확인 전이에요."

    /** 시연 단계 안내 카드 맨 아래 한 줄 */
    val footerLine: String get() = "앱 이름: $NAME (가안). $DRAFT_NOTE"
}
