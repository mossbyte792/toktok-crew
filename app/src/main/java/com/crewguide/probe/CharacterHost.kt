package com.crewguide.probe

/**
 * UI/UX v3 §17·§21: 캐릭터 렌더러 추상화(순수 Kotlin, Android·Live2D SDK 의존 없음).
 *
 * 기존 `CharacterRenderer`(CharacterRenderer.kt: View·스타일·자세 접점)는 그대로 둔다. 이 파일의 [CharacterDriver]는 그 위의
 * 생명주기·표정·말하기 계층이다(지시서 §17.2의 CharacterRenderer 계약을 이름 충돌 없이 옮김). Sprite 구현은 기존 CharacterRenderer를 감싼다.
 *
 * 상태(2026-11-11): **설계 + 순수 로직만.** 화면에는 아직 연결하지 않았다(기존 MascotView/CrewArtView가 그대로 그린다).
 * Live2D Cubism SDK for Java는 저장소에 없고 라이선스 확인 전이라, [Live2DUnavailable]은 항상 “사용 불가”를 돌려준다.
 *
 * 규칙
 * - 캐릭터는 이벤트의 **결과**만 표현한다. 렌더러·애니메이션·음성 완료 콜백이 보호자 확인·스티커·잠금 상태를 바꾸지 못한다
 *   (이 파일의 어떤 함수도 그런 상태를 인자로 돌려주지 않는다).
 * - 움직임은 유효한 P + 플랫폼 제한 없음 + 화면 보임 + 동작 줄이기 꺼짐일 때만. 하나라도 아니면 정지 그림.
 * - Live2D 로딩 실패는 Sprite로 자동 복귀하며 흐름을 멈추지 않는다.
 */
enum class CharacterState { IDLE, WELCOME, ASKING, LISTENING, HAPPY, REWARD, GOODBYE, REST }

enum class CharacterCue { NONE, BREATH, BLINK, WAVE, NOD, CHEER }

/** §17.2 렌더러 계약. 구현체는 예외를 밖으로 던지지 않고 false/무시로 처리해야 한다 */
interface CharacterDriver {
    val id: String
    fun load(): Boolean
    fun showState(state: CharacterState)
    fun playMotion(motion: CharacterCue)
    fun setSpeaking(active: Boolean)
    /** 0.0~1.0 음량(입 벌림용). 범위 밖은 잘라 쓴다 */
    fun setAudioLevel(value: Float)
    fun setReducedMotion(enabled: Boolean)
    fun pause()
    fun resume()
    fun release()
}

/** Live2D SDK가 없을 때의 자리표시. load()가 항상 false → 호스트가 Sprite로 복귀한다 */
class Live2DUnavailable : CharacterDriver {
    override val id = "live2d-unavailable"
    override fun load() = false
    override fun showState(state: CharacterState) {}
    override fun playMotion(motion: CharacterCue) {}
    override fun setSpeaking(active: Boolean) {}
    override fun setAudioLevel(value: Float) {}
    override fun setReducedMotion(enabled: Boolean) {}
    override fun pause() {}
    override fun resume() {}
    override fun release() {}
}

/** 움직임 허용 수준: 정지 그림 / 작은 움직임(숨쉬기·깜빡임) / 한 번 반응(인사·보상) */
enum class MotionLevel { STILL, SUBTLE, ONE_SHOT }

object CharacterCuePolicy {
    /**
     * @param parkConfirmed 유효한 P를 받았는가(불명·단절은 false)
     * @param uxRestricted 플랫폼 주행 제한(CarUxRestrictions)이 걸렸는가. 알 수 없으면 true로 넘긴다
     */
    fun level(parkConfirmed: Boolean, uxRestricted: Boolean, visible: Boolean, reducedMotion: Boolean, wantsOneShot: Boolean): MotionLevel {
        if (!parkConfirmed || uxRestricted || !visible || reducedMotion) return MotionLevel.STILL
        return if (wantsOneShot) MotionLevel.ONE_SHOT else MotionLevel.SUBTLE
    }

    /** 수준에서 허용되지 않는 동작은 NONE으로 바꾼다 */
    fun filter(m: CharacterCue, level: MotionLevel): CharacterCue = when (level) {
        MotionLevel.STILL -> CharacterCue.NONE
        MotionLevel.SUBTLE -> if (m == CharacterCue.BREATH || m == CharacterCue.BLINK) m else CharacterCue.NONE
        MotionLevel.ONE_SHOT -> m
    }

    /** RMS(0~1) → 입 벌림(0~1). 작은 잡음은 0, 0.6 이상은 1. 말하는 중이 아니면 0 */
    fun mouthOpen(rms: Float, speaking: Boolean): Float {
        if (!speaking || rms.isNaN()) return 0f
        val r = rms.coerceIn(0f, 1f)
        if (r < 0.05f) return 0f
        return ((r - 0.05f) / 0.55f).coerceIn(0f, 1f)
    }
}

/** §21 공통 이벤트 */
enum class GuardianFlowEvent {
    SEAT_SELECTED, CONFIRMATION_REQUESTED, GUARDIAN_YES, GUARDIAN_NO, CONFIRMATION_TIMEOUT, TRIP_LOCKED,
    CHILD_ANNOUNCEMENT_STARTED, CHILD_ANNOUNCEMENT_FINISHED, STICKER_RECORDED, EXIT_REMINDER_REQUESTED, TRIP_ENDED,
}

object CharacterEventMap {
    /** 이벤트 → 캐릭터 표정·동작(표시 전용). 음성 시작/끝은 표정을 바꾸지 않고 setSpeaking만 바꾼다(null) */
    data class Reaction(val state: CharacterState, val motion: CharacterCue)

    fun reactionOf(e: GuardianFlowEvent): Reaction? = when (e) {
        GuardianFlowEvent.SEAT_SELECTED -> Reaction(CharacterState.WELCOME, CharacterCue.NOD)
        GuardianFlowEvent.CONFIRMATION_REQUESTED -> Reaction(CharacterState.ASKING, CharacterCue.NONE)
        GuardianFlowEvent.GUARDIAN_YES -> Reaction(CharacterState.HAPPY, CharacterCue.NOD)
        GuardianFlowEvent.GUARDIAN_NO -> Reaction(CharacterState.ASKING, CharacterCue.NONE)   // 차분한 재질문(축하·실망 표정 없음)
        GuardianFlowEvent.CONFIRMATION_TIMEOUT -> Reaction(CharacterState.IDLE, CharacterCue.NONE)
        GuardianFlowEvent.TRIP_LOCKED -> Reaction(CharacterState.REST, CharacterCue.NONE)
        GuardianFlowEvent.STICKER_RECORDED -> Reaction(CharacterState.REWARD, CharacterCue.CHEER)
        GuardianFlowEvent.EXIT_REMINDER_REQUESTED -> Reaction(CharacterState.GOODBYE, CharacterCue.WAVE)
        GuardianFlowEvent.TRIP_ENDED -> Reaction(CharacterState.IDLE, CharacterCue.NONE)
        GuardianFlowEvent.CHILD_ANNOUNCEMENT_STARTED, GuardianFlowEvent.CHILD_ANNOUNCEMENT_FINISHED -> null
    }

    /** 한 번 반응(ONE_SHOT)이 필요한 동작인지 */
    fun isOneShot(m: CharacterCue): Boolean = m == CharacterCue.WAVE || m == CharacterCue.NOD || m == CharacterCue.CHEER
}

/**
 * 렌더러 선택·폴백 + 정책 적용. 기본 렌더러(sprite)는 항상 있어야 한다.
 * preferred(예: Live2D)가 load에 실패하거나 예외를 던지면 sprite로 바꾸고 [fallbackReason]에 남긴다.
 */
class CharacterHost(private val sprite: CharacterDriver, preferred: CharacterDriver? = null) {
    var active: CharacterDriver = sprite
        private set
    var fallbackReason: String? = null
        private set
    private var parkConfirmed = false
    private var uxRestricted = true
    private var visible = false
    private var reducedMotion = false

    init {
        if (preferred != null) {
            val ok = try { preferred.load() } catch (e: Exception) { false }
            if (ok) active = preferred else {
                fallbackReason = "${preferred.id} load 실패 → ${sprite.id}"
                try { preferred.release() } catch (e: Exception) {}
            }
        }
        if (active === sprite) try { sprite.load() } catch (e: Exception) {}
    }

    fun level(oneShot: Boolean) = CharacterCuePolicy.level(parkConfirmed, uxRestricted, visible, reducedMotion, oneShot)

    /** 차량·화면 조건이 바뀔 때마다 부른다. 움직임이 막히면 즉시 멈추고 말하는 입 모양도 닫는다 */
    fun updateConditions(parkConfirmed: Boolean, uxRestricted: Boolean, visible: Boolean, reducedMotion: Boolean) {
        this.parkConfirmed = parkConfirmed; this.uxRestricted = uxRestricted
        this.visible = visible; this.reducedMotion = reducedMotion
        safe { active.setReducedMotion(reducedMotion) }
        if (level(false) == MotionLevel.STILL) {
            safe { active.setSpeaking(false) }
            safe { active.pause() }
        } else safe { active.resume() }
    }

    fun onEvent(e: GuardianFlowEvent) {
        if (e == GuardianFlowEvent.CHILD_ANNOUNCEMENT_STARTED) { safe { active.setSpeaking(level(false) != MotionLevel.STILL) }; return }
        if (e == GuardianFlowEvent.CHILD_ANNOUNCEMENT_FINISHED) { safe { active.setSpeaking(false) }; return }
        val r = CharacterEventMap.reactionOf(e) ?: return
        safe { active.showState(r.state) }
        val m = CharacterCuePolicy.filter(r.motion, level(CharacterEventMap.isOneShot(r.motion)))
        if (m != CharacterCue.NONE) safe { active.playMotion(m) }
    }

    fun onAudioLevel(rms: Float, speaking: Boolean) {
        val v = if (level(false) == MotionLevel.STILL) 0f else CharacterCuePolicy.mouthOpen(rms, speaking)
        safe { active.setAudioLevel(v) }
    }

    fun release() { safe { active.release() } }

    private inline fun safe(block: () -> Unit) { try { block() } catch (e: Exception) {} }
}
