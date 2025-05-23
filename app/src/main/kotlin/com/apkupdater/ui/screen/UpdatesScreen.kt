package com.apkupdater.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InstallMobile
import com.apkupdater.data.ui.PendingApk
import com.apkupdater.util.launchInstallIntent
import androidx.tv.foundation.lazy.grid.items
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.EmptyGrid
import com.apkupdater.ui.component.InstalledGrid
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIcon
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvUpdateItem
import com.apkupdater.ui.component.UpdateItem
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.viewmodel.UpdatesViewModel
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import org.koin.androidx.compose.get


@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel) {
	// Collect both UI states
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val pendingApks by viewModel.pendingApksState.collectAsStateWithLifecycle()
	val context = LocalContext.current

	// Decide view based on uiState for regular updates
	uiState.onLoading {
		UpdatesScreenLoading(viewModel, pendingApks, context) // Pass pendingApks and context
	}.onError {
		UpdatesScreenError(viewModel, pendingApks, context) // Pass pendingApks and context
	}.onSuccess { successState ->
		UpdatesScreenSuccess(viewModel, successState.updates, pendingApks, context)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTopBar(viewModel: UpdatesViewModel, showClearAll: Boolean, onClearAll: () -> Unit) = TopAppBar(
	title = { Text(stringResource(R.string.tab_updates)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {
		if (showClearAll) {
			IconButton(onClick = onClearAll) {
				Icon(Icons.Filled.Delete, stringResource(R.string.clear_all_downloaded_apks))
			}
		}
		IconButton(onClick = { viewModel.refresh() }) { // Refreshes both regular updates and pending APKs
			RefreshIcon(stringResource(R.string.refresh_updates))
		}
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.ThumbUp, "Tab Icon")
		}
	}
)

@Composable
fun UpdatesScreenLoading(viewModel: UpdatesViewModel, pendingApks: List<PendingApk>, context: Context) = Column {
	UpdatesTopBar(viewModel, pendingApks.isNotEmpty()) { viewModel.deleteAllPendingApks() }
	PendingApkInstallSection(viewModel = viewModel, pendingApks = pendingApks, context = context)
	LoadingGrid()
}

@Composable
fun UpdatesScreenError(viewModel: UpdatesViewModel, pendingApks: List<PendingApk>, context: Context) = Column {
	UpdatesTopBar(viewModel, pendingApks.isNotEmpty()) { viewModel.deleteAllPendingApks() }
	PendingApkInstallSection(viewModel = viewModel, pendingApks = pendingApks, context = context)
	DefaultErrorScreen()
}

@Composable
fun UpdatesScreenSuccess(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	pendingApks: List<PendingApk>, // Added
	context: Context // Added
) = Column {
	val handler = LocalUriHandler.current
	val tv = get<Prefs>().androidTvUi.get()

	UpdatesTopBar(viewModel, pendingApks.isNotEmpty()) { viewModel.deleteAllPendingApks() }
	PendingApkInstallSection(viewModel = viewModel, pendingApks = pendingApks, context = context)

	// Regular updates list
	if (updates.isEmpty() && pendingApks.isEmpty()) { // Show empty grid only if both are empty
		EmptyGrid()
	} else if (updates.isNotEmpty()) { // Only show regular updates grid if there are updates
		if (tv) {
			TvGrid(viewModel, updates, handler)
		} else {
			Grid(viewModel, updates, handler)
		}
	} else if (pendingApks.isNotEmpty() && updates.isEmpty()) {
        // If only pending APKs are present and no regular updates,
        // you might want a specific message or rely on the PendingApkInstallSection only.
        // For now, this will result in no grid items for regular updates, which is fine.
         Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_regular_updates_found))
        }
    }
}

@Composable
fun PendingApkInstallSection(
	viewModel: UpdatesViewModel,
	pendingApks: List<PendingApk>,
	context: Context
) {
	if (pendingApks.isNotEmpty()) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
			Text(
				stringResource(R.string.downloaded_apks_title, pendingApks.size),
				style = MaterialTheme.typography.titleMedium,
				modifier = Modifier.padding(bottom = 8.dp)
			)
			Button(
				onClick = {
					pendingApks.forEach { apk ->
						context.launchInstallIntent(apk.filePath, onSuccess = {
                            // Deletion is now handled after each successful launch within the loop,
                            // or could be batched after all intents are launched.
                            // For simplicity, let's assume launchInstallIntent is synchronous enough for this,
                            // or the user handles one install at a time.
                            // A more robust solution might involve ActivityResultContracts for each.
                            viewModel.deletePendingApk(apk.filePath)
                        }, onFailure = {
                             // Optionally handle failure to launch intent, though less common
                            Log.e("PendingApkInstall", "Failed to launch install intent for ${apk.filePath}")
                        })
					}
                    // Refresh list after attempting all installs, some might have been quick.
                    // If installs are slow, user might need to refresh manually or we can listen to package changes.
                    // viewModel.loadPendingApks() // Re-enable if needed, or rely on user manual refresh.
				},
				modifier = Modifier.fillMaxWidth()
			) {
				Text(stringResource(R.string.install_all_downloaded_apks, pendingApks.size))
			}
			Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.tap_to_install_individually_swipe_to_delete),
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
			LazyColumn(modifier = Modifier.height(150.dp)) { // Max height for this section
				itemsIndexed(pendingApks, key = { _, apk -> apk.filePath }) { _, apk ->
					ListItem(
						headlineContent = { Text(apk.name) },
						supportingContent = { Text("Version: ${apk.versionName ?: "N/A"}\nPackage: ${apk.packageName ?: "N/A"}") },
						leadingContent = { Icon(Icons.Filled.InstallMobile, contentDescription = "Install APK") },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deletePendingApk(apk.filePath) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete APK")
                            }
                        },
						modifier = Modifier.clickable {
                            context.launchInstallIntent(apk.filePath, onSuccess = {
                                viewModel.deletePendingApk(apk.filePath)
                            }, onFailure = {
                                Log.e("PendingApkInstall", "Failed to launch install intent for ${apk.filePath}")
                            })
                        }
					)
                    HorizontalDivider()
				}
			}
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

		}
	}
}


@Composable
fun TvGrid(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	handler: UriHandler
) = TvInstalledGrid(contentPadding = PaddingValues(bottom = if (viewModel.pendingApksState.value.isNotEmpty()) 16.dp else 0.dp) ) { // Add padding if pending apks section is shown
	items(updates) { update ->
		TvUpdateItem(
			update,
			{ viewModel.install(update, handler) },
			{ viewModel.ignoreVersion(update.id)}
		)
	}
}

@Composable
fun Grid(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	handler: UriHandler
) = InstalledGrid(contentPadding = PaddingValues(bottom = if (viewModel.pendingApksState.value.isNotEmpty()) 16.dp else 0.dp) ) { // Add padding if pending apks section is shown
	items(updates) { update ->
		UpdateItem(update) {
			viewModel.install(update, handler)
		}
	}
}
