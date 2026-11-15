package com.crewguide.probe

import kotlin.random.Random

// 2026-11-14 사용자 지시: 교통 상식을 무작위 순서로 낸다. 순수 로직(Android 클래스 없음). 저장은 VoiceTipStore(SharedPreferences, commit).
//  - 섞은 순서(덱)를 한 바퀴 다 돌 때까지 같은 문장을 다시 내지 않는다.
//  - 같은 주제가 [TripTips.MAX_RUN]번을 넘겨 연달아 나오지 않게 섞는다(바퀴가 바뀔 때도 이어서 센다).
//  - 새 바퀴의 첫 문장은 직전 바퀴의 마지막 문장과 다르다.
//  - 덱과 위치를 문자열로 저장해 앱을 다시 켜도 이어서 낸다. 저장값을 못 읽거나 깨졌으면 새로 섞는다.

/** 교통 상식 주제: ① 차에 탈 때 ② 길에서 */
enum class TipTopic { CAR, ROAD }

/** 섞은 순서([order] = 상식 번호의 순열)와 다음에 낼 위치([pos], 0..order.size) */
data class TipDeck(val order: List<Int>, val pos: Int)

object TripTips {
    /** 같은 주제 최대 연속 수 */
    const val MAX_RUN = 2
    private const val VERSION = "v1"

    /** 상식 목록이 바뀌면 저장된 덱을 버리기 위한 지문(문장 내용 기준, 기기 밖으로 보내지 않음) */
    fun fingerprint(texts: List<String>): Int = texts.joinToString("\n").hashCode()

    /**
     * 다음 상식 번호와 저장할 새 문자열. [saved]가 null·깨짐·목록 변경이면 새로 섞는다.
     * [topics] = 상식 번호별 주제(VoiceLines.TIPS 순서), [fp] = [fingerprint].
     */
    fun next(saved: String?, topics: List<TipTopic>, fp: Int, rnd: Random): Pair<Int, String> {
        require(topics.isNotEmpty())
        var deck = decode(saved, topics.size, fp)
        if (deck == null) {
            deck = TipDeck(shuffle(topics, rnd, avoidFirst = null, prevTail = emptyList()), 0)
        } else if (deck.pos >= deck.order.size) {
            val prev = deck.order
            deck = TipDeck(shuffle(topics, rnd, avoidFirst = prev.last(), prevTail = prev.map { topics[it] }), 0)
        }
        val idx = deck.order[deck.pos]
        return idx to encode(TipDeck(deck.order, deck.pos + 1), fp)
    }

    /**
     * 한 바퀴 순서를 만든다. 주제는 남은 수에 비례해 무작위로 고르되, 같은 주제 [MAX_RUN]번 연속이면 다른 주제를 고르고,
     * 고른 뒤에도 끝까지 규칙을 지킬 수 있는 주제만 고른다. 주제 안에서는 미리 섞은 순서대로 꺼낸다.
     * [prevTail] = 직전 바퀴의 주제 순서(끝의 연속 수를 이어 센다), [avoidFirst] = 직전 바퀴 마지막 번호(첫 자리에 두지 않는다).
     */
    fun shuffle(topics: List<TipTopic>, rnd: Random, avoidFirst: Int?, prevTail: List<TipTopic>): List<Int> {
        val pools = TipTopic.values().associateWith { t -> topics.indices.filter { topics[it] == t }.shuffled(rnd).toMutableList() }
        var last: TipTopic? = prevTail.lastOrNull()
        var run = if (last == null) 0 else prevTail.takeLastWhile { it == last }.size
        val out = ArrayList<Int>(topics.size)
        while (out.size < topics.size) {
            val cands = TipTopic.values().filter { t -> pools.getValue(t).isNotEmpty() }
            val ok = cands.filter { t ->
                val newRun = if (t == last) run + 1 else 1
                newRun <= MAX_RUN && feasible(pools, t, newRun)
            }
            val choose = ok.ifEmpty { cands }   // 목록이 한쪽으로 크게 치우친 경우만(지금 목록에서는 일어나지 않음)
            val total = choose.sumOf { pools.getValue(it).size }
            var r = rnd.nextInt(total)
            var pick = choose.last()
            for (t in choose) { val n = pools.getValue(t).size; if (r < n) { pick = t; break }; r -= n }
            out += pools.getValue(pick).removeAt(0)
            run = if (pick == last) run + 1 else 1
            last = pick
        }
        if (avoidFirst != null && out.size > 1 && out[0] == avoidFirst) {
            // 같은 주제 안에서 자리를 바꾸면 주제 순서(연속 규칙)는 그대로다
            val t = topics[out[0]]
            val j = (1 until out.size).firstOrNull { topics[out[it]] == t } ?: 1
            out[0] = out[j].also { out[j] = out[0] }
        }
        return out
    }

    /** [pick]을 하나 꺼내 연속 [newRun]이 된 뒤, 남은 것을 규칙대로 놓을 수 있는가(두 주제) */
    private fun feasible(pools: Map<TipTopic, List<Int>>, pick: TipTopic, newRun: Int): Boolean {
        val same = pools.getValue(pick).size - 1
        val other = TipTopic.values().filter { it != pick }.sumOf { pools.getValue(it).size }
        return same <= (MAX_RUN - newRun) + MAX_RUN * other && other <= MAX_RUN * (same + 1)
    }

    fun encode(deck: TipDeck, fp: Int): String = "$VERSION;${deck.order.size};$fp;${deck.pos};${deck.order.joinToString(",")}"

    /** 저장 문자열 → 덱. 버전·개수·지문이 다르거나 순열이 아니거나 위치가 범위 밖이면 null */
    fun decode(s: String?, size: Int, fp: Int): TipDeck? {
        if (s.isNullOrBlank()) return null
        val p = s.split(";")
        if (p.size != 5 || p[0] != VERSION) return null
        if (p[1].toIntOrNull() != size || p[2].toIntOrNull() != fp) return null
        val pos = p[3].toIntOrNull() ?: return null
        val order = p[4].split(",").map { it.toIntOrNull() ?: return null }
        if (order.size != size || order.toSet() != (0 until size).toSet()) return null
        if (pos !in 0..size) return null
        return TipDeck(order, pos)
    }
}
