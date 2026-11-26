package hfx.tasks;

import java.util.List;

import hfx.async.BackstageTask;
import hfx.listadapter.FileSelectAdapter;

public class DeleteFilesTask extends BackstageTask<DeleteFilesTask.EventHandler> {

    final FileSelectAdapter adapter;
    final List<String> files;

    public DeleteFilesTask(EventHandler uiHandler, FileSelectAdapter adapter, List<String> files) {
        super(uiHandler);
        this.adapter = adapter;
        this.files = files;
    }

    @Override
    protected void onStart(EventHandler handler) throws Throwable {
        // 本地逐个删、远端一次指令带整份清单：差异都收在 adapter.deleteFiles 里
        boolean ok;
        try {
            ok = adapter.deleteFiles(files, handler::onDeleting);
        } catch (hfx.core.RemoteConsentException e) {
            // 对方拒绝了本次删除：只提示一次“被拒”，不要再叠一个“删除失败”
            handler.onPermissionDeniedByPeer();
            return;
        }
        if (!ok) {
            handler.onFailed(0, files.isEmpty() ? "" : files.get(0));
            return;
        }
        handler.onSuccess();
    }

    public interface EventHandler extends BackstageTask.BaseEventHandler{
        void onDeleting(int index,String file);
        void onFailed(int index,String file);
        /** 对方拒绝授权删除（只提示，不当成删除失败） */
        void onPermissionDeniedByPeer();
        void onSuccess();
    }
}
