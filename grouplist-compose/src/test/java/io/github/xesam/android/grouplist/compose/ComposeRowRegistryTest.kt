package io.github.xesam.android.grouplist.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freeze contract of [ComposeRowRegistry]: registration is complete before the
 * first read; late registration (after a slot lookup) must fail fast instead of
 * silently affecting only the rows composed afterwards.
 */
class ComposeRowRegistryTest {

    @Test
    fun register_afterFirstLookup_shouldFailFast() {
        val registry = ComposeRowRegistry()
        registry.register<Any>(1) { }

        // first read freezes the registry
        val lookup = registry.slotFor(1)
        assertTrue(lookup is ComposeRowRegistry.SlotLookup.Present)

        try {
            registry.register<Any>(2) { }
            throw AssertionError("Expected register after freeze to throw IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("frozen"))
        }
    }

    @Test
    fun registerBeforeFreeze_sameViewTypeTwice_staysPresent_andMissingViewTypeIsAbsent() {
        val registry = ComposeRowRegistry()

        // the legal pre-freeze path: re-registering a viewType is accepted and the
        // slot table still answers Present afterwards (map put-overwrite semantics)
        registry.register<Any>(1) { }
        registry.register<Any>(1) { }
        assertTrue(registry.slotFor(1) is ComposeRowRegistry.SlotLookup.Present)

        val absent = registry.slotFor(9)
        assertTrue(absent is ComposeRowRegistry.SlotLookup.Absent)
        assertEquals(9, (absent as ComposeRowRegistry.SlotLookup.Absent).viewType)
    }
}
