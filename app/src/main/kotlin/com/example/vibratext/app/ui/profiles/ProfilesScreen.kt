@file:OptIn(ExperimentalLayoutApi::class)

package com.example.vibratext.app.ui.profiles

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import com.example.vibratext.app.ui.components.CardTone
import com.example.vibratext.app.ui.components.ConfirmDialog
import com.example.vibratext.app.ui.components.ErrorText
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.data.ProfileImportResult
import com.example.vibratext.data.ProfileJson
import com.example.vibratext.data.ProfileTemplates
import com.example.vibratext.playback.SpeedPreset
import com.example.vibratext.rules.RuleEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class ProfilesViewModel(app: Application) : GraphViewModel(app) {
    val book: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun dismissMessage() {
        _message.value = null
    }

    fun setDefault(id: String) {
        viewModelScope.launch { graph.store.updateProfiles { it.setDefault(id) } }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { graph.store.updateProfiles { it.duplicate(id, UUID.randomUUID().toString()) } }
    }

    /** Returns an error text, or null if the rename was accepted. */
    fun rename(id: String, newName: String): String? {
        val name = newName.trim()
        val others = book.value.profiles.filter { it.id != id }
        return when {
            name.isEmpty() -> "The profile needs a name."
            name.length > EncodingProfile.MAX_NAME_LENGTH -> "At most ${EncodingProfile.MAX_NAME_LENGTH} characters."
            name.any { it.isISOControl() } -> "The name contains invalid characters."
            others.any { it.name.trim().equals(name, ignoreCase = true) } -> "Another profile already has this name."
            else -> {
                viewModelScope.launch { graph.store.updateProfiles { it.rename(id, name) } }
                null
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            // Rules that used the profile forget it in the same transaction, so none points at a missing profile.
            graph.store.updateProfilesAndRules({ it.delete(id) }, { RuleEditing.withoutProfile(it, id) })
        }
    }

    fun createFromTemplate(key: String): String? {
        val template = ProfileTemplates.all.firstOrNull { it.key == key } ?: return null
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            graph.store.updateProfiles { b ->
                val p = template.build(id)
                b.upsert(p.copy(name = b.uniqueName(p.name)))
            }
        }
        return id
    }

    fun export(uri: Uri, profileId: String) {
        val profile = book.value.byId(profileId) ?: return
        viewModelScope.launch {
            _message.value = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(ProfileJson.export(profile).toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("The file could not be opened")
                }
                "Exported “${profile.name}”. The file contains only encoding settings, no notification content."
            } catch (e: IOException) {
                "Export failed: ${e.message ?: "the file could not be written"}"
            } catch (e: SecurityException) {
                "Export failed: no permission to write that file."
            }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val text: String? = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val buffer = CharArray(ProfileJson.MAX_IMPORT_CHARS + 1)
                        var filled = 0
                        while (filled < buffer.size) {
                            val n = reader.read(buffer, filled, buffer.size - filled)
                            if (n < 0) break
                            filled += n
                        }
                        if (filled > ProfileJson.MAX_IMPORT_CHARS) null else String(buffer, 0, filled)
                    }
                }
            } catch (e: IOException) {
                _message.value = "Import failed: the file could not be read."
                return@launch
            } catch (e: SecurityException) {
                _message.value = "Import failed: no permission to read that file."
                return@launch
            }
            if (text == null) {
                _message.value = "Import failed: the file is too large or could not be opened."
                return@launch
            }
            when (val result = ProfileJson.import(text, UUID.randomUUID().toString())) {
                is ProfileImportResult.Failure -> _message.value = "Import failed:\n" + result.errors.joinToString("\n") { "• $it" }
                is ProfileImportResult.Success -> {
                    var finalName = result.profile.name
                    graph.store.updateProfiles { b ->
                        finalName = b.uniqueName(result.profile.name)
                        b.upsert(result.profile.copy(name = finalName))
                    }
                    _message.value = "Imported “$finalName”. Imported files are treated as data only: they were validated and cannot change anything except this profile."
                }
            }
        }
    }
}

@Composable
fun ProfilesScreen(onEdit: (String) -> Unit, vm: ProfilesViewModel = viewModel()) {
    val book by vm.book.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var exportId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var templateMenu by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.export(uri, id)
        exportId = null
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    ScreenScaffold(title = "Encoding profiles") { padding ->
        PageList(padding) {
            item {
                BodyText("A profile defines how text feels: symbol mapping, character table, timing, speed, repetition and intensity. Rules can use a different profile per app or contact.")
            }
            message?.let { text ->
                item {
                    InfoCard(tone = if (text.startsWith("Import failed") || text.startsWith("Export failed")) CardTone.WARNING else CardTone.GOOD) {
                        BodyText(text)
                        TextButton(onClick = vm::dismissMessage) { Text("Dismiss") }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        Button(onClick = { templateMenu = true }) { Text("New profile ▾") }
                        DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                            ProfileTemplates.all.forEach { t ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(t.label)
                                            HintText(t.description)
                                        }
                                    },
                                    onClick = {
                                        templateMenu = false
                                        vm.createFromTemplate(t.key)?.let(onEdit)
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import from file") }
                }
            }
            items(book.profiles, key = { it.id }) { p ->
                val isDefault = p.id == book.defaultProfileId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (isDefault) Text("Default", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        HintText(summary(p))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onEdit(p.id) }) { Text("Edit") }
                            if (!isDefault) TextButton(onClick = { vm.setDefault(p.id) }) { Text("Make default") }
                            TextButton(onClick = { vm.duplicate(p.id) }) { Text("Duplicate") }
                            TextButton(onClick = { renameId = p.id }) { Text("Rename") }
                            TextButton(onClick = {
                                exportId = p.id
                                exporter.launch("vibratext-" + p.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".json")
                            }) { Text("Export") }
                            if (book.profiles.size > 1) TextButton(onClick = { deleteId = p.id }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    renameId?.let { id ->
        val current = book.byId(id)
        if (current == null) {
            renameId = null
        } else {
            var name by remember(id) { mutableStateOf(current.name) }
            var error by remember(id) { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { renameId = null },
                title = { Text("Rename profile") },
                text = {
                    Column {
                        OutlinedTextField(value = name, onValueChange = { name = it; error = null }, singleLine = true, label = { Text("Name") })
                        error?.let { ErrorText(it) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        error = vm.rename(id, name)
                        if (error == null) renameId = null
                    }) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } },
            )
        }
    }

    deleteId?.let { id ->
        val target = book.byId(id)
        if (target == null) {
            deleteId = null
        } else {
            ConfirmDialog(
                title = "Delete “${target.name}”?",
                message = "Rules that used this profile will fall back to the default profile." +
                    if (id == book.defaultProfileId) " Another profile becomes the default." else "",
                confirmLabel = "Delete",
                onConfirm = {
                    vm.delete(id)
                    deleteId = null
                },
                onDismiss = { deleteId = null },
            )
        }
    }
}

private fun summary(p: EncodingProfile): String {
    val speed = if (p.speed.preset == SpeedPreset.CUSTOM) "speed ×${"%.2f".format(p.speed.customMultiplier)}" else "speed ${p.speed.preset.label.lowercase()}"
    val repeat = if (p.repeat.count > 1) "repeated ${p.repeat.count}×" else "played once"
    return "$speed · $repeat · intensity ${p.intensityPercent}% · ${if (p.caseMode.name == "PRESERVE") "keeps capitals" else "lower-case"}"
}
