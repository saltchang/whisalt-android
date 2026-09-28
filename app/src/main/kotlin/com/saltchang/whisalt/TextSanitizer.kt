package com.saltchang.whisalt

/** Cleans transcripts before they are injected into other apps. */
object TextSanitizer {
    private val CONTROL = Regex("[\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]")
    private val LINE_BREAKS = Regex("[\\r\\n\\t]+")
    private val SPACES = Regex(" {2,}")

    /** Drop control characters (incl. ESC) but keep newlines and tabs. */
    fun forTextField(text: String): String = text.replace("\r\n", "\n").replace(CONTROL, "")

    /** Single line with no control characters, so a paste can never press Enter. */
    fun forTerminal(text: String): String =
        forTextField(text).replace(LINE_BREAKS, " ").replace(SPACES, " ").trim()
}
