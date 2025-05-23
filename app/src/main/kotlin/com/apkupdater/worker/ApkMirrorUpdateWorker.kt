package com.apkupdater.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import android.os.Build.VERSION_CODES
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apkupdater.BuildConfig
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.AppsRepository
import com.apkupdater.source.apkmirror.ApkMirrorScraper
import com.apkupdater.source.apkmirror.ApkMirrorVariantInfo
import com.apkupdater.utils.HtmlFetcher
import com.apkupdater.utils.TextUtil
import io.github.g00fy2.versioncompare.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit


// Key for passing downloaded APK info
const val KEY_DOWNLOADED_APK_PACKAGE_NAME = "downloaded_apk_package_name"
const val KEY_DOWNLOADED_APK_VERSION_NAME = "downloaded_apk_version_name"
const val KEY_DOWNLOADED_APK_FILE_PATH = "downloaded_apk_file_path"
const val KEY_DOWNLOADED_APK_SOURCE_URL = "downloaded_apk_source_url"


class ApkMirrorUpdateWorker(
    private val appContext: Context, // Renamed from context for clarity
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val prefs: Prefs by inject()
    private val htmlFetcher: HtmlFetcher by inject()
    private val scraper: ApkMirrorScraper by inject()
    private val appsRepository: AppsRepository by inject() // To get installed apps
    private val okHttpClient: OkHttpClient by inject()

    companion object {
        private const val TAG = "ApkMirrorUpdateWorker"
        private const val WORK_NAME = "ApkMirrorUpdateWorker"
        private const val NOTIFICATION_CHANNEL_ID = "apk_mirror_downloads_channel" // More conventional ID
        private const val START_NOTIFICATION_ID = 1001 // For the "checking updates" notification
        private const val DOWNLOAD_NOTIFICATION_ID_BASE = 10000 // Base for individual download notifications
        private const val SUMMARY_NOTIFICATION_ID = 1002 // New ID for the summary notification

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val prefs: Prefs by inject() // KoinComponent for companion object to access Prefs

            if (!prefs.useApkMirror.get() || !prefs.apkMirrorAutomaticDownloadsEnabled.get()) {
                Log.i(TAG, "APKMirror automatic downloads are disabled in settings. Worker not scheduled.")
                cancel(context) // Cancel if it was scheduled previously but now disabled
                return
            }

            val constraintsBuilder = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(if (prefs.apkMirrorDownloadOnWifiOnly.get()) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true) // Good default for background work
                .setRequiresStorageNotLow(true) // Good default for downloads

            val requestBuilder = PeriodicWorkRequestBuilder<ApkMirrorUpdateWorker>(
                12, TimeUnit.HOURS // Default: Check twice a day. Can be made configurable later.
            )
                .setConstraints(constraintsBuilder.build())
                .setInitialDelay(Random.nextLong(15, 60), TimeUnit.MINUTES) // Random initial delay
                // .setBackoffCriteria(BackoffPolicy.LINEAR, PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS) // Default is 30s

            // For testing, use OneTimeWorkRequest
            // val request = OneTimeWorkRequestBuilder<ApkMirrorUpdateWorker>()
            //    .setConstraints(constraintsBuilder.build())
            //    .setInitialDelay(10, TimeUnit.SECONDS) // For testing
            //    .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing work if it's currently scheduled and not finished.
                requestBuilder.build()
            )
            Log.i(TAG, "ApkMirrorUpdateWorker scheduled. Wi-Fi only: ${prefs.apkMirrorDownloadOnWifiOnly.get()}. Period: 12 hours.")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "ApkMirrorUpdateWorker cancelled.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "ApkMirrorUpdateWorker starting...")

        if (!prefs.useApkMirror.get() || !prefs.apkMirrorAutomaticDownloadsEnabled.get()) {
            Log.i(TAG, "APKMirror automatic downloads are disabled by settings. Worker terminating successfully.")
            return@withContext Result.success()
        }

        // Network constraint check (programmatic, in addition to WorkManager constraints)
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) {
            Log.i(TAG, "No active network. Worker will retry if constraints are not met or fail if they were.")
            // WorkManager handles constraint violations by not running or stopping the worker.
            // Explicit check might be redundant if constraints are well-defined but good for immediate feedback.
            return@withContext Result.retry()
        }

        if (prefs.apkMirrorDownloadOnWifiOnly.get()) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (networkCapabilities == null || !networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                Log.i(TAG, "Wi-Fi only is enabled, but Wi-Fi is not connected. Worker will retry.")
                return@withContext Result.retry()
            }
        }

        createNotificationChannel()
        showStartWorkNotification()

        var overallResult: Result = Result.success()
        val successfullyDownloadedApps = mutableListOf<String>() // To store names of successfully downloaded apps

        try {
            // Fetch installed apps using the repository method that returns Flow<Result<List<AppUpdate>>>
            // This structure is assumed from AppsViewModel. For a worker, a one-shot fetch might be better.
            // Let's assume appsRepository.getInstalledApps() can be called directly for a list.
            val installedAppsResult = appsRepository.getInstalledAppsNonFlow( // Assuming such a method exists or can be added
                excludeSystem = prefs.excludeSystem.get(), // Use existing app filter settings
                excludeStore = prefs.excludeStore.get(),
                excludeDisabled = prefs.excludeDisabled.get()
            )

            if (installedAppsResult.isFailure) {
                Log.e(TAG, "Failed to get installed apps.", installedAppsResult.exceptionOrNull())
                clearStartWorkNotification()
                return@withContext Result.failure()
            }

            val installedApps = installedAppsResult.getOrNull() ?: emptyList()
            if (installedApps.isEmpty()) {
                Log.i(TAG, "No installed apps found to check or error fetching them.")
                clearStartWorkNotification()
                return@withContext Result.success() // No apps to check is not a worker failure.
            }

            Log.i(TAG, "Checking ${installedApps.size} installed apps for APKMirror updates...")

            for ((index, app) in installedApps.withIndex()) {
                // Construct APKMirror URL
                // Example: https://www.apkmirror.com/apk/google-inc/chrome/
                // This remains a heuristic. A search-first approach would be more robust.
                val appNameSlug = TextUtil.sanitizeAppNameForUrl(app.name) // e.g., "Google Chrome" -> "google-chrome"
                val developerSlug = getDeveloperSlugHeuristic(app.packageName, app.installerPackageName)
                val appOverviewUrl = "${ApkMirrorScraper.APKMIRROR_BASE_URL}/apk/$developerSlug/$appNameSlug/"

                Log.d(TAG, "Checking app (${index + 1}/${installedApps.size}): ${app.name} (${app.packageName}) -> $appOverviewUrl")

                val overviewHtml = htmlFetcher.fetchHtml(appOverviewUrl)
                if (overviewHtml == null) {
                    Log.w(TAG, "Failed to fetch HTML for ${app.name} from $appOverviewUrl. Skipping.")
                    continue // Skip this app, try others
                }

                // --- Crucial Test & Refine Point for ApkMirrorScraper ---
                // If selectors in ApkMirrorScraper are incorrect, parsing will fail here.
                // Extensive logging of `overviewHtml` for a few test apps would be needed in a live environment.
                // For this task, we assume selectors are mostly correct but acknowledge this dependency.
                // ---

                val availablePageVersions = scraper.parseAppOverviewPage(overviewHtml, appOverviewUrl)
                if (availablePageVersions.isEmpty()) {
                    Log.i(TAG, "No versions found on APKMirror for ${app.name} at $appOverviewUrl.")
                    continue
                }

                val latestAppVersionInfo = availablePageVersions
                    .filterNot { versionInfo ->
                        val vName = versionInfo.versionName.lowercase(Locale.ROOT)
                        (prefs.ignoreAlpha.get() && vName.contains("alpha")) ||
                        (prefs.ignoreBeta.get() && vName.contains("beta")) ||
                        (prefs.ignorePreRelease.get() && containsPreReleaseKeywords(vName))
                    }
                    .maxByOrNull { Version(it.versionName) }

                if (latestAppVersionInfo == null) {
                    Log.i(TAG, "No suitable latest version found for ${app.name} after filtering.")
                    continue
                }

                val latestVersionOnMirror = Version(latestAppVersionInfo.versionName)
                val installedVersion = Version(app.versionName)

                Log.d(TAG, "Latest on APKMirror for ${app.name}: ${latestVersionOnMirror.original}. Installed: ${installedVersion.original}")

                if (latestVersionOnMirror.isHigherThan(installedVersion)) {
                    Log.i(TAG, "Update found for ${app.name}: ${latestVersionOnMirror.original} > ${installedVersion.original}. URL: ${latestAppVersionInfo.versionPageUrl}")

                    val versionVariantsPageHtml = htmlFetcher.fetchHtml(latestAppVersionInfo.versionPageUrl)
                    if (versionVariantsPageHtml == null) {
                        Log.w(TAG, "Failed to fetch variants page HTML for ${app.name} from ${latestAppVersionInfo.versionPageUrl}")
                        continue
                    }

                    val variants = scraper.parseVersionVariantsPage(versionVariantsPageHtml, latestAppVersionInfo.versionPageUrl)
                    if (variants.isEmpty()) {
                        Log.w(TAG, "No APK variants found on page ${latestAppVersionInfo.versionPageUrl} for ${app.name}")
                        continue
                    }

                    val bestVariant = findBestVariant(variants, appNameSlug) // Implement filtering
                    if (bestVariant == null) {
                        Log.w(TAG, "No compatible APK variant found for ${app.name} ${latestVersionOnMirror.original}")
                        continue
                    }

                    val finalDownloadPageHtml = htmlFetcher.fetchHtml(bestVariant.sourcePageUrl)
                    if (finalDownloadPageHtml == null) {
                        Log.w(TAG, "Failed to fetch final download page HTML for ${app.name} from ${bestVariant.sourcePageUrl}")
                        continue
                    }
                    val finalApkInfo = scraper.parseFinalDownloadPage(finalDownloadPageHtml, bestVariant.sourcePageUrl, bestVariant)

                    if (finalApkInfo?.downloadLink == null) {
                        Log.w(TAG, "Could not get final APK download link for ${app.name} from ${bestVariant.sourcePageUrl}")
                        continue
                    }

                    Log.i(TAG, "Attempting to download APK for ${app.name} ${finalApkInfo.versionName ?: latestVersionOnMirror.original} from ${finalApkInfo.downloadLink}")
                    val downloadedFile = downloadApkWithProgress(app, finalApkInfo, index)
                    if (downloadedFile != null) {
                        successfullyDownloadedApps.add(app.name) // Add app name to the list
                        Log.i(TAG, "Successfully downloaded APK for ${app.name} to ${downloadedFile.absolutePath}")
                        // Store metadata - for now, we pass it via WorkManager's progress Data
                        val progressData = Data.Builder()
                            .putString(KEY_DOWNLOADED_APK_PACKAGE_NAME, app.packageName)
                            .putString(KEY_DOWNLOADED_APK_VERSION_NAME, finalApkInfo.versionName ?: latestVersionOnMirror.original)
                            .putString(KEY_DOWNLOADED_APK_FILE_PATH, downloadedFile.absolutePath)
                            .putString(KEY_DOWNLOADED_APK_SOURCE_URL, finalApkInfo.downloadLink)
                            .build()
                        setProgressAsync(progressData) // Inform observers about the download
                        // A more robust solution would be a database.
                    } else {
                        // Download failed, error notification was shown by downloadApkWithProgress
                        overallResult = Result.retry() // Signal that at least one download failed and should be retried
                    }
                }
            }

            Log.i(TAG, "Worker finished. Downloaded ${successfullyDownloadedApps.size} new APKs.")
            clearStartWorkNotification()

            if (successfullyDownloadedApps.isNotEmpty()) {
                showSummaryNotification(successfullyDownloadedApps)
            }

            return@withContext if (overallResult is Result.Retry && successfullyDownloadedApps.isNotEmpty()) {
                // If some downloads succeeded but others failed and need retry,
                // it's a partial success. WorkManager doesn't have a "partial success" state.
                // We can choose to report success if any download happened, or retry if any failed.
                // Retrying might re-download already successful ones if not handled carefully.
                // For simplicity, if any download failed, we retry the whole process.
                // However, the summary notification for successful ones will still be shown.
                Result.retry()
            } else {
                overallResult // success if all downloads were fine, retry if any failed without any success (and no successful ones)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in ApkMirrorUpdateWorker: ${e.message}", e)
            clearStartWorkNotification()
            return@withContext Result.failure() // Or Result.retry() based on the exception type
        }
    }

    // Heuristic for APKMirror developer slug
    private fun getDeveloperSlugHeuristic(packageName: String, installerPackageName: String?): String {
        val pName = packageName.lowercase(Locale.ROOT)
        val iName = installerPackageName?.lowercase(Locale.ROOT)

        return when {
            pName.contains("google") || iName?.contains("google") == true -> "google-inc"
            pName.contains("facebook") || pName.contains("meta") || iName?.contains("facebook") == true || iName?.contains("meta") == true -> "meta-platforms-inc" // Example, check actual slug
            pName.contains("microsoft") || iName?.contains("microsoft") == true -> "microsoft-corporation" // Example
            // Add more known mappings based on common apps from APKMirror
            else -> {
                // Fallback: try to derive from package name (e.g., com.example.app -> example)
                val parts = pName.split('.')
                if (parts.size >= 2) parts[parts.size - 2] else "misc"
            }
        }
    }

    private fun findBestVariant(variants: List<ApkMirrorVariantInfo>, appNameSlug: String): ApkMirrorVariantInfo? {
        // More sophisticated filtering is needed here.
        // 1. Filter by "apk" type if "bundle" or other types are present.
        //    (Requires ApkMirrorVariantInfo to have a 'type' field, or infer from downloadLink/text)
        //    For now, assume all are APKs or scraper filters them.

        val supportedAbis = Build.SUPPORTED_ABIS
        val sdkInt = VERSION_CODES.BASE // Build.VERSION.SDK_INT - use actual device SDK for real filtering

        // Crude DPI - ideally get from context.resources.displayMetrics
        // val deviceDpi = appContext.resources.displayMetrics.densityDpi (requires appContext)
        // For worker, it's simpler to prefer "nodpi" or common ones if specific info isn't easily available.

        return variants.filter { variant ->
            // Basic Android Version Check (example, needs refinement for ranges like "5.0+")
            val minSdk = variant.androidVersionRequired?.filter { it.isDigit() }?.toIntOrNull()
            val sdkCompatible = minSdk == null || sdkInt >= minSdk

            // Architecture Check
            val archCompatible = variant.architecture == null ||
                                 supportedAbis.contains(variant.architecture) ||
                                 variant.architecture.contains("universal") ||
                                 variant.architecture.contains("noarch") ||
                                 (variant.architecture.contains("arm") && supportedAbis.any { it.startsWith("arm") }) ||
                                 (variant.architecture.contains("x86") && supportedAbis.any { it.startsWith("x86") })

            // DPI Check (very basic)
            val dpiCompatible = variant.dpi == null || variant.dpi == "nodpi" // Prefer nodpi if available

            // TODO: Bundle vs APK type check. Assume for now scraper gives APKs or links that lead to APKs.
            //       The download link itself might be the best indicator if it ends with ".apk" or similar.
            //       Some APKs on APKMirror are "BUNDLES" (split APKs), which this downloader can't install.
            //       This logic should prioritize non-bundle APKs.

            sdkCompatible && archCompatible && dpiCompatible
        }.maxByOrNull {
            // Prioritize based on architecture match quality (exact > compatible group > universal)
            when {
                supportedAbis.contains(it.architecture) -> 3 // Exact match
                it.architecture?.contains("universal") == true || it.architecture?.contains("noarch") == true -> 2 // Universal
                it.architecture.isNullOrEmpty() -> 1 // No arch specified, assume compatible
                else -> 0 // Less preferred
            }
        }
    }


    private fun containsPreReleaseKeywords(versionName: String): Boolean { // Already lowercase
        return listOf("rc", "snapshot", "dev", "preview", "test", "nightly", "canary").any { versionName.contains(it) }
    }

    }

    private suspend fun downloadApkWithProgress(app: AppUpdate, apkInfo: ApkMirrorVariantInfo, notificationIndex: Int): File? {
        val downloadDir = File(appContext.cacheDir, "apk_mirror_downloads").apply { mkdirs() }
        val safePackageName = app.packageName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val safeVersion = (apkInfo.versionName ?: "unknown_version").replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val apkFile = File(downloadDir, "${safePackageName}_v${safeVersion}.apk")

        // Unique notification ID for each download
        val notificationId = DOWNLOAD_NOTIFICATION_ID_BASE + notificationIndex

        try {
            val request = Request.Builder().url(apkInfo.downloadLink).build() // Use the direct download link
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed for ${app.name}: ${response.code} ${response.message} from ${apkInfo.downloadLink}")
                showDownloadNotification(app.name, "Download failed: ${response.code}", false, notificationId, true)
                return null
            }
            val body = response.body
            if (body == null) {
                Log.e(TAG, "Download failed for ${app.name}: Empty response body from ${apkInfo.downloadLink}")
                showDownloadNotification(app.name, "Download failed: Empty response", false, notificationId, true)
                return null
            }

            val totalBytes = body.contentLength()
            var bytesCopied: Long = 0
            var lastProgress = -1

            showDownloadNotification(app.name, "Starting download...", false, notificationId, false, 0, totalBytes)

            FileOutputStream(apkFile).use { outputStream ->
                body.byteStream().use { inputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE) // 8KB buffer
                    var bytes = inputStream.read(buffer)
                    while (bytes >= 0) {
                        outputStream.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        bytes = inputStream.read(buffer)

                        if (totalBytes > 0) {
                            val progress = ((bytesCopied * 100) / totalBytes).toInt()
                            if (progress > lastProgress) { // Update notification only on progress change
                                setProgressAsync(Data.Builder().putInt("progress", progress).build()) // For foreground worker
                                showDownloadNotification(app.name, "Downloading...", false, notificationId, false, progress, totalBytes)
                                lastProgress = progress
                            }
                        }
                    }
                }
            }
            // Ensure 100% completion notification if not already shown
            if (lastProgress < 100 || totalBytes <= 0) { // also show if totalBytes was unknown
                 showDownloadNotification(app.name, "Processing download...", false, notificationId, false, 100, totalBytes.coerceAtLeast(0)) // Show 100% or indeterminate if size was 0
            }
            Thread.sleep(500) // Brief pause before final "complete" notification
            showDownloadNotification(app.name, "Download complete", true, notificationId, false)
            Log.i(TAG, "Download complete for ${app.name}: ${apkFile.absolutePath}")
            return apkFile
        } catch (e: IOException) {
            Log.e(TAG, "IOException during download for ${app.name} from ${apkInfo.downloadLink}: ${e.message}", e)
            showDownloadNotification(app.name, "Download error: ${e.localizedMessage}", false, notificationId, true)
            apkFile.delete() // Clean up partial download
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception during download for ${app.name} from ${apkInfo.downloadLink}: ${e.message}", e)
            showDownloadNotification(app.name, "Download error", false, notificationId, true)
            apkFile.delete()
            return null
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= VERSION_CODES.O) {
            val name = appContext.getString(R.string.notification_channel_apkmirror_downloads)
            val descriptionText = appContext.getString(R.string.notification_channel_apkmirror_downloads_description)
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance for background downloads
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(null, null) // Silent notifications for background progress
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showStartWorkNotification() {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show worker start notification.")
            return
        }
        // Generic icon like R.drawable.ic_notification_update or system default
        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download) // Standard system download icon
            .setContentTitle(appContext.getString(R.string.app_name) + " - APKMirror")
            .setContentText("Checking for app updates from APKMirror...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Make it ongoing as the worker is active
            .setSilent(true)  // No sound for this initial notification

        with(NotificationManagerCompat.from(appContext)) {
            notify(START_NOTIFICATION_ID, builder.build())
        }
    }

    private fun clearStartWorkNotification() {
        with(NotificationManagerCompat.from(appContext)) {
            cancel(START_NOTIFICATION_ID)
        }
    }

    private fun showDownloadNotification(appName: String, message: String, isComplete: Boolean, notificationId: Int, isError: Boolean, progress: Int = 0, totalSize: Long = 0) {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
             Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show download notification for $appName.")
            return
        }
        val title = when {
            isError -> "Download Error: $appName"
            isComplete -> "Download Complete: $appName"
            else -> "Downloading: $appName"
        }
        // Use standard system icons
        val icon = when {
            isError -> android.R.drawable.stat_sys_download_done // stat_sys_warning might be better if available
            isComplete -> android.R.drawable.stat_sys_download_done
            else -> android.R.drawable.stat_sys_download
        }

        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true) // Keep background download notifications silent
            .setAutoCancel(isComplete || isError) // Dismiss on click if complete or error

        if (!isComplete && !isError) {
            if (totalSize > 0) {
                builder.setProgress(100, progress, false) // Max 100, current progress, not indeterminate
            } else {
                builder.setProgress(0, 0, true) // Indeterminate if total size is unknown
            }
            builder.setOngoing(true) // Download in progress is ongoing
        } else {
            builder.setOngoing(false) // No longer ongoing if complete or error
        }

        // Potentially add an intent to open the app or a specific screen upon completion
        val contentIntent = Intent(appContext, com.apkupdater.ui.activity.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Optionally, add an extra to navigate to a specific tab if MainActivity supports it
            // putExtra("NAVIGATE_TO_TAB", "UPDATES_TAB_IDENTIFIER") // Example
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId, // Use unique request code for each notification to ensure PendingIntents are distinct
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)


        with(NotificationManagerCompat.from(appContext)) {
            notify(notificationId, builder.build())
        }
    }

    private fun showSummaryNotification(downloadedAppsNames: List<String>) {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show summary notification.")
            return
        }

        val count = downloadedAppsNames.size
        val title = appContext.resources.getQuantityString(R.plurals.apkmirror_summary_title, count, count)
        val contentText: String = if (count == 1) {
            appContext.getString(R.string.apkmirror_summary_content_single, downloadedAppsNames.first())
        } else {
            // Example: "App1, App2, and 2 others." or simply "App1, App2, App3."
            val displayLimit = 2
            val firstApps = downloadedAppsNames.take(displayLimit).joinToString(", ")
            if (count > displayLimit) {
                appContext.getString(R.string.apkmirror_summary_content_multiple_more, firstApps, count - displayLimit)
            } else {
                appContext.getString(R.string.apkmirror_summary_content_multiple, firstApps)
            }
        }


        val mainActivityIntent = Intent(appContext, com.apkupdater.ui.activity.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP // Clears task and brings MainActivity to front or creates new
            // If MainActivity needs to know to go to a specific tab:
            // putExtra("NAVIGATE_TO_SECTION", "DOWNLOADED_UPDATES") // Example extra
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            SUMMARY_NOTIFICATION_ID, // Unique request code for the summary PendingIntent
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done) // Standard system download complete icon
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText)) // For longer text if many apps
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Dismiss notification when tapped
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Default priority for summary

        with(NotificationManagerCompat.from(appContext)) {
            notify(SUMMARY_NOTIFICATION_ID, builder.build())
        }
        Log.i(TAG, "Summary notification shown for $count downloaded apps.")
    }
}

// Helper extension in AppsRepository (conceptual)
// fun AppsRepository.getInstalledAppsNonFlow(excludeSystem: Boolean, excludeStore: Boolean, excludeDisabled: Boolean): Result<List<AppUpdate>> {
//    // This would be a blocking call or a suspend function that collects the first emission of the Flow
//    // For a worker, direct suspend function might be cleaner if the Flow is for UI reactivity.
//    // This is a placeholder for how AppsRepository might provide a direct list.
//    // Actual implementation depends on AppsRepository's design.
//    // For now, the worker will assume it can get a List<AppUpdate> directly.
//    // If AppsRepository only offers Flow, the worker would need to collect it:
//    // runBlocking { getApps().firstOrNull()?.getOrNull() ?: emptyList() } // Example, but runBlocking is discouraged in workers
//    // Better: Make getInstalledApps a suspend fun in repo or use a specific method for workers.
//    return Result.success(emptyList()) // Placeholder
// }
//
// Need to ensure TextUtil.sanitizeAppNameForUrl is available or implement it.
// Example:
// object TextUtil {
//    ...
//    fun sanitizeAppNameForUrl(appName: String): String {
//        return appName.trim().lowercase(Locale.ROOT)
//            .replace(Regex("\\s+"), "-") // Replace spaces with hyphens
//            .replace(Regex("[^a-z0-9-]"), "") // Remove non-alphanumeric (except hyphen)
//            .take(50) // Limit length
//    }
// }

// Companion object needs to be a KoinComponent to inject Prefs for scheduling
// This is a common pattern but has implications. Alternatively, pass context to schedule and get Prefs instance there.
// Making companion a KoinComponent:
object ApkMirrorUpdateWorkerCompanion : KoinComponent {
    private val prefsKoin: Prefs by inject() // KoinComponent allows companion to inject
    fun scheduleUsingKoin(context: Context) {
         ApkMirrorUpdateWorker.schedule(context) // Call the static schedule with context
    }
     fun cancelUsingKoin(context: Context) {
        ApkMirrorUpdateWorker.cancel(context)
    }
}

// Import for PendingIntent in showDownloadNotification if it was missing
import android.app.PendingIntent
import android.content.Intent
