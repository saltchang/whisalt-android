package com.saltchang.whisalt

/** Cleans transcripts before they are injected into other apps. */
object TextSanitizer {
    private val CONTROL = Regex("[\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]")
    private val WHITESPACE = Regex("\\s+")

    /** Drop control characters (incl. ESC) but keep newlines and tabs. */
    fun forTextField(text: String): String = text.replace(CONTROL, "")

    /** Single line with no control characters, so a paste can never press Enter. */
    fun forTerminal(text: String): String =
        forTextField(text).replace(WHITESPACE, " ").trim()
}
