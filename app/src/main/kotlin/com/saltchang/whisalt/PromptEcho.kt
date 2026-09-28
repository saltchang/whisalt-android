package com.saltchang.whisalt

/**
 * Whisper treats its initial prompt as preceding text, so on unclear audio or pauses it can
 * "continue" the prompt, e.g. repeating vocabulary like 記憶體、硬碟 the user never said.
 */
object PromptEcho {
    private val TERM = Regex("[^\\p{P}\\p{S}\\p{Z}\\s]+")

    fun terms(text: String): List<String> = TERM.findAll(text.lowercase()).map { it.value }.toList()

    /**
     * OpenWhispr's `isDictionaryEcho` (90%+ of the output's terms are prompt terms, covering 70%+ of
     * the prompt), plus: two or more distinct terms that all come from the prompt, which catches
     * echoing just the script prompt's list. Real speech using a vocabulary word is kept, since it
     * brings words of its own, and so is a lone term: it may be exactly what the user said.
     */
    fun isEcho(output: String, prompt: String): Boolean {
        val outTerms = terms(output).toSet()
        val promptTerms = terms(prompt).toSet()
        if (outTerms.size < 2 || promptTerms.isEmpty()) return false
        val shared = outTerms.count { it in promptTerms }
        if (shared == outTerms.size) return true
        return shared.toDouble() / outTerms.size >= 0.9 && shared.toDouble() / promptTerms.size >= 0.7
    }

    /**
     * Cuts a trailing run of prompt words that repeats, the loop Whisper falls into after real
     * speech: "我在測試。記憶體、硬碟、記憶體" -> "我在測試。". A single trailing vocabulary word is kept.
     */
    fun stripTrailingLoop(output: String, prompt: String): String {
        val promptTerms = terms(prompt).toSet()
        val matches = TERM.findAll(output).toList()
        var start = matches.size
        while (start > 0 && matches[start - 1].value.lowercase() in promptTerms) start--
        val run = matches.subList(start, matches.size).map { it.value.lowercase() }
        if (run.size < 2 || run.toSet().size == run.size) return output
        return output.substring(0, matches[start].range.first).trimEnd { it.isWhitespace() || it in "、,，" }
    }

    /** Empty when the whole output is an echo of [dictionary] or of the full [prompt]. */
    fun clean(output: String, dictionary: String, prompt: String): String {
        if (isEcho(output, dictionary) || isEcho(output, prompt)) return ""
        return stripTrailingLoop(output, prompt)
    }
}
