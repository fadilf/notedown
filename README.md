# Notedown

A native Android dictation app built with Kotlin, Compose Material 3, Hilt, Room, DataStore, Retrofit/OkHttp, Media3 and WorkManager. Minimum Android 10 (API 29); targets Android 16 (API 36). Compile SDK 37 is required by the selected stable Compose libraries. There is no backend, account system or subscription.

## Build and run

Open this folder in a current Android Studio, install SDK 37, and use JDK 21 to run the bundled Gradle wrapper (application bytecode targets Java 17). Set `sdk.dir` in your untracked `local.properties` if necessary.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease # optimized, unsigned; configure your own release signing
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Instrumentation tests require a running emulator/device; the real microphone test grants microphone permission and records about a second of device audio. Tests use isolated API fakes and never spend OpenRouter credits.

1. Open Settings, enter an OpenRouter API key, and validate/save it.
2. Refresh models and select a transcription model. Discovery uses `output_modalities=transcription`, including specialized STT models.
3. Grant microphone access and enable **Notedown dictation** in Android's accessibility settings. Notification permission is optional but makes recording controls easier to access.
4. Focus a normal editable field in another app with its keyboard visible; tap the floating microphone. Drag it to reposition it; it snaps to the nearest screen edge and remembers its height. Settings lets you change its side or reset it above the keyboard. Stop saves audio and submits it; Cancel discards it without an API request.
5. Use history for playback, explicit copying, model selection for retranscription, version selection and deletion. The in-app Dictate button also works without accessibility enabled.

Audio and transcripts live in application-private files and Room. The API key is AES-GCM encrypted with an Android Keystore key; backup is disabled. Audio is sent directly to OpenRouter/provider for transcription; enabled cleanup sends raw text separately. Provider data retention policies still apply. Uninstalling deletes local history.

## Implementation

- `audio/`: compressed AAC/M4A capture, optional 16 kHz mono WAV capture, temporary M4A-to-WAV conversion, Media3 playback, microphone foreground service with notification Stop/Cancel controls.
- `domain/`: recording state coordinator, durable transcription worker, raw-first cleanup pipeline. Successful raw responses and their usage are saved transactionally. Cleanup failure leaves raw text usable.
- `data/`: Room parent recordings and child transcript versions with indexed cascading foreign keys; separate usage ledger; encrypted credentials, DataStore preferences, model cache and interruption recovery.
- `accessibility/`: actual IME window observation, completion-time text insertion. Overlay dictation inserts once into the currently focused editable field using its latest text and cursor/selection, including when the user switches fields or apps while processing. `ACTION_SET_TEXT` preserves text outside that selection. Password, disabled and unfocused fields are excluded. In-app recordings and history retranscription do not request automatic insertion. There is no automatic clipboard overwrite: the user explicitly taps Copy if insertion is unavailable.
- `overlay/`: bounded, nonfocusable `TYPE_ACCESSIBILITY_OVERLAY` window with idle, recording, saving, processing and result/error controls. Recording controls remain available if the keyboard closes.
- `ui/`: Compose history/settings, first-run permission guidance and the recording entry activity. Theme follows light/dark and dynamic colors.

A transcription creates a new version every time. Queued jobs persist their model, language, WAV option, cleanup model and prompt; changing preferences does not alter an existing job. WorkManager waits for connectivity and survives ordinary process death. HTTP errors remain retryable from history. API errors are deliberately **not automatically resubmitted** because a timed-out request may already have incurred cost. There is no upstream idempotency guarantee; process death after an upstream response but before local commit may require a charged retry. Cleanup cancellation propagates normally.

## Android microphone constraints

An accessibility overlay is not treated as blanket permission to start background microphone capture. Tapping it launches a compact, user-visible `CaptureActivity`; its resumed lifecycle starts the microphone foreground service automatically. Capture starts before the expanded recording state is shown, with **no second tap**. Overlay capture uses its own temporary task, separate from Notedown and any permission Settings page left in its back stack. Once recording starts, that task is removed and the original editor returns to the foreground while the service continues capture. The input target comes from the focused application window, even when touching the overlay makes it the active window. Android's activity/service transition contributes unavoidable startup latency; this implementation does not promise zero milliseconds or bypass permission checks. Permission denial requires the system's permission flow first. No `SYSTEM_ALERT_WINDOW`, battery optimization exemption, storage or contacts permission is requested.

Recordings automatically stop after ten minutes. Multipart uploads are capped at 25 MB; M4A keeps ordinary dictation small, and WAV compatibility mode also converts retained M4A on retranscription. Provider format/language support varies and failures are surfaced with actionable messages. Audio interrupted by process termination is recovered into history when possible; an M4A container killed before finalization can be unplayable. No claim of lossless recovery from force-stop or power loss is made. Android force-stop pauses background jobs until the app is opened again.

The usage screen totals exact decimal API-reported costs, counts successful requests, and labels missing costs as unavailable. It does not invent estimates or treat unknown usage as free. Deleting history retains the usage ledger.

## Verification and remaining device checks

Automated tests cover response/usage parsing, safe API errors, real Android cursor/selection insertion and field switching, one-time overlay delivery, password rejection, cleanup success/failure/cancellation, recording lifecycle guards, real M4A capture and WAV conversion/cancellation, Room relationships/version retention/cascades, settings persistence and Keystore roundtrip. See `docs/TESTING.md` for the required keyboard/OEM matrix and release checks. Live paid OpenRouter requests require your key and have not been exercised by the automated suite.

Verified here: debug and optimized unsigned release builds (release APK approximately 4.1 MB), lint (zero errors; eight nonfatal warnings), 15 unit tests and 13 instrumentation tests on an Android 15 emulator, including automatic activity-to-microphone-service startup. The first-run UI was inspected on the emulator. Public model discovery returned 30 transcription models and 460 text models at verification time; catalogs change. The overlay regression test taps the real microphone over a separate editor with Gboard visible and verifies it returns to that editor even when Accessibility Settings remains in Notedown's task. Physical Samsung/Gboard insertion and paid API calls remain manual checks.

## Authoritative references

Checked during implementation on November 7, 2026:

- [OpenRouter STT documentation](https://github.com/OpenRouterTeam/docs/blob/main/guides/overview/multimodal/stt.mdx): `/audio/transcriptions`, JSON and multipart requests, `output_modalities=transcription`, usage and provider-dependent formats.
- [OpenRouter chat completions](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request): separate optional text cleanup.
- [OpenRouter key information](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key): validation using `GET /key`.
- [Android foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): microphone while-in-use permission and visible-activity startup.
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom): stable Compose dependency alignment. Dependency versions are pinned in Gradle; there are no dynamic version selectors.
