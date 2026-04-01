package io.github.xesam.android.grouplist.vnext.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Contract of the {@link GroupKey} value object: blank rejection plus the
 * trim normalization documented on {@link GroupKey#of(String)}.
 */
public class GroupKeyTest {

    @Test
    public void of_shouldRejectNullAndBlankValues() {
        try {
            GroupKey.of(null);
            fail("expected IllegalArgumentException for null value");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be empty"));
        }
        try {
            GroupKey.of("");
            fail("expected IllegalArgumentException for empty value");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be empty"));
        }
        try {
            GroupKey.of("   ");
            fail("expected IllegalArgumentException for whitespace-only value");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be empty"));
        }
    }

    @Test
    public void of_shouldTrimSurroundingWhitespace() {
        // documented normalization: " a " and "a" are the SAME business identity,
        // otherwise untrimmed domain ids would silently fork the expansion-state map
        assertEquals("a", GroupKey.of("  a  ").value());
        assertEquals(GroupKey.of("a"), GroupKey.of(" a "));
        assertEquals(GroupKey.of("a").hashCode(), GroupKey.of("\ta\t").hashCode());
    }

    @Test
    public void groupKey_shouldHaveValueSemantics() {
        assertEquals(GroupKey.of("g:1"), GroupKey.of("g:1"));
        assertEquals("g:1", GroupKey.of("g:1").toString());
        assertNotEquals(GroupKey.of("g:1"), GroupKey.of("g:2"));
        assertFalse(GroupKey.of("g:1").equals("g:1"));
    }
}
