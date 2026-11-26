package hfx.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import hfx.core.TransferFileCallback;

public abstract class WriteFileCall implements Callable<Void> {
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final boolean[] channelFinished;
    private final ArrayList<LinkedList<FileBlock>> dequeArray;
    private volatile boolean canceled = false;
    /** 本轮已真正落盘的数据块 key；补发轮据此核对缺口 */
    private final Set<Long> receivedBlocks = new HashSet<>();

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        this.buffers = buffers;
        dequeArray = new ArrayList<>(dequeCount);
        channelFinished = new boolean[dequeCount];  // 初始化通道结束状态
        for (int i = 0; i < dequeCount; i++) {
            dequeArray.add(new LinkedList<>());
        }
    }

    @Override
    public Void call() throws Exception {
        FileChannel lastChannel = null;
        try {
            FileBlock block = takeBlock();
            FileBlock lastBlock = null;
            long cursor = 0;

            while (block != null) {
                if (block.isDirectory()) {
                    //File file = new File(block.path);
                    String file = block.path;
                    tryMkdirs(file);
                    setLastModified(file, block.lastModified);
                    synchronized (this) {
                        receivedBlocks.add(blockKey(block.fileIndex, block.index));
                    }
                    block = takeBlock();
                    continue;
                }
                //创建文件的父目录，如果不存在，保证后续文件能够创建
                //只在“换文件”时做一次：Android 端这是跨进程 binder 调用，原来每个块都调一次，
                //一个 4GB 文件（4MB 块）就白跑 1000 次往返。
                FileChannel channel;
                //如果上个文件与当前
                if (lastBlock == null || !lastBlock.path.equals(block.path)) {
                    if (lastChannel != null) {
                        closeFile();
                        setLastModified(lastBlock.path, lastBlock.lastModified);
                    }
                    createParentDirIfNotExists(block.path);
                    /*raf = new RandomAccessFile(file, "rw");
                    raf.setLength(block.totalSize);
                    channel = raf.getChannel();*/
                    channel = createAndOpenFile(block.path, block.totalSize);
                    cursor = 0;
                } else {
                    //raf = lastRaf;
                    channel = lastChannel;
                }
                //如果上个指针与当前指针不不一致就进行seek操作
                if (cursor != block.getStartPosition()) {
                    cursor = block.getStartPosition();
                    channel.position(cursor);
                }
                 /*   logSeek(block);
                } else {
                    logBlock(block);
                }*/

                ByteBuffer data = block.data;
                data.flip();
                //FileChannel.write 不保证一次写完，单次调用在极端情况下会丢尾部数据
                while (data.hasRemaining()) {
                    channel.write(data);
                }
                cursor += data.position();
                //回收缓冲区块
                buffers.add(block.data);
                synchronized (this) {
                    receivedBlocks.add(blockKey(block.fileIndex, block.index));
                }
                lastBlock = block;
                /*lastFile = file;
                lastRaf = raf;*/
                lastChannel = channel;
                block = takeBlock();
            }
            if (lastBlock != null) {
                closeFile();
                setLastModified(lastBlock.path, lastBlock.lastModified);
            }
        } catch (Exception e) {
            // 必须兜住所有异常，不能只 catch IOException：
            // Android 端目标路径不可写时 IO 服务返回 null 的 ParcelFileDescriptor（NPE），
            // :IOService 进程被杀时抛 RemoteException，这两类都不是 IOException 的子类。
            // 写线程“悄悄死掉”而 cancel() 没被调用，接收线程会继续把块塞进死队列、吃光缓冲池。
            if (lastChannel != null) {
                try {
                    lastChannel.close();
                } catch (Exception ignored) {
                }
            }
            try {
                closeFile();
            } catch (Exception ignored) {
            }
            cancel();
            throw e;
        }
        return null;
    }

    private void logSeek(FileBlock block) {
        System.out.printf("seek: %d %s %d %d %d%n",
                block.getStartPosition(), block.path, block.totalSize, block.index, block.getLength());
    }

    private void logBlock(FileBlock block) {
        System.out.printf("%s %d %d %d%n",
                block.path, block.totalSize, block.index, block.getLength());
    }

    /**
     * 取一个空闲缓冲。带取消感知：写线程已经死掉时立刻抛 IOException，
     * 接收线程才能退出，不会永久卡在 buffers.take() 上把整池缓冲占死。
     */
    public ByteBuffer getBuffer() throws InterruptedException, IOException {
        while (true) {
            if (canceled) {
                throw new IOException("write canceled");
            }
            ByteBuffer buffer = buffers.poll(100, TimeUnit.MILLISECONDS);
            if (buffer != null) {
                return buffer;
            }
        }
    }

    public boolean isCanceled() {
        return canceled;
    }

    /** (fileIndex, index) 的稳定 key，收发两端用同一算法。 */
    public static long blockKey(int fileIndex, int index) {
        return ((long) fileIndex << 32) | (index & 0xffffffffL);
    }

    /** 本轮已落盘的数据块 key 快照。 */
    public synchronized Set<Long> getReceivedBlocks() {
        return new HashSet<>(receivedBlocks);
    }

    /** 接收通道在块未入队前失败时，把缓冲还回池子。 */
    public void recycleBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            buffers.add(buffer);
        }
    }

    // 新增方法：标记通道结束
    public synchronized void finishChannel(int tIndex) {
        channelFinished[tIndex] = true;
        notify();  // 唤醒可能阻塞的写线程
    }

    /**
     * 取消写盘。必须幂等：每个接收线程在收到 END_OF_WRITE_ERROR/END_OF_READ_ERROR/
     * END_OF_INTERRUPTED 时都会调一次，重复回收同一批缓冲会让池里出现同一个 ByteBuffer
     * 的两份引用，两条线程同时往里写就是数据损坏。
     */
    public synchronized void cancel(){
        if (canceled) {
            return;
        }
        canceled = true;
        //回收未写入硬盘的块的ByteBuffer
        for (LinkedList<FileBlock> deque : dequeArray) {
            for (FileBlock fileBlock : deque) {
                if (fileBlock.data != null){
                    buffers.add(fileBlock.data);
                }
            }
            deque.clear();
        }
        notify();
    }

    /**
     * 入队一个块。返回 false 表示写线程已取消：块缓冲已归还池子，调用方应停止继续接收。
     */
    public synchronized boolean putBlock(FileBlock block, int tIndex) {
        if (canceled) {
            if (block.data != null) {
                buffers.add(block.data);
            }
            notify();
            return false;
        }
        dequeArray.get(tIndex).add(block);
        notify();  // 唤醒可能阻塞的写线程
        return true;
    }


    // 重构后的takeBlock（实现阻塞等待）
    private synchronized FileBlock takeBlock() throws InterruptedException {
        while (true) {
            FileBlock block = tryTakeBlockInternal();

            if (block != null) return block;

            //检查终止条件：所有通道结束 + 所有队列为空
            if (canceled || (allChannelsFinished() && allQueuesEmpty())) {
                return null;
            }

            wait();  // 阻塞等待直到被唤醒
        }
    }

    public synchronized FileBlock tryTakeBlockInternal() {
        FileBlock minHead = null;
        int mdqIndex = -1;

        for (int i = 0; i < dequeArray.size(); i++) {
            LinkedList<FileBlock> deque = dequeArray.get(i);
            if (!deque.isEmpty()) {
                FileBlock head = deque.getFirst();
                if (minHead == null || head.compareTo(minHead) < 0) {
                    minHead = head;
                    mdqIndex = i;
                }
            }
        }
        if (minHead != null) {
            dequeArray.get(mdqIndex).removeFirst();
        }
        return minHead;
    }

    private boolean allChannelsFinished() {
        for (boolean finished : channelFinished) {
            if (!finished) return false;
        }
        return true;
    }

    // 辅助方法：检查所有队列是否为空
    private boolean allQueuesEmpty() {
        for (LinkedList<FileBlock> deque : dequeArray) {
            if (!deque.isEmpty()) return false;
        }
        return true;
    }

    private void setLastModified(String file, long time) throws Exception {
        if (!setFileLastModified(file,time)) {
            System.out.println("Warning! file cannot set last modified:" + file);
        }
    }

    protected abstract void createParentDirIfNotExists(String path) throws Exception;
    protected abstract void tryMkdirs(String path) throws Exception;
    protected abstract FileChannel createAndOpenFile(String path,long length) throws Exception;
    protected abstract void closeFile() throws Exception;
    protected abstract boolean setFileLastModified(String path,long time) throws Exception;
}
