package com.aigetway.tattoo7.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.aigetway.tattoo7.R;
import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.gateway.GatewayServer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.aigetway.tattoo7.ui.MainActivity;

/**
 * 前台网关服务：WakeLock + START_STICKY 保活。
 * 注意：Service 是先实例化再 attach（attachBaseContext），
 * 所以 onCreate 里 getApplication() 会返回 null，禁止在这里取 context。
 */
public class GatewayService extends Service {

    public static final String ACTION_START = "com.aigetway.tattoo7.START";
    public static final String ACTION_STOP = "com.aigetway.tattoo7.STOP";
    public static final String EXTRA_PORT = "port";

    private static final String CHANNEL_ID = "gateway";
    private static volatile boolean sRunning = false;

    /** 供 UI 主动查询当前网关状态，不依赖广播。 */
    public static boolean isServerRunning() {
        return sRunning;
    }

    private GatewayServer server;
    private PowerManager.WakeLock wakeLock;
    private App app; // 在 onStartCommand 里才赋值，此时已 attach

    @Override
    public void onCreate() {
        super.onCreate();
        app = (App) getApplication();
        sInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        app = (App) getApplication(); // attach 之后才可用
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        startForegroundInternal();
        startGateway();
        return START_STICKY;
    }

    private void startGateway() {
        try {
            server = new GatewayServer();
            server.start(app.settings().port());
            sRunning = true;
            app.logSink().log("SERVICE", "网关已启动 port=" + app.settings().port());
            broadcastStatus(true, app.settings().port());
        } catch (Exception e) {
            sRunning = false;
            app.logSink().log("SERVICE", "网关启动失败: " + e.getMessage());
            broadcastStatus(false, app.settings().port());
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (server != null) server.stop();
        inflight.set(0);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        sInstance = null;
        if (app != null) {
            app.logSink().log("SERVICE", "网关已停止");
            broadcastStatus(false, app.settings().port());
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tattoo7::gateway");
            wakeLock.setReferenceCounted(false);
            // 发热优化：不再无限期持有 CPU。改为「有请求在处理时才亮锁」，
            // 空闲时允许 SoC 休眠（见 inflight 计数 + wake()/doneWake()）。
            // 这里只申请一个供请求期复用的锁实例，不立即 acquire。
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger inflight =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 请求开始：引用计数 >0 时短暂持有 CPU（10 分钟上限兜底）。 */
    public static void wake() {
        GatewayService s = sInstance;
        if (s == null || s.wakeLock == null) return;
        if (s.inflight.getAndIncrement() == 0 && !s.wakeLock.isHeld()) {
            try {
                s.wakeLock.acquire(10 * 60 * 1000L);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 请求结束：引用计数归零立即放锁，让 CPU 休眠。 */
    public static void doneWake() {
        GatewayService s = sInstance;
        if (s == null) return;
        if (s.inflight.decrementAndGet() <= 0) {
            s.inflight.set(0);
            try {
                if (s.wakeLock != null && s.wakeLock.isHeld()) s.wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static GatewayService sInstance;

    private void startForegroundInternal() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "AI 网关", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            Intent openIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                    PendingIntent.FLAG_IMMUTABLE);
            Notification notif = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Tattoo AI 网关")
                    .setContentText("运行中 · 端口 " + app.settings().port())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notif);
            }
        } catch (Exception e) {
            if (app != null) app.logSink().log("SERVICE", "前台通知失败: " + e.getMessage());
        }
    }

    private void broadcastStatus(boolean running, int port) {
        try {
            Intent svcIntent = new Intent("com.aigetway.tattoo7.STATUS");
            svcIntent.putExtra("running", running);
            svcIntent.putExtra("port", port);
            LocalBroadcastManager.getInstance(this).sendBroadcast(svcIntent);
        } catch (Exception ignored) {
        }
    }
}
