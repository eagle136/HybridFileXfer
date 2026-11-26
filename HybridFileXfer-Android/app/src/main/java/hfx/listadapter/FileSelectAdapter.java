package hfx.listadapter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.RemoteException;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.l4digital.fastscroll.FastScroller;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import hfx.R;
import hfx.core.Utils;
import hfx.core.Directory;
import hfx.core.RemoteFile;
import hfx.droid.HFXServer;
import hfx.tasks.DeleteFilesTask;
import hfx.tasks.ListFilesTask;

public abstract class FileSelectAdapter extends RecyclerView.Adapter<FileSelectAdapter.ViewHolder> implements FastScroller.SectionIndexer {
    Activity context;
    LayoutInflater layoutInflater;
    protected HFXServer server;
    List<RemoteFile> files = new ArrayList<>();
    HashSet<RemoteFile> selectedItems = new HashSet<>();
    public View loadingView;
    public RecyclerView recyclerView;
    public LinearLayoutManager linearLayoutManager;
    public Toolbar fileSelectToolbar;
    View.OnTouchListener onTouchListener;
    private int lastSelectedCount = 0;
    private int consecutiveListFailures = 0;
    /**
     * 目录列举请求令牌：每次 cd/back/refresh/jump 都换一个新令牌，
     * 只有最新一次请求的回调才会落到列表上。用于丢弃"点了 A 目录又马上点 B 目录"
     * 时先发起、后返回的 A 目录结果 —— 否则旧结果会覆盖新目录，界面停在 A。
     */
    private int listRequestToken = 0;
    private OnConfirmFileSelectionListener onConfirmFileSelectionListener;
    private LinkedList<DirPosition> positions = new LinkedList<>();
    private final int fileSystem;

    public FileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        this.context = context;
        this.server = server;
        this.loadingView = loadingView;
        this.recyclerView = recyclerView;
        this.linearLayoutManager = linearLayoutManager;
        this.onTouchListener = onTouchListener;
        this.fileSelectToolbar = fileSelectToolbar;

        this.fileSystem = getFileSystem(server);

        layoutInflater = LayoutInflater.from(context);
        jump(getDefaultDir(server));
        fileSelectToolbar.getMenu()
                .findItem(R.id.select_all)
                .setOnMenuItemClickListener(item -> {
                    selectAll();
                    return true;
                });
        fileSelectToolbar.getMenu()
                .findItem(R.id.delete)
                .setOnMenuItemClickListener(item -> {
                    List<String> files = new ArrayList<>(selectedItems.size());
                    for (RemoteFile file : selectedItems) {
                        files.add(file.getPath());
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(context.getString(R.string.que_ren_shan_chu_, selectedItems.size()));
                    if (selectedItems.size() < 4) {
                        for (String file : files) {
                            sb.append(file).append("\n");
                        }
                    } else {
                        for (int i = 0; i < 3; i++) {
                            String file = files.get(i);
                            sb.append(file).append("\n");
                        }
                        sb.append("……");
                    }
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.que_ren_shan_chu)
                            .setMessage(sb.toString())
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.ok, (dialog, which) -> {
                                cancelSelect();

                                ProgressDialog progressDialog = new ProgressDialog(context);
                                progressDialog.setCancelable(false);
                                progressDialog.setProgress(0);
                                progressDialog.setTitle(context.getString(R.string.shan_chu_zhong__));
                                progressDialog.setMessage("");
                                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                                progressDialog.setMax(files.size());
                                progressDialog.show();

                                new DeleteFilesTask(new DeleteFilesTask.EventHandler() {
                                    @Override
                                    public void onDeleting(int index, String file) {
                                        progressDialog.setProgress(index);
                                        progressDialog.setMessage(file);
                                    }

                                    @Override
                                    public void onFailed(int index, String file) {
                                        progressDialog.dismiss();
                                        new AlertDialog.Builder(context)
                                                .setTitle(R.string.shan_chu_wen_jian_shi_bai)
                                                .setMessage(context.getString(R.string.delete_failed_message, index, file))
                                                .setPositiveButton(R.string.ok, null)
                                                .show();
                                    }

                                    @Override
                                    public void onPermissionDeniedByPeer() {
                                        progressDialog.dismiss();
                                        Toast.makeText(context, R.string.peer_denied_delete,
                                                Toast.LENGTH_LONG).show();
                                    }

                                    @Override
                                    public void onSuccess() {
                                        progressDialog.dismiss();
                                        Toast.makeText(context, R.string.file_delete_success, Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onComplete() {
                                        refresh();
                                    }
                                }, this, files).execute();
                            }).show();
                    return true;
                });
        MenuItem transferItem = fileSelectToolbar.getMenu().findItem(R.id.transfer);
        transferItem.setTitle(getTransferActionTitle());
        transferItem.setIcon(getTransferActionIcon());
        transferItem.setOnMenuItemClickListener(item -> {
            onConfirmFileSelection(selectedItems);
            return true;
        });
        fileSelectToolbar.setNavigationOnClickListener(v -> cancelSelect());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(layoutInflater.inflate(R.layout.item_file, parent, false), onTouchListener);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RemoteFile item = files.get(position);
        View itemView = holder.itemView;
        holder.fileName.setText(item.getName());
        if (position == 0) {//..文件夹
            itemView.setBackground(null);
            holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.folder));
            itemView.setOnClickListener(v -> {
                if (!isSelectMode()) {
                    if (!back()) {
                        Toast.makeText(context, R.string.yi_jing_dao_gen_mu_lu_le,
                                Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(context, R.string.qing_xian_qu_xiao_xuan_ze, Toast.LENGTH_SHORT).show();
                }
            });
            itemView.setOnLongClickListener(null);
            holder.fileSize.setText("");
            holder.dateTime.setText("");
            return;
        }

        holder.dateTime.setText(Utils.formatDateTime(item.lastModified()));
        if (item.isDirectory()) {
            holder.fileSize.setText("");
        } else {
            holder.fileSize.setText(Utils.formatFileSize(item.getSize()));
        }
        if (selectedItems.contains(item)) {
            itemView.setBackgroundColor(context.getColor(R.color.blue_background_light));
            holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.baseline_check_circle_24));
        } else {
            itemView.setBackground(null);
            if (item.isDirectory()) {
                holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.folder));
            } else {
                holder.fileIcon.setImageDrawable(context.getDrawable(R.drawable.file));
            }
        }

        itemView.setOnLongClickListener(v -> {
            if (!isSelectMode()) {
                if (!selectedItems.contains(item)) {
                    addSelectItem(item);
                } else {
                    removeSelectItem(item);
                }
                notifyItemChanged(holder.getAdapterPosition());
            } else {
                if (selectedItems.contains(item)) {
                    onConfirmFileSelection(selectedItems);
                }
            }
            return true;
        });

        itemView.setOnClickListener(v -> {
            if (isSelectMode()) {
                if (!selectedItems.contains(item)) {
                    addSelectItem(item);
                } else {
                    removeSelectItem(item);
                }
                notifyItemChanged(holder.getAdapterPosition());
            } else if (item.isDirectory()) {
                cd(item.getPath());
            }
        });
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    public boolean isSelectMode() {
        return !selectedItems.isEmpty();
    }

    private void addSelectItem(RemoteFile item) {
        selectedItems.add(item);
        updateSelectedCount();
    }

    private void removeSelectItem(RemoteFile item) {
        selectedItems.remove(item);
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        int size = selectedItems.size();
        fileSelectToolbar.setTitle(context.getString(R.string.selected_items_count, size));

        if (selectedItems.isEmpty()) {
            fileSelectToolbar.setVisibility(View.INVISIBLE);
        } else if (lastSelectedCount == 0) {
            fileSelectToolbar.setVisibility(View.VISIBLE);
        }
        lastSelectedCount = size;
    }

    protected void loading() {
        // 首先将视图的透明度设为 0（隐藏状态）
        loadingView.setAlpha(0f);
        // 然后将视图设置为可见
        loadingView.setVisibility(View.VISIBLE);

        // 使用 ViewPropertyAnimator 渐渐显示
        loadingView.animate()
                .alpha(1f)  // 最终透明度为 1（完全显示）
                .setDuration(1000);  // 动画持续时间，单位为毫秒

        recyclerView.setVisibility(View.INVISIBLE);
    }

    protected void cancelLoading() {
        loadingView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        recyclerView.scheduleLayoutAnimation();
    }

    @SuppressLint("NotifyDataSetChanged")
    protected void changeFiles(List<RemoteFile> files) {
        loadingView.setVisibility(View.GONE);
        this.files = files;
        consecutiveListFailures = 0;
        notifyDataSetChanged();
        recyclerView.setVisibility(View.VISIBLE);
        recyclerView.scheduleLayoutAnimation();
    }

    public void cd(String dir) {
        loading();
        DirPosition currentPosition = positions.get(positions.size() - 1);
        //String dir = currentPosition.dir + name + "/";
        //获取当前可见的第一个元素的位置
        currentPosition.position = linearLayoutManager.findFirstVisibleItemPosition();

        System.out.println("cd " + dir);
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    DirPosition dirPosition = new DirPosition(new Directory(dir, fileSystem), 0);
                    positions.add(dirPosition);
                    recyclerView.scrollToPosition(0);
                    changeFiles(files);
                } else {
                    cancelLoading();
                    Toast.makeText(context, R.string.jin_ru_mu_lu_shi_bai, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
    }

    public void jump(String dir) {
        loading();
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    positions = createNewDirPositions(new Directory(dir, fileSystem));
                    recyclerView.scrollToPosition(0);
                    changeFiles(files);
                } else {
                    cancelLoading();
                    Toast.makeText(context, R.string.cannot_read_dir, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
    }

    public void refresh() {
        loading();
        listFilesASync(getCurrentDir(), files -> {
            if (files != null) {
                changeFiles(files);
            } else {
                cancelLoading();
                Toast.makeText(context, R.string.shua_xin_shi_bai, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 返回上一层目录。
     *
     * @return true 表示已经发起上溯（事件被消费）；false 表示当前已经在最初进入的目录，
     *         调用方自行决定是提示还是退出页面 —— 旧实现直接 return，
     *         导致返回键在根目录变成一个死循环。
     */
    public boolean back() {
        if (positions.size() < 2) {
            return false;
        }
        loading();
        DirPosition dirPosition = positions.get(positions.size() - 2);
        String dir = dirPosition.dir.path;
        System.out.println("back " + dir);
        listFilesASync(dir, new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                if (files != null) {
                    if (files.isEmpty()) {
                        cancelLoading();
                        Toast.makeText(context, R.string.fu_mu_lu_wen_jian_shu_wei_kong, Toast.LENGTH_SHORT).show();
                    } else {
                        positions.removeLast();
                        System.out.println(positions);
                        changeFiles(files);
                        linearLayoutManager.scrollToPositionWithOffset(dirPosition.position, 0);
                    }
                } else {
                    cancelLoading();
                    Toast.makeText(context, R.string.wu_quan_fang_wen_shang_ceng_mu_lu, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Throwable th) {
                handleListException(th);
            }
        });
        return true;
    }

    public abstract List<RemoteFile> listFiles(String path) throws Exception;

    /**
     * 列举目录发生异常时的默认处理（如 IO 服务进程被杀、binder 断开）：
     * 结束 loading 状态并提示，随后自动重试；连续失败 3 次后停止重试，
     * 避免"卡 loading"或无限重试循环。
     */
    protected void handleListException(Throwable th) {
        consecutiveListFailures++;
        cancelLoading();
        String dir = getCurrentDir();
        if (consecutiveListFailures >= 3) {
            Toast.makeText(context, context.getString(R.string.list_files_failed, dir),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(context, context.getString(R.string.list_files_failed_retry, dir),
                Toast.LENGTH_LONG).show();
        //1 秒后自动重试；若期间用户已导航到其他目录则放弃
        recyclerView.postDelayed(() -> {
            if (dir.equals(getCurrentDir())) {
                refresh();
            }
        }, 1000);
    }

    protected abstract String getDefaultDir(HFXServer server);

    protected abstract int getFileSystem(HFXServer server);

    protected abstract int getTransferActionTitle();

    protected abstract int getTransferActionIcon();

    //protected abstract void onDeleteFiles(List<String> files);
    public abstract boolean deleteFile(String file) throws RemoteException, IOException;

    /**
     * 批量删除。默认逐个调用 {@link #deleteFile}（本机删除不需要授权，
     * 逐条删还能按条刷新进度）；远端适配器覆写成"一次指令 + 一次授权"。
     *
     * @param progress 每开始删一项前回调一次，可为 null
     * @return 全部成功才 true
     */
    public boolean deleteFiles(List<String> files, DeleteProgress progress)
            throws RemoteException, IOException {
        for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);
            if (progress != null) {
                progress.onDeleting(i, file);
            }
            if (!deleteFile(file)) {
                return false;
            }
        }
        return true;
    }

    public interface DeleteProgress {
        void onDeleting(int index, String file);
    }

    public abstract void mkdir(String parent, String child);

    private void listFilesASync(String path, ListFilesTask.CallBack callBack) {
        final int token = ++listRequestToken;
        new ListFilesTask(new ListFilesTask.CallBack() {
            @Override
            public void onResult(List<RemoteFile> files) {
                // 不是最新请求的结果直接丢弃，避免旧目录覆盖新目录
                if (token != listRequestToken) {
                    return;
                }
                callBack.onResult(files);
            }

            @Override
            public void onError(Throwable th) {
                if (token != listRequestToken) {
                    return;
                }
                callBack.onError(th);
            }

            @Override
            public void onComplete() {
                if (token != listRequestToken) {
                    return;
                }
                callBack.onComplete();
            }
        }, path, this).execute();
    }


    public void setOnToTransferListener(OnConfirmFileSelectionListener onConfirmFileSelectionListener) {
        this.onConfirmFileSelectionListener = onConfirmFileSelectionListener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void cancelSelect() {
        selectedItems.clear();
        updateSelectedCount();
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAll() {
        selectedItems.clear();
        selectedItems.addAll(files);
        // 排除”..“文件夹；列表为空（切目录/刷新失败期间）时直接取 get(0) 会越界
        if (!files.isEmpty()) {
            selectedItems.remove(files.get(0));
        }
        updateSelectedCount();
        notifyDataSetChanged();
    }

    public String getCurrentDir() {
        return getCurrentDirectory().path;
    }

    public Directory getCurrentDirectory(){
        if (positions.isEmpty()){
            return new Directory("/",fileSystem);
        }
        return positions.get(positions.size() - 1).dir;
    }

    public interface OnConfirmFileSelectionListener {
        void onConfirmFileSelection(List<RemoteFile> selectedItems, Directory dir);
    }

    protected void onConfirmFileSelection(HashSet<RemoteFile> selectedItems) {
        List<RemoteFile> remoteFiles = new ArrayList<>(selectedItems);
        if (onConfirmFileSelectionListener != null) {
            onConfirmFileSelectionListener.onConfirmFileSelection(remoteFiles, getCurrentDirectory());
        }
    }


    private LinkedList<DirPosition> createNewDirPositions(@NotNull Directory directory) {
        LinkedList<DirPosition> positions = new LinkedList<>();
        while (directory != null) {
            positions.add(new DirPosition(directory, 0));
            directory = directory.parent();
        }
        Collections.reverse(positions);
        System.out.println(positions);
        return positions;
    }

    private static class DirPosition {
        public Directory dir;
        public int position;

        public DirPosition(Directory dir, int position) {
            this.dir = dir;
            this.position = position;
        }

        @Override
        public String toString() {
            return "DirPosition{" +
                    "dir='" + dir + '\'' +
                    ", position=" + position +
                    '}';
        }
    }

    @Override
    public CharSequence getSectionText(int position) {
        if (position < 0 || position >= files.size()) {
            return "";
        }
        String name = files.get(position).getName();
        // 空文件名（异常/被删条目）直接 charAt(0) 会 StringIndexOutOfBounds
        if (name == null || name.isEmpty()) {
            return "";
        }
        return String.valueOf(name.charAt(0));
    }


    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName, dateTime, fileSize;

        public ViewHolder(@NonNull View itemView, View.OnTouchListener onTouchListener) {
            super(itemView);
            itemView.setOnTouchListener(onTouchListener);
            fileIcon = itemView.findViewById(R.id.file_icon);
            fileName = itemView.findViewById(R.id.file_name);
            dateTime = itemView.findViewById(R.id.date_time);
            fileSize = itemView.findViewById(R.id.file_size);
        }
    }
}
