package com.example.vibratext.app.ui.profiles

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.encoding.CodeSymbols
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.EncodingTable
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.UnsupportedCharacterException
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.pipeline.ManualPlan
import com.example.vibratext.pipeline.ManualPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result of reading the symbol letters a user typed for a character. */
sealed interface CodeInput {
    data class Valid(val code: Int) : CodeInput
    data class Invalid(val message: String) : CodeInput
}

/**
 * Edits a *draft* copy of one profile. Nothing is saved until [save] succeeds, and Save is only
 * possible when [EncodingProfile.validate] reports no problems, so a stored profile is always valid.
 */
class ProfileEditorViewModel(app: Application, handle: SavedStateHandle) : GraphViewModel(app) {
    private val profileId: String = handle.get<String>("id").orEmpty()

    private val _draft = MutableStateFlow<EncodingProfile?>(null)
    val draft: StateFlow<EncodingProfile?> = _draft.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _saved = MutableStateFlow<EncodingProfile?>(null)

    private val _dirty = MutableStateFlow(false)

    /** True when the draft differs from what is stored. */
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    val supportsAmplitude: Boolean get() = graph.sink.supportsAmplitude()
    val hasVibrator: Boolean get() = graph.sink.isAvailable()

    private var previewRequestId: Long? = null

    init {
        viewModelScope.launch {
            val stored = graph.store.profiles.first().byId(profileId)
            _saved.value = stored
            _draft.value = stored
            _loaded.value = true
        }
    }

    fun update(transform: (EncodingProfile) -> EncodingProfile) {
        _draft.update { current -> current?.let(transform) }
        _dirty.value = _draft.value != _saved.value
    }

    fun save(onDone: () -> Unit) {
        val d = _draft.value ?: return
        if (d.validate().isNotEmpty()) return
        viewModelScope.launch {
            graph.store.updateProfiles { it.upsert(d) }
            _saved.value = d
            _dirty.value = false
            onDone()
        }
    }

    // ---- symbol mapping ------------------------------------------------------------------------

    /** Gives [digit] the chosen [symbol]. The mapping must stay one-to-one, so the digit that had it gets the old one. */
    fun setSymbolForDigit(digit: Int, symbol: VibSymbol) {
        update { p ->
            val list = p.symbolMapping.digitToSymbol.toMutableList()
            val other = list.indexOf(symbol)
            if (other >= 0 && other != digit) list[other] = list[digit]
            list[digit] = symbol
            p.copy(symbolMapping = SymbolMapping(list))
        }
    }

    // ---- character table -----------------------------------------------------------------------

    fun parseCode(text: String, forChar: Char?): CodeInput {
        val profile = _draft.value ?: return CodeInput.Invalid("No profile loaded.")
        val letters = text.filterNot { it.isWhitespace() || it == '|' }
        val symbols = ArrayList<VibSymbol>()
        for (c in letters) {
            symbols += VibSymbol.fromLetter(c) ?: return CodeInput.Invalid("“$c” is not a symbol. Use S, L, D or T.")
        }
        if (symbols.size != CodeSymbols.CODE_LENGTH) {
            return CodeInput.Invalid("A character needs exactly ${CodeSymbols.CODE_LENGTH} symbols (this has ${symbols.size}).")
        }
        val code = CodeSymbols.fromSymbols(symbols, profile.symbolMapping)
        ControlCode.fromCode(code)?.let { return CodeInput.Invalid("This code is reserved for the ${it.displayName} marker.") }
        val owner = profile.charCodes.entries.firstOrNull { it.value == code && it.key != forChar }
        if (owner != null) return CodeInput.Invalid("This code is already used by “${EncodingTable.display(owner.key)}”.")
        return CodeInput.Valid(code)
    }

    fun setCharCode(c: Char, code: Int) = update { it.copy(charCodes = LinkedHashMap(it.charCodes).also { m -> m[c] = code }) }

    fun removeChar(c: Char) = update { it.copy(charCodes = LinkedHashMap(it.charCodes).also { m -> m.remove(c) }) }

    fun resetTable() = update { it.copy(charCodes = EncodingTable.defaultCharCodes()) }

    // ---- previews --------------------------------------------------------------------------------

    fun planFor(sender: String, message: String): Result<ManualPlan?> {
        val p = _draft.value ?: return Result.success(null)
        if (p.validate().isNotEmpty()) return Result.failure(IllegalStateException("Fix the problems above first."))
        return try {
            Result.success(ManualPlayback.forMessage(sender, message, p))
        } catch (e: UnsupportedCharacterException) {
            Result.failure(e)
        }
    }

    fun playMessage(sender: String, message: String): String? {
        val plan = planFor(sender, message).getOrElse { return it.message ?: "This text cannot be encoded." } ?: return "Type a message first."
        play(plan.request)
        return null
    }

    fun playSymbol(symbol: VibSymbol) {
        val p = _draft.value ?: return
        play(ManualPlayback.forSymbol(symbol, p))
    }

    /** Plays the three symbols that [code] would give [c], before the user saves it. */
    fun playCode(c: Char, code: Int) {
        val p = _draft.value ?: return
        val temp = p.copy(charCodes = LinkedHashMap(p.charCodes).also { it[c] = code })
        try {
            ManualPlayback.forCharacter(c, temp)?.let { play(it.request) }
        } catch (_: IllegalArgumentException) {
            // The table is not valid yet; the editor already shows why.
        }
    }

    fun playCharacter(c: Char) {
        val p = _draft.value ?: return
        try {
            ManualPlayback.forCharacter(c, p)?.let { play(it.request) }
        } catch (_: IllegalArgumentException) {
        }
    }

    fun stop() = graph.controller.stop()

    private fun play(request: com.example.vibratext.playback.PlaybackRequest) {
        previewRequestId = request.id
        graph.controller.submit(request)
    }

    override fun onCleared() {
        // Leaving the editor must not leave a preview vibrating.
        val id = previewRequestId
        if (id != null && graph.controller.state.value.requestId == id) graph.controller.stop()
        super.onCleared()
    }
}
