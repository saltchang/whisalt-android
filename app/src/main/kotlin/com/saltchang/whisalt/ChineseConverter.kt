package com.saltchang.whisalt

import java.io.InputStream

/**
 * Simplified -> Traditional Chinese (Taiwan standard, with Taiwan phrases).
 *
 * Port of opencc-js 1.4.1 `Converter({ from: 'cn', to: 'twp' })` (the `s2twp` config), the same
 * conversion OpenWhispr uses on desktop. Dictionaries in assets/opencc are generated from
 * opencc-js 1.4.1 (MIT), whose data comes from OpenCC (Apache-2.0).
 */
class ChineseConverter(private val open: (String) -> InputStream) {
    private val files = HashMap<String, Map<String, String>>()
    private fun file(name: String) = files.getOrPut(name) { load(open("$name.txt")) }

    private val normalization = Dict(file("CJK_Compatibility_Ideographs"))
    private val segmentation = Dict(file("STPhrases"), file("STPhrases_GeneratedFromRegionalPhrases"))
    private val conversionChain = listOf(
        Dict(file("STPhrases"), file("STPhrases_GeneratedFromRegionalPhrases"), file("STCharacters")),
        Dict(file("TWPhrases"), file("TWVariantsPhrases"), file("TWVariants")),
    )

    fun toTaiwan(text: String): String =
        segmentation.segment(normalization.convert(text))
            .joinToString("") { segment -> conversionChain.fold(segment) { s, dict -> dict.convert(s) } }

    /** Earlier maps win for identical keys; the longest key across all maps wins overall. */
    private class Dict(vararg val maps: Map<String, String>) {
        private val maxKeyLength = maps.maxOf { m -> m.keys.maxOfOrNull { it.length } ?: 0 }

        /** Longest dictionary key starting at [i]: (end index, replacement), or null. */
        private fun matchPrefix(s: String, i: Int): Pair<Int, String>? {
            for (len in minOf(maxKeyLength, s.length - i) downTo 1) {
                val key = s.substring(i, i + len)
                for (m in maps) m[key]?.let { return i + len to it }
            }
            return null
        }

        fun convert(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val match = matchPrefix(s, i)
                if (match != null) {
                    append(match.second); i = match.first
                } else {
                    val end = i + unmatchedLength(s, i)
                    append(s, i, end); i = end
                }
            }
        }

        fun segment(s: String): List<String> {
            val segments = mutableListOf<String>()
            var unmatchedStart = -1
            var i = 0
            while (i < s.length) {
                val match = matchPrefix(s, i)
                if (match != null) {
                    if (unmatchedStart >= 0) { segments += s.substring(unmatchedStart, i); unmatchedStart = -1 }
                    segments += s.substring(i, match.first); i = match.first
                } else {
                    if (unmatchedStart < 0) unmatchedStart = i
                    i += unmatchedLength(s, i)
                }
            }
            if (unmatchedStart >= 0) segments += s.substring(unmatchedStart)
            return segments
        }
    }

    companion object {
        private val HAN = Regex("\\p{script=Han}")
        private val KANA_OR_HANGUL =
            Regex("[\\u3040-\\u30ff\\u31f0-\\u31ff\\uff66-\\uff9f\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]")

        /** Chinese text only: Japanese and Korean output (SenseVoice can emit both) is left alone. */
        fun isChinese(text: String) = HAN.containsMatchIn(text) && !KANA_OR_HANGUL.containsMatchIn(text)

        private fun load(input: InputStream): Map<String, String> = HashMap<String, String>().apply {
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) put(line.substring(0, tab), line.substring(tab + 1))
                }
            }
        }

        /** Unmatched text advances one code point at a time so surrogate pairs stay whole. */
        private fun unmatchedLength(s: String, i: Int) = Character.charCount(s.codePointAt(i))
    }
}
