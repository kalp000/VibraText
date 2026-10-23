package com.example.vibratext.app.ui.rules

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.ChoiceRow
import com.example.vibratext.app.ui.components.ConfirmDialog
import com.example.vibratext.app.ui.components.ErrorText
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.rules.NotificationClass
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.NotificationRuleEngine
import com.example.vibratext.rules.RuleScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class RulesViewModel(app: Application) : GraphViewModel(app) {
    val rules: StateFlow<List<NotificationRule>> = graph.store.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profiles: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())
    val settings: StateFlow<AppSettings> = graph.store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun labelFor(packageName: String): String = graph.installedApps.labelFor(packageName)

    /** Adds the rule, or replaces the one with the same id. */
    fun save(rule: NotificationRule) {
        viewModelScope.launch {
            graph.store.updateRules { list ->
                if (list.any { it.id == rule.id }) list.map { if (it.id == rule.id) rule else it } else list + rule
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { graph.store.updateRules { list -> list.filterNot { it.id == id } } }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { graph.store.updateRules { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } } }
    }

    fun newId(): String = UUID.randomUUID().toString()
}

@Composable
fun RulesScreen(onBack: () -> Unit, vm: RulesViewModel = viewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<NotificationRule?>(null) }
    var deleting by remember { mutableStateOf<NotificationRule?>(null) }

    ScreenScaffold(title = "Rules", onBack = onBack) { padding ->
        PageList(padding) {
            item {
                InfoCard(title = "How rules are applied") {
                    BodyText("When several rules match one notification, the most specific one wins for each setting:")
                    BodyText("1. Contact rule  2. App rule  3. Type-of-notification rule  4. Your global settings")
                    HintText(
                        "A rule only sets what you choose; everything else falls through to the next level. " +
                            "Contacts are matched by the sender name shown in the notification (case ignored); VibraText never reads your address book. " +
                            "“Mute” silences matching notifications; “Always allow” lets one through that a lower level would mute.",
                    )
                }
            }
            item { Button(onClick = { editing = NotificationRule(vm.newId(), RuleScope.CONTACT, "", mute = null) }) { Text("Add rule") } }
            if (rules.isEmpty()) {
                item { BodyText("No rules yet. Every selected app uses the default profile and your global settings.") }
            }
            items(rules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title(rule, vm), style = MaterialTheme.typography.titleMedium)
                                HintText(effects(rule, profiles))
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { vm.setEnabled(rule.id, it) })
                        }
                        Row {
                            TextButton(onClick = { editing = rule }) { Text("Edit") }
                            TextButton(onClick = { deleting = rule }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        RuleDialog(
            initial = initial,
            appChoices = settings.selectedPackages.map { vm.labelFor(it) to it }.sortedBy { it.first.lowercase() },
            profiles = profiles,
            onSave = {
                vm.save(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { rule ->
        ConfirmDialog(
            title = "Delete this rule?",
            message = title(rule, vm),
            confirmLabel = "Delete",
            onConfirm = {
                vm.delete(rule.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun title(rule: NotificationRule, vm: RulesViewModel): String = when (rule.scope) {
    RuleScope.CONTACT -> "Contact: ${rule.match}"
    RuleScope.APP -> "App: ${vm.labelFor(rule.match)}"
    RuleScope.CATEGORY -> "Type: ${NotificationClass.entries.firstOrNull { it.name == rule.match }?.label ?: rule.match}"
}

private fun effects(rule: NotificationRule, profiles: ProfileBook): String {
    val parts = ArrayList<String>()
    rule.profileId?.let { parts += "profile ${profiles.byId(it)?.name ?: "(deleted, uses default)"}" }
    rule.playbackMode?.let { parts += "mode ${it.label}" }
    when (rule.mute) {
        true -> parts += "muted"
        false -> parts += "always allowed"
        null -> {}
    }
    when (rule.important) {
        true -> parts += "important"
        false -> parts += "not important"
        null -> {}
    }
    return if (parts.isEmpty()) "does nothing yet" else parts.joinToString(" · ")
}

@Composable
private fun RuleDialog(
    initial: NotificationRule,
    appChoices: List<Pair<String, String>>,
    profiles: ProfileBook,
    onSave: (NotificationRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var rule by remember(initial.id) { mutableStateOf(initial) }
    var showErrors by remember(initial.id) { mutableStateOf(false) }
    val errors = NotificationRuleEngine.validate(rule)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow(
                    title = "Applies to",
                    options = RuleScope.entries.map { it.label to it },
                    selected = rule.scope,
                    onSelect = { scope ->
                        rule = rule.copy(
                            scope = scope,
                            match = when (scope) {
                                RuleScope.CONTACT -> ""
                                RuleScope.APP -> appChoices.firstOrNull()?.second ?: ""
                                RuleScope.CATEGORY -> NotificationClass.entries.first().name
                            },
                        )
                    },
                )
                when (rule.scope) {
                    RuleScope.CONTACT -> {
                        OutlinedTextField(
                            value = rule.match,
                            onValueChange = { rule = rule.copy(match = it) },
                            label = { Text("Sender name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HintText("Exactly as the notification shows it, for example “Alex”.")
                    }
                    RuleScope.APP -> {
                        if (appChoices.isEmpty()) {
                            HintText("Select apps on the Applications screen first.")
                        } else {
                            ChoiceRow(
                                title = "App",
                                options = appChoices.map { it.first to it.second },
                                selected = rule.match,
                                onSelect = { rule = rule.copy(match = it) },
                            )
                        }
                    }
                    RuleScope.CATEGORY -> ChoiceRow(
                        title = "Type of notification",
                        options = NotificationClass.entries.map { it.label to it.name },
                        selected = rule.match,
                        onSelect = { rule = rule.copy(match = it) },
                    )
                }
                ChoiceRow<String?>(
                    title = "Profile",
                    options = listOf<Pair<String, String?>>("No opinion (use the next level)" to null) + profiles.profiles.map { it.name to it.id },
                    selected = rule.profileId,
                    onSelect = { rule = rule.copy(profileId = it) },
                )
                ChoiceRow<PlaybackMode?>(
                    title = "What to play",
                    options = listOf<Pair<String, PlaybackMode?>>("No opinion (use the global mode)" to null) + PlaybackMode.entries.map { it.label to it },
                    selected = rule.playbackMode,
                    onSelect = { rule = rule.copy(playbackMode = it) },
                )
                ChoiceRow<Boolean?>(
                    title = "Mute",
                    options = listOf("No opinion" to null, "Mute: never vibrate" to true, "Always allow" to false),
                    selected = rule.mute,
                    onSelect = { rule = rule.copy(mute = it) },
                )
                ChoiceRow<Boolean?>(
                    title = "Important",
                    description = "Used by “Only important notifications” in Settings.",
                    options = listOf("No opinion" to null, "Important" to true, "Not important" to false),
                    selected = rule.important,
                    onSelect = { rule = rule.copy(important = it) },
                )
                if (showErrors) errors.forEach { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (errors.isEmpty()) onSave(rule.copy(match = rule.match.trim())) else showErrors = true
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
