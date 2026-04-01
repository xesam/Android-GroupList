package io.github.xesam.android.grouplist.vnext.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

public class DefaultEngineControllerCommandTest {

    @Test
    public void replaceChildren_whenGroupCollapsed_shouldKeepCollapsedAndRevealNewChildrenAfterExpand() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Collections.singletonList("old"))));

        controller.dispatch(new EngineCommand.ReplaceChildren(key, Arrays.asList("new-1", "new-2")));

        assertEquals(1, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());

        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));

        assertEquals(4, controller.snapshot().itemCount());
        assertEquals("new-1", controller.snapshot().rows().get(1).data());
        assertEquals("new-2", controller.snapshot().rows().get(2).data());
    }

    @Test
    public void replaceChildren_whenGroupExpanded_shouldRebuildVisibleRowsWithNewChildren() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Collections.singletonList("old"))));
        controller.takeChangeSet(); // drain submit
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        controller.takeChangeSet(); // drain expansion before replaceChildren

        controller.dispatch(new EngineCommand.ReplaceChildren(key, Arrays.asList("new-1", "new-2", "new-3")));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals(5, snapshot.itemCount());
        assertEquals("new-1", snapshot.rows().get(1).data());
        assertEquals("new-2", snapshot.rows().get(2).data());
        assertEquals("new-3", snapshot.rows().get(3).data());

        // precise change: 1 in-place UPDATE + 2 INSERT at tail, no notifyDataSetChanged fallback.
        ChangeSet changeSet = snapshot.changeSet();
        assertEquals(2, changeSet.operations().size());
        assertEquals(ChangeSet.Type.UPDATE, changeSet.operations().get(0).type());
        assertEquals(1, changeSet.operations().get(0).positionStart());
        assertEquals(1, changeSet.operations().get(0).itemCount());
        assertEquals(ChangeSet.Type.INSERT, changeSet.operations().get(1).type());
        assertEquals(2, changeSet.operations().get(1).positionStart());
        assertEquals(2, changeSet.operations().get(1).itemCount());
    }

    @Test
    public void replaceChildren_whenGroupCollapsed_shouldEmitEmptyChangeSet() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Collections.singletonList("old"))));
        controller.takeChangeSet(); // drain the reload

        controller.dispatch(new EngineCommand.ReplaceChildren(key, Collections.singletonList("new-1")));

        assertFalse(controller.hasPendingChanges());
        assertEquals(1, controller.snapshot().itemCount());
    }

    @Test
    public void replaceChildren_whenGroupFilteredOut_shouldEmitEmptyChangeSetAndKeepOverride() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .setGroupVisibilityResolver((groupKey, sourceGroup, filterPayload) -> {
                    if (!(filterPayload instanceof String) || ((String) filterPayload).isEmpty()) {
                        return true;
                    }
                    return groupKey.value().contains((String) filterPayload);
                })
                .build();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Collections.singletonList("old"))));
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        controller.dispatch(new EngineCommand.SetVisibilityFilter("group-b"));
        controller.takeChangeSet(); // drain the reveal/hide delta

        controller.dispatch(new EngineCommand.ReplaceChildren(key, Collections.singletonList("new-1")));

        assertFalse(controller.hasPendingChanges());
        assertEquals(0, controller.snapshot().itemCount());

        controller.dispatch(new EngineCommand.SetVisibilityFilter(""));
        // narrowing: the group returns with its expansion intent and the override revealed
        assertTrue(controller.isGroupExpanded(key));
        assertEquals(3, controller.snapshot().itemCount()); // header + overridden child + footer
        assertEquals("new-1", controller.snapshot().rows().get(1).data());
        // precise block INSERT, no repaint fallback
        List<ChangeSet.Operation> revealOps = controller.snapshot().changeSet().operations();
        assertEquals(1, revealOps.size());
        assertEquals(ChangeSet.Type.INSERT, revealOps.get(0).type());
        assertEquals(0, revealOps.get(0).positionStart());
        assertEquals(3, revealOps.get(0).itemCount());
    }

    @Test
    public void replaceChildren_whenGroupShrinks_shouldEmitUpdateThenRemove() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Arrays.asList("a1", "a2", "a3"))));
        controller.takeChangeSet(); // drain submit
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        controller.takeChangeSet(); // drain expansion before replaceChildren

        controller.dispatch(new EngineCommand.ReplaceChildren(key, Arrays.asList("b1")));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals(3, snapshot.itemCount());
        assertEquals("b1", snapshot.rows().get(1).data());
        ChangeSet changeSet = snapshot.changeSet();
        assertEquals(2, changeSet.operations().size());
        assertEquals(ChangeSet.Type.UPDATE, changeSet.operations().get(0).type());
        assertEquals(ChangeSet.Type.REMOVE, changeSet.operations().get(1).type());
        assertEquals(2, changeSet.operations().get(1).positionStart()); // after header(pos 0) + 1 in-place row
        assertEquals(2, changeSet.operations().get(1).itemCount());
    }

    @Test
    public void setVisibilityFilter_isNarrowing_restoreKeepsExpansionIntent() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .setGroupVisibilityResolver((groupKey, sourceGroup, filterPayload) -> {
                    if (!(filterPayload instanceof String) || ((String) filterPayload).isEmpty()) {
                        return true;
                    }
                    return groupKey.value().contains((String) filterPayload);
                })
                .build();

        controller.submit(Arrays.asList(
                new FakeGroup("group-a", Arrays.asList("a1", "a2")),
                new FakeGroup("group-b", Collections.singletonList("b1"))
        ));
        controller.dispatch(new EngineCommand.SetGroupExpanded(GroupKey.of("group-a"), true));
        controller.dispatch(new EngineCommand.SetVisibilityFilter("group-b"));

        assertEquals(1, controller.snapshot().itemCount());
        assertEquals("header-group-b", controller.snapshot().rows().get(0).data());

        controller.dispatch(new EngineCommand.SetVisibilityFilter(""));

        // narrowing restore: expansion intent survives the hidden period
        ListSnapshot snapshot = controller.snapshot();
        assertTrue(controller.isGroupExpanded(GroupKey.of("group-a")));
        assertEquals(5, snapshot.itemCount()); // header + a1/a2 + footer of group-a, then group-b's header
        assertEquals("header-group-a", snapshot.rows().get(0).data());
        assertEquals("a1", snapshot.rows().get(1).data());
        assertEquals("a2", snapshot.rows().get(2).data());
        assertEquals("footer-group-a", snapshot.rows().get(3).data());
        assertEquals("header-group-b", snapshot.rows().get(4).data());
    }

    @Test
    public void multipleDispatches_shouldKeepLatestChildrenAndExpandedStateConsistent() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Arrays.asList("a1", "a2"))));

        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        controller.dispatch(new EngineCommand.ReplaceChildren(key, Collections.singletonList("b1")));
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, false));
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals(3, snapshot.itemCount());
        assertEquals(PositionKind.GROUP_HEADER, snapshot.rows().get(0).mapping().kind());
        assertEquals("b1", snapshot.rows().get(1).data());
        assertEquals(PositionKind.GROUP_FOOTER, snapshot.rows().get(2).mapping().kind());
    }

    @Test
    public void multipleSchemas_shouldCoexistAndOnlyAffectTargetGroup() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerSchema(AltGroup.class, new AltGroupSchema())
                .registerViewType(String.class, 100)
                .build();

        controller.submit(Arrays.asList(
                new FakeGroup("group-a", Collections.singletonList("a1")),
                new AltGroup("alt-b", Arrays.asList("b1", "b2"))
        ));

        controller.dispatch(new EngineCommand.SetGroupExpanded(GroupKey.of("alt-b"), true));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals(5, snapshot.itemCount());
        assertEquals("header-group-a", snapshot.rows().get(0).data());
        assertEquals("ALT-alt-b", snapshot.rows().get(1).data());
        assertEquals("b1", snapshot.rows().get(2).data());
        assertEquals("b2", snapshot.rows().get(3).data());
        assertEquals("alt-footer-alt-b", snapshot.rows().get(4).data());
    }

    @Test
    public void setVisibilityFilter_shouldNarrowWithPreciseChangeSet() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .setGroupVisibilityResolver((groupKey, sourceGroup, filterPayload) -> {
                    if (!(filterPayload instanceof String)) {
                        return true;
                    }
                    return groupKey.value().contains((String) filterPayload);
                })
                .build();

        controller.submit(Arrays.asList(
                new FakeGroup("group-a", Collections.singletonList("a1")),
                new FakeGroup("group-b", Collections.singletonList("b1"))
        ));
        controller.dispatch(new EngineCommand.SetVisibilityFilter("group-b"));

        // narrowing: hidden group-a becomes a precise block REMOVE, not an empty repaint
        ChangeSet changeSet = controller.snapshot().changeSet();
        assertEquals(1, changeSet.operations().size());
        assertEquals(ChangeSet.Type.REMOVE, changeSet.operations().get(0).type());
        assertEquals(0, changeSet.operations().get(0).positionStart());
        assertEquals(1, changeSet.operations().get(0).itemCount());
        assertEquals(1, controller.snapshot().itemCount());
        // both groups are collapsed one-row blocks: pin the survivor, or an inverted
        // resolver would produce the same REMOVE coordinates
        assertEquals("header-group-b", controller.snapshot().rows().get(0).data());
    }

    @Test
    public void rapidToggle_shouldKeepStateConsistent() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Arrays.asList("a1", "a2"))));
        controller.takeChangeSet(); // drain submit

        controller.dispatch(new EngineCommand.ToggleGroup(key));
        controller.dispatch(new EngineCommand.ToggleGroup(key));
        controller.dispatch(new EngineCommand.ToggleGroup(key));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals(4, snapshot.itemCount());
        // With accumulation: 3 toggles produce 3 operations (expand, collapse, expand)
        // even though the net effect is one expansion
        List<ChangeSet.Operation> ops = snapshot.changeSet().operations();
        assertEquals(3, ops.size());
        assertEquals(ChangeSet.Type.INSERT, ops.get(0).type());
        assertEquals(1, ops.get(0).positionStart());
        assertEquals(3, ops.get(0).itemCount()); // 2 children + footer behind the header
        assertEquals(ChangeSet.Type.REMOVE, ops.get(1).type());
        assertEquals(1, ops.get(1).positionStart());
        assertEquals(3, ops.get(1).itemCount());
        assertEquals(ChangeSet.Type.INSERT, ops.get(2).type());
        assertEquals(1, ops.get(2).positionStart());
        assertEquals(3, ops.get(2).itemCount());
    }

    @Test
    public void rapidExpandCollapse_shouldEmitAccumulatedChangeSetNotNetZero() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Arrays.asList("a1", "a2"))));
        controller.takeChangeSet(); // drain submit

        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, false));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals(1, snapshot.itemCount());
        // With accumulation: expand then collapse produces 2 operations even though net effect is no-op
        List<ChangeSet.Operation> ops = snapshot.changeSet().operations();
        assertEquals(2, ops.size());
        assertEquals(ChangeSet.Type.INSERT, ops.get(0).type());
        assertEquals(1, ops.get(0).positionStart());
        assertEquals(3, ops.get(0).itemCount());
        assertEquals(ChangeSet.Type.REMOVE, ops.get(1).type());
        assertEquals(1, ops.get(1).positionStart());
        assertEquals(3, ops.get(1).itemCount());
    }

    @Test
    public void isGroupExpanded_reflectsCoreStateAndResetsAfterSubmit() {
        DefaultEngineController controller = newController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Collections.singletonList("a1"))));

        assertFalse(controller.isGroupExpanded(key));
        controller.dispatch(new EngineCommand.ToggleGroup(key));
        assertTrue(controller.isGroupExpanded(key));
        controller.dispatch(new EngineCommand.ToggleGroup(key));
        assertFalse(controller.isGroupExpanded(key));

        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        assertTrue(controller.isGroupExpanded(key));

        // submit is a reload: expansion state resets to initiallyExpanded
        controller.submit(Collections.singletonList(new FakeGroup("group-a", Collections.singletonList("a1"))));
        assertFalse(controller.isGroupExpanded(key));

        // unknown keys are collapsed, never throw
        assertFalse(controller.isGroupExpanded(GroupKey.of("missing")));
    }

    private static DefaultEngineController newController() {
        return DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .build();
    }

    private static final class FakeGroup {
        final String id;
        final java.util.List<String> children;

        FakeGroup(String id, java.util.List<String> children) {
            this.id = id;
            this.children = children;
        }
    }

    private static final class AltGroup {
        final String id;
        final java.util.List<String> entries;

        AltGroup(String id, java.util.List<String> entries) {
            this.id = id;
            this.entries = entries;
        }
    }

    @Test
    public void updateStandalone_shouldReplaceInPlaceWithPreciseUpdateAndKeepExpansion() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .build();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Arrays.asList(
                "card:ALL_GROUPS",
                new FakeGroup("group-a", Arrays.asList("a1", "a2"))
        ));
        controller.takeChangeSet(); // drain submit
        controller.dispatch(new EngineCommand.SetGroupExpanded(key, true));
        controller.takeChangeSet(); // drain expansion before updateStandalone

        controller.dispatch(EngineCommand.UpdateStandalone.ofData("card:ALL_GROUPS", "card:COMMENT_ONLY"));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals("card:COMMENT_ONLY", snapshot.rows().get(0).data());
        // precise in-place UPDATE, not a reload
        List<ChangeSet.Operation> ops = snapshot.changeSet().operations();
        assertEquals(1, ops.size());
        assertEquals(ChangeSet.Type.UPDATE, ops.get(0).type());
        assertEquals(0, ops.get(0).positionStart());
        assertEquals(1, ops.get(0).itemCount());
        // expansion untouched
        assertTrue(controller.isGroupExpanded(key));
        assertEquals(5, snapshot.itemCount()); // card + header + 2 children + footer
    }

    @Test
    public void updateStandalone_ofIndex_shouldUpdateTheExactSlotAndNothingElse() {
        DefaultEngineController controller = newController();
        controller.submit(Arrays.asList("a", "b", "c"));
        controller.takeChangeSet();

        controller.dispatch(EngineCommand.UpdateStandalone.ofIndex(1, "b-new"));

        // exactly one precise UPDATE at the touched flat position
        assertEquals("b-new", controller.snapshot().rows().get(1).data());
        assertEquals("a", controller.snapshot().rows().get(0).data());
        assertEquals("c", controller.snapshot().rows().get(2).data());
        assertEquals(java.util.Collections.singletonList("UPD:1"), summarizeOps(controller.takeChangeSet()));
    }

    @Test
    public void updateStandalone_ofData_withDuplicateEquals_shouldUpdateFirstSlotOnly() {
        DefaultEngineController controller = newController();
        controller.submit(Arrays.asList("dup", "x", "dup"));
        controller.takeChangeSet();

        controller.dispatch(EngineCommand.UpdateStandalone.ofData("dup", "changed"));

        assertEquals("changed", controller.snapshot().rows().get(0).data());
        assertEquals("dup", controller.snapshot().rows().get(2).data()); // second untouched
        assertEquals(java.util.Collections.singletonList("UPD:0"), summarizeOps(controller.takeChangeSet()));
    }

    @Test
    public void updateStandalone_ofIndexBeyondSlots_shouldFailSilent() {
        DefaultEngineController controller = newController();
        controller.submit(Arrays.asList("a", "b"));
        controller.takeChangeSet();

        controller.dispatch(EngineCommand.UpdateStandalone.ofIndex(5, "x"));

        assertFalse(controller.hasPendingChanges());
        assertEquals("a", controller.snapshot().rows().get(0).data());
    }

    private static java.util.List<String> summarizeOps(ChangeSet changeSet) {
        java.util.List<String> ops = new java.util.ArrayList<>();
        for (ChangeSet.Operation op : changeSet.operations()) {
            String type = op.type() == ChangeSet.Type.UPDATE ? "UPD"
                    : op.type() == ChangeSet.Type.INSERT ? "INS" : op.type().toString();
            ops.add(type + ":" + op.positionStart());
        }
        return ops;
    }

    @Test
    public void updateStandalone_unknownData_shouldFailSilent() {
        DefaultEngineController controller = newController();
        controller.submit(Collections.singletonList("solo"));
        controller.takeChangeSet(); // drain the reload

        controller.dispatch(EngineCommand.UpdateStandalone.ofData("missing", "new"));

        assertFalse(controller.hasPendingChanges());
        assertEquals("solo", controller.snapshot().rows().get(0).data());
    }

    private static final class FakeGroupSchema implements GroupSchema<FakeGroup, String, String, String> {
        @Override
        public GroupKey keyOf(FakeGroup group) {
            return GroupKey.of(group.id);
        }

        @Override
        public String headerOf(FakeGroup group) {
            return "header-" + group.id;
        }

        @Override
        public java.util.List<String> childrenOf(FakeGroup group) {
            return group.children;
        }

        @Override
        public String footerOf(FakeGroup group) {
            return "footer-" + group.id;
        }

        @Override
        public boolean initiallyExpanded(FakeGroup group) {
            return false;
        }

        @Override
        public boolean showFooterWhenCollapsed(FakeGroup group) {
            return false;
        }
    }

    private static final class AltGroupSchema implements GroupSchema<AltGroup, String, String, String> {
        @Override
        public GroupKey keyOf(AltGroup group) {
            return GroupKey.of(group.id);
        }

        @Override
        public String headerOf(AltGroup group) {
            return "ALT-" + group.id;
        }

        @Override
        public java.util.List<String> childrenOf(AltGroup group) {
            return group.entries;
        }

        @Override
        public String footerOf(AltGroup group) {
            return "alt-footer-" + group.id;
        }

        @Override
        public boolean initiallyExpanded(AltGroup group) {
            return false;
        }

        @Override
        public boolean showFooterWhenCollapsed(AltGroup group) {
            return false;
        }
    }
}
