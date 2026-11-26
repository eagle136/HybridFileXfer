package hfx.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import hfx.core.Directory;
import hfx.core.RemoteFile;
import hfx.core.TransferFileCallback;
import hfx.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    /**
     * 302: 每个物理网卡开多条 TCP 连接（原来是一张网卡一条）。
     * <p>
     * 实测（同一台机、同一条 wlan0、裸 TCP 无应用层协议）：
     * <pre>
     *   1 条流  156.6 MB/s
     *   2 条流  202.3 MB/s
     *   4 条流  233.7 MB/s
     *   8 条流  277.3 MB/s
     * </pre>
     * 单条 TCP 流受 cwnd 与空口调度限制，喂不满 WiFi 的多队列/A-MPDU 聚合能力，
     * 一张网卡一条连接等于把链路潜力浪费掉三分之一以上。
     * <p>
     * 303: 连接数从"所有网卡同一个倍数"改为"逐张网卡下发"——WiFi 8 条、USB_ADB 1 条。
     * 握手里的 int 由单值变成每张网卡一个，字节布局变了，故版本号必须同步。
     * <p>
     * 310: 3.1 版传输层优化——块大小 1MB→2MB、发送端改 sendfile 零拷贝、
     * 块头 7 次 write 合并为 1 次。数据块布局与 303 一致，版本号随应用版本对齐。
     * <p>
     * 311: 握手增加客户端名字/服务端名字/连接确认；传输命令前增加读写授权应答。
     * 字节流与 310 不兼容。
     * <p>
     * 312: 传输结束后增加“已收块集合/缺失块补发”握手；任一副通道中途断开时，
     * 接收端上报实收块，发送端只在存活通道上补发缺口，避免静默截断。
     * 字节流与 311 不兼容。
     * <p>
     * 314: LIST_FILES 应答前增加一个授权布尔（浏览目录也要本机确认）。
     * 应答字节流与 313 不兼容。
     * <p>
     * 320: 传输层 4MB 块改造——块大小 2MB→4MB（落盘偏移 = index * BLOCK_SIZE，
     * 收发两端必须是同一个块大小，否则文件会被静默写花）；接收缓冲池默认降到 20 块；
     * 握手在服务端名字/局域网 IP 之后新增一个 int：服务端下发的无线连接数。
     * 字节流与 314 不兼容。
     * <p>
     * 321: 授权粒度改造——LIST_FILES 不再要授权（改为回一个"本机是否受限"布尔）；
     * REQUEST_RECEIVE/REQUEST_SEND 的文件清单提前到授权之前下发，授权弹窗才能列出
     * 具体路径；DELETE_FILE 由单路径改成批量清单并在执行前授权。
     * 字节流与 320 不兼容。
     * <p>
     * 330: 3.2 版——版本号随应用版本对齐（权限验证、传输开销优化、安卓端互传
     * 自动扫描、传输开始自动切无线通道）。按不兼容处理，双端需同时升级。
     * 字节流与 321 不兼容。
     */
    public static final int VERSION_CODE = 330;

    /** 单次传输最多补发几轮；仍缺块则按失败处理，避免无限重试。 */
    private static final int MAX_RESEND_ROUNDS = 4;

    /** 失败路径上等传输线程退场的上限；超时后靠关闭 socket 把线程踢出来。 */
    private static final long TASK_JOIN_TIMEOUT_MS = 1000L;

    /** 正在跑的传输任务数。断开/FREE 缓冲前要等它归零，否则会释放还在用的 native 直连内存。 */
    private final AtomicInteger activeTransfers = new AtomicInteger();

    /** 是否还有传输在跑（Android 端释放缓冲池前必须为 false）。 */
    public boolean isTransferring() {
        return activeTransfers.get() > 0;
    }

    /** USB_ADB 固定 2 条，硬约束：不读配置、不接受对端下发。 */
    public static final int CONNECTIONS_USB_ADB = 2;
    /** 未识别网卡的兜底连接数。 */
    public static final int CONNECTIONS_FALLBACK = 4;
    /** 无线连接数的默认值与上下限；默认 3，可在服务端设置里改。 */
    public static final int CONNECTIONS_WIRELESS_DEFAULT = 3;
    public static final int CONNECTIONS_WIRELESS_MIN = 1;
    public static final int CONNECTIONS_WIRELESS_MAX = 8;

    /**
     * 每张网卡开几条 TCP 连接。
     * <p>
     * USB_ADB 固定 2 条，是<b>硬约束</b>：不读配置、不接受对端下发。adb forward 隧道
     * 手机侧是 USB 2.0（40MB/s 封顶），单流 35.5、6 流 40.8，2 条已经贴着物理上限，
     * 再多只是白养隧道线程。
     * <p>
     * 无线（wlan/ap/eth）条数由<b>服务端</b>在握手中下发：默认 3，可在手机设置里改
     * （{@code Config.getWirelessConnections()}，范围 1~8）。为什么下发而不是客户端本地
     * 配置：本项目主链路是“手机当服务端 + PC 当客户端”，发起传输的是服务端，而 PC 端
     * 没有配置文件——只管在客户端的话，手机上的设置对 PC 连接毫无作用。
     * <p>
     * 条数不能太少：WiFi 上单流受 cwnd 和空口调度限制，加流有效但存在拐点。
     * 实测（同一台机、同一条 wlan0、裸 TCP、无应用层协议）1/2/4/8 流 = 156/202/233/277 MB/s。
     * 3 流大致落在 200~220MB/s，要峰值就在设置里调到 6。
     * <p>
     * 逐张网卡的<b>实际</b>连接数仍由客户端在第二批握手中回报，服务端据此 accept。
     *
     * @param iName    通道（网卡）名
     * @param wireless 服务端下发的无线连接数；对 USB_ADB 与未识别网卡无效
     */
    public static int connectionsFor(String iName, int wireless) {
        if (iName.startsWith("USB_ADB")) {
            return CONNECTIONS_USB_ADB;
        }
        if (iName.startsWith("wlan") || iName.startsWith("ap") || iName.startsWith("eth")) {
            return Math.max(CONNECTIONS_WIRELESS_MIN,
                    Math.min(CONNECTIONS_WIRELESS_MAX, wireless));
        }
        return CONNECTIONS_FALLBACK;
    }
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;

    /** 连接确认/数据授权；CLI 默认自动放行，GUI/Activity 覆盖。 */
    protected volatile ConsentProvider consentProvider = ConsentProvider.AUTO_ALLOW;

    public void setConsentProvider(ConsentProvider consentProvider) {
        this.consentProvider = consentProvider == null
                ? ConsentProvider.AUTO_ALLOW : consentProvider;
    }

    /**
     * 下发本次操作的文件清单：条数 + 逐条 UTF 路径。
     * <p>
     * 清单是功能性数据（对方按它决定读哪些文件），所以这里绝不截断；
     * 条数只受 {@link ConsentProvider#MAX_PATHS} 的对端校验约束。
     */
    protected void writePaths(List<String> paths) throws IOException {
        int count = paths == null ? 0 : paths.size();
        if (count > ConsentProvider.MAX_PATHS) {
            throw new IOException("文件清单过长: " + count);
        }
        ctChannel.writeInt(count);
        for (int i = 0; i < count; i++) {
            ctChannel.writeUTF(paths.get(i) == null ? "" : paths.get(i));
        }
    }

    /** 读对方下发的文件清单；长度异常直接抛，避免带着错位继续跑。 */
    protected List<String> readPaths() throws IOException {
        int count = ctChannel.readInt();
        if (count < 0 || count > ConsentProvider.MAX_PATHS) {
            throw new IOException("文件清单长度异常，控制通道可能已错位: " + count);
        }
        List<String> paths = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            paths.add(ctChannel.readUTF());
        }
        return paths;
    }

    protected boolean sendFiles(List<RemoteFile> fileList,Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        activeTransfers.incrementAndGet();
        try {
            return sendFilesImpl(fileList, localDir, remoteDir, callback);
        } finally {
            activeTransfers.decrementAndGet();
        }
    }

    private boolean sendFilesImpl(List<RemoteFile> fileList,Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask);
        readThread.setName("FileRead");
        readThread.start();
        //另开一个线程读取传输流量信息，1秒一次；同时负责回收僵死可选通道的在途块
        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, readFileCall);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        long startTime = System.currentTimeMillis();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("UL_" + connection.iName);
            thread.start();
        }

        //其中一条通道断掉，可能控制器通道也一起跟着断了
        boolean complete;
        try {
            //等待客户端接收成功或者写入到硬盘时发生IO错误
            complete = ctChannel.readBoolean();
        } catch (IOException e) {
            speedMonitorThread.cancel();
            readFileCall.abort();
            settleSendTasks(transferTasks);
            callback.onIncomplete();
            return false;
        }

        if (!complete) {
            speedMonitorThread.cancel();
            String errMsg = ctChannel.readUTF();
            callback.onWriteFileError(errMsg);
            readFileCall.shutdownByWriteError();
            // 对端写盘失败并已关连接；这里等发送线程退干净，绝不能让它们跟着到下一次传输
            settleSendTasks(transferTasks);
            return true;
        }

        int lost = 0;
        boolean fatal = false;
        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof OptionalChannelLostException) {
                    lost++;
                } else {
                    fatal = true;
                    break;
                }
            } catch (InterruptedException e) {
                fatal = true;
                break;
            }
        }
        if (fatal || lost == transferTasks.size()) {
            speedMonitorThread.cancel();
            readFileCall.abort();
            settleSendTasks(transferTasks);
            if (lost == transferTasks.size()) {
                // 没有任何存活通道：关控制通道，避免双方停在半同步状态
                try {
                    ctChannel.close();
                } catch (IOException ignored) {
                }
            }
            callback.onIncomplete();
            return false;
        }

        // 接收端上报实收块；缺失块只在它认为存活的通道上补发
        try {
            if (!resendMissingBlocks(readFileCall, callback)) {
                speedMonitorThread.cancel();
                readFileCall.abort();
                settleSendTasks(transferTasks);
                closeConnectionsQuietly();
                try {
                    ctChannel.close();
                } catch (IOException ignored) {
                }
                callback.onIncomplete();
                return false;
            }
        } catch (IOException e) {
            speedMonitorThread.cancel();
            readFileCall.abort();
            settleSendTasks(transferTasks);
            closeConnectionsQuietly();
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
            callback.onIncomplete();
            return false;
        }

        speedMonitorThread.cancel();
        long totalUploadTraffic = 0;
        for (TransferConnection connection : connections) {
            totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
        }

        try {
            // 核对结束后接收端会写一个收尾布尔，必须先读掉，它滞留在通道上会让下一次控制请求整体错位一字节
            ctChannel.readBoolean();
            readFileTask.get();
            ctChannel.writeBoolean(true);
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeBoolean(false);
            ctChannel.writeUTF(ex);
            callback.onReadFileError(ex);
            return true;
        }

        callback.onComplete(true,totalUploadTraffic, System.currentTimeMillis() - startTime);
        return true;
    }

    /**
     * 与接收端核对已收块，并把缺口在“接收端仍认为存活”的通道上补发。
     * 接收端每次报一个全量已收集合和存活通道名；补到没有缺口为止。
     *
     * @return true=已补齐（或本来就不缺）；false=补不齐，调用方按失败处理
     */
    private boolean resendMissingBlocks(ReadFileCall readFileCall, TransferFileCallback callback)
            throws IOException {
        int round = 0;
        while (true) {
            int receivedCount = ctChannel.readInt();
            Set<Long> received = new HashSet<>();
            for (int i = 0; i < receivedCount; i++) {
                int fileIndex = ctChannel.readInt();
                int index = ctChannel.readInt();
                received.add(WriteFileCall.blockKey(fileIndex, index));
            }
            int liveCount = ctChannel.readInt();
            List<TransferConnection> live = new ArrayList<>(liveCount);
            for (int i = 0; i < liveCount; i++) {
                TransferConnection connection = findConnection(ctChannel.readUTF());
                if (connection != null) {
                    live.add(connection);
                }
            }

            List<FileBlock> missing = new ArrayList<>();
            for (FileBlock block : readFileCall.getDataBlocks()) {
                if (!received.contains(WriteFileCall.blockKey(block.fileIndex, block.index))) {
                    missing.add(block);
                }
            }
            ctChannel.writeInt(missing.size());
            for (FileBlock block : missing) {
                ctChannel.writeInt(block.fileIndex);
                ctChannel.writeInt(block.index);
            }
            if (missing.isEmpty()) {
                return true;
            }
            if (++round > MAX_RESEND_ROUNDS || live.isEmpty()) {
                return false;
            }

            readFileCall.clearPendingEndPoints();
            readFileCall.enqueueResend(missing);
            readFileCall.startEndPointBarrier(live.size());
            List<FutureTask<Void>> tasks = new ArrayList<>(live.size());
            for (TransferConnection connection : live) {
                FutureTask<Void> task = new FutureTask<>(
                        new SendFileCall(readFileCall, connection, callback, false));
                tasks.add(task);
                Thread thread = new Thread(task);
                thread.setName("RS_" + connection.iName);
                thread.start();
            }
            boolean fatal = false;
            for (FutureTask<Void> task : tasks) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof OptionalChannelLostException)) {
                        fatal = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fatal = true;
                }
            }
            if (fatal) {
                return false;
            }
        }
    }

    private TransferConnection findConnection(String name) {
        for (TransferConnection connection : connections) {
            if (connection.iName.equals(name)) {
                return connection;
            }
        }
        return null;
    }

    private void closeConnectionsQuietly() {
        for (TransferConnection connection : connections) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 等这批传输线程退场，超时就放弃（剩下的一定已经卡在 socket 上，由关连接来收）。 */
    private static void joinQuietly(List<FutureTask<Void>> tasks, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (FutureTask<Void> task : tasks) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                task.get(left, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 发送失败的收尾：先等发送线程退场；还有不退的（卡在 transferTo 上写不出去），
     * 就关掉传输通道把它们逼出来。
     * <p>
     * 不能让这些线程活到下一次传输：同一批 socket 上再起一层发送线程 = 双向交错写，
     * 接收端收到的就是错位的数据流。
     */
    private void settleSendTasks(List<FutureTask<Void>> tasks) {
        joinQuietly(tasks, TASK_JOIN_TIMEOUT_MS);
        for (FutureTask<Void> task : tasks) {
            if (!task.isDone()) {
                closeConnectionsQuietly();
                return;
            }
        }
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        activeTransfers.incrementAndGet();
        try {
            return receiveFilesImpl(callback);
        } finally {
            activeTransfers.decrementAndGet();
        }
    }

    private boolean receiveFilesImpl(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i, connection, writeFileCall, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("DL_" + connection.iName);
            thread.start();
        }
        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread thread = new Thread(writeFileTask);
        thread.setName("FileWrite");
        thread.start();
        try {
            writeFileTask.get();
        } catch (InterruptedException | ExecutionException e) {
            speedMonitorThread.cancel();
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            // 1) 先置取消位：接收线程不再从池里取块去填一个已经死掉的写线程
            writeFileCall.cancel();
            // 2) 先告诉对端“写失败”，再收通道，否则对端一直卡在 sendfile 上
            try {
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF(ex);
            } catch (IOException ignored) {
            }
            // 3) 写失败无法在同一批传输通道上恢复：关掉它们让卡在 read/transferTo 的线程退出，
            //    等这批线程退场后再关控制通道结束会话。
            //    绝不能让旧线程活到下一次 REQUEST_RECEIVE —— 同一批 socket 双读会直接串包。
            closeConnectionsQuietly();
            joinQuietly(transferTasks, TASK_JOIN_TIMEOUT_MS);
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
            callback.onWriteFileError(ex);
            return true;
        }

        int lost = 0;
        boolean fatal = false;
        List<TransferConnection> live = new ArrayList<>(connections);
        for (int i = 0; i < transferTasks.size(); i++) {
            try {
                transferTasks.get(i).get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof OptionalChannelLostException) {
                    lost++;
                    live.remove(connections.get(i));
                } else {
                    fatal = true;
                    break;
                }
            } catch (InterruptedException e) {
                fatal = true;
                break;
            }
        }
        if (fatal) {
            speedMonitorThread.cancel();
            // 接收线程发生致命错误：这里不再写“写线程正常”的布尔。
            // 发送端此刻在 sendFiles 里等这个布尔，收到后还会进补发握手等 receivedCount，
            // 而本方法会直接返回、不再发收到集合，双方会永久卡在控制通道上。
            // 直接关控制通道，发送端以 IOException 收口走 onIncomplete。
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
            callback.onIncomplete();
            return false;
        }
        if (lost == transferTasks.size()) {
            speedMonitorThread.cancel();
            //没有任何存活通道：关控制通道，避免双方停在半同步状态
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
            callback.onIncomplete();
            return false;
        }
        // 告诉对方“本机写盘线程正常”，随后进入缺失块核对
        ctChannel.writeBoolean(true);

        Set<Long> received = new HashSet<>(writeFileCall.getReceivedBlocks());
        int round = 0;
        while (true) {
            ctChannel.writeInt(received.size());
            for (Long key : received) {
                ctChannel.writeInt((int) (key >> 32));
                ctChannel.writeInt((int) (long) key);
            }
            ctChannel.writeInt(live.size());
            for (TransferConnection connection : live) {
                ctChannel.writeUTF(connection.iName);
            }
            int missingCount = ctChannel.readInt();
            if (missingCount == 0) {
                break;
            }
            // 缺失清单本身不用处理，读掉保持控制流同步
            for (int i = 0; i < missingCount; i++) {
                ctChannel.readInt();
                ctChannel.readInt();
            }
            if (round >= MAX_RESEND_ROUNDS || live.isEmpty()) {
                speedMonitorThread.cancel();
                try {
                    ctChannel.close();
                } catch (IOException ignored) {
                }
                callback.onIncomplete();
                return false;
            }
            round++;

            WriteFileCall patchWriteCall = createWriteFileCall(buffers, live.size());
            List<FutureTask<Void>> patchTasks = new ArrayList<>(live.size());
            for (int i = 0; i < live.size(); i++) {
                FutureTask<Void> task = new FutureTask<>(
                        new ReceiveFileCall(i, live.get(i), patchWriteCall, callback, false));
                patchTasks.add(task);
                Thread taskThread = new Thread(task);
                taskThread.setName("RSDL_" + live.get(i).iName);
                taskThread.start();
            }
            FutureTask<Void> patchWriter = new FutureTask<>(patchWriteCall);
            Thread writerThread = new Thread(patchWriter);
            writerThread.setName("FileWriteRS");
            writerThread.start();
            try {
                patchWriter.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                Throwable cause = e.getCause();
                String ex = cause != null ? cause.toString() : e.toString();
                // 补发轮写失败和对端已经不在同步点上，不能往控制通道发任何东西：
                // 先取消写线程、关掉这批传输通道并等线程退场，再关控制通道让对方按断开收口。
                patchWriteCall.cancel();
                for (TransferConnection connection : live) {
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                    }
                }
                joinQuietly(patchTasks, TASK_JOIN_TIMEOUT_MS);
                callback.onWriteFileError(ex);
                try {
                    ctChannel.close();
                } catch (IOException ignored) {
                }
                return true;
            }
            boolean patchFatal = false;
            for (int i = patchTasks.size() - 1; i >= 0; i--) {
                try {
                    patchTasks.get(i).get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof OptionalChannelLostException) {
                        live.remove(i);
                    } else {
                        patchFatal = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    patchFatal = true;
                }
            }
            if (patchFatal) {
                speedMonitorThread.cancel();
                try {
                    ctChannel.close();
                } catch (IOException ignored) {
                }
                callback.onIncomplete();
                return false;
            }
            received.addAll(patchWriteCall.getReceivedBlocks());
        }

        speedMonitorThread.cancel();
        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(false,totalDownloadTraffic, System.currentTimeMillis() - startTime);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
        return true;
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount);

}