package com.crewguide.probe

/** 이번 이동의 이동 끝 신호 출처(화면 표기용). TripEndState와 별개이며 메모리에만 둔다.
 *  MOCK은 debug 빌드의 모의 내비(src/debug)만 만든다. release에는 MOCK을 만드는 코드가 없다(2026-11-07 Unit 5.1). */
enum class SignalSource { NONE, REAL, MOCK }
