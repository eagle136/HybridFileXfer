package hfx;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import hfx.IIOService;
import hfx.core.ConsentProvider;
import hfx.core.Utils;
import hfx.core.TrafficInfo;
import hfx.core.TransferFileCallback;
import hfx.droid.DroidHFXClient;
import hfx.tasks.ClientTask;
import hfx.tasks.ConnectServerTask;
import hfx.ui.EdgeToEdge;

public class ClientActivity extends BaseActivity implements ConsentProvider {
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 5;

    Activity context;
    int ioMode;
    String controllerIp;
    String homeDir;
    ProgressDialog progressDialog;
    DroidHFXClient client;
    IIOService iioService;
    private volatile CountDownLatch pendingConsentLatch;
    TextView txvState;
    TextView txvUploadSpeed, txvDownloadSpeed;
    LinearLayout lConnections;
    private final Map<String, Holder> holderMap = new HashMap<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastProgressTime = new HashMap<>();
    /** API 33+ 通知权限是否已申请过（只弹一次系统框） */
    private boolean notificationPermissionRequested;
    /** 已建立/接管的会话；后台超时收尾后回到前台要退出本页 */
    private boolean sessionStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);
        context = this;
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        EdgeToEdge.padTop(toolbar);
        EdgeToEdge.padBottom(findViewById(R.id.root));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmLeave();
            }
        });
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras == null) {
            Toast.makeText(context, "need launch extras", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }


        ioMode = extras.getInt("io_mode");
        controllerIp = extras.getString("controller_ip");
        homeDir = extras.getString("home_dir");

        txvState = findViewById(R.id.state);
        txvUploadSpeed = findViewById(R.id.upload_speed);
        txvDownloadSpeed = findViewById(R.id.download_speed);
        lConnections = findViewById(R.id.connections);

        if (ClientSession.isActive() && ClientSession.client() != null) {
            // Activity 重建后接管仍在后台运行的会话，不重新握手
            adoptSession();
            return;
        }

        progressDialog = new ProgressDialog(context);
        progressDialog.setCancelable(false);
        progressDialog.setTitle(getString(R.string.connecting));
        progressDialog.setMessage(getString(R.string.starting_io_service));
        progressDialog.show();

        bindAndStartService();
    }

    private void adoptSession() {
        client = ClientSession.client();
        controllerIp = ClientSession.controllerIp();
        ioMode = ClientSession.ioMode();
        renderChannels(ClientSession.channelNames());
        txvState.setText(R.string.waiting_for_tasks);
        ClientSession.setUi(buildCallback());
        sessionStarted = true;
        startConnectionForeground();
    }

    private void bindAndStartService() {
        IoBinding.acquire(IoBinding.OWNER_CLIENT, this, ioMode, new IoBinding.Listener() {
            @Override
            public void onServiceConnected(IIOService service) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                onIoServiceConnected(service);
            }

            @Override
            public void onServiceDisconnected() {
                if (!isFinishing() && !isDestroyed()) {
                    dialogErrorMessage(getString(R.string.lian_jie_shi_fa_sheng_cuo_wu),
                            getString(R.string.fu_wu_yi_ting_zhi));
                }
            }
        });
    }

    private void onIoServiceConnected(IIOService service) {
        iioService = service;
        progressDialog.setMessage(getString(R.string.connecting_server));
        client = new DroidHFXClient(controllerIp, 5740, homeDir, iioService, context);
        client.setClientName(android.os.Build.MODEL);
        // Root/Shizuku 的 IO 服务不受分区存储限制，列目录不会缺文件
        client.setPrivileged(ioMode != Config.MODE_NORMAL);
        // 只有控制通道走 127.0.0.1（adb reverse）时才启用 USB_ADB；
        // 局域网控制下 127.0.0.1:5740 可能是本机自己的服务端。
        client.setAllowUsbAdb(hfx.core.HFXClient.isLoopbackAddress(controllerIp));
        client.setConsentProvider(this);
        new ConnectServerTask(new ConnectServerTask.Callback() {
            @Override
            public void onConnectSuccess(List<String> connectedChannels) {
                Log.i("HFXD", "client activity onConnectSuccess channels="
                        + (connectedChannels == null ? 0 : connectedChannels.size())
                        + " finishing=" + isFinishing());
                if (isFinishing() || isDestroyed()) {
                    // 页面已销毁：别把会话带进后台，直接关掉本次连接
                    try {
                        client.close();
                    } catch (Throwable ignored) {
                    }
                    IoBinding.release(IoBinding.OWNER_CLIENT, getApplicationContext());
                    return;
                }
                txvState.setText(R.string.waiting_for_tasks);
                Toast.makeText(context, R.string.connection_successful, Toast.LENGTH_SHORT).show();
                progressDialog.dismiss();
                renderChannels(connectedChannels);
                sessionStarted = true;
                ClientSession.begin(client, buildCallback(), context, controllerIp, ioMode,
                        connectedChannels);
                startConnectionForeground();
            }

            @Override
            public void onConnectingControlChannel(String address, int port) {
                progressDialog.setMessage(getString(R.string.connecting_controller,address));
            }

            @Override
            public void onAwaitingPeerConfirmation() {
                progressDialog.setMessage(getString(R.string.waiting_peer_confirmation));
            }

            @Override
            public void onConnectionDenied() {
                Log.i("HFXD", "client activity denied by peer");
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.peer_denied_connection));
            }

            @Override
            public void onVersionMismatch(int localVersion, int remoteVersion) {
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.Inconsistent_protocol_versions,localVersion,remoteVersion));
            }

            @Override
            public void onConnectControlFailed() {
                Log.i("HFXD", "client activity control connect failed");
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.control_channel_connection_failed));
            }

            @Override
            public void onNoTransferChannel() {
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.no_transfer_channel));
            }

            @Override
            public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
                progressDialog.setMessage(getString(R.string.connecting_transfer_channel,
                        name, inetAddress.getHostAddress(), bindAddress == null ?
                        "null" : bindAddress.getHostAddress()));
            }

            @Override
            public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        String.format(getString(R.string.transfer_channel_connection_failed),
                        name, inetAddress.getHostAddress(), e));
            }

            @Override
            public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.c_oom_title),
                        getString(R.string.failed_to_create_buffer_block,
                                createdBuffers,requiredBuffers,maxMemoryMB));
            }

            @Override
            public void onRemoteOOM() {
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.c_remote_oom_title), getString(R.string.c_remote_oom_message));
            }

            @Override
            public void onError(Throwable th) {
                Log.i("HFXD", "client activity connect error: " + th);
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.lian_jie_shi_fa_sheng_cuo_wu), th.toString());
            }
        }, client).execute();
    }

    /** 连接建立后把主进程提为前台服务，防止切后台被 ROM 杀进程断连。 */
    private void startConnectionForeground() {
        requestNotificationPermissionIfNeeded();
        ConnectionService.setOwner(getApplicationContext(), ConnectionService.OWNER_CLIENT,
                getString(R.string.conn_service_connected,
                        controllerIp == null ? "" : controllerIp));
    }

    /** API 33+ 通知权限；拒绝只影响通知可见性，不影响保活本身。 */
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && client != null) {
            // 授权后补刷一次前台通知
            ConnectionService.setOwner(getApplicationContext(), ConnectionService.OWNER_CLIENT,
                    getString(R.string.conn_service_connected,
                            controllerIp == null ? "" : controllerIp));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台空闲超时可能已把会话收尾：这里没有可展示的连接了，回主页重新发起
        if (sessionStarted && !ClientSession.isActive()) {
            finish();
            return;
        }
        // 浏览/列目录不再要求授权：进页面不弹任何权限框，
        // 只有对方真的要读/写/删本机文件时（requestData）才提示。
    }

    private ClientTask.Callback buildCallback() {
        ClientTask.Callback callback = new ClientTask.Callback() {
            @Override
            public void onReceiving() {
                txvState.setText(R.string.zheng_zai_jie_shou_wen_jian);
            }

            @Override
            public void onSending() {
                txvState.setText(R.string.zheng_zai_fa_song_wen_jian);
            }

            @Override
            public void onExit() {
                Log.i("HFXD", "client activity onExit");
                Toast.makeText(context, R.string.lian_jie_yi_guan_bi, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
                showProgress(iName, String.format("▲ [%s/%s] %s",
                        Utils.formatFileSize(targetSize),
                        Utils.formatFileSize(totalSize),
                        path));
            }

            @Override
            public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
                showProgress(iName, String.format("▼ [%s/%s] %s",
                        Utils.formatFileSize(targetSize),
                        Utils.formatFileSize(totalSize),
                        path));
            }

            @Override
            public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
                long totalUploadSpeed = 0;
                long totalDownloadSpeed = 0;
                for (TrafficInfo trafficInfo : trafficInfoList) {
                    Holder holder = holderMap.get(trafficInfo.iName);
                    if (holder != null) {
                        holder.uploadSpeed.setText(Utils.formatSpeed(trafficInfo.uploadTraffic));
                        holder.downloadSpeed.setText(Utils.formatSpeed(trafficInfo.downloadTraffic));
                        totalUploadSpeed += trafficInfo.uploadTraffic;
                        totalDownloadSpeed += trafficInfo.downloadTraffic;
                    }
                }
                txvUploadSpeed.setText(Utils.formatSpeed(totalUploadSpeed));
                txvDownloadSpeed.setText(Utils.formatSpeed(totalDownloadSpeed));
            }

            @Override
            public void onChannelComplete(String iName, long traffic, long time) {
                showEvent(iName, getString(R.string.transfer_completed,time == 0 ? "∞" :
                        Utils.formatSpeed(traffic / time * 1000)));
            }

            @Override
            public void onChannelError(String iName, int errorType, String message) {
                switch (errorType) {
                    case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                        showEvent(iName, message);
                        break;
                    case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                        showEvent(iName, context.getString(R.string.transmission_interrupted));
                        break;
                    case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                        showEvent(iName, getString(R.string.du_qu_wen_jian_shi_chu_cuo));
                        break;
                    case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                        showEvent(iName, getString(R.string.xie_ru_wen_jian_shi_chu_cuo));
                        break;
                    case TransferFileCallback.ERROR_TYPE_CHANNEL_LOST:
                        showEvent(iName, getString(R.string.auxiliary_channel_lost));
                        break;
                }
            }

            @Override
            public void onPermissionDenied(boolean isWrite) {
                txvState.setText(isWrite
                        ? R.string.denied_write_request : R.string.denied_read_request);
            }

            @Override
            public void onDeleteDenied() {
                txvState.setText(R.string.denied_delete_request);
            }

            @Override
            public void onReadFileError(String message) {
                txvState.setText(R.string.du_qu_wen_jian_shi_fa_sheng_cuo_wu);
            }

            @Override
            public void onWriteFileError(String message) {
                txvState.setText(R.string.xie_ru_wen_jian_shi_fa_sheng_cuo_wu);
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onComplete(boolean isUpload, long traffic, long time) {
                // time 可能为 0（极小文件毫秒级传完），除零会崩在 UI 线程
                long elapsed = time > 0 ? time : 1L;
                txvState.setText((isUpload ? "▲ " : "▼ ") + Utils.formatSpeed(traffic / elapsed * 1000) +
                        " · " + Utils.formatTime(time) + " · " + Utils.formatFileSize(traffic));
                for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
                    Holder holder = entry.getValue();
                    holder.downloadSpeed.setText(Utils.formatSpeed(0));
                    holder.uploadSpeed.setText(Utils.formatSpeed(0));
                }
                txvUploadSpeed.setText("0B/s");
                txvDownloadSpeed.setText("0B/s");
            }

            @Override
            public void onIncomplete() {
                Log.i("HFXD", "client activity onIncomplete");
                Toast.makeText(context, R.string.chuan_shu_shi_fa_sheng_yi_chang, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onError(Throwable th) {
                Log.i("HFXD", "client activity session error: " + th);
                dialogErrorMessage(getString(R.string.disconnection), th.toString());
            }
        };
        return callback;
    }

    private void renderChannels(List<String> names) {
        lConnections.removeAllViews();
        holderMap.clear();
        if (names == null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(context);
        for (String channelName : names) {
            View itemView = inflater.inflate(R.layout.item_net_interface_status, lConnections, false);
            Holder holder = new Holder(itemView);
            holder.channelName.setText(channelName);
            holder.channelIcon.setImageDrawable(
                    ContextCompat.getDrawable(context, NetCardIcon.matchIdForIName(channelName)));
            lConnections.addView(itemView);
            holderMap.put(channelName, holder);
        }
    }

    private void showEvent(String iName, String event) {
        Objects.requireNonNull(holderMap.get(iName))
                .transferEvent.setText(event);
    }

    /**
     * 传输进度：投递到主线程 + 100ms 节流。
     * 原来是在传输线程里逐块 String.format + setText，会直接把网络线程拖住。
     */
    private void showProgress(String iName, String event) {
        final Holder holder = holderMap.get(iName);
        if (holder == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long last = lastProgressTime.get(iName);
        if (last != null && now - last < 100) {
            return;
        }
        lastProgressTime.put(iName, now);
        uiHandler.post(() -> holder.transferEvent.setText(event));
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

    /** 客户端不承担服务端连接确认，握手阶段一律放行。 */
    @Override
    public boolean requestConnection(String peerName, String peerIp, long timeoutMs) {
        return true;
    }

    /**
     * 数据授权：阻塞传输线程，弹窗（本次允许 / 允许一次 / 拒绝），30s 无操作按拒绝。
     * 阻塞期间刚好是两个传输命令之间的空闲，不会影响其它 UI。
     * <p>
     * 弹窗里写清"要读/写/删哪些文件"：清单由对端在请求授权之前下发，
     * 太长时窗口内滚动，清单区高度封顶到屏高 38%，不会把弹窗撑出主界面。
     */
    @Override
    public int requestData(String peerName, String peerIp, int operation,
                           List<String> paths, long timeoutMs) {
        if (!hasLocalStoragePermission()) {
            final java.util.List<String> targets = paths;
            runOnUiThread(() -> promptLocalStorageFor(operation, targets));
            return ConsentProvider.CONSENT_DENY;
        }
        if (isFinishing() || isDestroyed()) {
            return ConsentProvider.CONSENT_DENY;
        }
        final long timeout = timeoutMs > 0 ? timeoutMs : ConsentProvider.DEFAULT_TIMEOUT_MS;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(ConsentProvider.CONSENT_DENY);
        pendingConsentLatch = latch;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }
            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.permission_request)
                    .setView(buildConsentView(peerName, peerIp, operation, paths))
                    .setPositiveButton(R.string.allow_session, (d, which) -> {
                        result.set(ConsentProvider.CONSENT_SESSION);
                        latch.countDown();
                    })
                    .setNeutralButton(R.string.allow_once, (d, which) -> {
                        result.set(ConsentProvider.CONSENT_ONCE);
                        latch.countDown();
                    })
                    .setNegativeButton(R.string.deny, (d, which) -> {
                        result.set(ConsentProvider.CONSENT_DENY);
                        latch.countDown();
                    })
                    .setCancelable(false)
                    .create();
            dialog.show();
            uiHandler.postDelayed(() -> {
                if (latch.getCount() > 0) {
                    result.set(ConsentProvider.CONSENT_DENY);
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
        pendingConsentLatch = null;
        return result.get();
    }

    /** 弹窗里最多列出多少条路径；更多时只给"共 N 项"的摘要，列表本身可滚动 */
    private static final int CONSENT_PATHS_SHOWN = 200;

    private View buildConsentView(String peerName, String peerIp, int operation,
                                  List<String> paths) {
        View view = getLayoutInflater().inflate(R.layout.dialog_data_consent, null);
        TextView action = view.findViewById(R.id.consent_action);
        TextView peer = view.findViewById(R.id.consent_peer);
        ListView list = view.findViewById(R.id.consent_paths);
        TextView summary = view.findViewById(R.id.consent_summary);

        int count = paths == null ? 0 : paths.size();
        action.setText(consentActionText(operation, count));
        peer.setText(getString(R.string.consent_peer_line,
                safeLabel(peerName, "unknown"), safeLabel(peerIp, "unknown")));

        int shown = Math.min(count, CONSENT_PATHS_SHOWN);
        List<String> items = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            items.add(paths.get(i));
        }
        if (items.isEmpty()) {
            items.add(getString(R.string.consent_no_paths));
        }
        list.setAdapter(new ArrayAdapter<>(this, R.layout.item_consent_path,
                R.id.consent_path, items));

        // 清单区高度：内容够短就贴内容，够长就封顶到屏高 38% 并内部滚动
        float density = getResources().getDisplayMetrics().density;
        int rowHeight = (int) (44 * density);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.38f);
        android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = Math.min(maxHeight, Math.max(rowHeight, rowHeight * items.size()));
        list.setLayoutParams(lp);

        if (count > shown) {
            summary.setText(getString(R.string.consent_more_paths, count, shown));
            summary.setVisibility(View.VISIBLE);
        } else {
            summary.setVisibility(View.GONE);
        }
        return view;
    }

    private String consentActionText(int operation, int count) {
        if (operation == ConsentProvider.OP_WRITE) {
            return getString(R.string.consent_write_action, count);
        }
        if (operation == ConsentProvider.OP_DELETE) {
            return getString(R.string.consent_delete_action, count);
        }
        return getString(R.string.consent_read_action, count);
    }

    private void dialogErrorMessage(String title, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(dialog -> {
                    finish();
                }).show();
    }

    /** 普通模式才需要应用自己的存储权限；Root/Shizuku 的 IO 服务不受限。 */
    private boolean hasLocalStoragePermission() {
        if (ioMode != Config.MODE_NORMAL) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 对端真的要读写/删除本机文件，而本机还没有"所有文件访问"时走到这里：
     * 说清本次动作与目标，再跳设置页。列表阶段不弹这个框。
     */
    private void promptLocalStorageFor(int operation, java.util.List<String> paths) {
        int count = paths == null ? 0 : paths.size();
        Toast.makeText(context, getString(R.string.storage_permission_needed_for_action,
                consentActionText(operation, count)), Toast.LENGTH_LONG).show();
        openAllFilesAccessSettings();
    }

    private void requestLocalStoragePermission() {
        openAllFilesAccessSettings();
    }

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }
    }

    @Override
    protected void onDestroy() {
        Log.i("HFXD", "ClientActivity onDestroy active=" + ClientSession.isActive()
                + " sameClient=" + (ClientSession.client() == client)
                + " finishing=" + isFinishing());
        CountDownLatch latch = pendingConsentLatch;
        if (latch != null) {
            latch.countDown();
        }
        if (ClientSession.isActive() && ClientSession.client() == client) {
            // 会话归进程所有：只摘掉 UI 目标，连接、前台保活和 IO 绑定继续
            ClientSession.setUi(null);
            super.onDestroy();
            return;
        }
        IoBinding.release(IoBinding.OWNER_CLIENT, this);
        ConnectionService.removeOwner(getApplicationContext(), ConnectionService.OWNER_CLIENT);
        super.onDestroy();
    }

    /**
     * 客户端不允许在本机单方面"挂断"再继续用，但把返回键彻底吞掉会让用户
     * 卡在这一页出不去（旧实现只弹一句 Toast）。这里改成确认后真正退出：
     * 关闭客户端连接 → 回主页，语义仍然是"断开连接"。
     */
    private void confirmLeave() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.leave_client_title)
                .setMessage(R.string.leave_client_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Log.i("HFXD", "client leave confirmed");
                    ClientSession.end();
                    finish();
                })
                .show();
    }

    private static class Holder {
        final View itemView;
        ImageView channelIcon;
        TextView channelName, uploadSpeed, downloadSpeed, transferEvent;

        public Holder(View itemView) {
            this.itemView = itemView;
            channelIcon = itemView.findViewById(R.id.channel_icon);
            channelName = itemView.findViewById(R.id.channel_name);
            uploadSpeed = itemView.findViewById(R.id.upload_speed);
            downloadSpeed = itemView.findViewById(R.id.download_speed);
            transferEvent = itemView.findViewById(R.id.txv_transfer_event);
        }
    }

}