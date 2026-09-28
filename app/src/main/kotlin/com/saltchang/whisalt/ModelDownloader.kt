package com.saltchang.whisalt

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    /** SHA-256 of the .tar.bz2 release asset */
    val sha256: String,
    val recommended: Boolean = false,
)

val MODEL_CATALOG = listOf(
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value",
        "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b", recommended = true),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★",
        "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542"),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality",
        "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast",
        "d5fe6ec4334fef36255b2a4010412cad4c007e33103fec62fb5d17cad88086f2"),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private const val MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        modelDir(ctx, model).exists()

    /** Download and extract model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
        // Extract outside models/ so a failed run never looks like an installed model
        val stagingDir = File(ctx.cacheDir, "extract-${model.archive}")

        Thread {
            try {
                downloadFile(url, tmpFile, onState)
                verifySha256(tmpFile, model.sha256)
                onState(DownloadState.Extracting)
                stagingDir.deleteRecursively()
                extractTarBz2(tmpFile, stagingDir)
                val extracted = File(stagingDir, model.archive)
                if (!extracted.isDirectory) throw IOException("Unexpected archive layout")
                val dest = modelDir(ctx, model)
                dest.parentFile?.mkdirs()
                dest.deleteRecursively()
                if (!extracted.renameTo(dest)) throw IOException("Could not install model")
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpFile.delete()
                stagingDir.deleteRecursively()
            }
        }.start()
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength()
            var downloaded = 0L

            response.body.byteStream().use { src ->
                FileOutputStream(dest).use { dst ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                        downloaded += n
                        if (total > 0)
                            onState(DownloadState.Downloading(downloaded.toFloat() / total))
                    }
                }
            }
        }
    }

    fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.forEachBlock { buf, n -> digest.update(buf, 0, n) }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("Checksum mismatch")
    }

    /** Extract tar.bz2 to outDir. Rejects path traversal, skips links, caps total size. */
    fun extractTarBz2(archive: File, outDir: File, maxBytes: Long = MAX_EXTRACT_BYTES) {
        outDir.mkdirs()
        val root = outDir.canonicalPath
        var written = 0L
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                val path = dest.canonicalPath
                require(path == root || path.startsWith(root + File.separator)) {
                    "Path traversal: ${entry.name}"
                }
                when {
                    entry.isDirectory -> dest.mkdirs()
                    // Symlinks and hard links are skipped: models never need them
                    entry.isSymbolicLink || entry.isLink -> {}
                    entry.isFile -> {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            val buf = ByteArray(16384)
                            var n: Int
                            while (tar.read(buf).also { n = it } != -1) {
                                written += n
                                if (written > maxBytes) throw IOException("Archive too large")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        }
    }
}
