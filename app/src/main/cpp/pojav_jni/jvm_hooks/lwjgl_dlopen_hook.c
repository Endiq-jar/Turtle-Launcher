//
// Created by maks on 06.01.2025.
//

#include "jvm_hooks.h"

#include <android/api-level.h>

#include "environ/environ.h"

#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>

#define TAG __FILE_NAME__
#include <log.h>
#include "ctxbridges/osm_bridge.h"


extern void* maybe_load_vulkan();

/**
 * Basically a verbatim implementation of ndlopen(), found at
 * https://github.com/PojavLauncherTeam/lwjgl3/blob/3.3.1/modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_DynamicLinkLoader.c#L11
 * but with our own additions for stuff like vulkanmod.
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                     __attribute__((unused)) jclass class,
                     jlong filename_ptr,
                     jint jmode) {
    const char* filename = (const char*) filename_ptr;
    LOGI("LWJGL ndlopen: \"%s\" mode=0x%x", filename, (int)jmode);

    // Oveeride vulkan loading to let us load vulkan ourselves
    if(strstr(filename, "libvulkan.so") == filename) {
        printf("LWJGL linkerhook: replacing load for libvulkan.so with custom driver\n");
        return (jlong) maybe_load_vulkan();
    }

    // This hook also serves the task of mitigating a bug: the idea is that since, on Android 10 and
    // earlier, the linker doesn't really do namespace nesting.
    // It is not a problem as most of the libraries are in the launcher path, but when you try to run
    // VulkanMod which loads shaderc outside of the default jni libs directory through this method,
    // it can't load it because the path is not in the allowed paths for the anonymous namesapce.
    // This method fixes the issue by being in libpojavexec, and thus being in the classloader namespace

    int mode = (int)jmode;
    return (jlong) dlopen(filename, mode);
}

/**
 * ndlsym hook — needed to redirect glGetString to our cross-thread wrapper
 * when OSMesa (Zink) is the active renderer. Without this LWJGL's
 * GL.createCapabilities() may run on a thread without OSMesa context bound.
 */
static jlong ndlsym_hook(__attribute__((unused)) JNIEnv *env,
                         __attribute__((unused)) jclass class,
                         jlong handle_ptr, jlong name_ptr) {
    void* handle = (void*)(uintptr_t)handle_ptr;
    const char* name = (const char*)(uintptr_t)name_ptr;
    void* result = dlsym(handle, name);

    LOGI("ndlsym_hook: name=\"%s\" handle=%p result=%p",
         name ? name : "(null)", handle, result);

    if (name == NULL) {
        return (jlong)(uintptr_t)result;
    }

    // OSMesaGetProcAddress redirect — LWJGL이 이걸로 glGetString lookup하니까
    if (result != NULL && strcmp(name, "OSMesaGetProcAddress") == 0) {
        LOGI("ndlsym_hook: redirect OSMesaGetProcAddress -> wrapped_OSMesaGetProcAddress");
        return (jlong)(uintptr_t)wrapped_OSMesaGetProcAddress;
    }

    // 직접 ndlsym("glGetString") 케이스도 커버 (LWJGL이 fallback할 때)
    if (result != NULL && real_glGetString != NULL
        && strcmp(name, "glGetString") == 0) {
        LOGI("LWJGL ndlsym: redirect glGetString -> wrapped_glGetString (raw=%p)", result);
        return (jlong)(uintptr_t)wrapped_glGetString;
    }

    return (jlong)(uintptr_t)result;
}

/**
 * Install the LWJGL dlopen hook. This allows us to mitigate linker bugs and add custom library overrides.
 */
void installLwjglDlopenHook(JNIEnv *env) {
    LOGI("Installing LWJGL dlopen() hook");
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if(dynamicLinkLoader == NULL) {
        LOGE("Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 1) != 0) {
        LOGE("Failed to register ndlopen hook");
        (*env)->ExceptionClear(env);
    } else {
        LOGI("Registered ndlopen hook OK");
    }
    JNINativeMethod ndlsymMethod[] = {
            {"ndlsym",  "(JJ)J", &ndlsym_hook}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlsymMethod, 1) != 0) {
        LOGE("Failed to register ndlsym hook -- signature mismatch?");
        (*env)->ExceptionClear(env);
    } else {
        LOGI("Registered ndlsym hook OK");
    }
}