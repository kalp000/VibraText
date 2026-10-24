package com.example.vibratext.pipeline

import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.encoding.MessageFramer
import com.example.vibratext.encoding.UnsupportedCharacterException
import com.example.vibratext.notification.ComposedMessage
import com.example.vibratext.notification.MessageComposer
import com.example.vibratext.notification.NotificationContext
import com.example.vibratext.notification.NotificationDeduplicator
import com.example.vibratext.notification.NotificationFilter
import com.example.vibratext.notification.NotificationParser
import com.example.vibratext.notification.ParsedNotification
import com.example.vibratext.notification.RawNotification
import com.example.vibratext.notification.RejectReason
import com.example.vibratext.playback.PlaybackController
import com.example.vibratext.playback.PlaybackRequest
import com.example.vibratext.playback.RequestIds
import com.example.vibratext.playback.RequestMeta
import com.example.vibratext.playback.RequestSource
import com.example.vibratext.playback.VibrationPatternGenerator
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.NotificationRuleEngine
import com.example.vibratext.rules.RuleOutcome
import java.time.ZoneId

sealed interface ProcessResult {
    data class Ignored(val reason: RejectReason) : ProcessResult
    data class Play(
        val request: PlaybackRequest,
        val composed: ComposedMessage,
        val profile: EncodingProfile,
        val parsed: ParsedNotification,
        val rule: RuleOutcome,
    ) : ProcessResult
}

/**
 * The whole decision pipeline for one incoming notification:
 *
 *   filter → parse → rules (profile / mode / mute / important) → duplicate check →
 *   compose fields → frame + encode → timed vibration script → [PlaybackRequest]
 *
 * Pure and synchronous: no Android classes, so every step is covered by unit tests.
 */
class NotificationProcessor(
    private val deduplicator: NotificationDeduplicator = NotificationDeduplicator(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun process(
        raw: RawNotification,
        applicationName: String,
        settings: AppSettings,
        rules: List<NotificationRule>,
        book: ProfileBook,
        ctx: NotificationContext,
    ): ProcessResult {
        NotificationFilter.check(raw, settings, ctx)?.let { return ProcessResult.Ignored(it) }

        val parsed = NotificationParser.parse(raw, applicationName)
        val outcome = NotificationRuleEngine.resolve(parsed, rules)
        if (outcome.mute) return ProcessResult.Ignored(RejectReason.MUTED_BY_RULE)
        if (settings.onlyImportant && !outcome.important) return ProcessResult.Ignored(RejectReason.NOT_IMPORTANT)

        if (settings.ignoreDuplicates &&
            deduplicator.isDuplicate(NotificationDeduplicator.signature(raw), ctx.nowMs, settings.duplicateWindowMs)
        ) {
            return ProcessResult.Ignored(RejectReason.DUPLICATE)
        }

        val profile = book.resolve(outcome.profileId)
        val mode = outcome.playbackMode ?: settings.playbackMode
        val composed = MessageComposer.compose(parsed, mode, settings, zone())

        val request = try {
            buildRequest(composed, profile, parsed, settings, count = 1)
        } catch (e: UnsupportedCharacterException) {
            return ProcessResult.Ignored(RejectReason.UNSUPPORTED_CHARACTER)
        }
        return ProcessResult.Play(request, composed, profile, parsed, outcome)
    }

    private fun buildRequest(
        composed: ComposedMessage,
        profile: EncodingProfile,
        parsed: ParsedNotification,
        settings: AppSettings,
        count: Int,
    ): PlaybackRequest {
        val framed = MessageFramer(profile.encoder()).frame(composed.fields)
        val script = VibrationPatternGenerator.generate(
            units = framed.units,
            mapping = profile.symbolMapping,
            timing = profile.effectiveTiming(),
            repeat = profile.repeat,
            completionTick = profile.completionTick,
        )
        return PlaybackRequest(
            id = RequestIds.next(),
            script = script,
            meta = RequestMeta(
                source = RequestSource.NOTIFICATION,
                packageName = parsed.packageName,
                applicationName = parsed.applicationName,
                senderName = parsed.senderName,
                preview = composed.preview,
                profileId = profile.id,
                profileName = profile.name,
                truncated = composed.truncated,
                notificationKey = parsed.notificationKey,
                combinedCount = count,
                parsed = parsed,
            ),
            intensityPercent = profile.intensityPercent,
            startDelayMs = settings.startDelayMs,
            combiner = { previous ->
                val n = previous + 1
                buildRequest(MessageComposer.composeCombined(parsed, n, composed.mode), profile, parsed, settings, n)
            },
        )
    }
}

/** Pushes the queue / pacing settings into a running controller. */
fun PlaybackController.applySettings(settings: AppSettings) {
    queuePolicy = settings.queuePolicy
    maxQueueSize = settings.maxQueueSize
    minIntervalMs = settings.minIntervalMs
}
