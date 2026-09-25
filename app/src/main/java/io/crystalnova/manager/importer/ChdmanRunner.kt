package io.crystalnova.manager.importer

import android.os.Build
import java.io.File

/**
 * Production [ChdConverter]: the chdman PIE executable bundled as
 * `app/src/main/jniLibs/arm64-v8a/libchdman.so` (built from MAME
 * source — see `docs/chdman-build.md`), extracted by the package
 * manager into the app's native-library directory thanks to
 * `android:extractNativeLibs="true"`.
 *
 * Guards: the binary must exist, be executable, and the device must
 * be arm64-v8a. The process mechanics are inherited from
 * [ProcessChdConverter].
 */
class ChdmanRunner(
    appNativeLibDir: File,
    supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) : ProcessChdConverter(File(appNativeLibDir, "libchdman.so")) {

    /** arm64-v8a only: that is the single ABI we ship chdman for. */
    val abiSupported: Boolean get() = "arm64-v8a" in supportedAbis

    override val available: Boolean
        get() = abiSupported && super.available
}
