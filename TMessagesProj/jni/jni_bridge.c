#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "TgWsProxyJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void* go_handle = NULL;

// Go function types
typedef int  (*fn_StartProxy)(const char*, int, const char*, const char*, int);
typedef int  (*fn_StopProxy)(void);
typedef void (*fn_SetPoolSize)(int);
typedef void (*fn_SetRecvBuffer)(int);
typedef void (*fn_SetSendBuffer)(int);
typedef void (*fn_SetCfProxyCacheDir)(const char*);
typedef void (*fn_SetCfProxyConfig)(int, int, const char*);
typedef void (*fn_SetFakeTls)(int, const char*);
typedef char*(*fn_GetSecretWithPrefix)(void);
typedef char*(*fn_GetStats)(void);
typedef char*(*fn_GetDiagnosticStats)(void);
typedef char*(*fn_GetTransportStats)(void);
typedef void (*fn_SetCfProxyPlainFallback)(int);
typedef void (*fn_SetDiagnosticEnabled)(int);
typedef void (*fn_FreeString)(char*);
typedef void (*fn_ResetForNetworkChange)(void);
typedef void (*fn_ResetCooldowns)(void);

// Function pointers
static fn_StartProxy         pStartProxy = NULL;
static fn_StopProxy          pStopProxy = NULL;
static fn_SetPoolSize        pSetPoolSize = NULL;
static fn_SetRecvBuffer      pSetRecvBuffer = NULL;
static fn_SetSendBuffer      pSetSendBuffer = NULL;
static fn_SetCfProxyCacheDir pSetCfProxyCacheDir = NULL;
static fn_SetCfProxyConfig   pSetCfProxyConfig = NULL;
static fn_SetFakeTls         pSetFakeTls = NULL;
static fn_GetSecretWithPrefix pGetSecretWithPrefix = NULL;
static fn_GetStats           pGetStats = NULL;
static fn_GetDiagnosticStats pGetDiagnosticStats = NULL;
static fn_GetTransportStats pGetTransportStats = NULL;
static fn_SetCfProxyPlainFallback pSetCfProxyPlainFallback = NULL;
static fn_SetDiagnosticEnabled pSetDiagnosticEnabled = NULL;
static fn_FreeString         pFreeString = NULL;
static fn_ResetForNetworkChange pResetForNetworkChange = NULL;
static fn_ResetCooldowns        pResetCooldowns = NULL;

__attribute__((constructor))
void init_bridge() {
    go_handle = dlopen("libtgwsproxy.so", RTLD_NOW | RTLD_GLOBAL);
    if (!go_handle) {
        LOGE("dlopen libtgwsproxy.so failed: %s", dlerror());
        return;
    }

    pStartProxy         = (fn_StartProxy)        dlsym(go_handle, "StartProxy");
    pStopProxy          = (fn_StopProxy)         dlsym(go_handle, "StopProxy");
    pSetPoolSize        = (fn_SetPoolSize)       dlsym(go_handle, "SetPoolSize");
    pSetRecvBuffer      = (fn_SetRecvBuffer)     dlsym(go_handle, "SetRecvBuffer");
    pSetSendBuffer      = (fn_SetSendBuffer)     dlsym(go_handle, "SetSendBuffer");
    pSetCfProxyCacheDir = (fn_SetCfProxyCacheDir)dlsym(go_handle, "SetCfProxyCacheDir");
    pSetCfProxyConfig   = (fn_SetCfProxyConfig)  dlsym(go_handle, "SetCfProxyConfig");
    pSetFakeTls         = (fn_SetFakeTls)        dlsym(go_handle, "SetFakeTls");
    pGetSecretWithPrefix= (fn_GetSecretWithPrefix)dlsym(go_handle, "GetSecretWithPrefix");
    pGetStats           = (fn_GetStats)          dlsym(go_handle, "GetStats");
    pGetDiagnosticStats = (fn_GetDiagnosticStats)dlsym(go_handle, "GetDiagnosticStats");
    pGetTransportStats = (fn_GetTransportStats)dlsym(go_handle, "GetTransportStats");
    pSetCfProxyPlainFallback = (fn_SetCfProxyPlainFallback)dlsym(go_handle, "SetCfProxyPlainFallback");
    pSetDiagnosticEnabled = (fn_SetDiagnosticEnabled)dlsym(go_handle, "SetDiagnosticEnabled");
    pFreeString         = (fn_FreeString)        dlsym(go_handle, "FreeString");
    pResetForNetworkChange = (fn_ResetForNetworkChange)dlsym(go_handle, "ResetForNetworkChange");
    pResetCooldowns        = (fn_ResetCooldowns)       dlsym(go_handle, "ResetCooldowns");

    if (!pStartProxy) LOGE("dlsym StartProxy failed: %s", dlerror());
}

// --- JNI implementations ---

JNIEXPORT jint JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_StartProxy(
    JNIEnv* env, jclass cls, jstring host, jint port, jstring dcIps, jstring secret, jint verbose) {

    if (!pStartProxy) return -1;

    const char* cHost = (*env)->GetStringUTFChars(env, host, NULL);
    const char* cDcIps = dcIps ? (*env)->GetStringUTFChars(env, dcIps, NULL) : "";
    const char* cSecret = secret ? (*env)->GetStringUTFChars(env, secret, NULL) : "";

    int result = pStartProxy(cHost, port, cDcIps, cSecret, verbose);

    (*env)->ReleaseStringUTFChars(env, host, cHost);
    if (dcIps) (*env)->ReleaseStringUTFChars(env, dcIps, cDcIps);
    if (secret) (*env)->ReleaseStringUTFChars(env, secret, cSecret);

    return result;
}

JNIEXPORT jint JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_StopProxy(JNIEnv* env, jclass cls) {
    if (!pStopProxy) return -1;
    return pStopProxy();
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetPoolSize(JNIEnv* env, jclass cls, jint size) {
    if (pSetPoolSize) pSetPoolSize(size);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetRecvBuffer(JNIEnv* env, jclass cls, jint size) {
    if (pSetRecvBuffer) pSetRecvBuffer(size);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetSendBuffer(JNIEnv* env, jclass cls, jint size) {
    if (pSetSendBuffer) pSetSendBuffer(size);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetCfProxyCacheDir(
    JNIEnv* env, jclass cls, jstring cacheDir) {

    if (!pSetCfProxyCacheDir) return;
    const char* cDir = cacheDir ? (*env)->GetStringUTFChars(env, cacheDir, NULL) : "";
    pSetCfProxyCacheDir(cDir);
    if (cacheDir) (*env)->ReleaseStringUTFChars(env, cacheDir, cDir);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetCfProxyConfig(
    JNIEnv* env, jclass cls, jint enabled, jint priority, jstring userDomain) {

    if (!pSetCfProxyConfig) return;
    const char* cDomain = userDomain ? (*env)->GetStringUTFChars(env, userDomain, NULL) : "";
    pSetCfProxyConfig(enabled, priority, cDomain);
    if (userDomain) (*env)->ReleaseStringUTFChars(env, userDomain, cDomain);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetFakeTls(
    JNIEnv* env, jclass cls, jint enabled, jstring domain) {

    if (!pSetFakeTls) return;
    const char* cDomain = domain ? (*env)->GetStringUTFChars(env, domain, NULL) : "";
    pSetFakeTls(enabled, cDomain);
    if (domain) (*env)->ReleaseStringUTFChars(env, domain, cDomain);
}

JNIEXPORT jstring JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetSecretWithPrefix(JNIEnv* env, jclass cls) {
    if (!pGetSecretWithPrefix) return NULL;
    char* result = pGetSecretWithPrefix();
    if (!result) return NULL;
    jstring jstr = (*env)->NewStringUTF(env, result);
    if (pFreeString) pFreeString(result);
    return jstr;
}

JNIEXPORT jstring JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetStats(JNIEnv* env, jclass cls) {
    if (!pGetStats) return NULL;
    char* result = pGetStats();
    if (!result) return NULL;
    jstring jstr = (*env)->NewStringUTF(env, result);
    if (pFreeString) pFreeString(result);
    return jstr;
}

JNIEXPORT jstring JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetTransportStats(JNIEnv* env, jclass cls) {
    if (!pGetTransportStats || !pFreeString) return NULL;
    char* result = pGetTransportStats();
    if (!result) return NULL;
    jstring str = (*env)->NewStringUTF(env, result);
    pFreeString(result);
    return str;
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetCfProxyPlainFallback(JNIEnv* env, jclass cls, jint enabled) {
    if (pSetCfProxyPlainFallback) pSetCfProxyPlainFallback(enabled);
}

JNIEXPORT jstring JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetDiagnosticStats(JNIEnv* env, jclass cls) {
    if (!pGetDiagnosticStats || !pFreeString) return NULL;
    char* result = pGetDiagnosticStats();
    if (!result) return NULL;
    jstring str = (*env)->NewStringUTF(env, result);
    pFreeString(result);
    return str;
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetDiagnosticEnabled(JNIEnv* env, jclass cls, jint enabled) {
    if (pSetDiagnosticEnabled) pSetDiagnosticEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_FreeString(
    JNIEnv* env, jclass cls, jstring str) {
    // Not used — freeing done in GetSecretWithPrefix/GetStats
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_ResetForNetworkChange(
    JNIEnv* env, jclass cls) {
    if (pResetForNetworkChange) pResetForNetworkChange();
}

JNIEXPORT void JNICALL
Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_ResetCooldowns(
    JNIEnv* env, jclass cls) {
    if (pResetCooldowns) pResetCooldowns();
}

// --- soundgram.wsproxy mappings ---

JNIEXPORT jint JNICALL
Java_soundgram_wsproxy_NativeProxy_StartProxy(
    JNIEnv* env, jclass cls, jstring host, jint port, jstring dcIps, jstring secret, jint verbose) {
    return Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_StartProxy(env, cls, host, port, dcIps, secret, verbose);
}

JNIEXPORT jint JNICALL
Java_soundgram_wsproxy_NativeProxy_StopProxy(JNIEnv* env, jclass cls) {
    return Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_StopProxy(env, cls);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetPoolSize(JNIEnv* env, jclass cls, jint size) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetPoolSize(env, cls, size);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetRecvBuffer(JNIEnv* env, jclass cls, jint size) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetRecvBuffer(env, cls, size);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetSendBuffer(JNIEnv* env, jclass cls, jint size) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetSendBuffer(env, cls, size);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetCfProxyCacheDir(
    JNIEnv* env, jclass cls, jstring cacheDir) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetCfProxyCacheDir(env, cls, cacheDir);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetCfProxyConfig(
    JNIEnv* env, jclass cls, jint enabled, jint priority, jstring userDomain) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetCfProxyConfig(env, cls, enabled, priority, userDomain);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetFakeTls(
    JNIEnv* env, jclass cls, jint enabled, jstring domain) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetFakeTls(env, cls, enabled, domain);
}

JNIEXPORT jstring JNICALL
Java_soundgram_wsproxy_NativeProxy_GetSecretWithPrefix(JNIEnv* env, jclass cls) {
    return Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetSecretWithPrefix(env, cls);
}

JNIEXPORT jstring JNICALL
Java_soundgram_wsproxy_NativeProxy_GetStats(JNIEnv* env, jclass cls) {
    return Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetStats(env, cls);
}

JNIEXPORT jstring JNICALL
Java_soundgram_wsproxy_NativeProxy_GetTransportStats(JNIEnv* env, jclass cls) {
    return Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetTransportStats(env, cls);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetCfProxyPlainFallback(JNIEnv* env, jclass cls, jint enabled) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetCfProxyPlainFallback(env, cls, enabled);
}

JNIEXPORT jstring JNICALL
Java_soundgram_wsproxy_NativeProxy_GetDiagnosticStats(JNIEnv* env, jclass cls) {
    return Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_GetDiagnosticStats(env, cls);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_SetDiagnosticEnabled(JNIEnv* env, jclass cls, jint enabled) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_SetDiagnosticEnabled(env, cls, enabled);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_FreeString(
    JNIEnv* env, jclass cls, jstring str) {
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_ResetForNetworkChange(
    JNIEnv* env, jclass cls) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_ResetForNetworkChange(env, cls);
}

JNIEXPORT void JNICALL
Java_soundgram_wsproxy_NativeProxy_ResetCooldowns(
    JNIEnv* env, jclass cls) {
    Java_tw_nekomimi_nekogram_wsproxy_NativeProxy_ResetCooldowns(env, cls);
}
