package com.example.vibratext.rules

import com.example.vibratext.data.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEditingTest {
    private var next = 0
    private fun id() = "r${++next}"

    @Test
    fun settingAnAppProfileCreatesTheRuleAndClearingItRemovesTheRuleAgain() {
        val a = RuleEditing.withAppProfile(emptyList(), "pkg.a", "quiet", ::id)
        assertEquals(1, a.size)
        assertEquals("quiet", RuleEditing.appProfileId(a, "pkg.a"))
        assertNull(RuleEditing.appProfileId(a, "pkg.b"))
        val b = RuleEditing.withAppProfile(a, "pkg.a", null, ::id)
        assertTrue(b.isEmpty())
        assertTrue(RuleEditing.withAppProfile(emptyList(), "pkg.a", null, ::id).isEmpty())
    }

    @Test
    fun anAppRuleWithOtherEffectsSurvivesClearingTheProfile() {
        val muted = NotificationRule("m", RuleScope.APP, "pkg.a", profileId = "quiet", mute = true)
        val result = RuleEditing.withAppProfile(listOf(muted), "pkg.a", null, ::id)
        assertEquals(listOf(muted.copy(profileId = null)), result)
        // changing a profile keeps the rule's other fields
        val changed = RuleEditing.withAppProfile(listOf(muted), "pkg.a", "fast", ::id)
        assertEquals("fast", changed.single().profileId)
        assertEquals(true, changed.single().mute)
    }

    @Test
    fun otherRulesAreNeverTouched() {
        val contact = NotificationRule("c", RuleScope.CONTACT, "Alex", profileId = "emergency")
        val result = RuleEditing.withAppProfile(listOf(contact), "pkg.a", "quiet", ::id)
        assertEquals(2, result.size)
        assertEquals(contact, result[0])
    }

    @Test
    fun deletingAProfileRemovesItFromRulesAndDropsRulesThatDoNothing() {
        val rules = listOf(
            NotificationRule("1", RuleScope.APP, "a", profileId = "gone"),
            NotificationRule("2", RuleScope.CONTACT, "Alex", profileId = "gone", playbackMode = PlaybackMode.SENDER_ONLY),
            NotificationRule("3", RuleScope.APP, "b", profileId = "kept"),
        )
        val result = RuleEditing.withoutProfile(rules, "gone")
        assertEquals(listOf("2", "3"), result.map { it.id })
        assertNull(result[0].profileId)
        assertEquals(PlaybackMode.SENDER_ONLY, result[0].playbackMode)
    }

    @Test
    fun removingAnAppRemovesOnlyItsAppRules() {
        val rules = listOf(
            NotificationRule("1", RuleScope.APP, "a", mute = true),
            NotificationRule("2", RuleScope.CONTACT, "a", mute = true),
            NotificationRule("3", RuleScope.APP, "b", mute = true),
        )
        assertEquals(listOf("2", "3"), RuleEditing.withoutApp(rules, "a").map { it.id })
    }
}
