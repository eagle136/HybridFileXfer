package hfx.core;

public interface ClientCallBack extends TransferFileCallback {
    void onReceiving();
    void onSending();
    void onExit();

    /** 对方请求删除本机文件，但被拒绝或授权超时。 */
    default void onDeleteDenied() {
    }
}
