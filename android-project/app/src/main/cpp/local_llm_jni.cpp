#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <limits>
#include <string>
#include <vector>

namespace {
constexpr const char * TAG = "SpiderLocalLLM";
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
const llama_vocab * g_vocab = nullptr;
uint32_t g_context_tokens = 2048;
bool g_backend_ready = false;

std::string from_jstring(JNIEnv * env, jstring input) {
    if (!input) return {};
    const jsize length = env->GetStringLength(input);
    const jchar * chars = env->GetStringChars(input, nullptr);
    if (!chars) return {};
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
                const uint32_t low = chars[i + 1];
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            } else {
                // Java strings may contain an unpaired surrogate; replace it so native tokenizers
                // always receive valid UTF-8 rather than an invalid encoded surrogate code point.
                cp = 0xFFFD;
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(input, chars);
    return out;
}

std::vector<jchar> utf8_to_utf16(const std::string & input) {
    std::vector<jchar> out;
    out.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        const size_t start = i;
        const uint8_t first = static_cast<uint8_t>(input[i]);
        uint32_t cp = 0xFFFD;
        size_t width = 1;
        bool valid = true;
        if (first <= 0x7F) {
            cp = first;
        } else {
            size_t expected = 0;
            uint32_t minimum = 0;
            if (first >= 0xC2 && first <= 0xDF) {
                expected = 2; minimum = 0x80; cp = first & 0x1F;
            } else if (first >= 0xE0 && first <= 0xEF) {
                expected = 3; minimum = 0x800; cp = first & 0x0F;
            } else if (first >= 0xF0 && first <= 0xF4) {
                expected = 4; minimum = 0x10000; cp = first & 0x07;
            } else {
                valid = false;
            }
            if (valid) {
                if (start + expected > input.size()) {
                    valid = false;
                } else {
                    for (size_t j = 1; j < expected; ++j) {
                        const uint8_t next = static_cast<uint8_t>(input[start + j]);
                        if ((next & 0xC0) != 0x80) { valid = false; break; }
                        cp = (cp << 6) | (next & 0x3F);
                    }
                    if (valid && (cp < minimum || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))) {
                        valid = false;
                    }
                    if (valid) width = expected;
                }
            }
        }
        i += valid ? width : 1;
        if (!valid) cp = 0xFFFD;
        if (cp <= 0xFFFF) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & input) {
    const std::vector<jchar> out = utf8_to_utf16(input);
    if (out.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

void throw_state(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

void unload_locked() {
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    g_vocab = nullptr;
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_context_tokens = 2048;
}

bool decode_tokens(const std::vector<llama_token> & tokens, llama_pos start_position) {
    if (!g_context || tokens.empty()) return false;
    llama_batch_ext * batch = llama_batch_ext_init(g_context);
    if (!batch) return false;
    llama_batch_ext_clear(batch);
    for (size_t i = 0; i < tokens.size(); ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (index < 0) {
            llama_batch_ext_free(batch);
            return false;
        }
        const llama_pos pos = start_position + static_cast<llama_pos>(i);
        if (!llama_batch_ext_set_pos(batch, index, &pos)) {
            llama_batch_ext_free(batch);
            return false;
        }
    }
    if (!llama_batch_ext_set_output_logits(batch, static_cast<int32_t>(tokens.size() - 1), true)) {
        llama_batch_ext_free(batch);
        return false;
    }
    const int32_t result = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch);
    llama_batch_ext_free(batch);
    return result == 0;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_iipauk_spider_SpiderLocalLlmRuntime_nativeLoadModel(
    JNIEnv * env, jobject /*thiz*/, jstring jpath, jint context_tokens, jint threads) {
    const std::string path = from_jstring(env, jpath);
    if (path.empty()) return to_jstring(env, "model_path_invalid");
    if (context_tokens < 512 || context_tokens > 4096) return to_jstring(env, "context_size_invalid");
    if (threads < 1 || threads > 8) return to_jstring(env, "thread_count_invalid");

    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // Portable CPU-first path for Android ARM64 and x86_64.
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        unload_locked();
        return to_jstring(env, "model_load_failed_check_gguf_and_available_ram");
    }
    g_vocab = llama_model_get_vocab(g_model);
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context_tokens);
    params.n_batch = static_cast<uint32_t>(context_tokens);
    params.n_ubatch = std::min<uint32_t>(params.n_batch, 512U);
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.no_perf = true;
    g_context = llama_init_from_model(g_model, params);
    if (!g_context || !g_vocab) {
        unload_locked();
        return to_jstring(env, "model_context_init_failed");
    }
    g_context_tokens = static_cast<uint32_t>(context_tokens);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Local model loaded; path omitted from logs");
    return to_jstring(env, "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_iipauk_spider_SpiderLocalLlmRuntime_nativeGenerate(
    JNIEnv * env, jobject /*thiz*/, jstring j_system, jobjectArray j_roles,
    jobjectArray j_contents, jint max_tokens, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vocab) {
        throw_state(env, "Локальная модель не загружена.");
        return nullptr;
    }
    if (max_tokens < 1 || max_tokens > 512 || !std::isfinite(temperature) || temperature < 0.0f || temperature > 1.5f) {
        throw_state(env, "Параметры локальной генерации недопустимы.");
        return nullptr;
    }
    const std::string system_prompt = from_jstring(env, j_system);
    const jsize count = j_roles ? env->GetArrayLength(j_roles) : 0;
    const jsize content_count = j_contents ? env->GetArrayLength(j_contents) : 0;
    if (count < 1 || count > 12 || count != content_count || system_prompt.size() > 5000) {
        throw_state(env, "Контекст локального диалога слишком большой или повреждён.");
        return nullptr;
    }

    std::vector<std::string> role_storage;
    std::vector<std::string> content_storage;
    role_storage.reserve(static_cast<size_t>(count) + 1);
    content_storage.reserve(static_cast<size_t>(count) + 1);
    role_storage.emplace_back("system");
    content_storage.emplace_back(system_prompt);
    size_t total_chars = system_prompt.size();
    for (jsize i = 0; i < count; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(j_roles, i));
        auto c = static_cast<jstring>(env->GetObjectArrayElement(j_contents, i));
        std::string role = from_jstring(env, r);
        std::string content = from_jstring(env, c);
        if (r) env->DeleteLocalRef(r);
        if (c) env->DeleteLocalRef(c);
        if (role != "user" && role != "assistant") {
            throw_state(env, "Роль сообщения локального диалога не разрешена.");
            return nullptr;
        }
        if (content.empty() || content.size() > 2400) {
            throw_state(env, "Сообщение локального диалога пустое или слишком большое.");
            return nullptr;
        }
        total_chars += content.size();
        if (total_chars > 14000) {
            throw_state(env, "Контекст локального диалога превышает безопасный предел.");
            return nullptr;
        }
        role_storage.push_back(std::move(role));
        content_storage.push_back(std::move(content));
    }

    std::vector<llama_chat_message> messages;
    messages.reserve(role_storage.size());
    for (size_t i = 0; i < role_storage.size(); ++i) {
        messages.push_back({role_storage[i].c_str(), content_storage[i].c_str()});
    }
    const char * chat_template = llama_model_chat_template(g_model, nullptr);
    if (!chat_template) {
        throw_state(env, "У локальной модели отсутствует поддерживаемый chat template.");
        return nullptr;
    }

    std::vector<char> formatted(std::max<size_t>(16384, total_chars * 3 + 1024));
    int32_t prompt_length = llama_chat_apply_template(chat_template, messages.data(), messages.size(), true, formatted.data(), formatted.size());
    if (prompt_length < 0 || static_cast<size_t>(prompt_length) > 1'000'000) {
        throw_state(env, "Не удалось подготовить диалог для локальной модели.");
        return nullptr;
    }
    if (static_cast<size_t>(prompt_length) >= formatted.size()) {
        formatted.resize(static_cast<size_t>(prompt_length) + 1);
        prompt_length = llama_chat_apply_template(chat_template, messages.data(), messages.size(), true, formatted.data(), formatted.size());
        if (prompt_length < 0 || static_cast<size_t>(prompt_length) >= formatted.size()) {
            throw_state(env, "Шаблон диалога локальной модели слишком большой.");
            return nullptr;
        }
    }
    const std::string prompt(formatted.data(), static_cast<size_t>(prompt_length));
    if (prompt.size() > 24000) {
        throw_state(env, "Подготовленный запрос превышает безопасный предел.");
        return nullptr;
    }
    const int32_t token_count = -llama_tokenize(g_vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (token_count <= 0 || static_cast<uint32_t>(token_count) + static_cast<uint32_t>(max_tokens) + 4 >= g_context_tokens) {
        throw_state(env, "Контекст модели заполнен. Начните более короткий диалог или увеличьте размер контекста.");
        return nullptr;
    }
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
    const int32_t tokenized = llama_tokenize(g_vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), prompt_tokens.data(), prompt_tokens.size(), true, true);
    if (tokenized < 0) {
        throw_state(env, "Не удалось токенизировать запрос локальной модели.");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) {
        llama_memory_clear(llama_get_memory(g_context), true);
        throw_state(env, "Не удалось подготовить выбор токенов локальной модели.");
        return nullptr;
    }
    llama_sampler * top_p = llama_sampler_init_top_p(0.90f, 1);
    if (!top_p) {
        llama_sampler_free(sampler);
        llama_memory_clear(llama_get_memory(g_context), true);
        throw_state(env, "Не удалось подготовить фильтр токенов локальной модели.");
        return nullptr;
    }
    llama_sampler_chain_add(sampler, top_p);
    llama_sampler * temp = llama_sampler_init_temp(temperature);
    if (!temp) {
        llama_sampler_free(sampler);
        llama_memory_clear(llama_get_memory(g_context), true);
        throw_state(env, "Не удалось подготовить температуру локальной модели.");
        return nullptr;
    }
    llama_sampler_chain_add(sampler, temp);
    llama_sampler * dist = llama_sampler_init_dist(LLAMA_DEFAULT_SEED);
    if (!dist) {
        llama_sampler_free(sampler);
        llama_memory_clear(llama_get_memory(g_context), true);
        throw_state(env, "Не удалось подготовить генератор локальной модели.");
        return nullptr;
    }
    llama_sampler_chain_add(sampler, dist);

    if (!decode_tokens(prompt_tokens, 0)) {
        llama_sampler_free(sampler);
        llama_memory_clear(llama_get_memory(g_context), true);
        throw_state(env, "Локальная модель не смогла обработать запрос.");
        return nullptr;
    }

    std::string response;
    response.reserve(static_cast<size_t>(max_tokens) * 4);
    for (jint i = 0; i < max_tokens; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (token == LLAMA_TOKEN_NULL) {
            llama_sampler_free(sampler);
            llama_memory_clear(llama_get_memory(g_context), true);
            throw_state(env, "Локальная модель не смогла выбрать следующий токен.");
            return nullptr;
        }
        if (llama_vocab_is_eog(g_vocab, token)) break;
        char piece[512];
        const int32_t piece_len = llama_token_to_piece(g_vocab, token, piece, sizeof(piece), 0, true);
        if (piece_len < 0) {
            llama_sampler_free(sampler);
            llama_memory_clear(llama_get_memory(g_context), true);
            throw_state(env, "Не удалось декодировать ответ локальной модели.");
            return nullptr;
        }
        if (response.size() + static_cast<size_t>(piece_len) > 16000) break;
        response.append(piece, static_cast<size_t>(piece_len));
        const std::vector<llama_token> next_token{token};
        if (i + 1 < max_tokens && !decode_tokens(next_token, static_cast<llama_pos>(prompt_tokens.size() + i))) {
            llama_sampler_free(sampler);
            llama_memory_clear(llama_get_memory(g_context), true);
            throw_state(env, "Генерация локальной модели прервалась.");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    llama_memory_clear(llama_get_memory(g_context), true);
    while (!response.empty() && (response.back() == '\0' || response.back() == ' ' || response.back() == '\n' || response.back() == '\r')) response.pop_back();
    if (response.empty()) {
        throw_state(env, "Локальная модель вернула пустой ответ.");
        return nullptr;
    }
    return to_jstring(env, response);
}

extern "C" JNIEXPORT void JNICALL
Java_com_iipauk_spider_SpiderLocalLlmRuntime_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_iipauk_spider_SpiderLocalLlmRuntime_nativeIsLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model && g_context && g_vocab) ? JNI_TRUE : JNI_FALSE;
}
