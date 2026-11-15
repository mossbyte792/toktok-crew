package com.crewguide.probe

/**
 * 2026-11-14 r5 4절(사용자 지시): 설정 '앱 정보·개인정보' 카드 고지 문구 단일 출처. 지금 앱 동작에 맞춰 고쳐 넣고 '검토 중인 초안' 표시는 없앴다.
 * 처음 안내 2/3([FirstRunCopy])와 같은 사실을 짧게 말한다. 문장마다 코드 근거(2026-11-14 확인):
 *  - 뒷줄에만 아이 배정: SeatCheckRule.isAssignable(결정 23). 앞줄 벨트 = 좌석별 값 true면 체결, 아니면 안전벨트 경고등(FrontBeltRule, 결정 22)
 *  - 신호를 못 받는 자리 = [보호자 확인](AutoCheckText.GUARDIAN_BUTTON, 결정 15)
 *  - 저장(모두 MODE_PRIVATE SharedPreferences, allowBackup=false): 별명·부르는 방식(crewguide_probe_profiles·_selection·_tone),
 *    스티커 기록 28일(crewguide_probe_sticker_log, StickerLogs.KEEP_DAYS)과 이동 진행 기록(같은 파일, 이동 번호만), 지난 좌석 이름(_seat_suggestion),
 *    설정(_guidance·_voice_tips·_voice_answer·_character·first_run·toktok_permissions)
 *  - 지우기 버튼 이름: ProfileUi.deleteOneLabel("‘별명’ 삭제")·DELETE_ALL_BTN, StickerLogs.CLEAR_BTN, "지난 좌석 추천 지우기" + SettingsLook.DANGER_MARK
 *  - 앱 코드(com.crewguide.probe)에 네트워크 호출 없음. 병합 Manifest의 INTERNET은 Pleos 음성 SDK가 넣음(FirstRun.kt 머리 주석)
 *  - 음성 인식(마이크): [MIC_LINE] 머리 주석 참고. 2026-11-14 이 브랜치(pm/dev-tips) 코드 기준으로는 앱이 듣기(request)를 시작하는 경로가 없다
 *    (옛 예/아니오 질문 ASKING에만 있고 자동 확인 흐름은 거기에 가지 않음). 사용자 추가 지시 A로 개발 B가 '도착 질문 뒤 5초 듣기'를 붙이므로
 *    고지는 그 동작 기준으로 미리 쓴다(통합 전 이 브랜치만으로는 고지가 동작보다 앞섬 — PM이 개발 B 결과와 맞춰 확정)
 *  - 내비: RouteStartInfo.duration·DestinationInfo.remainTime·DrivingInfo.destination.duration(초)과 길 안내 상태(getRouteStateInfo naviMode, 임시 결정 41 — PM r5 통합 때 추가)만 읽는다. 거리·이름·주소·좌표는 읽지 않는다(NaviTripEnd)
 *  - 알림: 이동 중 foreground service 상시 알림("톡톡크루 이동 안내 중 · 누르면 열려요", TripKeepAliveService) 하나뿐
 */
object PrivacyNotice {
    const val CARD_TITLE = "앱 정보·개인정보"

    /**
     * 마이크 사용 문단(사용자 추가 지시 A·결정 40 사용자 확정 2026-11-15 0시 — 개발 B 결과에 따라 PM이 고치기 쉽게 따로 둔다).
     * 동작(지시 A): 목적지 없이 출발한 이동이 유효 P가 되면 "목적지에 도착했나요?"를 묻고, 질문 음성이 끝난 뒤 5초만(임시 결정 40) Pleos STT로 네/아니요를 듣는다.
     * 버튼으로도 답한다. 결정 40: 기본 켬, 설정 '음성으로 답하기' 스위치(VoiceAnswerSetting, 개발 B가 설정에 추가)로 끈다.
     * 확인한 사실(개발 B·개발 C, SpeechToText 3.3.1 aar·앱 코드):
     *  - 듣기·녹음은 Pleos 음성 서비스(ai.pleos.playground.caas SpeechService, action SPEECH_ON_DEVICE_START)가 한다. 앱은 Mode.ON_DEVICE,
     *    매니페스트에 RECORD_AUDIO 없음(STT_SERVICE만). SDK ON_DEVICE 경로는 AIDL 바인딩뿐이고 통신 코드는 SERVER/HYBRID·registerApp(UA 토큰) 경로에만 있다(앱은 안 씀).
     *  - 앱 코드는 인식 글자를 decideSdkFinalText로 판정만 하고 저장·로그하지 않는다. 단 SDK 내부 SttCallback이 받은 결과를 android.util.Log.d로
     *    logcat에 찍는다(기기 안 시스템 로그, 앱 저장소 아님) — 보고에 적음.
     *  - Pleos 서비스 안에서 서버로 보내는지는 **모름**(inferenceSource 필드는 앱이 받지 않음, SDK가 INTERNET 권한을 붙임).
     *    사용자 결정(11-15 0시, 결정 40 확정): 사용자 예문대로 "전송될 수 있어요"까지만 쓴다("전송되지 않아요"·"전송돼요" 둘 다 쓰지 않음).
     */
    // r6 5절(개발 A, 지시 원문 기준 — A의 실제 문장·조건은 PM이 받는 대로 맞춘다): 목적지 없이 정차 → "목적지에 도착했나요?", 아니요 → "내릴까요?",
    // 길 안내 중 정차 → "잠시 정차해요. 내릴까요?". 질문마다 그 뒤 5초만 듣는다.
    const val MIC_LINE = "주차(P)하면 이렇게 물어요: 목적지 없이 달렸으면 ‘목적지에 도착했나요?’(아니라고 하면 ‘내릴까요?’), 길 안내 중이면 ‘잠시 정차해요. 내릴까요?’. " +
        "질문마다 그 뒤 5초 동안만 ‘네/아니요’를 들어요(버튼으로도 답할 수 있어요). " +
        "음성 인식은 차량(Pleos) 음성 서비스가 처리해요. 이 과정에서 음성이 서버로 전송될 수 있어요. " +
        "톡톡크루 앱은 들은 소리와 글자를 저장하거나 보내지 않아요. 설정의 ‘음성으로 답하기’에서 끌 수 있어요."

    /**
     * r6 4절(개발 D, 지시 원문 기준 — 임시 결정 45·46): 아이가 내렸다고 보려면 그 정차(유효 P) 동안 뒷문(왼쪽·오른쪽 어느 쪽이든)이 한 번이라도
     * 열렸고 그 자리 착석 신호가 비어 있어야 한다. 문 신호를 읽을 수 없으면(미지원·권한 거부·읽기 실패·단절 = 확인 불가) 착석 신호만으로 본다.
     * 문 신호는 Vehicle getDoor() getCurrentDoorPosition·registerDoorPosition(CAR_DOORS 권한, 읽기만). D의 조사·구현 결과는 PM이 받는 대로 맞춘다.
     */
    const val DOOR_LINE = "아이가 내렸는지는 주차 중에 뒷문이 열린 적이 있고 그 자리 착석 신호가 비었는지로 봐요. 문 신호를 받을 수 없으면 착석 신호만 봐요."

    /** r6 6절(개발 E, 지시 원문 기준): 빠른 시작을 위해 지난 이동이 끝난 시각과 끝난 방식만 저장(아이 이름·목적지 없음) */
    const val QUICK_START_SAVED = "빠른 시작을 위한 지난 이동의 번호, 끝난 시각과 끝난 방식(이동 중·중간 하차, 아이 이름·목적지는 없어요)"

    /**
     * r7 1절(개발 A, 임시 결정 48): 스티커는 하차 안내가 나올 때(실제 도착 뒤 유효 P / "예, 도착했어요" / "예, 내려요") 이동당 1회 준다.
     * 아이가 모두 내리면 멘트와 작별 인사만. [되돌리기]로 취소해도 준 스티커는 거두지 않고 같은 이동에서 다시 주지 않는다. 지시 원문 기준.
     */
    const val STICKER_LINE = "참여 스티커는 하차 안내가 나올 때(도착한 뒤 주차했을 때, ‘예, 도착했어요’, ‘예, 내려요’) 이동마다 한 번 줘요. " +
        "대답을 되돌려도 준 스티커는 그대로이고, 같은 이동에서 다시 주지 않아요."

    /**
     * r7 2절(개발 D·디자인 F, 임시 결정 47): 배정한 아이가 **모두** 내려야 자동으로 끝난다. 뒷문이 열린 적이 없으면 보호자가 하차 안내 화면의
     * [다 내렸어요]로 끝낸다(음성 "뒷좌석을 한 번 더 확인해 주세요." + 작별, 기준 줄 "(보호자 확인)"). 버튼 이름은 디자인 F 상수와 맞출 것(PM-INTEGRATE).
     */
    // r10d(사용자 지시 11-15): 출발 전 확인 때 조수석에 앉아 있던 사람도 마무리 대상(ExitDoorRule.FRONT_PASSENGER_TARGET), 운전석은 제외
    val FINISH_LINE = if (ExitDoorRule.FRONT_PASSENGER_TARGET)
        "배정한 아이와, 출발 전 확인 때 조수석에 앉아 있던 사람이 모두 내려야 이동이 저절로 끝나요(운전석은 빼요). 문이 열린 적이 없으면 보호자가 하차 화면의 [다 내렸어요]를 눌러 끝내요(보호자 확인)."
    else "배정한 아이가 모두 내려야 이동이 저절로 끝나요. 뒷문이 열린 적이 없으면 보호자가 하차 화면의 [다 내렸어요]를 눌러 끝내요(보호자 확인)."

    /**
     * r7 5절(개발 E, 임시 결정 50 — 될지 아직 모름): 자동 켜기 고지 한 줄. [AUTO_OPEN_ENABLED_IN_NOTICE]가 true일 때만 고지 목록에 들어간다.
     * 부팅 권한(RECEIVE_BOOT_COMPLETED 등)·마켓 정책 사실은 E 결과가 오면 PM이 넘긴다.
     */
    const val AUTO_OPEN_LINE = "차를 다시 켰을 때 지난 이동이 끝난 지 60분 안이면, 주차(P) 상태에서 ‘눌러서 출발 전 확인을 시작해요’ 알림을 띄워요(차에 따라 앱이 바로 열릴 수도 있어요). 이때 기어·주행 제한·길 안내 중인지만 읽어요. 설정에서 끌 수 있어요."   // PM r7: QA 에뮬에서 앞으로 오기는 BAL_BLOCK, 대체 알림만 동작(임시 결정 50)
    const val AUTO_OPEN_ENABLED_IN_NOTICE = false   // PM r7: 5절을 알림 대체로 release에 넣음(임시 결정 50). r10 B(사용자 결정): 자동 켜기를 꺼서 고지 줄도 숨김

    /**
     * r8b(스위치 SeatCheckRule.GUARDIAN_FOR_UNBUCKLED): 보호자 확인 문단. 스위치가 켜졌을 때만 '벨트 체결 신호가 없는 뒷자리'를 더한다
     * (재안내가 끝난 뒤, 카시트처럼 차량 벨트를 쓰지 않는 자리). 꺼지면 r7까지와 같은 문장.
     */
    val GUARDIAN_LINE: String = if (SeatCheckRule.GUARDIAN_FOR_UNBUCKLED)
        "신호를 받을 수 없는 자리, 그리고 안내 뒤에도 벨트 체결 신호가 없는 뒷자리(카시트처럼 차량 벨트를 쓰지 않는 자리)는 보호자가 직접 보고 [${AutoCheckText.GUARDIAN_BUTTON}]을 눌러요."
    else "신호를 받을 수 없는 자리는 보호자가 직접 보고 [${AutoCheckText.GUARDIAN_BUTTON}]을 눌러요."

    /**
     * r10b(개발 7, I 카시트 저장 — 개발 4 ProfileStore carSeat): 아이별 "차량 벨트를 쓰지 않는 자리(카시트)" 켬/끔. 별명과 같은 기기 저장(MODE_PRIVATE).
     * [CAR_SEAT_IN_NOTICE]를 false로 두면 고지에서 빠진다(I가 빠질 때 r9b 문장으로 되돌림). 고지 버전은 [FirstRunPolicy.VERSION] 4.
     */
    const val CAR_SEAT_IN_NOTICE = true
    const val CAR_SEAT_SAVED = "아이별 ‘차량 벨트를 쓰지 않는 자리(카시트)’ 켬/끔(이 기기에만 저장해요)"

    /**
     * r10(개발 7, M 이동 상태 저장·복원 — TripStateStore·TripRestoreRule): 앱이 갑자기 꺼져도 같은 이동을 이어 가려고 확인 뒤 이동 중 상태를 저장한다.
     * 저장 항목(TripRestoreRule.encode): 이동 번호, 단계, 배정 자리·아이 번호, 보호자 확인 자리, 스티커 지급 여부, 도착·대답 상태, 두고 내림 안내 횟수, 마지막 갱신 시각.
     * 목적지 이름·주소·위치·도착까지 시간·거리는 저장하지 않는다(다시 읽는다). 이동이 끝나면(모두 내림·이동 끝내기·앱 나가기·홈으로) 지우고,
     * 다시 켰을 때 마지막 갱신에서 10분(TripRestoreRule.WINDOW_MS, r10 수정)이 지났으면 쓰지 않고 지운다.
     * [TRIP_STATE_IN_NOTICE]는 TripRestoreRule.ENABLED와 같게 둔다(M이 빠지면 둘 다 false 또는 이 줄을 뺀다). 고지 버전은 4 그대로(4는 아직 배포 전).
     */
    const val TRIP_STATE_IN_NOTICE = true
    const val TRIP_STATE_SAVED = "이동 중 상태(이동 번호·단계·배정 자리와 아이 번호·보호자 확인 자리·스티커 지급 여부·대답 상태·두고 내림 안내 횟수, 목적지·시간은 없어요). " +
        "앱이 갑자기 꺼져도 같은 이동을 이어 가려고 저장하고, 이동이 끝나면 지워요(다시 켰을 때 10분이 지났으면 쓰지 않고 지워요)"

    /** 설정 카드 '이 기기에만 저장해요' 문단. 저장 항목이 바뀌면 여기 한 곳에서 고친다(r10b) */
    val SAVED_LINE: String = "이 기기에만 저장해요: 아이 별명·부르는 방식" +
        (if (CAR_SEAT_IN_NOTICE) "·카시트(차량 벨트를 쓰지 않는 자리) 켬/끔" else "") +
        ", 스티커 기록(28일), 지난 좌석 추천, 지난 이동의 번호·끝난 시각·방식(빠른 시작용, 다음 정상 마무리 때 지워요)" +
        (if (TRIP_STATE_IN_NOTICE) ", 이동 중 상태(이동 번호·단계·배정 자리·보호자 확인 자리·스티커·대답 상태·두고 내림 안내 횟수, 목적지·시간은 없어요. 이동이 끝나면 지워요)" else "") +
        ", 설정. 실명·사진·생년월일·녹음은 받지 않아요."

    val SETTINGS_LINES = listOfNotNull(
        "뒷줄 자리에만 아이를 정하고, 차량이 보내는 착석·벨트·뒷문 신호를 보고 다음 단계로 가요. 앞줄 벨트는 차량 안전벨트 경고등 신호를 기준으로 봐요.",
        DOOR_LINE,
        "$GUARDIAN_LINE 신호는 차량 센서 값이라 실제와 다를 수 있어요. 앱이 벨트 착용을 확인하거나 보장하지 않아요.",   // r8b: 앞 문장은 스위치에 따름
        FINISH_LINE,     // r7 2절(임시 결정 47). 위 세 문단(본문 색, MainActivity i < 3) 뒤에 둔다
        STICKER_LINE,    // r7 1절(임시 결정 48)
        SAVED_LINE,      // r10b: 카시트 켬/끔 추가(CAR_SEAT_IN_NOTICE)
        "톡톡크루가 만든 기능에는 이 기록을 기기 밖으로 보내는 기능이 없어요. 함께 들어 있는 차량 음성 SDK에는 인터넷 권한이 있어, 음성 인식 과정에서 정보가 서버로 전송될 수 있어요.",
        MIC_LINE,
        "내비에서는 도착까지 걸리는 시간 숫자, 길 안내 중인지 여부, 도착·경로 끝 신호만 받아요. 목적지 이름·주소·위치는 읽지 않아요. 알림은 이동 중 안내 표시에만 써요.",
        AUTO_OPEN_LINE.takeIf { AUTO_OPEN_ENABLED_IN_NOTICE },   // r7 5절: 자동 켜기가 들어가면 스위치를 true로
        "지우기: 아이 관리 [✕ ‘별명’ 삭제]·[✕ 모두 지우기], 이번 주 스티커 [✕ 스티커 기록 지우기], 기록·초기화 [✕ 지난 좌석 추천 지우기]. 앱을 지우면 모두 지워져요.",
    )

    /** 처음 안내와 설정 고지 전문(금지어·필수 사실 검사용) */
    fun all(): List<String> = SETTINGS_LINES + FirstRunCopy.all()
}
