package com.endiq.turtlelauncher.feature.terracotta;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.task.TaskExecutors;

import net.burningtnt.terracotta.TerracottaAndroidAPI;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Orchestration layer around TerracottaAndroidAPI - adapted from FoldCraftLauncher's
 * Terracotta.java (FCL-Team/FoldCraftLauncher, GPLv3). Simplified: uses a plain
 * listener list + TaskExecutors.runInUIThread for state updates instead of FCL's
 * fclcore FX-style ReadOnlyObjectProperty/InvocationDispatcher, since that
 * framework isn't part of this project. Orchestration logic (initialize, poll
 * loop, host/guest mode switching, VPN service lifecycle) is otherwise unchanged.
 */
public class Terracotta {

    public enum TerracottaMode { HOST, GUEST }

    public interface StateListener {
        void onStateChanged(TerracottaState.Ready state);
    }

    private static volatile boolean initialized = false;
    private static volatile TerracottaAndroidAPI.Metadata metadata = null;
    private static volatile TerracottaMode mode = null;

    private static final AtomicReference<TerracottaState.Ready> STATE = new AtomicReference<>(null);
    private static final List<StateListener> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * TurtleLauncher CRASH/HANG FIX - this used to be `LockSupport.parkNanos(500_000)`,
     * which is 500,000 NANOseconds = 0.5 milliseconds, i.e. ~2000 native getState() calls
     * per second, each allocating a JSON string and parsing it, on a thread that never
     * stopped for the entire lifetime of the process.
     *
     * That is a unit mistake, not a tuning choice: the FCL original this was adapted from
     * sleeps 500 MILLIseconds (Thread.sleep(500)), and 500_000 nanoseconds is 1000x faster
     * than that. Terracotta's own API doc says getState() "may block for ~1 seconds" during
     * EasyTier startup, so at 0.5ms the daemon was effectively sitting inside a blocking
     * JNI call permanently while still scheduling the next one.
     *
     * On a phone that pegs a core at 100% and allocates continuously - the UI thread gets
     * starved, GC thrashes, and the launcher freezes and then gets killed. That is exactly
     * the reported symptom: open Friends/LAN, launcher freezes, then closes.
     *
     * 500ms is responsive enough for a connection-status screen (nothing here changes
     * faster than that) and costs ~0.1% of the CPU the old value did.
     */
    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /** Give up rather than spin forever if the native backend is clearly dead: the poll
     *  loop's whole job is to notice state changes, and a backend that has thrown this many
     *  times in a row is not going to start answering. */
    private static final int MAX_CONSECUTIVE_FAILURES = 40;

    private static volatile boolean polling = false;
    private static volatile Thread daemon = null;

    @Nullable
    public static TerracottaMode getMode() {
        return mode;
    }

    @Nullable
    public static TerracottaState.Ready getState() {
        return STATE.get();
    }

    public static void addStateListener(StateListener listener) {
        LISTENERS.add(listener);
        // TurtleLauncher: the daemon stops itself once the last listener goes away (and
        // after a dead native backend), so coming back to the screen has to restart it.
        if (initialized) startPolling();
    }

    public static void removeStateListener(StateListener listener) {
        LISTENERS.remove(listener);
    }

    public static TerracottaAndroidAPI.Metadata getMetadata() {
        return metadata == null ? new TerracottaAndroidAPI.Metadata("unknown", 0, "unknown") : metadata;
    }

    /**
     * Starts the native backend. Call once, before host/join is used.
     *
     * BLOCKING - this does file I/O, System.loadLibrary() and a native start0() call, all
     * of which can take a noticeable amount of time on a slow device. Call it from a
     * background thread (TerracottaFragment does); do not call it from the UI thread, or
     * opening the Friends/LAN screen will hang the UI long enough to be killed as an ANR.
     * The VPN-permission callback it installs already marshals itself back to the UI thread.
     */
    public static synchronized void initialize(Activity activity) {
        if (initialized) return;

        metadata = TerracottaAndroidAPI.initialize(activity, () ->
            TaskExecutors.runInUIThread(() -> startTerracottaVpn(activity))
        );

        initialized = true;
        startPolling();
    }

    /**
     * Starts the state-poll daemon if it is not already running.
     *
     * TurtleLauncher: previously this thread was started once from initialize() and ran
     * `while(true)` forever with no way to stop it, so it kept polling (at the buggy 0.5ms
     * interval) for the entire life of the process, including long after the Friends/LAN
     * screen was closed. It is now restartable and stoppable, and idles without touching the
     * native layer at all while nobody is listening.
     */
    private static synchronized void startPolling() {
        if (daemon != null && daemon.isAlive()) return;
        polling = true;
        Thread thread = new Thread(Terracotta::pollLoop, "Terracotta Background Daemon");
        thread.setDaemon(true);
        daemon = thread;
        thread.start();
    }

    private static void pollLoop() {
        int consecutiveFailures = 0;
        while (polling) {
            // TurtleLauncher: skip the JNI call entirely when there is no reason to make it -
            // no screen registered to publish to, and no room hosted/joined whose state
            // anyone could act on. The P2P connection itself lives in the VPN service/native
            // backend and keeps running regardless; this loop only ever *observes* it.
            // (At 500ms the cost of polling is negligible either way - this is about not
            // waking up at all when Terracotta is idle, not about shaving CPU off the poll.)
            if (initialized && (!LISTENERS.isEmpty() || mode != null)) {
                try {
                    TerracottaState.Ready current = STATE.get();
                    int index = current == null ? -1 : current.getIndex();
                    String stateJson = TerracottaAndroidAPI.getState();
                    TerracottaState.Ready next = TerracottaState.parse(stateJson);
                    consecutiveFailures = 0;
                    if (next.getIndex() > index && STATE.compareAndSet(current, next)) {
                        TaskExecutors.runInUIThread(() -> {
                            for (StateListener listener : LISTENERS) listener.onStateChanged(next);
                        });
                    }
                } catch (Throwable t) {
                    // TurtleLauncher: was `catch (Exception e)` - too narrow. A JNI-layer
                    // failure (TerracottaAndroidAPI.getState()'s native getState0()) surfaces
                    // as an Error (UnsatisfiedLinkError etc.), not an Exception, and an
                    // uncaught throwable on ANY thread takes the whole app process down on
                    // Android.
                    consecutiveFailures++;
                    Logging.e("Terracotta", "State poll failed (" + consecutiveFailures + " in a row): " + t);
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Logging.e("Terracotta", "Native backend appears dead - stopping the state poll loop");
                        polling = false;
                        return;
                    }
                }
            }

            // Back off while the backend is failing instead of hammering it, capped at 8s.
            long backoff = consecutiveFailures == 0
                ? POLL_INTERVAL_NANOS
                : Math.min(POLL_INTERVAL_NANOS << Math.min(consecutiveFailures, 4),
                           TimeUnit.SECONDS.toNanos(8));
            LockSupport.parkNanos(backoff);
        }
    }

    /** Stops the poll daemon. Restarted automatically by [addStateListener]. */
    public static synchronized void stopPolling() {
        polling = false;
        Thread thread = daemon;
        daemon = null;
        if (thread != null) thread.interrupt();
    }

    public static void setWaiting(Context context, boolean manual) {
        if (!initialized) return;
        if (manual) stopTerracottaVpn(context);
        TerracottaAndroidAPI.setWaiting();
    }

    /** Host a room. player/extraNodes may be null. */
    public static void setScanning(@Nullable String room, @Nullable String player, @Nullable List<String> extraNodes) throws Exception {
        if (!initialized) throw new IllegalStateException("Call Terracotta.initialize() first");
        if (!(getState() instanceof TerracottaState.Waiting)) throw new IllegalStateException("Reset to waiting state first");

        mode = TerracottaMode.HOST;
        TerracottaAndroidAPI.setScanning(room, player, extraNodes);
    }

    /** Join a room by code. Returns false if the room code was rejected outright. */
    public static boolean setGuesting(String room, @Nullable String player, @Nullable List<String> extraNodes) throws Exception {
        if (!initialized) throw new IllegalStateException("Call Terracotta.initialize() first");
        if (!(getState() instanceof TerracottaState.Waiting)) throw new IllegalStateException("Reset to waiting state first");

        mode = TerracottaMode.GUEST;
        return TerracottaAndroidAPI.setGuesting(room, player, extraNodes);
    }

    @Nullable
    public static TerracottaAndroidAPI.RoomType parseRoomCode(String room) {
        if (!initialized || room == null) return null;
        return TerracottaAndroidAPI.parseRoomCode(room);
    }

    @Nullable
    public static String collectLogs() {
        if (!initialized) return null;
        try (Reader reader = TerracottaAndroidAPI.collectLogs(); StringWriter writer = new StringWriter()) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) writer.write(buf, 0, n);
            return writer.toString();
        } catch (IOException e) {
            Logging.e("Terracotta", "collectLogs failed: " + e);
            return "Failed to collect logs: " + e.getMessage();
        }
    }

    private static void startTerracottaVpn(Activity activity) {
        Intent intent = VpnService.prepare(activity);
        if (intent != null) {
            activity.startActivityForResult(intent, TerracottaVpnService.VPN_PERMISSION_REQUEST_CODE);
            // The activity is responsible for calling onVpnPermissionResult() from
            // its own onActivityResult() - see TerracottaVpnService for the constant.
        } else {
            Intent vpnIntent = new Intent(activity, TerracottaVpnService.class).setAction(TerracottaVpnService.ACTION_START);
            ContextCompat.startForegroundService(activity, vpnIntent);
        }
    }

    /** Call from the hosting Activity's onActivityResult() for VPN_PERMISSION_REQUEST_CODE. */
    public static void onVpnPermissionResult(Activity activity, boolean granted) {
        if (granted) {
            Intent vpnIntent = new Intent(activity, TerracottaVpnService.class).setAction(TerracottaVpnService.ACTION_START);
            ContextCompat.startForegroundService(activity, vpnIntent);
        } else {
            TerracottaAndroidAPI.getPendingVpnServiceRequest().reject();
            setWaiting(activity, true);
            Toast.makeText(activity, "VPN permission is required for Friends/LAN play", Toast.LENGTH_SHORT).show();
        }
    }

    private static void stopTerracottaVpn(Context context) {
        if (TerracottaVpnService.isRunning()) {
            Intent intent = new Intent(context, TerracottaVpnService.class).setAction(TerracottaVpnService.ACTION_STOP);
            ContextCompat.startForegroundService(context, intent);
        }
    }
}
