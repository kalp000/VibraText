package com.example.vibratext.data

import com.example.vibratext.encoding.CaseMode
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.UnsupportedPolicy
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.json.JsonException
import com.example.vibratext.json.JsonObj
import com.example.vibratext.json.MiniJson
import com.example.vibratext.playback.RepeatSettings
import com.example.vibratext.playback.SpeedPreset
import com.example.vibratext.playback.SpeedSettings
import com.example.vibratext.playback.TimingSettings

sealed interface ProfileImportResult {
    data class Success(val profile: EncodingProfile) : ProfileImportResult
    data class Failure(val errors: List<String>) : ProfileImportResult
}

/**
 * JSON for profile export/import and for local storage.
 *
 * Import is deliberately strict and *data-only*: the parser can only produce plain values,
 * only known keys are read (everything else is ignored, never interpreted), every field is
 * type-checked, size is limited, the result goes through [EncodingProfile.validate], and the
 * imported profile always gets a fresh id so it can never overwrite or impersonate another.
 */
object ProfileJson {
    const val FORMAT = "vibratext-profile"
    const val VERSION = 1
    const val MAX_IMPORT_CHARS = 200_000

    fun toMap(p: EncodingProfile): Map<String, Any?> = linkedMapOf(
        "id" to p.id,
        "name" to p.name,
        "symbolMapping" to p.symbolMapping.digitToSymbol.map { it.name },
        "charCodes" to LinkedHashMap<String, Any?>().also { m -> p.charCodes.forEach { (c, code) -> m[c.toString()] = code.toLong() } },
        "caseMode" to p.caseMode.name,
        "stripAccents" to p.stripAccents,
        "unsupportedPolicy" to p.unsupportedPolicy.name,
        "replacementChar" to p.replacementChar.toString(),
        "timing" to linkedMapOf<String, Any?>(
            "shortPulseMs" to p.timing.shortPulseMs.toLong(),
            "longPulseMs" to p.timing.longPulseMs.toLong(),
            "doublePulseMs" to p.timing.doublePulseMs.toLong(),
            "triplePulseMs" to p.timing.triplePulseMs.toLong(),
            "pulseGapMs" to p.timing.pulseGapMs.toLong(),
            "symbolGapMs" to p.timing.symbolGapMs.toLong(),
            "charGapMs" to p.timing.charGapMs.toLong(),
            "wordGapMs" to p.timing.wordGapMs.toLong(),
            "startMarkerPauseMs" to p.timing.startMarkerPauseMs.toLong(),
            "separatorPauseMs" to p.timing.separatorPauseMs.toLong(),
            "endMarkerPauseMs" to p.timing.endMarkerPauseMs.toLong(),
            "completionTickMs" to p.timing.completionTickMs.toLong(),
        ),
        "speed" to linkedMapOf<String, Any?>("preset" to p.speed.preset.name, "customMultiplier" to p.speed.customMultiplier),
        "repeat" to linkedMapOf<String, Any?>(
            "count" to p.repeat.count.toLong(),
            "pauseMs" to p.repeat.pauseMs.toLong(),
            "repeatMarker" to p.repeat.repeatMarker,
        ),
        "intensityPercent" to p.intensityPercent.toLong(),
        "completionTick" to p.completionTick,
    )

    /** Shareable single-profile file. */
    fun export(profile: EncodingProfile, pretty: Boolean = true): String =
        MiniJson.stringify(
            linkedMapOf<String, Any?>(
                "format" to FORMAT,
                "version" to VERSION.toLong(),
                "profile" to toMap(profile).filterKeys { it != "id" },
            ),
            pretty,
        )

    /** Parses and validates an exported profile. The result gets [newId] as its id. */
    fun import(json: String, newId: String): ProfileImportResult {
        if (json.length > MAX_IMPORT_CHARS) return ProfileImportResult.Failure(listOf("The file is too large to be a profile."))
        return try {
            val root = JsonObj.of(MiniJson.parse(json))
            if (root.optString("format") != FORMAT) {
                return ProfileImportResult.Failure(listOf("This is not a VibraText profile file."))
            }
            val version = root.int("version", 0)
            if (version < 1 || version > VERSION) {
                return ProfileImportResult.Failure(listOf("Unsupported profile version $version (this app understands up to $VERSION)."))
            }
            val profile = fromObj(root.obj("profile") ?: throw JsonException("\$.profile is required"), newId)
            val errors = profile.validate()
            if (errors.isEmpty()) ProfileImportResult.Success(profile) else ProfileImportResult.Failure(errors)
        } catch (e: JsonException) {
            ProfileImportResult.Failure(listOf(e.message ?: "Invalid file."))
        } catch (e: IllegalArgumentException) {
            ProfileImportResult.Failure(listOf(e.message ?: "Invalid profile."))
        }
    }

    /** Reads one profile object. Missing fields take defaults; wrong types throw [JsonException]. */
    fun fromObj(o: JsonObj, id: String): EncodingProfile {
        val d = EncodingProfile(id = id, name = "")
        val mapping = o.list("symbolMapping")?.let { list ->
            val symbols = list.map { v ->
                val name = v as? String ?: throw JsonException("\$.symbolMapping entries must be strings")
                VibSymbol.entries.firstOrNull { it.name == name } ?: throw JsonException("Unknown symbol '$name' in symbolMapping")
            }
            SymbolMapping.validationError(symbols)?.let { throw JsonException(it) }
            SymbolMapping(symbols)
        } ?: d.symbolMapping

        val codes: Map<Char, Int> = o.obj("charCodes")?.let { c ->
            val m = LinkedHashMap<Char, Int>()
            for (key in c.keys) {
                if (key.length != 1) throw JsonException("Character table key '$key' must be exactly one character")
                m[key[0]] = c.int(key)
            }
            m
        } ?: d.charCodes

        val t = o.obj("timing")
        val timing = if (t == null) d.timing else TimingSettings(
            shortPulseMs = t.int("shortPulseMs", d.timing.shortPulseMs),
            longPulseMs = t.int("longPulseMs", d.timing.longPulseMs),
            doublePulseMs = t.int("doublePulseMs", d.timing.doublePulseMs),
            triplePulseMs = t.int("triplePulseMs", d.timing.triplePulseMs),
            pulseGapMs = t.int("pulseGapMs", d.timing.pulseGapMs),
            symbolGapMs = t.int("symbolGapMs", d.timing.symbolGapMs),
            charGapMs = t.int("charGapMs", d.timing.charGapMs),
            wordGapMs = t.int("wordGapMs", d.timing.wordGapMs),
            startMarkerPauseMs = t.int("startMarkerPauseMs", d.timing.startMarkerPauseMs),
            separatorPauseMs = t.int("separatorPauseMs", d.timing.separatorPauseMs),
            endMarkerPauseMs = t.int("endMarkerPauseMs", d.timing.endMarkerPauseMs),
            completionTickMs = t.int("completionTickMs", d.timing.completionTickMs),
        )
        val s = o.obj("speed")
        val speed = if (s == null) d.speed else SpeedSettings(
            preset = enumOf<SpeedPreset>(s.string("preset", d.speed.preset.name), "speed.preset"),
            customMultiplier = s.double("customMultiplier", d.speed.customMultiplier),
        )
        val r = o.obj("repeat")
        val repeat = if (r == null) d.repeat else RepeatSettings(
            count = r.int("count", d.repeat.count),
            pauseMs = r.int("pauseMs", d.repeat.pauseMs),
            repeatMarker = r.bool("repeatMarker", d.repeat.repeatMarker),
        )
        val replacement = o.string("replacementChar", d.replacementChar.toString())
        if (replacement.length != 1) throw JsonException("\$.replacementChar must be exactly one character")

        return EncodingProfile(
            id = id,
            name = o.string("name"),
            symbolMapping = mapping,
            charCodes = codes,
            caseMode = enumOf<CaseMode>(o.string("caseMode", d.caseMode.name), "caseMode"),
            stripAccents = o.bool("stripAccents", d.stripAccents),
            unsupportedPolicy = enumOf<UnsupportedPolicy>(o.string("unsupportedPolicy", d.unsupportedPolicy.name), "unsupportedPolicy"),
            replacementChar = replacement[0],
            timing = timing,
            speed = speed,
            repeat = repeat,
            intensityPercent = o.int("intensityPercent", d.intensityPercent),
            completionTick = o.bool("completionTick", d.completionTick),
        )
    }

    // ---- local storage of the whole book ----------------------------------------------------

    fun encodeBook(book: ProfileBook): String =
        MiniJson.stringify(
            linkedMapOf<String, Any?>(
                "version" to VERSION.toLong(),
                "default" to book.defaultProfileId,
                "profiles" to book.profiles.map { toMap(it) },
            ),
        )

    /** Tolerant: invalid stored profiles are skipped; if nothing usable remains the initial book is returned. */
    fun decodeBook(json: String?): ProfileBook {
        if (json.isNullOrBlank()) return ProfileBook.initial()
        return try {
            val root = JsonObj.of(MiniJson.parse(json))
            val profiles = (root.list("profiles") ?: emptyList()).mapNotNull { item ->
                try {
                    val o = JsonObj.of(item)
                    val p = fromObj(o, o.string("id"))
                    if (p.validate().isEmpty()) p else null
                } catch (_: JsonException) {
                    null
                }
            }.distinctBy { it.id }
            if (profiles.isEmpty()) return ProfileBook.initial()
            val default = root.optString("default")?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
            ProfileBook(profiles, default)
        } catch (_: JsonException) {
            ProfileBook.initial()
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String, field: String): E =
        enumValues<E>().firstOrNull { it.name == name } ?: throw JsonException("Unknown value '$name' for $field")
}
