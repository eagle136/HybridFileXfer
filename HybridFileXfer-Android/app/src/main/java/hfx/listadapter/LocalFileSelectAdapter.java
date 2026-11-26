package hfx.listadapter;

import android.app.Activity;
import android.os.Environment;
import android.os.RemoteException;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import hfx.R;
import hfx.async.TaskManger;
import hfx.core.Directory;
import hfx.core.RemoteFile;
import hfx.droid.HFXServer;

public class LocalFileSelectAdapter extends FileSelectAdapter {
    public LocalFileSelectAdapter(Activity context, View loadingView, RecyclerView recyclerView, LinearLayoutManager
            linearLayoutManager, Toolbar fileSelectToolbar, View.OnTouchListener onTouchListener, HFXServer server) {
        super(context, loadingView, recyclerView, linearLayoutManager, fileSelectToolbar, onTouchListener, server);
    }

    @Override
    public List<RemoteFile> listFiles(String path) throws RemoteException {
        return server.listLocalFiles(path);
    }

    @Override
    protected String getDefaultDir(HFXServer server) {
        //return "/storage/emulated/0/";
        //改用方法获取内置存储目录，部分安卓设备的内部可能不是/storage/emulated/0/，导致读取目录失败
        String path = Environment.getExternalStorageDirectory().getAbsolutePath();
        System.out.println("ExternalStorageDirectory:"+path);
        return path;
    }

    @Override
    protected int getFileSystem(HFXServer server) {
        return Directory.getCurrentFileSystem();
    }

    @Override
    protected int getTransferActionTitle() {
        return R.string.upload;
    }

    @Override
    protected int getTransferActionIcon() {
        return R.drawable.baseline_upload_24;
    }


    @Override
    public boolean deleteFile(String file) throws RemoteException {
        return server.deleteLocalFile(file);
    }

    @Override
    public void mkdir(String parent, String child) {
        // binder 调用可能抛 RemoteException（:IOService 进程被系统杀掉时），
        // 原来在 UI 线程直接抛 RuntimeException 会当场崩掉页面；改到后台线程并用主线程回提示。
        TaskManger.start(() -> {
            boolean success;
            String error = null;
            try {
                success = server.createLocalDir(parent, child);
            } catch (RemoteException e) {
                success = false;
                error = e.toString();
            }
            final boolean ok = success;
            final String detail = error;
            TaskManger.postOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(context, R.string.chuang_jian_cheng_gong, Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    String message = context.getString(R.string.chuang_jian_shi_bai);
                    Toast.makeText(context,
                            detail == null ? message : message + " " + detail,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}
