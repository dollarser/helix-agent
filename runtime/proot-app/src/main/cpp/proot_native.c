#include <jni.h>

#include <unistd.h>

/*
 * HXA-084: native getpagesize() seam (see the Kotlin-side ProotNative).
 * Returns the host kernel's page size in bytes; the caller treats a
 * non-positive value as a platform fault (it fails closed elsewhere).
 */
JNIEXPORT jlong JNICALL
Java_com_helix_runtime_proot_app_ProotNative_nativeGetpagesize(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    long page = getpagesize();
    return page > 0 ? (jlong) page : (jlong) -1;
}
