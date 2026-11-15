package com.crewguide.probe

/**
 * v3 반복 4(P1): 설정 화면 틀(고정 머리 | 스크롤 본문 | 고정 하단 띠)의 순수 규칙.
 *
 * 예전 구조는 [무대로 돌아가기]를 본문 위에 띄웠다(overlay). 그러면 본문 보이는 높이 = 창 높이이고,
 * 버튼이 덮은 부분은 스크롤을 끝까지 내려도 버튼 뒤에 남는다(마지막 항목이 버튼 아래에 숨을 수 있음).
 * 새 구조는 머리·띠가 본문의 형제라 본문 높이 = 창 - 머리 - 띠. 스크롤 끝에서 마지막 항목의 아래 변 = 본문 아래 변 = 띠 위 변.
 * 실제 화면은 LinearLayout(weight)이 이 규칙대로 배치한다. 이 객체는 그 계약을 단위 테스트로 고정한다.
 */
object SettingsFrame {
    const val DIVIDER_DP = 2
    const val FOOTER_PAD_DP = 8
    const val BACK_MIN_WIDTH_DP = 320

    /** 본문(스크롤) 보이는 높이 */
    fun bodyViewport(windowPx: Int, headerPx: Int, footerPx: Int): Int = maxOf(0, windowPx - headerPx - footerPx)

    /** 스크롤 최대값 */
    fun maxScroll(contentPx: Int, viewportPx: Int): Int = maxOf(0, contentPx - viewportPx)

    /**
     * 스크롤을 끝까지 내렸을 때 마지막 항목(본문 내 [itemTop, itemBottom))이 가려지지 않고 모두 보이는가.
     * [overlayTopPx, overlayBottomPx)는 창 좌표에서 본문 위에 떠 있는 요소(새 구조에서는 없음 = 빈 구간).
     */
    fun lastItemFullyVisible(
        windowPx: Int, headerPx: Int, footerPx: Int, contentPx: Int, itemTop: Int, itemBottom: Int,
        overlayTopPx: Int = 0, overlayBottomPx: Int = 0,
    ): Boolean {
        val vp = bodyViewport(windowPx, headerPx, footerPx)
        val sy = maxScroll(contentPx, vp)
        val top = headerPx + itemTop - sy
        val bottom = headerPx + itemBottom - sy
        if (top < headerPx || bottom > headerPx + vp) return false
        val overlaps = overlayBottomPx > overlayTopPx && top < overlayBottomPx && bottom > overlayTopPx
        return !overlaps
    }
}

/**
 * v3 반복 6(P1-1): 설정 2열 균형. 왼쪽 = 아이 관리, 오른쪽 = 음성 → 기록·초기화 → 앱 정보(위에서 아래).
 * 글꼴 2.0에서 오른쪽 열이 왼쪽보다 훨씬 길어져(긴 설명 글) 왼쪽 아래가 비고 오른쪽만 길게 스크롤되던 것을 고친다.
 * 오른쪽 열의 '아래쪽' 카드부터 왼쪽 열 아래로 옮겨, 두 열 중 긴 쪽의 높이가 가장 작아지는 개수를 고른다.
 * 오른쪽에는 최소 1장을 남긴다. 줄어들지 않으면 옮기지 않는다(글꼴 1.0처럼 이미 맞으면 배치 그대로). 높이는 실측(px)이다.
 */
object SettingsColumns {
    /**
     * @param leftBase 왼쪽 열 기본 카드(아이 관리) 높이
     * @param atRight 오른쪽 카드들의 높이(오른쪽 열 폭에서 잰 값, 위→아래 순서)
     * @param atLeft 같은 카드를 왼쪽 열 폭에서 잰 높이(같은 순서)
     * @param gap 카드 사이 간격(각 카드 아래 여백)
     * @return 오른쪽 열 맨 아래에서부터 왼쪽으로 옮길 카드 수
     */
    fun moveCount(leftBase: Int, atRight: List<Int>, atLeft: List<Int>, gap: Int): Int {
        require(atRight.size == atLeft.size)
        val n = atRight.size
        fun tallest(k: Int): Int {
            val l = leftBase + gap + (n - k until n).sumOf { atLeft[it] + gap }
            val r = (0 until n - k).sumOf { atRight[it] + gap }
            return maxOf(l, r)
        }
        var best = 0
        var bestH = tallest(0)
        for (k in 1 until n) {
            val h = tallest(k)
            if (h < bestH) { best = k; bestH = h }
        }
        return best
    }
}
