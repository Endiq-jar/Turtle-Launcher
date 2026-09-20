//
// Amethyst-compatible SDL3 JNI_OnLoad guard.
//
// SDL3 is intentionally initialized once from ART.  When LWJGL resolves the
// same library from the embedded game JVM, allowing SDL3's JNI_OnLoad to run a
// second time makes its Android JNI globals belong to the wrong VM.  Intercept
// only dlsym("JNI_OnLoad") for the SDL3 handle: ART still calls the original
// JNI_OnLoad, while the guest VM receives a valid JNI version without running
// Android registration a second time.
//
// Do not hook dlopen here.  Amethyst found that dlopen hooks can crash the
// Turnip linker.  The existing LWJGL ndlopen bridge handles library loading;
// this hook only observes dlsym and is safe on the affected devices.
//

#include "environ/environ.h"
#include "native_hooks.h"

#include <android/log.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <string.h>

// The JNI_OnLoad function exported by SDL3.
typedef jint (*jni_onload_func)(JavaVM *vm, void *reserved);
typedef void *(*dlsym_func)(void *handle, const char *symbol);

static void *sdl3_handle;
static jni_onload_func original_sdl3_jni_onload;

static jint guest_sdl3_jni_onload(JavaVM *vm, void *reserved) {
    // ART owns SDL's Android Java registration.  A JNI_OnLoad from ART must
    // still go through the real function; only the embedded JRE is skipped.
    if (pojav_environ != NULL
            && vm == pojav_environ->dalvikJavaVMPtr
            && original_sdl3_jni_onload != NULL) {
        return original_sdl3_jni_onload(vm, reserved);
    }
    return JNI_VERSION_1_4;
}

static void *custom_dlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(custom_dlsym, dlsym_func, handle, symbol);
    BYTEHOOK_POP_STACK();

    if (sdl3_handle == NULL) {
        // RTLD_NOLOAD is important: this must never load SDL3 in the hook just
        // to inspect it, because that would defeat the ART-first ordering.
        sdl3_handle = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_NOW);
    }

    if (sdl3_handle != NULL
            && handle == sdl3_handle
            && symbol != NULL
            && strcmp(symbol, "JNI_OnLoad") == 0
            && result != NULL) {
        original_sdl3_jni_onload = (jni_onload_func) result;
        __android_log_print(ANDROID_LOG_INFO, "TurtleSDL3",
                "Amethyst SDL3 JNI_OnLoad guard installed: %p", result);
        return (void *) &guest_sdl3_jni_onload;
    }
    return result;
}

void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    if (bytehook_hook_all_p == NULL) return;
    bytehook_stub_t stub = bytehook_hook_all_p(
            NULL,
            "dlsym",
            (void *) &custom_dlsym,
            NULL,
            NULL);
    __android_log_print(ANDROID_LOG_INFO, "TurtleSDL3",
            "Amethyst SDL dlsym hook installed: %p", stub);
}
