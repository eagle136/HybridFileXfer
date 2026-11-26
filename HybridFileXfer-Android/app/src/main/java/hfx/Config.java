package hfx;

import android.content.Context;
import android.content.SharedPreferences;

import hfx.core.HFXService;

public class Config {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_ROOT = 1;
    private static Config instance;
    SharedPreferences preferences;

    public Config(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public static synchronized Config getInstance(Context context) {
        if (instance == null){
            instance = new Config(context.getApplicationContext().getSharedPreferences("config",Context.MODE_PRIVATE));
        }
        return instance;
    }

    public int getMode(){
        return preferences.getInt("mode",MODE_NORMAL);
    }

    public void setMode(int mode){
        preferences.edit().putInt("mode",mode).apply();
    }

    /** true：同时启用 USB_ADB 通道；false：只用局域网通道 */
    public boolean isAdbMode(){
        return preferences.getBoolean("adb_mode", false);
    }

    public void setAdbMode(boolean adbMode){
        preferences.edit().putBoolean("adb_mode", adbMode).apply();
    }

    public int getServerPort() {
        return 5740;
    }

    /**
     * 接收缓冲区块数（本机）。
     * <p>
     * 4MB 块下默认 20 块，即 20 x 4MB = 80MB 上限。不再沿用 1MB 时代的大池：
     * 20 块对 3~8 条数据通道足够（双缓冲基准 + 溢出余量），按 277MB/s 下行算
     * 约 290ms 抖动余量，同时把 native 占用从 256MB 压到 80MB。
     */
    public int getLocalBufferCount() {
        return preferences.getInt("local_buffer_count",20);
    }

    public void setLocalBufferCount(int count){
        preferences.edit().putInt("local_buffer_count",count).apply();
    }

    /** 同上，但作用于对端（服务端在握手中下发给客户端创建的块数）：20 x 4MB = 80MB */
    public int getRemoteBufferCount() {
        return preferences.getInt("remote_buffer_count",20);
    }

    public void setRemoteBufferCount(int count){
        preferences.edit().putInt("remote_buffer_count",count).apply();
    }

    /**
     * 每张无线网卡开几条 TCP 连接。默认 3，范围 1~8。
     * <p>
     * 该值由服务端在握手中下发给客户端（发起传输的是服务端），改这里等于改全链路的并发流数。
     * USB_ADB 不受此值影响，恒为 2 条。
     */
    public int getWirelessConnections() {
        int value = preferences.getInt("wireless_connections",
                HFXService.CONNECTIONS_WIRELESS_DEFAULT);
        return Math.max(HFXService.CONNECTIONS_WIRELESS_MIN,
                Math.min(HFXService.CONNECTIONS_WIRELESS_MAX, value));
    }

    public void setWirelessConnections(int count) {
        int value = Math.max(HFXService.CONNECTIONS_WIRELESS_MIN,
                Math.min(HFXService.CONNECTIONS_WIRELESS_MAX, count));
        preferences.edit().putInt("wireless_connections", value).apply();
    }

    public void setClientIOMode(int mode){
        preferences.edit().putInt("client_io_mode",mode).apply();
    }

    public int getClientIOMode(){
        return preferences.getInt("client_io_mode", MODE_NORMAL);
    }

    public void setConnectServerControllerIp(String ip){
        preferences.edit().putString("connect_server_controller_ip",ip).apply();
    }

    public String getConnectServerControllerIp(){
        return preferences.getString("connect_server_controller_ip","");
    }
}
