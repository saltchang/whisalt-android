package com.saltchang.whisalt

/** User vocabulary, edited as plain text in settings (one entry per line). */
object Vocabulary {
    const val HOTWORDS_PREF = "vocabulary_hotwords"
    const val REPLACEMENTS_PREF = "vocabulary_replacements"
    private const val ARROW = "=>"

    /**
     * Terms that bias Qwen3-ASR toward the right spelling, as the comma-separated list sherpa-onnx
     * expects. Commas inside a term would split it, so they are dropped.
     */
    fun hotwords(text: String): String =
        text.lines().map { it.replace(",", "").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    /** `wrong => right` lines; malformed lines are skipped. Longest `wrong` first so it wins overlaps. */
    fun replacements(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { line ->
            val arrow = line.indexOf(ARROW)
            if (arrow < 0) return@mapNotNull null
            val from = line.substring(0, arrow).trim()
            val to = line.substring(arrow + ARROW.length).trim()
            if (from.isEmpty()) null else from to to
        }.sortedByDescending { it.first.length }

    /** One left-to-right pass, so a replacement's output is never rewritten by another rule. */
    fun applyReplacements(text: String, rules: List<Pair<String, String>>): String {
        if (rules.isEmpty()) return text
        val targets = rules.toMap()
        // Alternation tries rules in order, and they are sorted longest first
        val pattern = Regex(rules.joinToString("|") { Regex.escape(it.first) })
        return pattern.replace(text) { targets.getValue(it.value) }
    }

    /** Settings subtitle: how many entries a list holds. */
    fun count(text: String) = text.lines().count { it.isNotBlank() }
}
