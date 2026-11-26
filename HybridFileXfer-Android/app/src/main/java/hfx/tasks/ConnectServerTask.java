package hfx.tasks;

import hfx.async.BackstageTask;
import hfx.core.HFXClient;
import hfx.core.ConnectServerCallback;
import hfx.droid.DroidHFXClient;

public class ConnectServerTask extends BackstageTask<ConnectServerTask.Callback> {
    private final DroidHFXClient client;
    public ConnectServerTask(Callback uiHandler, DroidHFXClient client) {
        super(uiHandler);
        this.client = client;
    }

    @Override
    protected void onStart(Callback callback) throws Throwable {
        if (!client.connect(callback)) {
            client.freeBuffers();
        }
    }

    @Override
    protected void onError(Throwable th) {
        client.freeBuffers();
    }

    public interface Callback extends ConnectServerCallback,BackstageTask.BaseEventHandler{
    }
}
