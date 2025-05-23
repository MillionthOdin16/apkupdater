package com.apkupdater.viewmodel

import androidx.lifecycle.viewModelScope
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.data.ui.removeId
import com.apkupdater.data.ui.setIsInstalling
import com.apkupdater.data.ui.setProgress
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.UpdatesRepository
import com.apkupdater.util.Badger
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.launchWithMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import com.apkupdater.data.ui.PendingApk
import com.apkupdater.repository.DownloadedApkRepository
import kotlinx.coroutines.flow.asStateFlow


class UpdatesViewModel(
	private val updatesRepository: UpdatesRepository,
	private val downloadedApkRepository: DownloadedApkRepository, // Added
	private val installer: SessionInstaller,
	private val prefs: Prefs,
	private val badger: Badger,
	downloader: Downloader,
	snackBar: SnackBar,
	stringer: Stringer,
	installLog: InstallLog
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog) {

	private val mutex = Mutex()
	private val _uiState = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading)
	val uiState: StateFlow<UpdatesUiState> = _uiState.asStateFlow()

	private val _pendingApksState = MutableStateFlow<List<PendingApk>>(emptyList())
	val pendingApksState: StateFlow<List<PendingApk>> = _pendingApksState.asStateFlow()


	init {
		subscribeToInstallStatus(_uiState.value.updates())
		subscribeToInstallProgress { progress ->
			_uiState.value = UpdatesUiState.Success(_uiState.value.mutableUpdates().setProgress(progress))
		}
		loadPendingApks() // Load initially
	}

	// Renamed original state() to uiState for clarity
	// fun state(): StateFlow<UpdatesUiState> = _uiState // Original, now exposed as uiState

	fun refresh(load: Boolean = true) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		if (load) _uiState.value = UpdatesUiState.Loading
		badger.changeUpdatesBadge("")
		updatesRepository.updates().collect {
			setSuccess(it)
		}
		loadPendingApks() // Also refresh pending APKs list
	}

	fun loadPendingApks() = viewModelScope.launch(Dispatchers.IO) {
		_pendingApksState.value = downloadedApkRepository.getPendingApks()
	}

	fun deletePendingApk(filePath: String) = viewModelScope.launch(Dispatchers.IO) {
		downloadedApkRepository.deletePendingApk(filePath)
		loadPendingApks() // Refresh list
	}
	
	fun deleteAllPendingApks() = viewModelScope.launch(Dispatchers.IO) {
		downloadedApkRepository.deleteAllPendingApks()
		loadPendingApks()
	}


	fun ignoreVersion(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val ignored = prefs.ignoredVersions.get().toMutableList()
		if (ignored.contains(id)) ignored.remove(id) else ignored.add(id)
		prefs.ignoredVersions.put(ignored)
		setSuccess(_uiState.value.mutableUpdates())
	}

	override fun cancelInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		_uiState.value = UpdatesUiState.Success(_uiState.value.mutableUpdates().setIsInstalling(id, false))
		installer.finish()
	}

	override fun finishInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		setSuccess(_uiState.value.mutableUpdates().removeId(id))
		installer.finish()
	}

	override fun downloadAndRootInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		_uiState.value = UpdatesUiState.Success(_uiState.value.mutableUpdates().setIsInstalling(update.id, true))
		downloadAndRootInstall(update.id, update.link)
	}

	override fun downloadAndInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
		if(installer.checkPermission()) {
			_uiState.value = UpdatesUiState.Success(_uiState.value.mutableUpdates().setIsInstalling(update.id, true))
			downloadAndInstall(update.id, update.packageName, update.link)
		}
	}

	private fun List<AppUpdate>.filterIgnoredVersions(ignoredVersions: List<Int>) = this
		.filter { !ignoredVersions.contains(it.id) }

	private fun setSuccess(updates: List<AppUpdate>) = updates
		.filterIgnoredVersions(prefs.ignoredVersions.get())
		.let {
			_uiState.value = UpdatesUiState.Success(it)
			badger.changeUpdatesBadge(it.size.toString())
		}

}
