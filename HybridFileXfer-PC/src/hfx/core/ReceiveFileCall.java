package hfx.core;

import hfx.core.TransferFileCallback;
import hfx.DataByteChannel;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;

public class ReceiveFileCall implements Callable<Void> {
    private final WriteFileCall writeFileCall;
    private final int tIndex;
    private final DataByteChannel channel;
    private final TransferFileCallback callback;
    private final TransferConnection connection;
    private final String iName;
    /** 本轮开始前该通道的累计下载字节数，用于补发轮只上报增量（同 SendFileCall） */
    private final long trafficBase;

    public ReceiveFileCall(int tIndex, TransferConnection connection,WriteFileCall writeFileCall,TransferFileCallback callback) {
        this(tIndex, connection, writeFileCall, callback, true);
    }

    /** resetTraffic=false 用于补发轮，避免把本单已统计的流量清零。 */
    public ReceiveFileCall(int tIndex, TransferConnection connection, WriteFileCall writeFileCall,
                           TransferFileCallback callback, boolean resetTraffic) {
        this.writeFileCall = writeFileCall;
        this.tIndex = tIndex;
        this.connection = connection;
        this.channel = connection.channel;
        this.callback = callback;
        iName = connection.iName;
        if (resetTraffic) {
            connection.resetTotalTrafficInfo();
        }
        this.trafficBase = connection.getTotalTraffic().downloadTraffic;
    }

    @Override
    public Void call() throws Exception {
        long startTime = System.currentTimeMillis();
        ByteBuffer buffer = null;
        boolean handedOff = false;
        try {
            while (true) {
                // 写线程已取消（写盘失败/对方读文件失败/通道中断）：本通道不再往死队列塞块，
                // 主动关掉连接，让对端 transferTo 立刻出错退出，避免旧线程活到下一次会话造成双读串包。
                if (writeFileCall.isCanceled()) {
                    closeConnectionQuietly();
                    writeFileCall.finishChannel(tIndex);
                    return null;
                }
                short header = channel.readShort();
                switch (header) {
                    case TransferIdentifiers.FOLDER: {
                        int fileIndex = channel.readInt();
                        String path = channel.readUTF();
                        long lastModified = channel.readLong();
                        if (!writeFileCall.putBlock(new FileBlock(false, fileIndex, path, lastModified, 0, 0, null), tIndex)) {
                            closeConnectionQuietly();
                            writeFileCall.finishChannel(tIndex);
                            return null;
                        }
                        break;
                    }
                    case TransferIdentifiers.FILE: {
                        int fileIndex = channel.readInt();
                        String path = channel.readUTF();
                        long lastModified = channel.readLong();
                        long totalSize = channel.readLong();
                        int index = channel.readInt();
                        int length = channel.readInt();
                        callback.onFileDownloading(iName, path,
                                index * (long) FileBlock.BLOCK_SIZE + length,
                                totalSize);
                        buffer = writeFileCall.getBuffer();
                        handedOff = false;
                        buffer.clear();
                        buffer.limit(length);
                        int read;
                        while (buffer.hasRemaining()) {
                            read = channel.read(buffer);
                            if (read == -1) {
                                throw new EOFException();
                            }
                            connection.addDownloadedBytes(read);
                        }
                        if (!writeFileCall.putBlock(new FileBlock(true, fileIndex, path, lastModified, totalSize, index, buffer), tIndex)) {
                            buffer = null;
                            closeConnectionQuietly();
                            writeFileCall.finishChannel(tIndex);
                            return null;
                        }
                        handedOff = true;
                        buffer = null;
                        break;
                    }
                    case TransferIdentifiers.EOF:
                        //System.out.println(iName + " 接收完成");
                        writeFileCall.finishChannel(tIndex);
                        callback.onChannelComplete(iName,
                                connection.getTotalTraffic().downloadTraffic - trafficBase,
                                System.currentTimeMillis() - startTime);
                        return null;
                    case TransferIdentifiers.END_OF_INTERRUPTED:
                        //System.out.println("传输通道：" + iName + " 已中断，因其他通道断开");
                        writeFileCall.cancel();
                        callback.onChannelError(iName,
                                TransferFileCallback.ERROR_TYPE_INTERRUPT,null);
                        return null;
                    case TransferIdentifiers.END_OF_READ_ERROR:
                        //System.out.println("传输通道：" + iName + " 已取消传输，因为读取文件时发生异常");
                        writeFileCall.cancel();
                        callback.onChannelError(iName,
                                TransferFileCallback.ERROR_TYPE_READ_ERROR,null);
                        return null;
                    case TransferIdentifiers.END_OF_WRITE_ERROR:
                        //System.out.println("传输通道：" + iName + " 已取消传输，因为写入文件时发生异常");
                        writeFileCall.cancel();
                        callback.onChannelError(iName,
                                TransferFileCallback.ERROR_TYPE_WRITE_ERROR,null);
                        return null;
                }
            }
        } catch (IOException e){
            writeFileCall.finishChannel(tIndex);
            if (buffer != null && !handedOff) {
                writeFileCall.recycleBuffer(buffer);
            }
            if (connection.optional) {
                callback.onChannelError(iName,
                        TransferFileCallback.ERROR_TYPE_CHANNEL_LOST, e.toString());
                throw new OptionalChannelLostException(iName, e);
            }
            callback.onChannelError(iName,
                    TransferFileCallback.ERROR_TYPE_EXCEPTION,e.toString());
            throw e;
        }
    }

    private void closeConnectionQuietly() {
        try {
            connection.close();
        } catch (IOException ignored) {
        }
    }
}
