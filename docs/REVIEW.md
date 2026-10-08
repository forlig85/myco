# MemoAutoRecorder V1 검수 결과

대상: `MemoAutoRecorder-source.zip` (V1, Java, 외부 라이브러리 없음, 5개 클래스)
기준 기기: Lenovo Yoga Tab (ZUI), Android 14~16
표기: **[확정]** 코드만 보고 단정할 수 있는 것 / **[추정]** 기기 로그로 확인이 필요한 것

---

## 1. 빌드 오류 가능성

| # | 내용 | 영향 |
|---|------|------|
| B1 | **[확정]** Gradle wrapper(`gradlew`, `gradle/wrapper/*`)가 없음. AGP 8.7.3은 Gradle 8.9 이상이 필요한데 버전을 고정할 수단이 없음 | CI에서 바로 빌드 불가. Android Studio도 Gradle 버전을 임의로 고름 |
| B2 | **[확정]** 서명 설정 없음. `assembleRelease`는 서명 안 된 APK를, `assembleDebug`는 빌드하는 기기마다 다른 debug 키로 서명된 APK를 만듦 | 설치 불가(미서명) 또는 업데이트할 때마다 "패키지 충돌"로 기존 앱 삭제 필요 |
| B3 | **[확정]** `versionCode 1` 고정 | 같은 키로 서명해도 다운그레이드/동일 버전으로 인식돼 업데이트가 꼬임 |
| B4 | **[확정]** `Environment.DIRECTORY_RECORDINGS`는 API 31 상수인데 `minSdk 29`. 컴파일 시 상수가 인라인되므로 빌드는 되지만(lint `InlinedApi` 경고), API 29/30에서는 MediaStore가 `Recordings/` 경로를 거부해 `insert`가 예외로 실패 | Android 10/11에서 녹음 시작 불가 |
| B5 | **[확정]** 패키지명 `com.openai.memoautorecorder`: 실제 회사 도메인을 쓰는 개인 앱. 같은 이름의 다른 앱과 충돌할 수 있고 오해 소지 | V2에서 `io.github.forlig85.memoauto`로 변경 (V1과는 다른 앱으로 설치됨) |
| B6 | **[경고]** `new Handler()`(Looper 미지정) deprecated, 사용하지 않는 import 다수 | 빌드는 됨 |

## 2. 런타임 오류 가능성

| # | 내용 |
|---|------|
| R1 | **[확정] 포그라운드 오판.** `onAccessibilityEvent`에서 `event.getPackageName()`만 보고 `currentPackage`를 갱신함. 메모 앱에서 키보드가 올라오면 IME 패키지, 알림창을 내리면 `com.android.systemui`, 토스트/볼륨 창 등도 "다른 앱"으로 처리 → 60초 타이머 시작 → 그 사이 메모 앱에서 다른 이벤트가 안 오면 녹음 종료. 분할 화면에서 다른 쪽 앱을 터치해도 이탈로 판단 |
| R2 | **[확정] 타일 상태 미확인.** `clickTile`은 상태를 보지 않고 클릭 → AI 기록이 이미 켜져 있으면 꺼버림. 클릭 후 결과도 확인하지 않음 |
| R3 | **[확정] 고정 지연 의존.** 250ms 후 패널 열기 → 1100ms에 타일 찾기 → 1500ms에 BACK → 2050ms에 녹음. 기기가 느리거나 ZUI 애니메이션이 길면 패널이 덜 열린 상태에서 탐색 실패. 타일이 2페이지 이후에 있으면 영영 못 찾음 |
| R4 | **[확정] 패널 닫기에 `GLOBAL_ACTION_BACK`.** 타일이 활동(Activity)을 띄우며 패널을 이미 닫았거나 패널이 안 열렸으면 BACK이 메모 앱으로 들어가 메모 화면을 닫거나 작성 중인 내용을 잃을 수 있음 |
| R5 | **[확정] 상태 불일치.** "녹음 중"을 SharedPreferences `recording` 플래그로만 판단. 프로세스가 죽으면(ZUI 백그라운드 정리, 크래시) `true`로 남아 `sessionActive = Prefs.recording()`이 계속 참 → 이후 자동 시작이 영구히 안 됨. `IS_PENDING=1` 상태의 미완성 파일도 남음(갤러리/파일 앱에서 안 보이다가 7일 뒤 시스템이 삭제) |
| R6 | **[확정] 권한 요청 연속 호출.** `requestPermissions`를 RECORD_AUDIO, POST_NOTIFICATIONS로 연달아 두 번 호출 → 첫 요청 대화상자가 떠 있는 동안 두 번째는 무시됨 |
| R7 | **[확정]** `startupInProgress`는 3.4초 뒤 무조건 해제되고, 그 시점의 `Prefs.recording`으로 성공 여부를 판단 → 녹음 시작이 3.4초보다 늦으면 실패로 오판하고 다음 이벤트에서 시퀀스를 또 실행 (타일 재클릭 → AI 기록 꺼짐) |
| R8 | **[확정]** 시작 실패가 `last_status` 문자열에만 기록되고, 앱 화면을 열기 전에는 사용자가 알 수 없음 (오버레이 권한 없을 때만 알림) |
| R9 | **[확정]** `startForeground`가 예외를 던지면 catch에서 `stopSelf()` → `startForegroundService()` 이후 `startForeground()`를 한 번도 성공시키지 못한 채 서비스가 종료되면 시스템이 `ForegroundServiceDidNotStartInTimeException`으로 앱 프로세스를 강제 종료함 |
| R10 | **[확정]** `recorder.stop()` 실패 시 파일을 바로 삭제 → 녹음 데이터가 있어도(긴 회의) 통째로 사라짐. 실패 원인도 안 남김 |
| R11 | **[확정]** 저장공간 확인, `MediaRecorder.OnErrorListener` 없음. 녹음 중 오류가 나도 서비스는 "녹음 중" 알림을 계속 띄움 |
| R12 | **[확정]** 녹음 시작 후 실제로 데이터가 기록되는지(파일 크기, 입력 음량) 확인하지 않음 |
| R13 | **[확정]** 사용자가 알림에서 "중지"를 눌러도 접근성 서비스의 `sessionActive`는 그대로 → 메모 앱을 나갔다 와도 재시작 안 됨 / 반대로 메모 앱 화면에서 이벤트가 오면 다시 시작될 수도 있음(타이밍 의존) |
| R14 | **[확정]** 접근성 서비스가 재연결되면(설정 변경, 프로세스 재생성) `Handler`에 걸린 타이머가 사라지고 상태 복구 로직 없음 |

## 3. Android 정책 이슈

### 3.1 포그라운드 서비스 (Android 14~16)
- **[확정]** `foregroundServiceType="microphone"` + `FOREGROUND_SERVICE_MICROPHONE` 선언은 올바름.
- **[확정]** 마이크는 *while-in-use* 권한. 앱이 백그라운드일 때 `startForeground(…MICROPHONE)`을 호출하면 Android 14+(targetSdk 34+)는 `SecurityException`, 13 이하는 예외 없이 **무음**으로 녹음될 수 있음.
- **[추정]** 접근성 서비스는 시스템이 `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`/`BIND_INCLUDE_CAPABILITIES`로 바인딩하므로, 접근성 서비스 프로세스에서 직접 FGS를 시작해도 마이크 권한이 허용될 가능성이 있음. 기기·버전마다 다를 수 있어 **V2는 "직접 시작 → 실패/무음이면 보조 화면으로 재시도"** 2단계로 처리하고, 결과를 로그로 남김.

### 3.2 백그라운드 시작 (투명 Activity 방식 검토)
- V1의 `RecorderKickActivity`(투명 Activity → 그 안에서 FGS 시작)는 **원리상 유효**: Activity가 resumed 상태이면 앱이 "사용 중"이므로 마이크 FGS 시작이 허용됨.
- 다만 V1 구현의 문제:
  - 기본 task affinity를 써서, 앱의 메인 화면 task가 남아 있으면 **메인 화면이 메모 앱 위로 올라올 수 있음** → V2는 `taskAffinity=""` + `singleInstance`로 별도 task.
  - `SYSTEM_ALERT_WINDOW` 미허용 시 무조건 실패로 처리하지만, 접근성 서비스 프로세스는 백그라운드 Activity 시작이 허용되는 경우가 많음 → V2는 오버레이 권한을 "권장"으로 낮추고 실제 시도 결과로 판단.
  - 2px 창이지만 포커스를 가져가 메모 앱 키보드가 내려감 → 보조 화면은 실패 시에만 사용.
  - 550ms 후 무조건 finish → 서비스가 `startForeground`를 하기 전에 Activity가 사라질 수 있음 → V2는 서비스의 시작 결과를 받은 뒤 닫음(최대 4초).
- 대안 검토: `SYSTEM_ALERT_WINDOW` 오버레이 창만 띄우는 방식은 Android 15부터 BFSL(백그라운드 FGS 시작) 예외로만 인정되고 while-in-use 권한은 주지 않으므로 마이크에는 부족. 알림 버튼 탭은 확실하지만 자동화가 아님. → **직접 시작 + 보조 화면 폴백**이 가장 현실적.

### 3.3 접근성
- **[확정]** Android 13+에서 브라우저/파일 앱으로 설치한 APK는 접근성 설정이 "제한된 설정"으로 막힘 → 앱 정보 ⋮ 메뉴의 **"제한된 설정 허용"** 필요 (README에 안내).
- **[확정]** `flagRetrieveInteractiveWindows`는 선언돼 있으나 V1은 `getWindows()`를 타일 탐색에만 쓰고 포그라운드 판단에는 안 씀.
- **[확정]** 접근성 서비스가 일반 자동화에 쓰여 Play 정책상 배포 불가 — 개인 사이드로드이므로 문제없음.
- **[확정]** `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`(API 31)가 있으므로 BACK 대신 사용 가능 → minSdk 31로 상향.

## 4. 녹음 0 byte 원인 (가능성 순)

1. **[추정] 프로세스 강제 종료.** MPEG-4(M4A)는 `stop()` 때 `moov` 박스를 써야 재생 가능한 파일이 됨. ZUI 백그라운드 정리나 크래시로 프로세스가 죽으면 파일이 `IS_PENDING` 상태로 남고, 파일 앱에 따라 0 byte 또는 재생 불가로 보임. 2번 크래시도 같은 결과.
2. **[추정] `startForeground` 실패 후 시스템 강제 종료(R9).** MediaStore 항목을 먼저 만든 뒤 `startForeground`가 `SecurityException`을 내면 정리 후 `stopSelf` → 시스템이 "startForeground를 호출하지 않음"으로 프로세스를 죽이는 과정에서 빈 항목이 남을 수 있음.
3. **[추정] 마이크 선점/권한 상실로 데이터 없음.** `MediaRecorder.start()`는 성공했지만 오디오 프레임이 안 들어오면 `stop()`이 `RuntimeException` → V1은 삭제(R10). 삭제 전에 프로세스가 죽으면 0 byte가 남음.
4. **[확정] API 29/30**에서는 `Recordings/` 경로 insert 실패(B4) → 기기 대상(14~16)에서는 해당 없음.
5. 쓰기 모드 `"w"`: 일부 저장소 제공자는 탐색(seek) 불가 fd를 돌려줌 → MPEG-4 writer 실패. V2는 `"rw"` 사용.

V2 대응: 시작 10초 안에 파일 크기가 0이면 실패 처리·알림, 입력 음량 0이 10초 지속되거나 시스템이 녹음을 무음 처리(`AudioRecordingConfiguration.isClientSilenced`)하면 경고, 비정상 종료로 남은 파일은 다음 실행 때 정리(0 byte 삭제, 데이터가 있으면 `_미완성`으로 남김), 크래시/종료 사유(`ApplicationExitInfo`)를 로그에 기록.

## 5. ZUI 특이사항

- **[추정] 백그라운드 정리.** ZUI는 최근 앱 목록에서 정리하거나 화면이 꺼지면 백그라운드 프로세스를 공격적으로 종료. 앱 프로세스가 죽으면 접근성 서비스도 함께 죽고, 반복되면 시스템이 접근성 서비스를 **꺼버릴** 수 있음. → 배터리 "제한 없음", 자동 실행 허용, 최근 앱에서 잠금(자물쇠) 필요.
- **[추정] 자동 실행 관리.** 설정 > 앱 > (앱) > 배터리/자동 실행 항목. 메뉴 이름은 ZUI 버전마다 다름. 꺼져 있으면 재부팅 후 접근성 서비스가 다시 붙지 않을 수 있음.
- **[추정] 백그라운드 팝업 제한.** 일부 ZUI 버전은 "백그라운드에서 창 표시" 권한을 따로 둠 → 보조 화면이 막히면 로그에 "보조 화면 응답 없음"으로 남음.
- **[추정] 빠른 설정 구조.** ZUI 태블릿은 알림창과 제어 센터가 분리될 수 있고, 타일 노드가 AOSP와 다른 클래스/텍스트를 가질 수 있음. "AI 기록"이 제조사 전용 타일이라면 상태(`stateDescription`)를 노출하지 않을 가능성도 있음 → V2의 "빠른 설정 진단" 덤프로 확인.
- **[추정]** ZUI 분할 화면/플로팅 창(팝업 창)은 각각 `TYPE_APPLICATION` 창으로 보고됨 → V2는 이 창 목록 기준으로 판단.

## 6. 구조 개선점

- 접근성 서비스 한 클래스가 감지·타일 조작·녹음 시작·타이머·알림을 모두 담당 → V2 분리:
  - `ui/` — Compose 화면(홈·설정·로그)
  - `recording/` — `RecorderService`(FGS), `OutputStore`(MediaStore/SAF 파일), `RecordStarter`(직접/보조 화면 시작), `RecorderKickActivity`
  - `a11y/` — `AutomationService`(이벤트 수신), `QuickSettingsAutomator`(패널·타일), `NodeDump`(진단)
  - `monitor/` — `ForegroundTracker`(보이는 창 판단), `SessionController`(세션 상태·이탈 타이머)
  - `share/` — (P1) ChatGPT 공유
  - `AppLog`(파일 로그), `Notifier`(알림/토스트), `Prefs`(설정)
- 녹음 상태의 단일 기준을 `RecorderService`의 메모리 상태(StateFlow)로 두고, SharedPreferences에는 "복구용 미완성 파일 정보"만 저장.
- 고정 지연 → 조건 폴링(패널 열림/타일 발견/상태 변경을 확인할 때까지, 제한 시간 안에서).
- 모든 실패에 알림 + 로그. 앱 안에서 로그 확인·복사·공유.
- Gradle wrapper + 고정 서명 키 + GitHub Actions 빌드/릴리스.
