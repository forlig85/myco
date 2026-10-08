package io.github.forlig85.memoauto.recording

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface RecState {
    data object Idle : RecState
    data object Starting : RecState
    data class Recording(
        val name: String,
        val uri: Uri,
        val startedAtElapsed: Long,
        val via: String,
        val warning: String? = null,
    ) : RecState
}

enum class FailReason { NO_PERMISSION, FGS_DENIED, SILENCED_AT_START, MIC_BUSY, STORAGE, NO_DATA, OTHER }

sealed interface RecEvent {
    /** 시작 후 약 2.5초간 검증(무음 처리 여부)까지 통과. */
    data class Started(val name: String, val via: String) : RecEvent
    data class Failed(val reason: FailReason, val message: String, val via: String) : RecEvent
    data class Saved(val name: String, val uri: Uri, val mime: String, val durationMs: Long, val bytes: Long) : RecEvent
}

/**
 * 마이크 포그라운드 서비스. 녹음 상태의 유일한 기준([state])이다.
 * 같은 프로세스 안의 다른 구성요소는 [state]/[events] 를 구독한다.
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "녹음"
        const val ACTION_START = "io.github.forlig85.memoauto.START"
        const val ACTION_STOP = "io.github.forlig85.memoauto.STOP"
        const val EXTRA_VIA = "via"

        private const val ALERT_SILENT = 2101
        private const val VERIFY_MS = 2500L
        private const val NO_DATA_FAIL_SEC = 10
        private const val SILENT_WARN_SEC = 10
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private const val STOP_FREE_BYTES = 20L * 1024 * 1024

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> = _state

        private val _events = MutableSharedFlow<RecEvent>(extraBufferCapacity = 16)
        val events: SharedFlow<RecEvent> = _events

        @Volatile private var instance: RecorderService? = null

        val isActive: Boolean get() = _state.value != RecState.Idle

        fun startIntent(ctx: Context, via: String) =
            Intent(ctx, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_VIA, via)

        /** 같은 프로세스에서 직접 중지 (백그라운드 서비스 시작 제한을 피함). */
        fun stop(reason: String) {
            val s = instance
            if (s == null) {
                AppLog.i(TAG, "중지 요청($reason) — 실행 중인 녹음 없음")
                _state.value = RecState.Idle
                return
            }
            s.stopRecording(reason)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var output: RecordingOutput? = null
    private var monitorJob: Job? = null
    private var startedAt = 0L
    private var via = "?"
    private var silencedBySystem = false
    private var audioCallback: AudioManager.AudioRecordingCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (recorder == null) {
                    // startService 로 들어온 경우라 startForeground 의무 없음
                    finishService()
                } else stopRecording("알림에서 중지")
            }
            ACTION_START -> handleStart(intent.getStringExtra(EXTRA_VIA) ?: "?")
            else -> {
                // 시스템 재시작(null intent) 등: 이어서 녹음할 파일이 없으므로 종료
                AppLog.w(TAG, "알 수 없는 시작 요청(${intent?.action}) — 종료")
                becomeForegroundSafely()
                finishService()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * startForegroundService() 로 시작된 이상 반드시 startForeground() 를 성공시켜야
     * 시스템이 앱을 강제 종료하지 않는다. 마이크 타입이 거부되면 specialUse 로라도 한 번 올린다.
     * @return 마이크 타입 포그라운드 성공 여부
     */
    private fun becomeForegroundMic(): Throwable? {
        val n = Notifier.recording(this, "회의 녹음 준비 중", "마이크를 여는 중…")
        return try {
            startForeground(Notifier.ID_RECORDING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            null
        } catch (e: Exception) {
            becomeForegroundSafely()
            e
        }
    }

    private fun becomeForegroundSafely() {
        val n = Notifier.recording(this, "메모 자동기록", "정리 중…")
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_RECORDING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_RECORDING, n)
            }
        }.onFailure { AppLog.w(TAG, "대체 포그라운드 전환도 실패", it) }
    }

    private fun handleStart(via: String) {
        if (recorder != null) {
            AppLog.i(TAG, "이미 녹음 중 — 시작 요청($via) 무시")
            // startForegroundService() 로 들어왔을 수 있으므로 포그라운드 의무를 다시 충족시킨다.
            runCatching {
                startForeground(
                    Notifier.ID_RECORDING,
                    Notifier.recording(this, "회의 녹음 중", output?.displayName ?: ""),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
            return
        }
        this.via = via
        _state.value = RecState.Starting

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            becomeForegroundSafely()
            fail(FailReason.NO_PERMISSION, "마이크 권한이 없습니다. 앱을 열어 권한을 허용하세요.")
            return
        }

        val fgErr = becomeForegroundMic()
        if (fgErr != null) {
            fail(
                FailReason.FGS_DENIED,
                "백그라운드에서 마이크 녹음을 시작할 수 없음(${fgErr.javaClass.simpleName}: ${fgErr.message})"
            )
            return
        }

        val free = OutputStore.freeBytesBeforeStart()
        if (free in 0 until MIN_FREE_BYTES) {
            fail(FailReason.STORAGE, "저장공간이 부족합니다(남은 공간 ${free / 1024 / 1024}MB).")
            return
        }

        val out = try {
            OutputStore.create(this, RecFormat.M4A)
        } catch (e: Exception) {
            AppLog.e(TAG, "파일 생성 실패", e)
            fail(FailReason.STORAGE, "녹음 파일을 만들 수 없음: ${e.message}")
            return
        }
        output = out

        val r = MediaRecorder(this)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioChannels(1)
            r.setOutputFile(out.pfd.fileDescriptor)
            val freeNow = OutputStore.freeBytes(out)
            if (freeNow > STOP_FREE_BYTES) r.setMaxFileSize(minOf(freeNow - STOP_FREE_BYTES, 3_500_000_000L))
            r.setOnErrorListener { _, what, extra ->
                AppLog.e(TAG, "MediaRecorder 오류 what=$what extra=$extra")
                Notifier.alert(this, "녹음 오류", "녹음기 오류(what=$what, extra=$extra). 지금까지 녹음된 내용을 저장합니다.")
                stopRecording("녹음기 오류")
            }
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Notifier.alert(this, "저장공간 부족", "저장공간이 부족해 녹음을 종료하고 저장했습니다.")
                    stopRecording("저장공간 한도 도달")
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopRecording("최대 시간 도달")
                }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            AppLog.e(TAG, "MediaRecorder 시작 실패", e)
            runCatching { r.reset() }
            runCatching { r.release() }
            OutputStore.discard(this, out)
            output = null
            fail(
                FailReason.MIC_BUSY,
                "마이크를 열 수 없음(${e.javaClass.simpleName}). 통화 중이거나 다른 앱이 마이크를 독점하고 있을 수 있습니다."
            )
            return
        }

        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        silencedBySystem = false
        _state.value = RecState.Recording(out.displayName, out.uri, startedAt, via)
        AppLog.i(TAG, "녹음 시작 [$via]: ${out.displayName} (${OutputStore.describeLocation(this)})")
        updateNotification("회의 녹음 중", out.displayName)
        registerSilenceCallback()
        startMonitor(out)
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notifier.ID_RECORDING, Notifier.recording(this, title, text))
        }
    }

    private fun registerSilenceCallback() {
        val am = getSystemService(AudioManager::class.java)
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                // 일반 앱은 자기 앱의 녹음 설정만 받는다.
                val silenced = configs.any { it.isClientSilenced }
                if (recorder == null || silenced == silencedBySystem) return
                silencedBySystem = silenced
                if (silenced) {
                    AppLog.w(TAG, "시스템이 이 앱의 녹음을 무음 처리함(다른 앱/통화가 마이크 사용 중이거나 백그라운드 권한 문제)")
                    setWarning("시스템이 무음 처리 중")
                    if (SystemClock.elapsedRealtime() - startedAt > VERIFY_MS) warnSilent("시스템이 이 앱의 녹음을 무음으로 처리하고 있습니다.")
                } else {
                    AppLog.i(TAG, "시스템 무음 처리 해제")
                    setWarning(null)
                }
            }
        }
        am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
        audioCallback = cb
        // 등록 시점의 상태도 반영
        silencedBySystem = am.activeRecordingConfigurations.any { it.isClientSilenced }
        if (silencedBySystem) AppLog.w(TAG, "시작 직후부터 무음 처리 상태")
    }

    private fun unregisterSilenceCallback() {
        audioCallback?.let { runCatching { getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(it) } }
        audioCallback = null
    }

    private fun setWarning(w: String?) {
        val s = _state.value
        if (s is RecState.Recording) _state.value = s.copy(warning = w)
    }

    private fun warnSilent(detail: String) {
        Notifier.alert(
            this, "녹음이 무음입니다",
            "$detail\n대안: ① 통화·다른 녹음 앱 종료 ② 설정에서 '녹음 시작 방식'을 '항상 보조 화면'으로 변경 " +
                "③ AI 기록만 사용하고 앱 녹음은 끄기 ④ 이 앱을 열어둔 상태에서 녹음 시작",
            key = ALERT_SILENT
        )
    }

    private fun startMonitor(out: RecordingOutput) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // ① 시작 검증: 무음 처리 여부
            delay(VERIFY_MS)
            if (recorder == null) return@launch
            if (silencedBySystem && via == RecordStarter.VIA_DIRECT) {
                AppLog.w(TAG, "직접 시작했으나 무음 처리됨 → 보조 화면으로 재시도하도록 실패 처리")
                abortAndDiscard(FailReason.SILENCED_AT_START, "백그라운드 시작이라 마이크가 무음 처리됨")
                return@launch
            }
            _events.tryEmit(RecEvent.Started(out.displayName, via))
            if (silencedBySystem) warnSilent("녹음은 시작됐지만 시스템이 무음으로 처리하고 있습니다.")

            // ② 진행 감시: 파일 크기, 입력 음량, 저장공간
            var sec = (VERIFY_MS / 1000).toInt()
            var zeroAmpSec = 0
            var silentWarned = false
            var everGrew = false
            while (isActive && recorder != null) {
                delay(1000)
                sec++
                val rec = recorder ?: break
                val size = OutputStore.size(out)
                if (size > 0) everGrew = true
                if (!everGrew && sec >= NO_DATA_FAIL_SEC) {
                    AppLog.e(TAG, "${sec}초 동안 파일 크기 0 byte → 실패 처리")
                    abortAndDiscard(FailReason.NO_DATA, "녹음을 시작했지만 ${sec}초 동안 파일에 데이터가 기록되지 않았습니다(0 byte).")
                    return@launch
                }
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
                if (amp == 0) zeroAmpSec++ else zeroAmpSec = 0
                if (zeroAmpSec >= SILENT_WARN_SEC && !silentWarned) {
                    silentWarned = true
                    AppLog.w(TAG, "입력 음량 0 이 ${zeroAmpSec}초 지속")
                    setWarning("입력 음량 0 (무음)")
                    warnSilent("최근 ${zeroAmpSec}초 동안 마이크 입력이 전혀 없습니다. 다른 앱이 마이크를 가져갔을 수 있습니다.")
                } else if (silentWarned && amp > 0) {
                    silentWarned = false
                    AppLog.i(TAG, "마이크 입력 복구됨")
                    setWarning(if (silencedBySystem) "시스템이 무음 처리 중" else null)
                    Notifier.cancel(this@RecorderService, ALERT_SILENT)
                }
                if (sec % 30 == 0) {
                    val free = OutputStore.freeBytes(out)
                    if (free in 0 until STOP_FREE_BYTES) {
                        Notifier.alert(this@RecorderService, "저장공간 부족", "남은 공간이 ${free / 1024 / 1024}MB 라 녹음을 종료하고 저장합니다.")
                        stopRecording("저장공간 부족")
                        return@launch
                    }
                }
                if (sec % 60 == 0) AppLog.i(TAG, "녹음 진행 ${sec / 60}분, ${size / 1024}KB, 최근 음량 $amp")
            }
        }
    }

    private fun releaseRecorder(): Boolean {
        val r = recorder ?: return false
        recorder = null
        val ok = try {
            r.stop(); true
        } catch (e: Exception) {
            AppLog.w(TAG, "MediaRecorder.stop() 실패(기록된 오디오가 없을 때 발생)", e)
            false
        }
        runCatching { r.reset() }
        runCatching { r.release() }
        return ok
    }

    /** 검증 실패: 파일 삭제 후 Failed 이벤트. */
    private fun abortAndDiscard(reason: FailReason, msg: String) {
        monitorJob?.cancel()
        unregisterSilenceCallback()
        releaseRecorder()
        output?.let { OutputStore.discard(this, it) }
        output = null
        fail(reason, msg)
    }

    private fun fail(reason: FailReason, msg: String) {
        AppLog.e(TAG, "시작 실패 [$via/$reason]: $msg")
        _events.tryEmit(RecEvent.Failed(reason, msg, via))
        // 자동 재시도가 가능한 실패는 RecordStarter 가 최종 실패일 때 알린다.
        val retryable = via == RecordStarter.VIA_DIRECT && (reason == FailReason.FGS_DENIED || reason == FailReason.SILENCED_AT_START)
        if (!retryable) Notifier.alert(this, "녹음 시작 실패", msg)
        finishService()
    }

    fun stopRecording(reason: String) {
        monitorJob?.cancel()
        unregisterSilenceCallback()
        val out = output
        if (recorder == null || out == null) {
            AppLog.i(TAG, "중지($reason): 녹음기 없음")
            finishService()
            return
        }
        val bytesBefore = OutputStore.size(out)
        val duration = SystemClock.elapsedRealtime() - startedAt
        val ok = releaseRecorder()
        val bytes = maxOf(bytesBefore, OutputStore.size(out))
        output = null
        if (ok) {
            try {
                OutputStore.finishSuccess(this, out)
                AppLog.i(TAG, "녹음 저장 완료($reason): ${out.displayName}, ${duration / 1000}초, ${bytes / 1024}KB")
                _events.tryEmit(RecEvent.Saved(out.displayName, out.uri, out.mime, duration, bytes))
                Notifier.saved(this, out.displayName, out.uri, out.mime)
            } catch (e: Exception) {
                AppLog.e(TAG, "파일 마무리 실패", e)
                Notifier.alert(this, "녹음 저장 실패", "파일 마무리 중 오류: ${e.message}")
            }
        } else {
            OutputStore.discard(this, out)
            Notifier.alert(this, "녹음 저장 실패", "녹음된 오디오가 없어 파일을 저장하지 못했습니다(${duration / 1000}초). 마이크가 다른 앱에 점유됐을 수 있습니다.")
        }
        finishService()
    }

    private fun finishService() {
        _state.value = RecState.Idle
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording("서비스 종료")
        unregisterSilenceCallback()
        scope.cancel()
        if (instance === this) instance = null
        _state.value = RecState.Idle
        super.onDestroy()
    }
}
