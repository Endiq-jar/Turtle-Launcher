package net.endiq.launcher;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static com.endiq.turtlelauncher.setting.AllStaticSettings.notchSize;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentActivity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.endiq.turtlelauncher.InfoDistributor;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.context.ContextExecutor;
import com.endiq.turtlelauncher.utils.LauncherProfiles;
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.feature.version.Version;
import com.endiq.turtlelauncher.task.Task;
import com.endiq.turtlelauncher.ui.activity.BaseActivity;
import com.endiq.turtlelauncher.ui.dialog.EditTextDialog;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.runtime.SelectRuntimeUtils;
import com.endiq.turtlelauncher.utils.stringutils.StringUtils;
import net.endiq.launcher.fragments.MainMenuFragment;
import net.endiq.launcher.lifecycle.ContextExecutorTask;
import net.endiq.launcher.memory.MemoryHoleFinder;
import net.endiq.launcher.memory.SelfMapsParser;
import net.endiq.launcher.multirt.MultiRTUtils;
import net.endiq.launcher.utils.FileUtils;
import net.endiq.launcher.value.DependentLibrary;
import net.endiq.launcher.value.MinecraftLibraryArtifact;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.CallbackBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@SuppressWarnings("IOStreamConstructor")
public final class Tools {
    public static final String NOTIFICATION_CHANNEL_DEFAULT = "channel_id";
    public static final float BYTE_TO_MB = 1024f * 1024f;
    public static final Gson GLOBAL_GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String LAUNCHERPROFILES_RTPREFIX = "turtle://";
    private final static boolean isClientFirst = false;
    public static int DEVICE_ARCHITECTURE;
    // New since 3.0.0
    public static String DIRNAME_HOME_JRE = "lib";

    /**
     * Checks if the Turtle's storage root is accessible and read-writable
     * @return true if storage is fine, false if storage is not accessible
     */
    public static boolean checkStorageRoot() {
        File externalFilesDir = new File(PathManager.DIR_GAME_HOME);
        //externalFilesDir == null when the storage is not mounted if it was obtained with the context call
        return Environment.getExternalStorageState(externalFilesDir).equals(Environment.MEDIA_MOUNTED);
    }

    public static void buildNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_DEFAULT,
                context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(channel);
    }

    public static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if(FileUtils.ensureDirectorySilently(configDir)) {
            File forgeSplashFile = new File(dir, "config/splash.properties");
            String forgeSplashContent = "enabled=true";
            try {
                if (forgeSplashFile.exists()) {
                    forgeSplashContent = Tools.read(forgeSplashFile.getAbsolutePath());
                }
                if (forgeSplashContent.contains("enabled=true")) {
                    Tools.write(forgeSplashFile.getAbsolutePath(),
                            forgeSplashContent.replace("enabled=true", "enabled=false"));
                }
            } catch (IOException e) {
                Logging.w(InfoDistributor.LAUNCHER_NAME, "Could not disable Forge 1.12.2 and below splash screen!", e);
            }
        } else {
            Logging.w(InfoDistributor.LAUNCHER_NAME, "Failed to create the configuration directory");
        }
    }

    public static String fromStringArray(String[] strArr) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strArr.length; i++) {
            if (i > 0) builder.append(" ");
            builder.append(strArr[i]);
        }

        return builder.toString();
    }

    public static String artifactToPath(DependentLibrary library) {
        if (library.downloads != null &&
            library.downloads.artifact != null &&
            library.downloads.artifact.path != null)
            return library.downloads.artifact.path;
        String[] libInfos = library.name.split(":");

        if (libInfos.length < 3) {
            Logging.e("Tools_artifactToPath", "Invalid library name format: " + library.name);
            return null;
        }

        String groupId = libInfos[0].replace('.', '/');
        String artifactId = libInfos[1];
        String version = libInfos[2];

        String classifier = (libInfos.length > 3) ? "-" + libInfos[3] : "";

        return String.format("%s/%s/%s/%s-%s%s.jar", groupId, artifactId, version, artifactId, version, classifier);
    }

    public static String getClientClasspath(Version version) {
        return new File(version.getVersionPath(), version.getVersionName() + ".jar").getAbsolutePath();
    }

    /**
     * Returns the launcher-provided LWJGL bridge payload.
     *
     * The legacy payload intentionally contains a complete LWJGL distribution because
     * pre-SDL Minecraft versions do not declare their LWJGL jars in a usable Android
     * form.  SDL Minecraft versions are different: their version-specific LWJGL core
     * and SDL jars must be the only providers of the LWJGL core or callback
     * implementation. The Android bridge contributes only its GLFW/Vulkan
     * integration classes; the exact game-provided GLFW jar owns GLFW callbacks.
     */
    public static String getLWJGL3ClassPath() {
        return getLWJGL3ClassPath(false);
    }

    public static String getLWJGL3ClassPath(JMinecraftVersionList.Version info) {
        return getLWJGL3ClassPath(info != null && resolveLwjglMode(info) == LwjglMode.NEW_SDL);
    }

    private static String getLWJGL3ClassPath(boolean newSdl) {
        File lwjgl3Folder = new File(PathManager.DIR_GAME_HOME, "lwjgl3");
        if (!lwjgl3Folder.isDirectory()) return "";

        // Never put both payloads on one class path. They intentionally contain
        // overlapping bridge classes, and the legacy payload also contains a full
        // older LWJGL core. The bridge jar is generated by jre_lwjgl3glfw and is
        // deliberately free of any org.lwjgl.system implementation for the SDL
        // path; the exact game jars own that API.
        String preferred = newSdl ? "lwjgl-glfw-bridge.jar" : "lwjgl-glfw-classes.jar";
        File selected = new File(lwjgl3Folder, preferred);
        return selected.isFile() ? selected.getAbsolutePath() : "";
    }

    public static String generateLaunchClassPath(JMinecraftVersionList.Version info, Version minecraftVersion) {
        StringBuilder finalClasspath = new StringBuilder(); //versnDir + "/" + version + "/" + version + ".jar:";

        String[] classpath = generateLibClasspath(info);

        String clientClasspath = getClientClasspath(minecraftVersion);

        if (isClientFirst) {
            finalClasspath.append(clientClasspath);
        }
        for (String jarFile : classpath) {
            if (!FileUtils.exists(jarFile)) {
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Ignored non-exists file: " + jarFile);
                continue;
            }
            finalClasspath.append((isClientFirst ? ":" : "")).append(jarFile).append(!isClientFirst ? ":" : "");
        }
        if (!isClientFirst) {
            finalClasspath.append(clientClasspath);
        }

        return finalClasspath.toString();
    }


    public static DisplayMetrics getDisplayMetrics(BaseActivity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();

        if (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode()) {
            //For devices with free form/split screen, we need window size, not screen size.
            displayMetrics = activity.getResources().getDisplayMetrics();
        } else {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else { // Removed the clause for devices with unofficial notch support, since it also ruins all devices with virtual nav bars before P
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
            }
            if (!activity.shouldIgnoreNotch()) {
                //Remove notch width when it isn't ignored.
                if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
                    displayMetrics.heightPixels -= notchSize;
                else
                    displayMetrics.widthPixels -= notchSize;
            }
        }
        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    public static void setFullscreen(Activity activity) {
        if (SDK_INT >= Build.VERSION_CODES.R) {
            setFullscreenModern(activity);
        } else {
            setFullscreenLegacy(activity);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void setFullscreenModern(Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        applyImmersiveState(activity, controller);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void applyImmersiveState(Activity activity, WindowInsetsControllerCompat controller) {
        if (controller == null) return;
        if (activity.isInMultiWindowMode()) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    private static void setFullscreenLegacy(Activity activity) {
        final View decorView = activity.getWindow().getDecorView();
        View.OnSystemUiVisibilityChangeListener visibilityChangeListener = visibility -> {
            boolean multiWindowMode = activity.isInMultiWindowMode();
            // When in multi-window mode, asking for fullscreen makes no sense (cause the launcher runs in a window)
            // So, ignore the fullscreen setting when activity is in multi window mode
            if (!multiWindowMode) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                }
            } else {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

        };
        decorView.setOnSystemUiVisibilityChangeListener(visibilityChangeListener);
        visibilityChangeListener.onSystemUiVisibilityChange(decorView.getSystemUiVisibility()); //call it once since the UI state may not change after the call, so the activity wont become fullscreen
    }

    public static DisplayMetrics currentDisplayMetrics;

    public static void updateWindowSize(BaseActivity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);

        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    public static float dpToPx(float dp) {
        //Better hope for the currentDisplayMetrics to be good
        return dp * currentDisplayMetrics.density;
    }

    public static float pxToDp(float px){
        //Better hope for the currentDisplayMetrics to be good
        return px / currentDisplayMetrics.density;
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, boolean overwrite) throws IOException {
        copyAssetFile(ctx, fileName, output, new File(fileName).getName(), overwrite);
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, String outputName, boolean overwrite) throws IOException {
        File parentFolder = new File(output);
        FileUtils.ensureDirectory(parentFolder);
        File destinationFile = new File(output, outputName);
        if(!destinationFile.exists() || overwrite){
            try(InputStream inputStream = ctx.getAssets().open(fileName)) {
                try (OutputStream outputStream = new FileOutputStream(destinationFile)){
                    IOUtils.copy(inputStream, outputStream);
                }
            }
        }
    }

    public static String printToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.close();
        return stringWriter.toString();
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }

    public static void showError(final Context ctx, final Throwable e, final boolean exitIfOk) {
        showError(ctx, R.string.generic_error, null ,e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int rolledMessage, final Throwable e) {
        showError(ctx, R.string.generic_error, ctx.getString(rolledMessage), e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e) {
        showError(ctx, R.string.generic_error, rolledMessage, e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e, boolean exitIfOk) {
        showError(ctx, R.string.generic_error, rolledMessage, e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int titleId, final Throwable e, final boolean exitIfOk) {
        showError(ctx, titleId, null, e, exitIfOk, false);
    }

    private static void showError(final Context ctx, final int titleId, final String rolledMessage, final Throwable e, final boolean exitIfOk, final boolean showMore) {
        if(e instanceof ContextExecutorTask) {
            ContextExecutor.executeTask((ContextExecutorTask) e);
            return;
        }
        Logging.e("ShowError", printToString(e));

        Runnable runnable = () -> {
            final String errMsg = showMore ? printToString(e) : rolledMessage != null ? rolledMessage : e.getMessage();
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.CustomAlertDialogTheme)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                        if(exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                ZHTools.killProcess();
                            } else if (ctx instanceof Activity) {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setNegativeButton(showMore ? R.string.error_show_less : R.string.error_show_more, (p1, p2) -> showError(ctx, titleId, rolledMessage, e, exitIfOk, !showMore))
                    .setNeutralButton(android.R.string.copy, (p1, p2) -> {
                        StringUtils.copyText("error", printToString(e), ctx);
                        if(exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                ZHTools.killProcess();
                            } else {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setCancelable(!exitIfOk);
            try {
                builder.show();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        };

        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Show the error remotely in a context-aware fashion. Has generally the same behaviour as
     * Tools.showError when in an activity, but when not in one, sends a notification that opens an
     * activity and calls Tools.showError().
     * NOTE: If the Throwable is a ContextExecutorTask and when not in an activity,
     * its executeWithApplication() method will never be called.
     * @param e the error (throwable)
     */
    public static void showErrorRemote(Throwable e) {
        showErrorRemote(null, e);
    }
    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        showErrorRemote(context.getString(rolledMessage), e);
    }
    public static void showErrorRemote(String rolledMessage, Throwable e) {
        ContextExecutor.executeTask(new ShowErrorActivity.RemoteErrorTask(e, rolledMessage));
    }

    private static boolean checkRules(JMinecraftVersionList.Arguments.ArgValue.ArgRules[] rules) {
        if(rules == null) return true; // always allow
        for (JMinecraftVersionList.Arguments.ArgValue.ArgRules rule : rules) {
            if (rule.action.equals("allow") && rule.os != null && rule.os.name.equals("osx")) {
                return false; //disallow
            }
        }
        return true; // allow if none match
    }

    public static void preProcessLibraries(DependentLibrary[] libraries) {
        if (libraries == null) return;
        for (DependentLibrary libItem : libraries) {
            if (libItem == null || libItem.name == null) continue;
            String[] nameParts = libItem.name.split(":");
            if (nameParts.length < 3) continue;
            String[] version = nameParts[2].split("\\.");
            if (libItem.name.startsWith("net.java.dev.jna:jna:")) {
                // Special handling for LabyMod 1.8.9, Forge 1.12.2(?) and oshi
                // we have libjnidispatch 5.13.0 in jniLibs directory
                if (version.length >= 2 && parseVersionPart(version[0]) >= 5 && parseVersionPart(version[1]) >= 13)
                    continue;
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Library " + libItem.name + " has been changed to version 5.13.0");
                createLibraryInfo(libItem);
                libItem.name = "net.java.dev.jna:jna:5.13.0";
                libItem.downloads.artifact.path = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
                libItem.downloads.artifact.sha1 = "1200e7ebeedbe0d10062093f32925a912020e747";
                libItem.downloads.artifact.url = "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
            } else if (libItem.name.startsWith("com.github.oshi:oshi-core:")) {
                //if (parseVersionPart(version[0]) >= 6 && parseVersionPart(version[1]) >= 3) return;
                // FIXME: ensure compatibility

                if (version.length < 2 || parseVersionPart(version[0]) != 6 || parseVersionPart(version[1]) != 2)
                    continue;
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Library " + libItem.name + " has been changed to version 6.3.0");
                createLibraryInfo(libItem);
                libItem.name = "com.github.oshi:oshi-core:6.3.0";
                libItem.downloads.artifact.path = "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
                libItem.downloads.artifact.sha1 = "9e98cf55be371cafdb9c70c35d04ec2a8c2b42ac";
                libItem.downloads.artifact.url = "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
            } else if (libItem.name.startsWith("org.ow2.asm:asm-all:")) {
                // Early versions of the ASM library get repalced with 5.0.4 because Turtle's LWJGL is compiled for
                // Java 8, which is not supported by old ASM versions. Mod loaders like Forge, which depend on this
                // library, often include lwjgl in their class transformations, which causes errors with old ASM versions.
                if (version.length < 1 || parseVersionPart(version[0]) < 0 || parseVersionPart(version[0]) >= 5) continue;
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Library " + libItem.name + " has been changed to version 5.0.4");
                createLibraryInfo(libItem);
                libItem.name = "org.ow2.asm:asm-all:5.0.4";
                libItem.url = null;
                libItem.downloads.artifact.path = "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
                libItem.downloads.artifact.sha1 = "e6244859997b3d4237a552669279780876228909";
                libItem.downloads.artifact.url = "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
            }
        }
    }

    private static int parseVersionPart(String part) {
        if (part == null) return -1;
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return -1;
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stripLibraryVersion(String name) {
        if (name == null) return "";
        int lastColon = name.lastIndexOf(':');
        return lastColon >= 0 ? name.substring(0, lastColon) : name;
    }

    private static void createLibraryInfo(DependentLibrary library) {
        if(library.downloads == null || library.downloads.artifact == null)
            library.downloads = new DependentLibrary.LibraryDownloads(new MinecraftLibraryArtifact());
    }

    public static boolean versionUsesLwjglSdl(JMinecraftVersionList.Version info) {
        if (info == null || info.libraries == null) return false;
        for (DependentLibrary libItem : info.libraries) {
            if (libItem == null) continue;
            if (libItem.name != null && libItem.name.startsWith("org.lwjgl:lwjgl-sdl:")) return true;
        }
        return false;
    }

    public enum LwjglMode { NEW_SDL, LEGACY }

    public static LwjglMode resolveLwjglMode(JMinecraftVersionList.Version info) {
        String override = com.endiq.turtlelauncher.setting.AllSettings.getLwjglCompatMode().getValue();
        if ("new".equals(override)) return LwjglMode.NEW_SDL;
        if ("legacy".equals(override)) return LwjglMode.LEGACY;
        return versionUsesLwjglSdl(info) ? LwjglMode.NEW_SDL : LwjglMode.LEGACY;
    }

    public static String[] generateLibClasspath(JMinecraftVersionList.Version info) {
        List<String> libDir = new ArrayList<>();
        for (DependentLibrary libItem : info.libraries) {
            if (!checkRules(libItem.rules)) continue;

            String libName = libItem.name;
            if (libName == null) continue;

            // Every LWJGL jar is placed in the ABI-consistent prefix below.
            // Keeping even the "critical" core/SDL jars here as well would
            // duplicate them after the bridge and make a stale downloaded jar
            // able to win class loading in future launchers.
            if (libName.startsWith("org.lwjgl:") ||
                libName.contains("jinput-platform") ||
                libName.contains("twitch-platform")
            ) {
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Ignored unusable dependency: " + libName);
                continue;
            }

            String libArtifactPath = artifactToPath(libItem);
            if (libArtifactPath == null) continue;
            libDir.add(ProfilePathHome.getLibrariesHome() + "/" + libArtifactPath);
        }
        return libDir.toArray(new String[0]);
    }

    private static boolean isLwjglNativeArtifact(DependentLibrary library) {
        if (library == null || library.name == null) return false;
        if (library.name.split(":").length > 3) return true;

        if (library.downloads != null && library.downloads.artifact != null) {
            String path = library.downloads.artifact.path;
            if (path != null && path.contains("-natives-")) return true;
        }
        return false;
    }

    public static String getLwjglAbiOverrideClasspath(JMinecraftVersionList.Version info) {
        List<String> paths = new ArrayList<>();
        if (info == null || info.libraries == null) return "";
        boolean versionUsesSdl = resolveLwjglMode(info) == LwjglMode.NEW_SDL;
        if (!versionUsesSdl) return "";

        for (DependentLibrary libItem : info.libraries) {
            if (libItem == null || !checkRules(libItem.rules)) continue;
            String libName = libItem.name;
            if (libName == null || !libName.startsWith("org.lwjgl:lwjgl:")) {
                if (libName == null || !libName.startsWith("org.lwjgl:lwjgl-")) continue;
            }
            // Natives classifier artifacts are extracted separately and must
            // never be put on the Java class path. On Android, leaving a
            // natives-linux-x64 jar here makes LWJGL report a platform mismatch
            // on the aarch64 guest before the GLFW hook can install. Check both
            // the Maven coordinate and the resolved artifact path: some version
            // manifests keep a three-part name while their downloads.artifact
            // path still points at a classifier jar.
            if (isLwjglNativeArtifact(libItem)) continue;

            // The bridge supplies only the Android GLFW entry class and its
            // small support types. Keep the exact version's lwjgl-glfw jar so
            // its callback classes match the exact core/Upcalls ABI; the bridge
            // is first on the class path, so its Android GLFW.class still wins.
            // Vulkan remains supplied by the bridge because its Android callback
            // implementation is part of that payload. All other LWJGL modules,
            // including core and SDL, come from this exact version's libraries.
            if (libName.startsWith("org.lwjgl:lwjgl-vulkan:")) continue;

            String libArtifactPath = artifactToPath(libItem);
            if (libArtifactPath == null) continue;
            String fullPath = ProfilePathHome.getLibrariesHome() + "/" + libArtifactPath;
            if (FileUtils.exists(fullPath) && !paths.contains(fullPath)) paths.add(fullPath);
        }
        return String.join(":", paths);
    }

    public static String getLwjglNativeLibraryOverride(JMinecraftVersionList.Version info) {
        return resolveLwjglMode(info) == LwjglMode.NEW_SDL ? null : "lwjgl-legacy";
    }

    public static JMinecraftVersionList.Version getVersionInfo(Version version) {
        return getVersionInfo(version, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JMinecraftVersionList.Version getVersionInfo(Version version, boolean skipInheriting) {
        try {
            JMinecraftVersionList.Version customVer = Tools.GLOBAL_GSON.fromJson(read(new File(version.getVersionPath(), version.getVersionName() + ".json")), JMinecraftVersionList.Version.class);
            if (customVer == null) {
                throw new RuntimeException("Corrupt version json for " + version.getVersionName() + " (parsed to null)");
            }
            if (customVer.libraries == null) customVer.libraries = new DependentLibrary[0];
            if (skipInheriting || customVer.inheritsFrom == null || customVer.inheritsFrom.equals(customVer.id)) {
                preProcessLibraries(customVer.libraries);
            } else {
                JMinecraftVersionList.Version inheritsVer;
                //If it won't download, just search for it
                try {
                    inheritsVer = Tools.GLOBAL_GSON.fromJson(read(version.getVersionsFolder() + "/" + customVer.inheritsFrom + "/" + customVer.inheritsFrom + ".json"), JMinecraftVersionList.Version.class);
                } catch (IOException e) {
                    throw new RuntimeException("Can't find the source version for " + version.getVersionName() + " (req version=" + customVer.inheritsFrom + ")");
                }
                if (inheritsVer == null) {
                    throw new RuntimeException("Corrupt version json for inherited version " + customVer.inheritsFrom + " (parsed to null)");
                }
                if (inheritsVer.libraries == null) inheritsVer.libraries = new DependentLibrary[0];
                //inheritsVer.inheritsFrom = inheritsVer.id;
                insertSafety(inheritsVer, customVer,
                        "assetIndex", "assets", "id",
                        "mainClass", "minecraftArguments",
                        "releaseTime", "time", "type"
                );

                // Go through the libraries, remove the ones overridden by the custom version
                List<DependentLibrary> inheritLibraryList = new ArrayList<>(Arrays.asList(inheritsVer.libraries));
                outer_loop:
                for(DependentLibrary library : customVer.libraries){
                    // A library entry with a null or colon-less name (malformed json)
                    // used to NPE / throw StringIndexOutOfBounds here - compare on the
                    // full name instead, which still dedups exact duplicates.
                    if (library == null) continue;
                    String libName = stripLibraryVersion(library.name);

                    for(DependentLibrary inheritLibrary : inheritLibraryList) {
                        if (inheritLibrary == null) continue;
                        String inheritLibName = stripLibraryVersion(inheritLibrary.name);

                        if(libName.equals(inheritLibName)){
                            Logging.d(InfoDistributor.LAUNCHER_NAME, "Library " + libName + ": Replaced version " +
                                    libName.substring(libName.lastIndexOf(":") + 1) + " with " +
                                    inheritLibName.substring(inheritLibName.lastIndexOf(":") + 1));

                            // Remove the library , superseded by the overriding libs
                            inheritLibraryList.remove(inheritLibrary);
                            continue outer_loop;
                        }
                    }
                }

                // Fuse libraries
                inheritLibraryList.addAll(Arrays.asList(customVer.libraries));
                inheritsVer.libraries = inheritLibraryList.toArray(new DependentLibrary[0]);
                preProcessLibraries(inheritsVer.libraries);


                // Inheriting Minecraft 1.13+ with append custom args
                if (inheritsVer.arguments != null && customVer.arguments != null
                        && inheritsVer.arguments.game != null && customVer.arguments.game != null) {
                    List totalArgList = new ArrayList(Arrays.asList(inheritsVer.arguments.game));

                    int nskip = 0;
                    for (int i = 0; i < customVer.arguments.game.length; i++) {
                        if (nskip > 0) {
                            nskip--;
                            continue;
                        }

                        Object perCustomArg = customVer.arguments.game[i];
                        if (perCustomArg instanceof String) {
                            String perCustomArgStr = (String) perCustomArg;
                            // Check if there is a duplicate argument on combine
                            if (perCustomArgStr.startsWith("--") && totalArgList.contains(perCustomArgStr)) {
                                // A duplicate flag as the LAST arg has no i+1 - guard the
                                // read instead of throwing ArrayIndexOutOfBounds.
                                if (i + 1 >= customVer.arguments.game.length) continue;
                                perCustomArg = customVer.arguments.game[i + 1];
                                if (perCustomArg instanceof String) {
                                    perCustomArgStr = (String) perCustomArg;
                                    // If the next is argument value, skip it
                                    if (!perCustomArgStr.startsWith("--")) {
                                        nskip++;
                                    }
                                }
                            } else {
                                totalArgList.add(perCustomArgStr);
                            }
                        } else if (!totalArgList.contains(perCustomArg)) {
                            totalArgList.add(perCustomArg);
                        }
                    }

                    inheritsVer.arguments.game = totalArgList.toArray(new Object[0]);
                }

                customVer = inheritsVer;
            }

            // LabyMod 4 sets version instead of majorVersion
            if (customVer.javaVersion != null && customVer.javaVersion.majorVersion == 0) {
                customVer.javaVersion.majorVersion = customVer.javaVersion.version;
            }

            if (customVer.javaVersion != null && customVer.javaVersion.component != null) {
                String comp = customVer.javaVersion.component.toLowerCase(java.util.Locale.ROOT);
                int derivedMaj = -1;
                if (comp.startsWith("java-epsilon"))        derivedMaj = 25;
                else if (comp.startsWith("java-delta"))     derivedMaj = 21;
                else if (comp.startsWith("java-gamma"))     derivedMaj = 17;
                else if (comp.startsWith("java-beta"))      derivedMaj = 8;
                else if (comp.startsWith("java-alpha"))     derivedMaj = 8;

                if (derivedMaj > 0) {
                    Logging.i("Tools", "Resolved javaVersion via component '" + comp + "' → Java " + derivedMaj);
                    customVer.javaVersion.majorVersion = derivedMaj;
                }
            }

            // Fallback: version-name heuristic for MC 26.x when component is absent.
            // MC 26.1 / 26.2 / ... require Java 25.
            if (customVer.javaVersion != null) {
                int maj = customVer.javaVersion.majorVersion;
                String vName = customVer.id != null ? customVer.id : "";

                // If majorVersion is still 0, apply name-based heuristic
                if (maj == 0) {
                    if (vName.startsWith("26.") || vName.startsWith("27.") || vName.startsWith("28.")) {
                        customVer.javaVersion.majorVersion = 25;
                        Logging.i("Tools", "MC version " + vName + " matched 26.x+ pattern → Java 25");
                    } else {
                        customVer.javaVersion.majorVersion = 8;
                    }
                } else if (maj == 21 && (vName.startsWith("26.") || vName.startsWith("27."))) {
                    // MC 26.x shipped with majorVersion=21 in early betas; bump to 25
                    Logging.i("Tools", "MC " + vName + " javaVersion=21 overridden to 25 (26.x requires Java 25)");
                    customVer.javaVersion.majorVersion = 25;
                } else if (maj > 25 && maj < 100) {
                    // Future-proof: unknown high version → stay at 25 (highest we support)
                    Logging.i("Tools", "MC javaVersion.majorVersion=" + maj + " > 25; capping to 25");
                    customVer.javaVersion.majorVersion = 25;
                }
            }
            return customVer;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertSafety(JMinecraftVersionList.Version targetVer, JMinecraftVersionList.Version fromVer, String... keyArr) {
        for (String key : keyArr) {
            Object value = null;
            try {
                Field fieldA = fromVer.getClass().getField(key);
                value = fieldA.get(fromVer);
                if (((value instanceof String) && !((String) value).isEmpty()) || value != null) {
                    Field fieldB = targetVer.getClass().getField(key);
                    fieldB.set(targetVer, value);
                }
            } catch (Throwable th) {
                Logging.w(InfoDistributor.LAUNCHER_NAME, "Unable to insert " + key + "=" + value, th);
            }
        }
    }

    public static String read(InputStream is) throws IOException {
        String readResult = IOUtils.toString(is, StandardCharsets.UTF_8);
        is.close();
        return readResult;
    }

    public static String read(String path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static String read(File path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static void write(String path, String content) throws IOException {
        File file = new File(path);
        FileUtils.ensureParentDirectory(file);
        try(FileOutputStream outStream = new FileOutputStream(file)) {
            IOUtils.write(content, outStream);
        }
    }

    public interface DownloaderFeedback {
        void updateProgress(long curr, long max);
    }


    public static boolean compareSHA1(File f, String sourceSHA) {
        try {
            String sha1_dst;
            try (InputStream is = new FileInputStream(f)) {
                sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
            }
            if(sourceSHA != null) {
                return sha1_dst.equalsIgnoreCase(sourceSHA);
            } else{
                return true; // fake match
            }
        }catch (IOException e) {
            Logging.i("SHA1","Fake-matching a hash due to a read error",e);
            return true;
        }
    }

    public static void ignoreNotch(boolean shouldIgnore, BaseActivity activity){
        if (SDK_INT >= P) {
            if (shouldIgnore) {
                activity.getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            } else {
                activity.getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            }
            activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            Tools.updateWindowSize(activity);
        }
    }

    public static int getTotalDeviceMemory(Context ctx){
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.totalMem / 1048576L);
    }

    public static int getFreeDeviceMemory(Context ctx){
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.availMem / 1048576L);
    }

    private static int internalGetMaxContinuousAddressSpaceSize() throws Exception{
        MemoryHoleFinder memoryHoleFinder = new MemoryHoleFinder();
        new SelfMapsParser(memoryHoleFinder).run();
        long largestHole = memoryHoleFinder.getLargestHole();
        if(largestHole == -1) return -1;
        else return (int)(largestHole / 1048576L);
    }

    public static int getMaxContinuousAddressSpaceSize() {
        try {
            return internalGetMaxContinuousAddressSpaceSize();
        }catch (Exception e){
            Logging.w("Tools", "Failed to find the largest uninterrupted address space");
            return -1;
        }
    }

    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        int display = (int) (displaySideRes * scaling);
        if (display % 2 != 0) display --;
        return display;
    }

    public static String getFileName(Context ctx, Uri uri) {
        Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
        if(c == null) return uri.getLastPathSegment(); // idk myself but it happens on asus file manager
        c.moveToFirst();
        int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if(columnIndex == -1) return uri.getLastPathSegment();
        String fileName = c.getString(columnIndex);
        c.close();
        return fileName;
    }

    public static void backToMainMenu(FragmentActivity fragmentActivity) {
        fragmentActivity.getSupportFragmentManager().popBackStack(MainMenuFragment.TAG, 0);
    }

    /** Remove the current fragment */
    public static void removeCurrentFragment(FragmentActivity fragmentActivity){
        fragmentActivity.getSupportFragmentManager().popBackStack();
    }

    public static void installMod(Activity activity, boolean customJavaArgs) {
        if (MultiRTUtils.getExactJreName(8) == null) {
            Toast.makeText(activity, R.string.multirt_nojava8rt, Toast.LENGTH_LONG).show();
            return;
        }

        if(!customJavaArgs){ // Launch the intent to get the jar file
            if(!(activity instanceof LauncherActivity))
                throw new IllegalStateException("Cannot start Mod Installer without LauncherActivity");
            LauncherActivity launcherActivity = (LauncherActivity)activity;
            launcherActivity.modInstallerLauncher.launch(null);
            return;
        }

        // install mods with custom arguments
        new EditTextDialog.Builder(activity)
                .setTitle(R.string.dialog_select_jar)
                .setHintText("-jar/-cp /path/to/file.jar ...")
                .setAsRequired()
                .setConfirmListener((editBox, checked) -> {
                    Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
                    intent.putExtra("javaArgs", editBox.getText().toString());
                    SelectRuntimeUtils.selectRuntime(activity, null, jreName -> {
                        intent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName);
                        activity.startActivity(intent);
                    });

                    return true;
                }).showDialog();
    }

    /** Launch the mod installer activity. The Uri must be from our own content provider or
     * from ACTION_OPEN_DOCUMENT
     */
    public static void launchModInstaller(Activity activity, @NonNull Uri uri){
        Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
        intent.putExtra("modUri", uri);
        SelectRuntimeUtils.selectRuntime(activity, null, jreName -> {
            LauncherProfiles.generateLauncherProfiles();
            intent.putExtra(JavaGUILauncherActivity.EXTRAS_JRE_NAME, jreName);
            activity.startActivity(intent);
        });
    }


    public static void installRuntimeFromUri(Context context, Uri uri) {
        Task.runTask(() -> {
            String name = getFileName(context, uri);
            MultiRTUtils.installRuntimeNamed(
                    PathManager.DIR_NATIVE_LIB,
                    context.getContentResolver().openInputStream(uri),
                    name);

            MultiRTUtils.postPrepare(name);
            return null;
        }).onThrowable(e -> Tools.showError(context, e))
                .execute();
    }

    public static String extractUntilCharacter(String input, String whatFor, char terminator) {
        int whatForStart = input.indexOf(whatFor);
        if(whatForStart == -1) return null;
        whatForStart += whatFor.length();
        int terminatorIndex = input.indexOf(terminator, whatForStart);
        if(terminatorIndex == -1) return null;
        return input.substring(whatForStart, terminatorIndex);
    }

    public static boolean isValidString(String string) {
        return string != null && !string.isEmpty();
    }

    public static boolean checkVulkanSupport(PackageManager packageManager) {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
    }

    public static <T> T getWeakReference(WeakReference<T> weakReference) {
        if(weakReference == null) return null;
        return weakReference.get();
    }

    private static String systemPropertiesGet(String systemProperty) throws Exception {
        Class<?> cSystemProperties = Class.forName("android.os.SystemProperties");
        java.lang.reflect.Method get = cSystemProperties.getMethod("get", String.class);
        return (String) get.invoke(null, systemProperty);
    }

    private static boolean isAdreno740() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model"))) {
            String gpuRenderer = br.readLine();
            return gpuRenderer != null &&
                    gpuRenderer.toLowerCase().contains("adreno") &&
                    gpuRenderer.contains("740");
        } catch (java.io.IOException e) {
            // If the file doesn't exist, we definitely aren't on a 740
            return false;
        }
    }

    public static boolean shouldUseUBWC() {
        try {
            boolean isSamsung = Build.MANUFACTURER.equalsIgnoreCase("samsung");
            boolean isOneUI = !systemPropertiesGet("ro.build.version.oneui").isEmpty();
            return isOneUI && isSamsung && isAdreno740();
        } catch (Exception e) {
            return false;
        }
    }
}
