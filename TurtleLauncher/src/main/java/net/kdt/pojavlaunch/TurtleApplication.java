package net.kdt.pojavlaunch;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.endiq.turtlelauncher.utils.ZHTools.getVersionCode;
import static com.endiq.turtlelauncher.utils.ZHTools.getVersionName;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import com.endiq.turtlelauncher.InfoDistributor;
import com.endiq.turtlelauncher.context.ContextExecutor;
import com.endiq.turtlelauncher.context.LocaleHelper;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.ui.activity.ErrorActivity;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.ZHTools;
import net.kdt.pojavlaunch.utils.FileUtils;
import java.io.File;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Date;







public class TurtleApplication extends Application {
	public static final String CRASH_REPORT_TAG = "TurtleCrashReport";

	@Override
	public void onCreate() {
		ContextExecutor.setApplication(this);

		Thread.setDefaultUncaughtExceptionHandler((thread, th) -> {
			// TurtleLauncher: renamed from latestcrash.txt -> latestlog.txt, and unified into
			// ONE folder (PathManager.DIR_LAUNCHER_LOG) instead of being split between
			// DIR_LAUNCHER_LOG and DIR_DATA depending on storage permission. That split was a
			// real bug: ZHTools.shareLogs() only zips DIR_LAUNCHER_LOG, so any report written
			// to DIR_DATA was written somewhere the user could never actually share it from.
			//
			// Deliberately NOT pointed at the GAME log (DIR_GAME_HOME/latestlog.txt), which
			// now shares this name: MainActivity/JavaGUILauncherActivity call Logger.begin()
			// on that path at every game start, and Logger.begin() truncates the file. A crash
			// report written there would be erased the next time the user launched Minecraft -
			// losing exactly the report they were trying to keep.
			File crashFile = new File(resolveCrashLogDir(), "latestlog.txt");
			try {
				// Same file NativeCrashCapture writes native/ANR deaths to - they used to have
				// separate filenames specifically to avoid overwriting each other; now that
				// they share one name, PREPEND (newest report first) and cap total size instead
				// of truncating on write, so this crash doesn't erase a different-typed one.
				FileUtils.ensureParentDirectory(crashFile);
				StringBuilder report = new StringBuilder();
				report.append(InfoDistributor.APP_NAME).append(" crash report\n");
				report.append(" - Time: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n");
				report.append(" - Device: ").append(Build.PRODUCT).append(" ").append(Build.MODEL).append("\n");
				report.append(" - Android version: ").append(Build.VERSION.RELEASE).append("\n");
				report.append(" - Launcher version: ").append(getVersionName()).append(" (").append(String.valueOf(getVersionCode())).append(")").append("\n");
				report.append(" - Crash stack trace:\n");
				report.append(Log.getStackTraceString(th));

				String previous = null;
				if (crashFile.isFile()) {
					try {
						previous = new String(java.nio.file.Files.readAllBytes(crashFile.toPath()));
					} catch (Throwable ignored) { /* fall through with previous == null */ }
				}
				String combined = (previous == null || previous.trim().isEmpty())
					? report.toString()
					: report + "\n\n════════ earlier report(s) below ════════\n\n" + previous;
				final int maxChars = 256 * 1024;
				if (combined.length() > maxChars) combined = combined.substring(0, maxChars);

				PrintStream crashStream = new PrintStream(crashFile);
				crashStream.print(combined);
				crashStream.close();
			} catch (Throwable throwable) {
				Logging.e(CRASH_REPORT_TAG, " - Exception attempt saving crash stack trace:", throwable);
				Logging.e(CRASH_REPORT_TAG, " - The crash stack trace was:", th);
			}

			ErrorActivity.showLauncherCrash(TurtleApplication.this, crashFile.getAbsolutePath(), th);
			ZHTools.killProcess();
		});

		try {
			super.onCreate();
			PathManager.DIR_DATA = getDir("files", MODE_PRIVATE).getParent();
			PathManager.DIR_CACHE = getCacheDir();
			PathManager.DIR_ACCOUNT_NEW = PathManager.DIR_DATA + "/accounts";
			Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();
			//Force x86 lib directory for Asus x86 based zenfones
			if(Architecture.isx86Device() && Architecture.is32BitsDevice()){
				String originalJNIDirectory = getApplicationInfo().nativeLibraryDir;
				getApplicationInfo().nativeLibraryDir = originalJNIDirectory.substring(0,
												originalJNIDirectory.lastIndexOf("/"))
												.concat("/x86");
			}
		} catch (Throwable throwable) {
			Intent ferrorIntent = new Intent(this, ErrorActivity.class);
			ferrorIntent.putExtra("throwable", throwable);
			ferrorIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			startActivity(ferrorIntent);
		}

		// TurtleLauncher: Shizuku/Sui binder tracking. Cheap and synchronous - it only
		// registers listeners; the binder itself is delivered later by ShizukuProvider (see
		// the manifest), and until then every caller sees "not available" and uses the
		// normal unprivileged path. Runs in whichever process this Application instance
		// belongs to, but only the `:launcher` process (where the UI lives, and which is
		// also the process ShizukuProvider is instantiated in) ever queries it.
		com.endiq.turtlelauncher.feature.shizuku.ShizukuManager.INSTANCE.init(this);

		// TurtleLauncher: AnrWatchdog, dark mode, and dynamic color theming - see
		// TurtleStartupInitializer for why this is triggered on-demand here rather than
		// via AndroidX Startup's automatic pre-onCreate discovery.
		androidx.startup.AppInitializer.getInstance(this)
			.initializeComponent(com.endiq.turtlelauncher.startup.TurtleStartupInitializer.class);
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		ContextExecutor.clearApplication();
	}

	@Override
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);
		// Smart memory management: shrink the image cache as soon as the system
		// reports memory pressure instead of waiting for a real OOM.
		// Memory is precious while the game runs, so release caches the game does not need.
		try {
			com.bumptech.glide.Glide.get(this).trimMemory(level);
		} catch (Throwable t) {
			Logging.e(CRASH_REPORT_TAG, "Failed to trim Glide memory", t);
		}
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		try {
			com.bumptech.glide.Glide.get(this).clearMemory();
		} catch (Throwable t) {
			Logging.e(CRASH_REPORT_TAG, "Failed to clear Glide memory", t);
		}
	}

	@Override
    protected void attachBaseContext(Context base) {
		ContextExecutor.setApplication(this);
        super.attachBaseContext(LocaleHelper.Companion.setLocale(base));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
		ContextExecutor.setApplication(this);
		LocaleHelper.Companion.setLocale(this);
    }

	/**
	 * Where every launcher crash report goes: PathManager.DIR_LAUNCHER_LOG, always - see the
	 * comment at the uncaught-exception handler in onCreate() for why the old two-folder
	 * split was wrong.
	 *
	 * The fallback matters and is not paranoia: DIR_LAUNCHER_LOG is a Kotlin `lateinit` that
	 * is only assigned by PathManager.initContextConstants(), which runs from an Activity's
	 * attachBaseContext(), not from Application.onCreate(). A crash before any Activity has
	 * been created therefore sees it uninitialized and reading it throws - which would kill
	 * the crash handler itself and lose the report. Falling back to DIR_DATA (assigned early
	 * in onCreate above, so always present by this point) means the report is still written
	 * somewhere rather than lost entirely.
	 */
	private static File resolveCrashLogDir() {
		try {
			File logDir = new File(PathManager.DIR_LAUNCHER_LOG);
			if (logDir.isDirectory() || logDir.mkdirs()) return logDir;
		} catch (Throwable ignored) {
			// Uninitialized (crashed before the first Activity) or unusable - fall through.
		}
		return new File(PathManager.DIR_DATA);
	}
}
