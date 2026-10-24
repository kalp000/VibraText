package com.example.vibratext.data

import com.example.vibratext.encoding.CaseMode
import com.example.vibratext.encoding.CharacterDecoder
import com.example.vibratext.encoding.CharacterEncoder
import com.example.vibratext.encoding.EncodingOptions
import com.example.vibratext.encoding.EncodingTable
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.UnsupportedPolicy
import com.example.vibratext.playback.RepeatSettings
import com.example.vibratext.playback.SpeedSettings
import com.example.vibratext.playback.TimingSettings

/**
 * Everything that defines "how text feels": symbol mapping, character table, case handling,
 * timing, speed, repetition and intensity. Profiles are plain data, stored locally as JSON.
 */
data class EncodingProfile(
    val id: String,
    val name: String,
    val symbolMapping: SymbolMapping = SymbolMapping.DEFAULT,
    val charCodes: Map<Char, Int> = EncodingTable.defaultCharCodes(),
    val caseMode: CaseMode = CaseMode.NORMALIZE,
    val stripAccents: Boolean = true,
    val unsupportedPolicy: UnsupportedPolicy = UnsupportedPolicy.REPLACE,
    val replacementChar: Char = '?',
    val timing: TimingSettings = TimingSettings.DEFAULT,
    val speed: SpeedSettings = SpeedSettings(),
    val repeat: RepeatSettings = RepeatSettings(),
    /** 1..100. Only effective on vibrators with amplitude control. */
    val intensityPercent: Int = 100,
    val completionTick: Boolean = true,
) {
    /** All problems with this profile; empty means it is safe to save and use. */
    fun validate(): List<String> {
        val e = mutableListOf<String>()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) e += "The profile needs a name."
        if (trimmed.length > MAX_NAME_LENGTH) e += "The profile name may be at most $MAX_NAME_LENGTH characters."
        if (name.any { it.isISOControl() }) e += "The profile name contains invalid characters."
        e += EncodingTable.validate(charCodes)
        if (EncodingTable.validate(charCodes).isEmpty() && unsupportedPolicy == UnsupportedPolicy.REPLACE && !charCodes.containsKey(replacementChar)) {
            e += "The replacement character '$replacementChar' is not in the character table."
        }
        e += timing.validate()
        e += speed.validate()
        e += repeat.validate()
        if (intensityPercent !in 1..100) e += "Intensity must be between 1 and 100 percent."
        return e
    }

    fun table(): EncodingTable = EncodingTable.create(charCodes)

    fun encoder(): CharacterEncoder =
        CharacterEncoder(table(), EncodingOptions(caseMode, stripAccents, unsupportedPolicy, replacementChar))

    fun decoder(): CharacterDecoder = CharacterDecoder(table(), symbolMapping)

    /** Timing after applying the speed preset / multiplier. */
    fun effectiveTiming(): TimingSettings = timing.scaled(speed.multiplier)

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}

/** The set of profiles plus which one is the global default. Immutable; operations return a new book. */
data class ProfileBook(val profiles: List<EncodingProfile>, val defaultProfileId: String) {
    init {
        require(profiles.isNotEmpty()) { "At least one profile is required" }
        require(profiles.any { it.id == defaultProfileId }) { "Default profile does not exist" }
        require(profiles.map { it.id }.toSet().size == profiles.size) { "Profile ids must be unique" }
    }

    val default: EncodingProfile get() = profiles.first { it.id == defaultProfileId }

    fun byId(id: String?): EncodingProfile? = if (id == null) null else profiles.firstOrNull { it.id == id }

    /** The profile with [id], or the default if it no longer exists. */
    fun resolve(id: String?): EncodingProfile = byId(id) ?: default

    fun upsert(profile: EncodingProfile): ProfileBook {
        val exists = profiles.any { it.id == profile.id }
        val list = if (exists) profiles.map { if (it.id == profile.id) profile else it } else profiles + profile
        return copy(profiles = list)
    }

    /** Deleting the last profile is not allowed; deleting the default promotes the first remaining one. */
    fun delete(id: String): ProfileBook {
        if (profiles.size <= 1 || profiles.none { it.id == id }) return this
        val remaining = profiles.filter { it.id != id }
        return ProfileBook(remaining, if (defaultProfileId == id) remaining.first().id else defaultProfileId)
    }

    fun setDefault(id: String): ProfileBook = if (profiles.any { it.id == id }) copy(defaultProfileId = id) else this

    fun rename(id: String, newName: String): ProfileBook =
        byId(id)?.let { upsert(it.copy(name = newName)) } ?: this

    fun duplicate(id: String, newId: String): ProfileBook {
        val src = byId(id) ?: return this
        return upsert(src.copy(id = newId, name = uniqueName("${src.name} copy")))
    }

    /** [base] if no profile has it, else "base 2", "base 3" … (names are compared ignoring case). */
    fun uniqueName(base: String): String {
        val taken = profiles.map { it.name.trim().lowercase() }.toSet()
        val trimmed = base.trim().take(EncodingProfile.MAX_NAME_LENGTH)
        if (trimmed.lowercase() !in taken) return trimmed
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = trimmed.take(EncodingProfile.MAX_NAME_LENGTH - suffix.length) + suffix
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    companion object {
        fun initial(): ProfileBook = ProfileBook(listOf(ProfileTemplates.defaultEnglish()), ProfileTemplates.DEFAULT_ID)
    }
}

object ProfileTemplates {
    const val DEFAULT_ID = "default"

    class Template(val key: String, val label: String, val description: String, val build: (id: String) -> EncodingProfile)

    fun defaultEnglish(id: String = DEFAULT_ID) = EncodingProfile(id = id, name = "Default English")

    val all: List<Template> = listOf(
        Template("default", "Default English", "Specification defaults: short 150 ms, long 500 ms, normal speed, played once.") {
            defaultEnglish(it)
        },
        Template("quiet", "Quiet Notifications", "Same code, but at 40 % intensity (needs a vibrator with amplitude control).") {
            EncodingProfile(id = it, name = "Quiet Notifications", intensityPercent = 40)
        },
        Template("fast", "Fast Messages", "Everything 25 % faster.") {
            EncodingProfile(id = it, name = "Fast Messages", speed = SpeedSettings(com.example.vibratext.playback.SpeedPreset.FAST))
        },
        Template("important", "Important Contacts", "Played twice with a marker between the repetitions.") {
            EncodingProfile(id = it, name = "Important Contacts", repeat = RepeatSettings(count = 2, pauseMs = 2500, repeatMarker = true))
        },
        Template("emergency", "Emergency Alerts", "Played three times at full intensity with repetition markers.") {
            EncodingProfile(id = it, name = "Emergency Alerts", repeat = RepeatSettings(count = 3, pauseMs = 1500, repeatMarker = true), intensityPercent = 100)
        },
        Template("case", "Case-sensitive", "Capital letters are sent with a case marker, so decoding keeps them.") {
            EncodingProfile(id = it, name = "Case-sensitive", caseMode = CaseMode.PRESERVE)
        },
    )
}
