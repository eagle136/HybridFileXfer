package hfx;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import hfx.async.TaskManger;

import rikka.shizuku.Shizuku;

/**
 * 进程级 IO 服务绑定。
 *
 * 会话要能在 Activity 销毁后继续存活（按返回键、系统回收后台 Activity），
 * 就不能再用 Activity 作为 bindService 的 Context：Activity 销毁时系统会自动解绑，
 * 普通模式下的 IOService 随之下线，后续文件操作全部失效。
 * 这里统一用 applicationContext 绑定，由会话结束路径显式 release。
 *
 * 服务端和客户端可能同时在线（等待中的服务端 + 客户端会话），两者的 IO 模式也可能不同，
 * 所以按 owner 各持一份绑定，任何一侧结束都不影响另一侧。
 */
public final class IoBinding {
    public static final String OWNER_SERVER = "server";
    public static final String OWNER_CLIENT = "client";

    public interface Listener {
        void onServiceConnected(IIOService service);

        void onServiceDisconnected();
    }

    private static final class Slot {
        Context appContext;
        ServiceConnection connection;
        IIOService service;
        int mode = -1;
        boolean binding;
        Listener listener;
    }

    private static final Map<String, Slot> slots = new HashMap<>();

    private IoBinding() {
    }

    private static Slot slot(String owner) {
        return slots.computeIfAbsent(owner, key -> new Slot());
    }

    public static synchronized IIOService service(String owner) {
        return slot(owner).service;
    }

    public static synchronized int mode(String owner) {
        return slot(owner).mode;
    }

    /**
     * 确保以指定模式绑定 IO 服务。已绑定且模式一致时立即回调；
     * 绑定中的重复请求只替换监听者。模式变化会先解绑该 owner 的旧服务。
     */
    public static synchronized void acquire(String owner, Context context, int ioMode,
                                            Listener newListener) {
        Context app = context.getApplicationContext();
        Slot slot = slot(owner);
        slot.appContext = app;
        slot.listener = newListener;
        if (slot.service != null && slot.mode == ioMode) {
            postConnected(newListener, slot.service);
            return;
        }
        if (slot.binding && slot.mode == ioMode) {
            return;
        }
        if (slot.binding) {
            unbindLocked(slot);
        }
        slot.mode = ioMode;
        slot.binding = true;
        slot.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                IIOService ready = IIOService.Stub.asInterface(binder);
                Listener target;
                synchronized (IoBinding.class) {
                    slot.service = ready;
                    slot.binding = false;
                    target = slot.listener;
                }
                if (target != null) {
                    postConnected(target, ready);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Listener target;
                synchronized (IoBinding.class) {
                    slot.service = null;
                    slot.binding = false;
                    slot.mode = -1;
                    slot.connection = null;
                    target = slot.listener;
                }
                if (target != null) {
                    TaskManger.postOnUiThread(target::onServiceDisconnected);
                }
            }
        };
        if (ioMode == Config.MODE_NORMAL) {
            app.bindService(new Intent(app, IOService.class), slot.connection,
                    Context.BIND_AUTO_CREATE);
        } else {
            Shizuku.bindUserService(IOService.getUserServiceArgs(app), slot.connection);
        }
    }

    /** 只替换监听者（Activity 重建后接管已有绑定），不改变绑定本身。 */
    public static synchronized void setListener(String owner, Listener newListener) {
        slot(owner).listener = newListener;
    }

    /** 某侧会话结束、且没有新的等待服务端时释放，避免 IO 服务进程常驻。幂等。 */
    public static synchronized void release(String owner, Context context) {
        Slot slot = slots.get(owner);
        if (slot == null) {
            return;
        }
        slot.listener = null;
        if (context != null) {
            slot.appContext = context.getApplicationContext();
        }
        if (slot.binding || slot.service != null) {
            unbindLocked(slot);
        }
    }

    private static void unbindLocked(Slot slot) {
        ServiceConnection conn = slot.connection;
        Context app = slot.appContext;
        boolean shizuku = slot.mode != Config.MODE_NORMAL;
        try {
            if (conn != null && app != null) {
                if (shizuku) {
                    Shizuku.unbindUserService(IOService.getUserServiceArgs(app), conn, true);
                } else {
                    app.unbindService(conn);
                }
            }
        } catch (Throwable ignored) {
        }
        slot.connection = null;
        slot.service = null;
        slot.binding = false;
        slot.mode = -1;
    }

    private static void postConnected(Listener target, IIOService ready) {
        TaskManger.postOnUiThread(() -> target.onServiceConnected(ready));
    }
}
