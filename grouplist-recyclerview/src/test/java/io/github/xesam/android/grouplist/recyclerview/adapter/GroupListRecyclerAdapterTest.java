package io.github.xesam.android.grouplist.recyclerview.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

/**
 * Facade suite for the INHERITANCE entry point.
 *
 * <p>{@link GroupListRecyclerAdapter} is a pure forwarder to
 * {@code SnapshotAdapterDelegate}, and the delegate's own suite
 * ({@code SnapshotAdapterDelegateTest}) owns all behavioral semantics. This suite
 * therefore pins ONLY the wiring that the facade alone can break: the
 * {@code CompatNotifier} → protected-hook bridge for every ChangeSet type, and the
 * forwarding of the public query / expansion API to the delegate. Asserting full
 * semantics here as well would just be the same delegate run twice.</p>
 */
public class GroupListRecyclerAdapterTest {

    @Test
    public void submitSnapshot_shouldRouteEachChangeSetTypeThroughTheProtectedHook() {
        RecordingAdapter adapter = new RecordingAdapter();
        List<ChangeSet.Operation> operations = Arrays.asList(
                ChangeSet.Operation.of(ChangeSet.Type.UPDATE, 0, 2, -1),
                ChangeSet.Operation.of(ChangeSet.Type.INSERT, 1, 2, -1),
                ChangeSet.Operation.of(ChangeSet.Type.REMOVE, 4, 1, -1),
                ChangeSet.Operation.of(ChangeSet.Type.MOVE, 6, 1, 3)
        );

        adapter.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.STANDALONE, null, -1),
                row(1, PositionKind.STANDALONE, null, -1)
        ), ChangeSet.of(operations)));

        assertEquals(
                Collections.singletonList("0:2@" + GroupListRecyclerAdapter.PAYLOAD_CONTENT_UPDATE),
                adapter.changedPayloadRanges);
        assertEquals("UPDATE must route through the payload overload only",
                0, adapter.plainChangedRanges.size());
        assertEquals(Collections.singletonList("1:2"), adapter.insertedRanges);
        assertEquals(Collections.singletonList("4:1"), adapter.removedRanges);
        assertEquals(Collections.singletonList("6:3"), adapter.movedItems);
        assertEquals(0, adapter.reloadCount);
    }

    @Test
    public void submitSnapshot_withReloadChangeSet_shouldReachDispatchNotifyDataSetChanged() {
        RecordingAdapter adapter = new RecordingAdapter();

        adapter.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null, -1)
        ), ChangeSet.reload()));

        assertEquals(1, adapter.reloadCount);
    }

    @Test
    public void submitSnapshot_withEmptyIncrementalChangeSet_shouldNotNotifyAtAll() {
        RecordingAdapter adapter = new RecordingAdapter();
        ListSnapshot frame = ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null, -1)
        ), ChangeSet.empty());

        adapter.submitSnapshot(frame);
        adapter.submitSnapshot(frame);

        assertEquals(0, adapter.reloadCount);
        assertTrue(adapter.insertedRanges.isEmpty());
        assertTrue(adapter.removedRanges.isEmpty());
        assertTrue(adapter.movedItems.isEmpty());
        assertTrue(adapter.changedPayloadRanges.isEmpty());
    }

    @Test
    public void submitSnapshotWithGroupChrome_shouldNotifyChangedChromeThroughNotifyItemChanged() {
        RecordingAdapter adapter = new RecordingAdapter();
        GroupKey key = GroupKey.of("comment");

        adapter.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, key, -1),
                row(1, PositionKind.GROUP_FOOTER, key, -1)
        ), ChangeSet.empty()));
        adapter.changedItems.clear();

        adapter.submitSnapshotWithGroupChrome(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, key, -1),
                row(1, PositionKind.GROUP_ITEM, key, 0),
                row(2, PositionKind.GROUP_FOOTER, key, -1)
        ), ChangeSet.empty()));

        assertEquals(Arrays.asList(0, 2), adapter.changedItems);
        assertEquals("chrome notification must not downgrade to a full reload", 0, adapter.reloadCount);
    }

    @Test
    public void queries_shouldForwardToTheDelegate() {
        RecordingAdapter adapter = new RecordingAdapter();
        GroupKey a = GroupKey.of("a");
        adapter.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a, -1),
                row(1, PositionKind.GROUP_ITEM, a, 0),
                row(2, PositionKind.GROUP_FOOTER, a, -1)
        ), ChangeSet.empty()));

        assertTrue(adapter.isGroupExpanded(a));
        assertTrue(adapter.isGroupExpandedAt(1));
        assertEquals(0, adapter.findGroupHeaderPosition(a));
        assertEquals(2, adapter.findGroupFooterPosition(a));
        assertEquals(3, adapter.getGroupState(a).visibleItemCount());
        assertEquals(3, adapter.getItemCount());
        assertEquals("data", adapter.getRow(1).data());
        assertNull(adapter.getGroupState(GroupKey.of("missing")));
    }

    @Test
    public void expansionIntent_shouldFlowThroughRequestSetGroupExpandedHook() {
        RecordingAdapter adapter = new RecordingAdapter();
        GroupKey a = GroupKey.of("a");

        adapter.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a, -1),
                row(1, PositionKind.GROUP_FOOTER, a, -1)
        ), ChangeSet.empty()));
        assertTrue("collapsed group: expand request must reach the protected hook",
                adapter.expandGroup(a));
        assertEquals(Collections.singletonList("a:true"), adapter.expandRequests);

        adapter.expandRequests.clear();
        adapter.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a, -1),
                row(1, PositionKind.GROUP_ITEM, a, 0),
                row(2, PositionKind.GROUP_FOOTER, a, -1)
        ), ChangeSet.empty()));
        assertTrue("expanded group: collapse request must reach the protected hook",
                adapter.collapseGroup(a));
        assertEquals(Collections.singletonList("a:false"), adapter.expandRequests);
    }

    private static ListSnapshot.Row<String> row(int flatPosition, PositionKind kind, GroupKey groupKey, int childIndex) {
        PositionMapping mapping = new PositionMapping(flatPosition, kind, groupKey, childIndex);
        return ListSnapshot.Row.of(mapping, "data", 1);
    }

    /** Records every notification channel so the facade's bridge can be asserted per type. */
    private static final class RecordingAdapter extends GroupListRecyclerAdapter {
        final List<Integer> changedItems = new ArrayList<>();
        final List<String> changedPayloadRanges = new ArrayList<>();
        /** The no-payload overload is retained surface but currently unreached (UPDATE
         *  always arrives through the payload overload); its own bucket keeps that
         *  negative anchor from being confused with the payload assertions. */
        final List<String> plainChangedRanges = new ArrayList<>();
        final List<String> insertedRanges = new ArrayList<>();
        final List<String> removedRanges = new ArrayList<>();
        final List<String> movedItems = new ArrayList<>();
        final List<String> expandRequests = new ArrayList<>();
        int reloadCount;

        @Override
        protected void dispatchNotifyDataSetChanged() {
            reloadCount++;
        }

        @Override
        protected void notifyItemChangedCompat(int position) {
            changedItems.add(position);
        }

        @Override
        protected void notifyItemRangeChangedCompat(int positionStart, int itemCount) {
            plainChangedRanges.add(positionStart + ":" + itemCount);
        }

        @Override
        protected void notifyItemRangeChangedCompat(int positionStart, int itemCount, Object payload) {
            changedPayloadRanges.add(positionStart + ":" + itemCount + "@" + payload);
        }

        @Override
        protected void notifyItemRangeInsertedCompat(int positionStart, int itemCount) {
            insertedRanges.add(positionStart + ":" + itemCount);
        }

        @Override
        protected void notifyItemRangeRemovedCompat(int positionStart, int itemCount) {
            removedRanges.add(positionStart + ":" + itemCount);
        }

        @Override
        protected void notifyItemMovedCompat(int fromPosition, int toPosition) {
            movedItems.add(fromPosition + ":" + toPosition);
        }

        @Override
        protected boolean requestSetGroupExpanded(@NonNull GroupKey groupKey, boolean expanded) {
            expandRequests.add(groupKey.value() + ":" + expanded);
            return true;
        }
    }
}
