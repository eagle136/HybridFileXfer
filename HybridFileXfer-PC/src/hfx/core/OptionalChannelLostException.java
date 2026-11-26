package hfx.core;

import java.io.IOException;

/**
 * 辅助通道（USB_ADB）断开。发送/接收任务用它区分“这条线没了”和“整单失败”：
 * 只要还有其它存活通道，传输继续；全部辅助通道都断开时才整单中止。
 */
public class OptionalChannelLostException extends IOException {
    public final String channelName;

    public OptionalChannelLostException(String channelName, Throwable cause) {
        super("optional channel lost: " + channelName, cause);
        this.channelName = channelName;
    }
}
