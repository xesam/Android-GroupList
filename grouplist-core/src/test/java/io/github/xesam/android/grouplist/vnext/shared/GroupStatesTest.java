package io.github.xesam.android.grouplist.vnext.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Same-input, same-answer contract between the two derivation paths + core semantics. */
public class GroupStatesTest {

    @Test
    public void twoDerivationPaths_shouldAgreeForIdenticalInput() {
        GroupKey a = GroupKey.of("a");
        GroupKey b = GroupKey.of("b");
        GroupKey missing = GroupKey.of("missing");
        List<ListSnapshot.Row<?>> rows = Arrays.asList(
                row(0, PositionKind.STANDALONE, null),
                row(1, PositionKind.GROUP_HEADER, a),
                row(2, PositionKind.GROUP_ITEM, a),
                row(3, PositionKind.GROUP_FOOTER, a),
                row(4, PositionKind.GROUP_HEADER, b),
                row(5, PositionKind.GROUP_FOOTER, b)
        );

        Map<GroupKey, GroupState> byKey = GroupStates.groupStateByKey(rows);

        // positive anchors: present keys must be answered by BOTH paths.
        // (The byKey lookups are asserted inside the loop below — asserting them here
        // as well would only repeat what the scan-side anchors already guarantee.)
        assertNotNull(GroupStates.computeGroupState(rows, a));
        assertNotNull(GroupStates.computeGroupState(rows, b));

        for (GroupKey key : Arrays.asList(a, b, missing)) {
            GroupState scanned = GroupStates.computeGroupState(rows, key);
            GroupState cached = byKey.get(key);
            if (scanned == null) {
                assertNull(cached);
                continue;
            }
            assertNotNull(cached);
            assertEquals(scanned.expanded(), cached.expanded());
            assertEquals(scanned.headerPosition(), cached.headerPosition());
            assertEquals(scanned.footerPosition(), cached.footerPosition());
            assertEquals(scanned.visibleItemCount(), cached.visibleItemCount());
        }
    }

    @Test
    public void expandedGroup_shouldReportPositionsAndCountsWithAllRows() {
        GroupKey a = GroupKey.of("a");
        List<ListSnapshot.Row<?>> rows = Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_ITEM, a),
                row(2, PositionKind.GROUP_ITEM, a),
                row(3, PositionKind.GROUP_FOOTER, a)
        );

        GroupState state = GroupStates.computeGroupState(rows, a);

        assertNotNull(state);
        assertTrue(state.expanded());
        assertEquals(0, state.headerPosition());
        assertEquals(3, state.footerPosition());
        assertEquals(4, state.visibleItemCount());
    }

    @Test
    public void headerOnlyGroup_shouldReportCollapsedWithNoFooter() {
        GroupKey a = GroupKey.of("header-only");
        List<ListSnapshot.Row<?>> rows = Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a)
        );

        GroupState state = GroupStates.computeGroupState(rows, a);

        assertNotNull(state);
        assertFalse(state.expanded());
        assertEquals(0, state.headerPosition());
        assertEquals(-1, state.footerPosition());
        assertEquals(1, state.visibleItemCount());
    }

    @Test
    public void groupWithoutHeaderRow_shouldBeSkippedByBothPaths() {
        GroupKey a = GroupKey.of("no-header");
        // synthetic input: a group-keyed segment without a GROUP_HEADER row
        List<ListSnapshot.Row<?>> rows = new ArrayList<>();
        rows.add(row(0, PositionKind.GROUP_ITEM, a));
        rows.add(row(1, PositionKind.GROUP_FOOTER, a));

        assertNull(GroupStates.computeGroupState(rows, a));
        assertFalse(GroupStates.groupStateByKey(rows).containsKey(a));
    }

    @Test
    public void missingKey_shouldReturnNullOrAbsent() {
        GroupKey a = GroupKey.of("a");
        List<ListSnapshot.Row<?>> rows = Arrays.asList(
                row(0, PositionKind.GROUP_HEADER, a),
                row(1, PositionKind.GROUP_FOOTER, a)
        );

        assertNull(GroupStates.computeGroupState(rows, GroupKey.of("missing")));
        assertFalse(GroupStates.groupStateByKey(rows).containsKey(GroupKey.of("missing")));
    }

    @Test
    public void emptyRows_shouldYieldNoEntries() {
        assertTrue(GroupStates.groupStateByKey(Arrays.asList()).isEmpty());
    }

    private static ListSnapshot.Row<String> row(int flatPosition, PositionKind kind, GroupKey groupKey) {
        return ListSnapshot.Row.of(new PositionMapping(flatPosition, kind, groupKey, -1), "data", 1);
    }
}
