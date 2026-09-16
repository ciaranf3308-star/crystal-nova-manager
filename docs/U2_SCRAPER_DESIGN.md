# Crystal Nova Manager — Phase U2 Scraper Design (corrected)

Supersedes the 2026-09-16 research report where they disagree. U2 is NOT
published by this milestone. U1.2 theme updater + app self-updater behaviour
is unchanged.

## 1. Storage: persistent sibling data directory

Scraper assets MUST NOT live inside `crystal-nova-pegasus-theme/` — the U1
updater swaps/replaces that directory on update and rollback.

```
<themes-root>/crystal-nova-data/
  games/<platform>/<game-id>/
    front.png  spine.png  back.png  media.png  logo.png  screenshot.png
    manifest.json
  index.json
  cache/            # downloaded originals, keyed by URL SHA-256 (persistent)
```

Two-level download cache: the persistent level is SAF-backed
`crystal-nova-data/cache/` (atomic writes, survives restarts — a re-scrape
never re-downloads). The app-private cache dir holds disposable staging
files only (bitmap decoders need real `File`s); staging may be wiped at
any time and is re-staged from the persistent level on demand. A failed
download returns null and never poisons the persistent cache.

- The U1 updater continues to touch ONLY `crystal-nova-pegasus-theme`,
  `crystal-nova-pegasus-theme.new`, `crystal-nova-pegasus-theme.backup`.
  It never reads or writes `crystal-nova-data`.
- The data dir is created under the already-granted themes tree via the
  existing `ThemeFs` SAF abstraction — no new permission needed.
- Theme-side resolver (future theme-repo change): `CrystalAssets.js`
  resolves `../crystal-nova-data/games/<platform>/<game-id>/<slot>.png`
  relative to the installed theme dir. Both dir names are fixed, so the
  relative path is stable. Contract specified here; QML not built in U2.

## 2. Game identity: no full ROM hashing on scan

Initial identity = `platform + normalized relative ROM path`.
Cache `fileSize` + `lastModified` per game; on rescan, changed size/mtime
marks the entry stale for re-match.

Full ROM hashes are lazy/on-demand ONLY when:
- a provider supports useful hash matching, or
- ambiguity requires it, or
- the user explicitly requests deeper identification.

`HashService` computes streaming SHA-256/CRC32 cancellably. Small
cartridge ROMs may be hashed cheaply later — never a scan prerequisite.

## 3. Provider stack for this milestone

- `ArtworkProvider` / `MetadataProvider` interfaces stay generic and
  key-capable, so TheGamesDB (or another structured provider) can plug in
  later without touching the rest of the scraper.
- Do NOT implement the TheGamesDB live provider (manual-approval key
  conflicts with project requirements; no access will be requested).
- Working stack: **Libretro thumbnails** (keyless artwork: boxFront,
  screenshot, clearLogo) + **existing Pegasus game metadata**
  (`metadata.pegasus.txt` parsed read-only where present in/near the ROM
  tree) + **generated Crystal fallbacks** (spine, back, physical media).

## 4. Generated-artwork provenance: metadata only

No visible watermark on generated spine/back/disc/cart — the art must stay
clean for the physical-case renderer. Provenance lives in `manifest.json`:
`sourceType: GENERATED`, `generatedBy: crystal-spine-v1` (etc.). The
Manager UI may badge generated vs real; the PNGs stay clean.

## 5. Physical media kinds

`PlatformTable.mediaKind`: DISC, CARTRIDGE, UMD, GENERIC, UNKNOWN.
Only known platforms get platform-specific media. UNKNOWN → neutral
GENERIC representation (or the slot stays incomplete). Never render an
unknown platform as a cartridge.

## 6. Region detection (reduced scope, v1)

Baseline: filename tags (modern No-Intro + legacy aliases) + Pegasus
metadata where present. Cheap fixed-offset header reads for small
cartridge formats (SNES, N64, GB/GBC, GBA, Genesis, DS). NO ISO/CHD
parsing, no SYSTEM.CNF / PARAM.SFO in v1 — later enrichment pass.
Region may remain UNKNOWN. Artwork region preference:
detected → Europe → World → USA → other; prefer coherent front+back pair.

## 7. Retained from the approved design

Completeness states (NO_MATCH … COMPLETE_CASE_AND_MEDIA), per-asset
provenance (sourceType/provider/providerGameId/region/sourceUrl/local
path/hash), real-replaces-generated / user-never-overwritten rules,
5-level matching flow with 0.85 fuzzy threshold (weak matches recorded,
never auto-accepted), transactional SAF writes (temp + rename), Libretro
CDN→raw fallback with candidate ladder, generated spine/back/media
generators, SCRAPER section UI with dashboard/progress/cancel/retry,
per-game manifest + global index.json, future manual-artwork editor hooks
(restore-previous via cache/, regenerate entry points).

## 8. Failure isolation (implemented)

- Each generated slot (spine/back/media) renders inside its own
  try/catch. A renderer exception marks that slot FAILED and the scrape
  continues — the game lands at PARTIAL_CASE, never FAILED, because
  "missing spine/back/media is not a scrape failure".
- Provider artwork downloads are per-slot too: one slot's network failure
  never blocks the other slots or the generated fallbacks.
- Failed network requests never erase existing REAL assets and never
  poison the download cache.
- A downloaded candidate whose title confidence is `None` (weak fuzzy)
  is kept with full provenance but NEVER auto-accepted as the game's
  match: no providerId/providerGameId/confidence claim, and the provider
  loop continues so a stronger provider can still match.

## 9b. Transactional SAF writes (implemented)

`writeAtomically` is a backup/restore transaction, not delete-and-retry:
`<name>.tmp` is written, the existing file is moved to `<name>.bak`,
then tmp is renamed to `<name>`. If the final rename fails, the backup
is restored and the tmp discarded — the original is never deleted, so a
failed write can never lose it. Stale `.tmp`/`.bak` files from crashed
runs are cleaned at the start of each write and never left behind on
success.

## 9c. Cancellation honesty (implemented)

`ScrapeJob.run()` always ends with a final progress emission. A
cancellation caught inside the per-game guard breaks the loop and emits
`cancelled=true`; a cancellation outside that guard (loop-top
`ensureActive`, index load/save) is caught by an outer guard that emits
the same honest final state before rethrowing for structured
concurrency. `ScraperManager` surfaces "SCRAPE CANCELLED" as a
dismissible notice, and the progress header renders "— CANCELLED" in the
warning color while keeping the counts reached so far.

## 9. Scanner multi-disc dedup (implemented)

Per directory, a disc set yields one game entry:
- `game.bin` (or `game (Track 1).bin`, `game (Disc 1).bin`) is skipped when
  a sibling `game.cue`/`game.gdi`/`game.m3u` exists (track/disc numeric
  suffixes stripped before comparing; case-insensitive).
- `track01.bin`/`track02.raw`/`track03.img` are skipped when a sibling
  `.gdi` exists.
- Files listed inside an `.m3u` are skipped in favor of the `.m3u` entry
  (the playlist itself is never skipped).
- Dedup is scoped per directory — same names in different folders are
  independent. The filename decision is a pure function
  (`LibraryScanner.skipNames`) covered by unit tests; the SAF walk itself
  needs hardware validation.
