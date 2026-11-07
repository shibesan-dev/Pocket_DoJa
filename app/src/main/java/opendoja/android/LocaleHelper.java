package opendoja.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

public final class LocaleHelper {
    private LocaleHelper() {
    }

    public static final String KEY_USE_SYSTEM_LANGUAGE = "useSystemLanguage";
    public static final String KEY_LANGUAGE = "language";

    public static final String[] LANGUAGE_TAGS = {
            "en", "zh-CN", "zh-TW", "th", "ja", "ko", "es", "vi", "ms"
    };

    public static final String[] LANGUAGE_NAMES = {
            "English", "中文 (简体)", "中文 (繁體)", "ไทย", "日本語", "한국어", "Español", "Tiếng Việt", "Bahasa Melayu"
    };

    public static void applyLocale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(DisplayActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean useSystem = prefs.getBoolean(KEY_USE_SYSTEM_LANGUAGE, true);
        Locale locale;
        if (useSystem) {
            locale = Locale.getDefault();
        } else {
            String tag = prefs.getString(KEY_LANGUAGE, "en");
            locale = Locale.forLanguageTag(tag.replace("_", "-"));
            if (locale == null || locale.getLanguage().isEmpty()) {
                locale = Locale.getDefault();
            }
        }
        Locale.setDefault(locale);
        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            context.createConfigurationContext(config);
        } else {
            config.locale = locale;
        }
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    public static Context wrap(Context base) {
        // For attachBaseContext, apply to a new context.
        SharedPreferences prefs = base.getSharedPreferences(DisplayActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean useSystem = prefs.getBoolean(KEY_USE_SYSTEM_LANGUAGE, true);
        Locale locale;
        if (useSystem) {
            locale = Locale.getDefault();
        } else {
            String tag = prefs.getString(KEY_LANGUAGE, "en");
            locale = Locale.forLanguageTag(tag.replace("_", "-"));
            if (locale == null || locale.getLanguage().isEmpty()) {
                locale = Locale.getDefault();
            }
        }
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
        } else {
            config.locale = locale;
        }
        return base.createConfigurationContext(config);
    }
}
