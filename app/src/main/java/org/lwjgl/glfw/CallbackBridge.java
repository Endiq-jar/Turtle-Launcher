package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import kr.co.donghyun.flamelauncher.presentation.MinecraftActivity;
import kr.co.donghyun.flamelauncher.presentation.util.MinecraftActivityBridge;

/**
 * PojavLauncher의 CallbackBridge — 안드로이드 측 진입점.
 * input_bridge_v3.c::JNI_OnLoad 가 이 클래스의 static 메서드를
 * GetStaticMethodID 로 찾는다. 없으면 JVM 부팅 시점에 abort.
 */
public class CallbackBridge {
    private static final String TAG = "CallbackBridge";

    // ─── input_bridge_v3.c 가 실제로 export 하는 native ───────────────
    public static native void nativeSetGrabbing(boolean grabbing);
    public static native boolean nativeSetInputReady(boolean inputReady);
    public static native void nativeSendData(boolean isAndroid, int type, String data);
    public static native String nativeClipboard(int action, byte[] copySrc);

    // ─── input_bridge_v3.c 의 nativeSendData 이벤트 타입 (CB_EVENT_*) ──
    public static final int EVENT_TYPE_CHAR             = 1000;
    public static final int EVENT_TYPE_CHAR_MODS        = 1001;
    public static final int EVENT_TYPE_CURSOR_POS       = 1003;
    public static final int EVENT_TYPE_FRAMEBUFFER_SIZE = 1004;
    public static final int EVENT_TYPE_KEY              = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON     = 1006;
    public static final int EVENT_TYPE_SCROLL           = 1007;
    public static final int EVENT_TYPE_WINDOW_SIZE      = 1008;

    // ─── input_bridge_v3.c::registerFunctions 가 RegisterNatives 로 붙이는 8개 ───
    //
    // ⚠️ 이 목록은 noncritical_fcns[] 와 **이름·시그니처가 정확히 같아야 한다.**
    //    RegisterNatives 는 표의 첫 항목이 어긋나는 순간 실패하면서 NoSuchMethodError 를
    //    JNI_OnLoad 에 남기고, 그러면 libglfw.so 를 System.load 하는 자리에서 그대로
    //    터진다 — 즉 게임이 **모든 버전에서** 실행 즉시 튕긴다.
    //    한때 이 메서드들을 자바 구현(nativeSendData 로 라우팅)으로 바꾼 적이 있는데,
    //    RegisterNatives 는 native 가 아닌 메서드에도 같은 에러를 내므로 그게 원인이었다.
    //    (게다가 CB_EVENT_WINDOW_SIZE 는 nativeSendData 가 무시해서 화면 크기 전달이 죽는다)
    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    public static native boolean nativeSendChar(char codepoint);
    public static native boolean nativeSendCharMods(char codepoint, int mods);
    public static native void nativeSendKey(int key, int scancode, int action, int mods);
    public static native void nativeSendCursorPos(float x, float y);
    public static native void nativeSendMouseButton(int button, int action, int mods);
    public static native void nativeSendScroll(double xoffset, double yoffset);
    public static native void nativeSendScreenSize(int width, int height);

    // ─── JVM → Android 콜백 (네이티브가 이 메서드들을 호출) ───────────

    /** 클립보드 접근. action: 2000=COPY, 2001=PASTE, 2002=OPEN. */
    public static String accessAndroidClipboard(int action, String copyContent) {
        try {
            Context ctx = MinecraftActivity.Companion.getCurrentInstance();
            if (ctx == null) return "";
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "";

            switch (action) {
                case 2000: // COPY
                    cm.setPrimaryClip(ClipData.newPlainText("Minecraft", copyContent));
                    return "";
                case 2001: // PASTE
                    if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                        return t != null ? t.toString() : "";
                    }
                    return "";
                case 2002: // OPEN — xdg-open 후킹으로 들어옴
                    Log.i(TAG, "openPath: " + copyContent);
                    if (!copyContent.isEmpty()) {
                        net.kdt.pojavlaunch.MainActivity.openLink(copyContent);
                    }
                    return "";
                default:
                    return "";
            }
        } catch (Throwable t) {
            Log.e(TAG, "accessAndroidClipboard error", t);
            return "";
        }
    }

    /** 마인크래프트가 마우스를 잡았는지/풀었는지 알림. */
    public static void onGrabStateChanged(boolean grabbing) {
        Log.d(TAG, "onGrabStateChanged: " + grabbing);
        try {
            MinecraftActivityBridge.onGrabStateChanged(grabbing);
        } catch (Throwable t) {
            Log.w(TAG, "onGrabStateChanged dispatch failed", t);
        }
    }

    /**
     * 네이티브(egl_bridge.c::reportFpsToJava)가 ~500ms 주기로 호출.
     * frames = 그 구간 동안 실제 스왑된 프레임 수, elapsedNanos = 구간 길이(ns).
     * 렌더(게임) 스레드에서 불리므로 dispatch 만 한다.
     */
    public static void onFramePresented(int frames, long elapsedNanos) {
        try {
            MinecraftActivityBridge.onFramePresented(frames, elapsedNanos);
        } catch (Throwable t) {
            Log.w(TAG, "onFramePresented dispatch failed", t);
        }
    }

    /**
     * 게임이 첫 프레임을 화면에 그린 직후 네이티브(egl_bridge.c::pojavSwapBuffers)가 호출.
     * 부팅 로딩 다이얼로그를 닫는 신호로 쓴다. 렌더 스레드에서 불리므로 dispatch 만 한다.
     */
    public static void onFirstFrameRendered() {
        Log.d(TAG, "onFirstFrameRendered");
        try {
            MinecraftActivityBridge.onFirstFrameRendered();
        } catch (Throwable t) {
            Log.w(TAG, "onFirstFrameRendered dispatch failed", t);
        }
    }

    public static void sendData(int type, String data) {
        nativeSendData(true, type, data);
    }

    public static void onGrabStateChanged(boolean grabbing, boolean ignoreSameValue) {
        onGrabStateChanged(grabbing);
    }

    /** 컨트롤러 direct input 활성화 요청. 미지원이라 no-op. */
    public static void onDirectInputEnable() {
        Log.d(TAG, "onDirectInputEnable (no-op)");
    }
}