package com.openai.memoautorecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {
    public static final String ACTION_START = "com.openai.memoautorecorder.START";
    public static final String ACTION_STOP = "com.openai.memoautorecorder.STOP";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "memo_recording";

    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private String fileName;
    private boolean recording;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "회의 녹음", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("메모 자동기록의 회의 음성 녹음 상태");
            nm.createNotificationChannel(c);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (!recording) startRecording();
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("회의 녹음 중")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(R.drawable.ic_mic, "중지", stopPi).build())
                .build();
    }

    private void startRecording() {
        try {
            fileName = "MemoMeeting_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date()) + ".m4a";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/MemoAuto");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            pfd = cr.openFileDescriptor(uri, "w");
            if (pfd == null) throw new IllegalStateException("openFileDescriptor failed");

            Notification n = notification(fileName);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }

            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.prepare();
            recorder.start();
            recording = true;
            Prefs.p(this).edit().putBoolean("recording", true).putString("last_file", fileName).apply();
            Prefs.status(this, "녹음 시작: " + fileName);
        } catch (Exception e) {
            Prefs.status(this, "녹음 시작 실패: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            cleanupFailed();
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopRecording() {
        boolean ok = false;
        if (recorder != null) {
            try { recorder.stop(); ok = true; } catch (Exception ignored) {}
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pfd = null;
        if (uri != null) {
            try {
                if (ok) {
                    ContentValues done = new ContentValues(); done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                    Prefs.status(this, "녹음 저장 완료: " + fileName);
                } else {
                    getContentResolver().delete(uri, null, null);
                    Prefs.status(this, "녹음 저장 실패 - 빈 파일 삭제");
                }
            } catch (Exception e) {
                Prefs.status(this, "파일 마무리 실패: " + e.getMessage());
            }
        }
        uri = null;
        recording = false;
        Prefs.p(this).edit().putBoolean("recording", false).apply();
        stopForeground(true);
        stopSelf();
    }

    private void cleanupFailed() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pfd = null;
        try { if (uri != null) getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
        uri = null;
        recording = false;
        Prefs.p(this).edit().putBoolean("recording", false).apply();
    }

    @Override public void onDestroy() {
        if (recording) stopRecording();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
