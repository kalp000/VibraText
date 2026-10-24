package com.example.vibratext.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.HistoryEntry
import com.example.vibratext.data.HistoryJson
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.data.ProfileJson
import com.example.vibratext.data.SettingsJson
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.RulesJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.vibraTextDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vibratext",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * All persistent data, stored only on this device in one DataStore file. Each value is one JSON
 * document produced and parsed by the (unit-tested) codecs in :core, which fall back to safe
 * defaults for anything missing or damaged, so a corrupt value can never crash the app or the
 * notification service.
 *
 * Every update runs inside DataStore's own transaction (`edit`), so two writers (for example the
 * UI saving a setting while the service records a history entry) can never overwrite each other.
 */
class LocalStore(context: Context) {
    private val store = context.applicationContext.vibraTextDataStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = data.map { SettingsJson.decode(it[SETTINGS]) }.distinctUntilChanged()
    val profiles: Flow<ProfileBook> = data.map { ProfileJson.decodeBook(it[PROFILES]) }.distinctUntilChanged()
    val rules: Flow<List<NotificationRule>> = data.map { RulesJson.decode(it[RULES]) }.distinctUntilChanged()
    val history: Flow<List<HistoryEntry>> = data.map { HistoryJson.decode(it[HISTORY]) }.distinctUntilChanged()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(SettingsJson.decode(prefs[SETTINGS])).sanitized()
            prefs[SETTINGS] = SettingsJson.encode(updated)
        }
    }

    suspend fun updateProfiles(transform: (ProfileBook) -> ProfileBook) {
        store.edit { prefs ->
            prefs[PROFILES] = ProfileJson.encodeBook(transform(ProfileJson.decodeBook(prefs[PROFILES])))
        }
    }

    suspend fun updateRules(transform: (List<NotificationRule>) -> List<NotificationRule>) {
        store.edit { prefs ->
            prefs[RULES] = RulesJson.encode(transform(RulesJson.decode(prefs[RULES])))
        }
    }

    /** Settings and rules in one transaction (e.g. when an app is removed from the monitored list). */
    suspend fun updateSettingsAndRules(
        settingsTransform: (AppSettings) -> AppSettings,
        rulesTransform: (List<NotificationRule>) -> List<NotificationRule>,
    ) {
        store.edit { prefs ->
            prefs[SETTINGS] = SettingsJson.encode(settingsTransform(SettingsJson.decode(prefs[SETTINGS])).sanitized())
            prefs[RULES] = RulesJson.encode(rulesTransform(RulesJson.decode(prefs[RULES])))
        }
    }

    /** Profiles and rules in one transaction (deleting a profile also clears rules that used it). */
    suspend fun updateProfilesAndRules(
        profilesTransform: (ProfileBook) -> ProfileBook,
        rulesTransform: (List<NotificationRule>) -> List<NotificationRule>,
    ) {
        store.edit { prefs ->
            prefs[PROFILES] = ProfileJson.encodeBook(profilesTransform(ProfileJson.decodeBook(prefs[PROFILES])))
            prefs[RULES] = RulesJson.encode(rulesTransform(RulesJson.decode(prefs[RULES])))
        }
    }

    suspend fun addHistory(entry: HistoryEntry, maxEntries: Int) {
        store.edit { prefs ->
            val list = HistoryJson.decode(prefs[HISTORY])
            prefs[HISTORY] = HistoryJson.encode(HistoryJson.add(list, entry, maxEntries))
        }
    }

    suspend fun deleteHistoryEntry(id: Long) {
        store.edit { prefs ->
            prefs[HISTORY] = HistoryJson.encode(HistoryJson.decode(prefs[HISTORY]).filterNot { it.id == id })
        }
    }

    /** Shortens stored history when the user lowers the limit. */
    suspend fun trimHistory(maxEntries: Int) {
        store.edit { prefs ->
            val list = HistoryJson.decode(prefs[HISTORY])
            if (list.size > maxEntries) prefs[HISTORY] = HistoryJson.encode(list.take(maxEntries))
        }
    }

    suspend fun clearHistory() {
        store.edit { it.remove(HISTORY) }
    }

    /** Deletes everything this app has stored (settings, profiles, rules, history). */
    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private companion object {
        val SETTINGS = stringPreferencesKey("settings_v1")
        val PROFILES = stringPreferencesKey("profiles_v1")
        val RULES = stringPreferencesKey("rules_v1")
        val HISTORY = stringPreferencesKey("history_v1")
    }
}
