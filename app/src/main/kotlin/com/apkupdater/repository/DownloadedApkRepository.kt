package com.apkupdater.repository

import android.content.Context
import android.util.Log
import com.apkupdater.data.ui.PendingApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadedApkRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloadedApkRepo"
        const val APK_MIRROR_DOWNLOAD_DIR_NAME = "apk_mirror_downloads"
    }

    private val downloadDir = File(context.cacheDir, APK_MIRROR_DOWNLOAD_DIR_NAME)

    /**
     * Scans the dedicated download directory for .apk files.
     * Attempts to parse package name and version from the filename.
     * Example filename format: "com.example.app_v1.2.3.apk"
     */
    suspend fun getPendingApks(): List<PendingApk> = withContext(Dispatchers.IO) {
        if (!downloadDir.exists() || !downloadDir.isDirectory) {
            Log.i(TAG, "Download directory '$APK_MIRROR_DOWNLOAD_DIR_NAME' does not exist or is not a directory.")
            return@withContext emptyList()
        }

        val apkFiles = downloadDir.listFiles { _, name -> name.endsWith(".apk") }
        if (apkFiles == null || apkFiles.isEmpty()) {
            Log.i(TAG, "No .apk files found in $APK_MIRROR_DOWNLOAD_DIR_NAME.")
            return@withContext emptyList()
        }

        Log.d(TAG, "Found ${apkFiles.size} APK files in $APK_MIRROR_DOWNLOAD_DIR_NAME.")

        return@withContext apkFiles.mapNotNull { file ->
            try {
                val nameWithoutExtension = file.nameWithoutExtension
                // Filename format: ${safePackageName}_v${safeVersion}
                val parts = nameWithoutExtension.split("_v")
                val packageName = if (parts.isNotEmpty()) parts[0].replace("_", ".") else null
                val versionName = if (parts.size > 1) parts[1].replace("_", ".") else null

                PendingApk(
                    name = file.name, // Simple name for now, could be app label if we query PackageManager
                    packageName = packageName,
                    versionName = versionName,
                    filePath = file.absolutePath,
                    file = file
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing filename ${file.name}: ${e.message}", e)
                // Fallback if parsing fails, still include the file
                PendingApk(
                    name = file.name,
                    packageName = null,
                    versionName = null,
                    filePath = file.absolutePath,
                    file = file
                )
            }
        }
    }

    /**
     * Deletes a specific APK file from the download directory.
     */
    suspend fun deletePendingApk(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (file.exists() && file.parentFile == downloadDir) { // Security check
                return@withContext file.delete()
            }
            Log.w(TAG, "File not found or not in download directory: $filePath")
            return@withContext false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException deleting file $filePath: ${e.message}", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file $filePath: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Deletes all APK files from the download directory.
     */
    suspend fun deleteAllPendingApks(): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        if (!downloadDir.exists() || !downloadDir.isDirectory) {
            return@withContext 0
        }
        downloadDir.listFiles { _, name -> name.endsWith(".apk") }?.forEach {
            if (it.delete()) {
                deletedCount++
            } else {
                Log.w(TAG, "Failed to delete file: ${it.absolutePath}")
            }
        }
        Log.i(TAG, "Deleted $deletedCount APKs from $APK_MIRROR_DOWNLOAD_DIR_NAME.")
        return@withContext deletedCount
    }
}
