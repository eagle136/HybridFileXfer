package hfx;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * 已连接状态的唤醒锁：CPU 部分唤醒锁 + WiFi 高性能锁。
 *
 * <p>口径（用户确认）：
 * <ul>
 *   <li>只在“连接已建立”期间持有：服务端握手成功 / 客户端会话 begin 时 acquire；</li>
 *   <li>会话收尾（手动断开、对端断开、空闲超时、进程退出）时 release，等待连接不算；</li>
 *   <li>整个连接期间一直持有，前台后台都一样，保证锁屏后 CPU 与无线链路不休眠。</li>
 * </ul>
 *
 * <p>幂等设计：重复 acquire 不叠加、release 一次即全部释放。服务端与客户端会话
 * 在本应用里互斥（见 MainActivity.stopServerForClient / maybeAutoStartServer），
 * 用布尔而不是计数可以避免“多 acquire 少 release”导致锁泄漏。
 */
public final class ConnectionLocks {
    private static final String TAG = "HFXD";
    private static final String CPU_TAG = "hfx:connection-cpu";
    private static final String WIFI_TAG = "hfx:connection-wifi";

    private static PowerManager.WakeLock cpuLock;
    private static WifiManager.WifiLock wifiLock;
    private static boolean held;

    private ConnectionLocks() {
    }

    /** 连接建立后调用；重复调用无副作用。 */
    public static synchronized void acquire(Context context) {
        if (held || context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        held = true;

        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock =
                        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG);
                lock.setReferenceCounted(false);
                lock.acquire();
                cpuLock = lock;
            }
        } catch (Throwable t) {
            // 权限被拒/系统限制：只降级不断连
            Log.w(TAG, "acquire cpu wake lock failed", t);
        }

        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                // HIGH_PERF 在 API 29 起废弃，10+ 用 LOW_LATENCY（语义仍是“低延迟保活”）
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                WifiManager.WifiLock lock = wm.createWifiLock(mode, WIFI_TAG);
                lock.setReferenceCounted(false);
                lock.acquire();
                wifiLock = lock;
            }
        } catch (Throwable t) {
            Log.w(TAG, "acquire wifi lock failed", t);
        }
        Log.i(TAG, "connection locks acquired");
    }

    /** 会话结束/空闲超时/进程退出时调用；重复调用无副作用。 */
    public static synchronized void release() {
        if (!held) {
            return;
        }
        held = false;
        PowerManager.WakeLock cpu = cpuLock;
        cpuLock = null;
        releaseQuietly(cpu, CPU_TAG);
        WifiManager.WifiLock wifi = wifiLock;
        wifiLock = null;
        releaseQuietly(wifi, WIFI_TAG);
        Log.i(TAG, "connection locks released");
    }

    private static void releaseQuietly(PowerManager.WakeLock lock, String tag) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeld()) {
                lock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "release wake lock failed tag=" + tag, t);
        }
    }

    private static void releaseQuietly(WifiManager.WifiLock lock, String tag) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeld()) {
                lock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "release wifi lock failed tag=" + tag, t);
        }
    }

    public static synchronized boolean isHeld() {
        return held;
    }
}
