package com.apkupdater.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object HtmlFetcher {

    private val client = OkHttpClient()

    // A common User-Agent string for a mobile browser.
    // This might need to be adjusted if APKMirror blocks it or if the app has a specific User-Agent.
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36"

    /**
     * Fetches HTML content from the given URL.
     *
     * @param url The URL to fetch HTML from.
     * @return The HTML content as a String, or null if an error occurred.
     */
    fun fetchHtml(url: String): String? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("HtmlFetcher", "Failed to fetch HTML: ${response.code} ${response.message}")
                    return null
                }
                return response.body?.string()
            }
        } catch (e: IOException) {
            Log.e("HtmlFetcher", "IOException during HTML fetch: ${e.message}", e)
            return null
        } catch (e: Exception) {
            Log.e("HtmlFetcher", "Unexpected error during HTML fetch: ${e.message}", e)
            return null
        }
    }

    /*
    // Example Usage (for logging/debugging):
    // This would typically be called from a ViewModel or a background thread.
    fun exampleFetch(someUrl: String = "https://www.apkmirror.com/apk/google-inc/chrome/") {
        Thread { // Network operations must not be on the main thread
            val htmlContent = fetchHtml(someUrl)
            if (htmlContent != null) {
                // For long HTML, Log.d might truncate. Consider writing to a file for full inspection.
                Log.d("HtmlFetcherExample", "Fetched HTML (first 500 chars): ${htmlContent.take(500)}")
            } else {
                Log.e("HtmlFetcherExample", "Failed to fetch HTML from $someUrl")
            }
        }.start()
    }
    */
}
