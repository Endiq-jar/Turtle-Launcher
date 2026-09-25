/*
 * Terracotta VpnService — ported for FlameLauncher.
 *
 * Original: ZalithLauncher2 (GPL-3.0), modified from FoldCraftLauncher.
 *   https://github.com/FCL-Team/FoldCraftLauncher/blob/5926006/FCL/src/main/java/com/tungsten/fcl/terracotta/TerracottaVPNService.java
 *
 * 수정(FlameLauncher 이식):
 *  - 패키지: kr.co.donghyun.flamelauncher.presentation.util.terracota (실제 디렉터리 기준)
 *  - TerracottaAndroidAPI 는 kr.co.donghyun.terracota 에 있어 import
 *  - Terracotta / TerracottaState 는 같은 패키지라 import 불필요
 *  - Terracotta.setWaiting() 인자 제거 (이식본 시그니처)
 *  - NotificationChannelData 의존 제거 → 자체 CHANNEL_ID + ensureChannel()
 *  - NOTIFICATION_ID_VPN_REQUEST_CODE 제거 → 고정 request code(1001)
 *
 * 버그 픽스 1 (ForegroundServiceDidNotStartInTimeException):
 *  - startForegroundService() 로 진입 가능한 "모든" 경로에서 startForeground() 를
 *    반드시 1회 호출하도록 보장.
 *  - mode 미설정 등으로 정식 알림(buildVpnNotification)이 null 이면
 *    buildFallbackNotification() 으로라도 포그라운드 약속을 이행.
 *  - ACTION_STOP 도 cleanup/stopSelf 전에 먼저 startForeground() 로 약속 이행.
 *
 * 버그 픽스 2 (IllegalStateException: There's no pending VpnService request):
 *  - getPendingVpnServiceRequest() 는 보류 요청이 없으면 던지므로 반드시 가드한다.
 *    (게임 부하로 onStartCommand 가 지연돼 native 요청이 타임아웃됐거나 중복 START 인 경우.)
 *    요청이 없으면: tun 이 이미 있으면 유지, 없으면 정리 후 종료(대기 복귀).
 *  - VPN 설정 호출 전체를 try/catch 로 감싸 어떤 실패도 크래시로 번지지 않게 함.
 */

package kr.co.donghyun.flamelauncher.presentation.util.terracota;

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
import android.util.Log;

import java.io.IOException;

import kr.co.donghyun.flamelauncher.R;
import kr.co.donghyun.terracota.TerracottaAndroidAPI;

/**
 * [Modified from FCL](https://github.com/FCL-Team/FoldCraftLauncher/blob/5926006/FCL/src/main/java/com/tungsten/fcl/terracotta/TerracottaVPNService.java)
 */
@SuppressLint("VpnServicePolicy")
public class TerracottaVPNService extends VpnService {

    private static final String TAG = "TerracottaVPNService";

    private static final int VPN_NOTIFICATION_ID = 1;

    /** 포그라운드 알림 채널 id (FlameLauncher 자체) */
    public static final String CHANNEL_ID = "flame_terracotta_vpn";

    public static final String ACTION_START        = "kr.co.donghyun.flamelauncher.terracotta.action.START";
    public static final String ACTION_STOP         = "kr.co.donghyun.flamelauncher.terracotta.action.STOP";
    public static final String ACTION_REPOST       = "kr.co.donghyun.flamelauncher.terracotta.action.REPOST";
    public static final String ACTION_UPDATE_STATE = "kr.co.donghyun.flamelauncher.terracotta.action.UPDATE_STATE";

    private static final String EXTRA_FROM_DELETE = "from_delete";
    public static final String EXTRA_STATE_TEXT   = "terracotta_state_text";

    private NotificationManager notificationManager;
    private int currentStateStringRes = -1;
    private volatile boolean isStopping = false;

    private ParcelFileDescriptor vpnInterface;

    private static boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;

        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand, action = " + action);

        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            ensureChannel();
        }

        if (ACTION_STOP.equals(action)) {
            isStopping = true;
            // ★ startForegroundService() 로 들어왔을 수 있으니, 내리기 전에
            //   포그라운드 약속을 먼저 이행한다(아니면 DidNotStartInTime 크래시).
            startForeground0(currentNotificationOrFallback());
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (ACTION_UPDATE_STATE.equals(action)) {
            currentStateStringRes = getStateTextRes(intent);

            if (!isStopping) {
                // notify 대신 startForeground 로 갱신해 포그라운드 약속도 함께 이행.
                startForeground0(currentNotificationOrFallback());
            }
            return Service.START_STICKY;
        }

        boolean fromDelete = intent != null && intent.getBooleanExtra(EXTRA_FROM_DELETE, false);

        if (ACTION_REPOST.equals(action) && fromDelete && !isStopping) {
            Log.d(TAG, "Repost VPN notification after user cleared it.");
            currentStateStringRes = getStateTextRes(intent);
            startForeground0(currentNotificationOrFallback());
            return Service.START_STICKY;
        }

        isStopping = false;

        // ★ 정식 알림이 아직 없으면(mode 미설정 등) 폴백으로라도 반드시 포그라운드 진입.
        //   (이걸 먼저 해 둬야 아래에서 일찍 종료해도 타임아웃 크래시가 안 난다.)
        startForeground0(currentNotificationOrFallback());

        // ★ 보류 중인 VPN 요청 획득. 없으면 getPendingVpnServiceRequest() 가
        //   IllegalStateException 을 던지므로(요청 타임아웃/중복 START 등) 반드시 가드한다.
        TerracottaAndroidAPI.VpnServiceRequest request = null;
        try {
            request = TerracottaAndroidAPI.getPendingVpnServiceRequest();
        } catch (Throwable t) {
            Log.w(TAG, "no pending VpnService request: " + t.getMessage());
        }

        if (request == null) {
            if (vpnInterface != null) {
                // 이미 tun 이 떠 있는데 들어온 중복 START → 무시하고 유지.
                Log.d(TAG, "duplicate START with no pending request; keeping existing tun.");
                return Service.START_STICKY;
            }
            // 설정할 VPN 이 없다 → 깔끔히 종료. onDestroy 에서 Terracotta.setWaiting() 으로 대기 복귀.
            Log.w(TAG, "START with no pending request and no tun; stopping service.");
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        // VPN 설정 (어떤 실패도 크래시로 번지지 않도록 통째로 가드)
        try {
            // 재설정 시 이전 tun 누수 방지
            if (vpnInterface != null) {
                try {
                    vpnInterface.close();
                } catch (IOException ignored) {
                }
                vpnInterface = null;
            }

            Builder vpnBuilder = new Builder().setSession("Terracotta Connection");
            try {
                vpnBuilder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            vpnInterface = request.startVpnService(vpnBuilder);
        } catch (Throwable t) {
            Log.e(TAG, "startVpnService failed: " + t.getMessage(), t);
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        return Service.START_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "onRevoke(): preempted by another VPN or revoked by user; tearing down.");
        isStopping = true;
        Terracotta.INSTANCE.setWaiting();
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy(): vpn service finished");
        isStopping = true;
        Terracotta.INSTANCE.setWaiting();
        cleanup();
        super.onDestroy();
    }

    private int getStateTextRes(Intent intent) {
        int res = -1;
        if (intent != null && intent.hasExtra(EXTRA_STATE_TEXT)) {
            res = intent.getIntExtra(EXTRA_STATE_TEXT, -1);
        }
        return res;
    }

    private void ensureChannel() {
        NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.terracotta_notification_title),
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 항상 non-null 인 알림을 돌려준다.
     * 정식 상태 알림을 만들 수 없으면(예: Terracotta.mode 가 아직 null) 최소 폴백 알림 사용.
     * startForeground() 가 어떤 분기에서도 안전하게 호출되도록 하기 위한 헬퍼.
     */
    private Notification currentNotificationOrFallback() {
        try {
            Notification n = buildVpnNotification();
            if (n != null) return n;
        } catch (Throwable t) {
            // 알림 생성이 던져도 startForeground() 가 반드시 호출되도록 폴백으로 내려간다.
            Log.w(TAG, "buildVpnNotification failed: " + t.getMessage());
        }
        return buildFallbackNotification();
    }

    /** mode 미설정 등으로 정식 알림을 못 만들 때 쓰는 최소 포그라운드 알림(절대 null 아님). */
    private Notification buildFallbackNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.terracotta_notification_title))
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private Notification buildVpnNotification() {
        Terracotta.Mode mode = Terracotta.INSTANCE.getMode();
        if (mode == null) return null;

        String title = getString(R.string.terracotta_notification_title);
        String modeText = mode == Terracotta.Mode.Host
                ? getString(R.string.terracotta_player_kind_host)
                : getString(R.string.terracotta_player_kind_guest);

        if (currentStateStringRes == -1) {
            TerracottaState.Ready state = Terracotta.INSTANCE.getState().getValue();
            if (state != null && !(state instanceof TerracottaState.Waiting)) {
                currentStateStringRes = state.localStringRes();
            }
        }
        String stateString = (currentStateStringRes == -1) ? "Unknown" : getString(currentStateStringRes);
        String contentText = String.format(getString(R.string.terracotta_notification_desc), modeText, stateString);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        Intent deleteIntent = new Intent(this, TerracottaVPNService.class)
                .setAction(ACTION_REPOST)
                .putExtra(EXTRA_FROM_DELETE, true)
                .putExtra(EXTRA_STATE_TEXT, currentStateStringRes);
        PendingIntent deletePendingIntent = PendingIntent.getService(
                this,
                1001,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(contentText)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setDeleteIntent(deletePendingIntent);

        return builder.build();
    }

    private void startForeground0(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(VPN_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(VPN_NOTIFICATION_ID, notification);
        }
    }

    private void cleanup() {
        Log.d(TAG, "cleanup(): close tun & cancel notification");

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