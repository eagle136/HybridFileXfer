package hfx.droid.callback;

import hfx.async.BackstageTask;

public interface StartServerCallback extends BackstageTask.BaseEventHandler {
    void onBindFailed(int port);
    void onStatedServer();
    void onAccepted(String name);
    void onAcceptFailed(String name);
    /** 本次连接尝试中途失败，已接受的通道全部作废，界面应回到等待连接。 */
    void onConnectAborted();
    void onPcOOM();
    void onMeOOM(int created, int localBufferCount);
    void onConnectSuccess();
}
