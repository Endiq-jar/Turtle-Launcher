package net.endiq.launcher.utils;

import androidx.annotation.Nullable;

import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.path.UrlManager;

import net.endiq.launcher.Tools;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {

    /**
     * Shared OkHttp client used for all file/string downloads in this class.
     *
     * TurtleLauncher CRASH FIX: this class used to open raw {@link HttpURLConnection}s via
     * {@code URL.openConnection()} / {@link UrlManager#createHttpConnection(URL)}. On Android,
     * that's backed by the old, largely unmaintained "com.android.okhttp" platform stack — which
     * has a long-standing bug where it reuses a pooled keep-alive connection the server (or a
     * carrier/proxy in between) already silently closed, producing:
     *   java.io.IOException: unexpected end of stream on com.android.okhttp.Address@...
     *   Caused by: java.io.EOFException: \n not found: size=0 content=...
     * right when reading response headers, with zero bytes ever received — completely unrelated
     * to file corruption or a bad URL. This happens most on flaky/mobile networks during large
     * file downloads (libraries, the Minecraft client jar, etc.).
     *
     * The fix is to route every download through the app's real, modern OkHttp 4.x client
     * (already a dependency, already used elsewhere via UrlManager) with
     * retryOnConnectionFailure(true): OkHttp's own RetryAndFollowUpInterceptor specifically
     * detects this "stale pooled connection" class of IOException and transparently retries once
     * with a brand-new connection before ever surfacing an error — which the legacy
     * HttpURLConnection path never did. The outer 5-attempt retry loop in {@link #ensureSha1}
     * is kept as an additional safety net for genuinely unstable connections.
     */
    private static final OkHttpClient DOWNLOAD_CLIENT = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(UrlManager.TIME_OUT.getFirst(), (TimeUnit) UrlManager.TIME_OUT.getSecond())
            .readTimeout(UrlManager.TIME_OUT.getFirst(), (TimeUnit) UrlManager.TIME_OUT.getSecond())
            .build();

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        Request request = UrlManager.createRequestBuilder(url.toString()).build();
        try (Response response = DOWNLOAD_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Server returned HTTP " + response.code() + ": " + response.message());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body for " + url);
            try (InputStream is = body.byteStream()) {
                IOUtils.copy(is, os);
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Download timed out: " + url, e);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);

        Request request = UrlManager.createRequestBuilder(urlInput).build();

        try (Response response = DOWNLOAD_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Server returned HTTP " + response.code() + ": " + response.message());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body for " + urlInput);

            long length = body.contentLength();
            byte[] readBuffer = (buffer == null) ? new byte[65535] : buffer;

            try (InputStream readStr = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {
                int current;
                long overall = 0;
                while ((current = readStr.read(readBuffer)) != -1) {
                    overall += current;
                    fos.write(readBuffer, 0, current);
                    monitor.updateProgress(overall, length);
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Download timed out: " + urlInput, e);
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, boolean force, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(PathManager.DIR_CACHE_STRING, cacheName);
        if (force && cacheDestination.exists()) org.apache.commons.io.FileUtils.deleteQuietly(cacheDestination);
        if (cacheDestination.isFile() && cacheDestination.canRead() &&
                ZHTools.getCurrentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Logging.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Logging.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Logging.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        IOException lastError = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            try {
                downloadFile(downloadFunction);
                lastError = null;
            } catch (IOException e) {
                // TurtleLauncher: keep retrying through transient network errors (stale
                // connections, dropped sockets, etc.) instead of aborting on the very first
                // one — but remember the last error in case every attempt fails, so the user
                // gets a meaningful message instead of just "SHA1 verification failed".
                lastError = e;
                Logging.i("DownloadUtils", "Download attempt " + attempts + "/5 failed for " + outputFile.getName(), e);
                // TurtleLauncher: back off before retrying instead of hammering a CDN that
                // just timed out — gives transient congestion/throttling a chance to clear.
                if (attempts < 5) {
                    try {
                        Thread.sleep(Math.min(500L * attempts, 2000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) {
            if (lastError != null) {
                throw new SHA1VerificationException("SHA1 verification failed after 5 download attempts (last error: " + lastError.getMessage() + ")");
            }
            throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        }
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        Request request = new Request.Builder().url(url).head().build();
        try (Response response = DOWNLOAD_CLIENT.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String contentLength = response.header("Content-Length");
                if (contentLength != null) {
                    try {
                        return Long.parseLong(contentLength);
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
            return -1;
        }
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}
