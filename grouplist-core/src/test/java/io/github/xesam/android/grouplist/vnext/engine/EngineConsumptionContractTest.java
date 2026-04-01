package io.github.xesam.android.grouplist.vnext.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * Public contract of the Engine consumption protocol pass-through:
 * hasPendingChanges/takeChangeSet delegate to Core; unknown command types fail fast.
 */
public class EngineConsumptionContractTest {

    private static final class LocalGroup {
        final String key;
        final String child;

        LocalGroup(String key, String child) {
            this.key = key;
            this.child = child;
        }
    }

    private static final class LocalGroupSchema implements GroupSchema<LocalGroup, String, String, String> {
        @Override
        public GroupKey keyOf(LocalGroup group) {
            return GroupKey.of(group.key);
        }

        @Override
        public String headerOf(LocalGroup group) {
            return "header-" + group.key;
        }

        @Override
        public java.util.List<String> childrenOf(LocalGroup group) {
            return Collections.singletonList(group.child);
        }

        @Override
        public String footerOf(LocalGroup group) {
            return "footer-" + group.key;
        }

        @Override
        public boolean initiallyExpanded(LocalGroup group) {
            return false;
        }

        @Override
        public boolean showFooterWhenCollapsed(LocalGroup group) {
            return true;
        }
    }

    private static DefaultEngineController newController() {
        return DefaultEngineController.builder()
                .registerSchema(LocalGroup.class, new LocalGroupSchema())
                .registerViewType(String.class, 100)
                .build();
    }

    @Test
    public void submit_shouldMarkReloadPendingUntilTaken() {
        DefaultEngineController controller = newController();
        controller.submit(Collections.singletonList(new LocalGroup("group-a", "a")));

        assertTrue(controller.hasPendingChanges());
        assertEquals(ChangeSet.Channel.RELOAD, controller.takeChangeSet().channel());
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void takeChangeSet_shouldDrainStateChangePublishedByDispatch() {
        DefaultEngineController controller = newController();
        controller.submit(Collections.singletonList(new LocalGroup("group-a", "a")));
        controller.takeChangeSet();

        controller.dispatch(new EngineCommand.ToggleGroup(GroupKey.of("group-a")));

        assertTrue(controller.hasPendingChanges());
        ChangeSet taken = controller.takeChangeSet();
        assertEquals(ChangeSet.Channel.INCREMENTAL, taken.channel());
        // Positive anchor, pinned to exact coordinates: the drained set must be the
        // real expansion delta — the group's single child row inserted between the
        // retained header and footer, so a degenerate "publish an empty or bogus
        // INCREMENTAL" implementation cannot pass.
        assertEquals(1, taken.operations().size());
        assertEquals(ChangeSet.Type.INSERT, taken.operations().get(0).type());
        assertEquals(1, taken.operations().get(0).positionStart());
        assertEquals(1, taken.operations().get(0).itemCount());
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void dispatch_withUnknownCommandType_shouldThrow() {
        DefaultEngineController controller = newController();
        controller.submit(Collections.singletonList(new LocalGroup("group-a", "a")));

        EngineCommand unknown = new EngineCommand() {
        };
        try {
            controller.dispatch(unknown);
            fail("Expected unknown command type to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown EngineCommand"));
        }
    }
}
