#include <jni.h>
#include <unistd.h>
#include <sys/stat.h>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"
#include "chat.h"
#include "log.h"
#include "sampling.h"

// Loaded only by :model_runtime. The service serializes load/generate/unload; cancel is lock-free.
namespace {
llama_model * model = nullptr;
llama_context * context = nullptr;
int asset_fd = -1;
std::atomic<bool> cancelled{false};
void silent_log(enum ggml_log_level, const char *, void *) {}
bool abort_generation(void *) { return cancelled.load(); }
void release() {
    if (context) llama_free(context);
    if (model) llama_model_free(model);
    if (asset_fd >= 0) close(asset_fd);
    context = nullptr; model = nullptr; asset_fd = -1;
}
jbyteArray bytes(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
std::string read_bytes(JNIEnv * env, jbyteArray value) {
    const auto size = env->GetArrayLength(value);
    if (size > 1024 * 1024) throw std::runtime_error("limit");
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
std::string generate(const common_json & request) {
    if (!context || !model) throw std::runtime_error("not loaded");
    common_chat_templates_inputs input;
    input.enable_thinking = request.at("thinking").get<bool>();
    input.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    for (const auto & row : request.at("history")) {
        common_chat_msg message;
        message.role = row.at("role"); message.content = row.at("text");
        message.tool_name = row.value("toolName", ""); message.tool_call_id = row.value("callId", "");
        for (const auto & call : row.at("calls")) {
            message.tool_calls.push_back({call.at("name"), call.at("arguments"), call.at("id")});
        }
        input.messages.push_back(std::move(message));
    }
    for (const auto & tool : request.at("functions")) {
        input.tools.push_back({tool.at("name"), tool.at("description"), tool.at("schema")});
    }
    const char * templ = llama_model_chat_template(model, nullptr);
    if (!templ || !*templ) throw std::runtime_error("missing template");
    auto templates = common_chat_templates_init(model, "");
    auto params = common_chat_templates_apply(templates.get(), input);
    if (params.prompt.size() > 1024 * 1024) throw std::runtime_error("prompt limit");
    const auto * vocab = llama_model_get_vocab(model);
    auto count = llama_tokenize(vocab, params.prompt.data(), params.prompt.size(), nullptr, 0, true, true);
    if (count >= 0 || -int64_t(count) >= llama_n_ctx(context)) throw std::runtime_error("context limit");
    std::vector<llama_token> tokens(-count);
    count = llama_tokenize(vocab, params.prompt.data(), params.prompt.size(), tokens.data(), tokens.size(), true, true);
    if (count < 0) throw std::runtime_error("tokenization");
    llama_memory_clear(llama_get_memory(context), true);
    for (int i = 0; i < count; i += 256) {
        if (cancelled.load()) throw std::runtime_error("cancelled");
        auto batch = llama_batch_get_one(tokens.data() + i, std::min(256, count - i));
        if (llama_decode(context, batch) != 0) throw std::runtime_error("decode");
    }
    common_params_sampling sampling;
    sampling.temp = 0.0f;
    if (!params.grammar.empty()) {
        sampling.grammar = {COMMON_GRAMMAR_TYPE_TOOL_CALLS, params.grammar};
        sampling.grammar_lazy = params.grammar_lazy;
        sampling.grammar_triggers = params.grammar_triggers;
        sampling.generation_prompt = params.generation_prompt;
    }
    for (const auto & marker : params.preserved_tokens) {
        auto ids = common_tokenize(vocab, marker, false, true);
        sampling.preserved_tokens.insert(ids.begin(), ids.end());
    }
    std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
        common_sampler_init(model, sampling), common_sampler_free);
    if (!sampler) throw std::runtime_error("sampler");
    const int limit = std::min(request.at("limit").get<int>(), int(llama_n_ctx(context)) - count);
    if (limit <= 0) throw std::runtime_error("context limit");
    std::string output;
    int generated = 0;
    bool stopped = false;
    for (; generated < limit; ++generated) {
        if (cancelled.load()) throw std::runtime_error("cancelled");
        auto token = common_sampler_sample(sampler.get(), context, -1);
        common_sampler_accept(sampler.get(), token, true);
        if (llama_vocab_is_eog(vocab, token)) { stopped = true; break; }
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        if (size < 0) {
            if (-int64_t(size) > 65536) throw std::runtime_error("piece limit");
            piece.resize(-size);
            size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        }
        if (size < 0) throw std::runtime_error("piece");
        output.append(piece.data(), size);
        if (output.size() > 1024 * 1024) throw std::runtime_error("output limit");
        for (const auto & stop : params.additional_stops) {
            if (stop.empty()) continue;
            const auto position = output.find(stop);
            if (position != std::string::npos) {
                output.resize(position); stopped = true; break;
            }
        }
        if (stopped) { ++generated; break; }
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(context, batch) != 0) throw std::runtime_error("decode");
    }
    // Truncated syntax is not a complete tool call. Preserve usage without invoking
    // a template parser that may throw on partial JSON and discard those counts.
    if (!stopped) {
        return common_json({{"text",""},{"reasoning",""},{"calls",common_json::array()},
            {"inputTokens",count},{"outputTokens",generated},{"finish","length"}}).dump();
    }
    common_chat_parser_params parser(params);
    parser.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    if (!params.parser.empty()) parser.parser.load(params.parser);
    auto message = common_chat_parse(output, false, parser);
    common_json calls = common_json::array();
    for (const auto & call : message.tool_calls) calls.push_back({{"name",call.name},{"arguments",call.arguments}});
    return common_json({{"text",message.content},{"reasoning",message.reasoning_content},{"calls",calls},
        {"inputTokens",count},{"outputTokens",generated},{"finish",stopped ? "stop" : "length"}}).dump();
}
}
extern "C" JNIEXPORT jint JNICALL
Java_com_helix_app_localmodel_LlamaNative_load(JNIEnv *, jobject, jint fd, jint window, jint threads, jlong memory_budget) {
    try {
    // Template exceptions can include input content. Only closed Kotlin error codes may escape.
    common_log_set_verbosity_thold(-1);
    common_log_pause(common_log_main());
    llama_log_set(silent_log, nullptr);
    release(); cancelled.store(false);
    llama_backend_init();
    asset_fd = dup(fd);
    if (asset_fd < 0) return -1;
    struct stat asset_stat {};
    if (fstat(asset_fd, &asset_stat) != 0 || memory_budget <= 0) { release(); return -1; }
    if (asset_stat.st_size >= memory_budget) { release(); return -2; }
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    mp.progress_callback = [](float, void *) { return !cancelled.load(); };
    model = llama_model_load_from_file(("/proc/self/fd/" + std::to_string(asset_fd)).c_str(), mp);
    if (!model) { release(); return -1; }
    const auto training_window = llama_model_n_ctx_train(model);
    window = std::min(window, int(training_window));
    char arch[128] {};
    llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));
    auto dimension = [&](const char * suffix, int fallback) -> int64_t {
        char value[32] {};
        if (llama_model_meta_val_str(model, (std::string(arch) + suffix).c_str(), value, sizeof(value)) < 0) return fallback;
        const auto parsed = std::stoll(value);
        if (parsed < 1 || parsed > 65536) throw std::runtime_error("dimension");
        return parsed;
    };
    const auto heads = llama_model_n_head(model);
    const auto head_dim = heads > 0 ? llama_model_n_embd(model) / heads : 0;
    const auto key_dim = dimension(".attention.key_length", head_dim);
    const auto value_dim = dimension(".attention.value_length", head_dim);
    // Conservative F16 KV estimate plus weights and scratch reserve. This is an
    // admission guard, not a promise that every architecture can fit the device.
    const long double kv = (key_dim + value_dim) * static_cast<long double>(llama_model_n_head_kv(model)) * llama_model_n_layer(model) * window * 2;
    if (asset_stat.st_size + kv + 256LL * 1024 * 1024 > memory_budget) { release(); return -2; }
    auto cp = llama_context_default_params();
    cp.n_ctx = window; cp.n_batch = 256; cp.n_ubatch = 256;
    cp.n_threads = threads; cp.n_threads_batch = threads; cp.abort_callback = abort_generation;
    context = llama_init_from_model(model, cp);
    if (!context) { release(); return -1; }
    return llama_model_n_ctx_train(model);
    } catch (const std::bad_alloc &) { release(); return -2; }
      catch (const std::exception &) { release(); return -1; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_helix_app_localmodel_LlamaNative_generate(JNIEnv * env, jobject, jbyteArray request) {
    try { return bytes(env, generate(common_json::parse(read_bytes(env, request)))); }
    catch (const std::bad_alloc &) { return bytes(env, "{\"error\":\"LOCAL_RUNTIME_OOM\"}"); }
    catch (const std::exception &) { return bytes(env, "{\"error\":\"LOCAL_GENERATION_FAILED\"}"); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_helix_app_localmodel_LlamaNative_cancel(JNIEnv *, jobject) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_com_helix_app_localmodel_LlamaNative_prepare(JNIEnv *, jobject) { cancelled.store(false); }
extern "C" JNIEXPORT void JNICALL
Java_com_helix_app_localmodel_LlamaNative_unload(JNIEnv *, jobject) { release(); }
