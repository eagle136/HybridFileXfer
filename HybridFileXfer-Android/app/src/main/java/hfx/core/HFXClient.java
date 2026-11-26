package hfx.core;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import hfx.core.Directory;
import hfx.core.RemoteFile;
import hfx.core.ClientCallBack;
import hfx.core.ConnectServerCallback;
import hfx.DataByteChannel;
import hfx.SessionIdleTimer;

public abstract class HFXClient extends HFXService {

    private static final String TAG = "HFXD";

    /** 每张网卡首次试连的超时；超时就跳过整张网卡，继续用其它通道建会话。 */
    private static final int DATA_CHANNEL_TIMEOUT_MS = 1000;
    /** 控制通道 TCP 建连超时：连不上要尽快失败，不能让界面一直停在“正在连接”。 */
    private static final int CONTROL_CONNECT_TIMEOUT_MS = 5000;
    /**
     * 控制通道握手阶段的读超时。要留足服务端弹授权框的时间（ConsentProvider 是 30s），
     * 握手结束后清零——传输期间同一 socket 上会长时间等对端收完，不能有读超时。
     */
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 45000;

    protected final String serverControllerAddress;
    protected final int serverPort;
    protected final String homeDir;
    protected boolean isRun = true;
    protected ClientCallBack callBack;

    /** 握手时发给服务端，用于对方弹“是否允许 xxx 连接” */
    private String clientName = "HFXClient";
    /** 服务端在握手中返回的名字，用于本机权限弹窗 */
    private String serverName = "";
    /** 服务端在握手中自报的局域网 IP，控制通道为回环地址时用于界面显示 */
    private String serverReportedAddress = "";

    /** 本次连接会话内已选择“本次允许”的方向 */
    private volatile boolean sessionWriteAllowed;
    private volatile boolean sessionReadAllowed;
    private volatile boolean sessionDeleteAllowed;

    /**
     * USB_ADB 开关：调用方确认本次连接的 adb forward/reverse 指向目标后才置 true。
     * 默认 false：USB_ADB 的 want 置 0；true：USB_ADB 按 connectionsFor() 的固定 2 条打开
     * （USB 的条数是硬约束，不随服务端下发的无线连接数变化）。
     */
    private volatile boolean allowUsbAdb = false;

    public HFXClient(String serverControllerAddress, int serverPort,String homeDir) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
        this.homeDir = homeDir;
    }

    public void setClientName(String name) {
        if (name == null) {
            return;
        }
        String v = name.replace('\r', ' ').replace('\n', ' ').trim();
        if (v.length() > 64) {
            v = v.substring(0, 64);
        }
        if (!v.isEmpty()) {
            this.clientName = v;
        }
    }

    public String getClientName() {
        return clientName;
    }

    /** 允许 USB_ADB 数据通道参与本次连接。 */
    public void setAllowUsbAdb(boolean allow) {
        this.allowUsbAdb = allow;
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerControllerAddress() {
        return serverControllerAddress;
    }

    /** 弹窗显示用：控制通道是 127.0.0.1 时改用对方自报的局域网 IP */
    public String getServerControllerDisplayAddress() {
        if (serverControllerAddress != null && !isLoopbackAddress(serverControllerAddress)) {
            return serverControllerAddress;
        }
        if (serverReportedAddress != null && !serverReportedAddress.isEmpty()) {
            return serverReportedAddress;
        }
        return serverControllerAddress;
    }

    private void resetConsentState() {
        sessionWriteAllowed = false;
        sessionReadAllowed = false;
        sessionDeleteAllowed = false;
    }

    public boolean connect(ConnectServerCallback callback) throws IOException {
        resetConsentState();
        // 服务端下发的无线连接数；连接失败时用默认值兜底，正常路径不会走到这里
        int serverWireless = HFXService.CONNECTIONS_WIRELESS_DEFAULT;
        SocketChannel controlSocket;
        try {
            //System.out.println("正在连接控制通道：" + serverControllerAddress);
            callback.onConnectingControlChannel(serverControllerAddress, serverPort);
            controlSocket = openControlChannel();
            ctChannel = new DataByteChannel(controlSocket);

            ctChannel.write(CLIENT_HEADER.getBytes(StandardCharsets.UTF_8));
            ctChannel.writeInt(VERSION_CODE);
            ctChannel.writeUTF(clientName);
            // 控制通道可能经 ADB 走 127.0.0.1，附带本机局域网 IP 供对方弹窗显示
            ctChannel.writeUTF(localLanIpv4());
            if (!ctChannel.readBoolean()) {
                //System.out.println("版本不一致，你的版本：" + VERSION_CODE + "，对方版本：" + ctChannel.readInt());
                callback.onVersionMismatch(VERSION_CODE, ctChannel.readInt());
                ctChannel.close();
                return false;
            }
            // 已发送本机名字，等待服务端用户确认连接
            callback.onAwaitingPeerConfirmation();
            if (!ctChannel.readBoolean()) {
                callback.onConnectionDenied();
                ctChannel.close();
                return false;
            }
            serverName = ctChannel.readUTF();
            String reportedAddress = ctChannel.readUTF();
            serverReportedAddress = reportedAddress == null ? "" : reportedAddress;
            // 320: 服务端下发的无线连接数。发起传输的是服务端，条数以它为准，
            // 这样手机上的设置才能真正约束客户端开几条流。
            serverWireless = ctChannel.readInt();
        } catch (IOException e) {
            //System.out.println("控制通道连接到手机失败，请检查手机的服务端是否启动？");
            callback.onConnectControlFailed();
            return false;
        }
        int ipCount = ctChannel.readInt();
        String[] names = new String[ipCount];
        InetAddress[] addresses = new InetAddress[ipCount];
        InetAddress[] bindAddresses = new InetAddress[ipCount];

        for (int i = 0; i < ipCount; i++) {
            String name = ctChannel.readUTF();
            byte[] address = new byte[ctChannel.readByte()];
            ctChannel.readFully(address);
            InetAddress inetAddress = InetAddress.getByAddress(address);
            byte l46 = ctChannel.readByte();
            InetAddress bindAddress = null;
            if (l46 != 0) {
                byte[] bAddress = new byte[l46];
                ctChannel.readFully(bAddress);
                bindAddress = InetAddress.getByAddress(bAddress);
            }
            names[i] = name;
            addresses[i] = inetAddress;
            bindAddresses[i] = bindAddress;
        }
        // 先按 1s 超时逐张网卡试连：连不上就整张跳过，不让一个不可达网卡拖垮整次连接。
        // USB_ADB 不再按“控制通道是否回环”猜测，由 allowUsbAdb 显式开关控制：
        // 只有本次连接确认了 adb 转发指向目标设备才参与，失败也不拖垮其它网卡。
        List<List<TransferConnection>> opened = new ArrayList<>(ipCount);
        int totalConnections = 0;
        for (int i = 0; i < ipCount; i++) {
            int want = connectionsFor(names[i], serverWireless);
            if (!allowUsbAdb && names[i].startsWith("USB_ADB")) {
                want = 0;
            }
            List<TransferConnection> list = openChannels(names[i], addresses[i], bindAddresses[i],
                    want, callback);
            opened.add(list);
            totalConnections += list.size();
        }
        for (int i = 0; i < ipCount; i++) {
            ctChannel.writeInt(opened.get(i).size());
        }
        if (totalConnections == 0) {
            callback.onNoTransferChannel();
            ctChannel.close();
            return false;
        }

        connections = new ArrayList<>(totalConnections);
        for (List<TransferConnection> list : opened) {
            for (TransferConnection connection : list) {
                ctChannel.writeBoolean(true);
                ctChannel.writeUTF(connection.iName);
                ctChannel.readBoolean();
                connections.add(connection);
            }
        }
        //初始化缓冲区块
        int bufferCount = ctChannel.readInt();
        for (int i = 0; i < bufferCount; i++) {
            ByteBuffer buffer = createBuffer(FileBlock.BLOCK_SIZE);
            if (buffer != null){
                buffers.add(buffer);
            } else {
                String arch = System.getProperty("os.arch");
                long availableMemoryMB = getAvailableMemoryMB();
                /*System.out.println("内存不足，创建缓冲区块失败！请尝试调小缓冲区块数（1MB每块）。成功创建" + i + "块，需要"
                        + bufferCount + "块。当前JVM最大内存：" + maxMemoryMB + "MB");
                if (arch != null && !arch.contains("64")) {
                    System.out.println("检测你正在使用32位Java，内存受限，建议使用64位Java");
                }*/
                freeBuffers();
                callback.onOOM(i, bufferCount, availableMemoryMB, arch);
                ctChannel.writeBoolean(false);
                return false;
            }
        }
        ctChannel.writeBoolean(true);
        if (!ctChannel.readBoolean()) {
            //System.out.println("连接失败，手机端内存不足，请调小缓存区块数");
            callback.onRemoteOOM();
            return false;
        }
        //返回文件系统信息给对方
        ctChannel.writeInt(Directory.getCurrentFileSystem());
        //返回主路径信息给对方
        ctChannel.writeUTF(homeDir);
        //System.out.println("传输通道已全部连接完成");
        List<String> channelNames = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            channelNames.add(connection.iName);
        }
        // 握手全部走完，撤掉读超时：后面这条 socket 上要一直挂着等传输完成
        try {
            controlSocket.socket().setSoTimeout(0);
        } catch (IOException ignored) {
        }
        callback.onConnectSuccess(channelNames);
        return true;
    }

    /** 控制通道：5s 建连超时 + TCP_NODELAY/keepalive + 握手阶段的读超时。 */
    private SocketChannel openControlChannel() throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        try {
            socketChannel.socket().setTcpNoDelay(true);
            socketChannel.socket().setKeepAlive(true);
            socketChannel.socket().setSoTimeout(HANDSHAKE_READ_TIMEOUT_MS);
            socketChannel.socket().connect(
                    new InetSocketAddress(serverControllerAddress, serverPort),
                    CONTROL_CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            try {
                socketChannel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return socketChannel;
    }

    /** 按 1s 超时试连一张网卡；第一条失败即放弃该网卡剩余通道。 */
    private List<TransferConnection> openChannels(String name, InetAddress address,
                                                  InetAddress bindAddress, int want,
                                                  ConnectServerCallback callback) {
        List<TransferConnection> out = new ArrayList<>();
        for (int j = 0; j < want; j++) {
            String connName = j == 0 ? name : name + "#" + (j + 1);
            SocketChannel socketChannel = null;
            try {
                if (j == 0) {
                    callback.onConnectingTransferChannel(name, address, bindAddress);
                }
                socketChannel = SocketChannel.open();
                if (bindAddress != null) {
                    socketChannel.bind(new InetSocketAddress(bindAddress, 0));
                }
                socketChannel.socket().connect(
                        new InetSocketAddress(address, serverPort), DATA_CHANNEL_TIMEOUT_MS);
                out.add(new TransferConnection(connName, socketChannel));
            } catch (IOException e) {
                if (socketChannel != null) {
                    try {
                        socketChannel.close();
                    } catch (IOException ignored) {
                    }
                }
                break;
            }
        }
        return out;
    }

    /** 本机第一个可用的局域网 IPv4；拿不到返回空串。优先无线/有线网卡。 */
    public static String localLanIpv4() {
        String fallback = "";
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return "";
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String name = networkInterface.getName();
                name = name == null ? "" : name.toLowerCase();
                for (java.util.Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                     addresses.hasMoreElements(); ) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
                            || name.startsWith("en") || name.startsWith("wi-fi")) {
                        return ip;
                    }
                    if (fallback.isEmpty()) {
                        fallback = ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static boolean isLoopbackAddress(String address) {
        if (address == null) {
            return false;
        }
        String value = address.trim().toLowerCase();
        return value.equals("localhost")
                || value.startsWith("127.")
                || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1");
    }

    public abstract ByteBuffer createBuffer(int size);

    public abstract long getAvailableMemoryMB();

    public void start(ClientCallBack transferFileCallback) throws Exception {
        this.callBack = transferFileCallback;
        Log.i(TAG, "client loop start addr=" + serverControllerAddress + " port=" + serverPort);
        try {
            loop();
            Log.i(TAG, "client loop returned normally");
        } catch (IOException e) {
            if (isRun) {
                // 控制通道被对端关闭 / adb 隧道重置：统一收口，不能把异常抛到
                // 外层线程变成未捕获栈，也不要把 UI 留在“已连接”状态。
                Log.i(TAG, "client loop IOException: " + e);
                System.out.println("控制通道已断开：" + e);
                close();
                callBack.onExit();
            }
        } catch (Throwable e) {
            Log.i(TAG, "client loop Throwable: " + e);
            throw e;
        }
    }

    private void loop() throws Exception {
        //LOOP
        while (isRun) {
            short id = ctChannel.readShort();
            SessionIdleTimer.markActivity();
            if (id != ControllerIdentifiers.LIST_FILES) {
                Log.i(TAG, "client cmd 0x" + Integer.toHexString(id & 0xFFFF));
            }
            switch (id) {
                case ControllerIdentifiers.LIST_FILES:
                    handleListFiles();
                    break;
                case ControllerIdentifiers.DELETE_FILE:
                    handleDeleteFiles();
                    break;
                case ControllerIdentifiers.MKDIR:
                    handleMkdir();
                    break;
                case ControllerIdentifiers.REQUEST_RECEIVE:
                    SessionIdleTimer.setTransferring(true);
                    try {
                        handleReceiveFiles();
                    } finally {
                        SessionIdleTimer.setTransferring(false);
                    }
                    break;
                case ControllerIdentifiers.REQUEST_SEND:
                    SessionIdleTimer.setTransferring(true);
                    try {
                        handleSendFiles();
                    } finally {
                        SessionIdleTimer.setTransferring(false);
                    }
                    break;
                case ControllerIdentifiers.SHUTDOWN:
                    handleShutdown();
                    break;
                default:
                    // 控制通道只会写出已定义的指令，读到其它值说明已经错位，继续读只会变成双向静默死锁
                    Log.i(TAG, "client unknown cmd: id=" + id
                            + " (0x" + Integer.toHexString(id & 0xFFFF) + ")");
                    System.out.println("未知的控制指令，控制通道错位：id=" + id
                            + " (0x" + Integer.toHexString(id & 0xFFFF) + ")");
                    close();
                    break;
            }
        }
    }


    /**
     * 批量删除：先收清单，再问一次授权（弹窗里就是这份清单），最后逐项回执。
     * <p>
     * 旧实现是“一条 DELETE_FILE 带一个路径、直接删”，对方没有任何确认机会。
     */
    private void handleDeleteFiles() throws Exception {
        List<String> paths = readPaths();
        int consent = requestDataConsent(ConsentProvider.OP_DELETE, paths);
        ctChannel.writeByte(consent);
        if (consent == ConsentProvider.CONSENT_DENY) {
            callBack.onDeleteDenied();
            return;
        }
        for (String path : paths) {
            try {
                ctChannel.writeBoolean(deleteLocalFile(path));
            } catch (Exception e) {
                // 单个删除失败不能把控制通道留在半同步状态：写完回执再继续
                ctChannel.writeBoolean(false);
            }
        }
    }

    protected abstract boolean deleteLocalFile(String path) throws Exception;
    /*public boolean deleteLocalFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }*/

    private void handleMkdir() throws Exception {
        String parent = ctChannel.readUTF();
        String child = ctChannel.readUTF();
        ctChannel.writeBoolean(mkdir(parent, child));
    }

    protected abstract boolean mkdir(String parent, String child) throws Exception;

    private void handleShutdown() {
        close();
        callBack.onExit();
        //System.out.println("收到停止指令，客户端已正常关闭！");
    }

    /**
     * 主动断开：关闭控制/传输通道，让阻塞在 start() 里的读线程退出。
     * 幂等，可从任意线程调用。
     */
    public void close() {
        isRun = false;
        DataByteChannel ct = ctChannel;
        if (ct != null) {
            try {
                ct.close();
            } catch (IOException ignored) {
            }
        }
        if (connections != null) {
            for (TransferConnection transferConnection : connections) {
                try {
                    transferConnection.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleListFiles() throws Exception {
        String path = ctChannel.readUTF();
        // 列目录不再要授权：浏览只是看，真正的读/写/删各自会单独问一次。
        // 只回一个“本机是否受存储权限限制”的布尔，对端据此提示“部分文件不可见”。
        ctChannel.writeBoolean(isLocalAccessLimited());
        //System.out.println(path);
        if (!path.equals("/")) {
            List<RemoteFile> files = listFiles(path);
            if (files != null) {
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {
                ctChannel.writeInt(-1);
            }
        } else {
            File[] roots = File.listRoots();
            //判断是否是Linux的目录结构，Windows的根目录是C:\\，而不是所有盘符，Linux的根目录是“/”没有盘符概念
            if (roots.length == 1 && roots[0].getAbsolutePath().equals("/")) {
                List<RemoteFile> files = listFiles(roots[0].getPath());
                if (files == null) {
                    ctChannel.writeInt(-1);
                    return;
                }
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {//Windows的
                ctChannel.writeInt(roots.length);
                for (File file : roots) {
                    ctChannel.writeUTF(file.getPath());//不要getName，否则空白
                    ctChannel.writeUTF(file.getPath());
                    ctChannel.writeLong(file.lastModified());
                    ctChannel.writeLong(file.length());
                    ctChannel.writeBoolean(file.isDirectory());
                }
            }
        }
        //已弃用ObjectOutputStream
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
    }

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    /**
     * 本机列举目录是否受存储权限限制（看不到常规文件，只能看到文件夹）。
     * 只用于给对端一句非阻塞提示，不影响协议能否继续。
     */
    protected abstract boolean isLocalAccessLimited();

    private void writeFile(RemoteFile file) throws IOException {
        ctChannel.writeUTF(file.getName());
        ctChannel.writeUTF(file.getPath());
        ctChannel.writeLong(file.lastModified());
        ctChannel.writeLong(file.getSize());
        ctChannel.writeBoolean(file.isDirectory());
    }

    private void handleReceiveFiles() throws IOException {
        // 清单在授权之前到：弹窗要能列出“对方要写入哪几个文件/目录”。
        List<String> paths = readPaths();
        int consent = requestDataConsent(ConsentProvider.OP_WRITE, paths);
        ctChannel.writeByte(consent);
        if (consent == ConsentProvider.CONSENT_DENY) {
            callBack.onPermissionDenied(true);
            return;
        }
        callBack.onReceiving();
        isRun = receiveFiles(callBack);
    }

    private void handleSendFiles() throws IOException {
        // 同样先收清单再问授权：对方拉取哪些文件，授权弹窗里逐条列出来。
        List<String> paths = readPaths();
        Directory remoteDir = new Directory(ctChannel.readUTF(), ctChannel.readInt());//对方的localDir
        Directory localDir = new Directory(ctChannel.readUTF(), Directory.getCurrentFileSystem());//对方为remoteDir
        int consent = requestDataConsent(ConsentProvider.OP_READ, paths);
        ctChannel.writeByte(consent);
        if (consent == ConsentProvider.CONSENT_DENY) {
            callBack.onPermissionDenied(false);
            return;
        }
        List<RemoteFile> fileList = new ArrayList<>(paths.size());
        for (String path : paths) {
            fileList.add(new RemoteFile(new File(path)));
        }
        callBack.onSending();
        isRun = sendFiles(fileList, localDir, remoteDir, callBack);
    }

    /** 命中“本次允许”则不再弹窗；否则交给 UI 阻塞等待，最长 30s。 */
    private int requestDataConsent(int operation, List<String> paths) {
        if (operation == ConsentProvider.OP_WRITE && sessionWriteAllowed) {
            return ConsentProvider.CONSENT_ONCE;
        }
        if (operation == ConsentProvider.OP_READ && sessionReadAllowed) {
            return ConsentProvider.CONSENT_ONCE;
        }
        if (operation == ConsentProvider.OP_DELETE && sessionDeleteAllowed) {
            return ConsentProvider.CONSENT_ONCE;
        }
        int consent = consentProvider.requestData(serverName, getServerControllerDisplayAddress(),
                operation, paths, ConsentProvider.DEFAULT_TIMEOUT_MS);
        if (consent == ConsentProvider.CONSENT_SESSION) {
            if (operation == ConsentProvider.OP_WRITE) {
                sessionWriteAllowed = true;
            } else if (operation == ConsentProvider.OP_DELETE) {
                sessionDeleteAllowed = true;
            } else {
                sessionReadAllowed = true;
            }
            return consent;
        }
        if (consent == ConsentProvider.CONSENT_ONCE) {
            return consent;
        }
        return ConsentProvider.CONSENT_DENY;
    }

    protected void freeBuffers(){
        buffers.clear();
    }

}
