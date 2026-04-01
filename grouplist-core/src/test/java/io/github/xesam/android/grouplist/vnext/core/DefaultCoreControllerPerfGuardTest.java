package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

/**
 * Load-shaped guard for the single-dispatch incremental path: after a heavy mixed
 * command sequence the flat model must stay exactly consistent (positions contiguous,
 * group regions well-formed) and finish within a generous timeout that guards against
 * gross algorithmic collapses (e.g. a quadratic accidental path).
 *
 * Scope note (honesty clause): this test checks FINAL-STATE consistency only. It does
 * NOT notarize ChangeSet sequential-application equivalence — that invariant is pinned
 * by {@link ChangeSetApplicabilityTest} / {@link ChangeSetApplicabilityFuzzTest}
 * (per-op simulation oracle) and the equivalence helpers in
 * {@link DefaultCoreControllerReconcileTest} / {@link DefaultCoreControllerReplaceRowsTest}.
 * Nor does it catch a silent regression of the segment-rebuild optimization back to a
 * full rebuild — a full rebuild produces the same structural output. That regression
 * is guarded by the REFERENCE-IDENTITY assertions in {@link DefaultCoreControllerReuseTest}.
 */
public class DefaultCoreControllerPerfGuardTest {

    private static final int GROUP_COUNT = 400;
    private static final int CHILDREN_PER_GROUP = 3;
    private static final int TOGGLE_ROUNDS = 3; // every group toggled N times

    @Test(timeout = 10_000)
    public void heavyMixedCommands_keepFlatModelExactlyConsistent() {
        DefaultCoreController controller = new DefaultCoreController();
        List<CoreRow> rows = new ArrayList<>();
        List<GroupKey> keys = new ArrayList<>();
        for (int i = 0; i < GROUP_COUNT; i++) {
            GroupKey key = GroupKey.of("group-" + i);
            keys.add(key);
            rows.add(CoreRow.Group.builder(key)
                    .header("header-" + i)
                    .footer(null)
                    .children(Arrays.asList("c0-" + i, "c1-" + i, "c2-" + i))
                    .initiallyExpanded(i % 2 == 0) // half initially expanded
                    .showFooterWhenCollapsed(false)
                    .headerViewType(21)
                    .footerViewType(21)
                    .childViewTypeResolver((childIndex, childData) -> 22)
                    .build());
        }
        List<List<String>> expectedChildren = new ArrayList<>();
        for (int g = 0; g < GROUP_COUNT; g++) {
            expectedChildren.add(Arrays.asList("c0-" + g, "c1-" + g, "c2-" + g));
        }
        controller.submit(rows);

        // deterministic "random" walk over groups
        long seed = 42;
        for (int round = 0; round < TOGGLE_ROUNDS; round++) {
            for (int i = 0; i < GROUP_COUNT; i++) {
                // unsigned shift keeps the index non-negative (no Math.abs edge case)
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                GroupKey key = keys.get((int) ((seed >>> 16) % GROUP_COUNT));
                if ((seed & 1L) == 0L) {
                    controller.dispatch(new CoreCommand.ToggleGroup(key));
                } else {
                    controller.dispatch(new CoreCommand.SetGroupExpanded(key, (seed & 2L) != 0L));
                }
                // interleave child replacement on visible groups
                if (i % 7 == 0) {
                    controller.dispatch(new CoreCommand.ReplaceChildren(
                            keys.get(i),
                            Arrays.asList("r0-" + i, "r1-" + i)
                    ));
                    expectedChildren.set(i, Arrays.asList("r0-" + i, "r1-" + i));
                }
            }
            // Drain accumulated operations periodically to prevent unbounded accumulation
            controller.takeChangeSet();
        }

        ListSnapshot snapshot = controller.snapshot();
        List<ListSnapshot.Row<?>> flat = snapshot.rows();

        // invariant 1: flat positions are exactly 0..n-1. (No size assertion here:
        // itemCount() is rows().size(), so comparing the two would be a tautology.
        // The walked index at the end of invariant 2 is what pins the total size.)
        for (int i = 0; i < flat.size(); i++) {
            assertEquals(i, flat.get(i).mapping().flatPosition());
        }

        // invariant 2: every expanded group shows its current children in order,
        // every collapsed group hides them; isGroupExpanded agrees with the flat model
        int index = 0;
        for (int g = 0; g < GROUP_COUNT; g++) {
            ListSnapshot.Row<?> header = flat.get(index++);
            assertEquals(PositionKind.GROUP_HEADER, header.mapping().kind());
            GroupKey key = keys.get(g);
            assertEquals(key, header.mapping().groupKey());
            assertEquals("header-" + g, header.data());

            boolean expanded = controller.isGroupExpanded(key);
            if (expanded) {
                List<String> children = expectedChildren.get(g);
                for (int c = 0; c < children.size(); c++) {
                    ListSnapshot.Row<?> child = flat.get(index++);
                    assertEquals(PositionKind.GROUP_ITEM, child.mapping().kind());
                    assertEquals(key, child.mapping().groupKey());
                    assertEquals(c, child.mapping().childIndex());
                    assertEquals(children.get(c), child.data());
                }
            }
        }
        assertEquals(flat.size(), index);
    }
}
