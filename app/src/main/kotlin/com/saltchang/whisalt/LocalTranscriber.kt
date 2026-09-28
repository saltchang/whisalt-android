package com.saltchang.whisalt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer) {

    private var released = false
    private var users = 0
    private var releaseRequested = false

    /** Keeps the model alive for a whole recording; returns false if it is already being unloaded. */
    @Synchronized
    fun acquire(): Boolean {
        if (released || releaseRequested) return false
        users++
        return true
    }

    /** Ends a use from [acquire]; frees the model if [release] was requested meanwhile. */
    @Synchronized
    fun releaseUse() {
        users--
        if (users == 0 && releaseRequested) free()
    }

    /** [language] is the recognizer's detected language ("zh", "ja", ...), or null if it reports none. */
    data class Transcript(val text: String, val language: String?)

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000, hotwords: String = ""): Transcript {
        check(!released) { "Model was unloaded" }
        val t0 = System.currentTimeMillis()
        val stream = recognizer.createStream()
        // Only Qwen3-ASR reads this option; other models ignore it
        if (hotwords.isNotEmpty()) stream.setOption("hotwords", hotwords)
        stream.acceptWaveform(samples, sampleRate)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        // Token count exposes runaway LLM decoding (Qwen3-ASR) without logging any text
        Log.i(TAG, "Decoded %.1fs audio in %dms, %d tokens".format(
            samples.size / sampleRate.toFloat(), System.currentTimeMillis() - t0, result.tokens.size))
        // SenseVoice reports its language as a token such as "<|zh|>"
        val language = result.lang.removePrefix("<|").removeSuffix("|>").ifBlank { null }
        return Transcript(result.text.trim(), language)
    }

    /** Frees native memory once no recording is using the model. Waits for an in-flight decode. */
    @Synchronized
    fun release() {
        releaseRequested = true
        if (users == 0) free()
    }

    private fun free() {
        if (released) return
        released = true
        recognizer.release()
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            val config = detectModelConfig(modelDir) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val p = dir.absolutePath

            // Qwen3-ASR (conv_frontend + encoder + decoder, HF tokenizer dir instead of tokens.txt)
            if (File("$p/conv_frontend.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = "$p/conv_frontend.onnx",
                            encoder = findFile(p, "encoder") ?: return null,
                            decoder = findFile(p, "decoder") ?: return null,
                            tokenizer = "$p/tokenizer",
                        ),
                        tokens = "",
                        // Official example uses 3; S23-class phones have 4+ big cores for the LLM decoder
                        numThreads = 4,
                    )
                )
            }

            val tokens = "$p/tokens.txt"
            if (!File(tokens).exists()) return null

            // Moonshine (has preprocess.onnx)
            if (File("$p/preprocess.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = findFile(p, "encode") ?: return null,
                            uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                            cachedDecoder = findFile(p, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val whisperEncoder = findFile(p, "encoder")
            val whisperDecoder = findFile(p, "decoder")
            if (whisperEncoder != null && whisperDecoder != null && findFile(p, "joiner") == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // SenseVoice ships the same single model.int8.onnx layout as NeMo CTC; tell them apart by name
            if (dir.name.contains("sense-voice")) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = findFile(p, "model") ?: return null,
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            return null
        }

        /** Find first file matching prefix (prefer int8 quantized). */
        private fun findFile(dir: String, prefix: String): String? {
            val d = File(dir)
            // Prefer int8 quantized
            d.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            // Fallback to any onnx/ort
            return d.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
