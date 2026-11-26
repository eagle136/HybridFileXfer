package hfx.droid;

import android.os.Build;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import hfx.async.BackstageTask;
import hfx.NativeMemory;
import hfx.IIOService;
import hfx.SessionIdleTimer;
import hfx.core.ConsentProvider;
import hfx.core.ControllerIdentifiers;
import hfx.core.FileBlock;
import hfx.core.HFXClient;
import hfx.core.HFXService;
import hfx.core.ReadFileCall;
import hfx.core.ReceiveFileCall;
import hfx.core.RemoteConsentException;
import hfx.core.SendFileCall;
import hfx.core.SpeedMonitorThread;
import hfx.core.TransferConnection;
import hfx.core.WriteFileCall;
import hfx.core.Directory;
import hfx.core.RemoteFile;
import hfx.core.ServerNetInterface;
import hfx.droid.callback.StartServerCallback;
import hfx.core.TransferFileCallback;
import hfx.DataByteChannel;
import hfx.ParcelableRemoteFile;

public class HFXServer extends HFXService {
    private static final String TAG = "HFXD";
    /** 对方一条清单记录最少约 19 字节（两个 UTF + 8 + 8 + 1），10 万条已达 1.9MB，超过只能是控制通道错位 */
    private static final int MAX_REMOTE_LIST_SIZE = 100_000;

    /** 握手阶段读超时：连上不发数据的探测/半开连接不能把 accept 循环钉死（要覆盖对端建数据通道的时间） */
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 15000;
    /** 等对端把声称的传输通道 accept 进来的上限 */
    private static final int TRANSFER_ACCEPT_TIMEOUT_MS = 15000;

    public static volatile HFXServer instance;
    protected final IIOService ioService;
    /** true：IO 服务跑在 root/shizuku 下，文件读写不需要应用存储权限 */
    private final boolean privileged;
    protected ServerSocketChannel serverSocketChannel;
    protected int remoteFileSystem;
    protected String remoteHomeDir;
    private String remoteClientName = "";
    private String remotePeerIp = "";
    /** 上一次列远端目录时，对方是否自报"受存储权限限制"（只能看到文件夹） */
    private volatile boolean remoteListLimited;

    /**
     * 控制通道的事务锁。
     * <p>
     * 服务端（本机是发起方）没有读指令的循环，{@code ctChannel} 只被本机 UI 线程使用，
     * 但调用方是分散的：列目录（每次 cd/back/refresh 一个新线程）、删除、新建目录、
     * 发起传输各在不同线程上。两条线程同时 writeShort+writeUTF+read… 就会把字节流交错，
     * 之后整个会话的读写都错位（表现为"列目录长度异常"或莫名断线）。
     * 所有控制通道事务必须持这把锁。
     */
    private final Object controlLock = new Object();

    /** 等待连接期间应答局域网扫描；握手成功后停止 */
    private DiscoveryResponder discoveryResponder;
    /** 反向连接（对方点击本机后请求连回）的确认入口；为空时一律拒绝 */
    private volatile DiscoveryResponder.Listener reverseAcceptListener;

    public void setReverseAcceptListener(DiscoveryResponder.Listener listener) {
        reverseAcceptListener = listener;
    }

    /**
     * 本次启动下发给客户端的无线连接数（每张无线网卡开几条 TCP）。
     * 发起传输的是服务端，所以条数由服务端的配置决定，客户端只照着开。
     */
    private int wirelessConnections = CONNECTIONS_WIRELESS_DEFAULT;

    public HFXServer(IIOService ioService, boolean privileged) {
        this.ioService = ioService;
        this.privileged = privileged;
    }

    public boolean isPrivileged() {
        return privileged;
    }

    public String getRemoteClientName() {
        return remoteClientName;
    }

    public String getRemotePeerIp() {
        return remotePeerIp;
    }

    /** 上一次列远端目录时对方是否受存储权限限制（清单里只有文件夹）。 */
    public boolean isRemoteListLimited() {
        return remoteListLimited;
    }

    /** 服务端设备名（机型）；反向连接请求 C 包也用这个名字自报。 */
    public static String serverName() {
        String model = Build.MODEL;
        if (model == null || model.trim().isEmpty()) {
            return "Android";
        }
        return model.trim();
    }

    private static String peerIp(SocketChannel socket) {
        try {
            SocketAddress address = socket.getRemoteAddress();
            if (address instanceof InetSocketAddress) {
                return ((InetSocketAddress) address).getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    /** ADB 隧道下对端地址是 127.0.0.1，弹窗改用客户端自报的局域网 IP */
    private static String displayPeerIp(String reportedIp, String socketIp) {
        if (socketIp != null && !HFXClient.isLoopbackAddress(socketIp)) {
            return socketIp;
        }
        if (reportedIp != null) {
            String value = reportedIp.trim();
            if (!value.isEmpty() && value.length() <= 45
                    && !HFXClient.isLoopbackAddress(value)
                    && value.indexOf(' ') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
                return value;
            }
        }
        return socketIp;
    }

    /** 服务端自报的局域网 IPv4，优先无线/有线网卡；没有则空串 */
    private static String lanIp(List<ServerNetInterface> interfaceList) {
        String fallback = "";
        for (ServerNetInterface netInterface : interfaceList) {
            InetAddress address = netInterface.address;
            if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                    || !address.isSiteLocalAddress()) {
                continue;
            }
            String name = netInterface.name == null ? "" : netInterface.name.toLowerCase();
            String ip = address.getHostAddress();
            if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                return ip;
            }
            if (fallback.isEmpty()) {
                fallback = ip;
            }
        }
        return fallback;
    }

    private static void closeConnectionsQuietly(List<TransferConnection> connections) {
        for (TransferConnection connection : connections) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
        connections.clear();
    }

    private void startDiscoveryResponder(int port) {
        stopDiscoveryResponder();
        DiscoveryResponder responder = new DiscoveryResponder(port, serverName(),
                reverseAcceptListener);
        discoveryResponder = responder;
        responder.start();
    }

    private void stopDiscoveryResponder() {
        DiscoveryResponder responder = discoveryResponder;
        discoveryResponder = null;
        if (responder != null) {
            responder.stop();
        }
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList, int localBufferCount,
                            int remoteBufferCount, int wirelessConnections,
                            StartServerCallback callback) throws IOException {
        // 服务端是传输的发起方，无线条数以本机配置为准；越界值在这里收口
        this.wirelessConnections = Math.max(CONNECTIONS_WIRELESS_MIN,
                Math.min(CONNECTIONS_WIRELESS_MAX, wirelessConnections));
        ServerSocketChannel serverSocketChannel;
        try {
            serverSocketChannel = ServerSocketChannel.open();
            // 断开后立即回到等待连接：允许在上一会话的 TIME_WAIT 期间重新监听同一端口
            serverSocketChannel.socket().setReuseAddress(true);
            serverSocketChannel.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            callback.onBindFailed(port);
            return;
        }
        this.serverSocketChannel = serverSocketChannel;
        startDiscoveryResponder(port);
        Log.i(TAG, "server listening port=" + port);
        callback.onStatedServer();
        //控制通道
        DataByteChannel ctChannel;
        //传输通道
        List<TransferConnection> connections = null;
        //第一此accept的为控制通道
        SocketChannel controlSocket = null;
        conn:
        while (true) {
            SocketChannel socket;
            try {
                socket = serverSocketChannel.accept();
            } catch (IOException e) {
                if (!serverSocketChannel.isOpen()) {
                    return;
                }
                throw e;
            }
            controlSocket = socket;
            ctChannel = new DataByteChannel(socket);
            try {
                // 握手阶段必须带读超时：端口扫描、TCP 探活、半开连接连上来但不发数据时，
                // 原来的 readFully 会无限阻塞，整个 accept 循环就再也接不了正常客户端
                // （注释里说的“只丢弃当前连接”其实做不到）。
                socket.socket().setTcpNoDelay(true);
                socket.socket().setKeepAlive(true);
                socket.socket().setSoTimeout(HANDSHAKE_READ_TIMEOUT_MS);
            } catch (IOException ignored) {
            }
            try {
                //协议判断
                byte[] headerBytes = HFXServer.CLIENT_HEADER.getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[headerBytes.length];
                ctChannel.readFully(header);
                if (!Arrays.equals(headerBytes, header)) {
                    ctChannel.writeBytes("protocol error\n");
                    ctChannel.close();
                    continue;
                }
                //版本判断
                int versionCode = ctChannel.readInt();
                if (versionCode != HFXServer.VERSION_CODE) {
                    //返回当前服务端版本信息
                    ctChannel.writeBoolean(false);//版本未正确匹配
                    ctChannel.writeInt(HFXServer.VERSION_CODE);
                    ctChannel.close();
                    continue;
                }
                ctChannel.writeBoolean(true);//版本正确匹配
                //客户端名字 + 本地连接确认
                String clientName = ctChannel.readUTF();
                String clientLanIp = ctChannel.readUTF();
                String peerAddress = displayPeerIp(clientLanIp, peerIp(socket));
                Log.i(TAG, "server control from name=" + clientName + " ip=" + peerAddress);
                boolean accepted = consentProvider.requestConnection(clientName,
                        peerAddress, ConsentProvider.DEFAULT_TIMEOUT_MS);
                Log.i(TAG, "server consent result=" + accepted);
                ctChannel.writeBoolean(accepted);
                if (!accepted) {
                    ctChannel.close();
                    continue;
                }
                remoteClientName = clientName;
                remotePeerIp = peerAddress;
                ctChannel.writeUTF(serverName());
                // 控制通道是回环地址时，客户端用这个局域网 IP 显示
                ctChannel.writeUTF(lanIp(interfaceList));
                // 320: 下发无线连接数（USB_ADB 不收此值约束，客户端恒开 2 条）
                ctChannel.writeInt(wirelessConnections);
            } catch (IOException e) {
                // TCP 探活、端口扫描、半开连接、握手超时：只丢弃当前连接，服务端继续等待
                Log.i(TAG, "server handshake IOException: " + e);
                try {
                    ctChannel.close();
                } catch (IOException ignored) {
                }
                // 已经 accept 的传输通道也要关，否则每次半途失败都漏几根 socket
                if (connections != null) {
                    closeConnectionsQuietly(connections);
                    connections = null;
                }
                callback.onConnectAborted();
                continue;
            }
            ctChannel.writeInt(interfaceList.size());//网卡IP数量
            for (ServerNetInterface netInterface : interfaceList) {
                byte[] address = netInterface.address.getAddress();
                ctChannel.writeUTF(netInterface.name);//网卡名称
                ctChannel.writeByte(address.length);//地址长度（IPv4：4与IPv6：16）
                ctChannel.write(address);//地址
                if (netInterface.clientBindAddress == null) {
                    ctChannel.writeByte(0);//地址长度（null:0）
                } else {
                    byte[] bAddress = netInterface.clientBindAddress.getAddress();
                    ctChannel.writeByte(bAddress.length);//地址长度（IPv4：4与IPv6：16）
                    ctChannel.write(bAddress);//地址
                }
            }
            //对端逐张网卡告知开几条连接（303 协议：从单一倍数改为按网卡下发）
            int[] perInterface = new int[interfaceList.size()];
            int totalConnections = 0;
            for (int i = 0; i < perInterface.length; i++) {
                perInterface[i] = ctChannel.readInt();
                // 允许 0：客户端在 LAN 控制下会主动禁用 USB_ADB 数据通道
                if (perInterface[i] < 0) {
                    perInterface[i] = 0;
                }
                totalConnections += perInterface[i];
            }
            //连接传输通道：按对端发来的顺序 accept，总数等于各网卡连接数之和
            connections = new ArrayList<>(totalConnections);
            // 对端可能声称要开 N 条却中途放弃，accept 不能无限期挂在这儿
            try {
                serverSocketChannel.socket().setSoTimeout(TRANSFER_ACCEPT_TIMEOUT_MS);
            } catch (IOException ignored) {
            }
            for (int i = 0; i < totalConnections; i++) {
                //对方连接通道是否成功
                boolean succeed = ctChannel.readBoolean();
                //对方所连接通道的名称（由控制器通道发送名称）
                String name = ctChannel.readUTF();
                if (succeed) {
                    //accept通道
                    SocketChannel transferSocket = serverSocketChannel.accept();
                    try {
                        // 传输通道关掉 Nagle：4MB 块 + 小包头混发时 Nagle 会拖 40ms
                        transferSocket.socket().setTcpNoDelay(true);
                        transferSocket.socket().setKeepAlive(true);
                    } catch (IOException ignored) {
                    }
                    connections.add(new TransferConnection(name, transferSocket));
                    ctChannel.writeBoolean(true);
                    callback.onAccepted(name);
                } else {
                    ctChannel.writeBoolean(false);
                    ctChannel.close();
                    callback.onAcceptFailed(name);
                    closeConnectionsQuietly(connections);
                    callback.onConnectAborted();
                    continue conn;
                }
            }
            // accept 阶段结束，恢复成可长等待（下次握手会重新设超时）
            try {
                serverSocketChannel.socket().setSoTimeout(0);
            } catch (IOException ignored) {
            }
            break;
        }

        LinkedBlockingDeque<ByteBuffer> buffers = this.buffers;
        //告知对方创建的缓冲区块数量
        ctChannel.writeInt(remoteBufferCount);
        if (!ctChannel.readBoolean()) {
            callback.onPcOOM();
            stopDiscoveryResponder();
            serverSocketChannel.close();
            return;
        }
        //创建缓冲区块
        for (int i = 0; i < localBufferCount; i++) {
            ByteBuffer buffer = NativeMemory.allocateLargeBuffer(FileBlock.BLOCK_SIZE);
            if (buffer != null) {
                buffers.add(buffer);
            } else {
                //释放缓冲区块内存
                for (ByteBuffer buff : buffers) {
                    NativeMemory.freeBuffer(buff);
                }
                buffers.clear();
                ctChannel.writeBoolean(false);
                stopDiscoveryResponder();
                serverSocketChannel.close();
                callback.onMeOOM(i, localBufferCount);
                return;
            }
        }
        ctChannel.writeBoolean(true);
        //读取对方文件系统信息
        this.remoteFileSystem = ctChannel.readInt();
        //读取对方设定的主目录
        this.remoteHomeDir = ctChannel.readUTF();
        this.ctChannel = ctChannel;
        this.connections = connections;
        // 握手完成，撤掉控制通道的读超时：这条 socket 之后要跨整个传输周期等对端应答，
        // 传输可能远超握手超时，留在那儿会把正常的长传输判成断线。
        if (controlSocket != null) {
            try {
                controlSocket.socket().setSoTimeout(0);
            } catch (IOException ignored) {
            }
        }
        stopDiscoveryResponder();
        Log.i(TAG, "server connect success channels=" + connections.size());
        callback.onConnectSuccess();
    }

    public void closeServerSocket() {
        Log.i(TAG, "server closeServerSocket");
        stopDiscoveryResponder();
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** 服务端监听/会话是否已被关闭；Activity 重建后用它判断是否还能接管。 */
    public boolean isClosed() {
        ServerSocketChannel channel = serverSocketChannel;
        return channel == null || !channel.isOpen();
    }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public String getRemoteHomeDir() {
        return remoteHomeDir;
    }

    public void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        SessionIdleTimer.markActivity();
        SessionIdleTimer.setTransferring(true);
        try {
            synchronized (controlLock) {
                ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
                // 清单先发：对方要在授权弹窗里列"写入哪几个文件"，然后才回 consent 字节
                writePaths(pathsOf(files));
                int consent = ctChannel.readByte();
                if (consent != ConsentProvider.CONSENT_ONCE && consent != ConsentProvider.CONSENT_SESSION) {
                    callback.onPermissionDenied(true);
                    return;
                }
                sendFiles(files,localDir,remoteDir,callback);
            }
        } finally {
            SessionIdleTimer.setTransferring(false);
        }
    }

    public void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        SessionIdleTimer.markActivity();
        SessionIdleTimer.setTransferring(true);
        try {
            synchronized (controlLock) {
                ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
                // 清单 + 目录先发，对方读完清单再决定是否授权“读取这些文件”
                writePaths(pathsOf(files));
                ctChannel.writeUTF(localDir.path);
                ctChannel.writeInt(localDir.fileSystem);
                ctChannel.writeUTF(remoteDir.path);
                int consent = ctChannel.readByte();
                if (consent != ConsentProvider.CONSENT_ONCE && consent != ConsentProvider.CONSENT_SESSION) {
                    callback.onPermissionDenied(false);
                    return;
                }
                receiveFiles(callback);
            }
        } finally {
            SessionIdleTimer.setTransferring(false);
        }
    }

    private static List<String> pathsOf(List<RemoteFile> files) {
        List<String> paths = new ArrayList<>(files.size());
        for (RemoteFile file : files) {
            paths.add(file.getPath());
        }
        return paths;
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    public List<RemoteFile> listClientFiles(String path) throws IOException {
        SessionIdleTimer.markActivity();
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
            ctChannel.writeUTF(path);
            // 列目录不需要授权：对方回一个“本机是否受存储权限限制”的布尔 + 清单。
            // 受限时清单里只有文件夹，界面据此给一句非阻塞提示。
            remoteListLimited = ctChannel.readBoolean();
            int listSize = ctChannel.readInt();
            if (listSize == -1) {
                return null;
            }
            if (listSize < 0 || listSize > MAX_REMOTE_LIST_SIZE) {
                throw new IOException("对方文件清单长度异常，控制通道可能已错位: " + listSize);
            }
            ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
            //| name       | path       | lastModified | size    | isDirectory |
            //| ---------- | ---------- | ------------ | ------- | ----------- |
            //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
            for (int i = 0; i < listSize; i++) {
                RemoteFile remoteFile = new RemoteFile(
                        ctChannel.readUTF(),//name
                        ctChannel.readUTF(),//path
                        ctChannel.readLong(),//lastModified
                        ctChannel.readLong(),//size
                        ctChannel.readBoolean()//isDirectory
                );
                remoteFiles.add(remoteFile);
            }
            return remoteFiles;
        }
    }

    /**
     * 批量删除远端文件：一条指令带整份清单，对方弹一次授权（清单就是弹窗内容），
     * 同意后逐项回执。
     *
     * @return 全部删除成功才 true；对方拒绝授权时抛 {@link RemoteConsentException}，
     *         控制通道仍然完好，调用方按"被拒"提示，不要当成断线。
     */
    public boolean deleteRemoteFiles(List<String> files) throws IOException {
        SessionIdleTimer.markActivity();
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
            writePaths(files);
            int consent = ctChannel.readByte();
            if (consent != ConsentProvider.CONSENT_ONCE && consent != ConsentProvider.CONSENT_SESSION) {
                throw new RemoteConsentException("对方拒绝本次删除请求");
            }
            boolean allDeleted = true;
            for (int i = 0; i < files.size(); i++) {
                if (!ctChannel.readBoolean()) {
                    allDeleted = false;
                }
            }
            return allDeleted;
        }
    }

    public boolean createRemoteDir(String parent, String child) throws IOException {
        SessionIdleTimer.markActivity();
        synchronized (controlLock) {
            ctChannel.writeShort(ControllerIdentifiers.MKDIR);
            ctChannel.writeUTF(parent);
            ctChannel.writeUTF(child);
            return ctChannel.readBoolean();
        }
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            List<ParcelableRemoteFile> slice = ioService.getAndRemoveFileListSlice(chunkId);
            if (slice == null) {
                //slice 已丢失（例如 IO 服务进程在此期间被重启），中止本次枚举，交由调用方重试
                return null;
            }
            remoteFiles.addAll(slice);
        }
        return remoteFiles;
    }

    public List<String> getConnectionListINames() {
        List<String> names = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            names.add(connection.iName);
        }
        return names;
    }

    public void disconnect(){
        Log.i(TAG, "server disconnect");
        SessionIdleTimer.stop(SessionIdleTimer.KEY_SERVER);
        stopDiscoveryResponder();
        // 顺序很重要：先通知对端、断开链路，等传输线程退场，最后才释放 native 缓冲。
        // 原来一进来就 free，如果此刻正在传输（:IOService 被杀导致 onServiceDisconnected、
        // 或用户在传输中手动断开），接收线程会在已经 free 的内存上继续读写 → 直接 SIGSEGV。
        if (ctChannel != null) {
            synchronized (controlLock) {
                try {
                    //通知断开连接
                    ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
                } catch (IOException ignored) {
                }
            }
        }
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
        //关闭所有用于传输的连接，让卡在 read/transferTo 的线程退出来
        if (connections != null) {
            for (TransferConnection connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
        }
        // 控制通道也要关：只写 SHUTDOWN 不关 socket 的话对端半开连接会一直挂着，
        // 本机这边句柄也不释放，要等 GC。
        if (ctChannel != null) {
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
        }
        waitTransferQuietly();
        freeBuffersIfIdle("disconnect");
    }

    public void close(){
        SessionIdleTimer.stop(SessionIdleTimer.KEY_SERVER);
        stopDiscoveryResponder();
        //关闭serverSocket，释放端口绑定，不用管其他accept的Socket
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (ctChannel != null) {
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (connections != null) {
            for (TransferConnection connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
        }
        waitTransferQuietly();
        freeBuffersIfIdle("close");
    }

    /** 等正在跑的传输退出（上限 2s），断开/释放缓冲前调用。 */
    private void waitTransferQuietly() {
        long deadline = System.currentTimeMillis() + 2000L;
        while (isTransferring() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 只有确认没有传输在跑才释放缓冲池：还活着就得放弃这次释放（宁可漏 80MB 也不能 UAF）。
     * 用 poll 逐个摘，避免弱一致迭代器把已经交出去的缓冲再 free 一次。
     */
    private void freeBuffersIfIdle(String reason) {
        if (isTransferring()) {
            Log.w(TAG, "skip freeing buffers: transfer still running (" + reason + ")");
            return;
        }
        ByteBuffer buffer;
        while ((buffer = buffers.poll()) != null) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers,dequeCount,ioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new DroidReadFileCall(ioService,buffers,files,localDir,remoteDir,operateThreadCount);
    }
}
