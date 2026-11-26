package hfx.droid;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import hfx.async.BackstageTask;
import hfx.NativeMemory;
import hfx.core.FileBlock;
import hfx.core.TransferConnection;
import hfx.core.ServerNetInterface;
import hfx.droid.callback.StartServerCallback;
import hfx.DataByteChannel;

public class StartServerTask extends BackstageTask<StartServerCallback> {
    private final HFXServer server;
    private final int port;
    private final List<ServerNetInterface> interfaceList;
    private final int localBufferCount;
    private final int remoteBufferCount;
    private final int wirelessConnections;

    public StartServerTask(StartServerCallback uiHandler, HFXServer server, int port, List<ServerNetInterface> interfaceList, int localBufferCount, int remoteBufferCount, int wirelessConnections) {
        super(uiHandler);
        this.server = server;
        this.port = port;
        this.interfaceList = interfaceList;
        this.localBufferCount = localBufferCount;
        this.remoteBufferCount = remoteBufferCount;
        this.wirelessConnections = wirelessConnections;
    }

    @Override
    protected void onStart(StartServerCallback callback) throws Throwable {
        server.startServer(port,interfaceList,localBufferCount,remoteBufferCount,wirelessConnections,callback);
    }

    @Override
    protected void onError(Throwable th) {
        server.close();
    }
}
