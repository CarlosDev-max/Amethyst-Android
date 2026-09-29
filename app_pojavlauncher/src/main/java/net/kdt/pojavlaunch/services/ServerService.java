package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.servers.ServerActivity;
import net.kdt.pojavlaunch.servers.ServerRunner;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import java.io.File;

/**
 * Keeps the :server process (and with it the in-process server JVM) alive while the
 * launcher UI is in the background. Runs in the same process as {@link ServerActivity}.
 */
public class ServerService extends Service {
    public static final String EXTRA_SERVER_NAME = "server_name";
    public static final String EXTRA_BUNDLE_DIR = "bundle_dir";
    public static final String EXTRA_KILL = "kill";

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_KILL, false)) {
            shutdownServer(intent);
            return START_NOT_STICKY;
        }
        String serverName = intent != null ? intent.getStringExtra(EXTRA_SERVER_NAME) : null;

        Intent consoleIntent = new Intent(this, ServerActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(this,
                NotificationUtils.PENDINGINTENT_CODE_SERVER_CONSOLE, consoleIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent killIntent = new Intent(this, ServerService.class).putExtra(EXTRA_KILL, true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this,
                NotificationUtils.PENDINGINTENT_CODE_KILL_SERVER_SERVICE, killIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setContentTitle(getString(R.string.local_server_notification_title,
                        serverName == null ? "" : serverName))
                .setContentText(getString(R.string.local_server_notification_body))
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.local_server_stop), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setNotificationSilent()
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_SERVER_SERVICE, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_SERVER_SERVICE, notification);
        }
        return START_NOT_STICKY;
    }

    /** Graceful stop first; the process is only killed if the JVM ignores the command. */
    private void shutdownServer(Intent intent) {
        if (ServerRunner.getState() == ServerRunner.STATE_RUNNING) {
            String bundlePath = intent != null ? intent.getStringExtra(EXTRA_BUNDLE_DIR) : null;
            if (bundlePath != null) ServerRunner.requestStop(new File(bundlePath));
            new Thread(() -> {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ignored) {}
                if (ServerRunner.getState() == ServerRunner.STATE_RUNNING) {
                    Process.killProcess(Process.myPid());
                }
            }, "ServerStopWatchdog").start();
        } else {
            Process.killProcess(Process.myPid());
        }
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
