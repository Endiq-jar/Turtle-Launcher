/*
 * V3 input bridge implementation.
 *
 * Status:
 * - Active development
 * - Works with some bugs:
 *  + Modded versions gives broken stuff..
 *
 * 
 * - Implements glfwSetCursorPos() to handle grab camera pos correctly.
 */

#include <assert.h>
#include <dlfcn.h>
#include <jni.h>
#include <libgen.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <math.h>

#define TAG __FILE_NAME__
#include "log.h"
#include "utils.h"
#include "environ/environ.h"
#include "jvm_hooks/jvm_hooks.h"

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007

#define CB_EVENT_CHAR              1000
#define CB_EVENT_CHAR_MODS         1001
#define CB_EVENT_CURSOR_ENTER      1002
#define CB_EVENT_CURSOR_POS        1003
#define CB_EVENT_FRAMEBUFFER_SIZE  1004
#define CB_EVENT_KEY               1005
#define CB_EVENT_MOUSE_BUTTON      1006
#define CB_EVENT_SCROLL            1007
#define CB_EVENT_WINDOW_SIZE       1008

#define TRY_ATTACH_ENV(env_name, vm, error_message, then) JNIEnv* env_name;\
do {                                                                       \
    env_name = get_attached_env(vm);                                       \
    if(env_name == NULL) {                                                 \
        printf(error_message);                                             \
        then                                                               \
    }                                                                      \
} while(0)

static void registerFunctions(JNIEnv *env);

// ─── ADDED: forward declaration for RegisterNatives in JNI_OnLoad ─────────
JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv *env, jclass clazz);
// ──────────────────────────────────────────────────────────────────────────

// 파일 상단 어딘가 (전역)
static jclass g_cbClass = NULL;
static jclass g_mbCbIClass = NULL;
static jmethodID g_cbGet = NULL;
static jmethodID g_mbInvoke = NULL;

static jclass g_cpCbIClass = NULL;
static jmethodID g_cpInvoke = NULL;

static jmethodID g_fbSizeInvoke = NULL;
static jmethodID g_winSizeInvoke = NULL;
static jmethodID g_cursorEnterInvoke = NULL;

// nativeSendScreenSize(=UI 스레드) 가 "리사이즈 콜백을 다시 쏴 달라"고 요청하는 플래그.
// 실제 dispatch 는 렌더 스레드(pojavBootDispatchFramebufferSize)에서만 수행한다.
static atomic_int g_pendingSizeDispatch = 0;

// input_bridge_v3.c 상단 헬퍼 추가
static jclass findAppClass(JNIEnv* env, const char* name) {
    // 1) 평범하게 시도
    jclass c = (*env)->FindClass(env, name);
    if (c != NULL) return c;
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    // 2) 이미 잡아둔 GLFW 클래스의 클래스로더로 로드
    //    (vmGlfwClass 는 JNI_OnLoad 의 runtime VM 분기에서 NewGlobalRef 됨)
    if (pojav_environ->vmGlfwClass == NULL) return NULL;

    jclass classClass = (*env)->FindClass(env, "java/lang/Class");
    jmethodID getCL = (*env)->GetMethodID(env, classClass, "getClassLoader",
                                          "()Ljava/lang/ClassLoader;");
    jobject loader = (*env)->CallObjectMethod(env, pojav_environ->vmGlfwClass, getCL);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NULL; }
    if (loader == NULL) return NULL;

    jclass clClass = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID loadClass = (*env)->GetMethodID(env, clClass, "loadClass",
                                              "(Ljava/lang/String;)Ljava/lang/Class;");

    // FindClass 는 'org/lwjgl/...' 슬래시 표기, loadClass 는 'org.lwjgl...' 점 표기
    char dotted[256];
    size_t n = strlen(name);
    if (n >= sizeof(dotted)) return NULL;
    for (size_t i = 0; i <= n; i++) dotted[i] = (name[i] == '/') ? '.' : name[i];

    jstring jname = (*env)->NewStringUTF(env, dotted);
    jclass result = (jclass)(*env)->CallObjectMethod(env, loader, loadClass, jname);
    (*env)->DeleteLocalRef(env, jname);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NULL; }
    return result;
}


jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        LOGI("Saving DVM environ...");
        //Save dalvik global JavaVM pointer
        pojav_environ->dalvikJavaVMPtr = vm;
        JNIEnv *dvEnv;
        (*vm)->GetEnv(vm, (void**) &dvEnv, JNI_VERSION_1_4);
        jclass localBridge = (*dvEnv)->FindClass(dvEnv, "org/lwjgl/glfw/CallbackBridge");
        if (localBridge == NULL) {
            if ((*dvEnv)->ExceptionCheck(dvEnv)) (*dvEnv)->ExceptionClear(dvEnv);
            LOGE("JNI_OnLoad: FindClass(org/lwjgl/glfw/CallbackBridge) FAILED");
            pojav_environ->bridgeClazz = NULL;
        } else {
            pojav_environ->bridgeClazz = (*dvEnv)->NewGlobalRef(dvEnv, localBridge);
        }
        if (pojav_environ->bridgeClazz != NULL) {
            pojav_environ->method_accessAndroidClipboard = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
            if ((*dvEnv)->ExceptionCheck(dvEnv)) (*dvEnv)->ExceptionClear(dvEnv);
            pojav_environ->method_onGrabStateChanged = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
            if ((*dvEnv)->ExceptionCheck(dvEnv)) (*dvEnv)->ExceptionClear(dvEnv);
            pojav_environ->method_onDirectInputEnable = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "onDirectInputEnable", "()V");
            if ((*dvEnv)->ExceptionCheck(dvEnv)) (*dvEnv)->ExceptionClear(dvEnv);
            LOGI("JNI_OnLoad: bridge methods resolved: clip=%p grab=%p dinput=%p",
                 pojav_environ->method_accessAndroidClipboard,
                 pojav_environ->method_onGrabStateChanged,
                 pojav_environ->method_onDirectInputEnable);
        }
        pojav_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (pojav_environ->dalvikJavaVMPtr != vm) {
        LOGI("Saving JVM environ...");
        pojav_environ->runtimeJavaVMPtr = vm;
        JNIEnv *vmEnv;
        (*vm)->GetEnv(vm, (void**) &vmEnv, JNI_VERSION_1_4);
        pojav_environ->vmGlfwClass = (*vmEnv)->NewGlobalRef(vmEnv, (*vmEnv)->FindClass(vmEnv, "org/lwjgl/glfw/GLFW"));
        pojav_environ->method_glftSetWindowAttrib = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "glfwSetWindowAttrib", "(JII)V");
        pojav_environ->method_internalWindowSizeChanged = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "internalWindowSizeChanged", "(JII)V");
        pojav_environ->method_internalChangeMonitorSize = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "internalChangeMonitorSize", "(II)V");
        jfieldID field_keyDownBuffer = (*vmEnv)->GetStaticFieldID(vmEnv, pojav_environ->vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject keyDownBufferJ = (*vmEnv)->GetStaticObjectField(vmEnv, pojav_environ->vmGlfwClass, field_keyDownBuffer);
        pojav_environ->keyDownBuffer = (*vmEnv)->GetDirectBufferAddress(vmEnv, keyDownBufferJ);
        jfieldID field_mouseDownBuffer = (*vmEnv)->GetStaticFieldID(vmEnv, pojav_environ->vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject mouseDownBufferJ = (*vmEnv)->GetStaticObjectField(vmEnv, pojav_environ->vmGlfwClass, field_mouseDownBuffer);
        pojav_environ->mouseDownBuffer = (*vmEnv)->GetDirectBufferAddress(vmEnv, mouseDownBufferJ);

        // ─── ADDED: GLFW.class의 native들을 RegisterNatives로 명시 등록 ───────
        // LWJGL이 libglfw.so를 ndlopen으로 직접 dlopen해서 JVM ClassLoader의
        // NativeLibrary 목록에 등록되지 않은 케이스 대응. 안 하면 UnsatisfiedLinkError.
        {
            JNINativeMethod glfwNatives[] = {
                    {"internalGetGamepadDataPointer", "()J",
                     (void*)&Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer},
            };
            if ((*vmEnv)->RegisterNatives(vmEnv, pojav_environ->vmGlfwClass,
                                          glfwNatives,
                                          sizeof(glfwNatives)/sizeof(glfwNatives[0])) != 0) {
                LOGE("Failed to RegisterNatives for GLFW (gamepad data pointer)");
                if ((*vmEnv)->ExceptionCheck(vmEnv)) (*vmEnv)->ExceptionClear(vmEnv);
            } else {
                LOGI("RegisterNatives OK: GLFW.internalGetGamepadDataPointer");
            }
        }
        // ──────────────────────────────────────────────────────────────────────

        hookExec(vmEnv);
        installLwjglDlopenHook(vmEnv);
        installEMUIIteratorMititgation(vmEnv);
    }

    if(pojav_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4);
        registerFunctions(env);
    }
    pojav_environ->isGrabbing = JNI_FALSE;

    return JNI_VERSION_1_4;
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback( \
        JNIEnv* env, jclass cls, jlong window, jlong callbackptr) { \
    LOGI("[CB_REG] " #NAME ": mcWindow=0x%llx cb=0x%llx mainBundle=0x%llx showing=0x%llx", \
         (unsigned long long)window, (unsigned long long)callbackptr, \
         (unsigned long long)pojav_environ->mainWindowBundle, \
         (unsigned long long)pojav_environ->showingWindow); \
    void** oldCallback = (void**) &pojav_environ->GLFW_invoke_##NAME; \
    pojav_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) *oldCallback; \
}



ADD_CALLBACK_WWIN(Char)
ADD_CALLBACK_WWIN(CharMods)
ADD_CALLBACK_WWIN(CursorEnter)
ADD_CALLBACK_WWIN(CursorPos)
ADD_CALLBACK_WWIN(Key)
ADD_CALLBACK_WWIN(MouseButton)
ADD_CALLBACK_WWIN(Scroll)
ADD_CALLBACK_WWIN(FramebufferSize)
ADD_CALLBACK_WWIN(WindowSize)

#undef ADD_CALLBACK_WWIN

void updateMonitorSize(int width, int height) {
    if (!pojav_environ->method_internalChangeMonitorSize) return;
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalChangeMonitorSize, width, height);
}
void updateWindowSize(void* window) {
    if (!pojav_environ->method_internalWindowSizeChanged) return;
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalWindowSizeChanged, (jlong)window, pojav_environ->savedWidth, pojav_environ->savedHeight);
}

// showingWindow 가 아직 0이면 mainWindowBundle 로 폴백.
// LWJGL이 nglfwSetShowingWindow 를 호출하기 전이라도 클릭이 안 dropped 되도록.
static inline void* effective_window() {
    long w = pojav_environ->showingWindow;
    if (w != 0) return (void*) w;
    if (pojav_environ->mainWindowBundle != NULL) {
        // 한 번이라도 fetch 됐으면 showingWindow 에도 캐싱해서 다음부터 빠르게
        pojav_environ->showingWindow = (long)(uintptr_t) pojav_environ->mainWindowBundle;
        return pojav_environ->mainWindowBundle;
    }
    return NULL;
}


static JNIEnv* getRuntimeEnv(void) {
    if (!pojav_environ->runtimeJavaVMPtr) return NULL;
    JNIEnv* env = NULL;
    jint stat = (*pojav_environ->runtimeJavaVMPtr)->GetEnv(
            pojav_environ->runtimeJavaVMPtr, (void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) {
        (*pojav_environ->runtimeJavaVMPtr)->AttachCurrentThread(
                pojav_environ->runtimeJavaVMPtr, &env, NULL);
    }
    return env;
}

static jboolean lazyInitOther(JNIEnv* env) {
    if (g_fbSizeInvoke && g_winSizeInvoke && g_cursorEnterInvoke) return JNI_TRUE;
    if (!g_cbGet) {
        jclass cb = findAppClass(env, "org/lwjgl/system/Callback");
        if (!cb) { (*env)->ExceptionClear(env); return JNI_FALSE; }
        g_cbClass = (*env)->NewGlobalRef(env, cb);
        g_cbGet = (*env)->GetStaticMethodID(env, cb, "get", "(J)Lorg/lwjgl/system/CallbackI;");
    }
    jclass fb = findAppClass(env, "org/lwjgl/glfw/GLFWFramebufferSizeCallbackI");
    if (fb) g_fbSizeInvoke = (*env)->GetMethodID(env, fb, "invoke", "(JII)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    jclass ws = findAppClass(env, "org/lwjgl/glfw/GLFWWindowSizeCallbackI");
    if (ws) g_winSizeInvoke = (*env)->GetMethodID(env, ws, "invoke", "(JII)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    jclass ce = findAppClass(env, "org/lwjgl/glfw/GLFWCursorEnterCallbackI");
    if (ce) g_cursorEnterInvoke = (*env)->GetMethodID(env, ce, "invoke", "(JZ)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    LOGI("[OTHER_DIRECT] init: fb=%p ws=%p ce=%p", g_fbSizeInvoke, g_winSizeInvoke, g_cursorEnterInvoke);
    return JNI_TRUE;
}

static void dispatchFramebufferSizeDirect(jint w, jint h) {
    JNIEnv* env = getRuntimeEnv();
    if (!env) return;
    lazyInitOther(env);
    if (!g_fbSizeInvoke || !pojav_environ->GLFW_invoke_FramebufferSize) return;
    jobject cb = (*env)->CallStaticObjectMethod(env, g_cbClass, g_cbGet,
                                                (jlong)(uintptr_t)pojav_environ->GLFW_invoke_FramebufferSize);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (!cb) { LOGI("[FB_DIRECT] null cb for 0x%llx",
                    (unsigned long long)(uintptr_t)pojav_environ->GLFW_invoke_FramebufferSize); return; }
    LOGI("[FB_DIRECT] invoke w=%d h=%d", w, h);
    (*env)->CallVoidMethod(env, cb, g_fbSizeInvoke,
                           (jlong)(uintptr_t)effective_window(), w, h);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cb);
}

static void dispatchWindowSizeDirect(jint w, jint h) {
    JNIEnv* env = getRuntimeEnv();
    if (!env) return;
    lazyInitOther(env);
    if (!g_winSizeInvoke || !pojav_environ->GLFW_invoke_WindowSize) return;
    jobject cb = (*env)->CallStaticObjectMethod(env, g_cbClass, g_cbGet,
                                                (jlong)(uintptr_t)pojav_environ->GLFW_invoke_WindowSize);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (!cb) return;
    LOGI("[WS_DIRECT] invoke w=%d h=%d", w, h);
    (*env)->CallVoidMethod(env, cb, g_winSizeInvoke,
                           (jlong)(uintptr_t)effective_window(), w, h);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cb);
}

__attribute__((unused)) static void dispatchCursorEnterDirect(jboolean entered) {
    JNIEnv* env = getRuntimeEnv();
    if (!env) return;
    lazyInitOther(env);
    if (!g_cursorEnterInvoke || !pojav_environ->GLFW_invoke_CursorEnter) return;
    jobject cb = (*env)->CallStaticObjectMethod(env, g_cbClass, g_cbGet,
                                                (jlong)(uintptr_t)pojav_environ->GLFW_invoke_CursorEnter);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (!cb) return;
    (*env)->CallVoidMethod(env, cb, g_cursorEnterInvoke,
                           (jlong)(uintptr_t)effective_window(), entered);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cb);
}

static jboolean lazyInitCpDirect(JNIEnv* env) {
    if (g_cpInvoke) return JNI_TRUE;
    // Callback class 는 lazyInitMbDirect 에서 이미 init 됐다고 가정
    if (!g_cbGet) {
        // 안전하게 한 번 더
        jclass cb = findAppClass(env, "org/lwjgl/system/Callback");
        if (!cb) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            return JNI_FALSE;
        }
        g_cbClass = (*env)->NewGlobalRef(env, cb);
        g_cbGet = (*env)->GetStaticMethodID(env, cb, "get", "(J)Lorg/lwjgl/system/CallbackI;");
    }
    jclass cp = findAppClass(env, "org/lwjgl/glfw/GLFWCursorPosCallbackI");
    if (!cp) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGI("[CP_DIRECT] FindClass failed"); return JNI_FALSE;
    }
    g_cpCbIClass = (*env)->NewGlobalRef(env, cp);
    g_cpInvoke = (*env)->GetMethodID(env, cp, "invoke", "(JDD)V");  // (long, double, double)
    if (!g_cpInvoke) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGI("[CP_DIRECT] GetMethodID failed"); return JNI_FALSE;
    }
    LOGI("[CP_DIRECT] init OK");
    return JNI_TRUE;
}

static void dispatchCursorPosDirect(jdouble x, jdouble y) {
    JNIEnv* env = getRuntimeEnv();
    if (!env) return;
    if (!lazyInitCpDirect(env)) return;
    if (!pojav_environ->GLFW_invoke_CursorPos) return;

    jlong cbAddr = (jlong)(uintptr_t) pojav_environ->GLFW_invoke_CursorPos;
    jobject cbObj = (*env)->CallStaticObjectMethod(env, g_cbClass, g_cbGet, cbAddr);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (!cbObj) {
        static int once = 0;
        if (!once++) LOGI("[CP_DIRECT] Callback.get returned null for 0x%llx", (unsigned long long)cbAddr);
        return;
    }

    // 한 번만 클래스명 찍기
    static jboolean logged_cls = JNI_FALSE;
    if (!logged_cls) {
        jclass oc = (*env)->GetObjectClass(env, cbObj);
        jclass cc = (*env)->FindClass(env, "java/lang/Class");
        jmethodID gn = (*env)->GetMethodID(env, cc, "getName", "()Ljava/lang/String;");
        jstring jn = (jstring)(*env)->CallObjectMethod(env, oc, gn);
        const char* n = (*env)->GetStringUTFChars(env, jn, NULL);
        LOGI("[CP_DIRECT] Callback class: %s", n);
        (*env)->ReleaseStringUTFChars(env, jn, n);
        logged_cls = JNI_TRUE;
    }

    jlong win = (jlong)(uintptr_t) effective_window();
    (*env)->CallVoidMethod(env, cbObj, g_cpInvoke, win, (jdouble)x, (jdouble)y);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, cbObj);
}


static jboolean lazyInitMbDirect(JNIEnv* env) {
    if (g_cbGet && g_mbInvoke) return JNI_TRUE;
    jclass cb = findAppClass(env, "org/lwjgl/system/Callback");   // ★ 교체
    if (!cb) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGI("[MB_DIRECT] FindClass Callback failed"); return JNI_FALSE;
    }
    g_cbClass = (*env)->NewGlobalRef(env, cb);
    g_cbGet = (*env)->GetStaticMethodID(env, cb, "get", "(J)Lorg/lwjgl/system/CallbackI;");
    if (!g_cbGet) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGI("[MB_DIRECT] GetStaticMethodID Callback.get failed"); return JNI_FALSE;
    }
    jclass mb = findAppClass(env, "org/lwjgl/glfw/GLFWMouseButtonCallbackI");  // ★ 교체
    if (!mb) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGI("[MB_DIRECT] FindClass GLFWMouseButtonCallbackI failed"); return JNI_FALSE;
    }
    g_mbCbIClass = (*env)->NewGlobalRef(env, mb);
    g_mbInvoke = (*env)->GetMethodID(env, mb, "invoke", "(JIII)V");
    if (!g_mbInvoke) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGI("[MB_DIRECT] GetMethodID invoke failed"); return JNI_FALSE;
    }
    LOGI("[MB_DIRECT] init OK: cbClass=%p mbCbI=%p get=%p invoke=%p",
         g_cbClass, g_mbCbIClass, g_cbGet, g_mbInvoke);
    return JNI_TRUE;
}

static void dispatchMouseButtonDirect(jint button, jint action, jint mods) {
    JNIEnv* env = getRuntimeEnv();
    if (!env) { LOGI("[MB_DIRECT] no runtime env"); return; }
    if (!lazyInitMbDirect(env)) return;

    jlong cbAddr = (jlong)(uintptr_t) pojav_environ->GLFW_invoke_MouseButton;
    jobject cbObj = (*env)->CallStaticObjectMethod(env, g_cbClass, g_cbGet, cbAddr);
    if ((*env)->ExceptionCheck(env)) {
        LOGI("[MB_DIRECT] Callback.get threw");
        (*env)->ExceptionClear(env);
        return;
    }
    if (!cbObj) { LOGI("[MB_DIRECT] Callback.get returned null for 0x%llx",
                       (unsigned long long)cbAddr); return; }

    jlong win = (jlong)(uintptr_t) effective_window();
    LOGI("[MB_DIRECT] invoking: win=0x%llx btn=%d act=%d mods=%d",
         (unsigned long long)win, button, action, mods);
    (*env)->CallVoidMethod(env, cbObj, g_mbInvoke, win, button, action, mods);
    if ((*env)->ExceptionCheck(env)) {
        jthrowable exc = (*env)->ExceptionOccurred(env);
        (*env)->ExceptionClear(env);
        jclass ec = (*env)->GetObjectClass(env, exc);
        jmethodID ts = (*env)->GetMethodID(env, ec, "toString", "()Ljava/lang/String;");
        jstring jm = (jstring)(*env)->CallObjectMethod(env, exc, ts);
        const char* m = (*env)->GetStringUTFChars(env, jm, NULL);
        LOGI("[MB_DIRECT] invoke threw: %s", m);
        (*env)->ReleaseStringUTFChars(env, jm, m);
    } else {
        LOGI("[MB_DIRECT] invoke returned cleanly");
    }
    (*env)->DeleteLocalRef(env, cbObj);
}

void pojavPumpEvents(void* window) {
    static int pump_counter = 0;
    if (++pump_counter % 60 == 0) {
        LOGI("[PUMP] pojavPumpEvents tick #%d outIdx=%zu targetIdx=%zu",
             pump_counter, pojav_environ->outEventIndex, pojav_environ->outTargetIndex);
    }

    if(pojav_environ->shouldUpdateMouse) {
        pojav_environ->GLFW_invoke_CursorPos(window, floor(pojav_environ->cursorX),
                                             floor(pojav_environ->cursorY));
    }
    if(pojav_environ->shouldUpdateMonitorSize) {
        updateWindowSize(window);
    }

    size_t index = pojav_environ->outEventIndex;
    size_t targetIndex = pojav_environ->outTargetIndex;

    while (targetIndex != index) {
        GLFWInputEvent event = pojav_environ->events[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(pojav_environ->GLFW_invoke_Char) pojav_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(pojav_environ->GLFW_invoke_CharMods) pojav_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_KEY:
                if(pojav_environ->GLFW_invoke_Key) pojav_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(pojav_environ->GLFW_invoke_MouseButton) pojav_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            case EVENT_TYPE_SCROLL:
                if(pojav_environ->GLFW_invoke_Scroll) pojav_environ->GLFW_invoke_Scroll(window, event.i1, event.i2);
                break;
        }

        index++;
        if (index >= EVENT_WINDOW_SIZE)
            index -= EVENT_WINDOW_SIZE;
    }

    // The out target index is updated by the rewinder
}

// 부팅 후 framebuffer/window size 를 MC 에 알림.
// egl_bridge.c::pojavSwapBuffers 에서 매 프레임(=실제 swap) 호출된다 → 항상 렌더 스레드.
//
// [왜 한 번이 아니라 여러 번인가]
//   예전엔 'static int dispatched' 로 평생 1회만 dispatch 했다. 그 1회는 콜백이 처음 등록되는
//   순간(= OptiFine init 직후, FML 모드 로딩/타이틀 렌더 루프 시작 '전')에 떨어진다.
//   1.12.2 는 Display.wasResized() 플래그로 리사이즈를 처리하는데, 이 이른 콜백은 타이틀 루프가
//   돌기 전에 플래그가 소진/덮어쓰여 버리고, 이후 재발화가 없어 init 기본 크기(854x480 등)에
//   그대로 굳는다(OptiFine 1.12.2 에서 재현; 바닐라는 타이밍이 달라 살아남음).
//   → 이 함수는 'swap 이 일어난 프레임'에서만 불리므로, 프레임이 쌓이기 시작한 뒤(=타이틀 루프가
//     실제로 도는 동안) 같은 크기를 몇 번 더 재dispatch 하면 반드시 한 번은 resize 가 박힌다.
//     기존과 동일 스레드/동일 env 라 안전하고, 초반 한정이라 FBO 재생성도 몇 번에 그친다.
void pojavBootDispatchFramebufferSize(void) {
    if (pojav_environ->savedWidth <= 0 || pojav_environ->savedHeight <= 0) return;
    if (!pojav_environ->GLFW_invoke_FramebufferSize) return;  // 콜백 등록 전엔 미발화

    static int  firstDispatched = 0;  // 콜백 등록 후 최초 1회 보장
    static long frame = 0;            // 실제 렌더된 프레임 수(= swap 횟수)
    frame++;

    // (A) 명시적 재요청: nativeSendScreenSize(런타임 회전/Kotlin 재전송)가 pending 을 세움.
    int pending = atomic_exchange_explicit(&g_pendingSizeDispatch, 0, memory_order_acq_rel);

    // (B) 부팅 타이밍 보정: 최초 1회 + 초반 프레임 몇 곳에서 재dispatch.
    //     프레임은 타이틀 루프가 돌기 시작해야 쌓이므로, 15/30/60.../300 은 타이틀 루프 안에 떨어진다.
    int bootKick = (!firstDispatched) ||
                   (frame == 15) || (frame == 30) ||
                   (frame <= 300 && (frame % 60 == 0));

    if (!pending && !bootKick) return;
    firstDispatched = 1;

    LOGI("[BOOT] dispatching FramebufferSize/WindowSize %dx%d (frame=%ld pending=%d)",
         pojav_environ->savedWidth, pojav_environ->savedHeight, frame, pending);
    dispatchFramebufferSizeDirect(pojav_environ->savedWidth, pojav_environ->savedHeight);
    dispatchWindowSizeDirect(pojav_environ->savedWidth, pojav_environ->savedHeight);
}

/** Prepare the library for sending out callbacks to all windows */
void pojavStartPumping() {
    static int start_counter = 0;
    if (++start_counter % 60 == 0) {
        LOGI("[PUMP] StartPumping tick #%d eventCounter=%zu",
             start_counter, atomic_load_explicit(&pojav_environ->eventCounter, memory_order_acquire));
    }

    size_t counter = atomic_load_explicit(&pojav_environ->eventCounter, memory_order_acquire);
    size_t index = pojav_environ->outEventIndex;

    unsigned targetIndex = index + counter;
    if (targetIndex >= EVENT_WINDOW_SIZE)
        targetIndex -= EVENT_WINDOW_SIZE;

    // Only accessed by one unique thread, no need for atomic store
    pojav_environ->inEventCount = counter;
    pojav_environ->outTargetIndex = targetIndex;

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((pojav_environ->cLastX != pojav_environ->cursorX || pojav_environ->cLastY != pojav_environ->cursorY) && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->cLastX = pojav_environ->cursorX;
        pojav_environ->cLastY = pojav_environ->cursorY;
        pojav_environ->shouldUpdateMouse = true;
    }
    if(pojav_environ->shouldUpdateMonitorSize) {
        // Perform a monitor size update here to avoid doing it on every single window
        updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
        // Mark the monitor size as consumed (since GLFW was made aware of it)
        pojav_environ->monitorSizeConsumed = true;
    }
}

/** Prepare the library for the next round of new events */
void pojavStopPumping() {
    static int stop_counter = 0;
    if (++stop_counter % 60 == 0) {
        LOGI("[PUMP] StopPumping tick #%d", stop_counter);
    }

    pojav_environ->outEventIndex = pojav_environ->outTargetIndex;

    // New events may have arrived while pumping, so remove only the difference before the start and end of execution
    atomic_fetch_sub_explicit(&pojav_environ->eventCounter, pojav_environ->inEventCount, memory_order_acquire);
    // Make sure the next frame won't send mouse or monitor updates if it's unnecessary
    pojav_environ->shouldUpdateMouse = false;
    // Only reset the update flag if the monitor size was consumed by pojavStartPumping. This
    // will delay the update to next frame if it had occured between pojavStartPumping and pojavStopPumping,
    // but it's better than not having it apply at all
    if(pojav_environ->shouldUpdateMonitorSize && pojav_environ->monitorSizeConsumed) {
        pojav_environ->shouldUpdateMonitorSize = false;
        pojav_environ->monitorSizeConsumed = false;
    }

}


JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jobject xpos,
                                           jobject ypos) {
    *(double*)(*env)->GetDirectBufferAddress(env, xpos) = pojav_environ->cursorX;
    *(double*)(*env)->GetDirectBufferAddress(env, ypos) = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(__attribute__((unused)) jlong window, jint lengthx, jdouble* xpos, jint lengthy, jdouble* ypos) {
    *xpos = pojav_environ->cursorX;
    *ypos = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window,
                                            jdoubleArray xpos, jdoubleArray ypos) {
    (*env)->SetDoubleArrayRegion(env, xpos, 0,1, &pojav_environ->cursorX);
    (*env)->SetDoubleArrayRegion(env, ypos, 0,1, &pojav_environ->cursorY);
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) jlong window, jdouble xpos,
                                                                         jdouble ypos) {
    pojav_environ->cLastX = pojav_environ->cursorX = xpos;
    pojav_environ->cLastY = pojav_environ->cursorY = ypos;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jdouble xpos,
                                          jdouble ypos) {
    JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(window, xpos, ypos);
}



void sendData(int type, int i1, int i2, int i3, int i4) {
    GLFWInputEvent *event = &pojav_environ->events[pojav_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    if (++pojav_environ->inEventIndex >= EVENT_WINDOW_SIZE)
        pojav_environ->inEventIndex -= EVENT_WINDOW_SIZE;

    atomic_fetch_add_explicit(&pojav_environ->eventCounter, 1, memory_order_acquire);
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    pojav_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(
        JNIEnv* env, jclass clazz, jint action, jbyteArray copySrc) {

    JNIEnv *dalvikEnv;
    // GetEnv로 먼저 확인 — 이미 attach 되어 있으면 그대로 사용
    jint envStat = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(
            pojav_environ->dalvikJavaVMPtr, (void**)&dalvikEnv, JNI_VERSION_1_6);

    if (envStat == JNI_EDETACHED) {
        // 처음 들어온 스레드만 attach. 이후로는 detach 하지 않음.
        (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    }
    assert(dalvikEnv != NULL);
    assert(pojav_environ->bridgeClazz != NULL);

    char *copySrcC;
    jstring copyDst = NULL;
    if (copySrc) {
        copySrcC = (char *)((*env)->GetByteArrayElements(env, copySrc, NULL));
        copyDst = (*dalvikEnv)->NewStringUTF(dalvikEnv, copySrcC);
    }

    jstring pasteDst = convertStringJVM(dalvikEnv, env,
                                        (jstring) (*dalvikEnv)->CallStaticObjectMethod(
                                                dalvikEnv, pojav_environ->bridgeClazz,
                                                pojav_environ->method_accessAndroidClipboard, action, copyDst));

    if (copySrc) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);
        (*env)->ReleaseByteArrayElements(env, copySrc, (jbyte *)copySrcC, 0);
    }

    // ★ DetachCurrentThread 호출 제거 ★

    return pasteDst;
}

JNIEXPORT jboolean JNICALL JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean inputReady) {
#ifdef DEBUG
    LOGD("Debug: Changing input state, isReady=%d, pojav_environ->isUseStackQueueCall=%d\n", inputReady, pojav_environ->isUseStackQueueCall);
#endif
    LOGI("Input ready: %i", inputReady);
    pojav_environ->isInputReady = inputReady;
    return pojav_environ->isUseStackQueueCall;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean inputReady) {
    return JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(inputReady);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
    // grab 상태는 무조건 먼저 반영한다 (알림이 실패하더라도 입력 경로는 올바르게 동작해야 함)
    pojav_environ->isGrabbing = grabbing;

    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        LOGW("nativeSetGrabbing: dalvik VM not ready, skipping notify (grab=%d)", grabbing);
        return;
    }
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeSetGrabbing failed!\n", return;);

    if (pojav_environ->bridgeClazz == NULL) {
        LOGW("nativeSetGrabbing: bridgeClazz null, skipping notify");
        return;
    }

    // 메서드 ID lazy 재확보 — JNI_OnLoad 시점 lookup 이 실패/누락됐을 수 있으므로
    //  여기서 다시 시도한다. 그래도 못 찾으면 abort 하지 말고 조용히 스킵한다.
    //  (LWJGL2 의 Mouse.create -> Display.create 경로에서 예외가 나면 게임이 멈춘다)
    if (pojav_environ->method_onGrabStateChanged == NULL) {
        pojav_environ->method_onGrabStateChanged = (*dvm_env)->GetStaticMethodID(
                dvm_env, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        if ((*dvm_env)->ExceptionCheck(dvm_env)) (*dvm_env)->ExceptionClear(dvm_env);
    }
    if (pojav_environ->method_onGrabStateChanged == NULL) {
        LOGW("nativeSetGrabbing: onGrabStateChanged(Z)V not found, skipping notify (grab=%d)", grabbing);
        return;
    }

    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz,
                                     pojav_environ->method_onGrabStateChanged, grabbing);
    if ((*dvm_env)->ExceptionCheck(dvm_env)) {
        // 자바 쪽 콜백에서 예외가 나도 게임 부팅을 막지 않는다.
        (*dvm_env)->ExceptionClear(dvm_env);
        LOGW("nativeSetGrabbing: onGrabStateChanged threw, cleared (grab=%d)", grabbing);
    }
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(__attribute__((unused)) JNIEnv *env, __attribute__((unused))  jclass clazz) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeEnableGamepadDirectInput failed!\n", return JNI_FALSE;);
    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz, pojav_environ->method_onDirectInputEnable);
    return JNI_TRUE;
}

jboolean critical_send_char(jchar codepoint) {
    if (pojav_environ->GLFW_invoke_Char && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(CB_EVENT_CHAR, codepoint, 0, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Char((void*) pojav_environ->showingWindow, (unsigned int) codepoint);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint) {
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    if (pojav_environ->GLFW_invoke_CharMods && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(CB_EVENT_CHAR_MODS, (int) codepoint, mods, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_CharMods((void*) pojav_environ->showingWindow, codepoint, mods);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char_mods(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}
/*
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorEnter(JNIEnv* env, jclass clazz, jint entered) {
    if (pojav_environ->GLFW_invoke_CursorEnter && pojav_environ->isInputReady) {
        pojav_environ->GLFW_invoke_CursorEnter(pojav_environ->showingWindow, entered);
    }
}
*/

void critical_send_cursor_pos(jfloat x, jfloat y) {
    LOGI("[DIAG] critical_send_cp: x=%.0f y=%.0f savedW=%d savedH=%d invoke=%p ready=%d enter_invoke=%p entered=%d", x, y, pojav_environ->savedWidth, pojav_environ->savedHeight, (void*)pojav_environ->GLFW_invoke_CursorPos, pojav_environ->isInputReady, (void*)pojav_environ->GLFW_invoke_CursorEnter, pojav_environ->isCursorEntered);
#ifdef DEBUG
    LOGD("Sending cursor position \n");
#endif
    if (pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady) {
#ifdef DEBUG
        LOGD("pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady \n");
#endif
        if (!pojav_environ->isCursorEntered) {
            pojav_environ->isCursorEntered = true;
            if (pojav_environ->GLFW_invoke_CursorEnter)
                pojav_environ->GLFW_invoke_CursorEnter((void*)pojav_environ->showingWindow, 1);
        }

        if (!pojav_environ->isUseStackQueueCall) {
            // 비-큐 경로: 즉시 한 번만 발화 (+ direct dispatch 도 같은 경로에서만)
            pojav_environ->GLFW_invoke_CursorPos((void*) pojav_environ->showingWindow, (double) x, (double) y);
            dispatchCursorPosDirect((jdouble)x, (jdouble)y);
        } else {
            // 큐 경로: 좌표만 저장. 실제 콜백 발화는 pojavPumpEvents 가 프레임당 1회 수행.
            //  여기서 직접 invoke / dispatch 하면 한 프레임에 이벤트가 중복 적재되어
            //  LWJGL2 호환 레이어(1.7.10/1.5.2)의 고정 마우스 버퍼가 오버플로우한다.
            pojav_environ->cursorX = x;
            pojav_environ->cursorY = y;
        }
    }
}

void noncritical_send_cursor_pos(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz,  jfloat x, jfloat y) {
    critical_send_cursor_pos(x, y);
}
#define max(a,b) \
   ({ __typeof__ (a) _a = (a); \
       __typeof__ (b) _b = (b); \
     _a > _b ? _a : _b; })
void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_Key && pojav_environ->isInputReady) {
        pojav_environ->keyDownBuffer[max(0, key-31)] = (jbyte) action;
        if (pojav_environ->isUseStackQueueCall) {
            sendData(CB_EVENT_KEY, key, scancode, action, mods);
        } else {
            pojav_environ->GLFW_invoke_Key((void*) pojav_environ->showingWindow, key, scancode, action, mods);
        }
    }
}
void noncritical_send_key(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}


void critical_send_mouse_button(jint button, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_MouseButton && pojav_environ->isInputReady) {
        LOGI("[DIAG] critical_send_mb: invoke=%p ready=%d showWin=%lx mainBundle=%p",
             pojav_environ->GLFW_invoke_MouseButton,
             pojav_environ->isInputReady,
             pojav_environ->showingWindow,
             pojav_environ->mainWindowBundle);

        if (pojav_environ->isUseStackQueueCall) {
            // 큐 경로: 큐에 1회만 적재. pojavPumpEvents 가 꺼내서 발화한다.
            //  (direct dispatch 는 호출하지 않는다 — 중복 발화 방지)
            sendData(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
        } else {
            // 비-큐 경로: 즉시 1회 발화 + direct dispatch
            pojav_environ->GLFW_invoke_MouseButton((void*) pojav_environ->showingWindow, button, action, mods);
            dispatchMouseButtonDirect(button, action, mods);
        }
    }
}

void noncritical_send_mouse_button(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

void critical_send_screen_size(jint width, jint height) {
    pojav_environ->savedWidth = width;
    pojav_environ->savedHeight = height;
    // Even if there was call to pojavStartPumping that consumed the size, this call
    // might happen right after it (or right before pojavStopPumping)
    // So unmark the size as "consumed"
    pojav_environ->monitorSizeConsumed = false;
    pojav_environ->shouldUpdateMonitorSize = true;
    // Don't use the direct updates  for screen dimensions.
    // This is done to ensure that we have predictable conditions to correctly call
    // updateMonitorSize() and updateWindowSize() while on the render thread with an attached
    // JNIEnv.
    //
    // 단, framebuffer/window size 리사이즈 콜백 재발화를 렌더 스레드에 요청한다.
    // (여긴 UI 스레드라 직접 invoke 하지 않고 플래그만 세움 → pojavBootDispatchFramebufferSize 가 처리)
    // 이렇게 해야 부팅 후 회전/해상도 변경이나 Kotlin 의 재전송이 실제 resize 로 이어진다.
    atomic_store_explicit(&g_pendingSizeDispatch, 1, memory_order_release);
}

void noncritical_send_screen_size(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint width, jint height) {
    critical_send_screen_size(width, height);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (pojav_environ->GLFW_invoke_Scroll && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(CB_EVENT_SCROLL, (int)xoffset, (int)yoffset, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Scroll((void*) pojav_environ->showingWindow, (double) xoffset, (double) yoffset);
        }
    }
}

void noncritical_send_scroll(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}


JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jlong window) {
    pojav_environ->showingWindow = (jlong) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint attrib, jint value) {
    // Check for stack queue no longer necessary here as the JVM crash's origin is resolved
    if (!pojav_environ->showingWindow) {
        // If the window is not shown, there is nothing to do yet.
        return;
    }

    // We cannot use pojav_environ->runtimeJNIEnvPtr_JRE here because that environment is attached
    // on the thread that loaded pojavexec (which is the thread that first references the GLFW class)
    // But this method is only called from the Android UI thread

    // Technically the better solution would be to have a permanently attached env pointer stored
    // in environ for the Android UI thread but this is the only place that uses it
    // (very rarely, only in lifecycle callbacks) so i dont care

    TRY_ATTACH_ENV(jvm_env, pojav_environ->runtimeJavaVMPtr, "nativeSetWindowAttrib failed: %i", return;);

    (*jvm_env)->CallStaticVoidMethod(
            jvm_env, pojav_environ->vmGlfwClass,
            pojav_environ->method_glftSetWindowAttrib,
            (jlong) pojav_environ->showingWindow, attrib, value
    );

    // Attaching every time is annoying, so stick the attachment to the Android GUI thread around
}
const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
        {"nativeSendScreenSize", "(II)V", critical_send_screen_size}
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
        {"nativeSendScreenSize", "(II)V", noncritical_send_screen_size}
};


static bool criticalNativeAvailable;

void dvm_testCriticalNative(void* arg0, void* arg1, void* arg2, void* arg3) {
    if(arg0 != 0 && arg2 == 0 && arg3 == 0) {
        criticalNativeAvailable = false;
    }else if (arg0 == 0 && arg1 == 0){
        criticalNativeAvailable = true;
    }else {
        criticalNativeAvailable = false; // just to be safe
    }
}

static bool tryCriticalNative(JNIEnv *env) {
    static const JNINativeMethod testJNIMethod[] = {
            { "testCriticalNative", "(II)V", dvm_testCriticalNative}
    };
    jclass criticalNativeTest = (*env)->FindClass(env, "net/kdt/pojavlaunch/CriticalNativeTest");
    if(criticalNativeTest == NULL) {
        LOGD("No CriticalNativeTest class found !");
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID criticalNativeTestMethod = (*env)->GetStaticMethodID(env, criticalNativeTest, "invokeTest", "()V");
    (*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1);
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    (*env)->UnregisterNatives(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool use_critical_cc = tryCriticalNative(env);
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if(use_critical_cc) {
        LOGI("CriticalNative is available. Enjoy the 4.6x times faster input!");
    }else{
        LOGI("CriticalNative is not available. Upgrade, maybe?");
    }
    jint rc = (*env)->RegisterNatives(env,
                            bridge_class,
                            use_critical_cc ? critical_fcns : noncritical_fcns,
                            sizeof(critical_fcns)/sizeof(critical_fcns[0]));
    // 표와 CallbackBridge.java 가 어긋나면 NoSuchMethodError 가 걸린 채로 JNI_OnLoad 가
    // 끝나고, System.load 자리에서 그대로 터져 게임이 실행 즉시 튕긴다. 입력이 죽는 건
    // 고쳐야 할 버그지만, 그것 때문에 실행 자체를 막지는 않는다 — 여기서 걷어내고 남긴다.
    if (rc != JNI_OK) {
        LOGE("RegisterNatives(CallbackBridge) FAILED rc=%d — 입력이 동작하지 않는다 (CallbackBridge.java 의 native 선언을 표와 맞출 것)", rc);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv *env, jclass clazz) {
    return (jlong) &pojav_environ->gamepadState;
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv *env, jclass clazz) {
    return (*env)->NewDirectByteBuffer(env, &pojav_environ->gamepadState.buttons, sizeof(pojav_environ->gamepadState.buttons));
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv *env, jclass clazz) {
    return (*env)->NewDirectByteBuffer(env, &pojav_environ->gamepadState.axes, sizeof(pojav_environ->gamepadState.axes));
}
JNIEXPORT void JNICALL
Java_kr_co_donghyun_flamelauncher_presentation_MinecraftActivity_nativeSendKey(
        JNIEnv* env, jobject thiz, jint key, jint scancode, jint action, jint mods) {
    (void)env; (void)thiz;
    pojav_environ->isInputReady = JNI_TRUE;
    pojav_environ->isUseStackQueueCall = JNI_TRUE;
    critical_send_key(key, scancode, action, mods);
}

JNIEXPORT void JNICALL
Java_kr_co_donghyun_flamelauncher_presentation_MinecraftActivity_nativeSendMouseButton(
        JNIEnv* env, jobject thiz, jint button, jint action, jint mods) {
    (void)env; (void)thiz;
    pojav_environ->isInputReady = JNI_TRUE;
    pojav_environ->isUseStackQueueCall = JNI_TRUE;
    critical_send_mouse_button(button, action, mods);
}

JNIEXPORT void JNICALL
Java_kr_co_donghyun_flamelauncher_presentation_MinecraftActivity_nativeSendCursorPos(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y) {
    (void)env; (void)thiz;
    pojav_environ->isInputReady = JNI_TRUE;
    pojav_environ->cursorX = (int)x;
    pojav_environ->cursorY = (int)y;
    pojav_environ->isUseStackQueueCall = JNI_TRUE;
    critical_send_cursor_pos(x, y);
}

// LWJGL 3.3.6 CallbackBridge — stub implementations
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendData(
        JNIEnv *env, jclass cls,
        jboolean isAndroid, jint type, jstring data) {
    (void)cls;
    if (!data) {
        LOGI("[NSD] null data type=%d", type);
        return;
    }
    const char* str = (*env)->GetStringUTFChars(env, data, NULL);
    LOGI("[NSD] type=%d isAndroid=%d data='%s'", type, isAndroid, str);

    int i1 = 0, i2 = 0, i3 = 0, i4 = 0;
    float f1 = 0.0f, f2 = 0.0f;

    switch (type) {
        case CB_EVENT_MOUSE_BUTTON:
            if (sscanf(str, "%d,%d,%d", &i1, &i2, &i3) >= 2)
                critical_send_mouse_button(i1, i2, i3);
            break;
        case CB_EVENT_CURSOR_POS:
            if (sscanf(str, "%f,%f", &f1, &f2) == 2)
                critical_send_cursor_pos(f1, f2);
            break;
        case CB_EVENT_KEY:
            if (sscanf(str, "%d,%d,%d,%d", &i1, &i2, &i3, &i4) >= 3)
                critical_send_key(i1, i2, i3, i4);
            break;
        case CB_EVENT_SCROLL:
            if (sscanf(str, "%f,%f", &f1, &f2) == 2)
                critical_send_scroll(f1, f2);   // (이 함수 없으면 GLFW_invoke_Scroll 직접)
            break;
        case CB_EVENT_CHAR:
            if (sscanf(str, "%d", &i1) == 1)
                critical_send_char((jchar)i1);
            break;
        case CB_EVENT_CHAR_MODS:
            if (sscanf(str, "%d,%d", &i1, &i2) == 2)
                critical_send_char_mods((jchar)i1, i2);
            break;
        case CB_EVENT_CURSOR_ENTER:
            // optional — cursor enter는 critical_send_cursor_pos 첫 호출에서 처리됨
            break;
        case CB_EVENT_WINDOW_SIZE:
        case CB_EVENT_FRAMEBUFFER_SIZE:
            // skip for now
            break;
        default:
            LOGI("[NSD] unhandled type %d", type);
            break;
    }

    (*env)->ReleaseStringUTFChars(env, data, str);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetCursorShape(
        JNIEnv *env, jclass cls, jint shape) {
    (void)env; (void)cls;
    LOGI("[NSD] SetCursorShape shape=%d", shape);
    // optional: implement if MC sets cursor shapes
}

// ──────────────────────────────────────────────────────────────────────────
//  cacio(caciocavallo) AWT Desktop 후킹 — 게임 내 "폴더 열기 / 링크 열기"
//
//  마인크래프트의 "리소스팩 폴더 열기", "스크린샷 폴더 열기", 하이퍼링크 등은
//  데스크톱에서 java.awt.Desktop 으로 처리된다. 안드로이드 JRE 는 그 구현으로
//  caciocavallo(cacio)를 쓰는데, 그 네이티브 종단점이 CTCDesktopPeer.openFile/openUri
//  이다(=여기). 이를 구현해 Dalvik 쪽 CallbackBridge.openPath/openLink 로 넘긴다.
//
//  • openFile(path)  → CallbackBridge.openPath(String)  : "file:///.../resourcepacks/" 같은 경로
//  • openUri(uri)    → CallbackBridge.openLink(String)  : http(s) 등 링크
//
//  자바 쪽(CallbackBridge)에서 경로/URI 를 받아 안드로이드 인텐트(SAF/브라우저/공유)로
//  처리한다. cacio 패키지명은 배포본에 따라 두 가지라 둘 다 등록한다.
// ──────────────────────────────────────────────────────────────────────────

// CallbackBridge 의 openPath / openLink 메서드 ID (최초 호출 시 lazy 캐시)
static jmethodID s_method_openPath = NULL;
static jmethodID s_method_openLink = NULL;

// runtime(JRE) 스레드에서 dalvik 으로 문자열을 넘겨 정적 메서드를 호출하는 공통 헬퍼.
//   jvmEnv     : 호출자(런타임 JVM) JNIEnv — 인자 jstring 을 읽는 데 사용
//   jstr       : 넘길 문자열(파일 경로 또는 URI)
//   methodId*  : 캐시할 메서드 ID 슬롯
//   methodName : "openPath" 또는 "openLink"
static void forward_string_to_bridge(JNIEnv *jvmEnv, jstring jstr,
                                     jmethodID *methodId, const char *methodName) {
    if (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL) {
        LOGE("[AWT] dalvik VM not ready, cannot forward %s", methodName);
        return;
    }
    if (pojav_environ->bridgeClazz == NULL) {
        LOGE("[AWT] CallbackBridge class not ready, cannot forward %s", methodName);
        return;
    }

    // dalvik(안드로이드) JNIEnv 확보 — 필요 시 attach (clipboard 후킹과 동일 패턴)
    JNIEnv *dalvikEnv = NULL;
    char detachable = 0;
    jint st = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(
            pojav_environ->dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) {
        (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    if (dalvikEnv == NULL) {
        LOGE("[AWT] failed to obtain dalvik JNIEnv for %s", methodName);
        return;
    }

    // 메서드 ID lazy 캐시: CallbackBridge.<methodName>(Ljava/lang/String;)V
    if (*methodId == NULL) {
        *methodId = (*dalvikEnv)->GetStaticMethodID(
                dalvikEnv, pojav_environ->bridgeClazz, methodName, "(Ljava/lang/String;)V");
        if (*methodId == NULL) {
            LOGE("[AWT] CallbackBridge.%s(String) not found — add it on the Java side", methodName);
            if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) (*dalvikEnv)->ExceptionClear(dalvikEnv);
            if (detachable) (*pojav_environ->dalvikJavaVMPtr)->DetachCurrentThread(pojav_environ->dalvikJavaVMPtr);
            return;
        }
    }

    // 런타임 JVM 의 jstring → C 문자열 → dalvik jstring 으로 재생성하여 호출
    const char *chars = (jstr != NULL) ? (*jvmEnv)->GetStringUTFChars(jvmEnv, jstr, NULL) : NULL;
    jstring dalvikStr = (chars != NULL) ? (*dalvikEnv)->NewStringUTF(dalvikEnv, chars) : NULL;

    LOGI("[AWT] forward %s -> %s", methodName, chars ? chars : "(null)");
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, pojav_environ->bridgeClazz, *methodId, dalvikStr);
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionDescribe(dalvikEnv);
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
    }

    if (dalvikStr != NULL) (*dalvikEnv)->DeleteLocalRef(dalvikEnv, dalvikStr);
    if (chars != NULL) (*jvmEnv)->ReleaseStringUTFChars(jvmEnv, jstr, chars);
    if (detachable) (*pojav_environ->dalvikJavaVMPtr)->DetachCurrentThread(pojav_environ->dalvikJavaVMPtr);
}

// ── cacio 패키지 A: net.java.openjdk.cacio.ctc ──
JNIEXPORT void JNICALL
Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openFile(JNIEnv *env, jclass clazz, jstring filePath) {
    (void) clazz;
    forward_string_to_bridge(env, filePath, &s_method_openPath, "openPath");
}

JNIEXPORT void JNICALL
Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openUri(JNIEnv *env, jclass clazz, jstring uri) {
    (void) clazz;
    forward_string_to_bridge(env, uri, &s_method_openLink, "openLink");
}

// ── cacio 패키지 B: com.github.caciocavallosilano.cacio.ctc ──
JNIEXPORT void JNICALL
Java_com_github_caciocavallosilano_cacio_ctc_CTCDesktopPeer_openFile(JNIEnv *env, jclass clazz, jstring filePath) {
    (void) clazz;
    forward_string_to_bridge(env, filePath, &s_method_openPath, "openPath");
}

JNIEXPORT void JNICALL
Java_com_github_caciocavallosilano_cacio_ctc_CTCDesktopPeer_openUri(JNIEnv *env, jclass clazz, jstring uri) {
    (void) clazz;
    forward_string_to_bridge(env, uri, &s_method_openLink, "openLink");
}