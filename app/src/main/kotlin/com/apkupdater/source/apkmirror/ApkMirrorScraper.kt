package com.apkupdater.source.apkmirror

import android.util.Log
import com.apkupdater.utils.TextUtil // Assuming a utility for text cleaning exists or will be created
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

object ApkMirrorScraper {

    private const val TAG = "ApkMirrorScraper"
    const val APKMIRROR_BASE_URL = "https://www.apkmirror.com"

    // --- Selectors for App Overview Page (e.g., /apk/google-inc/chrome/) ---
    // Refined based on (simulated) HTML inspection.

    // Main container for version listings
    private const val APP_PAGE_VERSIONS_LIST_SELECTOR = "div.listWidget"
    // Individual app version item (row) within the list
    private const val APP_PAGE_VERSION_ITEM_SELECTOR = "div.appRow" // Each <div class="appRow"> under listWidget
    // Version name/title within an item
    private const val APP_PAGE_VERSION_NAME_SELECTOR = "h5.appRowTitle" // e.g., <h5><a href=...>Chrome 125.0.6422.112</a></h5>
    // Link to the page that lists variants for this specific version string
    private const val APP_PAGE_VERSION_VARIANTS_LINK_SELECTOR = "a.accent_bg.dlButton" // The "View Variants" or similar button/link
    // Release date string
    private const val APP_PAGE_RELEASE_DATE_SELECTOR = "span.dateyear_utc" // e.g., <span class="dateyear_utc" data-utcdate="2023-10-24">Oct 24, 2023</span>
    // Notes, like number of variants or if it's a bundle
    private const val APP_PAGE_NOTES_SELECTOR = "span.infoSlide_extraDownloadsCount" // e.g., "3 variants" or "APK Bundle"

    // --- Selectors for Specific Version Page (listing multiple APKs for one version string) ---
    // This is the page linked by APP_PAGE_VERSION_VARIANTS_LINK_SELECTOR
    // It might list several APKs (arm, x86, different Android versions for the *same* Chrome version)

    private const val VERSION_VARIANTS_LIST_ITEM_SELECTOR = "div.table-row" // Each row representing an APK variant
    private const val VERSION_VARIANT_DOWNLOAD_PAGE_LINK_SELECTOR = "a[href*=-android-apk-download/]" // Link to the final download page for THIS specific APK
    private const val VERSION_VARIANT_ARCH_SELECTOR = "div.table-cell:nth-of-type(1) span.apkm-badge" // e.g. arm64-v8a
    private const val VERSION_VARIANT_ANDROID_VER_SELECTOR = "div.table-cell:nth-of-type(2)" // e.g. Android 10+
    private const val VERSION_VARIANT_DPI_SELECTOR = "div.table-cell:nth-of-type(3)" // e.g. nodpi

    // --- Selectors for Final Variant Download Page (the page just before actual .apk download) ---
    // This is the page linked by VERSION_VARIANT_DOWNLOAD_PAGE_LINK_SELECTOR

    // The actual download button/link for the .apk file. The href here is often relative to a download manager.
    private const val FINAL_DOWNLOAD_PAGE_APK_LINK_SELECTOR = "a.downloadButton[data-google-vignette=false]"
    // Sometimes the above is an intermediate link. The *actual* direct link might be on a subsequent page,
    // or dynamically generated. We'll assume for now this selector gets us a usable link or a link to a simple redirector.
    // For this task, we'll treat the href from this selector as the "downloadLink".

    // Metadata on this final download page
    private const val FINAL_DOWNLOAD_PAGE_VERSION_NAME_SELECTOR = "h1.appbreadcrumb > a:last-of-type" // "Google Chrome 125.0.6422.112"
    private const val FINAL_DOWNLOAD_PAGE_FILE_SIZE_SELECTOR = "span.notes:contains(Size:)" // e.g. "Size: 123 MB"


    /**
     * Parses HTML of an APKMirror app overview page (e.g., for "Google Chrome")
     * to extract a list of available app versions and links to their respective variant pages.
     *
     * @param html The HTML content of the app overview page.
     * @param appPageUrl The URL of the page being parsed, for resolving relative links.
     * @return A list of [ApkMirrorAppVersionInfo] objects.
     */
    fun parseAppOverviewPage(html: String, appPageUrl: String): List<ApkMirrorAppVersionInfo> {
        val appVersions = mutableListOf<ApkMirrorAppVersionInfo>()
        if (html.isEmpty()) {
            Log.w(TAG, "Input HTML for parseAppOverviewPage is empty.")
            return appVersions
        }

        try {
            val document: Document = Jsoup.parse(html, appPageUrl) // Provide base URI for resolving relative URLs
            val versionListContainer = document.selectFirst(APP_PAGE_VERSIONS_LIST_SELECTOR)

            if (versionListContainer == null) {
                Log.w(TAG, "Versions list container not found using selector: $APP_PAGE_VERSIONS_LIST_SELECTOR on $appPageUrl")
                return appVersions
            }

            val versionItems: Elements = versionListContainer.select(APP_PAGE_VERSION_ITEM_SELECTOR)
            if (versionItems.isEmpty()) {
                 Log.i(TAG, "No version items found using selector: $APP_PAGE_VERSION_ITEM_SELECTOR on $appPageUrl")
            }

            for (item in versionItems) {
                val nameElement = item.selectFirst(APP_PAGE_VERSION_NAME_SELECTOR)
                val linkElement = item.selectFirst(APP_PAGE_VERSION_VARIANTS_LINK_SELECTOR)
                val dateElement = item.selectFirst(APP_PAGE_RELEASE_DATE_SELECTOR)
                val notesElement = item.selectFirst(APP_PAGE_NOTES_SELECTOR) // Could be "X variants" or "APK Bundle"

                var versionName = nameElement?.text()?.trim()
                var variantsPageUrl = linkElement?.attr("abs:href") // abs:href resolves relative URLs

                // Basic filtering for sanity
                if (versionName != null && variantsPageUrl != null) {
                    // Exclude known non-release types if the name contains them
                    if (versionName.contains("alpha", ignoreCase = true) ||
                        versionName.contains("beta", ignoreCase = true) ||
                        versionName.contains("dev", ignoreCase = true) ||
                        versionName.contains("canary", ignoreCase = true)) {
                        // Log.d(TAG, "Skipping pre-release version: $versionName from $appPageUrl")
                        continue
                    }
                    // Clean up version name (e.g., remove " stable" or " official")
                    versionName = TextUtil.sanitizeVersionName(versionName)


                    val releaseDate = dateElement?.attr("data-utcdate") ?: dateElement?.text()?.trim()
                    val notes = notesElement?.text()?.trim()

                    appVersions.add(
                        ApkMirrorAppVersionInfo(
                            versionName = versionName,
                            versionPageUrl = variantsPageUrl,
                            releaseDate = releaseDate,
                            notes = notes
                        )
                    )
                } else {
                    // Log.d(TAG, "Skipping item on $appPageUrl due to missing name or URL. Name: '$versionName', URL: '$variantsPageUrl'")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing app overview page HTML ($appPageUrl): ${e.message}", e)
        }
        return appVersions
    }

    /**
     * Parses HTML of an APKMirror page that lists multiple APK variants for a single version string.
     * Example: "Google Chrome 125.0.6422.112" page, listing arm64, x86, etc. for Android 10+
     *
     * @param html The HTML content of the version's variants page.
     * @param versionPageUrl The URL of the page being parsed.
     * @return A list of [ApkMirrorVariantInfo] objects, each representing a downloadable APK variant.
     */
    fun parseVersionVariantsPage(html: String, versionPageUrl: String): List<ApkMirrorVariantInfo> {
        val variants = mutableListOf<ApkMirrorVariantInfo>()
        if (html.isEmpty()) {
            Log.w(TAG, "Input HTML for parseVersionVariantsPage is empty.")
            return variants
        }

        try {
            val document: Document = Jsoup.parse(html, versionPageUrl)
            // Look for a table or list of variants. The selector needs to be robust.
            // This assumes rows, each being a distinct APK.
            val variantRows: Elements = document.select("$APP_PAGE_VERSIONS_LIST_SELECTOR $VERSION_VARIANTS_LIST_ITEM_SELECTOR")
             if (variantRows.isEmpty()) {
                Log.w(TAG, "No variant rows found on $versionPageUrl using selector: $APP_PAGE_VERSIONS_LIST_SELECTOR $VERSION_VARIANTS_LIST_ITEM_SELECTOR")
                // Potentially, this page itself is the final download page if there's only one variant
                // For now, we assume it's a list. A more advanced version might try to parse it as a final page.
                return variants
            }

            for (row in variantRows) {
                val archElement = row.selectFirst(VERSION_VARIANT_ARCH_SELECTOR)
                val androidVerElement = row.selectFirst(VERSION_VARIANT_ANDROID_VER_SELECTOR)
                val dpiElement = row.selectFirst(VERSION_VARIANT_DPI_SELECTOR)
                // The link to the *final* download page for this specific APK variant
                val finalDownloadPageLinkElement = row.selectFirst(VERSION_VARIANT_DOWNLOAD_PAGE_LINK_SELECTOR)

                val finalDownloadPageUrl = finalDownloadPageLinkElement?.attr("abs:href")

                if (finalDownloadPageUrl == null) {
                    // Log.d(TAG, "Skipping variant row on $versionPageUrl, no final download page link found.")
                    continue
                }

                // We don't have the direct .apk link yet, that's on the finalDownloadPageUrl
                // We are collecting info about the variant and the link to its own download page.
                variants.add(
                    ApkMirrorVariantInfo(
                        sourcePageUrl = finalDownloadPageUrl, // This is the URL to fetch next
                        downloadLink = "", // To be filled by parsing finalDownloadPageUrl
                        architecture = archElement?.text()?.trim(),
                        androidVersionRequired = androidVerElement?.text()?.trim()?.replace("Android ", ""),
                        dpi = dpiElement?.text()?.trim()
                        // Version name might be inherited or found on the final page
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing version variants page HTML ($versionPageUrl): ${e.message}", e)
        }
        return variants
    }


    /**
     * Parses the HTML of a final APKMirror variant download page to extract the direct download link and metadata.
     *
     * @param html The HTML content of the final download page.
     * @param finalDownloadPageUrl The URL of the page being parsed.
     * @param existingVariantInfo Optional, if we have some info from the previous (variants list) page.
     * @return An [ApkMirrorVariantInfo] object with the download link, or null if not found.
     */
    fun parseFinalDownloadPage(html: String, finalDownloadPageUrl: String, existingVariantInfo: ApkMirrorVariantInfo? = null): ApkMirrorVariantInfo? {
        if (html.isEmpty()) {
            Log.w(TAG, "Input HTML for parseFinalDownloadPage is empty.")
            return null
        }

        try {
            val document: Document = Jsoup.parse(html, finalDownloadPageUrl)
            val downloadLinkElement: Element? = document.selectFirst(FINAL_DOWNLOAD_PAGE_APK_LINK_SELECTOR)
            var apkDownloadUrl = downloadLinkElement?.attr("abs:href") // Use abs:href to resolve relative path

            if (apkDownloadUrl == null || apkDownloadUrl.contains("javascript:void(0)")) { // Check for placeholder links
                 Log.i(TAG, "Primary download button selector '$FINAL_DOWNLOAD_PAGE_APK_LINK_SELECTOR' didn't yield a direct link or found JS void. URL: $finalDownloadPageUrl")
                 // Fallback: Look for any link that seems to point to the download handler and might be the one.
                 // This can be page-specific. Often there's a specific path like "/wp-content/plugins/android-apks-com/includes/download.php"
                 val fallbackElement = document.select("a[href*=/download/wp-content/], a[href*=/includes/download.php], a[href*=/download.php?id=]")
                                           .firstOrNull { it.hasAttr("href") }
                 apkDownloadUrl = fallbackElement?.attr("abs:href")
            }

            if (apkDownloadUrl == null) {
                Log.e(TAG, "Could not find the actual APK download link on page: $finalDownloadPageUrl")
                return null
            }
            
            // If the found URL is not absolute (e.g. starts with /download/...), make it absolute
            if (apkDownloadUrl.startsWith("/")) {
                apkDownloadUrl = APKMIRROR_BASE_URL + apkDownloadUrl
            }


            val versionNameOnPage = document.selectFirst(FINAL_DOWNLOAD_PAGE_VERSION_NAME_SELECTOR)?.text()?.trim() ?: existingVariantInfo?.versionName
            val fileSize = document.selectFirst(FINAL_DOWNLOAD_PAGE_FILE_SIZE_SELECTOR)?.text()?.replace("Size:", "")?.trim() ?: existingVariantInfo?.fileSize
            
            // Architecture, Android Version, DPI might be repeated on this page or we use existing ones.
            // For simplicity, we'll assume existingVariantInfo carries them if they were found on the variants list page.
            // More robust parsing could re-scrape them here as confirmation or primary source.

            return (existingVariantInfo ?: ApkMirrorVariantInfo(sourcePageUrl = finalDownloadPageUrl, downloadLink = "")).copy(
                downloadLink = apkDownloadUrl,
                versionName = TextUtil.sanitizeVersionName(versionNameOnPage ?: ""),
                fileSize = fileSize
                // architecture, androidVersionRequired, dpi could be re-parsed here for robustness
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing final download page HTML ($finalDownloadPageUrl): ${e.message}", e)
            return null
        }
    }
}

// Dummy TextUtil for now, should be in its own file: app/src/main/kotlin/com/apkupdater/utils/TextUtil.kt
// package com.apkupdater.utils
// object TextUtil {
//    fun sanitizeVersionName(name: String?): String {
//        if (name == null) return ""
//        return name.replace(" Stable", "", ignoreCase = true)
//                   .replace(" Official", "", ignoreCase = true)
//                   .replace(" Final", "", ignoreCase = true)
//                   .trim()
//    }
// }
