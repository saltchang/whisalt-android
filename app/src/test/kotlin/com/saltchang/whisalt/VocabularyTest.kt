package com.saltchang.whisalt

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyTest {

    @Test fun `hotwords become a comma-separated list`() {
        assertEquals("Whisalt,Kubernetes,鍾鹽", Vocabulary.hotwords("Whisalt\n  Kubernetes  \n\n鍾鹽\n"))
    }

    @Test fun `hotwords drop commas and duplicates`() {
        assertEquals("foobar,React", Vocabulary.hotwords("foo,bar\nReact\nReact"))
        assertEquals("", Vocabulary.hotwords(" \n"))
    }

    @Test fun `parses replacement rules and skips malformed lines`() {
        assertEquals(
            listOf("瑞艾克特" to "React", "在嗎" to "在嗎？"),
            Vocabulary.replacements("瑞艾克特 => React\n在嗎=>在嗎？\nno arrow here\n => empty source"),
        )
    }

    @Test fun `replacement target may be empty to delete a word`() {
        assertEquals(listOf("嗯" to ""), Vocabulary.replacements("嗯 =>"))
    }

    @Test fun `longer rules win over overlapping shorter ones`() {
        val rules = Vocabulary.replacements("Git => git\nGit Hub => GitHub")
        assertEquals("推到 GitHub 上，用 git 管理", Vocabulary.applyReplacements("推到 Git Hub 上，用 Git 管理", rules))
    }

    @Test fun `applies every occurrence`() {
        val rules = Vocabulary.replacements("瑞艾克特 => React")
        assertEquals("React 和 React Native", Vocabulary.applyReplacements("瑞艾克特 和 瑞艾克特 Native", rules))
    }

    @Test fun `counts non-blank entries`() {
        assertEquals(2, Vocabulary.count("a\n\n b \n"))
        assertEquals(0, Vocabulary.count(""))
    }
}
