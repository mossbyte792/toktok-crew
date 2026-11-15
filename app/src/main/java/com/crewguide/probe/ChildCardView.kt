package com.crewguide.probe

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 아이 카드 한 장(132x164dp 터치 영역 전체). 둥근 바탕 안 별명 첫 글자(장식, [ChildInitial]) + 별명 한 줄.
 * 2026-11-10 4차 결정 A: 옛 벡터 승무원 얼굴(SvgCharacterView)을 쓰지 않는다. 프로필의 style 저장값은 읽지도 바꾸지도 않는다.
 * 선택은 굵은 파랑 윤곽 + 오른쪽 위 체크 배지 + contentDescription으로 알린다. 상태를 저장하지 않고 bind로 받아 그리기만 한다.
 * 외부 이미지·네트워크·저장 없음. 모양/색 값은 ChildCardLayout이 정한다.
 */
class ChildCardView(
    context: Context,
    avatarBgRgb: Int,
    /** v3 반복 5(S02 2영역): 넓은 카드 — 아바타 88dp, 별명 최대 두 줄(넘치면 말줄임). 기본 false = 기존 132dp 한 줄 */
    private val wide: Boolean = false,
    onTap: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    // 이름 주의: GradientDrawable의 멤버(color 등)와 겹치지 않도록 cardBg로 부르고, apply 블록을 쓰지 않는다
    private val cardBg = GradientDrawable()
    private val initialView = TextView(context)
    private val nameView = TextView(context)
    private val badge = ImageView(context)
    private var shownSelected: Boolean? = null

    init {
        cardBg.shape = GradientDrawable.RECTANGLE
        cardBg.cornerRadius = dp(ChildCardLayout.RADIUS_DP).toFloat()
        background = cardBg
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onTap() }

        // 아바타: 원형 파스텔 배경(장식) 안에 별명 첫 글자. 카드 설명이 별명을 읽으므로 이 원은 낭독하지 않는다
        val avatar = FrameLayout(context)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(Palette.argb(avatarBgRgb))
        avatar.background = circle
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatar.clipToOutline = true
        avatar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // 글자 크기는 dp로 고정한다: 원(96dp) 안의 장식이라 글꼴 배율로 원 밖으로 넘치지 않게 한다(별명 줄은 sp로 커진다)
        initialView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ChildCardLayout.INITIAL_DP)
        initialView.typeface = StageFonts.typeface(context, StageFonts.FontRole.TITLE)
        initialView.gravity = Gravity.CENTER
        initialView.includeFontPadding = false
        initialView.setTextColor(Palette.argb(ChildInitial.TEXT_RGB))
        initialView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        avatar.addView(initialView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        nameView.textSize = ChildCardLayout.NAME_SP
        // v4.1: 별명은 최대 8자·띄어쓰기 없음 → 두 줄 허용은 "Yuna888/8"처럼 글자 중간 꺾임만 만들었다(글꼴 2.0, 에뮬). 넓은 배치도 한 줄 + 자동 크기
        DuoText.fitOneLine(nameView, ChildCardLayout.NAME_SP)
        nameView.ellipsize = TextUtils.TruncateAt.END
        nameView.gravity = Gravity.CENTER
        nameView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.setPadding(dp(ChildCardLayout.SIDE_PAD_DP), dp(ChildCardLayout.AVATAR_TOP_DP), dp(ChildCardLayout.SIDE_PAD_DP), if (wide) dp(StageSplit.CARD_BOTTOM_PAD_DP) else 0)
        val avatarPx = dp(if (wide) StageSplit.CARD_AVATAR_DP else ChildCardLayout.AVATAR_DP)
        column.addView(avatar, LinearLayout.LayoutParams(avatarPx, avatarPx))
        val nameLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nameLp.topMargin = dp(ChildCardLayout.NAME_GAP_DP)
        column.addView(nameView, nameLp)
        addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (wide) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT))
        if (wide) minimumHeight = dp(StageSplit.CARD_MIN_H_DP)

        // 선택 배지: 파랑(밤: 기존 활성 색) 원 + 체크. 색은 선택 때만 보이므로 selected=true 색으로 한 번 만든다
        val night = Palette.current.night
        val on = ChildCardLayout.colors(night, true)
        val badgeBg = GradientDrawable()
        badgeBg.shape = GradientDrawable.OVAL
        badgeBg.setColor(Palette.argb(on.badgeFill))
        badge.background = badgeBg
        val pad = dp(ChildCardLayout.BADGE_ICON_PAD_DP)
        badge.setPadding(pad, pad, pad, pad)
        badge.setImageDrawable(IconDrawable(IconKind.CHECK, Palette.argb(on.badgeCheck)))
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        badge.visibility = View.GONE
        val badgeLp = FrameLayout.LayoutParams(dp(ChildCardLayout.BADGE_DP), dp(ChildCardLayout.BADGE_DP), Gravity.TOP or Gravity.END)
        badgeLp.topMargin = dp(ChildCardLayout.BADGE_INSET_DP)
        badgeLp.marginEnd = dp(ChildCardLayout.BADGE_INSET_DP)
        addView(badge, badgeLp)
    }

    /** 목록이 바뀌지 않아도 매번 불러도 되는 가벼운 갱신(값이 같으면 다시 만들지 않음) */
    fun bind(label: String, selected: Boolean, night: Boolean) {
        val c = ChildCardLayout.colors(night, selected)
        cardBg.setColor(Palette.argb(c.fill))
        cardBg.setStroke(dp(ChildCardLayout.strokeDp(selected)), Palette.argb(c.outline))
        if (nameView.text.toString() != label) nameView.text = label
        nameView.setTextColor(Palette.argb(c.text))
        val initial = ChildInitial.of(label)
        if (initialView.text.toString() != initial) initialView.text = initial
        if (shownSelected != selected) {
            shownSelected = selected
            badge.visibility = if (selected) View.VISIBLE else View.GONE
        }
        contentDescription = ChildCardLayout.description(label, selected)
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) StageButtons.PRESSED_ALPHA else 1f
    }
}
