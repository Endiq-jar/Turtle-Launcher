#ifndef TURTLE_SDL_HOOK_H
#define TURTLE_SDL_HOOK_H

// dlsym helper macros (adapted from ZalithLauncher2's jni/utils.h).
// DECL_DLSYM(SDL_Foo) declares the SDL_Foo_t function-pointer type;
// SET_DLSYM_PTR(handle, SDL_Foo) resolves it into a local SDL_Foo_p.

#include <stdbool.h>
#include <dlfcn.h>
#include <jni.h>
#include <bytehook.h>
#include "sdl_log.h"

#define DECL_DLSYM(fn) typedef typeof(&fn) fn##_t;

#define SET_DLSYM_PTR(handle, fn)                     \
    fn##_t fn##_p;                                   \
    do {                                             \
        dlerror();                                   \
        void *_p = dlsym((handle), #fn);             \
        const char *_e = dlerror();                  \
        if (_e || !_p) {                             \
            LOG_TO_E("SDL_Hook: dlsym(" #fn ") failed: %s", _e ? _e : "unknown error"); \
        }                                            \
        fn##_p = (fn##_t)_p;                         \
    } while (0)

// Installed by sdl_dlsym_hook.c's customDlsym through bytehook's own dlsym hook.
void create_sdl_hooks(bytehook_stub_t (*bytehook_hook_all_p)(const char *callee_path_name, const char *sym_name, void *new_func,
                                                             bytehook_hooked_t hooked, void *hooked_arg));

// customDlsym exit handler in sdl_hook.c: returns an SDL dlsym-layer proxy for
// `symbol`, or NULL to leave the real resolution untouched.
void *sdlDlsymProxy(const char *symbol, void *real);

// True once SDL has created (and not yet destroyed) its primary window.
// Backs SdlBridge.isSdlRenderActive() to distinguish the MC 26.3+ SDL render
// path from gamepad-subsystem-only SDL use.
bool sdlhookHasPrimaryWindow(void);

// Installs bytehook + the dlsym hook + all SDL hooks. Idempotent; returns 0 on
// success, nonzero errno-style code on failure. Implemented in sdl_dlsym_hook.c.
int sdlhookInstall(void);

// Opaque primary-window pointer for the launcher-managed text-input channel.
// Implemented in sdl_hook.c.
struct SDL_Window;
struct SDL_Window *sdlhookGetPrimaryWindow(void);

// Records the ART (launcher) VM for JNI_OnLoad isolation. Implemented in
// sdl_dlsym_hook.c, called from JNI_OnLoad.
void sdlhookSetArtVm(JavaVM *vm);

#endif // TURTLE_SDL_HOOK_H
