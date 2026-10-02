// Turtle Launcher - SDL3 compatibility hooks for Minecraft 26.3+ (RenderPearl/SDL backend).
//
// What was crashing:
//   MC 26.3-snapshot-4+ replaced GLFW with SDL3. On Android that crashes in several
//   places unless the launcher interposes on SDL, because the game initializes SDL
//   the way a desktop app would:
//     1. RenderPearl creates a hidden tool window, then the real main window. SDL's
//        Android backend only supports ONE window per process, so the second
//        SDL_CreateWindow fails and the game dies during backend init (at the Mojang
//        logo). Fix: transparently reuse the first window for every later request.
//     2. The game requests a DESKTOP GL profile, but every mobile renderer
//        (MobileGlues, GL4ES, ...) is an OpenGL ES implementation whose host EGL
//        rejects desktop-style eglChooseConfig/eglCreateContext attributes. Fix:
//        force the ES profile before window creation and normalize EGL attributes
//        (with original-request-first retry semantics).
//     3. RenderPearl requires SDL and LWJGL to use the SAME Vulkan loader instance
//        (it compares vkGetInstanceProcAddr pointers). On Turnip devices the launcher
//        redirects LWJGL to a private loader copy (VULKAN_PTR), which SDL cannot see
//        when it loads by path. Fix: hand SDL the same handle, and ignore SDL's
//        unload of it.
//     4. MC 26.3-snapshot-8+ sets SDL_ENABLE_SCREEN_KEYBOARD=0 (desktop convention),
//        which permanently disables the soft keyboard on mobile. Fix: override the
//        hint back to 1 at SDL_Init time.
//     5. SDL_UpdateMouseFocus can clear mouse focus when the (resolution-scaled)
//        virtual-mouse coordinates fall outside the SDL window. Fix: fall back to the
//        last successfully resolved window (there is only ever one on Android).
//
// How it is hooked:
//   LWJGL's org.lwjgl.sdl binding resolves every SDL symbol with dlsym() and calls
//   the returned pointer directly, so plain PLT/GOT patching (bytehook_hook_all)
//   alone cannot see those calls. Like upstream, this module therefore hooks BOTH
//   layers: bytehook_hook_all() for normally-linked callers, plus a global dlsym()
//   hook that swaps SDL symbols for proxy functions at resolve time.
//
// This file is a self-contained port of ZalithLauncher2's sdl_hook.c
// (https://github.com/ZalithLauncher/ZalithLauncher2/pull/1721, GPL-3.0, itself
// referencing AngelAuraMC/Amethyst-Android and FCL-Team/FoldCraftLauncher), adapted
// so it does NOT depend on libpojavexec internals:
//   * No pojav_environ access. The ART-side SDL init that upstream triggers from the
//     SDL_Init hook (notifyLauncher) is already done eagerly by Turtle's
//     SdlAndroidJniPrep.setup() before the JVM starts, so the hook only applies the
//     SDL hints here.
//   * No link dependency on libpojavexec. The FPS counter (calculateFPS) is resolved
//     lazily with dlopen(RTLD_NOLOAD)+dlsym and simply skipped when unavailable.
//   * The primary-window pointer is tracked locally in this module and exposed to
//     Java through SdlBridge.isSdlRenderActive() / setNativeTextInputActive().

#include <stdbool.h>
#include <stdint.h>

#include "sdl_hook.h"
#include "sdl_log.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

// --- Minimal SDL3 declarations (only what the hooks need; the full headers live
// --- on the lwjgl-sdl binding side) ---
typedef uint32_t SDL_InitFlags;
typedef struct SDL_Window SDL_Window;
typedef struct SDL_Rect { int x, y, w, h; } SDL_Rect;

bool SDL_InitSubSystem(SDL_InitFlags flags);
bool SDL_SetHint(const char *name, const char *value);
bool SDL_SetTextInputArea(SDL_Window *window, const SDL_Rect *rect, int cursor);
void SDL_SetError(const char *fmt, ...);
const char *SDL_GetError(void);
SDL_Window *SDL_GetWindowFromEvent(const void *event);
SDL_Window *SDL_GetWindowFromID(uint32_t id);
bool SDL_GL_SetAttribute(int attr, int value);
void *SDL_LoadObject(const char *path);
void SDL_UnloadObject(void *handle);
void *SDL_LoadFunction(void *handle, const char *name);
SDL_Window *SDL_CreateWindow(const char *title, int w, int h, uint32_t flags);
SDL_Window *SDL_CreateWindowWithProperties(uint32_t props);
void SDL_DestroyWindow(SDL_Window *window);
void *SDL_EGL_GetProcAddress(const char *proc);

DECL_DLSYM(SDL_InitSubSystem)
DECL_DLSYM(SDL_SetHint);
DECL_DLSYM(SDL_SetTextInputArea);
DECL_DLSYM(SDL_SetError);
DECL_DLSYM(SDL_GetError);
DECL_DLSYM(SDL_GetWindowFromEvent)
DECL_DLSYM(SDL_GetWindowFromID)
DECL_DLSYM(SDL_GL_SetAttribute)
DECL_DLSYM(SDL_LoadObject)
DECL_DLSYM(SDL_UnloadObject)
DECL_DLSYM(SDL_LoadFunction)
DECL_DLSYM(SDL_CreateWindow)
DECL_DLSYM(SDL_CreateWindowWithProperties)
DECL_DLSYM(SDL_DestroyWindow)
DECL_DLSYM(SDL_EGL_GetProcAddress)

typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef int EGLint;
typedef int EGLBoolean;
typedef EGLBoolean (*eglChooseConfig_t)(EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs,
                                        EGLint config_size, EGLint *num_config);
typedef void *(*eglCreateContext_t)(EGLDisplay dpy, EGLConfig config, void *share, const EGLint *attrib_list);
typedef EGLBoolean (*eglSwapBuffers_t)(EGLDisplay dpy, void *surface);

// EGL constants (from EGL/egl.h), to avoid pulling in the full EGL headers.
#define EGL_NONE              0x3038
#define EGL_RENDERABLE_TYPE   0x3040
#define EGL_OPENGL_BIT        0x0008
#define EGL_OPENGL_ES2_BIT    0x0004
#define EGL_OPENGL_ES3_BIT    0x0040
#define EGL_CONTEXT_CLIENT_VERSION    0x3098
#define EGL_CONTEXT_MAJOR_VERSION_KHR 0x30FB
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FC



// --- SDL event-window resolution fix ---
//
// In SDL's Android backend, mouse focus (mouse->focus) can be wrongly cleared by
// SDL_UpdateMouseFocus's out-of-bounds check (virtual-mouse coordinates scaled by
// the launcher resolution can exceed the SDL window size).
// Only one window ever exists on Android, so fall back to the last successfully
// resolved window when event resolution fails.

static SDL_Window *sdlLastEventWindow = NULL;

static SDL_Window *custom_SDL_GetWindowFromEvent_Func(const void *event) {
    SDL_Window *window = BYTEHOOK_CALL_PREV(custom_SDL_GetWindowFromEvent_Func, SDL_GetWindowFromEvent_t, event);
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    BYTEHOOK_POP_STACK();
    return window;
}

static SDL_Window *custom_SDL_GetWindowFromID_Func(uint32_t id) {
    SDL_Window *window = BYTEHOOK_CALL_PREV(custom_SDL_GetWindowFromID_Func, SDL_GetWindowFromID_t, id);
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    BYTEHOOK_POP_STACK();
    return window;
}

// --- SDL event-window resolution fix ---

// --- Host-EGL compatibility for SDL GL-context creation on mobile (ES) renderers ---

// Some host libEGL implementations reject RENDERABLE_TYPE carrying ES3_BIT /
// OPENGL_BIT; normalize to ES2_BIT. Only used for the compatibility retry after
// the preferred request fails.
static EGLBoolean normalizeEglChooseConfigList(const EGLint *attrib_list, EGLint *fixed, int cap) {
    if (attrib_list == NULL) return 0;
    int n = 0;
    for (int i = 0; n < cap - 2; i += 2) {
        EGLint attr = attrib_list[i];
        EGLint val = attrib_list[i + 1];
        if (attr == EGL_NONE) {
            fixed[n] = EGL_NONE;
            fixed[n + 1] = 0;
            n += 2;
            break;
        }
        if (attr == EGL_RENDERABLE_TYPE) {
            // Normalize to ES2_BIT.
            if ((val & (EGL_OPENGL_ES3_BIT | EGL_OPENGL_BIT)) != 0 && (val & EGL_OPENGL_ES2_BIT) == 0) {
                val = (val & ~(EGL_OPENGL_ES3_BIT | EGL_OPENGL_BIT)) | EGL_OPENGL_ES2_BIT;
            }
        }
        fixed[n] = attr;
        fixed[n + 1] = val;
        n += 2;
    }
    return n > 0;
}

// Strip KHR version attributes the host does not understand, producing the
// compatibility-retry table. Returns the requested major version (0 if none).
static int normalizeEglContextAttribs(const EGLint *attrib_list, EGLint *fixed, int cap, bool esSemantics) {
    int version = 0;
    bool hasClientVersion = false;
    if (attrib_list == NULL) return 0;
    int n = 0;
    for (int i = 0; n < cap - 2; i += 2) {
        EGLint attr = attrib_list[i];
        EGLint val = attrib_list[i + 1];
        if (attr == EGL_NONE) break;
        if (attr == EGL_CONTEXT_MAJOR_VERSION_KHR) { // Record the major version, then strip.
            if (version == 0) version = val;
            continue;
        }
        if (attr == EGL_CONTEXT_MINOR_VERSION_KHR) continue;
        if (attr == EGL_CONTEXT_CLIENT_VERSION) {
            hasClientVersion = true;
            if (version == 0) version = val;
        }
        if (n >= cap - 2) return 0;
        fixed[n++] = attr;
        fixed[n++] = val;
    }
    // Only write CLIENT_VERSION back under ES semantics (avoids degrading to the
    // driver default version); desktop semantics must NOT write it - desktop
    // contexts do not use CLIENT_VERSION.
    if (esSemantics && version > 0 && !hasClientVersion) {
        if (n >= cap - 2) return 0;
        fixed[n++] = EGL_CONTEXT_CLIENT_VERSION;
        fixed[n++] = version;
    }
    if (n >= cap - 2) return 0;
    fixed[n++] = EGL_NONE;
    fixed[n++] = 0;
    return version;
}

static bool isMobileGluesEgl(void) {
    const char *egl = getenv("POJAVEXEC_EGL");
    if (egl == NULL) return false;
    const char *base = strrchr(egl, '/');
    return strcmp(base != NULL ? base + 1 : egl, "libmobileglues.so") == 0;
}

// The GLES compat layer (forced ES profile, RENDERABLE_TYPE normalization, CV=2
// fallback) only applies to mobile ES renderers; desktop/OSMesa paths must never
// be forced into ES.
static bool sdlGlesCompatEnabled(void) {
    const char *renderer = getenv("POJAV_RENDERER");
    if (renderer == NULL) return isMobileGluesEgl();
    if (strstr(renderer, "desktopgl") != NULL) return false;
    if (strncmp(renderer, "gallium_", 8) == 0) return false; // OSMesa family
    if (strcmp(renderer, "custom_gallium") == 0 || strcmp(renderer, "vulkan_zink") == 0) return false;
    if (strncmp(renderer, "opengles", 8) == 0) return true; // Built-in GL4ES/NGGL4ES
    return isMobileGluesEgl(); // MobileGlues
}

static bool sForcedEsProfile = false;

static bool shouldReusePrimaryWindow(void) {
    const char *value = getenv("POJAV_SDL_REUSE_WINDOW");
    if (value != NULL) return strcmp(value, "1") == 0;
    return true;
}

// --- Taking over SDL's EGL function resolution ---
// The original pointers are pinned after the first successful resolution, so
// repeated resolutions against different handles can never mix loaders.
static eglChooseConfig_t sOrigEglChooseConfig = NULL;
static eglCreateContext_t sOrigEglCreateContext = NULL;
static eglSwapBuffers_t sOrigEglSwapBuffers = NULL;

// libpojavexec's FPS counter, resolved lazily. NULL-safe: when it cannot be
// resolved (unexpected libpojavexec build), frames simply are not counted.
typedef void (*calculate_fps_t)(void);
static calculate_fps_t sCalculateFps = NULL;
static bool sCalculateFpsProbed = false;

static void callCalculateFps(void) {
    if (!sCalculateFpsProbed) {
        sCalculateFpsProbed = true;
        void *handle = dlopen("libpojavexec.so", RTLD_NOLOAD | RTLD_LOCAL);
        if (handle != NULL) {
            sCalculateFps = (calculate_fps_t) dlsym(handle, "calculateFPS");
        }
        if (sCalculateFps == NULL) {
            LOG_TO_W("SDL_Hook: calculateFPS not available, SDL-path FPS counting disabled");
        }
    }
    if (sCalculateFps != NULL) sCalculateFps();
}

static void *proxyEglCreateContext(EGLDisplay dpy, EGLConfig config, void *share, const EGLint *attrib_list) {
    if (sOrigEglCreateContext == NULL) {
        LOG_TO_E("SDL_Hook: eglCreateContext was not resolved");
        return NULL;
    }

    void *ctx = sOrigEglCreateContext(dpy, config, share, attrib_list);
    if (ctx != NULL || !sdlGlesCompatEnabled()) return ctx;

    bool esSemantics = sForcedEsProfile;
    EGLint fixed[64];
    int version = normalizeEglContextAttribs(attrib_list, fixed, 64, esSemantics);
    if (version == 0) return ctx;

    LOG_TO_W("SDL_Hook: retrying eglCreateContext without KHR version attrs (CV=%d)", version);
    ctx = sOrigEglCreateContext(dpy, config, share, fixed);
    if (ctx != NULL || !esSemantics || version <= 2) return ctx; // CV=2 is the last mobile fallback.

    LOG_TO_W("SDL_Hook: retrying eglCreateContext with CV=2 after CV=%d failed", version);
    EGLint es2[3] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    return sOrigEglCreateContext(dpy, config, share, es2);
}

static EGLBoolean proxyEglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs,
                                       EGLint config_size, EGLint *num_config) {
    if (sOrigEglChooseConfig == NULL) {
        LOG_TO_E("SDL_Hook: eglChooseConfig was not resolved");
        return 0;
    }

    EGLBoolean result = sOrigEglChooseConfig(dpy, attrib_list, configs, config_size, num_config);
    if (result && num_config != NULL && *num_config > 0) return result;
    if (!sdlGlesCompatEnabled()) return result; // Compat fallback is mobile-ES-renderers only.

    EGLint fixed[64];
    if (!normalizeEglChooseConfigList(attrib_list, fixed, 64)) return result;
    EGLint fallbackCount = 0;
    EGLBoolean fallbackResult = sOrigEglChooseConfig(dpy, fixed, configs, config_size, &fallbackCount);
    if (fallbackResult && num_config != NULL) *num_config = fallbackCount;
    LOG_TO_W("SDL_Hook: eglChooseConfig compatibility fallback result=%d count=%d", fallbackResult, fallbackCount);
    return fallbackResult;
}

static EGLBoolean proxyEglSwapBuffers(EGLDisplay dpy, void *surface) {
    if (sOrigEglSwapBuffers == NULL) {
        LOG_TO_E("SDL_Hook: eglSwapBuffers was not resolved");
        return 0;
    }
    callCalculateFps();
    return sOrigEglSwapBuffers(dpy, surface);
}

// Single injection point for the EGL proxies, shared by the bytehook layer and
// the dlsym-proxy layer.
static void *injectEglProxy(const char *name, void *resolved) {
    if (name == NULL) return resolved;
    if (strcmp(name, "eglChooseConfig") == 0) {
        if (resolved != NULL && sOrigEglChooseConfig == NULL) sOrigEglChooseConfig = (eglChooseConfig_t) resolved; // Pin after first resolution.
        if (sOrigEglChooseConfig != NULL && resolved != (void *) proxyEglChooseConfig) resolved = (void *) proxyEglChooseConfig;
    } else if (strcmp(name, "eglCreateContext") == 0) {
        if (resolved != NULL && sOrigEglCreateContext == NULL) sOrigEglCreateContext = (eglCreateContext_t) resolved;
        if (sOrigEglCreateContext != NULL && resolved != (void *) proxyEglCreateContext) resolved = (void *) proxyEglCreateContext;
    } else if (strcmp(name, "eglSwapBuffers") == 0) {
        if (resolved != NULL && sOrigEglSwapBuffers == NULL) sOrigEglSwapBuffers = (eglSwapBuffers_t) resolved;
        if (sOrigEglSwapBuffers != NULL && resolved != (void *) proxyEglSwapBuffers) resolved = (void *) proxyEglSwapBuffers;
    }
    return resolved;
}

// SDL resolves EGL functions through SDL_LoadFunction and then calls them
// directly; injecting the proxies there makes the compatibility retries apply.
static void *custom_SDL_LoadFunction_Func(void *handle, const char *name) {
    void *r = BYTEHOOK_CALL_PREV(custom_SDL_LoadFunction_Func, SDL_LoadFunction_t, handle, name);
    BYTEHOOK_POP_STACK();
    return injectEglProxy(name, r);
}

// SDL's public EGL resolution entry point can bypass SDL_LoadFunction; give it
// the same proxies.
static void *custom_SDL_EGLGetProcAddress_Func(const char *proc) {
    void *r = BYTEHOOK_CALL_PREV(custom_SDL_EGLGetProcAddress_Func, SDL_EGL_GetProcAddress_t, proc);
    BYTEHOOK_POP_STACK();
    if (proc == NULL || r == NULL) return r;
    if (strcmp(proc, "eglChooseConfig") == 0) {
        if (sOrigEglChooseConfig == NULL) sOrigEglChooseConfig = (eglChooseConfig_t) r;
        if (r != (void *) proxyEglChooseConfig) r = (void *) proxyEglChooseConfig;
    } else if (strcmp(proc, "eglCreateContext") == 0) {
        if (sOrigEglCreateContext == NULL) sOrigEglCreateContext = (eglCreateContext_t) r;
        if (r != (void *) proxyEglCreateContext) r = (void *) proxyEglCreateContext;
    } else if (strcmp(proc, "eglSwapBuffers") == 0) {
        if (sOrigEglSwapBuffers == NULL) sOrigEglSwapBuffers = (eglSwapBuffers_t) r;
        if (r != (void *) proxyEglSwapBuffers) r = (void *) proxyEglSwapBuffers;
    }
    return r;
}

// --- Vulkan loader consistency ---
// The launcher redirects LWJGL's Vulkan handle to a loader copy in a private
// namespace (Turnip path; the handle is recorded in the VULKAN_PTR environment
// variable). Since MC 26.3, RenderPearl requires SDL and LWJGL to use the same
// loader instance (it validates that vkGetInstanceProcAddr pointers match), but
// SDL can only load by path and can never reach that private instance. So when
// SDL loads the Vulkan loader, hand back the VULKAN_PTR handle instead; the
// handle's refcount is owned by the launcher, so SDL-side unloads of it are
// ignored.
static void *custom_SDL_LoadObject_Func(const char *path) {
    if (path != NULL && strstr(path, "libvulkan") != NULL) {
        const char *vkptr = getenv("VULKAN_PTR");
        if (vkptr != NULL && vkptr[0] != '\0') {
            void *handle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
            if (handle != NULL) return handle;
        }
    }
    void *r = BYTEHOOK_CALL_PREV(custom_SDL_LoadObject_Func, SDL_LoadObject_t, path);
    BYTEHOOK_POP_STACK();
    return r;
}

static void custom_SDL_UnloadObject_Func(void *handle) {
    const char *vkptr = getenv("VULKAN_PTR");
    if (vkptr != NULL && vkptr[0] != '\0') {
        void *vulkan_handle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
        if (handle == vulkan_handle) return;
    }
    BYTEHOOK_CALL_PREV(custom_SDL_UnloadObject_Func, SDL_UnloadObject_t, handle);
    BYTEHOOK_POP_STACK();
}

// The first successfully created SDL window; later creation requests are
// redirected to it.
static SDL_Window *sPrimaryWindow = NULL;
// Logical refcount of the primary window: 1 when the real window is created,
// incremented on every reuse.
static unsigned int sPrimaryWindowRefs = 0;

// Exposed to Java (SdlBridge.isSdlRenderActive) so the launcher can tell the SDL
// render path (MC 26.3+, window created) apart from gamepad-subsystem-only SDL
// use (e.g. MC 26.2 with Controlify, no window).
bool sdlhookHasPrimaryWindow(void) {
    return sPrimaryWindow != NULL;
}

SDL_Window *sdlhookGetPrimaryWindow(void) {
    return sPrimaryWindow;
}

// Releases one logical reference of the primary window; returns true when a real
// destroy must run. While reuse references are still held, the real destroy is
// skipped so the reused window is never destroyed early (use-after-destroy crash).
static bool releasePrimaryWindow(SDL_Window *window) {
    if (window == sPrimaryWindow) {
        if (sPrimaryWindowRefs > 0) {
            sPrimaryWindowRefs--;
            LOG_TO_I("SDL_Hook: releasing logical window %p, refs=%u", window, sPrimaryWindowRefs);
            if (sPrimaryWindowRefs > 0) {
                if (window == sdlLastEventWindow) sdlLastEventWindow = NULL;
                return false;
            }
            sPrimaryWindow = NULL;
        }
        if (window == sdlLastEventWindow) sdlLastEventWindow = NULL;
    } else if (window == sdlLastEventWindow) {
        sdlLastEventWindow = NULL;
    }
    return true;
}

static void custom_SDL_DestroyWindow_Func(SDL_Window *window) {
    if (releasePrimaryWindow(window)) {
        BYTEHOOK_CALL_PREV(custom_SDL_DestroyWindow_Func, SDL_DestroyWindow_t, window);
    }
    BYTEHOOK_POP_STACK();
}

// Launcher-side preparation for SDL_InitSubSystem, shared by the bytehook layer
// and the dlsym-proxy layer.
//
// NOTE (Turtle adaptation): upstream notifies the ART side here to load SDL and
// set up JNI lazily. Turtle instead initializes the ART side eagerly in
// SdlAndroidJniPrep.setup() before the JVM starts (that is also what guarantees
// a single SDL3 instance shared with the game), so this hook only applies hints.
static bool sdlInitSubSystemPrepare(SDL_InitFlags flags) {
    (void) flags;
    // This is the normal for the launcher, the default in SDL is false.
    SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_SetHint);
    if (SDL_SetHint_p) SDL_SetHint_p("SDL_RETURN_KEY_HIDES_IME", "true");
    // FIXME: MobileGlues has issues with passing in the proper EGL params to make this work
    if (SDL_SetHint_p && isMobileGluesEgl()) {
        SDL_SetHint_p("SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER", "0");
    }
    // MC follows the desktop convention of setting SDL_ENABLE_SCREEN_KEYBOARD=0 to
    // disable the platform soft keyboard (using its self-drawn IME UI instead),
    // but mobile depends on SDL to bring up the input method; MC sets this hint
    // before SDL_Init, and this hook runs at SDL_Init, so override it back on.
    if (SDL_SetHint_p) SDL_SetHint_p("SDL_ENABLE_SCREEN_KEYBOARD", "1");
    return true;
}

static bool custom_SDL_InitSubSystem_Func(SDL_InitFlags flags) {
    if (!sdlInitSubSystemPrepare(flags)) return false;

    // Call original func after doing all the needed setup
    bool r = BYTEHOOK_CALL_PREV(custom_SDL_InitSubSystem_Func, SDL_InitSubSystem_t, flags);
    if (!r){
        SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_GetError);
        LOG_TO_E("SDL_Hook: SDL_InitSubsystem Error: %s", SDL_GetError_p ? SDL_GetError_p() : "(no SDL_GetError)");
    }
    BYTEHOOK_POP_STACK();
    return r;
}

// Mobile renderers are all OpenGL ES implementations, but the game initializes
// SDL following desktop GL conventions, and non-ES profile requests are rejected
// by the host. So force the GL profile to ES ahead of every window creation.
static void forceEglProfileEs(void) {
    if (!sdlGlesCompatEnabled()) return;
    SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_GL_SetAttribute);
    if (SDL_GL_SetAttribute_p) {
        SDL_GL_SetAttribute_p(20 /* SDL_GL_CONTEXT_PROFILE_MASK */, 4 /* SDL_GL_CONTEXT_PROFILE_ES */);
        sForcedEsProfile = true;
    }
}

// --- Primary-window reuse under Android's single-window constraint ---
//
// SDL's Android backend only supports one window per process, but since MC 26.3
// RenderPearl first creates a hidden tool window at device init (the GL context
// attaches to it), and the later main-window creation is then refused - while
// destroying the tool window would invalidate the GL surface on it. So redirect
// later creation requests to the first window.
//
// Neither size nor orientation needs extra handling: size is decided by the
// Android Surface (taken from the Surface at creation, independent of the
// requested values), and orientation is uniformly controlled by the
// SDL_ORIENTATIONS hint.
// Every reuse issues one logical reference; the real window lives until all
// references are released (see releasePrimaryWindow).
static SDL_Window *reusePrimaryWindow(void) {
    ++sPrimaryWindowRefs;
    LOG_TO_I("SDL_Hook: reusing primary window %p, refs=%u", sPrimaryWindow, sPrimaryWindowRefs);
    return sPrimaryWindow;
}

static SDL_Window *custom_SDL_CreateWindow_Func(const char *title, int w, int h, uint32_t flags) {
    forceEglProfileEs();
    const bool reuse = shouldReusePrimaryWindow();
    LOG_TO_I("SDL_Hook: primary window reuse=%s", reuse ? "enabled" : "disabled");
    if (reuse && sPrimaryWindow != NULL) {
        return reusePrimaryWindow();
    }
    SDL_Window *wnd = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindow_Func, SDL_CreateWindow_t, title, w, h, flags);
    if (reuse && wnd != NULL) {
        sPrimaryWindow = wnd;
        sPrimaryWindowRefs = 1;
    }
    BYTEHOOK_POP_STACK();
    return wnd;
}

static SDL_Window *custom_SDL_CreateWindowWithProperties_Func(uint32_t props) {
    forceEglProfileEs();
    const bool reuse = shouldReusePrimaryWindow();
    LOG_TO_I("SDL_Hook: primary window reuse=%s", reuse ? "enabled" : "disabled");
    if (reuse && sPrimaryWindow != NULL) {
        return reusePrimaryWindow();
    }
    SDL_Window *wnd = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindowWithProperties_Func, SDL_CreateWindowWithProperties_t, props);
    if (reuse && wnd != NULL) {
        sPrimaryWindow = wnd;
        sPrimaryWindowRefs = 1;
    }
    BYTEHOOK_POP_STACK();
    return wnd;
}

// ---------- dlsym-layer proxies ----------
// Consumers such as LWJGL's org.lwjgl.sdl binding resolve SDL function pointers
// with dlopen+dlsym and then call them directly; bytehook's hook_all (GOT import
// patching) cannot intercept that kind of call, so with only the hooks above,
// primary-window reuse / destroy tracking would not apply to those consumers.
// customDlsym (sdl_dlsym_hook.c) swaps the symbols below for these proxies at the
// dlsym exit: the proxies call the cached real SDL functions and share the
// primary-window reuse / refcount logic with the hook layer, without depending
// on a bytehook call context.
// Reference FCL-Team/FoldCraftLauncher's sdl_hook.c
// (https://github.com/FCL-Team/FoldCraftLauncher/blob/e398181caaccf186247057dfa1ba0cf4e5cbc83e/FCL/src/main/jni/native_hooks/sdl_hook.c)

typedef bool (*sdlInitSubSystem_t)(SDL_InitFlags);
typedef SDL_Window *(*sdlCreateWindow_t)(const char *, int, int, uint32_t);
typedef SDL_Window *(*sdlCreateWindowWithProperties_t)(uint32_t);
typedef void (*sdlDestroyWindow_t)(SDL_Window *);
typedef SDL_Window *(*sdlGetWindowFromEvent_t)(const void *);
typedef SDL_Window *(*sdlGetWindowFromID_t)(uint32_t);
typedef void *(*sdlLoadObject_t)(const char *);
typedef void *(*sdlLoadFunction_t)(void *, const char *);
typedef void (*sdlUnloadObject_t)(void *);

static sdlInitSubSystem_t realSdlInitSubSystem = NULL;
static sdlCreateWindow_t realSdlCreateWindow = NULL;
static sdlCreateWindowWithProperties_t realSdlCreateWindowWithProperties = NULL;
static sdlDestroyWindow_t realSdlDestroyWindow = NULL;
static sdlGetWindowFromEvent_t realSdlGetWindowFromEvent = NULL;
static sdlGetWindowFromID_t realSdlGetWindowFromID = NULL;
static sdlLoadObject_t realSdlLoadObject = NULL;
static sdlLoadFunction_t realSdlLoadFunction = NULL;
static sdlUnloadObject_t realSdlUnloadObject = NULL;

static bool proxy_SDL_InitSubSystem(SDL_InitFlags flags) {
    if (!sdlInitSubSystemPrepare(flags)) return false;
    bool r = realSdlInitSubSystem(flags);
    if (!r) {
        SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_GetError);
        LOG_TO_E("SDL_Hook: SDL_InitSubsystem Error: %s", SDL_GetError_p ? SDL_GetError_p() : "(no SDL_GetError)");
    }
    return r;
}

static SDL_Window *proxy_SDL_CreateWindow(const char *title, int w, int h, uint32_t flags) {
    forceEglProfileEs();
    if (shouldReusePrimaryWindow() && sPrimaryWindow != NULL) return reusePrimaryWindow();
    SDL_Window *window = realSdlCreateWindow(title, w, h, flags);
    if (window != NULL && shouldReusePrimaryWindow()) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
    }
    return window;
}

static SDL_Window *proxy_SDL_CreateWindowWithProperties(uint32_t props) {
    forceEglProfileEs();
    if (shouldReusePrimaryWindow() && sPrimaryWindow != NULL) return reusePrimaryWindow();
    SDL_Window *window = realSdlCreateWindowWithProperties(props);
    if (window != NULL && shouldReusePrimaryWindow()) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
    }
    return window;
}

static void proxy_SDL_DestroyWindow(SDL_Window *window) {
    if (releasePrimaryWindow(window)) realSdlDestroyWindow(window);
}

static SDL_Window *proxy_SDL_GetWindowFromEvent(const void *event) {
    SDL_Window *window = realSdlGetWindowFromEvent(event);
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    return window;
}

static SDL_Window *proxy_SDL_GetWindowFromID(uint32_t id) {
    SDL_Window *window = realSdlGetWindowFromID(id);
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    return window;
}

static void *proxy_SDL_LoadObject(const char *path) {
    if (path != NULL && strstr(path, "libvulkan") != NULL) {
        const char *vkptr = getenv("VULKAN_PTR");
        if (vkptr != NULL && vkptr[0] != '\0') {
            void *handle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
            if (handle != NULL) return handle;
        }
    }
    return realSdlLoadObject(path);
}

static void *proxy_SDL_LoadFunction(void *handle, const char *name) {
    return injectEglProxy(name, realSdlLoadFunction(handle, name));
}

static void proxy_SDL_UnloadObject(void *handle) {
    const char *vkptr = getenv("VULKAN_PTR");
    if (vkptr != NULL && vkptr[0] != '\0') {
        void *vulkanHandle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
        if (handle == vulkanHandle) return;
    }
    realSdlUnloadObject(handle);
}

void create_sdl_hooks(bytehook_stub_t (*bytehook_hook_all_p)(const char *callee_path_name, const char *sym_name, void *new_func,
                                                             bytehook_hooked_t hooked, void *hooked_arg)) {
    // Don't set callee_path_name to anything besides NULL or else it won't be able to find the symbol
    bytehook_stub_t stub_SDL_InitSubSystem = bytehook_hook_all_p(NULL, "SDL_InitSubSystem", (void *) &custom_SDL_InitSubSystem_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GetWindowFromEvent = bytehook_hook_all_p(NULL, "SDL_GetWindowFromEvent", (void *) &custom_SDL_GetWindowFromEvent_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GetWindowFromID = bytehook_hook_all_p(NULL, "SDL_GetWindowFromID", (void *) &custom_SDL_GetWindowFromID_Func, NULL, NULL);
    // Force the ES profile ahead of window creation (covers both SDL3 window-creation entries).
    bytehook_stub_t stub_SDL_CreateWindow = bytehook_hook_all_p(NULL, "SDL_CreateWindow", (void *) &custom_SDL_CreateWindow_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_CreateWindowWithProperties = bytehook_hook_all_p(NULL, "SDL_CreateWindowWithProperties", (void *) &custom_SDL_CreateWindowWithProperties_Func, NULL, NULL);
    // Take over SDL's EGL function resolution, injecting the normalizing proxies.
    bytehook_stub_t stub_SDL_LoadFunction = bytehook_hook_all_p(NULL, "SDL_LoadFunction", (void *) &custom_SDL_LoadFunction_Func, NULL, NULL);
    // SDL's public EGL resolution entry gets the same proxies (backend callbacks can bypass LoadFunction).
    bytehook_stub_t stub_SDL_EGLGetProcAddress = bytehook_hook_all_p(NULL, "SDL_EGL_GetProcAddress", (void *) &custom_SDL_EGLGetProcAddress_Func, NULL, NULL);
    // Vulkan loader consistency: SDL uses the launcher-redirected loader handle.
    bytehook_stub_t stub_SDL_LoadObject = bytehook_hook_all_p(NULL, "SDL_LoadObject", (void *) &custom_SDL_LoadObject_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_UnloadObject = bytehook_hook_all_p(NULL, "SDL_UnloadObject", (void *) &custom_SDL_UnloadObject_Func, NULL, NULL);
    // Primary-window destroy tracking, paired with window reuse (see custom_SDL_DestroyWindow_Func).
    bytehook_stub_t stub_SDL_DestroyWindow = bytehook_hook_all_p(NULL, "SDL_DestroyWindow", (void *) &custom_SDL_DestroyWindow_Func, NULL, NULL);
    LOG_TO_I("SDL_Hook: Successfully initialized SDL hooks, stubs: InitSubSystem=%p GetWindowFromEvent=%p GetWindowFromID=%p CreateWindow=%p CreateWindowWithProps=%p LoadFunction=%p EGLGetProcAddress=%p LoadObject=%p UnloadObject=%p DestroyWindow=%p", stub_SDL_InitSubSystem, stub_SDL_GetWindowFromEvent, stub_SDL_GetWindowFromID, stub_SDL_CreateWindow, stub_SDL_CreateWindowWithProperties, stub_SDL_LoadFunction, stub_SDL_EGLGetProcAddress, stub_SDL_LoadObject, stub_SDL_UnloadObject, stub_SDL_DestroyWindow);
}

/** customDlsym exit: swap an SDL symbol's resolution for the dlsym-layer proxy; NULL means "don't intercept". */
void *sdlDlsymProxy(const char *symbol, void *real) {
    if (strcmp(symbol, "SDL_InitSubSystem") == 0) {
        if (realSdlInitSubSystem == NULL) realSdlInitSubSystem = (sdlInitSubSystem_t) real;
        return (void *) proxy_SDL_InitSubSystem;
    }
    if (strcmp(symbol, "SDL_CreateWindow") == 0) {
        if (realSdlCreateWindow == NULL) realSdlCreateWindow = (sdlCreateWindow_t) real;
        return (void *) proxy_SDL_CreateWindow;
    }
    if (strcmp(symbol, "SDL_CreateWindowWithProperties") == 0) {
        if (realSdlCreateWindowWithProperties == NULL) realSdlCreateWindowWithProperties = (sdlCreateWindowWithProperties_t) real;
        return (void *) proxy_SDL_CreateWindowWithProperties;
    }
    if (strcmp(symbol, "SDL_DestroyWindow") == 0) {
        if (realSdlDestroyWindow == NULL) realSdlDestroyWindow = (sdlDestroyWindow_t) real;
        return (void *) proxy_SDL_DestroyWindow;
    }
    if (strcmp(symbol, "SDL_GetWindowFromEvent") == 0) {
        if (realSdlGetWindowFromEvent == NULL) realSdlGetWindowFromEvent = (sdlGetWindowFromEvent_t) real;
        return (void *) proxy_SDL_GetWindowFromEvent;
    }
    if (strcmp(symbol, "SDL_GetWindowFromID") == 0) {
        if (realSdlGetWindowFromID == NULL) realSdlGetWindowFromID = (sdlGetWindowFromID_t) real;
        return (void *) proxy_SDL_GetWindowFromID;
    }
    if (strcmp(symbol, "SDL_LoadObject") == 0) {
        if (realSdlLoadObject == NULL) realSdlLoadObject = (sdlLoadObject_t) real;
        return (void *) proxy_SDL_LoadObject;
    }
    if (strcmp(symbol, "SDL_LoadFunction") == 0) {
        if (realSdlLoadFunction == NULL) realSdlLoadFunction = (sdlLoadFunction_t) real;
        return (void *) proxy_SDL_LoadFunction;
    }
    if (strcmp(symbol, "SDL_UnloadObject") == 0) {
        if (realSdlUnloadObject == NULL) realSdlUnloadObject = (sdlUnloadObject_t) real;
        return (void *) proxy_SDL_UnloadObject;
    }
    return NULL;
}
