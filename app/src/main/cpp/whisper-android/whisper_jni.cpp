#include <jni.h>
#include <string>
#include <sstream>
#include <algorithm>
#include "whisper.h"

namespace {
struct JniProgressState {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
};

void onWhisperProgress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *state = static_cast<JniProgressState *>(user_data);
    if (state == nullptr || state->vm == nullptr || state->callback == nullptr || state->method == nullptr) return;
    JNIEnv *callback_env = nullptr;
    bool attached = false;
    const jint env_status = state->vm->GetEnv(reinterpret_cast<void **>(&callback_env), JNI_VERSION_1_6);
    if (env_status == JNI_EDETACHED) {
        if (state->vm->AttachCurrentThread(&callback_env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (env_status != JNI_OK) {
        return;
    }
    callback_env->CallVoidMethod(state->callback, state->method, static_cast<jint>(progress));
    if (callback_env->ExceptionCheck()) callback_env->ExceptionClear();
    if (attached) state->vm->DetachCurrentThread();
}

std::string timestamp(int64_t seconds) {
    seconds = std::max<int64_t>(0, seconds);
    const int64_t minutes = seconds / 60;
    const int64_t remainder = seconds % 60;
    std::ostringstream out;
    if (minutes >= 60) {
        const int64_t hours = minutes / 60;
        out << (hours < 10 ? "0" : "") << hours << ":";
        const int64_t mm = minutes % 60;
        out << (mm < 10 ? "0" : "") << mm << ":";
    } else {
        out << (minutes < 10 ? "0" : "") << minutes << ":";
    }
    out << (remainder < 10 ? "0" : "") << remainder;
    return out.str();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yelinvoice_offline_WhisperNative_createContext(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yelinvoice_offline_WhisperNative_freeContext(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yelinvoice_offline_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle,
                                                      jfloatArray audio, jstring language,
                                                      jint threads, jobject progress_callback) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || audio == nullptr) return env->NewStringUTF("");
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize sampleCount = env->GetArrayLength(audio);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.greedy.best_of = 1;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = std::max(1, std::min(4, static_cast<int>(threads)));
    params.offset_ms = 0;
    params.no_context = false;
    params.single_segment = false;
    params.token_timestamps = true;

    JniProgressState progress_state;
    if (progress_callback != nullptr && env->GetJavaVM(&progress_state.vm) == JNI_OK) {
        progress_state.callback = env->NewGlobalRef(progress_callback);
        jclass callback_class = env->GetObjectClass(progress_callback);
        if (callback_class != nullptr) {
            progress_state.method = env->GetMethodID(callback_class, "onProgress", "(I)V");
            env->DeleteLocalRef(callback_class);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (progress_state.callback != nullptr && progress_state.method != nullptr) {
            params.progress_callback = onWhisperProgress;
            params.progress_callback_user_data = &progress_state;
        }
    }

    const int status = whisper_full(ctx, params, samples, sampleCount);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (progress_state.callback != nullptr) env->DeleteGlobalRef(progress_state.callback);
    if (status != 0) return env->NewStringUTF("");

    std::ostringstream result;
    std::string phrase;
    int64_t phrase_start = -1;
    int64_t phrase_end = 0;
    auto flush_phrase = [&]() {
        const auto first = phrase.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) {
            phrase.clear();
            phrase_start = -1;
            return;
        }
        phrase = phrase.substr(first);
        const auto last = phrase.find_last_not_of(" \t\r\n");
        phrase.resize(last + 1);
        const int64_t start_seconds = std::max<int64_t>(0, phrase_start) / 100;
        const int64_t bounded_end = std::max<int64_t>(phrase_end, phrase_start + 1);
        int64_t end_seconds = (bounded_end + 99) / 100;
        if (end_seconds <= start_seconds) end_seconds = start_seconds + 1;
        result << "[" << timestamp(start_seconds) << " - " << timestamp(end_seconds)
               << "] " << phrase << "\n";
        phrase.clear();
        phrase_start = -1;
    };
    const int count = whisper_full_n_segments(ctx);
    for (int i = 0; i < count; ++i) {
        const int token_count = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < token_count; ++j) {
            const char *token_text = whisper_full_get_token_text(ctx, i, j);
            if (token_text == nullptr) continue;
            std::string token(token_text);
            const bool timestamp_marker = token.rfind("[_TT_", 0) == 0 && token.back() == ']';
            if (timestamp_marker || token == "[_BEG_]" || token == "[_EOT_]") {
                flush_phrase();
                continue;
            }
            if (token.empty() || token.rfind("<|", 0) == 0 ||
                (token.rfind("[_", 0) == 0 && token.back() == ']')) continue;
            const auto first = token.find_first_not_of(" \t\r\n");
            if (first == std::string::npos) continue;
            token = token.substr(first);

            const int64_t token_start = std::max<int64_t>(0, whisper_full_get_token_t0(ctx, i, j));
            const int64_t token_end = std::max<int64_t>(token_start + 1, whisper_full_get_token_t1(ctx, i, j));
            if (phrase_start >= 0 && token_start > phrase_end + 35) flush_phrase();
            if (phrase_start >= 0 && token_start >= phrase_start + 500) flush_phrase();
            if (phrase_start < 0) phrase_start = token_start;
            phrase += token;
            phrase_end = token_end;

            const bool sentence_end = token.find("。") != std::string::npos ||
                                      token.find("！") != std::string::npos ||
                                      token.find("？") != std::string::npos ||
                                      token.find("!") != std::string::npos ||
                                      token.find("?") != std::string::npos ||
                                      token.find(";") != std::string::npos ||
                                      token.find("；") != std::string::npos;
            if (sentence_end) flush_phrase();
        }
    }
    flush_phrase();
    const std::string output = result.str();
    return env->NewStringUTF(output.c_str());
}
