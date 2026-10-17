package dev.hush.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-class equality, pinned because the bug was invisible.
 *
 * Both [Peer] and [GroupInfo] shipped with an id-only `equals`/`hashCode`
 * override. Both are published through `StateFlow` and rendered by diffing
 * against the previous value, so id-only equality made a *changed* object
 * compare equal to the old one, StateFlow deduplicated the emission, and
 * recomposition never ran.
 *
 * The visible symptoms were all "the notice updated, the thing the notice was
 * about did not":
 *
 *  - a rename left the group title on the old name;
 *  - a membership change left the member count stale;
 *  - an unread message arriving never lit the badge.
 *
 * No crash, no red screen, no exception — a state class lying about equality,
 * which is the hardest kind of bug to notice from the inside.
 */
class StateEqualityTest {

    @Test
    fun `a renamed group is not equal to the group with the old name`() {
        val before = GroupInfo(id = "g_1", name = "Group", adminId = "p_a", createdAt = 1L, memberIds = listOf("p_a"))
        val after = before.copy(name = "the androids")

        assertNotEquals("a rename must not dedupe away in StateFlow", before, after)
        assertEquals("ids are unchanged, which is exactly why id equality hid this", before.id, after.id)
    }

    @Test
    fun `a group whose admin changed is not equal to the old admin state`() {
        val before = GroupInfo(id = "g_1", name = "Group", adminId = "p_a", createdAt = 1L, memberIds = listOf("p_a", "p_b"))
        val after = before.copy(adminId = "p_b")

        assertNotEquals("a new admin must reach the UI", before, after)
    }

    @Test
    fun `a group with a changed member list is not equal to the old list`() {
        val before = GroupInfo(id = "g_1", name = "Group", adminId = "p_a", createdAt = 1L, memberIds = listOf("p_a"))
        val after = before.copy(memberIds = listOf("p_a", "p_b"))

        assertNotEquals("a member count change must not dedupe away", before, after)
    }

    @Test
    fun `a peer whose unread count changed is not equal to the old count`() {
        val before = peer(unread = 0)
        val after = before.copy(unreadCount = 1)

        assertNotEquals("an arriving message must light the badge", before, after)
    }

    @Test
    fun `a renamed peer is not equal to the peer with the old display name`() {
        val before = peer(unread = 0).copy(displayName = "old name")
        val after = before.copy(displayName = "new name")

        assertNotEquals("a roster name change must not dedupe away", before, after)
    }

    @Test
    fun `an identical group is equal so no spurious recomposition is caused`() {
        val a = GroupInfo(id = "g_1", name = "Group", adminId = "p_a", createdAt = 1L, memberIds = listOf("p_a", "p_b"))
        val b = GroupInfo(id = "g_1", name = "Group", adminId = "p_a", createdAt = 1L, memberIds = listOf("p_a", "p_b"))

        assertEquals("unchanged state must still dedupe", a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `monogram falls back rather than throwing on an empty name`() {
        assertEquals("#", GroupInfo(id = "g_1", name = "  ", adminId = "p_a", createdAt = 1L).initials)
        assertEquals("?", Peer(
            id = "p_1", displayName = "", identityPublic = ByteArray(0), safetyNumber = "",
            verified = false, lastSeen = 0L, online = false, unreadCount = 0,
        ).initials)
        assertTrue(
            GroupInfo(id = "g_1", name = "the androids", adminId = "p_a", createdAt = 1L).initials == "TA",
        )
    }

    private fun peer(unread: Int) = Peer(
        id = "p_1",
        displayName = "someone",
        identityPublic = ByteArray(32),
        safetyNumber = "",
        verified = false,
        lastSeen = 0L,
        online = true,
        unreadCount = unread,
    )
}