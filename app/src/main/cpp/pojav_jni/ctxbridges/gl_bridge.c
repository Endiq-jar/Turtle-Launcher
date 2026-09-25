#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
#include <log.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/types.h>


//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;

// 파일 상단 어디든 추가
static gl_render_window_t* g_lastInitializedBundle = NULL;


bool gl_init() {
    if(!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
        return false;
    }
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        LOGE("eglChooseConfig_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    if (num_configs == 0) {
        LOGE("%s", "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    // Get the first matching config
    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        // gl_bridge.c — gl_init_context 안
        EGLBoolean bindResult;
        const char* renderer = getenv("POJAV_RENDERER");
        bool wantDesktopGL = (renderer && strncmp(renderer, "opengles3_desktopgl", 19) == 0);

        if (wantDesktopGL) {
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
            if (!bindResult) {
                LOGW("Desktop GL bind 실패(0x%x) — ES 바인딩으로 폴백", eglGetError_p());
                bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
                // ★ 폴백한 사실을 환경변수에도 기록해서 LIBGL_GL 같은 후속 설정이
                //   "데스크톱 GL인 척"하지 않도록 한다
                setenv("POJAV_RENDERER", "opengles2", 1);
            }
        } else {
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) {
            LOGE("eglBindAPI 완전 실패: 0x%x", eglGetError_p());
            free(bundle);
            return NULL;   // ← 지금은 그냥 진행하는데 여기서 끊어야 합니다
        }
    }

    const char* libgl_es_env = getenv("LIBGL_ES");
    int libgl_es = libgl_es_env ? strtol(libgl_es_env, NULL, 0) : 2;
    if (libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext_p() finished with error: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    LOGI("gl_swap_surface: enter, nativeSurface=%p newNativeSurface=%p surface=%p",
         bundle->nativeSurface, bundle->newNativeSurface, bundle->surface);

    if(bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
    }
    if(bundle->surface != NULL) {
        EGLBoolean ds = eglDestroySurface_p(g_EglDisplay, bundle->surface);
        LOGI("gl_swap_surface: destroy old EGLSurface result=%d", ds);
    }

    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        int32_t w = ANativeWindow_getWidth(bundle->nativeSurface);
        int32_t h = ANativeWindow_getHeight(bundle->nativeSurface);
        LOGI("gl_swap_surface: ANativeWindow size=%dx%d", w, h);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config,
                                                   bundle->nativeSurface, NULL);
        if (bundle->surface == EGL_NO_SURFACE) {
            LOGE("gl_swap_surface: eglCreateWindowSurface FAILED error=0x%04x",
                 eglGetError_p());
            bundle->surface = NULL;
        } else {
            EGLint sw=0, sh=0;
            eglQuerySurface_p(g_EglDisplay, bundle->surface, EGL_WIDTH, &sw);
            eglQuerySurface_p(g_EglDisplay, bundle->surface, EGL_HEIGHT, &sh);
            LOGI("gl_swap_surface: new EGLSurface=%p size=%dx%d", bundle->surface, sw, sh);
        }
    }else{
        LOGI("No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    }
    //eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context);
}

void gl_make_current(gl_render_window_t* bundle) {
    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }

    currentBundle = bundle;

    bool hasSetMainWindow = false;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }

    // redundant 체크
    if (currentBundle == bundle && bundle->surface != NULL
        && bundle->newNativeSurface == NULL
        && bundle->state == STATE_RENDERER_ALIVE) {
        EGLContext cur_ctx = eglGetCurrentContext_p();
        EGLSurface cur_surf = eglGetCurrentSurface_p(EGL_DRAW);
        if (cur_ctx == bundle->context && cur_surf == bundle->surface) {
            return;
        }
    }

    if(bundle->surface == NULL) {
        gl_swap_surface(bundle);
    }
    eglMakeCurrent_p(g_EglDisplay, bundle->surface,
                     bundle->surface, bundle->context);
}

void gl_swap_buffers() {
    if (currentBundle == NULL) return;
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        LOGI("gl_swap_buffers: STATE_RENDERER_NEW_WINDOW detected, "
             "old surface=%p newNativeSurface=%p",
             currentBundle->surface, currentBundle->newNativeSurface);
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        LOGI("gl_swap_buffers: after swap_surface, new EGLSurface=%p nativeSurface=%p",
             currentBundle->surface, currentBundle->nativeSurface);
        EGLBoolean mc = eglMakeCurrent_p(g_EglDisplay, currentBundle->surface,
                                         currentBundle->surface, currentBundle->context);
        LOGI("gl_swap_buffers: makeCurrent after swap result=%d error=0x%04x",
             mc, eglGetError_p());
        currentBundle->state = STATE_RENDERER_ALIVE;
    }
    if(currentBundle->surface != NULL) {
        if(!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface)
           && eglGetError_p() == EGL_BAD_SURFACE) {

            EGLint err = eglGetError_p();
            LOGE("gl_swap_buffers: eglSwapBuffers FAILED error=0x%04x surface=%p",
                 err, currentBundle->surface);

            // ★ 이미 새 surface가 예약되어 있으면 폴백하지 말고 그걸 사용
            if (currentBundle->newNativeSurface != NULL) {
                LOGI("Swap failed but new surface pending — switching to it");
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                gl_swap_surface(currentBundle);
                eglMakeCurrent_p(g_EglDisplay, currentBundle->surface,
                                 currentBundle->surface, currentBundle->context);
                currentBundle->state = STATE_RENDERER_ALIVE;
            } else {
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                gl_swap_surface(currentBundle);  // 1x1 pbuffer로
                LOGI("The window has died, awaiting window change");
            }
        }
    }
}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    // force_vsync 덮어쓰기 제거 — 게임이 요청한 interval 을 그대로 적용한다.
    //   swapInterval==0 이면 VSync off(주사율 무관 최대 프레임), 1 이면 주사율 동기.
    //   (force_vsync 는 기본 false 이므로 평소엔 아래 줄이 영향을 주지 않는다.)
    if(pojav_environ->force_vsync) swapInterval = 1;
    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                                 jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}