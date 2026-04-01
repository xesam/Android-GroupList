package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

/**
 * Guards the bounded-memory edit-distance fallback declared on {@link RowDiff}: a
 * middle range whose edit distance exceeds {@link RowDiff#MAX_EDIT_DISTANCE} must
 * degrade to a single REMOVE + INSERT of the whole middle (still a correct
 * sequential delta, just without holder reuse) rather than allocating an unbounded
 * trace.
 */
public class RowDiffTest {

    @Test
    public void diff_whenEditDistanceExceedsBound_shouldFallBackToRemovePlusInsert() {
        int count = RowDiff.MAX_EDIT_DISTANCE + 88;
        List<ListSnapshot.Row<?>> oldRows = new ArrayList<>();
        List<ListSnapshot.Row<?>> newRows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            oldRows.add(row(i, "old-" + i));
            newRows.add(row(i, "new-" + i));
        }

        ChangeSet changeSet = RowDiff.diff(oldRows, newRows, ItemIdentity.DATA_EQUALS);

        assertEquals(ChangeSet.Channel.INCREMENTAL, changeSet.channel());
        assertEquals(2, changeSet.operations().size());
        ChangeSet.Operation remove = changeSet.operations().get(0);
        assertEquals(ChangeSet.Type.REMOVE, remove.type());
        assertEquals(0, remove.positionStart());
        assertEquals(count, remove.itemCount());
        ChangeSet.Operation insert = changeSet.operations().get(1);
        assertEquals(ChangeSet.Type.INSERT, insert.type());
        assertEquals(0, insert.positionStart());
        assertEquals(count, insert.itemCount());
    }

    @Test
    public void diff_withinBound_shouldEmitPreciseInPlaceUpdate() {
        // Identity is the item id, so a content change of the same item is an UPDATE
        // (with DATA_EQUALS identity it would degrade to REMOVE + INSERT by design).
        ItemIdentity byId = data -> ((Item) data).id;

        List<ListSnapshot.Row<?>> oldRows = new ArrayList<>();
        List<ListSnapshot.Row<?>> newRows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            oldRows.add(itemRow(i, new Item("id" + i, "v" + i)));
            newRows.add(itemRow(i, i == 3
                    ? new Item("id3", "v3-changed")
                    : new Item("id" + i, "v" + i)));
        }

        ChangeSet changeSet = RowDiff.diff(oldRows, newRows, byId);

        assertEquals(1, changeSet.operations().size());
        ChangeSet.Operation update = changeSet.operations().get(0);
        assertEquals(ChangeSet.Type.UPDATE, update.type());
        assertEquals(3, update.positionStart());
        assertEquals(1, update.itemCount());
    }

    private static final class Item {
        final String id;
        final String value;

        Item(String id, String value) {
            this.id = id;
            this.value = value;
        }

        // value-based equals: content comparison in RowDiff must see only item 3 as changed
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Item)) {
                return false;
            }
            Item other = (Item) o;
            return id.equals(other.id) && value.equals(other.value);
        }

        @Override
        public int hashCode() {
            return id.hashCode() * 31 + value.hashCode();
        }
    }

    private static ListSnapshot.Row<?> itemRow(int flatPosition, Item item) {
        return ListSnapshot.Row.of(new PositionMapping(flatPosition, PositionKind.STANDALONE, null, -1), item, 1);
    }

    private static ListSnapshot.Row<String> row(int flatPosition, String data) {
        return ListSnapshot.Row.of(
                new PositionMapping(flatPosition, PositionKind.STANDALONE, null, -1), data, 1);
    }
}
