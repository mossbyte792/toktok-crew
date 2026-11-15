package com.crewguide.probe

// 아이 프로필 1단계: 데이터 모델·검증·직렬화 순수 로직(Android 클래스 없음, 화면 연결 없음).
// 개인정보 규칙: 실명·사진·생년월일·음성은 받지 않는다. 이름 칸은 '별명'으로만 쓰고 앱 로컬에만 저장한다.
// 아래 검증 규칙은 개인정보 준수를 보장하지 않는다.

const val MAX_PROFILES = ChildProfiles.MAX_PROFILES
const val MAX_NICKNAME_CODEPOINTS = ChildProfiles.MAX_NICKNAME_CODEPOINTS
val DEFAULT_PROFILE: ChildProfile get() = ChildProfiles.DEFAULT_PROFILE
fun sanitizeNickname(raw: String?): String? = ChildProfiles.sanitizeNickname(raw)
fun encodeBook(book: ProfileBook): String = ProfileCodec.encodeBook(book)
fun decodeBook(text: String?): ProfileBook = ProfileCodec.decodeBook(text)
fun migrateLegacy(book: ProfileBook, legacyStyle: CharacterStyle, legacyTone: Tone): ChildProfile? =
    ChildProfiles.migrateLegacy(book, legacyStyle, legacyTone)

/** 프로필 하나. id는 슬롯 번호(0~3). 가상 기본 프로필만 id가 VIRTUAL_ID(-1)이며 저장되지 않는다 */
data class ChildProfile(
    val id: Int,
    val nickname: String,
    val style: CharacterStyle = CharacterStyle.DEFAULT,
    val tone: Tone = ToneSetting.DEFAULT,
    /** '아이 이름 불러주기'용. 기본 꺼짐 */
    val speakNickname: Boolean = false,
    /**
     * 2026-11-15 r10 I(사용자 결정): "차량 벨트를 쓰지 않는 자리(카시트)" 켬/끔. 기본 끔, 기기에만 저장.
     * 켜면 이 아이가 배정된 뒷자리는 미체결 신호일 때 재안내 3회를 기다리지 않고 바로 [보호자 확인]을 받는다(SeatCheckRule.carSeatSeats).
     * 옛 저장값(이 칸 없음)은 끔으로 읽는다.
     */
    val carSeat: Boolean = false,
)

object ChildProfiles {
    const val MAX_PROFILES = 4
    const val MAX_NICKNAME_CODEPOINTS = 8
    const val VIRTUAL_ID = -1
    private const val MIN_DIGIT_RUN_REJECT = 6

    /** 프로필이 0개일 때 쓰는 가상 프로필. 저장되지 않는다 */
    val DEFAULT_PROFILE = ChildProfile(VIRTUAL_ID, "기본", CharacterStyle.DEFAULT, Tone.A, false)

    // 저장 구분자와 겹칠 수 있는 문자
    private const val RESERVED = ";,=|\\\\\"'%&<>/:"

    /**
     * 별명 검증. 통과하면 정리된 별명, 거부하면 null(기본 별명으로 대체하지 않는다).
     * 규칙: 앞뒤 공백 제거 / 제어문자·줄바꿈·서식문자·저장 구분자(;,=|\ 등)·서로게이트(이모지 등) 제거 /
     * 연속 공백은 하나로 / 8 코드포인트로 자름 / 결과가 비면 거부 /
     * 숫자 6개 이상 연속 또는 '@'(전각 포함) 포함 시 거부.
     * 한계: 숫자·'@' 규칙은 생년월일·연락처·계정 모양 입력을 줄이는 휴리스틱일 뿐이며,
     * 실명 입력을 막지 못한다(예: 한글 이름을 별명 칸에 쓰는 경우). 개인정보 준수를 보장하지 않는다.
     * 모든 서로게이트를 제거하므로 BMP 밖 문자(일부 한자·이모지)는 쓸 수 없다.
     */
    fun sanitizeNickname(raw: String?): String? {
        try {
            if (raw == null) return null
            if (raw.indexOf('@') >= 0 || raw.indexOf('＠') >= 0) return null
            val sb = StringBuilder()
            for (c in raw) {
                when {
                    c.isSurrogate() -> {}
                    c == ' ' || c == '　' || c == ' ' -> sb.append(' ')
                    Character.isISOControl(c) -> {}
                    Character.getType(c) == Character.FORMAT.toInt() -> {}
                    Character.getType(c) == Character.UNASSIGNED.toInt() -> {}
                    Character.getType(c) == Character.PRIVATE_USE.toInt() -> {}
                    RESERVED.indexOf(c) >= 0 -> {}
                    c.isWhitespace() -> sb.append(' ')
                    else -> sb.append(c)
                }
            }
            val collapsed = sb.toString().trim().replace(Regex(" +"), " ")
            if (collapsed.isEmpty()) return null
            var run = 0
            for (c in collapsed) {
                if (Character.isDigit(c)) {
                    run++
                    if (run >= MIN_DIGIT_RUN_REJECT) return null
                } else run = 0
            }
            val cut = if (collapsed.length > MAX_NICKNAME_CODEPOINTS) collapsed.substring(0, MAX_NICKNAME_CODEPOINTS) else collapsed
            val result = cut.trim()
            return if (result.isEmpty()) null else result
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 기존 기기당 1벌 꾸미기·말투를 이어받는 가상 기본 프로필을 돌려준다(저장하지 않음, 순수 함수).
     * book이 비어 있지 않으면 null(이미 프로필이 있으므로 이어받을 필요 없음).
     */
    fun migrateLegacy(book: ProfileBook, legacyStyle: CharacterStyle, legacyTone: Tone): ChildProfile? =
        if (book.profiles.isEmpty()) {
            DEFAULT_PROFILE.copy(style = CharacterOptions.sanitize(legacyStyle), tone = legacyTone)
        } else null
}

/** add 결과: 바뀐 book과 추가된 프로필(거부되면 profile=null, book은 그대로) */
data class AddResult(val book: ProfileBook, val profile: ChildProfile?)

/** 프로필 목록(불변). 모든 연산은 새 ProfileBook을 돌려주며 예외를 던지지 않는다 */
data class ProfileBook(
    val profiles: List<ChildProfile> = emptyList(),
    val lastUsedId: Int? = null,
) {
    fun isEmpty(): Boolean = profiles.isEmpty()
    fun isFull(): Boolean = profiles.size >= ChildProfiles.MAX_PROFILES
    fun find(id: Int): ChildProfile? = profiles.firstOrNull { it.id == id }

    /** 빈 슬롯 중 가장 작은 번호를 사용. 가득 차거나 별명이 잘못되면 거부 */
    fun add(
        nickname: String?,
        style: CharacterStyle = CharacterStyle.DEFAULT,
        tone: Tone = ToneSetting.DEFAULT,
        speakNickname: Boolean = false,
        /** r10 I: 카시트 켬/끔(기본 끔) */
        carSeat: Boolean = false,
    ): AddResult {
        val nick = ChildProfiles.sanitizeNickname(nickname) ?: return AddResult(this, null)
        if (isFull()) return AddResult(this, null)
        val slot = (0 until ChildProfiles.MAX_PROFILES).firstOrNull { s -> profiles.none { it.id == s } }
            ?: return AddResult(this, null)
        val p = ChildProfile(slot, nick, CharacterOptions.sanitize(style), tone, speakNickname, carSeat)
        return AddResult(copy(profiles = (profiles + p).sortedBy { it.id }), p)
    }

    /** id가 같은 프로필을 교체. 없는 id이거나 별명이 잘못되면 변경 없음 */
    fun update(profile: ChildProfile): ProfileBook {
        if (find(profile.id) == null) return this
        val nick = ChildProfiles.sanitizeNickname(profile.nickname) ?: return this
        val fixed = profile.copy(nickname = nick, style = CharacterOptions.sanitize(profile.style))
        return copy(profiles = profiles.map { if (it.id == profile.id) fixed else it })
    }

    /**
     * r10 I: 카시트 켬/끔만 바꾼다(등록·수정 화면에서 부름). 없는 id거나 값이 같으면 같은 객체.
     * 저장은 호출자가 [ProfileStore.save]로 한다.
     */
    fun setCarSeat(id: Int, on: Boolean): ProfileBook {
        val p = find(id) ?: return this
        if (p.carSeat == on) return this
        return copy(profiles = profiles.map { if (it.id == id) it.copy(carSeat = on) else it })
    }

    /** r10 I: 카시트를 켠 아이 id(배정 자리 판단용) */
    fun carSeatIds(): Set<Int> = profiles.filter { it.carSeat }.map { it.id }.toSet()

    /** 삭제. 마지막 사용 id가 삭제되면 null */
    fun delete(id: Int): ProfileBook {
        if (find(id) == null) return this
        return ProfileBook(profiles.filter { it.id != id }, if (lastUsedId == id) null else lastUsedId)
    }

    fun deleteAll(): ProfileBook = ProfileBook()

    /** 마지막 사용 id 기록. 없는 id면 변경 없음 */
    fun markUsed(id: Int): ProfileBook = if (find(id) != null) copy(lastUsedId = id) else this

    /** 마지막으로 쓴 프로필을 맨 앞에, 나머지는 슬롯 번호순 */
    fun sortedByRecent(): List<ChildProfile> {
        val last = lastUsedId?.let { find(it) }
        val rest = profiles.filter { it.id != last?.id }.sortedBy { it.id }
        return if (last != null) listOf(last) + rest else rest
    }

    /** 표시할 프로필 목록: 비어 있으면 가상 기본 프로필 하나 */
    fun displayList(): List<ChildProfile> = if (profiles.isEmpty()) listOf(ChildProfiles.DEFAULT_PROFILE) else sortedByRecent()
}

/**
 * 저장 형식(고정 v1): "v1;l=<마지막 id 또는 n>;p=<id>,<h>,<s>,<o>,<hs>,<t>,<a>,<말투 a|b>,<불러주기 0|1>,<별명 코드포인트 16진수를 '-'로 연결>"
 * (p= 항목은 프로필 수만큼 반복). 형식 예(별명 "가나"):
 * "v1;l=0;p=0,1,0,2,0,0,1,b,1,ac00-b098,0,1"  (11번째 칸=제복 요소, 12번째 칸=카시트 0|1.
 * 옛 10칸 줄은 제복 요소 0·카시트 끔, 옛 11칸 줄은 카시트 끔으로 읽는다 — r10 I 마이그레이션)
 * 별명은 16진 코드포인트 목록이라 구분자와 충돌하지 않는다.
 */
object ProfileCodec {
    const val VERSION = "v1"
    const val MAX_LENGTH = 2048
    private const val FIELDS = 10                // 옛 형식(제복 요소 없음, uniform=0)
    private const val FIELDS_WITH_UNIFORM = 11   // 별명 뒤에 제복 요소 칸(카시트 칸 없음 → 끔)
    private const val FIELDS_WITH_CAR_SEAT = 12  // r10 I 새 형식: 제복 요소 뒤에 카시트 칸(0|1)

    fun encodeBook(book: ProfileBook): String {
        try {
            val sb = StringBuilder(VERSION)
            val last = book.lastUsedId?.takeIf { book.find(it) != null }
            sb.append(";l=").append(last?.toString() ?: "n")
            for (p in book.profiles.take(ChildProfiles.MAX_PROFILES)) {
                val nick = ChildProfiles.sanitizeNickname(p.nickname) ?: continue
                if (p.id !in 0 until ChildProfiles.MAX_PROFILES) continue
                val s = CharacterOptions.sanitize(p.style)
                sb.append(";p=").append(p.id).append(',')
                    .append(s.hairColorIndex).append(',').append(s.skinColorIndex).append(',')
                    .append(s.outfitColorIndex).append(',').append(s.hairShapeIndex).append(',')
                    .append(s.toneIndex).append(',').append(s.accessoryIndex).append(',')
                    .append(ToneSetting.encode(p.tone)).append(',')
                    .append(if (p.speakNickname) '1' else '0').append(',')
                    .append(nick.map { Integer.toHexString(it.code) }.joinToString("-"))
                    .append(',').append(s.uniform)
                    .append(',').append(if (p.carSeat) '1' else '0')   // r10 I
            }
            return sb.toString()
        } catch (e: Exception) {
            return VERSION
        }
    }

    /** 어떤 입력이든 예외 없이 ProfileBook을 돌려준다. 잘못된 프로필은 버리고 가능한 것만 남긴다 */
    fun decodeBook(text: String?): ProfileBook {
        try {
            if (text == null || text.isEmpty() || text.length > MAX_LENGTH) return ProfileBook()
            val parts = text.split(';')
            if (parts[0] != VERSION) return ProfileBook()
            var lastRaw: String? = null
            val list = ArrayList<ChildProfile>()
            var segments = 0
            for (part in parts.drop(1)) {
                if (part.startsWith("l=")) {
                    if (lastRaw == null) lastRaw = part.substring(2)
                } else if (part.startsWith("p=")) {
                    segments++
                    if (segments > ChildProfiles.MAX_PROFILES) break
                    val p = decodeProfile(part.substring(2)) ?: continue
                    if (list.none { it.id == p.id }) list.add(p)
                }
            }
            val sorted = list.sortedBy { it.id }
            val last = lastRaw?.takeIf { it.length in 1..2 && it.all { c -> c in '0'..'9' } }?.toInt()
            return ProfileBook(sorted, if (last != null && sorted.any { it.id == last }) last else null)
        } catch (e: Exception) {
            return ProfileBook()
        }
    }

    private fun decodeProfile(s: String): ChildProfile? {
        val f = s.split(',')
        if (f.size != FIELDS && f.size != FIELDS_WITH_UNIFORM && f.size != FIELDS_WITH_CAR_SEAT) return null
        // 11번째 칸(제복 요소)은 숫자가 아니거나 비정상이면 프로필을 버리지 않고 0으로 보정한다. 옛 10칸 줄은 0
        val uniform = if (f.size >= FIELDS_WITH_UNIFORM) {
            val u = f[10]
            if (u.isNotEmpty() && u.length <= 3 && u.all { it in '0'..'9' }) u.toInt() else 0
        } else 0
        // r10 I: 12번째 칸(카시트)은 "1"일 때만 켬. 옛 10·11칸 줄·그 밖 값은 끔(프로필을 버리지 않는다)
        val carSeat = f.size == FIELDS_WITH_CAR_SEAT && f[11] == "1"
        val n = IntArray(7)
        for (i in 0 until 7) {
            val v = f[i]
            if (v.isEmpty() || v.length > 3 || !v.all { it in '0'..'9' }) return null
            n[i] = v.toInt()
        }
        val id = n[0]
        if (id !in 0 until ChildProfiles.MAX_PROFILES) return null
        val style = CharacterStyle(n[1], n[2], n[3], n[4], n[5], n[6], uniform)
        // 범위 밖 값은 프로필을 버리지 않고 sanitize로 0 보정한다
        val tone = when (f[7]) { "a" -> Tone.A; "b" -> Tone.B; else -> return null }
        val speak = when (f[8]) { "0" -> false; "1" -> true; else -> return null }
        val toks = f[9].split('-')
        if (toks.isEmpty() || toks.size > ChildProfiles.MAX_NICKNAME_CODEPOINTS) return null
        val sb = StringBuilder()
        for (t in toks) {
            if (t.isEmpty() || t.length > 4 || !t.all { it in '0'..'9' || it in 'a'..'f' }) return null
            sb.append(t.toInt(16).toChar())
        }
        val raw = sb.toString()
        val nick = ChildProfiles.sanitizeNickname(raw) ?: return null
        if (nick != raw) return null
        return ChildProfile(id, nick, CharacterOptions.sanitize(style), tone, speak, carSeat)
    }
}
