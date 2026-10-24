package com.example.vibratext.app.ui.history

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.ConfirmDialog
import com.example.vibratext.app.ui.components.Format
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.ui.components.SettingSwitch
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.HistoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : GraphViewModel(app) {
    val history: StateFlow<List<HistoryEntry>> = graph.store.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = graph.store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { graph.store.updateSettings { it.copy(historyEnabled = enabled) } }
    }

    fun setStoreContent(store: Boolean) {
        viewModelScope.launch {
            graph.store.updateSettings { it.copy(historyStoreContent = store) }
            if (!store) {
                // Turning content off also removes the content that was already stored.
                graph.store.clearHistory()
            }
        }
    }

    fun clear() {
        viewModelScope.launch { graph.store.clearHistory() }
    }

    fun delete(id: Long) {
        viewModelScope.launch { graph.store.deleteHistoryEntry(id) }
    }
}

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val entries by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmNoContent by remember { mutableStateOf(false) }

    ScreenScaffold(title = "History") { padding ->
        PageList(padding) {
            item {
                InfoCard(title = "Privacy") {
                    BodyText("History is stored only on this phone and is never uploaded. It is optional.")
                    SettingSwitch(
                        title = "Keep a history",
                        description = "When off, nothing is recorded about processed notifications.",
                        checked = settings.historyEnabled,
                        onCheckedChange = vm::setEnabled,
                    )
                    SettingSwitch(
                        title = "Store sender and message preview",
                        description = "When off, only the app, time, profile and status are kept. Turning it off deletes the content already stored.",
                        checked = settings.historyStoreContent,
                        onCheckedChange = { on -> if (on) vm.setStoreContent(true) else confirmNoContent = true },
                        enabled = settings.historyEnabled,
                    )
                    OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("Clear history") }
                }
            }
            if (entries.isEmpty()) {
                item {
                    BodyText(
                        if (settings.historyEnabled) "No notifications have been processed yet." else "History is switched off.",
                    )
                }
            }
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text(
                                listOfNotNull(e.applicationName.ifBlank { e.packageName }, e.sender).joinToString(" · "),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(Format.dateTime(e.timestamp), style = MaterialTheme.typography.labelSmall)
                        }
                        if (e.preview.isNotBlank()) BodyText(e.preview) else HintText("(no content stored)")
                        HintText(
                            buildList {
                                add(e.status.label)
                                e.profileName?.let { add("profile $it") }
                                if (e.truncated) add("message was shortened")
                                if (e.combinedCount > 1) add("${e.combinedCount} notifications combined")
                            }.joinToString(" · "),
                        )
                        TextButton(onClick = { vm.delete(e.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the history?",
            message = "All recorded notifications are deleted from this phone.",
            confirmLabel = "Clear",
            onConfirm = {
                vm.clear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmNoContent) {
        ConfirmDialog(
            title = "Stop storing content?",
            message = "The history will keep only app, time, profile and status. Existing history entries are deleted now.",
            confirmLabel = "Stop storing",
            onConfirm = {
                vm.setStoreContent(false)
                confirmNoContent = false
            },
            onDismiss = { confirmNoContent = false },
        )
    }
}
