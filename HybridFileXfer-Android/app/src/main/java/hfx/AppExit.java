package hfx;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 空闲超时后的“软件自行退出”（用户口径：断开并结束整个进程）。
 *
 * <p>顺序：停空闲看门狗 → 放掉唤醒锁 → 停 IO 服务进程 → 关掉界面（finishAffinity）
 * → 延时结束主进程。延时是给上面的收尾（尤其是对端 SHUTDOWN / socket 关闭）
 * 留出落地时间，避免把“已断开”变成对端只看到半连接。
 */
public final class AppExit {
    private static final String TAG = "HFXD";
    /** 收尾到杀进程之间的缓冲时间；太短会让 SHUTDOWN/close 来不及落地。 */
    private static final long KILL_DELAY_MS = 800L;

    private static volatile boolean exiting;

    private AppExit() {
    }

    /** 幂等：重复调用只执行一次退出流程。 */
    public static void exit(Context context, String reason) {
        if (exiting) {
            return;
        }
        exiting = true;
        Log.i(TAG, "app exit reason=" + reason);

        Context app = context == null ? null : context.getApplicationContext();
        Runnable task = () -> {
            SessionIdleTimer.stop();
            ConnectionLocks.release();
            if (app != null) {
                try {
                    app.stopService(new Intent(app, IOService.class));
                } catch (Throwable t) {
                    Log.w(TAG, "stop io service failed", t);
                }
            }
            BaseActivity current = BaseActivity.current();
            if (current != null) {
                try {
                    current.finishAffinity();
                } catch (Throwable ignored) {
                }
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.i(TAG, "kill process");
                android.os.Process.killProcess(android.os.Process.myPid());
            }, KILL_DELAY_MS);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            new Handler(Looper.getMainLooper()).post(task);
        }
    }
}
