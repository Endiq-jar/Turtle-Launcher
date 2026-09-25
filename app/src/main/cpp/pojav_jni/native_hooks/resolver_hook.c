//
// getaddrinfo 후킹 — JVM 의 호스트네임 해석을 Android InetAddress 로 위임.
//
// 동작:
//  1) 원래 getaddrinfo 를 먼저 호출. 성공하면 그대로 반환 (정상 경로).
//  2) 실패하면 Dalvik(Android) JVM 에 attach 해서 InetAddress.getAllByName(host)
//     를 호출 → 결과 InetAddress[] 의 byte 주소를 꺼내 addrinfo 체인 생성.
//
// freeaddrinfo 는 후킹하지 않음. ai / ai_addr 을 일반 calloc 으로 잡았으니
// glibc/bionic 의 freeaddrinfo 가 그대로 free 해도 안전.
//

#define _GNU_SOURCE
#include "native_hooks.h"
#include <jni.h>
#include <bytehook.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netdb.h>
#include <pthread.h>

#include "environ/environ.h"

#define TAG __FILE_NAME__
#include <log.h>
#include <dlfcn.h>

typedef int (*getaddrinfo_fn)(const char*, const char*,
                              const struct addrinfo*, struct addrinfo**);

static jclass    g_inetAddrClass = NULL;        // global ref
static jmethodID g_method_getAllByName = NULL;  // static
static jmethodID g_method_getAddress   = NULL;  // instance
static pthread_mutex_t g_init_mutex = PTHREAD_MUTEX_INITIALIZER;
// 파일 상단에 static
static getaddrinfo_fn g_real_getaddrinfo = NULL;

static struct addrinfo* alloc_ai_v4(uint32_t ip_be, uint16_t port_be, int socktype) {
    struct addrinfo* ai = calloc(1, sizeof(struct addrinfo));
    struct sockaddr_in* sa = calloc(1, sizeof(struct sockaddr_in));
    if (!ai || !sa) { free(ai); free(sa); return NULL; }
    sa->sin_family = AF_INET;
    sa->sin_port = port_be;
    sa->sin_addr.s_addr = ip_be;
    ai->ai_family = AF_INET;
    ai->ai_socktype = socktype ? socktype : SOCK_STREAM;
    ai->ai_addrlen = sizeof(struct sockaddr_in);
    ai->ai_addr = (struct sockaddr*)sa;
    return ai;
}

static struct addrinfo* alloc_ai_v6(const uint8_t bytes[16], uint16_t port_be, int socktype) {
    struct addrinfo* ai = calloc(1, sizeof(struct addrinfo));
    struct sockaddr_in6* sa = calloc(1, sizeof(struct sockaddr_in6));
    if (!ai || !sa) { free(ai); free(sa); return NULL; }
    sa->sin6_family = AF_INET6;
    sa->sin6_port = port_be;
    memcpy(&sa->sin6_addr, bytes, 16);
    ai->ai_family = AF_INET6;
    ai->ai_socktype = socktype ? socktype : SOCK_STREAM;
    ai->ai_addrlen = sizeof(struct sockaddr_in6);
    ai->ai_addr = (struct sockaddr*)sa;
    return ai;
}

static bool looks_like_ip_literal(const char* s) {
    bool has_digit = false;
    bool only_ip_chars = true;
    for (const char* p = s; *p; p++) {
        if (*p >= '0' && *p <= '9') { has_digit = true; continue; }
        if (*p == '.' || *p == ':') continue;
        // IPv6 hex
        if ((*p >= 'a' && *p <= 'f') || (*p >= 'A' && *p <= 'F')) continue;
        only_ip_chars = false;
        break;
    }
    return has_digit && only_ip_chars;
}

static int ensure_jni_cached(JNIEnv* env) {
    pthread_mutex_lock(&g_init_mutex);
    int rc = 0;
    if (g_method_getAllByName == NULL) {
        jclass cls = (*env)->FindClass(env, "java/net/InetAddress");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (cls == NULL) { rc = -1; goto out; }
        g_inetAddrClass = (jclass)(*env)->NewGlobalRef(env, cls);
        g_method_getAllByName = (*env)->GetStaticMethodID(env, g_inetAddrClass,
                                                          "getAllByName", "(Ljava/lang/String;)[Ljava/net/InetAddress;");
        g_method_getAddress = (*env)->GetMethodID(env, g_inetAddrClass,
                                                  "getAddress", "()[B");
        (*env)->DeleteLocalRef(env, cls);
        if (g_method_getAllByName == NULL || g_method_getAddress == NULL) rc = -1;
    }
    out:
    pthread_mutex_unlock(&g_init_mutex);
    return rc;
}

static int resolve_via_android(const char* node, const char* service,
                               const struct addrinfo* hints,
                               struct addrinfo** res) {
    if (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL || node == NULL)
        return EAI_FAIL;

    uint16_t port_be = 0;
    if (service && *service) {
        char* end = NULL;
        long p = strtol(service, &end, 10);
        if (end && *end == 0 && p >= 0 && p <= 65535) port_be = htons((uint16_t)p);
    }
    int socktype   = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
    int want_fam   = (hints && hints->ai_family)   ? hints->ai_family   : AF_UNSPEC;

    JavaVM* vm = pojav_environ->dalvikJavaVMPtr;
    JNIEnv* env = NULL;
    int detach = 0;
    jint st = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return EAI_FAIL;
        detach = 1;
    } else if (st != JNI_OK || env == NULL) {
        return EAI_FAIL;
    }

    if (ensure_jni_cached(env) != 0) {
        if (detach) (*vm)->DetachCurrentThread(vm);
        return EAI_FAIL;
    }

    jstring jhost = (*env)->NewStringUTF(env, node);
    jobjectArray addrs = (jobjectArray)(*env)->CallStaticObjectMethod(env,
                                                                      g_inetAddrClass, g_method_getAllByName, jhost);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, jhost);
        if (detach) (*vm)->DetachCurrentThread(vm);
        LOGI("Android resolver: '%s' → UnknownHostException", node);
        return EAI_NONAME;
    }
    (*env)->DeleteLocalRef(env, jhost);

    if (addrs == NULL) {
        if (detach) (*vm)->DetachCurrentThread(vm);
        return EAI_NONAME;
    }

    jsize count = (*env)->GetArrayLength(env, addrs);
    struct addrinfo *head = NULL, *tail = NULL;
    int ok = 0;

    for (jsize i = 0; i < count; i++) {
        jobject ia = (*env)->GetObjectArrayElement(env, addrs, i);
        if (!ia) continue;
        jbyteArray b = (jbyteArray)(*env)->CallObjectMethod(env, ia, g_method_getAddress);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, ia);
            continue;
        }
        if (!b) { (*env)->DeleteLocalRef(env, ia); continue; }
        jsize len = (*env)->GetArrayLength(env, b);
        struct addrinfo* node_ai = NULL;
        if (len == 4 && (want_fam == AF_UNSPEC || want_fam == AF_INET)) {
            uint32_t ip_be;
            (*env)->GetByteArrayRegion(env, b, 0, 4, (jbyte*)&ip_be);
            node_ai = alloc_ai_v4(ip_be, port_be, socktype);
        } else if (len == 16 && (want_fam == AF_UNSPEC || want_fam == AF_INET6)) {
            uint8_t buf[16];
            (*env)->GetByteArrayRegion(env, b, 0, 16, (jbyte*)buf);
            node_ai = alloc_ai_v6(buf, port_be, socktype);
        }
        (*env)->DeleteLocalRef(env, b);
        (*env)->DeleteLocalRef(env, ia);
        if (node_ai) {
            if (!head) head = node_ai;
            if (tail) tail->ai_next = node_ai;
            tail = node_ai;
            ok++;
        }
    }
    (*env)->DeleteLocalRef(env, addrs);
    if (detach) (*vm)->DetachCurrentThread(vm);

    if (!head) return EAI_NONAME;
    *res = head;
    LOGI("Android resolver: '%s' → %d addr(s)", node, ok);
    return 0;
}

static int custom_getaddrinfo(const char* node, const char* service,
                              const struct addrinfo* hints,
                              struct addrinfo** res) {
    LOGI("⮕ getaddrinfo('%s', service='%s')",
         node ? node : "(null)", service ? service : "(null)");

    // 첫 진입 시 원래 함수 lazy-bind
    if (g_real_getaddrinfo == NULL) {
        g_real_getaddrinfo = (getaddrinfo_fn) dlsym(RTLD_NEXT, "getaddrinfo");
        if (g_real_getaddrinfo == NULL) {
            // 최후의 수단 — bytehook trampoline
            int r = BYTEHOOK_CALL_PREV(custom_getaddrinfo, getaddrinfo_fn,
                                       node, service, hints, res);
            BYTEHOOK_POP_STACK();
            return r;
        }
    }

    if (node == NULL || looks_like_ip_literal(node)) {
        BYTEHOOK_POP_STACK();
        return g_real_getaddrinfo(node, service, hints, res);
    }

    int orig = g_real_getaddrinfo(node, service, hints, res);
    if (orig == 0) {
        LOGI("   ✓ native ok: '%s'", node);
        BYTEHOOK_POP_STACK();
        return 0;
    }
    LOGI("native getaddrinfo('%s') failed (rc=%d) — falling back to Android", node, orig);
    int rc = resolve_via_android(node, service, hints, res);
    BYTEHOOK_POP_STACK();
    return rc;
}

void create_resolver_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    bytehook_stub_t s = bytehook_hook_all_p(
            NULL, "getaddrinfo", &custom_getaddrinfo, NULL, NULL);
    LOGI("Installed resolver hook, stub=%p", s);
}