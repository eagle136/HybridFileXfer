package hfx.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Edge-to-edge 适配。
 *
 * <p>targetSdk 35 起系统强制应用绘制到系统栏下面（Android 15 无法 opt-out，
 * Android 16/17 连 {@code windowOptOutEdgeToEdgeEnforcement} 都失效），
 * 不自己处理 insets 的直接后果就是：
 * <ul>
 *   <li>顶部标题被状态栏压住；</li>
 *   <li>底部按钮栏被导航条/手势条盖住 —— 也就是“底栏需要垫高”；</li>
 *   <li>列表最后一行永远滚动不出来。</li>
 * </ul>
 *
 * <p>这里只做两件事：把窗口交给 edge-to-edge，并把 systemBars + 刘海
 * （displayCutout）转换成 View 的 padding。转换按“在原有 padding 上追加”
 * 处理，所以 XML 里写死的间距不会被覆盖，重复调用也不会累加。
 */
public final class EdgeToEdge {

    private EdgeToEdge() {
    }

    /** 每个 Activity 的 onCreate 里调用一次，必须在 setContentView 之前/之后都可以。 */
    public static void enable(Activity activity) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 系统的半透明遮罩会在深色界面上压出一条灰带，关掉
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            // 界面本体是深色（page_bg），系统栏图标必须用浅色
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
    }

    /** 顶部垫高：状态栏 + 刘海。用在工具栏之类的顶部固定栏上。 */
    public static void padTop(View view) {
        pad(view, true, false);
    }

    /** 底部垫高：导航条 / 手势条。用在底部固定栏或整页根布局上。 */
    public static void padBottom(View view) {
        pad(view, false, true);
    }

    public static void padTopBottom(View view) {
        pad(view, true, true);
    }

    private static void pad(View view, boolean top, boolean bottom) {
        if (view == null || (!top && !bottom)) {
            return;
        }
        // 原始 padding 在设置监听前捕获：回调里只做“基准 + inset”，
        // 这样系统重复分发 insets 时不会一层层叠加。
        final int baseLeft = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int baseRight = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = systemBars(windowInsets);
            // 只处理垂直方向：同一页面里顶部栏和底部栏各管一段，
            // 左右 insets 交给它们任一处理都会和另一段互相叠加。
            v.setPadding(baseLeft, baseTop + (top ? bars.top : 0),
                    baseRight, baseBottom + (bottom ? bars.bottom : 0));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** systemBars 不含挖孔区域，刘海屏要额外并上 displayCutout。 */
    private static Insets systemBars(WindowInsetsCompat insets) {
        return insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
    }
}
