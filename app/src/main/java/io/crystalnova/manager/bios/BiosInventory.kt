package io.crystalnova.manager.bios

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.KeyValueStore

/** One file found by the recursive BIOS scan. */
data class BiosFile(
    val name: String,
    val sizeBytes: Long,
    /** Path relative to the BIOS root, e.g. `ps2/scph39001.bin`. */
    val relativePath: String,
)

/** BIOS root probe result. */
sealed interface BiosRootState {
    /** A persisted, readable BIOS folder grant. */
    data class Granted(val treeUri: String, val displayPath: String) : BiosRootState
    /** No grant yet; the picker should open at [candidateTreeUri]. */
    data class NotGranted(val candidateTreeUri: String?, val displayPath: String?) : BiosRootState
    /** No ROM root configured, so no volume can be derived. */
    data object NoRomRoot : BiosRootState
}

/**
 * Reusable BIOS inventory layer (v24, deliberately small).
 *
 * - Root discovery: the preferred BIOS root is the `bios/` sibling of
 *   the persisted ROM root ([BiosDiscovery]); a SAF grant is adopted
 *   explicitly via [adoptBiosTreeUri] — Crystal never assumes access.
 * - Scanning is recursive ([BiosFile.relativePath] keeps nesting) with
 *   depth/file caps, because a copied EmuDeck tree nests.
 * - PS2 is the only platform whose status can gate READY; see
 *   [ps2Status] / [ps2Issue]. Optional/HLE platforms always report
 *   READY and never create issues.
 *
 * User-owned files only: Crystal never distributes, downloads, or
 * links firmware. A valid-looking file on the SD card is
 * IMPORT_REQUIRED until the user confirms the emulator-side import —
 * Crystal cannot write NetherSX2's app-private `files/bios/` on
 * Android 11+ (scoped storage; even MANAGE_EXTERNAL_STORAGE cannot
 * reach another app's `Android/data`), so completion is attested, not
 * auto-detected.
 *
 * [lister] is a seam for JVM unit tests; null means the SAF-backed
 * default. [logger] is the same Logcat seam [StorageLocations] uses.
 */
class BiosInventory(
    private val context: Context?,
    private val prefs: KeyValueStore,
    private val romTreeUriProvider: () -> String?,
    internal var lister: ((treeUri: String) -> List<BiosFile>?)? = null,
    private val logger: (tag: String, msg: String, err: Throwable?) -> Unit =
        { tag, msg, err -> Log.w(tag, msg, err) },
) {
    companion object {
        const val KEY_BIOS_TREE_URI = "bios_tree_uri"
        const val KEY_PS2_IMPORT_CONFIRMED = "bios_ps2_import_confirmed"
        private const val TAG = "BiosInventory"
        private const val MAX_SCAN_DEPTH = 8
        private const val MAX_SCAN_FILES = 2000
    }

    fun biosTreeUri(): String? = prefs.getString(KEY_BIOS_TREE_URI)

    fun isPs2ImportConfirmed(): Boolean =
        prefs.getString(KEY_PS2_IMPORT_CONFIRMED) == "1"

    fun setPs2ImportConfirmed(confirmed: Boolean) {
        try {
            if (confirmed) prefs.putString(KEY_PS2_IMPORT_CONFIRMED, "1")
            else prefs.remove(KEY_PS2_IMPORT_CONFIRMED)
        } catch (e: Exception) {
            logger(TAG, "setPs2ImportConfirmed failed", e)
        }
    }

    /**
     * Takes the persistable SAF grant for the BIOS folder and persists
     * it. Read access is all Crystal needs (scan only). False on
     * SecurityException; never throws.
     */
    fun adoptBiosTreeUri(cr: ContentResolver, uri: Uri): Boolean {
        return try {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.putString(KEY_BIOS_TREE_URI, uri.toString())
            // A newly adopted folder may hold a different BIOS than the
            // one the user previously confirmed — re-verify from scratch.
            prefs.remove(KEY_PS2_IMPORT_CONFIRMED)
            true
        } catch (e: SecurityException) {
            logger(TAG, "persistable permission denied for BIOS folder", e)
            false
        } catch (e: Exception) {
            logger(TAG, "adopt BIOS folder failed", e)
            false
        }
    }

    fun clearBiosFolder() {
        try {
            prefs.remove(KEY_BIOS_TREE_URI)
            prefs.remove(KEY_PS2_IMPORT_CONFIRMED)
        } catch (e: Exception) {
            logger(TAG, "clearBiosFolder failed", e)
        }
    }

    /** True when the persisted BIOS grant still reads. */
    fun hasBiosAccess(): Boolean {
        val uri = biosTreeUri() ?: return false
        val ctx = context ?: return false
        return try {
            val doc = DocumentFile.fromTreeUri(ctx, Uri.parse(uri))
            doc != null && doc.canRead()
        } catch (_: Exception) {
            false
        }
    }

    fun probeRoot(): BiosRootState {
        val granted = biosTreeUri()
        if (granted != null) {
            return if (hasBiosAccess()) {
                BiosRootState.Granted(granted, displayPathFor(granted))
            } else {
                // Grant persisted but lost: fall through to re-pick at
                // the preferred location.
                val rom = romTreeUriProvider() ?: return BiosRootState.NoRomRoot
                BiosRootState.NotGranted(
                    BiosDiscovery.candidateBiosTreeUri(rom),
                    BiosDiscovery.preferredBiosDisplayPath(rom),
                )
            }
        }
        val rom = romTreeUriProvider() ?: return BiosRootState.NoRomRoot
        return BiosRootState.NotGranted(
            BiosDiscovery.candidateBiosTreeUri(rom),
            BiosDiscovery.preferredBiosDisplayPath(rom),
        )
    }

    /** Friendly path for a BIOS tree URI; never a raw content:// URI. */
    fun displayPathFor(treeUri: String): String {
        val marker = "/tree/"
        val idx = treeUri.indexOf(marker)
        if (idx < 0) return "LOCATION UNAVAILABLE"
        val encoded = treeUri.substring(idx + marker.length).substringBefore('/')
        val docId = try {
            java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            return "LOCATION UNAVAILABLE"
        }
        val volume = docId.substringBefore(':')
        val rest = docId.substringAfter(':', "").trimStart('/')
        if (volume.isEmpty()) return "LOCATION UNAVAILABLE"
        val tail = if (rest.isEmpty()) "/" else "/$rest"
        return if (volume == "primary") "INTERNAL STORAGE $tail" else "SD CARD $tail"
    }

    /**
     * Recursive scan of the granted BIOS root. Returns null when there
     * is no readable grant; an empty list when the folder reads but
     * holds nothing. Never throws.
     */
    fun scan(): List<BiosFile>? {
        val uri = biosTreeUri() ?: return null
        if (!hasBiosAccess()) return null
        return try {
            (lister ?: ::defaultLister)(uri) ?: emptyList()
        } catch (e: Exception) {
            logger(TAG, "BIOS scan failed", e)
            emptyList()
        }
    }

    private fun defaultLister(treeUri: String): List<BiosFile>? {
        val ctx = context ?: return null
        return try {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) ?: return null
            val out = mutableListOf<BiosFile>()
            fun walk(dir: DocumentFile, rel: String, depth: Int) {
                if (depth > MAX_SCAN_DEPTH || out.size >= MAX_SCAN_FILES) return
                val kids = try {
                    dir.listFiles()
                } catch (_: Exception) {
                    return
                }
                for (k in kids) {
                    val name = k.name ?: continue
                    try {
                        if (k.isDirectory) {
                            walk(k, "$rel$name/", depth + 1)
                        } else if (k.isFile) {
                            out.add(BiosFile(name, k.length(), rel + name))
                        }
                    } catch (_: Exception) {
                        // One bad entry must not abort the whole scan.
                    }
                }
            }
            walk(root, "", 0)
            out
        } catch (_: Exception) {
            null
        }
    }

    // ---------- PS2 status ----------

    /**
     * The valid-looking PS2 BIOS in [files]: name matches the SCPH
     * pattern AND the size is exactly a retail PS2 BIOS image.
     */
    fun detectPs2Bios(files: List<BiosFile>): BiosFile? =
        files.firstOrNull {
            BiosFirmwareTable.PS2.candidateNameRegex.matches(it.name) &&
                it.sizeBytes == BiosFirmwareTable.PS2.expectedSizeBytes
        }

    /**
     * A name-matching file with the wrong size — present but not a file
     * we would trust NetherSX2 to accept.
     */
    fun detectPs2Unverified(files: List<BiosFile>): BiosFile? =
        files.firstOrNull {
            BiosFirmwareTable.PS2.candidateNameRegex.matches(it.name) &&
                it.sizeBytes != BiosFirmwareTable.PS2.expectedSizeBytes
        }

    /**
     * PS2 firmware status. [ps2GameCount] gates everything: with no PS2
     * games, firmware is NOT_REQUIRED and creates no issue.
     */
    fun ps2Status(ps2GameCount: Int, files: List<BiosFile>?): BiosStatus {
        if (ps2GameCount <= 0) return BiosStatus.NOT_REQUIRED
        if (files == null) return BiosStatus.REQUIRED_MISSING
        if (detectPs2Bios(files) != null) {
            return if (isPs2ImportConfirmed()) BiosStatus.READY else BiosStatus.IMPORT_REQUIRED
        }
        return if (detectPs2Unverified(files) != null) BiosStatus.FOUND_UNVERIFIED
        else BiosStatus.REQUIRED_MISSING
    }

    /**
     * The HOME readiness issue for PS2, or null when PS2 firmware does
     * not block READY. Only REQUIRED_MISSING, IMPORT_REQUIRED and
     * FOUND_UNVERIFIED create issues — and only when PS2 games exist.
     */
    fun ps2Issue(ps2GameCount: Int, files: List<BiosFile>?): BiosIssue? {
        return when (ps2Status(ps2GameCount, files)) {
            BiosStatus.REQUIRED_MISSING -> BiosIssue("PS2", "BIOS MISSING")
            BiosStatus.IMPORT_REQUIRED -> BiosIssue("PS2", "BIOS REQUIRED")
            BiosStatus.FOUND_UNVERIFIED -> BiosIssue("PS2", "BIOS UNVERIFIED")
            else -> null
        }
    }

    /**
     * Display status for any platform on the BIOS screen. Non-PS2
     * platforms never gate: OPTIONAL (HLE fallback) reads READY,
     * everything else NOT_REQUIRED.
     */
    fun statusForPlatform(platformSlug: String): BiosStatus {
        val fw = BiosFirmwareTable.forPlatform(platformSlug) ?: return BiosStatus.NOT_REQUIRED
        return when (fw.requirement) {
            BiosRequirement.REQUIRED -> BiosStatus.IMPORT_REQUIRED // refined by ps2Status with real data
            BiosRequirement.OPTIONAL -> BiosStatus.READY
            BiosRequirement.NOT_REQUIRED -> BiosStatus.NOT_REQUIRED
        }
    }
}
