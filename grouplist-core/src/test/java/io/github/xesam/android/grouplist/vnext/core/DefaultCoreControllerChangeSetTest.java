package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

public class DefaultCoreControllerChangeSetTest {

    @Test
    public void collapseExpandedGroup_shouldEmitRemoveFromHeaderTail() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-a");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(key, Arrays.asList("a", "b"), true, false, true)
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, false));

        assertOperation(controller.snapshot().changeSet(), ChangeSet.Type.REMOVE, 2, 3);
        assertEquals(2, controller.snapshot().itemCount());
    }

    @Test
    public void expandCollapsedGroup_withCollapsedFooterVisible_shouldInsertOnlyChildren() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-footer");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(key, Arrays.asList("a", "b"), false, true, true)
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, true));

        assertOperation(controller.snapshot().changeSet(), ChangeSet.Type.INSERT, 2, 2);
        assertEquals(5, controller.snapshot().itemCount());
    }

    @Test
    public void collapseExpandedGroup_withCollapsedFooterVisible_shouldRemoveOnlyChildren() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-footer");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(key, Arrays.asList("a", "b"), true, true, true)
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, false));

        assertOperation(controller.snapshot().changeSet(), ChangeSet.Type.REMOVE, 2, 2);
        assertEquals(3, controller.snapshot().itemCount());
    }

    @Test
    public void expandSecondGroup_shouldUseCurrentVisibleHeaderPosition() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey first = GroupKey.of("group-first");
        GroupKey second = GroupKey.of("group-second");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(first, Arrays.asList("a1", "a2"), true, false, true),
                group(second, Arrays.asList("b1"), false, false, true)
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(second, true));

        assertOperation(controller.snapshot().changeSet(), ChangeSet.Type.INSERT, 6, 2);
        assertEquals(8, controller.snapshot().itemCount());
    }

    @Test
    public void expandGroup_withNoChildrenAndNoFooter_shouldEmitEmptyChangeSet() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-empty");
        controller.submit(Collections.singletonList(
                group(key, Collections.emptyList(), false, false, false)
        ));

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, true));

        assertTrue(controller.snapshot().changeSet().isEmpty());
        assertEquals(1, controller.snapshot().itemCount());
    }

    @Test
    public void submitAfterExpansion_shouldReloadAndRestoreInitialState() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-reset");
        controller.submit(Collections.singletonList(
                group(key, Arrays.asList("a", "b"), false, false, true)
        ));
        controller.dispatch(new CoreCommand.SetGroupExpanded(key, true));
        assertEquals(4, controller.snapshot().itemCount());

        controller.submit(Collections.singletonList(
                group(key, Arrays.asList("a", "b"), false, false, true)
        ));

        assertEquals(ChangeSet.Channel.RELOAD, controller.takeChangeSet().channel());
        assertFalse(controller.hasPendingChanges());
        assertEquals(1, controller.snapshot().itemCount());
    }

    @Test
    public void replaceChildren_whenExpandedAndSameSize_shouldEmitSingleUpdate() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-replace");
        controller.submit(Arrays.asList(
                standalone("s0"),
                group(key, Arrays.asList("a", "b"), true, false, true)
        ));

        controller.dispatch(new CoreCommand.ReplaceChildren(key, Arrays.asList("x", "y")));

        ListSnapshot snapshot = controller.snapshot();
        assertOperation(snapshot.changeSet(), ChangeSet.Type.UPDATE, 2, 2);
        assertEquals(5, snapshot.itemCount()); // s0 + header + x + y + footer
        assertEquals("x", snapshot.rows().get(2).data());
        assertEquals("y", snapshot.rows().get(3).data());
        assertEquals("footer-group-replace", snapshot.rows().get(4).data());
    }

    @Test
    public void replaceChildren_whenExpandedAndGrown_shouldEmitUpdateThenInsert() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-replace");
        controller.submit(Collections.singletonList(
                group(key, Arrays.asList("a"), true, false, true)
        ));

        controller.dispatch(new CoreCommand.ReplaceChildren(key, Arrays.asList("x", "y", "z")));

        ChangeSet changeSet = controller.snapshot().changeSet();
        assertEquals(2, changeSet.operations().size());
        assertEquals(ChangeSet.Type.UPDATE, changeSet.operations().get(0).type());
        assertEquals(1, changeSet.operations().get(0).positionStart());
        assertEquals(ChangeSet.Type.INSERT, changeSet.operations().get(1).type());
        assertEquals(2, changeSet.operations().get(1).positionStart());
        assertEquals(2, changeSet.operations().get(1).itemCount());
        assertEquals(5, controller.snapshot().itemCount());
    }

    @Test
    public void replaceChildren_whenExpandedAndShrunkToZero_shouldEmitRemove() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-replace");
        controller.submit(Collections.singletonList(
                group(key, Arrays.asList("a", "b"), true, false, true)
        ));

        controller.dispatch(new CoreCommand.ReplaceChildren(key, Collections.emptyList()));

        assertOperation(controller.snapshot().changeSet(), ChangeSet.Type.REMOVE, 1, 2);
        assertEquals(2, controller.snapshot().itemCount());
    }

    @Test
    public void replaceChildren_whenCollapsed_shouldEmitEmptyChangeSetAndAffectFutureExpansion() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey key = GroupKey.of("group-replace");
        controller.submit(Collections.singletonList(
                group(key, Arrays.asList("a", "b"), false, false, true)
        ));
        controller.takeChangeSet(); // drain the reload so emptiness below is attributable to the dispatch

        controller.dispatch(new CoreCommand.ReplaceChildren(key, Collections.singletonList("x")));

        assertTrue(controller.snapshot().changeSet().isEmpty());
        assertEquals(1, controller.snapshot().itemCount());

        controller.dispatch(new CoreCommand.SetGroupExpanded(key, true));

        assertEquals(3, controller.snapshot().itemCount());
        assertEquals("x", controller.snapshot().rows().get(1).data());
    }

    @Test
    public void replaceChildren_unknownKey_shouldBeFailSilentNoOp() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Collections.singletonList(
                group(GroupKey.of("group-a"), Collections.singletonList("a"), false, false, true)
        ));
        controller.takeChangeSet();

        controller.dispatch(new CoreCommand.ReplaceChildren(GroupKey.of("missing"), Collections.singletonList("x")));

        assertFalse(controller.hasPendingChanges());
        assertTrue(controller.takeChangeSet().isEmpty());
    }

    @Test
    public void updateStandaloneFlat_shouldKeepReplacedRowViewType() {
        DefaultCoreController controller = new DefaultCoreController();
        // The two rows carry DIFFERENT viewTypes on purpose: with both at 11 the
        // assertion below would be satisfied by any constant, so a bug that took the
        // viewType from the wrong row (or ignored the replaced row entirely) would pass.
        controller.submit(Arrays.asList(standalone("s0", 11), standalone("s1", 12)));

        controller.dispatch(new CoreCommand.UpdateStandaloneFlat(0, "s0-new"));

        ListSnapshot snapshot = controller.snapshot();
        assertOperation(snapshot.changeSet(), ChangeSet.Type.UPDATE, 0, 1);
        assertEquals("s0-new", snapshot.rows().get(0).data());
        assertEquals("updated row must keep the replaced row's viewType",
                11, snapshot.rows().get(0).viewType());
    }

    @Test
    public void updateStandaloneFlat_withEqualData_shouldBeNoOp() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Collections.singletonList(standalone("s0")));
        controller.takeChangeSet();
        ListSnapshot before = controller.snapshot();

        controller.dispatch(new CoreCommand.UpdateStandaloneFlat(0, "s0"));

        assertSame("equal-payload update publishes nothing", before, controller.snapshot());
        assertFalse(controller.hasPendingChanges());
    }

    private static void assertOperation(ChangeSet changeSet, ChangeSet.Type type, int positionStart, int itemCount) {
        assertEquals(1, changeSet.operations().size());
        ChangeSet.Operation operation = changeSet.operations().get(0);
        assertEquals(type, operation.type());
        assertEquals(positionStart, operation.positionStart());
        assertEquals(itemCount, operation.itemCount());
    }

    private static CoreRow.Standalone<String> standalone(String value) {
        return CoreRow.Standalone.of(value, 11);
    }

    private static CoreRow.Standalone<String> standalone(String value, int viewType) {
        return CoreRow.Standalone.of(value, viewType);
    }

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
