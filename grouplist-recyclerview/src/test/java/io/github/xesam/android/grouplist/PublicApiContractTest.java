package io.github.xesam.android.grouplist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import io.github.xesam.android.grouplist.recyclerview.binder.ItemBinder;
import io.github.xesam.android.grouplist.recyclerview.binder.ItemBinderRegistry;
import io.github.xesam.android.grouplist.vnext.core.CoreController;
import io.github.xesam.android.grouplist.vnext.core.CoreRow;
import io.github.xesam.android.grouplist.vnext.engine.BinderRegistry;
import io.github.xesam.android.grouplist.vnext.engine.EngineCommand;
import io.github.xesam.android.grouplist.vnext.engine.EngineController;
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Cross-module runtime-behavior contracts of small public pieces shared by all
 * consumers: argument validation, defensive snapshotting / unmodifiable views
 * ({@code EngineCommand.ReplaceChildren}, {@code CoreRow.Group}),
 * {@link ItemBinder} payload fallback, and a narrow reflection probe of the
 * consumption protocol (probe + drain, no clear hook).
 *
 * <p>Scope note: this is NOT a locked API-surface snapshot — it intentionally
 * does not pin signatures or detect renames of unprobed members. Behavioral
 * compatibility of the controller protocol itself is guarded by the core and
 * adapter suites.</p>
 */
public class PublicApiContractTest {

    @Test
    public void groupKey_of_withNullOrBlankValue_shouldThrow() {
        assertIllegalArgument(() -> GroupKey.of(null), "GroupKey value must not be empty");
        assertIllegalArgument(() -> GroupKey.of("  "), "GroupKey value must not be empty");
    }

    @Test
    public void binderRegistry_register_withNullRoleOrModelClass_shouldThrow() {
        BinderRegistry registry = new BinderRegistry();

        assertIllegalArgument(
                () -> registry.register(null, String.class, 1),
                "Role must not be null"
        );
        assertIllegalArgument(
                () -> registry.register(BinderRegistry.ViewRole.GROUP_ITEM, null, 1),
                "Model class must not be null"
        );
    }

    @Test
    public void binderRegistry_registerResolver_withInvalidInput_shouldThrow() {
        BinderRegistry registry = new BinderRegistry();

        assertIllegalArgument(
                () -> registry.registerResolver(" ", (role, model) -> 1),
                "Resolver key must not be empty"
        );
        assertIllegalArgument(
                () -> registry.registerResolver("dynamic", null),
                "Resolver must not be null"
        );
    }

    @Test
    public void binderRegistry_resolveViewType_withNullRole_shouldThrow() {
        BinderRegistry registry = new BinderRegistry();

        assertIllegalArgument(
                () -> registry.resolveViewType(null, "value"),
                "Role must not be null"
        );
    }

    @Test
    public void itemBinderRegistry_require_whenBinderMissing_shouldThrow() {
        ItemBinderRegistry registry = new ItemBinderRegistry();

        try {
            registry.require(42);
            fail("Expected missing binder to throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("viewType=42"));
        }
    }

    @Test
    public void itemBinder_payloadAwareBind_shouldFallBackToFullBind() {
        // RecyclerView contract: default payloads overload = full rebind. A payload-aware
        // binder that does NOT override the payloads overload must still rebind correctly.
        List<String> calls = new ArrayList<>();
        ItemBinder binder = new ItemBinder() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(LayoutInflater inflater, ViewGroup parent) {
                throw new UnsupportedOperationException("not invoked in this contract test");
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, ListSnapshot.Row<?> row, int position) {
                calls.add("full");
            }

            @Override
            public void onBindViewHolder(
                    RecyclerView.ViewHolder holder,
                    ListSnapshot.Row<?> row,
                    int position,
                    List<Object> payloads
            ) {
                ItemBinder.super.onBindViewHolder(holder, row, position, payloads);
                calls.add("payload");
            }
        };
        binder.onBindViewHolder(null, null, 0, java.util.Collections.emptyList());
        assertEquals(Arrays.asList("full", "payload"), calls);
    }

    @Test
    public void engineCommand_replaceChildren_shouldSnapshotInputAndExposeUnmodifiableList() {
        List<String> source = new ArrayList<>(Arrays.asList("a", "b"));
        EngineCommand.ReplaceChildren command = new EngineCommand.ReplaceChildren(GroupKey.of("group-a"), source);
        source.add("c");

        assertEquals(Arrays.asList("a", "b"), command.children());
        assertThrows(UnsupportedOperationException.class, () -> command.children().add("x"));
    }

    @Test
    public void coreRowGroup_shouldSnapshotChildrenAndExposeUnmodifiableList() {
        List<String> source = new ArrayList<>(Arrays.asList("a", "b"));
        CoreRow.Group<String, String, String> group = CoreRow.Group.<String, String, String>builder(GroupKey.of("group-a"))
        .header("header")
        .footer("footer")
        .children(source)
        .initiallyExpanded(false)
        .showFooterWhenCollapsed(false)
        .headerViewType(1)
        .footerViewType(2)
        .childViewTypeResolver((childIndex, childData) -> 3)
        .build();
        source.add("c");

        assertEquals(Arrays.asList("a", "b"), group.children());
        assertThrows(UnsupportedOperationException.class, () -> group.children().add("x"));
    }

    @Test
    public void coreRowGroup_withNullGroupKey_shouldThrow() {
        try {
            CoreRow.Group.builder(null)
            .header("header")
            .footer("footer")
            .children(Arrays.asList("a"))
            .initiallyExpanded(false)
            .showFooterWhenCollapsed(false)
            .headerViewType(1)
            .footerViewType(2)
            .childViewTypeResolver((childIndex, childData) -> 3)
            .build();
            fail("Expected null groupKey to throw");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("groupKey must not be null"));
        }
    }

    /**
     * Shape check on the declared API surface (what reflection can see): the drain
     * methods are declared and no {@code clearChangeSet} exists. The *behavior* of the
     * drain protocol is pinned elsewhere, on real controllers
     * ({@code ChangeSetConsumptionContractTest}, {@code EngineConsumptionContractTest}).
     */
    @Test
    public void controllers_shouldDeclareDrainMethodsAndNoClearChangeSet() {
        assertMethod(CoreController.class, "hasPendingChanges");
        assertMethod(CoreController.class, "takeChangeSet");
        assertMethod(EngineController.class, "hasPendingChanges");
        assertMethod(EngineController.class, "takeChangeSet");
        try {
            CoreController.class.getMethod("clearChangeSet");
            fail("clearChangeSet must not be part of the public API");
        } catch (NoSuchMethodException expected) {
            // expected: the consumption protocol has exactly one drain point
        }
    }

    @Test
    public void changeSet_channelSemantics_shouldBePublicContract() {
        assertEquals(ChangeSet.Channel.RELOAD, ChangeSet.reload().channel());
        assertEquals(ChangeSet.Channel.INCREMENTAL, ChangeSet.empty().channel());
        assertEquals(ChangeSet.Channel.INCREMENTAL, ChangeSet.of(java.util.Collections.emptyList()).channel());
        // RELOAD is never "empty": it always denotes a pending full repaint
        assertFalse(ChangeSet.reload().isEmpty());
        assertTrue(ChangeSet.empty().isEmpty());
    }

    private static void assertMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
        } catch (NoSuchMethodException e) {
            fail(type.getSimpleName() + " missing public method: " + name);
        }
    }

    private static void assertIllegalArgument(ThrowingRunnable runnable, String expectedMessagePart) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(expectedMessagePart));
        } catch (Exception unexpected) {
            throw new AssertionError("Unexpected exception type", unexpected);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
