# Crystal Nova Manager — Phase U1

Android updater app for the **Crystal Nova Pegasus theme**. This is Phase U1:
the app is *only* an updater. It checks GitHub for new theme releases,
downloads them, validates them, and swaps them into Pegasus — safely, with
rollback. There is no scraper in this phase.

- Package (release): `io.crystalnova.manager`
- Min SDK 26 · Target/Compile SDK 34
- UI: Jetpack Compose, controller-first landscape layout (1280×960 / 4:3), touch friendly
- Theme repository (stable channel only): `ciaranf3308-star/crystal-nova-pegasus-theme`, branch `main`

## Building

Requirements: JDK 17, Android SDK with platform 34.

```bash
./gradlew assembleDebug
```

The APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

No local Maven mirrors, proxy hacks, or machine-specific paths are needed —
the build resolves everything from `google()` and `mavenCentral()`.

## Installation

1. Build the APK (above) or take a provided `app-debug.apk`.
2. Copy it to the Nova and install it (sideload — allow "install unknown apps"
   for the file manager you use).
3. The debug build installs as `io.crystalnova.manager.debug`, so it can sit
   alongside the release build without clobbering it.

The app requests only the standard `INTERNET` permission (for update checks
and downloads). It does **not** request all-files access.

## First run — picking the theme folder (SAF)

On first launch the app asks you to choose the folder where your Pegasus
themes live. This uses Android's Storage Access Framework folder picker:

1. Tap the folder prompt.
2. Navigate to the directory that *contains* your theme folders
   (the parent of `crystal-nova-pegasus-theme`), and confirm.
3. The app persists the permission (`takePersistableUriPermission`), so the
   grant survives reboots — you only do this once.

The app then works exclusively inside that tree via `DocumentFile`. It will
never touch anything outside the selected folder, and it never asks for
broad storage permissions.

> **U1.1:** if you accidentally select `crystal-nova-pegasus-theme` itself,
> the app normalizes to the parent `themes/` directory when the system
> permits it, otherwise it asks you to pick again — it will never install
> into a nested `crystal-nova-pegasus-theme/crystal-nova-pegasus-theme/`.
> A nested install left behind by U1 is detected and repaired automatically
> on launch.

## Legacy Phase 1.7 bootstrap

If your theme was installed manually (the Phase 1.7 layout) it has no
`crystal-version.json`. That is fine — the app treats a theme directory
without a version marker as a valid legacy install:

- It shows the installed theme as "legacy" (version unknown).
- The first update stages the new release, validates its
  `crystal-version.json`, swaps it in, and records the version from that
  point on. Legacy installs are upgraded, never rejected.

## Updating

1. The app queries the GitHub API for the latest commit on `main` and reads
   `crystal-version.json` from the repository.
2. If the remote version is newer than the installed one, it downloads the
   release ZIP from `codeload.github.com` (direct download, no redirect
   hopping), showing progress.
3. The ZIP is validated before anything is touched:
   - `crystal-version.json` must exist, parse, and match the expected remote version;
   - every extracted path must stay inside the staging directory
     (zip-slip protection).
4. The validated package is staged to `crystal-nova-pegasus-theme.new`,
   the live theme is moved to `crystal-nova-pegasus-theme.backup`, and the
   staged copy is promoted to `crystal-nova-pegasus-theme`. The live theme is
   never updated file-by-file.
5. Pegasus is relaunched via its normal launch intent
   (`org.pegasus_frontend.android`) so it picks up the new theme. If Pegasus
   isn't installed, the app tells you to restart it manually instead of
   failing.

## Rollback

Every update keeps the previous theme in `crystal-nova-pegasus-theme.backup`.
If the new theme misbehaves, the rollback action restores the backup as the
live theme and relaunches Pegasus. The backup is the last working install —
rollback returns you to exactly what you had before the update.

## Offline operation

- With no network, the app shows the installed theme state (installed
  version, legacy status, backup presence) and disables update checks —
  it never errors out or clears state just because the network is down.
- Update checks and downloads require internet; everything else (viewing
  status, rolling back to the on-device backup) works fully offline.

## Security boundaries

- **Downloads come from one place only.** The allowlist is limited to
  `api.github.com`, `codeload.github.com`, `github.com`, and
  `raw.githubusercontent.com`. Anything else is rejected.
- **No tokens, no accounts, no telemetry.** The theme repository is public;
  the app authenticates to nothing and phones home to nothing.
- **No privileged APIs.** No root, no force-stop, no package-management
  calls. Pegasus is opened only through its public launch intent.
- **Scoped storage only.** The app uses the SAF tree you grant it and
  `DocumentFile` — it does not request `MANAGE_EXTERNAL_STORAGE`
  (all-files access).
- **Theme directory only.** Inside the granted theme tree the updater
  touches only `crystal-nova-pegasus-theme`,
  `crystal-nova-pegasus-theme.new`, and
  `crystal-nova-pegasus-theme.backup`. It never touches ROMs, BIOS files,
  saves, emulator or Pegasus configuration, `metadata.pegasus.txt`, or
  scraped artwork.
- **ROM access is importer-only and grant-scoped.** The game importer
  writes game files solely into the ROM-root folder you grant it in
  IMPORTER SETTINGS, through its own transaction (inspect → stage →
  validate → move → verify → clean up → delete source last). No other
  part of the app touches ROM storage, and no broad storage permission
  is requested anywhere.
- **Validate-then-swap.** A release is only promoted after its
  `crystal-version.json` validates and every ZIP entry is confirmed to
  extract inside the staging directory.

## Project layout

```
app/src/main/java/io/crystalnova/manager/
  data/      GitHub API + download client, version parsing, storage interfaces
  storage/   SAF DocumentFile theme storage (live / staging / backup)
  updater/   Update state machine, ZIP validation, orchestration
  ui/        Compose screens (ThemeUpdateScreen, components, design tokens)
  MainActivity.kt  Wiring: SAF picker, permissions, Pegasus launch intent
app/src/test/      Unit tests (JUnit 4, run on the JVM)
```

Screenshots/Paparazzi are intentionally deferred — visual testing happens on
the real Nova hardware.
