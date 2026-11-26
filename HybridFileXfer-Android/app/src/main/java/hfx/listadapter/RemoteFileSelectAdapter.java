package hfx.listadapter;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

import hfx.MainActivity;
import hfx.core.RemoteConsentException;
import hfx.core.RemoteFile;
import hfx.R;
import hfx.droid.HFXServer;

public class RemoteFileSelectAdapter extends FileSelectAdapter {

    /** 断线只提示一次：连点几下会叠出多个对话框，且第一个关掉后页面已经 finish。 */
    private boolean connectionLostReported;
    /** 对方未授权存储权限的提示也只弹一次，避免每进一层目录弹一次 */
    private boolean remoteLimitedHintShown;

    public RemoteFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener, server);
    }

    @Override
    public List<RemoteFile> listFiles(String path) throws IOException {
        List<RemoteFile> files = server.listClientFiles(path);
        // 列目录本身不需要授权；对方若缺存储权限，清单里只有文件夹，这里只提示一句
        if (server.isRemoteListLimited() && !remoteLimitedHintShown) {
            remoteLimitedHintShown = true;
            context.runOnUiThread(() -> Toast.makeText(context,
                    R.string.remote_list_limited, Toast.LENGTH_LONG).show());
        }
        return files;
    }

    /**
     * 列举远端目录失败分两种，处理方式完全不同：
     * <ul>
     *   <li>授权被拒（{@link RemoteConsentException}）：通道还在，只是这一条请求被否，
     *       提示一句留在原页面即可，不能按断线处理；</li>
     *   <li>其它异常：控制通道已经写不出去（电脑端退出、网络中断），本地没法自愈，
     *       必须让用户回主界面重连。旧实现把它套进了 mkdir 的“创建失败”对话框，
     *       点一次文件夹就弹“创建失败”，观感就是“查看文件夹就失败”。</li>
     * </ul>
     */
    @Override
    protected void handleListException(Throwable th) {
        if (th instanceof RemoteConsentException) {
            cancelLoading();
            Toast.makeText(context, R.string.remote_browse_denied, Toast.LENGTH_LONG).show();
            return;
        }
        reportConnectionLost();
    }

    @Override
    protected int getTransferActionTitle() {
        return R.string.download;
    }

    @Override
    protected int getTransferActionIcon() {
        return R.drawable.baseline_file_download_24;
    }

    @Override
    protected String getDefaultDir(HFXServer server) {
        return server.getRemoteHomeDir();
    }

    @Override
    protected int getFileSystem(HFXServer server) {
        return server.getRemoteFileSystem();
    }

    @Override
    public boolean deleteFile(String file) throws IOException {
        return deleteFiles(java.util.Collections.singletonList(file), null);
    }

    /**
     * 远端删除走批量：一条 DELETE_FILE 带整份清单，电脑端弹一次授权。
     * 逐条发会让用户为选中的 20 个文件点 20 次确认。
     * <p>
     * 授权被拒抛 {@link RemoteConsentException}，由调用方按"被拒"提示，
     * 不要和断线混在一起。
     */
    @Override
    public boolean deleteFiles(List<String> files, DeleteProgress progress) throws IOException {
        if (progress != null && !files.isEmpty()) {
            progress.onDeleting(0, files.get(0));
        }
        return server.deleteRemoteFiles(files);
    }

    @Override
    public void mkdir(String parent, String child) {
        new Thread(() -> {
            try {
                boolean success = server.createRemoteDir(parent, child);
                context.runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(context, R.string.chuang_jian_cheng_gong, Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(context, R.string.chuang_jian_shi_bai, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException e) {
                context.runOnUiThread(this::reportConnectionLost);
            }
        }).start();
    }

    /**
     * 控制通道已断：电脑端退出或网络中断，手机这边没有任何办法自己恢复。
     * 结束传输页并把结果回给 MainActivity，让它关掉这条僵尸会话、
     * 重新回到“等待连接”，用户在电脑上重新连一次就能继续用。
     */
    private void reportConnectionLost() {
        if (connectionLostReported) {
            return;
        }
        connectionLostReported = true;
        cancelLoading();
        context.setResult(MainActivity.RESULT_CODE_SERVER_DISCONNECT);
        new AlertDialog.Builder(context)
                .setTitle(R.string.connection_lost_title)
                .setMessage(R.string.connection_lost_message)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (dialog, which) -> context.finish())
                .show();
    }
}
