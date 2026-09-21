//
// Amethyst-compatible SDL3 launcher integration hook.
//
// Minecraft 26.3 opens SDL from the embedded OpenJDK VM.  The Android SDL
// frontend, however, must be registered in ART before SDL creates its video
// window.  Amethyst solves that race by notifying the launcher from the first
// SDL_InitSubSystem call.  Keep the hook small: it only asks the existing
// CallbackBridge to prepare SDL, then calls SDL's original function.
//
// The hook is intentionally best-effort.  If the bridge is unavailable, SDL's
// own return value and error handling remain unchanged.
//

#include "environ/environ.h"
#include "native_hooks.h"

#include <android/log.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#define TURTLE_SDL_NOTIFICATION_TYPE 0
#define TURTLE_SDL_ACTION_INIT 0

// SDL3 declares SDL_InitSubSystem as bool SDL_InitSubSystem(SDL_InitFlags).
// uint32_t has the same ABI and avoids requiring SDL's headers in the launcher
// native build (the Android SDL headers are bundled with the game version).
typedef bool (*sdl_init_subsystem_func)(uint32_t flags);

typedef void (*sdl_set_hint_func)(const char *name, const char *value);

static void notify_launcher_before_sdl_init(uint32_t flags) {
    if (pojav_environ == NULL
            || pojav_environ->dalvikJavaVMPtr == NULL
            || pojav_environ->bridgeClazz == NULL
            || pojav_environ->method_notifyLauncher == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "TurtleSDL3",
                "SDL launcher notification skipped: ART bridge is not ready");
        return;
    }

    JNIEnv *env = NULL;
    JavaVM *vm = pojav_environ->dalvikJavaVMPtr;
    jint env_result = (*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_4);
    if (env_result == JNI_EDETACHED) {
        env_result = (*vm)->AttachCurrentThread(vm, &env, NULL);
    }
    if (env_result != JNI_OK || env == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "TurtleSDL3",
                "SDL launcher notification could not attach to ART (%d)", env_result);
        return;
    }

    jint action[2];
    action[0] = TURTLE_SDL_ACTION_INIT;
    action[1] = flags > INT32_MAX ? -1 : (jint) flags;
    jintArray action_array = (*env)->NewIntArray(env, 2);
    if (action_array == NULL) return;
    (*env)->SetIntArrayRegion(env, action_array, 0, 2, action);
    (*env)->CallStaticBooleanMethod(env, pojav_environ->bridgeClazz,
            pojav_environ->method_notifyLauncher,
            TURTLE_SDL_NOTIFICATION_TYPE, action_array);
    (*env)->DeleteLocalRef(env, action_array);

    if ((*env)->ExceptionCheck(env)) {
        __android_log_print(ANDROID_LOG_WARN, "TurtleSDL3",
                "CallbackBridge.notifyLauncher threw while preparing SDL");
        (*env)->ExceptionClear(env);
    }
}

static bool custom_sdl_init_subsystem(uint32_t flags) {
    notify_launcher_before_sdl_init(flags);

    // Amethyst also applies the keyboard hint at this boundary.  It is harmless
    // for vanilla 26.3 and prevents SDL from reopening the IME on Return.
    void *sdl_handle = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_NOW);
    if (sdl_handle != NULL) {
        sdl_set_hint_func set_hint =
                (sdl_set_hint_func) dlsym(sdl_handle, "SDL_SetHint");
        if (set_hint != NULL) {
            set_hint("SDL_RETURN_KEY_HIDES_IME", "true");
        }
        dlclose(sdl_handle);
    }

    bool result = BYTEHOOK_CALL_PREV(
            custom_sdl_init_subsystem,
            sdl_init_subsystem_func,
            flags);
    BYTEHOOK_POP_STACK();
    return result;
}

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    if (bytehook_hook_all_p == NULL) return;
    bytehook_stub_t stub = bytehook_hook_all_p(
            NULL,
            "SDL_InitSubSystem",
            (void *) &custom_sdl_init_subsystem,
            NULL,
            NULL);
    __android_log_print(ANDROID_LOG_INFO, "TurtleSDL3",
            "Amethyst SDL_InitSubSystem hook installed: %p", stub);
}
