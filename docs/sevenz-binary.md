# 7zz native binary — provenance

The Game Importer's 7z fallback shells out to `7zz` (7-Zip) in a
separate process with its own memory space. This exists because some
7z archives (e.g. LZMA with a 256MB dictionary) cannot be touched
inside the app's heap — in-app `SevenZFile.getInputStream` allocates
the full dictionary, which exceeds the ~276MB Dalvik heap.

## Binary

| Field | Value |
|---|---|
| File in repo | `app/src/main/jniLibs/arm64-v8a/lib7zz.so` |
| Source | Official 7-Zip 26.04 release, `7z2604-linux-arm64.tar.xz` |
| Download URL | `https://github.com/ip7z/7zip/releases/download/26.04/7z2604-linux-arm64.tar.xz` |
| Tarball SHA-256 | `5b0ac3aa91c1e3499f011d6af69d7f6b4de3ef2f011d9e2ff44c58ebd04aa388` |
| Tarball size | 1,316,372 bytes |
| Binary used | `7zzs` from the tarball (the **static** build), renamed to `lib7zz.so` |
| Binary size | 3,121,936 bytes |
| `file` | `ELF 64-bit LSB executable, ARM aarch64, statically linked` |
| Dynamic section | none (`readelf -d`: no NEEDED entries, no INTERP) |

The dynamic `7zz` in the same tarball links glibc
(`libpthread.so.0`, `/lib/ld-linux-aarch64.so.1`) and **cannot** run
on Android (bionic). The static `7zzs` makes no libc calls at all, so
it runs anywhere the kernel accepts ARM64 ELFs.

## Why the `.so` name

Same trick as `libchdman.so`: files under `jniLibs/<abi>/` named
`lib*.so` are extracted by the package manager into the app's
native-library directory thanks to `android:extractNativeLibs="true"`,
where the app executes them via `ProcessBuilder`. The filename is
irrelevant to the kernel.

## License

7-Zip is under the GNU LGPL v2.1 with the unRAR restriction
(see `License.txt` in the upstream tarball). The binary is used
unmodified.

## If the static binary ever fails on-device

The acceptance gate is the Nova hardware run. If the static
non-PIE executable is ever rejected, the fallback is building 7-Zip
from source with the Android NDK (bionic) as a PIE executable —
the same recipe as `docs/chdman-build.md`.
