package hfx.droid;

import java.io.IOException;
import java.nio.ByteBuffer;

import hfx.async.BackstageTask;
import hfx.NativeMemory;
import hfx.core.ControllerIdentifiers;
import hfx.core.TransferConnection;

public class DisconnectTask extends BackstageTask<BackstageTask.BaseEventHandler> {
    private final HFXServer server;
    public DisconnectTask(BaseEventHandler uiHandler, HFXServer server) {
        super(uiHandler);
        this.server = server;
    }

    @Override
    protected void onStart(BaseEventHandler eventHandlerProxy) throws Throwable {
        server.disconnect();
    }
}
