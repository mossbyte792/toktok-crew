package com.crewguide.probe

// 아이 프로필 2단계: 화면 연결용 순수 로직(Android 클래스 없음).
// 2026-11-14 사용자 지시: 화면 제목의 "(가안, 시연용)"·"(가안)" 표기를 지웠다(release 화면이 보이던 글자와 같다).
// 이 규칙은 개인정보 준수를 보장하지 않는다. 별명 칸 외에는 입력 칸이 없다.

object ProfileUi {
    const val SECTION_TITLE = "누가 탔나요?"
    const val STORE_NOTE = "아이 이름(별명)은 이 기기의 앱에만 저장돼요. 지우기를 누르면 지워져요."
    const val EMPTY_NOTE = "아직 등록한 아이가 없어요. 등록하지 않아도 지금처럼 쓸 수 있어요"
    const val BUSY_NOTE = "보호자 확인 중에는 바꿀 수 없어요."
    const val REGISTER_BTN = "새 아이 등록"
    const val DELETE_ONE_BTN = "선택한 아이 삭제"
    const val DELETE_ALL_BTN = "모두 지우기"
    const val LAST_BTN = "지난번 아이 그대로"
    const val LAST_MARK = "지난번"
    const val NICK_HINT = "별명(최대 8자)"
    const val NICK_GUIDE = "실명·사진·생년월일은 입력하지 마세요(별명만 써요)"
    const val SUBMIT_BTN = "등록"
    const val CANCEL_BTN = "취소"
    const val CONFIRM_TEXT = "정말 지울까요?"
    const val CONFIRM_OK_BTN = "지우기"
    const val MODE_LABEL = "부르는 방식"
    const val MODE_NAMES = "이름 부르기"
    const val MODE_GENERIC = Greeting.SINGLE   // PM 결정 27: 총칭 호칭 그대로(예전 "얘들아")
    const val MODE_NONE = "부르지 않기"
    const val SPEAK_SWITCH = "아이 이름 불러주기(음성)"
    const val SPEAK_NOTE = "켜면 안내 음성이 별명을 읽어요. 발음이 어색할 수 있어요"
    const val PREVIEW_SENTENCE = UserTexts.CHILD_NOTICE_A
    const val MSG_INVALID = "별명을 다시 써 주세요(숫자를 길게 쓰거나 @가 들어가면 안 돼요)"
    const val MSG_FULL = "아이는 최대 4명까지 등록할 수 있어요"

    const val EDIT_BTN = "선택한 아이 수정"
    const val SAVE_BTN = "저장"
    // 2026-11-10 4차 결정 A: 꾸미기·말투 고르기는 Release에서 쓸 수 없으므로 실제로 바뀌는 것(별명)만 알린다
    const val EDIT_NOTE = "별명만 바뀌어요. 스티커 기록은 그대로예요"

    const val SUB_TITLE = "부르는 방식·호칭 미리보기"
    const val COLLAPSED_NONE = "등록한 아이 없음"
    const val COLLAPSED_PICK = "아이 고르기"

    /** 접힌 제목 줄 요약: 등록 없음 / 등록했지만 선택 없음 / 아이 N명·확인 좌석 M석(summaryLabel 재사용) */
    fun collapsedSummary(registeredCount: Int, selectedCount: Int, seatCount: Int): String = when {
        registeredCount <= 0 -> COLLAPSED_NONE
        selectedCount <= 0 -> COLLAPSED_PICK
        else -> ChildSelections.summaryLabel(selectedCount, seatCount)
    }

    /** 제목 줄 전체 문구. 접힘이면 '▶ 제목 · 요약', 펼침이면 '▼ 제목' */
    fun titleLine(expanded: Boolean, registeredCount: Int, selectedCount: Int, seatCount: Int): String =
        if (expanded) "▼ $SECTION_TITLE"
        else "▶ $SECTION_TITLE · " + collapsedSummary(registeredCount, selectedCount, seatCount)

    /** 펼침 여부: 보호자가 눌러 펼쳤거나, 입력 패널이 열려 있는 동안은 펼침 유지 */
    fun bodyExpanded(open: Boolean, inputOpen: Boolean): Boolean = open || inputOpen

    /** 수정 버튼은 선택된 카드가 정확히 1개이고 조작이 가능할 때만 활성 */
    fun canEdit(validP: Boolean, idle: Boolean, sel: ChildSelection, book: ProfileBook): Boolean =
        controlsEnabled(validP, idle) && canDeleteOne(sel, book)

    /** 수정 저장 검증: 별명 거부는 INVALID(가득 참 규칙은 쓰지 않는다) */
    fun checkEdit(raw: String?): RegisterCheck =
        if (ChildProfiles.sanitizeNickname(raw) == null) RegisterCheck.INVALID else RegisterCheck.OK

    /**
     * 수정 저장: 별명만 갱신한 새 book. 대상이 없거나 별명이 거부되면 null.
     * 2026-11-10 4차 결정 A·B: 이 아이의 꾸밈(style)·말투(tone) 저장값은 그대로 둔다(기기 현재 값으로 덮어쓰지 않음 →
     * 저장된 B 말투·꾸밈이 별명 수정만으로 바뀌거나 지워지지 않는다). id·스티커·지난번 기록도 그대로.
     */
    fun applyEdit(book: ProfileBook, id: Int, raw: String?): ProfileBook? {
        val old = book.find(id) ?: return null
        val nick = ChildProfiles.sanitizeNickname(raw) ?: return null
        return book.update(old.copy(nickname = nick))
    }

    enum class RegisterCheck { OK, INVALID, FULL }

    /** 등록 입력 검증 결과. 가득 찬 경우가 먼저, 별명 거부는 sanitizeNickname 결과가 null일 때 */
    fun checkRegister(raw: String?, book: ProfileBook): RegisterCheck = when {
        book.isFull() -> RegisterCheck.FULL
        ChildProfiles.sanitizeNickname(raw) == null -> RegisterCheck.INVALID
        else -> RegisterCheck.OK
    }

    /** OK면 null(안내 없음) */
    fun registerMessage(c: RegisterCheck): String? = when (c) {
        RegisterCheck.OK -> null
        RegisterCheck.INVALID -> MSG_INVALID
        RegisterCheck.FULL -> MSG_FULL
    }

    /** 삭제 확인 줄 상태 */
    enum class DeleteStep { NONE, CONFIRM_ONE, CONFIRM_ALL }

    fun canDeleteOne(sel: ChildSelection, book: ProfileBook): Boolean =
        sel.size == 1 && book.find(sel.ids[0]) != null

    fun canDeleteAll(book: ProfileBook): Boolean = !book.isEmpty()

    /** [선택한 아이 삭제]를 눌렀을 때. 조건이 안 맞으면 NONE */
    fun requestDeleteOne(sel: ChildSelection, book: ProfileBook): DeleteStep =
        if (canDeleteOne(sel, book)) DeleteStep.CONFIRM_ONE else DeleteStep.NONE

    fun requestDeleteAll(book: ProfileBook): DeleteStep =
        if (canDeleteAll(book)) DeleteStep.CONFIRM_ALL else DeleteStep.NONE

    /** 확인 줄이 열린 뒤 조건이 깨졌으면(선택 변경·삭제됨) 닫는다 */
    fun stepStillValid(step: DeleteStep, sel: ChildSelection, book: ProfileBook): DeleteStep = when (step) {
        DeleteStep.NONE -> DeleteStep.NONE
        DeleteStep.CONFIRM_ONE -> if (canDeleteOne(sel, book)) step else DeleteStep.NONE
        DeleteStep.CONFIRM_ALL -> if (canDeleteAll(book)) step else DeleteStep.NONE
    }

    /** 삭제 결과. allCleared가 true면 ProfileStore.clearAll을 쓴다 */
    data class DeleteResult(val book: ProfileBook, val selection: ChildSelection, val last: ChildSelection, val allCleared: Boolean)

    /** [지우기] 확정. NONE이거나 대상이 없으면 null(변경 없음) */
    fun confirmDelete(step: DeleteStep, book: ProfileBook, sel: ChildSelection, last: ChildSelection): DeleteResult? = when (step) {
        DeleteStep.NONE -> null
        DeleteStep.CONFIRM_ONE -> if (!canDeleteOne(sel, book)) null else {
            val nb = book.delete(sel.ids[0])
            DeleteResult(nb, ChildSelections.pruneDeleted(sel, nb), ChildSelections.pruneDeleted(last, nb), false)
        }
        DeleteStep.CONFIRM_ALL -> if (!canDeleteAll(book)) null else {
            DeleteResult(book.deleteAll(), ChildSelection(), ChildSelection(), true)
        }
    }

    /** 영역 전체 표시: 유효한 P일 때만 */
    fun areaVisible(validP: Boolean): Boolean = validP

    /** 조작 버튼 활성: 유효한 P이고 보호자 확인 IDLE일 때만 */
    fun controlsEnabled(validP: Boolean, idle: Boolean): Boolean = validP && idle

    /** 입력 패널·확인 줄은 같은 조건이어야 열려 있을 수 있다 */
    fun canOpenInput(validP: Boolean, idle: Boolean, book: ProfileBook): Boolean =
        controlsEnabled(validP, idle) && !book.isFull()

    /** 카드 글자: 선택은 ✔(색에만 의존하지 않음), 지난번 표시는 둘째 줄 */
    fun cardText(nickname: String, selected: Boolean, isLast: Boolean): String =
        (if (selected) "✔ " else "") + nickname + (if (isLast) "\n$LAST_MARK" else "")

    fun modeLabel(mode: AddressMode, selected: Boolean): String {
        val name = when (mode) {
            AddressMode.NAMES -> MODE_NAMES
            AddressMode.GENERIC -> MODE_GENERIC
            AddressMode.NONE -> MODE_NONE
        }
        return (if (selected) "✔ " else "") + name
    }

    /** 화면용 호칭 미리보기(음성용 spokenGreeting은 쓰지 않는다) */
    fun previewText(mode: AddressMode, nicknames: List<String>): String =
        "호칭 미리보기: " + Greeting.compose(Greeting.greeting(mode, nicknames), PREVIEW_SENTENCE)

    // ---- v3 반복 3(2026-11-11) S06 동선: 무엇을 고쳐/지우는지 버튼과 확인 줄에 직접 보인다 ----
    const val HINT_PICK_ONE = "위에서 아이를 눌러 고르세요"   // 루프 r3: 한 줄로 짧게
    fun hintPickOnly(n: Int) = "수정·삭제는 한 명만 골랐을 때 쓸 수 있어요(지금 ${n}명)"

    private fun oneNick(sel: ChildSelection, book: ProfileBook): String? =
        if (canDeleteOne(sel, book)) book.find(sel.ids[0])?.nickname else null

    /** 한 명을 골랐으면 "‘별명’ 수정", 아니면 기본 문구 */
    fun editLabel(sel: ChildSelection, book: ProfileBook): String = oneNick(sel, book)?.let { "‘${short(it)}’ 수정" } ?: EDIT_BTN
    fun deleteOneLabel(sel: ChildSelection, book: ProfileBook): String = oneNick(sel, book)?.let { "‘${short(it)}’ 삭제" } ?: DELETE_ONE_BTN
    /** 버튼 반 칸(글꼴 2.0)에서도 한 줄: 별명이 길면 앞 LABEL_NICK_MAX 글자 + … (코드포인트 기준) */
    const val LABEL_NICK_MAX = 4
    fun short(nick: String): String {
        val n = nick.codePointCount(0, nick.length)
        return if (n <= LABEL_NICK_MAX) nick else nick.substring(0, nick.offsetByCodePoints(0, LABEL_NICK_MAX)) + "…"
    }

    /** v3 반복 4: 설정 아이 카드의 선택 표시는 원 안 기호(이름 앞에 붙이지 않음), 선택 상태는 설명으로 읽는다 */
    const val CARD_CHECK = "✔"
    fun cardDesc(nickname: String, selected: Boolean): String = if (selected) "$nickname, 선택됨" else "$nickname, 선택 안 됨"

    /** v3 반복 4: 4명이 차서 [새 아이 등록]이 꺼졌을 때의 이유(이전에는 이유 없이 점선 버튼만 보였다) */
    const val HINT_FULL = "새 아이 등록: 최대 4명이에요. 한 명을 지우면 다시 등록할 수 있어요"

    /** 수정·삭제(그리고 4명이면 새 등록)가 꺼져 있는 이유(등록 없음·한 명 선택·4명 미만이면 null = 안내 줄 숨김) */
    /** 루프 r3: 등록한 아이가 1명뿐이고 아무도 고르지 않았으면 그 아이를 고른 것으로 본다(수정·삭제 대상이 분명하므로) */
    fun autoSelect(sel: ChildSelection, book: ProfileBook): ChildSelection {
        val list = book.sortedByRecent()
        return if (sel.size == 0 && list.size == 1) ChildSelection(listOf(list[0].id)) else sel
    }

    /** 루프 r3: 수정·삭제 버튼 상태(활성 모양 = true). 한 명이 골라져 있고 조작 가능할 때만 활성, 아니면 비활성 모양 + 안내 한 줄([manageHint]) */
    data class ManageState(val editEnabled: Boolean, val deleteOneEnabled: Boolean, val hint: String?)
    fun manageState(controls: Boolean, sel: ChildSelection, book: ProfileBook): ManageState {
        val one = controls && canDeleteOne(sel, book)
        return ManageState(one, one, if (controls) manageHint(sel, book) else null)
    }

    fun manageHint(sel: ChildSelection, book: ProfileBook): String? {
        val pick = when {
            book.isEmpty() -> null
            sel.size == 0 -> HINT_PICK_ONE
            sel.size >= 2 -> hintPickOnly(sel.size)
            else -> null
        }
        return if (book.isFull()) listOfNotNull(pick, HINT_FULL).joinToString("\n") else pick
    }

    /** 삭제 확인 줄: 무엇이 함께 지워지는지 말한다(스티커 기록 연동은 doDelete와 같음) */
    fun confirmText(step: DeleteStep, sel: ChildSelection, book: ProfileBook): String = when (step) {
        DeleteStep.CONFIRM_ONE -> oneNick(sel, book)?.let { "‘$it’ 별명과 이 아이의 스티커 기록을 지울까요?" } ?: CONFIRM_TEXT
        DeleteStep.CONFIRM_ALL -> "등록한 아이 ${book.sortedByRecent().size}명과 스티커 기록을 모두 지울까요?"
        DeleteStep.NONE -> CONFIRM_TEXT
    }

    /** 지난번 조합이 현재 목록에 모두 있을 때만 그 id 집합, 아니면 빈 목록 */
    fun highlightedLast(last: ChildSelection, book: ProfileBook): List<Int> =
        if (ChildSelections.shouldHighlightLast(last, book)) last.ids else emptyList()
}
