package hfx;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

import hfx.droid.DroidHFXClient;
import hfx.tasks.ClientTask;

/**
 * 客户端会话的进程级持有者。
 *
 * 会话（控制通道 + 传输线程）在 Activity 销毁后继续运行，3 分钟后台空闲由
 * {@link SessionIdleTimer} 收尾；Activity 重建时通过 {@link #setUi} 重新挂上界面。
 */
public final class ClientSession {
    private static final Object LOCK = new Object();

    private static DroidHFXClient client;
    private static volatile ClientTask.Callback ui;
    private static Context appContext;
    private static String controllerIp;
    private static int ioMode;
    private static List<String> channelNames;
    private static volatile boolean active;

    /**
     * ClientTask 只持有这个转发器；UI 目标可随 Activity 重建替换。
     * 结束类事件先收尾会话，再把事件转给（收尾前的）当前界面。
     */
    private static final ClientTask.Callback FORWARDER = (ClientTask.Callback) Proxy.newProxyInstance(
            ClientTask.Callback.class.getClassLoader(),
            new Class<?>[]{ClientTask.Callback.class},
            (proxy, method, args) -> {
                String name = method.getName();
                boolean terminal = name.equals("onExit")
                        || name.equals("onIncomplete")
                        || (name.equals("onError") && args != null && args.length == 1)
                        || (name.equals("onComplete") && (args == null || args.length == 0));
                ClientTask.Callback target = ui;
                if (terminal) {
                    end();
                }
                if (target == null) {
                    return null;
                }
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });

    private ClientSession() {
    }

    static boolean isActive() {
        return active;
    }

    static DroidHFXClient client() {
        synchronized (LOCK) {
            return client;
        }
    }

    static String controllerIp() {
        synchronized (LOCK) {
            return controllerIp;
        }
    }

    static int ioMode() {
        synchronized (LOCK) {
            return ioMode;
        }
    }

    static List<String> channelNames() {
        synchronized (LOCK) {
            return channelNames;
        }
    }

    /** 连接成功后登记会话并启动传输循环；Activity 只是它的 UI 目标。 */
    static void begin(DroidHFXClient c, ClientTask.Callback target, Context context,
                      String ip, int mode, List<String> names) {
        Log.i("HFXD", "session begin ip=" + ip + " channels=" + (names == null ? 0 : names.size()));
        synchronized (LOCK) {
            client = c;
            ui = target;
            appContext = context.getApplicationContext();
            controllerIp = ip;
            ioMode = mode;
            channelNames = names;
            active = true;
        }
        new ClientTask(FORWARDER, c).execute();
        ConnectionLocks.acquire(context);
        SessionIdleTimer.start(SessionIdleTimer.KEY_CLIENT, ClientSession::onIdleTimeout);
    }

    /** Activity 重建/销毁时替换或清空 UI 目标，会话本身不动。 */
    static void setUi(ClientTask.Callback target) {
        ui = target;
    }

    /** 会话收尾：停计时、关连接、释放前台保活与 IO 绑定。幂等。 */
    static void end() {
        Log.i("HFXD", "session end");
        DroidHFXClient c;
        Context ctx;
        synchronized (LOCK) {
            if (!active && client == null) {
                return;
            }
            active = false;
            c = client;
            client = null;
            ui = null;
            channelNames = null;
            ctx = appContext;
        }
        SessionIdleTimer.stop(SessionIdleTimer.KEY_CLIENT);
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
            // 客户端缓冲是 NativeMemory 直接分配的（默认 20×4MB），GC 管不到：
            // 不在这里释放，每连一次漏约 80MB，反复进出必 OOM。
            try {
                c.freeBuffers();
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
            ConnectionService.removeOwner(ctx, ConnectionService.OWNER_CLIENT);
            IoBinding.release(IoBinding.OWNER_CLIENT, ctx);
        }
        ConnectionLocks.release();
    }

    /**
     * 前台/后台空闲满 3 分钟：断开连接后按用户口径“软件自行退出”（结束整个进程）。
     * 手动返回键断开走 {@link #end()}，不会退出进程。
     */
    static void onIdleTimeout() {
        Log.i("HFXD", "client idle timeout -> exit");
        Context ctx = appContext;
        end();
        AppExit.exit(ctx, "client idle");
    }
}
