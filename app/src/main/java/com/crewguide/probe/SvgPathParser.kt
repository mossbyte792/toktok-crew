package com.crewguide.probe

/** SVG 경로 명령(절대좌표로 정규화). Android 클래스를 쓰지 않는 순수 로직 */
sealed class PathCmd {
    data class MoveTo(val x: Float, val y: Float) : PathCmd()
    data class LineTo(val x: Float, val y: Float) : PathCmd()
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCmd()
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathCmd()
    object Close : PathCmd()
}

/**
 * SVG path 문자열(d)을 절대좌표 명령 목록으로 바꾼다.
 * 지원: M/m L/l H/h V/v C/c Q/q Z/z. 명령 뒤 좌표가 반복되면 같은 명령을 반복한다(M/m 뒤 반복은 LineTo).
 * 지원하지 않는 명령(A, S, T 등)·형식 오류·좌표 부족이면 예외 없이 빈 목록을 돌려준다(부분 결과 없음).
 */
object SvgPathParser {

    fun parse(d: String): List<PathCmd> {
        return try {
            parseOrNull(d) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private class Reader(private val s: String) {
        var pos = 0
        fun atEnd(): Boolean = pos >= s.length
        fun next(): Char { val c = s[pos]; pos++; return c }

        fun skipSep() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun peekNumberStart(): Boolean {
            if (pos >= s.length) return false
            val c = s[pos]
            return (c in '0'..'9') || c == '-' || c == '+' || c == '.'
        }

        /** 숫자 하나를 읽는다. 형식이 틀리면 null(위치는 바꾸지 않음) */
        fun readNumber(): Float? {
            val start = pos
            var p = pos
            if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
            var digits = 0
            while (p < s.length && s[p] in '0'..'9') { p++; digits++ }
            if (p < s.length && s[p] == '.') {
                p++
                while (p < s.length && s[p] in '0'..'9') { p++; digits++ }
            }
            if (digits == 0) return null
            if (p < s.length && (s[p] == 'e' || s[p] == 'E')) {
                var q = p + 1
                if (q < s.length && (s[q] == '+' || s[q] == '-')) q++
                var ed = 0
                while (q < s.length && s[q] in '0'..'9') { q++; ed++ }
                if (ed == 0) return null
                p = q
            }
            val v = s.substring(start, p).toFloatOrNull() ?: return null
            if (v.isNaN() || v.isInfinite()) return null
            pos = p
            return v
        }
    }

    private fun parseOrNull(d: String): List<PathCmd>? {
        val out = ArrayList<PathCmd>()
        val r = Reader(d)
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var first = true
        while (true) {
            r.skipSep()
            if (r.atEnd()) break
            val c = r.next()
            val lower = c.lowercaseChar()
            if (lower != 'm' && lower != 'l' && lower != 'h' && lower != 'v' &&
                lower != 'c' && lower != 'q' && lower != 'z'
            ) return null
            if (first && lower != 'm') return null
            first = false
            val rel = c.isLowerCase()
            if (lower == 'z') {
                out.add(PathCmd.Close)
                cx = sx
                cy = sy
                continue
            }
            val argc = when (lower) {
                'm', 'l' -> 2
                'h', 'v' -> 1
                'c' -> 6
                else -> 4 // 'q'
            }
            var firstSet = true
            do {
                val a = FloatArray(argc)
                for (k in 0 until argc) {
                    r.skipSep()
                    a[k] = r.readNumber() ?: return null
                }
                val ox = if (rel) cx else 0f
                val oy = if (rel) cy else 0f
                when (lower) {
                    'm' -> {
                        val x = a[0] + ox
                        val y = a[1] + oy
                        if (firstSet) {
                            out.add(PathCmd.MoveTo(x, y))
                            sx = x
                            sy = y
                        } else {
                            out.add(PathCmd.LineTo(x, y))
                        }
                        cx = x
                        cy = y
                    }
                    'l' -> {
                        val x = a[0] + ox
                        val y = a[1] + oy
                        out.add(PathCmd.LineTo(x, y))
                        cx = x
                        cy = y
                    }
                    'h' -> {
                        val x = a[0] + ox
                        out.add(PathCmd.LineTo(x, cy))
                        cx = x
                    }
                    'v' -> {
                        val y = a[0] + oy
                        out.add(PathCmd.LineTo(cx, y))
                        cy = y
                    }
                    'c' -> {
                        out.add(PathCmd.CubicTo(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy, a[4] + ox, a[5] + oy))
                        cx = a[4] + ox
                        cy = a[5] + oy
                    }
                    else -> { // 'q'
                        out.add(PathCmd.QuadTo(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy))
                        cx = a[2] + ox
                        cy = a[3] + oy
                    }
                }
                firstSet = false
                r.skipSep()
            } while (r.peekNumberStart())
        }
        return out
    }
}
