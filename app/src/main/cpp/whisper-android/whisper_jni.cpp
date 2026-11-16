#include <jni.h>
#include <string>
#include <sstream>
#include <algorithm>
#include "whisper.h"

namespace {
std::string timestamp(int64_t centiseconds) {
    const int64_t seconds = std::max<int64_t>(0, centiseconds) / 100;
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
                                                      jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || audio == nullptr) return env->NewStringUTF("");
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize sampleCount = env->GetArrayLength(audio);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
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
    params.token_timestamps = false;

    const int status = whisper_full(ctx, params, samples, sampleCount);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (status != 0) return env->NewStringUTF("");

    std::ostringstream result;
    const int count = whisper_full_n_segments(ctx);
    for (int i = 0; i < count; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        std::string line(text);
        const auto first = line.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) continue;
        line = line.substr(first);
        const auto last = line.find_last_not_of(" \t\r\n");
        line.resize(last + 1);
        result << "[" << timestamp(whisper_full_get_segment_t0(ctx, i))
               << " - " << timestamp(whisper_full_get_segment_t1(ctx, i))
               << "] " << line << "\n";
    }
    const std::string output = result.str();
    return env->NewStringUTF(output.c_str());
}
