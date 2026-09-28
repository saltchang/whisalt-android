package com.saltchang.whisalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Expected outputs are derived by hand from the bundled opencc-js 1.4.1 dictionaries
 * (s2twp: STPhrases/STCharacters, then TWPhrases/TWVariants).
 */
class ChineseConverterTest {

    private val converter = ChineseConverter { File("src/main/assets/opencc/$it").inputStream() }

    @Test fun `converts to Taiwan phrases`() {
        assertEquals(
            "這個軟體的網路設定有問題，請幫我看一下伺服器的記憶體和硬碟。",
            converter.toTaiwan("这个软件的网络设置有问题，请帮我看一下服务器的内存和硬盘。"),
        )
    }

    @Test fun `uses generated regional phrases and Taiwan vocabulary`() {
        assertEquals(
            "這個影片的預設程式是什麼？滑鼠和印表機都連不上資料庫。",
            converter.toTaiwan("这个视频的默认程序是什么？鼠标和打印机都连不上数据库。"),
        )
    }

    @Test fun `applies Taiwan variants across segments`() {
        // ST gives 裏/喫/麪; TWVariants turns them into 裡/吃/麵
        assertEquals(
            "我把頭髮洗乾淨以後，去裡面吃了一碗麵條。",
            converter.toTaiwan("我把头发洗干净以后，去里面吃了一碗面条。"),
        )
    }

    @Test fun `matches OpenCC word for word`() {
        // 着 -> 著 via TWVariants; 信息 -> 資訊 via TWPhrases, as OpenCC does
        assertEquals("他著急地發了一封資訊給我。", converter.toTaiwan("他着急地发了一封信息给我。"))
    }

    @Test fun `normalizes CJK compatibility ideographs`() {
        assertEquals("豈", converter.toTaiwan("豈"))
    }

    @Test fun `leaves Traditional Chinese and other text unchanged`() {
        assertEquals("我們的軟體很好用。", converter.toTaiwan("我們的軟體很好用。"))
        assertEquals("git push origin main 123", converter.toTaiwan("git push origin main 123"))
        assertEquals("", converter.toTaiwan(""))
    }

    @Test fun `keeps surrogate pairs intact`() {
        assertEquals("👍這個", converter.toTaiwan("👍这个"))
    }

    @Test fun `trusts the recognizer language when it reports one`() {
        assertTrue(ChineseConverter.isChinese("这个软件は", language = "zh"))
        assertFalse(ChineseConverter.isChinese("写真", language = "ja"))
        assertFalse(ChineseConverter.isChinese("사진", language = "ko"))
        assertTrue(ChineseConverter.isChinese("幫我 review 这个", language = "en"))
        assertFalse(ChineseConverter.isChinese("hello", language = "en"))
    }

    @Test fun `detects Chinese but not Japanese or Korean`() {
        assertTrue(ChineseConverter.isChinese("这个软件"))
        assertTrue(ChineseConverter.isChinese("幫我 review 這個 PR"))
        assertFalse(ChineseConverter.isChinese("これは日本語です"))
        assertFalse(ChineseConverter.isChinese("日本語の文章"))
        assertFalse(ChineseConverter.isChinese("한국어 문장"))
        assertFalse(ChineseConverter.isChinese("plain English"))
    }
}
