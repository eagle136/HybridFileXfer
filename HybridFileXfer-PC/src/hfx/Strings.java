package hfx;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Strings {

    private static final ResourceBundle messages;

    private static final ResourceBundle.Control UTF8_CONTROL = new ResourceBundle.Control() {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
                throws IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) {
                    return null;
                }
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    return new PropertyResourceBundle(reader);
                }
            }
        }
    };

    static {
        //由于Java读取properties文件用的是ISO-8859-1编码，这里加个Control让它用UTF-8编码来读
        //语言策略（不提供切换功能）：系统语言为中文（含繁体）时强制使用简体中文，其它语言强制使用英文
        Locale target = isChinese(Locale.getDefault())
                ? Locale.SIMPLIFIED_CHINESE
                : Locale.ENGLISH;
        messages = ResourceBundle.getBundle("messages", target, UTF8_CONTROL);
    }

    private static boolean isChinese(Locale locale) {
        return "zh".equalsIgnoreCase(locale.getLanguage());
    }

    public static String get(String key) {
        return messages.getString(key);
    }

    public static String get(String key, Object... args) {
        String message = messages.getString(key);
        return String.format(message, args); // 使用占位符替换
    }

    public static void printf(String key){
        System.out.println(get(key));
    }

    public static void printf(String key, Object... args){
        System.out.println(get(key, args));
    }
}
