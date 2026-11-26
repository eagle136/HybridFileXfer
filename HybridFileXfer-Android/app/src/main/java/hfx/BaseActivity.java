package hfx;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.LocaleList;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;
import java.util.Locale;

import hfx.core.Discovery;
import hfx.ui.EdgeToEdge;
import hfx.ui.UiScale;

/**
 * 全应用强制语言策略（不提供应用内切换功能）：
 * 系统语言为中文（含繁体）时使用简体中文，其它语言一律使用英文。
 * 通过 attachBaseContext 包裹 context 的 locale 实现，
 * 对 Activity 内所有资源查找（含对话框、弹窗）生效。
 *
 * <p>同时统一初始化两个全局适配器：
 * {@link Discovery#init}（组播锁需要 applicationContext）与
 * {@link UiScale#attach}（屏幕百分比换算需要当前 Activity 的 DisplayMetrics）。
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** 最近创建的界面；空闲超时要 finishAffinity 结束任务，不能只靠进程被杀留残影。 */
    private static WeakReference<BaseActivity> currentRef;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(wrapLocale(base));
    }

    /** 给 AppExit 用；界面已销毁时返回 null。 */
    static BaseActivity current() {
        WeakReference<BaseActivity> ref = currentRef;
        return ref == null ? null : ref.get();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentRef = new WeakReference<>(this);
        // 必须在 setContentView 之前开：Android 15+ 强制 edge-to-edge，
        // 不显式处理就只能靠这里统一接管，否则内容会被系统栏压住。
        EdgeToEdge.enable(this);
        Discovery.init(this);
        UiScale.attach(this);
    }

    @Override
    protected void onDestroy() {
        if (current() == this) {
            currentRef = null;
        }
        super.onDestroy();
    }

    private static Context wrapLocale(Context context) {
        Locale systemLocale = context.getResources().getConfiguration().getLocales().get(0);
        Locale target = isChinese(systemLocale) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(target));
        return context.createConfigurationContext(config);
    }

    private static boolean isChinese(Locale locale) {
        return "zh".equalsIgnoreCase(locale.getLanguage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionIdleTimer.onAppResumed();
    }

    @Override
    protected void onPause() {
        SessionIdleTimer.onAppPaused();
        super.onPause();
    }

    /**
     * 前后台都计时的口径下，“无动作”要包含人机操作：
     * 点按/滑动/按键都刷新空闲计时，否则用户在前台正常浏览目录也会被 3 分钟踢掉。
     */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        SessionIdleTimer.markActivity();
    }
}
