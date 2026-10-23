package com.example.vibratext.rules

import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.json.JsonException
import com.example.vibratext.json.JsonObj
import com.example.vibratext.json.MiniJson
import com.example.vibratext.notification.NotificationParser
import com.example.vibratext.notification.ParsedNotification

enum class RuleScope(val label: String) {
    CONTACT("Contact"),
    APP("App"),
    CATEGORY("Type of notification"),
}

/** Kinds of notification a CATEGORY rule can match. A notification can belong to several. */
enum class NotificationClass(val label: String) {
    CALL("Calls"),
    HIGH_PRIORITY("High-priority notifications"),
    UNKNOWN_SENDER("Unknown sender"),
    NO_MESSAGE_TEXT("No message text"),
    MESSAGE("Messages"),
}

/**
 * One rule. [match] depends on [scope]:
 *  CONTACT  → sender name as exposed in the notification (compared ignoring case)
 *  APP      → package name
 *  CATEGORY → a [NotificationClass] name
 *
 * Every outcome field is optional; null means "this rule has no opinion".
 * [mute]: true = ignore matching notifications, false = explicitly allow (e.g. let one contact
 * through although the app is muted).
 */
data class NotificationRule(
    val id: String,
    val scope: RuleScope,
    val match: String,
    val enabled: Boolean = true,
    val profileId: String? = null,
    val playbackMode: PlaybackMode? = null,
    val important: Boolean? = null,
    val mute: Boolean? = null,
)

data class RuleOutcome(
    val profileId: String? = null,
    val playbackMode: PlaybackMode? = null,
    val important: Boolean = false,
    val mute: Boolean = false,
    val matchedRuleIds: List<String> = emptyList(),
)

/**
 * Priority order (documented in the README), first rule with an opinion wins per setting:
 *  1. contact rule  2. app rule  3. type-of-notification rule  4. global default.
 * Within one level, rules are checked in list order.
 *
 * Contact matching only uses the sender name that the notification itself exposes; no access to
 * the address book is needed.
 */
object NotificationRuleEngine {

    fun classify(parsed: ParsedNotification): Set<NotificationClass> {
        val classes = LinkedHashSet<NotificationClass>()
        if (parsed.notificationCategory == NotificationParser.CATEGORY_CALL) classes += NotificationClass.CALL
        if (parsed.importance != null && parsed.importance >= 4) classes += NotificationClass.HIGH_PRIORITY
        if (!parsed.hasSender) classes += NotificationClass.UNKNOWN_SENDER
        if (!parsed.hasMessage) classes += NotificationClass.NO_MESSAGE_TEXT
        if (parsed.isMessaging || parsed.notificationCategory == NotificationParser.CATEGORY_MESSAGE) classes += NotificationClass.MESSAGE
        return classes
    }

    fun resolve(parsed: ParsedNotification, rules: List<NotificationRule>): RuleOutcome {
        val active = rules.filter { it.enabled }
        val classes = classify(parsed)
        val sender = parsed.senderName?.trim()
        val contact = if (sender.isNullOrEmpty()) emptyList() else
            active.filter { it.scope == RuleScope.CONTACT && it.match.trim().equals(sender, ignoreCase = true) }
        val app = active.filter { it.scope == RuleScope.APP && it.match == parsed.packageName }
        val category = active.filter { r ->
            r.scope == RuleScope.CATEGORY && classes.any { it.name == r.match }
        }
        val ordered = contact + app + category
        return RuleOutcome(
            profileId = ordered.firstNotNullOfOrNull { it.profileId },
            playbackMode = ordered.firstNotNullOfOrNull { it.playbackMode },
            important = ordered.firstNotNullOfOrNull { it.important } ?: false,
            mute = ordered.firstNotNullOfOrNull { it.mute } ?: false,
            matchedRuleIds = ordered.map { it.id },
        )
    }

    /** Problems with a rule the user is about to save. */
    fun validate(rule: NotificationRule): List<String> {
        val e = mutableListOf<String>()
        if (rule.match.isBlank()) e += "The rule needs something to match (a contact, app or type)."
        if (rule.scope == RuleScope.CATEGORY && NotificationClass.entries.none { it.name == rule.match }) e += "Unknown notification type '${rule.match}'."
        if (rule.profileId == null && rule.playbackMode == null && rule.important == null && rule.mute == null) {
            e += "The rule does nothing yet. Choose a profile, a playback mode, mute or important."
        }
        return e
    }
}

/** Small edits the UI makes to the rule list (kept here so they are unit-tested). */
object RuleEditing {
    /** The profile chosen by the first APP rule for [packageName] that sets one, or null. */
    fun appProfileId(rules: List<NotificationRule>, packageName: String): String? =
        rules.firstOrNull { it.scope == RuleScope.APP && it.match == packageName && it.profileId != null }?.profileId

    /**
     * Sets (or, with null, clears) the profile for [packageName] on its APP rule. Creates the rule
     * if needed and removes it again if it no longer has any effect.
     */
    fun withAppProfile(rules: List<NotificationRule>, packageName: String, profileId: String?, newRuleId: () -> String): List<NotificationRule> {
        val index = rules.indexOfFirst { it.scope == RuleScope.APP && it.match == packageName && it.profileId != null }
            .takeIf { it >= 0 } ?: rules.indexOfFirst { it.scope == RuleScope.APP && it.match == packageName }
        if (index < 0) {
            return if (profileId == null) rules else rules + NotificationRule(newRuleId(), RuleScope.APP, packageName, profileId = profileId)
        }
        val updated = rules[index].copy(profileId = profileId)
        return if (hasNoEffect(updated)) rules.filterIndexed { i, _ -> i != index } else rules.mapIndexed { i, r -> if (i == index) updated else r }
    }

    /** Forgets a deleted profile everywhere; rules that then do nothing are removed. */
    fun withoutProfile(rules: List<NotificationRule>, profileId: String): List<NotificationRule> =
        rules.map { if (it.profileId == profileId) it.copy(profileId = null) else it }.filterNot { hasNoEffect(it) }

    /** Removes every rule that belongs to [packageName] (used when an app is no longer monitored). */
    fun withoutApp(rules: List<NotificationRule>, packageName: String): List<NotificationRule> =
        rules.filterNot { it.scope == RuleScope.APP && it.match == packageName }

    private fun hasNoEffect(r: NotificationRule) = r.profileId == null && r.playbackMode == null && r.important == null && r.mute == null
}

object RulesJson {
    fun encode(rules: List<NotificationRule>): String = MiniJson.stringify(
        rules.map { r ->
            linkedMapOf<String, Any?>(
                "id" to r.id,
                "scope" to r.scope.name,
                "match" to r.match,
                "enabled" to r.enabled,
                "profileId" to r.profileId,
                "playbackMode" to r.playbackMode?.name,
                "important" to r.important,
                "mute" to r.mute,
            )
        },
    )

    fun decode(json: String?): List<NotificationRule> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            (MiniJson.parse(json) as? List<*> ?: return emptyList()).mapNotNull { item ->
                try {
                    val o = JsonObj.of(item)
                    NotificationRule(
                        id = o.string("id"),
                        scope = RuleScope.entries.firstOrNull { it.name == o.string("scope") } ?: return@mapNotNull null,
                        match = o.string("match"),
                        enabled = o.bool("enabled", true),
                        profileId = o.optString("profileId"),
                        playbackMode = PlaybackMode.entries.firstOrNull { it.name == o.optString("playbackMode") },
                        important = if (o.has("important")) o.bool("important") else null,
                        mute = if (o.has("mute")) o.bool("mute") else null,
                    )
                } catch (_: JsonException) {
                    null
                }
            }
        } catch (_: JsonException) {
            emptyList()
        }
    }
}
