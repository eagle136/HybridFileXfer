package hfx.core;

import java.util.List;

import hfx.core.TrafficInfo;

public interface TransferFileCallback {
    int ERROR_TYPE_EXCEPTION = 1;
    int ERROR_TYPE_INTERRUPT = 2;
    int ERROR_TYPE_READ_ERROR = 3;
    int ERROR_TYPE_WRITE_ERROR = 4;
    /** 辅助通道（USB_ADB）断开；剩余通道继续传输，不算整单失败 */
    int ERROR_TYPE_CHANNEL_LOST = 5;

    void onFileUploading(String iName, String path, long targetSize, long totalSize);

    void onFileDownloading(String iName, String path, long targetSize, long totalSize);

    void onSpeedInfo(List<TrafficInfo> trafficInfoList);

    void onChannelComplete(String iName, long traffic, long time);

    void onChannelError(String iName, int errorType, String message);//异常信息 or 断开

    void onReadFileError(String message);

    void onWriteFileError(String message);

    void onComplete(boolean isUpload,long traffic, long time);

    //传输通道有其中一个断开时
    void onIncomplete();

    /**
     * 对端拒绝/超时，或本机拒绝了本次写入/读取请求。
     *
     * @param isWrite true=写入（上传到本机）；false=读取（对方从本机拉取）
     */
    default void onPermissionDenied(boolean isWrite) {
    }

}
