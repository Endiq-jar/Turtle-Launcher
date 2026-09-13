#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <environ/environ.h>
#include <unistd.h>
#include "gl_bridge.h"
#include "egl_loader.h"

//
// Created by maks on 17.09.2022.
//
// TurtleLauncher: EGL improvements (config scoring, error logging, front-buffer/
// low-latency rendering, adaptive vsync, FORCE_VSYNC) added on top of the
// original bridge - search "TurtleLauncher:" for the specific additions.
//

static const char* g_LogTag = "GLBridge";
static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;

// TurtleLauncher: extension support, detected once in gl_log_egl_info() right
// after eglInitialize. Everything downstream checks these instead of calling
// eglQueryString repeatedly or, worse, assuming an extension is there.
static bool g_HasSwapControlTear = false;          // EGL_EXT_swap_control_tear -> adaptive vsync
static bool g_HasMutableRenderBuffer = false;       // EGL_KHR_mutable_render_buffer -> can flip to EGL_SINGLE_BUFFER after creation
static bool g_HasFrontBufferAutoRefresh = false;    // EGL_ANDROID_front_buffer_auto_refresh -> driver keeps the front buffer fresh without an explicit swap
static bool g_LowLatencyChecked = false;
static bool g_LowLatencyRequested = false;

/**
 * TurtleLauncher: translates an eglGetError() code into something actually
 * readable in logcat instead of a bare hex value you have to go look up.
 */
static void log_egl_error(const char* operation) {
    EGLint error = eglGetError_p();
    const char* description;
    switch (error) {
        case EGL_SUCCESS: return; // nothing to log
        case EGL_NOT_INITIALIZED: description = "EGL_NOT_INITIALIZED"; break;
        case EGL_BAD_ACCESS: description = "EGL_BAD_ACCESS"; break;
        case EGL_BAD_ALLOC: description = "EGL_BAD_ALLOC (out of memory)"; break;
        case EGL_BAD_ATTRIBUTE: description = "EGL_BAD_ATTRIBUTE"; break;
        case EGL_BAD_CONTEXT: description = "EGL_BAD_CONTEXT"; break;
        case EGL_BAD_CONFIG: description = "EGL_BAD_CONFIG"; break;
        case EGL_BAD_CURRENT_SURFACE: description = "EGL_BAD_CURRENT_SURFACE"; break;
        case EGL_BAD_DISPLAY: description = "EGL_BAD_DISPLAY"; break;
        case EGL_BAD_SURFACE: description = "EGL_BAD_SURFACE"; break;
        case EGL_BAD_MATCH: description = "EGL_BAD_MATCH (incompatible arguments)"; break;
        case EGL_BAD_PARAMETER: description = "EGL_BAD_PARAMETER"; break;
        case EGL_BAD_NATIVE_PIXMAP: description = "EGL_BAD_NATIVE_PIXMAP"; break;
        case EGL_BAD_NATIVE_WINDOW: description = "EGL_BAD_NATIVE_WINDOW"; break;
        case EGL_CONTEXT_LOST: description = "EGL_CONTEXT_LOST (driver died, likely a GPU reset)"; break;
        default: description = "unknown EGL error"; break;
    }
    __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s failed: %s (0x%04x)", operation, description, error);
}

/**
 * TurtleLauncher: queries EGL_VENDOR/EGL_VERSION plus the extensions we care
 * about, once, right after eglInitialize. This both gives us something useful
 * in logcat when someone reports a renderer bug on an unfamiliar GPU, and
 * caches the extension support flags everything else below reads.
 */
void gl_log_egl_info() {
    const char* vendor = eglQueryString_p ? eglQueryString_p(g_EglDisplay, EGL_VENDOR) : NULL;
    const char* version = eglQueryString_p ? eglQueryString_p(g_EglDisplay, EGL_VERSION) : NULL;
    const char* extensions = eglQueryString_p ? eglQueryString_p(g_EglDisplay, EGL_EXTENSIONS) : NULL;

    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "EGL vendor: %s, version: %s",
                        vendor ? vendor : "(unknown)", version ? version : "(unknown)");

    if (extensions != NULL) {
        g_HasSwapControlTear = strstr(extensions, "EGL_EXT_swap_control_tear") != NULL;
        g_HasMutableRenderBuffer = strstr(extensions, "EGL_KHR_mutable_render_buffer") != NULL;
        g_HasFrontBufferAutoRefresh = strstr(extensions, "EGL_ANDROID_front_buffer_auto_refresh") != NULL;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "EGL extensions of interest - adaptive vsync: %s, mutable render buffer: %s, front-buffer auto-refresh: %s",
                            g_HasSwapControlTear ? "yes" : "no",
                            g_HasMutableRenderBuffer ? "yes" : "no",
                            g_HasFrontBufferAutoRefresh ? "yes" : "no");
    } else {
        __android_log_print(ANDROID_LOG_WARN, g_LogTag, "eglQueryString(EGL_EXTENSIONS) returned NULL - assuming no optional extension support");
    }
}

bool gl_low_latency_requested() {
    if (!g_LowLatencyChecked) {
        const char* env = getenv("POJAV_LOW_LATENCY_RENDERING");
        g_LowLatencyRequested = env != NULL && !strcmp(env, "1");
        g_LowLatencyChecked = true;
    }
    return g_LowLatencyRequested;
}

bool gl_init() {
    dlsym_EGL();
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);

    if (g_EglDisplay == EGL_NO_DISPLAY)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s",
                            "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "eglInitialize_p() failed: %04x",
                            eglGetError_p());
        return false;
    }
    gl_log_egl_info();
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if (currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if (!result_width || !result_height) goto zero;
    return;

    zero:
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_RED_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_DEPTH_SIZE, 24,
                    EGL_SURFACE_TYPE,
                    EGL_WINDOW_BIT|EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE,
                    EGL_OPENGL_ES2_BIT,
                    EGL_NONE
                    };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE)
    {
        log_egl_error("eglChooseConfig (counting)");
        free(bundle);
        return NULL;
    }

    if (num_configs == 0)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "%s",
                            "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    // TurtleLauncher: EGL config scoring. eglChooseConfig() itself already sorts
    // candidates per the EGL spec's own rules, but that ordering doesn't know
    // anything about a "caveat" config being a slow/emulated fallback, and
    // doesn't care whether EGL_SWAP_BEHAVIOR_PRESERVED_BIT is available (some
    // drivers use that as a signal to keep the previous frame's contents around
    // instead of re-rendering it, which is the closest thing EGL exposes to a
    // "buffering strategy" knob). We pull every matching config and pick the
    // best-scoring one ourselves instead of blindly trusting index 0.
    EGLint configCount = num_configs;
    if (configCount > 32) configCount = 32; // sane ceiling, no device has this many distinct configs anyway
    EGLConfig candidates[32];
    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, candidates, configCount, &num_configs) != EGL_TRUE || num_configs == 0)
    {
        log_egl_error("eglChooseConfig (fetching candidates)");
        free(bundle);
        return NULL;
    }

    EGLConfig bestConfig = candidates[0];
    int bestScore = -1;
    for (EGLint i = 0; i < num_configs; i++) {
        EGLint caveat = EGL_NONE, surfaceType = 0;
        eglGetConfigAttrib_p(g_EglDisplay, candidates[i], EGL_CONFIG_CAVEAT, &caveat);
        eglGetConfigAttrib_p(g_EglDisplay, candidates[i], EGL_SURFACE_TYPE, &surfaceType);

        int score = 0;
        if (caveat == EGL_NONE) score += 10;                                  // not a slow/emulated fallback config
        if (surfaceType & EGL_SWAP_BEHAVIOR_PRESERVED_BIT) score += 1;        // buffer-preservation available if we ever want it
        if (score > bestScore) {
            bestScore = score;
            bestConfig = candidates[i];
        }
    }
    bundle->config = bestConfig;
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        EGLBoolean bindResult;

        if (!strncmp(getenv("POJAV_RENDERER"), "opengles3_desktopgl", 19))
        {
            printf("EGLBridge: Binding to OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) log_egl_error("eglBindAPI");
    }

    int libgl_es = strtol(getenv("LIBGL_ES"), NULL, 0);
    if (libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT)
    {
        log_egl_error("eglCreateContext");
        free(bundle);
        return NULL;
    }
    if (share != NULL) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Created context %p sharing objects with %p", bundle->context, share->context);
    }
    return bundle;
}

/**
 * TurtleLauncher: front-buffer / low-latency rendering. Only does anything if
 * POJAV_LOW_LATENCY_RENDERING=1 was set before launch (see gl_low_latency_
 * requested()). Two-step, both gated on the driver actually advertising
 * support - this is a latency/tearing trade-off, not something to force on a
 * driver that doesn't handle it well:
 *   1. EGL_KHR_mutable_render_buffer lets us flip an already-created surface
 *      to EGL_SINGLE_BUFFER - rendering goes straight to what's on screen
 *      instead of waiting on the swap chain.
 *   2. EGL_ANDROID_front_buffer_auto_refresh on top of that tells the
 *      compositor to keep re-presenting the front buffer automatically, which
 *      is what actually makes single-buffered rendering look right instead of
 *      needing an explicit (and self-defeating) eglSwapBuffers per change.
 * No-ops entirely, silently, if either the setting is off or the extensions
 * aren't there - falls back to the normal double-buffered surface as-is.
 */
static void apply_low_latency_mode(EGLSurface surface) {
    if (!gl_low_latency_requested()) return;
    if (!g_HasMutableRenderBuffer || eglSurfaceAttrib_p == NULL) {
        __android_log_print(ANDROID_LOG_WARN, g_LogTag,
                            "POJAV_LOW_LATENCY_RENDERING was requested but EGL_KHR_mutable_render_buffer isn't available on this device - staying double-buffered");
        return;
    }
    if (!eglSurfaceAttrib_p(g_EglDisplay, surface, EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER)) {
        log_egl_error("eglSurfaceAttrib(EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER)");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Low-latency rendering enabled: surface is now single-buffered");

    if (g_HasFrontBufferAutoRefresh) {
        if (eglSurfaceAttrib_p(g_EglDisplay, surface, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL_TRUE)) {
            __android_log_print(ANDROID_LOG_INFO, g_LogTag, "EGL_ANDROID_front_buffer_auto_refresh enabled on top of single-buffering");
        } else {
            log_egl_error("eglSurfaceAttrib(EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID)");
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, g_LogTag,
                            "EGL_ANDROID_front_buffer_auto_refresh isn't available - single-buffered without it may need explicit swaps to stay fresh");
    }
}

void gl_swap_surface(gl_render_window_t* bundle) {
    // 有新 Surface 待切换，这里直接切换
    if (bundle->newNativeSurface != NULL)
    {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
        if (bundle->surface == EGL_NO_SURFACE) {
            log_egl_error("eglCreateWindowSurface");
        } else {
            apply_low_latency_mode(bundle->surface);
        }
        return;
    }

    /*
     * In some cases (see MinecraftGLSurface.start(), android kills the surface automatically for
     * us, if we try to release/destroy it, we SIGSEGV. Check if we are -19x-19 or some other
     * invalid value and skip the release because Android decided to handle releasing it for us.
     * This goes against every piece of documentation I have ever seen but who actually reads those?
     *
     * Some drivers take forever to properly destroy the surface, they do it part at a time or
     * some other garbage while SIGSEGVing us if we try releasing while they're in the middle of
     * turning the surface dead. This makes the width and height make it look valid when it actually
     * isn't so we wait for them and hope there is no race condition of both us and Android trying
     * to release the surface. This seems driver dependent as AVD and Waydroid do not need 0.75s
     * to set the bloody height and width to their proper values. They just do it, instantly.
     */
    usleep(750000); // An overkill amount of time to wait for a surface to finish dying
    int32_t nativeWindowWidth = ANativeWindow_getWidth(pojav_environ->turtleWindow);
    int32_t nativeWindowHeight = ANativeWindow_getHeight(pojav_environ->turtleWindow);
    if ((nativeWindowWidth > 0) || (nativeWindowHeight > 0)) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Native surface dimensions (%d x %d)\n",
                            nativeWindowWidth, nativeWindowHeight);
        if (bundle->nativeSurface != NULL) {
            ANativeWindow_release(bundle->nativeSurface);
        }
        if (bundle->surface != NULL) eglDestroySurface_p(g_EglDisplay, bundle->surface);
    } else {
        __android_log_print(ANDROID_LOG_WARN, g_LogTag,
                            "Native surface dimensions (%d x %d) are invalid! Assuming android has already released window.\n",
                            nativeWindowWidth, nativeWindowHeight);
    }

    // 无新窗口可用，回退到 1x1 pbuffer 避免渲染彻底中断
    __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "No new native surface, switching to 1x1 pbuffer");
    bundle->nativeSurface = NULL;
    const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
    bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
}

void gl_make_current(gl_render_window_t* bundle) {

    if (bundle == NULL)
    {
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT))
        {
            currentBundle = NULL;
        }
        return;
    }

    bool hasSetMainWindow = false;
    if (pojav_environ->mainWindowBundle == NULL)
    {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->turtleWindow;
        hasSetMainWindow = true;
    }

    if (bundle->surface == NULL)
        gl_swap_surface(bundle);

    if (eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context))
    {
        currentBundle = bundle;
    } else {
        if (hasSetMainWindow)
        {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

void gl_swap_buffers() {
    if (currentBundle->state == STATE_RENDERER_NEW_WINDOW)
    {
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }

    if (currentBundle->surface != NULL)
        if (!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface) && eglGetError_p() == EGL_BAD_SURFACE)
        {
            eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            currentBundle->newNativeSurface = NULL;
            gl_swap_surface(currentBundle);
            eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
            // 清理过期状态，避免下一帧重复进入 gl_swap_surface 导致回退到 1×1 pbuffer
            if (currentBundle->nativeSurface != NULL && currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
                currentBundle->state = STATE_RENDERER_ALIVE;
            }
            __android_log_print(ANDROID_LOG_INFO, g_LogTag, "The window has died, awaiting window change");
        }

}

void gl_setup_window() {
    if (pojav_environ->mainWindowBundle != NULL)
    {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->turtleWindow;
    }
}

/**
 * TurtleLauncher: FORCE_VSYNC (set from AllSettings.getForceVsync() in
 * JREUtils.setJavaEnv) previously had no native-side reader at all - the
 * setting existed in the UI and got put into the launch environment, but
 * nothing ever consumed it, so toggling it did nothing. This is now the
 * consumer: when set, it overrides whatever interval Minecraft's own video
 * settings asked for. POJAV_ADAPTIVE_VSYNC additionally requests adaptive
 * vsync (EGL_EXT_swap_control_tear, interval -1) instead of a flat on/off,
 * falling back to regular vsync if the driver doesn't advertise the
 * extension - adaptive vsync only means anything once already syncing.
 */
void gl_swap_interval(int swapInterval) {
    const char *renderer = getenv("POJAV_RENDERER");
    if (renderer && !strcmp(renderer, "opengles3_desktopgl_zink_kopper") && !getenv("POJAV_VSYNC_IN_ZINK")) {
        return;
    }

    const char* forceVsync = getenv("FORCE_VSYNC");
    if (forceVsync != NULL && !strcmp(forceVsync, "true")) {
        swapInterval = 1;
    }

    if (swapInterval != 0) {
        const char* adaptive = getenv("POJAV_ADAPTIVE_VSYNC");
        if (adaptive != NULL && !strcmp(adaptive, "1")) {
            if (g_HasSwapControlTear) {
                swapInterval = -1;
            } else {
                __android_log_print(ANDROID_LOG_WARN, g_LogTag,
                                    "POJAV_ADAPTIVE_VSYNC was requested but EGL_EXT_swap_control_tear isn't available - using regular vsync instead");
            }
        }
    }

    if (!eglSwapInterval_p(g_EglDisplay, swapInterval)) {
        log_egl_error("eglSwapInterval");
    }
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}

