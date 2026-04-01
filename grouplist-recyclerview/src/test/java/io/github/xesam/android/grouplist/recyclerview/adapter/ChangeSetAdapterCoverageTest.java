package io.github.xesam.android.grouplist.recyclerview.adapter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import io.github.xesam.android.grouplist.recyclerview.delegate.SnapshotAdapterDelegate;
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

/**
 * Behavioral exhaustiveness guard: drives EVERY {@link ChangeSet.Type} through a
 * real submit and asserts it reaches a fine-grained notification, never a full
 * reload. Unlike a set-vs-enum mirror check, a type that has no mapping — or that
 * silently fell through to {@code notifyDataSetChanged} — fails here. (A type
 * missing from the delegate's dispatch map additionally throws from its class-init
 * guard, so the failure is loud either way.)
 */
public class ChangeSetAdapterCoverageTest {

    @Test
    public void everyChangeSetType_mapsToFineGrainedNotification() {
        for (ChangeSet.Type type : ChangeSet.Type.values()) {
            RecordingNotifier notifier = new RecordingNotifier();
            SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(notifier);

            delegate.submitSnapshot(ListSnapshot.of(
                    Arrays.asList(row(0, PositionKind.STANDALONE, null)),
                    ChangeSet.of(Arrays.asList(ChangeSet.Operation.of(type, 0, 1, 1)))
            ));

            assertEquals(type + " must map to exactly one fine-grained notification",
                    1, notifier.fineGrainedCount);
            assertEquals(type + " must not fall back to notifyDataSetChanged",
                    0, notifier.dataSetChangedCount);
        }
    }

    private static ListSnapshot.Row<String> row(int flatPosition, PositionKind kind,
            io.github.xesam.android.grouplist.vnext.shared.GroupKey groupKey) {
        return ListSnapshot.Row.of(new PositionMapping(flatPosition, kind, groupKey, -1), "data", 1);
    }

    private static final class RecordingNotifier implements SnapshotAdapterDelegate.Notifier {
        int dataSetChangedCount;
        int fineGrainedCount;

        @Override
        public void onDataReload() {
            dataSetChangedCount++;
        }

        @Override
        public void onItemChanged(int position) {
            fineGrainedCount++;
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            fineGrainedCount++;
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            fineGrainedCount++;
        }

        @Override
        public void onItemMoved(int fromPosition, int toPosition) {
            fineGrainedCount++;
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            fineGrainedCount++;
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, Object payload) {
            fineGrainedCount++;
        }
    }
}
