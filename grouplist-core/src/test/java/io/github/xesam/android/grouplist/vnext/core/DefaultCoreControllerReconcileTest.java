package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Contract tests for the {@link CoreCommand.ReconcileRows} visibility channel:
 * precise ChangeSet emission (sequential-application convention), expansion
 * survival across hidden periods, standalone alignment fail-loud, and the
 * perf-guard style sequential-equivalence invariant.
 */
public class DefaultCoreControllerReconcileTest {

    private static CoreRow.Group<String, String, String> group(String key, boolean initiallyExpanded, List<String> children) {
        return CoreRow.Group.<String, String, String>builder(GroupKey.of(key))
        .header("header:" + key)
        .footer("footer:" + key)
        .children(children)
        .initiallyExpanded(initiallyExpanded)
        .showFooterWhenCollapsed(false)
        .headerViewType(21)
        .footerViewType(23)
        .childViewTypeResolver((childIndex, childData) -> 22)
        .build();
    }

    private static List<String> childrenOf(int count) {
        List<String> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            children.add("child:" + i);
        }
        return children;
    }

    private static void assertOpEquals(ChangeSet.Operation op, ChangeSet.Type type, int positionStart, int itemCount) {
        assertEquals(type, op.type());
        assertEquals(positionStart, op.positionStart());
        assertEquals(itemCount, op.itemCount());
    }

    @Test
    public void reconcile_hideMiddleCollapsedGroup_shouldEmitSingleRemove() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:2", false, childrenOf(1))
        )));

        // old layout: [s0, g1.header] -> g1 block at flat 1, size 1 (collapsed)
        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        assertOpEquals(ops.get(0), ChangeSet.Type.REMOVE, 1, 1);
        assertEquals(2, controller.snapshot().itemCount());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_hideExpandedGroup_shouldRemoveWholeVisibleBlock() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:1", false, childrenOf(2)),
                group("g:expanded", false, childrenOf(3))
        ));
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g:expanded"))); // size: header + 3 children + footer
        controller.takeChangeSet(); // drain toggle operations before reconcile
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReconcileRows(
                Collections.singletonList(group("g:1", false, childrenOf(2)))
        ));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        // old layout: g1 block at 0 size 1; g2 block at 1, size = header + 3 children + footer = 5
        assertOpEquals(ops.get(0), ChangeSet.Type.REMOVE, 1, 5);
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_multipleRemovals_shouldEmitDescendingPositions() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:a", false, childrenOf(1)), // block at 0, size 1
                group("g:b", false, childrenOf(1)), // block at 1, size 1
                group("g:c", false, childrenOf(1))  // block at 2, size 1
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReconcileRows(
                Collections.singletonList(group("g:b", false, childrenOf(1)))
        ));

        // descending: remove g:c (pos 2) first, then g:a (pos 0)
        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(2, ops.size());
        assertOpEquals(ops.get(0), ChangeSet.Type.REMOVE, 2, 1);
        assertOpEquals(ops.get(1), ChangeSet.Type.REMOVE, 0, 1);
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_revealHiddenGroup_shouldInsertAtEvolvingPosition() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        ));
        controller.takeChangeSet(); // drain submit
        // hide g:1
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:2", false, childrenOf(1))
        )));
        controller.takeChangeSet(); // drain hide operations
        ListSnapshot before = controller.snapshot();

        // reveal g:1 between s0 and g:2
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        )));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        // evolving list after the hide: [s0, g2.header]; inserted block lands at 1
        assertOpEquals(ops.get(0), ChangeSet.Type.INSERT, 1, 1);
        // collapsed reveal: s0 + g1.header + g2.header
        assertEquals(3, controller.snapshot().itemCount());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_expansionSurvivesHiddenPeriod() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:1");
        controller.submit(Arrays.asList(
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        ));
        controller.takeChangeSet(); // drain submit
        controller.dispatch(new CoreCommand.ToggleGroup(key));
        controller.takeChangeSet(); // drain toggle
        assertTrue(controller.isGroupExpanded(key));

        // hide g:1 while expanded
        controller.dispatch(new CoreCommand.ReconcileRows(
                Collections.singletonList(group("g:2", false, childrenOf(1)))
        ));
        controller.takeChangeSet(); // drain hide
        assertTrue(controller.isGroupExpanded(key));
        ListSnapshot before = controller.snapshot();

        // reveal: intent carried out — INSERT must deliver header + 2 children + footer
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        )));
        assertTrue(controller.isGroupExpanded(key));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        assertOpEquals(ops.get(0), ChangeSet.Type.INSERT, 0, 4); // header + 2 children + footer
        // g1 visible rows (4) + g2 collapsed header (1)
        assertEquals(5, controller.snapshot().itemCount());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_interleavedRemoveAndInsert_shouldStaySequentiallyConsistent() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:a", false, childrenOf(2)), // collapsed: block at 0, size 1
                group("g:b", false, childrenOf(1)), // block at 1, size 1
                group("g:c", false, childrenOf(1))  // block at 2, size 1
        ));
        ListSnapshot before = controller.snapshot();

        // remove g:a and g:c, insert never-seen g:e after g:b — one reconcile
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:b", false, childrenOf(1)),
                group("g:e", true, childrenOf(1))   // brand-new key, initiallyExpanded
        )));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(3, ops.size());
        // descending removals first: g:c at old pos 2, then g:a at old pos 0
        assertOpEquals(ops.get(0), ChangeSet.Type.REMOVE, 2, 1);
        assertOpEquals(ops.get(1), ChangeSet.Type.REMOVE, 0, 1);
        // evolving list now [g:b.header]; INSERT g:e block (header + 1 child + footer) at 1
        assertOpEquals(ops.get(2), ChangeSet.Type.INSERT, 1, 3);
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_multipleInserts_shouldCountPrecedingInsertedBlocks() {
        // Regression guard: a single reconcile that reveals SEVERAL groups at once
        // (e.g. COMMENT_ONLY -> ALL_GROUPS) emits multiple INSERTs in ascending
        // order. Each INSERT's positionStart must count the preceding inserted
        // blocks already applied to the consumer's evolving list — skipping them
        // would shift every subsequent INSERT left and corrupt the
        // consumer's list structure.
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(1))
        ));
        ListSnapshot before = controller.snapshot();

        // reveal two NEW groups around the kept standalone and g:1
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:new-a", true, childrenOf(2)), // expansion intent taken from initiallyExpanded
                CoreRow.Standalone.of("s0", 11),
                group("g:new-b", true, childrenOf(1)),
                group("g:1", false, childrenOf(1))
        )));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        // INSERT g:new-a at 0 (header + 2 children + footer)
        assertOpEquals(ops.get(0), ChangeSet.Type.INSERT, 0, 4);
        // INSERT g:new-b: evolving list is [g:new-a(4 rows), s0] — position 5
        assertOpEquals(ops.get(1), ChangeSet.Type.INSERT, 5, 3);
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_multiRemoveAndMultiInsert_shouldStaySequentiallyConsistent() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:a", true, childrenOf(1)),
                group("g:b", true, childrenOf(2)),
                group("g:c", false, childrenOf(1)),
                CoreRow.Standalone.of("s0", 11)
        ));
        ListSnapshot before = controller.snapshot();

        // hide g:a and g:c, reveal never-seen g:x and g:y around the survivor g:b
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:x", true, childrenOf(1)),
                group("g:b", true, childrenOf(2)),
                group("g:y", false, childrenOf(2)),
                CoreRow.Standalone.of("s0", 11)
        )));
        // standalone count stays aligned: the sole s0 survives the reconcile.

        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_noVisibilityDelta_shouldBeSilentAndKeepRawRows() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        ));
        controller.takeChangeSet(); // drain the reload so emptiness below is attributable to the reconcile
        ListSnapshot before = controller.snapshot();

        // same visible set, fresh (equal) row objects
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:1", false, childrenOf(2)),
                group("g:2", false, childrenOf(1))
        )));

        assertTrue(controller.snapshot().changeSet().isEmpty());
        // silent path: raw Row objects are reused unchanged (no O(n) rebuild, no repaint)
        assertEquals(before.rows(), controller.snapshot().rows());
        assertEquals(before.itemCount(), controller.snapshot().itemCount());
    }

    @Test
    public void reconcile_standaloneContentDrift_shouldEmitUpdateInFinalCoordinates() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(1)),
                CoreRow.Standalone.of("s1", 11)
        ));
        ListSnapshot before = controller.snapshot();

        // mode drift on the trailing standalone; g:2 is brand new and expanded
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:2", true, childrenOf(1)),
                CoreRow.Standalone.of("s1:changed", 11)
        )));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(3, ops.size());
        // old layout: s0(0), g1.header(1), s1(2) -> REMOVE g:1 block at 1, descending
        assertOpEquals(ops.get(0), ChangeSet.Type.REMOVE, 1, 1);
        // INSERT g:2 block (header + 1 child + footer = 3) at evolving pos 1
        assertOpEquals(ops.get(1), ChangeSet.Type.INSERT, 1, 3);
        // UPDATE last: after the two ops above the evolved list already matches the
        // final layout: s0, g2(3), s1 -> changed s1 sits at index 4
        assertOpEquals(ops.get(2), ChangeSet.Type.UPDATE, 4, 1);
        assertEquals(5, controller.snapshot().itemCount());
        assertEquals("s1:changed", controller.snapshot().rows().get(4).data());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_standaloneViewTypeDrift_shouldEmitUpdate() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(1)),
                CoreRow.Standalone.of("s1", 11)
        ));
        ListSnapshot before = controller.snapshot();

        // same data, drifted viewType on the trailing standalone: content semantics
        // must consider viewType, otherwise the binder silently keeps the old mapping
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(1)),
                CoreRow.Standalone.of("s1", 99)
        )));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        assertOpEquals(ops.get(0), ChangeSet.Type.UPDATE, 2, 1);
        assertEquals(99, controller.snapshot().rows().get(2).viewType());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_standaloneCountDrift_shouldFailLoud() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(1))
        ));

        try {
            controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                    CoreRow.Standalone.of("s0", 11),
                    CoreRow.Standalone.of("s1", 11),
                    group("g:1", false, childrenOf(1))
            )));
            fail("expected IllegalArgumentException for standalone count drift");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("visibility-only"));
        }
    }

    @Test
    public void reconcile_standaloneRemovalDrift_shouldFailLoud() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(1)),
                CoreRow.Standalone.of("s1", 11)
        ));

        try {
            controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                    CoreRow.Standalone.of("s0", 11),
                    group("g:1", false, childrenOf(1))
            )));
            fail("expected IllegalArgumentException for standalone count drift (missing trailing standalone)");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("visibility-only"));
        }
    }

    @Test
    public void reconcile_hiddenGroupBetweenStandalones_shouldRemoveBlockOnly() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", false, childrenOf(2)),
                CoreRow.Standalone.of("s1", 11)
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                CoreRow.Standalone.of("s1", 11)
        )));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        assertOpEquals(ops.get(0), ChangeSet.Type.REMOVE, 1, 1);
        assertEquals(2, controller.snapshot().itemCount());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void reconcile_indexBookkeeping_survivesSubsequentToggleAfterHide() {
        // Regression: sourceIndexByKey must be rebuilt (not putIfAbsent-merged) by a
        // reconcile — a stale index made expand-toggle cast a Standalone to a Group
        // and crashed rebuildSnapshotAroundGroup.
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:hidden", false, childrenOf(1)), // visible block 0, size 1
                group("g:kept", false, childrenOf(2)),   // block at 1
                CoreRow.Standalone.of("s0", 11)
        ));

        // hide the leading group
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:kept", false, childrenOf(2)),
                CoreRow.Standalone.of("s0", 11)
        )));

        // toggle the surviving group: stale index of g:kept was 1 -> now a Standalone
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g:kept")));

        assertEquals(5, controller.snapshot().itemCount()); // header + 2 children + footer + standalone
        assertTrue(controller.isGroupExpanded(GroupKey.of("g:kept")));

        // and the previously hidden group can still be re-shown afterwards
        controller.dispatch(new CoreCommand.ReconcileRows(Arrays.asList(
                group("g:hidden", false, childrenOf(1)),
                group("g:kept", false, childrenOf(2)),
                CoreRow.Standalone.of("s0", 11)
        )));
        assertTrue(controller.isGroupExpanded(GroupKey.of("g:kept")));
        assertEquals(6, controller.snapshot().itemCount());
    }

    /**
     * Perf-guard style invariant: sequentially
     * applying the published ChangeSet operations to the BEFORE snapshot's rows
     * must land on a list structurally equal to the AFTER snapshot — same size,
     * and every kept (non-inserted) slot maps to an after-row identical in
     * kind / groupKey / childIndex / data / viewType. Inserted ranges must be
     * exactly the groups absent from the before rows.
     */
    private static void assertSequentialApplicationEquivalence(ListSnapshot before, DefaultCoreController controller) {
        ListSnapshot after = controller.snapshot();

        List<Object> evolved = new ArrayList<>();
        for (ListSnapshot.Row<?> row : before.rows()) {
            evolved.add(row);
        }
        for (ChangeSet.Operation op : after.changeSet().operations()) {
            switch (op.type()) {
                case REMOVE:
                    evolved.subList(op.positionStart(), op.positionStart() + op.itemCount()).clear();
                    break;
                case INSERT:
                    for (int i = 0; i < op.itemCount(); i++) {
                        evolved.add(op.positionStart() + i, INSERTED_TOKEN);
                    }
                    break;
                case UPDATE:
                    for (int i = 0; i < op.itemCount(); i++) {
                        int at = op.positionStart() + i;
                        Object current = evolved.get(at);
                        if (current instanceof ListSnapshot.Row) {
                            // An UPDATE must cover content that really changed; a
                            // no-op UPDATE would otherwise be masked by UPDATED_TOKEN.
                            ListSnapshot.Row<?> beforeRow = (ListSnapshot.Row<?>) current;
                            ListSnapshot.Row<?> afterRow = after.rows().get(at);
                            assertTrue("UPDATE at " + at + " must cover a changed row",
                                    beforeRow.viewType() != afterRow.viewType()
                                            || !java.util.Objects.equals(beforeRow.data(), afterRow.data()));
                        }
                        evolved.set(at, UPDATED_TOKEN);
                    }
                    break;
                default:
                    throw new AssertionError("reconcile must not emit " + op.type());
            }
        }

        assertEquals(after.itemCount(), evolved.size());

        // group keys visible in the BEFORE snapshot — inserted rows must belong to none of them
        java.util.Set<GroupKey> beforeGroupKeys = new java.util.HashSet<>();
        for (ListSnapshot.Row<?> row : before.rows()) {
            if (row.mapping().groupKey() != null) {
                beforeGroupKeys.add(row.mapping().groupKey());
            }
        }

        int insertedRows = 0;
        for (int i = 0; i < evolved.size(); i++) {
            ListSnapshot.Row<?> afterRow = after.rows().get(i);
            if (INSERTED_TOKEN.equals(evolved.get(i))) {
                insertedRows++;
                assertTrue("inserted rows must be group rows (standalones are never hidden)",
                        afterRow.mapping().groupKey() != null);
                assertTrue("inserted rows must not repeat a before-visible group",
                        !beforeGroupKeys.contains(afterRow.mapping().groupKey()));
            }
        }

        for (int i = 0; i < evolved.size(); i++) {
            if (INSERTED_TOKEN.equals(evolved.get(i)) || UPDATED_TOKEN.equals(evolved.get(i))) {
                continue;
            }
            ListSnapshot.Row<?> keptRow = (ListSnapshot.Row<?>) evolved.get(i);
            ListSnapshot.Row<?> afterRow = after.rows().get(i);
            assertEquals(keptRow.mapping().kind(), afterRow.mapping().kind());
            assertEquals(keptRow.mapping().groupKey(), afterRow.mapping().groupKey());
            assertEquals(keptRow.mapping().childIndex(), afterRow.mapping().childIndex());
            assertEquals(keptRow.data(), afterRow.data());
            assertEquals(keptRow.viewType(), afterRow.viewType());
        }
    }

    private static final String INSERTED_TOKEN = "<<INSERTED>>";
    private static final String UPDATED_TOKEN = "<<UPDATED>>";
}
