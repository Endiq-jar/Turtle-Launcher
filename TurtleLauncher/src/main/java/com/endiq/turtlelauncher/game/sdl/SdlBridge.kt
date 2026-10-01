package com.endiq.turtlelauncher.game.sdl

import android.app.Activity
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.annotation.MainThread
import com.endiq.turtlelauncher.setting.AllSettings
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface

/**
 * Owns the SDL integration state shared by the launcher and game JVM.
 *
 * Port of ZalithLauncher2's SdlBridge (GPL-3.0), minus the Compose state flows
 * (Turtle has no Compose layer) and the CallbackBridge gamepad-direct-input
 * reset (Turtle's CallbackBridge has no such state).
 *
 * Unlike Zalith — where these natives live in the always-loaded libpojavexec —
 * Turtle's SDL natives live in libsdlhook.so, which is only loaded for SDL
 * launches. The [isSdlRenderActive] / [setNativeTextInputActive] /
 * [initializeControllerSubsystems] entry points are therefore null-safe
 * wrappers: they short-circuit when SDL is off and swallow
 * [UnsatisfiedLinkError] so GLFW launches can never trip over them.
 */
@Keep
object SdlBridge {
    private const val TAG = "SdlBridge"

    private var currentSurface: Surface? = null

    /** Source of the currently registered Surface. */
    private var currentSource: Any? = null

    /** Incremented on every new Surface registration, for lifecycle observation. */
    private var surfaceGeneration = 0L
    private var jniReady = false
    private var sdlInitialized = false

    @JvmStatic
    @Synchronized
    fun setupJNI(): Boolean {
        if (jniReady) {
            return true
        }
        SDL.setupJNI()
        jniReady = true
        return true
    }

    @JvmStatic
    @Synchronized
    fun markSdlInitialized(): Boolean {
        if (sdlInitialized) return false
        sdlInitialized = true
        return true
    }

    @JvmStatic
    @Synchronized
    fun clearSdlInitialized() {
        sdlInitialized = false
    }

    @JvmStatic
    @Volatile
    var sdlEnabled: Boolean = false

    /**
     * Whether the launcher responds when SDL asks to show the input method.
     */
    @JvmStatic
    fun getSdlImeAutoShowEnabled(): Boolean = AllSettings.getSdlAutoShowIme().getValue()

    /**
     * Registers the game Surface (and host layout) with SDL. The first call also
     * creates the SDLSurface and runs SDLActivity.externalInitialize; later calls
     * just re-point the native surface.
     *
     * @param surface the game Surface, or null when only the activity/layout side
     * needs (re-)initializing ahead of the Surface (eager launch setup).
     */
    @JvmStatic
    @MainThread
    fun prepareSurface(activity: Activity, surface: Surface?, layout: ViewGroup?, source: Any? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { prepareSurface(activity, surface, layout, source) }
            return
        }
        currentSurface = surface
        currentSource = source
        surfaceGeneration++

        if (SDLActivity.getSDLSurface() == null) {
            SDL.initialize()
            SDL.setContext(activity)
            SDLActivity.externalInitialize(SDLSurface(activity), layout, surface)
        } else if (surface != null) {
            SDLSurface.setNativeSurface(surface)
        }
    }

    @JvmStatic
    @MainThread
    fun registerSurface(activity: Activity, surface: Surface, layout: ViewGroup?) {
        currentSurface = surface
    }

    @JvmStatic
    @MainThread
    fun beginSurfaceDestroy(source: Any?, surface: Surface?): Boolean {
        return source != null && currentSource === source && surface != null && currentSurface === surface
    }

    @JvmStatic
    @MainThread
    fun unregisterSurface(surface: Surface?) {
        if (surface != null && currentSurface === surface) {
            currentSurface = null
            currentSource = null
        }
    }

    @JvmStatic
    @MainThread
    @Synchronized
    fun reset() {
        currentSurface = null
        currentSource = null
        surfaceGeneration = 0L
        jniReady = false
        sdlInitialized = false
        sdlEnabled = false
        SDLSurface.clearNativeSurface()
        SDL.initialize()
    }

    @JvmStatic
    fun initializeControllerSubsystems() {
        if (!sdlEnabled) return
        try {
            initializeControllerSubsystemsNative()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "initializeControllerSubsystems: libsdlhook not loaded", e)
        }
    }

    /**
     * Whether the game is running on the SDL render path (SDL window created,
     * MC 26.3+). Returns false when SDL is only used by the controller
     * subsystems (e.g. MC 26.2 with Controlify) — game input still goes through
     * the GLFW bridge then, and the keyboard must not be delegated to SDL.
     */
    @JvmStatic
    fun isSdlRenderActive(): Boolean {
        if (!sdlEnabled) return false
        return try {
            isSdlRenderActiveNative()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "isSdlRenderActive: libsdlhook not loaded", e)
            false
        }
    }

    /**
     * Activates/deactivates the native-side SDL text-input channel. When the
     * game side closed the channel (mods with self-drawn input UIs do), the
     * launcher must activate it on the game's behalf when explicitly showing
     * the IME, or committed text is dropped at the native layer.
     */
    @JvmStatic
    fun setNativeTextInputActive(active: Boolean): Boolean {
        if (!sdlEnabled) return false
        return try {
            setNativeTextInputActiveNative(active)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "setNativeTextInputActive: libsdlhook not loaded", e)
            false
        }
    }

    @JvmStatic
    private external fun initializeControllerSubsystemsNative()

    @JvmStatic
    private external fun isSdlRenderActiveNative(): Boolean

    @JvmStatic
    private external fun setNativeTextInputActiveNative(active: Boolean): Boolean
}
