package io.github.xesam.android.grouplist.vnext.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.core.CoreRow;
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

public class DefaultEngineControllerTest {

    @Test
    public void submitAndToggle_shouldUseSchemaAndEmitSnapshot() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();

        controller.submit(Arrays.asList(
                "standalone",
                new FakeGroup("group-a", Arrays.asList("c1", "c2"))
        ));

        assertEquals(2, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(1).mapping().kind());

        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));

        // standalone + header + 2 children + footer (FakeGroupSchema always defines a footer)
        assertEquals(5, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_ITEM, controller.snapshot().rows().get(2).mapping().kind());
        assertEquals(PositionKind.GROUP_FOOTER, controller.snapshot().rows().get(4).mapping().kind());
    }

    @Test
    public void setVisibilityFilter_shouldOnlyAffectGroups() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .setGroupVisibilityResolver((groupKey, sourceGroup, filterPayload) -> {
                    if (!(filterPayload instanceof String)) {
                        return true;
                    }
                    String expected = (String) filterPayload;
                    return groupKey.value().contains(expected);
                })
        .build();

        controller.submit(Arrays.asList(
                "standalone",
                new FakeGroup("group-a", Arrays.asList("c1")),
                new FakeGroup("group-b", Arrays.asList("c2"))
        ));
        assertEquals(3, controller.snapshot().itemCount());

        controller.dispatch(new EngineCommand.SetVisibilityFilter("b"));
        assertEquals(2, controller.snapshot().itemCount());
        assertEquals("standalone", controller.snapshot().rows().get(0).data());
        // Both groups are collapsed one-row blocks, so "one REMOVE at 1" alone cannot
        // tell which group survived — the resolver direction could be inverted and the
        // counts would still match. Pin the survivor explicitly.
        assertEquals("header-group-b", controller.snapshot().rows().get(1).data());
        // narrowing: precise REMOVE of group-a's block, standalone untouched
        assertEquals(1, controller.snapshot().changeSet().operations().size());
        assertEquals(ChangeSet.Type.REMOVE,
                controller.snapshot().changeSet().operations().get(0).type());
        assertEquals(1, controller.snapshot().changeSet().operations().get(0).positionStart());
        assertEquals(1, controller.snapshot().changeSet().operations().get(0).itemCount());
    }

    @Test
    public void setVisibilityFilter_isNarrowing_expansionSurvivesHideAndRestore() {
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
                "standalone",
                new FakeGroup("group-a", Arrays.asList("a1", "a2")),
                new FakeGroup("group-b", Arrays.asList("b1"))
        ));
        controller.takeChangeSet(); // drain submit

        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        controller.takeChangeSet(); // drain toggle
        assertEquals(6, controller.snapshot().itemCount());

        controller.dispatch(new EngineCommand.SetVisibilityFilter("b"));
        controller.takeChangeSet(); // drain filter narrowing
        assertEquals(2, controller.snapshot().itemCount());

        controller.dispatch(new EngineCommand.SetVisibilityFilter(""));
        // narrow restore: expansion intent carried out — no reload reset anymore
        assertTrue(controller.isGroupExpanded(GroupKey.of("group-a")));
        assertEquals(6, controller.snapshot().itemCount());
        // precise block INSERT of group-a (header + 2 children + footer) at 1
        List<ChangeSet.Operation> ops =
                controller.snapshot().changeSet().operations();
        assertEquals(1, ops.size());
        assertEquals(ChangeSet.Type.INSERT, ops.get(0).type());
        assertEquals(1, ops.get(0).positionStart());
        assertEquals(4, ops.get(0).itemCount());
    }

    @Test
    public void dynamicResolver_shouldOverrideClassViewType() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .registerViewTypeResolver("dynamic", (role, model) -> {
                    if (role == BinderRegistry.ViewRole.GROUP_ITEM && "c1".equals(model)) {
                        return 999;
                    }
                    return BinderRegistry.ViewTypeResolver.UNHANDLED;
                })
        .build();

        controller.submit(Arrays.asList(
                new FakeGroup("group-a", Arrays.asList("c1"))
        ));
        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));

        assertEquals(999, controller.snapshot().rows().get(1).viewType());
    }

    @Test
    public void toggleRevealingChildren_whenGroupItemMappingMissing_shouldThrow() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerStandaloneViewType(String.class, 100)
                .registerGroupHeaderViewType(String.class, 101)
                .registerGroupFooterViewType(String.class, 103)
        .build();

        // Child viewTypes resolve lazily through the ItemViewTypeResolver, so a
        // collapsed group's missing GROUP_ITEM mapping survives submit and only
        // surfaces when the toggle reveals its children.
        controller.submit(Arrays.asList(
                "standalone",
                new FakeGroup("group-a", Arrays.asList("c1"))
        ));

        try {
            controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
            fail("Expected missing GROUP_ITEM mapping to throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("GROUP_ITEM"));
        }
    }

    @Test
    public void collapsedGroup_withFooterVisible_shouldExposeFooterAndExpandFromFooterBaseline() {
        GroupSchema<FakeGroup, String, String, String> footerVisibleSchema = new GroupSchema<FakeGroup, String, String, String>() {
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
                return true;
            }
        };

        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, footerVisibleSchema)
                .registerViewType(String.class, 100)
        .build();

        controller.submit(Arrays.asList(new FakeGroup("group-a", Arrays.asList("c1", "c2"))));
        assertEquals(2, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());
        assertEquals(PositionKind.GROUP_FOOTER, controller.snapshot().rows().get(1).mapping().kind());

        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        assertEquals(4, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_ITEM, controller.snapshot().rows().get(1).mapping().kind());
        assertEquals(PositionKind.GROUP_FOOTER, controller.snapshot().rows().get(3).mapping().kind());
    }

    @Test
    public void replaceChildren_shouldIgnoreLaterMutationOfTheCallerList() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        controller.submit(Arrays.asList(new FakeGroup("group-a", Arrays.asList("c1"))));
        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        assertEquals(3, controller.snapshot().itemCount());

        List<String> children = new ArrayList<>(Arrays.asList("x1", "x2"));
        controller.dispatch(new EngineCommand.ReplaceChildren(GroupKey.of("group-a"), children));
        children.add("x3");

        assertEquals(4, controller.snapshot().itemCount());
        assertEquals("x1", controller.snapshot().rows().get(1).data());
        assertEquals("x2", controller.snapshot().rows().get(2).data());
    }

    @Test
    public void setGroupExpanded_whenStateUnchanged_shouldEmitEmptyChangeSet() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        controller.submit(Arrays.asList(new FakeGroup("group-a", Arrays.asList("c1"))));
        controller.takeChangeSet(); // drain the reload
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new EngineCommand.SetGroupExpanded(GroupKey.of("group-a"), false));

        assertEquals(1, controller.snapshot().itemCount());
        assertTrue(controller.snapshot().changeSet().isEmpty());
        // The no-change guard has to prevent the publish itself, not merely yield an
        // empty delta: without the guard applyExpanded still recomputes and publishes a
        // fresh snapshot (new instance, bumped version) whose delta happens to be empty.
        // Instance identity is what tells the two implementations apart.
        assertSame("no-change SetGroupExpanded must not publish a new snapshot",
                before, controller.snapshot());
    }

    @Test
    public void submit_withNullRows_shouldThrow() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        try {
            controller.submit(null);
            fail("Expected null rows to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rows must not be null"));
        }
    }

    @Test
    public void dispatch_withNullCommand_shouldThrow() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        controller.submit(Arrays.asList(new FakeGroup("group-a", Arrays.asList("c1"))));

        try {
            controller.dispatch(null);
            fail("Expected null command to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("command must not be null"));
        }
    }

    @Test
    public void submit_withDuplicateGroupKey_shouldThrow() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        try {
            controller.submit(Arrays.asList(
                    new FakeGroup("group-a", Arrays.asList("c1")),
                    new FakeGroup("group-a", Arrays.asList("c2"))
            ));
            fail("Expected duplicate groupKey to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate groupKey"));
        }
    }

    @Test
    public void submit_duplicateKeyFailure_shouldKeepEngineAndCoreStateAtomic() {
        // no-half-applied-state contract, engine variant: a throwing submit must
        // not swap in partial orderedSlots/groupRecordByKey while the core
        // controller still holds the old world (engine/core world split)
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        controller.submit(Arrays.asList(
                "standalone",
                new FakeGroup("group-a", Arrays.asList("c1"))
        ));
        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        controller.takeChangeSet();
        int itemCountBefore = controller.snapshot().itemCount();

        try {
            controller.submit(Arrays.asList(
                    new FakeGroup("group-a", Arrays.asList("c1")),
                    new FakeGroup("group-a", Arrays.asList("c2"))
            ));
            fail("Expected duplicate groupKey to throw");
        } catch (IllegalArgumentException expected) {
            // contract: pre-call state preserved
        }

        // engine's interpreted world and core's snapshot agree, expansion survives
        assertEquals(itemCountBefore, controller.snapshot().itemCount());
        assertTrue(controller.isGroupExpanded(GroupKey.of("group-a")));
        // subsequent dispatches work against a consistent world
        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        assertFalse(controller.isGroupExpanded(GroupKey.of("group-a")));
        assertEquals(2, controller.snapshot().itemCount());
    }

    @Test
    public void submit_unmappedViewTypeFailure_shouldKeepEngineAndCoreStateAtomic() {
        // buildCoreRows throws IllegalStateException for an unmapped model class;
        // that failure must also happen BEFORE any state swap
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        controller.submit(Arrays.asList(
                "standalone",
                new FakeGroup("group-a", Arrays.asList("c1"))
        ));
        controller.takeChangeSet();
        int itemCountBefore = controller.snapshot().itemCount();

        try {
            controller.submit(Arrays.asList(
                    // Integer is NOT registered for any role -> binder resolution fails
                    42
            ));
            fail("Expected unmapped viewType to throw");
        } catch (IllegalStateException expected) {
            // contract: pre-call state preserved
        }

        assertEquals(itemCountBefore, controller.snapshot().itemCount());
        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        assertTrue(controller.isGroupExpanded(GroupKey.of("group-a")));
    }

    @Test
    public void unknownGroupCommands_shouldLeavePendingChangeSetUntouched() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
        .build();
        controller.submit(Arrays.asList(new FakeGroup("group-a", Arrays.asList("c1"))));
        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));
        assertFalse(controller.snapshot().changeSet().isEmpty());
        List<ChangeSet.Operation> pendingOpsBefore = controller.snapshot().changeSet().operations();

        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("missing")));
        controller.dispatch(new EngineCommand.ReplaceChildren(GroupKey.of("missing"), Arrays.asList("x1")));
        controller.dispatch(new EngineCommand.SetGroupExpanded(GroupKey.of("missing"), true));

        // fail-silent no-ops never touch pending consumption state: op-for-op
        // identity, not merely the same op count
        List<ChangeSet.Operation> pendingOpsAfter = controller.snapshot().changeSet().operations();
        assertEquals(pendingOpsBefore.size(), pendingOpsAfter.size());
        for (int i = 0; i < pendingOpsBefore.size(); i++) {
            assertEquals("pending op " + i + " type must be untouched",
                    pendingOpsBefore.get(i).type(), pendingOpsAfter.get(i).type());
            assertEquals("pending op " + i + " position must be untouched",
                    pendingOpsBefore.get(i).positionStart(), pendingOpsAfter.get(i).positionStart());
            assertEquals("pending op " + i + " count must be untouched",
                    pendingOpsBefore.get(i).itemCount(), pendingOpsAfter.get(i).itemCount());
        }
        assertTrue(controller.hasPendingChanges());
        controller.takeChangeSet();
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void registerSchema_shouldMatchByAssignableType() {
        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(BaseGroup.class, new BaseGroupSchema())
                .registerViewType(String.class, 100)
        .build();

        controller.submit(Arrays.asList(new ChildGroup("group-c", Arrays.asList("i1"))));

        assertEquals(1, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());
    }

    @Test
    public void registerSchema_mostSpecificAssignableClassShouldWinRegardlessOfOrder() {
        // The submitted model class is NOT itself registered, so the exact-class
        // lookup misses and the assignable-specificity loop in findSchema runs.
        GroupSchema<ChildGroup, String, String, String> childSchema = new ChildGroupSchema();

        DefaultEngineController childFirst = DefaultEngineController.builder()
                .registerSchema(ChildGroup.class, childSchema)
                .registerSchema(BaseGroup.class, new BaseGroupSchema())
                .registerViewType(String.class, 100)
                .build();
        DefaultEngineController baseFirst = DefaultEngineController.builder()
                .registerSchema(BaseGroup.class, new BaseGroupSchema())
                .registerSchema(ChildGroup.class, childSchema)
                .registerViewType(String.class, 100)
                .build();

        childFirst.submit(Arrays.asList(new GrandChildGroup("group-c", Arrays.asList("i1"))));
        baseFirst.submit(Arrays.asList(new GrandChildGroup("group-c", Arrays.asList("i1"))));

        // the most specific registered class (ChildGroup) wins in BOTH registration orders
        assertEquals("child-header-group-c", childFirst.snapshot().rows().get(0).data());
        assertEquals("child-header-group-c", baseFirst.snapshot().rows().get(0).data());
    }

    @Test
    public void setCoreRowFactory_shouldAllowCustomCoreRowCreation() {
        final int[] standaloneCalls = {0};
        final int[] groupCalls = {0};
        CoreRowFactory rowFactory = new CoreRowFactory() {
            @Override
            public CoreRow createStandalone(Object row, int standaloneViewType) {
                standaloneCalls[0]++;
                return CoreRow.Standalone.of(row, standaloneViewType + 1000);
            }

            @Override
            public <I> CoreRow createGroup(
                    GroupKey groupKey,
                    Object header,
                    Object footer,
                    List<I> children,
                    boolean expanded,
                    boolean showFooterWhenCollapsed,
                    int headerViewType,
                    int footerViewType,
                    CoreRow.Group.ItemViewTypeResolver<I> itemViewTypeResolver
            ) {
                groupCalls[0]++;
                return CoreRow.Group.<Object, I, Object>builder(groupKey)
                .header(header)
                .footer(footer)
                .children(children)
                .initiallyExpanded(expanded)
                .showFooterWhenCollapsed(showFooterWhenCollapsed)
                .headerViewType(headerViewType + 1000)
                .footerViewType(footerViewType + 1000)
                .childViewTypeResolver((childIndex, childData) -> itemViewTypeResolver.resolve(childIndex, childData) + 1000)
                .build();
            }
        };

        DefaultEngineController controller = DefaultEngineController.builder()
                .registerSchema(FakeGroup.class, new FakeGroupSchema())
                .registerViewType(String.class, 100)
                .setCoreRowFactory(rowFactory)
        .build();

        controller.submit(Arrays.asList(
                "standalone",
                new FakeGroup("group-a", Arrays.asList("c1"))
        ));

        assertEquals(1, standaloneCalls[0]);
        assertEquals(1, groupCalls[0]);
        assertEquals(1100, controller.snapshot().rows().get(0).viewType());
        assertEquals(1100, controller.snapshot().rows().get(1).viewType());

        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));

        assertEquals(1, standaloneCalls[0]);
        assertEquals(1, groupCalls[0]);
        assertEquals(1100, controller.snapshot().rows().get(2).viewType());
        assertEquals(1100, controller.snapshot().rows().get(3).viewType());

        controller.dispatch(new EngineCommand.SetVisibilityFilter(null));
        assertEquals(2, standaloneCalls[0]);
        assertEquals(2, groupCalls[0]);
    }

    private static final class FakeGroup {
        final String id;
        final java.util.List<String> children;

        FakeGroup(String id, java.util.List<String> children) {
            this.id = id;
            this.children = children;
        }
    }

    private static class BaseGroup {
        final String id;
        final java.util.List<String> children;

        BaseGroup(String id, java.util.List<String> children) {
            this.id = id;
            this.children = children;
        }
    }

    private static class ChildGroup extends BaseGroup {
        ChildGroup(String id, java.util.List<String> children) {
            super(id, children);
        }
    }

    /** Unregistered subclass of ChildGroup: forces findSchema into the specificity loop. */
    private static final class GrandChildGroup extends ChildGroup {
        GrandChildGroup(String id, java.util.List<String> children) {
            super(id, children);
        }
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

    private static final class ChildGroupSchema implements GroupSchema<ChildGroup, String, String, String> {
        @Override
        public GroupKey keyOf(ChildGroup group) {
            return GroupKey.of(group.id);
        }

        @Override
        public String headerOf(ChildGroup group) {
            return "child-header-" + group.id;
        }

        @Override
        public java.util.List<String> childrenOf(ChildGroup group) {
            return group.children;
        }

        @Override
        public String footerOf(ChildGroup group) {
            return "footer-" + group.id;
        }

        @Override
        public boolean initiallyExpanded(ChildGroup group) {
            return false;
        }

        @Override
        public boolean showFooterWhenCollapsed(ChildGroup group) {
            return false;
        }
    }

    private static final class BaseGroupSchema implements GroupSchema<BaseGroup, String, String, String> {
        @Override
        public GroupKey keyOf(BaseGroup group) {
            return GroupKey.of(group.id);
        }

        @Override
        public String headerOf(BaseGroup group) {
            return "header-" + group.id;
        }

        @Override
        public java.util.List<String> childrenOf(BaseGroup group) {
            return group.children;
        }

        @Override
        public String footerOf(BaseGroup group) {
            return "footer-" + group.id;
        }

        @Override
        public boolean initiallyExpanded(BaseGroup group) {
            return false;
        }

        @Override
        public boolean showFooterWhenCollapsed(BaseGroup group) {
            return false;
        }
    }
}
