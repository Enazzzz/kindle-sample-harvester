# Kindle Sample Harvester

Touch-first WAV sample harvester for Kindle Fire HD 10.

**Hear → Select → Save → Continue.**

## What it does

1. Open a WAV
2. Play it
3. Drag a region on the waveform
4. Tap **SAVE** → writes `sample_0001.wav`, `sample_0002.wav`, …
5. Playback keeps going

Samples land in `/Samples/` on the SD card when available, otherwise internal shared storage.

Dark appliance UI. No naming prompts. No cloud. Tap a selection to audition it. Brief `saved sample_####` cue after each save.

## Install on Fire HD 10

1. On the Fire: **Settings → Security & Privacy → Apps from Unknown Sources** (enable for your file manager / browser)
2. Copy `app-debug.apk` to the tablet (USB, email, or SD card)
3. Open the APK and install
4. Grant storage permission when asked
5. **Open** a WAV → play → drag → **SAVE**

Build the APK yourself:

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

If `gradle/wrapper/gradle-wrapper.jar` is missing after clone:

```bash
./bootstrap-wrapper.sh
# or: gradle wrapper --gradle-version 8.7
```

## Target

- Fire HD 10 (11th gen / Fire OS 7+, Android 9+)
- minSdk 28
- Offline only
- WAV in / WAV out (no processing, no normalization)
