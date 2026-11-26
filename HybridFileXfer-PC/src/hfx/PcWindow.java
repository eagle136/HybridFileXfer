package hfx;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.event.WindowStateListener;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import hfx.Main;
import hfx.Strings;
import hfx.core.ConsentProvider;
import hfx.core.Discovery;
import hfx.core.DiscoveryResponder;
import hfx.core.HFXClient;
import hfx.core.Utils;
import hfx.core.TrafficInfo;
import hfx.core.ClientCallBack;
import hfx.core.ConnectServerCallback;
import hfx.core.TransferFileCallback;
import hfx.jdk.JdkHFXClient;

/**
 * PC 端 GUI：只做被动客户端。
 *
 * <ul>
 *   <li>扫描/连接/断开全部手动触发，启动不扫描、不连接；</li>
 *   <li>权限区固定高度、平时留空，读写授权在窗口内完成；</li>
 *   <li>速度只在连接后显示，1s 刷新；</li>
 *   <li>ADB 按钮只复用现有 adb forward + 取 IP，不改变 CLI 的 adb 行为。</li>
 * </ul>
 */
public final class PcWindow implements ConsentProvider {

    private static final int PERMISSION_PANEL_MIN_HEIGHT = 160;
    private static final int PERMISSION_PANEL_MAX_HEIGHT = 320;
    /** 面板里最多列出多少条路径，更多时只给"共 N 项"摘要（列表本身可滚动） */
    private static final int PERMISSION_PATHS_SHOWN = 200;

    private final String homeDir;
    private final String adbDevice;

    private final JFrame frame = new JFrame(Strings.get("app_window_title"));
    private final DefaultListModel<Discovery.Device> deviceModel = new DefaultListModel<>();
    private final JList<Discovery.Device> deviceList = new JList<>(deviceModel);
    private final JButton scanButton = new JButton(Strings.get("scan_start"));
    private final JButton adbButton = new JButton(Strings.get("adb_connect"));
    private final JButton disconnectButton = new JButton(Strings.get("disconnect_link"));
    private final JButton firewallButton = new JButton(Strings.get("firewall_allow"));
    private final JLabel statusLabel = new JLabel(Strings.get("not_connected"));
    private final JLabel channelLabel = new JLabel();
    private final JLabel discoverableLabel = new JLabel(Strings.get("discoverable_no"));
    private final JLabel uploadLabel = new JLabel();
    private final JLabel downloadLabel = new JLabel();
    private final JPanel speedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 2));
    private final JPanel permissionPanel = new JPanel(new BorderLayout(6, 6));
    private final JLabel permissionMessage = new JLabel();
    private final JLabel permissionPeer = new JLabel();
    private final JLabel permissionCountdown = new JLabel();
    /** 授权弹窗里的路径清单：只做展示，条数超限时截断并在摘要里说明 */
    private final DefaultListModel<String> permissionPathModel = new DefaultListModel<>();
    private final JList<String> permissionPathList = new JList<>(permissionPathModel);
    private final JScrollPane permissionPathScroll = new JScrollPane(permissionPathList);
    private final JButton allowSessionButton = new JButton(Strings.get("allow_session"));
    private final JButton denyButton = new JButton(Strings.get("deny"));
    private final JButton allowOnceButton = new JButton(Strings.get("allow_once"));
    private final Timer speedTimer;

    private volatile Discovery.Scan scan;
    private volatile JdkHFXClient client;
    private volatile boolean connecting;
    private volatile boolean connected;
    /** 一次性标记：connectViaAdb 刚执行完 forward 时置 true，下一次 connectTo 跳过自动 forward。 */
    private volatile boolean skipAutoAdbForward;
    /** 防火墙放行请求进行中（避免重复点击） */
    private volatile boolean firewallRequesting;
    private volatile long latestUpload;
    private volatile long latestDownload;

    /**
     * 连接尝试代号。每次 connectTo 自增并把 {@link #activeAttempt} 指向它，
     * 取消/失败时把 activeAttempt 清 0 —— 旧线程剩下的步骤（包括握手成功后
     * 把状态改成"已连接"）全部按代号作废，否则连接中点"断开"只是把界面复位，
     * 后台那次连接还会接着跑完然后把窗口改回"已连接"。
     */
    private final AtomicInteger connectAttempt = new AtomicInteger();
    private volatile int activeAttempt;

    private DiscoveryResponder responder;
    private final AtomicBoolean reversePending = new AtomicBoolean(false);

    private volatile CountDownLatch permissionLatch;
    private volatile AtomicInteger permissionResult;
    private volatile Timer permissionTimer;

    public PcWindow(String homeDir, final String initialIp, String adbDevice) {
        this.homeDir = (homeDir == null || homeDir.isEmpty()) ? "/" : homeDir;
        this.adbDevice = adbDevice;
        this.responder = new DiscoveryResponder(5740,
                safeLabel("PC-" + localHostName()), reverseListener);

        buildUi();
        speedTimer = new Timer(1000, e -> refreshSpeed());
        speedTimer.start();

        if (initialIp != null && !initialIp.isEmpty()) {
            SwingUtilities.invokeLater(() -> connectTo(initialIp));
        }
    }

    public void show() {
        if (SwingUtilities.isEventDispatchThread()) {
            showOnEdt();
        } else {
            SwingUtilities.invokeLater(this::showOnEdt);
        }
    }

    private void showOnEdt() {
        frame.setVisible(true);
        if (frame.getState() == Frame.ICONIFIED) {
            frame.setState(Frame.NORMAL);
        }
        frame.toFront();
        frame.requestFocus();
        // 启动即扫一次；列表出来后仍需用户单击设备才连接
        startScan();
    }

    private void buildUi() {
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    onDeviceClicked();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(deviceList);
        scrollPane.setPreferredSize(new Dimension(600, 210));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttonRow.add(scanButton);
        buttonRow.add(adbButton);
        buttonRow.add(disconnectButton);
        buttonRow.add(firewallButton);
        disconnectButton.setEnabled(false);

        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        channelLabel.setHorizontalAlignment(SwingConstants.LEFT);
        channelLabel.setVisible(false);
        uploadLabel.setText(Strings.get("upload_speed") + " 0B/s");
        downloadLabel.setText(Strings.get("download_speed") + " 0B/s");
        speedPanel.add(uploadLabel);
        speedPanel.add(downloadLabel);
        speedPanel.setVisible(false);

        permissionPanel.setBorder(BorderFactory.createEtchedBorder());
        permissionMessage.setHorizontalAlignment(SwingConstants.LEFT);
        permissionMessage.setFont(permissionMessage.getFont().deriveFont(Font.BOLD));
        permissionPeer.setHorizontalAlignment(SwingConstants.LEFT);
        permissionPathList.setVisibleRowCount(6);
        permissionPathList.setCellRenderer(new PathCellRenderer());
        permissionPathScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel permissionHead = new JPanel();
        permissionHead.setLayout(new BoxLayout(permissionHead, BoxLayout.Y_AXIS));
        permissionHead.add(permissionMessage);
        permissionHead.add(permissionPeer);
        permissionPanel.add(permissionHead, BorderLayout.NORTH);
        permissionPanel.add(permissionPathScroll, BorderLayout.CENTER);
        applyPermissionPanelHeight();
        clearPermissionPanel();

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        buttonRow.setAlignmentX(0.0f);
        statusLabel.setAlignmentX(0.0f);
        channelLabel.setAlignmentX(0.0f);
        discoverableLabel.setAlignmentX(0.0f);
        speedPanel.setAlignmentX(0.0f);
        permissionPanel.setAlignmentX(0.0f);
        center.add(buttonRow);
        center.add(statusLabel);
        center.add(channelLabel);
        center.add(discoverableLabel);
        center.add(speedPanel);
        center.add(permissionPanel);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(scrollPane, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(660, 640);
        frame.setMinimumSize(new Dimension(560, 560));
        frame.setLocationRelativeTo(null);

        scanButton.addActionListener(e -> startScan());
        adbButton.addActionListener(e -> connectViaAdb());
        disconnectButton.addActionListener(e -> disconnect());
        firewallButton.addActionListener(e -> requestFirewallAllow());
        allowSessionButton.addActionListener(e -> finishPermission(ConsentProvider.CONSENT_SESSION));
        allowOnceButton.addActionListener(e -> finishPermission(ConsentProvider.CONSENT_ONCE));
        denyButton.addActionListener(e -> finishPermission(ConsentProvider.CONSENT_DENY));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        frame.addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                refreshDiscoverability();
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                refreshDiscoverability();
            }
        });
        frame.addWindowStateListener(new WindowStateListener() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                if (e.getNewState() == Frame.ICONIFIED || e.getNewState() == Frame.NORMAL) {
                    refreshDiscoverability();
                }
            }
        });
        // 窗口高度变化时按比例重算权限面板高度，保证面板始终不超过主界面
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyPermissionPanelHeight();
                if (!permissionPathModel.isEmpty()) {
                    permissionPanel.revalidate();
                }
            }
        });
    }

    private void startScan() {
        if (connecting || scan != null) {
            return;
        }
        scanButton.setEnabled(false);
        setStatus(Strings.get("scanning_lan"));
        scan = Discovery.startScan(Discovery.DEFAULT_TIMEOUT_MS, devices -> SwingUtilities.invokeLater(() -> {
            scan = null;
            scanButton.setEnabled(true);
            deviceModel.clear();
            for (Discovery.Device device : devices) {
                deviceModel.addElement(device);
            }
            setStatus(devices.isEmpty()
                    ? Strings.get("scan_none")
                    : Strings.get("scan_found", devices.size()));
        }));
    }

    private void onDeviceClicked() {
        Discovery.Device device = deviceList.getSelectedValue();
        if (device == null) {
            return;
        }
        if (connecting || connected) {
            setStatus(Strings.get("disconnect_first"));
            return;
        }
        connectTo(device.ip);
    }

    private void connectViaAdb() {
        if (connecting || connected) {
            setStatus(Strings.get("disconnect_first"));
            return;
        }
        adbButton.setEnabled(false);
        scanButton.setEnabled(false);
        setStatus(Strings.get("adb_forwarding"));
        Thread thread = new Thread(() -> {
            final boolean forwarded = Main.executeAdbForwardCommand(5740, adbDevice);
            final String lan = forwarded ? Main.chooseAdbControlAddress(adbDevice) : null;
            final String address = forwarded ? (lan != null ? lan : "127.0.0.1") : null;
            SwingUtilities.invokeLater(() -> {
                adbButton.setEnabled(true);
                scanButton.setEnabled(true);
                if (address == null) {
                    setStatus(Strings.get("adb_forward_failed"));
                    return;
                }
                setStatus(lan != null
                        ? Strings.get("main_channel", "LAN " + lan)
                        : Strings.get("adb_fallback"));
                // forward 刚执行成功：下一次 connectTo 跳过自动 forward，直接允许 USB_ADB
                skipAutoAdbForward = true;
                connectTo(address);
            });
        }, "hfx-adb");
        thread.setDaemon(true);
        thread.start();
    }

    /** 反向连接（EDT）：由 tryAcceptReverse 原子领取后调度过来。 */
    private void acceptReverse(final String ip, final int port, final String name) {
        if (connecting || connected || client != null) {
            reversePending.set(false);
            setStatus(Strings.get("reverse_busy", safeLabel(name)));
            return;
        }
        // 成功路径的 reversePending 由 connectTo 开头清掉
        setStatus(Strings.get("reverse_request", safeLabel(name), ip));
        connectTo(ip);
    }

    private void connectTo(final String address) {
        // 反向连接领取标记在开始连接时清除（含直连/ADB 路径共用）
        reversePending.set(false);
        if (connecting || connected) {
            // 本次尝试未发生，作废旧的一次性标记，避免后续无关连接跳过 forward 验证
            skipAutoAdbForward = false;
            return;
        }
        connecting = true;
        final int attempt = connectAttempt.incrementAndGet();
        activeAttempt = attempt;
        refreshDiscoverability();
        scanButton.setEnabled(false);
        adbButton.setEnabled(false);
        disconnectButton.setEnabled(true);
        setStatus(Strings.get("connecting_to", address));

        Thread thread = new Thread(() -> {
            // 先决定 USB_ADB 是否参与：connectViaAdb 刚 forward 成功则跳过验证；
            // 否则在 hfx-connect 线程内现场验证+执行 forward（此时 connecting 已为 true，
            // ensureAdbForward 内部不得再判断 connecting/connected）。
            boolean allowUsbAdb;
            if (skipAutoAdbForward) {
                skipAutoAdbForward = false;
                allowUsbAdb = true;
            } else {
                allowUsbAdb = ensureAdbForward(address);
                if (!allowUsbAdb) {
                    setStatus(Strings.get("adb_forward_unavailable"));
                }
            }
            // adb forward 可能耗时数秒，这期间用户可能已经点了"断开"
            if (attempt != activeAttempt) {
                return;
            }
            final JdkHFXClient newClient = new JdkHFXClient(address, 5740, homeDir);
            newClient.setClientName(localHostName());
            newClient.setConsentProvider(this);
            newClient.setAllowUsbAdb(allowUsbAdb);
            client = newClient;
            if (attempt != activeAttempt) {
                abandonAttempt(newClient);
                return;
            }
            boolean ok;
            try {
                ok = newClient.connect(connectCallback);
            } catch (Throwable t) {
                if (attempt != activeAttempt) {
                    abandonAttempt(newClient);
                    return;
                }
                SwingUtilities.invokeLater(() -> connectFailed(newClient, t.toString()));
                return;
            }
            if (attempt != activeAttempt) {
                // 握手期间被取消：这条连接已经没有主人了，直接关掉
                abandonAttempt(newClient);
                return;
            }
            if (!ok) {
                SwingUtilities.invokeLater(() -> connectFailed(newClient, null));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (attempt != activeAttempt) {
                    return;
                }
                connected = true;
                connecting = false;
                speedPanel.setVisible(true);
                latestUpload = 0;
                latestDownload = 0;
                refreshSpeed();
                disconnectButton.setEnabled(true);
                setStatus(Strings.get("connected_to", newClient.getServerControllerDisplayAddress()));
            });
            try {
                newClient.start(clientCallback);
            } catch (Throwable ignored) {
                // close()/服务端 shutdown 都会走到这里
            }
            SwingUtilities.invokeLater(() -> {
                if (client == newClient) {
                    resetToIdle();
                }
            });
        }, "hfx-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 连接前自动 adb forward：仅当 adb 目标唯一且能确认指向本目标地址时执行。
     * 在 hfx-connect 线程内调用（connecting 已为 true）；不抛异常，
     * 失败只影响 USB_ADB 能否验证，不影响局域网通道。
     */
    private boolean ensureAdbForward(String targetAddress) {
        try {
            String target = Main.resolveAdbTarget(adbDevice);
            if (target == null) {
                return false;
            }
            if (!HFXClient.isLoopbackAddress(targetAddress)) {
                List<String> ips = Main.getAdbLanIps(target);
                if (!ips.contains(targetAddress)) {
                    // 目标地址不在该 adb 设备的 LAN IP 里，避免把 A 手机的转发接到 B 手机
                    return false;
                }
            }
            // 回环地址视为显式 ADB 连接，允许 forward
            return Main.executeAdbForwardCommand(5740, target);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 连接过程中取消：作废本次尝试代号，并关掉可能已经建了一半的连接
     * （握手正卡在 read 上时只有 close 能把它拽出来）。
     */
    private void cancelConnect() {
        final JdkHFXClient pending = client;
        // 先让代号失效，旧线程后续的成功回调就落到 if (attempt != activeAttempt) 分支里
        activeAttempt = 0;
        connectAttempt.incrementAndGet();
        client = null;
        connecting = false;
        connected = false;
        finishPermission(ConsentProvider.CONSENT_DENY);
        SwingUtilities.invokeLater(() -> {
            channelLabel.setText("");
            channelLabel.setVisible(false);
            speedPanel.setVisible(false);
            disconnectButton.setEnabled(false);
            scanButton.setEnabled(true);
            adbButton.setEnabled(true);
            setStatus(Strings.get("connect_cancelled"));
            refreshDiscoverability();
        });
        if (pending != null) {
            Thread closeThread = new Thread(() -> closeQuietly(pending), "hfx-connect-cancel");
            closeThread.setDaemon(true);
            closeThread.start();
        }
    }

    private void closeQuietly(JdkHFXClient c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** 放弃一次已被取消的连接：先摘掉窗口对它的引用，再关连接 */
    private void abandonAttempt(JdkHFXClient c) {
        if (client == c) {
            client = null;
        }
        closeQuietly(c);
    }

    private void connectFailed(JdkHFXClient failed, String message) {
        activeAttempt = 0;
        reversePending.set(false);
        if (client == failed) {
            client = null;
        }
        connecting = false;
        connected = false;
        channelLabel.setText("");
        channelLabel.setVisible(false);
        speedPanel.setVisible(false);
        disconnectButton.setEnabled(false);
        scanButton.setEnabled(true);
        adbButton.setEnabled(true);
        if (message != null && !message.isEmpty()) {
            setStatus(message);
        }
        refreshDiscoverability();
    }

    private void disconnect() {
        // 还在连接阶段：这次"断开"必须把进行中的连接尝试一起废掉。
        // 原来只是把界面复位，后台线程握手成功后又会把窗口改回"已连接"。
        if (connecting && !connected) {
            cancelConnect();
            return;
        }
        finishPermission(ConsentProvider.CONSENT_DENY);
        final JdkHFXClient current = client;
        disconnectButton.setEnabled(false);
        setStatus(Strings.get("disconnecting"));
        Thread thread = new Thread(() -> {
            if (current != null) {
                current.close();
            }
            SwingUtilities.invokeLater(() -> {
                if (client == current) {
                    resetToIdle();
                }
            });
        }, "hfx-disconnect");
        thread.setDaemon(true);
        thread.start();
    }

    private void resetToIdle() {
        finishPermission(ConsentProvider.CONSENT_DENY);
        reversePending.set(false);
        client = null;
        connected = false;
        connecting = false;
        scan = null;
        channelLabel.setText("");
        channelLabel.setVisible(false);
        speedPanel.setVisible(false);
        latestUpload = 0;
        latestDownload = 0;
        clearPermissionPanel();
        disconnectButton.setEnabled(false);
        scanButton.setEnabled(true);
        adbButton.setEnabled(true);
        setStatus(Strings.get("not_connected"));
        refreshDiscoverability();
    }

    private void shutdown() {
        if (scan != null) {
            scan.cancel();
            scan = null;
        }
        reversePending.set(false);
        finishPermission(ConsentProvider.CONSENT_DENY);
        stopDiscoveryResponder();
        JdkHFXClient current = client;
        if (current != null) {
            try {
                current.close();
            } catch (Throwable ignored) {
            }
        }
        speedTimer.stop();
        frame.dispose();
        System.exit(0);
    }

    private void refreshSpeed() {
        if (!speedPanel.isVisible()) {
            return;
        }
        uploadLabel.setText(Strings.get("upload_speed") + " " + Utils.formatSpeed(latestUpload));
        downloadLabel.setText(Strings.get("download_speed") + " " + Utils.formatSpeed(latestDownload));
    }

    private static final String FIREWALL_RULE = "HybridFileXfer UDP 5741";

    /**
     * 主动申请 Windows 防火墙放行 UDP 5741 入站（单次 UAC）。
     * 后台线程执行双层 PowerShell（内层 Start-Process -Verb RunAs 提权），
     * 先删同名旧规则再加新规则保证幂等；结果回 EDT 更新状态栏。
     */
    private void requestFirewallAllow() {
        if (firewallRequesting) {
            return;
        }
        String os = System.getProperty("os.name");
        if (os == null || !os.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            setStatus(Strings.get("firewall_failed"));
            return;
        }
        firewallRequesting = true;
        firewallButton.setEnabled(false);
        setStatus(Strings.get("firewall_requesting"));
        Thread thread = new Thread(() -> {
            boolean ok = runFirewallElevated();
            SwingUtilities.invokeLater(() -> {
                firewallRequesting = false;
                firewallButton.setEnabled(true);
                setStatus(ok ? Strings.get("firewall_added") : Strings.get("firewall_failed"));
            });
        }, "hfx-firewall");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean runFirewallElevated() {
        String script =
                "$ErrorActionPreference = 'Stop'\n"
                + "$inner = 'netsh advfirewall firewall delete rule name=\"" + FIREWALL_RULE + "\" | Out-Null' + \"`n\" +\n"
                + "         'netsh advfirewall firewall add rule name=\"" + FIREWALL_RULE + "\" dir=in action=allow protocol=UDP localport=5741 | Out-Null' + \"`n\" +\n"
                + "         'if ($LASTEXITCODE -ne 0) { exit 1 }'\n"
                + "Start-Process -FilePath powershell -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-Command',$inner) -Verb RunAs -Wait\n"
                + "if ($LASTEXITCODE -ne 0) { exit 1 }";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // 排空输出，防止管道缓冲区写满导致阻塞
                }
            }
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable t) {
            System.err.println("防火墙放行申请失败: " + t);
            return false;
        }
    }

    private void setStatus(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }
    }

    private boolean isDiscoverable() {
        return frame.isShowing()
                && frame.isFocused()
                && frame.getState() != Frame.ICONIFIED
                && !connecting
                && !connected
                && client == null;
    }

    private void refreshDiscoverability() {
        final boolean discoverable = isDiscoverable();
        if (discoverable) {
            startDiscoveryResponder();
        } else {
            stopDiscoveryResponder();
        }
        final String text = discoverable
                ? Strings.get("discoverable_yes")
                : Strings.get("discoverable_no");
        if (SwingUtilities.isEventDispatchThread()) {
            discoverableLabel.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> discoverableLabel.setText(text));
        }
    }

    private void startDiscoveryResponder() {
        if (responder == null) {
            return;
        }
        responder.start();
    }

    private void stopDiscoveryResponder() {
        if (responder != null) {
            responder.stop();
        }
    }

    /** 发现应答监听器：回调都在 UDP 收包线程上执行，UI 操作由本类自己切 EDT。 */
    private final DiscoveryResponder.Listener reverseListener = new DiscoveryResponder.Listener() {
        @Override
        public boolean isDiscoverable() {
            // 必须显式限定到外部类，否则这里调的是匿名类自己（接口实现），无限递归爆栈
            return PcWindow.this.isDiscoverable();
        }

        @Override
        public boolean tryAcceptReverse(String ip, int port, String name) {
            if (!PcWindow.this.isDiscoverable() || connecting || connected || client != null) {
                return false;
            }
            if (!reversePending.compareAndSet(false, true)) {
                return false;
            }
            SwingUtilities.invokeLater(() -> acceptReverse(ip, port, name));
            return true;
        }

        @Override
        public void onReverseAccepted(String ip, int port, String name) {
            // 连接已由 tryAcceptReverse 调度到 EDT（acceptReverse）；此处仅协议完整性钩子
        }
    };

    // ==================== 数据授权 ====================

    @Override
    public boolean requestConnection(String peerName, String peerIp, long timeoutMs) {
        return true;
    }

    @Override
    public int requestData(String peerName, String peerIp, int operation,
                           List<String> paths, long timeoutMs) {
        // 不要用 connected：EDT 的显示标志可能还没轮到，但控制通道已经可以收命令
        if (client == null) {
            return ConsentProvider.CONSENT_DENY;
        }
        if (permissionLatch != null) {
            // 同一时刻只允许一个待处理授权
            return ConsentProvider.CONSENT_DENY;
        }
        final long timeout = timeoutMs > 0 ? timeoutMs : ConsentProvider.DEFAULT_TIMEOUT_MS;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(ConsentProvider.CONSENT_DENY);
        permissionLatch = latch;
        permissionResult = result;
        SwingUtilities.invokeLater(() -> showPermission(peerName, peerIp, operation, paths, timeout));
        try {
            latch.await(timeout + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        permissionLatch = null;
        return result.get();
    }

    /**
     * 权限面板：动作标题 + 对端 + 可滚动的路径清单 + 倒计时/按钮。
     * 面板高度按窗口高度算（1/3，160~320px），清单超出就在面板内滚动，
     * 保证面板永远不超出主界面。
     */
    private void showPermission(String peerName, String peerIp, int operation,
                                List<String> paths, long timeoutMs) {
        permissionPanel.removeAll();
        int count = paths == null ? 0 : paths.size();
        String message;
        if (operation == ConsentProvider.OP_WRITE) {
            message = Strings.get("consent_write_action", count);
        } else if (operation == ConsentProvider.OP_DELETE) {
            message = Strings.get("consent_delete_action", count);
        } else {
            message = Strings.get("consent_read_action", count);
        }
        permissionMessage.setText(message);
        permissionPeer.setText(Strings.get("consent_peer_line",
                safeLabel(peerName), safeLabel(peerIp)));
        permissionCountdown.setText(Strings.get("remaining_seconds",
                (int) Math.max(1, (timeoutMs + 999) / 1000)));

        permissionPathModel.clear();
        int shown = Math.min(count, PERMISSION_PATHS_SHOWN);
        for (int i = 0; i < shown; i++) {
            permissionPathModel.addElement(paths.get(i));
        }
        if (count == 0) {
            permissionPathModel.addElement(Strings.get("consent_no_paths"));
        }
        permissionPathList.setVisibleRowCount(Math.max(2, Math.min(6, Math.max(1, shown))));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttons.add(allowSessionButton);
        buttons.add(denyButton);
        buttons.add(allowOnceButton);
        buttons.add(permissionCountdown);
        if (count > shown) {
            buttons.add(new JLabel(Strings.get("consent_more_paths", count, shown)));
        }

        JPanel permissionHead = new JPanel();
        permissionHead.setLayout(new BoxLayout(permissionHead, BoxLayout.Y_AXIS));
        permissionHead.add(permissionMessage);
        permissionHead.add(permissionPeer);

        permissionPanel.add(permissionHead, BorderLayout.NORTH);
        permissionPanel.add(permissionPathScroll, BorderLayout.CENTER);
        permissionPanel.add(buttons, BorderLayout.SOUTH);
        applyPermissionPanelHeight();
        permissionPanel.setVisible(true);
        permissionPanel.revalidate();
        permissionPanel.repaint();

        final long deadline = System.currentTimeMillis() + timeoutMs;
        if (permissionTimer != null) {
            permissionTimer.stop();
        }
        permissionTimer = new Timer(1000, e -> {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                finishPermission(ConsentProvider.CONSENT_DENY);
            } else {
                permissionCountdown.setText(Strings.get("remaining_seconds",
                        (int) ((left + 999) / 1000)));
            }
        });
        permissionTimer.start();
    }

    /** 面板高度 = 窗口高度 1/3，夹在 160~320 之间；窗口拉高也不会把面板撑爆。 */
    private void applyPermissionPanelHeight() {
        int frameHeight = frame.getHeight() > 0 ? frame.getHeight() : 640;
        int height = Math.max(PERMISSION_PANEL_MIN_HEIGHT,
                Math.min(PERMISSION_PANEL_MAX_HEIGHT, frameHeight / 3));
        permissionPanel.setPreferredSize(new Dimension(0, height));
        permissionPanel.setMinimumSize(new Dimension(0, height));
        permissionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    private void finishPermission(int value) {
        if (permissionTimer != null) {
            permissionTimer.stop();
        }
        CountDownLatch latch = permissionLatch;
        AtomicInteger result = permissionResult;
        if (latch != null && latch.getCount() > 0) {
            if (result != null) {
                result.set(value);
            }
            latch.countDown();
        }
        clearPermissionPanel();
    }

    private void clearPermissionPanel() {
        if (SwingUtilities.isEventDispatchThread()) {
            permissionPanel.removeAll();
            permissionPathModel.clear();
            permissionPanel.setVisible(false);
            permissionPanel.revalidate();
            permissionPanel.repaint();
        } else {
            SwingUtilities.invokeLater(() -> {
                permissionPanel.removeAll();
                permissionPathModel.clear();
                permissionPanel.setVisible(false);
                permissionPanel.revalidate();
                permissionPanel.repaint();
            });
        }
    }

    /** 路径渲染：两行以内、中间省略，超长路径不会把面板撑宽。 */
    private static final class PathCellRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index,
                    isSelected, cellHasFocus);
            String text = value == null ? "" : value.toString();
            label.setText("<html><body style='width:420px'>" + escapeHtml(text) + "</body></html>");
            label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return label;
        }

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static String safeLabel(String value) {
        if (value == null) {
            return "unknown";
        }
        String v = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (v.length() > 64) {
            v = v.substring(0, 64);
        }
        return v.isEmpty() ? "unknown" : v;
    }

    // ==================== 回调 ====================

    private final ConnectServerCallback connectCallback = new ConnectServerCallback() {
        @Override
        public void onConnectingControlChannel(String address, int port) {
            setStatus(Strings.get("connecting_to", address));
        }

        @Override
        public void onAwaitingPeerConfirmation() {
            setStatus(Strings.get("waiting_peer_confirmation"));
        }

        @Override
        public void onConnectionDenied() {
            setStatus(Strings.get("peer_denied_connection"));
        }

        @Override
        public void onVersionMismatch(int localVersion, int remoteVersion) {
            setStatus(Strings.get("version_mismatch", localVersion, remoteVersion));
        }

        @Override
        public void onConnectControlFailed() {
            setStatus(Strings.get("connect_control_failed"));
        }

        @Override
        public void onConnectingTransferChannel(String name, InetAddress inetAddress,
                                                InetAddress bindAddress) {
            setStatus(Strings.get("connecting_transfer_channel", name,
                    inetAddress.getHostAddress(),
                    bindAddress == null ? "null" : bindAddress.getHostAddress()));
        }

        @Override
        public void onConnectTransferChannelFailed(String name, InetAddress inetAddress,
                                                   Exception e) {
            setStatus(Strings.get("connect_transfer_failed", name,
                    inetAddress.getHostAddress(), e.toString()));
        }

        @Override
        public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB,
                          String osArch) {
            setStatus(Strings.get("out_of_memory", createdBuffers, requiredBuffers, maxMemoryMB));
        }

        @Override
        public void onRemoteOOM() {
            setStatus(Strings.get("remote_out_of_memory"));
        }

        @Override
        public void onConnectSuccess(List<String> channelNames) {
            // 握手成功后用户已经点了"断开"：这条连接正要被丢弃，别再往窗口上贴通道信息
            if (activeAttempt == 0) {
                return;
            }
            setStatus(Strings.get("connect_success"));
            final String text = Strings.get("connected_channels", channelNames.size(),
                    String.join(", ", channelNames));
            SwingUtilities.invokeLater(() -> {
                channelLabel.setText(text);
                channelLabel.setVisible(true);
            });
        }
    };

    private final ClientCallBack clientCallback = new ClientCallBack() {
        @Override
        public void onReceiving() {
            setStatus(Strings.get("receiving_files"));
        }

        @Override
        public void onSending() {
            setStatus(Strings.get("sending_files"));
        }

        @Override
        public void onExit() {
            setStatus(Strings.get("client_exit"));
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
            long up = 0;
            long down = 0;
            for (TrafficInfo info : trafficInfoList) {
                up += info.uploadTraffic;
                down += info.downloadTraffic;
            }
            latestUpload = up;
            latestDownload = down;
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            setStatus(Strings.get("channel_complete", iName,
                    time == 0 ? "∞" : Utils.formatSpeed(traffic / time * 1000)));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            if (errorType == TransferFileCallback.ERROR_TYPE_CHANNEL_LOST) {
                setStatus(Strings.get("channel_error_lost", iName));
            } else if (errorType == TransferFileCallback.ERROR_TYPE_EXCEPTION) {
                setStatus(Strings.get("channel_error_exception", iName, message));
            }
        }

        @Override
        public void onReadFileError(String message) {
            setStatus(Strings.get("file_read_error", message));
        }

        @Override
        public void onWriteFileError(String message) {
            setStatus(Strings.get("file_write_error", message));
        }

        @Override
        public void onPermissionDenied(boolean isWrite) {
            setStatus(isWrite
                    ? Strings.get("denied_write_request")
                    : Strings.get("denied_read_request"));
            latestUpload = 0;
            latestDownload = 0;
        }

        @Override
        public void onDeleteDenied() {
            setStatus(Strings.get("denied_delete_message"));
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            latestUpload = 0;
            latestDownload = 0;
            setStatus(Strings.get(isUpload ? "upload_complete" : "download_complete",
                    Utils.formatSpeed(time == 0 ? 0 : traffic / time * 1000),
                    Utils.formatTime(time), Utils.formatFileSize(traffic)));
        }

        @Override
        public void onIncomplete() {
            setStatus(Strings.get("transfer_failed"));
            latestUpload = 0;
            latestDownload = 0;
        }
    };

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Throwable ignored) {
            return "PC";
        }
    }
}
