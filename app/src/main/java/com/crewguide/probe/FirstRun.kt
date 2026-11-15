package com.crewguide.probe

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UI/UX v2 U0 — 첫 실행 안내(보호자용 3단계).
 *
 * 데이터 흐름 조사(2026-11-11, 코드 기준. 상세: docs/개발기록/2026-11-11_Claude_UIUX_v2_오버나잇.md):
 *  - 아이 별명·호칭 방식·스티커 기록(28일 지나면 지움)·지난 좌석 이름·음성 켜짐/꺼짐·꾸미기·이동 세션 = 이 앱 전용 SharedPreferences(MODE_PRIVATE)
 *  - [정정 2026-11-11] 앱 소스 Manifest에는 INTERNET을 선언하지 않았지만, **병합된 APK에는 `android.permission.INTERNET`이 있다**
 *    (Pleos SpeechToText 3.3.1 SDK의 Manifest가 병합됨 — manifest-merger 보고서). 권한은 지우지 않았다(SDK 동작 영향 미확인).
 *  - 앱이 직접 쓴 코드(com.crewguide.probe)에는 네트워크 호출이 없다(소스 검색). 하지만 APK 안에는 SDK가 넣은
 *    HTTPS 통신 라이브러리(libuacore.so: reqwest·hyper·rustls 문자열)와 서버형 STT 경로(ServerStt·gRPC 문자열)가 함께 들어 있다.
 *    → "앱 전체가 네트워크를 쓰지 않는다"고 쓰지 않는다. SDK 내부가 언제 무엇을 보내는지는 **미확인**.
 *  - 앱은 STT·TTS를 Mode.ON_DEVICE로 만든다. SDK는 이것을 Pleos 시스템 서비스(ai.pleos.playground.caas)에 bindService로 넘긴다.
 *    그 서비스 안에서의 처리 위치(차량 안/밖)는 **미확인** — 기기 안 처리라고 쓰지 않는다.
 *  - 에뮬레이터 1회 관찰(2026-11-11, Pleos Connect v2.1.0, 인터넷 연결 VALIDATED): 질문 TTS 2회 + ON_DEVICE STT 약 10초 동안
 *    앱 UID(u10_a199)와 caas UID의 netstats 바이트 기록 0. 실차·다른 경로(별명 TTS 읽기, 장시간)는 미확인이며 이것으로 "전송 없음"을 단정하지 않는다.
 *  - allowBackup=false. 실명·사진·생년월일·녹음은 받는 칸이 없다
 *  - 음성 답하기(기본 꺼짐)를 켜면 Pleos 음성 인식 서비스가 마이크를 쓴다
 *  - 안내 음성은 Pleos TTS 서비스에 문장을 넘긴다. '별명 TTS 읽기'(기본 꺼짐)를 켜면 그 문장에 별명이 들어간다
 *
 * 2026-11-14 r5 4절(사용자 지시): 고지 문구를 지금 앱 동작에 맞게 고쳐 넣고 '검토 중인 초안' 표시(옛 COPY_APPROVED·DRAFT_NOTE)를 없앴다.
 * 문장별 코드 근거는 [PrivacyNotice] 머리 주석(설정 카드와 같은 사실). 동의 절차는 없고 "확인했어요"로 읽음 기록만 남긴다.
 * 음성 인식: 사용자 추가 지시 A(2026-11-14) — 목적지 없이 다녀온 이동이 P가 되면 "목적지에 도착했나요?" 뒤 5초만 듣는다(결정 40 사용자 확정, 기본 켬, 설정 '음성으로 답하기')(개발 B가 붙임).
 * 고지는 PrivacyNotice.MIC_LINE 한 곳에서 가져온다(SDK 확인 내용은 그 주석).
 * 안전 규칙: 이 화면은 홈과 같은 조건(정차 P 허용·대기)에서만 보인다. 잠금이 생기면 MainActivity가 숨기며, 읽지 않았다고 잠금이 풀리지 않는다.
 */
object FirstRunCopy {
    const val STEP1_TITLE = "톡톡크루를 소개해요"
    // 2026-11-14 루프 r4: 혼자 합쇼체였던 소개 문장을 앱 전체와 같은 해요체로(뜻 그대로)
    const val STEP1_BODY = "톡톡크루는 출발 전 보호자의 확인을 돕고, 아이에게 쉽고 친근하게 안내해요."
    val STEP1_POINTS = listOf(
        // 2026-11-13 ISSUE-02: 옛 흐름(보호자 [예] 보고) → 착석·벨트 신호 자동 진행(신호를 받을 수 없는 자리만 [보호자 확인] 버튼)
        // 2026-11-14 루프 r4: 옛 고르기 흐름 표현 → 자동 배정 흐름(홈 STEPS 1번·STEP3_HOW 2번과 같은 뜻)
        "[시작]을 누르면 뒷자리 착석 신호로 아이 자리를 정하고, 벨트 체결 신호를 보고 다음 단계로 가요.",
        // r8b: 스위치(SeatCheckRule.GUARDIAN_FOR_UNBUCKLED)가 켜지면 '만'을 빼고 미체결 뒷자리를 더한다(꺼지면 지금 문장 그대로)
        (if (SeatCheckRule.GUARDIAN_FOR_UNBUCKLED) "신호를 받을 수 없거나 안내 뒤에도 벨트 체결 신호가 없는 뒷자리는 보호자가 직접 보고 [보호자 확인]을 눌러요."
            else "신호를 받을 수 없는 자리만 보호자가 직접 보고 [보호자 확인]을 눌러요.") + " 신호는 참고 정보이고, 앱이 벨트 착용을 확인하거나 보장하지 않아요.",
        "차가 움직이면 화면을 잠가요. 조작은 주차(P) 상태에서만 할 수 있어요.",
    )

    // r5: 저장만이 아니라 신호·음성·내비·알림 고지도 담아 설정 카드와 같은 제목으로
    const val STEP2_TITLE = PrivacyNotice.CARD_TITLE
    /**
     * v4(2026-11-12): 2단계 첫 화면에 먼저 보이는 요약 3줄. 아래 상세 문구(STEP2_SAVED·NOT_SAVED·VOICE·DELETE)를 줄여 말한 것이며
     * 상세는 그대로 '자세히' 스크롤 영역에 남긴다(필수 정보 누락 없음).
     */
    val STEP2_SUMMARY = listOf(
        "별명·스티커 기록·설정 등은 이 기기에만 저장해요",   // r10 사용자 지시(11-15): '…만 저장'이 실제 저장 항목(빠른 시작·좌석 추천·카시트·이동 상태)보다 좁아 바꿈. 목록은 '자세히'

        "실명·사진·생년월일·목소리 녹음은 받지 않아요",
        "마이크는 주차 때 묻는 질문 뒤 5초만 써요",   // 결정 40(사용자 확정 11-15) + r6 5절 두 질문: 네/아니요(PrivacyNotice.MIC_LINE)
    )
    const val STEP2_DETAIL = "자세히"
    /** r5: '자세히' 안 묶음 제목(예전 화면 코드에 박혀 있던 "저장하지 않는 것"·"음성 기능"을 상수로) */
    const val HEAD_SIGNALS = "자리와 신호"
    const val HEAD_SAVED = "이 기기에 저장하는 것"
    const val HEAD_NOT_SAVED = "받지 않는 것·보내지 않는 것"
    const val HEAD_VOICE = "음성·내비·알림"
    /** r5: 자리·신호 고지(뒷줄 배정 결정 23, 앞줄 경고등 결정 22, 보호자 확인 결정 15) */
    val STEP2_SIGNALS = listOf(
        "아이 자리는 뒷줄에만 정해요. 앞줄 벨트는 차량 안전벨트 경고등 신호를 기준으로 봐요.",
        PrivacyNotice.DOOR_LINE,   // r6 4절: 문 열림 + 착석으로 하차 판단(임시 결정 45·46)
        PrivacyNotice.FINISH_LINE,   // r7 2절: 모두 내려야 자동 끝, 문이 안 열렸으면 [다 내렸어요](임시 결정 47)
        PrivacyNotice.GUARDIAN_LINE,   // r8b: 스위치가 켜졌을 때만 미체결 뒷자리를 더한 문장
        "안내는 차량 신호 기준이에요. 신호는 실제와 다를 수 있고, 앱이 벨트 착용을 확인하거나 보장하지 않아요.",
    ) + listOfNotNull(
        PrivacyNotice.AUTO_OPEN_LINE.takeIf { PrivacyNotice.AUTO_OPEN_ENABLED_IN_NOTICE },   // r7 5절: 자동 켜기 — 스위치가 true일 때만
    )
    val STEP2_SAVED = listOf(
        "아이 별명(최대 4명), 부르는 방식",
    ) + listOfNotNull(
        PrivacyNotice.CAR_SEAT_SAVED.takeIf { PrivacyNotice.CAR_SEAT_IN_NOTICE },   // r10b(I): 아이별 카시트 켬/끔
    ) + listOf(
        "아이별 참여 스티커 기록(28일이 지나면 지워요)과 이동 번호 같은 진행 기록",
        PrivacyNotice.STICKER_LINE,   // r7 1절: 스티커는 하차 안내 때(임시 결정 48)
        "지난번 좌석 추천(좌석 이름), 안내 음성 켜짐/꺼짐 같은 설정",
        PrivacyNotice.QUICK_START_SAVED,   // r6 6절(개발 E)
    ) + listOfNotNull(
        PrivacyNotice.TRIP_STATE_SAVED.takeIf { PrivacyNotice.TRIP_STATE_IN_NOTICE },   // r10(M): 이동 중 상태 저장·복원
    )
    val STEP2_NOT_SAVED = listOf(
        "실명·사진·생년월일·목소리 녹음은 받지 않아요.",
        // 2026-11-11 정정: Pleos SpeechToText 3.3.1 SDK가 android.permission.INTERNET을 병합해 넣는다(manifest-merger 보고서 확인).
        // 이전 문구 "이 앱에는 인터넷 권한이 없어요"는 사실과 달랐다. 앱이 직접 쓴 코드에는 네트워크 호출이 없다(소스 검색 0건).
        // 2차 정정: "앱이 직접 인터넷으로 보내는 기능은 없어요"도 APK 전체로는 넓다(SDK의 통신 라이브러리가 같은 앱 안에 있음) → 저장 위치와 SDK 미확인을 나눠 쓴다
        "위 기록은 모두 이 기기의 앱 안에만 저장돼요. 톡톡크루가 만든 기능에는 이 기록을 기기 밖으로 보내는 기능이 없어요.",
        "다만 앱에 함께 들어 있는 차량 음성 SDK에는 인터넷 권한이 있어, 음성 인식 과정에서 정보가 서버로 전송될 수 있어요.",   // r6a 3절(사용자 예문): '아직 확인하지 못했어요' → '전송될 수 있어요'
    )
    val STEP2_VOICE = listOf(
        PrivacyNotice.MIC_LINE,   // 마이크 문단(사용자 추가 지시 A·결정 40 사용자 확정 11-15)
        "안내 음성은 차량(Pleos) 음성 서비스가 읽어 줘요. ‘${ProfileUi.SPEAK_SWITCH}’를 켜면 안내 문장에 별명이 들어가요. 이 과정에서 안내 문장이 서버로 전송될 수 있어요.",
        "내비에서는 도착까지 걸리는 시간 숫자, 길 안내 중인지 여부, 도착·경로 끝 신호만 받아요. 목적지 이름·주소·위치는 읽지 않아요.",
        "알림은 이동 중 안내가 이어지고 있다는 표시에만 써요.",
    )
    // r5: 버튼 이름은 화면 그대로(지우기 버튼 앞 기호 ✕, 한 명을 고르면 "‘별명’ 삭제")
    const val STEP2_DELETE = "지우는 방법: 설정 › 아이 관리에서 아이를 고르고 [✕ ‘별명’ 삭제] 또는 [✕ 모두 지우기](스티커 기록도 함께), 이번 주 스티커 [✕ 스티커 기록 지우기], 기록·초기화 [✕ 지난 좌석 추천 지우기]. 앱을 지우면 모두 지워져요."

    const val STEP3_TITLE = "어떻게 시작할까요?"
    const val STEP3_BODY = "아이를 등록하면 별명으로 불러 줘요. 등록하지 않아도 바로 쓸 수 있어요."
    /** v4(2026-11-12): 3단계 빈 칸에 '시작 방법' 요약(앱의 실제 순서 그대로, 새 기능 약속 없음) */
    val STEP3_HOW = listOf(
        "홈에서 [시작]을 눌러요",
        "뒷자리 착석 신호로 아이 자리를 정해요",   // 2026-11-14 루프 r3 PM: 자동 배정 흐름에 맞춤(홈 STEPS 1번과 같은 뜻)
        "착석·벨트 신호를 받으면 다음 단계로 가요",
    )
    const val BTN_NEXT = "다음"
    const val BTN_BACK = "이전"
    const val BTN_ACK = "확인했어요"
    const val BTN_REGISTER = "아이 등록하기"
    const val BTN_GUEST = "등록 없이 시작"
    const val BTN_SETTINGS = "설정 먼저 보기"
    /** 2026-11-14 디자인(SCREEN_EXIT_CHECK ⚠️⑧): 설정 [처음 안내 다시 보기]로 연 안내에만 보이는 [닫기] */
    const val BTN_CLOSE = "닫기"
    const val BTN_CLOSE_DESC = "처음 안내 닫기"

    /** 모든 문구(금지어 검사용) */
    fun all(): List<String> = listOf(STEP1_TITLE, STEP1_BODY, STEP2_TITLE, STEP2_DETAIL, STEP2_DELETE, STEP3_TITLE, STEP3_BODY,
        HEAD_SIGNALS, HEAD_SAVED, HEAD_NOT_SAVED, HEAD_VOICE) +
        STEP1_POINTS + STEP3_HOW + STEP2_SUMMARY + STEP2_SIGNALS + STEP2_SAVED + STEP2_NOT_SAVED + STEP2_VOICE
}

/** 첫 실행 안내를 보일지(순수). 홈이 보일 수 있는 조건(정차 허용·대기·다른 화면 없음)일 때만, 아직 확인하지 않았으면 */
object FirstRunPolicy {
    // 문구가 크게 바뀌면 올려 다시 보이게 한다(2: 2026-11-13 ISSUE-02 신호 자동 진행 흐름으로 안내 변경).
    // 2026-11-14 r5 고지 문구 개정 때는 올리지 않았다(PM 판단 대기: 올리면 이미 확인한 사용자에게 처음 안내가 다시 뜬다)
    // 3: 2026-11-15 r7 4절(사용자 지시) — r5·r6 고지 개정(문 열림·정차 질문·전송 가능성·빠른 시작 저장)과 r7(스티커 시점·[다 내렸어요])을 이미 본 사용자에게 한 번 더
    // 4: 2026-11-15 r10b(개발 7) — 저장 항목 추가(I 카시트 켬/끔, r10 M 이동 중 상태). 4는 r10b·r10이 같이 쓴다(배포 전)
    const val VERSION = 4
    fun shouldShow(ackedVersion: Int, homeShowable: Boolean): Boolean = homeShowable && ackedVersion < VERSION
}

/** 확인 기록(버전 숫자 하나). 읽기 실패는 0(다시 보임) */
object FirstRunStore {
    private const val FILE = "first_run"
    private const val KEY = "acked_version"
    fun load(ctx: Context): Int = try { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY, 0) } catch (e: Exception) { 0 }
    fun save(ctx: Context, v: Int): Boolean = try { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, v).commit() } catch (e: Exception) { false }
    fun clear(ctx: Context): Boolean = try { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).commit() } catch (e: Exception) { false }
}

/**
 * 2026-11-14 디자인(SCREEN_EXIT_CHECK ⚠️⑧): [닫기] 표시 규칙(순수). 첫 실행에는 없고(읽기 기록을 남기는 흐름을 끝까지 가야 한다),
 * 설정에서 다시 연 안내(replay)에서만 모든 단계(1~3/3)에 보인다.
 */
object FirstRunClose {
    const val MIN_WIDTH_DP = 160   // 글자 두 자 버튼이 너무 작아 보이지 않게(높이는 DuoButton 76dp)
    fun visible(replay: Boolean): Boolean = replay
}

/**
 * 3단계 안내 화면(홈 위에 덮음). 단계는 메모리에만 두고, 재생성 때는 [step]을 MainActivity가 저장·복원한다.
 * 다시 보기인지는 [show]`(step, replay)`로 넘긴다. 다시 보기이면 카드 맨 위 줄에 단계 표시와 [닫기]를 두고, [닫기]는 [onClose]를 부른다.
 */
class FirstRunScreen(
    context: Context,
    private val onAck: () -> Unit,
    private val onRegister: () -> Unit,
    private val onGuest: () -> Unit,
    private val onSettings: () -> Unit,
    /** 다시 보기의 [닫기] 탭(개발: MainActivity가 endFirstRun + 홈/설정 복귀로 연결). 첫 실행에는 버튼이 없어 불리지 않는다 */
    private val onClose: () -> Unit = {},
) : FrameLayout(context) {
    var step = 0
        private set
    /** 설정 [처음 안내 다시 보기]로 열었는지. [show]`(step, replay)`로만 바꾼다(단계 이동 [이전]·[다음]에는 그대로) */
    var replay = false
        private set
    private val roles = DuoTokens.roles(Palette.current.night)
    private val card = DuoCard(context)
    private val stepView = TextView(context)
    /** 다시 보기 전용 맨 위 줄: [처음 안내 n / 3 ........ [닫기]]. 첫 실행에는 GONE(배치가 예전과 같다) */
    private val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private lateinit var closeButton: DuoButton
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val row = LinearLayout(context)
    private lateinit var scroll: ScrollView
    private fun dp(v: Int) = DuoDraw.dp(context, v)

    init {
        setBackgroundColor(Palette.argb(roles.bg))
        isClickable = true   // 아래 홈으로 터치가 새지 않게
        row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        val art = ImageView(context).apply {
            val id = resources.getIdentifier(CrewArtAsset.WAVE.resName, "drawable", context.packageName)
            if (id != 0) setImageResource(id)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stepView.textSize = DuoTokens.CAPTION_SP; stepView.typeface = Typeface.DEFAULT_BOLD
        stepView.setTextColor(Palette.argb(roles.stateOnText))
        // ISSUE-03(2026-11-13): 1/3·3/3처럼 내용이 짧으면 카드 아래가 크게 비었다(특히 키 큰 창). 단계 표시를 내용 묶음 맨 위로 옮기고,
        // 내용 묶음을 버튼 위 남는 칸의 세로 가운데에 둔다(fillViewport). 버튼 자리는 단계마다 그대로(맨 아래). 내용이 길면(2/3, 글꼴 2.0) 예전처럼 스크롤.
        content.gravity = Gravity.CENTER_VERTICAL
        scroll = ScrollView(context).apply {
            isFillViewport = true
            isScrollbarFadingEnabled = false   // 넘칠 때 스크롤 막대를 계속 보여 '아래에 더 있음'을 알린다(글꼴 2.0 캡처에서 줄이 반쯤 잘려 보임)
            addView(content)
        }
        closeButton = button(FirstRunCopy.BTN_CLOSE, DuoTokens.Kind.NEUTRAL) { if (replay) onClose() }.apply {
            contentDescription = FirstRunCopy.BTN_CLOSE_DESC
            val w = dp(FirstRunClose.MIN_WIDTH_DP); minWidth = w; minimumWidth = w
        }
        card.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        // V6 S07: 왼쪽 안내 카드(1fr) | 오른쪽 둥근 무대 패널(520) 안에 캐릭터(표시만)
        val night = Palette.current.night
        card.background = V6Panels.card(resources.displayMetrics.density, night)
        row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.68f))
        val stage = FrameLayout(context).apply {
            background = V6Panels.stagePanel(resources.displayMetrics.density, night)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(art, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        row.addView(stage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.32f).apply { leftMargin = dp(V6Tokens.SPACE_XXL_DP) })
        val m = dp(40)
        // ISSUE-03: 높이는 onMeasure가 내용에 맞춰 줄일 수 있다(FirstRunFit). 줄이면 화면 세로 가운데
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL).apply { setMargins(m, m, m, m) })
        show(0)
    }

    /**
     * ISSUE-03: 내용이 짧으면(1/3·3/3, 키 큰 창) 카드·무대 줄의 높이를 '내용이 다 들어가는 높이'로 줄인다(하한 = 쓸 수 있는 높이의 FirstRunFit.MIN_FRACTION).
     * 내용이 길면(2/3, 글꼴 2.0) 그대로 꽉 채우고 카드 안에서 스크롤한다. 배치만 바꾸며 문구·버튼 순서는 그대로다.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val avail = row.measuredHeight
        if (avail <= 0 || scroll.measuredWidth <= 0) return
        content.measure(MeasureSpec.makeMeasureSpec(scroll.measuredWidth - scroll.paddingLeft - scroll.paddingRight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val natural = card.measuredHeight - scroll.measuredHeight + content.measuredHeight
        val want = FirstRunFit.rowHeight(natural, avail)
        if (want < avail) {
            row.measure(MeasureSpec.makeMeasureSpec(row.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(want, MeasureSpec.EXACTLY))
        }
    }

    private fun bullet(t: String): TextView = DuoText.body(context, "• $t").apply { setPadding(0, dp(6), 0, dp(6)) }
    private fun head(t: String): TextView = DuoText.title(context, t).apply { typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE); setPadding(0, dp(8), 0, dp(12)) }
    private fun sub(t: String): TextView = DuoText.body(context, t).apply { typeface = Typeface.DEFAULT_BOLD; setTextColor(Palette.argb(roles.stateOnText)); setPadding(0, dp(16), 0, dp(4)) }

    /** 요약 칸: 보조 버튼 채움색 바탕 + 둥근 모서리, 굵은 본문 줄(번호 기호로 색 없이도 구분) */
    private fun summaryBox(lines: List<String>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = DuoDraw.round(roles.neutralFill, dp(DuoTokens.BUTTON_RADIUS_DP).toFloat())
        val pad = dp(20)
        setPadding(pad, dp(12), pad, dp(12))
        lines.forEachIndexed { i, t ->
            addView(DuoText.body(context, "${i + 1}. $t").apply {
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Palette.argb(roles.neutralText)); setPadding(0, dp(6), 0, dp(6))
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun button(label: String, kind: DuoTokens.Kind, onTap: () -> Unit): DuoButton =
        DuoButton(context, kind).apply { text = label; setOnClickListener { onTap() } }

    /** 단계와 다시 보기 여부를 함께 정한다(MainActivity의 syncFirstRun이 부른다: `show(start, firstRunReplay)`) */
    fun show(s: Int, replay: Boolean) {
        this.replay = replay
        show(s)
    }

    fun show(s: Int) {
        step = s.coerceIn(0, 2)
        content.removeAllViews(); buttons.removeAllViews()
        stepView.text = "처음 안내 ${step + 1} / 3"
        (stepView.parent as? ViewGroup)?.removeView(stepView)
        header.removeAllViews()
        if (FirstRunClose.visible(replay)) {
            // 다시 보기: 단계 표시를 맨 위 줄 왼쪽으로 올리고 오른쪽에 [닫기](내용 칸 높이를 [닫기] 줄만큼만 덜 쓴다)
            header.addView(stepView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
            header.visibility = View.VISIBLE
        } else {
            header.visibility = View.GONE
            content.addView(stepView)   // ISSUE-03: 단계 표시는 제목 바로 위(내용 묶음과 함께 가운데로)
        }
        val lp = { w: Float -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { setMargins(dp(8), 0, dp(8), 0) } }
        when (step) {
            0 -> {
                content.addView(head(FirstRunCopy.STEP1_TITLE))
                content.addView(DuoText.body(context, FirstRunCopy.STEP1_BODY))
                // v4: 핵심 세 가지를 옅은 칸으로 묶어 먼저 읽히게(문구는 v3 그대로)
                content.addView(summaryBox(FirstRunCopy.STEP1_POINTS).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
                buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))   // 빈 칸: 높이 1(WRAP이면 세로를 다 차지한다)
                buttons.addView(button(FirstRunCopy.BTN_NEXT, DuoTokens.Kind.PRIMARY) { show(1) }, lp(1f))
            }
            1 -> {
                content.addView(head(FirstRunCopy.STEP2_TITLE))
                // v4: 핵심 3줄을 먼저(옅은 칸), 상세는 그 아래 '자세히'로 스크롤
                content.addView(summaryBox(FirstRunCopy.STEP2_SUMMARY))
                content.addView(sub(FirstRunCopy.STEP2_DETAIL))
                content.addView(sub(FirstRunCopy.HEAD_SIGNALS))
                for (p in FirstRunCopy.STEP2_SIGNALS) content.addView(bullet(p))
                content.addView(sub(FirstRunCopy.HEAD_SAVED))
                for (p in FirstRunCopy.STEP2_SAVED) content.addView(bullet(p))
                content.addView(sub(FirstRunCopy.HEAD_NOT_SAVED))
                for (p in FirstRunCopy.STEP2_NOT_SAVED) content.addView(bullet(p))
                content.addView(sub(FirstRunCopy.HEAD_VOICE))
                for (p in FirstRunCopy.STEP2_VOICE) content.addView(bullet(p))
                content.addView(DuoText.caption(context, FirstRunCopy.STEP2_DELETE).apply { setPadding(0, dp(12), 0, 0) })
                buttons.addView(button(FirstRunCopy.BTN_BACK, DuoTokens.Kind.NEUTRAL) { show(0) }, lp(1f))
                buttons.addView(button(FirstRunCopy.BTN_ACK, DuoTokens.Kind.PRIMARY) { onAck(); show(2) }, lp(1f))
            }
            else -> {
                content.addView(head(FirstRunCopy.STEP3_TITLE))
                content.addView(DuoText.body(context, FirstRunCopy.STEP3_BODY))
                content.addView(summaryBox(FirstRunCopy.STEP3_HOW).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })
                buttons.addView(button(FirstRunCopy.BTN_SETTINGS, DuoTokens.Kind.NEUTRAL) { onSettings() }, lp(1f))
                buttons.addView(button(FirstRunCopy.BTN_GUEST, DuoTokens.Kind.ACCENT) { onGuest() }, lp(1f))
                buttons.addView(button(FirstRunCopy.BTN_REGISTER, DuoTokens.Kind.PRIMARY) { onRegister() }, lp(1f))
            }
        }
    }
}

/** ISSUE-03(2026-11-13): 첫 실행 안내 카드 줄 높이(순수, px). 내용 높이만큼만 쓰되 하한은 쓸 수 있는 높이의 [MIN_FRACTION], 상한은 쓸 수 있는 높이 */
object FirstRunFit {
    const val MIN_FRACTION = 0.6f
    fun rowHeight(naturalPx: Int, availPx: Int): Int {
        if (availPx <= 0) return availPx
        val min = (availPx * MIN_FRACTION).toInt()
        return naturalPx.coerceIn(min, availPx)
    }
}

/** 2026-11-11 U5: 재생성 복원 계획(순수). 화면 위치만 복원하고 보호자 확인 진행은 복원하지 않는다 */
object UiRestore {
    const val KEY_SETTINGS = "ui_settings_open"
    const val KEY_FIRST_RUN_STEP = "ui_first_run_step"
    const val KEY_FIRST_RUN_FINISHING = "ui_first_run_finishing"
    const val KEY_FIRST_RUN_REPLAY = "ui_first_run_replay"
    const val SETTINGS_WINDOW_MS = 10_000L
    /** v3(2026-11-11, P2): 설정 안 하위 접이식 펼침 상태(비트: 1 = 부르는 방식·호칭, 2 = 이번 주 스티커). 재생성 때만 넘긴다 */
    const val KEY_FOLDS = "ui_settings_folds"
    const val FOLD_SUB = 1
    const val FOLD_STICKER = 2
    fun foldsMask(subOpen: Boolean, stickerOpen: Boolean): Int = (if (subOpen) FOLD_SUB else 0) or (if (stickerOpen) FOLD_STICKER else 0)
    /** 설정을 다시 열 때만 펼침을 복원한다. 설정을 다시 열지 않으면 0(아무것도 펼치지 않음) */
    fun foldsToRestore(reopenSettings: Boolean, mask: Int): Int = if (reopenSettings) mask and (FOLD_SUB or FOLD_STICKER) else 0

    /**
     * v4.1(2026-11-12): 홈을 떠나 무대(좌석·이동 화면)에 있었는지. 구성 변경 재생성(야간 전환·글꼴 배율) 때만 넘긴다.
     * 원인: 야간 전환이 Activity를 다시 만들면 onResume의 showHome()이 무조건 홈으로 돌려, 좌석 화면에 있던 보호자가 홈으로 튕겼다(에뮬 재현).
     * 복원은 '화면 위치'만이다. 보호자 확인 진행은 복원하지 않고(IDLE로 다시 묻기), 잠금·이동 끝 장면은 무대 렌더 규칙이 그대로 앞선다.
     */
    const val KEY_ON_STAGE = "ui_on_stage"

    /** 재생성 직후 첫 onResume에서 홈 대신 무대를 유지할지: 구성 변경 재생성이었고, 저장 시점에 홈이 아니었고, 설정 복원이 아닐 때만 */
    fun keepStageAfterRecreate(changingConfig: Boolean, wasOnStage: Boolean, reopenSettings: Boolean): Boolean =
        changingConfig && wasOnStage && !reopenSettings

    data class Plan(val reopenSettings: Boolean, val firstRunStep: Int)

    /** 설정이 열려 있었으면 다시 연다(조건 확인은 호출자). 첫 안내 단계는 0..2만 인정, 그 밖은 -1(처음부터) */
    fun plan(settingsWasOpen: Boolean, firstRunStep: Int): Plan =
        Plan(settingsWasOpen, if (firstRunStep in 0..2) firstRunStep else -1)
}
