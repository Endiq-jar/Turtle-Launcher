package com.endiq.turtlelauncher.feature.terracotta;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.StringRes;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.log.Logging;

import net.burningtnt.terracotta.TerracottaAndroidAPI;

import java.io.IOException;

/**
 * Terracotta's VPN foreground service.
 *
 * Reimplemented from Zalith Launcher 2's TerracottaVPNService.java
 * (ZalithLauncher/ZalithLauncher2, GPLv3 - itself modified from FCL), keeping this
 * project's own hardening: the Throwable guard around startVpnService(), the
 * self-contained notification channel, and the dedicated ic_friends_network small icon.
 *
 * What the Zalith port adds over the previous simplified version here:
 * - ACTION_UPDATE_STATE: the VPN notification now shows the live Terracotta state text
 *   ("Setting up your room…", "Room created", …) instead of a static "Hosting"/"Connected"
 *   string - Terracotta.java pushes a state resource here on every transition.
 * - ACTION_REPOST + delete intent: if the user swipes the (ongoing) notification away on
 *   an OEM ROM that allows it, it is reposted instead of leaving an invisible foreground
 *   service that the system can kill at any moment.
 * - isStopping guard so a teardown in flight is not re-foregrounded by a queued intent.
 * - buildVpnNotification() returns null when the mode is unknown, so the service never
 *   foregrounds itself for a connection that does not exist.
 * - Explicit FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE on Android 14+, matching the
 *   manifest declaration.
 */
@SuppressLint("VpnServicePolicy")
public class TerracottaVpnService extends VpnService {
    private static final String TAG = "TerracottaVpnService";
    private static final String CHANNEL_ID = "terracotta_vpn_channel";
    private static final int VPN_NOTIFICATION_ID = 1;

    public static final int VPN_PERMISSION_REQUEST_CODE = 0x7E44; // arbitrary, just needs to be a stable app-unique request code

    public static final String ACTION_START = "com.endiq.turtlelauncher.terracotta.action.START";
    public static final String ACTION_STOP = "com.endiq.turtlelauncher.terracotta.action.STOP";
    public static final String ACTION_REPOST = "com.endiq.turtlelauncher.terracotta.action.REPOST";
    public static final String ACTION_UPDATE_STATE = "com.endiq.turtlelauncher.terracotta.action.UPDATE_STATE";

    private static final String EXTRA_FROM_DELETE = "from_delete";
    public static final String EXTRA_STATE_TEXT = "terracotta_state_text";

    private NotificationManager notificationManager;
    /** Either -1 (no state text yet) or a valid string resource - deliberately not
     *  annotated @StringRes because -1 would trip the ResourceType lint. */
    private int currentStateStringRes = -1;
    private volatile boolean isStopping = false;

    private ParcelFileDescriptor vpnInterface;
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        String action = intent != null ? intent.getAction() : null;
        Logging.d(TAG, "onStartCommand, action = " + action);

        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }

        if (ACTION_STOP.equals(action)) {
            isStopping = true;
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        createNotificationChannelIfNeeded();

        if (ACTION_UPDATE_STATE.equals(action)) {
            currentStateStringRes = getStateTextRes(intent);
            if (!isStopping) {
                Notification notification = buildVpnNotification();
                if (notification != null) {
                    notificationManager.notify(VPN_NOTIFICATION_ID, notification);
                }
            }
            return Service.START_STICKY;
        }

        boolean fromDelete = intent != null && intent.getBooleanExtra(EXTRA_FROM_DELETE, false);
        if (ACTION_REPOST.equals(action) && fromDelete && !isStopping) {
            // The user cleared the ongoing notification on a ROM that allows it - put it
            // back so the foreground service stays visible (and stays alive).
            Logging.d(TAG, "Reposting VPN notification after user cleared it.");
            currentStateStringRes = getStateTextRes(intent);
            Notification notification = buildVpnNotification();
            if (notification == null) {
                return Service.START_NOT_STICKY;
            }
            startForeground0(notification);
            return Service.START_STICKY;
        }

        isStopping = false;

        Notification notification = buildVpnNotification();
        if (notification == null) {
            return Service.START_NOT_STICKY;
        }
        startForeground0(notification);

        Builder vpnBuilder = new Builder().setSession("Terracotta Connection");
        try {
            vpnBuilder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        try {
            TerracottaAndroidAPI.VpnServiceRequest request = TerracottaAndroidAPI.getPendingVpnServiceRequest();
            vpnInterface = request.startVpnService(vpnBuilder);
        } catch (Throwable t) {
            // TurtleLauncher: was catch (Exception) - same Error-vs-Exception gap as
            // Terracotta.java's poll daemon and TerracottaChat.kt's connection threads.
            // Zalith upstream lets this throw straight through and crash the process.
            Logging.e(TAG, "Failed to start VPN interface: " + t);
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        return Service.START_STICKY;
    }

    @Override
    public void onRevoke() {
        Logging.w(TAG, "onRevoke(): VPN preempted by another VPN or revoked by user; tearing down");
        isStopping = true;
        Terracotta.setWaiting(this, false);
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Logging.d(TAG, "onDestroy(): VPN service finished");
        isStopping = true;
        Terracotta.setWaiting(this, false);
        cleanup();
        super.onDestroy();
    }

    private int getStateTextRes(Intent intent) {
        int res = intent != null && intent.hasExtra(EXTRA_STATE_TEXT)
            ? intent.getIntExtra(EXTRA_STATE_TEXT, -1)
            : -1;
        // Normalize anything that isn't a plausible resource id (real ids are positive)
        // to -1, so buildVpnNotification() can never call getString(0).
        return res > 0 ? res : -1;
    }

    private void createNotificationChannelIfNeeded() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.terracotta_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.terracotta_notification_channel_desc));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildVpnNotification() {
        Terracotta.TerracottaMode mode = Terracotta.getMode();
        if (mode == null) {
            // No host/guest session exists - nothing worth foregrounding the service for.
            return null;
        }

        String modeText = getString(mode == Terracotta.TerracottaMode.HOST
            ? R.string.terracotta_player_kind_host
            : R.string.terracotta_player_kind_guest);

        if (currentStateStringRes == -1) {
            TerracottaState.Ready state = Terracotta.getState();
            if (state != null && !(state instanceof TerracottaState.Waiting)) {
                @StringRes int res = state.localStringRes();
                if (res != 0) {
                    currentStateStringRes = res;
                }
            }
        }
        String stateString = currentStateStringRes == -1
            ? getString(R.string.terracotta_status_default)
            : getString(currentStateStringRes);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        // Dedicated Friends/LAN icon. Solid-fill vector rather than a stroked one - see
        // ic_friends_network.xml for why a stroked icon like ic_globe doesn't survive
        // being a notification small icon.
        builder.setSmallIcon(R.drawable.ic_friends_network)
            .setContentTitle(getString(R.string.terracotta_notification_title))
            .setContentText(getString(R.string.terracotta_notification_desc, modeText, stateString))
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setDeleteIntent(buildDeleteIntent());

        return builder.build();
    }

    private PendingIntent buildDeleteIntent() {
        Intent deleteIntent = new Intent(this, TerracottaVpnService.class)
            .setAction(ACTION_REPOST)
            .putExtra(EXTRA_FROM_DELETE, true)
            .putExtra(EXTRA_STATE_TEXT, currentStateStringRes);
        return PendingIntent.getService(
            this,
            VPN_PERMISSION_REQUEST_CODE,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForeground0(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(VPN_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(VPN_NOTIFICATION_ID, notification);
        }
    }

    private void cleanup() {
        if (notificationManager != null) {
            notificationManager.cancel(VPN_NOTIFICATION_ID);
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }
        running = false;
    }
}
