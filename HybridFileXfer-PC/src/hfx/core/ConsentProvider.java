package hfx.core;

import java.util.List;

/**
 * 连接确认与传输授权接口。
 *
 * <p>服务端用它确认“是否允许某客户端连接”；客户端用它确认“是否允许对方
 * 写入/读取/删除本机数据”。实现方负责把结果投到自己的 UI，并在超时后返回拒绝。
 *
 * <p>授权请求必须带上本次操作涉及的具体路径（{@code paths}），这样 UI 才能写清
 * “读哪几个文件 / 写哪几个文件 / 删哪几个文件”，而不是笼统地问“是否允许读取数据”。
 *
 * <p>列举目录不再单独授权：浏览只是看，真正的读/写/删各自会问一次，
 * 所以原来独立的 OP_BROWSE 已移除。
 *
 * <p>{@link #AUTO_ALLOW} 供 CLI / 旧流程使用，保持“无交互界面也能传”的原有行为。
 */
public interface ConsentProvider {
    /** 拒绝当前这一次请求 */
    int CONSENT_DENY = 0;
    /** 只允许当前这一条传输命令（单文件或多文件都算一条） */
    int CONSENT_ONCE = 1;
    /** 本次连接会话内，同类操作不再询问（读、写、删除分别记录） */
    int CONSENT_SESSION = 2;

    /** 对方要写入本机数据（上传到本机） */
    int OP_WRITE = 1;
    /** 对方要读取本机数据（从本机拉取） */
    int OP_READ = 2;
    /** 对方要删除本机文件 */
    int OP_DELETE = 3;

    long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * 单次授权请求携带的路径条数上限：只用来识别控制通道错位/恶意对端，
     * 不做截断（截断会让"选中的文件"和"实际传输的文件"对不上）。
     * UI 侧自行决定只列前几条，剩下的用"共 N 项"概括。
     */
    int MAX_PATHS = 10_000;

    /**
     * 服务端在握手阶段调用：是否允许该客户端连接。
     *
     * @return true 允许；false 拒绝（含超时）
     */
    boolean requestConnection(String peerName, String peerIp, long timeoutMs);

    /**
     * 客户端在传输/删除命令前调用：是否允许对方写入/读取/删除本机数据。
     *
     * @param paths 本次操作涉及的具体路径，可能被截断到 {@link #MAX_PATHS} 条
     * @return {@link #CONSENT_DENY} / {@link #CONSENT_ONCE} / {@link #CONSENT_SESSION}
     */
    int requestData(String peerName, String peerIp, int operation, List<String> paths, long timeoutMs);

    /** 无交互界面的默认实现：连接放行、数据按会话放行。 */
    ConsentProvider AUTO_ALLOW = new ConsentProvider() {
        @Override
        public boolean requestConnection(String peerName, String peerIp, long timeoutMs) {
            return true;
        }

        @Override
        public int requestData(String peerName, String peerIp, int operation,
                               List<String> paths, long timeoutMs) {
            return CONSENT_SESSION;
        }
    };
}
