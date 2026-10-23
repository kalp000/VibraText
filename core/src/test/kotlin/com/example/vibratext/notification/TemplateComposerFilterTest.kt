package com.example.vibratext.notification

import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.LongMessagePolicy
import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.data.UnknownSenderBehavior
import com.example.vibratext.encoding.MessageSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TemplateComposerFilterTest {

    // ---- templates ----------------------------------------------------------------------------

    private val values = TemplateRenderer.Values("WhatsApp", "Alex", "Hello", "09:05")

    @Test
    fun templatesRenderAllPlaceholders() {
        assertEquals("Alex: Hello", TemplateRenderer.render("{sender}: {message}", values))
        assertEquals("WhatsApp notification. Alex: Hello", TemplateRenderer.render("{app} notification. {sender}: {message}", values))
        assertEquals("Message from Alex. Hello", TemplateRenderer.render("Message from {sender}. {message}", values))
        assertEquals("Alex sent a message.", TemplateRenderer.render("{sender} sent a message.", values))
        assertEquals("09:05 Alex", TemplateRenderer.render("{time} {sender}", values))
    }

    @Test
    fun missingFieldsUseDocumentedFallbacksInsteadOfInventingData() {
        val t = "Message from {sender}. {message}"
        assertEquals("WhatsApp: Hello", TemplateRenderer.render(t, values.copy(sender = null)))
        assertEquals("WhatsApp: Alex", TemplateRenderer.render(t, values.copy(message = " ")))
        assertEquals("WhatsApp", TemplateRenderer.render(t, values.copy(sender = null, message = null)))
        // a template that does not use the missing field is unaffected
        assertEquals("Hello", TemplateRenderer.render("{message}", values.copy(sender = null)))
        assertEquals("Alex sent a message.", TemplateRenderer.render("{sender} sent a message.", values.copy(message = null)))
    }

    @Test
    fun templateValidationExplainsProblems() {
        assertTrue(TemplateRenderer.validate("{sender}: {message}").isEmpty())
        assertTrue(TemplateRenderer.validate("").isNotEmpty())
        assertTrue(TemplateRenderer.validate("{who} said {message}").any { it.contains("Unknown placeholder {who}") })
        assertTrue(TemplateRenderer.validate("{sender: {message}").isNotEmpty())
        assertTrue(TemplateRenderer.validate("just words").any { it.contains("must contain") })
        assertTrue(TemplateRenderer.validate("{message}".repeat(30)).any { it.contains("at most") })
        assertTrue(TemplateRenderer.validate("{time} only").any { it.contains("must contain") })
    }

    // ---- composer -----------------------------------------------------------------------------

    private fun parsed(
        sender: String? = "Alex",
        message: String? = "Hello.",
        app: String = "WhatsApp",
        summary: String? = null,
    ) = ParsedNotification("com.whatsapp", app, sender, message, null, 1_700_000_000_000, "k", "msg", summary, SenderSource.MESSAGING_STYLE)

    private val settings = AppSettings()

    private fun texts(m: ComposedMessage) = m.fields.map { it.text }
    private fun sections(m: ComposedMessage) = m.fields.map { it.section }

    @Test
    fun fullMessageModePlaysSenderThenMessage() {
        val m = MessageComposer.compose(parsed(), PlaybackMode.FULL_MESSAGE, settings)
        assertEquals(listOf("Alex", "Hello."), texts(m))
        assertEquals(listOf(MessageSection.SENDER, MessageSection.MESSAGE), sections(m))
        assertFalse(m.truncated)
        assertEquals("Alex: Hello.", m.preview)
    }

    @Test
    fun senderOnlyMessageOnlyAndAppSenderMessageModes() {
        assertEquals(listOf("Alex"), texts(MessageComposer.compose(parsed(), PlaybackMode.SENDER_ONLY, settings)))
        assertEquals(listOf("Hello."), texts(MessageComposer.compose(parsed(), PlaybackMode.MESSAGE_ONLY, settings)))
        val asm = MessageComposer.compose(parsed(), PlaybackMode.APP_SENDER_MESSAGE, settings)
        assertEquals(listOf("WhatsApp", "Alex", "Hello."), texts(asm))
        assertEquals(listOf(MessageSection.APP, MessageSection.SENDER, MessageSection.MESSAGE), sections(asm))
    }

    @Test
    fun templateModePlaysOneTextField() {
        val s = settings.copy(template = "{sender} says {message} at {time}")
        val m = MessageComposer.compose(parsed(), PlaybackMode.TEMPLATE, s, ZoneOffset.UTC)
        assertEquals(listOf("Alex says Hello. at 22:13"), texts(m))
        assertEquals(MessageSection.TEXT, m.fields.single().section)
    }

    @Test
    fun unknownSenderPlaysTheAppNameOrAnEmptyFieldAsConfigured() {
        val noSender = parsed(sender = null)
        assertEquals(listOf("WhatsApp", "Hello."), texts(MessageComposer.compose(noSender, PlaybackMode.FULL_MESSAGE, settings)))
        val empty = settings.copy(unknownSenderBehavior = UnknownSenderBehavior.EMPTY_SENDER_FIELD)
        assertEquals(listOf("", "Hello."), texts(MessageComposer.compose(noSender, PlaybackMode.FULL_MESSAGE, empty)))
        // app + sender + message keeps field positions: the sender slot stays empty
        assertEquals(listOf("WhatsApp", "", "Hello."), texts(MessageComposer.compose(noSender, PlaybackMode.APP_SENDER_MESSAGE, settings)))
        // sender-only without a sender plays the app name, labelled as the app
        val so = MessageComposer.compose(noSender, PlaybackMode.SENDER_ONLY, settings)
        assertEquals(listOf("WhatsApp"), texts(so))
        assertEquals(MessageSection.APP, so.fields.single().section)
    }

    @Test
    fun notificationsWithoutMessageTextPlayOnlyWhatIsExposed() {
        assertEquals(listOf("Alex"), texts(MessageComposer.compose(parsed(message = null), PlaybackMode.FULL_MESSAGE, settings)))
        assertEquals(listOf("WhatsApp"), texts(MessageComposer.compose(parsed(sender = null, message = null), PlaybackMode.FULL_MESSAGE, settings)))
        assertEquals(listOf("Alex"), texts(MessageComposer.compose(parsed(message = null), PlaybackMode.MESSAGE_ONLY, settings)))
        assertEquals(listOf("WhatsApp", "Alex"), texts(MessageComposer.compose(parsed(message = null), PlaybackMode.APP_SENDER_MESSAGE, settings)))
    }

    private val longText = "This is a rather long message that goes on and on and on for quite a while"

    @Test
    fun longMessagesAreTruncatedVisiblyNeverSilently() {
        val s = settings.copy(maxMessageChars = 20, longMessagePolicy = LongMessagePolicy.FIRST_N)
        val m = MessageComposer.compose(parsed(message = longText), PlaybackMode.FULL_MESSAGE, s)
        assertEquals("This is a rather lon", texts(m)[1])
        assertTrue(m.truncated)
        assertTrue(m.fields[1].truncated) // TRUNCATED marker will be played
        assertFalse(m.fields[0].truncated)
        assertEquals(longText.length, m.originalMessageLength)

        val noMarker = MessageComposer.compose(parsed(message = longText), PlaybackMode.FULL_MESSAGE, s.copy(playTruncationMarker = false))
        assertTrue(noMarker.truncated) // still reported to the UI
        assertFalse(noMarker.fields[1].truncated)
    }

    @Test
    fun longMessagePoliciesBehaveAsDocumented() {
        val base = settings.copy(maxMessageChars = 20)
        val full = MessageComposer.compose(parsed(message = longText), PlaybackMode.FULL_MESSAGE, base.copy(longMessagePolicy = LongMessagePolicy.PLAY_FULL))
        assertEquals(longText, texts(full)[1])
        assertFalse(full.truncated)

        val senderOnly = MessageComposer.compose(parsed(message = longText), PlaybackMode.FULL_MESSAGE, base.copy(longMessagePolicy = LongMessagePolicy.SENDER_ONLY))
        assertEquals(listOf("Alex"), texts(senderOnly))
        assertTrue(senderOnly.truncated)
        assertTrue(senderOnly.fields.single().truncated) // tells the listener a message was left out

        val summary = MessageComposer.compose(parsed(message = longText, summary = "12 new messages"), PlaybackMode.FULL_MESSAGE, base.copy(longMessagePolicy = LongMessagePolicy.SUMMARY))
        assertEquals("12 new messages", texts(summary)[1])
        assertTrue(summary.truncated)
        val noSummary = MessageComposer.compose(parsed(message = longText), PlaybackMode.FULL_MESSAGE, base.copy(longMessagePolicy = LongMessagePolicy.SUMMARY))
        assertEquals("This is a rather lon", texts(noSummary)[1])
    }

    @Test
    fun hardCapStillAppliesToPlayFull() {
        val huge = "x".repeat(900)
        val m = MessageComposer.compose(parsed(message = huge), PlaybackMode.FULL_MESSAGE, settings.copy(maxMessageChars = 500, longMessagePolicy = LongMessagePolicy.PLAY_FULL))
        assertEquals(500, texts(m)[1].length)
        assertTrue(m.truncated)
    }

    @Test
    fun longSenderAndAppNamesAreShortened() {
        val m = MessageComposer.compose(parsed(sender = "A".repeat(40), app = "Some Very Long Application Name"), PlaybackMode.APP_SENDER_MESSAGE, settings.copy(maxSenderChars = 10))
        assertEquals(12, texts(m)[0].length)
        assertEquals(10, texts(m)[1].length)
        assertTrue(m.fields[1].truncated)
        assertTrue(m.truncated)
    }

    @Test
    fun cuttingNeverSplitsASurrogatePair() {
        val m = MessageComposer.compose(parsed(message = "ab😀cd"), PlaybackMode.MESSAGE_ONLY, settings.copy(maxMessageChars = 3))
        assertEquals("ab", texts(m).single())
    }

    @Test
    fun combinedAnnouncementIsShortAndExplicit() {
        val m = MessageComposer.composeCombined(parsed(), 4)
        assertEquals(listOf("WhatsApp", "4 new"), texts(m))
    }

    // ---- filter -------------------------------------------------------------------------------

    private fun raw(pkg: String = "com.whatsapp", configure: (RawNotification) -> RawNotification = { it }) =
        configure(RawNotification(packageName = pkg, key = "k", title = "Alex", text = "Hi"))

    private val on = AppSettings(monitoringEnabled = true, selectedPackages = setOf("com.whatsapp", "org.thoughtcrime.securesms"))
    private val ctx = NotificationContext(ownPackage = "com.example.vibratext", deviceInUse = false, nowMs = 1000)

    private fun check(r: RawNotification, s: AppSettings = on, c: NotificationContext = ctx) = NotificationFilter.check(r, s, c)

    @Test
    fun acceptsSelectedAppsAndRejectsOthers() {
        assertNull(check(raw()))
        assertNull(check(raw("org.thoughtcrime.securesms")))
        assertEquals(RejectReason.NOT_SELECTED, check(raw("com.instagram.android")))
        assertEquals(RejectReason.MONITORING_OFF, check(raw(), on.copy(monitoringEnabled = false)))
    }

    @Test
    fun ignoresVibraTextItselfEvenIfSelected() {
        val s = on.copy(selectedPackages = on.selectedPackages + "com.example.vibratext")
        assertEquals(RejectReason.OWN_APP, check(raw("com.example.vibratext"), s))
    }

    @Test
    fun ongoingGroupSummarySilentAndEmptyNotificationsAreIgnoredByDefault() {
        assertEquals(RejectReason.ONGOING, check(raw { it.copy(isOngoing = true) }))
        assertNull(check(raw { it.copy(isOngoing = true) }, on.copy(playOngoing = true)))
        assertEquals(RejectReason.GROUP_SUMMARY, check(raw { it.copy(isGroupSummary = true) }))
        assertNull(check(raw { it.copy(isGroupSummary = true) }, on.copy(playGroupSummaries = true)))
        assertEquals(RejectReason.SILENT, check(raw { it.copy(isSilent = true) }))
        assertNull(check(raw { it.copy(isSilent = true) }, on.copy(playSilent = true)))
        assertEquals(RejectReason.NO_READABLE_CONTENT, check(raw { it.copy(title = null, text = null) }))
        assertNull(check(raw { it.copy(title = null, text = null) }, on.copy(requireReadableContent = false)))
    }

    @Test
    fun screenStateDndAndCallSettingsAreRespected() {
        val locked = ctx.copy(deviceInUse = false)
        val inUse = ctx.copy(deviceInUse = true)
        assertEquals(RejectReason.SCREEN_LOCKED, check(raw(), on.copy(playWhileLocked = false), locked))
        assertNull(check(raw(), on.copy(playWhileLocked = false), inUse))
        assertEquals(RejectReason.SCREEN_UNLOCKED, check(raw(), on.copy(playWhileUnlocked = false), inUse))
        assertNull(check(raw(), on.copy(playWhileUnlocked = false), locked))
        assertEquals(RejectReason.DO_NOT_DISTURB, check(raw(), on.copy(ignoreWhenDnd = true), ctx.copy(doNotDisturbActive = true)))
        assertNull(check(raw(), on, ctx.copy(doNotDisturbActive = true)))
        assertEquals(RejectReason.IN_CALL, check(raw(), on, ctx.copy(inCall = true)))
        assertNull(check(raw(), on.copy(pauseDuringCalls = false), ctx.copy(inCall = true)))
    }

    // ---- deduplicator -------------------------------------------------------------------------

    @Test
    fun duplicateEventsAreFilteredWithinTheWindowOnly() {
        val d = NotificationDeduplicator()
        assertFalse(d.isDuplicate("a", 1_000, 5_000))
        assertTrue(d.isDuplicate("a", 2_000, 5_000))
        assertFalse(d.isDuplicate("b", 2_000, 5_000))
        assertFalse(d.isDuplicate("a", 7_000, 5_000)) // window passed
        assertTrue(d.isDuplicate("a", 8_000, 5_000))
    }

    @Test
    fun deduplicatorIsBounded() {
        val d = NotificationDeduplicator(maxEntries = 3)
        for (i in 1..10) d.isDuplicate("s$i", i.toLong(), 1_000_000)
        assertFalse(d.isDuplicate("s1", 11, 1_000_000)) // evicted long ago
        assertTrue(d.isDuplicate("s10", 12, 1_000_000))
    }

    @Test
    fun anUpdatedConversationWithANewMessageIsNotADuplicate() {
        val first = raw { it.copy(messages = listOf(RawMessage("Alex", "one", 100))) }
        val updatedSame = first.copy(postTime = 5_000)
        val newMessage = first.copy(messages = listOf(RawMessage("Alex", "one", 100), RawMessage("Alex", "two", 200)))
        val sameText = first.copy(messages = listOf(RawMessage("Alex", "one", 100), RawMessage("Alex", "one", 250)))
        val sigs = listOf(first, updatedSame, newMessage, sameText).map { NotificationDeduplicator.signature(it) }
        assertEquals(sigs[0], sigs[1]) // re-post of identical content
        assertTrue(sigs[0] != sigs[2]) // new message
        assertTrue(sigs[0] != sigs[3]) // same words, but a new message (new timestamp)
    }
}
