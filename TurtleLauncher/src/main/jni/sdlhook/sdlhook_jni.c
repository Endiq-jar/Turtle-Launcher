// JNI entry points of libsdlhook.so (Turtle package namespace).
//
//   SdlHook.nativeInit()                        install the hooks (idempotent)
//   SdlBridge.initializeControllerSubsystems()  SDL_Init(GAMEPAD|JOYSTICK|EVENTS)
//   SdlBridge.isSdlRenderActive()               primary SDL window exists?
//   SdlBridge.setNativeTextInputActive(active)  launcher-managed IME switch
//
// The text-input channel (port of ZalithLauncher2 input_bridge_v3.c, itself
// referencing FCL-Team/FoldCraftLauncher) lets the launcher side explicitly
// show/hide the soft keyboard on the SDL render path: SDL3 text-input APIs must
// run on the thread that owns the window, so the calls are dispatched through
// SDL_RunOnMainThread and the result is mirrored back to Java asynchronously.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <dlfcn.h>
#include <jni.h>

#include "sdl_hook.h"
#include "sdl_log.h"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    sdlhookSetArtVm(vm);
    return JNI_VERSION_1_4;
}

JNIEXPORT jint JNICALL
Java_com_endiq_turtlelauncher_launch_SdlHook_nativeInit(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) sdlhookInstall();
}

JNIEXPORT void JNICALL
Java_com_endiq_turtlelauncher_game_sdl_SdlBridge_initializeControllerSubsystemsNative(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    typedef bool (*SDL_Init_Func)(unsigned int flags);
    void *handle = dlopen("libSDL3.so", RTLD_NOW);
    if (handle == NULL) {
        LOG_TO_E("SDL_Hook: initializeControllerSubsystems: libSDL3.so dlopen failed");
        return;
    }
    SDL_Init_Func SDL_Init = (SDL_Init_Func) dlsym(handle, "SDL_Init");
    if (SDL_Init == NULL) {
        LOG_TO_E("SDL_Hook: initializeControllerSubsystems: SDL_Init not found");
        return;
    }
    // SDL3: SDL_INIT_GAMEPAD=0x2000 | SDL_INIT_JOYSTICK=0x200 | SDL_INIT_EVENTS=0x4000
    SDL_Init(0x2000u | 0x200u | 0x4000u);
    LOG_TO_I("SDL_Hook: initializeControllerSubsystems: SDL controller subsystems initialized");
}

JNIEXPORT jboolean JNICALL
Java_com_endiq_turtlelauncher_game_sdl_SdlBridge_isSdlRenderActiveNative(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // The SDL render path is flagged by the creation of the first SDL window;
    // controller-subsystem-only SDL use (MC 26.2 with Controlify) has no window.
    return sdlhookHasPrimaryWindow() ? JNI_TRUE : JNI_FALSE;
}

typedef uint32_t SDL_PropertiesID;
typedef void (*SDL_MainThreadCallback)(void *userdata);
typedef bool (*sdlStartTextInput_t)(struct SDL_Window *, SDL_PropertiesID);
typedef bool (*sdlStopTextInput_t)(struct SDL_Window *);
typedef bool (*sdlRunOnMainThread_t)(SDL_MainThreadCallback, void *, bool);

static void sdlTextInputMainThreadCallback(void *userdata) {
    void *handle = dlopen("libSDL3.so", RTLD_NOW);
    if (handle == NULL) return;
    struct SDL_Window *window = sdlhookGetPrimaryWindow();
    if (window == NULL) return;
    if (userdata != NULL) {
        sdlStartTextInput_t start = (sdlStartTextInput_t) dlsym(handle, "SDL_StartTextInput");
        if (start != NULL) start(window, 0);
        else LOG_TO_E("SDL_Hook: sdlTextInputMainThreadCallback: SDL_StartTextInput not found");
    } else {
        sdlStopTextInput_t stop = (sdlStopTextInput_t) dlsym(handle, "SDL_StopTextInput");
        if (stop != NULL) stop(window);
        else LOG_TO_E("SDL_Hook: sdlTextInputMainThreadCallback: SDL_StopTextInput not found");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_endiq_turtlelauncher_game_sdl_SdlBridge_setNativeTextInputActiveNative(JNIEnv *env, jclass clazz, jboolean active) {
    (void) env;
    (void) clazz;
    if (!sdlhookHasPrimaryWindow()) {
        LOG_TO_W("SDL_Hook: setNativeTextInputActive: no SDL window (SDL render path inactive)");
        return JNI_FALSE;
    }
    void *handle = dlopen("libSDL3.so", RTLD_NOW);
    if (handle == NULL) {
        LOG_TO_E("SDL_Hook: setNativeTextInputActive: libSDL3.so dlopen failed");
        return JNI_FALSE;
    }
    sdlRunOnMainThread_t runOnMain = (sdlRunOnMainThread_t) dlsym(handle, "SDL_RunOnMainThread");
    if (runOnMain == NULL) {
        LOG_TO_E("SDL_Hook: setNativeTextInputActive: SDL_RunOnMainThread not found");
        return JNI_FALSE;
    }
    // SDL3 text-input APIs must run on the window thread; dispatch through
    // SDL_RunOnMainThread without waiting — activation is mirrored back to the
    // Java layer asynchronously by SDL's showTextInput callback.
    bool result = runOnMain(sdlTextInputMainThreadCallback, (void *) (intptr_t) (active ? 1 : 0), false);
    if (!result) {
        LOG_TO_E("SDL_Hook: setNativeTextInputActive: SDL_RunOnMainThread dispatch failed");
    }
    return result ? JNI_TRUE : JNI_FALSE;
}
