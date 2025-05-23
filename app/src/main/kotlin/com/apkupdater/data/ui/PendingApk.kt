package com.apkupdater.data.ui

import java.io.File

/**
 * Represents an APK file downloaded by ApkMirrorUpdateWorker, pending installation.
 *
 * @param name The display name (e.g., derived from filename, or could be improved later with PackageManager).
 * @param packageName The package name, potentially parsed from the filename.
 * @param versionName The version name, potentially parsed from the filename.
 * @param filePath The absolute path to the .apk file.
 * @param file The File object itself.
 */
data class PendingApk(
    val name: String,
    val packageName: String?, // May not always be parseable from filename
    val versionName: String?, // May not always be parseable from filename
    val filePath: String,
    val file: File
)
