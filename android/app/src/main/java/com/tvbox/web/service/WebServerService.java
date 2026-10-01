package com.tvbox.web.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tvbox.web.MainActivity;
import com.tvbox.web.R;
import com.tvbox.web.engine.SpiderManager;
import com.tvbox.web.server.WebServer;
import com.tvbox.web.utils.NetworkUtils;

public class WebServerService extends Service {

    private static final String TAG = "WebServerService";
    private static final String CHANNEL_ID = "tvbox_web_service_channel";
    private static final int NOTIFICATION_ID = 2026;
    public static final int SERVER_PORT = 8999;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    private WebServer mServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireLocks();
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    private void startServer() {
        try {
            // Server started cleanly without bundling any third-party source URLs
            // Sources are configured and loaded dynamically by the user via Web UI or Settings

            mServer = new WebServer(this, SERVER_PORT);
            mServer.start(5000);
            Log.i(TAG, "TVBox WebServer started successfully on port " + SERVER_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebServer: " + e.getMessage(), e);
        }
    }

    private void stopServer() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
            Log.i(TAG, "TVBox WebServer stopped.");
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && mWakeLock == null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBoxWeb:WakeLock");
                mWakeLock.acquire();
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && mWifiLock == null) {
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBoxWeb:WifiLock");
                mWifiLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring locks: " + e.getMessage(), e);
        }
    }

    private void releaseLocks() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
                mWakeLock = null;
            }
            if (mWifiLock != null && mWifiLock.isHeld()) {
                mWifiLock.release();
                mWifiLock = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TVBox Web 后台服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持 TVBox Web 局域网服务在后台常驻运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        String ip = NetworkUtils.getLocalIpAddress();
        String contentText = "局域网访问地址: http://" + ip + ":" + SERVER_PORT;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TVBox Web 服务正在运行")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServer();
        releaseLocks();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
