package io.github.xesam.android.grouplist.vnext.core;

import java.util.List;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Threading contract: see the canonical statement in
 * {@code io.github.xesam.android.grouplist.vnext} package-info.java
 * (single-threaded use; no cross-thread memory-visibility guarantee).
 */
public interface CoreController {

    /**
     * Replaces all source rows and rebuilds latest snapshot.
     *
     * <p>Expansion-state contract: submit resets every group's expansion to its
     * {@code initiallyExpanded} value; live state is not carried across re-submits.
     *
     * <p>Consumption contract: marks a pending {@link ChangeSet.Channel#RELOAD}
     * change; drain it via {@link #takeChangeSet()}. Fails with
     * {@link IllegalArgumentException} for a null rows list, a null element, or a
     * duplicate {@link GroupKey}.
     */
    void submit(List<? extends CoreRow> rows);

    /**
     * Applies a {@link CoreCommand} to the current state. Fails with
     * {@link IllegalArgumentException} for a null or unknown command type.
     * Race-tolerant targets (e.g. toggling an unknown group key) are documented
     * fail-silent no-ops on the command itself and never throw; structural
     * misuse (e.g. standalone count drift in ReconcileRows) throws.
     */
    void dispatch(CoreCommand command);

    /**
     * Consumption protocol — non-destructive probe.
     *
     * @return true if a not-yet-taken change (reload or non-empty incremental)
     *         is pending since the last {@link #takeChangeSet()}.
     */
    boolean hasPendingChanges();

    /**
     * Consumption protocol — draining read. Returns and clears the pending
     * {@link ChangeSet}; afterwards {@link #hasPendingChanges()} is false until
     * the next submit/dispatch that publishes a change.
     *
     * <p>{@link #snapshot()} is a pure read and never drains: polling it repeatedly
     * is safe and cheap. This method is the single consumption point removing the
     * need for any "remember to clear" discipline in producers.
     *
     * @return the pending change set; {@link ChangeSet#empty()} when nothing is pending.
     */
    ChangeSet takeChangeSet();

    /**
     * Single source of truth for expansion state. Read-only query against the
     * controller's internal state; groups without state (unknown key) are collapsed.
     */
    boolean isGroupExpanded(GroupKey groupKey);

    ListSnapshot snapshot();
}
