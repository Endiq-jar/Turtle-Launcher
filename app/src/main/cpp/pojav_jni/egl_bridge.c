#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>
#include <time.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/osmesa_loader.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"
#include <vulkan/vulkan.h>

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

// GPU 감지 결과를 담는 구조체.
typedef struct {
    bool     is_adreno;       // Adreno/Qualcomm/Turnip/Freedreno GPU 인지
    bool     is_mali;         // Mali(ARM) GPU 인지 — Panfrost 후보 우선순위 판단용
    bool     found;           // Vulkan 디바이스를 하나라도 찾았는지
    uint32_t api_version;     // 디바이스가 보고한 Vulkan API 버전(VK_MAKE_VERSION 인코딩)
} gpu_probe_t;


struct PotatoBridge {

    /* EGLContext */ void* eglContext;
    /* EGLDisplay */ void* eglDisplay;
    /* EGLSurface */ void* eglSurface;
/*
    void* eglSurfaceRead;
    void* eglSurfaceDraw;
*/
};
EGLConfig config;
struct PotatoBridge potatoBridge;

#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "log.h"

#define RENDERER_GL4ES 1
#define RENDERER_VK_ZINK 2
#define RENDERER_VULKAN 4

// load_vulkan 을 bool 반환으로 (Turnip 로드 성공 여부).
// 기존 void load_vulkan() 호출부가 있으면 같이 수정 필요.
static bool g_turnip_loaded = false;

EXTERNAL_API void pojavTerminate() {
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

            //case RENDERER_VIRGL:
        case RENDERER_VK_ZINK: {
            // Nothing to do here
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(
        JNIEnv* env, jclass clazz, jobject surface)
{
    LOGI("setupBridgeWindow: enter, old pojavWindow=%p", pojav_environ->pojavWindow);

    if (pojav_environ->pojavWindow != NULL) {
        ANativeWindow_release(pojav_environ->pojavWindow);
        pojav_environ->pojavWindow = NULL;
    }

    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);
    if (pojav_environ->pojavWindow == NULL) {
        LOGE("setupBridgeWindow: ANativeWindow_fromSurface returned NULL");
        return;
    }
    int32_t w = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    int32_t h = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    LOGI("setupBridgeWindow: new pojavWindow=%p size=%dx%d, mainWindowBundle=%p",
         pojav_environ->pojavWindow, w, h, pojav_environ->mainWindowBundle);

    if(br_setup_window != NULL) br_setup_window();

    LOGI("setupBridgeWindow: after br_setup_window, state=%d newNativeSurface=%p",
         pojav_environ->mainWindowBundle ? pojav_environ->mainWindowBundle->state : -1,
         pojav_environ->mainWindowBundle ? pojav_environ->mainWindowBundle->newNativeSurface : NULL);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass clazz) {
    ANativeWindow_release(pojav_environ->pojavWindow);
}

#define ADRENO_POSSIBLE
#ifdef ADRENO_POSSIBLE
#include <adrenotools/driver.h>

/**
 * 사용자가 웹/K11MCH1 AdrenoToolsDrivers 등에서 받은 표준 포맷의 Turnip 드라이버
 * zip 을 압축 해제해서 저장해둔 폴더 + meta.json 에서 읽은 실제 .so 파일명을
 * 환경변수로 전달받아 libadrenotools 로 로드한다.
 *   - POJAV_CUSTOM_VULKAN_DRIVER_DIR : 압축 해제된 드라이버 폴더 경로
 *   - POJAV_CUSTOM_VULKAN_DRIVER_NAME: 그 폴더 안의 .so 파일명(meta.json 의 libraryName)
 * 둘 다 없으면 기존 동작(시스템 기본 Vulkan) 그대로 — 완전히 옵트인이라
 * 기존 사용자 경험에는 영향이 없다.
 */
void* load_custom_vulkan_driver() {
    const char* driver_dir = getenv("POJAV_CUSTOM_VULKAN_DRIVER_DIR");
    const char* driver_name = getenv("POJAV_CUSTOM_VULKAN_DRIVER_NAME");
    if (driver_dir == NULL || driver_name == NULL) return NULL;

    const char* native_lib_dir = getenv("POJAV_NATIVEDIR");   // getApplicationInfo().nativeLibraryDir 값
    const char* tmp_dir = getenv("TMPDIR");
    if (native_lib_dir == NULL) {
        printf("AdrenoTools: POJAV_NATIVEDIR 가 없어서 커스텀 드라이버를 로드할 수 없음\n");
        return NULL;
    }

    void* lib = adrenotools_open_libvulkan(
            RTLD_NOW | RTLD_LOCAL,
            ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT,
            tmp_dir,
            native_lib_dir,
            driver_dir,
            driver_name,
            tmp_dir,
            NULL
    );
    if (lib == NULL) {
        printf("AdrenoTools: 커스텀 드라이버 로드 실패 (%s/%s)\n", driver_dir, driver_name);
    } else {
        printf("AdrenoTools: 커스텀 Vulkan 드라이버 로드 성공 (%s)\n", driver_name);
    }
    return lib;
}
#endif

#define ADRENO_POSSIBLE_OLD
#ifdef ADRENO_POSSIBLE_OLD
void* load_turnip_vulkan() {
    if(getenv("POJAV_LOAD_TURNIP") == NULL) return NULL;
    const char* native_dir = getenv("POJAV_NATIVEDIR");
    const char* cache_dir = getenv("TMPDIR");
    if(!linker_ns_load(native_dir)) return NULL;
    void* linkerhook = linker_ns_dlopen("liblinkerhook.so", RTLD_LOCAL | RTLD_NOW);
    if(linkerhook == NULL) return NULL;
    void* turnip_driver_handle = linker_ns_dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW);
    if(turnip_driver_handle == NULL) {
        printf("AdrenoSupp: Failed to load Turnip!\n%s\n", dlerror());
        dlclose(linkerhook);
        return NULL;
    }
    void* dl_android = linker_ns_dlopen("libdl_android.so", RTLD_LOCAL | RTLD_LAZY);
    if(dl_android == NULL) {
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    void* android_get_exported_namespace = dlsym(dl_android, "android_get_exported_namespace");
    void (*linkerhook_pass_handles)(void*, void*, void*) = dlsym(linkerhook, "app__pojav_linkerhook_pass_handles");
    if(linkerhook_pass_handles == NULL || android_get_exported_namespace == NULL) {
        dlclose(dl_android);
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }
    linkerhook_pass_handles(turnip_driver_handle, android_dlopen_ext, android_get_exported_namespace);
    void* libvulkan = linker_ns_dlopen_unique(cache_dir, "libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    return libvulkan;
}
#endif

static void set_vulkan_ptr(void* ptr) {
    char envval[64];
    sprintf(envval, "%"PRIxPTR, (uintptr_t)ptr);
    setenv("VULKAN_PTR", envval, 1);
}

void load_vulkan() {
    if(android_get_device_api_level() >= 28) {
#ifdef ADRENO_POSSIBLE
        // 사용자가 설정에서 커스텀 Vulkan 드라이버(Turnip 등)를 지정해뒀으면 최우선 시도.
        void* customResult = load_custom_vulkan_driver();
        if(customResult != NULL) {
            printf("AdrenoTools: 커스텀 드라이버 사용, loader address: %p\n", customResult);
            set_vulkan_ptr(customResult);
            g_turnip_loaded = true;
            return;
        }
#endif
#ifdef ADRENO_POSSIBLE_OLD
        void* result = load_turnip_vulkan();
        if(result != NULL) {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            g_turnip_loaded = true;
            return;
        }
        g_turnip_loaded = false;   // ★ 실패 기록
#endif
    }
    printf("OSMDroid: loading vulkan regularly...\n");
    void* vulkan_ptr = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    set_vulkan_ptr(vulkan_ptr);
}



static bool probe_vulkan_works() {
    void* h = dlopen("libvulkan.so", RTLD_NOW);
    if (!h) { printf("Zink probe: libvulkan.so 못 찾음\n"); return false; }

    typedef PFN_vkVoidFunction (*PFN_vkGetInstanceProcAddr_t)(VkInstance, const char*);
    typedef VkResult (*PFN_vkCreateInstance_t)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);

    PFN_vkGetInstanceProcAddr_t gipa = (PFN_vkGetInstanceProcAddr_t)dlsym(h, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance_t createInst = (PFN_vkCreateInstance_t)dlsym(h, "vkCreateInstance");
    if (!gipa || !createInst) { dlclose(h); return false; }

    VkApplicationInfo app = {0};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci = {0};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkInstance inst = VK_NULL_HANDLE;
    if (createInst(&ci, NULL, &inst) != VK_SUCCESS) {
        printf("Zink probe: vkCreateInstance 실패\n");
        dlclose(h); return false;
    }

    PFN_vkEnumeratePhysicalDevices enumPD =
            (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceFeatures getFeats =
            (PFN_vkGetPhysicalDeviceFeatures)gipa(inst, "vkGetPhysicalDeviceFeatures");
    PFN_vkGetPhysicalDeviceProperties getProps =
            (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
    PFN_vkDestroyInstance destInst =
            (PFN_vkDestroyInstance)gipa(inst, "vkDestroyInstance");

    uint32_t deviceCount = 0;
    enumPD(inst, &deviceCount, NULL);
    if (deviceCount == 0) {
        printf("Zink probe: no Vulkan physical devices\n");
        if (destInst) destInst(inst, NULL);
        dlclose(h); return false;
    }

    if (deviceCount > 8) deviceCount = 8;
    VkPhysicalDevice phys[8];
    enumPD(inst, &deviceCount, phys);

    bool any_compatible = false;

    for (uint32_t i = 0; i < deviceCount; i++) {
        VkPhysicalDeviceProperties props = {0};
        VkPhysicalDeviceFeatures   feats = {0};
        getProps(phys[i], &props);
        getFeats(phys[i], &feats);
        bool ok = true;
        printf("Zink probe: device #%u (%s) logicOp=%d fillModeNonSolid=%d shaderClipDistance=%d -> %s\n",
               i, props.deviceName,
               feats.logicOp, feats.fillModeNonSolid, feats.shaderClipDistance,
               ok ? "COMPATIBLE" : "INCOMPATIBLE");
        if (ok) { any_compatible = true; }
    }

    if (destInst) destInst(inst, NULL);
    dlclose(h);

    if (!any_compatible) {
        printf("Zink probe: no Vulkan device meets Zink base requirements\n");
    }
    return any_compatible;
}

// 순정 libvulkan 으로 첫 물리 디바이스의 벤더/버전만 읽고 바로 닫는다(경량).
// MESA_VK_VERSION_OVERRIDE 를 기기별로 정하기 위한 정보 수집용.
static gpu_probe_t detect_gpu() {
    gpu_probe_t r = { false, false, false, 0 };
    void* h = dlopen("libvulkan.so", RTLD_NOW);
    if (!h) return r;

    typedef PFN_vkVoidFunction (*GIPA_t)(VkInstance, const char*);
    typedef VkResult (*CI_t)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
    GIPA_t gipa = (GIPA_t)dlsym(h, "vkGetInstanceProcAddr");
    CI_t   createInst = (CI_t)dlsym(h, "vkCreateInstance");
    if (!gipa || !createInst) { dlclose(h); return r; }

    VkApplicationInfo app = {0};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci = {0};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkInstance inst = VK_NULL_HANDLE;
    if (createInst(&ci, NULL, &inst) != VK_SUCCESS) { dlclose(h); return r; }

    PFN_vkEnumeratePhysicalDevices enumPD =
            (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties getProps =
            (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
    PFN_vkDestroyInstance destInst =
            (PFN_vkDestroyInstance)gipa(inst, "vkDestroyInstance");

    uint32_t count = 0;
    if (enumPD && getProps) {
        enumPD(inst, &count, NULL);
        if (count > 8) count = 8;
        if (count > 0) {
            VkPhysicalDevice phys[8];
            enumPD(inst, &count, phys);
            // 첫 디바이스를 대표로 사용(모바일은 보통 1개).
            //   여러 개면 Adreno 를 우선 채택.
            uint32_t chosen = 0;
            for (uint32_t i = 0; i < count; i++) {
                VkPhysicalDeviceProperties props = {0};
                getProps(phys[i], &props);
                printf("GPU detect: device #%u = %s (api %u.%u.%u)\n",
                       i, props.deviceName,
                       VK_VERSION_MAJOR(props.apiVersion),
                       VK_VERSION_MINOR(props.apiVersion),
                       VK_VERSION_PATCH(props.apiVersion));
                bool adreno = strcasestr(props.deviceName, "adreno")    ||
                              strcasestr(props.deviceName, "qualcomm")  ||
                              strcasestr(props.deviceName, "turnip")    ||
                              strcasestr(props.deviceName, "freedreno");
                bool mali   = strcasestr(props.deviceName, "mali")     ||
                              strcasestr(props.deviceName, "panfrost") ||
                              strcasestr(props.deviceName, "arm ");
                if (adreno && !r.is_adreno) { chosen = i; r.is_adreno = true; }
                if (mali   && !r.is_mali)   { r.is_mali = true; if (!r.is_adreno) chosen = i; }
            }
            VkPhysicalDeviceProperties cp = {0};
            getProps(phys[chosen], &cp);
            r.api_version = cp.apiVersion;
            r.found = true;
        }
    }
    if (destInst) destInst(inst, NULL);
    dlclose(h);
    return r;
}

// ── GALLIUM_DRIVER 후보(Freedreno/Panfrost) 실동작 여부 프로브 ──────────────
//   ZL2 는 Vulkan Zink / Freedreno / Panfrost 를 사용자가 수동으로 고르는
//   별개의 렌더러 3개로 노출하지만(FreedrenoRenderer.kt/PanfrostRenderer.kt),
//   여기서는 그 3개를 자동 폴백 체인으로 묶는다. GALLIUM_DRIVER 는 실제
//   드라이버 초기화가 지연(lazy)되어 있어서 dlopen/dlsym 단계만으로는
//   성공 여부를 알 수 없다 — osm_init_context() 와 동일한 패턴으로 더미
//   OSMesaContext 를 하나 만들어보고 즉시 폐기해서 실동작 여부를 판정한다.
static bool try_gallium_probe(const char* label) {
    if (!dlsym_OSMesa()) {
        printf("OpenGL: [%s] libOSMesa dlsym 실패\n", label);
        return false;
    }
    if (OSMesaCreateContext_p == NULL || OSMesaDestroyContext_p == NULL) {
        printf("OpenGL: [%s] OSMesaCreateContext_p 심볼 없음\n", label);
        return false;
    }
    OSMesaContext probe = OSMesaCreateContext_p(GL_RGBA, NULL);
    if (probe == NULL) {
        printf("OpenGL: [%s] OSMesaCreateContext 실패 — 이 기기에서 동작 안 함\n", label);
        return false;
    }
    OSMesaDestroyContext_p(probe);
    printf("OpenGL: [%s] 프로브 성공\n", label);
    return true;
}

int pojavInitOpenGL() {
    // VSync 강제 비활성화 — 게임이 요청하는 swap interval(0=무제한)을 그대로 존중한다.
    //   true 로 두면 gl_swap_interval / swap 경로에서 무조건 1(=디스플레이 주사율 동기)로 덮어써
    //   60fps(또는 패널 주사율)에 고정된다. 최대 프레임을 뽑기 위해 false.
    //   (실제 상한은 게임 비디오 설정의 "최대 프레임 속도"가 결정한다 → 무제한으로 둘 것)
    pojav_environ->force_vsync = false;

    const char *renderer = getenv("POJAV_RENDERER");
    if (!renderer) {
        printf("POJAV_RENDERER not set! defaulting to vulkan_zink\n");
        renderer = "vulkan_zink";
    }

    printf("OpenGL: renderer = %s\n", renderer);

    // ── 렌더러별 bridge table 설정 ─────────────────────────────
    if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
        printf("OpenGL: set_gl_bridge_tbl() done (GL4ES path)\n");
    } else if (strcmp(renderer, "vulkan_zink") == 0) {
        // GPU 벤더 + 지원 Vulkan 버전 감지(경량).
        gpu_probe_t gpu = detect_gpu();

        // ── "Vulkan Zink 계열" 3종 자동 폴백 체인 ──────────────────────────
        //   ZL2 는 이 3개를 사용자가 수동으로 고르는 별개 렌더러로 노출한다
        //   (VulkanZinkRenderer.kt / FreedrenoRenderer.kt / PanfrostRenderer.kt).
        //   env 값은 그 3개 소스와 동일하게 맞췄다:
        //     · GV_ZINK      : GALLIUM_DRIVER=zink,      MESA_LOADER_DRIVER_OVERRIDE=zink
        //     · GV_FREEDRENO : GALLIUM_DRIVER=freedreno, MESA_LOADER_DRIVER_OVERRIDE=kgsl   (Adreno 전용)
        //     · GV_PANFROST  : GALLIUM_DRIVER=panfrost,  MESA_DISK_CACHE_SINGLE_FILE=1        (Mali 전용)
        //   여기서는 이 3개를 하나로 묶어 "1순위 실패 시 2순위, 그것도 실패하면
        //   3순위, 셋 다 실패하면 GL4ES" 로 자동 시도한다. 번들 libOSMesa.so 는
        //   zink/freedreno/panfrost 3개 Gallium 드라이버가 전부 컴파일되어
        //   있음을 확인했다(strings 로 "Mali-G57 (Panfrost)" 등 문자열 확인됨).
        typedef enum { GV_ZINK, GV_FREEDRENO, GV_PANFROST } gallium_variant_t;

        // 우선순위: 표준 Zink 는 항상 1순위(가장 넓은 호환성).
        //   2순위는 감지된 벤더의 네이티브 Gallium 드라이버, 3순위는 나머지 하나
        //   (해당 없는 기기에서는 바로 실패하고 다음으로 넘어갈 뿐이라 안전함).
        gallium_variant_t order[3];
        if (gpu.is_adreno) {
            order[0] = GV_ZINK; order[1] = GV_FREEDRENO; order[2] = GV_PANFROST;
        } else {
            // Mali 감지든, 벤더 미상이든 동일 순서(둘 다 Freedreno 는 어차피 스킵됨).
            order[0] = GV_ZINK; order[1] = GV_PANFROST; order[2] = GV_FREEDRENO;
        }

        if (gpu.is_adreno) {
            printf("OpenGL: Adreno GPU 감지 → Turnip 활성화\n");
            setenv("POJAV_LOAD_TURNIP", "1", 1);
        }

        bool variant_ok = false;
        gallium_variant_t chosen = GV_ZINK;

        for (int i = 0; i < 3; i++) {
            gallium_variant_t v = order[i];
            const char *label =
                    (v == GV_ZINK)      ? "Vulkan Zink" :
                    (v == GV_FREEDRENO) ? "Freedreno(Adreno)" : "Panfrost(Mali)";

            // 벤더가 명확히 반대인 후보는 시도해봐야 실패만 하므로 미리 스킵.
            if (v == GV_FREEDRENO && !gpu.is_adreno && gpu.found) {
                printf("OpenGL: [%s] Adreno 아님 → 스킵\n", label);
                continue;
            }
            if (v == GV_PANFROST && gpu.is_adreno) {
                printf("OpenGL: [%s] Adreno 기기 → 스킵\n", label);
                continue;
            }

            // 이전 후보가 남긴 env 를 정리하고 이번 후보 값으로 세팅.
            unsetenv("MESA_LOADER_DRIVER_OVERRIDE");
            unsetenv("MESA_DISK_CACHE_SINGLE_FILE");
            switch (v) {
                case GV_ZINK:
                    setenv("GALLIUM_DRIVER", "zink", 1);
                    setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
                    break;
                case GV_FREEDRENO:
                    setenv("GALLIUM_DRIVER", "freedreno", 1);
                    setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
                    break;
                case GV_PANFROST:
                    setenv("GALLIUM_DRIVER", "panfrost", 1);
                    setenv("MESA_DISK_CACHE_SINGLE_FILE", "1", 1);
                    break;
            }

            bool this_ok;
            if (v == GV_ZINK) {
                // 표준 Zink 는 기존에 검증된 Vulkan 인스턴스/디바이스 프로브를 그대로 사용.
                this_ok = gpu.is_adreno ? true : probe_vulkan_works();
                if (this_ok) {
                    load_vulkan();
                    // Adreno 인데 Turnip 로드 실패 → 순정으로는 Zink 가 죽으므로 이 후보는 실패 처리.
                    if (gpu.is_adreno && !g_turnip_loaded) {
                        printf("OpenGL: [%s] Adreno Turnip 로드 실패\n", label);
                        this_ok = false;
                    }
                }
            } else {
                // Freedreno/Panfrost 는 실제 더미 OSMesaContext 생성 성공 여부로 판정.
                if (v == GV_FREEDRENO) load_vulkan();   // ZL2 FreedrenoRenderer 와 동일하게 유지
                this_ok = try_gallium_probe(label);
            }

            if (this_ok) {
                variant_ok = true;
                chosen = v;
                printf("OpenGL: [%s] 선택됨\n", label);
                break;
            }
            printf("OpenGL: [%s] 실패 — 다음 후보로 폴백\n", label);

            // Kotlin(RendererManager.buildEnv)에서 이 버전은 GL4ES 로 안전하게 구동
            // 가능하다고 판단했으면(POJAV_ZINK_FALLBACK_GL4ES_DIRECT=1), 표준 Zink 가
            // 실패한 시점에 Freedreno/Panfrost 를 더 시도하지 않고 곧바로 GL4ES 로
            // 폴백한다 — 어차피 GL4ES 가 최종 목적지라면 중간 단계에서 시간을 쓸 이유가 없다.
            if (v == GV_ZINK) {
                const char *prefer_gl4es = getenv("POJAV_ZINK_FALLBACK_GL4ES_DIRECT");
                if (prefer_gl4es != NULL && strcmp(prefer_gl4es, "1") == 0) {
                    printf("OpenGL: Zink 실패, GL4ES 지원 버전으로 판단됨 → Freedreno/Panfrost 건너뛰고 GL4ES 직행\n");
                    break;
                }
            }
        }

        if (!variant_ok) {
            printf("OpenGL: Vulkan/Zink 계열 3종 모두 실패 — GL4ES 로 최종 폴백\n");
            setenv("POJAV_RENDERER", "opengles2", 1);
            setenv("LIBGL_NAME",     "libgl4es_114.so", 1);
            setenv("DLOPEN",         "libgl4es_114.so", 1);
            setenv("LIBGL_ES",       "2",         1);
            unsetenv("GALLIUM_DRIVER");
            unsetenv("MESA_LOADER_DRIVER_OVERRIDE");
            unsetenv("MESA_DISK_CACHE_SINGLE_FILE");
            pojav_environ->config_renderer = RENDERER_GL4ES;
            set_gl_bridge_tbl();
            printf("OpenGL: switched to GL4ES bridge table\n");
        } else {
            pojav_environ->config_renderer = RENDERER_VK_ZINK;

            if (chosen == GV_ZINK) {
                // Zink 전용 튜닝 env 는 Zink 가 실제로 선택됐을 때만 적용
                //   (Freedreno/Panfrost 는 Vulkan 을 거치지 않는 네이티브 GL 경로라 불필요).
                if (!getenv("MESA_GL_VERSION_OVERRIDE")) setenv("MESA_GL_VERSION_OVERRIDE", "4.6", 1);
                if (!getenv("MESA_GLSL_VERSION_OVERRIDE"))
                    setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);
                if (!getenv("force_glsl_extensions_warn"))
                    setenv("force_glsl_extensions_warn", "true", 1);
                if (!getenv("allow_higher_compat_version"))
                    setenv("allow_higher_compat_version", "true", 1);
                if (!getenv("allow_glsl_extension_directive_midshader"))
                    setenv("allow_glsl_extension_directive_midshader", "true", 1);

                setenv("ZINK_DESCRIPTORS", "lazy", 1);          // descriptor 지연 할당(누적 방지)
                setenv("MESA_GL_MAX_TEXTURE_SIZE", "4096", 1);
                setenv("ZINK_DEBUG", "compact", 1);
                setenv("MESA_VK_WSI_PRESENT_MODE", "fifo", 1);

                // ── MESA_VK_VERSION_OVERRIDE: 고정하지 않고 GPU 에 맞춰 유동 결정 ──
                //   사용자가 이미 명시적으로 지정했으면 존중(건드리지 않음).
                if (!getenv("MESA_VK_VERSION_OVERRIDE")) {
                    if (gpu.is_adreno) {
                        // Adreno/Turnip: 강제 override 제거 → Turnip 이 보고하는 네이티브 버전 사용.
                        //   (Turnip 은 Adreno 세대에 따라 1.0~1.3+ 로 다양하게 보고)
                        unsetenv("MESA_VK_VERSION_OVERRIDE");
                        printf("OpenGL: Adreno → MESA_VK_VERSION_OVERRIDE 미설정(네이티브 버전 사용)\n");
                    } else if (gpu.found) {
                        // 비-Adreno(주로 Mali): 기기 보고 버전과 1.1 중 낮은 쪽으로 상한.
                        //   Mali 는 1.1 이 검증된 안전선이고 1.3 은 불안정(PojavLauncher 위키).
                        //   기기가 1.1 미만이면 그 값을 그대로 둠(억지로 올리지 않음).
                        uint32_t maj = VK_VERSION_MAJOR(gpu.api_version);
                        uint32_t min = VK_VERSION_MINOR(gpu.api_version);
                        const char *ver;
                        if (maj > 1 || (maj == 1 && min >= 1)) {
                            ver = "1.1";                 // 1.1 이상이면 1.1 로 상한
                        } else {
                            ver = "1.0";                 // 1.0 기기면 1.0 유지
                        }
                        setenv("MESA_VK_VERSION_OVERRIDE", ver, 1);
                        printf("OpenGL: non-Adreno → MESA_VK_VERSION_OVERRIDE=%s (기기 보고 %u.%u)\n",
                               ver, maj, min);
                    } else {
                        // GPU 감지 실패(드라이버 못 엶 등): 기존 보수값 1.1 유지.
                        setenv("MESA_VK_VERSION_OVERRIDE", "1.1", 1);
                        printf("OpenGL: GPU 감지 실패 → MESA_VK_VERSION_OVERRIDE=1.1(보수 기본)\n");
                    }
                }
            } else if (chosen == GV_PANFROST) {
                // ZL2 PanfrostRenderer 와 동일한 안전선(1.21.4 이하 권장) — 로그만 남김.
                printf("OpenGL: Panfrost 선택됨 — 최신 버전(1.21.5+)에서는 불안정할 수 있음\n");
            }

            set_osm_bridge_tbl();
            printf("OpenGL: set_osm_bridge_tbl() done (%s path)\n",
                   chosen == GV_ZINK ? "Zink" : chosen == GV_FREEDRENO ? "Freedreno" : "Panfrost");
        }
    }  else {
        printf("OpenGL: unknown renderer '%s', defaulting to vulkan_zink\n", renderer);
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER", "zink", 1);
        // ★ Zink 성능 핵심 env (Zink 경로와 동일) — fallback 에서도 동일하게 적용
        setenv("ZINK_DESCRIPTORS", "lazy", 1);
        setenv("MESA_VK_VERSION_OVERRIDE", "1.1", 1);
        setenv("ZINK_DEBUG", "compact", 1);
        setenv("MESA_VK_WSI_PRESENT_MODE", "fifo", 1);
        set_osm_bridge_tbl();
        printf("OpenGL: set_osm_bridge_tbl() done (fallback path)\n");
    }

    // ── bridge table 채워졌는지 검증 ───────────────────────────
    if (br_init == NULL || br_init_context == NULL ||
        br_make_current == NULL || br_swap_buffers == NULL) {
        printf("OpenGL: FATAL — bridge_tbl not populated! "
               "br_init=%p br_init_context=%p br_make_current=%p br_swap_buffers=%p\n",
               (void*)br_init, (void*)br_init_context,
               (void*)br_make_current, (void*)br_swap_buffers);
        return -1;
    }
    printf("OpenGL: bridge_tbl OK — br_init=%p br_init_context=%p br_make_current=%p\n",
           (void*)br_init, (void*)br_init_context, (void*)br_make_current);

    // ── 실제 렌더 백엔드 초기화 (EGL or OSMesa) ───────────────
    printf("OpenGL: calling br_init() ...\n");
    if (!br_init()) {
        printf("OpenGL: br_init() FAILED — EGL/OSMesa 초기화 실패\n");
        return -1;
    }
    printf("OpenGL: br_init() succeeded\n");

    return 0;
}

extern void updateMonitorSize(int width, int height);

EXTERNAL_API int pojavInit() {
    pojav_environ->glfwThreadVmEnv = get_attached_env(pojav_environ->runtimeJavaVMPtr);
    if(pojav_environ->glfwThreadVmEnv == NULL) {
        printf("Failed to attach Java-side JNIEnv to GLFW thread\n");
        return 0;
    }
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(
            pojav_environ->pojavWindow,
            pojav_environ->savedWidth, pojav_environ->savedHeight,
            AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);

    if (pojavInitOpenGL() != 0) {
        printf("pojavInit: pojavInitOpenGL() failed\n");
        return 0;   // LWJGL에 실패 알림
    }
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API:
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

extern void pojavBootDispatchFramebufferSize(void);

// ── 첫 프레임 콜백 (부팅 로딩 다이얼로그를 닫기 위함) ──
// 게임이 surface 에 처음 그리는 순간(첫 swap)을 감지해 Java 측에 한 번 알린다.
//
// 중요: CallbackBridge 는 "dalvik(앱) VM" 의 클래스다. 게임 런타임 JVM(glfwThreadVmEnv)
// 에서 FindClass 로 찾으면 클래스로더가 달라 '메서드 없는' 다른 클래스를 잡는다.
// 그래서 input_bridge_v3.c::JNI_OnLoad 가 캐시해둔 dalvik 전역 참조
// (pojav_environ->bridgeClazz) 를 그대로 쓰고, 호출도 dalvik VM env 로 한다.
// (clipboard/grab 콜백과 동일한 검증된 경로)
static bool firstFrameReported = false;
static jmethodID s_method_onFirstFrameRendered = NULL;

static void reportFirstFrameToJava() {
    if (firstFrameReported) return;
    firstFrameReported = true;

    if (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL) {
        printf("reportFirstFrame: no dalvik JavaVM, skip\n");
        return;
    }
    if (pojav_environ->bridgeClazz == NULL) {
        printf("reportFirstFrame: bridgeClazz not cached yet, skip\n");
        return;
    }

    // dalvik VM 의 env 확보 (clipboard 콜백과 동일 패턴)
    JNIEnv* dalvikEnv = NULL;
    jint envStat = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(
            pojav_environ->dalvikJavaVMPtr, (void**)&dalvikEnv, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    }
    if (dalvikEnv == NULL) {
        printf("reportFirstFrame: failed to get dalvik JNIEnv\n");
        return;
    }

    // 메서드 ID 한 번만 조회해서 캐시 (올바른 클래스이므로 정상적으로 찾아진다)
    if (s_method_onFirstFrameRendered == NULL) {
        s_method_onFirstFrameRendered = (*dalvikEnv)->GetStaticMethodID(
                dalvikEnv, pojav_environ->bridgeClazz, "onFirstFrameRendered", "()V");
        if (s_method_onFirstFrameRendered == NULL) {
            if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) (*dalvikEnv)->ExceptionClear(dalvikEnv);
            printf("reportFirstFrame: onFirstFrameRendered() not found on bridgeClazz\n");
            return;
        }
    }

    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, pojav_environ->bridgeClazz,
                                       s_method_onFirstFrameRendered);
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) (*dalvikEnv)->ExceptionClear(dalvikEnv);
    // DetachCurrentThread 하지 않음 (clipboard 콜백과 동일 — 이후 재사용)
    printf("reportFirstFrame: notified Java (first frame rendered)\n");
}

// ── 실시간 FPS 콜백 ──
// pojavSwapBuffers() 가 매 프레임 부르지만, 매번 JNI 호출하면 오버헤드가 크므로
// 일정 주기(500ms)마다 누적 프레임 수를 모아 한 번씩 Dalvik 측에 보고한다.
// (reportFirstFrameToJava 와 동일한 검증된 경로: dalvik VM env + bridgeClazz)
static jmethodID s_method_onFramePresented = NULL;
static int       s_fpsFrameAccum = 0;
static int64_t   s_fpsLastReportNs = 0;

static int64_t nowMonotonicNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void reportFpsToJava() {
    s_fpsFrameAccum++;

    int64_t now = nowMonotonicNs();
    if (s_fpsLastReportNs == 0) { s_fpsLastReportNs = now; return; }

    int64_t elapsed = now - s_fpsLastReportNs;
    if (elapsed < 500000000LL) return;   // 500ms 미만이면 계속 누적

    if (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL
        || pojav_environ->bridgeClazz == NULL) {
        s_fpsFrameAccum = 0; s_fpsLastReportNs = now; return;
    }

    JNIEnv* dalvikEnv = NULL;
    jint envStat = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(
            pojav_environ->dalvikJavaVMPtr, (void**)&dalvikEnv, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    }
    if (dalvikEnv == NULL) { s_fpsFrameAccum = 0; s_fpsLastReportNs = now; return; }

    if (s_method_onFramePresented == NULL) {
        s_method_onFramePresented = (*dalvikEnv)->GetStaticMethodID(
                dalvikEnv, pojav_environ->bridgeClazz, "onFramePresented", "(IJ)V");
        if (s_method_onFramePresented == NULL) {
            if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) (*dalvikEnv)->ExceptionClear(dalvikEnv);
            printf("reportFps: onFramePresented(IJ)V not found on bridgeClazz\n");
            s_fpsFrameAccum = 0; s_fpsLastReportNs = now;
            return;
        }
    }

    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, pojav_environ->bridgeClazz,
                                       s_method_onFramePresented,
                                       (jint)s_fpsFrameAccum, (jlong)elapsed);
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) (*dalvikEnv)->ExceptionClear(dalvikEnv);

    s_fpsFrameAccum = 0;
    s_fpsLastReportNs = now;
    // DetachCurrentThread 안 함 (기존 콜백과 동일 — 게임 스레드 재사용)
}

EXTERNAL_API void pojavSwapBuffers() {
    static int counter = 0;
    if ((++counter % 60) == 0) {
        printf("pojavSwapBuffers: tid=%d counter=%d\n", gettid(), counter);
    }

    pojavBootDispatchFramebufferSize();   // ★ 추가: 첫 swap 직전 한 번 발화

    br_swap_buffers();

    // ★ 첫 프레임을 실제로 화면에 올린 직후 Java 측에 알림 (로딩 다이얼로그 닫기)
    if (!firstFrameReported) {
        reportFirstFrameToJava();
    }

    reportFpsToJava();   // ★ 실제 스왑(=화면에 올라간 프레임)마다 집계 → 500ms 주기 보고
}

EXTERNAL_API void* pojavGetCurrentContext() {
    void* current = br_get_current();

    // ★ OSMesa 경로일 때: 이 스레드에 context가 안 묶여 있으면 강제 rebind
    if (current != NULL && pojav_environ->config_renderer == RENDERER_VK_ZINK) {
        OSMesaContext osmCur = OSMesaGetCurrentContext_p ? OSMesaGetCurrentContext_p() : NULL;
        if (osmCur == NULL) {
            printf("pojavGetCurrentContext: rebinding OSMesa to current thread (tid=%d)\n",
                   gettid());
            br_make_current((basic_render_window_t*)current);
        }
    }
    return current;
}

EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (br_make_current == NULL) {
        printf("pojavMakeCurrent: br_make_current is NULL!\n");
        return;
    }
    br_make_current((basic_render_window_t*)window);
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    printf("pojavCreateContext: tid=%d contextSrc=%p\n", gettid(), contextSrc);

    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void *) pojav_environ->pojavWindow;
    }
    if (br_init_context == NULL) {
        printf("pojavCreateContext: br_init_context is NULL!\n");
        return NULL;
    }
    void* result = br_init_context((basic_render_window_t*)contextSrc);
    if (result == NULL) {
        // ★ 추가: zink/osmesa가 context 생성에 실패한 케이스
        printf("pojavCreateContext: br_init_context returned NULL — "
               "renderer=%d, likely Vulkan device incompatible with zink "
               "(no suitable physical device / missing extensions)\n",
               pojav_environ->config_renderer);
    }
    printf("pojavCreateContext returned %p\n", result);
    return result;
}

void* maybe_load_vulkan() {
    // We use the env var because
    // 1. it's easier to do that
    // 2. it won't break if something will try to load vulkan and osmesa simultaneously
    if(getenv("VULKAN_PTR") == NULL) load_vulkan();
    return (void*) strtoul(getenv("VULKAN_PTR"), NULL, 0x10);
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    br_swap_interval(interval);
}