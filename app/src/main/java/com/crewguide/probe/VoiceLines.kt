package com.crewguide.probe

// 2026-11-13 음성 4종(PM 지시 1): 탑승 환영 · 내릴 때 작별 · 이동 중 교통 상식(아이 대상). 순수 로직(Android 클래스 없음).
// 벨트 풀림 경고음은 Chime.kt. 발화·큐·포커스는 MainActivity(enqueueSay → TtsGuide.speakAuto)가 한다.
// 문구 원칙: 신호 기준·보증 표현 금지(안전합니다/착용 완료/확실/올바르게/모두 안전해요/완료). VoiceLinesTest가 금지어를 검사한다.

object VoiceLines {
    /** 탑승 환영 본문(호칭 뒤). PM 결정 27: 문구는 Greeting.WELCOME_BODY 한 곳 */
    const val WELCOME_BODY = Greeting.WELCOME_BODY
    /** 하차 마무리(보호자 문장) 뒤 아이에게 1회 */
    const val FAREWELL = "오늘도 함께해 줘서 고마워요. 잘 다녀와요!"
    /** 교통 상식 앞머리 */
    const val TIP_PREFIX = "톡톡크루 상식이에요."

    /**
     * 탑승 환영 1회 문장 "{호칭}, 탑승을 환영해요! 오늘도 톡톡크루와 함께 가요."(PM 결정 27). '별명 TTS 읽기'가 켜져 있고 배정된 아이 별명이 있으면
     * "하늘 승객님"(두 명이면 "하늘 승객님, 바다 승객님"), 꺼져 있거나 별명이 없으면 Greeting.SINGLE(여럿이면 Greeting.PLURAL). 호칭 방식 NONE이면 호칭 없이 본문만.
     * 별명이 길면(Greeting.MAX_CODEPOINTS 초과) 총칭으로 줄인다.
     */
    fun welcome(mode: AddressMode, nicknames: List<String>, speakNicknames: Boolean): String {
        if (mode == AddressMode.NONE) return WELCOME_BODY
        val names = nicknames.filter { it.isNotBlank() }
        val call = if (speakNicknames && names.isNotEmpty()) Greeting.greeting(AddressMode.NAMES, names)
            else if (names.size >= 2) Greeting.PLURAL else Greeting.SINGLE
        return Greeting.compose(call.ifEmpty { Greeting.SINGLE }, WELCOME_BODY)
    }

    /**
     * 이동 중 교통 상식(아이 대상, 음성만). 출처·근거는 docs/개발기록/autocheck_2026-11-13/PM/VOICE_SOURCES.md(문장마다 기관·자료·URL·확인 날짜).
     * 2026-11-14 사용자 지시: 30문장 이상, 두 주제(차에 탈 때 / 길에서), 무작위 순서(TripTips가 섞고 VoiceTipStore가 저장).
     *  S1 법제처 생활법령 「가정의 차량 탑승 시 안전수칙」 · S2 현대자동차그룹 「우리 아이의 안전한 카라이프를 위한 팁」
     *  S4 법제처 생활법령 「어린이통학버스에 대한 안전관리」 · S5 법제처 생활법령 「어린이 보행사고 예방을 위한 안전수칙」
     *  S6 법제처 생활법령 「어린이 자전거 탑승 시 안전수칙」 · S7 서울시 교통분야 누리집 「어린이 교통안전, 함께 지켜주세요」
     *  S8 행정안전부 교통사고 국민행동요령(도봉구 재난유형별 행동요령 게재) · S9 정책브리핑 행정안전부 '일이삼사'
     *  S10 정책브리핑 정책기자단(행정안전부 보행안전 수칙) · S11 서울시 내 손안에 서울 · S12 강남구 카드뉴스(개인형 이동장치)
     *  S3 Transport for NSW(원문 열람 실패, 비활성 문장만)
     * 'unverified' = 원문에서 같은 뜻의 문장을 직접 확인하지 못함([출처 미확인]/[부분 확인]). 문장 안에 호칭을 넣지 않는다(결정 27).
     */
    data class Tip(val text: String, val topic: TipTopic, val source: String, val unverified: Boolean = false)

    val TIPS: List<Tip> = listOf(
        // ① 차에 탈 때
        Tip("창문 밖으로 손이나 머리를 내밀지 않아요.", TipTopic.CAR, "S2"),
        Tip("차가 달리는 동안에는 벨트를 맨 채로 자리에 앉아 있어요.", TipTopic.CAR, "S1,S2"),
        Tip("벨트가 꼬여 있거나 불편하면 풀지 말고 어른에게 말해 줘요.", TipTopic.CAR, "S2,S1"),
        Tip("차 문은 차가 멈추고 어른이 괜찮다고 한 뒤에 열어요.", TipTopic.CAR, "S1"),
        Tip("운전대나 기어는 장난으로라도 만지지 않아요.", TipTopic.CAR, "S1"),
        Tip("열세 살까지는 앞자리 말고 뒷자리에 앉아요.", TipTopic.CAR, "S1,S11"),
        Tip("차를 탈 때는 어른 무릎이 아니라 내 자리에 앉아요.", TipTopic.CAR, "S1"),
        Tip("벨트를 매면 버클이 끝까지 잠겼는지 어른과 함께 살펴요.", TipTopic.CAR, "S2"),
        Tip("달리는 차에서는 문 손잡이를 당기지 않아요.", TipTopic.CAR, "S2"),
        Tip("차가 달릴 때는 일어서거나 자리를 옮기지 않아요.", TipTopic.CAR, "S4"),
        Tip("차는 완전히 멈춘 뒤에 타고 내려요.", TipTopic.CAR, "S7"),
        Tip("차에서 내릴 때는 뒤에서 오는 자전거나 차가 없는지 살펴요.", TipTopic.CAR, "S8,S2"),
        Tip("차에서 내릴 때는 서두르지 말고 천천히 내려요.", TipTopic.CAR, "S2"),
        Tip("차에서 내리면 먼저 인도처럼 차가 다니지 않는 곳으로 가요.", TipTopic.CAR, "S4"),
        Tip("어른이 차에서 내릴 때는 혼자 남지 말고 같이 내려요.", TipTopic.CAR, "S1,S2"),
        Tip("햇볕에 오래 있던 차에서는 벨트가 뜨거운지 어른과 먼저 살펴요.", TipTopic.CAR, "S1"),
        // ② 길에서
        Tip("찻길을 건너기 전에는 먼저 멈춰 서요.", TipTopic.ROAD, "S8,S9"),
        Tip("건너기 전에 이쪽저쪽을 3초 동안 살펴요.", TipTopic.ROAD, "S9"),
        Tip("왼쪽, 오른쪽을 보고 차가 멈췄는지 본 뒤에 건너요.", TipTopic.ROAD, "S5"),
        Tip("건너는 동안에도 차를 계속 보면서 걸어요.", TipTopic.ROAD, "S5"),
        Tip("길은 횡단보도로 건너요.", TipTopic.ROAD, "S5,S8"),
        Tip("초록불로 바뀌어도 차가 멈췄는지 먼저 봐요.", TipTopic.ROAD, "S8,S7"),
        Tip("초록불이 깜빡이면 건너기 시작하지 말고 다음 신호를 기다려요.", TipTopic.ROAD, "S8,S11"),
        Tip("길을 건널 때는 손을 들어 운전자에게 내가 보이게 해요.", TipTopic.ROAD, "S7"),
        Tip("운전자와 눈을 맞추고 차가 멈춘 걸 본 뒤에 건너요.", TipTopic.ROAD, "S7"),
        Tip("걸을 때는 인도로 다니고, 인도에서도 안쪽으로 걸어요.", TipTopic.ROAD, "S5,S7,S8"),
        Tip("인도가 없는 길에서는 길 가장자리로 걸어요.", TipTopic.ROAD, "S8"),
        Tip("골목길에서 큰길로 나갈 때는 멈춰서 좌우를 살펴요.", TipTopic.ROAD, "S8"),
        Tip("공놀이는 찻길이 아니라 놀이터나 운동장에서 해요.", TipTopic.ROAD, "S8,S7"),
        Tip("주차된 차 사이나 차 바로 뒤에서는 놀지 않아요.", TipTopic.ROAD, "S7"),
        Tip("공이 찻길로 굴러가면 쫓아가지 말고 어른에게 말해요.", TipTopic.ROAD, "S7"),
        Tip("밤이나 비 오는 날에는 눈에 잘 띄는 밝은 옷을 입어요.", TipTopic.ROAD, "S8,S7,S6"),
        Tip("비 오는 날 우산은 앞이 보이게 똑바로 써요.", TipTopic.ROAD, "S8"),
        Tip("찻길을 건널 때는 어른과 함께 건너요.", TipTopic.ROAD, "S8"),
        Tip("길을 걸을 때는 어른 손을 잡고 걸어요.", TipTopic.ROAD, "S7"),
        Tip("신호등이 없는 횡단보도에서는 차가 완전히 멈춘 뒤에 건너요.", TipTopic.ROAD, "S8"),
        Tip("자전거나 킥보드를 탈 때는 헬멧과 보호대를 해요.", TipTopic.ROAD, "S6"),
        Tip("자전거로 횡단보도를 건널 때는 내려서 끌고 건너요.", TipTopic.ROAD, "S6"),
        Tip("내리막길에서는 자전거에서 내려 걸어가요.", TipTopic.ROAD, "S6"),
        Tip("자전거를 탈 때는 이어폰을 끼지 않아요.", TipTopic.ROAD, "S6"),
        Tip("자전거는 길 오른쪽 가장자리에 붙어서 타요.", TipTopic.ROAD, "S6"),
        Tip("전동킥보드는 만 13세가 될 때까지 타지 않아요.", TipTopic.ROAD, "S12"),
    )

    /**
     * PM 결정 21(2026-11-14): 원문을 확인하지 못한 문장은 원문을 확인할 때까지 쓰지 않는다(TIPS에 넣지 않음).
     * 원문을 확인하면 출처를 고쳐 TIPS로 옮긴다. 이유는 VOICE_SOURCES.md
     */
    val DISABLED_TIPS: List<Tip> = listOf(
        Tip("내릴 때는 차가 다니는 쪽 말고 인도 쪽 문으로 내려요.", TipTopic.CAR, "S3", unverified = true),   // NSW 원문 403, 검색 요약만
        Tip("주차장에서는 어른 손을 잡고 걸어요.", TipTopic.ROAD, "-", unverified = true),                    // 국내 공공 원문 미확인(11-14 재검색)
        // 11-14 PM 임시 결정: 정책기자단·시민기자 글(보조 출처)로만 확인 → 기관 원문을 확인할 때까지 비활성
        Tip("버스를 타고 내릴 때도 왼쪽, 오른쪽을 살펴요.", TipTopic.CAR, "S11", unverified = true),
        Tip("길을 걸을 때는 휴대폰을 보지 않아요.", TipTopic.ROAD, "S10,S11", unverified = true),
    )

    fun tipText(i: Int): String = "$TIP_PREFIX ${TIPS[Math.floorMod(i, TIPS.size)].text}"
}

// 교통 상식 시점 규칙(SafetyTipState·SafetyTipRule)은 2026-11-14 r5 3절에서 SafetyTipRule.kt로 옮겼다.

/**
 * 2026-11-14 지시 3(개발 몫): 말풍선에만 있고 음성이 없던 문장에 음성을 붙인다(대조표 PM/VOICE_BUBBLE_MAP.md). 순수 규칙.
 * 다른 경로가 이미 같은 뜻을 말하는 말풍선(벨트 재안내·확인 완료·도착·모두 내림·하차 한 줄)은 여기서 다시 말하지 않는다.
 * 반복 막기: 홈 인사 = 앱 프로세스당 1회(유효 P·홈이 보일 때), 확인 단계 문장 = 확인 세대당 1회.
 * 2026-11-14 사용자 지시: 이동 끝 말풍선 음성("이번 이동이 끝난 것 같아요. 끝내기를 눌러 주세요", 키 TRIP_END)은 장면과 함께 없앴다.
 */
enum class BubbleVoiceKey { HOME_GREET, PICK_SEAT, NEEDS_GUARDIAN }

object BubbleVoiceRule {
    /** 홈 말풍선과 같은 문장 */
    const val HOME_GREET = HomeText.BUBBLE

    /** 무대 장면·자막 → (키, 말할 문장). 음성을 따로 붙일 말풍선이 아니면 null */
    fun forStage(scene: Scene, caption: String): Pair<BubbleVoiceKey, String>? = when {
        scene == Scene.AUTO_CHECK && caption == SeatCheckRule.TEXT_PICK_SEAT -> BubbleVoiceKey.PICK_SEAT to caption
        scene == Scene.AUTO_CHECK && caption == SeatCheckRule.TEXT_NEEDS_GUARDIAN -> BubbleVoiceKey.NEEDS_GUARDIAN to caption
        else -> null
    }

    /** 반복 막기 키(같은 키는 한 번만 말한다). [scope] = 확인 세대 */
    fun onceKey(key: BubbleVoiceKey, scope: Int): String = if (key == BubbleVoiceKey.HOME_GREET) key.name else "${key.name}:$scope"

    /** 홈 인사: 이번 앱 실행에서 아직 안 했고, 홈이 보이며, 화면이 앞(resumed)이고, 유효 P + 플랫폼 허용일 때만 */
    fun homeGreetDue(saidThisSession: Boolean, homeShown: Boolean, resumed: Boolean, parkAllowed: Boolean): Boolean =
        !saidThisSession && homeShown && resumed && parkAllowed
}

/** 홈 인사를 이번 앱 실행(프로세스)에서 했는가. 화면 재생성(야간 전환)으로 다시 하지 않는다. 메인 스레드 전용 */
object HomeGreetStore {
    var said = false
}

/**
 * 2026-11-14 PM 계약 v3(AutoCheckUi.bubbleLine/bubbleSeq): 승무원이 실제로 말한(발화 요청한) 문장을 말풍선용으로 쌓는 순수 규칙.
 * - 말풍선 문장 = 음성 문장에서 예정 시간 문장("도착까지 약 N분 걸려요.", 결정 14: 화면에 넣지 않음)을
 *   뺀 것. 남는 것이 없으면 쌓지 않는다(루프 r5 1절: 예전 "앱을 열어 하차를 확인해 주세요."는 없앴다).
 * - 같은 문장이 연달아 나오면 순번을 올리지 않는다. 이동마다 0부터, 새 이동·홈 복귀 때 [reset].
 * - 주행 중(앱 뒤·잠금)에 말한 문장도 값은 쌓는다(화면 연출은 디자인이 막는다).
 */
data class BubbleFeed(val line: String = "", val seq: Int = 0)

object BubbleFeedRule {
    private val ETA_TOTAL = Regex("""\s*도착까지 약 \d+분 걸려요\.?""")

    /** 음성 문장 → 말풍선 문장 */
    fun bubbleText(spoken: String): String =
        spoken.replace(ETA_TOTAL, "").replace(Regex(" {2,}"), " ").trim()

    fun onSpoken(f: BubbleFeed, spoken: String): BubbleFeed {
        val t = bubbleText(spoken)
        if (t.isEmpty() || t == f.line) return f
        return BubbleFeed(t, f.seq + 1)
    }

    fun reset(): BubbleFeed = BubbleFeed()
}

/** 말풍선 순번 보관소(프로세스 메모리, 메인 스레드 전용). 화면 재생성(야간 전환)에도 같은 이동이면 이어 간다 */
object BubbleFeedStore {
    var feed = BubbleFeed()
}
