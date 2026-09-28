// JNI bridge for WhisperCppTranscriber. Calls on one context must be serialized by the caller.
#include <android/log.h>
#include <jni.h>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

namespace {

std::string toString(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// whisper.cpp logs to stderr, which Android drops; forward warnings and errors to logcat
void logToLogcat(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, "WhisperCpp", text);
    else if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, "WhisperCpp", text);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_saltchang_whisalt_WhisperCppTranscriber_nativeInit(
        JNIEnv *env, jclass, jstring modelPath, jstring backendDir) {
    // Registers the fastest CPU variant this phone supports (libggml-cpu-*.so)
    static bool backendsLoaded = false;
    if (!backendsLoaded) {
        whisper_log_set(logToLogcat, nullptr);
        ggml_backend_load_all_from_path(toString(env, backendDir).c_str());
        backendsLoaded = true;
    }
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(toString(env, modelPath).c_str(), params);
    return reinterpret_cast<jlong>(ctx);
}

// Returns raw UTF-8 bytes: NewStringUTF rejects emoji and the split multi-byte characters
// Whisper can emit at token boundaries, while Kotlin's decoder substitutes them safely
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_saltchang_whisalt_WhisperCppTranscriber_nativeTranscribe(
        JNIEnv *env, jclass, jlong ptr, jfloatArray samples, jstring language,
        jstring prompt, jint threads, jint audioCtx, jint maxTokens) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    const std::string lang = toString(env, language);
    const std::string initialPrompt = toString(env, prompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.c_str();
    params.initial_prompt = initialPrompt.empty() ? nullptr : initialPrompt.c_str();
    params.audio_ctx = audioCtx;
    // Temperature fallback re-decodes up to 5 times when output looks unreliable: a few ms on a
    // desktop GPU, but a 1 s clip took 24 s on a phone CPU. Decode once, and cap the length so a
    // repetition loop cannot run on
    params.temperature_inc = 0.0f;
    params.max_tokens = maxTokens;
    params.no_timestamps = true;
    params.single_segment = true;  // VAD already hands us one utterance
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    jsize n = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (rc != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "whisper_full failed");
        return nullptr;
    }

    std::string text;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_saltchang_whisalt_WhisperCppTranscriber_nativeFree(JNIEnv *, jclass, jlong ptr) {
    whisper_free(reinterpret_cast<whisper_context *>(ptr));
}
