package com.example.vibratext.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.vibratext.app.data.LocalStore
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.HistoryEntry
import com.example.vibratext.data.HistoryStatus
import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.data.ProfileTemplates
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.RuleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Persistence on a real device: what is written can be read back, and concurrent writers do not lose updates. */
@RunWith(AndroidJUnit4::class)
class LocalStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = LocalStore(context)

    @Before
    fun clean() = runBlocking { store.clearAll() }

    @After
    fun cleanUp() = runBlocking { store.clearAll() }

    @Test
    fun defaultsAreSafe() = runBlocking {
        val s = store.settings.first()
        assertFalse("the app must never vibrate before the user enables monitoring", s.monitoringEnabled)
        assertTrue(s.selectedPackages.isEmpty())
        assertEquals(1, store.profiles.first().profiles.size)
        assertTrue(store.rules.first().isEmpty())
        assertTrue(store.history.first().isEmpty())
    }

    @Test
    fun settingsPersist() = runBlocking {
        store.updateSettings { it.copy(monitoringEnabled = true, selectedPackages = setOf("a", "b"), maxMessageChars = 33, playbackMode = PlaybackMode.SENDER_ONLY) }
        val s = store.settings.first()
        assertTrue(s.monitoringEnabled)
        assertEquals(setOf("a", "b"), s.selectedPackages)
        assertEquals(33, s.maxMessageChars)
        assertEquals(PlaybackMode.SENDER_ONLY, s.playbackMode)
    }

    @Test
    fun invalidSettingsAreClampedBeforeTheyAreSaved() = runBlocking {
        store.updateSettings { it.copy(maxMessageChars = 100_000, minIntervalMs = -5) }
        val s = store.settings.first()
        assertEquals(AppSettings.MESSAGE_CHARS.last, s.maxMessageChars)
        assertEquals(0L, s.minIntervalMs)
    }

    @Test
    fun profilesWithACustomMappingAndCharacterCodePersist() = runBlocking {
        val swapped = SymbolMapping(listOf(VibSymbol.LONG, VibSymbol.SHORT, VibSymbol.TRIPLE, VibSymbol.DOUBLE))
        store.updateProfiles { book ->
            val p = ProfileTemplates.defaultEnglish("custom").copy(name = "Custom", symbolMapping = swapped)
            book.upsert(p)
        }
        val book = store.profiles.first()
        assertEquals(2, book.profiles.size)
        assertEquals(swapped, book.byId("custom")!!.symbolMapping)
    }

    @Test
    fun rulesPersist() = runBlocking {
        val rule = NotificationRule("r1", RuleScope.CONTACT, "Alex", important = true, playbackMode = PlaybackMode.FULL_MESSAGE)
        store.updateRules { it + rule }
        assertEquals(listOf(rule), store.rules.first())
    }

    @Test
    fun historyIsBoundedNewestFirstAndCanBeCleared() = runBlocking {
        for (i in 1..12) {
            store.addHistory(HistoryEntry(i.toLong(), i * 10L, "App", "pkg", "S", "m$i", "Default", HistoryStatus.COMPLETED, false), 10)
        }
        val list = store.history.first()
        assertEquals(10, list.size)
        assertEquals(12L, list.first().id)
        store.deleteHistoryEntry(12L)
        assertEquals(11L, store.history.first().first().id)
        store.clearHistory()
        assertTrue(store.history.first().isEmpty())
    }

    @Test
    fun concurrentWritersNeverLoseAnUpdate() = runBlocking {
        val jobs = (1..40).map { i ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                store.addHistory(HistoryEntry(i.toLong(), i.toLong(), "App", "pkg", null, "x", null, HistoryStatus.COMPLETED, false), 1000)
            }
        }
        jobs.awaitAll()
        assertEquals(40, store.history.first().size)
    }
}
