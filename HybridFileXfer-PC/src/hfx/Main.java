/**
 * This program is free software: redistribute it and/or modify it under GPLv3+.
 * See <https://www.gnu.org/licenses/> for full license details.
 */
package hfx;

import hfx.core.Utils;
import hfx.core.TrafficInfo;
import hfx.core.ClientCallBack;
import hfx.core.ConnectServerCallback;
import hfx.core.TransferFileCallback;
import hfx.jdk.JdkHFXClient;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import hfx.core.Discovery;
import hfx.PcWindow;

public class Main {
    private static final ConnectServerCallback connectServerCallback = new ConnectServerCallback() {
        @Override
        public void onConnectingControlChannel(String address, int port) {
            Strings.printf("connecting_control_channel", address);
        }

        @Override
        public void onVersionMismatch(int localVersion, int remoteVersion) {
            Strings.printf("version_mismatch", localVersion, remoteVersion);
        }

        @Override
        public void onConnectControlFailed() {
            Strings.printf("connect_control_failed");
        }

        @Override
        public void onAwaitingPeerConfirmation() {
            Strings.printf("waiting_peer_confirmation");
        }

        @Override
        public void onConnectionDenied() {
            Strings.printf("peer_denied_connection");
        }

        @Override
        public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
            Strings.printf("connecting_transfer_channel", name, inetAddress.getHostAddress(),
                    bindAddress == null ? "null" : bindAddress.getHostAddress());
        }

        @Override
        public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
            Strings.printf("connect_transfer_failed", name, inetAddress.getHostAddress(), e.toString());
        }

        @Override
        public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
            Strings.printf("out_of_memory", createdBuffers, requiredBuffers, maxMemoryMB);
            if (osArch != null && !osArch.contains("64")) {
                Strings.printf("java_32bit_warning");
            }
        }

        @Override
        public void onRemoteOOM() {
            Strings.printf("remote_out_of_memory");
        }

        @Override
        public void onConnectSuccess(List<String> channelNames) {
            Strings.printf("connect_success");
        }
    };

    private static final ClientCallBack clientCallBack = new ClientCallBack() {
        /**
         * 按物理网卡累计字节数。单通道各打印一行看不出"某张网卡到底贡献了多少"，
         * 尤其 USB_ADB 走的是 adb 隧道、不经过以太网卡，网卡监控里根本看不到它，
         * 只能靠这里汇总对账。
         */
        private final Map<String, Long> ifaceBytes = new LinkedHashMap<>();

        /** 去掉 "#2" 这类分连接后缀，还原成物理网卡名 */
        private String interfaceOf(String iName) {
            int i = iName.indexOf('#');
            return i < 0 ? iName : iName.substring(0, i);
        }

        @Override
        public void onReceiving() {
            synchronized (ifaceBytes) {
                ifaceBytes.clear();
            }
            Strings.printf("receiving_files");
        }

        @Override
        public void onSending() {
            synchronized (ifaceBytes) {
                ifaceBytes.clear();
            }
            Strings.printf("sending_files");
        }

        @Override
        public void onExit() {
            Strings.printf("client_exit");
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {

        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {

        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
            //每秒打印一次各通道速率。多通道聚合时，"总速度上不去"到底是某条通道被链路限死，
            //还是所有通道都没跑满，靠这一行就能直接看出来。
            StringBuilder sb = new StringBuilder("[实时] ");
            for (TrafficInfo info : trafficInfoList) {
                sb.append(info.iName)
                        .append(" ▲").append(Utils.formatSpeed(info.uploadTraffic))
                        .append(" ▼").append(Utils.formatSpeed(info.downloadTraffic))
                        .append("   ");
            }
            System.out.println(sb);
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            Strings.printf("channel_complete", iName, time == 0 ? "∞" : Utils.formatSpeed(traffic / time * 1000));
            String iface = interfaceOf(iName);
            synchronized (ifaceBytes) {
                Long old = ifaceBytes.get(iface);
                ifaceBytes.put(iface, (old == null ? 0 : old) + traffic);
            }
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            switch (errorType) {
                case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                    Strings.printf("channel_error_exception", iName, message);
                    break;
                case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                    Strings.printf("channel_error_interrupt", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                    Strings.printf("channel_error_read", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                    Strings.printf("channel_error_write", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_CHANNEL_LOST:
                    Strings.printf("channel_error_lost", iName);
                    break;
            }
        }

        @Override
        public void onPermissionDenied(boolean isWrite) {
            Strings.printf(isWrite ? "denied_write_request" : "denied_read_request");
        }

        @Override
        public void onReadFileError(String message) {
            Strings.printf("file_read_error", message);
        }

        @Override
        public void onWriteFileError(String message) {
            Strings.printf("file_write_error", message);
        }

        /**
         * 传输结束后按物理网卡汇总一行。用法：
         * 拿这一行和网卡监控对比 —— 某张网卡"应用侧算出来的"明显低于监控值，
         * 说明它的连接没被喂饱；监控里完全没有的（USB_ADB），只能看这一行。
         */
        private void printIfaceSummary(long timeMs) {
            if (timeMs <= 0) return;
            StringBuilder sb = new StringBuilder("[网卡汇总] ");
            long total = 0;
            synchronized (ifaceBytes) {
                for (Map.Entry<String, Long> e : ifaceBytes.entrySet()) {
                    long bytes = e.getValue();
                    total += bytes;
                    sb.append(e.getKey())
                            .append(' ').append(Utils.formatSpeed(bytes / timeMs * 1000))
                            .append(" (").append(Utils.formatFileSize(bytes)).append(")   ");
                }
                ifaceBytes.clear();
            }
            System.out.println(sb.append("| 合计 ").append(Utils.formatSpeed(total / timeMs * 1000)));
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            // time 可能为 0（极小文件毫秒级传完），除零会让 CLI 直接抛 ArithmeticException
            long elapsed = time > 0 ? time : 1L;
            if (isUpload) {
                Strings.printf("upload_complete", Utils.formatSpeed(traffic / elapsed * 1000), Utils.formatTime(time), Utils.formatFileSize(traffic));
            } else {
                Strings.printf("download_complete", Utils.formatSpeed(traffic / elapsed * 1000), Utils.formatTime(time), Utils.formatFileSize(traffic));
            }
            printIfaceSummary(time);
        }

        @Override
        public void onIncomplete() {
            Strings.printf("transfer_failed");
        }
    };

    /**主函数*/
    public static void main(String[] args) throws Exception {
        Map<String, String> paramMap = new HashMap<>();
        parseArguments(paramMap, args);

        // 打印版本号
        if (paramMap.containsKey("-v")) {
            Strings.printf("version");
            return;
        }

        // 打印帮助
        if (paramMap.containsKey("-h")) {
            Strings.printf("help_pages");
            return;
        }

        // 解析命令行参数
        final String connect = paramMap.get("-c");
        String homeDirArg = paramMap.get("-d");
        final String homeDir = homeDirArg == null ? "/" : homeDirArg;
        final String device = paramMap.get("-s");
        final boolean headless = GraphicsEnvironment.isHeadless();

        // 不带 -c：打开窗口，等待用户手动扫描/连接（headless 回退帮助）
        if (connect == null || connect.isEmpty()) {
            if (headless) {
                Strings.printf("help_pages");
                return;
            }
            javax.swing.SwingUtilities.invokeLater(
                    () -> new PcWindow(homeDir, null, device).show());
            return;
        }

        if (connect.equals("adb")) {
            // 先重置 adb（杀掉全部 adb.exe 再拉起 server），清掉 USB/无线并存时的多传输态
            final String target = restartAdbAndForward(5740, device);
            if (target == null) {
                return;
            }
            System.out.println(Strings.get("adb_forward_succeed"));
            // 优先把主信道切到可达 LAN；没有则回退 127.0.0.1（拔线会中断）
            String lan = chooseAdbControlAddress(target);
            final String serverAddress = lan != null ? lan : "127.0.0.1";
            Strings.printf("main_channel", lan != null ? "LAN " + lan : "USB_ADB");
            if (lan == null) {
                Strings.printf("adb_fallback");
            }
            JdkHFXClient hfxClient = new JdkHFXClient(serverAddress, 5740, homeDir);
            hfxClient.setClientName(localHostName());
            // forward 刚由本进程对目标设备执行成功，允许 USB_ADB 参与（失败不影响 LAN 通道）
            hfxClient.setAllowUsbAdb(true);
            if (hfxClient.connect(connectServerCallback)) {
                hfxClient.start(clientCallBack);
            }
            return;
        }

        // -c <IP>：打开窗口并直连该 IP（显式指定，不经扫描）
        if (headless) {
            Strings.printf("help_pages");
            return;
        }
        final String targetIp = connect;
        javax.swing.SwingUtilities.invokeLater(
                () -> new PcWindow(homeDir, targetIp, device).show());
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Throwable ignored) {
            return "PC";
        }
    }

    /**
     * 将所有命令行标签转化为 String 键值对
     * @param paramMap 参数键值对
     * @param args 所有命令行参数
     * @author nlsdt 2025-7-15
     */
    private static void parseArguments(Map<String, String> paramMap, String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (arg.startsWith("-")) {


                String value = null;
                String key = arg;

                // 有等号拆为键值对
                if (arg.contains("=")) {
                    final int eqPos = arg.indexOf('=');
                    if (eqPos < arg.length() - 1) {
                        value = arg.substring(eqPos + 1);
                    }
                    key = arg.substring(0, eqPos);
                }   // 无等号获取下一个参数为值，跳过下一个参数
                else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    value = args[i + 1];
                    i++;
                }

                // 长参数短参数转换

                switch (key){
                    case "--connect":       key = "-c"; break;
                    case "--switch-device": key = "-s"; break;
                    case "--dir":           key = "-d"; break;
                    case "--help":          key = "-h"; break;
                    case "--version":       key = "-v"; break;
                    case "-c":
                    case "-s":
                    case "-d":
                    case "-h":
                    case "-v":
                        break;
                    default: // 非法参数处理
                        if (arg.startsWith("-")) {
                            Strings.printf("unknown_option", key);
                        } else {
                            Strings.printf("invalid_argument", key);
                        }
                        Strings.printf("see_help");
                        System.exit(1);
                }
                paramMap.put(key, value);
            }
        }
    }

    public static boolean executeAdbForwardCommand(int port, String device) {        try {
            // 获取当前jar包所在的目录
            String jarDirectory = System.getProperty("user.dir");

            String adbPath = findAdbPath();

            // 执行adb forward命令
            // 旧实现先阻塞读 stderr、读完再读 stdout：adb 只要往 stdout 写满管道缓冲
            // 就会卡住等我们读，而我们正卡在 stderr 上，直接死锁（adb 后台保活时确实会输出）。
            // 这里合并两路输出成一条流读，顺带用进程参数数组避免 adb 路径含空格时被 exec 拆开。
            List<String> command = new ArrayList<>();
            command.add(adbPath);
            if (device != null) {
                command.add("-s");
                command.add(device);
            }
            command.add("forward");
            command.add("tcp:" + port);
            command.add("tcp:" + port);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(jarDirectory));
            builder.redirectErrorStream(true);
            Process forwardProcess = builder.start();
            try {
                // 读取合并后的输出（stderr 已经并进 stdout，不会再出现双流排空死锁）
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(forwardProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("adb:" + line);
                    }
                }
                // 等待命令执行完成
                int forwardExitCode = forwardProcess.waitFor();
                return forwardExitCode == 0;
            } finally {
                if (forwardProcess.isAlive()) {
                    forwardProcess.destroy();
                }
            }

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false; // 执行失败
        }
    }

    /**
     * 点击「ADB 连接」前重置 adb：先结束本机所有 adb.exe，再用随包 adb 重新拉起 server。
     * <p>
     * USB 与无线调试同时在线时，不带 -s 的 adb forward 会直接报
     * “more than one device/emulator”；重启 server 后传输列表回到干净状态
     * （只剩 USB，无线需要重新 adb connect 才会出现），forward 才能落到唯一目标上。
     * 注意：这会连带结束其它工具（如 Android Studio）正在使用的 adb server。
     */
    public static void restartAdbServer() {
        // 先杀掉全部 adb.exe（含残留 server 与多传输态）
        try {
            ProcessBuilder killer = isWindowsOs()
                    ? new ProcessBuilder("taskkill", "/F", "/IM", "adb.exe")
                    : new ProcessBuilder("pkill", "-x", "adb");
            killer.redirectErrorStream(true);
            Process kill = killer.start();
            drainQuietly(kill.getInputStream());
            kill.waitFor();
        } catch (Throwable ignored) {
        }
        // 再用随包 adb 重新拉起 server（start-server 会等到 daemon 就绪才返回）
        try {
            ProcessBuilder starter = new ProcessBuilder(findAdbPath(), "start-server");
            starter.redirectErrorStream(true);
            Process start = starter.start();
            drainQuietly(start.getInputStream());
            start.waitFor();
        } catch (Throwable ignored) {
        }
        // USB 重新枚举需要一点时间，给后面的 forward 留出窗口
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 重启 adb 后执行 forward，返回实际使用的 adb serial（失败返回 null）。
     * <p>
     * 未显式指定 -s 时优先选唯一的 USB 设备：无线调试通常会自动重连，
     * 出现 USB + 无线两条传输时，不带 -s 的 adb forward 必然报 more than one device/emulator。
     */
    public static String restartAdbAndForward(int port, String device) {
        restartAdbServer();
        for (int i = 0; i < 3; i++) {
            String target = resolveAdbForwardTarget(device);
            if (target != null && executeAdbForwardCommand(port, target)) {
                return target;
            }
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 解析 forward 目标：显式 -s 优先；否则优先唯一的 USB 设备，再退唯一在线设备。 */
    public static String resolveAdbForwardTarget(String requestedDevice) {
        List<String> serials = listAdbDeviceSerials();
        if (requestedDevice != null && !requestedDevice.isEmpty()) {
            return serials.contains(requestedDevice) ? requestedDevice : null;
        }
        String usb = null;
        int usbCount = 0;
        for (String serial : serials) {
            // 无线调试是 "ip:port"，模拟器是 "emulator-xxxx"：都不算物理 USB
            if (serial.contains(":") || serial.startsWith("emulator-")) {
                continue;
            }
            usb = serial;
            usbCount++;
        }
        if (usbCount == 1) {
            return usb;
        }
        return serials.size() == 1 ? serials.get(0) : null;
    }

    /** 读 adb devices，只返回 state=device 的 serial（离线/未授权一律忽略）。 */
    private static List<String> listAdbDeviceSerials() {
        List<String> devices = new ArrayList<>();
        try {
            String adb = findAdbPath();
            Process process = Runtime.getRuntime().exec(adb + " devices", null,
                    new File(System.getProperty("user.dir")));
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));
            while ((line = errorReader.readLine()) != null) {
                out.append(line).append('\n');
            }
            process.waitFor();
            for (String raw : out.toString().split("\\r?\\n")) {
                String[] parts = raw.trim().split("\\s+");
                if (parts.length >= 2 && "device".equals(parts[1])) {
                    devices.add(parts[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        return devices;
    }

    /** 把进程输出读空：adb 写满管道缓冲区后会阻塞，不读就会卡死在 waitFor。 */
    private static void drainQuietly(java.io.InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            while (reader.readLine() != null) {
                // 丢弃
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 解析唯一的 adb 目标：执行 "adb devices"，解析 serial + 空白 + state 行，
     * 只接受 state 恰好为 "device" 的行；忽略表头、空行、offline、unauthorized。
     * requestedDevice 非空：仅当该 serial 存在且 state=device 时返回它，否则 null；
     * 为空：恰好一台 state=device 时返回其 serial，0 台或多台返回 null。
     * 不打印 serial 到正常输出；异常返回 null。
     */
    public static String resolveAdbTarget(String requestedDevice) {
        List<String> devices = listAdbDeviceSerials();
        if (requestedDevice != null && !requestedDevice.isEmpty()) {
            return devices.contains(requestedDevice) ? requestedDevice : null;
        }
        return devices.size() == 1 ? devices.get(0) : null;
    }

    private static String findAdbPath() {
        // 打包目录：adb.exe 与启动器同级，jar 在 app/ 下
        try {
            File code = new File(Main.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            File base = code.isFile() ? code.getParentFile().getParentFile() : code.getParentFile();
            File bundled = new File(base, "adb.exe");
            if (bundled.isFile()) {
                return bundled.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        try {
            Process process = Runtime.getRuntime().exec("adb version");
            if (process.waitFor() == 0) {
                return "adb";
            }
        } catch (Throwable ignored) {
        }
        return "./adb";
    }

    /**
     * 选 ADB 引导后的主信道地址：解析手机私网 IPv4 → 同 /24 优先 →
     * 逐个 1s TCP 探活。探活只 connect/close，不发 HFXC 头，不会触发手机端确认。
     *
     * @return 可达的手机 LAN IP；没有则 null（调用方回退 127.0.0.1）
     */
    public static String chooseAdbControlAddress(String device) {
        List<String> ips = getAdbLanIps(device);
        if (ips.isEmpty()) {
            return null;
        }
        final String ownPrefix = Discovery.subnetPrefixOf(Discovery.localIpv4());
        Collections.sort(ips, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                boolean sameA = ownPrefix != null && ownPrefix.equals(Discovery.subnetPrefixOf(a));
                boolean sameB = ownPrefix != null && ownPrefix.equals(Discovery.subnetPrefixOf(b));
                if (sameA == sameB) {
                    return 0;
                }
                return sameA ? -1 : 1;
            }
        });
        for (String ip : ips) {
            if (isTcpReachable(ip, 5740, 1000)) {
                return ip;
            }
        }
        return null;
    }

    public static boolean isTcpReachable(String ip, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 读取手机上的私网 IPv4（优先 ip -4，失败回退 ip addr）。 */
    public static List<String> getAdbLanIps(String device) {
        String adb = findAdbPath();
        String output = runAdbShell(adb, device, "ip -4 addr show");
        List<String> ips = parseAdbIps(output);
        if (ips.isEmpty()) {
            output = runAdbShell(adb, device, "ip addr show");
            ips = parseAdbIps(output);
        }
        return ips;
    }

    private static String runAdbShell(String adb, String device, String command) {
        try {
            String full = adb
                    + (device == null || device.isEmpty() ? "" : " -s " + device)
                    + " shell " + command;
            Process process = Runtime.getRuntime().exec(full, null,
                    new File(System.getProperty("user.dir")));
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));
            while ((line = errorReader.readLine()) != null) {
                out.append(line).append('\n');
            }
            process.waitFor();
            return out.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<String> parseAdbIps(String text) {
        final List<String> out = new ArrayList<>();
        final List<Integer> ranks = new ArrayList<>();
        String iface = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.matches("^\\d+:\\s+.*")) {
                int first = line.indexOf(':');
                String rest = line.substring(first + 1).trim();
                int second = rest.indexOf(':');
                iface = (second > 0 ? rest.substring(0, second) : rest).trim();
                continue;
            }
            if (!line.startsWith("inet ")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String ip = parts[1];
            int slash = ip.indexOf('/');
            if (slash > 0) {
                ip = ip.substring(0, slash);
            }
            String name = iface.toLowerCase();
            if (name.startsWith("lo") || name.startsWith("rmnet")
                    || name.startsWith("ccmni") || name.startsWith("p2p")) {
                continue;
            }
            if (Discovery.subnetPrefixOf(ip) == null
                    || ip.startsWith("127.") || ip.startsWith("169.254.")) {
                continue;
            }
            if (!out.contains(ip)) {
                out.add(ip);
                ranks.add(interfaceRank(name));
            }
        }
        // wlan > eth > rndis > 其它；同档保持 ip 输出顺序（稳定排序）
        for (int i = 1; i < out.size(); i++) {
            for (int j = i; j > 0 && ranks.get(j) < ranks.get(j - 1); j--) {
                String ip = out.get(j);
                out.set(j, out.get(j - 1));
                out.set(j - 1, ip);
                int rank = ranks.get(j);
                ranks.set(j, ranks.get(j - 1));
                ranks.set(j - 1, rank);
            }
        }
        return out;
    }

    private static int interfaceRank(String name) {
        if (name.startsWith("wlan")) {
            return 0;
        }
        if (name.startsWith("eth")) {
            return 1;
        }
        if (name.startsWith("rndis") || name.startsWith("usb")) {
            return 2;
        }
        return 3;
    }

}
