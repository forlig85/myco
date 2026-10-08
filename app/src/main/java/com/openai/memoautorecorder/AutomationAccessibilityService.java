package com.openai.memoautorecorder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

public class AutomationAccessibilityService extends AccessibilityService {
    public static volatile AutomationAccessibilityService instance;
    private final Handler h = new Handler();
    private boolean startupInProgress = false;
    private boolean sessionActive = false;
    private String currentPackage = "";
    private Runnable pendingStop;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        sessionActive = Prefs.recording(this);
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        Prefs.status(this, "접근성 서비스 연결됨");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        currentPackage = pkg;
        if (!Prefs.enabled(this)) return;
        String target = Prefs.targetPackage(this);
        if (target.isEmpty()) return;

        if (target.equals(pkg)) {
            cancelPendingStop();
            if (!sessionActive && !startupInProgress) startSessionSequence();
        } else {
            if (sessionActive && !startupInProgress) scheduleStopCheck();
        }
    }

    private void startSessionSequence() {
        startupInProgress = true;
        Prefs.status(this, "메모 앱 감지 → AI 기록 실행 준비");
        h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS), 250);
        h.postDelayed(() -> {
            boolean clicked = clickTile(Prefs.tileText(this));
            Prefs.status(this, clicked ? "AI 기록 타일 클릭" : "AI 기록 타일을 찾지 못함");
        }, 1100);
        h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 1500);
        h.postDelayed(this::launchRecorderKick, 2050);
        h.postDelayed(() -> {
            sessionActive = Prefs.recording(this);
            startupInProgress = false;
            if (sessionActive) Prefs.status(this, "회의 녹음 중");
        }, 3400);
    }

    public void runQuickSettingsTest() {
        h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS), 100);
        h.postDelayed(() -> {
            boolean ok = clickTile(Prefs.tileText(this));
            Prefs.status(this, ok ? "테스트: AI 기록 클릭 성공" : "테스트: AI 기록 타일 미발견");
        }, 900);
    }

    private void launchRecorderKick() {
        if (!Settings.canDrawOverlays(this)) {
            Prefs.status(this, "다른 앱 위에 표시 권한이 없어 녹음 시작 실패");
            showSetupNotification("'다른 앱 위에 표시' 권한을 허용해야 자동 녹음이 시작됩니다.");
            startupInProgress = false;
            return;
        }
        try {
            Intent i = new Intent(this, RecorderKickActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
        } catch (Exception e) {
            Prefs.status(this, "녹음 시작 화면 실행 실패: " + e.getClass().getSimpleName());
            showSetupNotification("자동 녹음 시작이 차단되었습니다. 앱을 열어 설정을 확인해주세요.");
            startupInProgress = false;
        }
    }

    private void scheduleStopCheck() {
        cancelPendingStop();
        final String target = Prefs.targetPackage(this);
        pendingStop = () -> {
            if (!target.equals(currentPackage) && sessionActive) {
                Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
                startService(i);
                sessionActive = false;
                Prefs.status(this, "메모 앱 미복귀 → 녹음 종료");
            }
        };
        h.postDelayed(pendingStop, Prefs.exitDelay(this) * 1000L);
    }

    private void cancelPendingStop() {
        if (pendingStop != null) h.removeCallbacks(pendingStop);
        pendingStop = null;
    }

    private boolean clickTile(String needle) {
        if (needle == null || needle.trim().isEmpty()) needle = "AI 기록";
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && clickMatching(r, needle)) return true;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && clickMatching(root, needle);
    }

    private boolean clickMatching(AccessibilityNodeInfo node, String needle) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        boolean match = contains(t, needle) || contains(d, needle);
        if (match) {
            AccessibilityNodeInfo c = node;
            for (int i=0; i<4 && c != null; i++) {
                if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                c = c.getParent();
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && clickMatching(child, needle)) return true;
        }
        return false;
    }

    private boolean contains(CharSequence s, String n) {
        return s != null && s.toString().replace(" ", "").contains(n.replace(" ", ""));
    }

    private void showSetupNotification(String msg) {
        String ch = "memo_auto_setup";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(ch, "메모 자동기록 안내", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, ch)
                .setSmallIcon(com.openai.memoautorecorder.R.drawable.ic_mic)
                .setContentTitle("메모 자동기록 설정 필요")
                .setContentText(msg)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(2002, n);
    }

    @Override public void onInterrupt() {}
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }
}
