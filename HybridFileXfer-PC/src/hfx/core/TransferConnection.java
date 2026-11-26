package hfx.core;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;

import hfx.core.TrafficInfo;
import hfx.DataByteChannel;

public class TransferConnection {
    public final String iName;
    /**
     * 辅助通道：USB_ADB。允许它中途断开并把在途块交给其它通道重传，
     * 不触发整单中断。Wi-Fi 等主通道仍按原逻辑处理。
     */
    public final boolean optional;
    /** 头字段读写用的包装通道 */
    public final DataByteChannel channel;
    /**
     * 原始 SocketChannel。sendfile 零拷贝必须直接把数据写进它，
     * 不能经过 {@link DataByteChannel} 包装，否则 JDK 会退化成普通拷贝。
     */
    public final SocketChannel socket;

    private TrafficInfo currentTraffic;
    private TrafficInfo totalTraffic;

    /** 发送侧：当前是否持有一个尚未发完的块（空闲等待队列不算停滞） */
    private volatile boolean inFlight;
    /** 发送/接收最近一次有字节进展的时刻 */
    private volatile long lastProgressAt = System.currentTimeMillis();
    /**
     * 已被看门狗判定僵死并接管。
     * <p>
     * adb 隧道黑洞化时 sendfile 可能永远卡在内核里，连 close() 都叫不醒；
     * 看门狗必须自己释放这块的在途计数，否则读线程的 END_POINT 栅栏永远等不到 0。
     * 被接管的通道之后若从 sendfile 里醒来，只允许抛 OptionalChannelLost，
     * 不能再回队/再减计数（那块已由缺失块补发握手兜底）。
     */
    private final java.util.concurrent.atomic.AtomicBoolean reaped =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public TransferConnection(String iName, SocketChannel socket) throws IOException {
        this.iName = iName;
        this.optional = iName != null && iName.startsWith("USB_ADB");
        this.socket = socket;
        tuneSocket(socket);
        this.channel = new DataByteChannel(socket);
        //必须带上名字：第一次采样取到的就是这个对象，名字缺失会打印 null
        currentTraffic = new TrafficInfo(iName);
        totalTraffic = new TrafficInfo(iName);
    }

    /**
     * 传输通道的 socket 调优。所有通道（客户端连出的 + 服务端 accept 的）都会经过这个构造器，
     * 因此这里是唯一需要设置的地方。
     * <p>
     * 这里<b>只</b>开 TCP_NODELAY，<b>不</b>显式设置 SO_SNDBUF/SO_RCVBUF：
     * 一旦应用层显式设置这两个值，内核就会关闭发送/接收窗口的自动调整
     * （Linux 的 tcp_moderate_rcvbuf、Windows 的 receive-window auto-tuning）。
     * 在速率和 RTT 都在波动的无线链路上，固定缓冲只会比自动调整更差。
     */
    private static void tuneSocket(SocketChannel socket) {
        try {
            //块头是小包（约 100 字节），不关 Nagle 会被扣住等 ACK，
            //等于每个块的边界上白等一个 RTT。
            socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (Exception ignored) {
        }
    }

    public synchronized void addUploadedBytes(long byteCount) {
        currentTraffic.uploadTraffic += byteCount;
        totalTraffic.uploadTraffic += byteCount;
        lastProgressAt = System.currentTimeMillis();
    }

    public synchronized void addDownloadedBytes(long byteCount) {
        currentTraffic.downloadTraffic += byteCount;
        totalTraffic.downloadTraffic += byteCount;
        lastProgressAt = System.currentTimeMillis();
    }

    /** 发送侧取到/交还一个数据块时调用；取块时重置停滞计时。 */
    public void markInFlight(boolean value) {
        this.inFlight = value;
        if (value) {
            this.lastProgressAt = System.currentTimeMillis();
        }
    }

    /** 辅助通道持有在途块且 10s 没有任何字节进展 → 认为链路僵死。 */
    public boolean isStalled(long now, long timeoutMs) {
        return optional && inFlight && now - lastProgressAt > timeoutMs;
    }

    /** 看门狗接管这条僵死通道；只有第一次调用返回 true。 */
    public boolean reap() {
        return reaped.compareAndSet(false, true);
    }

    public boolean isReaped() {
        return reaped.get();
    }

    public synchronized TrafficInfo resetCurrentTrafficInfo() {
        TrafficInfo info = currentTraffic;
        currentTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo resetTotalTrafficInfo() {
        TrafficInfo info = totalTraffic;
        totalTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo getTotalTraffic(){
        return totalTraffic;
    }

    public void close() throws IOException {
        channel.close();
    }
}
