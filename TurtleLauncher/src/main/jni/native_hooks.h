// Native hooks used by the Android-side launcher integration.
//
// SDL 3 is loaded by the embedded game JVM, while the SDL Android Java
// frontend lives in ART.  The hooks below are deliberately installed from
// the existing exithook module so they cover both VMs without making the game
// classpath depend on launcher implementation classes.
#ifndef TURTLELAUNCHER_NATIVE_HOOKS_H
#define TURTLELAUNCHER_NATIVE_HOOKS_H

#include <bytehook.h>

typedef bytehook_stub_t (*bytehook_hook_all_t)(
        const char *callee_path_name,
        const char *sym_name,
        void *new_func,
        bytehook_hooked_t hooked,
        void *hooked_arg);

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p);

#endif // TURTLELAUNCHER_NATIVE_HOOKS_H
