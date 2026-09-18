package net.endiq.launcher.prefs;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static net.endiq.launcher.Architecture.is32BitsDevice;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;

import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.feature.unpack.Jre;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.setting.AllStaticSettings;
import com.endiq.turtlelauncher.setting.Settings;
import com.endiq.turtlelauncher.ui.activity.BaseActivity;

import net.endiq.launcher.Tools;
import net.endiq.launcher.multirt.MultiRTUtils;
import net.endiq.launcher.utils.JREUtils;

public class LauncherPreferences {
    public static void loadPreferences() {
        String argLwjglLibname = "-Dorg.lwjgl.opengl.libname=";
        String javaArgs = AllSettings.getJavaArgs().getValue();
        for (String arg : JREUtils.parseJavaArguments(javaArgs)) {
            if (arg.startsWith(argLwjglLibname)) {
                // purge arg
                AllSettings.getJavaArgs().put(javaArgs.replace(arg, "")).save();
            }
        }

        reloadRuntime();
    }

    public static void reloadRuntime() {
        if (!Settings.Manager.contains("defaultRuntime") && !MultiRTUtils.getRuntimes().isEmpty()) {
            // Set the default runtime.
            AllSettings.getDefaultRuntime().put("Internal-17").save();
        }
    }

    /**
     * This functions aims at finding the best default RAM amount,
     * according to the RAM amount of the physical device.
     * Put not enough RAM ? Minecraft will lag and crash.
     * Put too much RAM ?
     * The GC will lag, android won't be able to breathe properly.
     * @param ctx Context needed to get the total memory of the device.
     * @return The best default value found.
     */
    public static int findBestRAMAllocation(Context ctx){
        int deviceRam = Tools.getTotalDeviceMemory(ctx);

        // TurtleLauncher: the 32-bit cap below used to sit after the <8192 branches,
        // so it only ever fired for a 32-bit device with 8GB+ RAM (rare) - any 32-bit
        // device with 4-8GB RAM fell through to the 2058MB branch instead, which is
        // well past what's safe for a 32-bit process's address space. Moved to the
        // top so "limit the max for 32-bit devices more harshly" actually applies to
        // the devices it's meant for; a 32-bit process's addressable space is the
        // real constraint here, not installed RAM.
        if (is32BitsDevice()) {
            if (deviceRam < 1024) return 384;
            if (deviceRam < 1536) return 512;
            return 696; // hard cap regardless of how much more physical RAM exists
        }

        if (deviceRam < 1024) return 384;
        if (deviceRam < 1536) return 512;
        if (deviceRam < 2048) return 768;
        if (deviceRam < 4096) return 1536;
        if (deviceRam < 8192) return 2058;
        // TurtleLauncher: everything below here used to be dead code - every branch
        // above already returns for anything under 8192MB, so a 64-bit device with
        // 8GB+ RAM always got a flat 2048MB recommendation no matter how much more
        // RAM it actually had. Added real tiers instead of leaving that headroom
        // unused; these are a judgment call, not restored original values, since the
        // dead tiers below this point (936/1144/1536/2048 for <3064/<4096/<6144/else)
        // were self-contradictory - a 64-bit-only path recommending less RAM (1144)
        // than the 32-bit-reachable path above it (1536) at the same RAM tier.
        if (deviceRam < 12288) return 3072; // 8-12GB devices
        if (deviceRam < 16384) return 4096; // 12-16GB devices
        return 6144;                        // 16GB+ devices
    }

    /** Compute the notch size to avoid being out of bounds */
    public static void computeNotchSize(BaseActivity activity) {
        if (Build.VERSION.SDK_INT < P) return;
        try {
            final Rect cutout;
            if(SDK_INT >= Build.VERSION_CODES.S){
                cutout = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets().getDisplayCutout().getBoundingRects().get(0);
            } else {
                cutout = activity.getWindow().getDecorView().getRootWindowInsets().getDisplayCutout().getBoundingRects().get(0);
            }

            // Notch values are rotation sensitive, handle all cases
            int orientation = activity.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_PORTRAIT) AllStaticSettings.notchSize = cutout.height();
            else if (orientation == Configuration.ORIENTATION_LANDSCAPE) AllStaticSettings.notchSize = cutout.width();
            else AllStaticSettings.notchSize = Math.min(cutout.width(), cutout.height());

        }catch (Exception e){
            Logging.i("NOTCH DETECTION", "No notch detected, or the device if in split screen mode");
            AllStaticSettings.notchSize = -1;
        }
        Tools.updateWindowSize(activity);
    }
}
