package com.crewguide.probe

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat

/**
 * 무대 글꼴 교체 지점. 글꼴을 바꾸려면 이 파일의 [StageFonts.typeface]/[StageFonts.loadDisplay] 한 곳만 고친다.
 * 큰 글꼴(자막·큰 버튼 5종)은 Jua(res/font/jua_regular.ttf, OFL 1.1, 한글 KS X 1001 2350자+영문·숫자·기호 서브셋)이며,
 * 읽기에 실패하면 시스템 굵은 글꼴로 대체한다. 나머지 역할은 시스템 글꼴이다.
 * Jua는 굵기가 한 가지라 호출처는 대입(typeface = ...)만 한다(setTypeface(font, BOLD)로 가짜 굵게를 덧입히지 않는다).
 * 롤백: [loadDisplay]가 Typeface.DEFAULT_BOLD를 바로 반환하게 바꾸면 이전 상태와 같다.
 */
object StageFonts {
    /** 글꼴이 쓰이는 자리 */
    enum class FontRole { CAPTION, BIG_BUTTON, TITLE, OTHER }

    /** 큰 글꼴(굵은 표시용)을 쓰는 역할인가. 순수 함수(Android 클래스 사용 금지) */
    fun usesDisplayFont(role: FontRole): Boolean = when (role) {
        FontRole.CAPTION, FontRole.BIG_BUTTON, FontRole.TITLE -> true
        FontRole.OTHER -> false
    }

    // 캐시: 메인 스레드에서만 호출한다(동기화 없음). 실패해도 '시도함'으로 기록해 다시 읽지 않는다.
    private var displayTried = false
    private var displayCached: Typeface? = null

    /** 역할별 글꼴. 여기를 바꾸면 전체 글꼴이 바뀐다. 메인 스레드에서만 호출한다. */
    fun typeface(context: Context, role: FontRole): Typeface =
        if (usesDisplayFont(role)) loadDisplay(context) else Typeface.DEFAULT

    /** Jua를 한 번만 읽는다. null이거나 예외면 시스템 굵은 글꼴. 실패 로그는 한 번만 남긴다. */
    private fun loadDisplay(context: Context): Typeface {
        if (displayTried) return displayCached ?: Typeface.DEFAULT_BOLD
        displayTried = true
        val loaded: Typeface? = try {
            ResourcesCompat.getFont(context, R.font.jua_regular)
        } catch (e: Resources.NotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
        displayCached = loaded
        if (loaded == null) Log.w("StageFonts", "[무대] 글꼴 로드 실패 → 시스템 굵은 글꼴")
        return loaded ?: Typeface.DEFAULT_BOLD
    }
}
