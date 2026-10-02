package com.example.data.favorite

import com.example.source.ComicChapter
import com.example.source.anilist.TitleNormalizer
import java.text.Normalizer

/** Cross-source identity uses unique titles or explicit episode numbers, never list positions or IDs. */
object ComicChapterMatching {
    fun mapping(old: List<ComicChapter>, fresh: List<ComicChapter>): Map<String, ComicChapter> {
        val result = linkedMapOf<String, ComicChapter>()
        val used = hashSetOf<String>()
        fun pair(key: (ComicChapter) -> String?) {
            val oldGroups = old.mapNotNull { c -> key(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
            val newGroups = fresh.mapNotNull { c -> key(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
            oldGroups.forEach { (identity, group) ->
                val to = newGroups[identity]?.singleOrNull()
                val from = group.singleOrNull()
                if (from != null && to != null && from.id !in result && to.id !in used && compatibleVolumes(from, to)) {
                    result[from.id] = to
                    used.add(to.id)
                }
            }
        }
        pair { c -> TitleNormalizer.compact(c.title).takeIf { it.isNotBlank() }?.let { "${volume(c)}|$it" } }
        pair { c -> number(c.title)?.let { "${volume(c)}|$it" } }
        // Some sources omit volume names. Only globally unique numbers may bridge that omission.
        pair { c -> number(c.title) }
        return result
    }

    private fun volume(c: ComicChapter): String {
        val inline = Regex("(?i)第\\s*(\\d+)\\s*[卷巻]|vol(?:ume)?\\.?\\s*(\\d+)").find(c.title)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val raw = c.volume?.trim()?.takeIf { it.isNotBlank() } ?: inline.orEmpty()
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val digits = Regex("(?i)^(?:第\\s*)?(\\d+)(?:\\s*[卷巻])?$|^(?:vol(?:ume)?\\.?\\s*)(\\d+)$").matchEntire(normalized)
        return digits?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()?.toString()
            ?: TitleNormalizer.compact(raw)
    }

    private fun compatibleVolumes(a: ComicChapter, b: ComicChapter): Boolean =
        volume(a).isBlank() || volume(b).isBlank() || volume(a) == volume(b)

    private fun number(raw: String): String? {
        val title = Regex("第\\s*([零〇一二两兩三四五六七八九十百千]+)\\s*([话話回章])")
            .replace(Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()) { match ->
                chineseNumber(match.groupValues[1])?.let { "第$it${match.groupValues[2]}" } ?: match.value
            }
        if (Regex("(?i)番外|特別|特别|extra|special|omake|附录|附錄|上篇|下篇|前篇|后篇|後篇|上半|下半|part|[（(]\\s*[上下前后後]\\s*[）)]").containsMatchIn(title)) return null
        val explicit = Regex("(?i)第\\s*(\\d+(?:\\.\\d+)?)\\s*[话話回章]|(?<![\\p{L}\\p{N}])(?:chapter\\s*|ch\\.?\\s*|episode\\s*|ep\\.?\\s*)(\\d+(?:\\.\\d+)?)").find(title)
        val bare = Regex("^(\\d+(?:\\.\\d+)?)(?:$|\\s|[话話回章:：])").find(title)
        val value = explicit?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: bare?.groupValues?.get(1) ?: return null
        return value.takeIf { it.length <= 16 }?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
    }

    private fun chineseNumber(raw: String): Int? {
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (raw.all { it in digits }) return raw.map { digits.getValue(it) }.joinToString("").toIntOrNull()
        var total = 0
        var current = 0
        for (char in raw) {
            val digit = digits[char]
            if (digit != null) current = digit else {
                val unit = when (char) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> return null }
                total += (if (current == 0) 1 else current) * unit
                current = 0
            }
        }
        return total + current
    }
}

data class FavoriteMigrationReport(val migrated: Int, val unmatched: Int, val resumeMatched: Boolean)
