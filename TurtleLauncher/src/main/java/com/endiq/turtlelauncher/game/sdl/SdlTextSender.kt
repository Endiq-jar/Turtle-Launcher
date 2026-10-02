package com.endiq.turtlelauncher.game.sdl

import android.view.KeyEvent
import androidx.annotation.Keep
import net.endiq.launcher.EfficientAndroidLWJGLKeycode
import org.libsdl.app.SDLActivity

/**
 * Sends text and keys to the game through SDL (MC 26.3+ render path).
 *
 * Port of ZalithLauncher2's SdlTextSender (GPL-3.0), plus [sendBackspace]:
 * Turtle routes the whole [CharacterSenderStrategy][net.endiq.launcher.customcontrols.keyboard.CharacterSenderStrategy]
 * (char/enter/backspace) through here on the SDL path, via LwjglCharSender.
 */
@Keep
object SdlTextSender {
    @JvmStatic
    fun sendChar(character: Char) {
        if (!SdlBridge.sdlEnabled) return
        try {
            SDLActivity.onNativeTextInput(character.toString())
        } catch (e: UnsatisfiedLinkError) {
            // SDL native not ready yet; drop the char.
        }
    }

    @JvmStatic
    fun sendEnter() {
        if (!SdlBridge.sdlEnabled) return
        try {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER)
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER)
        } catch (e: UnsatisfiedLinkError) {
            // SDL native not ready yet; drop the key.
        }
    }

    @JvmStatic
    fun sendBackspace() {
        if (!SdlBridge.sdlEnabled) return
        try {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_DEL)
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_DEL)
        } catch (e: UnsatisfiedLinkError) {
            // SDL native not ready yet; drop the key.
        }
    }

    @JvmStatic
    fun sendKey(lwjglGlfwKeycode: Int) {
        if (!SdlBridge.sdlEnabled) return
        val keyCode = EfficientAndroidLWJGLKeycode.getSdlAndroidKeycode(lwjglGlfwKeycode)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        try {
            SDLActivity.onNativeKeyDown(keyCode)
            SDLActivity.onNativeKeyUp(keyCode)
        } catch (e: UnsatisfiedLinkError) {
            // SDL native not ready yet; drop the key.
        }
    }
}
