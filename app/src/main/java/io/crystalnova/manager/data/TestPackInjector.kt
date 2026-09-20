package io.crystalnova.manager.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * u46 test utility: bulletproof delivery of the iiSU platform-pack test
 * ZIP. Chat attachments kept arriving corrupted on the Nova, so the
 * verified ZIP ships inside the APK as an asset and this copies it into
 * the shared Downloads collection on demand.
 *
 * Flow, all on [ioDispatcher] (production default is [Dispatchers.IO];
 * tests inject their own):
 *  1. Read the bundled asset and verify its SHA-256 against
 *     [EXPECTED_SHA256]. A mismatch aborts before anything is written.
 *  2. Insert into MediaStore Downloads (Android 10+, no storage
 *     permission needed for the app's own entry) and stream the bytes.
 *  3. Re-read the written entry and verify its SHA-256. A mismatch
 *     deletes the entry and reports failure.
 *
 * Every failure path returns [Result.Err] with an honest message —
 * never a fake success. The success message names the actual file in
 * Downloads (MediaStore may suffix a duplicate name).
 */
object TestPackInjector {

    const val ASSET_NAME = "crystal-test-pack.zip"

    /** SHA-256 of the verified crystal-test-pack.zip (2026-09-19). */
    const val EXPECTED_SHA256 =
        "367302c6b9959c331e9d3cd4a5e386703f597b30f50358ec1ef71c7b658eb123"

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Err(val message: String) : Result
    }

    suspend fun inject(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Result = withContext(ioDispatcher) {
        try {
            val bytes = try {
                context.assets.open(ASSET_NAME).use { it.readBytes() }
            } catch (e: Exception) {
                return@withContext Result.Err(
                    "TEST PACK ASSET MISSING IN APK — NOT COPIED.",
                )
            }
            if (!sha256Hex(bytes).equals(EXPECTED_SHA256, ignoreCase = true)) {
                return@withContext Result.Err(
                    "BUNDLED TEST PACK HASH MISMATCH — NOT COPIED.",
                )
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext Result.Err(
                    "INJECT NEEDS ANDROID 10 OR NEWER — NOT COPIED.",
                )
            }

            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, ASSET_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                pending,
            ) ?: return@withContext Result.Err(
                "COULD NOT CREATE DOWNLOADS ENTRY — NOT COPIED.",
            )
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: run {
                        resolver.delete(uri, null, null)
                        return@withContext Result.Err(
                            "COULD NOT WRITE FILE — NOT COPIED.",
                        )
                    }
                val written = resolver.openInputStream(uri)?.use { it.readBytes() }
                if (written == null ||
                    !sha256Hex(written).equals(EXPECTED_SHA256, ignoreCase = true)
                ) {
                    resolver.delete(uri, null, null)
                    return@withContext Result.Err(
                        "COPY VERIFICATION FAILED — ENTRY REMOVED, NOT COPIED.",
                    )
                }
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                val actualName = resolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: ASSET_NAME
                Result.Ok(
                    "COPIED + SHA-256 VERIFIED: Downloads/$actualName — " +
                        "IMPORT IT IN iiSU VIA APPEARANCE > iiSU THEMES.",
                )
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            Result.Err("INJECT FAILED: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Lowercase hex SHA-256. Pure function — unit-testable on the JVM. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
