package hfx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接保活前台服务（主进程）。
 *
 * 本机 TCP 连接（服务端监听 / 客户端会话）全部跑在主进程：应用切后台后
 * 主进程随时可能被 ROM 杀掉，socket 随之断开。把主进程提为前台服务可
 * 显著提高存活概率。PC 端没有此问题，无需对应改动。
 *
 * MainActivity（服务端）与 ClientActivity（客户端）各持有一个引用：
 * 谁最后释放，谁停掉服务；通知文案按当前引用拼接显示。
 */
public class ConnectionService extends Service {
    private static final String TAG = ConnectionService.class.getSimpleName();

    /** 保活引用 key：MainActivity / ClientActivity 各占一个 */
    public static final String OWNER_MAIN = "main";
    public static final String OWNER_CLIENT = "client";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "hfx_connection";

    /** owner -> 状态文案；static 以便每次 onStartCommand 都能重建通知 */
    private static final Map<String, String> owners = new ConcurrentHashMap<>();

    public static void setOwner(Context context, String owner, String status) {
        owners.put(owner, status);
        startServiceIfNeed(context);
    }

    public static void removeOwner(Context context, String owner) {
        owners.remove(owner);
        if (owners.isEmpty()) {
            context.stopService(new Intent(context, ConnectionService.class));
        } else {
            // 还有其他引用：再拉一次前台刷新通知文案
            startServiceIfNeed(context);
        }
    }

    private static void startServiceIfNeed(Context context) {
        try {
            context.startForegroundService(new Intent(context, ConnectionService.class));
        } catch (Throwable t) {
            // 后台启动限制等异常：保活失败只降级不断连，记录即可
            Log.w(TAG, "startForegroundService failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.conn_service_channel),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        // 点击通知回主界面
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(joinStatuses())
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        owners.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String joinStatuses() {
        StringBuilder sb = new StringBuilder();
        for (String status : owners.values()) {
            if (status == null || status.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(status);
        }
        if (sb.length() == 0) {
            sb.append(getString(R.string.conn_service_default_status));
        }
        return sb.toString();
    }
}
