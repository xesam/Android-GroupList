package io.github.xesam.android.grouplist.vnext.core;

import java.util.Collections;
import java.util.List;

import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * Commands that mutate expand/collapse state in CoreController.
 */
public interface CoreCommand {

    /**
     * Toggles the expansion state of the group identified by {@code groupKey}.
     *
     * <p>Failure policy: unknown group key is a fail-silent no-op (never throws);
     * toggling is derived from the controller's exclusive expansion state.
     */
    final class ToggleGroup implements CoreCommand {
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
    final class SetGroupExpanded implements CoreCommand {
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
     * Replaces one STANDALONE row's data in place at a known visible position.
     *
     * <p>Semantics: a precisely-scoped single UPDATE in final coordinates; no group
     * expansion state or visibility is touched, and no count/structure constraints
     * (contrast {@link ReconcileRows}) apply. The updated row keeps its
     * {@code viewType} from the replaced row.
     *
     * <p>Failure policy: an out-of-range {@code flatPosition}, or one pointing at a
     * non-standalone row (header / group item / footer), is a structural error and
     * throws {@link IllegalArgumentException}.
     */
    final class UpdateStandaloneFlat implements CoreCommand {
        private final int flatPosition;
        private final Object newData;

        public UpdateStandaloneFlat(int flatPosition, Object newData) {
            this.flatPosition = flatPosition;
            this.newData = newData;
        }

        public int flatPosition() {
            return flatPosition;
        }

        public Object newData() {
            return newData;
        }
    }

    /**
     * Reconciles the VISIBLE top-level blocks against the submitted rows — a
     * visibility-only change, in contrast to {@link CoreController#submit} whose semantic is a full
     * reload. Public contract (guarded by tests):
     * <ul>
     *   <li>Groups ABSENT from rows become hidden; groups present are kept or revealed.
     *     Set-of-visible change is the ONLY difference this channel resolves.</li>
     *   <li>Expansion state survives across hidden periods and is carried out on
     *     reappearance (the "hidden = paused, not destroyed" mental model); a brand-new
     *     key starts from {@code initiallyExpanded} exactly as on first submit.</li>
     *   <li>Standalone rows must align positionally (equal count); a same-slot content
     *     change (data OR viewType drift) is carried out as an in-place UPDATE emitted
     *     last (ascending, final coordinates) — a count drift means a structural change
     *     and must use {@code submit} (reload semantics) instead.</li>
     *   <li>Kept (present in both) group blocks must be content-identical: children
     *     changes go through {@link ReplaceChildren} which owns the visible-diff path.
     *     Reordered groups are not supported either.</li>
     *   <li>Emission order: REMOVEd blocks descending by old position, then INSERTed
     *     blocks ascending, then standalone UPDATEs ascending in final coordinates —
     *     every position follows the sequential application convention of
     *     {@link io.github.xesam.android.grouplist.vnext.shared.ChangeSet}.</li>
     * </ul>
     */
    final class ReconcileRows implements CoreCommand {
        private final List<? extends CoreRow> rows;

        public ReconcileRows(List<? extends CoreRow> rows) {
            this.rows = rows;
        }

        public List<? extends CoreRow> rows() {
            return rows;
        }
    }

    /**
     * Replaces the children of an existing group in place.
     * Positions are recomputed structurally, and the changeSet is content-aware:
     * a shared-prefix child whose data changed becomes UPDATE, an unchanged child
     * emits nothing (the consumer keeps its holder), and a size delta becomes
     * INSERT/REMOVE at the group's tail.
     *
     * <p>Failure policy: unknown group key is a fail-silent no-op. Collapsed targets
     * yield an empty pending change; the replace is preserved until the group
     * becomes visible again (“hidden = paused, not destroyed”).
     */
    final class ReplaceChildren implements CoreCommand {
        private final GroupKey groupKey;
        private final List<?> children;

        public ReplaceChildren(GroupKey groupKey, List<?> children) {
            this.groupKey = groupKey;
            this.children = children == null ? Collections.emptyList() : children;
        }

        public GroupKey groupKey() {
            return groupKey;
        }

        public List<?> children() {
            return children;
        }
    }

    /**
     * Keyed full reconcile: replaces the entire flat visible model by diffing the new
     * rows against the current ones using {@link ItemIdentity} for standalone rows
     * and group items. Groups are matched by {@link GroupKey}; expansion state is
     * preserved across the replace (same “hidden = paused” semantics as
     * {@link ReconcileRows}).
     *
     * <p>Differences from {@link ReconcileRows}:
     * <ul>
     *   <li>Standalone rows need not align positionally: added/removed/moved standalones
     *     show up as INSERT/REMOVE operations.</li>
     *   <li>Children of kept groups are diffed: a content change within a group emits
     *     precise UPDATE/INSERT/REMOVE operations instead of requiring a separate
     *     {@link ReplaceChildren} dispatch.</li>
     *   <li>Reordered groups are supported: a moved group shows up as REMOVE + INSERT.</li>
     * </ul>
     *
     * <p>The identity hook applies only to standalone rows and group items (both scoped
     * to their containing group); headers and footers are always identified by
     * (kind, GroupKey). When {@code identity} is null, {@link ItemIdentity#DATA_EQUALS}
     * is used (i.e., the data itself is the identity).
     *
     * <p>Failure policy: structural misuse throws {@code IllegalArgumentException}
     * BEFORE any state is mutated — a null rows list, a null rows element, or a
     * duplicate {@code GroupKey} (the same programming-error matrix as
     * {@code submit}). The never-throw policy covers only the diff itself.
     *
     * <p>Lifetime note: expansion state is preserved across the replace following
     * the same “hidden = paused” semantics as {@link ReconcileRows}; keys that
     * vanish keep their state for a later reappearance.
     */
    final class ReplaceRows implements CoreCommand {
        private final List<? extends CoreRow> rows;
        private final ItemIdentity identity;

        public ReplaceRows(List<? extends CoreRow> rows, ItemIdentity identity) {
            this.rows = rows;
            this.identity = identity;
        }

        public List<? extends CoreRow> rows() {
            return rows;
        }

        public ItemIdentity identity() {
            return identity;
        }
    }
}
