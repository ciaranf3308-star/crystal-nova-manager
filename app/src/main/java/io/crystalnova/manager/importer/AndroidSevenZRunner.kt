package io.crystalnova.manager.importer

import android.os.Build
import java.io.File

/**
 * Production [SevenZRunner]: the 7zzs static ARM64 executable bundled
 * as `app/src/main/jniLibs/arm64-v8a/lib7zz.so` (official 7-Zip 26.04
 * static build from 7-zip.org — see `docs/sevenz-binary.md`),
 * extracted by the package manager into the app's native-library
 * directory thanks to `android:extractNativeLibs="true"`.
 *
 * Guards: the binary must exist, be executable, and the device must
 * be arm64-v8a. The process mechanics are inherited from
 * [ProcessSevenZRunner].
 */
class AndroidSevenZRunner(
    appNativeLibDir: File,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) : ProcessSevenZRunner(File(appNativeLibDir, "lib7zz.so")) {

    /** arm64-v8a only: that is the single ABI we ship 7zz for. */
    val abiSupported: Boolean get() = "arm64-v8a" in supportedAbis

    override val available: Boolean
        get() = abiSupported && super.available
}
