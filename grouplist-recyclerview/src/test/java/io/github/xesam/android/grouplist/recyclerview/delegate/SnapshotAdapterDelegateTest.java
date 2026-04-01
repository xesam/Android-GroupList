package io.github.xesam.android.grouplist.recyclerview.delegate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.core.CoreCommand;
import io.github.xesam.android.grouplist.vnext.core.CoreRow;
import io.github.xesam.android.grouplist.vnext.core.DefaultCoreController;
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.GroupState;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

/**
 * Composition-path tests: exercise the delegate directly with a recording
 * Notifier. The facade suites run the same delegate through the other entry
 * point, so the two suites are NOT an equivalence proof (there is only one
 * implementation) — each pins the wiring only its own entry point can break:
 * here that is the Notifier callback path and {@code setOnExpansionRequest};
 * there it is the CompatNotifier → protected-hook bridge.
 */
public class SnapshotAdapterDelegateTest {

    @Test
    public void submitSnapshot_shouldDispatchInsertRemoveMoveAndUpdateOperationsFromChangeSet() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        List<ChangeSet.Operation> operations = Arrays.asList(
                ChangeSet.Operation.of(ChangeSet.Type.UPDATE, 0, 2, -1),
                ChangeSet.Operation.of(ChangeSet.Type.INSERT, 1, 2, -1),
                ChangeSet.Operation.of(ChangeSet.Type.REMOVE, 4, 1, -1),
                ChangeSet.Operation.of(ChangeSet.Type.MOVE, 6, 1, 3)
        );

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.STANDALONE, null),
                row(1, PositionKind.STANDALONE, null)
        ), ChangeSet.of(operations)));

        assertEquals(Collections.singletonList("0:2@" + SnapshotAdapterDelegate.PAYLOAD_CONTENT_UPDATE),
                notifier.changedPayloadRanges);
        assertEquals(Collections.singletonList("1:2"), notifier.insertedRanges);
        assertEquals(Collections.singletonList("4:1"), notifier.removedRanges);
        assertEquals(Collections.singletonList("6:3"), notifier.movedItems);
        assertEquals("UPDATE must use the payload overload", 0, notifier.plainChangedRanges.size());
        assertEquals(0, notifier.dataSetChangedCount);
    }

    @Test
    public void submitSnapshot_withEmptyIncrementalChangeSet_shouldNotNotifyAtAll() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);

        delegate.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null)
        ), ChangeSet.empty()));
        delegate.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null)
        ), ChangeSet.empty()));

        assertEquals(0, notifier.dataSetChangedCount);
        assertTrue(notifier.insertedRanges.isEmpty());
        assertTrue(notifier.removedRanges.isEmpty());
        assertTrue(notifier.movedItems.isEmpty());
    }

    @Test
    public void submitSnapshot_withReloadChangeSet_shouldNotifyDataSetChanged() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);

        delegate.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null)
        ), ChangeSet.reload()));

        assertEquals(1, notifier.dataSetChangedCount);
    }

    @Test
    public void submitSnapshot_realControllerDeltas_shouldCarryRecyclerViewValidCoordinates() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey g0 = GroupKey.of("g0");
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 1),
                group(g0, "a", "b", "c")
        ));
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        delegate.submitSnapshot(controller.snapshot());
        // consumption contract: drain after every delivered frame, or undrained
        // incrementals compound into the next frame and get applied twice
        controller.takeChangeSet();
        assertEquals(1, notifier.dataSetChangedCount);

        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, true));
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();
        assertEquals(Collections.singletonList("2:3"), notifier.insertedRanges);
        assertEquals(5, delegate.getItemCount());
        notifier.insertedRanges.clear();

        controller.dispatch(new CoreCommand.UpdateStandaloneFlat(0, "s0-changed"));
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();
        assertEquals(Collections.singletonList("0:1@" + SnapshotAdapterDelegate.PAYLOAD_CONTENT_UPDATE),
                notifier.changedPayloadRanges);
        assertTrue(notifier.insertedRanges.isEmpty()); // no stale INSERT re-application

        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, false));
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();
        assertEquals(Collections.singletonList("2:3"), notifier.removedRanges);
        assertTrue(notifier.insertedRanges.isEmpty());
    }

    @Test
    public void submitSnapshot_whenIntermediateVersionedFramesSkipped_shouldDegradeToReload() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey g0 = GroupKey.of("g0");
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 1),
                group(g0, "a", "b", "c")
        ));
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();
        notifier.dataSetChangedCount = 0;

        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, true));
        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, false));
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();

        assertEquals(1, notifier.dataSetChangedCount);
        assertTrue(notifier.insertedRanges.isEmpty());
        assertTrue(notifier.removedRanges.isEmpty());
    }

    /**
     * Delegate-own coverage (no facade counterpart: the facade suite pins
     * bridge wiring only): submitting the exact (already displayed) versioned
     * frame again must apply nothing.
     */
    @Test
    public void submitSnapshot_versionedRedeliveryOfDisplayedFrame_shouldNotNotify() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey g0 = GroupKey.of("g0");
        controller.submit(Arrays.asList(group(g0, "a", "b", "c")));
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();
        notifier.dataSetChangedCount = 0;

        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, true));
        delegate.submitSnapshot(controller.snapshot());
        controller.takeChangeSet();
        assertEquals(Collections.singletonList("1:3"), notifier.insertedRanges);

        // re-delivering the SAME (already displayed) immutable frame: nothing to apply.
        delegate.submitSnapshot(controller.snapshot());
        assertEquals(0, notifier.dataSetChangedCount);
        assertEquals(Collections.singletonList("1:3"), notifier.insertedRanges);
        assertTrue(notifier.changedPayloadRanges.isEmpty());
    }

    /** Collapse-direction chrome (the facade chrome test covers expand direction only). */
    @Test
    public void submitSnapshotWithGroupChrome_onCollapse_shouldNotifyHeaderAndFooterInNewPositions() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));
        notifier.changedItems.clear();

        delegate.submitSnapshotWithGroupChrome(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));

        assertEquals(Arrays.asList(0, 1), notifier.changedItems);
    }

    /** Chrome suppression when expansion is unchanged (delegate-own; no facade counterpart). */
    @Test
    public void submitSnapshotWithGroupChrome_whenExpansionUnchanged_shouldNotNotifyChrome() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));
        notifier.changedItems.clear();

        delegate.submitSnapshotWithGroupChrome(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));

        assertTrue(notifier.changedItems.isEmpty());
    }

    @Test
    public void submitSnapshotWithGroupChrome_shouldNotifyHeaderAndFooterForChangedGroup() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        GroupKey b = GroupKey.of("b");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a),
                row(2, PositionKind.GROUP_HEADER, b),
                row(3, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));
        notifier.changedItems.clear();

        delegate.submitSnapshotWithGroupChrome(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a),
                row(3, PositionKind.GROUP_HEADER, b),
                row(4, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));

        assertEquals(Arrays.asList(0, 2), notifier.changedItems);
    }

    /**
     * Multiple groups changing in the same frame: every changed group's header and
     * footer must be notified, at their positions in the NEW frame. This is what the
     * left-to-right sweep in {@code chromePositionsForGroups} is for — a single-group
     * fixture cannot tell "report every changed group" apart from "report the first".
     */
    @Test
    public void submitSnapshotWithGroupChrome_shouldNotifyEveryChangedGroupInNewPositions() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        GroupKey b = GroupKey.of("b");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a),
                row(2, PositionKind.GROUP_HEADER, b),
                row(3, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));
        notifier.changedItems.clear();

        delegate.submitSnapshotWithGroupChrome(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a),
                row(3, PositionKind.GROUP_HEADER, b),
                row(4, PositionKind.GROUP_ITEM, b),
                row(5, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));

        assertEquals(Arrays.asList(0, 2, 3, 5), notifier.changedItems);
    }

    @Test
    public void groupStateQueries_shouldResolveExpandedAndHeaderFooterPositions() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        GroupKey b = GroupKey.of("b");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a),
                row(3, PositionKind.GROUP_HEADER, b),
                row(4, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));

        assertTrue(delegate.isGroupExpanded(a));
        assertFalse(delegate.isGroupExpanded(b));
        assertTrue(delegate.isGroupExpandedAt(0));
        assertFalse(delegate.isGroupExpandedAt(3));
        assertEquals(0, delegate.findGroupHeaderPosition(a));
        assertEquals(2, delegate.findGroupFooterPosition(a));
        assertEquals(3, delegate.findGroupHeaderPosition(b));
        assertEquals(4, delegate.findGroupFooterPosition(b));
        assertEquals(-1, delegate.findGroupHeaderPosition(GroupKey.of("missing")));
        assertFalse(delegate.isGroupExpandedAt(99));

        GroupState state = delegate.getGroupState(a);
        assertTrue(state.expanded());
        assertEquals(3, state.visibleItemCount());
        assertEquals(5, delegate.getItemCount());
    }

    @Test
    public void groupState_shouldReportAdapterRelativePositionsNotFlatPositions() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey key = GroupKey.of("comment");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(10, PositionKind.GROUP_HEADER, key),
                row(11, PositionKind.GROUP_ITEM, key),
                row(12, PositionKind.GROUP_FOOTER, key)
        ), ChangeSet.empty()));

        GroupState state = delegate.getGroupState(key);
        assertEquals(0, state.headerPosition());
        assertEquals(2, state.footerPosition());
        assertEquals(0, delegate.findGroupHeaderPosition(key));
        assertEquals(2, delegate.findGroupFooterPosition(key));
    }

    @Test
    public void getGroupState_whenGroupMissing_shouldReturnNull() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        delegate.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null)
        ), ChangeSet.empty()));

        assertNull(delegate.getGroupState(GroupKey.of("missing")));
    }

    @Test
    public void getGroupState_shouldHandleHeaderOnlyGroup() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey key = GroupKey.of("header-only");
        delegate.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.GROUP_HEADER, key)
        ), ChangeSet.empty()));

        GroupState state = delegate.getGroupState(key);
        assertEquals(0, state.headerPosition());
        assertEquals(-1, state.footerPosition());
        assertEquals(1, state.visibleItemCount());
        assertFalse(state.expanded());
    }

    @Test
    public void groupStateCache_shouldStayConsistentWithLatestSnapshot() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        GroupKey b = GroupKey.of("b");

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));
        GroupState before = delegate.getGroupState(a);
        assertEquals(0, before.headerPosition());
        assertEquals(1, before.footerPosition());
        assertFalse(before.expanded());

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a),
                row(3, PositionKind.GROUP_HEADER, b),
                row(4, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));

        GroupState after = delegate.getGroupState(a);
        assertEquals(0, after.headerPosition());
        assertEquals(2, after.footerPosition());
        assertTrue(after.expanded());
        GroupState stateB = delegate.getGroupState(b);
        assertEquals(3, stateB.headerPosition());
        assertEquals(4, stateB.footerPosition());
        assertFalse(stateB.expanded());
    }

    @Test
    public void isGroupExpandedAt_shouldReturnFalseForStandaloneAnchor() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.STANDALONE, null),
                row(1, PositionKind.GROUP_HEADER, a),
                row(2, PositionKind.GROUP_ITEM, a),
                row(3, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));

        assertFalse(delegate.isGroupExpandedAt(0));
        assertTrue(delegate.isGroupExpandedAt(1));
        assertTrue(delegate.isGroupExpandedAt(2));
        assertTrue(delegate.isGroupExpandedAt(3));
    }

    @Test
    public void expansionRequests_shouldFlowThroughCallback_andRespectGuards() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        List<String> requests = new ArrayList<>();

        delegate.setOnExpansionRequest((groupKey, expanded) -> {
            requests.add(groupKey.value() + ":" + expanded);
            return true;
        });

        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));

        assertTrue(delegate.expandGroup(a));
        assertEquals(Collections.singletonList("a:true"), requests);

        requests.clear();
        // the guard compares against the SNAPSHOT-DERIVED state: resubmit an
        // expanded snapshot first, then the same-direction request is guarded
        // before the callback ever fires
        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_FOOTER, a)
        ), ChangeSet.empty()));
        assertFalse(delegate.setGroupExpanded(a, true));
        assertFalse(delegate.collapseGroup(GroupKey.of("missing")));
        assertTrue(requests.isEmpty());

        // null reset restores the default reject behavior
        delegate.setOnExpansionRequest(null);
        requests.clear();
        assertFalse(delegate.expandGroup(a));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void setGroupsExpanded_shouldDeduplicateAndCountChanged() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        GroupKey a = GroupKey.of("a");
        GroupKey b = GroupKey.of("b");
        List<String> requests = new ArrayList<>();
        delegate.setOnExpansionRequest((groupKey, expanded) -> {
            requests.add(groupKey.value() + ":" + expanded);
            return true;
        });
        delegate.submitSnapshot(ListSnapshot.of(Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a),
                row(2, PositionKind.GROUP_HEADER, b),
                row(3, PositionKind.GROUP_FOOTER, b)
        ), ChangeSet.empty()));

        int changed = delegate.setGroupsExpanded(Arrays.asList(a, a, b, null), true);

        assertEquals(2, changed);
        assertEquals(Arrays.asList("a:true", "b:true"), requests);
    }

    @Test
    public void getRow_whenPositionOutOfBounds_shouldThrowWithExplicitMessage() {
        RecordingNotifier notifier = new RecordingNotifier();
        SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);
        delegate.submitSnapshot(ListSnapshot.of(Collections.singletonList(
                row(0, PositionKind.STANDALONE, null)
        ), ChangeSet.empty()));

        try {
            delegate.getRow(1);
            throw new AssertionError("Expected out of bounds access to throw");
        } catch (IndexOutOfBoundsException expected) {
            assertTrue("message should name the offending position",
                    expected.getMessage().contains("position=1"));
        }
    }

    private static ListSnapshot.Row<String> row(int flatPosition, PositionKind kind, GroupKey groupKey) {
        PositionMapping mapping = new PositionMapping(flatPosition, kind, groupKey, -1);
        return ListSnapshot.Row.of(mapping, "data", 1);
    }

    private static CoreRow.Group<Object, Object, Object> group(GroupKey groupKey, Object... children) {
        return CoreRow.Group.<Object, Object, Object>builder(groupKey)
                .header("header")
                .children(Arrays.asList(children))
                .childViewTypeResolver((childIndex, childData) -> 2)
                .build();
    }

    private static final class RecordingNotifier implements SnapshotAdapterDelegate.Notifier {
        final List<Integer> changedItems = new ArrayList<>();
        final List<String> changedPayloadRanges = new ArrayList<>();
        /** The no-payload overload is retained surface but currently unreached; its own list. */
        final List<String> plainChangedRanges = new ArrayList<>();
        final List<String> insertedRanges = new ArrayList<>();
        final List<String> removedRanges = new ArrayList<>();
        final List<String> movedItems = new ArrayList<>();
        int dataSetChangedCount;

        @Override
        public void onDataReload() {
            dataSetChangedCount++;
        }

        @Override
        public void onItemChanged(int position) {
            changedItems.add(position);
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            insertedRanges.add(positionStart + ":" + itemCount);
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            removedRanges.add(positionStart + ":" + itemCount);
        }

        @Override
        public void onItemMoved(int fromPosition, int toPosition) {
            movedItems.add(fromPosition + ":" + toPosition);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            plainChangedRanges.add(positionStart + ":" + itemCount);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, @NonNull Object payload) {
            changedPayloadRanges.add(positionStart + ":" + itemCount + "@" + payload);
        }
    }
}
