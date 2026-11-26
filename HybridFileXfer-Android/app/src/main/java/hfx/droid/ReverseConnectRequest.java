package hfx.droid;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import hfx.core.Discovery;

/**
 * 反向连接请求（安卓 → PC）：向可发现的 PC 的 5741 发送 HFXDISC/1 C，
 * 等待其 HFXDISC/1 A 应答（0=接受，1=拒绝/忙），随后由 PC 主动连入本机服务端。
 *
 * <p>使用临时 UDP socket（不占用 5741），单发一次 C 后限时等待 A；
 * 超时、异常或被取消都按 TIMEOUT 上报。回调在请求线程执行，UI 层需自行切回主线程。
 */
public final class ReverseConnectRequest {

    private static final String TAG = "HFX-Reverse";
    private static final long TIMEOUT_MS = 4000L;
    private static final int RECEIVE_BUFFER = Discovery.REVERSE_MAX_LENGTH;

    public enum Result {
        /** A 0：PC 已接受，即将主动连入本机 5740 服务端 */
        ACCEPTED,
        /** A 1：PC 忙碌或当前不可发现 */
        REJECTED,
        /** 超时 / 异常 / 被取消 */
        TIMEOUT
    }

    public interface Callback {
        void onResult(Result result);
    }

    private volatile boolean canceled;
    private volatile DatagramSocket socket;

    /** 发起请求，使用默认超时（PC 端）。 */
    public void start(String targetIp, int tcpPort, String name, final Callback callback) {
        start(targetIp, tcpPort, name, TIMEOUT_MS, callback);
    }

    /**
     * 发起请求；callback 不可为 null 时必须在主线程安全使用（回调在请求线程上执行）。
     * 安卓对端要先弹确认框，调用方需给足超时（大于确认框倒计时）。
     */
    public void start(String targetIp, int tcpPort, String name, final long timeoutMs,
                      final Callback callback) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Result result = doRequest(targetIp, tcpPort, name, timeoutMs);
                if (callback != null) {
                    callback.onResult(result);
                }
            }
        }, "hfx-reverse-request");
        thread.setDaemon(true);
        thread.start();
    }

    /** 取消：关闭收包 socket，请求线程尽快退出并以 TIMEOUT 上报。 */
    public void cancel() {
        Log.i("HFXD", "C cancel");
        canceled = true;
        DatagramSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private Result doRequest(String targetIp, int tcpPort, String name, long timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            socket = s;
            s.setSoTimeout((int) timeoutMs);
            byte[] request = Discovery.reverseConnectPacket(tcpPort, name);
            s.send(new DatagramPacket(request, request.length,
                    InetAddress.getByName(targetIp), Discovery.UDP_PORT));
            Log.i("HFXD", "C sent to " + targetIp + ":" + Discovery.UDP_PORT
                    + " local=" + s.getLocalPort() + " wait=" + timeoutMs);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!canceled && System.currentTimeMillis() < deadline) {
                DatagramPacket packet =
                        new DatagramPacket(new byte[RECEIVE_BUFFER], RECEIVE_BUFFER);
                try {
                    s.receive(packet);
                } catch (SocketTimeoutException e) {
                    Log.i("HFXD", "C recv timeout local=" + s.getLocalPort());
                    break;
                }
                Log.i("HFXD", "C recv from " + packet.getAddress() + ":" + packet.getPort()
                        + " len=" + packet.getLength() + " text="
                        + new String(packet.getData(), 0, packet.getLength()));
                Boolean accepted = Discovery.parseAck(packet.getData(), packet.getLength());
                if (accepted != null) {
                    return accepted ? Result.ACCEPTED : Result.REJECTED;
                }
                // 其它包（如别家的 R/P/Q）忽略，继续等 A
            }
        } catch (Throwable t) {
            if (!canceled) {
                Log.w(TAG, "反向连接请求失败: " + t);
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            // 只清自己：cancel()→新请求 快速连发时，旧线程不得清掉新线程刚写入的 socket
            if (socket == s) {
                socket = null;
            }
        }
        return Result.TIMEOUT;
    }
}
