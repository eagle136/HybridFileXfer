package hfx.droid;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import hfx.core.Discovery;

/**
 * 服务端 UDP 发现应答：等待连接期间收到 HFXDISC/1 Q 就单播回一次自己的名字和 TCP 端口。
 *
 * <p>除了被动应答，还会每 {@link Discovery#ANNOUNCE_INTERVAL_MS} 主动向外通告一次
 * （广播 + 组播，发往扫描端固定端口 {@link Discovery#CLIENT_PORT}）。这是“扫不到”的主因兜底：
 * 旧实现只有单向请求-应答，扫描方的 Q 一旦被 AP 丢弃或跨 /24 丢失，就永远发现不了对端；
 * 主动通告让扫描方即使一个 Q 都没送到也能收到 R 包。
 *
 * <p>同一 IP 2s 限频。TCP 服务握手成功后由 HFXServer 调用 {@link #stop()}，
 * 避免把“已占用的服务端”继续暴露给扫描方。
 *
 * <p>收到反向连接请求 HFXDISC/1 C 时，先校验端口与来源网段、3s/IP 限频，再交给
 * {@link Listener} 决定是否接受；接受与否都异步回 HFXDISC/1 A（0 接受 / 1 拒绝），
 * 以便由界面先弹确认框再作答。
 */
public final class DiscoveryResponder {

    private static final String TAG = "HFX-Discovery";
    private static final long REPLY_INTERVAL_MS = 2000L;
    private static final long REPLY_TTL_MS = 3_600_000L;
    private static final long CONNECT_INTERVAL_MS = 3000L;
    /** 通告线程的分片睡眠，便于 stop() 后快速退出 */
    private static final long ANNOUNCE_TICK_MS = 100L;

    private final int tcpPort;
    private final String deviceName;
    private final Listener listener;
    private final ConcurrentHashMap<String, Long> lastReplyAt =
            new ConcurrentHashMap<String, Long>();
    /** 上次接受反向连接的 IP 与时间，用于 3s/IP 限频（拒绝不记录，允许用户重试） */
    private final ConcurrentHashMap<String, Long> lastConnectAt =
            new ConcurrentHashMap<String, Long>();

    private volatile boolean running;
    private volatile MulticastSocket socket;
    private Thread receiveThread;
    private Thread announceThread;
    private Object multicastLock;
    /** 应答与主动通告分属两个线程，共用同一个 socket 发送，串行化避免包体交错 */
    private final Object sendLock = new Object();

    /** 反向连接的应答口；实现方可在任意线程、任意时刻调用，但只能调用一次。 */
    public interface Reply {
        void respond(boolean accepted);
    }

    /** 反向连接请求的裁决口；UI 侧用它先确认再接受。 */
    public interface Listener {
        /** 当前是否可接受反向连接（例如界面在前台且服务端空闲）。 */
        boolean isDiscoverable();

        /**
         * 处理一次反向连接请求。
         *
         * @return true 表示已接管，稍后必须调用 {@code reply.respond(...)}；
         *         false 表示立即拒绝，此时不会再调用 reply
         */
        boolean onReverseRequest(String ip, int port, String name, Reply reply);
    }

    public DiscoveryResponder(int tcpPort, String deviceName) {
        this(tcpPort, deviceName, null);
    }

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
        multicastLock = Discovery.acquireMulticastLock("hfx-responder");

        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "hfx-discovery-responder");
        receiveThread.setDaemon(true);
        receiveThread.start();

        announceThread = new Thread(new Runnable() {
            @Override
            public void run() {
                announceLoop();
            }
        }, "hfx-discovery-announcer");
        announceThread.setDaemon(true);
        announceThread.start();
    }

    public synchronized void stop() {
        running = false;
        MulticastSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        Discovery.releaseMulticastLock(multicastLock);
        multicastLock = null;
    }

    private void loop() {
        MulticastSocket s = null;
        try {
            s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Discovery.UDP_PORT));
            s.setBroadcast(true);
            Discovery.joinGroupQuietly(s);
            socket = s;
            byte[] buffer = new byte[Discovery.REVERSE_MAX_LENGTH];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                s.receive(packet);
                if (!running) {
                    break;
                }
                if (packet.getAddress() == null) {
                    continue;
                }
                String ip = packet.getAddress().getHostAddress();
                if (ip == null) {
                    continue;
                }
                if (Discovery.isQuery(packet.getData(), packet.getLength())) {
                    handleQuery(s, packet, ip);
                } else if (Discovery.isReverseConnect(packet.getData(), packet.getLength())) {
                    handleReverseConnect(s, packet, ip);
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.w(TAG, "发现应答退出: " + t);
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            // 只清自己这一轮的 socket：stop() 后立刻 start() 时，旧线程可能在新线程
            // 已经写进 socket 之后才跑到这里，无条件置 null 会把新 socket 弄丢，
            // 之后 stop() 关不掉它、主动通告也跟着失联。
            if (socket == s) {
                socket = null;
            }
        }
    }

    /**
     * 周期性主动通告自己的存在。目标端口是扫描端的固定端口 CLIENT_PORT；
     * 广播与组播各发一遍，任一通道可达即可被扫描方看到。
     */
    private void announceLoop() {
        byte[] packet = Discovery.responsePacket(tcpPort, deviceName);
        while (running) {
            sendAnnouncement(packet);
            long slept = 0L;
            while (running && slept < Discovery.ANNOUNCE_INTERVAL_MS) {
                try {
                    Thread.sleep(ANNOUNCE_TICK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                slept += ANNOUNCE_TICK_MS;
            }
        }
    }

    private void sendAnnouncement(byte[] packet) {
        MulticastSocket s = socket;
        if (s == null || s.isClosed()) {
            return;
        }
        synchronized (sendLock) {
            for (InetAddress address : Discovery.broadcastAddresses()) {
                try {
                    s.send(new DatagramPacket(packet, packet.length, address, Discovery.CLIENT_PORT));
                } catch (Throwable ignored) {
                }
            }
            try {
                s.send(new DatagramPacket(packet, packet.length,
                        InetAddress.getByName(Discovery.MULTICAST_ADDRESS), Discovery.CLIENT_PORT));
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleQuery(MulticastSocket s, DatagramPacket packet, String ip) {
        long now = System.currentTimeMillis();
        Long previous = lastReplyAt.get(ip);
        if (previous != null && now - previous < REPLY_INTERVAL_MS) {
            return;
        }
        lastReplyAt.put(ip, now);
        sweepStale(lastReplyAt, now);
        sendReply(s, Discovery.responsePacket(tcpPort, deviceName),
                packet.getAddress(), packet.getPort());
    }

    /**
     * 反向连接请求：校验顺序 isDiscoverable → 端口一致 → 私网 IPv4 → 3s/IP 限频，
     * 之后交给界面确认；A 0/A 1 由 {@link Reply} 异步发出。
     */
    private void handleReverseConnect(MulticastSocket s, DatagramPacket packet, String ip) {
        Log.i("HFXD", "C got from " + ip + ":" + packet.getPort()
                + " len=" + packet.getLength());
        Discovery.ReverseConnect connect = Discovery.parseReverseConnect(
                packet.getData(), packet.getLength());
        if (connect == null) {
            return;
        }
        long now = System.currentTimeMillis();
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
        final InetAddress address = packet.getAddress();
        final int replyPort = packet.getPort();
        if (!allowed) {
            sendReply(s, Discovery.reverseAckPacket(false), address, replyPort);
            return;
        }
        final AtomicBoolean answered = new AtomicBoolean(false);
        Reply reply = new Reply() {
            @Override
            public void respond(boolean accepted) {
                if (!answered.compareAndSet(false, true)) {
                    return;
                }
                sendReply(s, Discovery.reverseAckPacket(accepted), address, replyPort);
                if (accepted) {
                    // 只在接受时刷新限频表：拒绝（忙/取消）不占用窗口
                    long at = System.currentTimeMillis();
                    lastConnectAt.put(ip, at);
                    sweepStale(lastConnectAt, at);
                }
            }
        };
        if (!listener.onReverseRequest(ip, connect.port, connect.name, reply)) {
            reply.respond(false);
        }
    }

    private void sendReply(MulticastSocket s, byte[] payload, InetAddress address, int port) {
        if (address == null) {
            return;
        }
        synchronized (sendLock) {
            try {
                s.send(new DatagramPacket(payload, payload.length, address, port));
                Log.i("HFXD", "reply sent " + new String(payload) + " -> "
                        + address.getHostAddress() + ":" + port);
            } catch (Throwable t) {
                Log.i("HFXD", "reply FAIL " + new String(payload) + " -> "
                        + address.getHostAddress() + ":" + port + " " + t);
            }
        }
    }

    /** 表规模达到阈值时清理过期条目，避免长期运行无限增长。 */
    private static void sweepStale(Map<String, Long> table, long now) {
        if (table.size() < 32) {
            return;
        }
        long cutoff = now - REPLY_TTL_MS;
        for (Map.Entry<String, Long> entry : table.entrySet()) {
            if (entry.getValue() < cutoff) {
                table.remove(entry.getKey());
            }
        }
    }
}
