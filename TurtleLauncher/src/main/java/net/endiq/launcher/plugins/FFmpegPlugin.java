package net.endiq.launcher.plugins;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.endiq.turtlelauncher.feature.log.Logging;

import java.io.File;

public class FFmpegPlugin {
    public static boolean isAvailable = false;
    public static String libraryPath;
    public static String executablePath;
    public static void discover(Context context) {
        PackageManager manager = context.getPackageManager();
        try {
            // "net.kdt.pojavlaunch.ffmpeg" is NOT our package - it is the applicationId
            // hardwired into the external FFmpeg plugin APK this launcher tells ReplayMod
            // users to install (Pojav.FFmpeg.Plugin, distributed via the FCL-Team release
            // URL in ModChecker). Renaming it here to match this launcher's own packages
            // would make getPackageInfo() look for an app that can never exist, silently
            // disabling ReplayMod rendering. Same for the matching <queries> entry in
            // AndroidManifest.xml. Deliberately kept as the external APK's true id.
            PackageInfo ffmpegPluginInfo = manager.getPackageInfo("net.kdt.pojavlaunch.ffmpeg", PackageManager.GET_SHARED_LIBRARY_FILES);
            libraryPath = ffmpegPluginInfo.applicationInfo.nativeLibraryDir;
            File ffmpegExecutable = new File(libraryPath, "libffmpeg.so");
            executablePath = ffmpegExecutable.getAbsolutePath();
            // Older plugin versions still have the old executable location
            isAvailable = ffmpegExecutable.exists();
        }catch (Exception e) {
            Logging.i("FFmpegPlugin", "Failed to discover plugin", e);
        }
    }
}
