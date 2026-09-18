package net.endiq.launcher.services;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.endiq.turtlelauncher.R;

import net.endiq.launcher.Tools;
import net.endiq.launcher.utils.NotificationUtils;

public class ScreenRecorderAudioService extends Service {

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, ScreenRecorderAudioService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ScreenRecorderAudioService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, Tools.NOTIFICATION_CHANNEL_DEFAULT)
                .setContentTitle("Recording audio")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setSilent(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_RECORDING_AUDIO, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_RECORDING_AUDIO, notification);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
