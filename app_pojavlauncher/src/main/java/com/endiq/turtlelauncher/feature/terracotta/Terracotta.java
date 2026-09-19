package com.endiq.turtlelauncher.feature.terracotta;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;
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

public class Terracotta {

    public enum TerracottaMode { HOST, GUEST }

    public interface StateListener {
        void onStateChanged(TerracottaState.Ready state);
    }

    private static volatile boolean initialized = false;
    private static volatile TerracottaAndroidAPI.Metadata metadata = null;
    private static volatile TerracottaMode mode = null;
    /** Application context, captured in [initialize] - used to push state updates to the
     *  VPN service notification without depending on the (possibly gone) Activity. */
    @Nullable
    private static volatile Context appContext = null;

    private static final AtomicReference<TerracottaState.Ready> STATE = new AtomicReference<>(null);
    private static final List<StateListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

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

        // TurtleLauncher diagnostic marker (Sept 2026, Friends/LAN SIGABRT at open): the
        // native library loads (and can abort the process) inside
        // TerracottaAndroidAPI.initialize() below, on THIS thread. One line naming the
        // entry path means the next native tombstone can be matched to its trigger from
        // logcat alone. Current wiring (verified): MainMenuFragment's Friends/LAN button
        // -> TerracottaFragment.onViewCreated -> TaskExecutors background executor ->
        // here. Nothing else in the app calls this.
        Logging.i("Terracotta", "Terracotta.initialize() entered on thread " + Thread.currentThread().getName());

        appContext = activity.getApplicationContext();
        metadata = TerracottaAndroidAPI.initialize(activity, () ->
            TaskExecutors.runInUIThread(() -> {
                try {
                    startTerracottaVpn(activity);
                } catch (Throwable t) {
                    Logging.e("Terracotta", "Could not start the VPN service for Terracotta", t);
                    try {
                        TerracottaAndroidAPI.getPendingVpnServiceRequest().reject();
                        mode = null;
                        setWaiting(activity, false);
                    } catch (Throwable ignored) {
                    }
                }
            })
        );

        initialized = true;
        startPolling();
        // Zalith Launcher 2 resets the backend to Waiting right after initialize() so the
        // first host/join never races a stale native state. The poll daemon needs up to one
        // tick (500ms) to observe it, and setScanning/setGuesting additionally wait for it
        // (see awaitWaitingState).
        TerracottaAndroidAPI.setWaiting();
    }

    private static void awaitWaitingState() {
        long deadline = System.currentTimeMillis() + 3000;
        while (!(STATE.get() instanceof TerracottaState.Waiting) && System.currentTimeMillis() < deadline) {
            try {
                TerracottaState.Ready next = TerracottaState.parse(TerracottaAndroidAPI.getState());
                STATE.set(next);
                if (next instanceof TerracottaState.Waiting) return;
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                Logging.w("Terracotta", "awaitWaitingState: state read failed", t);
                return;
            }
        }
    }

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
            if (initialized && (!LISTENERS.isEmpty() || mode != null)) {
                try {
                    TerracottaState.Ready current = STATE.get();
                    int index = current == null ? -1 : current.getIndex();
                    String stateJson = TerracottaAndroidAPI.getState();
                    TerracottaState.Ready next = TerracottaState.parse(stateJson);
                    consecutiveFailures = 0;
                    if (next.getIndex() > index && STATE.compareAndSet(current, next)) {
                        // Keep the VPN foreground notification in sync with the connection
                        // state - Zalith Launcher 2's notificationJob does the same via its
                        // EventViewModel (Event.Terracotta.VPNUpdateState).
                        notifyVpnServiceOfState(next);
                        TaskExecutors.runInUIThread(() -> {
                            for (StateListener listener : LISTENERS) listener.onStateChanged(next);
                        });
                    }
                } catch (Throwable t) {
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
        if (!(getState() instanceof TerracottaState.Waiting)) awaitWaitingState();
        if (!(getState() instanceof TerracottaState.Waiting)) throw new IllegalStateException("Reset to waiting state first");

        mode = TerracottaMode.HOST;
        TerracottaAndroidAPI.setScanning(room, player, extraNodes);
    }

    /** Join a room by code. Returns false if the room code was rejected outright. */
    public static boolean setGuesting(String room, @Nullable String player, @Nullable List<String> extraNodes) throws Exception {
        if (!initialized) throw new IllegalStateException("Call Terracotta.initialize() first");
        if (!(getState() instanceof TerracottaState.Waiting)) awaitWaitingState();
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
            Toast.makeText(activity, R.string.terracotta_vpn_permission_required, Toast.LENGTH_SHORT).show();
        }
    }

    private static void stopTerracottaVpn(Context context) {
        if (TerracottaVpnService.isRunning()) {
            context.stopService(new Intent(context, TerracottaVpnService.class));
        }
    }

    private static void notifyVpnServiceOfState(TerracottaState.Ready state) {
        Context context = appContext;
        if (context == null || state instanceof TerracottaState.Waiting) return;
        if (!TerracottaVpnService.isRunning()) return;

        int stringRes = state.localStringRes();
        if (stringRes == 0) return;

        try {
            Intent intent = new Intent(context, TerracottaVpnService.class)
                .setAction(TerracottaVpnService.ACTION_UPDATE_STATE)
                .putExtra(TerracottaVpnService.EXTRA_STATE_TEXT, stringRes);
            context.startService(intent);
        } catch (Throwable t) {
            // Includes ForegroundServiceStartNotAllowedException (an IllegalStateException).
            Logging.w("Terracotta", "Could not update VPN notification state text", t);
        }
    }
}
