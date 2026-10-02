# libsdlhook.so — SDL3 compatibility hooks for Minecraft 26.3+

Self-contained native module (links only against `-llog` and `-ldl`) that makes
MC 26.3+ (RenderPearl/SDL backend) work on Android.

## Why it exists

MC 26.3-snapshot-4+ replaced GLFW with SDL3, and the game initializes SDL the
way a desktop app would. On Android that crashes during backend init (at the
Mojang logo) unless the launcher interposes:

1. **Single-window reuse** — RenderPearl creates a hidden tool window, then the
   real main window. SDL's Android backend supports only one window per process,
   so the second `SDL_CreateWindow` fails. Later creation requests are
   transparently redirected to the first window (with logical refcounting so the
   real window is never destroyed early).
2. **ES profile + EGL normalization** — the game requests a desktop GL profile,
   but every mobile renderer is OpenGL ES. The ES profile is forced before
   window creation, and `eglChooseConfig`/`eglCreateContext` get
   original-request-first retry semantics with compatibility fallbacks
   (mobile-ES renderers only; desktop/OSMesa paths untouched).
3. **Vulkan loader consistency** — RenderPearl requires SDL and LWJGL to share
   one loader instance, but on the Turnip path the launcher redirects LWJGL to
   a private loader copy (`VULKAN_PTR`). SDL is handed the same handle, and
   SDL-side unloads of it are ignored.
4. **`SDL_ENABLE_SCREEN_KEYBOARD` override** — MC sets it to `0` (desktop
   convention); overridden back to `1` at `SDL_Init` so the soft keyboard works.
5. **Event-window fallback** — `SDL_GetWindowFromEvent/FromID` fall back to the
   last resolved window (there is only ever one on Android) when resolution
   fails, so scaled virtual-mouse coordinates can't clear mouse focus.
6. **JNI_OnLoad isolation** — the embedded game JVM shares this process; its
   `JNI_OnLoad` call on libSDL3.so is swallowed so SDL's JNI stays bound to the
   ART (launcher) VM.

LWJGL's `org.lwjgl.sdl` binding resolves SDL symbols with `dlsym()` and calls
the pointers directly, so hooking happens on **two layers**: `bytehook_hook_all`
(GOT patching, for normally-linked callers) plus a global `dlsym()` hook that
swaps SDL symbols for proxies at resolve time.

## Files

| File | Contents |
|---|---|
| `sdl_hook.c` / `sdl_hook.h` | SDL hooks + dlsym-layer proxies, primary-window tracking, EGL/Vulkan compat |
| `sdl_dlsym_hook.c` | `dlsym` hook (SDL proxy swap + JNI_OnLoad isolation), `sdlhookInstall()` |
| `sdlhook_jni.c` | JNI: `SdlHook.nativeInit`, `SdlBridge.{initializeControllerSubsystems,isSdlRenderActive,setNativeTextInputActive}` |
| `sdl_log.h` | logcat logging macros |
| `include/bytehook.h` | Vendored ByteHook API header ([bytedance/bhook](https://github.com/bytedance/bhook), MIT) — header only; the implementation is loaded at runtime from `libbytehook.so` |

## Build

The module is **not** part of the app's Gradle build (which stays NDK-free so it
can be built in Termux). It is compiled by `.github/workflows/build-sdlhook.yml`
for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and the resulting
`libsdlhook.so` files are committed to `TurtleLauncher/src/main/jniLibs/<abi>/`.

Java side: `SdlHook` loads the library and calls `nativeInit()`; see
`com.endiq.turtlelauncher.launch.SdlHook` and
`com.endiq.turtlelauncher.game.sdl.SdlBridge`.

## Origin

Port of ZalithLauncher2's SDL3 support
([PR #1721](https://github.com/ZalithLauncher/ZalithLauncher2/pull/1721),
GPL-3.0; itself referencing Amethyst-Android and FoldCraftLauncher), adapted to
a dependency-free module: no `libpojavexec` linkage (`calculateFPS` is resolved
lazily and NULL-safe), no ART callbacks from the `SDL_Init` hook (the ART side
is initialized eagerly by `SdlAndroidJniPrep` instead), and the primary-window
pointer is tracked in-module.
