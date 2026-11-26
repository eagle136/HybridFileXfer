package hfx.droid;

import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import hfx.IIOService;
import hfx.core.ReadFileCall;
import hfx.core.SourceHandle;
import hfx.core.Directory;
import hfx.core.RemoteFile;

public class DroidReadFileCall extends ReadFileCall {
    private final IIOService ioService;

    public DroidReadFileCall(IIOService ioService,LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        super(buffers, files, localDir, remoteDir, operateThreadCount);
        this.ioService = ioService;
    }

    @Override
    protected boolean fileExists(String path) throws Exception {
        return ioService.fileExists(path);
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return HFXServer.listLocalFiles(ioService,path);
    }

    /**
     * 通过 IIOService 拿到 fd，包装成 FileChannel 供 sendfile 使用。
     * <p>
     * fd 由高权限的 IO 服务进程打开，本进程只持有 ParcelFileDescriptor，
     * transferTo 依然会在内核里完成零拷贝。
     */
    @Override
    public SourceHandle openSource(String path) throws Exception {
        final ParcelFileDescriptor pfd = ioService.openReadableFile(path);
        if (pfd == null) {
            throw new IOException("cannot open for read: " + path);
        }
        final FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
        final FileChannel channel = fis.getChannel();
        return new SourceHandle() {
            @Override
            public FileChannel channel() {
                return channel;
            }

            @Override
            public void close() throws IOException {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        };
    }
}
