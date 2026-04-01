package io.github.xesam.android.grouplist.compose

import io.github.xesam.android.grouplist.vnext.core.CoreCommand
import io.github.xesam.android.grouplist.vnext.core.CoreRow
import io.github.xesam.android.grouplist.vnext.core.DefaultCoreController
import io.github.xesam.android.grouplist.vnext.shared.GroupKey
import io.github.xesam.android.grouplist.vnext.shared.PositionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stability contracts of [composeKeys]: uniqueness, and key survival across
 * expansion / visibility / in-place content updates (so LazyColumn recomposes
 * instead of remove+insert on every core UPDATE).
 */
class ComposeKeysTest {

    private fun standalone(data: Any) = CoreRow.Standalone.of(data, 1)

    private fun group(key: String, children: Int, expanded: Boolean): CoreRow.Group<Any, Any, Any> =
        CoreRow.Group.builder<Any, Any, Any>(GroupKey.of(key))
            .header("header:$key")
            .children((0 until children).map { "child:$it" })
            .childViewTypeResolver { _, _ -> 2 }
            .initiallyExpanded(expanded)
            .build()

    @Test
    fun keys_areUnique_forNoCollisionCrash() {
        val controller = DefaultCoreController()
        controller.submit(listOf(
            standalone("dup-content"),
            standalone("dup-content"), // same content, different slots
            group("g:0", 2, true),
            standalone("dup-content"),
        ))
        val keys = controller.snapshot().composeKeys()
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun keys_surviveGroupExpansion_standaloneKeysUnchanged() {
        val controller = DefaultCoreController()
        controller.submit(listOf(
            standalone("s0"),
            group("g:0", 3, false),
            standalone("s1"),
        ))
        val before = controller.snapshot().composeKeys()
        controller.dispatch(
            CoreCommand.SetGroupExpanded(GroupKey.of("g:0"), true)
        )
        val after = controller.snapshot().composeKeys()

        val standaloneBefore = before.filter { it.startsWith(PositionKind.STANDALONE.name) }
        val standaloneAfter = after.filter { it.startsWith(PositionKind.STANDALONE.name) }
        assertEquals(standaloneBefore, standaloneAfter)
    }

    @Test
    fun keys_surviveInPlaceContentUpdate_standaloneKeyUnchanged() {
        val controller = DefaultCoreController()
        controller.submit(listOf(
            standalone("s0"),
            group("g:0", 1, false),
        ))
        val standaloneKeyBefore = controller.snapshot().composeKeys()[0]

        // in-place update of the visible standalone at flat position 0
        controller.dispatch(
            CoreCommand.UpdateStandaloneFlat(0, "s0-changed")
        )
        // keys are ordinal-based and thus independent of the data instance:
        // a data-object swap (in-place UPDATE) must keep the item's identity.
        val standaloneKeyAfter = controller.snapshot().composeKeys()[0]

        assertEquals(standaloneKeyBefore, standaloneKeyAfter)
        assertEquals("STANDALONE:0", standaloneKeyAfter)
    }

    @Test
    fun groupKeys_areSemantic_keyedByKindGroupKeyChildIndex() {
        val controller = DefaultCoreController()
        controller.submit(listOf(
            group("g:0", 2, true),
            group("g:1", 1, true),
        ))
        val keys = controller.snapshot().composeKeys()
        assertEquals("GROUP_HEADER:g:0", keys[0])
        assertEquals("GROUP_ITEM:g:0:0", keys[1])
        assertEquals("GROUP_ITEM:g:0:1", keys[2])
        assertEquals("GROUP_HEADER:g:1", keys[3])
        assertEquals("GROUP_ITEM:g:1:0", keys[4])
    }

    @Test
    fun footerKey_isDistinctFromHeaderKey_ofSameGroup() {
        val controller = DefaultCoreController()
        controller.submit(listOf(
            CoreRow.Group.builder<Any, Any, Any>(GroupKey.of("g"))
                .header("header")
                .footer("footer")
                .children(listOf("child"))
                .childViewTypeResolver { _, _ -> 2 }
                .initiallyExpanded(true)
                .build(),
        ))
        val keys = controller.snapshot().composeKeys()
        assertEquals("GROUP_HEADER:g", keys[0])
        assertEquals("GROUP_ITEM:g:0", keys[1])
        assertEquals("GROUP_FOOTER:g", keys[2])
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun keys_surviveReplaceChildren_keptTail_childKeysChangeOnlyAtTheTail() {
        val controller = DefaultCoreController()
        controller.submit(listOf(
            group("g:0", 3, true),
        ))
        val before = controller.snapshot().composeKeys()

        controller.dispatch(
            CoreCommand.ReplaceChildren(GroupKey.of("g:0"), listOf("kept", "other"))
        )
        val after = controller.snapshot().composeKeys()

        // equal-count head keeps its key (in-place recompose), the removed tail key disappears
        assertEquals(listOf("GROUP_HEADER:g:0", "GROUP_ITEM:g:0:0", "GROUP_ITEM:g:0:1"), after)
        assertEquals(before.subList(0, 2), after.subList(0, 2))
        assertTrue(before.contains("GROUP_ITEM:g:0:2"))
        assertFalse(after.contains("GROUP_ITEM:g:0:2"))
    }
}
