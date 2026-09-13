package com.endiq.turtlelauncher.feature.terracotta;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.log.Logging;

import net.burningtnt.terracotta.TerracottaAndroidAPI;

import java.io.IOException;

/**
 * Terracotta's VPN foreground service - adapted from FCL's TerracottaVPNService.java
 * (FCL-Team/FoldCraftLauncher, GPLv3). Notification/state-text handling simplified
 * (no delete-intent repost flow) since that's a polish detail, not core to the
 * connection working; everything the actual P2P connection depends on is unchanged.
 */
@SuppressLint("VpnServicePolicy")
public class TerracottaVpnService extends VpnService {
    private static final String TAG = "TerracottaVpnService";
    private static final String CHANNEL_ID = "terracotta_vpn_channel";
    private static final int VPN_NOTIFICATION_ID = 1;

    public static final int VPN_PERMISSION_REQUEST_CODE = 0x7E44; // arbitrary, just needs to be a stable app-unique request code

    public static final String ACTION_START = "com.endiq.turtlelauncher.terracotta.action.START";
    public static final String ACTION_STOP = "com.endiq.turtlelauncher.terracotta.action.STOP";

    private NotificationManager notificationManager;
    private ParcelFileDescriptor vpnInterface;
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        String action = intent != null ? intent.getAction() : null;

        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }

        if (ACTION_STOP.equals(action)) {
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        createNotificationChannelIfNeeded();
        startForeground(VPN_NOTIFICATION_ID, buildVpnNotification());

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
        Logging.w(TAG, "onRevoke(): VPN preempted or revoked by user");
        Terracotta.setWaiting(this, false);
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Terracotta.setWaiting(this, false);
        cleanup();
        super.onDestroy();
    }

    private void createNotificationChannelIfNeeded() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Terracotta VPN", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Friends/LAN connection status");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildVpnNotification() {
        Terracotta.TerracottaMode mode = Terracotta.getMode();
        String modeText = mode == Terracotta.TerracottaMode.HOST ? "Hosting" : "Connected to friend";

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        // Dedicated Friends/LAN icon (was ic_chat, the in-game chat glyph, with a TODO to
        // replace it). Solid-fill vector rather than a stroked one - see ic_friends_network.xml
        // for why a stroked icon like ic_globe doesn't survive being a notification small icon.
        builder.setSmallIcon(R.drawable.ic_friends_network)
            .setContentTitle("TurtleLauncher Friends")
            .setContentText(modeText)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE);
        return builder.build();
    }

    private void cleanup() {
        if (notificationManager != null) notificationManager.cancel(VPN_NOTIFICATION_ID);
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
