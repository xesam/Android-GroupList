package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

public class DefaultCoreControllerTest {

    @Test
    public void toggleGroupByKey_shouldEmitInsertAndResolveGroupKey() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:1");

        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                CoreRow.Group.builder(key)
                .header("h1")
                .footer("f1")
                .children(Arrays.asList("a", "b"))
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(false)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        assertEquals(2, controller.snapshot().itemCount());
        assertEquals(PositionKind.STANDALONE, controller.snapshot().rows().get(0).mapping().kind());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(1).mapping().kind());

        controller.dispatch(new CoreCommand.ToggleGroup(key));

        // Precise op pin (children + footer inserted at the header's tail in one block)
        assertEquals(1, controller.snapshot().changeSet().operations().size());
        ChangeSet.Operation toggleOp = controller.snapshot().changeSet().operations().get(0);
        assertEquals(ChangeSet.Type.INSERT, toggleOp.type());
        assertEquals(2, toggleOp.positionStart());
        assertEquals(3, toggleOp.itemCount());
        assertEquals(5, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_ITEM, controller.snapshot().rows().get(2).mapping().kind());
        assertEquals(key, controller.snapshot().rows().get(2).mapping().groupKey());
        assertEquals(0, controller.snapshot().rows().get(2).mapping().childIndex());
    }

    @Test
    public void submit_withNullRowElement_shouldFailFast() {
        DefaultCoreController controller = new DefaultCoreController();
        try {
            controller.submit(Arrays.asList(
                    CoreRow.Standalone.of("x", 1),
                    null
            ));
            fail("expected IllegalArgumentException for null row element");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null elements"));
        }
        // controller kept its pre-submit state, no half-applied submit
        assertEquals(0, controller.snapshot().itemCount());
    }

    @Test
    public void setGroupExpanded_onUnknownKey_shouldBeNoOp() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(CoreRow.Standalone.of("x", 1)));
        controller.takeChangeSet(); // drain the reload

        controller.dispatch(new CoreCommand.SetGroupExpanded(GroupKey.of("missing"), true));

        assertEquals(1, controller.snapshot().itemCount());
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void collapsedGroup_withFooterVisible_shouldKeepFooterAndToggleDeltaFromFooterBaseline() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:footer");
        controller.submit(Arrays.asList(
                CoreRow.Group.builder(key)
                .header("header")
                .footer("footer")
                .children(Arrays.asList("a", "b"))
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(true)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        assertEquals(2, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());
        assertEquals(PositionKind.GROUP_FOOTER, controller.snapshot().rows().get(1).mapping().kind());

        controller.dispatch(new CoreCommand.ToggleGroup(key));
        assertEquals(4, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_ITEM, controller.snapshot().rows().get(1).mapping().kind());
        assertEquals(PositionKind.GROUP_FOOTER, controller.snapshot().rows().get(3).mapping().kind());
    }

    @Test
    public void setGroupExpanded_whenStateUnchanged_shouldEmitEmptyChangeSet() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:stable");
        controller.submit(Arrays.asList(
                CoreRow.Group.builder(key)
                .header("header")
                .footer("footer")
                .children(Arrays.asList("a"))
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(false)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        controller.takeChangeSet(); // drain the reload; the same-state dispatch below must not publish anything

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, false));

        assertEquals(1, controller.snapshot().itemCount());
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void setGroupExpanded_shouldEmitInsertOperationFromHeaderTail() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:delta");
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                CoreRow.Group.builder(key)
                .header("header")
                .footer("footer")
                .children(Arrays.asList("a", "b"))
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(false)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, true));

        assertEquals(1, controller.snapshot().changeSet().operations().size());
        assertEquals(ChangeSet.Type.INSERT,
                controller.snapshot().changeSet().operations().get(0).type());
        assertEquals(2, controller.snapshot().changeSet().operations().get(0).positionStart());
        assertEquals(3, controller.snapshot().changeSet().operations().get(0).itemCount());
    }

    @Test
    public void submit_whenDuplicateGroupKey_shouldThrow() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey duplicate = GroupKey.of("g:dup");

        try {
            controller.submit(Arrays.asList(
                    CoreRow.Group.builder(duplicate)
                    .header("h1")
                    .footer("f1")
                    .children(Arrays.asList("a"))
                    .initiallyExpanded(false)
                    .showFooterWhenCollapsed(false)
                    .headerViewType(21)
                    .footerViewType(23)
                    .childViewTypeResolver((i, d) -> 22)
                    .build(),
                    CoreRow.Group.builder(duplicate)
                    .header("h2")
                    .footer("f2")
                    .children(Arrays.asList("b"))
                    .initiallyExpanded(false)
                    .showFooterWhenCollapsed(false)
                    .headerViewType(21)
                    .footerViewType(23)
                    .childViewTypeResolver((i, d) -> 22)
                    .build()
            ));
            fail("Expected duplicate groupKey to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate groupKey"));
        }
    }

    @Test
    public void submit_duplicateKeyFailure_shouldKeepPreSubmitStateAtomic() {
        // the no-half-applied-state contract: a throwing submit must not leave a
        // mutated sourceRows behind (previously the old sourceRows had already
        // been replaced when the duplicate check fired)
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey live = GroupKey.of("g:live");
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                CoreRow.Group.builder(live)
                .header("h1")
                .children(Arrays.asList("a", "b"))
                .initiallyExpanded(true)
                .headerViewType(21)
                .childViewTypeResolver((i, d) -> 22)
                .build()
        ));
        controller.takeChangeSet();
        controller.dispatch(new CoreCommand.SetGroupExpanded(live, false));
        ListSnapshot before = controller.snapshot();

        try {
            controller.submit(Arrays.asList(
                    CoreRow.Group.builder(live).header("x")
                    .headerViewType(21)
                    .childViewTypeResolver((i, d) -> 22)
                    .build(),
                    CoreRow.Group.builder(live).header("y")
                    .headerViewType(21)
                    .childViewTypeResolver((i, d) -> 22)
                    .build()
            ));
            fail("Expected duplicate groupKey to throw");
        } catch (IllegalArgumentException expected) {
            // contract: pre-call state preserved
        }

        // snapshot unchanged, expansion state unchanged (still collapsed), pending untouched.
        // Instance identity, not a re-read of the row count: a half-committed submit that
        // had already swapped sourceRows would still report the same count for these two
        // collapsed groups, but it would have published a fresh snapshot to say so.
        assertEquals(2, before.itemCount());
        assertSame("a throwing submit must not publish a new snapshot",
                before, controller.snapshot());
        assertTrue("the toggle's pending delta must survive the failed submit",
                controller.hasPendingChanges());
        assertFalse(controller.isGroupExpanded(live));
        // a subsequent dispatch still works against a consistent world
        controller.dispatch(new CoreCommand.ToggleGroup(live));
        // s0 + header + 2 children (footer-less group)
        assertEquals(4, controller.snapshot().itemCount());
    }

    @Test
    public void toggleGroup_onUnknownKey_shouldLeavePendingChangeSetUntouched() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey existing = GroupKey.of("g:known");
        controller.submit(Arrays.asList(
                CoreRow.Group.builder(existing)
                .header("h1")
                .footer("f1")
                .children(Arrays.asList("a"))
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(false)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((i, d) -> 22)
                .build()
        ));
        controller.dispatch(new CoreCommand.ToggleGroup(existing));
        assertFalse(controller.snapshot().changeSet().isEmpty());
        List<ChangeSet.Operation> pendingOpsBefore = controller.snapshot().changeSet().operations();

        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g:missing")));

        // Draining semantics: a fail-silent no-op never touches pending consumption
        // state; the earlier (still unconsumed) change stays available for takeChangeSet.
        assertEquals(3, controller.snapshot().itemCount());
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

        // and draining still yields the earlier operations unchanged: op-for-op
        // identity with the pre-no-op pending list, not merely the same size
        ChangeSet taken = controller.takeChangeSet();
        assertEquals(pendingOpsBefore.size(), taken.operations().size());
        for (int i = 0; i < pendingOpsBefore.size(); i++) {
            assertSame(pendingOpsBefore.get(i), taken.operations().get(i));
        }
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void submit_withNullRows_shouldThrow() {
        DefaultCoreController controller = new DefaultCoreController();
        try {
            controller.submit(null);
            fail("Expected null rows to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rows must not be null"));
        }
    }

    @Test
    public void dispatch_withNullCommand_shouldThrow() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(CoreRow.Standalone.of("x", 1)));
        try {
            controller.dispatch(null);
            fail("Expected null command to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("command must not be null"));
        }
    }

    @Test
    public void coreRowGroup_withoutResolverAndNonEmptyChildren_shouldThrow() {
        try {
            CoreRow.Group.builder(GroupKey.of("g:null-resolver"))
            .header("header")
            .footer("footer")
            .children(Arrays.asList("a"))
            .initiallyExpanded(false)
            .showFooterWhenCollapsed(false)
            .headerViewType(21)
            .footerViewType(23)
            .build();
            fail("Expected missing resolver to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("childViewTypeResolver must be set"));
        }
    }

    @Test
    public void submit_withEmptyGroupNoFooter_shouldRenderHeaderOnly() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:empty");
        controller.submit(Arrays.asList(
                CoreRow.Group.builder(key)
                .header("header")
                .footer(null)
                .children(Arrays.asList())
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(false)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        assertEquals(1, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());
    }

    @Test
    public void toggleGroup_withEmptyGroupNoFooter_shouldEmitEmptyChangeSet() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:empty");
        controller.submit(Arrays.asList(
                CoreRow.Group.builder(key)
                .header("header")
                .footer(null)
                .children(Arrays.asList())
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(false)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        controller.dispatch(new CoreCommand.ToggleGroup(key));

        // The empty delta must not be the ONLY observable: without this state
        // assertion a ToggleGroup branch degraded to a plain no-op would pass here,
        // since a childless footer-less group has the same visible size either way.
        assertTrue(controller.isGroupExpanded(key));
        assertTrue(controller.snapshot().changeSet().isEmpty());
        assertEquals(1, controller.snapshot().itemCount());
    }

    @Test
    public void toggleGroup_withEmptyGroupAndCollapsedFooter_shouldEmitEmptyChangeSetAndKeepFooterVisible() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("g:empty-footer");
        controller.submit(Arrays.asList(
                CoreRow.Group.builder(key)
                .header("header")
                .footer("footer")
                .children(Arrays.asList())
                .initiallyExpanded(false)
                .showFooterWhenCollapsed(true)
                .headerViewType(21)
                .footerViewType(23)
                .childViewTypeResolver((childIndex, childData) -> 22)
                .build()
        ));

        assertEquals(2, controller.snapshot().itemCount());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());
        assertEquals(PositionKind.GROUP_FOOTER, controller.snapshot().rows().get(1).mapping().kind());

        controller.dispatch(new CoreCommand.ToggleGroup(key));

        // see the footer-less sibling: the expansion flip is the observable that
        // separates a real toggle from a no-op when the visible size cannot move.
        assertTrue(controller.isGroupExpanded(key));
        assertTrue(controller.snapshot().changeSet().isEmpty());
        assertEquals(2, controller.snapshot().itemCount());
    }

    @Test
    public void submit_withManyGroups_shouldBuildFullSnapshot() {
        DefaultCoreController controller = new DefaultCoreController();
        java.util.List<CoreRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(CoreRow.Group.builder(GroupKey.of("g:" + i))
            .header("header-" + i)
            .footer("footer-" + i)
            .children(Arrays.asList("a", "b"))
            .initiallyExpanded(false)
            .showFooterWhenCollapsed(false)
            .headerViewType(21)
            .footerViewType(23)
            .childViewTypeResolver((childIndex, childData) -> 22)
            .build());
        }
        controller.submit(rows);

        assertEquals(100, controller.snapshot().itemCount());
        // "full snapshot" means the reload channel with every group's header, in submit
        // order — a matching row count alone would also be produced by a broken build pass
        assertEquals(ChangeSet.Channel.RELOAD, controller.snapshot().changeSet().channel());
        assertEquals(PositionKind.GROUP_HEADER, controller.snapshot().rows().get(0).mapping().kind());
        assertEquals("header-0", controller.snapshot().rows().get(0).data());
        assertEquals("header-99", controller.snapshot().rows().get(99).data());
    }

    @Test
    public void toggleGroup_inLargeList_shouldEmitCorrectChangeSet() {
        DefaultCoreController controller = new DefaultCoreController();
        java.util.List<CoreRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(CoreRow.Group.builder(GroupKey.of("g:" + i))
            .header("header-" + i)
            .footer(null)
            .children(Arrays.asList("a", "b", "c"))
            .initiallyExpanded(false)
            .showFooterWhenCollapsed(false)
            .headerViewType(21)
            .footerViewType(23)
            .childViewTypeResolver((childIndex, childData) -> 22)
            .build());
        }
        controller.submit(rows);

        GroupKey target = GroupKey.of("g:25");
        controller.dispatch(new CoreCommand.ToggleGroup(target));

        assertEquals(53, controller.snapshot().itemCount());
        assertEquals(1, controller.snapshot().changeSet().operations().size());
        assertEquals(ChangeSet.Type.INSERT,
                controller.snapshot().changeSet().operations().get(0).type());
        assertEquals(26, controller.snapshot().changeSet().operations().get(0).positionStart());
        assertEquals(3, controller.snapshot().changeSet().operations().get(0).itemCount());
    }
}
