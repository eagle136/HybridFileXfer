package hfx;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话空闲看门狗（进程级）。
 *
 * 规则（用户口径，2026-11-17 调整）：
 * - 前台、后台都计时：前台只要有人在操作（点按/按键）就刷新，不再“前台永不断开”。
 * - 有传输任务时不计时；传输结束后重新起算。
 * - 空闲满 {@link #IDLE_TIMEOUT_MS} 后执行各会话注册的超时动作（断开会话 + 释放保活 +
 *   结束进程，见 {@link AppExit}）。
 *
 * 计时器故意放在进程级静态对象里：Activity 被销毁后仍要能把会话收尾。
 * 服务端与客户端可能同时在线，所以动作按 key 分开注册，任一结束不影响另一个。
 */
public final class SessionIdleTimer {
    public static final long IDLE_TIMEOUT_MS = 180_000L;

    private static final String TAG = "HFXD";

    public static final String KEY_SERVER = "server";
    public static final String KEY_CLIENT = "client";

    private static final long CHECK_INTERVAL_MS = 5_000L;

    private static HandlerThread thread;
    private static Handler handler;

    private static volatile boolean running;
    private static volatile boolean transferring;
    private static volatile long lastActivityAt;
    private static final Map<String, Runnable> timeoutActions = new ConcurrentHashMap<>();

    private static int resumedActivities;

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!transferring
                    && SystemClock.elapsedRealtime() - lastActivityAt >= IDLE_TIMEOUT_MS) {
                Log.i(TAG, "idle timeout fire keys=" + timeoutActions.keySet()
                        + " resumed=" + resumedActivities);
                List<Runnable> actions = new ArrayList<>(timeoutActions.values());
                timeoutActions.clear();
                stopTicking();
                for (Runnable action : actions) {
                    action.run();
                }
                return;
            }
            Handler h = handler;
            if (h != null) {
                h.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    };

    private SessionIdleTimer() {
    }

    /** 某类会话建立后注册超时动作；重复调用只替换同 key 的动作。 */
    public static void start(String key, Runnable action) {
        Log.i(TAG, "idle start key=" + key);
        timeoutActions.put(key, action);
        lastActivityAt = SystemClock.elapsedRealtime();
        if (running) {
            return;
        }
        running = true;
        handler().removeCallbacks(tick);
        handler().postDelayed(tick, CHECK_INTERVAL_MS);
    }

    /** 某类会话结束（手动断开/对端断开/超时）时注销；全部注销后停止计时。 */
    public static void stop(String key) {
        Log.i(TAG, "idle stop key=" + key);
        timeoutActions.remove(key);
        if (timeoutActions.isEmpty()) {
            stopTicking();
        }
    }

    /** 无条件停止全部计时（进程内所有会话均已收尾）。 */
    public static void stop() {
        timeoutActions.clear();
        stopTicking();
    }

    private static void stopTicking() {
        running = false;
        Handler h = handler;
        if (h != null) {
            h.removeCallbacks(tick);
        }
    }

    /** 任意控制请求或传输活动：刷新最后活动时间。 */
    public static void markActivity() {
        lastActivityAt = SystemClock.elapsedRealtime();
    }

    /** 传输任务开始/结束。结束时重新起算空闲。 */
    public static void setTransferring(boolean value) {
        transferring = value;
        if (!value) {
            markActivity();
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static synchronized void onAppResumed() {
        resumedActivities++;
        // 前后台都计时后，回前台这个动作本身要重置 3 分钟，
        // 否则后台已空转 2:59 时切回来会立刻被判超时。
        markActivity();
        Log.i(TAG, "app resumed count=" + resumedActivities);
    }

    public static synchronized void onAppPaused() {
        if (resumedActivities > 0) {
            resumedActivities--;
        }
        Log.i(TAG, "app paused count=" + resumedActivities);
        if (resumedActivities == 0) {
            // 进入后台时重新起算 3 分钟，而不是沿用前台最后一次操作时间
            markActivity();
        }
    }

    public static synchronized boolean isAppForeground() {
        return resumedActivities > 0;
    }

    private static synchronized Handler handler() {
        if (handler == null) {
            thread = new HandlerThread("hfx-idle-timer");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }
}
