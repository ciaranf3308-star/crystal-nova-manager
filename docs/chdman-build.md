# chdman native binaries — build recipe

The PS1 pipeline shells out to `chdman` (MAME's Compressed Hunks of
Data manager) for `createcd`/`verify`. Two binaries are built from the
same pinned MAME revision and committed to this repo:

| Binary | Path in repo | Size (bytes) |
|---|---|---|
| Android ARM64 PIE | `app/src/main/jniLibs/arm64-v8a/libchdman.so` | 2,556,216 |
| Linux x86_64 (CI test binary) | `tools/chdman-linux-x64/chdman` | 2,313,640 |

Both are stripped release builds. MAME source is **not** committed
(1.2 GB); only the recipe below plus the license/attribution.

## Inputs

- **Android NDK r28c** (`android-ndk-r28c-linux.zip`,
  722,261,334 bytes, clang 19.0.1).
- **MAME revision `ecf0add29f06ba131994dca5b88c3a0edf6c2ad8`**
  (`ecf0add2 nec/pc9821.cpp: remove SB16 left-over lambda`),
  shallow-fetched — no full clone needed:

```sh
git init mame && cd mame
git remote add origin https://github.com/mamedev/mame.git
git fetch --depth 1 origin ecf0add29f06ba131994dca5b88c3a0edf6c2ad8
git checkout ecf0add29f06ba131994dca5b88c3a0edf6c2ad8
```

## SDL shim (both targets)

`chdman` only needs five SDL clipboard helpers out of
`src/osd/modules/lib/osdlib_unix.cpp`, which it never calls. Instead
of building all of SDL, the build environment provides a shim —
MAME source stays pristine:

- `include/SDL2/SDL.h` — declarations of `SDL_HasClipboardText`,
  `SDL_GetClipboardText`, `SDL_free`, `SDL_SetClipboardText`,
  `SDL_GetError`.
- `src/sdl_shim.c` — no-op stubs (clipboard unsupported).
- `bin/sdl2-config` — answers `--cflags`/`--libs`/`--version` for
  MAME's build system.
- `lib/libSDL2.a` — the stubs compiled to a static archive
  (ARM64 via NDK clang for the Android build, x86_64 via system gcc
  for the Linux build), so the final binaries carry **zero** SDL
  runtime dependency.

## Linux x86_64 build (CI test binary)

```sh
cd mame
make TOOLS=1 EMULATOR=0 USE_QTDEBUG=0 -j2 build/projects/sdl/mame/gmake-linux/Makefile
# Link the static SDL shim instead of system SDL2 so the binary is
# self-contained on the CI runner:
sed -i 's|-lSDL2|/path/to/sdl-shim-linux/lib/libSDL2.a|g' \
    build/projects/sdl/mame/gmake-linux/chdman.make
make -C build/projects/sdl/mame/gmake-linux -j2 config=release chdman
strip chdman -o tools/chdman-linux-x64/chdman
```

(`USE_QTDEBUG=0`: the Qt debugger UI needs `qmake6`, which the build
host lacks; tools don't need it. The first attempt built every tool —
`make -C … chdman` afterwards builds only chdman and its
`utils/expat/7z/ocore_sdl/zlib/zstd/flac/utf8proc` deps.)

Verified: `file` → ELF 64-bit x86-64; `ldd` → only
linux-vdso/libstdc++/libm/libgcc_s/libc/ld-linux;
`./chdman --help` prints 0.288 usage;
real synthetic CUE/BIN `createcd` → `verify`
("Raw SHA1 verification successful!").

## Android ARM64 build (device binary)

```sh
cd mame
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
export SDL_INSTALL_ROOT=/path/to/sdl-shim   # the ARM64 shim
make android-ndk build/projects/sdl/mame/gmake-android-arm64/Makefile \
    TOOLS=1 EMULATOR=0 USE_QTDEBUG=0
# Static-link the NDK C++ runtime (devices don't ship libc++_shared.so
# for standalone executables):
sed -i 's|--target=aarch64-none-linux-android24|--target=aarch64-none-linux-android24 -static-libstdc++|' \
    build/projects/sdl/mame/gmake-android-arm64/chdman.make
make -C build/projects/sdl/mame/gmake-android-arm64 -j2 config=release chdman
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
    chdman -o app/src/main/jniLibs/arm64-v8a/libchdman.so
```

Verified with `llvm-readelf`: ELF64, AArch64, `Type: DYN`
(PIE — required for `ProcessBuilder` exec on Android),
interpreter `/system/bin/linker64`, `NEEDED` only
`libc.so libdl.so libm.so libandroid.so liblog.so`
(all Android system libraries).

The app loads it via `ChdmanRunner` from the app's native-library
directory (extracted thanks to
`android:extractNativeLibs="true"` in the manifest) and executes it
as a PIE binary — the `.so` name is only so the package manager
treats it as a native library.

## License and attribution

`chdman` is built from MAME sources. MAME as a whole is made
available under the **GNU General Public License version 2 or later**
(see `COPYING` and `docs/legal/GPL-2.0` in the pinned revision;
individual files carry their own headers, e.g. `src/tools/chdman.cpp`
is BSD-3-Clause). We treat the resulting binaries as
**GPL-2.0-or-later** — the same conclusion as other projects bundling
MAME's chdman — and comply as follows:

- The GPL-2.0 license text ships in the app at
  `app/src/main/assets/legal/GPL-2.0.txt`.
- This recipe plus the pinned revision above identifies the exact
  corresponding source: MAME at
  `ecf0add29f06ba131994dca5b88c3a0edf6c2ad8`
  (https://github.com/mamedev/mame), built with the commands in this
  file. MAME source is not modified.

### Written offer (GPLv2 §3b)

We offer, valid for at least three years from the distribution of any
build containing `libchdman.so` / `tools/chdman-linux-x64/chdman`, to
give any third party a complete machine-readable copy of the
corresponding source code (the MAME revision above plus the SDL shim
sources described here) on a medium customarily used for software
interchange, for no more than the cost of physically performing the
distribution. Request it via the repository issue tracker, referencing
"chdman source offer".
