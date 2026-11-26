package hfx.core;

import java.io.IOException;

/**
 * 对端明确拒绝了本次操作（授权被拒/超时），而不是连接出了问题。
 * <p>
 * 需要和普通 {@link IOException} 区分开：控制通道断开时用户要回主界面重连，
 * 而授权被拒只是这一条请求被否掉，页面和数据都还在，不能按断线处理。
 */
public class RemoteConsentException extends IOException {
    public RemoteConsentException(String message) {
        super(message);
    }
}
