package com.example.vibratext.app

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.vibratext.app.notification.NotificationExtractor
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.notification.NotificationContext
import com.example.vibratext.notification.NotificationParser
import com.example.vibratext.notification.RejectReason
import com.example.vibratext.pipeline.NotificationProcessor
import com.example.vibratext.pipeline.ProcessResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds real Android Notification objects (the same kinds of payload messaging apps post) and
 * checks that the extractor and the core pipeline read them correctly. These run on a device or
 * emulator. They do NOT involve WhatsApp, Signal or any other real app.
 */
@RunWith(AndroidJUnit4::class)
class NotificationExtractorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun extract(n: Notification, pkg: String = "org.example.chat", importance: Int? = 3) =
        NotificationExtractor.extract(n, pkg, "key-1", 1, 1_000L, importance)

    private fun builder() = NotificationCompat.Builder(context, "test-channel").setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun groupNotification(): Notification {
        val me = Person.Builder().setName("Me").setKey("me").build()
        val alex = Person.Builder().setName("Alex").setKey("alex").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Family")
            .setGroupConversation(true)
            .addMessage("Dinner at eight?", 100L, alex)
            .addMessage("Sounds good", 200L, me)
            .addMessage("Bring bread", 300L, alex)
        return builder().setStyle(style).setCategory(Notification.CATEGORY_MESSAGE).build()
    }

    @Test
    fun messagingStyleExposesSendersTextsAndWhoWroteThem() {
        val raw = extract(groupNotification())
        assertTrue(raw.isMessagingStyle)
        assertTrue(raw.isGroupConversation)
        assertEquals("Family", raw.conversationTitle)
        assertEquals(3, raw.messages.size)
        assertEquals("Alex", raw.messages[0].sender)
        assertFalse(raw.messages[0].fromSelf)
        assertTrue(raw.messages[1].fromSelf)
        assertEquals("Bring bread", raw.messages[2].text)
        assertEquals(Notification.CATEGORY_MESSAGE, raw.category)
    }

    @Test
    fun theCorePipelineReadsTheLatestIncomingMessageAndSenderFromARealNotification() {
        val raw = extract(groupNotification())
        val parsed = NotificationParser.parse(raw, "Chat")
        assertEquals("Alex", parsed.senderName)
        assertEquals("Bring bread", parsed.messageText)
        assertTrue(parsed.isGroupConversation)
    }

    @Test
    fun oneToOneMessagingNotificationWithoutPersonStillGivesAMessage() {
        val me = Person.Builder().setName("Me").build()
        val style = NotificationCompat.MessagingStyle(me).addMessage("Hello, are you free?", 50L, Person.Builder().setName("Alex").build())
        val raw = extract(builder().setContentTitle("Alex").setStyle(style).build())
        val parsed = NotificationParser.parse(raw, "Chat")
        assertEquals("Alex", parsed.senderName)
        assertEquals("Hello, are you free?", parsed.messageText)
    }

    @Test
    fun ordinaryNotificationExposesTitleTextAndBigText() {
        val n = builder()
            .setContentTitle("Weather")
            .setContentText("Rain later")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Rain later this afternoon, bring an umbrella"))
            .build()
        val raw = extract(n)
        assertFalse(raw.isMessagingStyle)
        assertEquals("Weather", raw.title)
        assertEquals("Rain later", raw.text)
        assertEquals("Rain later this afternoon, bring an umbrella", raw.bigText)
        assertTrue(raw.messages.isEmpty())
    }

    @Test
    fun flagsAndImportanceAreReflected() {
        val ongoing = extract(builder().setContentTitle("Music").setOngoing(true).build())
        assertTrue(ongoing.isOngoing)
        val summary = extract(builder().setContentTitle("5 messages").setGroup("g").setGroupSummary(true).build())
        assertTrue(summary.isGroupSummary)
        val silent = extract(builder().setContentTitle("Quiet").build(), importance = 2)
        assertTrue(silent.isSilent)
        val loud = extract(builder().setContentTitle("Loud").build(), importance = 4)
        assertFalse(loud.isSilent)
        assertEquals(4, loud.importance)
        assertNull(extract(builder().setContentTitle("Unknown").build(), importance = null).importance)
    }

    @Test
    fun anEmptyNotificationHasNoReadableContent() {
        val raw = extract(builder().build())
        assertFalse(NotificationParser.hasReadableContent(raw))
    }

    @Test
    fun endToEndANotificationFromASelectedAppBecomesAPlaybackRequestAndOthersAreIgnored() {
        val processor = NotificationProcessor()
        val settings = AppSettings(monitoringEnabled = true, selectedPackages = setOf("org.example.chat"))
        val ctx = NotificationContext(ownPackage = "com.example.vibratext", deviceInUse = false, nowMs = 1_000L)
        val book = ProfileBook.initial()

        val selected = extract(groupNotification(), pkg = "org.example.chat")
        val result = processor.process(selected, "Chat", settings, emptyList(), book, ctx)
        assertTrue(result is ProcessResult.Play)
        val play = result as ProcessResult.Play
        assertTrue(play.request.script.totalDurationMs > 0)
        assertEquals("Alex", play.request.meta.senderName)

        val other = extract(groupNotification(), pkg = "org.example.other")
        val ignored = processor.process(other, "Other", settings, emptyList(), book, ctx)
        assertEquals(RejectReason.NOT_SELECTED, (ignored as ProcessResult.Ignored).reason)

        val own = extract(groupNotification(), pkg = "com.example.vibratext")
        val ownResult = processor.process(own, "VibraText", settings.copy(selectedPackages = setOf("com.example.vibratext")), emptyList(), book, ctx)
        assertEquals(RejectReason.OWN_APP, (ownResult as ProcessResult.Ignored).reason)
    }
}
