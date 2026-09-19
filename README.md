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

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Sideload onto the Fire HD 10 (Apps → install unknown apps allowed).

## Target

- Fire HD 10 (11th gen / Fire OS 7+, Android 9+)
- minSdk 28
- Offline only
- WAV in / WAV out (no processing)
