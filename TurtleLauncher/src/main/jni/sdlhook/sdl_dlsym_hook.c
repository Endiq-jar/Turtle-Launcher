// dlsym interception layer for the SDL hooks + module installer.
//
// Two jobs:
//   1. Keep SDL's JNI bound to the ART (launcher) VM. The embedded game JVM
//      shares this process; when it loads libSDL3.so it would invoke
//      JNI_OnLoad with the GAME JavaVM, clobbering the ART-side SDL setup
//      (SdlAndroidJniPrep.setup() runs eagerly on ART before the JVM starts).
//      So resolutions of libSDL3.so's JNI_OnLoad are swapped for a stub that
//      only forwards to the real one for the captured ART VM.
//      Reference: https://github.com/AngelAuraMC/Amethyst-Android/commit/e4c79084d
//   2. Swap SDL symbols for the dlsym-layer proxies (sdl_hook.c) at resolve
//      time, covering consumers like LWJGL's org.lwjgl.sdl binding that call
//      dlsym'd function pointers directly (bytehook's GOT patching cannot see
//      those calls).

#include <stdatomic.h>
#include <stdbool.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <bytehook.h>

#include "sdl_hook.h"
#include "sdl_log.h"

typedef void *(*dlsym_func_t)(void *handle, const char *symbol);
typedef jint (*jni_on_load_func_t)(JavaVM *vm, void *reserved);

typedef bytehook_stub_t (*bytehook_hook_all_fn)(const char *callee_path_name, const char *symbol,
                                                void *new_func, bytehook_hooked_t hooked, void *hooked_arg);

// ART (launcher) VM, captured in JNI_OnLoad (sdlhook_jni.c). SDL's real
// JNI_OnLoad only ever runs against this VM.
static JavaVM *gArtVm = NULL;

void sdlhookSetArtVm(JavaVM *vm) {
    gArtVm = vm;
}

static void *sdlHandle = NULL;
static jni_on_load_func_t originalSdlJniOnLoad = NULL;

static jint isolatedSdlJniOnLoad(JavaVM *vm, void *reserved) {
    if (originalSdlJniOnLoad != NULL && gArtVm != NULL && gArtVm == vm) {
        return originalSdlJniOnLoad(vm, reserved);
    }
    LOG_TO_W("SDL_Hook: ignoring JNI_OnLoad of libSDL3.so for non-ART VM %p", (void *) vm);
    return JNI_VERSION_1_4;
}

static void *customDlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(customDlsym, dlsym_func_t, handle, symbol);
    BYTEHOOK_POP_STACK();
    if (sdlHandle == NULL) {
        sdlHandle = dlopen("libSDL3.so", RTLD_LOCAL | RTLD_NOW);
    }
    if (sdlHandle != NULL && handle == sdlHandle && symbol != NULL && strcmp(symbol, "JNI_OnLoad") == 0) {
        originalSdlJniOnLoad = (jni_on_load_func_t) result;
        result = (void *) isolatedSdlJniOnLoad;
    }
    // LWJGL's org.lwjgl.sdl binding and similar consumers resolve SDL function
    // pointers via dlsym and call them directly; hook_all's GOT import patching
    // cannot intercept those calls, so swap resolutions for sdl_hook's
    // dlsym-layer proxies at the resolve exit.
    if (result != NULL && symbol != NULL) {
        void *proxy = sdlDlsymProxy(symbol, result);
        if (proxy != NULL) {
            LOG_TO_I("SDL_Hook: dlsym proxy for %s", symbol);
            result = proxy;
        }
    }
    return result;
}

static void create_sdl_dlopen_hooks(bytehook_hook_all_fn hookAll) {
    if (hookAll == NULL) return;
    hookAll(NULL, "dlsym", (void *) customDlsym, NULL, NULL);
}

// Installs everything: bytehook (from libbytehook.so, resolved at runtime so
// this module links against nothing but the platform), the dlsym hook, and all
// SDL hooks. Idempotent; returns 0 on success, nonzero on failure.
int sdlhookInstall(void) {
    static atomic_bool installed = false;
    static atomic_int installStatus = 0;
    if (atomic_load(&installed)) return atomic_load(&installStatus);

    int status = 1;
    void *bytehookHandle = dlopen("libbytehook.so", RTLD_NOW);
    if (bytehookHandle == NULL) {
        LOG_TO_E("SDL_Hook: failed to load libbytehook.so: %s", dlerror());
        goto out;
    }

    bytehook_hook_all_fn bytehook_hook_all_p =
            (bytehook_hook_all_fn) dlsym(bytehookHandle, "bytehook_hook_all");
    int (*bytehook_init_p)(int mode, bool debug) =
            (int (*)(int, bool)) dlsym(bytehookHandle, "bytehook_init");
    if (bytehook_hook_all_p == NULL || bytehook_init_p == NULL) {
        LOG_TO_E("SDL_Hook: libbytehook.so is missing bytehook_hook_all/bytehook_init");
        dlclose(bytehookHandle);
        goto out;
    }

    int bhookStatus = bytehook_init_p(BYTEHOOK_MODE_AUTOMATIC, false);
    if (bhookStatus != BYTEHOOK_STATUS_CODE_OK) {
        LOG_TO_E("SDL_Hook: bytehook_init failed (%d)", bhookStatus);
        dlclose(bytehookHandle);
        goto out;
    }

    // The game JVM is embedded in this process, so game-side SDL_InitSubSystem
    // calls are intercepted by the same hooks.
    create_sdl_hooks(bytehook_hook_all_p);
    create_sdl_dlopen_hooks(bytehook_hook_all_p);
    LOG_TO_I("SDL_Hook: SDL hooks installed");
    status = 0;

out:
    atomic_store(&installStatus, status);
    atomic_store(&installed, true);
    return status;
}
