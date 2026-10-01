package net.endiq.launcher.customcontrols.keyboard;

import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;

import net.endiq.launcher.LwjglGlfwKeycode;

import com.endiq.turtlelauncher.game.sdl.SdlBridge;
import com.endiq.turtlelauncher.game.sdl.SdlTextSender;

import org.lwjgl.glfw.CallbackBridge;

/** Sends keys via the CallBackBridge, or via SDL on the SDL render path (MC 26.3+). */
public class LwjglCharSender implements CharacterSenderStrategy {
    /** True when game text input must go through SDL instead of the GLFW bridge. */
    private static boolean useSdl() {
        // Same gate as TouchCharInput: SDL owns text only once its render path
        // is up (window created). Controller-subsystem-only SDL (MC 26.2 with
        // Controlify) keeps the GLFW bridge.
        return SdlBridge.getSdlEnabled() && SdlBridge.isSdlRenderActive();
    }

    @Override
    public void sendBackspace() {
        if (useSdl()) {
            SdlTextSender.sendBackspace();
            return;
        }
        CallbackBridge.sendKeycode(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE, '\u0008', 0, 0, true);
        CallbackBridge.sendKeycode(LwjglGlfwKeycode.GLFW_KEY_BACKSPACE, '\u0008', 0, 0, false);
    }

    @Override
    public void sendEnter() {
        if (useSdl()) {
            SdlTextSender.sendEnter();
            return;
        }
        sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER);
    }

    @Override
    public void sendChar(char character) {
        if (useSdl()) {
            SdlTextSender.sendChar(character);
            return;
        }
        CallbackBridge.sendChar(character, 0);
    }
}
