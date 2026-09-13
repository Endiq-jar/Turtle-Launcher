package com.endiq.turtlelauncher.support.touch_controller;

import android.content.Context;
import android.os.Vibrator;
import android.system.Os;

import com.endiq.turtlelauncher.InfoDistributor;
import com.endiq.turtlelauncher.feature.log.Logging;

import net.kdt.pojavlaunch.Logger;

import top.fifthlight.touchcontroller.proxy.client.LauncherProxyClient;
import top.fifthlight.touchcontroller.proxy.client.MessageTransport;
import top.fifthlight.touchcontroller.proxy.client.android.transport.UnixSocketTransportKt;

/**
 * For compatibility with the TouchController mod.
 * <a href="">https://modrinth.com/mod/touchcontroller</a>
 */
public final class ControllerProxy {
    private static LauncherProxyClient proxyClient;

    private ControllerProxy() {}

    /**
     * Start the control proxy client to talk to the TouchController mod.
     */
    public static void startProxy(Context context) {
        if (proxyClient == null) {
            try {
                MessageTransport transport = UnixSocketTransportKt.UnixSocketTransport(InfoDistributor.LAUNCHER_NAME);
                Os.setenv("TOUCH_CONTROLLER_PROXY_SOCKET", InfoDistributor.LAUNCHER_NAME, true);
                LauncherProxyClient client = new LauncherProxyClient(transport);
                Vibrator vibrator = context.getSystemService(Vibrator.class);
                VibrationHandler handler = new VibrationHandler(vibrator);
                client.setVibrationHandler(handler);
                client.run();
                Logger.appendToLog("TouchController: TouchController Proxy Client has been created!");
                proxyClient = client;
            } catch (Throwable ex) {
                Logging.w("TouchController", "TouchController proxy client create failed", ex);
                proxyClient = null;
            }
        }
    }

    static LauncherProxyClient getProxyClient() {
        return proxyClient;
    }
}
