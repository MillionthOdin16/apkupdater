package com.apkupdater.source.apkmirror

/**
 * Holds information about a specific app version found on the APKMirror app overview page.
 *
 * @param versionName The version name, e.g., "125.0.6422.112".
 * @param versionPageUrl The URL to the page listing specific variants (APKs) for this version string.
 *                       This is typically a full URL.
 * @param releaseDate Optional release date string, if available.
 * @param notes Optional notes, like number of variants.
 */
data class ApkMirrorAppVersionInfo(
    val versionName: String,
    val versionPageUrl: String,
    val releaseDate: String? = null,
    val notes: String? = null
)

/**
 * Holds information extracted from a specific variant/download page.
 * This represents a single downloadable APK.
 *
 * @param sourcePageUrl The URL of the page where this variant information was extracted.
 * @param downloadLink The direct download link for the APK file (may need to be made absolute).
 * @param versionName The version name of this specific variant (e.g. "125.0.6422.112-2").
 * @param architecture The architecture (e.g., "arm64-v8a", "x86_64").
 * @param androidVersionRequired The minimum Android version required (e.g., "Android 5.0+").
 * @param dpi The screen DPI (e.g., "nodpi", "480dpi").
 * @param fileSize Optional file size as a string, if available.
 */
data class ApkMirrorVariantInfo(
    val sourcePageUrl: String,
    val downloadLink: String,
    val versionName: String? = null,
    val architecture: String? = null,
    val androidVersionRequired: String? = null,
    val dpi: String? = null,
    val fileSize: String? = null
)
