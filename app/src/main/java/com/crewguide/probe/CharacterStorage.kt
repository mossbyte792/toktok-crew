package com.crewguide.probe

import android.content.Context

/**
 * 캐릭터 꾸미기 저장 형식(순수 로직, Android 클래스 없음). 저장 항목은 7개 인덱스뿐이다(u=제복 요소, 없으면 0).
 * 형식 예: "v1;h=0;s=0;o=0;hs=0;t=0;a=0;u=0"
 */
object CharacterCodec {
    const val VERSION = "v1"
    const val MAX_LENGTH = 128
    private const val MAX_DIGITS = 3
    private val RECOGNIZED_KEYS = listOf("h", "s", "o", "hs", "t", "a", "u")

    fun encodeStyle(style: CharacterStyle): String {
        val s = CharacterOptions.sanitize(style)
        return "$VERSION;h=${s.hairColorIndex};s=${s.skinColorIndex};o=${s.outfitColorIndex};" +
            "hs=${s.hairShapeIndex};t=${s.toneIndex};a=${s.accessoryIndex};u=${s.uniform}"
    }

    /** 어떤 입력이든 예외 없이 sanitize를 통과한 style을 돌려준다. 문제 있는 항목은 0(기본값)으로 보정 */
    fun decodeStyle(text: String?): CharacterStyle {
        try {
            if (text == null || text.isEmpty() || text.length > MAX_LENGTH) return CharacterStyle.DEFAULT
            val parts = text.split(';')
            if (parts.isEmpty() || parts[0] != VERSION) return CharacterStyle.DEFAULT
            val map = HashMap<String, Int>()
            for (p in parts.drop(1)) {
                val eq = p.indexOf('=')
                if (eq <= 0) continue
                val key = p.substring(0, eq)
                val v = p.substring(eq + 1)
                if (v.isEmpty() || v.length > MAX_DIGITS || !v.all { it in '0'..'9' }) continue
                if (!map.containsKey(key)) map[key] = v.toInt()
            }
            // 인식 가능한 키가 하나도 파싱되지 않았으면 새 기본값. 롤백: 이 줄을 지우면 CharacterStyle()이 된다
            if (RECOGNIZED_KEYS.none { map.containsKey(it) }) return CharacterStyle.DEFAULT
            return CharacterOptions.sanitize(
                CharacterStyle(
                    map["h"] ?: 0, map["s"] ?: 0, map["o"] ?: 0,
                    map["hs"] ?: 0, map["t"] ?: 0, map["a"] ?: 0,
                    map["u"] ?: 0,
                ),
            )
        } catch (e: Exception) {
            return CharacterStyle.DEFAULT
        }
    }
}

/**
 * 무대 안내자 승무원 기본 모습 이전 규칙(순수 로직, Android 클래스 없음).
 * 2026-11-08 ch 결정 2(F3): 기존 사용자의 저장값을 덮어쓰지 않는다. 이전 표시만 남기고 style 문자열은 그대로 둔다.
 * - 신규 설치(저장값 없음): 승인된 기본 스타일(CharacterStyle.DEFAULT: 옷 색 0, 제복 5)을 화면에 쓴다. 저장값은 만들지 않는다.
 * - 기존 사용자(저장값 있음): 저장 문자열을 다시 쓰지 않는다(읽을 수 없는 버전·손상 값도 원본 그대로 보존).
 * - 이미 이전된 데이터: 아무것도 쓰지 않는다. 몇 번을 실행해도 결과가 같다(멱등).
 * - 표시 쓰기가 실패해도 style 값은 건드리지 않으므로 지워지거나 초기화되지 않는다.
 * 복구 불가: 이 수정 전 버전에서 이미 이전된 기기는 옷 색·제복 원래 값의 기록이 없어 되돌릴 수 없다(0·5로 남음).
 */
object GuideStyleMigration {
    const val MIGRATED_KEY = "guide_style_migrated_v1"

    /** 이전 결과: style = 화면에 쓸 값, rewriteStyle = 저장 style 문자열을 다시 쓸지(항상 false), markMigrated = 표시를 남길지 */
    data class Plan(val style: CharacterStyle, val rewriteStyle: Boolean, val markMigrated: Boolean)

    fun plan(stored: String?, alreadyMigrated: Boolean): Plan = Plan(
        style = if (stored == null) CharacterStyle.DEFAULT else CharacterCodec.decodeStyle(stored),
        rewriteStyle = false,
        markMigrated = !alreadyMigrated,
    )

    /** 기존 사용자 값 보존: 아무 필드도 바꾸지 않는다(이전 버전은 옷 색 0·제복 5로 덮어썼다) */
    fun migrate(style: CharacterStyle): CharacterStyle = style
}

/** 이 앱 전용 SharedPreferences(MODE_PRIVATE)에 style 문자열 하나만 저장하는 얇은 래퍼. 실패하면 DEFAULT */
object CharacterStore {
    const val FILE_NAME = "crewguide_probe_character"
    const val KEY_STYLE = "character_style_v1"

    // 2026-11-08 F3: 이전 표시만 남기고 저장 style은 다시 쓰지 않는다(GuideStyleMigration.plan)
    fun load(context: Context): CharacterStyle = try {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val p = GuideStyleMigration.plan(prefs.getString(KEY_STYLE, null), prefs.contains(GuideStyleMigration.MIGRATED_KEY))
        if (p.markMigrated) {
            try { prefs.edit().putBoolean(GuideStyleMigration.MIGRATED_KEY, true).apply() } catch (e: Exception) { /* 표시 실패: 다음 실행에 다시 시도, style 불변 */ }
        }
        p.style
    } catch (e: Exception) {
        CharacterStyle.DEFAULT
    }

    fun save(context: Context, style: CharacterStyle) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_STYLE, CharacterCodec.encodeStyle(CharacterOptions.sanitize(style))).apply()
        } catch (e: Exception) {
            // 저장 실패는 화면 흐름에 영향을 주지 않는다
        }
    }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().remove(KEY_STYLE).apply()
        } catch (e: Exception) {
            // 무시
        }
    }
}
