package net.endiq.launcher.utils;

import static com.endiq.turtlelauncher.utils.path.PathManager.DIR_NATIVE_LIB;
import static net.endiq.launcher.Architecture.ARCH_X86;
import static net.endiq.launcher.Architecture.is64BitsDevice;
import static net.endiq.launcher.Tools.currentDisplayMetrics;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.endiq.turtlelauncher.InfoDistributor;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.event.value.JvmExitEvent;
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathHome;
import com.endiq.turtlelauncher.feature.customprofilepath.ProfilePathManager;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.feature.version.Version;
import com.endiq.turtlelauncher.feature.version.VersionInfo;
import com.endiq.turtlelauncher.plugins.driver.DriverPluginManager;
import com.endiq.turtlelauncher.plugins.renderer.RendererPluginManager;
import com.endiq.turtlelauncher.plugins.renderer.RendererPlugin;
import com.endiq.turtlelauncher.renderer.RendererInterface;
import com.endiq.turtlelauncher.renderer.Renderers;
import com.endiq.turtlelauncher.renderer.renderers.HolyGL4ESRenderer;
import com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.ui.activity.ErrorActivity;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.path.LibPath;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.oracle.dalvik.VMLauncher;

import net.endiq.launcher.Architecture;
import net.endiq.launcher.Logger;
import net.endiq.launcher.Tools;
import net.endiq.launcher.multirt.MultiRTUtils;
import net.endiq.launcher.multirt.Runtime;
import net.endiq.launcher.plugins.FFmpegPlugin;

import org.greenrobot.eventbus.EventBus;
import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public final class JREUtils {
    private JREUtils() {}

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;

    public static String findInLdLibPath(String libName) {
        if (Os.getenv("LD_LIBRARY_PATH")==null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            }catch (ErrnoException e) {
                Logging.e("JREUtils", Tools.printToString(e));
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if (list != null) {
            for(File f : list) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    returnValue.add(f);
                }else if (f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    public static void initJavaRuntime(String jreHome) {
        dlopen(findInLdLibPath("libjli.so"));
        if (!dlopen("libjvm.so")) {
            Logging.w("DynamicLoader","Failed to load with no path, trying with full path");
            dlopen(jvmLibraryPath+"/libjvm.so");
        }
        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        // dlopen(findInLdLibPath("libjsig.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        for (File f : locateLibs(new File(jreHome, Tools.DIRNAME_HOME_JRE))) {
            dlopen(f.getAbsolutePath());
        }
    }

    public static void redirectAndPrintJRELog() {

        Logging.v("jrelog","Log starts here");
        new Thread(new Runnable(){
            int failTime = 0;
            ProcessBuilder logcatPb;
            @Override
            public void run() {
                try {
                    if (logcatPb == null) {
                        logcatPb = new ProcessBuilder().command("logcat", /* "-G", "1mb", */ "-v", "brief", "-s", "jrelog:I", "LIBGL:I", "NativeInput").redirectErrorStream(true);
                    }

                    Logging.i("jrelog-logcat","Clearing logcat");
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();
                    Logging.i("jrelog-logcat","Starting logcat");
                    Process p = logcatPb.start();

                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = p.getInputStream().read(buf)) != -1) {
                        String currStr = new String(buf, 0, len);
                        Logger.appendToLog(currStr);
                    }

                    if (p.waitFor() != 0) {
                        Logging.e("jrelog-logcat", "Logcat exited with code " + p.exitValue());
                        failTime++;
                        Logging.i("jrelog-logcat", (failTime <= 10 ? "Restarting logcat" : "Too many restart fails") + " (attempt " + failTime + "/10");
                        if (failTime <= 10) {
                            run();
                        } else {
                            Logger.appendToLog("ERROR: Unable to get more Logging.");
                        }
                    }
                } catch (Throwable e) {
                    Logging.e("jrelog-logcat", "Exception on logging thread", e);
                    Logger.appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                }
            }
        }).start();
        Logging.i("jrelog-logcat","Logcat thread started");

    }

    public static void relocateLibPath(Runtime runtime, String jreHome) {
        String JRE_ARCHITECTURE = runtime.arch;
        if (Architecture.archAsInt(JRE_ARCHITECTURE) == ARCH_X86){
            JRE_ARCHITECTURE = "i386/i486/i586";
        }

        for (String arch : JRE_ARCHITECTURE.split("/")) {
            File f = new File(jreHome, "lib/" + arch);
            if (f.exists() && f.isDirectory()) {
                Tools.DIRNAME_HOME_JRE = "lib/" + arch;
            }
        }

        String libName = is64BitsDevice() ? "lib64" : "lib";
        StringBuilder ldLibraryPath = new StringBuilder();
        if (FFmpegPlugin.isAvailable) {
            ldLibraryPath.append(FFmpegPlugin.libraryPath).append(":");
        }
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();
        if (customRenderer != null) {
            ldLibraryPath.append(customRenderer.getPath()).append(":");
        }
        ldLibraryPath.append(jreHome)
                .append("/").append(Tools.DIRNAME_HOME_JRE)
                .append("/jli:").append(jreHome).append("/").append(Tools.DIRNAME_HOME_JRE)
                .append(":");
        ldLibraryPath.append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:");
        File runtimeModDir = PathManager.DIR_RUNTIME_MOD;
        if (runtimeModDir != null) {
            ldLibraryPath.append(runtimeModDir.getAbsolutePath()).append(":");
        }
        ldLibraryPath.append(DIR_NATIVE_LIB);
        LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    private static void initLdLibraryPath(String jreHome) {
        File serverFile = new File(jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/server/libjvm.so");
        jvmLibraryPath = jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/" + (serverFile.exists() ? "server" : "client");
        Logging.d("DynamicLoader","Base LD_LIBRARY_PATH: " + LD_LIBRARY_PATH);
        Logging.d("DynamicLoader","Internal LD_LIBRARY_PATH: "+jvmLibraryPath + ":" + LD_LIBRARY_PATH);
        setLdLibraryPath(jvmLibraryPath + ":" + LD_LIBRARY_PATH);
    }

    private static void setJavaEnv(Map<String, String> envMap, String jreHome) {
        envMap.put("POJAV_NATIVEDIR", DIR_NATIVE_LIB);
        envMap.put("DRIVER_PATH", DriverPluginManager.getDriver().getPath());
        envMap.put("JAVA_HOME", jreHome);

        envMap.put("ALSOFT_DRIVERS", "opensl");
        envMap.put("HOME", PathManager.DIR_GAME_HOME);
        envMap.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", jreHome + "/bin:" + Os.getenv("PATH"));
        envMap.put("FORCE_VSYNC", String.valueOf(AllSettings.getForceVsync().getValue()));
        envMap.put("AWTSTUB_WIDTH", Integer.toString(CallbackBridge.windowWidth > 0 ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth));
        envMap.put("AWTSTUB_HEIGHT", Integer.toString(CallbackBridge.windowHeight > 0 ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight));
        envMap.put("MOD_ANDROID_RUNTIME", PathManager.DIR_RUNTIME_MOD != null ? PathManager.DIR_RUNTIME_MOD.getAbsolutePath() : "");

        // Fix: on arm64 devices with ARM Memory Tagging Extension (MTE) enabled by default
        // (Pixel 8+, Galaxy S24+, and other newer SoCs), the GPU driver's native code trips
        // MTE tag-check faults and SIGSEGVs - most visibly in the Vulkan/Zink backend on the
        // latest Minecraft versions. Preloading this shim calls mallopt() to disable heap tag
        // checking for the game process before anything else runs. It's a no-op (and harmless)
        // on devices/architectures without MTE.
        File heapTaggingShim = new File(DIR_NATIVE_LIB, "libdisable_heap_tagging.so");
        if (heapTaggingShim.exists()) {
            String existingPreload = envMap.get("LD_PRELOAD");
            envMap.put("LD_PRELOAD", existingPreload == null || existingPreload.isEmpty()
                    ? heapTaggingShim.getAbsolutePath()
                    : existingPreload + ":" + heapTaggingShim.getAbsolutePath());
        }

        if (AllSettings.getDumpShaders().getValue())
            envMap.put("LIBGL_VGPU_DUMP", "1");
        if (AllSettings.getZinkPreferSystemDriver().getValue())
            envMap.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "1");
        if (AllSettings.getVsyncInZink().getValue())
            envMap.put("POJAV_VSYNC_IN_ZINK", "1");
        if (AllSettings.getAdaptiveVsync().getValue())
            envMap.put("POJAV_ADAPTIVE_VSYNC", "1");
        if (AllSettings.getLowLatencyRendering().getValue())
            envMap.put("POJAV_LOW_LATENCY_RENDERING", "1");
        if (AllSettings.getBigCoreAffinity().getValue())
            envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
        // TurtleLauncher Phone Settings: Scheduler Tuning - only meaningful once Advanced
        // CPU Tuning is expanded; same env-var-handoff pattern as POJAV_BIG_CORE_AFFINITY
        // above (native side reads the flag, this launcher only signals intent).
        if (AllSettings.getAdvancedCpuTuning().getValue() && AllSettings.getSchedulerTuning().getValue())
            envMap.put("POJAV_SCHED_TUNING", "1");
        if (FFmpegPlugin.isAvailable)
            envMap.put("POJAV_FFMPEG_PATH", FFmpegPlugin.executablePath);
    }

    private static void setRendererEnv(Map<String, String> envMap) {
        RendererInterface currentRenderer = Renderers.INSTANCE.getCurrentRenderer();
        String rendererId = currentRenderer.getRendererId();

        boolean isGl4esRenderer = rendererId.equals(HolyGL4ESRenderer.ID);

        if (isGl4esRenderer) {
            envMap.put("LIBGL_ES", "2");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NOERROR", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_NORMALIZE", "1");

            if (com.endiq.turtlelauncher.setting.AllSettings.getJniBatching().getValue())
                envMap.put("LIBGL_BATCH", "1"); // batch GL calls instead of dispatching each one immediately
            if (com.endiq.turtlelauncher.setting.AllSettings.getJniCachedReferences().getValue())
                envMap.put("LIBGL_USEVBO", "1"); // keep vertex data in VBOs instead of re-uploading client-side arrays
            if (com.endiq.turtlelauncher.setting.AllSettings.getReducedJniCalls().getValue())
                envMap.put("LIBGL_SKIPTEXCOPIES", "1"); // skip a redundant texture copy on upload
            if (com.endiq.turtlelauncher.setting.AllSettings.getNativeObjectPooling().getValue())
                envMap.put("LIBGL_RECYCLEFBO", "1"); // reuse framebuffer objects instead of recreating them
        }

        envMap.putAll(currentRenderer.getRendererEnv().getValue());

        String eglName = currentRenderer.getRendererEGL();
        if (eglName != null) envMap.put("POJAVEXEC_EGL", eglName);

        envMap.put("POJAV_RENDERER", currentRenderer.getNativeRendererId());

        if (RendererPluginManager.getSelectedRendererPlugin() != null) return;

        boolean isZinkRenderer = rendererId.equals(ZinkRenderer.ID);
        boolean shaderCacheEnabled = com.endiq.turtlelauncher.setting.AllSettings.getRendererShaderCacheEnabled().getValue();

        if (isZinkRenderer) {
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            if (shaderCacheEnabled) envMap.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
            else envMap.put("MESA_GLSL_CACHE_DISABLE", "true");
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("LIB_MESA_NAME", loadGraphicsLibrary());
        } else if (!isGl4esRenderer) {
            // VirGL, VGPU, Freedreno - Mesa-gallium-based, need the cache dir/compat flags
            // but not the Zink driver override.
            if (shaderCacheEnabled) envMap.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
            else envMap.put("MESA_GLSL_CACHE_DISABLE", "true");
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("LIB_MESA_NAME", loadGraphicsLibrary());
        }

        if (com.endiq.turtlelauncher.setting.AllSettings.getRendererDebugLogging().getValue()) {
            Logging.i("RendererDebug", "renderer=" + rendererId + " gl4esFamily=" + isGl4esRenderer +
                    " zink=" + isZinkRenderer + " shaderCache=" + shaderCacheEnabled +
                    " jni=[batch=" + com.endiq.turtlelauncher.setting.AllSettings.getJniBatching().getValue() +
                    ",cache=" + com.endiq.turtlelauncher.setting.AllSettings.getJniCachedReferences().getValue() +
                    ",pool=" + com.endiq.turtlelauncher.setting.AllSettings.getNativeObjectPooling().getValue() +
                    ",reduced=" + com.endiq.turtlelauncher.setting.AllSettings.getReducedJniCalls().getValue() + "]" +
                    " env=" + envMap);
        }

        if (!envMap.containsKey("LIBGL_ES")) {
            int glesMajor = getDetectedVersion();
            Logging.i("glesDetect","GLES version detected: "+glesMajor);

            if (glesMajor < 3) {
                //fallback to 2 since it's the minimum for the entire app
                envMap.put("LIBGL_ES","2");
            } else if (isGl4esRenderer) {
                envMap.put("LIBGL_ES", "2");
            } else {
                // TODO if can: other backends such as Vulkan.
                // Sure, they should provide GLES 3 support.
                envMap.put("LIBGL_ES", "3");
            }
        }
    }

    private static void setCustomEnv(Map<String, String> envMap) throws Throwable {
        File customEnvFile = new File(PathManager.DIR_GAME_HOME, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            // This file is user-edited: blank lines and comments (no '=') used to
            // crash game launch with StringIndexOutOfBounds - skip them instead.
            try (BufferedReader reader = new BufferedReader(new FileReader(customEnvFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int index = line.indexOf("=");
                    if (index <= 0) continue;
                    envMap.put(line.substring(0, index), line.substring(index + 1));
                }
            }
        }
    }

    private static void checkAndUsedJSPH(Map<String, String> envMap, final Runtime runtime) {
        boolean onUseJSPH = runtime.javaVersion > 11;
        if (!onUseJSPH) return;
        File dir = new File(DIR_NATIVE_LIB);
        if (!dir.isDirectory()) return;
        String jsphName = runtime.javaVersion == 17 ? "libjsph17" : "libjsph21";
        File[] files = dir.listFiles((dir1, name) -> name.startsWith(jsphName));
        if (files != null && files.length > 0) {
            String libName = DIR_NATIVE_LIB + "/" + jsphName + ".so";
            envMap.put("JSP", libName);
        }
    }

    private static void setEnv(String jreHome, final Runtime runtime, Version gameVersion) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();

        setJavaEnv(envMap, jreHome);
        setCustomEnv(envMap);

        String drivers = envMap.get("ALSOFT_DRIVERS");
        if (drivers == null || drivers.trim().isEmpty() || drivers.trim().equalsIgnoreCase("android")) {
            envMap.put("ALSOFT_DRIVERS", "opensl");
            Logging.w("JREUtils", "ALSOFT_DRIVERS was '" + drivers + "' (invalid for this build) — forced to 'opensl' to restore sound");
        }

        if (gameVersion != null) {
            checkAndUsedJSPH(envMap, runtime);

            VersionInfo versionInfo = gameVersion.getVersionInfo();
            if (versionInfo != null && versionInfo.getLoaderInfo() != null) {
                for (VersionInfo.LoaderInfo loaderInfo : versionInfo.getLoaderInfo()) {
                    if (loaderInfo.getLoaderEnvKey() != null) {
                        envMap.put(loaderInfo.getLoaderEnvKey(), "1");
                    }
                }
            }

            if (Renderers.INSTANCE.isCurrentRendererValid()) {
                setRendererEnv(envMap);
            }

            String eglLibraryName = envMap.get("POJAVEXEC_EGL");
            if (eglLibraryName != null) {
                // Plugin renderers (e.g. the MobileGlues app's renderer) expose their EGL
                // library as an absolute path inside the plugin - only bare names live in
                // the launcher's own native folder and need prefixing.
                envMap.put("SDL_EGL_LIBRARY",
                    eglLibraryName.startsWith("/") ? eglLibraryName : DIR_NATIVE_LIB + "/" + eglLibraryName);
            }
            String sdlGraphicsLib = loadGraphicsLibrary();
            if (sdlGraphicsLib != null) {
                envMap.put("SDL_OPENGL_LIBRARY", sdlGraphicsLib);
            }

            envMap.put("TURTLE_VERSION_CODE", String.valueOf(ZHTools.getVersionCode()));
        }

        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            }catch (NullPointerException exception){
                Logging.e("JREUtils", exception.toString());
            }
        }
    }

    private static void initGraphicAndSoundEngine(boolean renderer) {
        if (!dlopen(DIR_NATIVE_LIB + "/libopenal.so")) {
            Logging.e("SOUND_LIBRARY", "Failed to pre-load libopenal.so from " + DIR_NATIVE_LIB
                + " — sound will not work in-game. Check that the APK includes the native library.");
        }

        if (!renderer) return;

        String rendererLib = loadGraphicsLibrary();
        RendererPlugin customRenderer = RendererPluginManager.getSelectedRendererPlugin();

        if (customRenderer != null) {
            customRenderer.getDlopen().forEach(lib -> dlopen(customRenderer.getPath() + "/" + lib));
        }

        if (!dlopen(rendererLib) && !dlopen(findInLdLibPath(rendererLib))) {
            Logging.e("RENDER_LIBRARY", "Failed to load renderer " + rendererLib);
        }
    }

    private static void launchJavaVM(
            final AppCompatActivity activity,
            String runtimeHome,
            Version gameVersion,
            final List<String> JVMArgs,
            final String userArgsString
    ) {
        List<String> userArgs = getJavaArgs(runtimeHome, userArgsString);
        //Remove arguments that can interfere with the good working of the launcher
        purgeArg(userArgs,"-Xms");
        purgeArg(userArgs,"-Xmx");
        purgeArg(userArgs,"-d32");
        purgeArg(userArgs,"-d64");
        purgeArg(userArgs, "-Xint");
        purgeArg(userArgs, "-XX:+UseTransparentHugePages");
        purgeArg(userArgs, "-XX:+UseLargePagesInMetaspace");
        purgeArg(userArgs, "-XX:+UseLargePages");
        purgeArg(userArgs, "-Dorg.lwjgl.opengl.libname");
        // Don't let the user specify a custom Freetype library (as the user is unlikely to specify a version compiled for Android)
        purgeArg(userArgs, "-Dorg.lwjgl.freetype.libname");
        // Overridden by us to specify the exact number of cores that the android system has
        purgeArg(userArgs, "-XX:ActiveProcessorCount");
        // Overridden below by TurtleLauncher's GC Statistics toggle, if it's on
        purgeArg(userArgs, "-Xlog:gc");

        userArgs.add("-javaagent:" + LibPath.MIO_LIB_PATCHER.getAbsolutePath());

        //Add automatically generated args
        int ramAllocationMb = AllSettings.getRamAllocation().getValue().getValue();
        // TurtleLauncher Memory Optimizer: Equal Heap Sizes (-Xms = -Xmx) reserves the whole
        // heap up front, trading a bigger initial commit for fewer heap-resize GC pauses mid
        // game. Off instead starts small (half of -Xmx) and lets the JVM grow it on demand.
        boolean equalHeapSizes = com.endiq.turtlelauncher.setting.AllSettings.getEqualHeapSizes().getValue();
        int xmsMb = equalHeapSizes ? ramAllocationMb : Math.max(256, ramAllocationMb / 2);
        userArgs.add("-Xms" + xmsMb + "M");
        userArgs.add("-Xmx" + ramAllocationMb + "M");
        if (Renderers.INSTANCE.isCurrentRendererValid()) userArgs.add("-Dorg.lwjgl.opengl.libname=" + loadGraphicsLibrary());

        // Force LWJGL to use the Freetype library intended for it, instead of using the one
        // that we ship with Java (since it may be older than what's needed)
        userArgs.add("-Dorg.lwjgl.freetype.libname="+ DIR_NATIVE_LIB +"/libfreetype.so");

        int coreCount;
        int perInstanceCores = (gameVersion != null && com.endiq.turtlelauncher.setting.AllSettings.getPerInstanceCpuOverride().getValue())
                ? gameVersion.getVersionConfig().getCpuCoreOverride() : -1;
        if (perInstanceCores > 0) {
            coreCount = perInstanceCores;
        } else if (com.endiq.turtlelauncher.setting.AllSettings.getManualCoreOverride().getValue()) {
            coreCount = com.endiq.turtlelauncher.setting.AllSettings.getManualCoreCount().getValue();
        } else {
            coreCount = java.lang.Runtime.getRuntime().availableProcessors();
        }
        if (coreCount <= 0 || coreCount > 16) coreCount = 8;
        userArgs.add("-XX:ActiveProcessorCount=" + coreCount);

        // TurtleLauncher Phone Settings: GC Statistics - JDK unified logging of every GC
        // pause to the launcher log, so a slow session can be diagnosed after the fact.
        if (com.endiq.turtlelauncher.setting.AllSettings.getGcStatistics().getValue()) {
            userArgs.add("-Xlog:gc:file=" + PathManager.DIR_CACHE.getAbsolutePath() + "/gc.log::filecount=0");
        }

        userArgs.addAll(JVMArgs);
        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.autoram_info_msg, AllSettings.getRamAllocation().getValue().getValue()), Toast.LENGTH_SHORT).show());
        System.out.println(JVMArgs);
        for (int i = 0; i < userArgs.size(); i++) {
            String arg = userArgs.get(i);
            if (arg.startsWith("--accessToken")) {
                i += 1;
            }
            Logger.appendToLog("JVMArg: " + arg);
        }

        setupExitMethod(activity.getApplication());
        initializeGameExitHook();
        chdir(gameVersion == null ? ProfilePathHome.getGameHome() : gameVersion.getGameDir().getAbsolutePath());
        userArgs.add(0,"java");
        if (gameVersion != null && Tools.resolveLwjglMode(Tools.getVersionInfo(gameVersion)) == Tools.LwjglMode.NEW_SDL) {
            com.endiq.turtlelauncher.launch.SdlAndroidJniPrep.ensureSingleSdl3Source(gameVersion.getVersionName());
            com.endiq.turtlelauncher.launch.SdlAndroidJniPrep.ensureMobileGluesShaderErrorIgnore();
            com.endiq.turtlelauncher.launch.SdlAndroidJniPrep.setup(activity);
        }

        com.endiq.turtlelauncher.feature.log.GameLogcat.start();

        com.endiq.turtlelauncher.feature.log.GameOutputCapture.install();

        final int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
        // Reached only on a graceful JVM exit - on a signal the process is already gone and
        // the drain dies with it, which is exactly the case the captured file exists for.
        com.endiq.turtlelauncher.feature.log.GameOutputCapture.uninstall();
        com.endiq.turtlelauncher.feature.log.GameLogcat.stop();
        Logger.appendToLog("Java Exit code: " + exitCode);
        if (exitCode != 0) {
            if (com.endiq.turtlelauncher.setting.AllSettings.getFastBoot().getValue()) {
                com.endiq.turtlelauncher.setting.AllSettings.getFastBoot().put(false).save();
                Logger.appendToLog("Fast Boot auto-disabled after a crash, so the next launch re-verifies files.");
            }
            String diagnosis = com.endiq.turtlelauncher.feature.log.CrashAnalyzer.analyzeGameExit(gameVersion, exitCode);
            ErrorActivity.showExitMessage(activity, exitCode, false, diagnosis);
        }
        EventBus.getDefault().post(new JvmExitEvent(exitCode));
    }

    public static void launchWithUtils(
            final AppCompatActivity activity,
            final Runtime runtime,
            Version gameVersion,
            final List<String> JVMArgs,
            final String userArgsString
    ) throws Throwable {
        String runtimeHome = MultiRTUtils.getRuntimeHome(runtime.name).getAbsolutePath();

        relocateLibPath(runtime, runtimeHome);

        initLdLibraryPath(runtimeHome);

        setEnv(runtimeHome, runtime, gameVersion);

        initJavaRuntime(runtimeHome);

        initGraphicAndSoundEngine(gameVersion != null);

        launchJavaVM(activity, runtimeHome, gameVersion, JVMArgs, userArgsString);
    }

    /**
     *  Gives an argument list filled with both the user args
     *  and the auto-generated ones (eg. the window resolution).
     * @return A list filled with args.
     */
    public static List<String> getJavaArgs(String runtimeHome, String userArgumentsString) {
        List<String> userArguments = parseJavaArguments(userArgumentsString);
        String resolvFile;
        resolvFile = new File(PathManager.DIR_DATA,"resolv.conf").getAbsolutePath();

        ArrayList<String> overridableArguments = new ArrayList<>(Arrays.asList(
                "-Djava.home=" + runtimeHome,
                "-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath(),
                "-Djna.boot.library.path=" + DIR_NATIVE_LIB,
                "-Duser.home=" + ProfilePathManager.INSTANCE.getCurrentPath(),
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.arch=aarch64",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dturtle.path.minecraft=" + ProfilePathHome.getGameHome(),
                "-Dturtle.path.private.account=" + PathManager.DIR_ACCOUNT_NEW,
                "-Duser.timezone=" + TimeZone.getDefault().getID(),

                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
                //LWJGL 3 DEBUG FLAGS
                //"-Dorg.lwjgl.util.Debug=true",
                //"-Dorg.lwjgl.util.DebugFunctions=true",
                //"-Dorg.lwjgl.util.DebugLoader=true",
                // GLFW Stub width height
                "-Dglfwstub.windowWidth=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, AllSettings.getResolutionRatio().getValue() / 100F),
                "-Dglfwstub.windowHeight=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, AllSettings.getResolutionRatio().getValue() / 100F),
                "-Dglfwstub.initEgl=false",
                "-Dext.net.resolvPath=" +resolvFile,
                "-Dlog4j2.formatMsgNoLookups=true", //Log4j RCE mitigation

                "-Dnet.minecraft.clientmodname=" + InfoDistributor.LAUNCHER_NAME,
                "-Dfml.earlyprogresswindow=false", //Forge 1.14+ workaround
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK", // Default is POSIX_SPAWN which requires starting jspawnhelper, which doesn't work on Android
                "-Dsodium.checks.issue2561=false"
        ));

        List<String> additionalArguments = new ArrayList<>();
        for (String arg : overridableArguments) {
            String strippedArg = arg.substring(0,arg.indexOf('='));
            boolean add = true;
            for (String uarg : userArguments) {
                if (uarg.startsWith(strippedArg)) {
                    add = false;
                    break;
                }
            }
            if (add)
                additionalArguments.add(arg);
            else
                Logging.i("ArgProcessor","Arg skipped: "+arg);
        }

        //Add all the arguments
        userArguments.addAll(additionalArguments);

        return userArguments;
    }

    /**
     * Parse and separate java arguments in a user friendly fashion
     * It supports multi line and absence of spaces between arguments
     * The function also supports auto-removal of improper arguments, although it may miss some.
     *
     * @param args The un-parsed argument list.
     * @return Parsed args as an ArrayList
     */
    public static ArrayList<String> parseJavaArguments(String args){
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        //For each prefixes, we separate args.
        String[] separators = new String[]{"-XX:-","-XX:+", "-XX:","--", "-D", "-X", "-javaagent:", "-verbose"};
        for (String prefix : separators){
            while (true) {
                int start = args.indexOf(prefix);
                if (start == -1) break;
                //Get the end of the current argument by checking the nearest separator
                int end = -1;
                for (String separator: separators) {
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if (tempEnd == -1) continue;
                    if (end == -1) {
                        end = tempEnd;
                        continue;
                    }
                    end = Math.min(end, tempEnd);
                }
                //Fallback
                if (end == -1) end = args.length();

                //Extract it
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                //Check if two args aren't bundled together by mistake
                if (parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if (arraySize > 0) {
                        String lastString = parsedArguments.get(arraySize - 1);
                        // Looking for list elements
                        if (lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")) {
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                }
                else Logging.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    /**
     * Open the render library in accordance to the settings.
     * It will fallback if it fails to load the library.
     * @return The name of the loaded library
     */
    public static String loadGraphicsLibrary() {
        if (!Renderers.INSTANCE.isCurrentRendererValid()) return null;
        else {
            RendererPlugin rendererPlugin = RendererPluginManager.getSelectedRendererPlugin();
            if (rendererPlugin != null) {
                return rendererPlugin.getPath() + "/" + rendererPlugin.getGlName();
            } else {
                return Renderers.INSTANCE.getCurrentRenderer().getRendererLibrary();
            }
        }
    }

    /**
     * Remove the argument from the list, if it exists
     * If the argument exists multiple times, they will all be removed.
     * @param argList The argument list to purge
     * @param argStart The argument to purge from the list.
     */
    private static void purgeArg(List<String> argList, String argStart) {
        argList.removeIf(arg -> arg.startsWith(argStart));
    }
    private static final int EGL_OPENGL_ES_BIT = 0x0001;
    private static final int EGL_OPENGL_ES2_BIT = 0x0004;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    @SuppressWarnings("SameParameterValue")
    private static boolean hasExtension(String extensions, String name) {
        int start = extensions.indexOf(name);
        while (start >= 0) {
            // check that we didn't find a prefix of a longer extension name
            int end = start + name.length();
            if (end == extensions.length() || extensions.charAt(end) == ' ') {
                return true;
            }
            start = extensions.indexOf(name, end);
        }
        return false;
    }

    public static int getDetectedVersion() {
        /*
         * Get all the device configurations and check the EGL_RENDERABLE_TYPE attribute
         * to determine the highest ES version supported by any config. The
         * EGL_KHR_create_context extension is required to check for ES3 support; if the
         * extension is not present this test will fail to detect ES3 support. This
         * effectively makes the extension mandatory for ES3-capable devices.
         */
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] numConfigs = new int[1];
        if (egl.eglInitialize(display, null)) {
            try {
                boolean checkES3 = hasExtension(egl.eglQueryString(display, EGL10.EGL_EXTENSIONS),
                        "EGL_KHR_create_context");
                if (egl.eglGetConfigs(display, null, 0, numConfigs)) {
                    EGLConfig[] configs = new EGLConfig[numConfigs[0]];
                    if (egl.eglGetConfigs(display, configs, numConfigs[0], numConfigs)) {
                        int highestEsVersion = 0;
                        int[] value = new int[1];
                        for (int i = 0; i < numConfigs[0]; i++) {
                            if (egl.eglGetConfigAttrib(display, configs[i],
                                    EGL10.EGL_RENDERABLE_TYPE, value)) {
                                if (checkES3 && ((value[0] & EGL_OPENGL_ES3_BIT_KHR) ==
                                        EGL_OPENGL_ES3_BIT_KHR)) {
                                    if (highestEsVersion < 3) highestEsVersion = 3;
                                } else if ((value[0] & EGL_OPENGL_ES2_BIT) == EGL_OPENGL_ES2_BIT) {
                                    if (highestEsVersion < 2) highestEsVersion = 2;
                                } else if ((value[0] & EGL_OPENGL_ES_BIT) == EGL_OPENGL_ES_BIT) {
                                    if (highestEsVersion < 1) highestEsVersion = 1;
                                }
                            } else {
                                Logging.w("glesDetect", "Getting config attribute with "
                                        + "EGL10#eglGetConfigAttrib failed "
                                        + "(" + i + "/" + numConfigs[0] + "): "
                                        + egl.eglGetError());
                            }
                        }
                        return highestEsVersion;
                    } else {
                        Logging.e("glesDetect", "Getting configs with EGL10#eglGetConfigs failed: "
                                + egl.eglGetError());
                        return -1;
                    }
                } else {
                    Logging.e("glesDetect", "Getting number of configs with EGL10#eglGetConfigs failed: "
                            + egl.eglGetError());
                    return -2;
                }
            } finally {
                egl.eglTerminate(display);
            }
        } else {
            Logging.e("glesDetect", "Couldn't initialize EGL.");
            return -3;
        }
    }
    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeGameExitHook();
    public static native void setupExitMethod(Context context);
    // Obtain AWT screen pixels to render on Android SurfaceView
    public static native int[] renderAWTScreenFrame(/* Object canvas, int width, int height */);
    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
}
