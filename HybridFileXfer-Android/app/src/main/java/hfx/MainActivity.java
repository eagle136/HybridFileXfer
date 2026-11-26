package hfx;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;
import hfx.async.BackstageTask;
import hfx.IIOService;
import hfx.core.ConsentProvider;
import hfx.core.Discovery;
import hfx.core.FileBlock;
import hfx.core.HFXService;
import hfx.core.ServerNetInterface;
import hfx.droid.HFXServer;
import hfx.droid.DiscoveryResponder;
import hfx.droid.ReverseConnectRequest;
import hfx.droid.StartServerTask;
import hfx.droid.callback.StartServerCallback;
import hfx.listadapter.NetCardsAdapter;
import hfx.ui.EdgeToEdge;
import hfx.ui.UiScale;

public class MainActivity extends BaseActivity
        implements View.OnClickListener, ConsentProvider {
    public static final int REQUEST_CODE_TRANSFER = 2;
    public static final int REQUEST_CODE_LOCAL_NETWORK = 3;
    public static final int REQUEST_CODE_POST_NOTIFICATIONS = 4;
    public static final int RESULT_CODE_SERVER_DISCONNECT = 1;

    private static final String TAG = "HFXD";

    /** Android 16 (API 36) 起局域网包按 uid 管控 */
    private static final int SDK_LOCAL_NETWORK = 36;
    private static final String PERMISSION_LOCAL_NETWORK =
            "android.permission.ACCESS_LOCAL_NETWORK";

    private NetCardsAdapter netCardsAdapter;
    private Switch switchAdb;
    private Spinner spinnerMode;
    Button toTransfer;
    ListView deviceList;
    TextView scanStatus;
    ArrayAdapter<Discovery.Device> deviceAdapter;
    Context context;
    private boolean isShizuku = false;
    private HFXServer server;
    private Config config;
    private Discovery.Scan clientScan;
    /** 进行中的反向连接请求（C 已发出，等待 A） */
    private ReverseConnectRequest reverseRequest;
    /** 服务端未就绪时暂存的反向目标，onStatedServer 后补发 C */
    private Discovery.Device pendingReverseTarget;
    /**
     * 本机主动发起反向连接的目标 IP：对方接受后会回连本机服务端，
     * 这一次回连不再弹“是否允许连接”确认（用户点选即视为同意）。
     */
    private volatile String invitedReverseIp;
    /** 进行中的反向目标名：会话直接建立时用它改写状态行，避免停留在“正在请求” */
    private volatile String invitedReverseName;
    private volatile boolean resumed;

    /** PC 端对 C 的应答在 4s 内；安卓对端要先弹确认框，给足 20s */
    private static final long REVERSE_PC_TIMEOUT_MS = 4_000L;
    private static final long REVERSE_ANDROID_TIMEOUT_MS = 20_000L;
    /** 本机被请求连接时的确认框倒计时，必须短于对方的等待超时 */
    private static final long REVERSE_CONFIRM_TIMEOUT_MS = 15_000L;
    /** 同一时刻只允许一个待确认的反向连接 */
    private final AtomicBoolean reverseAcceptPending = new AtomicBoolean(false);
    private volatile DiscoveryResponder.Reply reverseAcceptReply;
    private AlertDialog reverseAcceptDialog;
    private String reverseAcceptIp;
    private final Handler reverseAcceptHandler = new Handler(Looper.getMainLooper());
    private final Runnable reverseAcceptTimeout = () -> finishReverseAccept(false, reverseAcceptIp);
    /** 进程级 IO 绑定持有 applicationContext，Activity 销毁后会话仍可继续读写 */
    private static Context appContext;

    private TextView txvStatus;
    private TextView txvPeer;
    private TextView txvModeHint;
    private View statusDot;
    private View channelsDivider;
    private Button disconnectBtn;
    private LinearLayout connectionsView;
    private LinearLayout advancedPanel;
    private ImageView advancedArrow;
    private boolean advancedOpen;
    private boolean ignoreModeSelection;
    /** 本地网络权限授予后是否继续刚才的扫描（只有用户主动点扫描才置 true） */
    private boolean scanAfterLocalNetworkGrant;
    /** API 33+ 通知权限是否已申请过（只弹一次系统框） */
    private boolean notificationPermissionRequested;
    /**
     * 主页扫描轮询间隔。进入前台后持续重扫，对端一上线就能看到；
     * 旧实现只有“点高级设置里的扫描 chip”这一个入口，且 onCreate 注释声称启动时自动扫一次
     * 但实际没有任何调用点，用户不展开折叠面板就永远看到空列表。
     */
    private static final long SCAN_RETRY_MS = 8000L;
    private final Handler autoScanHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoScanTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            startHomeScan();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        appContext = getApplicationContext();
        config = Config.getInstance(context);

        // 自绘工具栏负责状态栏垫高，根布局负责导航条垫高：
        // edge-to-edge 下若两边都不处理，底部按钮栏会被手势条压住一半。
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        EdgeToEdge.padTop(toolbar);
        EdgeToEdge.padBottom(findViewById(R.id.root));

        switchAdb = findViewById(R.id.switch_adb);
        spinnerMode = findViewById(R.id.spinner_mode);
        txvStatus = findViewById(R.id.txv_status);
        txvPeer = findViewById(R.id.txv_peer);
        txvModeHint = findViewById(R.id.txv_mode_hint);
        statusDot = findViewById(R.id.status_dot);
        channelsDivider = findViewById(R.id.channels_divider);
        connectionsView = findViewById(R.id.connections);
        advancedPanel = findViewById(R.id.advanced_panel);
        advancedArrow = findViewById(R.id.advanced_arrow);

        toTransfer = findViewById(R.id.to_transfer);
        toTransfer.setOnClickListener(this);
        disconnectBtn = findViewById(R.id.disconnect_server);
        disconnectBtn.setOnClickListener(v -> {
            if (server == null) {
                Toast.makeText(context, R.string.not_connected, Toast.LENGTH_SHORT).show();
            } else {
                disconnect(true);
            }
        });
        deviceList = findViewById(R.id.list_discovered_devices);
        scanStatus = findViewById(R.id.txv_scan_status);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        deviceList.setAdapter(deviceAdapter);
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            Discovery.Device device = deviceAdapter.getItem(position);
            if (device == null) {
                return;
            }
            // 谁点击谁保持主控：统一走反向连接，对方确认后作为客户端连入本机服务端
            requestReverseConnect(device);
        });
        findViewById(R.id.refresh_devices).setOnClickListener(v -> startHomeScan());
        findViewById(R.id.refresh).setOnClickListener(this);
        findViewById(R.id.advanced_toggle).setOnClickListener(v -> setAdvancedOpen(!advancedOpen));

        RecyclerView recyclerView = findViewById(R.id.rec_view_net_cards);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        try {
            netCardsAdapter = new NetCardsAdapter(this);
            recyclerView.setAdapter(netCardsAdapter);
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        switchAdb.setChecked(config.isAdbMode());
        switchAdb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (config.isAdbMode() == isChecked) {
                return;
            }
            config.setAdbMode(isChecked);
            updateModeHint();
            onConnectionConfigChanged();
        });
        updateModeHint();

        spinnerMode.setSelection(config.getMode());
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreModeSelection) {
                    return;
                }
                int previous = config.getMode();
                if (position == previous) {
                    return;
                }
                if (position != Config.MODE_NORMAL && checkShizukuOrReq(position)) {
                    ignoreModeSelection = true;
                    spinnerMode.setSelection(previous);
                    ignoreModeSelection = false;
                    return;
                }
                config.setMode(position);
                onConnectionConfigChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        showWaitingStatus();
        adoptLiveSession();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.to_transfer) {
            if (HFXServer.instance == null) {
                Toast.makeText(context, R.string.not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(new Intent(context, TransferActivity.class), REQUEST_CODE_TRANSFER);
        } else if (id == R.id.refresh) {
            if (server == null) {
                reloadNetCards();
            } else if (HFXServer.instance == null) {
                // 空闲等待中：刷新网卡要先停服务端，再按新列表自动恢复等待
                stopIdleServer();
                reloadNetCards();
                maybeAutoStartServer();
            } else {
                Toast.makeText(context, R.string.qing_xian_ting_zhi_fu_wu_duan, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setAdvancedOpen(boolean open) {
        advancedOpen = open;
        advancedPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        advancedArrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private void updateModeHint() {
        txvModeHint.setText(config.isAdbMode() ? R.string.adb_mode_hint : R.string.lan_mode_hint);
        txvModeHint.setVisibility(View.VISIBLE);
    }

    /** 连接方式或 IO 模式变化：空闲服务端立即按新配置重启，已连接则等断开后生效。 */
    private void onConnectionConfigChanged() {
        if (HFXServer.instance != null) {
            Toast.makeText(context, R.string.config_change_after_disconnect, Toast.LENGTH_SHORT).show();
            return;
        }
        if (server != null) {
            stopIdleServer();
        }
        maybeAutoStartServer();
    }

    private void reloadNetCards() {
        if (netCardsAdapter == null) {
            return;
        }
        try {
            netCardsAdapter.reload();
        } catch (SocketException | UnknownHostException e) {
            Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (server != null && server.isClosed()) {
            // 后台空闲超时已拆掉会话/等待服务端：按前台规则重新进入等待
            resetServerState();
        } else if (HFXServer.instance != null && server != HFXServer.instance) {
            adoptLiveSession();
        }
        maybeAutoStartServer();
        applyScreenScale();
        // 立刻扫一次，之后由 autoScanTick 每 8s 轮询
        scheduleAutoScan(0L);
    }

    @Override
    protected void onPause() {
        resumed = false;
        autoScanHandler.removeCallbacks(autoScanTick);
        if (clientScan != null) {
            clientScan.cancel();
            clientScan = null;
        }
        super.onPause();
    }

    /**
     * 软件在前台就进入“等待连接”，从而被局域网扫描到；有连接时不自动重启。
     * 只用已授予的权限和当前网卡选择，缺任何一项都在状态栏提示。
     */
    private void maybeAutoStartServer() {
        if (!resumed || server != null || isFinishing() || isDestroyed()) {
            return;
        }
        // 本机正在作为客户端连别人：别再起监听，避免抢同一端口/双份保活
        if (ClientSession.isActive()) {
            return;
        }
        if (spinnerMode == null || netCardsAdapter == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= SDK_LOCAL_NETWORK
                && ContextCompat.checkSelfPermission(this, PERMISSION_LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            txvStatus.setText(R.string.local_network_needed);
            checkLocalNetworkPermissionOrRequest(false);
            return;
        }
        int mode = spinnerMode.getSelectedItemPosition();
        isShizuku = mode != Config.MODE_NORMAL;
        if (!canStartServerSilently(mode)) {
            if (!isShizukuReady(mode)) {
                txvStatus.setText(R.string.status_shizuku_required);
            } else {
                List<ServerNetInterface> selected = selectedServerInterfaces();
                if (selected == null || selected.isEmpty()) {
                    txvStatus.setText(R.string.mei_you_wang_ka_xuan_ze);
                } else {
                    showWaitingStatus();
                }
            }
            return;
        }
        bindAndStartService();
    }

    private boolean isShizukuReady(int mode) {
        if (mode == Config.MODE_NORMAL) {
            return true;
        }
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != 0) {
            return false;
        }
        int uid = Shizuku.getUid();
        return mode == Config.MODE_ROOT ? uid == 0 : uid == 2000;
    }

    private boolean canStartServerSilently(int mode) {
        if (!isShizukuReady(mode)) {
            return false;
        }
        List<ServerNetInterface> selected = selectedServerInterfaces();
        if (selected == null || selected.isEmpty()) {
            return false;
        }
        return localBufferUsageMB() <= getAvailableMemoryMB();
    }

    /**
     * 本机缓冲池按块大小折算后的内存占用（MB）。
     * <p>
     * 块数乘块大小的换算集中在这里：原来直接拿"块数"跟"MB"比较，1MB 块时代碰巧等价，
     * 2MB 起就一直是错的，4MB 下会放过 4 倍超额配置。
     */
    private long localBufferUsageMB() {
        return (long) config.getLocalBufferCount() * (FileBlock.BLOCK_SIZE / (1024 * 1024));
    }

    /** 局域网网卡 +（ADB 模式）USB_ADB。 */
    private List<ServerNetInterface> selectedServerInterfaces() {
        if (netCardsAdapter == null) {
            return null;
        }
        List<ServerNetInterface> list = netCardsAdapter.getSelectedInterfaces();
        if (list == null) {
            return null;
        }
        if (config.isAdbMode()) {
            try {
                list.add(0, new ServerNetInterface("USB_ADB",
                        InetAddress.getByName("127.0.0.1"), null));
            } catch (UnknownHostException ignored) {
            }
        }
        return list;
    }

    public void startServer() {
        if (checkLocalNetworkPermissionOrRequest(false)) {
            return;
        }
        List<ServerNetInterface> selectedInterfaces = selectedServerInterfaces();
        if (selectedInterfaces == null) {
            return;
        }
        if (selectedInterfaces.isEmpty()) {
            Toast.makeText(context, R.string.mei_you_wang_ka_xuan_ze, Toast.LENGTH_SHORT).show();
            return;
        }
        long availableMemoryMB = getAvailableMemoryMB();
        if (localBufferUsageMB() > availableMemoryMB) {
            Toast.makeText(context, R.string.shou_ji_ke_yong_nei_cun_bu_zu, Toast.LENGTH_SHORT).show();
            return;
        }

        if (spinnerMode.getSelectedItemPosition() == 0) {
            isShizuku = false;
        } else {
            if (checkShizukuOrReq(spinnerMode.getSelectedItemPosition())) {
                return;
            }
            isShizuku = true;
        }
        bindAndStartService();
    }

    private void bindAndStartService() {
        netCardsAdapter.setEnableModify(false);
        IoBinding.acquire(IoBinding.OWNER_SERVER, this, currentIoMode(), serverIoListener());
    }

    private IoBinding.Listener serverIoListener() {
        return new IoBinding.Listener() {
            @Override
            public void onServiceConnected(IIOService service) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                startServerWithService(service);
            }

            @Override
            public void onServiceDisconnected() {
                // IO 服务异常死亡：有会话就按断线收口，空闲则回到起始态
                if (!isFinishing() && !isDestroyed()) {
                    disconnect(false);
                }
            }
        };
    }

    private int currentIoMode() {
        return isShizuku ? spinnerMode.getSelectedItemPosition() : Config.MODE_NORMAL;
    }

    /**
     * Activity 重建后接管仍在运行的会话：会话归进程所有，Activity 销毁只丢 UI。
     */
    private void adoptLiveSession() {
        HFXServer live = HFXServer.instance;
        if (live == null || server == live) {
            return;
        }
        server = live;
        server.setConsentProvider(this);
        IoBinding.setListener(IoBinding.OWNER_SERVER, serverIoListener());
        if (netCardsAdapter != null) {
            netCardsAdapter.setEnableModify(false);
        }
        renderChannels(live.getConnectionListINames());
        showConnectedStatus();
        syncConnectionForeground();
        ConnectionLocks.acquire(getApplicationContext());
        SessionIdleTimer.start(SessionIdleTimer.KEY_SERVER, MainActivity::onServerIdleTimeout);
    }

    /** 空闲 3 分钟无动作：优雅关闭会话，按用户口径“软件自行退出”（结束整个进程）。 */
    private static void onServerIdleTimeout() {
        Log.i(TAG, "server idle timeout -> disconnect & exit");
        HFXServer live = HFXServer.instance;
        if (live != null) {
            live.disconnect();
            HFXServer.instance = null;
        }
        Context ctx = appContext;
        if (ctx != null) {
            ConnectionService.removeOwner(ctx, ConnectionService.OWNER_MAIN);
            IoBinding.release(IoBinding.OWNER_SERVER, ctx);
        }
        ConnectionLocks.release();
        AppExit.exit(ctx, "server idle");
    }

    private void startServerWithService(IIOService ioService) {
        if (ioService == null || server != null || HFXServer.instance != null) {
            return;
        }
        List<ServerNetInterface> interfaceList = selectedServerInterfaces();
        if (interfaceList == null || interfaceList.isEmpty()) {
            return;
        }
        server = new HFXServer(ioService, isShizuku);
        server.setConsentProvider(this);
        server.setReverseAcceptListener(new DiscoveryResponder.Listener() {
            @Override
            public boolean isDiscoverable() {
                return isReverseAcceptable();
            }

            @Override
            public boolean onReverseRequest(String ip, int port, String name,
                                            DiscoveryResponder.Reply reply) {
                if (!isReverseAcceptable()) {
                    return false;
                }
                if (!reverseAcceptPending.compareAndSet(false, true)) {
                    return false;
                }
                reverseAcceptHandler.post(() -> showReverseAcceptDialog(ip, name, reply));
                return true;
            }
        });
        StartServerCallback callback = new StartServerCallback() {
            @Override
            public void onBindFailed(int port) {
                clearReverseState();
                netCardsAdapter.setEnableModify(true);
                server = null;
                resetConnectedStatus();
                syncConnectionForeground();
                txvStatus.setText(getString(R.string.status_bind_failed, port));
            }

            @Override
            public void onStatedServer() {
                List<ServerNetInterface> selected = netCardsAdapter.getSelectedInterfaces();
                if (selected != null) {
                    for (ServerNetInterface selectedInterface : selected) {
                        netCardsAdapter.changeItemState(selectedInterface.name,
                                getString(R.string.deng_dai_lian_jie));
                    }
                }
                showWaitingStatus();
                syncConnectionForeground();
                // 反向连接延迟补发：服务端刚就绪，向之前点选的 PC 发 C
                Discovery.Device pending = pendingReverseTarget;
                if (pending != null) {
                    pendingReverseTarget = null;
                    if (server == null || HFXServer.instance != null) {
                        return; // 期间状态已变，放弃补发
                    }
                    fireReverseRequest(pending);
                }
            }

            @Override
            public void onAccepted(String name) {
                if (HFXServer.instance == null) {
                    txvStatus.setText(R.string.status_validating_channels);
                }
                addChannelRow(name);
            }

            @Override
            public void onAcceptFailed(String name) {
                netCardsAdapter.changeItemState(name, getString(R.string.lian_jie_shi_bai));
            }

            @Override
            public void onConnectAborted() {
                Log.i(TAG, "server connect aborted");
                HFXServer.instance = null;
                resetConnectedStatus();
                syncConnectionForeground();
                List<ServerNetInterface> selected = netCardsAdapter.getSelectedInterfaces();
                if (selected == null) {
                    return;
                }
                for (ServerNetInterface netInterface : selected) {
                    netCardsAdapter.changeItemState(netInterface.name,
                            getString(R.string.deng_dai_lian_jie));
                }
            }

            @Override
            public void onPcOOM() {
                Toast.makeText(context, R.string.dui_fang_nei_cun_bu_zu, Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onMeOOM(int created, int localBufferCount) {
                Toast.makeText(context, getString(R.string.buffer_block_creation_failed_toast,
                        created, localBufferCount), Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onConnectSuccess() {
                Log.i(TAG, "server onConnectSuccess destroyed=" + isDestroyed()
                        + " ioMode=" + IoBinding.mode(IoBinding.OWNER_SERVER));
                if (isDestroyed() || IoBinding.mode(IoBinding.OWNER_SERVER) == -1) {
                    // onDestroy 已释放 IO/保活：不能再把会话留在后台
                    server.disconnect();
                    HFXServer.instance = null;
                    return;
                }
                HFXServer.instance = server;
                invitedReverseIp = null;
                // 对方接受后直接回连成功：不必再等 UDP 应答（应答可能丢失/晚到），
                // 取消挂起的反向请求，避免几十秒后再弹“请求超时，请重试”
                ReverseConnectRequest pending = reverseRequest;
                reverseRequest = null;
                if (pending != null) {
                    String name = invitedReverseName;
                    Log.i(TAG, "reverse resolved by connect name=" + name);
                    pending.cancel();
                    if (name != null) {
                        scanStatus.setText(getString(R.string.reverse_accepted, name));
                    }
                }
                invitedReverseName = null;
                renderChannels(server.getConnectionListINames());
                showConnectedStatus();
                syncConnectionForeground();
                ConnectionLocks.acquire(getApplicationContext());
                SessionIdleTimer.start(SessionIdleTimer.KEY_SERVER,
                        MainActivity::onServerIdleTimeout);
                SessionIdleTimer.markActivity();
            }

            @Override
            public void onError(Throwable th) {
                Log.i(TAG, "server onError: " + th);
                Toast.makeText(context, R.string.fu_wu_yi_ting_zhi, Toast.LENGTH_SHORT).show();
                changeToStartState();
            }
        };
        new StartServerTask(callback, server, config.getServerPort(), interfaceList,
                config.getLocalBufferCount(), config.getRemoteBufferCount(),
                config.getWirelessConnections()).execute();
    }

    private void addChannelRow(String name) {
        if (name == null) {
            return;
        }
        for (int i = 0; i < connectionsView.getChildCount(); i++) {
            TextView existing = connectionsView.getChildAt(i).findViewById(R.id.channel_name);
            if (existing != null && name.contentEquals(existing.getText())) {
                return;
            }
        }
        View item = LayoutInflater.from(context)
                .inflate(R.layout.item_channel_status, connectionsView, false);
        ((TextView) item.findViewById(R.id.channel_name)).setText(name);
        ((ImageView) item.findViewById(R.id.channel_icon)).setImageDrawable(
                ContextCompat.getDrawable(context, NetCardIcon.matchIdForIName(name)));
        connectionsView.addView(item);
        syncStatusUi();
    }

    private void renderChannels(List<String> names) {
        connectionsView.removeAllViews();
        if (names == null) {
            return;
        }
        for (String name : names) {
            addChannelRow(name);
        }
    }

    private void showWaitingStatus() {
        txvStatus.setText(config.isAdbMode()
                ? R.string.status_waiting_adb : R.string.status_waiting_lan);
        syncStatusUi();
    }

    /** 圆点、按钮可用性与通道分隔线只在这里统一算，避免每个回调各写一遍漏改。 */
    private void syncStatusUi() {
        boolean connected = HFXServer.instance != null;
        boolean listening = server != null;
        int dotColor;
        if (connected) {
            dotColor = R.color.status_ok;
        } else if (listening) {
            dotColor = R.color.status_waiting;
        } else {
            dotColor = R.color.status_idle;
        }
        statusDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, dotColor)));
        disconnectBtn.setEnabled(listening);
        toTransfer.setEnabled(connected);
        channelsDivider.setVisibility(
                connectionsView.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void showConnectedStatus() {
        int count = connectionsView.getChildCount();
        txvStatus.setText(getString(R.string.status_connected) + " · "
                + getString(R.string.channels_count, count));
        if (server != null) {
            String name = safeLabel(server.getRemoteClientName(), "");
            String ip = safeLabel(server.getRemotePeerIp(), "");
            String peer;
            if (!name.isEmpty() && !ip.isEmpty()) {
                peer = name + " (" + ip + ")";
            } else if (!name.isEmpty()) {
                peer = name;
            } else {
                peer = ip;
            }
            if (!peer.isEmpty()) {
                txvPeer.setText(getString(R.string.peer_label, peer));
                txvPeer.setVisibility(View.VISIBLE);
            }
        }
        syncStatusUi();
    }

    private void resetConnectedStatus() {
        connectionsView.removeAllViews();
        txvPeer.setText("");
        txvPeer.setVisibility(View.GONE);
        showWaitingStatus();
    }

    /**
     * Android 16+ 的本地网络权限。返回 true 表示已发起申请、本次动作应中止；
     * 授予后再点一次原按钮即可（权限检查在建任何 socket 之前）。
     */
    private boolean checkLocalNetworkPermissionOrRequest(boolean forScan) {
        if (Build.VERSION.SDK_INT < SDK_LOCAL_NETWORK) {
            return false;
        }
        if (ContextCompat.checkSelfPermission(this, PERMISSION_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        scanAfterLocalNetworkGrant = forScan;
        ActivityCompat.requestPermissions(this,
                new String[]{PERMISSION_LOCAL_NETWORK}, REQUEST_CODE_LOCAL_NETWORK);
        return true;
    }

    /**
     * API 33+ 起前台服务的通知需要运行时权限。只申请一次；
     * 拒绝不阻止保活（前台状态不受影响），只是看不到通知。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionRequested = true;
        ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_CODE_POST_NOTIFICATIONS);
    }

    /**
     * 前台保活与本机连接状态保持同步：
     * 服务端在监听或已连接 → 启动（或刷新）前台服务；服务端已停 → 释放引用。
     * 客户端模式由 ClientActivity 独立持有一个引用，两者并存时最后一个释放者停服务。
     */
    private void syncConnectionForeground() {
        if (server == null) {
            ConnectionService.removeOwner(getApplicationContext(), ConnectionService.OWNER_MAIN);
            return;
        }
        requestNotificationPermissionIfNeeded();
        String status;
        if (HFXServer.instance != null) {
            String name = safeLabel(server.getRemoteClientName(), "");
            String ip = safeLabel(server.getRemotePeerIp(), "");
            String peer;
            if (!name.isEmpty() && !ip.isEmpty()) {
                peer = name + " (" + ip + ")";
            } else {
                peer = name.isEmpty() ? ip : name;
            }
            status = peer.isEmpty()
                    ? getString(R.string.status_connected)
                    : getString(R.string.conn_service_connected, peer);
        } else {
            status = getString(config.isAdbMode()
                    ? R.string.status_waiting_adb : R.string.status_waiting_lan);
        }
        ConnectionService.setOwner(getApplicationContext(), ConnectionService.OWNER_MAIN, status);
    }

    private void showLocalNetworkSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.local_network_denied)
                .setMessage(R.string.local_network_permission_message)
                .setPositiveButton(R.string.go_to_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_LOCAL_NETWORK) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Toast.makeText(context, R.string.local_network_granted, Toast.LENGTH_SHORT).show();
                maybeAutoStartServer();
                if (scanAfterLocalNetworkGrant) {
                    startHomeScan();
                }
            } else {
                showLocalNetworkSettingsDialog();
            }
            scanAfterLocalNetworkGrant = false;
        } else if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && server != null) {
                // 授权后补刷一次前台通知（之前因未授权只保活、不显示通知）
                syncConnectionForeground();
            } else if (!granted && server != null) {
                Toast.makeText(context, R.string.notification_permission_denied,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (v.length() > 64) {
            v = v.substring(0, 64);
        }
        return v.isEmpty() ? fallback : v;
    }

    /** 服务端握手阶段：是否允许该客户端连接（30s 未操作按拒绝）。 */
    @Override
    public boolean requestConnection(String peerName, String peerIp, long timeoutMs) {
        // 本机点选设备发起的反向连接：对方接受后回连，不再二次确认
        String invited = invitedReverseIp;
        if (invited != null && invited.equals(peerIp)) {
            Log.i(TAG, "consent auto-approve invited ip=" + peerIp);
            invitedReverseIp = null;
            return true;
        }
        if (isFinishing() || isDestroyed()) {
            return false;
        }
        final long timeout = timeoutMs > 0 ? timeoutMs : ConsentProvider.DEFAULT_TIMEOUT_MS;
        Log.i(TAG, "consent dialog asked name=" + peerName + " ip=" + peerIp
                + " invited=" + invitedReverseIp + " timeout=" + timeout);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean result = new AtomicBoolean(false);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }
            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.connection_request)
                    .setMessage(getString(R.string.allow_connection_message,
                            safeLabel(peerName, "unknown"), safeLabel(peerIp, "unknown")))
                    .setPositiveButton(R.string.allow, (d, which) -> {
                        result.set(true);
                        latch.countDown();
                    })
                    .setNegativeButton(R.string.deny, (d, which) -> {
                        result.set(false);
                        latch.countDown();
                    })
                    .setCancelable(false)
                    .create();
            dialog.show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (latch.getCount() > 0) {
                    result.set(false);
                    latch.countDown();
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            }, timeout);
        });
        try {
            latch.await(timeout + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "consent dialog result=" + result.get());
        return result.get();
    }

    /** 服务端不主动接收数据授权请求；当前传输都由服务端发起。 */
    @Override
    public int requestData(String peerName, String peerIp, int operation,
                           java.util.List<String> paths, long timeoutMs) {
        return ConsentProvider.CONSENT_DENY;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_TRANSFER) {
            if (resultCode == RESULT_CODE_SERVER_DISCONNECT) {
                disconnect(false);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.github) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/eagle136/HybridFileXfer"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.update) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/eagle136/HybridFileXfer/releases"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.open_wifi_p2p_settings) {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings", "com.android.settings.Settings$WifiP2pSettingsActivity");
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (itemId == R.id.buffer_count) {
            View view = View.inflate(context, R.layout.dialog_buffer_settings, null);
            EditText editLocalCount = view.findViewById(R.id.edit_local_buffer_count);
            EditText editRemoteCount = view.findViewById(R.id.edit_remote_buffer_count);
            EditText editWireless = view.findViewById(R.id.edit_wireless_connections);
            editLocalCount.setText(String.valueOf(config.getLocalBufferCount()));
            editRemoteCount.setText(String.valueOf(config.getRemoteBufferCount()));
            editWireless.setText(String.valueOf(config.getWirelessConnections()));
            // 提示按块大小折算成"最多还能设多少块"：4MB 块下约等于可用内存 / 4
            editLocalCount.setHint(getString(R.string.dang_qian_zui_gao_ke_she_zhi,
                    getAvailableMemoryMB() / (FileBlock.BLOCK_SIZE / (1024 * 1024))));

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.she_zhi_huan_chong_qu_kuai_shu)
                    .setView(view)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(v -> {
                    String localCountStr = editLocalCount.getText().toString();
                    String remoteCountStr = editRemoteCount.getText().toString();
                    String wirelessStr = editWireless.getText().toString();

                    // 清除之前的错误提示
                    editLocalCount.setError(null);
                    editRemoteCount.setError(null);
                    editWireless.setError(null);

                    // 校验是否为空
                    if (localCountStr.isEmpty()) {
                        editLocalCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }
                    if (remoteCountStr.isEmpty()) {
                        editRemoteCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }
                    if (wirelessStr.isEmpty()) {
                        editWireless.setError(getString(R.string.wu_xian_xian_cheng_shu_bu_neng_wei_kong));
                        return;
                    }

                    int localCount = Integer.parseInt(localCountStr);
                    int remoteCount = Integer.parseInt(remoteCountStr);
                    int wireless = Integer.parseInt(wirelessStr);

                    // 最小 2 块：4MB 块下的双缓冲下限（一块在填、一块在写）
                    if (localCount < 2) {
                        editLocalCount.setError(getString(R.string.zui_xiao_ke_she_zhi_2));
                        return;
                    }
                    if (remoteCount < 2) {
                        editRemoteCount.setError(getString(R.string.zui_xiao_ke_she_zhi_2));
                        return;
                    }
                    if (wireless < HFXService.CONNECTIONS_WIRELESS_MIN
                            || wireless > HFXService.CONNECTIONS_WIRELESS_MAX) {
                        editWireless.setError(getString(R.string.wu_xian_xian_cheng_shu_chao_chu_fan_wei,
                                HFXService.CONNECTIONS_WIRELESS_MIN, HFXService.CONNECTIONS_WIRELESS_MAX));
                        return;
                    }

                    config.setLocalBufferCount(localCount);
                    config.setRemoteBufferCount(remoteCount);
                    config.setWirelessConnections(wireless);
                    dialog.dismiss();
                });
            });

            dialog.show();
        } else if (itemId == R.id.client) {
            startClient();
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * 扫描一次局域网。进入前台后由 {@link #autoScanTick} 每 8s 触发，
     * 不需要用户展开高级设置再点“扫描”才能看到设备。
     * 结果直接列在主页，点条目即连接。
     */
    private void startHomeScan() {
        if (checkLocalNetworkPermissionOrRequest(true)) {
            return;
        }
        if (clientScan != null) {
            // 上一次还没结束，等它回调后自己会排下一次，这里不叠加
            scheduleAutoScan(SCAN_RETRY_MS);
            return;
        }
        scanStatus.setText(R.string.scanning_lan);
        clientScan = Discovery.startScan(Discovery.DEFAULT_TIMEOUT_MS, devices -> runOnUiThread(() -> {
            clientScan = null;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            deviceAdapter.clear();
            deviceAdapter.addAll(devices);
            deviceAdapter.notifyDataSetChanged();
            if (devices.isEmpty()) {
                deviceList.setVisibility(View.GONE);
                // 扫不到时把本机地址摆出来：能立刻分辨“服务端没起”“不在同一网段”
                scanStatus.setText(getString(R.string.scan_no_devices_local,
                        safeLabel(Discovery.localIpv4(), "?")));
            } else {
                deviceList.setVisibility(View.VISIBLE);
                scanStatus.setText(getString(R.string.scan_found_devices, devices.size()));
            }
            scheduleAutoScan(SCAN_RETRY_MS);
        }));
    }

    /** 排下一次自动扫描；只有前台才排，避免后台空转发广播耗电。 */
    private void scheduleAutoScan(long delayMs) {
        autoScanHandler.removeCallbacks(autoScanTick);
        if (!resumed || isFinishing() || isDestroyed()) {
            return;
        }
        autoScanHandler.postDelayed(autoScanTick, delayMs);
    }

    /**
     * 按屏幕尺寸百分比修正那些 XML 表达不了的尺寸：
     * 设备列表高度与底部按钮高度，避免小屏只剩两行、大屏控件过瘦或过胖。
     */
    private void applyScreenScale() {
        if (deviceList != null) {
            ViewGroup.LayoutParams params = deviceList.getLayoutParams();
            int height = UiScale.heightPercent(0.20f, 96, 240);
            if (params != null && params.height != height) {
                params.height = height;
                deviceList.setLayoutParams(params);
            }
        }
        int buttonHeight = UiScale.heightPercent(0.062f, 46, 64);
        applyHeight(disconnectBtn, buttonHeight);
        applyHeight(toTransfer, buttonHeight);
    }

    private static void applyHeight(View view, int heightPx) {
        if (view == null || heightPx <= 0) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != heightPx) {
            params.height = heightPx;
            view.setLayoutParams(params);
        }
    }

    /**
     * 反向连接：点选设备后向其 5741 发 C，对方确认接受后作为客户端连入本机 5740 服务端，
     * 本机保持主控（传输按钮在本机）。PC 用 4s 超时，安卓对端要弹确认框用 20s。
     * 前提：本机服务端处于等待连接；未启动时先启动，就绪后补发 C。
     */
    private void requestReverseConnect(Discovery.Device device) {
        Log.i(TAG, "reverse tap ip=" + device.ip + " name=" + device.name
                + " reverse=" + device.reverse + " server=" + (server != null));
        if (reverseRequest != null || pendingReverseTarget != null) {
            return; // 已有进行中的反向流程
        }
        if (HFXServer.instance != null) {
            Toast.makeText(context, getString(R.string.reverse_need_idle),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (server == null) {
            pendingReverseTarget = device;
            // 权限/网卡/内存检查都在内部；C 延迟到 onStatedServer 后发出
            startServer();
            return;
        }
        fireReverseRequest(device);
    }

    /** 服务端已在等待连接：发 C 并等待 A。 */
    private void fireReverseRequest(Discovery.Device device) {
        if (reverseRequest != null) {
            return;
        }
        scanStatus.setText(getString(R.string.reverse_requesting, device.name));
        invitedReverseIp = device.ip;
        invitedReverseName = device.name;
        ReverseConnectRequest request = new ReverseConnectRequest();
        reverseRequest = request;
        long timeout = device.reverse ? REVERSE_PC_TIMEOUT_MS : REVERSE_ANDROID_TIMEOUT_MS;
        Log.i(TAG, "reverse send C ip=" + device.ip + " port=" + config.getServerPort()
                + " timeout=" + timeout);
        request.start(device.ip, config.getServerPort(), HFXServer.serverName(), timeout,
                result -> runOnUiThread(() -> {
                    Log.i(TAG, "reverse result=" + result);
                    if (reverseRequest != request) {
                        return; // 已被取消/替换
                    }
                    reverseRequest = null;
                    invitedReverseName = null;
                    if (result != ReverseConnectRequest.Result.ACCEPTED) {
                        invitedReverseIp = null;
                    }
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (result == ReverseConnectRequest.Result.ACCEPTED) {
                        scanStatus.setText(getString(R.string.reverse_accepted, device.name));
                    } else if (result == ReverseConnectRequest.Result.REJECTED) {
                        Toast.makeText(context, getString(R.string.reverse_busy, device.name),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, getString(R.string.reverse_timeout, device.name),
                                Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    /** UDP 收包线程上的粗筛：前台、空闲、没有进行中的反向流程才提示确认。 */
    private boolean isReverseAcceptable() {
        return resumed && !isFinishing() && !isDestroyed()
                && server != null && HFXServer.instance == null
                && reverseRequest == null && pendingReverseTarget == null;
    }

    private void showReverseAcceptDialog(String ip, String name, DiscoveryResponder.Reply reply) {
        Log.i(TAG, "reverse prompt ip=" + ip + " name=" + name
                + " acceptable=" + isReverseAcceptable());
        if (!isReverseAcceptable()) {
            reverseAcceptPending.set(false);
            reply.respond(false);
            return;
        }
        reverseAcceptReply = reply;
        reverseAcceptIp = ip;
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.connection_request)
                .setMessage(getString(R.string.reverse_connection_message,
                        safeLabel(name, "Android"), safeLabel(ip, "unknown")))
                .setPositiveButton(R.string.allow, (d, which) -> finishReverseAccept(true, ip))
                .setNegativeButton(R.string.deny, (d, which) -> finishReverseAccept(false, ip))
                .setCancelable(false)
                .create();
        reverseAcceptDialog = dialog;
        try {
            dialog.show();
        } catch (Throwable t) {
            // 窗口已失效时不能把对方晾到超时
            reverseAcceptDialog = null;
            reverseAcceptReply = null;
            reverseAcceptIp = null;
            reverseAcceptPending.set(false);
            reply.respond(false);
            return;
        }
        reverseAcceptHandler.removeCallbacks(reverseAcceptTimeout);
        reverseAcceptHandler.postDelayed(reverseAcceptTimeout, REVERSE_CONFIRM_TIMEOUT_MS);
    }

    /**
     * 回 A 并切换角色：接受则先回 A0（对方据此等待），再停本机空闲服务端、
     * 以客户端身份打开传输页，controller_ip 指向点击方。
     */
    private void finishReverseAccept(boolean accepted, String ip) {
        Log.i(TAG, "reverse prompt answer accepted=" + accepted + " ip=" + ip);
        reverseAcceptHandler.removeCallbacks(reverseAcceptTimeout);
        DiscoveryResponder.Reply reply = reverseAcceptReply;
        reverseAcceptReply = null;
        reverseAcceptIp = null;
        reverseAcceptPending.set(false);
        AlertDialog dialog = reverseAcceptDialog;
        reverseAcceptDialog = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        if (reply == null) {
            return;
        }
        if (!accepted) {
            reply.respond(false);
            return;
        }
        if (isFinishing() || isDestroyed() || !resumed
                || server == null || HFXServer.instance != null) {
            Log.i(TAG, "reverse accept rejected state finishing=" + isFinishing()
                    + " destroyed=" + isDestroyed() + " resumed=" + resumed
                    + " server=" + (server != null)
                    + " live=" + (HFXServer.instance != null));
            reply.respond(false);
            return;
        }
        int mode = spinnerMode.getSelectedItemPosition();
        if (mode != Config.MODE_NORMAL && checkShizukuOrReq(mode)) {
            reply.respond(false);
            return;
        }
        reply.respond(true);
        config.setConnectServerControllerIp(ip);
        config.setClientIOMode(mode);
        stopIdleServer();
        Intent intent = new Intent(context, ClientActivity.class);
        intent.putExtra("io_mode", mode);
        intent.putExtra("controller_ip", ip);
        intent.putExtra("home_dir", Environment.getExternalStorageDirectory().getAbsolutePath());
        startActivityForResult(intent, REQUEST_CODE_TRANSFER);
    }

    /** 服务端停止/异常时清理反向流程状态。 */
    private void clearReverseState() {
        Log.i(TAG, "clearReverseState pending=" + pendingReverseTarget
                + " request=" + (reverseRequest != null) + " invited=" + invitedReverseIp);
        pendingReverseTarget = null;
        invitedReverseIp = null;
        invitedReverseName = null;
        if (reverseRequest != null) {
            reverseRequest.cancel();
            reverseRequest = null;
        }
        reverseAcceptHandler.removeCallbacks(reverseAcceptTimeout);
        DiscoveryResponder.Reply reply = reverseAcceptReply;
        reverseAcceptReply = null;
        reverseAcceptIp = null;
        reverseAcceptPending.set(false);
        AlertDialog dialog = reverseAcceptDialog;
        reverseAcceptDialog = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        if (reply != null) {
            reply.respond(false);
        }
    }

    /**
     * 作为客户端连接前先停掉本机等待中的服务端：5740 端口要让给 adb reverse，
     * 也避免 127.0.0.1 的 USB_ADB 通道连回自己。已有会话时不允许直接切换角色。
     */
    private boolean stopServerForClient() {
        if (HFXServer.instance != null) {
            Toast.makeText(context, R.string.reverse_need_idle, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (server != null) {
            stopIdleServer();
        }
        return true;
    }

    private void startClient() {
        // Android 16+ 的本地网络权限在客户端一样要申请
        if (checkLocalNetworkPermissionOrRequest(false)) {
            return;
        }
        View view = View.inflate(context, R.layout.dialog_connect_server, null);
        EditText editIp = view.findViewById(R.id.edit_server_controller_ip);
        editIp.setText(config.getConnectServerControllerIp());
        EditText editMainDir = view.findViewById(R.id.edit_home_dir);
        editMainDir.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        Spinner spinner = view.findViewById(R.id.spinner_mode);
        spinner.setSelection(config.getClientIOMode());

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setTitle(R.string.connect_to_server)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = editIp.getText().toString();
            if (TextUtils.isEmpty(ip)) {
                editIp.setError(getString(R.string.please_enter_the_server_ip));
                return;
            }
            if (TextUtils.isEmpty(editMainDir.getText())) {
                editMainDir.setError(getString(R.string.please_enter_the_home_dir));
                return;
            }
            int mode = spinner.getSelectedItemPosition();
            if (mode != 0) {
                if (checkShizukuOrReq(mode)) {
                    return;
                }
            }
            if (!stopServerForClient()) {
                return;
            }
            config.setConnectServerControllerIp(ip);
            config.setClientIOMode(mode);
            Intent intent = new Intent(context, ClientActivity.class);
            intent.putExtra("io_mode", spinner.getSelectedItemPosition());
            intent.putExtra("controller_ip", ip);
            intent.putExtra("home_dir", editMainDir.getText().toString());
            startActivity(intent);
            dialog.dismiss();
        });
    }

    public boolean checkShizukuOrReq(int mode) {
        if (!Shizuku.pingBinder()) {
            showInstallShizukuDialog(context);
            return true;
        } else {
            if (Shizuku.checkSelfPermission() != 0) {
                Toast.makeText(context, R.string.wei_shou_quan_sui_ti_shi, Toast.LENGTH_SHORT).show();
                Shizuku.requestPermission(1);
                return true;
            }
        }
        System.out.println("Shizuku UID:" + Shizuku.getUid());
        if (mode == 1) {
            if (Shizuku.getUid() != 0) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                        .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_root)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return true;
            }
        } else if (mode == 2) {
            if (Shizuku.getUid() != 2000) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                        .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_adb)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return true;
            }
        }
        return false;
    }

    public void showInstallShizukuDialog(Context context) {
        // 创建对话框
        new AlertDialog.Builder(context)
                .setTitle(R.string.shizuku_not_running)
                .setMessage(R.string.shizuku_not_running_message)
                .setPositiveButton(R.string.open_shizuku, (dialog, which) -> {
                    try {
                        Intent intent = context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.please_install_shizuku, Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                        context.startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changeToStartState() {
        SessionIdleTimer.stop(SessionIdleTimer.KEY_SERVER);
        ConnectionLocks.release();
        clearReverseState();
        netCardsAdapter.setEnableModify(true);
        HFXServer.instance = null;
        server = null;
        resetConnectedStatus();
        syncConnectionForeground();
    }

    private void disconnect(boolean toast) {
        if (server == null) {
            resetServerState();
            return;
        }
        if (HFXServer.instance == null) {
            stopIdleServer();
            if (toast) {
                Toast.makeText(context, R.string.fu_wu_yi_guan_bi, Toast.LENGTH_SHORT).show();
            }
        } else {
            server.disconnect(new BackstageTask.BaseEventHandler() {
                @Override
                public void onComplete() {
                    if (toast) {
                        Toast.makeText(context, R.string.fu_wu_yi_guan_bi, Toast.LENGTH_SHORT).show();
                    }
                    resetServerState();
                    // 会话结束后仍在前台就恢复“可被扫描”
                    maybeAutoStartServer();
                }
            });
        }
    }

    /** 关闭尚在等待连接的服务端（没有会话），响应线程随 socket 关闭退出。 */
    private void stopIdleServer() {
        if (server != null) {
            server.closeServerSocket();
        }
        resetServerState();
    }

    private void resetServerState() {
        SessionIdleTimer.stop(SessionIdleTimer.KEY_SERVER);
        ConnectionLocks.release();
        clearReverseState();
        if (netCardsAdapter != null) {
            netCardsAdapter.setEnableModify(true);
        }
        HFXServer.instance = null;
        server = null;
        resetConnectedStatus();
        syncConnectionForeground();
    }

    private long getAvailableMemoryMB() {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMemory = memoryInfo.totalMem;

        long availableMemory = memoryInfo.availMem;

        long totalMemoryMB = totalMemory / (1024 * 1024);
        long availableMemoryMB = availableMemory / (1024 * 1024);

        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "MainActivity onDestroy live=" + (HFXServer.instance != null)
                + " server=" + (server != null) + " finishing=" + isFinishing());
        autoScanHandler.removeCallbacks(autoScanTick);
        if (clientScan != null) {
            clientScan.cancel();
            clientScan = null;
        }
        clearReverseState();
        if (HFXServer.instance != null) {
            // 会话进行中：socket、IO 绑定和前台保活都保留，由进程级看门狗负责超时收尾
            super.onDestroy();
            return;
        }
        // 等待中的服务端也要关掉，否则旋转重建后旧监听仍占着 5740，新的自动等待会绑定失败
        if (server != null) {
            server.closeServerSocket();
            server = null;
        }
        SessionIdleTimer.stop(SessionIdleTimer.KEY_SERVER);
        ConnectionLocks.release();
        IoBinding.release(IoBinding.OWNER_SERVER, this);
        ConnectionService.removeOwner(getApplicationContext(), ConnectionService.OWNER_MAIN);
        super.onDestroy();
    }
}
