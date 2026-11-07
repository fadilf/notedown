# Publishing APK releases

GitHub Actions publishes an installable, signed APK when a push to `main` increases the app's major/minor version. It uses a standard GitHub-hosted Linux runner, which is free for public repositories.

## One-time signing setup

Use one release signing key for all releases so Android can install updates over previous versions. If you already distribute release-signed APKs, use that existing key.

Otherwise, create a key locally with the JDK's `keytool`. Run this in the project root; `*.jks` files are ignored by Git:

```sh
keytool -genkeypair -v -keystore notedown-release.jks \
  -alias notedown -keyalg RSA -keysize 3072 -validity 10000
```

Keep a secure backup of the keystore, alias, and passwords. Do not commit them. Losing the key prevents signing compatible updates. A debug-signed installation must be uninstalled before installing a release-signed APK; uninstalling deletes local history.

In the repository's **Settings → Secrets and variables → Actions**, add these repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded contents of the keystore |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (`notedown` for the command above) |
| `RELEASE_KEY_PASSWORD` | Key password (usually the same as the keystore password) |

To upload the keystore without printing its contents, use GitHub CLI:

```sh
base64 -w 0 notedown-release.jks | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_STORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

The last three commands prompt for values. Also ensure GitHub Actions is enabled in the repository. The release job requests `contents: write` to create a tag and release; repository or organization policy must allow this.

## Publish a version

1. In `app/build.gradle.kts`, increase `versionName`'s minor version and increase `versionCode`. For example, change `1.0.2` / `3` to `1.1.0` / `4`.
2. Commit and push or merge that change into `main`.
3. Watch **Actions → Release APK**. After tests, lint, build, and signature verification succeed, the workflow creates a release such as `v1.1.0` with `Notedown-1.1.0.apk` and a SHA-256 checksum file.
4. Download the APK from [Releases](https://github.com/fadilf/notedown/releases) and complete the relevant [device checks](TESTING.md).

The README's download link always points to the latest release.

## Trigger rules

- Versions must be numeric `major.minor.patch` values.
- The workflow compares the version before the entire push with the version at its final commit, not just the last commit's parent.
- An increase in the `(major, minor)` pair triggers a release. This includes a major rollover such as `1.9.2 → 2.0.0`.
- Patch-only changes (`1.1.0 → 1.1.1`), unchanged versions, and version decreases do not build or publish.
- A qualifying version increase without an increased `versionCode` fails the version check.
- Pushes that do not change `app/build.gradle.kts` do not start the workflow. Other changes to that file run only the lightweight version check unless the version qualifies.
- Creating `main` for the first time has no previous version to compare and does not publish a release.

No release is published merely by adding the workflow. The first release requires a subsequent version increase.

## Failed releases

If a job fails before publishing, correct the issue or missing secrets and rerun that workflow from Actions. Never reuse a published version for different APK contents. If the release already exists, publish a new qualifying version instead of replacing its APK. GitHub's ordinary concurrency queue can replace an older pending run when several release pushes arrive quickly, so wait for each release workflow to finish before pushing the next release bump.

The workflow restores the signing key into the runner's temporary directory and removes it after the job. Missing signing secrets stop the job before building. APK signatures are verified before upload.

## Local release signing

Gradle uses signing only when `RELEASE_KEYSTORE_PATH` is set. Supply it as an absolute path along with `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` in your local environment, then run:

```sh
./gradlew :app:assembleRelease
```

With signing configured, the APK is `app/build/outputs/apk/release/app-release.apk`. Without it, Gradle produces an unsigned release APK. Keep environment values out of shell history and source control.
