package net.kdt.pojavlaunch;

import static com.endiq.turtlelauncher.utils.ZHTools.getVersionCode;
import static com.endiq.turtlelauncher.utils.ZHTools.getVersionName;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.endiq.turtlelauncher.context.ContextExecutor;
import com.endiq.turtlelauncher.context.LocaleHelper;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.ui.activity.ErrorActivity;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.path.PathManager;

import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Date;

/**
 * Turtle Launcher application layer on top of the Amethyst core.
 *
 * Extends {@link PojavApplication} so the Amethyst core initialization
 * (storage gate, {@link net.kdt.pojavlaunch.prefs.LauncherPreferences},
 * {@link net.kdt.pojavlaunch.tasks.AsyncAssetManager} runtime unpacking and the
 * shared executor service used by the launch pipeline) runs unchanged, then adds
 * the Turtle-specific startup: crash reporting into the Turtle log directory,
 * Shizuku management and the Turtle startup initializer.
 */
public class TurtleApplication extends PojavApplication {
	public static final String CRASH_REPORT_TAG = "TurtleCrashReport";

	@Override
	public void onCreate() {
		// Turtle path constants first, so the crash handler installed below (and any
		// early Turtle code) always has valid directories even if the core init fails.
		try {
			PathManager.DIR_DATA = getDir("files", MODE_PRIVATE).getParent();
			PathManager.DIR_CACHE = getCacheDir();
			PathManager.DIR_ACCOUNT_NEW = PathManager.DIR_DATA + "/accounts";
		} catch (Throwable t) {
			Log.e(CRASH_REPORT_TAG, "Failed to initialize Turtle path manager", t);
		}

		// Amethyst core init: crash handler, storage gate, preferences, constants,
		// architecture detection, AsyncAssetManager.unpackRuntime, executor service.
		super.onCreate();

		// Replace the Amethyst crash handler with the Turtle one (writes into the
		// Turtle log dir, keeps earlier reports, shows the Turtle error screen).
		Thread.setDefaultUncaughtExceptionHandler((thread, th) -> {
			File crashFile = new File(resolveCrashLogDir(), "latestlog.txt");
			try {
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

		com.endiq.turtlelauncher.feature.shizuku.ShizukuManager.INSTANCE.init(this);

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
