# Repository guidance

## Project and layout

Notedown is a native Android dictation app with no backend or account system. It uses Kotlin, Jetpack Compose Material 3, Hilt, Room, DataStore, Retrofit/OkHttp, Media3, and WorkManager. Audio and optional text cleanup requests go directly to OpenRouter.

The single application module is `app`, with production Kotlin under `app/src/main/java/com/notedown/`:

- `accessibility/`: keyboard visibility, eligible text fields, and completion-time text insertion.
- `overlay/`: floating microphone and recording/result controls.
- `audio/`: recording, foreground service, WAV conversion, and playback.
- `domain/`: recording coordination, queued transcription, cleanup, and pending insertion.
- `data/`: Room entities/DAO, repositories, recovery, settings, and encrypted credentials.
- `api/`: OpenRouter interfaces, service abstractions, response parsing, and safe errors.
- `di/`: Hilt providers in `AppModule.kt`.
- `ui/`: Compose screens, view model, theme, and capture activity.

Read `README.md` for product behavior and `docs/TESTING.md` for device verification. Build configuration lives in the root and app `build.gradle.kts` files; dependency versions are pinned there.

## Build and verification

Use the bundled Gradle wrapper with JDK 21 and Android SDK 37. Application bytecode targets Java 17; minimum Android API is 29 and target API is 36. Configure the local SDK through untracked `local.properties` when needed.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
```

- The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
- Release builds are optimized and unsigned unless signing is configured locally.
- JVM tests live in `app/src/test/`; device tests and the editor fixture live in `app/src/androidTest/`.
- Instrumentation requires a running emulator or device. Audio tests grant microphone permission and record device audio; overlay tests interact with accessibility and keyboard state.
- Automated API tests use fakes and must not spend OpenRouter credits. Live endpoint checks require an explicitly supplied test key with credits; see `docs/TESTING.md`.
- For code changes, run relevant tests and lint, plus a build when compilation or packaging is affected. Use device tests for Android-dependent behavior. Report which checks ran and any environment blockers; do not present emulator results as physical-device verification.
- Documentation-only changes do not require Android builds or tests; check paths, commands, and the diff instead.

## Implementation conventions

- Follow the surrounding Kotlin style and `kotlin.code.style=official`; keep edits focused and avoid unrelated reformatting.
- Keep UI in Compose and reuse existing repositories, service interfaces, Hilt bindings, and coroutine/Flow patterns.
- Propagate coroutine cancellation. In broad exception handlers, rethrow `CancellationException` before handling ordinary failures.
- Keep provider errors actionable without exposing response bodies or private content.
- Preserve existing user data when changing Room entities: update the database version, provide migrations and relevant tests, and retain exported schemas under `app/schemas/`. Do not introduce destructive migration as a shortcut.
- Keep dependencies pinned; update README or device-check guidance when behavior or setup changes.

## Behavior to preserve

- Each transcription or explicit retry creates a new transcript version. Queued work snapshots its model, language, audio option, and cleanup configuration; later preference changes must not alter it.
- Save successful raw transcription and its usage transactionally before cleanup. Cleanup is optional and separate; its failure must leave the raw result usable. Reuse an already persisted raw response when resuming work.
- Do not automatically resubmit failed paid requests or enable transparent HTTP retries. A timed-out request may already have incurred cost, and upstream idempotency is not guaranteed. Preserve connectivity-constrained queuing and ordinary process-death recovery.
- Keep API-reported costs exact, with missing costs represented as unknown. Deleting history removes audio and transcript versions and cancels their work, but retains the usage ledger.
- Start microphone capture through the user-visible, resumed `CaptureActivity` and microphone foreground service. A single overlay tap starts capture and returns to the original editor; preserve the separate temporary task behavior.
- Overlay completion inserts at most once into the currently focused eligible field using its latest text and selection. Preserve surrounding text and reject password, disabled, or unfocused fields. In-app recordings and history retranscriptions must not automatically insert text.
- Clipboard writes require explicit Copy. Cancel discards capture without an API request. Keep the ten-minute recording limit and 25 MB upload cap.

## Privacy and local files

- Never commit or print credentials, `.env` contents, signing material, or private recordings/transcripts. Do not put them in fixtures, logs, screenshots, or error messages.
- Keep API keys encrypted through Android Keystore, audio in application-private storage, and backups disabled.
- Preserve the existing permission scope; do not add overlay, storage, contacts, or battery-exemption permissions to bypass Android lifecycle restrictions.
- Leave `.env`, `local.properties`, signing files, and generated `.gradle/`, `.kotlin/`, and `build/` directories untracked. Do not hand-edit generated build outputs.
