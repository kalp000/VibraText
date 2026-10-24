package com.example.vibratext.app.ui.apps

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.ChoiceRow
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.ui.components.SettingSwitch
import com.example.vibratext.app.util.InstalledApp
import com.example.vibratext.app.util.InstalledAppsRepository
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.RuleEditing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class AppsViewModel(app: Application) : GraphViewModel(app) {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    val query = MutableStateFlow("")
    val showSystem = MutableStateFlow(false)

    val settings: StateFlow<AppSettings> = graph.store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val profiles: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())
    val rules: StateFlow<List<NotificationRule>> = graph.store.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null while the list is still loading. */
    val visibleApps: StateFlow<List<InstalledApp>?> = combine(installed, settings, query, showSystem) { apps, s, q, system ->
        if (apps == null) return@combine null
        val selected = s.selectedPackages
        val known = apps.map { it.packageName }.toSet()
        // Apps that were selected earlier but are no longer visible stay listed so they can be removed.
        val missing = selected.filter { it !in known }.map { InstalledApp(it, graph.installedApps.labelFor(it), isSystem = false) }
        (apps + missing)
            .filter { (system || !it.isSystem || it.packageName in selected || it.packageName in InstalledAppsRepository.KNOWN_MESSENGERS) }
            .filter { q.isBlank() || it.label.contains(q.trim(), ignoreCase = true) || it.packageName.contains(q.trim(), ignoreCase = true) }
            .sortedWith(compareBy({ it.packageName !in InstalledAppsRepository.KNOWN_MESSENGERS }, { it.label.lowercase() }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { installed.value = graph.installedApps.load() }
    }

    fun setSelected(packageName: String, selected: Boolean) {
        viewModelScope.launch {
            graph.store.updateSettings {
                it.copy(selectedPackages = if (selected) it.selectedPackages + packageName else it.selectedPackages - packageName)
            }
        }
    }

    fun setAppProfile(packageName: String, profileId: String?) {
        viewModelScope.launch {
            graph.store.updateRules { RuleEditing.withAppProfile(it, packageName, profileId) { UUID.randomUUID().toString() } }
        }
    }
}

@Composable
fun AppsScreen(vm: AppsViewModel = viewModel()) {
    val apps by vm.visibleApps.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val showSystem by vm.showSystem.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Applications") { padding ->
        PageList(padding) {
            item {
                BodyText("Choose the apps whose notifications become vibration. Notifications from every other app are never opened.")
                HintText("${settings.selectedPackages.size} selected. Messaging apps such as WhatsApp, Signal and Telegram are listed first when installed.")
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SettingSwitch(
                    title = "Show system apps",
                    description = "Pre-installed apps that were never updated (clock, stock messages, ...) are hidden by default.",
                    checked = showSystem,
                    onCheckedChange = { vm.showSystem.value = it },
                )
            }
            val list = apps
            if (list == null) {
                item { BodyText("Loading installed apps…") }
            } else if (list.isEmpty()) {
                item { BodyText("No apps match.") }
            } else {
                items(list, key = { it.packageName }) { app ->
                    val selected = app.packageName in settings.selectedPackages
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { vm.setSelected(app.packageName, !selected) },
                            ) {
                                Checkbox(checked = selected, onCheckedChange = { vm.setSelected(app.packageName, it) })
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    HintText(app.packageName)
                                }
                            }
                            if (selected) {
                                ChoiceRow(
                                    title = "Profile for this app",
                                    options = listOf<Pair<String, String?>>("Default profile (${profiles.default.name})" to null) +
                                        profiles.profiles.map { it.name to it.id },
                                    selected = RuleEditing.appProfileId(rules, app.packageName)?.takeIf { id -> profiles.byId(id) != null },
                                    onSelect = { vm.setAppProfile(app.packageName, it) },
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
