package com.apkupdater.utils

import java.util.regex.Pattern

object TextUtil {

    // A more comprehensive pattern to extract version numbers.
    // Handles cases like:
    // "1.2.3", "1.2.3-beta1", "1.2.3.4", "1.2", "125.0.6422.112",
    // "Version 1.2.3", "Chrome 125.0.6422.112", etc.
    // It tries to find a sequence of numbers separated by dots, possibly with a suffix.
    private val VERSION_PATTERN = Pattern.compile("""(\d+(\.\d+)+([.-][\w.-]+)?)""")

    /**
     * Sanitizes a version name string to extract a cleaner, more standard version number.
     * It attempts to remove common prefixes/suffixes like "Version ", " Stable", " Official", etc.
     * and then tries to extract a pattern like X.Y.Z.
     *
     * @param name The raw version name string.
     * @return A cleaner version string, or the original if no specific pattern is matched.
     *         Returns an empty string if the input is null or blank.
     */
    fun sanitizeVersionName(name: String?): String {
        if (name.isNullOrBlank()) {
            return ""
        }

        // Remove common textual prefixes/suffixes
        var sanitized = name.replace("Stable", "", ignoreCase = true)
            .replace("Official", "", ignoreCase = true)
            .replace("Final", "", ignoreCase = true)
            .replace("Release", "", ignoreCase = true)
            .replace("Version", "", ignoreCase = true)
            .replace("Google Chrome", "", ignoreCase = true) // Specific to Chrome example
            .replace("Chrome", "", ignoreCase = true)       // Specific to Chrome example
            .trim()

        // Attempt to extract version using regex
        val matcher = VERSION_PATTERN.matcher(sanitized)
        if (matcher.find()) {
            val extractedVersion = matcher.group(1)
            if (extractedVersion != null && extractedVersion.isNotEmpty()) {
                // Further clean up if the regex matched more than just the version number
                // (e.g. if the original was "App v1.2.3 download")
                // This simplistic approach assumes the version is usually at the start or is the dominant part.
                val parts = sanitized.split(Regex("\\s+"))
                for (part in parts) {
                    if (part.matches(Regex("""\d+(\.\d+)+([.-][\w.-]+)?"""))) {
                        return part
                    }
                }
                return extractedVersion // return the matched group if specific part search fails
            }
        }
        
        // If regex fails, return the text-sanitized version
        // This might be an actual name like "Nougat" or "Oreo" if it's not a dot-version
        return sanitized.ifEmpty { name.trim() } // return original if sanitization made it empty
    }

    /**
     * Cleans text by removing excessive whitespace and trimming.
     *
     * @param text The text to clean.
     * @return Cleaned text, or an empty string if input is null.
     */
    fun cleanText(text: String?): String {
        return text?.replace("\\s+".toRegex(), " ")?.trim() ?: ""
    }

    /**
     * Sanitizes an application name for use in a URL slug.
     * e.g., "Google Chrome" -> "google-chrome"
     *
     * @param appName The raw application name.
     * @return A sanitized string suitable for URL slugs.
     */
    fun sanitizeAppNameForUrl(appName: String?): String {
        if (appName.isNullOrBlank()) {
            return "unknown-app"
        }
        return appName.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^\\w\\s-]"), "") // Remove non-alphanumeric, non-space, non-hyphen
            .replace(Regex("\\s+"), "-")    // Replace spaces with hyphens
            .replace(Regex("-+"), "-")      // Replace multiple hyphens with single
            .take(50) // Limit length for sanity
    }
}
