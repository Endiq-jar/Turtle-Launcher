#include <jni.h>
#include <dlfcn.h>
#include <vector>
#include <string>
#include <algorithm>
#include <android/log.h>
#include <sys/mman.h>


#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include "environ.h"
#include "log.h"

// turtlejvmjvm.cpp 상단에 include 추가
#include <vulkan/vulkan.h>
#include <dlfcn.h>


#include <resolv.h>
#include <arpa/nameser.h>
#include <netinet/in.h>
#include <dlfcn.h>
#include <poll.h>
#include <unistd.h>

#include <map>
#include <string>
#include <mutex>
#include <dlfcn.h>

#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

#define LOG_TAG "TurtleLauncherJVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
// JNI_CreateJavaVM 함수 포인터 타입 정의
typedef jint (*CreateJavaVM_t)(JavaVM**, void**, void*);
// 💡 [핵심 추가] stdout/stderr에 찍히는 자바 로그를 실시간으로 낚아채 로그캣에 뿌려주는 백그라운드 쓰레드
static int pfd[2];
static pthread_t logging_thread;
// android_res_n* 는 API 29+ 라 dlsym 으로 런타임 lookup
typedef int  (*android_res_nquery_fn)(uint64_t network, const char* dname,
                                      int ns_class, int ns_type, uint32_t flags);
typedef int  (*android_res_nresult_fn)(int fd, int* rcode,
                                       uint8_t* answer, size_t anslen);
typedef void (*android_res_cancel_fn)(int fd);

// hostname → SRV port. lookupAllHostAddr 가 SRV 성공 시 채워두고,
// connect0 후킹이 lookup 해서 port 치환.
static std::map<std::string, uint16_t> g_srv_port_cache;
static std::mutex g_srv_cache_mutex;

// 원본 Net.connect0 (시그니처 두 가지 — JDK8 / JDK17+)
static jint (*g_orig_connect0_v1)(JNIEnv*, jclass, jobject, jobject, jint) = nullptr;
static jint (*g_orig_connect0_v2)(JNIEnv*, jclass, jboolean, jobject, jobject, jint) = nullptr;

static android_res_nquery_fn  s_res_nquery  = nullptr;
static android_res_nresult_fn s_res_nresult = nullptr;
static android_res_cancel_fn  s_res_cancel  = nullptr;
static bool s_res_resolved = false;

static struct pojav_environ_s* get_pojav_environ() {
    const char* s = getenv("POJAV_ENVIRON");
    if (!s) return nullptr;
    char* endptr = nullptr;
    uintptr_t p = strtoul(s, &endptr, 16);
    if (endptr && *endptr != 0) return nullptr;
    if (p == 0) return nullptr;
    return (struct pojav_environ_s*)p;
}

static void resolve_android_res_symbols() {
    if (s_res_resolved) return;
    s_res_resolved = true;
    void* h = dlopen("libandroid.so", RTLD_NOW);
    if (!h) {
        LOGW("dlopen libandroid.so failed: %s", dlerror());
        return;
    }
    s_res_nquery  = (android_res_nquery_fn)  dlsym(h, "android_res_nquery");
    s_res_nresult = (android_res_nresult_fn) dlsym(h, "android_res_nresult");
    s_res_cancel  = (android_res_cancel_fn)  dlsym(h, "android_res_cancel");
    LOGI("android_res_n*: query=%p result=%p cancel=%p",
         s_res_nquery, s_res_nresult, s_res_cancel);
}

static bool query_minecraft_srv(const char* host,
                                char* out_target, size_t out_target_size,
                                uint16_t* out_port) {
    if (!host || !out_target) return false;
    if (host[0] == '_') return false;

    resolve_android_res_symbols();
    if (!s_res_nquery || !s_res_nresult) {
        LOGW("android_res_n* unavailable (device API < 29?)");
        return false;
    }

    char qname[300];
    int qn = snprintf(qname, sizeof(qname), "_minecraft._tcp.%s", host);
    if (qn <= 0 || qn >= (int)sizeof(qname)) return false;

    // network=0 → 시스템 기본 네트워크 사용
    int fd = s_res_nquery(0, qname, ns_c_in, ns_t_srv, 0);
    if (fd < 0) {
        LOGI("res_nquery failed for %s: %d", qname, fd);
        return false;
    }

    struct pollfd pfd = { .fd = fd, .events = POLLIN, .revents = 0 };
    int pret = poll(&pfd, 1, 3000);   // 3초 timeout
    if (pret <= 0) {
        LOGI("res_nquery poll timeout for %s", qname);
        if (s_res_cancel) s_res_cancel(fd);
        else close(fd);
        return false;
    }

    int rcode = 0;
    uint8_t answer[NS_PACKETSZ];
    int len = s_res_nresult(fd, &rcode, answer, sizeof(answer));
    // res_nresult 가 fd 자동 close

    if (len <= 0 || rcode != 0) {
        LOGI("res_nresult: len=%d rcode=%d for %s", len, rcode, qname);
        return false;
    }

    ns_msg msg;
    if (ns_initparse(answer, len, &msg) < 0) return false;

    int count = ns_msg_count(msg, ns_s_an);
    uint16_t best_prio = 0xFFFF;
    bool found = false;
    for (int i = 0; i < count; i++) {
        ns_rr rr;
        if (ns_parserr(&msg, ns_s_an, i, &rr) < 0) continue;
        if (ns_rr_type(rr) != ns_t_srv) continue;
        if (ns_rr_rdlen(rr) < 7) continue;

        const unsigned char* rdata = ns_rr_rdata(rr);
        uint16_t priority = ntohs(*(const uint16_t*)(rdata + 0));
        uint16_t port     = ntohs(*(const uint16_t*)(rdata + 4));

        char target[256];
        if (dn_expand(ns_msg_base(msg), ns_msg_end(msg),
                      rdata + 6, target, sizeof(target)) < 0) continue;

        size_t tlen = strlen(target);
        if (tlen > 0 && target[tlen - 1] == '.') target[tlen - 1] = 0;
        if (target[0] == 0) continue;

        if (priority < best_prio) {
            strncpy(out_target, target, out_target_size - 1);
            out_target[out_target_size - 1] = 0;
            *out_port = port;
            best_prio = priority;
            found = true;
        }
    }

    if (found) {
        std::lock_guard<std::mutex> lock(g_srv_cache_mutex);
        g_srv_port_cache[std::string(host)] = *out_port;
        LOGI("   💾 cached SRV port: %s → :%u", host, *out_port);
    }
    return found;
}

static jobjectArray pingLookupCore(JNIEnv* env, jstring hostname) {
    const char* host = env->GetStringUTFChars(hostname, nullptr);
    LOGI("🌐 lookupAllHostAddr('%s')", host);

    // localhost / IP literal / 짧은 이름은 SRV 시도 건너뛰기 (잡음 줄임)
    bool skip_srv = (!strcmp(host, "localhost") || !strchr(host, '.'));

    char srv_target[256];
    uint16_t srv_port = 0;
    const char* effective_host = host;
    if (!skip_srv && query_minecraft_srv(host, srv_target, sizeof(srv_target), &srv_port)) {
        LOGI("   📌 SRV: _minecraft._tcp.%s → %s:%u", host, srv_target, srv_port);
        effective_host = srv_target;
    }

    struct pojav_environ_s* environ_ptr = get_pojav_environ();
    JavaVM* dvm = environ_ptr ? environ_ptr->dalvikJavaVMPtr : nullptr;
    if (!dvm) {
        env->ReleaseStringUTFChars(hostname, host);
        jclass uhe = env->FindClass("java/net/UnknownHostException");
        env->ThrowNew(uhe, "Dalvik VM unavailable (environ not ready)");
        return nullptr;
    }

    JNIEnv* dEnv = nullptr;
    bool detach = false;
    if (dvm->GetEnv((void**)&dEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        dvm->AttachCurrentThread(&dEnv, nullptr);
        detach = true;
    }

    jclass dCls = dEnv->FindClass("java/net/InetAddress");
    jmethodID dGetAllByName = dEnv->GetStaticMethodID(dCls, "getAllByName",
                                                      "(Ljava/lang/String;)[Ljava/net/InetAddress;");
    jmethodID dGetAddress = dEnv->GetMethodID(dCls, "getAddress", "()[B");

    // ★ effective_host 로 resolve (SRV target 또는 원본)
    jstring dHost = dEnv->NewStringUTF(effective_host);
    jobjectArray dResults = (jobjectArray)dEnv->CallStaticObjectMethod(
            dCls, dGetAllByName, dHost);
    bool failed = dEnv->ExceptionCheck();
    if (failed) dEnv->ExceptionClear();
    dEnv->DeleteLocalRef(dHost);

    if (failed || !dResults) {
        dEnv->DeleteLocalRef(dCls);
        if (detach) dvm->DetachCurrentThread();
        LOGI("   ✗ unknown host: '%s'", effective_host);
        jclass uhe = env->FindClass("java/net/UnknownHostException");
        env->ThrowNew(uhe, host);
        env->ReleaseStringUTFChars(hostname, host);
        return nullptr;
    }

    jsize count = dEnv->GetArrayLength(dResults);

    jclass jvmCls = env->FindClass("java/net/InetAddress");
    jmethodID jvmGetByAddr = env->GetStaticMethodID(jvmCls, "getByAddress",
                                                    "(Ljava/lang/String;[B)Ljava/net/InetAddress;");

    // 일단 IPv4 우선 — KR ISP 에서 IPv6 unreachable 흔함
    // 그래서 IPv4 만 골라서 반환 (없으면 IPv6 라도)
    int ipv4_count = 0;
    for (jsize i = 0; i < count; i++) {
        jobject a = dEnv->GetObjectArrayElement(dResults, i);
        jbyteArray b = (jbyteArray)dEnv->CallObjectMethod(a, dGetAddress);
        if (dEnv->GetArrayLength(b) == 4) ipv4_count++;
        dEnv->DeleteLocalRef(b);
        dEnv->DeleteLocalRef(a);
    }
    bool ipv4_only = ipv4_count > 0;
    int out_count = ipv4_only ? ipv4_count : count;

    jobjectArray result = env->NewObjectArray(out_count, jvmCls, nullptr);
    int out_idx = 0;

    for (jsize i = 0; i < count; i++) {
        jobject dAddr = dEnv->GetObjectArrayElement(dResults, i);
        jbyteArray dBytes = (jbyteArray)dEnv->CallObjectMethod(dAddr, dGetAddress);
        jsize blen = dEnv->GetArrayLength(dBytes);

        if (ipv4_only && blen != 4) {
            dEnv->DeleteLocalRef(dBytes);
            dEnv->DeleteLocalRef(dAddr);
            continue;
        }

        jbyte tmp[16];
        dEnv->GetByteArrayRegion(dBytes, 0, blen, tmp);

        jbyteArray jvmBytes = env->NewByteArray(blen);
        env->SetByteArrayRegion(jvmBytes, 0, blen, tmp);

        // ★ 원본 hostname 으로 저장 — 마인크래프트가 나중에 hostname 비교할 때 일치하도록
        jobject jvmAddr = env->CallStaticObjectMethod(
                jvmCls, jvmGetByAddr, hostname, jvmBytes);
        env->SetObjectArrayElement(result, out_idx++, jvmAddr);

        env->DeleteLocalRef(jvmBytes);
        env->DeleteLocalRef(jvmAddr);
        dEnv->DeleteLocalRef(dBytes);
        dEnv->DeleteLocalRef(dAddr);
    }

    dEnv->DeleteLocalRef(dResults);
    dEnv->DeleteLocalRef(dCls);
    env->DeleteLocalRef(jvmCls);
    if (detach) dvm->DetachCurrentThread();

    LOGI("   ✓ resolved %d address(es) for '%s' (effective='%s')",
         out_idx, host, effective_host);
    env->ReleaseStringUTFChars(hostname, host);
    return result;
}



void* stdout_logger_thread_func(void*) {
    ssize_t read_size;
    char buffer[512];
    while ((read_size = read(pfd[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[read_size] = 0;
        // 로그캣에 "MinecraftJVM_IO"라는 태그로 마인크래프트 내부 출력을 중계합니다.
        __android_log_print(ANDROID_LOG_INFO, "MinecraftJVM_IO", "%s", buffer);
    }
    return nullptr;
}

// turtlejvmjvm.cpp에 추가
static bool g_isGrabbing = false;
typedef void (*SetGrabbing_t)(JNIEnv*, jclass, jboolean);
[[maybe_unused]] static SetGrabbing_t g_originalSetGrabbing = nullptr;

static int g_guiScale = 0;


// JDK 8/17 시그니처: (String) -> InetAddress[]
extern "C" jobjectArray pingLookupAllHostAddr_v1(
        JNIEnv* env, jobject self, jstring hostname) {
    return pingLookupCore(env, hostname);
}

// JDK 18+ 시그니처: (String, int) -> InetAddress[]  (characteristics 무시)
extern "C" jobjectArray pingLookupAllHostAddr_v2(
        JNIEnv* env, jobject self, jstring hostname, jint /*chars*/) {
    return pingLookupCore(env, hostname);
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeSetGuiScale(
        JNIEnv* env, jobject thiz, jint scale) {
    g_guiScale = scale;
    LOGI("GUI Scale 설정: %d", scale);
}

extern "C" JNIEXPORT jint JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeGetGuiScale(
        JNIEnv* env, jobject thiz) {
    return g_guiScale;
}


// 우리가 실제로 실행할 함수
//
// 중요: 이 함수는 JVM(런타임/Hotspot) 스레드에서 호출된다
//  (Mouse.create -> glfwSetInputMode -> nativeSetGrabbing).
//  JVM 클래스로더의 org.lwjgl.glfw.CallbackBridge 에는 onGrabStateChanged 가
//  없을 수 있어서, 전달받은 JVM env 로 GetStaticMethodID 하면
//  NoSuchMethodError 가 발생하고 그게 Mouse.create 로 전파되어 게임이 죽는다.
//  따라서 JVM env 는 절대 건드리지 않고, Dalvik(안드로이드) VM 에 붙어서
//  앱 측 CallbackBridge.onGrabStateChanged 를 호출한다.
//  (input_bridge_v3.c::JNI_OnLoad 가 dalvik bridgeClazz/메서드ID 를 이미 캐시함)
void hookedSetGrabbing(JNIEnv* env, jclass clazz, jboolean grabbing) {
    (void)env; (void)clazz;
    g_isGrabbing = grabbing;
    LOGI("🎯 GrabState: %d", grabbing);

    struct pojav_environ_s* ep = get_pojav_environ();
    if (!ep || !ep->dalvikJavaVMPtr) {
        LOGW("hookedSetGrabbing: dalvik VM not ready, state-only (grab=%d)", grabbing);
        return;
    }
    if (!ep->bridgeClazz) {
        LOGW("hookedSetGrabbing: dalvik bridgeClazz null, state-only");
        return;
    }

    JavaVM* dvm = ep->dalvikJavaVMPtr;
    JNIEnv* dEnv = nullptr;
    bool detach = false;
    jint st = dvm->GetEnv((void**)&dEnv, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        if (dvm->AttachCurrentThread(&dEnv, nullptr) != JNI_OK || !dEnv) {
            LOGW("hookedSetGrabbing: AttachCurrentThread failed, state-only");
            return;
        }
        detach = true;
    } else if (st != JNI_OK || !dEnv) {
        LOGW("hookedSetGrabbing: GetEnv(dalvik) failed (%d), state-only", st);
        return;
    }

    // input_bridge_v3.c 가 캐시한 dalvik 메서드ID 재사용. 없으면 여기서 재확보.
    jmethodID onGrab = ep->method_onGrabStateChanged;
    if (!onGrab) {
        onGrab = dEnv->GetStaticMethodID(ep->bridgeClazz, "onGrabStateChanged", "(Z)V");
        if (dEnv->ExceptionCheck()) dEnv->ExceptionClear();
    }
    if (!onGrab) {
        LOGW("hookedSetGrabbing: onGrabStateChanged not found on dalvik bridge, state-only");
        if (detach) dvm->DetachCurrentThread();
        return;
    }

    dEnv->CallStaticVoidMethod(ep->bridgeClazz, onGrab, grabbing);
    if (dEnv->ExceptionCheck()) {
        // dalvik 콜백에서 예외가 나도 게임 부팅을 막지 않는다.
        dEnv->ExceptionClear();
        LOGW("hookedSetGrabbing: dalvik onGrabStateChanged threw, cleared (grab=%d)", grabbing);
    }

    if (detach) dvm->DetachCurrentThread();
}


// ──────────────────────────────────────────────────────────────────
// java.net.Inet{4,6}AddressImpl.lookupAllHostAddr 를 우리 구현으로 교체.
// JVM 의 libnet.so getaddrinfo 가 PLT 우회 경로를 타도, Java 측 시작점에서
// 갈아끼우니까 모든 호출이 우리에게 들어온다. 안에서는 Dalvik(Android) JVM 의
// InetAddress.getAllByName 으로 위임 → Android 의 정상 DNS resolver 사용.
// ──────────────────────────────────────────────────────────────────

static jint JNICALL pingConnect0_common(JNIEnv* env, jobject remote, jint port,
                                        int* out_effective_port) {
    *out_effective_port = port;

    jclass iaCls = env->GetObjectClass(remote);
    jmethodID mGetHostName = env->GetMethodID(iaCls, "getHostName", "()Ljava/lang/String;");
    jstring jhost = (jstring) env->CallObjectMethod(remote, mGetHostName);
    env->DeleteLocalRef(iaCls);

    if (!jhost) return 0;
    const char* host = env->GetStringUTFChars(jhost, nullptr);

    {
        std::lock_guard<std::mutex> lock(g_srv_cache_mutex);
        auto it = g_srv_port_cache.find(std::string(host));
        if (it != g_srv_port_cache.end() && it->second != 0) {
            *out_effective_port = it->second;
            LOGI("📌 connect0 port override: %s :%d → :%d", host, port, *out_effective_port);
        }
    }

    env->ReleaseStringUTFChars(jhost, host);
    env->DeleteLocalRef(jhost);
    return 0;
}

// JDK 17+ 시그니처
static jint JNICALL pingConnect0_v2(JNIEnv* env, jclass cls,
                                    jboolean preferIPv6,
                                    jobject fd, jobject remote, jint port) {
    int eff = port;
    pingConnect0_common(env, remote, port, &eff);
    return g_orig_connect0_v2
           ? g_orig_connect0_v2(env, cls, preferIPv6, fd, remote, eff)
           : -1;
}

// JDK 8 시그니처
static jint JNICALL pingConnect0_v1(JNIEnv* env, jclass cls,
                                    jobject fd, jobject remote, jint port) {
    int eff = port;
    pingConnect0_common(env, remote, port, &eff);
    return g_orig_connect0_v1
           ? g_orig_connect0_v1(env, cls, fd, remote, eff)
           : -1;
}

static void installNetConnectHook(JNIEnv* env) {
    // 원본 함수 dlsym (RegisterNatives 이전에)
    void* sym = nullptr;
    void* hNio = dlopen("libnio.so", RTLD_NOW);
    if (hNio) sym = dlsym(hNio, "Java_sun_nio_ch_Net_connect0");
    if (!sym)  sym = dlsym(RTLD_DEFAULT, "Java_sun_nio_ch_Net_connect0");
    if (!sym) {
        LOGW("⚠️  Net.connect0 원본 심볼 dlsym 실패: %s", dlerror());
        return;
    }

    jclass netCls = env->FindClass("sun/nio/ch/Net");
    if (!netCls) {
        env->ExceptionClear();
        LOGW("⚠️  sun/nio/ch/Net 클래스 못 찾음");
        return;
    }

    // JDK 17+ 시그니처 먼저 시도
    JNINativeMethod m_v2[] = {
            { (char*)"connect0",
              (char*)"(ZLjava/io/FileDescriptor;Ljava/net/InetAddress;I)I",
              (void*)pingConnect0_v2 }
    };
    if (env->RegisterNatives(netCls, m_v2, 1) == JNI_OK) {
        g_orig_connect0_v2 =
                (decltype(g_orig_connect0_v2)) sym;
        LOGI("✅ Hooked sun/nio/ch/Net.connect0(Z,FD,IA,I) [JDK17+]");
        env->DeleteLocalRef(netCls);
        return;
    }
    env->ExceptionClear();

    // JDK 8 시그니처 fallback
    JNINativeMethod m_v1[] = {
            { (char*)"connect0",
              (char*)"(Ljava/io/FileDescriptor;Ljava/net/InetAddress;I)I",
              (void*)pingConnect0_v1 }
    };
    if (env->RegisterNatives(netCls, m_v1, 1) == JNI_OK) {
        g_orig_connect0_v1 =
                (decltype(g_orig_connect0_v1)) sym;
        LOGI("✅ Hooked sun/nio/ch/Net.connect0(FD,IA,I) [JDK8]");
    } else {
        env->ExceptionClear();
        LOGW("⚠️  Net.connect0 후킹 실패 (양쪽 시그니처 모두)");
    }
    env->DeleteLocalRef(netCls);
}

static void installInetAddressHook(JNIEnv* env) {
    const char* classes[] = {
            "java/net/Inet6AddressImpl",
            "java/net/Inet4AddressImpl",
    };

    JNINativeMethod m_v1[] = {
            { (char*)"lookupAllHostAddr",
              (char*)"(Ljava/lang/String;)[Ljava/net/InetAddress;",
              (void*)&pingLookupAllHostAddr_v1 }
    };
    JNINativeMethod m_v2[] = {
            { (char*)"lookupAllHostAddr",
              (char*)"(Ljava/lang/String;I)[Ljava/net/InetAddress;",
              (void*)&pingLookupAllHostAddr_v2 }
    };

    for (const char* cn : classes) {
        jclass cls = env->FindClass(cn);
        if (env->ExceptionCheck()) { env->ExceptionClear(); cls = nullptr; }
        if (!cls) {
            LOGI("InetAddressHook: %s not found (skip)", cn);
            continue;
        }

        // JDK 별로 둘 중 하나만 존재. 둘 다 시도하고 ExceptionClear.
        if (env->RegisterNatives(cls, m_v1, 1) == 0) {
            LOGI("✅ Hooked %s.lookupAllHostAddr(String)", cn);
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (env->RegisterNatives(cls, m_v2, 1) == 0) {
            LOGI("✅ Hooked %s.lookupAllHostAddr(String,int)", cn);
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);
    }
}

// JVM 생성 후 후킹 설치
static void installGrabbingHook() {
    void* handle = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (!handle) { LOGE("libpojavexec.so not found"); return; }

    void* sym = dlsym(handle, "Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing");
    if (!sym) { LOGE("nativeSetGrabbing symbol not found"); return; }

    g_originalSetGrabbing = (SetGrabbing_t)sym;

    // 함수 포인터 주소를 hookedSetGrabbing으로 교체
    uintptr_t addr = (uintptr_t)sym;
    uintptr_t pageStart = addr & ~(getpagesize() - 1);
    mprotect((void*)pageStart, getpagesize() * 2, PROT_READ | PROT_WRITE | PROT_EXEC);

    // arm64 절대 점프 패치
    uint32_t patch[4];
    patch[0] = 0x58000051; // ldr x17, #8
    patch[1] = 0xd61f0220; // br x17
    uint64_t target = (uint64_t)hookedSetGrabbing;
    memcpy(&patch[2], &target, 8);
    memcpy((void*)addr, patch, sizeof(patch));

    __builtin___clear_cache((char*)addr, (char*)addr + sizeof(patch));
    LOGI("✅ nativeSetGrabbing 후킹 완료");
}

static void registerGLFWGamepadNative(JNIEnv* customEnv) {
    LOGI("🎯 GLFW.internalGetGamepadDataPointer RegisterNatives 시도");

    jclass glfwClass = customEnv->FindClass("org/lwjgl/glfw/GLFW");
    if (customEnv->ExceptionCheck()) {
        LOGE("  FindClass 중 예외 발생");
        customEnv->ExceptionClear();
    }
    if (glfwClass == nullptr) {
        LOGE("  ❌ org/lwjgl/glfw/GLFW 클래스 못 찾음 (아직 classpath에 미로드)");
        return;
    }


    LOGI("  ✅ GLFW 클래스 찾음");

    // 우리가 빌드한 libglfw.so 우선, 그 다음 libpojavexec.so 폴백
    const char* libCandidates[] = { "libglfw.so", "libpojavexec.so", nullptr };
    void* fn = nullptr;
    for (int i = 0; libCandidates[i]; i++) {
        void* h = dlopen(libCandidates[i], RTLD_NOLOAD | RTLD_NOW);
        if (!h) {
            LOGI("  %s 로드 안 되어 있음 — 다음 후보 시도", libCandidates[i]);
            continue;
        }
        fn = dlsym(h, "Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer");
        if (fn) {
            LOGI("  ✅ 심볼 발견 in %s: %p", libCandidates[i], fn);
            break;
        }
        LOGI("  %s 안에 심볼 없음", libCandidates[i]);
    }

    if (!fn) {
        // 마지막 시도: 현재 프로세스 전체 심볼 테이블에서 검색
        fn = dlsym(RTLD_DEFAULT, "Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer");
        if (fn) {
            LOGI("  ✅ RTLD_DEFAULT에서 심볼 발견: %p", fn);
        } else {
            LOGE("  ❌ 어디에서도 심볼 못 찾음");
            customEnv->DeleteLocalRef(glfwClass);
            return;
        }
    }

    JNINativeMethod m[] = {
            { (char*)"internalGetGamepadDataPointer", (char*)"()J", fn }
    };
    jint r = customEnv->RegisterNatives(glfwClass, m, 1);
    if (r != 0 || customEnv->ExceptionCheck()) {
        LOGE("  ❌ RegisterNatives 실패: %d", r);
        if (customEnv->ExceptionCheck()) {
            customEnv->ExceptionDescribe();
            customEnv->ExceptionClear();
        }
    } else {
        LOGI("  ✅ RegisterNatives 성공");
    }

    customEnv->DeleteLocalRef(glfwClass);
}

// libpojavexec.so 의 nativeSetGrabbing 은 installGrabbingHook() 에서 점프 패치로
// hookedSetGrabbing 으로 리다이렉트된다. JNI 이름 바인딩이 (libpojavexec.so 대신)
// 이 libturtlejvmjvm.so 심볼을 고를 수도 있으므로, 여기서도 동일한 안전 경로
// (JVM env 미사용, dalvik 측 콜백 호출)로 위임한다.
extern "C" JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(
        JNIEnv* env, jclass clazz, jboolean grabbing) {
    hookedSetGrabbing(env, clazz, grabbing);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeIsGrabbing(
        JNIEnv* env, jobject thiz) {
    return g_isGrabbing ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeSetupBridgeWindow(
        JNIEnv* env, jobject thiz, jobject surface) {
    LOGI("nativeSetupBridgeWindow 호출됨");
    typedef void (*SetupBridgeWindow_t)(JNIEnv*, jclass, jobject);

    const char* libs[] = { "libglfw.so", "libpojavexec.so", nullptr };
    for (int i = 0; libs[i]; i++) {
        void* h = dlopen(libs[i], RTLD_NOLOAD | RTLD_NOW);
        if (!h) continue;
        SetupBridgeWindow_t fn = (SetupBridgeWindow_t)dlsym(h,
                                                            "Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow");
        if (fn) {
            LOGI("setupBridgeWindow 호출 → %s (fn=%p)", libs[i], fn);
            fn(env, nullptr, surface);
        }
    }
}

// Caused by 체인까지 재귀 출력
static void printJavaException(JNIEnv* env, jthrowable ex, int depth) {
    if (!ex || depth > 8) return;

    jclass exClass = env->GetObjectClass(ex);
    if (!exClass) return;

    jmethodID toStringMethod = env->GetMethodID(exClass, "toString", "()Ljava/lang/String;");
    if (toStringMethod) {
        jstring msgString = (jstring)env->CallObjectMethod(ex, toStringMethod);
        if (msgString) {
            const char* msgC = env->GetStringUTFChars(msgString, nullptr);
            if (depth == 0) LOGE("❌ 예외: %s", msgC);
            else            LOGE("⤷ Caused by: %s", msgC);
            env->ReleaseStringUTFChars(msgString, msgC);
            env->DeleteLocalRef(msgString);
        }
    }

    jmethodID getStackTraceMethod = env->GetMethodID(exClass, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    if (getStackTraceMethod) {
        jobjectArray stackTrace = (jobjectArray)env->CallObjectMethod(ex, getStackTraceMethod);
        if (stackTrace) {
            jsize length = env->GetArrayLength(stackTrace);
            int maxFrames = (depth == 0) ? 30 : 20;
            for (int i = 0; i < length && i < maxFrames; i++) {
                jobject element = env->GetObjectArrayElement(stackTrace, i);
                if (element) {
                    jclass elementClass = env->GetObjectClass(element);
                    jmethodID elementToString = env->GetMethodID(elementClass, "toString", "()Ljava/lang/String;");
                    if (elementToString) {
                        jstring elementString = (jstring)env->CallObjectMethod(element, elementToString);
                        if (elementString) {
                            const char* elementC = env->GetStringUTFChars(elementString, nullptr);
                            LOGE("    at %s", elementC);
                            env->ReleaseStringUTFChars(elementString, elementC);
                            env->DeleteLocalRef(elementString);
                        }
                    }
                    env->DeleteLocalRef(elementClass);
                    env->DeleteLocalRef(element);
                }
            }
            env->DeleteLocalRef(stackTrace);
        }
    }

    jmethodID getCauseMethod = env->GetMethodID(exClass, "getCause", "()Ljava/lang/Throwable;");
    if (getCauseMethod) {
        jthrowable cause = (jthrowable)env->CallObjectMethod(ex, getCauseMethod);
        if (cause && !env->IsSameObject(cause, ex)) {
            printJavaException(env, cause, depth + 1);
        }
        if (cause) env->DeleteLocalRef(cause);
    }

    env->DeleteLocalRef(exClass);
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_util_jni_JavaNativeLauncher_preloadAwtStubs(
        JNIEnv* env, jclass clazz, jstring nativeLibDir) {
    const char* dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    std::string path = std::string(dir) + "/libpojavexec.so";
    env->ReleaseStringUTFChars(nativeLibDir, dir);

    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (h) {
        LOGI("✅ libpojavexec.so EARLY RTLD_GLOBAL: %s", path.c_str());
    } else {
        LOGE("❌ EARLY-LOAD 실패: %s", dlerror());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_util_jni_JavaNativeLauncher_nativeSetEnv(
        JNIEnv* env, jobject thiz, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    setenv(k, v, 1);
    LOGI("setenv: %s=%s", k, v);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}


// JNI 함수 위에 probe 본체 복제 (egl_bridge.c 와 동일)
static bool zink_probe_vulkan_works() {
    void* h = dlopen("libvulkan.so", RTLD_NOW);
    if (!h) { printf("Zink probe: libvulkan.so 못 찾음\n"); return false; }

    typedef PFN_vkVoidFunction (*PFN_vkGetInstanceProcAddr_t)(VkInstance, const char*);
    typedef VkResult (*PFN_vkCreateInstance_t)(const VkInstanceCreateInfo*,
                                               const VkAllocationCallbacks*, VkInstance*);

    auto gipa = (PFN_vkGetInstanceProcAddr_t)dlsym(h, "vkGetInstanceProcAddr");
    auto createInst = (PFN_vkCreateInstance_t)dlsym(h, "vkCreateInstance");
    if (!gipa || !createInst) { dlclose(h); return false; }

    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkInstance inst = VK_NULL_HANDLE;
    if (createInst(&ci, nullptr, &inst) != VK_SUCCESS) {
        printf("Zink probe: vkCreateInstance 실패\n");
        dlclose(h); return false;
    }

    auto enumPD   = (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
    auto getFeats = (PFN_vkGetPhysicalDeviceFeatures)gipa(inst, "vkGetPhysicalDeviceFeatures");
    auto getProps = (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
    auto destInst = (PFN_vkDestroyInstance)gipa(inst, "vkDestroyInstance");

    uint32_t deviceCount = 0;
    enumPD(inst, &deviceCount, nullptr);
    if (deviceCount == 0) {
        printf("Zink probe: no Vulkan physical devices\n");
        if (destInst) destInst(inst, nullptr);
        dlclose(h); return false;
    }

    if (deviceCount > 8) deviceCount = 8;
    VkPhysicalDevice phys[8];
    enumPD(inst, &deviceCount, phys);

    bool any_compatible = false;
    for (uint32_t i = 0; i < deviceCount; i++) {
        VkPhysicalDeviceProperties props = {};
        VkPhysicalDeviceFeatures   feats = {};
        getProps(phys[i], &props);
        getFeats(phys[i], &feats);
        bool ok = true;
        printf("Zink probe: device #%u (%s) logicOp=%d fillModeNonSolid=%d shaderClipDistance=%d -> %s\n",
               i, props.deviceName,
               feats.logicOp, feats.fillModeNonSolid, feats.shaderClipDistance,
               ok ? "COMPATIBLE" : "INCOMPATIBLE");
        if (ok) any_compatible = true;
    }

    if (destInst) destInst(inst, nullptr);
    dlclose(h);
    return any_compatible;
}

// JNI 진입점
extern "C" JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_util_renderer_RendererProbe_nativeZinkCompatible(
        JNIEnv* /*env*/, jclass /*clazz*/)
{
    return zink_probe_vulkan_works() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_util_jni_JavaNativeLauncher_bootMinecraftJVM(
        JNIEnv* env, jobject thiz, jstring lib_jvm_path, jobjectArray jvm_args, jobjectArray mc_args) {

    // ── Renderer 환경변수 기본값 ─────────────────────────────────
    if (!getenv("POJAV_RENDERER"))  setenv("POJAV_RENDERER",  "vulkan_zink", 0);
    if (!getenv("LIBGL_STRING"))    setenv("LIBGL_STRING",    "VulkanGL",    0);
    if (!getenv("LIBGL_NAME"))      setenv("LIBGL_NAME",      "libgl4es_114.so",   0);
    if (!getenv("DLOPEN"))          setenv("DLOPEN",          "libgl4es_114.so",   0);
    if (!getenv("LIBGL_ES"))        setenv("LIBGL_ES",        "3",           0);
    if (!getenv("FORCE_VSYNC"))     setenv("FORCE_VSYNC",     "false",       0);
    if (!getenv("POJAV_VSYNC"))     setenv("POJAV_VSYNC",     "1",           0);

    LOGI("🎨 Renderer env at boot: POJAV_RENDERER=%s, LIBGL_NAME=%s, LIBGL_ES=%s",
         getenv("POJAV_RENDERER"), getenv("LIBGL_NAME"), getenv("LIBGL_ES"));

    // ── stdout/stderr → logcat 파이프 ────────────────────────────
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);
    pipe(pfd);
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);
    pthread_create(&logging_thread, nullptr, stdout_logger_thread_func, nullptr);
    pthread_detach(logging_thread);

    // ── libjvm.so 로드 ──────────────────────────────────────────
    const char* path = env->GetStringUTFChars(lib_jvm_path, nullptr);
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);

    if (!handle) {
        LOGE("libjvm.so 최종 로드 실패: %s", dlerror());
        env->ReleaseStringUTFChars(lib_jvm_path, path);
        return -1;
    }

    // 경로 파싱: .../jre8_runtime/lib/aarch64/server/libjvm.so
    //  → server_lib_dir = .../jre8_runtime/lib/aarch64/server
    //  → java_lib_dir   = .../jre8_runtime/lib/aarch64
    std::string jvm_path_str(path);
    size_t last_slash = jvm_path_str.find_last_of("/");
    std::string server_lib_dir = jvm_path_str.substr(0, last_slash);
    size_t parent_slash = server_lib_dir.find_last_of("/");
    std::string java_lib_dir = server_lib_dir.substr(0, parent_slash);

    env->ReleaseStringUTFChars(lib_jvm_path, path);

    // ── 공통 헬퍼 ────────────────────────────────────────────────
    auto preload = [](const std::string& p, const char* label) -> bool {
        void* h = dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (h) {
            LOGI("  ✅ preload: %s (%s)", label, p.c_str());
            return true;
        } else {
            LOGE("  ❌ preload FAILED (%s): %s", label, dlerror());
            return false;
        }
    };

    auto is_already_loaded = [](const std::string& p) -> bool {
        void* h = dlopen(p.c_str(), RTLD_NOLOAD | RTLD_NOW);
        if (h) {
            dlclose(h); // refcount 원복
            return true;
        }
        return false;
    };

    // ── ★★★ 가장 먼저: APK 의 패치된 libawt_xawt.so ★★★ ──────────
    // 이유: JRE 의 libfontmanager.so 가 SONAME 으로 libawt_xawt.so 를 찾고
    // 그 안에서 AWTCountFonts/AWTFreeChar 등 17 개 X11FontScaler 심볼을 resolve 한다.
    // JRE 본가의 libawt_xawt.so 에는 이 심볼이 없으므로 (X11 없는 Android 라서),
    // 우리가 xawt_fake.c 의 노옵 스텁으로 만든 APK 버전을 SONAME 으로 먼저 점유시켜야
    // 이후 libfontmanager.so dlopen 이 성공한다.
    //
    // 순서가 뒤집히면 (JRE 가 먼저) → SONAME 이 JRE 버전으로 고정되어
    // 이후 APK 버전 dlopen 은 no-op 가 되고, libfontmanager.so 는 미해결 심볼로 실패.
    const char* apk_native_dir = getenv("POJAV_NATIVEDIR");

    // ── LD_LIBRARY_PATH 보강 (cacio CTCScreen 필수) ─────────────────
    // cacio-tta 의 CTCScreen.<clinit> 은 System.getenv("LD_LIBRARY_PATH").split(":") 로
    // 각 경로를 돌며 libpojavexec_awt.so 를 직접 찾아 System.load 한다.
    // 이 환경변수가 null 이면 .split() 에서 NullPointerException 으로 cacio 초기화가
    // 통째로 죽는다(시스템 클래스로더 CTCPreloadClassLoader.<clinit> 단계).
    // 우리가 libpojavexec_awt.so 를 이미 preload 했더라도 cacio 는 별도로 재탐색하므로,
    // libpojavexec_awt.so 가 있는 APK 네이티브 디렉토리를 반드시 포함시켜야 한다.
    if (apk_native_dir) {
        const char* old_ld = getenv("LD_LIBRARY_PATH");
        std::string new_ld = apk_native_dir;
        if (old_ld && *old_ld) {
            // 이미 값이 있으면 중복 없이 앞에 붙인다.
            std::string old_str_ld(old_ld);
            if (old_str_ld.find(apk_native_dir) == std::string::npos) {
                new_ld = std::string(apk_native_dir) + ":" + old_str_ld;
            } else {
                new_ld = old_str_ld;
            }
        }
        // ★ JRE 의 lib 와 lib/jli 도 LD_LIBRARY_PATH 에 포함한다 (ZL2 getRuntimeLibraryPath 와 동일).
        //   libinstrument.so / libjli.so 및 그 의존성이 dlopen 검색 경로에서 해결되도록 보장.
        std::string jli_dir = java_lib_dir + "/jli";
        for (const std::string& extra : { java_lib_dir, jli_dir }) {
            if (new_ld.find(extra) == std::string::npos) {
                new_ld += ":" + extra;
            }
        }
        setenv("LD_LIBRARY_PATH", new_ld.c_str(), 1);
        LOGI("  🔗 LD_LIBRARY_PATH=%s (cacio CTCScreen + JRE jli/instrument 용)", getenv("LD_LIBRARY_PATH"));
    } else {
        LOGE("  ⚠️ POJAV_NATIVEDIR 미설정 — LD_LIBRARY_PATH 보강 불가, cacio 초기화가 NPE 로 죽을 수 있음");
    }

    if (apk_native_dir) {
        std::string pojavexec_awt_apk = std::string(apk_native_dir) + "/libpojavexec_awt.so";
        preload(pojavexec_awt_apk, "libpojavexec_awt.so (Cacio AWT bridge)");
    }

    if (apk_native_dir) {
        std::string apk_xawt = std::string(apk_native_dir) + "/libawt_xawt.so";
        if (!preload(apk_xawt, "libawt_xawt.so (APK patched, MUST be first)")) {
            LOGE("⚠️ APK libawt_xawt.so 로드 실패 — libfontmanager.so 도 곧 실패할 것");
        }
    } else {
        LOGE("⚠️ POJAV_NATIVEDIR 미설정 — APK libawt_xawt.so 로드 불가");
    }

    // ── pojavexec.so (Pojav 내부 JNI 브릿지) ────────────────────
    if (apk_native_dir) {
        std::string pojavexec_apk = std::string(apk_native_dir) + "/libpojavexec.so";
        preload(pojavexec_apk, "libpojavexec.so (APK)");
    }

    // ── OpenJDK 의존성 체인 (jli → verify → java → zip → net → nio) ──
    //
    // ★ libjli.so 를 가장 먼저 dlopen 한다 (ZL2 dlopenJavaRuntime 와 동일 순서).
    //   -javaagent 로 instrument agent 를 띄우면 JVM 이 libinstrument.so 를 로드하는데,
    //   libinstrument.so 는 libjli.so 의 심볼에 의존한다. jli 가 먼저 RTLD_GLOBAL 로
    //   올라와 있지 않으면 instrument 의 의존성이 안 풀려 dlopen 이 실패하고,
    //   Android linker 는 이를 "library libinstrument.so not found" 로 뭉뚱그려 보고한다.
    //   (NeoForge 처럼 --module-path/--add-modules 를 쓰는 경우 특히 두드러진다.)
    //   jli 는 보통 lib/jli/libjli.so 에 있고, 없으면 lib/libjli.so 에 있다.
    {
        std::string jli_sub = java_lib_dir + "/jli/libjli.so";
        std::string jli_flat = java_lib_dir + "/libjli.so";
        if (!is_already_loaded("libjli.so")) {
            if      (preload(jli_sub,  "libjli.so (jli/)")) {}
            else if (preload(jli_flat, "libjli.so")) {}
            else LOGE("  ⚠️ libjli.so 로드 실패 — -javaagent(cacio) 가 libinstrument.so 를 못 찾을 수 있음");
        } else {
            LOGI("  ℹ️ libjli.so 이미 로드됨");
        }
    }

    std::string verify_path = java_lib_dir + "/libverify.so";
    std::string java_path   = java_lib_dir + "/libjava.so";
    std::string zip_path    = java_lib_dir + "/libzip.so";
    std::string net_path    = java_lib_dir + "/libnet.so";
    std::string nio_path    = java_lib_dir + "/libnio.so";

    preload(verify_path, "libverify.so");
    preload(java_path,   "libjava.so");
    preload(zip_path,    "libzip.so");
    preload(net_path,    "libnet.so");
    preload(nio_path,    "libnio.so");

    // ★ libinstrument.so 를 명시적으로 preload 한다.
    //   파일은 lib/libinstrument.so 에 존재하지만, JVM 의 agent 로더가 찾는 경로
    //   (sun.boot.library.path)와 dlopen 검색 경로가 module-path 처리 중 어긋날 수 있어
    //   미리 RTLD_GLOBAL 로 올려두면 agent 단계에서 확실히 해결된다. jli 다음에 둔다.
    {
        std::string instrument_path = java_lib_dir + "/libinstrument.so";
        if (!is_already_loaded("libinstrument.so")) {
            preload(instrument_path, "libinstrument.so (cacio agent 의존)");
        } else {
            LOGI("  ℹ️ libinstrument.so 이미 로드됨");
        }
    }

    // ── Legacy AWT 체인 (1.5.2 ~ 1.12.2 호환) ───────────────────
    // libawt → libawt_xawt(JRE, 이미 SONAME 점유됨) → libawt_headless → libfontmanager
    std::string jre_lib_dir = java_path.substr(0, java_path.find_last_of('/'));

    preload(jre_lib_dir + "/libawt.so", "libawt.so");

    // JRE 의 libawt_xawt.so 는 명시적으로 호출만 — 이미 APK 버전이 SONAME 을 점유했기에
    // 이건 사실상 no-op 가 되어야 정상. 만약 여기서 "성공" 메시지가 뜨고 dlsym 으로
    // X11FontScaler 심볼을 못 찾는다면 SONAME 점유가 실패했다는 신호.
    if (is_already_loaded("libawt_xawt.so")) {
        LOGI("  ℹ️ libawt_xawt.so SONAME 이미 점유됨 (예상대로 APK 버전이 win)");
    } else {
        LOGE("  ⚠️ libawt_xawt.so SONAME 이 비어있음 — APK preload 실패 가능성");
        preload(jre_lib_dir + "/libawt_xawt.so", "libawt_xawt.so (JRE fallback)");
    }

    preload(jre_lib_dir + "/libawt_headless.so", "libawt_headless.so");

    // ── libfontmanager.so — 진실의 순간 ─────────────────────────
    // 여기서 실패하면 dlerror 에 어떤 심볼이 빠졌는지 정확히 찍힌다.
    // "cannot locate symbol 'AWTXxx'" 식으로 나오면 xawt_fake.c 에 해당 스텁 추가.
    if (!preload(jre_lib_dir + "/libfontmanager.so", "libfontmanager.so")) {
        LOGE("⚠️ libfontmanager.so 로드 실패 — 폰트 렌더링은 동작 안 할 수 있음");
        LOGE("    → xawt_fake.c 에 누락된 X11FontScaler 스텁 추가 필요");
    }

    LOGI("✅ 자바 핵심 의존성 라이브러리 선행 로드 완료");

    // ── JNI_CreateJavaVM 심볼 획득 ──────────────────────────────
    CreateJavaVM_t createJavaVM = (CreateJavaVM_t)dlsym(handle, "JNI_CreateJavaVM");
    if (!createJavaVM) {
        LOGE("JNI_CreateJavaVM 심볼을 찾을 수 없습니다: %s", dlerror());
        dlclose(handle);
        return -2;
    }

    // ── JVM Arguments 조립 ─────────────────────────────────────
    jsize jvm_arg_count = env->GetArrayLength(jvm_args);
    std::vector<JavaVMOption> options(jvm_arg_count);

    for (int i = 0; i < jvm_arg_count; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(jvm_args, i);
        const char* raw_arg = env->GetStringUTFChars(arg, nullptr);
        options[i].optionString = strdup(raw_arg);
        options[i].extraInfo = nullptr;
        LOGI("전달된 JVM 인자 [%d]: %s", i, options[i].optionString);
        env->ReleaseStringUTFChars(arg, raw_arg);
    }

    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = jvm_arg_count;
    vm_args.options = options.data();
    vm_args.ignoreUnrecognized = JNI_FALSE;

    // user.dir 처리
    for (int i = 0; i < jvm_arg_count; i++) {
        const char* opt = options[i].optionString;
        if (strncmp(opt, "-Duser.dir=", 11) == 0) {
            const char* dir = opt + 11;
            LOGI("chdir to: %s", dir);
            chdir(dir);
            break;
        }
    }

    // ── JVM 부팅 ────────────────────────────────────────────────
    JavaVM* customVM = nullptr;
    JNIEnv* customEnv = nullptr;

    LOGI("JNI_CreateJavaVM 호출 중...");
    jint res = createJavaVM(&customVM, (void**)&customEnv, &vm_args);

    if (res != JNI_OK) {
        LOGE("JVM 생성 실패: 코드 %d", res);
        return -3;
    }
    LOGI("✅ 내장 JVM 부팅 성공!");
    installGrabbingHook();
    installInetAddressHook(customEnv);   // ★ 추가
    installNetConnectHook(customEnv);

    // ── 마인크래프트 메인 클래스 로드 ───────────────────────────
    // 시스템 프로퍼티에서 mainClass 읽기
    jclass systemClass = customEnv->FindClass("java/lang/System");
    jmethodID getPropMethod = customEnv->GetStaticMethodID(
            systemClass, "getProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring propKey = customEnv->NewStringUTF("ping.main.class");
    jstring propDefault = customEnv->NewStringUTF("net.minecraft.client.main.Main");
    jstring propValue = (jstring)customEnv->CallStaticObjectMethod(
            systemClass, getPropMethod, propKey, propDefault);
    const char* mainClassNameRaw = customEnv->GetStringUTFChars(propValue, nullptr);

    // dot → slash 변환
    std::string mainClassNameStr(mainClassNameRaw);
    std::replace(mainClassNameStr.begin(), mainClassNameStr.end(), '.', '/');
    customEnv->ReleaseStringUTFChars(propValue, mainClassNameRaw);

    LOGI("mainClass: %s", mainClassNameStr.c_str());

    jclass mainClass = customEnv->FindClass(mainClassNameStr.c_str());
    if (mainClass == nullptr) {
        if (customEnv->ExceptionCheck()) {
            LOGE("🚨 OpenJDK 내부 예외 발생!");
            jthrowable ex = customEnv->ExceptionOccurred();
            customEnv->ExceptionClear();
            printJavaException(customEnv, ex, 0);
            customEnv->DeleteLocalRef(ex);
        }
        LOGE("마인크래프트 메인 클래스를 찾을 수 없습니다.");
        return -4;
    }

    jmethodID mainMethod = customEnv->GetStaticMethodID(
            mainClass, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        LOGE("main 메서드를 찾을 수 없습니다.");
        return -5;
    }

    // ── 마인크래프트 실행 인자 배열 생성 ────────────────────────
    jsize mc_arg_count = env->GetArrayLength(mc_args);
    jclass stringClass = customEnv->FindClass("java/lang/String");
    jobjectArray mc_args_for_jvm = customEnv->NewObjectArray(mc_arg_count, stringClass, nullptr);

    for (int i = 0; i < mc_arg_count; ++i) {
        jstring arg = (jstring)env->GetObjectArrayElement(mc_args, i);
        const char* raw_arg = env->GetStringUTFChars(arg, nullptr);
        jstring jvm_str = customEnv->NewStringUTF(raw_arg);
        customEnv->SetObjectArrayElement(mc_args_for_jvm, i, jvm_str);
        env->ReleaseStringUTFChars(arg, raw_arg);
    }

    // ── 🚀 Minecraft 실행 ──────────────────────────────────────
    LOGI("%s.main() 실행!", mainClassNameStr.c_str());
    customEnv->CallStaticVoidMethod(mainClass, mainMethod, mc_args_for_jvm);

    if (customEnv->ExceptionCheck()) {
        LOGE("🚨 마인크래프트 실행 중 치명적인 자바 예외가 발생했습니다!");
        jthrowable ex = customEnv->ExceptionOccurred();
        customEnv->ExceptionClear();
        printJavaException(customEnv, ex, 0);
        customEnv->DeleteLocalRef(ex);
        return -6;
    }

    // ⚠️ 여기서 _exit() 을 부르는 시도(즉시든, 타임아웃 뒤든)를 두 번 다 되돌렸다.
    //   원인: _exit() 은 ART(안드로이드 런타임)의 정상적인 프로세스 정리 경로를
    //   거치지 않는 "생짜" 시스템콜이라, 안드로이드는 호출 "시점"과 무관하게
    //   이걸 예외 없이 "비정상 종료"로 취급해서 "앱이 강제 종료됨" 팝업을
    //   띄운다 — 8초 지연을 둬도 실제로 그 시점에 _exit() 이 불리는 순간
    //   똑같이 강제종료로 보였다(오래 플레이하다 렌더 스레드가 실제로 멈추는
    //   경우 재현됨).
    //
    //   즉 "크래시 리포트가 로그에 남는 것"(Mojang 의 ClientShutdownWatchdog
    //   자체 처리)보다 "사용자가 실제로 보는 강제종료 팝업"이 훨씬 나쁜
    //   경험이라, 여기서 개입하지 않고 그냥 정상 리턴한다. 렌더 스레드가
    //   종료 시 멈추는 근본 원인(Zink 종료 시 EGL 호출이 파괴된 Surface 를
    //   상대로 블로킹되는 것으로 추정)을 egl_bridge.c 쪽에서 고치는 게
    //   맞는 다음 단계 — 종료 신호가 오면 렌더 스레드가 추가 EGL 호출을
    //   하기 전에 먼저 멈추도록 만드는 방향.
    return 0;
}


// 한 방에 처리: environ 에서 mainWindowBundle 꺼내서 nglfwSetShowingWindow 호출
extern "C" JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeTrySetupShowingWindow(
        JNIEnv* env, jobject thiz) {

    // 1) environ 확인
    const char* env_str = getenv("POJAV_ENVIRON");
    if (!env_str) {
        return JNI_FALSE;
    }
    char* endptr;
    uintptr_t environ_ptr = strtoul(env_str, &endptr, 16);
    if (*endptr != '\0' || environ_ptr == 0) {
        return JNI_FALSE;
    }

    // 2) mainWindowBundle 위치는 환경구조체의 두 번째 필드 (offset = sizeof(void*))
    void** mainWindowBundle_loc = (void**)(environ_ptr + sizeof(void*));
    void* mainWindowBundle = *mainWindowBundle_loc;
    if (!mainWindowBundle) {
        return JNI_FALSE;
    }

    // 3) nglfwSetShowingWindow 호출
    void* h = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) h = dlopen("libglfw.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) {
        LOGE("nativeTrySetupShowingWindow: libpojavexec/libglfw 미로드");
        return JNI_FALSE;
    }

    typedef void (*SetShowing_t)(JNIEnv*, jclass, jlong);
    SetShowing_t fn = (SetShowing_t)dlsym(
            h, "Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow");
    if (!fn) {
        LOGE("nglfwSetShowingWindow 심볼 없음");
        return JNI_FALSE;
    }

    fn(env, nullptr, (jlong)(uintptr_t)mainWindowBundle);
    LOGI("✅ showingWindow = mainWindowBundle = %p", mainWindowBundle);
    return JNI_TRUE;
}

// 현재 environ 의 상태를 한 번에 덤프
extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeDumpInputState(
        JNIEnv* env, jobject thiz) {
    const char* env_str = getenv("POJAV_ENVIRON");
    if (!env_str) {
        LOGI("DUMP: POJAV_ENVIRON 없음");
        return;
    }
    char* endptr;
    uintptr_t environ_ptr = strtoul(env_str, &endptr, 16);
    if (*endptr != '\0' || environ_ptr == 0) {
        LOGI("DUMP: environ 파싱 실패");
        return;
    }

    void* h = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (!h) h = dlopen("libglfw.so", RTLD_NOLOAD | RTLD_NOW);

    void** main_bundle = (void**)(environ_ptr + sizeof(void*));
    LOGI("DUMP: mainWindowBundle = %p", *main_bundle);
    LOGI("DUMP: pojavWindow      = %p", *(void**)environ_ptr);
}
extern "C" JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_presentation_MinecraftActivity_nativeDumpCharCallback(
        JNIEnv* env, jobject thiz) {
    const char* env_str = getenv("POJAV_ENVIRON");
    if (!env_str) { LOGI("DUMP: no POJAV_ENVIRON"); return; }
    char* endptr;
    uintptr_t ptr = strtoul(env_str, &endptr, 16);
    if (*endptr != '\0' || ptr == 0) {
        LOGI("DUMP: environ parse fail");
        return;
    }

    void* h = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_NOW);
    if (h) {
        void* p1 = dlsym(h, "Java_org_lwjgl_glfw_GLFW_nglfwSetCharCallback");
        void* p2 = dlsym(h, "Java_org_lwjgl_glfw_GLFW_nglfwSetKeyCallback");
        void* p3 = dlsym(h, "Java_org_lwjgl_glfw_GLFW_nglfwSetMouseButtonCallback");
        LOGI("DUMP: dlsym nglfwSetCharCallback        = %p", p1);
        LOGI("DUMP: dlsym nglfwSetKeyCallback         = %p", p2);
        LOGI("DUMP: dlsym nglfwSetMouseButtonCallback = %p", p3);
    }

    struct pojav_environ_s* e = (struct pojav_environ_s*)ptr;

    LOGI("DUMP: GLFW_invoke_Char     = %p", (void*)e->GLFW_invoke_Char);
    LOGI("DUMP: GLFW_invoke_CharMods = %p", (void*)e->GLFW_invoke_CharMods);  // ★ 추가
    LOGI("DUMP: GLFW_invoke_Key      = %p", (void*)e->GLFW_invoke_Key);
    LOGI("DUMP: pojav_environ      = %p", e);
    LOGI("DUMP: mainWindowBundle   = %p", e->mainWindowBundle);
    LOGI("DUMP: showingWindow      = 0x%lx", (long)e->showingWindow);
    LOGI("DUMP: isInputReady       = %d", e->isInputReady);
    LOGI("DUMP: isGrabbing         = %d", e->isGrabbing);
    LOGI("DUMP: GLFW_invoke_Char   = %p", (void*)e->GLFW_invoke_Char);
    LOGI("DUMP: GLFW_invoke_Key    = %p", (void*)e->GLFW_invoke_Key);
    LOGI("DUMP: GLFW_invoke_MouseButton = %p", (void*)e->GLFW_invoke_MouseButton);
}