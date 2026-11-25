# Mumla Walkie-Talkie Mobile Debug & Deploy Guide (for AI Agents)

This repository is a customized Android **Mumla** (`se.lublin.mumla`) client used on an Android PoC walkie-talkie.
When the user runs an AI agent (such as Antigravity CLI `agy`) from **Android Termux** on their phone while outside, follow this end-to-end workflow automatically without asking the user to manually read logs or build APKs.

## Environment Assumptions
- Running in **Termux** on the user's Android phone.
- Do **NOT** run `./gradlew` locally inside Termux (too slow / NDK unsupported on Termux). Always use **GitHub Actions** for cloud builds.
- The walkie-talkie is connected via **ADB** (either USB OTG or wireless `adb connect <ip>:5555`).
- Default package name: `se.lublin.mumla` (flavor `fossDebug`).

## End-to-End Bugfix Workflow

### 1. Check ADB Connection & Pull Logs
1. Check connected devices:
   ```bash
   adb devices
   ```
   If no device is listed, ask the user to connect the Type-C cable (or enable wireless ADB `adb tcpip 5555` via Bugjaeger and share the walkie-talkie IP).
2. Capture recent logs and crashes for Mumla without asking the user to inspect them:
   ```bash
   # Recent crash buffer
   adb logcat -b crash -d -t 200
   # Mumla-filtered recent logcat
   adb logcat -d -t 500 | grep -iE "mumla|humla|se\.lublin|AndroidRuntime|FATAL|ptt|gps|audio"
   ```
   If the user needs to reproduce the bug live, clear the buffer (`adb logcat -c`), ask them to trigger the bug once, then dump with `adb logcat -d`.

### 2. Fix Code & Push to Trigger Cloud Build
1. Inspect the relevant source files in `app/src/main/...`, apply a minimal, clean fix.
2. Commit and push to `master`:
   ```bash
   git add -A
   git commit -m "fix: <concise summary>"
   git push origin master
   ```

### 3. Wait for GitHub Actions Cloud Build & Download APK
The workflow `.github/workflows/build-apk.yml` automatically builds `:app:assembleFossDebug` on every push to `master` and publishes:
- Artifact name: `mumla-debug-apk`
- Rolling pre-release tag: `dev-latest` (asset: `mumla-latest-debug.apk`)

Wait for the latest run to finish and download the APK:
```bash
# Find and watch the latest workflow run
RUN_ID=$(gh run list --workflow=build-apk.yml --branch=master --limit=1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status

# Download the built APK
rm -rf /tmp/mumla-apk && mkdir -p /tmp/mumla-apk
gh run download "$RUN_ID" --name mumla-debug-apk --dir /tmp/mumla-apk
```
*(Fallback: `gh release download dev-latest -p "mumla-latest-debug.apk" -D /tmp/mumla-apk --clobber`)*

### 4. Install onto the Walkie-Talkie & Verify
1. Install over the existing app (preserving user data and server config):
   ```bash
   APK=$(ls /tmp/mumla-apk/*.apk | head -n 1)
   adb install -r "$APK"
   ```
   - If `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, it means the existing app on the walkie-talkie was signed with a different keystore. Warn the user before uninstalling (`adb uninstall se.lublin.mumla`), because uninstalling removes saved servers/certificates.
2. Relaunch Mumla and check for immediate crashes:
   ```bash
   adb logcat -c
   adb shell monkey -p se.lublin.mumla -c android.intent.category.LAUNCHER 1
   sleep 3
   adb logcat -b crash -d
   ```
3. Report the fix summary and verification result concisely in Traditional Chinese (繁體中文).

## Hardware & UI Gotchas (Anysecu G393 / msm8909go)
- **Display override quirk**: Physical panel is `128x160`, but firmware overrides it to `200x240 @ 80dpi` (`smallestScreenWidthDp` reports `400dp`). Never use `sw300dp` resource qualifiers to detect this screen; always use `DisplayClarityHelper` (`densityDpi <= 120 || shortSidePx <= 240`), and never hardcode large raw pixel heights (e.g., `150px` PTT button fills 75% of the screen).
- **Adreno 304 GPU corruption**: On overridden low-res displays, HWUI OpenGL rendering of `RecyclerView` / transparent containers causes stride misalignment & snow corruption. Every user-facing `Activity` should call `DisplayClarityHelper.applySafeRendering(this)` (forces Skia `LAYER_TYPE_SOFTWARE` on low-res hardware only) and set an opaque `?android:attr/colorBackground`.
- **Locale sync**: `app/build.gradle` restricts `resourceConfigurations` to `['zh-rTW', 'en']`. Keep `language_notranslate.xml` and `local_config.xml` aligned with packed locales.
- **3-Screen UI Verification SOP**: After any UI/theme change, capture and inspect all three screens via `adb screencap` before shipping: **Favourites list**, **In-room channel list**, and **Settings**.
