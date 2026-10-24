package com.example.vibratext.app.ui.decoder

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.CardTone
import com.example.vibratext.app.ui.components.ChoiceRow
import com.example.vibratext.app.ui.components.ErrorText
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.encoding.CharacterDecoder
import com.example.vibratext.encoding.CodeSymbols
import com.example.vibratext.encoding.DecodeResult
import com.example.vibratext.encoding.DecodedMessage
import com.example.vibratext.pipeline.ManualPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface DecoderState {
    data object Empty : DecoderState
    data class Decoded(val message: DecodedMessage) : DecoderState
    data class Failed(val errors: List<String>) : DecoderState
}

class DecoderViewModel(app: Application) : GraphViewModel(app) {
    val book: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())
    val chosenProfileId = MutableStateFlow<String?>(null)
    val input = MutableStateFlow("")

    val profile: StateFlow<EncodingProfile> = combine(book, chosenProfileId) { b, id -> b.resolve(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial().default)

    val state: StateFlow<DecoderState> = combine(profile, input) { p, text ->
        if (text.isBlank()) {
            DecoderState.Empty
        } else {
            when (val r = p.decoder().decodeSymbols(text)) {
                is DecodeResult.Success -> DecoderState.Decoded(r.value)
                is DecodeResult.Failure -> DecoderState.Failed(r.errors.map { it.message })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DecoderState.Empty)

    /** Fills the box with the symbols of a sample message, encoded with the chosen profile. */
    fun insertExample() {
        val plan = ManualPlayback.forMessage("Alex", "Hi!", profile.value)
        if (plan != null) input.value = plan.symbolText
    }
}

@Composable
fun DecoderScreen(onBack: () -> Unit, vm: DecoderViewModel = viewModel()) {
    val book by vm.book.collectAsStateWithLifecycle()
    val chosen by vm.chosenProfileId.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Decoder", onBack = onBack) { padding ->
        PageList(padding) {
            item {
                InfoCard(title = "How to write a sequence") {
                    BodyText("Use S = short, L = long, D = double, T = triple.")
                    BodyText("Every character is exactly ${CodeSymbols.CODE_LENGTH} symbols. Put “|” between characters if you like, or write them all together and they are cut into groups of ${CodeSymbols.CODE_LENGTH}.")
                    BodyText("Example (one character per group):  S L D T S | L S D T S")
                    HintText("Framed messages start with the START marker and end with the END marker; the decoder then splits sender and message at the separator. Spaces and line breaks are ignored. The mapping and character table of the chosen profile are used, so the decoder always agrees with the encoder.")
                }
            }
            item {
                ChoiceRow<String?>(
                    title = "Profile (mapping and table)",
                    options = listOf<Pair<String, String?>>("Default (${book.default.name})" to null) + book.profiles.map { it.name to it.id },
                    selected = chosen?.takeIf { id -> book.byId(id) != null },
                    onSelect = { vm.chosenProfileId.value = it },
                )
            }
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = { vm.input.value = it },
                    label = { Text("Symbols") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::insertExample) { Text("Insert example") }
                    OutlinedButton(onClick = { vm.input.value = "" }) { Text("Clear") }
                }
            }
            item {
                when (val s = state) {
                    DecoderState.Empty -> HintText("Enter a sequence to decode it.")
                    is DecoderState.Failed -> InfoCard(title = "Cannot decode", tone = CardTone.WARNING) {
                        s.errors.forEach { ErrorText(it) }
                    }
                    is DecoderState.Decoded -> InfoCard(title = "Decoded", tone = CardTone.GOOD) {
                        if (s.message.framed && s.message.fields.size > 1) {
                            s.message.fields.forEachIndexed { i, f ->
                                BodyText("Field ${i + 1}: ${f.text}" + if (f.truncated) "  (marked as shortened)" else "")
                            }
                        } else {
                            BodyText(s.message.displayText)
                            s.message.fields.firstOrNull()?.takeIf { it.truncated }?.let { HintText("The sender marked this text as shortened.") }
                        }
                        HintText(
                            "Capitals cannot be recovered unless the profile preserves case; everything else is exactly what was sent.",
                        )
                    }
                }
            }
        }
    }
}
