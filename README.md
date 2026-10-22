<p align="center">
  <img src="design/logo/ekwalizer-icon.svg" width="160" alt="ekwalizer">
</p>

<h1 align="center">ekwalizer</h1>

<p align="center"><b>Even sound, calm nights.</b><br>
System-wide night mode for Android TV: one volume level for every app, so the next video doesn't blast you awake.</p>

---

You fall asleep to YouTube. You set the volume for one video, the next one is twice as loud. ekwalizer fixes that on the device itself, for **all** audio: YouTube, any other player, the launcher, everything. No custom player, no per-app setup, no root.

## How it works

- A foreground service attaches Android's [`DynamicsProcessing`](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing) effect to the **global output mix** (audio session 0), so every app's audio passes through it inside `audioserver` before it reaches HDMI.
- The effect is a single full-band compressor with a slow release (works as a loudness leveler: loud videos are pushed down, quiet ones lifted by makeup gain) followed by a brick-wall limiter that caps peaks.
- Silence and hiss below −60 dBFS are not lifted (noise gate + expander).
- A watchdog re-attaches the effect if it is lost (audio server restart, HDMI re-plug). The mode is restored after reboot.

**Latency and performance.** Processing runs in 5 ms blocks, well below lip-sync tolerance (~45 ms). The effect is one compressor band plus a limiter on 48 kHz stereo, so the CPU cost should be tiny (not yet measured during playback). Video decoding is untouched.

### Strength presets

| Preset | Threshold | Ratio | Attack / Release | Makeup | Ceiling |
|---|---|---|---|---|---|
| Soft | −24 dBFS | 3:1 | 20 / 400 ms | +6 dB | −6 dBFS |
| Medium | −30 dBFS | 6:1 | 10 / 600 ms | +10 dB | −8 dBFS |
| Strong | −36 dBFS | 12:1 | 5 / 900 ms | +15 dB | −10 dBFS |

Tune them in [`Leveler.java`](app/src/main/java/com/ekwalizer/nightmode/Leveler.java).

## Requirements and limitations

- Android 9+ (API 28), built for Android TV / Google TV. Regular Android should work too, but it is not tested.
- Tested on Xiaomi Mi Box S (2nd gen), Android TV 11, no root.
- Effects on the global output mix are deprecated in Android. Most firmwares still allow them for normal apps, but a vendor can block them. The status line in the app shows whether the effect is attached.
- **Dolby/DTS passthrough** bypasses Android's mixer and cannot be processed. PCM output (the usual case for YouTube) works.
- AOSP `DynamicsProcessing` only implements the frequency-resolution variant; ekwalizer uses it.
- *Amlogic AVL* engine is an experimental option for Amlogic boxes and is not verified.

## Install

Download `ekwalizer-<version>.apk` from [Releases](https://github.com/maxpowerart/ekwalizer/releases) and install it on the TV with "Send files to TV", a USB stick, or over adb (enable developer options and network debugging on the TV first):

```bash
adb install ekwalizer-1.0.0.apk
```

Build from source (JDK 17+, Android SDK 36):

```bash
./gradlew assembleDebug
```

## Updates

ekwalizer updates itself from GitHub releases, the same model as the other Kwa apps (GitKwaken, Kwalendar):

- The channel is `releases/latest/download/update.json`: `{version, notes, url, sha256, published}`.
- Checked at startup and every 6 hours (Settings → Обновления → Проверять автоматически), or manually with "Проверить". A skipped version is not offered by background checks.
- The APK is verified against the SHA-256 in `update.json` while downloading, then installed through Android's `PackageInstaller`, so Android asks you to confirm. The first time, Android also asks to allow ekwalizer to install apps; granting it restarts ekwalizer, the install continues.
- Updates must be signed with the same release key. Debug builds have no channel.
- A withdrawn release (see below) is reported in the app with the reason. Android does not let an app downgrade itself, so going back means uninstalling and installing the older APK.
- No telemetry: only `update.json`, the GitHub releases list and the APK are fetched.

## Releasing

1. Once: create the release key with `tools/build-release.ps1 -NewKeystore` (stored in `.local/android-signing`, git-ignored — keep a backup, without it installed copies can't be updated). Add repository secrets `EKW_KEYSTORE_BASE64` (base64 of `ekwalizer-release.jks`) and `EKW_KEYSTORE_PASSWORD`.
2. Bump `appVersion` in `app/build.gradle` (versionCode is derived from it) and add a `## X.Y.Z` section to `CHANGELOG.md`.
3. Push tag `vX.Y.Z`. [`release.yml`](.github/workflows/release.yml) builds the signed APK and publishes it with `update.json` and `SHA256SUMS.txt`. A tag with `-` (e.g. `v1.1.0-beta.1`) becomes a pre-release and is not offered as an update.
4. Bad release? Run [`withdraw.yml`](.github/workflows/withdraw.yml) with the version and a reason: the channel goes back to the newest good release. `restore` undoes it.

`tools/build-release.ps1` builds the same assets locally into `dist/`.

## Use

1. Open **ekwalizer** from the launcher.
2. Press **OK** on the big card to turn night mode on.
3. Use **◀ ▶** to pick the strength: Soft, Medium or Strong.
4. Press **Back**. Night mode keeps running in the background.

## Design

UI style and brand: [`design/STYLE.md`](design/STYLE.md), logo and icons in [`design/logo/`](design/logo/). ekwalizer is part of the Kwa family of apps (GitKwaken, Kwalendar, KwanimeGo).

## По-русски

ekwalizer — ночной режим для Android TV: выравнивает громкость всего звука приставки, чтобы следующий ролик не заорал. Работает для любого приложения, без root. Эффект `DynamicsProcessing` (компрессор + лимитер) висит на глобальной аудиосессии, задержка около 5 мс. Сила: мягко, средне, сильно. Не работает с Dolby/DTS passthrough. Обновляется сам из релизов GitHub, установка — после вашего подтверждения.

## License

[Apache License 2.0](LICENSE).

Android is a trademark of Google LLC. YouTube is a trademark of Google LLC. Xiaomi and Mi Box are trademarks of Xiaomi. Amlogic is a trademark of Amlogic, Inc. They are mentioned only to describe compatibility; this project is not affiliated with or endorsed by them.
