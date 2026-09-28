package com.saltchang.whisalt

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ModelDownloaderTest {

    @Test fun `extracts tar bz2 with nested files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `rejects traversal into sibling dir sharing the prefix`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../out2/evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
            assertFalse(File(tmp, "out2/evil.txt").exists())
        }
    }

    @Test fun `rejects archives larger than the limit`() {
        withTempDir { tmp ->
            val archive = File(tmp, "big.tar.bz2")
            writeTarBz2(archive, mapOf("m/a.onnx" to "x".repeat(100)))

            assertThrows(java.io.IOException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"), maxBytes = 50)
            }
        }
    }

    @Test fun `skips symlink entries`() {
        withTempDir { tmp ->
            val archive = File(tmp, "link.tar.bz2")
            TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(archive))).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("m/link", TarArchiveEntry.LF_SYMLINK).apply {
                    linkName = "/etc/passwd"
                })
                tar.closeArchiveEntry()
            }
            val outDir = File(tmp, "out")

            ModelDownloader.extractTarBz2(archive, outDir)

            assertFalse(File(outDir, "m/link").exists())
        }
    }

    @Test fun `verifies sha256`() {
        withTempDir { tmp ->
            val file = File(tmp, "f").apply { writeText("abc") }
            ModelDownloader.verifySha256(file,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            assertThrows(java.io.IOException::class.java) {
                ModelDownloader.verifySha256(file, "00".repeat(32))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(7, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
        // Every download is pinned: the release archive, or the direct file
        val sha = Regex("[0-9a-f]{64}")
        assertTrue(MODEL_CATALOG.all { m ->
            val file = m.file
            if (file == null) m.sha256.matches(sha) && m.archive.startsWith("sherpa-onnx-")
            else file.sha256.matches(sha) && file.url.startsWith("https://")
        })
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
}
