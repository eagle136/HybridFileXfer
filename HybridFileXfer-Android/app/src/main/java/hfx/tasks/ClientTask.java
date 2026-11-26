package hfx.tasks;

import hfx.async.BackstageTask;
import hfx.core.ClientCallBack;
import hfx.droid.DroidHFXClient;

public class ClientTask extends BackstageTask<ClientTask.Callback> {
    private final DroidHFXClient client;

    public ClientTask(Callback uiHandler, DroidHFXClient client) {
        super(uiHandler);
        this.client = client;
    }

    @Override
    protected void onStart(Callback eventHandlerProxy) throws Throwable {
        client.start(eventHandlerProxy);
    }

    public interface Callback extends ClientCallBack,BackstageTask.BaseEventHandler{}
}
