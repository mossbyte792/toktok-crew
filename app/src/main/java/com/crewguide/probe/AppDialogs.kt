package com.crewguide.probe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 2026-11-14 루프 r1 K2: 앱 확인 대화상자(앱 나가기·알림 안내·이동 끝내기)를 시스템 기본 버튼 대신 앱 모양으로 그린다.
 * 둥근 카드(24dp) + DuoButton(둥근 16dp, 높이 76dp 이상): 확인 = 주버튼(PRIMARY, 파랑·흰 글자), 취소 = 중립(NEUTRAL, 회색 계열·본문색 글자), 글꼴 Jua.
 * 색·모양은 DuoTokens·Palette 토큰만 쓴다. 동작(취소 = 바깥 누름·뒤로가기 = onCancel)은 예전 AlertDialog와 같다.
 */
object AppDialogs {
    /** 버튼 순서·종류(순수 값, 테스트용): 왼쪽 취소(중립), 오른쪽 확인(주버튼) */
    val BUTTON_KINDS: List<DuoTokens.Kind> = listOf(DuoTokens.Kind.NEUTRAL, DuoTokens.Kind.PRIMARY)

    /** r10(11-15 사용자 지시): 카드 바깥 대화상자 패널의 흰 사각 바탕을 지운다. false면 예전 모양 */
    const val ROUND_CARD_ONLY = true

    fun confirm(ctx: Context, message: String, yes: String, no: String,
                onYes: () -> Unit, onNo: () -> Unit, onCancel: () -> Unit): AlertDialog {
        val dp = { v: Int -> DuoDraw.dp(ctx, v) }
        val card = DuoCard(ctx)
        val jua = StageFonts.typeface(ctx, StageFonts.FontRole.BIG_BUTTON)
        card.addView(DuoText.body(ctx, message).apply { typeface = jua; setPadding(0, 0, 0, dp(DuoTokens.GRID_DP * 3)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val noBtn = DuoButton(ctx, BUTTON_KINDS[0]).apply { text = no; typeface = jua; minWidth = dp(160); minimumWidth = dp(160) }
        val yesBtn = DuoButton(ctx, BUTTON_KINDS[1]).apply { text = yes; typeface = jua; minWidth = dp(160); minimumWidth = dp(160) }
        row.addView(noBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(DuoTokens.GRID_DP * 2) })
        row.addView(yesBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = AlertDialog.Builder(ctx).setView(card).setOnCancelListener { onCancel() }.create()
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))   // 둥근 카드가 보이게(시스템 사각 바탕 없음)
        // r10 사용자 지시(11-15, release 첫 실행 캡처): 창 바탕을 투명하게 해도 AlertDialog 안쪽 패널(parentPanel·customPanel 등)이
        // 흰 사각 바탕·여백을 따로 그려 '흰 사각 틀 안의 둥근 카드'로 보였다 → 보일 때 카드 바깥 패널들의 바탕·여백을 지워 둥근 카드만 남긴다.
        if (ROUND_CARD_ONLY) d.setOnShowListener {
            val decor = d.window?.decorView
            var p: android.view.ViewParent? = card.parent
            while (p is View && p !== decor) {
                val v: View = p
                v.background = null
                v.setPadding(0, 0, 0, 0)
                p = v.parent
            }
        }
        noBtn.setOnClickListener { d.dismiss(); onNo() }
        yesBtn.setOnClickListener { d.dismiss(); onYes() }
        return d
    }
}
