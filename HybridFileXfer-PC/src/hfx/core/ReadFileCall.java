package hfx.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import hfx.core.Directory;
import hfx.core.RemoteFile;

public abstract class ReadFileCall implements Callable<Void> {
    public static final FileBlock END_POINT = new FileBlock(true, -1, "END_POINT", 0, 0, -1, null);
    public static final FileBlock INTERRUPT = new FileBlock(true, -1, "INTERRUPT", 0, 0, -1, null);
    public static final FileBlock READ_ERROR = new FileBlock(true, -1, "READ_ERROR", 0, 0, -1, null);
    public static final FileBlock WRITE_ERROR = new FileBlock(true, -1, "WRITE_ERROR", 0, 0, -1, null);

    private final LinkedBlockingDeque<FileBlock> deque = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    private final int operateThreadCount;
    private int fileIndex = -1;

    /**
     * 在途块栅栏：数据块被通道取走时 +1，发送完成或回队时 -1。
     * 只有全部数据块确认发送完（且队列为空）才放 END_POINT，
     * 避免辅助通道晚失败时数据块丢失、接收端提前收尾。
     */
    private final Object flowLock = new Object();
    private int inFlight;
    private boolean aborted;
    /** recycleAllBuffer 只能生效一次，防多条通道同时失败时重复回收同一个缓冲 */
    private boolean buffersRecycled;
    /** 本轮生成的全部数据块描述符，供接收端上报缺失后定位补发内容 */
    private final List<FileBlock> dataBlocks = new ArrayList<>();

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        this.buffers = buffers;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.operateThreadCount = operateThreadCount;
    }

    @Override
    public Void call() throws Exception {
        try {
            for (RemoteFile file : files) {
                if (!fileExists(file.getPath())) {
                    continue;
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file);
                }
            }
            awaitAllBlocksSent();
            synchronized (flowLock) {
                for (int i = 0; i < operateThreadCount; i++) {
                    deque.add(END_POINT);
                }
            }
        } catch (Exception e) {
            //当发生读取错误时
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(READ_ERROR);
            }
            throw e;
        }
        return null;
    }

    private void listFilesAndRead(RemoteFile folder) throws Exception {
        List<RemoteFile> files = listFiles(folder.getPath());
        if (files != null) {
            for (RemoteFile file : files) {
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file); // 递归遍历子文件夹
                }
            }
        }
    }

    /**
     * 只生成“块描述符”，不读取任何文件数据。
     * <p>
     * 真正的数据由 {@link SendFileCall} 通过 {@code FileChannel.transferTo} 零拷贝直接
     * 从 page cache 送入 socket，因此这里既不占用缓冲池，也不产生用户态拷贝。
     * <p>
     * 顺带修掉了原实现里的两个问题：
     * <ul>
     *   <li>{@code channel.read()} 返回值未检查导致的死循环；</li>
     *   <li>每个文件都要 open/close 一次只为拿 size。</li>
     * </ul>
     */
    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        String targetPath = localDir.generateTransferPath(file.getPath(), remoteDir);
        if (file.isDirectory()) {
            FileBlock folder = new FileBlock(false, fileIndex, targetPath, file.lastModified(), 0, 0, null);
            synchronized (flowLock) {
                dataBlocks.add(folder);
            }
            deque.add(folder);
            return;
        }
        // 用实际打开后的 size，而不是 RemoteFile.getSize() 的列表快照：
        // 若文件在列出列表之后被改动，快照会偏小，导致静默截断（接收端得到一个残缺但“看起来正常”的文件）。
        long length;
        SourceHandle probe = openSource(file.getPath());
        try {
            length = probe.channel().size();
        } finally {
            try {
                probe.close();
            } catch (Exception ignored) {
            }
        }
        long lastModified = file.lastModified();
        if (length <= 0) {
            // 空文件也要发一个 length=0 的块，否则接收端不会创建该文件
            FileBlock empty = new FileBlock(fileIndex, targetPath, file.getPath(), lastModified, 0, 0, 0);
            synchronized (flowLock) {
                dataBlocks.add(empty);
            }
            deque.add(empty);
            return;
        }
        int i = 0;
        long remaining = length;
        while (remaining > 0) {
            int blkSize = (int) Math.min(remaining, FileBlock.BLOCK_SIZE);
            FileBlock block = new FileBlock(fileIndex, targetPath, file.getPath(), lastModified, length, i, blkSize);
            synchronized (flowLock) {
                dataBlocks.add(block);
            }
            deque.add(block);
            remaining -= blkSize;
            i++;
        }
    }

    public void recycleBuffer(ByteBuffer buffer) {
        buffers.add(buffer);
    }

    /**
     * 取一个块并计入在途。返回 END_POINT/INTERRUPT 等哨兵块时不计数。
     * <p>
     * 队列为空时用 50ms 短轮询而不是 {@code deque.take()}：abort() 只唤醒
     * {@code flowLock}，唤不动卡在 take() 上的发送线程，失败路径上这些线程会永远
     * 留在那儿（每失败一次漏 N 条线程）。同时 inFlight 的累加和出队放在同一把锁里，
     * 避免 awaitAllBlocksSent 在"已出队但还没计数"的窗口里看到 inFlight==0 提前收尾。
     */
    public FileBlock takeBlockCounted() throws InterruptedException {
        while (true) {
            FileBlock block;
            synchronized (flowLock) {
                block = deque.pollFirst();
                if (block != null) {
                    if (block.fileIndex != -1) {
                        inFlight++;
                    }
                    return block;
                }
                if (aborted) {
                    return null;
                }
            }
            block = deque.pollFirst(50, TimeUnit.MILLISECONDS);
            if (block != null) {
                synchronized (flowLock) {
                    if (block.fileIndex != -1) {
                        inFlight++;
                    }
                    return block;
                }
            }
        }
    }

    /** 一个数据块发送完成（或回队失败前）调用。 */
    public void onBlockFinished() {
        synchronized (flowLock) {
            if (inFlight > 0) {
                inFlight--;
            }
            flowLock.notifyAll();
        }
    }

    /** 辅助通道失败：把当前块放回队首，交给其它通道重传。 */
    public void requeueBlock(FileBlock block) {
        if (block == null) {
            return;
        }
        synchronized (flowLock) {
            deque.addFirst(block);
            flowLock.notifyAll();
        }
    }

    /** 所有数据块确认发出前，读线程不提前放 END_POINT。 */
    private void awaitAllBlocksSent() throws InterruptedException {
        synchronized (flowLock) {
            while (!aborted && (inFlight > 0 || !deque.isEmpty())) {
                flowLock.wait(100);
            }
        }
    }

    /** 本轮生成的全部数据块（不含目录块），顺序即发送顺序。 */
    public List<FileBlock> getDataBlocks() {
        synchronized (flowLock) {
            return new ArrayList<>(dataBlocks);
        }
    }

    /** 清掉上一轮可能残留的 END_POINT（有通道失败时它没被取走）。 */
    public void clearPendingEndPoints() {
        synchronized (flowLock) {
            java.util.Iterator<FileBlock> iterator = deque.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == END_POINT) {
                    iterator.remove();
                }
            }
        }
    }

    /** 补发轮：把缺失块放回队列，交给存活通道重发。 */
    public void enqueueResend(List<FileBlock> blocks) {
        synchronized (flowLock) {
            for (FileBlock block : blocks) {
                deque.add(block);
            }
            flowLock.notifyAll();
        }
    }

    /**
     * 补发轮：等这批块全部确认发出后，再按存活通道数放 END_POINT。
     * 单独起线程，避免阻塞调用方（调用方还要启动发送线程）。
     */
    public void startEndPointBarrier(final int channelCount) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    awaitAllBlocksSent();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (flowLock) {
                    for (int i = 0; i < channelCount; i++) {
                        deque.add(END_POINT);
                    }
                }
            }
        }, "ResendReader");
        thread.setDaemon(true);
        thread.start();
    }

    /** 整单终止：唤醒栅栏，不再等待在途块。 */
    public void abort() {
        synchronized (flowLock) {
            aborted = true;
            flowLock.notifyAll();
        }
    }

    //当对方写入时发生错误时
    public void shutdownByWriteError() {
        abort();
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount; i++) {
            deque.addFirst(WRITE_ERROR);
        }
    }

    //当其中任意一条通道断开时
    public void shutdownByConnectionBreak() {
        abort();
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount - 1; i++) {
            deque.addFirst(INTERRUPT);
        }
    }

    private void recycleAllBuffer() {
        synchronized (flowLock) {
            // 多条通道可能同时失败并各调一次；重复回收会让池里出现同一个缓冲的两份引用
            if (buffersRecycled) {
                return;
            }
            buffersRecycled = true;
            for (FileBlock fileBlock : deque) {
                if (fileBlock.data != null) {
                    recycleBuffer(fileBlock.data);
                }
            }
        }
    }

    /**
     * 打开一个只读源句柄供 sendfile 使用。
     * 句柄生命周期由 {@link SendFileCall} 管理（同一文件复用，换文件即关闭）。
     */
    public abstract SourceHandle openSource(String path) throws Exception;

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

}
