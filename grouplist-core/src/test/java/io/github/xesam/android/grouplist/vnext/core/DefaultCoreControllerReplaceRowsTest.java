package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

/**
 * Contract tests for the keyed full-reconcile channel
 * ({@link CoreCommand.ReplaceRows} / {@code RowDiff}): precise diff emission in
 * sequential-application coordinates, identity-driven in-place UPDATEs,
 * expansion survival, structural-misuse fail-loud, and the perf-guard style
 * sequential-equivalence invariant.
 */
public class DefaultCoreControllerReplaceRowsTest {

    private static CoreRow.Group<String, String, String> group(String key, boolean expanded, List<String> children) {
        return CoreRow.Group.<String, String, String>builder(GroupKey.of(key))
                .header("header:" + key)
                .footer("footer:" + key)
                .children(children)
                .initiallyExpanded(expanded)
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

    /** Identity strategy for test child models: keyed by an explicit id prefix ("id:x:payload"). */
    private static final ItemIdentity CHILD_IDENTITY = data -> {
        String s = String.valueOf(data);
        int idx = s.indexOf(':');
        int second = s.indexOf(':', idx + 1);
        return second < 0 ? s : s.substring(0, second + 1);
    };

    @Test
    public void replaceRows_sameStructure_emptyDelta() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", true, childrenOf(2))
        ));
        controller.takeChangeSet();
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", true, childrenOf(2))
        ), CHILD_IDENTITY));

        ChangeSet cs = controller.takeChangeSet();
        assertTrue("identical rows must produce an empty delta (no repaint)", cs.isEmpty());
        assertEquals(before.itemCount(), controller.snapshot().itemCount());
    }

    @Test
    public void replaceRows_identityMatch_contentChangeBecomesInPlaceUpdate() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("id:0:old", 11),
                group("g:1", true, Arrays.asList("id:0:c-old", "id:1:c-kept"))
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                CoreRow.Standalone.of("id:0:new", 11),
                group("g:1", true, Arrays.asList("id:0:c-new", "id:1:c-kept"))
        ), CHILD_IDENTITY));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations(); // non-draining: the equivalence helper below reads the same changeSet
        // every identity-matched row with changed data is an in-place UPDATE
        List<ChangeSet.Operation> updates = new ArrayList<>();
        for (ChangeSet.Operation op : ops) {
            assertEquals(ChangeSet.Type.UPDATE, op.type());
            updates.add(op);
        }
        assertEquals(2, updates.size());
        assertEquals("id:0:new", controller.snapshot().rows().get(0).data());
        assertEquals("id:0:c-new", controller.snapshot().rows().get(2).data());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void replaceRows_childInsertAndRemove_emitPreciseOps() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:1", true, childrenOf(3)),
                CoreRow.Standalone.of("s0", 11)
        ));
        ListSnapshot before = controller.snapshot();

        // remove child 0, insert a new child at the tail
        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                group("g:1", true, Arrays.asList("child:1", "child:2", "child:new")),
                CoreRow.Standalone.of("s0", 11)
        ), CHILD_IDENTITY));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations(); // non-draining: the equivalence helper below reads the same changeSet
        assertEquals(2, ops.size());
        assertEquals(ChangeSet.Type.REMOVE, ops.get(0).type());
        assertEquals(1, ops.get(0).positionStart()); // first child sits right after the header
        assertEquals(1, ops.get(0).itemCount());
        assertEquals(ChangeSet.Type.INSERT, ops.get(1).type());
        assertEquals(3, ops.get(1).positionStart()); // tail child lands after the two kept children
        assertEquals(1, ops.get(1).itemCount());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void replaceRows_reorderedGroup_showsUpAsRemovePlusInsert() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:1", true, childrenOf(1)),
                group("g:2", false, childrenOf(1))
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                group("g:2", false, childrenOf(1)),
                group("g:1", true, childrenOf(1))
        ), CHILD_IDENTITY));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations(); // non-draining: the equivalence helper below reads the same changeSet
        assertEquals("a reordered block emits exactly its REMOVE + INSERT (no MOVE, no filler ops)", 2, ops.size());
        assertEquals(ChangeSet.Type.INSERT, ops.get(0).type());
        assertEquals(0, ops.get(0).positionStart()); // g:2's header becomes the new first row
        assertEquals(1, ops.get(0).itemCount());
        assertEquals(ChangeSet.Type.REMOVE, ops.get(1).type());
        assertEquals(4, ops.get(1).positionStart()); // g:2's old header, still last before the removal
        assertEquals(1, ops.get(1).itemCount());
        assertSequentialApplicationEquivalence(before, controller);
    }

    /**
     * Default identity ({@link ItemIdentity#DATA_EQUALS}, used when {@code identity}
     * is null): a content change of a key-less row has no identity match, so the
     * diff must present it as REMOVE + INSERT instead of an in-place UPDATE.
     */
    @Test
    public void replaceRows_defaultIdentity_contentChangeBecomesRemovePlusInsert() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", true, childrenOf(1))
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                CoreRow.Standalone.of("s0-changed", 11),
                group("g:1", true, childrenOf(1))
        ), null)); // null identity -> DATA_EQUALS

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations(); // non-draining: the equivalence helper below reads the same changeSet
        assertEquals(2, ops.size());
        assertEquals(ChangeSet.Type.REMOVE, ops.get(0).type());
        assertEquals(0, ops.get(0).positionStart());
        assertEquals(1, ops.get(0).itemCount());
        assertEquals(ChangeSet.Type.INSERT, ops.get(1).type());
        assertEquals(0, ops.get(1).positionStart());
        assertEquals(1, ops.get(1).itemCount());
        assertEquals("s0-changed", controller.snapshot().rows().get(0).data());
        assertSequentialApplicationEquivalence(before, controller);
    }

    @Test
    public void replaceRows_expansionState_survivesReplacementAndVanishing() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                group("g:1", false, childrenOf(1)),
                group("g:2", false, childrenOf(1))
        ));
        controller.dispatch(new CoreCommand.SetGroupExpanded(GroupKey.of("g:2"), true));
        int expandedSize = controller.snapshot().itemCount(); // header + child + footer for g:2

        // g:2 vanishes WHILE expanded: its intent must outlive the vanish
        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                group("g:1", false, childrenOf(1))
        ), CHILD_IDENTITY));
        assertTrue("vanished keys keep their expansion state (hidden = paused)",
                controller.isGroupExpanded(GroupKey.of("g:2")));
        assertEquals(expandedSize - 3, controller.snapshot().itemCount());

        // g:2 reappears: its pre-vanish intent is carried out as visible children
        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                group("g:1", false, childrenOf(1)),
                group("g:2", false, childrenOf(1))
        ), CHILD_IDENTITY));
        assertTrue(controller.isGroupExpanded(GroupKey.of("g:2")));
        assertEquals(expandedSize, controller.snapshot().itemCount());
        assertEquals(GroupKey.of("g:2"), controller.snapshot().rows().get(2).mapping().groupKey());
        assertEquals(PositionKind.GROUP_ITEM, controller.snapshot().rows().get(2).mapping().kind());
    }

    @Test
    public void replaceRows_standaloneAddedAndRemoved_diffedWithoutCountConstraint() {
        // ReplaceRows, in contrast to ReconcileRows, tolerates standalone count drift
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", true, childrenOf(1))
        ));
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                group("g:1", true, childrenOf(1)),
                CoreRow.Standalone.of("id:9:new-trailing", 11)
        ), CHILD_IDENTITY));

        List<ChangeSet.Operation> ops = controller.snapshot().changeSet().operations(); // non-draining: the equivalence helper below reads the same changeSet
        assertEquals(2, ops.size());
        assertEquals(ChangeSet.Type.REMOVE, ops.get(0).type());
        assertEquals(0, ops.get(0).positionStart()); // leading standalone removed
        assertEquals(1, ops.get(0).itemCount());
        assertEquals(ChangeSet.Type.INSERT, ops.get(1).type());
        assertEquals(3, ops.get(1).positionStart()); // trailing standalone appended behind the group's footer
        assertEquals(1, ops.get(1).itemCount());
        assertSequentialApplicationEquivalence(before, controller);
        assertEquals(4, controller.snapshot().itemCount());
    }

    @Test
    public void replaceRows_nullRows_shouldThrowAndKeepState() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", true, childrenOf(1))
        ));
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g:1")));
        controller.takeChangeSet();
        ListSnapshot before = controller.snapshot();

        try {
            controller.dispatch(new CoreCommand.ReplaceRows(null, null));
            fail("expected IllegalArgumentException for null rows");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be null"));
        }
        // pre-mutation gate: no half-applied state
        assertEquals(before.itemCount(), controller.snapshot().itemCount());
    }

    @Test
    public void replaceRows_nullElement_shouldThrowAndKeepState() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(group("g:1", true, childrenOf(1))));
        ListSnapshot before = controller.snapshot();

        List<CoreRow> rows = new ArrayList<>();
        rows.add(group("g:1", true, childrenOf(1)));
        rows.add(null);
        try {
            controller.dispatch(new CoreCommand.ReplaceRows(rows, null));
            fail("expected IllegalArgumentException for a null rows element");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null elements"));
        }
        // pre-mutation gate: the controller still holds the exact same snapshot
        assertSame(before, controller.snapshot());
    }

    @Test
    public void replaceRows_duplicateKeys_shouldThrowAndKeepState() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(group("g:1", true, childrenOf(1))));
        ListSnapshot before = controller.snapshot();

        try {
            controller.dispatch(new CoreCommand.ReplaceRows(Arrays.asList(
                    group("g:1", true, childrenOf(1)),
                    group("g:1", true, childrenOf(2))
            ), null));
            fail("expected IllegalArgumentException for duplicate groupKey");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate groupKey"));
        }
        // pre-mutation gate: controller keeps its pre-dispatch state (same snapshot instance)
        assertSame(before, controller.snapshot());
    }

    /**
     * Sequential-application equivalence (same invariant as the ReconcileRows tests):
     * applying the published ChangeSet to the BEFORE snapshot must land on a list
     * structurally equal to the AFTER snapshot.
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
                    throw new AssertionError("ReplaceRows must not emit " + op.type());
            }
        }

        assertEquals(after.itemCount(), evolved.size());

        for (int i = 0; i < evolved.size(); i++) {
            ListSnapshot.Row<?> afterRow = after.rows().get(i);
            Object slot = evolved.get(i);
            if (INSERTED_TOKEN.equals(slot) || UPDATED_TOKEN.equals(slot)) {
                continue;
            }
            ListSnapshot.Row<?> keptRow = (ListSnapshot.Row<?>) slot;
            assertEquals(keptRow.mapping().kind(), afterRow.mapping().kind());
            assertEquals(keptRow.mapping().groupKey(), afterRow.mapping().groupKey());
            // childIndex deliberately NOT compared: it is positional and gets renumbered
            // by the rebuild when a preceding sibling is removed/inserted.
            assertEquals(keptRow.data(), afterRow.data());
            assertEquals(keptRow.viewType(), afterRow.viewType());
        }
    }

    private static final String INSERTED_TOKEN = "<<INSERTED>>";
    private static final String UPDATED_TOKEN = "<<UPDATED>>";
}
