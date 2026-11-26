package hfx.core;

import java.net.InetAddress;
import java.util.List;

public interface ConnectServerCallback {
    void onConnectingControlChannel(String address, int port);

    void onVersionMismatch(int localVersion, int remoteVersion);

    void onConnectControlFailed();

    void onConnectingTransferChannel(
            String name,
            InetAddress inetAddress,
            InetAddress bindAddress);

    void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e);

    void onOOM(
            int createdBuffers,
            int requiredBuffers,
            long maxMemoryMB,
            String osArch);

    void onRemoteOOM();

    void onConnectSuccess(List<String> channelNames);

    /** 已把本机名字发给服务端，等待对方确认连接（默认无提示）。 */
    default void onAwaitingPeerConfirmation() {
    }

    /** 对方拒绝连接或 30s 未确认。 */
    default void onConnectionDenied() {
    }

    /** 控制通道已连上，但没有一张网卡的数据通道能在 1s 内连通。 */
    default void onNoTransferChannel() {
    }
}
