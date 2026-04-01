package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Allocation-focused assertions for the segment-scoped snapshot rebuild: a single
 * structural change must not reallocate the rows BEFORE the affected group, nor its
 * header (position and key are unchanged), so consumers can rely on Row reference
 * identity as a cheap "this row did not change" signal.
 *
 * <p>Boundary: the rows AFTER the affected group are rebuilt, not reused. Their flat
 * positions shift, and {@code ListSnapshot.Row} owns its {@code PositionMapping}, so a
 * shifted row needs a new mapping — the implementation documents this as the accepted
 * limit of the reuse optimization. Reference identity there is NOT guaranteed.</p>
 */
public class DefaultCoreControllerReuseTest {

    @Test
    public void expandGroup_rowsBeforeAffectedGroupKeepExactReferences() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey first = GroupKey.of("group-first");
        GroupKey second = GroupKey.of("group-second");
        controller.submit(Arrays.asList(
                standalone("s0"),
                standalone("s1"),
                group(first, Arrays.asList("a1", "a2"), true, false, true),
                group(second, Arrays.asList("b1"), false, false, true)
        ));
        List<ListSnapshot.Row<?>> before = controller.snapshot().rows();

        controller.dispatch(new CoreCommand.SetGroupExpanded(second, true));

        List<ListSnapshot.Row<?>> after = controller.snapshot().rows();
        // prefix (s0, s1, first's 4 rows) is untouched: same objects, same mappings
        for (int i = 0; i < 6; i++) {
            assertSame(before.get(i), after.get(i));
        }
        // the affected group's header keeps its position: also reused as-is
        assertSame(before.get(6), after.get(6));
        // children of the affected group are fresh rows
        assertEquals("b1", after.get(7).data());
        assertEquals(7, after.get(7).mapping().flatPosition());
    }

    @Test
    public void collapseGroup_suffixRowsShiftByExactDelta() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey first = GroupKey.of("group-first");
        GroupKey second = GroupKey.of("group-second");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(first, Arrays.asList("a1", "a2", "a3"), true, false, false),
                group(second, Arrays.asList("b1"), true, false, true)
        ));
        // layout: [s0, hdr-first, a1, a2, a3, hdr-second, b1, footer-second]
        List<ListSnapshot.Row<?>> before = controller.snapshot().rows();

        controller.dispatch(new CoreCommand.SetGroupExpanded(first, false));

        List<ListSnapshot.Row<?>> after = controller.snapshot().rows();
        // [s0, hdr-first(reused), hdr-second, b1, footer-second]: tail shifted by -3
        assertEquals(5, after.size());
        assertSame(before.get(0), after.get(0)); // untouched prefix
        assertSame(before.get(1), after.get(1)); // affected group header reused, position kept
        assertEquals("header-group-second", after.get(2).data());
        assertEquals(2, after.get(2).mapping().flatPosition());
        assertEquals("b1", after.get(3).data());
        assertEquals(3, after.get(3).mapping().flatPosition());
        assertEquals("footer-group-second", after.get(4).data());
        assertEquals(4, after.get(4).mapping().flatPosition());
        assertEquals(ChangeSet.Type.REMOVE, controller.snapshot().changeSet().operations().get(0).type());
        assertEquals(2, controller.snapshot().changeSet().operations().get(0).positionStart());
    }

    @Test
    public void replaceChildren_whenCollapsed_reusesEveryRowReference() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(key, Arrays.asList("a1", "a2"), false, false, true)
        ));
        controller.takeChangeSet(); // drain the reload
        ListSnapshot beforeSnapshot = controller.snapshot();
        List<ListSnapshot.Row<?>> before = beforeSnapshot.rows();

        controller.dispatch(new CoreCommand.ReplaceChildren(key, Collections.singletonList("x")));

        // Collapsed target: nothing visible changes, so no new snapshot is published —
        // the SAME wrapper (and therefore the same Row references) must come back.
        assertSame(beforeSnapshot, controller.snapshot());
        List<ListSnapshot.Row<?>> after = controller.snapshot().rows();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), after.get(i));
        }
        assertTrue(controller.snapshot().changeSet().isEmpty());

        // the override is live in the source rows: expanding the group reveals "x"
        controller.dispatch(new CoreCommand.SetGroupExpanded(key, true));
        List<ListSnapshot.Row<?>> expanded = controller.snapshot().rows();
        assertEquals("x", expanded.get(2).data());
        assertEquals(0, expanded.get(2).mapping().childIndex());
    }

    @Test
    public void successiveExpandAndCollapse_shouldTrackFlatPositionsExactly() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey g1 = GroupKey.of("g1");
        GroupKey g2 = GroupKey.of("g2");
        GroupKey g3 = GroupKey.of("g3");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(g1, Arrays.asList("a1", "a2"), false, false, false),
                group(g2, Collections.singletonList("b1")), // no explicit initial state -> builder default false
                group(g3, Arrays.asList("c1", "c2"), false, true, true)
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(g3, true));   // middle-ish state change
        controller.dispatch(new CoreCommand.SetGroupExpanded(g1, true));   // earlier group affects later positions
        controller.dispatch(new CoreCommand.SetGroupExpanded(g2, true));
        controller.dispatch(new CoreCommand.SetGroupExpanded(g1, false));  // collapse shifts everything after

        List<ListSnapshot.Row<?>> rows = controller.snapshot().rows();
        int expectedFlat = 0;
        for (ListSnapshot.Row<?> row : rows) {
            assertEquals(expectedFlat, row.mapping().flatPosition());
            expectedFlat++;
        }
        assertEquals("header-g3", rows.get(4).data());
        assertEquals("footer-g3", rows.get(7).data());
        assertEquals(8, rows.size());
    }

    private static CoreRow.Standalone<String> standalone(String value) {
        return CoreRow.Standalone.of(value, 11);
    }

    private static CoreRow.Group<String, String, String> group(
            GroupKey key,
            java.util.List<String> children
    ) {
        return group(key, children, false, false, false);
    }

    @SuppressWarnings("SameParameterValue")
    private static CoreRow.Group<String, String, String> group(
            GroupKey key,
            java.util.List<String> children,
            boolean initiallyExpanded,
            boolean showFooterWhenCollapsed,
            boolean withFooter
    ) {
        return CoreRow.Group.<String, String, String>builder(key)
        .header("header-" + key.value())
        .footer(withFooter ? "footer-" + key.value() : null)
        .children(children)
        .initiallyExpanded(initiallyExpanded)
        .showFooterWhenCollapsed(showFooterWhenCollapsed)
        .headerViewType(21)
        .footerViewType(23)
        .childViewTypeResolver((childIndex, childData) -> 22)
        .build();
    }
}
