package io.github.xesam.android.grouplist.vnext.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

public interface EngineCommand {

    /**
     * Toggles the expansion state of the group identified by {@code groupKey}.
     *
     * <p>Failure policy: unknown group key is a fail-silent no-op (never throws);
     * the toggle target is derived from the Core layer's exclusive expansion state,
     * never from a second copy.
     */
    final class ToggleGroup implements EngineCommand {
        private final GroupKey groupKey;

        public ToggleGroup(GroupKey groupKey) {
            this.groupKey = groupKey;
        }

        public GroupKey groupKey() {
            return groupKey;
        }
    }

    /**
     * Explicitly sets the expansion state of a group.
     *
     * <p>Failure policy: unknown group key or a no-change value
     * ({@code expanded} equal to current state) is a fail-silent no-op.
     */
    final class SetGroupExpanded implements EngineCommand {
        private final GroupKey groupKey;
        private final boolean expanded;

        public SetGroupExpanded(GroupKey groupKey, boolean expanded) {
            this.groupKey = groupKey;
            this.expanded = expanded;
        }

        public GroupKey groupKey() {
            return groupKey;
        }

        public boolean expanded() {
            return expanded;
        }
    }

    /**
     * Replaces the children of an existing group in place.
     * Precise-change contract (public behavior, asserted by tests): when the group is visible and
     * expanded, the changeSet describes the replace exactly (UPDATE for children whose content
     * changed, INSERT/REMOVE for a size delta tail); no full-reset notification occurs. Collapsed or
     * filtered-out groups yield an EMPTY changeSet (nothing visible changes) and the override is
     * preserved until the group becomes visible (hidden = paused, not destroyed — this includes the
     * {@link SetVisibilityFilter} hidden state).
     *
     * <p>Failure policy: unknown group key is a fail-silent no-op.
     */
    final class ReplaceChildren implements EngineCommand {
        private final GroupKey groupKey;
        private final List<Object> children;

        public ReplaceChildren(GroupKey groupKey, List<?> children) {
            this.groupKey = groupKey;
            this.children = children == null ? new ArrayList<Object>() : new ArrayList<Object>(children);
        }

        public GroupKey groupKey() {
            return groupKey;
        }

        public List<Object> children() {
            return Collections.unmodifiableList(children);
        }
    }

    /**
     * Semantic contract (public behavior, asserted by tests) — NARROW THE VISIBLE WORLD:
     * changes the visibility filter payload and reconciles the VISIBLE group set
     * through the precise incremental channel ({@link io.github.xesam.android.grouplist.vnext.core.CoreCommand.ReconcileRows}):
     * <ul>
     *   <li>newly hidden groups become block REMOVEs, re-shown ones block INSERTs —
     *     no empty-changeSet repaint; expansion state and scroll anchors survive;</li>
     *   <li>a group's expansion intent continues across hidden periods and is carried
     *     out on reappearance (hidden = paused, not destroyed — same mental model as
     *     the silent childrenOverride path);</li>
     *   <li>Swap of the underlying dataset is a RELOAD and keeps using
     *     {@link DefaultEngineController#submit} whose reload-reset contract is
     *     unchanged. Data change vs. visibility change are now EXPLICITLY separated
     *     command channels.</li>
     * </ul>
     */
    final class SetVisibilityFilter implements EngineCommand {
        private final Object payload;

        public SetVisibilityFilter(Object payload) {
            this.payload = payload;
        }

        public Object payload() {
            return payload;
        }
    }

    /**
     * Replaces one STANDALONE row's data in place. Two locating windows:
     * <ul>
     *   <li>{@link #ofIndex(int, Object)} — precise, recommended: the index is the
     *     slot ordinal of the standalone row within the submitted order
     *     (standalone rows are always visible, so this ordinal is stable across
     *     expansion / visibility changes);</li>
     *   <li>{@link #ofData(Object, Object)} — equals-based convenience: when equal
     *     data occurs multiple times the FIRST slot (in submit order) is updated;
     *     use ofIndex to disambiguate.</li>
     * </ul>
     *
     * <p>Both publish a precisely-scoped single UPDATE (no reload, no reload-channel
     * constraints) and never touch group expansion state.
     *
     * <p>Failure policy (both windows): unknown index / data is a fail-silent no-op.
     */
    final class UpdateStandalone implements EngineCommand {
        private final int standaloneIndex;
        private final Object oldData;
        private final Object newData;

        private UpdateStandalone(int standaloneIndex, Object oldData, Object newData) {
            this.standaloneIndex = standaloneIndex;
            this.oldData = oldData;
            this.newData = newData;
        }

        /** Precise window: slot ordinal of the standalone row in submit order. */
        public static UpdateStandalone ofIndex(int standaloneIndex, Object newData) {
            return new UpdateStandalone(standaloneIndex, null, newData);
        }

        /** equals-based window: first matching slot in submit order. */
        public static UpdateStandalone ofData(Object oldData, Object newData) {
            return new UpdateStandalone(-1, oldData, newData);
        }

        /** Slot ordinal, or -1 when this command was created via {@link #ofData}. */
        public int standaloneIndex() {
            return standaloneIndex;
        }

        /** Locating data, or null when this command was created via {@link #ofIndex}. */
        public Object oldData() {
            return oldData;
        }

        public Object newData() {
            return newData;
        }
    }
}
