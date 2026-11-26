package hfx.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网发现（客户端主动扫描）。
 *
 * <p>扫描只在用户点击“扫描”时触发一次：广播询问 + 本网段 /24 单播询问，
 * 总窗口默认 3s，每 800ms 补发一次。客户端使用一个临时 UDP socket 收应答，
 * 不固定监听端口，因此不需要为 PC 增加入站防火墙规则。
 *
 * <p>协议（UTF-8 文本）：
 * <pre>
 *   请求：HFXDISC/1 Q
 *   应答：HFXDISC/1 R &lt;tcpPort&gt; &lt;urlencoded name&gt;
 * </pre>
 */
public final class Discovery {

    public static final int UDP_PORT = 5741;
    public static final long DEFAULT_TIMEOUT_MS = 3000L;

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

    private Discovery() {
    }

    public static final class Device {
        public final String ip;
        public final String name;
        public final int port;

        public Device(String ip, String name, int port) {
            this.ip = ip;
            this.name = name;
            this.port = port;
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
        private volatile DatagramSocket socket;

        public void cancel() {
            canceled = true;
            DatagramSocket s = socket;
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
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            scan.socket = socket;
            socket.setBroadcast(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            byte[] query = queryPacket();
            List<String> ownIps = localIpv4All();
            String own = localIpv4();
            String prefix = subnetPrefixOf(own);
            List<String> targets = prefix == null
                    ? new ArrayList<String>() : subnetTargets(prefix, ownIps);

            long startedAt = System.currentTimeMillis();
            long lastSendAt = 0L;
            boolean swept = false;
            while (!scan.isCanceled() && System.currentTimeMillis() - startedAt < timeoutMs) {
                long now = System.currentTimeMillis();
                if (now - lastSendAt >= RESEND_TICK_MS) {
                    lastSendAt = now;
                    sendBroadcast(socket, query);
                    if (!swept && !targets.isEmpty()) {
                        swept = true;
                        sweep(socket, query, targets, scan);
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
                    Device device = parseResponse(text, ip);
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
        }
        return new ArrayList<Device>(found.values());
    }

    private static void sendBroadcast(DatagramSocket socket, byte[] query) {
        for (InetAddress address : broadcastAddresses()) {
            try {
                socket.send(new DatagramPacket(query, query.length, address, UDP_PORT));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sweep(DatagramSocket socket, byte[] query, List<String> targets, Scan scan) {
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
        String safe = sanitizeName(name);
        String encoded;
        try {
            encoded = URLEncoder.encode(safe, "UTF-8");
        } catch (Throwable e) {
            encoded = safe;
        }
        return (RESPONSE_PREFIX + tcpPort + " " + encoded).getBytes(StandardCharsets.UTF_8);
    }

    /** PC 反向发现应答：HFXDISC/1 P &lt;tcpPort&gt; &lt;urlencoded name&gt;。 */
    public static byte[] reverseResponsePacket(int tcpPort, String name) {
        String safe = sanitizeName(name);
        String encoded;
        try {
            encoded = URLEncoder.encode(safe, "UTF-8");
        } catch (Throwable e) {
            encoded = safe;
        }
        return (REVERSE_RESPONSE_PREFIX + tcpPort + " " + encoded).getBytes(StandardCharsets.UTF_8);
    }

    /** PC 对反向连接请求的应答：accepted=true → "HFXDISC/1 A 0"，false → "HFXDISC/1 A 1"。 */
    public static byte[] reverseAckPacket(boolean accepted) {
        return (ACK_PREFIX + (accepted ? "0" : "1")).getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isQuery(byte[] data, int length) {
        if (data == null || length <= 0 || length > 64) {
            return false;
        }
        return QUERY.equals(new String(data, 0, length, StandardCharsets.UTF_8));
    }

    /** 安卓请求 PC 连回：HFXDISC/1 C &lt;tcpPort&gt; &lt;urlencoded name&gt;，长度上限 128。 */
    public static boolean isReverseConnect(byte[] data, int length) {
        if (data == null || length <= 0 || length > REVERSE_MAX_LENGTH) {
            return false;
        }
        return new String(data, 0, length, StandardCharsets.UTF_8).startsWith(REVERSE_CONNECT_PREFIX);
    }

    /** 反向连接请求解析结果；port 为安卓服务端端口（当前 5740），name 只用于显示。 */
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

    private static Device parseResponse(String text, String ip) {
        if (text == null || !text.startsWith(RESPONSE_PREFIX)) {
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
            name = "HFX";
        }
        return new Device(ip, name, port);
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

    private static List<InetAddress> broadcastAddresses() {
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
     * 是否私网 IPv4：只接受 10/8、172.16/12、192.168/16；
     * 排除 null、回环、169.254.*、IPv6。与 {@link #subnetPrefixOf} 判定一致。
     */
    public static boolean isSiteLocalIpv4(String ip) {
        return subnetPrefixOf(ip) != null;
    }

    private static List<String> subnetTargets(String prefix, List<String> skipIps) {
        List<String> out = new ArrayList<String>(254);
        for (int i = 1; i <= 254; i++) {
            String host = prefix + "." + i;
            if (!skipIps.contains(host)) {
                out.add(host);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
