@file:OptIn(ExperimentalLayoutApi::class)

package com.example.vibratext.app.ui.testing

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.example.vibratext.app.ui.components.ChoiceRow
import com.example.vibratext.app.ui.components.ErrorText
import com.example.vibratext.app.ui.components.Format
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.PlaybackCard
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.ui.components.SectionHeader
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.encoding.CodeSymbols
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.UnsupportedCharacterException
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.pipeline.ManualPlan
import com.example.vibratext.pipeline.ManualPlayback
import com.example.vibratext.playback.PlaybackRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface PlanState {
    data object Empty : PlanState
    data class Ready(val plan: ManualPlan) : PlanState
    data class Failed(val message: String) : PlanState
}

class TestViewModel(app: Application) : GraphViewModel(app) {
    val book: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())
    val playback = graph.controller.state

    /** null = the default profile. */
    val chosenProfileId = MutableStateFlow<String?>(null)
    val sender = MutableStateFlow("")
    val message = MutableStateFlow("hello")
    val characterText = MutableStateFlow("a")

    val profile: StateFlow<EncodingProfile> = combine(book, chosenProfileId) { b, id -> b.resolve(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial().default)

    val plan: StateFlow<PlanState> = combine(profile, sender, message) { p, s, m ->
        try {
            ManualPlayback.forMessage(s, m, p)?.let { PlanState.Ready(it) } ?: PlanState.Empty
        } catch (e: UnsupportedCharacterException) {
            PlanState.Failed("This profile skips messages with characters that are not in its table: ${e.message}")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanState.Empty)

    val hasVibrator: Boolean get() = graph.sink.isAvailable()

    private fun play(request: PlaybackRequest) = graph.controller.submit(request)

    fun playSymbol(symbol: VibSymbol) = play(ManualPlayback.forSymbol(symbol, profile.value))
    fun playControl(control: ControlCode) = play(ManualPlayback.forControl(control, profile.value))
    fun playTick() = play(ManualPlayback.forTick(profile.value))

    fun playMessage() {
        (plan.value as? PlanState.Ready)?.let { play(it.plan.request) }
    }

    /** Plays the code of the typed character. Returns an error text, or null. */
    fun playCharacter(): String? {
        val c = characterText.value.singleOrNull() ?: return "Type exactly one character."
        val p = ManualPlayback.forCharacter(c, profile.value) ?: return "“$c” is not in this profile's table."
        play(p.request)
        return null
    }

    fun stop() = graph.controller.stop()

    fun replay(): Boolean = graph.controller.replay()

    fun clear() {
        graph.controller.stop()
        sender.value = ""
        message.value = ""
    }
}

@Composable
fun TestScreen(onBack: () -> Unit, vm: TestViewModel = viewModel()) {
    val book by vm.book.collectAsStateWithLifecycle()
    val chosen by vm.chosenProfileId.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val sender by vm.sender.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val charText by vm.characterText.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var charError by remember { mutableStateOf<String?>(null) }
    var replayError by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "Test & calibrate", onBack = onBack) { padding ->
        PageList(padding) {
            if (!vm.hasVibrator) {
                item { InfoCard(tone = CardTone.WARNING) { BodyText("This device reports no vibrator, so nothing can be felt here.") } }
            }
            item {
                HintText("Everything here uses the same encoder, mapping and timing as real notifications, and starts immediately.")
            }
            item {
                ChoiceRow<String?>(
                    title = "Profile to test",
                    options = listOf<Pair<String, String?>>("Default (${book.default.name})" to null) + book.profiles.map { it.name to it.id },
                    selected = chosen?.takeIf { id -> book.byId(id) != null },
                    onSelect = { vm.chosenProfileId.value = it },
                )
            }

            item { SectionHeader("Single symbols") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VibSymbol.entries.forEach { s -> FilledTonalButton(onClick = { vm.playSymbol(s) }) { Text("Test ${s.label}") } }
                }
            }
            item {
                val mapping = profile.symbolMapping
                HintText("Current mapping: " + (0 until CodeSymbols.RADIX).joinToString(", ") { "digit $it = ${mapping.symbolFor(it).label}" })
            }

            item { SectionHeader("Markers") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.playControl(ControlCode.START) }) { Text("Test START marker") }
                    FilledTonalButton(onClick = { vm.playControl(ControlCode.SEPARATOR) }) { Text("Test field separator") }
                    FilledTonalButton(onClick = { vm.playControl(ControlCode.END) }) { Text("Test END marker") }
                    FilledTonalButton(onClick = vm::playTick) { Text("Test completion tick") }
                }
            }

            item { SectionHeader("One character") }
            item {
                OutlinedTextField(
                    value = charText,
                    onValueChange = { vm.characterText.value = it.take(1); charError = null },
                    label = { Text("Character") },
                    singleLine = true,
                )
            }
            item {
                val c = charText.singleOrNull()
                val code = c?.let { profile.charCodes[it] }
                if (c != null && code != null) {
                    HintText("Code $code = " + CodeSymbols.toSymbols(code, profile.symbolMapping).joinToString(" ") { it.letter.toString() })
                }
                charError?.let { ErrorText(it) }
                FilledTonalButton(onClick = { charError = vm.playCharacter() }) { Text("Test current character mapping") }
            }

            item { SectionHeader("Complete message") }
            item {
                OutlinedTextField(
                    value = sender,
                    onValueChange = { vm.sender.value = it },
                    label = { Text("Sender (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = message,
                    onValueChange = { vm.message.value = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                when (val p = plan) {
                    PlanState.Empty -> HintText("Type a message to see how it is encoded.")
                    is PlanState.Failed -> ErrorText(p.message)
                    is PlanState.Ready -> InfoCard(title = "Result") {
                        BodyText("Original message:\n${p.plan.original}")
                        BodyText("Normalized message:\n" + p.plan.normalizedFields.joinToString("\n→ "))
                        BodyText("Encoded symbols (S L D T, | between characters):\n${p.plan.symbolText}")
                        BodyText("Estimated playback duration: ${Format.duration(p.plan.estimatedDurationMs)}")
                        if (p.plan.replacedCount > 0) {
                            HintText("${p.plan.replacedCount} character(s) are not in the table and were replaced by “${profile.replacementChar}”.")
                        }
                    }
                }
            }
            item { PlaybackCard(playback, onStop = vm::stop) }
            item {
                replayError?.let { ErrorText(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::playMessage, enabled = plan is PlanState.Ready) { Text("Play") }
                    Button(onClick = vm::stop, enabled = playback.isBusy) { Text("Stop") }
                    OutlinedButton(onClick = { replayError = if (vm.replay()) null else "Nothing to replay yet." }) { Text("Replay") }
                    OutlinedButton(onClick = vm::clear) { Text("Clear") }
                }
            }
        }
    }
}
