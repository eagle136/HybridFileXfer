package hfx;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import hfx.IIOService;
import hfx.ParcelableRemoteFile;
/*
帮我生成对应的AIDL代码，要生成事务代码一起，例如destroy() = 16777114这种数字。
除了destroy方法，其余的数字从1开始递增
interface IIOService {
    void destroy() = 16777114;
    ParcelFileDescriptor createAndOpenWriteableFile(String path, long length) = 1;
    ParcelFileDescriptor openReadableFile(String path) = 2;
    ……
}
*/
public class IOServiceImpl extends IIOService.Stub {
    private static final String TAG = "HFX_IO";
    //由于Parcel的限制，传输太大的文件列表信息会直接崩，所以要分块
    public static final int CHUNK_SIZE = 1000;
    private final AtomicInteger localFileListSliceId = new AtomicInteger(1);
    //binder 由多个线程调用，slice 表需并发安全
    private final Map<Integer, List<ParcelableRemoteFile>> localFileListSliceMap = new ConcurrentHashMap<>();

    public void destroy(){
        System.exit(0);
    }

    public ParcelFileDescriptor createAndOpenWriteableFile(String path,long length){
        try {
            File file = new File(path);
            RandomAccessFile randomAccessFile = new RandomAccessFile(file,"rw");
            randomAccessFile.setLength(length);
            randomAccessFile.close();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public ParcelFileDescriptor openReadableFile(String path){
        try {
            return ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public int[] listFiles(String path) {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null) {
            //null 通常意味着无权限（API 30+ 缺"所有文件访问"）或路径不存在
            Log.d(TAG, "listFiles(\"" + path + "\") returned null");
            return null;
        }
        int dirCount = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                dirCount++;
            }
        }
        //诊断日志：对比"所有文件访问"授权前后，确认枚举是否被分区存储裁剪
        Log.d(TAG, "listFiles(\"" + path + "\") total=" + files.length
                + ", dirs=" + dirCount + ", files=" + (files.length - dirCount));
        int totalSize = files.length;
        ArrayList<Integer> ids = new ArrayList<>(totalSize / CHUNK_SIZE + 1);
        for (int i = 0; i < totalSize; i += CHUNK_SIZE) {
            List<ParcelableRemoteFile> remoteFiles = new ArrayList<>();
            // 直接使用索引遍历，而不是创建子数组
            for (int j = i; j < Math.min(i + CHUNK_SIZE, totalSize); j++) {
                remoteFiles.add(new ParcelableRemoteFile(files[j]));
            }
            int id = localFileListSliceId.getAndAdd(1);
            ids.add(id);
            localFileListSliceMap.put(id, remoteFiles);
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    public List<ParcelableRemoteFile> getAndRemoveFileListSlice(int sliceId) {
        return localFileListSliceMap.remove(sliceId);
    }

    public boolean setFileLastModified(String path,long time){
        return new File(path).setLastModified(time);
    }

    public boolean deleteFile(String path){
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }

    public boolean appendAndMkdirs(String parent, String child){
        return new File(parent,child).mkdirs();
    }

    public boolean mkdirs(String path){
        return new File(path).mkdirs();
    }

    public boolean fileExists(String path){
        return new File(path).exists();
    }

    public boolean isFile(String path){
        return new File(path).isFile();
    }

    public String getFileParent(String path){
        return new File(path).getParent();
    }

    /**
     * 创建文件的父路径如果不存在
     * @param path 文件的路径，然后创建的文件是它的parent
     * @return 异常信息
     */
    public String createParentDirIfNotExists(String path){
        File file = new File(path);
        File parentFile = file.getParentFile();
        //如果就是根目录的情况
        if (parentFile == null){
            return null;
        }
        return mkdirOrThrow(parentFile);
    }

    public String tryMkdirs(String path) {
        return mkdirOrThrow(new File(path));
    }

    private String mkdirOrThrow(File file){
        if (file.exists()) {
            if (file.isDirectory()) {
                return null;
            }
            // 目标已是一个普通文件：绝不能"删掉用户文件再建目录"（对方一个同名目录块就能
            // 静默吃掉本机数据），直接报错，由上层按写失败提示并结束这次传输。
            return "target exists as a file, refuse to replace it with a directory: " + file;
        }
        if (!file.mkdirs()) {
            return "cannot mkdirs " + file;
        }
        return null;
    }

}
