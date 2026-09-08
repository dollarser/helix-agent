// Test-process-only JVMTI observer. Never linked into an APK or production runtime.
#include <jni.h>
// Use the installed JDK's JVMTI declarations with the NDK's Android JNI ABI.
#define _JAVASOFT_JNI_H_
#define JNINativeInterface_ JNINativeInterface
#include <jvmti.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unwind.h>
#include <mutex>
#include <cstring>
#include <string>
#include <unordered_map>

namespace {
jvmtiEnv* ti = nullptr;
jniNativeInterface* original = nullptr;
jniNativeInterface replacement; // ART retains this table: process lifetime is required.
std::mutex guard;
struct Counts { size_t created = 0, deleted = 0; };
std::unordered_map<jweak, std::string> live;
std::unordered_map<std::string, Counts> groups;
thread_local bool observing = false;
size_t unmatched = 0;
struct Stack { std::string text; size_t depth = 0; };
_Unwind_Reason_Code unwind(_Unwind_Context* context, void* data) {
    auto& stack = *static_cast<Stack*>(data);
    const uintptr_t pc = _Unwind_GetIP(context);
    Dl_info info{};
    if (pc && dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname) {
        char offset[32];
        snprintf(offset, sizeof(offset), "+%zx;", pc - reinterpret_cast<uintptr_t>(info.dli_fbase));
        stack.text += std::string(strrchr(info.dli_fname, '/') ? strrchr(info.dli_fname, '/') + 1 : info.dli_fname) + offset;
    }
    return ++stack.depth >= 12 ? _URC_END_OF_STACK : _URC_NO_REASON;
}
jweak JNICALL create(JNIEnv* env, jobject object) {
    jweak result = original->NewWeakGlobalRef(env, object);
    if (!result || observing || env->ExceptionCheck()) return result;
    observing = true;
    Stack stack;
    Dl_info caller{};
    void* address = __builtin_return_address(0);
    if (dladdr(address, &caller) && caller.dli_fname) {
        char offset[32];
        snprintf(offset, sizeof(offset), "+%zx;", reinterpret_cast<uintptr_t>(address) - reinterpret_cast<uintptr_t>(caller.dli_fbase));
        stack.text = std::string(strrchr(caller.dli_fname, '/') ? strrchr(caller.dli_fname, '/') + 1 : caller.dli_fname) + offset;
    }
    _Unwind_Backtrace(unwind, &stack);
    std::string key = "unknown";
    jclass klass = env->GetObjectClass(object);
    char* signature = nullptr;
    if (klass && ti->GetClassSignature(klass, &signature, nullptr) == JVMTI_ERROR_NONE) {
        key = signature;
        ti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    }
    if (klass) env->DeleteLocalRef(klass);
    key += " stack=" + stack.text;
    {
        std::lock_guard<std::mutex> lock(guard);
        if (live.size() < 100000) {
            live[result] = key;
            groups[key].created++;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "HelixJniTrace", "OVERFLOW observation invalid");
        }
    }
    observing = false;
    return result;
}
void JNICALL remove(JNIEnv* env, jweak reference) {
    {
        std::lock_guard<std::mutex> lock(guard);
        auto found = live.find(reference);
        if (found != live.end()) {
            groups[found->second].deleted++;
            live.erase(found);
        } else if (reference) {
            unmatched++;
        }
    }
    original->DeleteWeakGlobalRef(env, reference);
}
void dump() {
    std::lock_guard<std::mutex> lock(guard);
    __android_log_print(ANDROID_LOG_INFO, "HelixJniTrace", "SUMMARY live=%zu groups=%zu unmatchedDeletes=%zu", live.size(), groups.size(), unmatched);
    std::unordered_map<std::string, Counts> classes;
    for (const auto& item : groups) {
        auto& aggregate = classes[item.first.substr(0, item.first.find(" stack="))];
        aggregate.created += item.second.created;
        aggregate.deleted += item.second.deleted;
        const auto& count = item.second;
        if (count.created != count.deleted) {
            __android_log_print(ANDROID_LOG_INFO, "HelixJniTrace", "GROUP new=%zu delete=%zu live=%zu %s", count.created, count.deleted, count.created-count.deleted, item.first.c_str());
        }
    }
    for (const auto& item : classes) {
        __android_log_print(ANDROID_LOG_INFO, "HelixJniTrace", "CLASS new=%zu delete=%zu live=%zu %s", item.second.created, item.second.deleted, item.second.created-item.second.deleted, item.first.c_str());
    }
    __android_log_print(ANDROID_LOG_INFO, "HelixJniTrace", "END reference dump");
}
}
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char*, void*) {
    if (original) { dump(); return JNI_OK; }
    if (vm->GetEnv(reinterpret_cast<void**>(&ti), JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
    if (ti->GetJNIFunctionTable(&original) != JVMTI_ERROR_NONE) return JNI_ERR;
    replacement = *original;
    replacement.NewWeakGlobalRef = create;
    replacement.DeleteWeakGlobalRef = remove;
    const auto error = ti->SetJNIFunctionTable(&replacement);
    __android_log_print(ANDROID_LOG_INFO, "HelixJniTrace", "ATTACH result=%d", error);
    return error == JVMTI_ERROR_NONE ? JNI_OK : JNI_ERR;
}
