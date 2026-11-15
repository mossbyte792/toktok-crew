package com.crewguide.probe

/**
 * UI/UX v3 단계 1(2026-11-11): [CharacterHost]를 기존 공식 v6.1 PNG 렌더러([CrewPngRenderer])에 연결하는 Sprite 구현.
 *
 * - 어떤 PNG를 그릴지의 기준은 여전히 [CrewArt.forStage](장면·실제 TTS/STT 상태·기어 종류)다. 이 드라이버는 그 결과(base)를 받아
 *   호스트가 고른 표정(CharacterState)과 합치기만 한다. 잠금(DRIVING/INTERRUPTED)과 실제 음성 상태(LISTENING/SPEAKING)는 항상 base가 이긴다.
 * - 공식 PNG는 정지 이미지 6장뿐이라 동작(NOD·WAVE·CHEER 등)은 그리지 않고 마지막 요청만 기록한다(새 그림·합성 금지, v6.1 원본 보존).
 * - 순수 Kotlin: 실제 View는 sink 함수로만 닿는다. sink가 예외를 던져도 호스트가 삼킨다.
 */
object SpriteArtResolver {
    /**
     * 정지 PNG 6장은 표정 상태를 따로 갖지 않으므로 base(CrewArt.forStage)를 그대로 그린다.
     * 2026-11-11 에뮬레이터 회귀 수정: 이전 판의 REST→QUIET는 잠금(TRIP_LOCKED)이 풀린 뒤에도 표정이 남아
     * 고르기(PICK) 화면에 parked 대신 quiet가 그려졌다. 잠금 그림은 이미 base(DRIVING/INTERRUPTED)가 맡으므로 없앴다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolve(base: CrewArtState, state: CharacterState): CrewArtState = base
}

class SpriteCharacterDriver(private val sink: (CrewArtState) -> Unit) : CharacterDriver {
    override val id = "sprite-v61-png"
    var base: CrewArtState = CrewArtState.IDLE; private set
    var state: CharacterState = CharacterState.IDLE; private set
    var lastMotion: CharacterCue = CharacterCue.NONE; private set
    var paused = true; private set
    var drawn: CrewArtState? = null; private set

    override fun load(): Boolean = true

    /** 장면이 정한 기준 그림. 렌더 때마다 부른다 */
    fun setBase(b: CrewArtState) { base = b; push() }

    override fun showState(state: CharacterState) { this.state = state; push() }
    override fun playMotion(motion: CharacterCue) { lastMotion = motion }   // 정지 PNG: 기록만
    override fun setSpeaking(active: Boolean) {}   // 말하는 그림은 실제 TTS 상태(base=SPEAKING)로만 정한다
    override fun setAudioLevel(value: Float) {}
    override fun setReducedMotion(enabled: Boolean) {}
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun release() {}

    private fun push() {
        val next = SpriteArtResolver.resolve(base, state)
        if (next == drawn) return
        drawn = next
        sink(next)
    }
}

/** 무대 장면 전환 → 캐릭터 이벤트(표시 전용). 같은 장면 반복·첫 렌더의 PICK은 이벤트 없음 */
object StageCharacterEvents {
    /**
     * 2026-11-15 r7 임시 결정 51(계약 v7 1절): 보상 표정([GuardianFlowEvent.STICKER_RECORDED])은 스티커 연출과 같은 때 — **하차 안내 화면(ARRIVED)에서
     * [stickerJustGranted]가 새로 true가 될 때** 한 번. 하차 안내는 질문 화면과 같은 ARRIVED 장면이라("예, 도착했어요"·"예, 내려요" 뒤) 장면이 바뀌지 않아도
     * 지급 표시가 false → true로 바뀌면 낸다([prevJust] = 직전 렌더의 값). 모두 내림(ALL_LEFT)에서는 새로 내지 않는다(이동 끝 표정).
     */
    fun of(prev: Scene?, next: Scene, stickerJustGranted: Boolean, prevJust: Boolean): GuardianFlowEvent? {
        if (next == Scene.ARRIVED && stickerJustGranted && !(prev == Scene.ARRIVED && prevJust)) return GuardianFlowEvent.STICKER_RECORDED
        return of(prev, next)
    }

    /** 장면 전환만 보는 판(같은 장면 반복은 이벤트 없음). 하차 안내로 들어오며 이미 지급 표시가 있으면 보상 표정 */
    fun of(prev: Scene?, next: Scene, stickerJustGranted: Boolean): GuardianFlowEvent? =
        if (prev == next) null else of(prev, next, stickerJustGranted, prevJust = false)

    fun of(prev: Scene?, next: Scene): GuardianFlowEvent? {
        if (prev == next) return null
        return when (next) {
            Scene.READY -> if (prev == Scene.PICK) GuardianFlowEvent.SEAT_SELECTED else null
            Scene.QUESTION -> GuardianFlowEvent.CONFIRMATION_REQUESTED
            Scene.YES -> GuardianFlowEvent.GUARDIAN_YES
            Scene.WAIT_GUARDIAN -> GuardianFlowEvent.CONFIRMATION_TIMEOUT
            Scene.LOCKED -> GuardianFlowEvent.TRIP_LOCKED
            Scene.TRIP_END -> GuardianFlowEvent.TRIP_ENDED
            Scene.EXIT_CHECK -> GuardianFlowEvent.EXIT_REMINDER_REQUESTED
            // 2026-11-13 디자인 담당 확정: 자동 확인은 '확인 요청' 표정, 출발 준비는 보호자 '예'가 아니므로 GUARDIAN_YES 대신 좌석 고름(가벼운 반응),
            // 도착은 하차 안내(r7: 스티커 연출이 있으면 4인자 of가 STICKER_RECORDED로 바꾼다), 모두 내림은 이동 끝
            Scene.AUTO_CHECK -> GuardianFlowEvent.CONFIRMATION_REQUESTED
            Scene.DEPART_READY -> GuardianFlowEvent.SEAT_SELECTED
            Scene.ARRIVED -> GuardianFlowEvent.EXIT_REMINDER_REQUESTED
            Scene.ALL_LEFT -> GuardianFlowEvent.TRIP_ENDED
            Scene.PICK, Scene.ANNOUNCE -> null
        }
    }
}
