package com.openai.memoautorecorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class RecorderKickActivity extends Activity {
    private boolean started = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = 2; lp.height = 2; lp.gravity = Gravity.TOP | Gravity.START; lp.dimAmount = 0f;
        getWindow().setAttributes(lp);
        TextView v = new TextView(this); v.setText(" "); v.setBackgroundColor(Color.TRANSPARENT);
        setContentView(v);
    }

    @Override protected void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Prefs.status(this, "마이크 권한 없음");
            finish();
            return;
        }
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
        startForegroundService(i);
        new Handler().postDelayed(this::finish, 550);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
