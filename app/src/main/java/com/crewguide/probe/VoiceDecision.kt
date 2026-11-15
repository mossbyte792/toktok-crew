package com.crewguide.probe

/** 음성 인식 결과를 예/아니오로 판정한 값. UNSURE가 기본 안전 경로다(진행하지 않고 터치 확인 안내). */
enum class VoiceDecision { YES, NO, UNSURE }

/**
 * 음성 판정 잠정 기본값. 아래 모든 값은 잠정 가정값이며 실측·사용자 결정으로 확인된 것이 아니다.
 * SDK 호출 없음. 인식 원문은 로그에 남기지 않는다.
 */
object VoiceDefaults {
    // 잠정 가정값: 예류 허용 목록 (정확 일치만)
    val YES_WORDS = setOf("예", "네", "응")
    // 잠정 가정값: 아니오류 허용 목록 (정확 일치만)
    val NO_WORDS = setOf("아니오", "아니요", "아니")
    // 잠정 가정값: 신뢰도가 있을 때 이 값 미만이면 UNSURE (0.0~1.0 척도라는 가정도 미검증)
    const val CONFIDENCE_THRESHOLD = 0.7f
    // 잠정 가정값: 신뢰도 값이 없을 때 확정을 허용할지. false=UNSURE가 기본
    const val ACCEPT_WHEN_CONFIDENCE_MISSING = false
    // PM의 임시 판단이며 사용자 확인 대상이다.
    // SDK(SpeechToText 3.3.1)는 신뢰도를 제공하지 않는다. 'SDK 신뢰도 부재 시 정확 일치 최종 결과 확정 허용'.
    // true: SDK 최종(completed=true) 결과가 허용 단어와 정확 일치하면 YES/NO 확정. false: 음성은 항상 UNSURE(터치로 넘김).
    // 이 상수는 SDK 음성 경로(decideSdkFinalText)에서만 쓰며 기존 ACCEPT_WHEN_CONFIDENCE_MISSING에는 영향이 없다.
    const val ACCEPT_EXACT_FINAL_WHEN_SDK_CONFIDENCE_MISSING = true
}

/** SDK 최종 텍스트 정규화: 공백과 끝/중간의 마침표·쉼표·물음표·느낌표만 제거한다. 원문은 저장·로그하지 않는다. */
fun normalizeSdkText(text: String): String =
    text.filter { !it.isWhitespace() && it !in ".,?!。" }

/** SDK 최종 결과 1개를 판정한다. 신뢰도는 없음(null)으로 넘긴다. */
fun decideSdkFinalText(text: String): VoiceDecision =
    decideYesNo(listOf(normalizeSdkText(text)), null, sdkFinalPath = true)

/**
 * 순수 판정 함수. 후보 목록의 첫 항목이 최상위 결과라고 가정한다.
 * - 빈 목록/빈 문자열, 허용 목록 밖, 예·아니오 후보 혼재 → UNSURE
 * - 신뢰도가 있고 임계값 미만 → UNSURE
 * - 신뢰도 없음 → ACCEPT_WHEN_CONFIDENCE_MISSING이 false면 UNSURE
 */
fun decideYesNo(candidates: List<String>, confidence: Float?, sdkFinalPath: Boolean = false): VoiceDecision {
    val norm = candidates.map { it.trim() }.filter { it.isNotEmpty() }
    val top = norm.firstOrNull() ?: return VoiceDecision.UNSURE
    val hasYes = norm.any { it in VoiceDefaults.YES_WORDS }
    val hasNo = norm.any { it in VoiceDefaults.NO_WORDS }
    if (hasYes && hasNo) return VoiceDecision.UNSURE
    val result = when (top) {
        in VoiceDefaults.YES_WORDS -> VoiceDecision.YES
        in VoiceDefaults.NO_WORDS -> VoiceDecision.NO
        else -> return VoiceDecision.UNSURE
    }
    if (confidence == null) {
        val allowed = if (sdkFinalPath) VoiceDefaults.ACCEPT_EXACT_FINAL_WHEN_SDK_CONFIDENCE_MISSING
                      else VoiceDefaults.ACCEPT_WHEN_CONFIDENCE_MISSING
        if (!allowed) return VoiceDecision.UNSURE
    } else if (confidence.isNaN() || confidence < VoiceDefaults.CONFIDENCE_THRESHOLD) {
        return VoiceDecision.UNSURE
    }
    return result
}
