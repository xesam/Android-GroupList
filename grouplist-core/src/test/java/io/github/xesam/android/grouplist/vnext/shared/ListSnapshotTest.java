package io.github.xesam.android.grouplist.vnext.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Contract tests for the factory split the Core hot path relies on: {@code of}
 * takes a defensive copy (the caller keeps ownership of its list), while
 * {@code ofTrusted} does NOT (the caller hands the list over exclusively and must
 * not mutate it afterwards). Also pins that the versioned trusted factory carries
 * version / baseVersion through unchanged. (The lineage GATE built on top of those
 * numbers — when a delta may be applied vs. a full rebuild — lives in the consumer,
 * and is pinned by the adapter suite.)
 */
public class ListSnapshotTest {

    @Test
    public void of_shouldTakeDefensiveCopy() {
        List<ListSnapshot.Row<?>> source = new ArrayList<>();
        source.add(row(0, "a"));

        ListSnapshot snapshot = ListSnapshot.of(source, ChangeSet.empty());

        source.add(row(1, "b"));
        source.clear();

        assertEquals(1, snapshot.itemCount());
        assertEquals("a", snapshot.rows().get(0).data());
    }

    @Test
    public void ofTrusted_shouldNotCopy_rowsReflectSourceMutations() {
        List<ListSnapshot.Row<?>> source = new ArrayList<>();
        source.add(row(0, "a"));

        ListSnapshot snapshot = ListSnapshot.ofTrusted(source, ChangeSet.empty());

        // trusted path documents "caller owns the list": no copy is made, so later
        // mutations of the source are visible (and are the caller's own bug).
        source.add(row(1, "b"));
        assertEquals(2, snapshot.itemCount());
    }

    @Test
    public void rows_shouldBeUnmodifiable() {
        ListSnapshot snapshot = ListSnapshot.of(Arrays.asList(row(0, "a")), ChangeSet.empty());

        try {
            snapshot.rows().add(row(1, "b"));
            fail("rows() must be read-only");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void of_shouldBeUnversioned() {
        ListSnapshot snapshot = ListSnapshot.of(Arrays.asList(row(0, "a")), ChangeSet.empty());

        assertFalse(snapshot.isVersioned());
        assertEquals(ListSnapshot.UNVERSIONED, snapshot.version());
        assertEquals(ListSnapshot.UNVERSIONED, snapshot.baseVersion());
    }

    @Test
    public void ofTrustedVersioned_shouldCarryVersionAndBaseVersion() {
        ListSnapshot snapshot = ListSnapshot.ofTrusted(
                new ArrayList<ListSnapshot.Row<?>>(), ChangeSet.reload(), 7L, 5L);

        assertTrue(snapshot.isVersioned());
        assertEquals(7L, snapshot.version());
        assertEquals(5L, snapshot.baseVersion());
        assertEquals(ChangeSet.Channel.RELOAD, snapshot.changeSet().channel());
    }

    @Test
    public void nullChangeSet_shouldDefaultToEmptyIncremental() {
        ListSnapshot snapshot = ListSnapshot.of(Arrays.asList(row(0, "a")), null);

        assertEquals(ChangeSet.Channel.INCREMENTAL, snapshot.changeSet().channel());
        assertTrue(snapshot.changeSet().isEmpty());
    }

    private static ListSnapshot.Row<String> row(int flatPosition, String data) {
        return ListSnapshot.Row.of(
                new PositionMapping(flatPosition, PositionKind.STANDALONE, null, -1), data, 1);
    }
}
