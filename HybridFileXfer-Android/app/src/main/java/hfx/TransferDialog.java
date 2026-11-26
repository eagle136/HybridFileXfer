package hfx;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import hfx.core.Utils;
import hfx.core.TrafficInfo;

public class TransferDialog {
    /** 进度文本最快更新间隔。传输线程每秒会产生上百次进度回调，全部推到 UI 线程没有意义 */
    private static final long PROGRESS_INTERVAL_MS = 100;
    /** 多久没收到任何进度/速度回调就判定链路无响应。速度回调每秒一次，30 秒是很宽的阈值 */
    private static final long STALL_TIMEOUT_MS = 30_000L;
    /** 看门狗检查间隔 */
    private static final long WATCHDOG_INTERVAL_MS = 1_000L;

    public final boolean isUpload;
    private final Map<String, Holder> holderMap;
    private final Context context;
    private final View dialogView;
    private AlertDialog dialog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastProgressTime = new HashMap<>();

    /** 最后一次收到进度/速度回调的时刻（传输线程写，UI 线程读） */
    private volatile long lastActivityTime = SystemClock.elapsedRealtime();
    /** 是否已判定链路无响应 */
    private boolean stalled = false;
    /** 传输结束（完成/失败/已关闭）后不再看门 */
    private boolean finished = false;
    /** 链路无响应时"断开并退出"要执行的动作，由使用方注入 */
    private Runnable onStallExit;
    /** 常规状态下完成按钮的文案与动作，兜底态结束后要还原 */
    private String positiveText = "";
    private View.OnClickListener positiveAction;

    /**
     * 心跳看门狗：传输线程可能在 read/transferTo 上永远卡住（对端断电、Wi-Fi 掉线、
     * USB 拔线都不一定会让 socket 立刻报错），而对话框 setCancelable(false) 又吞掉了返回键，
     * 结果就是"静默断网只能杀进程"。这里只要 STALL_TIMEOUT_MS 内没有任何回调，
     * 就放开一个"断开并退出"按钮给用户兜底。
     */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (finished) {
                return;
            }
            long idle = SystemClock.elapsedRealtime() - lastActivityTime;
            if (idle >= STALL_TIMEOUT_MS) {
                if (!stalled) {
                    stalled = true;
                    onStall();
                }
            } else if (stalled) {
                // 进度又回来了，撤销兜底态
                stalled = false;
                restoreCloseButton();
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };


    public TransferDialog(Context context, boolean isUpload, List<String> channelNames) {
        this.context = context;
        holderMap = new HashMap<>();
        LayoutInflater inflater = LayoutInflater.from(context);
        dialogView = View.inflate(context, R.layout.dialog_transfer, null);
        LinearLayout linearLayout = dialogView.findViewById(R.id.linearLayout);
        for (String channelName : channelNames) {
            View itemView = inflater.inflate(R.layout.item_net_interface_status, linearLayout, false);
            //只保留上或下的其中一个速度指示器
            if (isUpload) {
                itemView.findViewById(R.id.download_speed).setVisibility(View.GONE);
            } else {
                itemView.findViewById(R.id.upload_speed).setVisibility(View.GONE);
            }
            Holder holder = new Holder(itemView);
            holder.channelName.setText(channelName);
            holder.channelIcon.setImageDrawable(ContextCompat.getDrawable(context, NetCardIcon.matchIdForIName(channelName)));
            holderMap.put(channelName, holder);
            linearLayout.addView(itemView);
        }
        this.isUpload = isUpload;
    }


    public void show() {
        dialog = new AlertDialog.Builder(context)
                .setTitle(isUpload ? context.getString(R.string.uploading_speed_zero) :
                        context.getString(R.string.downloading_speed_zero))
                .setView(dialogView)
                .setPositiveButton(R.string.complete, null)
                .setCancelable(false)
                .show();
        Button closeButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        closeButton.setEnabled(false);
        positiveText = context.getString(R.string.complete);
        positiveAction = null;
        finished = false;
        stalled = false;
        lastActivityTime = SystemClock.elapsedRealtime();
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    /**
     * 注入"链路无响应"时兜底按钮的动作（通常是断开会话 + 退出页面）。
     * 不注入时按钮只做 dismiss，至少保证用户能出去。
     */
    public void setOnStallExit(Runnable onStallExit) {
        this.onStallExit = onStallExit;
    }

    /** 收到任何进度/速度回调都算一次心跳 */
    private void touch() {
        lastActivityTime = SystemClock.elapsedRealtime();
    }

    private void onStall() {
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        long idleMs = SystemClock.elapsedRealtime() - lastActivityTime;
        dialog.setTitle(context.getString(R.string.link_no_response, (int) (idleMs / 1000)));
        Button closeButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        closeButton.setText(R.string.disconnect_and_exit);
        closeButton.setEnabled(true);
        closeButton.setOnClickListener(v -> {
            Runnable action = onStallExit;
            dismiss();
            if (action != null) {
                action.run();
            }
        });
        // 原来是 setCancelable(false)，静默断网时连返回键都按不动；这里放开兜底出口
        dialog.setCancelable(true);
        dialog.setOnCancelListener(d -> {
            Runnable action = onStallExit;
            dismiss();
            if (action != null) {
                action.run();
            }
        });
    }

    /** 进度恢复后把按钮还原成"完成"，避免用户误点断开一个正常的传输 */
    private void restoreCloseButton() {
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        Button closeButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        closeButton.setText(positiveText);
        closeButton.setOnClickListener(positiveAction);
        closeButton.setEnabled(false);
        dialog.setCancelable(false);
        dialog.setOnCancelListener(null);
    }


    public void showEvent(String iName, String event) {
        touch();
        Objects.requireNonNull(holderMap.get(iName)).transferEvent.setText(event);
    }

    /**
     * 传输进度专用：所有进度文本都来自传输线程，直接 setText 会：
     * 1) 让传输线程卡在 measure/layout 上（每块一次）；
     * 2) 跨线程改 View，长度变化时触发 requestLayout，属于未定义行为。
     * 这里改成投递到主线程 + 100ms 节流。
     */
    public void showProgress(String iName, String event) {
        final Holder holder = holderMap.get(iName);
        if (holder == null) {
            return;
        }
        // 只有真实进度才算心跳；速度回调是定时轮询的，链路死了也照样一秒一次，不能拿来续命
        touch();
        long now = SystemClock.elapsedRealtime();
        Long last = lastProgressTime.get(iName);
        if (last != null && now - last < PROGRESS_INTERVAL_MS) {
            return;
        }
        lastProgressTime.put(iName, now);
        mainHandler.post(() -> holder.transferEvent.setText(event));
    }

    public void showSpeeds(List<TrafficInfo> trafficInfoList) {
        long totalUploadSpeed = 0;
        long totalDownloadSpeed = 0;
        for (TrafficInfo trafficInfo : trafficInfoList) {
            Holder holder = holderMap.get(trafficInfo.iName);
            if (holder != null) {
                holder.uploadSpeed.setText(Utils.formatSpeed(trafficInfo.uploadTraffic));
                holder.downloadSpeed.setText(Utils.formatSpeed(trafficInfo.downloadTraffic));
                totalUploadSpeed += trafficInfo.uploadTraffic;
                totalDownloadSpeed += trafficInfo.downloadTraffic;
            }
        }
        if (isUpload) {
            if (!stalled) {
                dialog.setTitle(context.getString(R.string.uploading_speed,
                        ((float) totalUploadSpeed) / 1024 / 1024));
            }
        } else {
            if (!stalled) {
                dialog.setTitle(context.getString(R.string.downloading_speed,
                        ((float) totalDownloadSpeed) / 1024 / 1024));
            }
        }
    }

    public void complete(boolean isUpload, long traffic, long time) {
        finished = true;
        mainHandler.removeCallbacks(watchdog);
        if (stalled) {
            stalled = false;
            restoreCloseButton();
        }
        setCloseBtnEnable(true);
        // 极小文件/空文件在毫秒级就传完，time 可能是 0；直接除会抛 ArithmeticException 崩在 UI 线程
        long elapsed = time > 0 ? time : 1L;
        dialog.setTitle((isUpload ? "▲" : "▼") + Utils.formatSpeed(traffic / elapsed * 1000) +
                "·" + Utils.formatTime(time) + "·" + Utils.formatFileSize(traffic));
        for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
            Holder holder = entry.getValue();
            holder.downloadSpeed.setText(Utils.formatSpeed(0));
            holder.uploadSpeed.setText(Utils.formatSpeed(0));
        }
    }

    public void setTitle(String title) {
        dialog.setTitle(title);
    }

    public void setCloseBtnEnable(boolean enable) {
        if (enable) {
            // 关闭按钮一旦可用就说明这次传输已经收口了，看门狗没必要继续跑
            finished = true;
            mainHandler.removeCallbacks(watchdog);
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enable);
    }

    public void setButton(String text, View.OnClickListener onClickListener) {
        Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        button.setText(text);
        button.setOnClickListener(onClickListener);
        // 记下来，兜底态结束后要还原成这个"正常出口"
        positiveText = text;
        positiveAction = onClickListener;
    }

    public void dismiss() {
        finished = true;
        mainHandler.removeCallbacks(watchdog);
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    public AlertDialog getDialog() {
        return dialog;
    }

    private static class Holder {
        final View itemView;
        ImageView channelIcon;
        TextView channelName, uploadSpeed, downloadSpeed, transferEvent;

        public Holder(View itemView) {
            this.itemView = itemView;
            channelIcon = itemView.findViewById(R.id.channel_icon);
            channelName = itemView.findViewById(R.id.channel_name);
            uploadSpeed = itemView.findViewById(R.id.upload_speed);
            downloadSpeed = itemView.findViewById(R.id.download_speed);
            transferEvent = itemView.findViewById(R.id.txv_transfer_event);
        }
    }
}
