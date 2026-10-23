package com.example.vibratext.notification

/**
 * User templates such as "{sender}: {message}" or "Message from {sender}. {message}".
 *
 * Supported placeholders: {app} {sender} {message} {time}.
 *
 * Missing data is never invented. If the template uses {sender} or {message} and that value is
 * not exposed by the notification, a documented fallback template is used instead:
 *   sender missing  → "{app}: {message}"   (the app name stands in, as the "unknown sender" rule says)
 *   message missing → "{app}: {sender}"
 *   both missing    → "{app}"
 */
object TemplateRenderer {
    val PLACEHOLDERS = listOf("app", "sender", "message", "time")
    const val MAX_TEMPLATE_LENGTH = 120
    const val FALLBACK_NO_SENDER = "{app}: {message}"
    const val FALLBACK_NO_MESSAGE = "{app}: {sender}"
    const val FALLBACK_NOTHING = "{app}"

    private val token = Regex("\\{([^{}]*)\\}")

    data class Values(val app: String, val sender: String?, val message: String?, val time: String)

    fun usedPlaceholders(template: String): Set<String> =
        token.findAll(template).map { it.groupValues[1] }.toSet()

    /** Human-readable problems; empty means the template can be saved. */
    fun validate(template: String): List<String> {
        val e = mutableListOf<String>()
        if (template.isBlank()) return listOf("The template is empty.")
        if (template.length > MAX_TEMPLATE_LENGTH) e += "The template may be at most $MAX_TEMPLATE_LENGTH characters."
        val supported = PLACEHOLDERS.joinToString { "{$it}" }
        for (name in usedPlaceholders(template)) {
            if (name !in PLACEHOLDERS) e += "Unknown placeholder {$name}. Supported: $supported."
        }
        val withoutTokens = token.replace(template, "")
        if (withoutTokens.contains('{') || withoutTokens.contains('}')) e += "The template has an unmatched brace."
        val used = usedPlaceholders(template)
        if ("sender" !in used && "message" !in used && "app" !in used) e += "The template must contain {sender}, {message} or {app}."
        return e
    }

    fun render(template: String, values: Values): String {
        val used = usedPlaceholders(template)
        val senderMissing = "sender" in used && values.sender.isNullOrBlank()
        val messageMissing = "message" in used && values.message.isNullOrBlank()
        val chosen = when {
            !senderMissing && !messageMissing -> template
            senderMissing && messageMissing -> FALLBACK_NOTHING
            senderMissing -> if (values.message.isNullOrBlank()) FALLBACK_NOTHING else FALLBACK_NO_SENDER
            else -> if (values.sender.isNullOrBlank()) FALLBACK_NOTHING else FALLBACK_NO_MESSAGE
        }
        val out = token.replace(chosen) { m ->
            when (m.groupValues[1]) {
                "app" -> values.app
                "sender" -> values.sender.orEmpty()
                "message" -> values.message.orEmpty()
                "time" -> values.time
                else -> "" // unknown placeholders are dropped, never echoed
            }
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }
}
