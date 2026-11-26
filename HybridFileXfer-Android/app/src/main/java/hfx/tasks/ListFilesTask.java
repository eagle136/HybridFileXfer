package hfx.tasks;

import java.util.List;

import hfx.async.BackstageTask;
import hfx.core.Utils;
import hfx.core.RemoteFile;
import hfx.listadapter.FileSelectAdapter;

public class ListFilesTask extends BackstageTask<ListFilesTask.CallBack> {

    public final String path;
    public final FileSelectAdapter adapter;

    public ListFilesTask(CallBack uiHandler, String path, FileSelectAdapter adapter) {
        super(uiHandler);
        this.path = path;
        this.adapter = adapter;
    }

    @Override
    protected void onStart(CallBack eventHandlerProxy) throws Throwable {
        List<RemoteFile> listFiles = adapter.listFiles(path);
        if (listFiles != null) {
            Utils.sortFiles(listFiles);
            listFiles.add(0,new RemoteFile("..", "", 0, 0, true));
            eventHandlerProxy.onResult(listFiles);
        } else {
            eventHandlerProxy.onResult(null);
        }
    }

    public interface CallBack extends BackstageTask.BaseEventHandler{
        void onResult(List<RemoteFile> files);
    }
}
