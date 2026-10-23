package com.example.vibratext.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vibratext.playback.PlaybackPhase
import com.example.vibratext.playback.PlaybackState

/** Live playback status: what is being played, where it is, and a Stop button. */
@Composable
fun PlaybackCard(state: PlaybackState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    InfoCard(title = if (state.isTest) "Test playback" else "Playing a notification", modifier = modifier) {
        val who = listOfNotNull(state.currentApplication, state.currentSender).joinToString(" · ")
        if (who.isNotEmpty()) BodyText(who)
        when (state.phase) {
            PlaybackPhase.WAITING -> BodyText("Waiting to start…")
            PlaybackPhase.PLAYING -> {
                BodyText("Character: ${state.currentCharacter ?: "–"}   Symbol: ${state.currentSymbol?.label ?: "–"}")
                BodyText("Part: ${state.currentSection?.name?.lowercase() ?: "–"}   Position: ${state.currentMessageIndex}/${state.totalMessageLength}")
                if (state.totalRepeats > 1) BodyText("Repetition ${state.repeatIndex} of ${state.totalRepeats}")
            }
            PlaybackPhase.IDLE -> BodyText("Nothing is playing.")
        }
        LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (state.queueSize > 0) HintText("${state.queueSize} more waiting in the queue")
        state.lastError?.let { ErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStop, enabled = state.isBusy) { Text("Stop") }
        }
    }
}
