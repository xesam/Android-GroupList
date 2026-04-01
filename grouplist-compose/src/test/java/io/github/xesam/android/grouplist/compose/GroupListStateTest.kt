package io.github.xesam.android.grouplist.compose

import io.github.xesam.android.grouplist.vnext.core.CoreCommand
import io.github.xesam.android.grouplist.vnext.core.CoreRow
import io.github.xesam.android.grouplist.vnext.shared.GroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-level contracts of [GroupListState]: invalidation funnel ([snapshot]
 * identity swaps exactly when the visible list changed) and the ChangeSet
 * drain discipline. Snapshot state (mutableStateOf) is headless-usable, so
 * no Compose UI environment is needed.
 */
class GroupListStateTest {

    private fun standalone(data: Any) = CoreRow.Standalone.of(data, 1)

    private fun group(key: String, children: Int, expanded: Boolean): CoreRow.Group<Any, Any, Any> =
        CoreRow.Group.builder<Any, Any, Any>(GroupKey.of(key))
            .header("header:$key")
            .children((0 until children).map { "child:$it" })
            .childViewTypeResolver { _, _ -> 2 }
            .initiallyExpanded(expanded)
            .build()

    @Test
    fun submit_publishesNewSnapshot_andDrainsPendingChangeSet() {
        val state = GroupListState()
        val initial = state.snapshot
        state.submit(listOf(standalone("s0"), group("g:0", 1, false)))
        val after = state.snapshot

        assertNotSame(initial, after)
        assertEquals(2, after.itemCount()) // standalone + collapsed header
        // drain discipline: the published wrapper carries an EMPTY changeSet — the
        // positional delta was consumed by refresh() (single-consumer protocol).
        assertTrue(after.changeSet().isEmpty())
    }

    @Test
    fun dispatch_unknownKey_isFailSilent_andKeepsSnapshotInstance() {
        val state = GroupListState()
        state.submit(listOf(standalone("s0")))
        val snapshot = state.snapshot

        // fail-silent unknown key: no state change -> the core keeps the SAME snapshot
        // instance -> mutableStateOf does not invalidate -> LazyColumn recomposes nothing.
        state.dispatch(CoreCommand.SetGroupExpanded(GroupKey.of("missing"), true))
        assertSame(snapshot, state.snapshot)
    }

    @Test
    fun dispatch_noOpExpandedValue_keepsSnapshotInstance() {
        val state = GroupListState()
        state.submit(listOf(group("g:0", 1, false)))
        val snapshot = state.snapshot

        // set expanded to the current value: no-change branch keeps the instance.
        state.setGroupExpanded(GroupKey.of("g:0"), false)
        assertSame(snapshot, state.snapshot)

        state.toggleGroup(GroupKey.of("g:0"))
        assertNotSame(snapshot, state.snapshot)
        assertEquals(2, state.snapshot.itemCount()) // header + 1 child (no footer)
    }

    @Test
    fun replaceChildren_onCollapsedGroup_isSilentUntilExpand() {
        val state = GroupListState()
        state.submit(listOf(group("g:0", 1, false)))
        val snapshot = state.snapshot

        // "hidden = paused": the override is cached; nothing visible changes.
        state.replaceChildren(GroupKey.of("g:0"), listOf("c0", "c1", "c2"))
        assertSame(snapshot, state.snapshot)

        // the later expand reveals the OVERRIDDEN children, visible in the new snapshot.
        state.toggleGroup(GroupKey.of("g:0"))
        assertEquals(4, state.snapshot.itemCount()) // header + 3 overridden children
        assertTrue(state.isGroupExpanded(GroupKey.of("g:0")))
    }
}
