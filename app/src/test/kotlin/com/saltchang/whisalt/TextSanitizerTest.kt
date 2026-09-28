package com.saltchang.whisalt

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSanitizerTest {

    @Test fun `text field keeps newlines and tabs`() {
        assertEquals("line one\nline\ttwo", TextSanitizer.forTextField("line one\r\nline\ttwo"))
    }

    @Test fun `text field drops escape and other control characters`() {
        assertEquals("[31mred", TextSanitizer.forTextField("\u001b[31m\u0007red\u0000"))
    }

    @Test fun `terminal output is a single line`() {
        assertEquals("ls -l . rm -rf ~", TextSanitizer.forTerminal("ls -l .\nrm -rf ~\n"))
    }

    @Test fun `terminal output collapses whitespace and strips escapes`() {
        assertEquals("git status", TextSanitizer.forTerminal("  git\u001b \r\n\t status  "))
    }

    @Test fun `plain text is unchanged`() {
        assertEquals("How do I increase the font size?", TextSanitizer.forTerminal("How do I increase the font size?"))
    }
}
