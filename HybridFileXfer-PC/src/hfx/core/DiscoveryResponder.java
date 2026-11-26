package hfx.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PC 端常驻 UDP 发现应答器（JDK 版，不依赖 Android）。
 *
 * <p>绑定 {@link Discovery#UDP_PORT}（5741），等待连接期间：
 * <ul>
 *   <li>收到手机扫描 Q：若 {@link Listener#isDiscoverable()} 且 2s/IP 限频内，
 *       单播回 P（名字 + tcpPort）；</li>
 *   <li>收到安卓反向连接请求 C：按序校验 isDiscoverable → 端口 5740 → 私网 IPv4 →
 *       3s/IP 限频 → {@link Listener#tryAcceptReverse}，接受回 A 0，拒绝回 A 1。</li>
 * </ul>
 *
 * <p>所有协议字符串都调用 {@link Discovery} 的编码方法，本类不手写协议文本。
 * 收包线程不做任何 Swing 操作；UI 状态切换由监听方（PcWindow）自己切到 EDT。
 */
public final class DiscoveryResponder {

    private static final long REPLY_INTERVAL_MS = 2000L;
    private static final long CONNECT_INTERVAL_MS = 3000L;
    private static final long TABLE_TTL_MS = 3_600_000L;
    private static final int TABLE_SWEEP_THRESHOLD = 32;

    /**
     * 由 PC 窗口实现；回调都在 UDP 收包线程上执行。
     */
    public interface Listener {
        /** 当前是否可被发现（前台、聚焦、非最小化、空闲）。 */
        boolean isDiscoverable();

        /**
         * 原子领取一次反向连接；返回 true 才允许回 A 0 并调用 onReverseAccepted。
         * 实现必须保证同一时刻只有一个领取成功。
         */
        boolean tryAcceptReverse(String ip, int port, String name);

        /** 已回 A 0 后的通知钩子；实际连接由实现方在 EDT 上发起。 */
        void onReverseAccepted(String ip, int port, String name);
    }

    private final int tcpPort;
    private final String deviceName;
    private final Listener listener;
    private final ConcurrentHashMap<String, Long> lastReplyAt =
            new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Long> lastConnectAt =
            new ConcurrentHashMap<String, Long>();

    private volatile boolean running;
    private volatile boolean errorLogged;
    private volatile DatagramSocket socket;

    public DiscoveryResponder(int tcpPort, String deviceName, Listener listener) {
        this.tcpPort = tcpPort;
        this.deviceName = deviceName;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        errorLogged = false;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "hfx-pc-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void loop() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Discovery.UDP_PORT));
            socket = s;
            byte[] buffer = new byte[Discovery.REVERSE_MAX_LENGTH];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                s.receive(packet);
                try {
                    handle(packet, s);
                } catch (Throwable t) {
                    logOnce(t);
                }
            }
        } catch (Throwable t) {
            // socket 绑定/接收失败只影响当前循环
            logOnce(t);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            // 只清自己：stop()→start() 快速连发时，旧线程不得清掉新线程刚写入的 socket
            if (socket == s) {
                socket = null;
            }
        }
    }

    private void handle(DatagramPacket packet, DatagramSocket s) {
        if (packet.getAddress() == null) {
            return;
        }
        String ip = packet.getAddress().getHostAddress();
        if (ip == null) {
            return;
        }
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (Discovery.isQuery(data, length)) {
            handleQuery(packet, s, ip);
            return;
        }
        if (Discovery.isReverseConnect(data, length)) {
            handleConnect(packet, s, ip, data, length);
            return;
        }
        // 其它包忽略
    }

    private void handleQuery(DatagramPacket packet, DatagramSocket s, String ip) {
        // 先查可发现：防 stop() 延迟窗口内仍应答
        if (listener == null || !listener.isDiscoverable()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastReplyAt.get(ip);
        if (previous != null && now - previous < REPLY_INTERVAL_MS) {
            return;
        }
        lastReplyAt.put(ip, now);
        sweepStale(lastReplyAt, now);
        sendQuietly(s, Discovery.reverseResponsePacket(tcpPort, deviceName), packet);
    }

    private void handleConnect(DatagramPacket packet, DatagramSocket s, String ip,
                              byte[] data, int length) {
        Discovery.ReverseConnect connect = Discovery.parseReverseConnect(data, length);
        if (connect == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 校验顺序：isDiscoverable -> 端口 5740 -> 私网 IPv4 -> 3s/IP 限频 -> tryAcceptReverse
        boolean allowed = listener != null
                && listener.isDiscoverable()
                && connect.port == tcpPort
                && Discovery.isSiteLocalIpv4(ip);
        if (allowed) {
            Long previous = lastConnectAt.get(ip);
            if (previous != null && now - previous < CONNECT_INTERVAL_MS) {
                allowed = false;
            }
        }
        if (!allowed) {
            sendQuietly(s, Discovery.reverseAckPacket(false), packet);
            return;
        }
        boolean accepted = listener.tryAcceptReverse(ip, connect.port, connect.name);
        if (accepted) {
            // 只在接受时刷新限频表：拒绝（忙/失焦）不占用窗口
            lastConnectAt.put(ip, now);
            sweepStale(lastConnectAt, now);
        }
        sendQuietly(s, Discovery.reverseAckPacket(accepted), packet);
        if (accepted) {
            listener.onReverseAccepted(ip, connect.port, connect.name);
        }
    }

    private static void sendQuietly(DatagramSocket s, byte[] payload, DatagramPacket from) {
        try {
            s.send(new DatagramPacket(payload, payload.length,
                    from.getAddress(), from.getPort()));
        } catch (Throwable ignored) {
        }
    }

    /** 表规模达到阈值时清理过期条目，避免长期运行无限增长。 */
    private static void sweepStale(Map<String, Long> table, long now) {
        if (table.size() < TABLE_SWEEP_THRESHOLD) {
            return;
        }
        long cutoff = now - TABLE_TTL_MS;
        for (Map.Entry<String, Long> entry : table.entrySet()) {
            if (entry.getValue() < cutoff) {
                table.remove(entry.getKey());
            }
        }
    }

    /** running 为 true 时最多 System.err 打一行，不逐包刷屏。 */
    private void logOnce(Throwable t) {
        if (running && !errorLogged) {
            errorLogged = true;
            System.err.println("PC 发现应答器异常: " + t);
        }
    }
}
