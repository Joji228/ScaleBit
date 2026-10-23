# Updates

Scale Bit checks GitHub for a newer version each time you open the app (at most once a day) and
shows it in **Settings → Updates**. Tapping **Download** fetches the APK, checks it against its
published SHA-256, and opens Android's install prompt. You confirm once and the update replaces
the app in place: meals, weigh-ins, settings and API keys stay.

## What the app needs to find a release

- A GitHub release that is **not** a draft or pre-release, and is the latest one.
- Two assets, named from the tag (`v1.5.0` → version `1.5.0`):
  - `ScaleBit-1.5.0.apk`
  - `ScaleBit-1.5.0.apk.sha256` (the `sha256sum` line for the APK)
- Downloads must come from `github.com` or GitHub's asset hosts over HTTPS. Anything else is ignored.
- The repo must be **public**: the app reads releases without a token, and no token is shipped.

## Publishing a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` must go up every
   release, or Android will refuse the update. The tag must match `versionName`.
2. Build the signed APK with the release keystore (it stays on your PC, see `keystore.properties`):

   ```bash
   ./gradlew assembleRelease
   ```

3. Copy it to `ScaleBit-<version>.apk` and write its checksum next to it (PowerShell):

   ```powershell
   Copy-Item app/build/outputs/apk/release/app-release.apk ScaleBit-1.5.0.apk
   $h = (Get-FileHash ScaleBit-1.5.0.apk -Algorithm SHA256).Hash.ToLower()
   "$h  ScaleBit-1.5.0.apk" | Set-Content -Encoding ascii ScaleBit-1.5.0.apk.sha256
   ```

4. Commit, tag and publish (`gh` CLI):

   ```bash
   gh release create v1.5.0 ScaleBit-1.5.0.apk ScaleBit-1.5.0.apk.sha256 --title "Scale Bit 1.5.0" --notes "What changed"
   ```

## Why the signing key matters

Android only installs an update that is signed with the same key as the installed app, and it
keeps the app's data only in that case. Losing the keystore, or signing one release with a
different key, means every phone must uninstall and start over. Back up `scalebit-release.keystore`
and `keystore.properties` somewhere outside this repository.

The checksum catches a corrupted or swapped download. The signature check is the real guard: the
app also compares the downloaded APK's signing key with its own before it opens the installer.

## Limits

- Android does not let an app install silently. There is always one system confirmation.
- The first install needs **Allow from this source** for Scale Bit. The app opens that page when
  Android asks.
- Google Play forbids apps that install other APKs, so a Play Store build must not include this
  updater. It belongs only in the sideloaded GitHub build.
