// 마인크래프트 26.3+ 입력 — 화면 버튼·터치·게임패드를 SDL 이벤트로 넣는다.
//
// 26.3 은 GLFW 를 버렸다. 그래서 기존 경로(CallbackBridge → libpojavexec 의 GLFW 콜백)는
// 아무 데도 닿지 않는다. SDL 의 안드로이드 백엔드가 자기 SurfaceView 의 터치는 스스로
// 받지만, 우리 화면 버튼·조이스틱·게임패드는 그 뷰 밖에서 오므로 직접 넣어야 한다.
//
// 26.3 이 실제로 읽는 값 (iOS 포팅에서 클라이언트 jar 을 javap 로 확인한 것과 같다):
//   키     scancode = SDL 스캔코드(= HID usage, W = 26), mod = SDL_Keymod
//   버튼   1 좌 · 2 가운데 · 3 우 (InputConstants.MOUSE_BUTTON_*)
//   이동   x/y 는 창 좌표. grab 중에는 xrel/yrel 만 누적한다(MouseHandler.onMove)
//   공통   windowID 가 게임 창이 아니면 버린다
//
// ⚠️ SDL_PushEvent 는 SDL 의 **내부 상태를 안 바꾼다**(SDL_events.h). 게임이 상태를 직접
//    읽는 곳은 공개 API 로 맞춘다 — 수식키는 누적해서 SDL_SetModState, 절대 이동은
//    SDL_WarpMouseInWindow.
// ⚠️ 헤더를 들여오지 않고 dlsym 으로 붙는다(SDL3 헤더는 100여 개). 이벤트 구조체만
//    옮겨 적는다 — SDL3 는 ABI 고정을 약속하고, 이벤트 크기는 128바이트다.

#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FLAME_SDL", __VA_ARGS__)


typedef union {
    uint32_t type;
    struct { uint32_t type, reserved; uint64_t timestamp; uint32_t windowID, which;
             int32_t scancode; uint32_t key; uint16_t mod, raw; bool down, repeat; } key;
    struct { uint32_t type, reserved; uint64_t timestamp; uint32_t windowID;
             const char *text; } text;
    struct { uint32_t type, reserved; uint64_t timestamp; uint32_t windowID, which, state;
             float x, y, xrel, yrel; } motion;
    struct { uint32_t type, reserved; uint64_t timestamp; uint32_t windowID, which;
             uint8_t button; bool down; uint8_t clicks, padding; float x, y; } button;
    struct { uint32_t type, reserved; uint64_t timestamp; uint32_t windowID, which;
             float x, y; int32_t direction; float mouse_x, mouse_y; int32_t integer_x, integer_y; } wheel;
    uint8_t padding[128];
} FlameSDLEvent;

enum {
    FLAME_SDL_KEY_DOWN = 0x300, FLAME_SDL_KEY_UP = 0x301, FLAME_SDL_TEXT_INPUT = 0x303,
    FLAME_SDL_MOUSE_MOTION = 0x400, FLAME_SDL_BUTTON_DOWN = 0x401, FLAME_SDL_BUTTON_UP = 0x402,
    FLAME_SDL_MOUSE_WHEEL = 0x403,
};

static struct {
    bool     (*PushEvent)(FlameSDLEvent *);
    void    *(*GetKeyboardFocus)(void);
    uint32_t (*GetWindowID)(void *);
    bool     (*GetWindowSize)(void *, int *, int *);
    bool     (*GetWindowRelativeMouseMode)(void *);
    void     (*WarpMouseInWindow)(void *, float, float);
    uint32_t (*GetKeyFromScancode)(int32_t, uint16_t, bool);
    void     (*SetModState)(uint16_t);
    void     (*SetMainReady)(void);
} sdl;

/// libSDL3.so 는 **자바가 System.loadLibrary 로 먼저** 올린다(SDLActivity.getLibraries).
/// 그래야 SDL 의 JNI_OnLoad 가 달빅 VM 을 잡는다. 여기서는 그 인스턴스에 붙기만 한다.
static bool flame_sdlBind(void) {
    if (sdl.PushEvent) return true;
    void *h = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_LAZY);
    if (!h) return false;
#define FLAME_SDL_LOAD(name) sdl.name = (__typeof__(sdl.name))dlsym(h, "SDL_" #name)
    FLAME_SDL_LOAD(GetKeyboardFocus);
    FLAME_SDL_LOAD(GetWindowID);
    FLAME_SDL_LOAD(GetWindowSize);
    FLAME_SDL_LOAD(GetWindowRelativeMouseMode);
    FLAME_SDL_LOAD(WarpMouseInWindow);
    FLAME_SDL_LOAD(GetKeyFromScancode);
    FLAME_SDL_LOAD(SetModState);
    FLAME_SDL_LOAD(SetMainReady);
    FLAME_SDL_LOAD(PushEvent);   // 마지막 — 이게 서 있으면 나머지도 채워진 것이다
#undef FLAME_SDL_LOAD
    return sdl.PushEvent != NULL;
}

/// 게임의 SDL 창. 없으면 NULL — 아직 부팅 중이거나 26.3 이 아니다.
static void *flame_sdlWindow(void) {
    static void *window;
    if (!flame_sdlBind()) return NULL;
    void *focus = sdl.GetKeyboardFocus();
    if (focus) window = focus;
    // 창이 없어졌으면 ID 가 0 이다 — SDL3 는 객체 포인터를 검증하므로 묵은 포인터도 안전하다.
    return window && sdl.GetWindowID(window) ? window : NULL;
}

/// SDL_PushEvent 는 수식키 상태를 안 바꾸므로 여기서 누적한다.
static uint16_t flame_sdlMods;
/// 우리 프레임버퍼 크기(해상도 배율 적용 후). 절대 좌표를 창 좌표로 되돌릴 때 쓴다.
static int flame_fbWidth, flame_fbHeight;

#define J(name) Java_kr_co_donghyun_flamelauncher_presentation_MinecraftActivity_##name

JNIEXPORT jboolean JNICALL J(nativeSdlSendKey)(JNIEnv *env, jobject thiz, jint scancode, jint action) {
    (void)env; (void)thiz;
    void *w = flame_sdlWindow();
    if (!w) return JNI_FALSE;
    if (scancode <= 0) return JNI_TRUE;   // 26.3 에 대응하는 키가 없다

    bool down = action != 0;
    // LCtrl LShift LAlt LGui RCtrl RShift RAlt RGui (스캔코드 224~231) → SDL_KMOD_* 비트
    static const uint16_t modBits[8] = { 0x0040, 0x0001, 0x0100, 0x0400, 0x0080, 0x0002, 0x0200, 0x0800 };
    if (scancode >= 224 && scancode <= 231) {
        uint16_t bit = modBits[scancode - 224];
        flame_sdlMods = down ? (flame_sdlMods | bit) : (flame_sdlMods & ~bit);
        sdl.SetModState(flame_sdlMods);
    }

    FlameSDLEvent e = {0};
    e.key.type = down ? FLAME_SDL_KEY_DOWN : FLAME_SDL_KEY_UP;
    e.key.windowID = sdl.GetWindowID(w);
    e.key.scancode = scancode;
    e.key.key = sdl.GetKeyFromScancode(scancode, flame_sdlMods, true);
    e.key.mod = flame_sdlMods;
    e.key.down = down;
    e.key.repeat = action == 2;
    sdl.PushEvent(&e);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL J(nativeSdlSendChar)(JNIEnv *env, jobject thiz, jint codepoint) {
    (void)env; (void)thiz;
    void *w = flame_sdlWindow();
    if (!w) return JNI_FALSE;

    // ⚠️ text 는 포인터다. 게임 스레드가 이 이벤트를 꺼내 읽을 때까지 살아 있어야 한다.
    // ponytail: 256칸 링 — 한 번에 256글자 넘게 밀리면 앞 글자가 덮인다. 그땐 이벤트마다 할당으로.
    static char ring[256][5];
    static unsigned slot;
    char *text = ring[slot++ % 256];

    // UTF-32 → UTF-8 (마인크래프트 채팅은 한 글자씩 온다)
    unsigned c = (unsigned)codepoint;
    int n = 0;
    if (c < 0x80) { text[n++] = (char)c; }
    else if (c < 0x800) { text[n++] = (char)(0xC0 | (c >> 6)); text[n++] = (char)(0x80 | (c & 0x3F)); }
    else if (c < 0x10000) { text[n++] = (char)(0xE0 | (c >> 12)); text[n++] = (char)(0x80 | ((c >> 6) & 0x3F)); text[n++] = (char)(0x80 | (c & 0x3F)); }
    else { text[n++] = (char)(0xF0 | (c >> 18)); text[n++] = (char)(0x80 | ((c >> 12) & 0x3F)); text[n++] = (char)(0x80 | ((c >> 6) & 0x3F)); text[n++] = (char)(0x80 | (c & 0x3F)); }
    text[n] = '\0';

    FlameSDLEvent e = {0};
    e.text.type = FLAME_SDL_TEXT_INPUT;
    e.text.windowID = sdl.GetWindowID(w);
    e.text.text = text;
    sdl.PushEvent(&e);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL J(nativeSdlSendMouseButton)(JNIEnv *env, jobject thiz, jint glfwButton, jint action) {
    (void)env; (void)thiz;
    void *w = flame_sdlWindow();
    if (!w) return JNI_FALSE;
    if (glfwButton < 0 || glfwButton > 2) return JNI_TRUE;

    static const uint8_t sdlButton[3] = { 1, 3, 2 };   // GLFW 좌·우·가운데 → SDL 1·3·2
    FlameSDLEvent e = {0};
    e.button.type = action ? FLAME_SDL_BUTTON_DOWN : FLAME_SDL_BUTTON_UP;
    e.button.windowID = sdl.GetWindowID(w);
    e.button.button = sdlButton[glfwButton];
    e.button.down = action != 0;
    e.button.clicks = 1;
    sdl.PushEvent(&e);
    return JNI_TRUE;
}

/// @param mode 0 = 절대좌표(우리 프레임버퍼 px), 1 = 상대델타(인게임 시점 회전)
JNIEXPORT jboolean JNICALL J(nativeSdlSendCursorPos)(JNIEnv *env, jobject thiz, jint mode, jfloat x, jfloat y) {
    (void)env; (void)thiz;
    void *w = flame_sdlWindow();
    if (!w) return JNI_FALSE;

    if (mode == 1) {
        FlameSDLEvent e = {0};
        e.motion.type = FLAME_SDL_MOUSE_MOTION;
        e.motion.windowID = sdl.GetWindowID(w);
        e.motion.xrel = x;
        e.motion.yrel = y;
        sdl.PushEvent(&e);
        return JNI_TRUE;
    }

    int ww = 0, wh = 0;
    if (flame_fbWidth > 0 && flame_fbHeight > 0 && sdl.GetWindowSize(w, &ww, &wh)) {
        sdl.WarpMouseInWindow(w, x * ww / flame_fbWidth, y * wh / flame_fbHeight);
    } else {
        sdl.WarpMouseInWindow(w, x, y);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL J(nativeSdlSendScroll)(JNIEnv *env, jobject thiz, jfloat dx, jfloat dy) {
    (void)env; (void)thiz;
    void *w = flame_sdlWindow();
    if (!w) return JNI_FALSE;

    FlameSDLEvent e = {0};
    e.wheel.type = FLAME_SDL_MOUSE_WHEEL;
    e.wheel.windowID = sdl.GetWindowID(w);
    e.wheel.x = dx;
    e.wheel.y = dy;
    e.wheel.integer_x = (int32_t)dx;
    e.wheel.integer_y = (int32_t)dy;
    sdl.PushEvent(&e);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL J(nativeSdlSetFramebufferSize)(JNIEnv *env, jobject thiz, jint w, jint h) {
    (void)env; (void)thiz;
    flame_fbWidth = w;
    flame_fbHeight = h;
}

/// 게임이 마우스를 잡고 있으면 1, 아니면 0. SDL 창이 아직 없으면 -1.
/// 26.3 은 grab 을 SDL_SetWindowRelativeMouseMode 로 건다(InputConstants.grabMouse).
JNIEXPORT jint JNICALL J(nativeSdlIsGrabbing)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    void *w = flame_sdlWindow();
    return w ? (sdl.GetWindowRelativeMouseMode(w) ? 1 : 0) : -1;
}

/// MobileGlues 가 센 성공 스왑 수. 0 보다 크면 첫 프레임이 화면에 나간 것이다.
/// ⚠️ SDL 을 건드리지 않는다 — UI 쪽에서 SDL 을 조회하면 창을 만드는 게임 스레드와
///    락이 엉켜 메인 스레드가 멈췄다(실측).
JNIEXPORT jint JNICALL J(nativeMgSwapCount)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    static unsigned (*count)(void);
    if (!count) {
        void *h = dlopen("libmobileglues.so", RTLD_NOLOAD | RTLD_LAZY);
        if (!h) return -1;
        count = (unsigned (*)(void))dlsym(h, "flame_mg_swap_count");
        if (!count) return -1;
    }
    return (jint)count();
}

/// 게임(JVM)이 SDL_Init 을 부르기 전에 "메인 준비됨"을 알린다.
///
/// ⚠️ SDL_main 을 쓰지 않는 구조라 이걸 안 하면 SDL_Init 이 거절한다:
///      Unable to initialize SDL: Application didn't initialize properly,
///      did you include SDL_main.h in the file containing your main() function?
///    (iOS 포팅에서도 같은 자리에서 걸렸다 — 그쪽은 프로브가 대신 불러 줬다)
JNIEXPORT jboolean JNICALL J(nativeSdlSetMainReady)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!flame_sdlBind() || !sdl.SetMainReady) return JNI_FALSE;
    sdl.SetMainReady();
    LOG("SDL_SetMainReady 완료");
    return JNI_TRUE;
}
