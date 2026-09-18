package com.movtery.turtlelauncher.ui.activity;

import android.content.Context;

import androidx.annotation.Keep;

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
