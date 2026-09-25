//
// /etc/resolv.conf 및 /etc/hosts 접근을 사용자 정의 경로로 리다이렉트.
//
// 두 가지 케이스 모두 커버:
//  1) JDK 의 sun.net.dns.ResolverConfigurationImpl 가 /etc/resolv.conf 를 읽음
//     → SRV 레코드(=KR 마인크래프트 서버에서 많이 씀) 조회용
//  2) glibc/bionic 의 getaddrinfo 가 /etc/hosts 를 먼저 본 다음 DNS 로 fallback
//     → Hamachi 25.x.x.x IP 같은 수동 매핑용
//

#include "native_hooks.h"
#include <jni.h>
#include <bytehook.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <stdbool.h>

#define TAG __FILE_NAME__
#include <log.h>

static char g_resolv_redirect[512] = {0};
static char g_hosts_redirect[512]  = {0};
static bool g_hooks_installed = false;

static const char* RESOLV_TARGET = "/etc/resolv.conf";
static const char* HOSTS_TARGET  = "/etc/hosts";

static inline const char* maybe_redirect(const char* path) {
    if (!path) return path;
    if (g_resolv_redirect[0] && strcmp(path, RESOLV_TARGET) == 0) {
        return g_resolv_redirect;
    }
    if (g_hosts_redirect[0] && strcmp(path, HOSTS_TARGET) == 0) {
        return g_hosts_redirect;
    }
    return path;
}

typedef int   (*open_func)(const char*, int, mode_t);
typedef int   (*openat_func)(int, const char*, int, mode_t);
typedef FILE* (*fopen_func)(const char*, const char*);

static int custom_open(const char* pathname, int flags, mode_t mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("open(%s) → %s", pathname, real);
    int result = BYTEHOOK_CALL_PREV(custom_open, open_func, real, flags, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

static int custom_open64(const char* pathname, int flags, mode_t mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("open64(%s) → %s", pathname, real);
    int result = BYTEHOOK_CALL_PREV(custom_open64, open_func, real, flags, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

static int custom_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("openat(%s) → %s", pathname, real);
    int result = BYTEHOOK_CALL_PREV(custom_openat, openat_func, dirfd, real, flags, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

static FILE* custom_fopen(const char* pathname, const char* mode) {
    const char* real = maybe_redirect(pathname);
    if (real != pathname) LOGI("fopen(%s) → %s", pathname, real);
    FILE* result = BYTEHOOK_CALL_PREV(custom_fopen, fopen_func, real, mode);
    BYTEHOOK_POP_STACK();
    return result;
}

//static void install_hooks_once(void) {
//    if (g_hooks_installed) return;
//
//    bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
//
//    bytehook_stub_t s1 = bytehook_hook_all(NULL, "open",    &custom_open,    NULL, NULL);
//    bytehook_stub_t s2 = bytehook_hook_all(NULL, "open64",  &custom_open64,  NULL, NULL);
//    bytehook_stub_t s3 = bytehook_hook_all(NULL, "openat",  &custom_openat,  NULL, NULL);
//    bytehook_stub_t s4 = bytehook_hook_all(NULL, "fopen",   &custom_fopen,   NULL, NULL);
//    bytehook_stub_t s5 = bytehook_hook_all(NULL, "fopen64", &custom_fopen,   NULL, NULL);
//
//    LOGI("network hooks 설치: open=%p open64=%p openat=%p fopen=%p fopen64=%p",
//         s1, s2, s3, s4, s5);
//
//    create_resolver_hooks(bytehook_hook_all);
//    g_hooks_installed = true;
//}

/**
 * resolv.conf 리다이렉트 — KR 도메인 SRV 레코드 조회용
 */
JNIEXPORT void JNICALL
Java_kr_co_donghyun_flamelauncher_presentation_util_dns_DnsHookNative_installResolvConfRedirect(
        JNIEnv* env, jclass clazz, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    strncpy(g_resolv_redirect, path, sizeof(g_resolv_redirect) - 1);
    g_resolv_redirect[sizeof(g_resolv_redirect) - 1] = 0;
    LOGI("resolv.conf 리다이렉트 대상: %s", g_resolv_redirect);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

//    install_hooks_once();
}

/**
 * hosts 리다이렉트 — Hamachi/LAN 등 수동 호스트 매핑용
 */
JNIEXPORT void JNICALL
Java_kr_co_donghyun_flamelauncher_presentation_util_dns_DnsHookNative_installHostsRedirect(
        JNIEnv* env, jclass clazz, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    strncpy(g_hosts_redirect, path, sizeof(g_hosts_redirect) - 1);
    g_hosts_redirect[sizeof(g_hosts_redirect) - 1] = 0;
    LOGI("hosts 리다이렉트 대상: %s", g_hosts_redirect);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

//    install_hooks_once();
}