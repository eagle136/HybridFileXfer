package hfx.ui;

import android.app.Activity;
import android.util.DisplayMetrics;

/**
 * 屏幕百分比与密度换算工具。
 *
 * <p>Android 没有 CSS 那种百分比布局，真正“跟随分辨率”的路只有三条：
 * {@code layout_weight}、{@code layout_constraint*_percent}、以及运行时按屏幕尺寸计算。
 * XML 里的 {@code layout_weight} / {@code percent} 负责结构性分割（列宽、行高比例），
 * 本类负责补上 XML 表达不了的那部分：同一份 dimens 在极端屏幕（超小屏 / 平板）上的
 * 连续裁切，以及需要按屏高百分比定尺寸的控件（底部按钮栏、设备列表）。
 *
 * <p>所有方法返回值单位为 px，可直接赋给 LayoutParams。
 */
public final class UiScale {

    /** 基准屏宽（dp），与 res/values/dimens.xml 的设计基准保持一致 */
    private static final float BASE_WIDTH_DP = 360f;
    /** 固定尺寸随屏宽缩放的比例上下限：低于 0.85 会挤，高于 1.45 会散 */
    private static final float MIN_FACTOR = 0.85f;
    private static final float MAX_FACTOR = 1.45f;

    private static float density = 1f;
    private static int screenWidthDp = 360;
    private static int screenHeightDp = 640;

    private UiScale() {
    }

    /** 在每个 Activity 的 onCreate 里调用一次（BaseActivity 已统一处理）。 */
    public static void attach(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            density = metrics.density > 0f ? metrics.density : 1f;
            screenWidthDp = Math.round(metrics.widthPixels / density);
            screenHeightDp = Math.round(metrics.heightPixels / density);
        } catch (Throwable ignored) {
        }
    }

    public static int screenWidthDp() {
        return screenWidthDp;
    }

    public static int screenHeightDp() {
        return screenHeightDp;
    }

    /** 窄屏（<360dp）：需要收紧内边距与字号 */
    public static boolean isCompact() {
        return screenWidthDp < 360;
    }

    /** 平板/大屏（>=600dp）：双栏布局可用 */
    public static boolean isWide() {
        return screenWidthDp >= 600;
    }

    /** 屏宽百分比，clamp 到 [minDp, maxDp]，返回 px。 */
    public static int widthPercent(float ratio, int minDp, int maxDp) {
        return dp(clamp(screenWidthDp * ratio, minDp, maxDp));
    }

    /** 屏高百分比，clamp 到 [minDp, maxDp]，返回 px。 */
    public static int heightPercent(float ratio, int minDp, int maxDp) {
        return dp(clamp(screenHeightDp * ratio, minDp, maxDp));
    }

    /** 以 360dp 为基准等比缩放的固定尺寸，clamp 到 0.85~1.45 倍，返回 px。 */
    public static int scaled(float baseDp) {
        float factor = clamp(screenWidthDp / BASE_WIDTH_DP, MIN_FACTOR, MAX_FACTOR);
        return dp(baseDp * factor);
    }

    public static int dp(float value) {
        return Math.round(value * density);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
