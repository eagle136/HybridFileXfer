package hfx.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import hfx.core.TransferConnection;
import hfx.core.TrafficInfo;
import hfx.core.TransferFileCallback;

public class SpeedMonitorThread extends Thread {
    private volatile boolean isRun = true;
    private final List<TransferConnection> connections;
    private final TransferFileCallback callback;
    /** 发送侧才有；接收侧传 null。僵死通道的在途块要还给它的栅栏计数。 */
    private final ReadFileCall readFileCall;

    public SpeedMonitorThread(List<TransferConnection> connections, TransferFileCallback callback) {
        this(connections, callback, null);
    }

    public SpeedMonitorThread(List<TransferConnection> connections, TransferFileCallback callback,
                              ReadFileCall readFileCall) {
        this.connections = connections;
        this.callback = callback;
        this.readFileCall = readFileCall;
    }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        while (isRun){
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
            List<TrafficInfo> trafficInfoList = new ArrayList<>();
            for (TransferConnection channel : connections) {
                trafficInfoList.add(channel.resetCurrentTrafficInfo());
            }
            callback.onSpeedInfo(trafficInfoList);

            // USB_ADB 拔线后若 socket 僵死：只有“持有在途块且 10s 无字节进展”才关，
            // 空闲等队列的通道不动。
            // 30s 实测会让整单在数据发完后空等约 20s 才补发（adb 隧道先死、对端
            // sendfile 还往已死的隧道缓冲里写，直到 socket 写满才彻底阻塞）；
            // 健康通道上 4MB 块最慢也会在秒级内有进展，10s 足够区分僵死与拥塞。
            long now = System.currentTimeMillis();
            for (TransferConnection connection : connections) {
                if (connection.isStalled(now, 10_000L) && connection.reap()) {
                    // 先释放它在途的那一块，让读线程的 END_POINT 栅栏能过。
                    // 这块不在这里回队：卡在 sendfile 的线程可能稍后才醒，
                    // 丢失的块统一由缺失块补发握手找回。
                    connection.markInFlight(false);
                    if (readFileCall != null) {
                        readFileCall.onBlockFinished();
                    }
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public void cancel(){
        isRun = false;
        interrupt();
    }
}
