package hfx.droid;

import android.app.ActivityManager;
import android.content.Context;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import hfx.NativeMemory;
import hfx.IIOService;
import hfx.core.HFXClient;
import hfx.core.ReadFileCall;
import hfx.core.WriteFileCall;
import hfx.core.Directory;
import hfx.core.RemoteFile;

public class DroidHFXClient extends HFXClient {
    private final IIOService iioService;
    private final Context context;
    /** Root/Shizuku 模式的 IO 服务跑在特权进程里，不受分区存储限制 */
    private volatile boolean privileged;

    public DroidHFXClient(String serverControllerAddress, int serverPort,String homeDir, IIOService iioService, Context context) {
        super(serverControllerAddress, serverPort,homeDir);
        this.iioService = iioService;
        this.context = context;
    }

    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    @Override
    public ByteBuffer createBuffer(int size) {
        return NativeMemory.allocateLargeBuffer(size);
    }

    @Override
    public long getAvailableMemoryMB() {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMemory = memoryInfo.totalMem;

        long availableMemory = memoryInfo.availMem;

        long totalMemoryMB = totalMemory / (1024 * 1024);
        long availableMemoryMB = availableMemory / (1024 * 1024);

        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected boolean deleteLocalFile(String path) throws Exception {
        return iioService.deleteFile(path);
    }

    @Override
    protected boolean mkdir(String parent, String child) throws Exception {
        return iioService.appendAndMkdirs(parent,child);
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return HFXServer.listLocalFiles(iioService,path);
    }

    /**
     * 普通模式下没有"所有文件访问"时，File.listFiles() 只返回文件夹，
     * 对端看到的清单会缺常规文件——用这个布尔给它一句非阻塞提示。
     */
    @Override
    protected boolean isLocalAccessLimited() {
        if (privileged) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return !android.os.Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers,dequeCount,iioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new DroidReadFileCall(iioService,buffers,files,localDir,remoteDir,operateThreadCount);
    }

    /**
     * 释放缓冲池里的 native 直连内存。
     * <p>
     * 用 poll 逐个摘下来再 free：for-each 遍历期间若还有线程从池里取缓冲，
     * 弱一致迭代器可能把一个已经交出去、正在被写入的缓冲再 free 一次（use-after-free）。
     */
    public synchronized void freeBuffers(){
        ByteBuffer buffer;
        while ((buffer = buffers.poll()) != null) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
    }
}
