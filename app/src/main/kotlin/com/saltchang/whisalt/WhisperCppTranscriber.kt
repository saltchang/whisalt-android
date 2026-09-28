package com.saltchang.whisalt

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/**
 * Whisper large-v3-turbo via whisper.cpp, set up like OpenWhispr on desktop: Chinese forced and a
 * Traditional Chinese initial prompt (plus the user's vocabulary) so output is Taiwan-style text.
 */
class WhisperCppTranscriber private constructor(private val ptr: Long) : LocalTranscriber() {

    override fun decode(samples: FloatArray, sampleRate: Int, hotwords: String): Transcript {
        val prompt = prompt(hotwords)
        val bytes = nativeTranscribe(
            ptr, samples, LANGUAGE, prompt, THREADS,
            audioCtx(samples.size, sampleRate), maxTokens(samples.size, sampleRate),
        )
        val raw = collapseRepeats(String(bytes, Charsets.UTF_8).trim())
        // Whisper can "continue" its prompt on pauses, repeating vocabulary the user never said
        val text = PromptEcho.clean(raw, hotwords, prompt)
        // Whisper cannot report Chinese vs Japanese once the language is forced
        return Transcript(text, language = null)
    }

    override fun freeNative() = nativeFree(ptr)

    companion object {
        private const val TAG = "WhisperCpp"
        private const val LANGUAGE = "zh"
        private const val THREADS = 4

        /** OpenWhispr's prompt for Traditional Chinese: biases script, Taiwan vocabulary and punctuation. */
        const val SCRIPT_PROMPT = "以下是繁體中文。語言、學習、軟體、網路。"

        private const val FRAMES_PER_SECOND = 50 // encoder frames; the full 30 s window is 1500
        private const val MAX_AUDIO_CTX = 1500
        // Headroom past the speech itself: 320 frames for 5.6 s dropped words on a phone, 448 did not
        private const val AUDIO_CTX_HEADROOM = 168

        /** [hotwords] as comma-separated terms (see [Vocabulary.hotwords]), appended like a dictionary. */
        fun prompt(hotwords: String): String =
            if (hotwords.isBlank()) SCRIPT_PROMPT else "$SCRIPT_PROMPT ${hotwords.split(',').joinToString("、")}"

        /**
         * Encoder window sized to the segment instead of the fixed 30 s: encoding cost scales with it,
         * so a 5.6 s segment takes ~2.4 s instead of ~13 s on a Snapdragon 8 Gen 2. Multiple of 64.
         */
        fun audioCtx(sampleCount: Int, sampleRate: Int): Int {
            val speechFrames = ceil(sampleCount.toDouble() / sampleRate * FRAMES_PER_SECOND).toInt()
            val frames = (speechFrames + AUDIO_CTX_HEADROOM + 63) / 64 * 64
            return min(frames, MAX_AUDIO_CTX)
        }

        /**
         * Output cap per segment. Speech runs ~4-6 Chinese characters per second, so 12 tokens/s plus
         * slack never truncates real speech but stops a repetition loop early.
         */
        fun maxTokens(sampleCount: Int, sampleRate: Int): Int =
            ceil(sampleCount.toDouble() / sampleRate * 12).toInt() + 16

        // A unit of up to 8 characters repeated 5+ times in a row; real speech rarely does that
        private val REPEAT_LOOP = Regex("(.{1,8}?)\\1{4,}", RegexOption.DOT_MATCHES_ALL)

        /**
         * Greedy decoding without temperature fallback can get stuck repeating one token until
         * [maxTokens] ("以以以以…"). Keeps a single copy of any such run.
         */
        fun collapseRepeats(text: String): String = REPEAT_LOOP.replace(text, "$1")

        fun create(ctx: Context, modelFile: File): WhisperCppTranscriber? {
            // Loaded here, not in init, so JVM unit tests can use prompt() and audioCtx()
            System.loadLibrary("whisper_jni")
            // ggml loads its CPU variants (libggml-cpu-*.so) from the extracted native library dir
            val ptr = nativeInit(modelFile.absolutePath, ctx.applicationInfo.nativeLibraryDir)
            if (ptr == 0L) {
                Log.e(TAG, "Failed to load ${modelFile.name}")
                return null
            }
            return WhisperCppTranscriber(ptr)
        }

        @JvmStatic private external fun nativeInit(modelPath: String, backendDir: String): Long

        @JvmStatic private external fun nativeTranscribe(
            ptr: Long, samples: FloatArray, language: String, prompt: String, threads: Int,
            audioCtx: Int, maxTokens: Int,
        ): ByteArray

        @JvmStatic private external fun nativeFree(ptr: Long)
    }
}
