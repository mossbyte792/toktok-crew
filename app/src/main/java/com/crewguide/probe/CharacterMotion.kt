package com.crewguide.probe

// 캐릭터 절제된 동작(가안, 단계 4) 순수 함수. Android 클래스를 쓰지 않고 시간을 인자로 받는다.
// 소리가 났다는 뜻이 아니다: 말하는 입은 ANNOUNCE 장면 기준의 그림일 뿐이다.

object CharacterMotion {
    const val BLINK_PERIOD_MS = 5000L
    const val BLINK_DURATION_MS = 160L
    const val BLINK_MIN = 0.1f

    const val WAVE_CENTER_DEG = 7.5f
    const val WAVE_AMP_DEG = 17.5f
    const val WAVE_PERIOD_MS = 1200L
    const val WAVE_ACTIVE_MS = 2400L
    const val WAVE_FADE_MS = 300L

    /** 홈 화면 숨쉬기(2026-11-07): 4초 주기, 위아래 최대 5dp. 시스템 애니메이션이 꺼져 있으면 쓰지 않는다 */
    const val BREATH_PERIOD_MS = 4000L
    const val BREATH_AMP_DP = 5f
    const val TILT_QUESTION_DEG = 8f
    const val MOUTH_STEP_MS = 250L
}

/** 눈 열림 비율 0.1~1.0. 5000ms 주기의 마지막 160ms에만 1→0.1→1(삼각파), 나머지는 1.0. 음수는 0 취급 */
fun blinkOpenness(tMs: Long): Float {
    val t = if (tMs < 0L) 0L else tMs
    val phase = t % CharacterMotion.BLINK_PERIOD_MS
    val start = CharacterMotion.BLINK_PERIOD_MS - CharacterMotion.BLINK_DURATION_MS
    if (phase < start) return 1f
    val x = (phase - start).toFloat() / CharacterMotion.BLINK_DURATION_MS   // 0..1
    val closeness = 1f - kotlin.math.abs(2f * x - 1f)                        // 0→1→0
    return (1f - (1f - CharacterMotion.BLINK_MIN) * closeness).coerceIn(CharacterMotion.BLINK_MIN, 1f)
}

/** 앞 팔 회전 각도(도) −10~+25. active=false면 0. 처음 2400ms 사인, 이후 300ms 선형 감쇠, 그 뒤 0 */
fun waveAngleDeg(sceneElapsedMs: Long, active: Boolean): Float {
    if (!active) return 0f
    val t = if (sceneElapsedMs < 0L) 0L else sceneElapsedMs
    val v: Float = when {
        t < CharacterMotion.WAVE_ACTIVE_MS -> {
            val rad = 2.0 * Math.PI * (t % CharacterMotion.WAVE_PERIOD_MS).toDouble() / CharacterMotion.WAVE_PERIOD_MS.toDouble()
            (CharacterMotion.WAVE_CENTER_DEG + CharacterMotion.WAVE_AMP_DEG * Math.sin(rad).toFloat())
        }
        t < CharacterMotion.WAVE_ACTIVE_MS + CharacterMotion.WAVE_FADE_MS -> {
            val left = CharacterMotion.WAVE_ACTIVE_MS + CharacterMotion.WAVE_FADE_MS - t
            CharacterMotion.WAVE_CENTER_DEG * left.toFloat() / CharacterMotion.WAVE_FADE_MS
        }
        else -> 0f
    }
    return v.coerceIn(-10f, 25f)
}

/** 머리 기울임(도): QUESTION이면 8, 그 외 0 */
fun headTiltDeg(scene: Scene): Float =
    if (scene == Scene.QUESTION) CharacterMotion.TILT_QUESTION_DEG else 0f

/** 입 모양: 기본은 mouthOf(scene). ANNOUNCE만 250ms 간격으로 SMALL/WIDE 번갈아 */
fun mouthAt(scene: Scene, sceneElapsedMs: Long): MouthShape {
    if (scene != Scene.ANNOUNCE) return mouthOf(scene)
    val t = if (sceneElapsedMs < 0L) 0L else sceneElapsedMs
    return if ((t / CharacterMotion.MOUTH_STEP_MS) % 2L == 0L) MouthShape.SMALL else MouthShape.WIDE
}

/** 장면과 시간으로 자세를 조합한다. 깜빡임은 절대 시간, 손 흔들기·입은 장면 경과 시간 기준 */
fun poseFor(scene: Scene, sceneElapsedMs: Long, absoluteMs: Long): CharacterPose = CharacterPose(
    mouth = mouthAt(scene, sceneElapsedMs),
    eyesOpen = blinkOpenness(absoluteMs),
    waveDeg = waveAngleDeg(sceneElapsedMs, scene == Scene.YES),
    tiltDeg = headTiltDeg(scene),
)
