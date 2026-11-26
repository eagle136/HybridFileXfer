package hfx;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Objects;

import hfx.async.TaskManger;
import hfx.core.Utils;
import hfx.core.Directory;
import hfx.core.RemoteFile;
import hfx.core.TrafficInfo;
import hfx.core.TransferFileCallback;
import hfx.droid.HFXServer;
import hfx.listadapter.BookmarkAdapter;
import hfx.listadapter.FileSelectAdapter;
import hfx.listadapter.LocalFileSelectAdapter;
import hfx.listadapter.RemoteFileSelectAdapter;
import hfx.tasks.BTransferFileCallback;
import hfx.tasks.SendFilesToRemoteTask;
import hfx.tasks.SendFilesToShelfTask;
import hfx.ui.EdgeToEdge;

public class TransferActivity extends BaseActivity {
    private boolean isLeftFocus = true;
    private Activity context;
    //private HFXServer server;
    private HFXServer server;
    private FileSelectAdapter leftRVAdapter, rightRVAdapter;
    RecyclerView leftList;
    RecyclerView rightList;
    ConstraintLayout shadowGroupLeft;
    ConstraintLayout shadowGroupRight;
    /**
     * 是否正在等待授予"所有文件访问"权限：授予后 onResume 自动刷新左侧列表
     */
    private boolean pendingLocalAccess;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = this;
        setContentView(R.layout.activity_transfer);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // 工具栏背景延伸到状态栏下（frame_tool 有 primary 底色），
        // 根布局留出导航条高度，否则下半区最后一行被手势条盖住。
        EdgeToEdge.padTop(findViewById(R.id.frame_tool));
        EdgeToEdge.padBottom(findViewById(R.id.root));
        // 预测性返回（Android 13+/17 默认开启）走 Dispatcher，
        // onKeyDown 收不到，必须在这里接管，否则目录层级退不出去。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        leftList = findViewById(R.id.rv_left_files);
        rightList = findViewById(R.id.rv_right_files);
        shadowGroupLeft = findViewById(R.id.shadow_group_left);
        shadowGroupRight = findViewById(R.id.shadow_group_right);
        shadowGroupLeft.setVisibility(View.INVISIBLE);
        Toolbar leftSelectToolbar = findViewById(R.id.toolbar_select_left);
        Toolbar rightSelectToolbar = findViewById(R.id.toolbar_select_right);

        server = HFXServer.instance;
        if (server == null) {
            Toast.makeText(this, R.string.fu_wu_wei_yun_xing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        //初始化左边
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        leftList.setLayoutManager(layoutManager);
        OnTouchListener onTouchListener = new OnTouchListener(this, true);
        leftRVAdapter = new LocalFileSelectAdapter(context, findViewById(R.id.loading_left),
                leftList, layoutManager, leftSelectToolbar, onTouchListener, server);
        leftList.setAdapter(leftRVAdapter);
        Animation animation = AnimationUtils.loadAnimation(context, R.anim.list_anim);
        LayoutAnimationController layoutAnimationController = new LayoutAnimationController(animation);
        layoutAnimationController.setOrder(LayoutAnimationController.ORDER_NORMAL);
        layoutAnimationController.setDelay(0.2f);
        leftList.setLayoutAnimation(layoutAnimationController);
        leftList.setOnTouchListener(onTouchListener);

        //初始化右边
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        rightList.setLayoutManager(layoutManager);
        onTouchListener = new OnTouchListener(this, false);
        rightRVAdapter = new RemoteFileSelectAdapter(context, findViewById(R.id.loading_right),
                rightList, layoutManager, rightSelectToolbar, onTouchListener, server);
        rightList.setAdapter(rightRVAdapter);
        animation = AnimationUtils.loadAnimation(context, R.anim.list_anim);
        layoutAnimationController = new LayoutAnimationController(animation);
        layoutAnimationController.setOrder(LayoutAnimationController.ORDER_NORMAL);
        layoutAnimationController.setDelay(0.2f);
        rightList.setLayoutAnimation(layoutAnimationController);
        rightList.setOnTouchListener(onTouchListener);

        leftRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle(R.string.que_ren_shang_chuan)
                .setMessage(getString(R.string.chuan_shu_dao_dian_nao_mu_lu, selectedItems.size(), rightRVAdapter.getCurrentDir()))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    leftRVAdapter.cancelSelect();
                    sendFilesToRemote(selectedItems, dir, rightRVAdapter.getCurrentDirectory());
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        rightRVAdapter.setOnToTransferListener((selectedItems, dir) -> new AlertDialog.Builder(context)
                .setTitle(R.string.que_ren_xia_zai)
                .setMessage(getString(R.string.chuan_shu_dao_shou_ji_mu_lu, selectedItems.size(), leftRVAdapter.getCurrentDir()))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    rightRVAdapter.cancelSelect();
                    sendFilesToShelf(selectedItems, leftRVAdapter.getCurrentDirectory(), dir);
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

        // 进页面不再弹权限框：只在本机缺"所有文件访问"时给一句非阻塞提示，
        // 真正的授权留到用户点上传/下载时再要。
        updateLocalAccessHint();
    }

    private void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir) {
        if (!ensureStoragePermission(true)) {
            return;
        }
        TransferDialogHandler handler = new TransferDialogHandler(context, true, server.getConnectionListINames(), rightRVAdapter);
        new SendFilesToRemoteTask(handler, server, files, localDir, remoteDir).execute();
    }

    private void sendFilesToShelf(List<RemoteFile> selectedItems, Directory localDir, Directory remoteDir) {
        if (!ensureStoragePermission(false)) {
            return;
        }
        TransferDialogHandler handler = new TransferDialogHandler(context, false, server.getConnectionListINames(), leftRVAdapter);
        new SendFilesToShelfTask(handler, server, selectedItems, localDir, remoteDir).execute();
    }

    /**
     * 真正上传/下载时才申请存储权限（进页面/列目录阶段不申请、不弹窗）。
     * Root/Shizuku 的 IO 服务已具备文件权限，直接放行。
     *
     * @param isUpload true=上传（要读本机选中的文件）；false=下载（要写本机目标目录）
     */
    private boolean ensureStoragePermission(boolean isUpload) {
        if (server.isPrivileged()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                return true;
            }
            pendingLocalAccess = true;
            Toast.makeText(context, isUpload
                    ? R.string.storage_needed_upload : R.string.storage_needed_download,
                    Toast.LENGTH_LONG).show();
            openAllFilesAccessSettings();
            return false;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        pendingLocalAccess = true;
        Toast.makeText(context, isUpload
                ? R.string.storage_needed_upload : R.string.storage_needed_download,
                Toast.LENGTH_LONG).show();
        ActivityCompat.requestPermissions(this, new String[]{
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        return false;
    }

    /**
     * 本机浏览权限是否已具备：
     * - Root/Shizuku 的 IO 服务不受分区存储限制；
     * - API 30+ 需要"所有文件访问"，否则 File.listFiles() 只返回文件夹（常规文件被隐藏）；
     * - API 30 以下只需经典存储运行时权限。
     */
    private boolean hasFullLocalAccess() {
        if (server != null && server.isPrivileged()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 工具栏副标题：缺本地存储权限时给一句非阻塞提示。
     * 不弹窗、不拦浏览——用户可以先看目录，真正上传/下载时再申请。
     */
    private void updateLocalAccessHint() {
        ActionBar bar = getSupportActionBar();
        if (bar == null) {
            return;
        }
        if (hasFullLocalAccess()) {
            bar.setSubtitle(null);
        } else {
            bar.setSubtitle(R.string.local_list_limited_hint);
        }
    }

    private void openAllFilesAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台空闲超时可能已把会话拆掉，回到这里时不能继续用旧连接
        if (server == null || server.isClosed() || HFXServer.instance != server) {
            Log.i("HFXD", "TransferActivity resume invalid server=" + (server != null)
                    + " live=" + (HFXServer.instance != null));
            Toast.makeText(this, R.string.fu_wu_wei_yun_xing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        boolean granted = hasFullLocalAccess();
        if (pendingLocalAccess && granted && leftRVAdapter != null) {
            // 从设置页回来且已授权：补齐右侧目录（此前的清单可能缺常规文件）
            pendingLocalAccess = false;
            leftRVAdapter.refresh();
        }
        updateLocalAccessHint();
    }


    public void switchTo(boolean isLeft) {
        if (isLeft) {
            //切换内阴影
            if (!this.isLeftFocus) {
                shadowGroupLeft.setVisibility(View.INVISIBLE);
                shadowGroupRight.setVisibility(View.VISIBLE);
            }
            if (leftRVAdapter.isSelectMode()) {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.VISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            } else {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            }
        } else {
            if (isLeftFocus) {
                shadowGroupLeft.setVisibility(View.VISIBLE);
                shadowGroupRight.setVisibility(View.INVISIBLE);
            }
            if (rightRVAdapter.isSelectMode()) {
                rightRVAdapter.fileSelectToolbar.setVisibility(View.VISIBLE);
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            } else {
                leftRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
                rightRVAdapter.fileSelectToolbar.setVisibility(View.INVISIBLE);
            }
        }
        this.isLeftFocus = isLeft;
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.refresh) {
            if (leftRVAdapter == null || rightRVAdapter == null) {
                return true;
            }
            if (isLeftFocus) {
                leftRVAdapter.refresh();
            } else {
                rightRVAdapter.refresh();
            }
            return true;
        } else if (id == R.id.bookmark_list) {
            View dialogView = View.inflate(context, R.layout.dialog_bookmarks, null);
            RecyclerView recyclerView = dialogView.findViewById(R.id.bookmark_list);
            LinearLayoutManager layoutManager = new LinearLayoutManager(context);
            layoutManager.setOrientation(RecyclerView.VERTICAL);
            recyclerView.setLayoutManager(layoutManager);
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setPositiveButton(R.string.close, null);
            if (isLeftFocus) {
                builder.setTitle(R.string.ben_di_wen_jian_jia_shu_qian);
                recyclerView.setAdapter(new BookmarkAdapter(context, builder.show(), leftRVAdapter, false));
            } else {
                builder.setTitle(R.string.dian_nao_wen_jian_jia_shu_qian);
                recyclerView.setAdapter(new BookmarkAdapter(context, builder.show(), rightRVAdapter, true));
            }
            return true;
        } else if (id == R.id.add_bookmark) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (isLeftFocus) {
                builder.setTitle(R.string.que_ren_tian_jia_dao_ben_di_wen_jian_jia_shu_qian_ma)
                        .setMessage(leftRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            addBookmark(false, leftRVAdapter.getCurrentDir());
                        });
            } else {
                builder.setTitle(R.string.que_ren_tian_jia_dao_dian_nao_wen_jian_jia_shu_qian_ma)
                        .setMessage(rightRVAdapter.getCurrentDir())
                        .setPositiveButton(R.string.ok, (dialog, which) -> {
                            addBookmark(true, rightRVAdapter.getCurrentDir());
                        });
            }
            builder.setNegativeButton(R.string.cancel, null);
            builder.show();
            return true;
        } else if (id == R.id.mkdir) {
            View dialogView = View.inflate(context, R.layout.edit_text, null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            String title = isLeftFocus
                    ? context.getString(R.string.create_folder_mobile)
                    : context.getString(R.string.create_folder_computer);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    editText.setError(getString(R.string.qing_shu_ru_wen_jian_ming));
                    return;
                }
                if (Utils.containsIllegalCharacters(input)) {
                    editText.setError(context.getString(R.string.invalid_filename_characters));
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus) {
                    leftRVAdapter.mkdir(leftRVAdapter.getCurrentDir(), input);
                } else {
                    rightRVAdapter.mkdir(rightRVAdapter.getCurrentDir(), input);
                }
            });
        } else if (id == R.id.jump) {
            View dialogView = View.inflate(context, R.layout.edit_text, null);
            EditText editText = dialogView.findViewById(R.id.edit_text);
            editText.setText(isLeftFocus ? leftRVAdapter.getCurrentDir() : rightRVAdapter.getCurrentDir());
            String title = isLeftFocus
                    ? context.getString(R.string.jump_path_mobile)
                    : context.getString(R.string.jump_path_computer);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(dialogView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = editText.getText().toString();
                if (TextUtils.isEmpty(input)) {
                    editText.setError(getString(R.string.qing_shu_ru_lu_jing));
                    return;
                }
                dialog.dismiss();
                if (isLeftFocus) {
                    leftRVAdapter.jump(input);
                } else {
                    rightRVAdapter.jump(input);
                }
            });
        }
        return super.onOptionsItemSelected(item);
    }

    private void addBookmark(boolean isRemote, String path) {
        boolean exists;
        ConfigDB configDB = ConfigDB.getInstance(context);
        if (isRemote) {
            exists = configDB.checkRemoteBookmarkExists(path);
            if (!exists) {
                configDB.addRemoteBookmark(path);
            }
        } else {
            exists = configDB.checkLocalBookmarkExists(path);
            if (!exists) {
                configDB.addLocalBookmark(path);
            }
        }
        if (!exists) {
            String message = isRemote
                    ? getString(R.string.bookmark_added_remote)
                    : getString(R.string.bookmark_added_local);

            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.shu_qian_yi_cun_zai, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    private static class OnTouchListener implements View.OnTouchListener {

        private final TransferActivity activity;
        private final boolean isLeft;

        public OnTouchListener(TransferActivity activity, boolean isLeft) {
            this.activity = activity;
            this.isLeft = isLeft;
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                activity.switchTo(isLeft);
            }
            return false;
        }
    }

    private static class TransferDialogHandler implements BTransferFileCallback {
        Activity context;
        TransferDialog transferDialog;
        FileSelectAdapter adapter;

        public TransferDialogHandler(Activity context, boolean isUpload, List<String> channelNames, FileSelectAdapter adapter) {
            this.context = context;
            this.adapter = adapter;
            transferDialog = new TransferDialog(context, isUpload, channelNames);
            // 兜底出口：链路无响应时用户点"断开并退出"要真的把会话拆掉，
            // 否则返回键被对话框吞着，只能靠杀进程。
            transferDialog.setOnStallExit(() -> {
                HFXServer current = HFXServer.instance;
                if (current != null) {
                    // disconnect 里要等传输线程退场（最多 2s），不能占 UI 线程
                    TaskManger.start(current::disconnect);
                }
                if (!context.isFinishing()) {
                    context.finish();
                }
            });
            transferDialog.show();
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
            transferDialog.showProgress(iName, String.format("▲ [%s/%s] %s",
                    Utils.formatFileSize(targetSize),
                    Utils.formatFileSize(totalSize),
                    path));
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
            transferDialog.showProgress(iName, String.format("▼ [%s/%s] %s",
                    Utils.formatFileSize(targetSize),
                    Utils.formatFileSize(totalSize),
                    path));
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
            transferDialog.showSpeeds(trafficInfoList);
        }


        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            transferDialog.showEvent(iName, String.format("传输完毕！平均速度：%s",
                    time == 0 ? "∞" : Utils.formatSpeed(traffic / time * 1000)));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            switch (errorType) {
                case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                    transferDialog.showEvent(iName, message);
                    break;
                case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                    transferDialog.showEvent(iName, context.getString(R.string.transmission_interrupted));
                    break;
                case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                    transferDialog.showEvent(iName, "读取文件时出错");
                    break;
                case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                    transferDialog.showEvent(iName, "写入文件时出错");
                    break;
                case TransferFileCallback.ERROR_TYPE_CHANNEL_LOST:
                    transferDialog.showEvent(iName,
                            context.getString(R.string.auxiliary_channel_lost));
                    break;
            }
        }

        @Override
        public void onPermissionDenied(boolean isWrite) {
            transferDialog.dismiss();
            Toast.makeText(context,
                    isWrite ? R.string.peer_denied_write : R.string.peer_denied_read,
                    Toast.LENGTH_LONG).show();
        }

        @Override
        public void onReadFileError(String message) {
            transferDialog.setTitle("传输失败");
            transferDialog.setCloseBtnEnable(true);
            new AlertDialog.Builder(context)
                    .setTitle("读取文件时发生错误")
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }

        @Override
        public void onWriteFileError(String message) {
            transferDialog.setTitle("传输失败");
            transferDialog.setCloseBtnEnable(true);
            new AlertDialog.Builder(context)
                    .setTitle("写入文件时发生错误")
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            //文件传输完成，平均总速度：
            transferDialog.complete(isUpload, traffic, time);
            adapter.refresh();
        }

        @Override
        public void onIncomplete() {
            Toast.makeText(context, R.string.chuan_shu_shi_fa_sheng_yi_chang, Toast.LENGTH_LONG).show();
            transferDialog.setCloseBtnEnable(true);
            transferDialog.setButton(context.getString(R.string.exit), v -> context.finish());
            context.setResult(MainActivity.RESULT_CODE_SERVER_DISCONNECT);
        }

        @Override
        public void onError(Throwable th) {
            transferDialog.dismiss();
            new AlertDialog.Builder(context)
                    .setTitle("发生错误")
                    .setMessage(th.toString())
                    .setPositiveButton(R.string.ok, null)
                    .setOnDismissListener(dialog -> context.finish())
                    .show();
        }
    }

    /**
     * 返回键三段式：选择模式 → 取消选择；还能上溯 → 回上一层目录；
     * 已在默认目录 → 结束本页回主页。
     *
     * <p>旧实现把返回键全吞掉，{@code back()} 在根目录只弹一句 Toast，
     * 结果用户在传输页按返回永远回不到主界面，只能从最近任务里切。
     */
    private void handleBack() {
        FileSelectAdapter adapter = isLeftFocus ? leftRVAdapter : rightRVAdapter;
        if (adapter == null) {
            finish();
            return;
        }
        if (adapter.isSelectMode()) {
            adapter.cancelSelect();
            return;
        }
        if (!adapter.back()) {
            finish();
        }
    }
}