package com.crewguide.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView

/**
 * debug 전용(2026-11-13 디자인 담당): 미리보기 캡처 때 실제 View 경계로 배치를 점검해 텍스트로 남긴다.
 * - TOUCH: 누를 수 있는 View(클릭 가능·보임)의 dp 크기와 76dp 미만 여부
 * - ELLIPSIS: 말줄임(…)이 생긴 글자
 * - VCLIP: 글자 높이가 View 안쪽 높이보다 커서 잘림
 * - PCLIP: View가 조상(스크롤 제외)의 경계 밖으로 나가 잘림
 * - OVERLAP: 서로 조상/자손이 아닌 두 글자 View가 겹침(의도된 겹침인지 눈으로 다시 본다)
 * - CONTRAST: 글자색(currentTextColor)과 그 글자 View 영역에서 가장 많이 칠해진 픽셀색(=실제 바탕)의 대비. 4.5 미만이면 LOW
 *   (그림자·그라데이션 바탕은 최빈색 하나로 근사한다)
 * 좌표는 틀(root) 기준 px, 크기는 dp. 틀의 scale은 무시한다(배치 값 그대로).
 * DECOR: 이전 말풍선(지시 4, [TripBubbleColumn.DECOR_TAG])은 장식이라 잘림·대비·글자 겹침 집계에서 빼고 위치만 적는다(위쪽 페이드 마스크로 가려짐).
 * BUBBLE-*(2026-11-14): 이동 흐름 말풍선 칸([TripBubbleColumn])이 있으면 지금·이전 말풍선 모두를 실제로 그린 픽셀로 잰다.
 *   예전 감사는 글자 View끼리만 봐서(캐릭터는 그림 View) 말풍선이 캐릭터 머리·모자를 덮어도 겹침 0이었다.
 *   - BUBBLE-CHAR: 캐릭터 그림을 따로 그린 알파(보이는 부분)와 말풍선 칸을 따로 그린 알파(꼬리·그림자·페이드 반영)가 함께 칠해진 픽셀 수, 가장 가까운 가로 틈
 *   - BUBBLE-EDGE: 말풍선 상자가 무대 카드 왼쪽·오른쪽·아래 테두리 안쪽 8dp를 넘거나, 지금 말풍선이 칸 위로 잘림
 *   - BUBBLE-BUTTON: 말풍선 상자(칸 안 보이는 부분)와 같은 무대의 버튼 상자가 겹침
 *   셋 다 SUMMARY의 overlap에 더하고, 따로 bubbleCharPx·bubbleEdge·bubbleButton으로도 적는다.
 */
object PreviewLayoutAudit {
    const val MIN_TOUCH_DP = 76f

    /** scrollHidden: 스크롤 칸 안에 있으면서 지금 보이는 칸 밖(일부라도)으로 나간 View. 잘림이 아니라 스크롤해야 보이는 것 */
    private class Item(val v: View, val l: Int, val t: Int, val r: Int, val b: Int, val clipBy: String?, val scrollHidden: Boolean)

    fun audit(root: View, density: Float, bmp: Bitmap? = null): String {
        val items = ArrayList<Item>()
        walk(root, 0, 0, root, items, density)
        val out = StringBuilder()
        out.append("frame ${root.width}x${root.height} density=$density fontScale=${root.resources.configuration.fontScale}\n")
        var touchFail = 0; var clip = 0; var overlap = 0; var lowContrast = 0; var minContrast = 99.0; var scrollHidden = 0
        var decor = 0
        for (it in items) {
            val v = it.v
            val wDp = (it.r - it.l) / density; val hDp = (it.b - it.t) / density
            if (v.tag == TripBubbleColumn.DECOR_TAG) {
                decor++; out.append("DECOR ${label(v)} rect=${it.l},${it.t},${it.r},${it.b} alpha=${"%.2f".format(v.alpha)}\n"); continue
            }
            if (it.scrollHidden) {
                if (v is TextView && !v.text.isNullOrEmpty() || v.isClickable) { scrollHidden++; out.append("SCROLL ${label(v)} (스크롤해야 다 보임)\n") }
                continue
            }
            if (v.isClickable && v.isEnabled) {
                val ok = wDp >= MIN_TOUCH_DP && hDp >= MIN_TOUCH_DP
                if (!ok) touchFail++
                out.append("TOUCH ${if (ok) "OK " else "LOW"} ${"%.0f".format(wDp)}x${"%.0f".format(hDp)}dp ${label(v)} @${it.l},${it.t}\n")
            }
            if (v is TextView && !v.text.isNullOrEmpty()) {
                val lay = v.layout
                if (lay != null) {
                    for (i in 0 until lay.lineCount) if (lay.getEllipsisCount(i) > 0) { clip++; out.append("ELLIPSIS ${label(v)} line=$i\n"); break }
                    val inner = v.height - v.compoundPaddingTop - v.compoundPaddingBottom
                    if (lay.height > inner + 1) { clip++; out.append("VCLIP ${label(v)} text=${lay.height}px inner=${inner}px\n") }
                }
            }
            if (bmp != null && v is TextView && !v.text.isNullOrEmpty() && it.r > it.l && it.b > it.t) {
                val fg = v.currentTextColor
                val bg = dominant(bmp, it.l, it.t, it.r, it.b, fg)
                if (bg != null) {
                    val ratio = DesignRules.contrastRatio(fg and 0xFFFFFF, bg and 0xFFFFFF)
                    if (ratio < minContrast) minContrast = ratio
                    val ok = ratio >= DesignRules.MIN_TEXT_CONTRAST
                    if (!ok) lowContrast++
                    val hex = "fg=#%06X bg=#%06X".format(fg and 0xFFFFFF, bg and 0xFFFFFF)
                    out.append("CONTRAST ${if (ok) "OK " else "LOW"} ${"%.2f".format(ratio)} $hex ${label(v)}\n")
                }
            }
            if (it.clipBy != null && (it.r > it.l) && (v is TextView || v.isClickable)) {
                clip++; out.append("PCLIP ${label(v)} by ${it.clipBy} rect=${it.l},${it.t},${it.r},${it.b}\n")
            }
        }
        val texts = items.filter { !it.scrollHidden && it.v.tag != TripBubbleColumn.DECOR_TAG && it.v is TextView && !(it.v as TextView).text.isNullOrEmpty() && it.r > it.l && it.b > it.t }
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            val a = texts[i]; val b = texts[j]
            if (isAncestor(a.v, b.v) || isAncestor(b.v, a.v)) continue
            val ix = minOf(a.r, b.r) - maxOf(a.l, b.l); val iy = minOf(a.b, b.b) - maxOf(a.t, b.t)
            if (ix > 2 && iy > 2) { overlap++; out.append("OVERLAP ${label(a.v)} x ${label(b.v)} ${ix}x${iy}px\n") }
        }
        val bub = auditBubbles(items, density, out)
        if (bub != null) overlap += (if (bub[0] > 0) 1 else 0) + bub[1] + bub[2]
        val bubText = if (bub == null) "" else " bubbles=${bub[3]} bubbleCharPx=${bub[0]} bubbleEdge=${bub[1]} bubbleButton=${bub[2]}"
        out.append("SUMMARY touchLow=$touchFail clip=$clip overlap=$overlap contrastLow=$lowContrast scrollHidden=$scrollHidden decor=$decor minContrast=${"%.2f".format(minContrast)} views=${items.size}$bubText\n")
        return out.toString()
    }

    /** View 하나만 자기 크기로 그린 ARGB 픽셀(행 우선). 자식의 투명도·이동·칸의 페이드 마스크가 반영된다 */
    private fun pixelsOf(v: View): IntArray {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        bmp.recycle()
        return px
    }

    /**
     * 말풍선 칸마다 캐릭터·카드 테두리·버튼과의 겹침을 잰다. 칸이 없으면 null.
     * 결과 = [캐릭터와 함께 칠해진 픽셀 수, 테두리 위반 수, 버튼 겹침 수, 그려진 말풍선 수]
     */
    private fun auditBubbles(items: List<Item>, density: Float, out: StringBuilder): IntArray? {
        var found = false
        var charPx = 0; var edge = 0; var button = 0; var shown = 0
        val inset = (BUBBLE_EDGE_DP * density).toInt()
        for (it in items) {
            val col = it.v as? TripBubbleColumn ?: continue
            val stage = col.parent as? ViewGroup ?: continue
            if (col.width <= 0 || col.height <= 0) continue
            found = true
            // 무대(부모) 좌표 → 틀 좌표 변환값
            val sx = it.l - col.left; val sy = it.t - col.top
            val colPx = pixelsOf(col)
            val kids = (0 until col.childCount).map { i -> col.getChildAt(i) }.filter { c -> c.visibility == View.VISIBLE && c.alpha > 0f && c.width > 0 && c.height > 0 }
            shown += kids.size
            // 말풍선 상자(무대 좌표). 이동 중이면 translationY 반영
            fun rectOf(c: View) = intArrayOf(col.left + c.left, col.top + c.top + c.translationY.toInt(), col.left + c.right, col.top + c.bottom + c.translationY.toInt())
            for (i in 0 until stage.childCount) {
                val sib = stage.getChildAt(i)
                if (sib === col || sib.visibility != View.VISIBLE || sib.width <= 0 || sib.height <= 0) continue
                if (sib is CrewArtView) {
                    val artPx = pixelsOf(sib)
                    val vis = MaskOverlap.bounds(artPx, sib.width, sib.height, ART_ALPHA_MIN)
                    val total = MaskOverlap.count(artPx, sib.width, sib.height, sib.left, sib.top, colPx, col.width, col.height, col.left, col.top, ART_ALPHA_MIN, BUBBLE_ALPHA_MIN, null)
                    val gap = MaskOverlap.minRowGap(colPx, col.width, col.height, col.left, col.top, BUBBLE_ALPHA_MIN, artPx, sib.width, sib.height, sib.left, sib.top, ART_ALPHA_MIN)
                    charPx += total
                    val visText = if (vis == null) "없음" else "${sx + sib.left + vis[0]},${sy + sib.top + vis[1]},${sx + sib.left + vis[2]},${sy + sib.top + vis[3]}"
                    out.append("BUBBLE-CHAR ${if (total == 0) "OK " else "HIT"} overlapPx=$total minGapPx=${gap ?: "-"} charVisible=$visText bubbles=${kids.size}\n")
                    for (c in kids) {
                        val r = rectOf(c)
                        val n = MaskOverlap.count(artPx, sib.width, sib.height, sib.left, sib.top, colPx, col.width, col.height, col.left, col.top, ART_ALPHA_MIN, BUBBLE_ALPHA_MIN, r)
                        out.append("BUBBLE ${if (c.tag == TripBubbleColumn.DECOR_TAG) "prev" else "now "} ${label(c)} rect=${sx + r[0]},${sy + r[1]},${sx + r[2]},${sy + r[3]} alpha=${"%.2f".format(c.alpha)} charOverlapPx=$n\n")
                    }
                } else if (sib.isClickable) {
                    for (c in kids) {
                        val r = rectOf(c)
                        // 칸 밖으로 나간 부분은 그려지지 않는다(clipChildren)
                        val ix = minOf(r[2], col.right, sib.right) - maxOf(r[0], col.left, sib.left)
                        val iy = minOf(r[3], col.bottom, sib.bottom) - maxOf(r[1], col.top, sib.top)
                        if (ix > 0 && iy > 0) { button++; out.append("BUBBLE-BUTTON HIT ${label(c)} x ${label(sib)} ${ix}x${iy}px\n") }
                    }
                }
            }
            for (c in kids) {
                val r = rectOf(c)
                val why = ArrayList<String>()
                if (r[0] < inset) why += "왼쪽 ${r[0]}px"
                if (r[2] > stage.width - inset) why += "오른쪽 ${stage.width - r[2]}px"
                if (minOf(r[3], col.bottom) > stage.height - inset) why += "아래 ${stage.height - r[3]}px"
                if (c.tag != TripBubbleColumn.DECOR_TAG && r[1] < col.top) why += "위로 잘림 ${col.top - r[1]}px"
                if (why.isNotEmpty()) { edge++; out.append("BUBBLE-EDGE HIT ${label(c)} ${why.joinToString()} (테두리 안쪽 ${inset}px 기준)\n") }
            }
            if (kids.isNotEmpty()) {
                val l = kids.minOf { c -> rectOf(c)[0] }; val r = kids.maxOf { c -> rectOf(c)[2] }
                out.append("BUBBLE-EDGE margins left=${l}px right=${stage.width - r}px top=${col.top}px (무대 카드 안쪽 기준)\n")
            }
        }
        return if (found) intArrayOf(charPx, edge, button, shown) else null
    }

    /** 캐릭터 그림에서 '보인다'고 보는 알파(배치 규칙 [ArtSilhouette.ALPHA_MIN]과 같은 값) */
    private const val ART_ALPHA_MIN = ArtSilhouette.ALPHA_MIN
    /** 말풍선 칸에서 '칠해졌다'고 보는 알파(옅은 이전 말풍선·그림자도 포함) */
    private const val BUBBLE_ALPHA_MIN = 8
    /** 말풍선이 지켜야 할 무대 카드 테두리 안쪽 여백(dp) */
    private const val BUBBLE_EDGE_DP = 8

    /** 영역 안 최빈 픽셀색(글자색과 아주 가까운 색은 뺀다). 4px 간격 표본 */
    private fun dominant(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, fg: Int): Int? {
        val counts = HashMap<Int, Int>()
        val x0 = l.coerceIn(0, bmp.width - 1); val x1 = r.coerceIn(0, bmp.width); val y0 = t.coerceIn(0, bmp.height - 1); val y1 = b.coerceIn(0, bmp.height)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bmp.getPixel(x, y) or 0xFF000000.toInt()
                val dr = Color.red(p) - Color.red(fg); val dg = Color.green(p) - Color.green(fg); val db = Color.blue(p) - Color.blue(fg)
                if (dr * dr + dg * dg + db * db > 40 * 40) counts[p] = (counts[p] ?: 0) + 1
                x += 4
            }
            y += 4
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p: android.view.ViewParent? = b.parent
        while (p != null) { if (p === a) return true; p = p.parent }
        return false
    }

    private fun walk(v: View, ox: Int, oy: Int, root: View, out: MutableList<Item>, density: Float, clipRect: IntArray? = null, clipName: String? = null, viewport: IntArray? = null) {
        if (v.visibility != View.VISIBLE || v.alpha == 0f) return
        val l = ox + v.left; val t = oy + v.top
        val r = l + v.width; val b = t + v.height
        val tl = if (v === root) 0 else l; val tt = if (v === root) 0 else t
        val tr = tl + v.width; val tb = tt + v.height
        var clipBy: String? = null
        if (clipRect != null && (tl < clipRect[0] - 1 || tt < clipRect[1] - 1 || tr > clipRect[2] + 1 || tb > clipRect[3] + 1)) clipBy = clipName
        val hidden = viewport != null && (tl < viewport[0] - 1 || tt < viewport[1] - 1 || tr > viewport[2] + 1 || tb > viewport[3] + 1)
        out.add(Item(v, tl, tt, tr, tb, clipBy, hidden))
        if (v is ViewGroup) {
            val scroll = v is ScrollView || v is HorizontalScrollView
            val nextClip: IntArray? = when {
                scroll -> null
                v.clipChildren -> intArrayOf(tl, tt, tr, tb).let { c -> if (clipRect == null) c else intArrayOf(maxOf(c[0], clipRect[0]), maxOf(c[1], clipRect[1]), minOf(c[2], clipRect[2]), minOf(c[3], clipRect[3])) }
                else -> clipRect
            }
            val nextName = if (scroll) null else if (v.clipChildren) v.javaClass.simpleName else clipName
            val nextViewport = if (scroll) intArrayOf(tl, tt, tr, tb) else viewport
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                walk(c, tl - v.scrollX, tt - v.scrollY, root, out, density, nextClip, nextName, nextViewport)
            }
        }
    }

    private fun label(v: View): String {
        val name = v.javaClass.simpleName
        val txt = (v as? TextView)?.text?.toString() ?: v.contentDescription?.toString() ?: ""
        return "$name\"${txt.replace('\n', ' ').take(24)}\""
    }
}

/**
 * 두 그림(ARGB 픽셀, 행 우선)을 같은 좌표계에 놓고 보이는 부분끼리 겹치는지 세는 순수 계산(debug 감사용, Android 클래스 없음).
 * (ax, ay)·(bx, by) = 각 그림 왼쪽 위의 공통 좌표, aMin·bMin = 이 알파(0~255)를 넘으면 보인다고 본다.
 */
object MaskOverlap {
    private fun on(px: IntArray, i: Int, min: Int) = (px[i] ushr 24) > min

    /** 함께 보이는 픽셀 수. [clip] = 공통 좌표의 [l, t, r, b]가 있으면 그 안만 센다 */
    fun count(a: IntArray, aw: Int, ah: Int, ax: Int, ay: Int, b: IntArray, bw: Int, bh: Int, bx: Int, by: Int, aMin: Int, bMin: Int, clip: IntArray?): Int {
        var l = maxOf(ax, bx); var t = maxOf(ay, by); var r = minOf(ax + aw, bx + bw); var bt = minOf(ay + ah, by + bh)
        if (clip != null) { l = maxOf(l, clip[0]); t = maxOf(t, clip[1]); r = minOf(r, clip[2]); bt = minOf(bt, clip[3]) }
        var n = 0
        for (y in t until bt) for (x in l until r) {
            if (on(a, (y - ay) * aw + (x - ax), aMin) && on(b, (y - by) * bw + (x - bx), bMin)) n++
        }
        return n
    }

    /** 보이는 부분의 경계 [l, t, r, b](그림 안 좌표, r·b는 바깥쪽). 보이는 픽셀이 없으면 null */
    fun bounds(a: IntArray, aw: Int, ah: Int, aMin: Int): IntArray? {
        var l = aw; var t = ah; var r = -1; var b = -1
        for (y in 0 until ah) for (x in 0 until aw) if (on(a, y * aw + x, aMin)) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /**
     * 같은 행에서 왼쪽 그림(a)의 가장 오른쪽 보이는 픽셀과 오른쪽 그림(b)의 가장 왼쪽 보이는 픽셀 사이 빈 칸 수의 최솟값.
     * 겹치면 음수. 둘 다 보이는 행이 없으면 null(위아래로 완전히 떨어져 있음)
     */
    fun minRowGap(a: IntArray, aw: Int, ah: Int, ax: Int, ay: Int, aMin: Int, b: IntArray, bw: Int, bh: Int, bx: Int, by: Int, bMin: Int): Int? {
        var best: Int? = null
        for (y in maxOf(ay, by) until minOf(ay + ah, by + bh)) {
            var ar = -1
            for (x in aw - 1 downTo 0) if (on(a, (y - ay) * aw + x, aMin)) { ar = ax + x; break }
            if (ar < 0) continue
            var bl = -1
            for (x in 0 until bw) if (on(b, (y - by) * bw + x, bMin)) { bl = bx + x; break }
            if (bl < 0) continue
            val g = bl - ar - 1
            if (best == null || g < best) best = g
        }
        return best
    }
}
