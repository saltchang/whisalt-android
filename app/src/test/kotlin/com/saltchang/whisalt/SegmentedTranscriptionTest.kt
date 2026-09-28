package com.saltchang.whisalt

import com.saltchang.whisalt.LocalTranscriber.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentedTranscriptionTest {

    private fun merge(vararg parts: Transcript) = SegmentedTranscription.merge(parts.toList())

    @Test fun `joins Chinese segments without spaces`() {
        assertEquals("今天天氣不錯，我們去走走。", merge(Transcript("今天天氣不錯，", "zh"), Transcript("我們去走走。", "zh")).text)
    }

    @Test fun `separates Latin words across segments`() {
        assertEquals("please review this PR", merge(Transcript("please review", "en"), Transcript("this PR", "en")).text)
    }

    @Test fun `separates English sentences split after punctuation`() {
        assertEquals("Hello. How are you?", merge(Transcript("Hello.", "en"), Transcript("How are you?", "en")).text)
        // Chinese after English punctuation still gets no space
        assertEquals("Done,好的", merge(Transcript("Done,", null), Transcript("好的", null)).text)
    }

    @Test fun `mixed Chinese and English needs no extra space`() {
        assertEquals("幫我看一下 PR，然後merge", merge(Transcript("幫我看一下 PR，", null), Transcript("然後merge", null)).text)
        assertEquals("這個 API很好用", merge(Transcript("這個 API", null), Transcript("很好用", null)).text)
    }

    @Test fun `drops blank segments and trims`() {
        assertEquals("你好。再見。", merge(Transcript(" 你好。 ", "zh"), Transcript("  ", "zh"), Transcript("再見。", "zh")).text)
        assertEquals("", merge(Transcript("", null)).text)
    }

    @Test fun `keeps a language only when all segments agree`() {
        assertEquals("zh", merge(Transcript("一", "zh"), Transcript("二", "zh")).language)
        assertNull(merge(Transcript("一", "zh"), Transcript("two", "en")).language)
        assertNull(merge(Transcript("一", null), Transcript("二", null)).language)
    }
}
