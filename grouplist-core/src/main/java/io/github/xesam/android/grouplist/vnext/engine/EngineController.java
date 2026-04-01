package io.github.xesam.android.grouplist.vnext.engine;

import java.util.List;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Threading contract: see the canonical statement in
 * {@code io.github.xesam.android.grouplist.vnext} package-info.java
 * (single-threaded use; no cross-thread memory-visibility guarantee).
 *
 * Expansion-state public contract:
 * <ul>
 *   <li>Live expansion state is owned by this controller exclusively; query it via
 *       {@link #isGroupExpanded(GroupKey)} instead of keeping a second copy.</li>
 *   <li>The relative state carried by business models / GroupSchema (
 *       {@code initiallyExpanded}) is an INITIAL value: a full re-submit resets expansion
 *       to it. This reset is the public contract of a reload-semantic operation.</li>
 * </ul>
 *
 * Recommended learning path:
 * 1) submit business rows directly.
 * 2) dispatch EngineCommand for state changes.
 * 3) consume ListSnapshot in RecyclerView adapter.
 *
 * Use CoreController directly only when you need full manual control over CoreRow structure.
 *
 * <h3>Engine → Core command mapping (public knowledge)</h3>
 * <table border="1">
 *   <tr><th>EngineCommand</th><th>Core translation</th></tr>
 *   <tr><td>ToggleGroup</td><td>SetGroupExpanded(target derived from Core's state)</td></tr>
 *   <tr><td>SetGroupExpanded</td><td>SetGroupExpanded</td></tr>
 *   <tr><td>ReplaceChildren</td><td>ReplaceChildren (visible groups) / silent override retention (hidden)</td></tr>
 *   <tr><td>SetVisibilityFilter</td><td>ReconcileRows (visibility-only channel)</td></tr>
 *   <tr><td>UpdateStandalone</td><td>UpdateStandaloneFlat (precisely-scoped single UPDATE)</td></tr>
 * </table>
 *
 * <h3>Weak-typing boundary</h3>
 * {@code submit(List<?>)} interprets rows through the registered GroupSchema set:
 * a row whose class matches no schema is treated as a standalone. This is the only
 * place static typing gives way to registry lookup; schema mismatches surface as
 * structure surprises (a group-shaped model rendered standalone), NOT as errors.
 *
 * <h3>Consumption protocol</h3>
 * See {@link io.github.xesam.android.grouplist.vnext.core.CoreController}: {@code snapshot()} is a pure non-draining read,
 * {@code hasPendingChanges()} a non-draining probe, {@code takeChangeSet()} the single
 * draining point. No-op / fail-silent dispatches never disturb pending state.
 */
public interface EngineController {

    void submit(List<?> rows);

    void dispatch(EngineCommand command);

    /**
     * Consumption protocol pass-through to the Core layer.
     *
     * @return true if a not-yet-taken change (reload or non-empty incremental)
     *         is pending since the last {@link #takeChangeSet()}.
     */
    boolean hasPendingChanges();

    /**
     * Consumption protocol — draining read. Returns and clears the pending
     * {@link ChangeSet}. {@link #snapshot()} stays a pure, non-draining read.
     *
     * @return the pending change set; {@link ChangeSet#empty()} when nothing is pending.
     */
    ChangeSet takeChangeSet();

    /**
     * Read-only query of live expansion state. Single source of truth:
     * the controller layer (Core) owns it; callers must not keep a second copy.
     */
    boolean isGroupExpanded(GroupKey groupKey);

    ListSnapshot snapshot();
}
