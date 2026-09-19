package net.burningtnt.terracotta;

import android.content.Context;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * <p>An API to handle Terracotta Android.</p>
 *
 * <p>Unlike normal JNI bindings, relocating this class to another package is supported.</p>
 *
 * <h1>State Definition</h1>
 *
 * <p>For Android platform, developers must invoke {@link #initialize} with a {@link VpnServiceCallback} to initialize the rust backend.
 * Then, {@link #getState()}, {@link #setWaiting()}, {@link #setGuesting}, {@link #setScanning} are available to hook states from Terracotta.</p>
 *
 * <p>All methods here are thread-safe and can be invoked concurrently from multiple threads.</p>
 *
 * <p>For each state, self-increased {@code index} and {@code state} fields are provided.
 * A state with a greater {@code index} should be considered as a new state, while {@code state} reveals the type of the specific state.</p>
 *
 * <p>For state definitions, view <a href="https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/terracotta/TerracottaState.java#L108-L120">all subclasses of Ready</a></p>
 *
 * <h1>VpnService</h1>
 *
 * <p>Terracotta will submit VpnService Requests when EasyTier is acquiring one.
 * To configure the callback for receiving requests, see {@link #initialize}</p>
 *
 * <p>When receiving one, {@link #getPendingVpnServiceRequest()} is available to get the pending request.
 * Developer must make sure either {@link VpnServiceRequest#startVpnService} or {@link VpnServiceRequest#reject()} is invoked,
 * or Terracotta would stuck and EasyTier cannot submit a new VpnService Request.</p>
 *
 * <p>The VpnServiceRequest must be fulfilled in 30 seconds, or it will be considered as timeout.</p>
 *
 * <h1>Panic</h1>
 *
 * <p>A RuntimeException will be thrown if an error occured in native level.</p>
 */
@Keep
public final class TerracottaAndroidAPI {
    /**
     * <p>Callback for receiving VpnService Requests</p>
     *
     * <p>When receiving one, {@link #getPendingVpnServiceRequest()} is available to get the pending request.
     * Developer must make sure either {@link VpnServiceRequest#startVpnService} or {@link VpnServiceRequest#reject()} is invoked,
     * or Terracotta would stuck and EasyTier cannot submit a new VpnService Request.</p>
     *
     * @implNote The VpnServiceRequest must be fulfilled in 30 seconds, or it will be considered as timeout.
     */
    @Keep
    public interface VpnServiceCallback {
        void onStartVpnService();
    }

    /**
     * <p>A VpnService Request submitted by Terracotta. See {@link VpnServiceCallback}</p>
     */
    @Keep
    public interface VpnServiceRequest {
        /**
         * Create a Vpn Connection and fulfill the VpnService Request.
         *
         * @param builder A pre-configured VpnService builder.
         * @return The established Vpn Connection. Developers must close this file descriptor after EasyTier exits.
         * @throws RuntimeException if {@link VpnService.Builder#establish()} returns null.
         * @implNote Developers is able to configure the builder before passing it into Terracotta
         * to fully-custom the connection.
         */
        ParcelFileDescriptor startVpnService(VpnService.Builder builder);

        /**
         * <p>Reject the VpnServiceRequest.</p>
         */
        void reject();
    }

    /**
     * <p>Metadata of Terracotta Android</p>
     */
    @Keep
    public static final class Metadata {
        private final String terracottaVersion;

        private final long terracottaCompileTime;

        private final String easyTierVersion;

        public Metadata(String terracottaVersion, long terracottaCompileTime, String easyTierVersion) {
            this.terracottaVersion = terracottaVersion;
            this.terracottaCompileTime = terracottaCompileTime;
            this.easyTierVersion = easyTierVersion;
        }

        /**
         * @return Terracotta Android version.
         */
        public String getTerracottaVersion() {
            return terracottaVersion;
        }

        /**
         * @return Terracotta Android compile timestamp. The format is identical with {@link System#currentTimeMillis()}.
         */
        public long getTerracottaCompileTime() {
            return terracottaCompileTime;
        }

        /**
         * @return EasyTier version.
         */
        public String getEasyTierVersion() {
            return easyTierVersion;
        }
    }

    private static final String[] EXPECTED_JNI_TABLE = {
        "start0(Ljava/lang/String;I)I",
        "getState0()Ljava/lang/String;",
        "setWaiting0()V",
        "setScanning0(Ljava/lang/String;Ljava/lang/String;)V",
        "setGuesting0(Ljava/lang/String;Ljava/lang/String;)Z",
        "verifyRoomCode0(Ljava/lang/String;)I",
        "getMetadata0()Ljava/lang/String;",
        "prepareExportLogs0()J",
        "finishExportLogs0(J)V",
        "panic0()V",
        "onVpnServiceStateChanged(BBBBSLjava/lang/String;)I",
    };

    static {
        try {
            Log.i("TerracottaAndroidAPI", "About to load libterracotta.so (thread="
                + Thread.currentThread().getName() + ", native_location="
                + TerracottaAndroidAPI.class.getName().replace('.', '/') + ")");
            Log.i("TerracottaAndroidAPI", "Expected JNI descriptors (name+signature the native side registers): "
                + Arrays.toString(EXPECTED_JNI_TABLE));
        } catch (Throwable ignored) {
            // Never let logging abort class initialization.
        }
        System.setProperty("net.burningtnt.terracotta.native_location", TerracottaAndroidAPI.class.getName().replace('.', '/'));
        System.loadLibrary("terracotta");
        // Reached only if JNI_OnLoad completed without aborting - its own marker, so a
        // missing line between the two above pins any crash to inside loadLibrary itself.
        try {
            Log.i("TerracottaAndroidAPI", "libterracotta.so loaded and JNI_OnLoad completed");
        } catch (Throwable ignored) {
        }
    }

    private static volatile VpnServiceRequest pendingRequest = null;

    private static final class RuntimeContext {
        private final VpnServiceCallback vpnServiceCallback;

        private final RandomAccessFile logging;

        public RuntimeContext(VpnServiceCallback vpnServiceCallback, RandomAccessFile logging) {
            this.vpnServiceCallback = vpnServiceCallback;
            this.logging = logging;
        }
    }

    private static volatile RuntimeContext runtimeContext = null;

    /** One-shot guard for the native start0() call - see initialize()'s doc comment. */
    private static final AtomicBoolean START_ATTEMPTED = new AtomicBoolean(false);

    /**
     * <p>Get current pending VpnService Request.</p>
     *
     * @return The pending VpnService Request.
     * @throws IllegalStateException if no pending VpnService Request exists.
     */
    public static VpnServiceRequest getPendingVpnServiceRequest() {
        VpnServiceRequest handle = pendingRequest;
        if (handle == null) {
            throw new IllegalStateException("There's no pending VpnService request.");
        }
        return handle;
    }

    /**
     * <p>Initialize the Terracotta Android.</p>
     *
     * @param context  An Android context object.
     * @param callback A callback to handle VpnService for EasyTier. See {@link VpnServiceCallback} for more information.
     */
    public static synchronized Metadata initialize(Context context, VpnServiceCallback callback) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callback, "callback");

        if (runtimeContext != null) {
            throw new IllegalStateException("Terracotta Android has already started.");
        }

        File root = new File(context.getFilesDir(), "net.burningtnt.terracotta");

        File base = new File(root, "rs");
        base.mkdirs();
        if (!base.isDirectory()) {
            throw new RuntimeException("Cannot create net.burningtnt.terracotta/rs directory.");
        }

        RandomAccessFile logging;
        int fd;
        try {
            logging = new RandomAccessFile(new File(root, "application.log"), "rw");
            fd = ParcelFileDescriptor.dup(logging.getFD()).detachFd();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // One-shot native start guard - see the method doc. Armed only here, immediately
        // before the call it protects, so pre-native setup failures remain retryable.
        if (!START_ATTEMPTED.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "Terracotta Android's native backend already had a failed start in this process; " +
                "not calling into it again with possibly half-initialized state. Restart the app " +
                "to retry. (The first failure's error was reported when it happened.)");
        }

        int code = start0(base.getAbsolutePath(), fd);
        if (code != 0) {
            throw new RuntimeException("Cannot start Terracotta Android: " + code);
        }

        runtimeContext = new RuntimeContext(callback, logging);

        String[] parts = getMetadata0().split("\0", 4);
        if (parts.length != 3) {
            throw new AssertionError("Should NOT be here.");
        }
        // The compile-time field comes from the native backend as text - a malformed
        // value must not crash the app. 0 matches the established "unknown" fallback
        // (see Terracotta.getMetadata()).
        long compileTime;
        try {
            compileTime = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            Log.w("TerracottaAndroidAPI", "Malformed native compile-time metadata: " + parts[1], e);
            compileTime = 0L;
        }
        return new Metadata(parts[0], compileTime, parts[2]);
    }

    /**
     * <p>Fetch current state from Terracotta Android.</p>
     *
     * @return A json representing the current state. See {@link TerracottaAndroidAPI} for state definitions.
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static String getState() {
        assertStarted();
        return getState0();
    }

    /**
     * <p>Set Terracotta Android into 'waiting' state.</p>
     *
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static void setWaiting() {
        assertStarted();
        setWaiting0();
    }

    /**
     * <p>Set Terracotta Android into 'host-scanning' state.</p>
     *
     * @param room       the room code, which may be used if it's valid.
     * @param player     the player's name. A default value will be taken if it's null.
     * @param extraNodes extra public server nodes for EasyTier. NOT forwarded - see
     *                   {@link #warnExtraNodesUnsupported}. Accepted and ignored so callers
     *                   keep compiling; the native ABI has no such parameter.
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static void setScanning(@Nullable String room, @Nullable String player, @Nullable List<String> extraNodes) {
        assertStarted();
        warnExtraNodesUnsupported(extraNodes);
        setScanning0(room, player);
    }

    /**
     * <p>Set Terracotta Android into 'guest-connecting' state.</p>
     *
     * @param room       the room code. False will be returned if it's invalid.
     * @param player     the player's name. A default value will be taken if it's null.
     * @param extraNodes extra public server nodes for EasyTier. NOT forwarded - see
     *                   {@link #warnExtraNodesUnsupported}.
     * @return True if room code is valid, false otherwise.
     * @throws IllegalStateException if Terracotta Android hasn't been initialized.
     * @throws NullPointerException  if room is null.
     * @implNote Usually, this method doesn't take a long time to fetch states.
     * However, when initializing the EasyTier, state fetching may block for ~1 seconds.
     */
    public static boolean setGuesting(String room, @Nullable String player, @Nullable List<String> extraNodes) {
        Objects.requireNonNull(room, "room");

        assertStarted();
        warnExtraNodesUnsupported(extraNodes);
        return setGuesting0(room, player);
    }

    private static void warnExtraNodesUnsupported(@Nullable List<String> extraNodes) {
        if (extraNodes != null && !extraNodes.isEmpty() && EXTRA_NODES_WARNING.compareAndSet(false, true)) {
            Log.w("TerracottaAndroidAPI", "Ignoring " + extraNodes.size()
                + " extra EasyTier node(s): libterracotta.so (Terracotta 0.4.2) has no "
                + "extraNodes entry point, so only its built-in node set is used.");
        }
    }

    private static final AtomicBoolean EXTRA_NODES_WARNING = new AtomicBoolean(false);

    /**
     * Room types supported by Terracotta Android
     */
    public enum RoomType {
        TERRACOTTA_LEGACY, PCL2CE, SCAFFOLDING
    }

    /**
     * <p>Parse the given room code.</p>
     *
     * @param room A room code.
     * @return The type of the room, or null if invalid.
     * @throws NullPointerException if room is null
     * @implNote Though this method is fast enough to be invoked in UI thread,
     * dispatch all invocation to a separated worker is better in order to
     * prevent potential UI freezing issues.
     */
    public static RoomType parseRoomCode(String room) {
        Objects.requireNonNull(room, "room");

        assertStarted();
        switch (verifyRoomCode0(room)) {
            case -1:
                return null;
            case 1:
                return RoomType.TERRACOTTA_LEGACY;
            case 2:
                return RoomType.PCL2CE;
            case 3:
                return RoomType.SCAFFOLDING;
            default:
                throw new AssertionError("Should NOT be here.");
        }
    }

    /**
     * <p>Collect logs of Terracotta Android.</p>
     *
     * <p>Developers must immediately copy all data out of the returned reader and close it.
     * Otherwise all methods, except 'parseRoomCode', will be blocked.</p>
     *
     * @return A reader containing logs.
     * @throws RuntimeException if logging export is unsupported.
     */
    public static Reader collectLogs() throws IOException {
        assertStarted();

        long ptr = prepareExportLogs0();
        if (ptr == 0) {
            throw new RuntimeException("Cannot export logs from Terracotta Android.");
        }

        RandomAccessFile file = runtimeContext.logging;
        file.seek(0);

        return new BufferedReader(new InputStreamReader(new InputStream() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public int read() throws IOException {
                assertOpen();
                return file.read();
            }

            @Override
            public int read(byte[] b) throws IOException {
                assertOpen();
                return file.read(b);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                assertOpen();
                return file.read(b, off, len);
            }

            @Override
            public int available() throws IOException {
                assertOpen();
                return Math.toIntExact(file.length() - file.getFilePointer());
            }

            @Override
            public void close() throws IOException {
                super.close();
                cleanup();
            }

            @Override
            protected void finalize() throws Throwable {
                super.finalize();
                cleanup();
            }

            private void assertOpen() throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream has already been closed");
                }
            }

            private void cleanup() {
                if (closed.compareAndSet(false, true)) {
                    finishExportLogs0(ptr);
                }
            }
        }, StandardCharsets.UTF_8));
    }

    /**
     * @deprecated This API is exposed for debug purpose.
     */
    @Deprecated
    public static void panic() {
        panic0();
        throw new AssertionError("Should NOT be here: A RuntimeException should be thrown in panic0");
    }

    private static final long FD_PENDING = ((long) Integer.MAX_VALUE) + 1;
    private static final long FD_REJECT = FD_PENDING + 1;

    @Keep
    @SuppressWarnings("unused") // Native callback
    private static int onVpnServiceStateChanged(byte ip1, byte ip2, byte ip3, byte ip4, short network_length, String cidr) throws UnknownHostException {
        if (pendingRequest != null) {
            throw new AssertionError("Should NOT be here.");
        }

        AtomicLong fd = new AtomicLong(FD_PENDING);
        InetAddress address = InetAddress.getByAddress(new byte[]{ip1, ip2, ip3, ip4});

        pendingRequest = new VpnServiceRequest() {
            @Override
            public ParcelFileDescriptor startVpnService(VpnService.Builder builder) {
                builder.addAddress(address, network_length)
                        .addDnsServer("223.5.5.5")
                        .addDnsServer("114.114.114.114");

                if (!cidr.isEmpty()) {
                    for (String part : cidr.split("\0")) {
                        String[] parts = part.split("/", 3);
                        if (parts.length != 2) {
                            Log.w("TerracottaAndroidAPI", "Skipping illegal CIDR route: " + Arrays.toString(parts));
                            continue;
                        }
                        int prefixLength;
                        try {
                            prefixLength = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            Log.w("TerracottaAndroidAPI", "Skipping CIDR route with bad prefix: " + part, e);
                            continue;
                        }
                        builder.addRoute(parts[0], prefixLength);
                    }
                }

                ParcelFileDescriptor connection = builder.establish();
                if (connection == null) {
                    throw new RuntimeException("Cannot establish a VPN connection.");
                }

                fd.set(connection.getFd());
                return connection;
            }

            @Override
            public void reject() {
                fd.set(FD_REJECT);
            }
        };

        TerracottaAndroidAPI.runtimeContext.vpnServiceCallback.onStartVpnService();

        long timestamp = System.currentTimeMillis();
        while (true) {
            long value = fd.get();
            if (value == FD_PENDING) {
                if (System.currentTimeMillis() - timestamp >= 30000) {
                    Log.wtf("TerracottaAndroidAPI", "VpnService Request hasn't been fulfilled in 30s.");
                    throw new IllegalStateException();
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            } else if (value == FD_REJECT) {
                pendingRequest = null;
                throw new IllegalStateException();
            } else {
                pendingRequest = null;

                if ((int) value != value) {
                    throw new AssertionError("Should NOT be here.");
                }
                return (int) value;
            }
        }
    }

    private static void assertStarted() {
        if (runtimeContext == null) {
            throw new IllegalStateException("Terracotta Android hasn't started yet.");
        }
    }

    @Keep
    private static native int start0(String baseDir, int loggingFD);

    @Keep
    private static native String getState0();

    @Keep
    private static native void setWaiting0();

    @Keep
    private static native void setScanning0(String room, String player);

    @Keep
    private static native boolean setGuesting0(String room, String player);

    @Keep
    private static native int verifyRoomCode0(String room);

    @Keep
    private static native String getMetadata0();

    @Keep
    private static native long prepareExportLogs0();

    @Keep
    private static native void finishExportLogs0(long pointer);

    @Keep
    private static native void panic0();
}