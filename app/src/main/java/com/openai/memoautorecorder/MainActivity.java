package com.openai.memoautorecorder;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 10;
    private static final int REQ_NOTIF = 11;
    private TextView status;
    private TextView target;
    private EditText tile;
    private EditText delay;
    private Switch enabled;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("메모 자동기록", 26, true);
        root.addView(title);
        TextView desc = text("메모 앱을 열면 AI 기록을 켜고 회의 녹음을 시작합니다. 메모 앱을 벗어난 뒤 설정한 시간 동안 돌아오지 않으면 녹음을 종료합니다.", 15, false);
        desc.setPadding(0, dp(8), 0, dp(18));
        root.addView(desc);

        target = text("", 16, true);
        root.addView(target);
        root.addView(button("1. 메모 앱 선택", v -> pickApp()));
        root.addView(button("2. 마이크 권한 허용", v -> requestAudio()));
        root.addView(button("3. 다른 앱 위에 표시 허용", v -> openOverlay()));
        root.addView(button("4. 접근성 자동화 켜기", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("5. 배터리 최적화 제외(권장)", v -> requestBatteryExemption()));

        TextView lab1 = text("빠른 설정 타일 글자", 14, true);
        lab1.setPadding(0, dp(18), 0, dp(5));
        root.addView(lab1);
        tile = new EditText(this);
        tile.setSingleLine(true);
        root.addView(tile);

        TextView lab2 = text("메모 앱 이탈 후 녹음 종료 대기(초)", 14, true);
        lab2.setPadding(0, dp(12), 0, dp(5));
        root.addView(lab2);
        delay = new EditText(this);
        delay.setInputType(InputType.TYPE_CLASS_NUMBER);
        delay.setSingleLine(true);
        root.addView(delay);

        enabled = new Switch(this);
        enabled.setText("자동화 사용");
        enabled.setTextSize(17);
        enabled.setPadding(0, dp(16), 0, dp(8));
        root.addView(enabled);

        root.addView(button("설정 저장", v -> save()));
        root.addView(button("테스트: AI 기록 타일 누르기", v -> testTile()));
        root.addView(button("테스트: 15초 녹음", v -> testRecording()));
        root.addView(button("현재 녹음 중지", v -> stopRecording()));

        status = text("", 14, false);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);
        return scroll;
    }

    private void refresh() {
        target.setText("선택 앱: " + Prefs.targetLabel(this) + "\n패키지: " + Prefs.targetPackage(this));
        tile.setText(Prefs.tileText(this));
        delay.setText(String.valueOf(Prefs.exitDelay(this)));
        enabled.setChecked(Prefs.enabled(this));
        status.setText("마이크 권한: " + yes(hasAudio()) +
                "\n알림 권한: " + yes(hasNotif()) +
                "\n다른 앱 위에 표시: " + yes(Settings.canDrawOverlays(this)) +
                "\n접근성 서비스: " + yes(isAccessibilityEnabled()) +
                "\n녹음 중: " + yes(Prefs.recording(this)) +
                "\n최근 상태: " + Prefs.lastStatus(this));
    }

    private void save() {
        int s = 60;
        try { s = Integer.parseInt(delay.getText().toString().trim()); } catch (Exception ignored) {}
        s = Math.max(5, Math.min(3600, s));
        Prefs.p(this).edit()
                .putString("tile_text", tile.getText().toString().trim().isEmpty() ? "AI 기록" : tile.getText().toString().trim())
                .putInt("exit_delay", s)
                .putBoolean("enabled", enabled.isChecked())
                .apply();
        toast("저장했습니다.");
        refresh();
    }

    private void pickApp() {
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(i, 0);
        final List<ResolveInfo> filtered = new ArrayList<>();
        for (ResolveInfo r : apps) {
            if (!getPackageName().equals(r.activityInfo.packageName)) filtered.add(r);
        }
        Collections.sort(filtered, Comparator.comparing(a -> a.loadLabel(getPackageManager()).toString()));
        String[] names = new String[filtered.size()];
        for (int n=0; n<filtered.size(); n++) names[n] = filtered.get(n).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this)
                .setTitle("메모 앱 선택")
                .setItems(names, (d, which) -> {
                    ResolveInfo r = filtered.get(which);
                    String label = r.loadLabel(getPackageManager()).toString();
                    String pkg = r.activityInfo.packageName;
                    Prefs.p(this).edit().putString("target_label", label).putString("target_package", pkg).apply();
                    refresh();
                }).show();
    }

    private void requestAudio() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private void openOverlay() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void requestBatteryExemption() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } else toast("이미 배터리 최적화 제외 상태입니다.");
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void testTile() {
        save();
        AutomationAccessibilityService s = AutomationAccessibilityService.instance;
        if (s == null) {
            toast("접근성 서비스를 먼저 켜주세요.");
            return;
        }
        s.runQuickSettingsTest();
    }

    private void testRecording() {
        if (!hasAudio()) { toast("마이크 권한을 먼저 허용해주세요."); return; }
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
        startForegroundService(i);
        toast("15초 녹음을 시작합니다.");
        new Handler().postDelayed(this::stopRecording, 15000);
    }

    private void stopRecording() {
        startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP));
        new Handler().postDelayed(this::refresh, 700);
    }

    private boolean hasAudio() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasNotif() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityEnabled() {
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName cn = new ComponentName(this, AutomationAccessibilityService.class);
        return enabledServices != null && enabledServices.toLowerCase().contains(cn.flattenToString().toLowerCase());
    }

    private String yes(boolean b) { return b ? "✓" : "✗"; }
    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50)); p.setMargins(0, dp(5), 0, dp(5)); b.setLayoutParams(p); return b;
    }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); if (bold) t.setTypeface(null, 1); return t;
    }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
