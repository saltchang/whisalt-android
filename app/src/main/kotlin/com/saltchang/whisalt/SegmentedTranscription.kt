package com.saltchang.whisalt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Decodes each speech segment as soon as VAD hears a pause, while the user keeps talking, so after
 * they stop only the last segment is left. Qwen3-ASR decodes at ~0.3x real time on a phone, so a
 * 25 s dictation otherwise means a ~7.5 s wait.
 *
 * [accept] runs on the recording thread; [finish] runs once, after that thread has been joined.
 */
class SegmentedTranscription(assets: AssetManager, private val transcriber: LocalTranscriber) {
    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                // Half a second of silence ends a segment: short enough to split at clause
                // boundaries, long enough not to cut mid-phrase
                minSilenceDuration = 0.5f,
                windowSize = WINDOW,
                maxSpeechDuration = 15f,
            ),
        ),
    )
    private val worker = Executors.newSingleThreadExecutor()
    private val segments = mutableListOf<Future<LocalTranscriber.Transcript>>()
    private val window = FloatArray(WINDOW)
    private var filled = 0

    fun accept(samples: FloatArray) {
        for (s in samples) {
            window[filled++] = s
            if (filled == WINDOW) {
                vad.acceptWaveform(window.copyOf())
                filled = 0
                submitFinishedSegments()
            }
        }
    }

    /** Waits for every segment; null if VAD heard no speech at all. Releases native resources. */
    fun finish(): LocalTranscriber.Transcript? = try {
        if (filled > 0) vad.acceptWaveform(window.copyOf(filled))
        vad.flush()
        submitFinishedSegments()
        if (segments.isEmpty()) null else merge(segments.map { it.get() })
    } finally {
        release()
    }

    fun release() {
        worker.shutdownNow()
        vad.release()
    }

    private fun submitFinishedSegments() {
        while (!vad.empty()) {
            val samples = vad.front().samples
            vad.pop()
            segments += worker.submit(Callable { transcriber.transcribe(samples) })
        }
    }

    companion object {
        private const val WINDOW = 512

        /** Joins segment texts; a space only between two Latin letters/digits, as Chinese needs none. */
        fun merge(parts: List<LocalTranscriber.Transcript>): LocalTranscriber.Transcript {
            val text = parts.map { it.text.trim() }.filter { it.isNotEmpty() }
                .reduceOrNull { acc, next ->
                    if (isLatinWordChar(acc.last()) && isLatinWordChar(next.first())) "$acc $next" else acc + next
                }.orEmpty()
            // Only keep a language every segment agrees on
            val language = parts.map { it.language }.distinct().singleOrNull()
            return LocalTranscriber.Transcript(text, language)
        }

        private fun isLatinWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    }
}
