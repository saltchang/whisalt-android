package com.saltchang.whisalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptEchoTest {

    private val dictionary = "記憶體,硬碟"
    private val prompt = WhisperCppTranscriber.prompt(dictionary)

    @Test fun `splits terms on any punctuation`() {
        assertEquals(listOf("記憶體", "硬碟", "react"), PromptEcho.terms("記憶體、硬碟, React。"))
    }

    @Test fun `output that only repeats the vocabulary is an echo`() {
        assertTrue(PromptEcho.isEcho("記憶體、硬碟、記憶體、硬碟", dictionary))
        assertEquals("", PromptEcho.clean("記憶體、硬碟、記憶體、硬碟、記憶體", dictionary, prompt))
    }

    @Test fun `output that repeats the script prompt is an echo`() {
        assertEquals("", PromptEcho.clean("語言、學習、軟體、網路。", dictionary, prompt))
    }

    @Test fun `real speech using vocabulary words is kept`() {
        assertFalse(PromptEcho.isEcho("記憶體和硬碟都要升級", dictionary))
        assertEquals("我的記憶體不夠，要買硬碟。", PromptEcho.clean("我的記憶體不夠，要買硬碟。", dictionary, prompt))
        // A lone vocabulary word covers only part of the list
        assertEquals("硬碟", PromptEcho.clean("硬碟", dictionary, prompt))
        // Saying the only vocabulary word is not an echo either
        assertEquals("硬碟。", PromptEcho.clean("硬碟。", "硬碟", WhisperCppTranscriber.prompt("硬碟")))
    }

    @Test fun `trailing loop after real speech is cut`() {
        assertEquals("我在測試。", PromptEcho.clean("我在測試。記憶體、硬碟、記憶體、硬碟", dictionary, prompt))
        assertEquals("幫我看一下", PromptEcho.clean("幫我看一下、硬碟、記憶體、硬碟", dictionary, prompt))
    }

    @Test fun `vocabulary after real speech without repeats is kept`() {
        assertEquals("我要換硬碟", PromptEcho.clean("我要換硬碟", dictionary, prompt))
        assertEquals("先檢查，記憶體、硬碟", PromptEcho.clean("先檢查，記憶體、硬碟", dictionary, prompt))
    }

    @Test fun `without vocabulary the script prompt is still checked`() {
        val bare = WhisperCppTranscriber.prompt("")
        assertEquals("", PromptEcho.clean("以下是繁體中文。語言、學習、軟體、網路。", "", bare))
        assertEquals("今天天氣很好。", PromptEcho.clean("今天天氣很好。", "", bare))
    }
}
