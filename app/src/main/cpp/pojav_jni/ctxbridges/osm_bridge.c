//
// Created by maks on 18.10.2023.
//
#include <malloc.h>
#include <string.h>
#include <environ/environ.h>
#include "osm_bridge.h"
#define TAG __FILE_NAME__
#include <log.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "time.h"
#define gettid() ((pid_t)syscall(SYS_gettid))

static __thread osm_render_window_t* currentBundle;   // ★ ZL2 와 동일 — 스레드 로컬(렌더 스레드 격리)

// a tiny buffer for rendering when there's nowhere t render
static char no_render_buffer[16 * 16 * 4] __attribute__((aligned(16)));
static bool hasSetNoRendererBuffer = false;   // ★ ZL2 와 동일 — 더미 버퍼는 최초 1회만 세팅
const GLubyte* (*real_glGetString)(GLenum name) = NULL;

// Its not in a .h file because it is not supposed to be used outsife of this file.
void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);

bool osm_init() {
    if(!dlsym_OSMesa()) return false;
    real_glGetString = (const GLubyte* (*)(GLenum)) OSMesaGetProcAddress_p("glGetString");
    LOGI("osm_init: real_glGetString=%p", (void*)real_glGetString);
    return true;
}

osm_render_window_t* osm_get_current() {
    return currentBundle;
}

osm_render_window_t* osm_init_context(osm_render_window_t* share) {
    osm_render_window_t* render_window = malloc(sizeof(osm_render_window_t));
    if(render_window == NULL) return NULL;
    memset(render_window, 0, sizeof(osm_render_window_t));
    OSMesaContext osmesa_share = NULL;
    if(share != NULL) osmesa_share = share->context;
    OSMesaContext context = OSMesaCreateContext_p(GL_RGBA, osmesa_share);
    if(context == NULL) {
        free(render_window);
        return NULL;
    }
    render_window->context = context;
    return render_window;
}




void osm_set_no_render_buffer(ANativeWindow_Buffer* buffer) {
    buffer->bits = &no_render_buffer;
    buffer->width = 16;
    buffer->height = 16;
    buffer->stride = 16;  // RGBA row 길이를 픽셀 단위로
}

void osm_swap_surfaces(osm_render_window_t* bundle) {
    if(bundle->nativeSurface != NULL && bundle->newNativeSurface != bundle->nativeSurface) {
        if(!bundle->disable_rendering) {
            LOGI("Unlocking for cleanup...");
            ANativeWindow_unlockAndPost(bundle->nativeSurface);
        }
        ANativeWindow_release(bundle->nativeSurface);
    }
    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, WINDOW_FORMAT_RGBX_8888);
        bundle->disable_rendering = false;
        return;
    }else {
        LOGI("No new native surface, switching to dummy framebuffer");
        bundle->nativeSurface = NULL;
        osm_set_no_render_buffer(&bundle->buffer);
        bundle->disable_rendering = true;
    }

}

void osm_release_window() {
    currentBundle->newNativeSurface = NULL;
    osm_swap_surfaces(currentBundle);
}

void osm_apply_current_ll() {
    ANativeWindow_Buffer* buffer = &currentBundle->buffer;
    OSMesaMakeCurrent_p(currentBundle->context, buffer->bits,
                        GL_UNSIGNED_BYTE, buffer->width, buffer->height);
    if(buffer->stride != currentBundle->last_stride)
        OSMesaPixelStore_p(OSMESA_ROW_LENGTH, buffer->stride);
    currentBundle->last_stride = buffer->stride;

#ifdef OSM_DIAG_ONCE
    static bool diag_logged = false;
    if (!diag_logged && glGetString_p) {
        diag_logged = true;
        LOGI("[diag] GL_VERSION=%s VENDOR=%s RENDERER=%s",
             glGetString_p(0x1F02), glGetString_p(0x1F00), glGetString_p(0x1F01));
    }
#endif
}
void* wrapped_OSMesaGetProcAddress(const char* funcName) {
    void* real = OSMesaGetProcAddress_p ? OSMesaGetProcAddress_p(funcName) : NULL;
    if (real != NULL && funcName != NULL && strcmp(funcName, "glGetString") == 0) {
        LOGI("wrapped_OSMesaGetProcAddress: redirect glGetString -> wrapped_glGetString (raw=%p)", real);
        return (void*)wrapped_glGetString;
    }
    return real;
}

const GLubyte* wrapped_glGetString(GLenum name) {
    if (OSMesaGetCurrentContext_p && OSMesaGetCurrentContext_p() == NULL
        && currentBundle != NULL) {
        static int rebind_count = 0;
        if (++rebind_count % 60 == 0)
            LOGI("wrapped_glGetString rebind #%d (이게 계속 늘면 문제)", rebind_count);
        osm_apply_current_ll();
    }
    return real_glGetString ? real_glGetString(name) : NULL;
}

void osm_make_current(osm_render_window_t* bundle) {
    if(bundle == NULL) {
        //technically this does nothing as its not possible to unbind a context in OSMesa
        OSMesaMakeCurrent_p(NULL, NULL, 0, 0, 0);
        currentBundle = NULL;
        return;
    }
    bool hasSetMainWindow = false;
    currentBundle = bundle;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*) bundle;
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }
    if(bundle->nativeSurface == NULL) {
        //prepare the buffer for our first render!
        osm_swap_surfaces(bundle);
        if(hasSetMainWindow) pojav_environ->mainWindowBundle->state = STATE_RENDERER_ALIVE;
    }
    // ★ ZL2 와 동일 — 더미 버퍼는 최초 1회만. 매번 덮어쓰면 실제 표면 버퍼가 리셋되어
    //   화면이 16x16 더미에만 그려지고(=화면 안 뜸) make_current 가 폭주한다.
    if (!hasSetNoRendererBuffer) {
        osm_set_no_render_buffer(&bundle->buffer);
        hasSetNoRendererBuffer = true;
    }
    osm_apply_current_ll();
    OSMesaPixelStore_p(OSMESA_Y_UP,0);
}

void osm_swap_buffers() {
    if (currentBundle == NULL) {
        // make_current(NULL) 직후 등 — 그릴 대상이 없으면 조용히 무시 (NULL 역참조 방지)
        return;
    }
    static int fc = 0;
    struct timespec t0, t1, t2, t3;
    clock_gettime(CLOCK_MONOTONIC, &t0);

    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        osm_swap_surfaces(currentBundle);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }
    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_lock(currentBundle->nativeSurface, &currentBundle->buffer, NULL) != 0)
            osm_release_window();
    clock_gettime(CLOCK_MONOTONIC, &t1);   // lock 끝

    osm_apply_current_ll();
    clock_gettime(CLOCK_MONOTONIC, &t2);   // makeCurrent 끝

    glFinish_p();
    clock_gettime(CLOCK_MONOTONIC, &t3);   // glFinish 끝 = GPU 렌더 완료 시점

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_unlockAndPost(currentBundle->nativeSurface) != 0)
            osm_release_window();

    struct timespec t4; clock_gettime(CLOCK_MONOTONIC, &t4);  // post 끝

    if ((fc++ % 60) == 0) {
        double lock   = (t1.tv_sec-t0.tv_sec)*1e3 + (t1.tv_nsec-t0.tv_nsec)/1e6;
        double mkcur  = (t2.tv_sec-t1.tv_sec)*1e3 + (t2.tv_nsec-t1.tv_nsec)/1e6;
        double finish = (t3.tv_sec-t2.tv_sec)*1e3 + (t3.tv_nsec-t2.tv_nsec)/1e6;
        double post   = (t4.tv_sec-t3.tv_sec)*1e3 + (t4.tv_nsec-t3.tv_nsec)/1e6;
        LOGI("[PERF] lock=%.1f makeCur=%.1f glFinish=%.1f post=%.1f ms",
             lock, mkcur, finish, post);
    }
}

void osm_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void osm_swap_interval(int swapInterval) {
    if(pojav_environ->mainWindowBundle != NULL && pojav_environ->mainWindowBundle->nativeSurface != NULL) {
        setNativeWindowSwapInterval(pojav_environ->mainWindowBundle->nativeSurface, swapInterval);
    }
}