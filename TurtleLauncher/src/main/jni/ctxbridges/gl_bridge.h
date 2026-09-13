//
// Created by maks on 17.09.2022.
//
#include <EGL//egl.h>
#include <stdbool.h>
#ifndef TURTLELAUNCHER_GL_BRIDGE_H
#define TURTLELAUNCHER_GL_BRIDGE_H

typedef struct {
    char       state;
    struct ANativeWindow *nativeSurface;
    struct ANativeWindow *newNativeSurface;
    EGLConfig  config;
    EGLint     format;
    EGLContext context;
    EGLSurface surface;
} gl_render_window_t;

bool gl_init();
gl_render_window_t* gl_get_current();
gl_render_window_t* gl_init_context(gl_render_window_t* share);
void gl_make_current(gl_render_window_t* bundle);
void gl_swap_buffers();
void gl_setup_window();
void gl_swap_interval(int swapInterval);

/**
 * TurtleLauncher EGL improvements (see gl_bridge.c for the full rundown):
 * logs the EGL vendor/version and the handful of extensions we care about once,
 * right after eglInitialize - called automatically from gl_init().
 */
void gl_log_egl_info();

/**
 * TurtleLauncher: front-buffer / low-latency rendering. When enabled (and the
 * device actually supports EGL_KHR_mutable_render_buffer), window surfaces are
 * switched to EGL_SINGLE_BUFFER right after creation, and
 * EGL_ANDROID_front_buffer_auto_refresh is enabled on top of it if that
 * extension is also present. Silently no-ops back to normal double-buffered
 * rendering on devices/drivers that don't support it - this is a latency
 * trade-off (skips waiting on the swap chain), not something to force blindly.
 * Reads POJAV_LOW_LATENCY_RENDERING once, same convention as the other
 * env-var-driven toggles in this codebase (see JREUtils.setJavaEnv/setRendererEnv).
 */
bool gl_low_latency_requested();

#endif //TURTLELAUNCHER_GL_BRIDGE_H
