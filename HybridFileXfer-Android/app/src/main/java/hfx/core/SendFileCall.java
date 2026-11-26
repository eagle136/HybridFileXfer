package hfx.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Callable;

import hfx.core.TransferFileCallback;
import hfx.DataByteChannel;

public class SendFileCall implements Callable<Void> {
    private final ReadFileCall readFileCall;
    private final DataByteChannel channel;
    /**
     * 必须是原始 SocketChannel。
     * {@code FileChannel.transferTo} 只有在目标是 SocketChannel 时才会走 sendfile/TransmitFile
     * 零拷贝路径；若传入包装过的 DataByteChannel，JDK 会退化成普通读写拷贝，收益归零。
     */
    private final SocketChannel socket;
    private final TransferConnection connection;
    private final TransferFileCallback callback;

    /** 当前复用的源文件句柄，换文件时替换 */
    private SourceHandle source;
    private String sourcePath;
    /**
     * 本轮开始前该通道的累计上传字节数。补发轮复用同一连接时，onChannelComplete
     * 只能上报本轮增量，否则调用方按通道累加会把上一轮的流量再算一遍。
     */
    private final long trafficBase;

    public SendFileCall(ReadFileCall readFileCall, TransferConnection connection, TransferFileCallback callback) {
        this(readFileCall, connection, callback, true);
    }

    /** resetTraffic=false 用于补发轮，避免把本单已统计的流量清零。 */
    public SendFileCall(ReadFileCall readFileCall, TransferConnection connection,
                        TransferFileCallback callback, boolean resetTraffic) {
        this.readFileCall = readFileCall;
        this.connection = connection;
        this.channel = connection.channel;
        this.socket = connection.socket;
        this.callback = callback;
        if (resetTraffic) {
            connection.resetTotalTrafficInfo();
        }
        this.trafficBase = connection.getTotalTraffic().uploadTraffic;
    }

    @Override
    public Void call() throws Exception {
        FileBlock fileBlock = null;
        boolean counted = false;
        try {
            long startTime = System.currentTimeMillis();
            while (true) {
                fileBlock = readFileCall.takeBlockCounted();
                if (fileBlock == null) {
                    // 整单已被 abort（写失败/致命错误）：没有块可发了，直接退出
                    counted = false;
                    break;
                }
                counted = fileBlock.fileIndex != -1;
                //-1为特殊块
                if (fileBlock.fileIndex == -1) {
                    if (fileBlock == ReadFileCall.END_POINT) {
                        channel.writeShort(TransferIdentifiers.EOF);
                        callback.onChannelComplete(connection.iName,
                                connection.getTotalTraffic().uploadTraffic - trafficBase,
                                System.currentTimeMillis() - startTime);
                    } else if (fileBlock == ReadFileCall.INTERRUPT) {
                        channel.writeShort(TransferIdentifiers.END_OF_INTERRUPTED);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_INTERRUPT, null);
                    } else if (fileBlock == ReadFileCall.READ_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_READ_ERROR);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_READ_ERROR, null);
                    } else if (fileBlock == ReadFileCall.WRITE_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_WRITE_ERROR);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_WRITE_ERROR, null);
                    }
                    break;
                }
                connection.markInFlight(true);
                if (!fileBlock.isFile) {
                    // 目录：类型 + 序号 + 路径 + 修改时间，一次写出
                    channel.writeBlockHeader(TransferIdentifiers.FOLDER, fileBlock.fileIndex,
                            fileBlock.path, fileBlock.lastModified, false, 0, 0, 0);
                } else {
                    // 块头一次写出：原来是 7 次独立 write，等于每块 7 次 syscall + 7 个小包
                    channel.writeBlockHeader(TransferIdentifiers.FILE, fileBlock.fileIndex,
                            fileBlock.path, fileBlock.lastModified, true,
                            fileBlock.totalSize, fileBlock.index, fileBlock.getLength());

                    callback.onFileUploading(connection.iName, fileBlock.path,
                            fileBlock.getStartPosition() + fileBlock.getLength(),
                            fileBlock.totalSize);

                    transferBlock(fileBlock);
                }
                // 被看门狗接管时，这块的在途计数已经由它释放，不能再减一次
                if (!connection.isReaped()) {
                    readFileCall.onBlockFinished();
                }
                counted = false;
                connection.markInFlight(false);
            }
        } catch (Exception e) {
            if (connection.optional) {
                if (counted && fileBlock != null && !connection.isReaped()) {
                    // 先回队、再减在途：保证读线程放 END_POINT 前块已在队首，
                    // 活着的通道还能把它重传出去。
                    readFileCall.requeueBlock(fileBlock);
                    readFileCall.onBlockFinished();
                }
                // 哨兵（EOF 等）写失败不重传：该通道已消耗掉自己那份哨兵，
                // 其余通道仍有对应的 END_POINT。
                connection.markInFlight(false);
                // 主动关掉半死通道，让对端也能立刻结束该通道的读循环
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
                callback.onChannelError(connection.iName,
                        TransferFileCallback.ERROR_TYPE_CHANNEL_LOST, e.toString());
                throw new OptionalChannelLostException(connection.iName, e);
            }
            if (counted && fileBlock != null) {
                if (fileBlock.data != null) {
                    //回收文件分块的ByteBuffer，否则导致这个Buffer免费了
                    readFileCall.recycleBuffer(fileBlock.data);
                }
                readFileCall.onBlockFinished();
            }
            connection.markInFlight(false);
            readFileCall.shutdownByConnectionBreak();
            callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            throw e;
        } finally {
            closeSource();
        }
        return null;
    }

    /**
     * 零拷贝发送一个块：数据从 page cache 直接进 socket，不经过用户内存。
     * <p>
     * transferTo 允许返回小于请求的字节数（发送缓冲区满时），必须循环推进，
     * 否则会导致流错位——这是零拷贝改造最容易踩的坑。
     */
    private void transferBlock(FileBlock block) throws Exception {
        long remain = block.getLength();
        if (remain <= 0) {
            // 空文件：只发了头部，接收端据此创建一个 0 字节文件
            return;
        }
        // 必须用 sourcePath（本地真实路径）打开源文件；block.path 是交给对端的落盘路径，
        // 拿它 open 会得到一个指向对端文件系统的路径，必然失败。
        SourceHandle src = sourceFor(block.sourcePath);
        FileChannel fc = src.channel();
        long offset = block.fileOffset;
        while (remain > 0) {
            long written = fc.transferTo(offset, remain, socket);
            if (written <= 0) {
                throw new IOException("transferTo made no progress: n=" + written
                        + ", offset=" + offset + ", remain=" + remain
                        + ", source=" + block.sourcePath + ", target=" + block.path);
            }
            offset += written;
            remain -= written;
            connection.addUploadedBytes(written);
        }
    }

    private SourceHandle sourceFor(String path) throws Exception {
        if (path == null) {
            throw new IOException("block carries no sourcePath; refusing to open an unknown path");
        }
        if (source != null && path.equals(sourcePath)) {
            return source;
        }
        closeSource();
        source = readFileCall.openSource(path);
        sourcePath = path;
        return source;
    }

    private void closeSource() {
        if (source != null) {
            try {
                source.close();
            } catch (IOException ignored) {
            }
            source = null;
            sourcePath = null;
        }
    }
}
