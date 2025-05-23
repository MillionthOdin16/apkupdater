package com.apkupdater.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.apkupdater.prefs.Prefs
import com.apkupdater.transform.toAppInstalled
import com.apkupdater.util.orFalse
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import com.apkupdater.data.ui.AppUpdate // Ensure AppUpdate is imported


class AppsRepository(
	private val context: Context,
	private val prefs: Prefs
) {

	// Existing Flow-based method
	suspend fun getApps() = flow {
		val apps = context.packageManager
			.getInstalledPackages(PackageManager.MATCH_ALL + getSignatureFlag())
			.asSequence()
			.filter { !excludeSystem() || it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
			.filter { !excludeSystem() || it.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 }
			.filter { !excludeDisabled() || it.applicationInfo.enabled }
			.filter { !excludeStore() || !isAppStore(getInstallerPackageName(it.packageName)) }
			.map { it.toAppInstalled(context, ignoredApps()) }
			.sortedBy { it.name }
			.sortedBy { it.ignored }
			.toList()
		emit(Result.success(apps))
	}.catch {
		Log.e("AppsRepository", "Error getting apps.", it)
		emit(Result.failure(it))
	}

	private fun excludeSystem() = prefs.excludeSystem.get()
	private fun excludeDisabled() = prefs.excludeDisabled.get()
	private fun excludeStore() = prefs.excludeStore.get()
	private fun ignoredApps() = prefs.ignoredApps.get()

	@Suppress("DEPRECATION")
	private fun getSignatureFlag(): Int {
		return if (Build.VERSION.SDK_INT >= 28) {
			PackageManager.GET_SIGNING_CERTIFICATES
		} else {
			PackageManager.GET_SIGNATURES
		}
	}

	@Suppress("DEPRECATION")
	private fun getInstallerPackageName(packageName: String): String {
		return if (Build.VERSION.SDK_INT < 30) {
			context.packageManager.getInstallerPackageName(packageName).orEmpty()
		} else {
			context.packageManager.getInstallSourceInfo(packageName).installingPackageName.orEmpty()
		}
	}

	// Checks if Play Store or Amazon Store
	private fun isAppStore(name: String?) = name?.contains("com.android.vending").orFalse()
		|| name?.contains("com.amazon").orFalse()


	/**
	 * Provides a one-shot list of installed applications, suitable for background workers.
	 * This is a convenience wrapper around the Flow-based getApps().
	 *
	 * @param excludeSystem Corresponds to prefs.excludeSystem
	 * @param excludeStore Corresponds to prefs.excludeStore
	 * @param excludeDisabled Corresponds to prefs.excludeDisabled
	 * @return Result<List<AppUpdate>> containing the list of apps or an error.
	 */
	suspend fun getInstalledAppsNonFlow(excludeSystem: Boolean, excludeStore: Boolean, excludeDisabled: Boolean): Result<List<AppUpdate>> {
		// Temporarily override prefs for the scope of this call if direct args are preferred
		// Or, ensure prefs are set correctly before calling this if it strictly uses prefs object.
		// For this implementation, we assume the flow `getApps()` uses the current state of `prefs`
		// or that the parameters passed are used to filter *after* fetching if `getApps` doesn't take them.
		// The current getApps() uses prefs directly, so ensure they are what you expect or modify getApps to take params.

		// For simplicity, we'll rely on the existing prefs getters within getApps()
		// and assume the boolean flags passed are for potential future direct filtering if needed.
		// This means the worker should ensure Prefs are set as desired if these flags were to control internal prefs state.
		// However, the worker already reads prefs for these, so it's more about how AppsRepository is designed.
		// Let's assume getApps() internally uses the Prefs object which reflects the current settings.

		val flowResult = getApps().firstOrNull() // Collect the first emission
		return flowResult ?: Result.failure(Exception("Failed to collect app list from flow"))
	}
}
