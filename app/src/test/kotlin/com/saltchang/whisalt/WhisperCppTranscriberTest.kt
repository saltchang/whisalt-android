package com.saltchang.whisalt

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperCppTranscriberTest {

    @Test fun `audio window fits the segment with headroom`() {
        // 5.6 s: 280 speech frames + headroom -> 448, the setting verified on a phone
        assertEquals(448, WhisperCppTranscriber.audioCtx(89_600, 16_000))
        assertEquals(192, WhisperCppTranscriber.audioCtx(0, 16_000))
        assertEquals(960, WhisperCppTranscriber.audioCtx(15 * 16_000, 16_000))
    }

    @Test fun `audio window never exceeds the 30 s encoder window`() {
        assertEquals(1500, WhisperCppTranscriber.audioCtx(60 * 16_000, 16_000))
    }

    @Test fun `output cap scales with audio length`() {
        assertEquals(16, WhisperCppTranscriber.maxTokens(0, 16_000))
        assertEquals(28, WhisperCppTranscriber.maxTokens(16_000, 16_000))
        assertEquals(196, WhisperCppTranscriber.maxTokens(15 * 16_000, 16_000))
    }

    @Test fun `decoding loops collapse to one copy`() {
        assertEquals("今天先到這裡，以", WhisperCppTranscriber.collapseRepeats("今天先到這裡，" + "以".repeat(20)))
        assertEquals("好的，記憶體、", WhisperCppTranscriber.collapseRepeats("好的，" + "記憶體、".repeat(6)))
    }

    @Test fun `ordinary repetition in speech is kept`() {
        assertEquals("哈哈哈，謝謝謝謝", WhisperCppTranscriber.collapseRepeats("哈哈哈，謝謝謝謝"))
        assertEquals("看看這個", WhisperCppTranscriber.collapseRepeats("看看這個"))
    }

    @Test fun `prompt is the Traditional Chinese hint plus vocabulary`() {
        assertEquals(WhisperCppTranscriber.SCRIPT_PROMPT, WhisperCppTranscriber.prompt(""))
        assertEquals(
            "${WhisperCppTranscriber.SCRIPT_PROMPT} Whisalt、Kubernetes、鍾鹽",
            WhisperCppTranscriber.prompt("Whisalt,Kubernetes,鍾鹽"),
        )
    }
}
