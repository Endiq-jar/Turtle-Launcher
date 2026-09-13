package com.movtery.turtlelauncher.ui.activity;

import android.content.Context;

import androidx.annotation.Keep;

/**
 * Crash-compatibility trampoline for the prebuilt native exit hook.
 *
 * <p>The shipped {@code libpojavexec.so} (all four ABIs under
 * {@code src/main/jniLibs}, built from the pre-rename tree) does a {@code FindClass}
 * on the JNI class path {@code com/movtery/turtlelauncher/ui/activity/ErrorActivity}
 * (via {@code JREUtils.setupExitMethod()}) and the game-exit hook later calls its
 * static {@code showExitMethod(Context, int, boolean)}. The binaries originally
 * hardcoded the pre-rename {@code com/movtery/zalithlauncher/...} spelling; during
 * the Turtle rebrand that string was patched in place in all four prebuilt
 * {@code .so} files (a safe byte-for-byte substitution, since
 * {@code zalithlauncher} and {@code turtlelauncher} are the same length), so the
 * native side now resolves this exact class. There is no source for that native
 * method left in this repo ({@code jni/} only carries the newer TurtleBridge-based
 * sources, which the Gradle build doesn't even compile - {@code externalNativeBuild}
 * is disabled and the APK ships the prebuilt {@code .so} files), so this class path
 * must keep its {@code com.movtery.turtlelauncher} home unless the launcher natives
 * ever get a full NDK rebuild.
 *
 * <p>When the Java sources were renamed to {@code com.endiq.*}, this class stopped
 * existing at the old path. Every game launch then died the same way: the
 * {@code FindClass} throws {@code ClassNotFoundException}, the very next JNI call
 * ({@code NewGlobalRef}) runs with that exception still pending, ART aborts with
 * "JNI DETECTED ERROR IN APPLICATION", and the whole process takes SIGABRT
 * (exit status 6) before the JVM even starts.
 *
 * <p>This class re-creates the exact old entry point and forwards to the real,
 * renamed {@code com.endiq.turtlelauncher.ui.activity.ErrorActivity}, restoring the
 * game exit/crash screen with zero behavior change. It is never started as an
 * Activity itself (no manifest entry needed) - native code only invokes the static
 * method. Kept verbatim by R8 via {@code @Keep} plus an explicit rule in
 * {@code proguard-rules.pro}, since nothing in Java references it.
 */
@Keep
public class ErrorActivity {

    /**
     * Exact signature the native exit hook looks up:
     * {@code showExitMessage(Landroid/content/Context;IZ)V}.
     */
    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        com.endiq.turtlelauncher.ui.activity.ErrorActivity.showExitMessage(ctx, code, isSignal);
    }
}
