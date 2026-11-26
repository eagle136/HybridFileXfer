package hfx.droid;

import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedBlockingDeque;

import hfx.IIOService;
import hfx.core.WriteFileCall;

public class DroidWriteFileCall extends WriteFileCall {
    private final IIOService ioService;
    private ParcelFileDescriptor pfd;
    private FileOutputStream fileOutputStream;
    private FileChannel channel;
    public DroidWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, IIOService ioService) {
        super(buffers, dequeCount);
        this.ioService = ioService;
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        String exception = ioService.createParentDirIfNotExists(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        String exception = ioService.tryMkdirs(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected FileChannel createAndOpenFile(String path, long length) throws Exception {
        ParcelFileDescriptor descriptor = ioService.createAndOpenWriteableFile(path, length);
        if (descriptor == null) {
            // IO 服务在权限不足、目标路径是目录、路径非法、文件已被删时会返回 null。
            // 以前直接解引用，NPE 不会被 WriteFileCall 的 catch(IOException) 兜住，
            // 写线程悄悄死掉 → 接收线程继续读块、吃光缓冲池 → 整个会话卡死。
            throw new IOException("无法创建文件（权限不足、目标是目录或路径非法）: " + path);
        }
        pfd = descriptor;
        fileOutputStream = new FileOutputStream(descriptor.getFileDescriptor());
        channel = fileOutputStream.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        // 幂等：正常收尾和写失败路径都会调一次，字段已置空就不再碰底层 fd
        FileChannel ch = channel;
        channel = null;
        if (ch != null) {
            try {
                ch.close();
            } catch (Exception ignored) {
            }
        }
        FileOutputStream fos = fileOutputStream;
        fileOutputStream = null;
        if (fos != null) {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
        }
        ParcelFileDescriptor d = pfd;
        pfd = null;
        if (d != null) {
            try {
                d.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected boolean setFileLastModified(String path, long time) throws Exception {
        return ioService.setFileLastModified(path,time);
    }
}
