package hfx.core;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网发现（客户端主动扫描）。
 *
 * <p>扫描只在用户点击“扫描”或主页自动轮询时触发：广播 + 组播询问 + 本网段单播询问，
 * 总窗口默认 4.5s，每 800ms 补发一次。客户端使用固定端口 {@link #CLIENT_PORT} 收应答，
 * 这样服务端的主动通告（见 DiscoveryResponder）也能被命中；端口被占时退化为临时端口，
 * 只保留请求-应答通道。
 *
 * <p>协议（UTF-8 文本）：
 * <pre>
 *   请求：HFXDISC/1 Q
 *   应答：HFXDISC/1 R &lt;tcpPort&gt; &lt;urlencoded name&gt;
 *   PC 反向应答（可发现的 PC）：HFXDISC/1 P &lt;tcpPort&gt; &lt;urlencoded name&gt;
 *   反向连接请求（安卓 → PC）：HFXDISC/1 C &lt;tcpPort&gt; &lt;urlencoded name&gt;
 *   反向连接应答（PC → 安卓）：HFXDISC/1 A 0（接受）/ HFXDISC/1 A 1（拒绝/忙）
 * </pre>
 *
 * <p>三个通道互相独立，任一可达即可发现对端，解决部分 AP 过滤广播、
 * 跨 /24 网段、以及 Android 17+ 本地网络权限下的漏发现：
 * <ol>
 *   <li>受限广播 255.255.255.255:5741</li>
 *   <li>各网卡广播地址:5741</li>
 *   <li>组播 {@link #MULTICAST_ADDRESS}:5741（AP 普遍转发组播但不一定转发广播）</li>
 * </ol>
 */
public final class Discovery {

    /** 服务端接收询问、应答时使用的端口 */
    public static final int UDP_PORT = 5741;
    /** 扫描端固定接收端口：服务端主动通告发往此端口，端口被占时退化为临时端口 */
    public static final int CLIENT_PORT = 5742;
    /** 局域网组播组，需与 PC 端保持一致 */
    public static final String MULTICAST_ADDRESS = "239.255.74.41";
    public static final long DEFAULT_TIMEOUT_MS = 4500L;
    /** 服务端主动通告周期 */
    public static final long ANNOUNCE_INTERVAL_MS = 2500L;

    private static final String MAGIC = "HFXDISC/1";
    private static final String QUERY = MAGIC + " Q";
    private static final String RESPONSE_PREFIX = MAGIC + " R ";
    public static final String REVERSE_RESPONSE_PREFIX = MAGIC + " P ";
    public static final String REVERSE_CONNECT_PREFIX = MAGIC + " C ";
    public static final String ACK_PREFIX = MAGIC + " A ";
    public static final int REVERSE_MAX_LENGTH = 128;
    private static final int SWEEP_BURST = 16;
    private static final long SWEEP_PACE_MS = 8L;
    private static final long RESEND_TICK_MS = 800L;
    private static final int SOCKET_TIMEOUT_MS = 200;
    /** 一次扫描最多铺几个 /24 段，避免多网卡场景把包量放大到拖慢窗口 */
    private static final int MAX_SWEEP_SUBNETS = 3;

    /** 应用上下文，用于取 WifiManager 申请组播锁（组播锁必须在应用上下文上持有） */
    private static volatile Context appContext;

    private Discovery() {
    }

    /** 由 BaseActivity 在启动时调用；重复调用覆盖为最新的 applicationContext，不会泄漏 Activity。 */
    public static void init(Context context) {
        if (context != null) {
            try {
                appContext = context.getApplicationContext();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 申请 WifiManager.MulticastLock：没有它时多数 ROM 会直接丢弃收到的组播包，
     * 组播通道形同虚设。返回的对象只用于 {@link #releaseMulticastLock(Object)}。
     */
    public static Object acquireMulticastLock(String tag) {
        Context ctx = appContext;
        if (ctx == null) {
            return null;
        }
        try {
            WifiManager wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            WifiManager.MulticastLock lock = wifiManager.createMulticastLock(tag);
            lock.setReferenceCounted(true);
            lock.acquire();
            return lock;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void releaseMulticastLock(Object lock) {
        if (lock instanceof WifiManager.MulticastLock) {
            try {
                ((WifiManager.MulticastLock) lock).release();
            } catch (Throwable ignored) {
            }
        }
    }

    public static final class Device {
        public final String ip;
        public final String name;
        public final int port;
        /** true：P 包发现的 PC（反向连接入口）；false：R 包发现的安卓服务端 */
        public final boolean reverse;

        public Device(String ip, String name, int port, boolean reverse) {
            this.ip = ip;
            this.name = name;
            this.port = port;
            this.reverse = reverse;
        }

        @Override
        public String toString() {
            return name + " (" + ip + ")";
        }
    }

    public interface Listener {
        void onFinished(List<Device> devices);
    }

    /** 一次扫描的句柄；取消时关闭 socket，扫描线程尽快退出。 */
    public static final class Scan {
        private volatile boolean canceled;
        private volatile MulticastSocket socket;

        public void cancel() {
            canceled = true;
            MulticastSocket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }

        public boolean isCanceled() {
            return canceled;
        }
    }

    /** 后台扫描一次；回调在扫描线程上执行，UI 层需自行切回主线程。 */
    public static Scan startScan(long timeoutMs, final Listener listener) {
        final Scan scan = new Scan();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                List<Device> devices = scan(scan, timeoutMs);
                if (!scan.isCanceled() && listener != null) {
                    listener.onFinished(devices);
                }
            }
        }, "hfx-discovery");
        thread.setDaemon(true);
        thread.start();
        return scan;
    }

    public static List<Device> scanOnce(long timeoutMs) {
        return scan(new Scan(), timeoutMs);
    }

    private static List<Device> scan(Scan scan, long timeoutMs) {
        Map<String, Device> found = new LinkedHashMap<String, Device>();
        MulticastSocket socket = null;
        Object multicastLock = acquireMulticastLock("hfx-scan");
        try {
            socket = openClientSocket();
            if (socket == null) {
                return new ArrayList<Device>();
            }
            scan.socket = socket;
            socket.setBroadcast(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            final byte[] query = queryPacket();
            List<String> ownIps = localIpv4All();
            final List<String> targets = subnetTargetsAll(ownIps);

            long startedAt = System.currentTimeMillis();
            long lastSendAt = 0L;
            boolean swept = false;
            while (!scan.isCanceled() && System.currentTimeMillis() - startedAt < timeoutMs) {
                long now = System.currentTimeMillis();
                if (now - lastSendAt >= RESEND_TICK_MS) {
                    lastSendAt = now;
                    sendQuery(socket, query);
                    if (!swept && !targets.isEmpty()) {
                        swept = true;
                        // 254 个包同步发会阻塞收包循环，放独立线程
                        startSweepThread(socket, query, targets, scan);
                    }
                }
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
                    socket.receive(packet);
                    String ip = packet.getAddress() == null
                            ? null : packet.getAddress().getHostAddress();
                    if (ip == null || ownIps.contains(ip)) {
                        continue;
                    }
                    String text = new String(packet.getData(), 0, packet.getLength(),
                            StandardCharsets.UTF_8);
                    Device device = parseResponse(text, ip, RESPONSE_PREFIX, false);
                    if (device == null) {
                        device = parseResponse(text, ip, REVERSE_RESPONSE_PREFIX, true);
                    }
                    if (device != null) {
                        found.put(ip, device);
                    }
                } catch (SocketTimeoutException ignored) {
                    // 继续检查窗口/取消
                } catch (IOException e) {
                    if (!scan.isCanceled()) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
            scan.socket = null;
            releaseMulticastLock(multicastLock);
        }
        return new ArrayList<Device>(found.values());
    }

    /**
     * 扫描端 socket：优先绑定固定 CLIENT_PORT，这样服务端的主动通告（发往该端口）能收到。
     * 绑定失败（上一个扫描实例未释放）时回退临时端口，仅保留请求-应答通道。
     */
    private static MulticastSocket openClientSocket() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(CLIENT_PORT));
        } catch (Throwable e) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
            socket = null;
        }
        if (socket == null) {
            try {
                socket = new MulticastSocket();
            } catch (Throwable e) {
                return null;
            }
        }
        try {
            socket.setTimeToLive(1);
        } catch (Throwable ignored) {
        }
        joinGroupQuietly(socket);
        return socket;
    }

    /** 加入组播组；失败不影响单播/广播通道。 */
    public static void joinGroupQuietly(MulticastSocket socket) {
        try {
            socket.joinGroup(InetAddress.getByName(MULTICAST_ADDRESS));
        } catch (Throwable ignored) {
        }
    }

    /** 广播 + 组播同时发一遍询问，任一通道通就能被应答。 */
    private static void sendQuery(MulticastSocket socket, byte[] query) {
        for (InetAddress address : broadcastAddresses()) {
            try {
                socket.send(new DatagramPacket(query, query.length, address, UDP_PORT));
            } catch (Throwable ignored) {
            }
        }
        try {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.send(new DatagramPacket(query, query.length, group, UDP_PORT));
        } catch (Throwable ignored) {
        }
    }

    private static void startSweepThread(final MulticastSocket socket, final byte[] query,
                                         final List<String> targets, final Scan scan) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                sweep(socket, query, targets, scan);
            }
        }, "hfx-discovery-sweep");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sweep(MulticastSocket socket, byte[] query, List<String> targets, Scan scan) {
        int sent = 0;
        for (String host : targets) {
            if (scan.isCanceled()) {
                return;
            }
            try {
                socket.send(new DatagramPacket(query, query.length,
                        InetAddress.getByName(host), UDP_PORT));
            } catch (Throwable ignored) {
            }
            sent++;
            if (sent % SWEEP_BURST == 0) {
                try {
                    Thread.sleep(SWEEP_PACE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static byte[] queryPacket() {
        return QUERY.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] responsePacket(int tcpPort, String name) {
        return encodePacket(RESPONSE_PREFIX, tcpPort, name);
    }

    /** 反向连接请求（安卓 → 安卓/PC）：HFXDISC/1 C &lt;tcpPort&gt; &lt;urlencoded name&gt;。 */
    public static byte[] reverseConnectPacket(int tcpPort, String name) {
        return encodePacket(REVERSE_CONNECT_PREFIX, tcpPort, name);
    }

    /** 对反向连接请求的应答：accepted=true → "HFXDISC/1 A 0"，false → "HFXDISC/1 A 1"。 */
    public static byte[] reverseAckPacket(boolean accepted) {
        return (ACK_PREFIX + (accepted ? "0" : "1")).getBytes(StandardCharsets.UTF_8);
    }

    /** 收到的包是否为反向连接请求 C。 */
    public static boolean isReverseConnect(byte[] data, int length) {
        if (data == null || length <= 0 || length > REVERSE_MAX_LENGTH) {
            return false;
        }
        return new String(data, 0, length, StandardCharsets.UTF_8)
                .startsWith(REVERSE_CONNECT_PREFIX);
    }

    /** 反向连接请求解析结果；port 为请求方服务端端口（当前 5740），name 只用于显示。 */
    public static final class ReverseConnect {
        public final int port;
        public final String name;

        public ReverseConnect(int port, String name) {
            this.port = port;
            this.name = name;
        }
    }

    /**
     * 解析 C 包；空包、超长、前缀不符、端口非数字、端口越界都返回 null，不抛异常。
     * 名字走 URLDecoder，失败则用原文；再走 sanitizeName；空名字用 "Android"。
     */
    public static ReverseConnect parseReverseConnect(byte[] data, int length) {
        if (data == null || length <= 0 || length > REVERSE_MAX_LENGTH) {
            return null;
        }
        String text = new String(data, 0, length, StandardCharsets.UTF_8);
        if (!text.startsWith(REVERSE_CONNECT_PREFIX)) {
            return null;
        }
        String[] parts = text.split(" ", 4);
        if (parts.length < 4) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        String name;
        try {
            name = URLDecoder.decode(parts[3], "UTF-8");
        } catch (Throwable e) {
            name = parts[3];
        }
        name = sanitizeName(name);
        if (name.isEmpty()) {
            name = "Android";
        }
        return new ReverseConnect(port, name);
    }

    /** 是否私网 IPv4：只接受 10/8、172.16/12、192.168/16，与 subnetPrefixOf 判定一致。 */
    public static boolean isSiteLocalIpv4(String ip) {
        return subnetPrefixOf(ip) != null;
    }

    private static byte[] encodePacket(String prefix, int tcpPort, String name) {
        String safe = sanitizeName(name);
        String encoded;
        try {
            encoded = URLEncoder.encode(safe, "UTF-8");
        } catch (Throwable e) {
            encoded = safe;
        }
        return (prefix + tcpPort + " " + encoded).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析 PC 的反向连接应答 A 包：true=接受（A 0），false=拒绝/忙（A 1）；
     * 格式不符返回 null，不抛异常。
     */
    public static Boolean parseAck(byte[] data, int length) {
        if (data == null || length <= 0 || length > 32) {
            return null;
        }
        String text = new String(data, 0, length, StandardCharsets.UTF_8);
        if (!text.startsWith(ACK_PREFIX)) {
            return null;
        }
        if (text.equals(ACK_PREFIX + "0")) {
            return Boolean.TRUE;
        }
        if (text.equals(ACK_PREFIX + "1")) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static boolean isQuery(byte[] data, int length) {
        if (data == null || length <= 0 || length > 64) {
            return false;
        }
        return QUERY.equals(new String(data, 0, length, StandardCharsets.UTF_8));
    }

    private static Device parseResponse(String text, String ip, String prefix, boolean reverse) {
        if (text == null || !text.startsWith(prefix)) {
            return null;
        }
        String[] parts = text.split(" ", 4);
        if (parts.length < 4) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        String name;
        try {
            name = URLDecoder.decode(parts[3], "UTF-8");
        } catch (Throwable e) {
            name = parts[3];
        }
        name = sanitizeName(name);
        if (name.isEmpty()) {
            name = reverse ? "PC" : "HFX";
        }
        return new Device(ip, name, port, reverse);
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String v = name.replace('\r', ' ').replace('\n', ' ').trim();
        if (v.length() > 64) {
            v = v.substring(0, 64);
        }
        return v;
    }

    /** 全部候选广播地址：受限广播 + 各可用网卡广播。 */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Throwable ignored) {
        }
        try {
            for (NetworkInterface nif : allInterfaces()) {
                if (!isUsable(nif)) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null && !out.contains(broadcast)) {
                        out.add(broadcast);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<NetworkInterface> allInterfaces() {
        List<NetworkInterface> out = new ArrayList<NetworkInterface>();
        try {
            java.util.Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                out.add(en.nextElement());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean isUsable(NetworkInterface nif) {
        try {
            if (!nif.isUp() || nif.isLoopback()) {
                return false;
            }
        } catch (Throwable e) {
            return false;
        }
        String name = nif.getName().toLowerCase();
        return !name.startsWith("rmnet")
                && !name.startsWith("ccmni")
                && !name.startsWith("p2p");
    }

    /**
     * 本机全部可用 IPv4（用于跳过“自己扫到自己”）。
     * 跳过回环、链路本地、蜂窝和 p2p 接口。
     */
    public static List<String> localIpv4All() {
        List<String> out = new ArrayList<String>();
        for (NetworkInterface nif : allInterfaces()) {
            if (!isUsable(nif)) {
                continue;
            }
            try {
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (host == null || host.startsWith("169.254.")) {
                        continue;
                    }
                    if (!out.contains(host)) {
                        out.add(host);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 用于扫描与 ADB 主信道选择的代表地址：优先 192.168.*，其次任意私网地址。 */
    public static String localIpv4() {
        List<String> all = localIpv4All();
        for (String ip : all) {
            if (ip.startsWith("192.168.")) {
                return ip;
            }
        }
        for (String ip : all) {
            if (subnetPrefixOf(ip) != null) {
                return ip;
            }
        }
        return all.isEmpty() ? "" : all.get(0);
    }

    /**
     * 私网 IPv4 的 /24 前缀；公网、链路本地、IPv6 返回 null。
     * 只认 10/8、172.16/12、192.168/16。
     */
    public static String subnetPrefixOf(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        int cut = v.indexOf('%');
        if (cut >= 0) {
            v = v.substring(0, cut).trim();
        }
        String[] p = v.split("\\.");
        if (p.length != 4) {
            return null;
        }
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                o[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (o[i] < 0 || o[i] > 255) {
                return null;
            }
        }
        boolean priv = o[0] == 10 || (o[0] == 192 && o[1] == 168)
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31);
        return priv ? (o[0] + "." + o[1] + "." + o[2]) : null;
    }

    /**
     * 收集所有本机私网地址所属 /24 段的全部主机地址。
     * 旧实现只取 localIpv4() 的单个段，双网卡（wlan + 热点/USB 网络）时会漏扫另一半。
     */
    static List<String> subnetTargetsAll(List<String> ownIps) {
        List<String> prefixes = new ArrayList<String>();
        for (String ip : ownIps) {
            String prefix = subnetPrefixOf(ip);
            if (prefix != null && !prefixes.contains(prefix) && prefixes.size() < MAX_SWEEP_SUBNETS) {
                prefixes.add(prefix);
            }
        }
        List<String> out = new ArrayList<String>();
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                String host = prefix + "." + i;
                if (!ownIps.contains(host) && !out.contains(host)) {
                    out.add(host);
                }
            }
        }
        return out;
    }
}
