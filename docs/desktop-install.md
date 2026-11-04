# My Habits for Windows

The desktop app reads and writes the same habits as the phone app, through the sync folder in
Google Drive (see `drive-sync.md`).

## Install

1. Open the latest release on GitHub (tags are named `mine-N`).
2. Download `my-habits-desktop-N.msi` and double-click it.
3. Start **My Habits** from the Start menu.
4. In the window, pick your Drive `Habits` folder as the sync folder (for example `H:\My Drive\Habits`).

The installer is per user: it needs no administrator rights and installs under
`%LOCALAPPDATA%\My Habits`.

## Update

The app checks GitHub for a newer `my-habits-desktop-N.msi` a few seconds after it starts, and
every 6 hours while it stays open. When it finds one, a teal banner appears under the top bar:

1. Click **Install and restart**. The app downloads the installer, closes, and the installer runs
   with a progress bar.
2. The new version opens by itself when the installer finishes.

**Later** hides the banner until the next check. If the check fails (offline, or GitHub is
rate-limiting), nothing is shown and it tries again later.

You can also update by hand: download the newer `my-habits-desktop-N.msi` and run it. It replaces
the old version in place and keeps your habits.

An app run from source (`DESKTOP.bat`) never checks for updates. The installed version is
`1.0.N`, where N is the build number of the release (`mine-N`); a local build without
`-PbuildNumber` is `1.0.0`, so any released build counts as newer.

## No installer (portable)

Download `my-habits-desktop-N-portable.zip`, unzip it anywhere, and run `My Habits.exe`.

## Where the data lives

- Habits database: `%APPDATA%\MyHabits\uhabits.db`
- Settings (including the sync folder): `%APPDATA%\MyHabits\config.properties`

Uninstalling from Windows Settings does not delete these two files.

## Build it yourself

```
gradlew.bat :uhabits-desktop:packageMsi
gradlew.bat :uhabits-desktop:packagePortableZip
```

The MSI lands in `uhabits-desktop/build/compose/binaries/main/msi/`, the zip in
`uhabits-desktop/build/portable/`. Pass `-PbuildNumber=N` to set the version (`1.0.N`).

## Notes

- The MSI's upgrade id (`upgradeUuid` in `uhabits-desktop/build.gradle.kts`) must never change,
  or Windows will install new versions next to the old one instead of replacing it.
- The app has no custom icon yet.
