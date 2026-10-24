package com.example.vibratext.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Representative payloads: what real apps put into notification extras (modelled, not captured from a device). */
class NotificationParserTest {

    private fun raw(
        pkg: String = "com.whatsapp",
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        subText: String? = null,
        conversationTitle: String? = null,
        messages: List<RawMessage> = emptyList(),
        category: String? = null,
        messaging: Boolean = false,
        group: Boolean = false,
    ) = RawNotification(
        packageName = pkg, key = "0|$pkg|1|null|10", id = 1, postTime = 1_000,
        title = title, text = text, bigText = bigText, subText = subText, conversationTitle = conversationTitle,
        messages = messages, category = category, isMessagingStyle = messaging, isGroupConversation = group,
    )

    @Test
    fun messagingStyleUsesExplicitSenderAndLatestIncomingMessage() {
        val p = NotificationParser.parse(
            raw(
                title = "Alex", text = "Hello, are you free?", messaging = true, category = "msg",
                messages = listOf(
                    RawMessage("Alex", "Hi", 100),
                    RawMessage("Alex", "Hello, are you free?", 200),
                ),
            ),
            "WhatsApp",
        )
        assertEquals("Alex", p.senderName)
        assertEquals("Hello, are you free?", p.messageText)
        assertEquals(SenderSource.MESSAGING_STYLE, p.senderSource)
        assertEquals(200L, p.timestamp)
        assertEquals("WhatsApp", p.applicationName)
        assertTrue(p.isMessaging)
    }

    @Test
    fun ownRepliesAreSkippedWhenChoosingTheLatestIncomingMessage() {
        val p = NotificationParser.parse(
            raw(
                messaging = true,
                messages = listOf(
                    RawMessage("Sam", "Dinner at 8?", 100),
                    RawMessage("Me", "Sure!", 200, fromSelf = true),
                ),
            ),
            "Signal",
        )
        assertEquals("Sam", p.senderName)
        assertEquals("Dinner at 8?", p.messageText)
    }

    @Test
    fun groupChatKeepsPerMessageSenderAndConversationTitle() {
        val p = NotificationParser.parse(
            raw(
                title = "Family", conversationTitle = "Family", group = true, messaging = true,
                messages = listOf(RawMessage("Mum", "Lunch is ready", 300)),
            ),
            "WhatsApp",
        )
        assertEquals("Mum", p.senderName)
        assertEquals("Family", p.conversationTitle)
        assertTrue(p.isGroupConversation)
    }

    @Test
    fun oneToOneMessagingStyleWithoutPersonUsesTheConversationPartner() {
        val p = NotificationParser.parse(
            raw(title = "Jo", messaging = true, messages = listOf(RawMessage(null, "ping", 5))),
            "Signal",
        )
        assertEquals("Jo", p.senderName)
        assertEquals(SenderSource.TITLE, p.senderSource)
    }

    @Test
    fun groupChatWithoutExplicitSenderDoesNotInventOne() {
        val p = NotificationParser.parse(
            raw(title = "Family", conversationTitle = "Family", group = true, messaging = true, messages = listOf(RawMessage(null, "hi all", 5))),
            "WhatsApp",
        )
        assertNull(p.senderName)
        assertEquals("hi all", p.messageText)
        assertEquals(SenderSource.NONE, p.senderSource)
    }

    @Test
    fun ordinaryMessageNotificationUsesTitleAndText() {
        val p = NotificationParser.parse(raw(pkg = "org.telegram.messenger", title = "Robin", text = "Photo received", category = "msg"), "Telegram")
        assertEquals("Robin", p.senderName)
        assertEquals("Photo received", p.messageText)
        assertEquals(SenderSource.TITLE, p.senderSource)
    }

    @Test
    fun genericNotificationHasNoSenderAndTheTitleBecomesPartOfTheMessage() {
        val p = NotificationParser.parse(raw(pkg = "com.shop", title = "Parcel update", text = "Arriving today", category = "status"), "Shop")
        assertNull(p.senderName)
        assertEquals("Parcel update. Arriving today", p.messageText)
        assertEquals(SenderSource.NONE, p.senderSource)
    }

    @Test
    fun expandedTextReplacesAnEllipsisCutShortText() {
        val p = NotificationParser.parse(
            raw(title = "Kim", text = "Are you coming to the par…", bigText = "Are you coming to the party tonight?", category = "msg"),
            "Messages",
        )
        assertEquals("Are you coming to the party tonight?", p.messageText)
    }

    @Test
    fun expandedTextIsUsedWhenShortTextIsMissingButNotWhenItDiffers() {
        assertEquals("only big", NotificationParser.parse(raw(title = "A", bigText = "only big", category = "msg"), "X").messageText)
        assertEquals("short", NotificationParser.parse(raw(title = "A", text = "short", bigText = "something else entirely", category = "msg"), "X").messageText)
    }

    @Test
    fun senderNameIsNotRepeatedInsideTheMessage() {
        val p = NotificationParser.parse(raw(title = "Alex", text = "Alex: see you soon", category = "msg"), "WhatsApp")
        assertEquals("see you soon", p.messageText)
        val only = NotificationParser.parse(raw(title = "Alex", text = "Alex:", category = "msg"), "WhatsApp")
        assertEquals("Alex:", only.messageText) // nothing left after stripping: keep as is
    }

    @Test
    fun hiddenContentIsReportedAsExposedNothingMore() {
        // Preview hidden by the app: only a generic line is exposed.
        val p = NotificationParser.parse(raw(title = "WhatsApp", text = "New message", category = "msg"), "WhatsApp")
        assertEquals("WhatsApp", p.senderName)
        assertEquals("New message", p.messageText)
    }

    @Test
    fun senderWithoutMessageAndNothingAtAll() {
        val senderOnly = NotificationParser.parse(raw(title = "Alex", category = "msg"), "Signal")
        assertEquals("Alex", senderOnly.senderName)
        assertNull(senderOnly.messageText)
        assertFalse(senderOnly.hasMessage)

        val nothing = NotificationParser.parse(raw(), "Signal")
        assertNull(nothing.senderName)
        assertNull(nothing.messageText)
        assertFalse(NotificationParser.hasReadableContent(raw()))
        assertTrue(NotificationParser.hasReadableContent(raw(text = "x")))
        assertTrue(NotificationParser.hasReadableContent(raw(messages = listOf(RawMessage("a", "b", 1)))))
    }

    @Test
    fun blankInvisibleAndMissingFieldsAreHandledGracefully() {
        val p = NotificationParser.parse(raw(title = "‎  ", text = "‏​   ", category = "msg"), "X")
        assertNull(p.senderName)
        assertNull(p.messageText)
        val weird = NotificationParser.parse(raw(messages = listOf(RawMessage(null, null, 0), RawMessage("A", "   ", 0))), "X")
        assertNull(weird.messageText)
        assertEquals("Anna", NotificationParser.clean("‎ Anna ‏"))
        assertNull(NotificationParser.clean(null))
    }

    @Test
    fun subTextIsKeptAsSummaryAndCategoryIsPreserved() {
        val p = NotificationParser.parse(raw(title = "Pat", text = "x", subText = "3 new messages", category = "email"), "Gmail")
        assertEquals("3 new messages", p.summaryText)
        assertEquals("email", p.notificationCategory)
    }
}
