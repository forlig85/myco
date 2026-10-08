package com.openai.memoautorecorder;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String NAME = "memo_auto_prefs";
    private Prefs() {}

    public static SharedPreferences p(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String targetPackage(Context c) { return p(c).getString("target_package", ""); }
    public static String targetLabel(Context c) { return p(c).getString("target_label", "선택 안 됨"); }
    public static String tileText(Context c) { return p(c).getString("tile_text", "AI 기록"); }
    public static int exitDelay(Context c) { return Math.max(5, p(c).getInt("exit_delay", 60)); }
    public static boolean enabled(Context c) { return p(c).getBoolean("enabled", false); }
    public static boolean recording(Context c) { return p(c).getBoolean("recording", false); }
    public static String lastStatus(Context c) { return p(c).getString("last_status", "대기 중"); }

    public static void status(Context c, String s) {
        p(c).edit().putString("last_status", s).apply();
    }
}
