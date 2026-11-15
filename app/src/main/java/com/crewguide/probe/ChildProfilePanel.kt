package com.crewguide.probe

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * '누가 탔나요?' 접이식 영역(2단계: 선택·등록·삭제·호칭 설정 UI).
 * 3단계: 선택한 아이들의 말투·호칭은 MainActivity가 발화·표시 시점에 이 패널에서 읽어 ChildNotice로 적용한다. 카드 안에 작은 아바타가 있고 선택한 아이 수정이 가능하다.
 * 입력 칸은 별명 하나뿐이다. 저장은 이 앱의 로컬 SharedPreferences(ProfileStore, SelectionStore)만 쓴다.
 * 유효한 P가 아니면 영역 전체(입력 패널·확인 줄 포함)를 숨기고, 보호자 확인이 IDLE이 아니면 조작은 비활성이다.
 */
class ChildProfilePanel(
    private val context: Context,
    private val dp: (Int) -> Int,
    private val gapPx: Int,
    private val textSp: Float,
    private val titleSp: Float,
    private val minTouchPx: Int,
    private val textColor: Int,
    private val currentStyle: () -> CharacterStyle,
    private val currentTone: () -> Tone,
    private val onChanged: () -> Unit = {},
) {
    private companion object { const val AVATAR_DP = 44 }

    object Grant {
        /** grantSticker: 저장 실패(기록 안 됨) */
        const val SAVE_FAILED = StickerLogs.GRANT_SAVE_FAILED
        /** grantSticker: 시작 때 기록을 읽지 못해 저장하지 않음 */
        const val READ_FAILED = StickerLogs.GRANT_READ_FAILED
    }

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title: TextView
    private val busyView: TextView
    private val emptyView: TextView
    private val cardRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val lastBtn: Button
    private val summaryView: TextView
    private val registerBtn: Button
    private val deleteOneBtn: Button
    private val deleteAllBtn: Button
    private val inputPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val nickInput: EditText
    private val inputMsg: TextView
    private val submitBtn: Button
    private val cancelBtn: Button
    private val confirmRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val confirmOkBtn: Button
    private val confirmCancelBtn: Button
    private val modeBtns = LinkedHashMap<AddressMode, Button>()
    private val speakSwitch: Switch
    private val previewView: TextView

    private var book: ProfileBook = ProfileBook()
    private var selection = ChildSelection()
    private var settings = SelectionSettings()
    private var step = ProfileUi.DeleteStep.NONE
    private var inputOpen = false
    private var open = false      // 기본 접힘. 메모리에만 두고 저장하지 않는다
    private var subOpen = false   // 하위 접이식(부르는 방식·호칭 미리보기) 기본 접힘, 저장 안 함
    private val subBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val subTitle: TextView
    private var validP = false
    private var idle = false
    private var seatCount = 0
    private var loadingSwitch = false
    private var editingId: Int? = null
    private val editBtn: Button
    private val editNote: TextView
    /** r10 I: 등록·수정 입력 패널의 "차량 벨트를 쓰지 않는 자리(카시트)" 켬/끔 줄(76dp, ●켜짐/○꺼짐 기호 + 글자). [submit]·[submitEdit] 때 저장 */
    private val carSeatSwitch = DuoSwitch(context)
    private val carSeatRow: DuoToggleRow
    /** 입력 패널이 열려 있는 동안의 카시트 값(저장 전). 등록은 끔으로 시작, 수정은 그 아이의 저장값 */
    private var draftCarSeat = false
    private var loadingCarSeat = false

    // 스티커 기록(로컬). 하위 접이식 '이번 주 스티커(가안)'은 기본 접힘이며 저장하지 않는다
    private var stickerLog = StickerLog()
    /** 시작 때 저장된 스티커 기록을 읽지 못함(2026-11-08 F5). true면 자동 저장을 하지 않아 원본을 보존한다 */
    private var stickerReadFailed = false
    private var stickerOpen = false
    private var stickerClearAsk = false
    private val stickerTitle: TextView
    private val stickerBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val stickerLines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val stickerUndoBtn: Button
    private val stickerClearBtn: Button
    private val stickerConfirmRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val stickerConfirmOkBtn: Button
    private val stickerConfirmCancelBtn: Button

    private fun today(): String = java.time.LocalDate.now().toString()   // yyyy-MM-dd, 날짜 문자열만 쓴다

    /**
     * 보호자 '예'(YES_REPORTED)에서 기존 스티커 규칙이 새 부여를 허용한 순간에만 MainActivity가 호출한다.
     * 선택된 아이가 있을 때만 각각 +1을 기록·저장하고 아이 수를 돌려준다. 선택이 없으면 0(기록 없음).
     */
    /**
     * 선택한 아이들에게 +1. 반환: 기록한 아이 수, 0 = 대상 없음·같은 세션, [Grant.SAVE_FAILED] = 저장 실패(메모리도 되돌림),
     * [Grant.READ_FAILED] = 시작 때 기록을 읽지 못해 저장하지 않음(원본 보존, 2026-11-08 F5·F2)
     */
    /** tripAfter가 있으면 스티커 기록과 이동 세션(지급 표시)을 한 번의 commit으로 함께 저장한다(2026-11-08 Unit 6 최종, ch 결정 3) */
    fun grantSticker(sessionKey: Int, tripAfter: TripSession? = null): Int {
        val ids = selectedProfiles().map { it.id }.toSet()
        val (next, r) = StickerLogs.grantAndSave(stickerLog, ids, today(), sessionKey, stickerReadFailed) {
            if (tripAfter != null) StickerLogStore.saveWithTrip(context, it, tripAfter) else StickerLogStore.save(context, it)
        }
        if (r > 0) { stickerLog = next; render() }
        return r
    }

    /** 선택된 아이들의 프로필(id 순).발화·표시 시점마다 읽는다 */
    fun selectedProfiles(): List<ChildProfile> = ChildSelections.profilesOf(selection, book)

    /** 부르는 방식과 별명 TTS 읽기 설정(저장값) */
    fun addressMode(): AddressMode = settings.mode
    fun speakNicknames(): Boolean = settings.speakNicknames

    /** 입력 중(패널이 열리고 칸에 포커스가 있음)인지. 바깥 스크롤이 맨 위로 가는 규칙이 입력을 방해하지 않게 쓴다 */
    fun isEditing(): Boolean = inputOpen && nickInput.hasFocus()

    /** 별명 입력칸이 열려 있는지(포커스와 무관). 시스템 뒤로가기 판단(BackRule)에 쓴다 */
    fun isInputOpen(): Boolean = inputOpen

    /**
     * 2026-11-14 SCREEN_EXIT_CHECK ⑤: 설정을 닫거나 뒤로가기 때 입력칸을 닫고 키보드를 숨긴다(입력 글자는 버림).
     * 열려 있지 않으면 아무것도 하지 않고 false
     */
    fun closeInputIfOpen(): Boolean {
        if (!inputOpen) return false
        closeInput()
        return true
    }

    private fun tv(t: String, sp: Float) = TextView(context).apply {
        text = t; textSize = sp; setTextColor(textColor); setPadding(0, dp(4), 0, dp(4))
    }

    // v3 단계 2: 직접 칠하던 Button 대신 공통 DuoButton(같은 그리기 엔진). 크기·글자·색 값은 그대로
    private fun newButton(onClick: () -> Unit) = DuoButton(context, DuoTokens.Kind.NEUTRAL).apply {
        textSize = titleSp; typeface = StageFonts.typeface(context, StageFonts.FontRole.BIG_BUTTON); isAllCaps = false   // 루프 r2: 설정 버튼 Jua
        minHeight = minTouchPx; minimumHeight = minTouchPx
        setOnClickListener { onClick() }
    }

    /** 활성/비활성·선택 상태에 맞는 색을 칠한다(선택은 ✔ 글자로도 구분) */
    // 2026-11-11 U2: 공통 DuoButton 모양(하단 띠·눌림·점선 비활성). 선택 = 주(파랑), 그 밖 = 보조(연회색). 선택은 ✔ 글자로도 구분
    private fun paint(b: Button, selected: Boolean, danger: Boolean = false) {
        val kind = when { selected -> DuoTokens.Kind.PRIMARY; danger -> DuoTokens.Kind.DANGER; else -> DuoTokens.Kind.NEUTRAL }
        val d = b as? DuoButton
        if (d != null) {
            if (d.kind != kind) d.kind = kind else d.restyle()   // 활성/비활성은 DuoButton.setEnabled가 다시 칠한다
            d.setPadding(dp(8), 0, dp(8), dp(DuoTokens.EDGE_DP))
            return
        }
        val r = DuoTokens.roles(Palette.current.night)
        b.background = DuoDraw.edgeButton(context, r.fill(kind), r.edge(kind), r.disabledFill, r.disabledStroke)
        b.setTextColor(Palette.argb(if (!b.isEnabled) r.disabledText else r.ink(kind)))
        b.stateListAnimator = null
        b.setPadding(dp(8), 0, dp(8), dp(DuoTokens.EDGE_DP))
    }

    private fun lp(left: Int = 0, right: Int = gapPx) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(left, 0, right, gapPx) }

    private lateinit var manageHintView: TextView
    private lateinit var confirmTextView: TextView

    init {
        title = TextView(context).apply {
            textSize = titleSp; setTextColor(textColor); setPadding(0, dp(8), 0, dp(8))
            minimumHeight = maxOf(minTouchPx, dp(76)); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                // 입력 패널이 열려 있는 동안은 접히지 않는다
                if (!inputOpen) { open = !open; render() }
            }
        }
        root.addView(title)

        busyView = tv(ProfileUi.BUSY_NOTE, textSp).apply { visibility = View.GONE }
        body.addView(busyView)
        emptyView = tv(ProfileUi.EMPTY_NOTE, textSp)
        body.addView(emptyView)
        body.addView(cardRow)

        lastBtn = newButton { chooseLast() }.apply { text = ProfileUi.LAST_BTN }
        body.addView(lastBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, gapPx) })
        summaryView = tv("", textSp)
        body.addView(summaryView)

        registerBtn = newButton { openInput() }.apply { text = ProfileUi.REGISTER_BTN }
        deleteOneBtn = newButton { askDelete(true) }.apply { text = ProfileUi.DELETE_ONE_BTN }
        deleteAllBtn = newButton { askDelete(false) }.apply { text = SettingsLook.dangerLabel(ProfileUi.DELETE_ALL_BTN) }
        val manageRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        editBtn = newButton { openEdit() }.apply { text = ProfileUi.EDIT_BTN }
        // 글자(24sp)·터치 높이(76dp)를 줄이지 않기 위해 2줄 × 2개로 나눈다
        val manageRow2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        manageRow.addView(registerBtn, lp()); manageRow.addView(editBtn, lp(right = 0))
        manageRow2.addView(deleteOneBtn, lp()); manageRow2.addView(deleteAllBtn, lp(right = 0))
        body.addView(manageRow)
        body.addView(manageRow2)
        manageHintView = tv("", textSp).apply { visibility = View.GONE }   // v3 반복 3: 수정·삭제가 꺼진 이유
        body.addView(manageHintView)

        // 인라인 입력 패널(별도 Activity·Dialog 없음). 별명 칸 하나만 있다
        nickInput = EditText(context).apply {
            textSize = titleSp; setTextColor(textColor); hint = ProfileUi.NICK_HINT
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(ChildProfiles.MAX_NICKNAME_CODEPOINTS))
            minHeight = minTouchPx; minimumHeight = minTouchPx
            // ISSUE-04: 가로 화면에서 키보드가 전체 화면(추출) 모드로 떠 앱과 "실명·사진·생년월일은 입력하지 마세요" 안내를 가리던 것을 막는다
            imeOptions = NickImeOptions.apply(imeOptions)
            // 야간에만 힌트 글자색을 명시한다(주간은 기존 모양 유지). 입력 글자는 위에서 textColor로 지정됨
            if (Palette.current.night) setHintTextColor(Palette.argb(Palette.current.chipStroke))
        }
        inputMsg = tv("", textSp).apply { visibility = View.GONE }
        submitBtn = newButton { submit() }.apply { text = ProfileUi.SUBMIT_BTN }
        cancelBtn = newButton { closeInput() }.apply { text = ProfileUi.CANCEL_BTN }
        // ISSUE-12(2026-11-13): 키보드가 올라오면 별명 칸 아래의 [등록]·[취소]가 가려졌다. 버튼을 별명 칸과 같은 줄 오른쪽에 둔다
        // (칸이 보이면 버튼도 보인다). 버튼 크기·글자는 그대로(76dp 이상)
        val inputRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputRow.addView(nickInput, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, gapPx, 0) })
        inputRow.addView(submitBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, gapPx, 0) })
        inputRow.addView(cancelBtn, LinearLayout.LayoutParams(-2, -2))
        inputPanel.addView(tv(ProfileUi.NICK_GUIDE, textSp))
        editNote = tv(ProfileUi.EDIT_NOTE, textSp).apply { visibility = View.GONE }
        inputPanel.addView(editNote)
        // r10 I: 카시트 켬/끔 줄. 키보드가 올라와도 가리지 않게 별명 칸 위에 둔다(ISSUE-12와 같은 이유)
        carSeatRow = DuoToggleRow(context, CarSeatUi.TITLE, CarSeatUi.DESC, carSeatSwitch)
        carSeatSwitch.setOnCheckedChangeListener { _, checked ->
            if (loadingCarSeat) return@setOnCheckedChangeListener
            if (!enabled()) { setCarSeatSwitch(draftCarSeat); return@setOnCheckedChangeListener }
            draftCarSeat = checked
        }
        inputPanel.addView(carSeatRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, gapPx) })
        inputPanel.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, gapPx) })
        inputPanel.addView(inputMsg)
        body.addView(inputPanel)

        // 인라인 삭제 확인 줄
        confirmOkBtn = newButton { doDelete() }.apply { text = ProfileUi.CONFIRM_OK_BTN }
        confirmCancelBtn = newButton { step = ProfileUi.DeleteStep.NONE; render() }.apply { text = ProfileUi.CANCEL_BTN }
        val confirmBtns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        confirmBtns.addView(confirmOkBtn, lp()); confirmBtns.addView(confirmCancelBtn, lp(right = 0))
        confirmTextView = tv(ProfileUi.CONFIRM_TEXT, titleSp)
        confirmRow.addView(confirmTextView)
        confirmRow.addView(confirmBtns)
        body.addView(confirmRow)

        // 호칭 설정(저장만, 말풍선·TTS에는 쓰지 않는다)
        subTitle = TextView(context).apply {
            textSize = titleSp; setTextColor(textColor); setPadding(0, dp(8), 0, dp(8))
            minimumHeight = maxOf(minTouchPx, dp(76)); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { subOpen = !subOpen; render() }
        }
        body.addView(subTitle)
        subBody.addView(tv(ProfileUi.STORE_NOTE, textSp))
        subBody.addView(tv(ProfileUi.MODE_LABEL, textSp))
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val modes = listOf(AddressMode.NAMES, AddressMode.GENERIC, AddressMode.NONE)
        for ((i, m) in modes.withIndex()) {
            val b = newButton { chooseMode(m) }
            modeBtns[m] = b
            modeRow.addView(b, lp(right = if (i == modes.lastIndex) 0 else gapPx))
        }
        subBody.addView(modeRow)
        speakSwitch = Switch(context).apply {
            text = ProfileUi.SPEAK_SWITCH
            textSize = titleSp; setTextColor(textColor); minimumHeight = minTouchPx
            setOnCheckedChangeListener { _, checked ->
                if (loadingSwitch) return@setOnCheckedChangeListener
                if (!ProfileUi.controlsEnabled(validP, idle)) { setSwitch(settings.speakNicknames); return@setOnCheckedChangeListener }
                if (checked == settings.speakNicknames) return@setOnCheckedChangeListener
                settings = settings.copy(speakNicknames = checked)
                SelectionStore.save(context, settings)
                render()
            }
        }
        subBody.addView(speakSwitch)
        subBody.addView(tv(ProfileUi.SPEAK_NOTE, textSp))
        previewView = tv("", textSp)
        subBody.addView(previewView)
        body.addView(subBody)

        // 하위 접이식 '이번 주 스티커(가안)': 아이별 한 줄, 취소, 기록 지우기(인라인 확인)
        stickerTitle = TextView(context).apply {
            textSize = titleSp; setTextColor(textColor); setPadding(0, dp(8), 0, dp(8))
            minimumHeight = maxOf(minTouchPx, dp(76)); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { stickerOpen = !stickerOpen; if (!stickerOpen) stickerClearAsk = false; render() }
        }
        body.addView(stickerTitle)
        stickerUndoBtn = newButton { undoSticker() }.apply { text = StickerLogs.UNDO_BTN }
        stickerClearBtn = newButton {
            if (enabled()) {
                stickerClearAsk = true; render()
                showConfirm(StickerLogs.CLEAR_CONFIRM, onYes = { clearStickers() }, onNo = { stickerClearAsk = false; render() })   // 루프 r2: 앱 모양 확인 창
            }
        }.apply { text = SettingsLook.dangerLabel(StickerLogs.CLEAR_BTN) }
        stickerConfirmOkBtn = newButton { clearStickers() }.apply { text = ProfileUi.CONFIRM_OK_BTN }
        stickerConfirmCancelBtn = newButton { stickerClearAsk = false; render() }.apply { text = ProfileUi.CANCEL_BTN }
        val stickerConfirmBtns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        stickerConfirmBtns.addView(stickerConfirmOkBtn, lp()); stickerConfirmBtns.addView(stickerConfirmCancelBtn, lp(right = 0))
        stickerConfirmRow.addView(tv(StickerLogs.CLEAR_CONFIRM, titleSp))
        stickerConfirmRow.addView(stickerConfirmBtns)
        val stickerBtnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        stickerBtnRow.addView(stickerUndoBtn, lp()); stickerBtnRow.addView(stickerClearBtn, lp(right = 0))
        stickerBody.addView(stickerLines)
        stickerBody.addView(tv(StickerLogs.NOTE, textSp))
        stickerBody.addView(stickerBtnRow)
        stickerBody.addView(stickerConfirmRow)
        body.addView(stickerBody)

        root.addView(body)
        load()
        render()
    }

    /** r10 I: 저장 없이 카시트 스위치 모양만 맞춘다 */
    private fun setCarSeatSwitch(v: Boolean) {
        loadingCarSeat = true
        carSeatSwitch.isChecked = v
        loadingCarSeat = false
    }

    private fun setSwitch(v: Boolean) {
        loadingSwitch = true
        speakSwitch.isChecked = v
        loadingSwitch = false
    }

    /** 앱 시작 때 저장값을 읽는다(예외는 기본값). 마지막 조합은 자동 선택하지 않는다 */
    private fun load() {
        book = try { ProfileStore.load(context) } catch (e: Exception) { ProfileBook() }
        settings = try { SelectionStore.load(context) } catch (e: Exception) { SelectionSettings() }
        selection = ChildSelection()
        setSwitch(settings.speakNicknames)
        // 스티커 기록: 읽기에 성공했을 때만 28일 넘은 날짜를 지우고(바뀐 경우만) 저장한다.
        // 2026-11-08 F5: 읽기 실패(버전 불일치·손상·예외)는 빈 기록으로 확정하지 않는다. 화면은 빈 기록, 저장값은 그대로 두고 자동 저장을 하지 않는다
        val plan = StickerLogs.startup(StickerLogStore.loadOrNull(context), today())
        stickerLog = plan.log
        stickerReadFailed = plan.readFailed
        if (plan.save) StickerLogStore.save(context, stickerLog)
    }

    private fun undoSticker() {
        if (!enabled() || stickerLog.last == null || stickerReadFailed) return
        val next = StickerLogs.undoLast(stickerLog)
        if (StickerLogStore.save(context, next)) stickerLog = next
        render()
    }

    private fun clearStickers() {
        if (!enabled()) return
        stickerClearAsk = false
        stickerLog = StickerLogs.clearAll(stickerLog)
        StickerLogStore.clearAll(context)
        stickerReadFailed = false   // 보호자가 직접 지웠다(읽지 못한 원본도 함께 삭제됨)
        render()
    }

    /**
     * 2026-11-14(지시 6) 시험·녹화 전 스티커 기록 초기화. debug 브로드캐스트(DEBUG_RESET_STICKERS)에서만 부른다(release에는 부르는 코드 없음).
     * 설정의 [스티커 기록 지우기]와 같은 처리(메모리 기록과 저장값 함께)이고, 아이 등록(별명)은 남긴다. 화면 조작이 아니라 잠금 조건은 보지 않는다
     */
    fun clearStickersForTest() {
        stickerClearAsk = false
        stickerLog = StickerLogs.clearAll(stickerLog)
        StickerLogStore.clearAll(context)
        stickerReadFailed = false
        render()
    }

    private fun enabled() = ProfileUi.controlsEnabled(validP, idle)

    // 무대 칩용(읽기 전용 얇은 접근자). 로직은 아래 기존 함수를 그대로 쓴다
    fun profileList(): List<ChildProfile> = book.sortedByRecent()
    fun isChildSelected(id: Int): Boolean = selection.contains(id)
    fun lastChoiceVisible(): Boolean = ProfileUi.highlightedLast(settings.last, book).isNotEmpty()   // lastBtn 표시 조건과 같음

    fun chooseLast() {
        if (!enabled()) return
        val ids = ProfileUi.highlightedLast(settings.last, book)
        if (ids.isEmpty()) return
        selection = ChildSelections.normalize(ids)
        render()
        onChanged()
    }

    /**
     * 2026-11-13 PM 계약 v2: 좌석 화면에서 자리에 배정한 아이를 이번 이동의 선택으로 쓴다(자동 확인 완료 때 1회).
     * 확인 흐름 안(정차 P에서 시작한 확인)에서만 부르므로 대기 상태 조건([enabled])을 보지 않는다. 등록부에 없는 id는 버린다
     */
    fun selectForTrip(ids: Collection<Int>) {
        selection = ChildSelections.normalize(ids.filter { id -> book.profiles.any { it.id == id } })
        render()
    }

    fun toggle(id: Int) {
        if (!enabled()) return
        selection = ChildSelections.toggle(selection, id, book)
        render()
        onChanged()
    }

    /** 보호자 설정 화면에서 펼침 여부를 바꾼다 */
    fun setExpanded(v: Boolean) {
        open = v
        render()
    }

    /** 무대에서 프로필이 0명일 때 바로 별명 입력 칸을 펼친다 */
    fun expandForRegister() {
        open = true
        openInput()
    }

    private fun chooseMode(m: AddressMode) {
        if (!enabled() || settings.mode == m) return
        settings = settings.copy(mode = m)
        SelectionStore.save(context, settings)
        render()
        onChanged()
    }

    private fun openEdit() {
        if (!ProfileUi.canEdit(validP, idle, selection, book)) return
        val p = book.find(selection.ids[0]) ?: return
        editingId = p.id
        inputOpen = true
        draftCarSeat = CarSeatUi.draftFor(p); setCarSeatSwitch(draftCarSeat)   // r10 I: 그 아이의 저장값
        nickInput.setText(p.nickname)
        nickInput.setSelection(nickInput.text?.length ?: 0)
        inputMsg.text = ""; inputMsg.visibility = View.GONE
        step = ProfileUi.DeleteStep.NONE
        render()
        nickInput.requestFocus()
    }

    private fun openInput() {
        if (!ProfileUi.canOpenInput(validP, idle, book)) return
        editingId = null
        inputOpen = true
        draftCarSeat = CarSeatUi.draftFor(null); setCarSeatSwitch(draftCarSeat)   // r10 I: 등록은 끔으로 시작
        nickInput.setText("")
        inputMsg.text = ""; inputMsg.visibility = View.GONE
        step = ProfileUi.DeleteStep.NONE
        render()
        nickInput.requestFocus()
    }

    private fun closeInput() {
        val was = inputOpen
        inputOpen = false
        editingId = null
        draftCarSeat = false; setCarSeatSwitch(false)   // r10 I: 저장하지 않은 값은 버린다
        nickInput.setText("")
        inputMsg.text = ""; inputMsg.visibility = View.GONE
        if (was) {
            try {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(nickInput.windowToken, 0)
            } catch (e: Exception) { /* 무시 */ }
            nickInput.clearFocus()
        }
        render()
    }

    private fun submitEdit(id: Int) {
        if (!enabled()) return
        val raw = nickInput.text?.toString()
        if (ProfileUi.checkEdit(raw) != ProfileUi.RegisterCheck.OK) {
            inputMsg.text = ProfileUi.MSG_INVALID; inputMsg.visibility = View.VISIBLE
            return
        }
        // 별명만 바꾼다(4차 결정 A·B: 이 아이의 꾸밈·말투 저장값은 덮어쓰지 않는다)
        val nb = ProfileUi.applyEdit(book, id, raw)
        if (nb == null) {
            inputMsg.text = ProfileUi.MSG_INVALID; inputMsg.visibility = View.VISIBLE
            return
        }
        book = CarSeatUi.applyEdit(nb, id, draftCarSeat)   // r10 I: 카시트 켬/끔도 같이 저장(e62f3cb ProfileBook.setCarSeat)
        ProfileStore.save(context, book)
        closeInput()
        onChanged()
    }

    private fun submit() {
        editingId?.let { submitEdit(it); return }
        if (!ProfileUi.canOpenInput(validP, idle, book)) return
        val raw = nickInput.text?.toString()
        val check = ProfileUi.checkRegister(raw, book)
        val msg = ProfileUi.registerMessage(check)
        if (msg != null) {
            inputMsg.text = msg; inputMsg.visibility = View.VISIBLE
            return
        }
        // 현재 화면의 꾸미기·말투를 새 프로필 값으로 복사한다
        val r = book.add(raw, currentStyle(), currentTone(), false, carSeat = draftCarSeat)   // r10 I(e62f3cb ProfileBook.add carSeat)
        val p = r.profile
        if (p == null) {
            inputMsg.text = ProfileUi.MSG_INVALID; inputMsg.visibility = View.VISIBLE
            return
        }
        // 2026-11-08 F4: 번호를 다시 쓰므로 같은 번호의 옛 스티커 기록을 먼저 지운다(삭제 때 스티커 저장이 빠진 경우).
        // 읽기 실패 중이면 원본을 덮어쓰지 않는다(GAP: 그 뒤 읽기가 회복되면 옛 기록이 보일 수 있음 → 다음 추가·삭제 때 정리)
        val cleaned = StickerLogs.forNewProfile(stickerLog, p.id)
        if (cleaned !== stickerLog && !stickerReadFailed && StickerLogStore.save(context, cleaned)) stickerLog = cleaned
        book = r.book
        ProfileStore.save(context, book)
        selection = ChildSelections.toggle(selection, p.id, book).let { if (it.contains(p.id)) it else selection }
        closeInput()
        onChanged()
    }

    /**
     * 루프 r2 설정 정리: '부르는 방식·호칭' 접이식(안내 음성 카드로)·'이번 주 스티커' 접이식(따로 카드로)을 떼어 준다.
     * 펼침·조작·잠금 규칙은 그대로 이 패널의 render가 관리한다(뷰의 부모만 바뀜). 설정은 잠금이 되면 닫히므로 떼어 낸 뷰도 함께 가려진다
     */
    fun detachAddressViews(): List<View> = listOf<View>(subTitle, subBody).onEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    fun detachStickerViews(): List<View> = listOf<View>(stickerTitle, stickerBody).onEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }

    private fun askDelete(one: Boolean) {
        if (!enabled()) return
        inputOpen = false
        editingId = null
        step = if (one) ProfileUi.requestDeleteOne(selection, book) else ProfileUi.requestDeleteAll(book)
        render()
        // 루프 r2 설정 정리: 지우기 확인은 앱 모양 확인 창(AppDialogs.confirm). [지우기]를 누른 순간 doDelete가 조건을 다시 본다
        if (step != ProfileUi.DeleteStep.NONE) showConfirm(ProfileUi.confirmText(step, selection, book),
            onYes = { doDelete() }, onNo = { step = ProfileUi.DeleteStep.NONE; render() })
    }

    /** 루프 r2: 열린 확인 창(지우기). 잠금·조작 불가가 되면 render가 닫는다 */
    private var confirmDialog: android.app.AlertDialog? = null

    private fun closeConfirmDialog() {
        val d = confirmDialog ?: return
        confirmDialog = null
        if (d.isShowing) d.dismiss()
    }

    private fun showConfirm(message: String, onYes: () -> Unit, onNo: () -> Unit) {
        closeConfirmDialog()
        val d = AppDialogs.confirm(context, message, ProfileUi.CONFIRM_OK_BTN, ProfileUi.CANCEL_BTN,
            onYes = { confirmDialog = null; onYes() },
            onNo = { confirmDialog = null; onNo() },
            onCancel = { confirmDialog = null; onNo() })
        confirmDialog = d
        try { d.show() } catch (e: RuntimeException) { confirmDialog = null; onNo() }
    }

    private fun doDelete() {
        if (!enabled()) return
        val deletingId = if (step == ProfileUi.DeleteStep.CONFIRM_ONE) selection.ids.firstOrNull() else null
        val r = ProfileUi.confirmDelete(step, book, selection, settings.last)
        step = ProfileUi.DeleteStep.NONE
        if (r != null) {
            // 삭제 연동: 선택한 아이 삭제는 그 아이의 스티커 기록만, 모두 지우기는 스티커 기록 전체를 지운다
            if (r.allCleared) {
                stickerLog = StickerLogs.clearAll(stickerLog)
                StickerLogStore.clearAll(context)
                stickerReadFailed = false
            } else if (deletingId != null) {
                stickerLog = StickerLogs.removeProfile(stickerLog, deletingId)
                if (!stickerReadFailed) StickerLogStore.save(context, stickerLog)   // 실패해도 다음 추가 때 forNewProfile이 정리
            }
            book = r.book
            selection = r.selection
            settings = settings.copy(last = r.last)
            if (r.allCleared) ProfileStore.clearAll(context) else ProfileStore.save(context, book)
            SelectionStore.save(context, settings)
        }
        render()
        onChanged()
    }

    /** [보호자 확인 시작]이 눌려 확인이 시작될 때 호출. 선택이 비어 있으면 기존 마지막 조합을 그대로 둔다 */
    fun recordLast() {
        if (selection.isEmpty()) return
        settings = settings.copy(last = ChildSelections.recordLast(selection))
        SelectionStore.save(context, settings)
        render()
    }

    /** 메인 스레드. validP=유효한 P, idle=보호자 확인 IDLE, seatCount=확인 대상 좌석 수 */
    fun update(validP: Boolean, idle: Boolean, seatCount: Int) {
        this.validP = validP; this.idle = idle; this.seatCount = seatCount
        render()
    }

    /** v3(P2): 재생성 복원용 현재 하위 접이식 펼침(UiRestore 비트) */
    fun foldsMask(): Int = UiRestore.foldsMask(subOpen, stickerOpen)

    /** v3(P2): 재생성 뒤 설정을 다시 열었을 때 펼침을 되돌린다. 잠금(유효한 P 아님)이면 아무것도 하지 않는다 */
    fun restoreFolds(mask: Int) {
        if (!ProfileUi.areaVisible(validP) || mask == 0) return
        subOpen = (mask and UiRestore.FOLD_SUB) != 0
        stickerOpen = (mask and UiRestore.FOLD_STICKER) != 0
        render()
    }

    private fun render() {
        if (!ProfileUi.areaVisible(validP)) {
            // 잠금: 입력 패널·확인 줄을 닫고 영역을 숨긴다
            if (inputOpen) closeInputQuiet()
            step = ProfileUi.DeleteStep.NONE
            open = false; subOpen = false   // 잠금이면 접힘으로 돌아온다
            stickerOpen = false; stickerClearAsk = false
            closeConfirmDialog()   // 루프 r2: 잠금이면 지우기 확인 창도 닫는다
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        val on = enabled()
        if (!on) {
            if (inputOpen) closeInputQuiet()
            step = ProfileUi.DeleteStep.NONE
            stickerClearAsk = false
            closeConfirmDialog()
        }
        selection = ProfileUi.autoSelect(ChildSelections.pruneDeleted(selection, book), book)   // 루프 r3: 1명뿐이면 자동 선택
        step = ProfileUi.stepStillValid(step, selection, book)
        // 루프 r2 설정 정리: 설정 카드 제목('아이 관리')이 이미 있으므로 옛 접이식 제목("▼ 누가 탔나요?")은 숨기고 늘 펼친다
        val expanded = true
        title.visibility = View.GONE
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        subTitle.text = (if (subOpen) "▼ " else "▶ ") + ReleaseCopy.t(ProfileUi.SUB_TITLE)
        subBody.visibility = if (subOpen) View.VISIBLE else View.GONE
        busyView.visibility = if (idle) View.GONE else View.VISIBLE

        // 카드(한 줄, 최대 4개)
        val list = book.sortedByRecent()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        cardRow.removeAllViews()
        for ((i, p) in list.withIndex()) {
            val sel = selection.contains(p.id)
            cardRow.addView(buildCard(p, i, sel, false, on), lp(right = if (i == list.lastIndex) 0 else gapPx))   // 루프 r2: "지난번" 표시 없음(자동 배정 흐름)
        }
        cardRow.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        lastBtn.visibility = View.GONE   // 루프 r2: [지난번 아이 그대로]는 자동 배정 흐름에서 쓰지 않는다
        lastBtn.isEnabled = on; paint(lastBtn, false)
        summaryView.visibility = View.GONE   // 루프 r2: "아이 N명 · 확인 좌석 M석"은 옛 선택 흐름 문구
        summaryView.text = ChildSelections.summaryLabel(selection.size, seatCount)

        registerBtn.isEnabled = on && !book.isFull(); paint(registerBtn, false)
        val manage = ProfileUi.manageState(on, selection, book)   // 루프 r3: 수정·삭제 상태는 순수 함수 하나로
        editBtn.isEnabled = manage.editEnabled; paint(editBtn, false)
        editBtn.text = ProfileUi.editLabel(selection, book)
        deleteOneBtn.text = SettingsLook.dangerLabel(ProfileUi.deleteOneLabel(selection, book))   // 루프 r2: 지우기 = 빨강 글자 + 기호
        val hint = manage.hint
        manageHintView.visibility = if (hint == null) View.GONE else View.VISIBLE
        manageHintView.text = hint ?: ""
        confirmTextView.text = ProfileUi.confirmText(step, selection, book)
        editNote.visibility = if (editingId != null) View.VISIBLE else View.GONE
        submitBtn.text = if (editingId != null) ProfileUi.SAVE_BTN else ProfileUi.SUBMIT_BTN
        deleteOneBtn.isEnabled = manage.deleteOneEnabled; paint(deleteOneBtn, false, danger = true)   // v3: 지우기는 '주의' 모양(레퍼런스 04)
        deleteAllBtn.isEnabled = on && ProfileUi.canDeleteAll(book); paint(deleteAllBtn, false, danger = true)

        inputPanel.visibility = if (inputOpen && on) View.VISIBLE else View.GONE
        submitBtn.isEnabled = on; paint(submitBtn, false)
        if (carSeatRow.isEnabled != on) carSeatRow.isEnabled = on   // r10 I: 비활성은 스위치 회색 + "지금은 바꿀 수 없음"(투명도 없음)
        cancelBtn.isEnabled = on; paint(cancelBtn, false)
        confirmRow.visibility = View.GONE   // 루프 r2: 인라인 확인 줄 대신 앱 모양 확인 창(showConfirm)
        confirmOkBtn.isEnabled = on; paint(confirmOkBtn, false, danger = true)
        confirmCancelBtn.isEnabled = on; paint(confirmCancelBtn, false)

        for ((m, b) in modeBtns) {
            val sel = settings.mode == m
            b.text = ProfileUi.modeLabel(m, sel)
            b.isEnabled = on
            paint(b, sel)
        }
        speakSwitch.isEnabled = on

        // 이번 주 스티커(가안): 아이별 한 줄(순위·비교 없음, 등록 순서는 목록과 같음)
        val todayStr = today()
        // 2026-11-14 사용자 지시: "(가안)" 표기는 debug 화면에도 보이지 않게 늘 지운다(상수 StickerLogs.SUB_TITLE은 담당 밖 파일이라 쓰는 곳에서 지움)
        stickerTitle.text = (if (stickerOpen) "▼ " else "▶ ") + SettingsCopy.STICKER_TOGGLE   // 루프 r2: 카드 제목 '이번 주 스티커' 아래 접이식 줄
        stickerBody.visibility = if (stickerOpen) View.VISIBLE else View.GONE
        stickerLines.removeAllViews()
        if (list.isEmpty()) stickerLines.addView(tv(StickerLogs.EMPTY_NOTE, textSp))
        else stickerLines.addView(tv(StickerBoard.MEANING_NOTE, textSp))
        for (p in list) {
            val wk = StickerLogs.weekSummary(stickerLog, p.id, todayStr).total
            stickerLines.addView(tv(StickerLogs.summaryLine(p.nickname, wk, StickerLogs.todayCount(stickerLog, p.id, todayStr)), textSp).apply {
                typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(4))
            })
            // 2026-11-11 UI/UX v3 S05: 요일별 도장판(월~일 7칸, 표시 전용 — 기록·지급 규칙은 그대로)
            val cells = StickerBoard.week(stickerLog, p.id, todayStr)
            if (cells.isNotEmpty()) {
                stickerLines.addView(StickerBoardView(context).apply { bind(cells) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            stickerLines.addView(tv(StickerBoard.footer(StickerBoard.weekTotal(cells)), textSp))
        }
        stickerUndoBtn.isEnabled = on && stickerLog.last != null; paint(stickerUndoBtn, false)
        stickerClearBtn.isEnabled = on; paint(stickerClearBtn, false, danger = true)
        stickerConfirmRow.visibility = View.GONE   // 루프 r2: 인라인 확인 줄 대신 앱 모양 확인 창
        stickerConfirmOkBtn.isEnabled = on; paint(stickerConfirmOkBtn, false, danger = true)
        stickerConfirmCancelBtn.isEnabled = on; paint(stickerConfirmCancelBtn, false)
        val names = ChildSelections.profilesOf(selection, book).map { it.nickname }
        previewView.text = ProfileUi.previewText(settings.mode, names)
    }

    /**
     * 카드 한 장: 작은 원(별명 첫 글자, 장식)과 별명 글자를 한 줄에 둔다. 높이는 최소 76dp(줄 높이를 늘리지 않음).
     * 2026-11-10 4차 결정 A: 옛 벡터 승무원 아바타(GuideCharacterView)를 쓰지 않는다. 원 바탕은 무대 카드와 같은 순서 색.
     */
    private fun buildCard(p: ChildProfile, index: Int, sel: Boolean, isLast: Boolean, on: Boolean): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = maxOf(minTouchPx, dp(76))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isEnabled = on
            isClickable = true
            setOnClickListener { toggle(p.id) }
        }
        val pal = Palette.current
        val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        val fg: Int
        when {
            !on -> { bg.setColor(Palette.argb(pal.btnOffBg)); fg = Palette.argb(pal.btnOffText) }
            sel -> { bg.setColor(Palette.argb(pal.btnOnBg)); fg = Palette.argb(pal.btnOnText) }
            else -> { bg.setColor(Palette.argb(pal.chipBg)); bg.setStroke(dp(2), Palette.argb(pal.chipStroke)); fg = Palette.argb(pal.chipText) }
        }
        card.background = bg
        // 장식 원: 별명은 옆 글자가 읽으므로 TalkBack이 다시 읽지 않게 한다(중복 낭독 방지)
        val avatar = TextView(context).apply {
            // v3 반복 4: 선택 표시(✔)를 이름 앞이 아니라 이 원 안에 둔다(8자 별명에서 "✔"만 윗줄에 남던 줄바꿈 결함). 색이 아닌 표시는 유지
            text = if (sel) ProfileUi.CARD_CHECK else ChildInitial.of(p.nickname)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, AVATAR_DP / 2f)
            typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Palette.argb(ChildInitial.TEXT_RGB))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.argb(ChildCardLayout.avatarBg(index))) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(AVATAR_DP), dp(AVATAR_DP)).apply { setMargins(0, 0, dp(4), 0) })
        val label = TextView(context).apply {
            text = ProfileUi.cardText(p.nickname, false, false)
            textSize = titleSp; setTextColor(fg)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = ProfileUi.cardDesc(p.nickname, sel)   // 원 안의 ✔는 낭독하지 않으므로 선택 상태를 여기서 읽는다
            DuoText.fitOneLine(this, titleSp)   // v4.1: 아이 4명 + 글꼴 2.0에서 "Haneul0/1" 글자 중간 꺾임 방지
        }
        // 둘째 줄: '지난번'과 '스티커 N개'를 같은 줄에 20sp로 둔다(카드 높이는 지난번 표시가 있던 때와 같은 두 줄)
        val second = TextView(context).apply {
            text = (if (isLast) "${ProfileUi.LAST_MARK} · " else "") + StickerLogs.cardCountText(StickerLogs.totalCount(stickerLog, p.id))
            textSize = textSp; setTextColor(fg)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            DuoText.fitOneLine(this, textSp)   // v4.1: "스티커/0개" 꺾임 방지
        }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(second) }
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    /** render 안에서 쓰는 재귀 없는 닫기(입력 상태만 초기화) */
    private fun closeInputQuiet() {
        inputOpen = false
        editingId = null
        draftCarSeat = false; setCarSeatSwitch(false)   // r10 I
        nickInput.setText("")
        inputMsg.text = ""; inputMsg.visibility = View.GONE
        try {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(nickInput.windowToken, 0)
        } catch (e: Exception) { /* 무시 */ }
        nickInput.clearFocus()
    }
}

/**
 * 2026-11-15 r10 I(사용자 결정): 아이 등록·수정의 "차량 벨트를 쓰지 않는 자리(카시트)" 켬/끔 줄 문구와 순수 규칙.
 * 값은 [ChildProfile.carSeat](개발 4 e62f3cb, 기본 끔, 기기에만 저장). 켠 아이가 앉은 뒷자리는 미체결 신호일 때 바로 [보호자 확인]이 보인다(개발 4 SeatCheckRule).
 * 신호를 바꿔 말하지 않는다(벨트를 맸다고 하지 않음).
 */
object CarSeatUi {
    const val TITLE = "차량 벨트를 쓰지 않는 자리(카시트)"
    const val DESC = "켜면 벨트 체결 신호가 없을 때 바로 [보호자 확인]을 보여요"

    /** 입력 패널을 열 때 스위치 값: 수정이면 그 아이의 저장값, 등록이면 끔 */
    fun draftFor(editing: ChildProfile?): Boolean = editing?.carSeat == true

    /** 수정 저장: 별명을 바꾼 등록부에 카시트 값만 더한다(없는 id면 그대로) */
    fun applyEdit(book: ProfileBook, id: Int, on: Boolean): ProfileBook = book.setCarSeat(id, on)

    fun all(): List<String> = listOf(TITLE, DESC)
}

/** 별명 입력칸 IME 옵션(ISSUE-04). 기존 옵션·동작은 그대로 두고 추출(전체 화면) 키보드만 끈다 */
object NickImeOptions {
    const val NO_FULLSCREEN_FLAGS: Int = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    fun apply(base: Int): Int = base or NO_FULLSCREEN_FLAGS
}
