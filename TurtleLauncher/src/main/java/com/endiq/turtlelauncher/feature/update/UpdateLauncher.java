package com.endiq.turtlelauncher.feature.update;

import static com.endiq.turtlelauncher.task.TaskExecutors.runInUIThread;
import static com.endiq.turtlelauncher.utils.file.FileTools.formatFileSize;
import static com.endiq.turtlelauncher.utils.path.UrlManager.TIME_OUT;

import android.app.Dialog;
import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.ui.dialog.ProgressDialog;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.path.UrlManager;

import net.endiq.launcher.Tools;

import org.apache.commons.io.FileUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public final class UpdateLauncher {
    private final Context context;
    private final LauncherVersion launcherVersion;
    private final boolean isZipAsset;
    private final File downloadedFile;
    private final Call call;
    private ProgressDialog dialog;
    private Timer timer;
    private boolean isCanceled = false;

    public UpdateLauncher(Context context, LauncherVersion launcherVersion) {
        this.context = context;
        this.launcherVersion = launcherVersion;

        String assetName = launcherVersion.getAssetName();
        this.isZipAsset = assetName != null && assetName.toLowerCase(Locale.ROOT).endsWith(".zip");
        this.downloadedFile = isZipAsset
                ? new File(PathManager.DIR_APP_CACHE, "update_download.zip")
                : UpdateUtils.sApkFile;

        this.call = new OkHttpClient.Builder()
                .writeTimeout(TIME_OUT.getFirst(), TIME_OUT.getSecond())
                .build()
                .newCall(
                        UrlManager.createRequestBuilder(launcherVersion.getAssetDownloadUrl()).build()
                );
    }

    public void start() {
        this.call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                UpdateUtils.showFailToast(context, context.getString(R.string.update_fail));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    UpdateUtils.showFailToast(context, context.getString(R.string.update_fail_code, response.code()));
                    throw new IOException("Unexpected code " + response);
                } else {
                    File outputFile = UpdateLauncher.this.downloadedFile;
                    Objects.requireNonNull(response.body());
                    try (InputStream inputStream = response.body().byteStream();
                         OutputStream outputStream = Files.newOutputStream(outputFile.toPath())
                    ) {
                        byte[] buffer = new byte[1024 * 1024];
                        int bytesRead;

                        runInUIThread(() -> {
                            UpdateLauncher.this.dialog = new ProgressDialog(UpdateLauncher.this.context, () -> {
                                UpdateLauncher.this.stop();
                                return true;
                            });
                            UpdateLauncher.this.dialog.show();
                        });

                        final long[] downloadedSize = new long[1];
                        final long[] lastSize = {0};
                        final long[] lastTime = {ZHTools.getCurrentTimeMillis()};

                        // Throttle the refresh rate.
                        timer = new Timer();
                        timer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                long size = downloadedSize[0];
                                long currentTime = ZHTools.getCurrentTimeMillis();
                                double timeElapsed = (currentTime - lastTime[0]) / 1000.0;
                                long sizeChange = size - lastSize[0];
                                long rate = (long) (sizeChange / timeElapsed);

                                lastSize[0] = size;
                                lastTime[0] = currentTime;

                                String formattedDownloaded = formatFileSize(size);
                                String totalSize = formatFileSize(UpdateUtils.getFileSize(launcherVersion.getFileSize()));
                                handleDialog(dialog -> {
                                    dialog.updateProgress(size, UpdateUtils.getFileSize(launcherVersion.getFileSize()));
                                    dialog.updateRate(rate > 0 ? rate : 0L);
                                    dialog.updateText(String.format(context.getString(R.string.update_downloading), formattedDownloaded, totalSize));
                                });
                            }
                        }, 0, 120);

                        try {
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                                downloadedSize[0] += bytesRead;
                            }
                            finish(outputFile);
                        } catch (Exception e) {
                            handleDownloadError(e);
                        }
                    } catch (Exception e) {
                        handleDownloadError(e);
                    }
                }
            }
        });
    }

    private void finish(File outputFile) {
        timer.cancel();

        if (!isZipAsset) {
            handleDialog(Dialog::dismiss);
            UpdateUtils.installApk(context, outputFile);
            return;
        }

        runInUIThread(() -> handleDialog(dialog -> dialog.updateText(context.getString(R.string.update_extracting))));
        try {
            File extractedApk = extractApkFromZip(outputFile);
            FileUtils.deleteQuietly(outputFile); // raw zip no longer needed once the real apk is out
            handleDialog(Dialog::dismiss);
            UpdateUtils.installApk(context, extractedApk);
        } catch (IOException e) {
            handleDialog(Dialog::dismiss);
            FileUtils.deleteQuietly(outputFile);
            FileUtils.deleteQuietly(UpdateUtils.sApkFile);
            runInUIThread(() -> Toast.makeText(context, context.getString(R.string.update_invalid_package), Toast.LENGTH_LONG).show());
            Logging.e("Update Launcher", "Downloaded update package did not contain an installable APK", e);
        }
    }

    private File extractApkFromZip(File zipFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile.toPath())))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                try (OutputStream out = Files.newOutputStream(UpdateUtils.sApkFile.toPath())) {
                    byte[] buffer = new byte[1024 * 1024];
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                return UpdateUtils.sApkFile;
            }
        }
        throw new IOException("No .apk entry found inside downloaded update package");
    }

    public void handleDialog(Consumer<ProgressDialog> func) {
        if (UpdateLauncher.this.dialog != null) {
            runInUIThread(() -> func.accept(UpdateLauncher.this.dialog));
        }
    }

    private void handleDownloadError(Exception e) {
        if (isCanceled) {
            // The download was cancelled; swallow every cancellation exception.
            return;
        }

        handleDialog(Dialog::dismiss);
        runInUIThread(() -> Tools.showError(context, R.string.update_fail, e));
        timer.cancel();
        FileUtils.deleteQuietly(downloadedFile);
        Logging.e("Update Launcher", "There was an exception downloading the update!", e);
    }

    private void stop() {
        this.isCanceled = true;
        this.call.cancel();
        this.timer.cancel();
        FileUtils.deleteQuietly(downloadedFile);
    }
}
