package io.github.xesam.android.grouplist.recyclerview.delegate;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.xesam.android.grouplist.recyclerview.binder.ItemBinder;
import io.github.xesam.android.grouplist.recyclerview.binder.ItemBinderRegistry;
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.GroupState;
import io.github.xesam.android.grouplist.vnext.shared.GroupStates;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

/**
 * The single real implementation behind the RecyclerView adapter layer, extracted
 * from {@code GroupListRecyclerAdapter} so it can be wired EITHER by inheritance
 * (the adapter facade delegates here) OR by composition (a consumer-owned adapter
 * base class holds a delegate and forwards its four adapter callbacks).
 *
 * <p>Ownership of all side effects is callback-based so the delegate never needs
 * to be a RecyclerView.Adapter itself:</p>
 * <ul>
 *   <li>{@link Notifier} — the fine-grained notification channel (reload /
 *       insert / remove / move / change). Provide {@link AdapterNotifier} to
 *       route into any {@link RecyclerView.Adapter} directly, or the facade's
 *       private compat bridge to keep subclass overrides of the adapter's
 *       protected notify hooks working;</li>
 *   <li>{@link OnExpansionRequest} — expansion intent. The facade wires a
 *       method reference to its protected {@code requestSetGroupExpanded} hook,
 *       which keeps existing subclass overrides working unchanged.</li>
 * </ul>
 *
 * <p>Priority contract: the delegate only ever consults the installed callback.
 * There is deliberately no override-vs-callback precedence rule, because a
 * consumer cannot hold both for the same decision point: the facade does not
 * expose its delegate, and composition consumers have no protected hooks.</p>
 *
 * <p>All the behavioral contracts of the former adapter implementation apply
 * verbatim: main-thread only; version lineage gate; no-op incremental submits
 * never repaint; ChangeSet translation is order-preserving (same semantics as
 * DiffUtil).</p>
 */
public final class SnapshotAdapterDelegate {

    /**
     * Fine-grained notification channel. Exactly the notification surface of the
     * former protected notify hooks, expressed as calls on the consumer's adapter.
     *
     * <p>The two {@code onItemRangeChanged} overloads mirror
     * {@link RecyclerView.Adapter}'s notify family. The delegate currently emits
     * UPDATE through the payload overload only, so the no-payload overload is part
     * of the retained surface (the facade bridges it to
     * {@code notifyItemRangeChangedCompat(int, int)}) but is currently unreached.</p>
     */
    public interface Notifier {
        /** Full repaint (notifyDataSetChanged). */
        void onDataReload();

        void onItemChanged(int position);

        void onItemRangeInserted(int positionStart, int itemCount);

        void onItemRangeRemoved(int positionStart, int itemCount);

        void onItemMoved(int fromPosition, int toPosition);

        /** Retained RecyclerView-parity overload; currently unreached (see interface note). */
        void onItemRangeChanged(int positionStart, int itemCount);

        void onItemRangeChanged(int positionStart, int itemCount, @NonNull Object payload);
    }

    /** Expansion intent callback: the consumer decides whether the request was accepted. */
    public interface OnExpansionRequest {
        boolean onExpansionRequest(@NonNull GroupKey groupKey, boolean expanded);
    }

    private static final OnExpansionRequest REJECT = (groupKey, expanded) -> false;

    /** Maps one {@link ChangeSet.Operation} onto the Notifier channel for its type. */
    private interface OperationDispatch {
        void notify(Notifier notifier, ChangeSet.Operation op);
    }

    /**
     * The single source of truth for ChangeSet.Type → notification mapping. A
     * parallel hand-maintained set or switch would let a new Type slip through to
     * a silent full repaint; here the map IS the mapping, and the class-init guard
     * checks it against the enum, so a new Type without an entry fails loudly at
     * class-init in every environment rather than degrading consumers.
     */
    private static final Map<ChangeSet.Type, OperationDispatch> DISPATCH_BY_TYPE =
            buildDispatchByType();

    private static Map<ChangeSet.Type, OperationDispatch> buildDispatchByType() {
        Map<ChangeSet.Type, OperationDispatch> dispatch = new EnumMap<>(ChangeSet.Type.class);
        dispatch.put(ChangeSet.Type.INSERT,
                (notifier, op) -> notifier.onItemRangeInserted(op.positionStart(), op.itemCount()));
        dispatch.put(ChangeSet.Type.REMOVE,
                (notifier, op) -> notifier.onItemRangeRemoved(op.positionStart(), op.itemCount()));
        dispatch.put(ChangeSet.Type.MOVE,
                (notifier, op) -> notifier.onItemMoved(op.positionStart(), op.toPosition()));
        dispatch.put(ChangeSet.Type.UPDATE,
                (notifier, op) -> notifier.onItemRangeChanged(
                        op.positionStart(), op.itemCount(), PAYLOAD_CONTENT_UPDATE));
        for (ChangeSet.Type type : ChangeSet.Type.values()) {
            if (!dispatch.containsKey(type)) {
                throw new IllegalStateException(
                        "ChangeSet.Type." + type + " has no adapter notification mapping. "
                                + "Add an entry in SnapshotAdapterDelegate#buildDispatchByType, "
                                + "or every submit containing it silently degrades to "
                                + "notifyDataSetChanged.");
            }
        }
        return Collections.unmodifiableMap(dispatch);
    }

    /**
     * Payload marker delivered with in-place UPDATE notifications. Binders may
     * override the payloads-aware {@link ItemBinder#onBindViewHolder} to implement
     * partial rebinds on it; the default falls back to a full bind.
     */
    public static final String PAYLOAD_CONTENT_UPDATE = "grouplist:content-update";

    private final ItemBinderRegistry binderRegistry = new ItemBinderRegistry();
    private final Notifier notifier;
    private final List<ListSnapshot.Row<?>> rows = new ArrayList<>();
    private final Map<GroupKey, GroupState> groupStateByKey = new LinkedHashMap<>();
    /**
     * Version of the last versioned snapshot displayed ({@link ListSnapshot#UNVERSIONED}
     * before the first one / for hand-built snapshots). Feeds the lineage gate in
     * {@link #resolveApplicableChangeSet}.
     */
    private long displayedVersion = ListSnapshot.UNVERSIONED;
    private OnExpansionRequest expansionRequest = REJECT;

    public SnapshotAdapterDelegate(@NonNull Notifier notifier) {
        this.notifier = notifier;
    }

    /**
     * Installs the expansion-intent callback. {@code null} resets to the default
     * reject behavior (the raw delegate never fakes an expansion state change).
     */
    public void setOnExpansionRequest(@Nullable OnExpansionRequest listener) {
        this.expansionRequest = listener != null ? listener : REJECT;
    }

    @NonNull
    public SnapshotAdapterDelegate registerBinder(int viewType, @NonNull ItemBinder binder) {
        binderRegistry.register(viewType, binder);
        return this;
    }

    /**
     * Baseline snapshot submit API.
     *
     * <p>Change-set dispatch contract (see {@link #applyChangeSet}):</p>
     * <ul>
     *   <li>{@link ChangeSet.Channel#RELOAD}: full repaint;</li>
     *   <li>{@link ChangeSet.Channel#INCREMENTAL} empty: <b>no notification at all</b>
     *     — re-submitting a no-op snapshot must never repaint;</li>
     *   <li>{@link ChangeSet.Channel#INCREMENTAL} non-empty: precise ordered notifications.</li>
     * </ul>
     *
     * <p>Must be called on the main thread (RecyclerView notification contract and the
     * single-threaded contract of the vnext controllers feeding it).</p>
     *
     * <p>Must be matched with {@code controller.takeChangeSet()} when driving a
     * controller directly (the delegate reads the snapshot's change set, it does not
     * drain the controller).</p>
     */
    public void submitSnapshot(@NonNull ListSnapshot snapshot) {
        submitSnapshotInternal(snapshot, false);
    }

    /**
     * Recommended submit API when group header/footer chrome depends on expanded state.
     */
    public void submitSnapshotWithGroupChrome(@NonNull ListSnapshot snapshot) {
        submitSnapshotInternal(snapshot, true);
    }

    private void submitSnapshotInternal(ListSnapshot snapshot, boolean withGroupChrome) {
        // Capture the old expansion map BEFORE submitRows swaps the rows in.
        Map<GroupKey, Boolean> oldExpanded = withGroupChrome ? expandedByGroupKey(rows) : null;
        ChangeSet applicable = resolveApplicableChangeSet(snapshot);
        submitRows(snapshot.rows(), applicable);
        if (snapshot.isVersioned()) {
            displayedVersion = snapshot.version();
        }

        if (withGroupChrome && applicable.channel() != ChangeSet.Channel.RELOAD) {
            // A RELOAD repainted the whole list already; per-position change
            // notifications on top of it would be redundant double binds.
            Map<GroupKey, Boolean> newExpanded = expandedByGroupKey(snapshot.rows());
            Map<GroupKey, Boolean> changedGroups = changedExpandedGroups(oldExpanded, newExpanded);
            for (Integer position : chromePositionsForGroups(rows, changedGroups)) {
                notifier.onItemChanged(position);
            }
        }
    }

    /**
     * Lineage gate — consumer side of the snapshot version contract: precise
     * application of an incremental delta requires {@code baseVersion == displayedVersion}.
     * UNVERSIONED snapshots (hand-built, e.g. tests) carry no lineage and pass through.
     */
    private ChangeSet resolveApplicableChangeSet(@NonNull ListSnapshot snapshot) {
        ChangeSet changeSet = snapshot.changeSet();
        if (!snapshot.isVersioned() || changeSet.channel() == ChangeSet.Channel.RELOAD) {
            return changeSet;
        }
        if (snapshot.version() == displayedVersion) {
            // re-delivery of the frame already displayed: nothing to apply.
            return ChangeSet.empty();
        }
        if (displayedVersion == ListSnapshot.UNVERSIONED || snapshot.baseVersion() != displayedVersion) {
            // no displayed lineage to apply against, or intermediate frames were
            // skipped: stale coordinates must never reach notify — rebuild instead.
            return ChangeSet.reload();
        }
        return changeSet;
    }

    private void submitRows(List<ListSnapshot.Row<?>> nextRows, ChangeSet changeSet) {
        rows.clear();
        rows.addAll(nextRows);
        rebuildGroupStateCache();
        applyChangeSet(changeSet);
    }

    // ---- adapter-facing forwarding points (composition consumers call these) ----

    public int getItemCount() {
        return rows.size();
    }

    public int getItemViewType(int position) {
        return rows.get(position).viewType();
    }

    @NonNull
    public RecyclerView.ViewHolder onCreateViewHolder(
            @NonNull LayoutInflater inflater,
            @NonNull ViewGroup parent,
            int viewType
    ) {
        return binderRegistry.require(viewType).onCreateViewHolder(inflater, parent);
    }

    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ListSnapshot.Row<?> row = rows.get(position);
        binderRegistry.require(row.viewType()).onBindViewHolder(holder, row, position);
    }

    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder,
            int position,
            @NonNull List<Object> payloads
    ) {
        ListSnapshot.Row<?> row = rows.get(position);
        binderRegistry.require(row.viewType()).onBindViewHolder(holder, row, position, payloads);
    }

    // ---- group state queries ----

    public boolean isGroupExpanded(@NonNull GroupKey groupKey) {
        GroupState state = getGroupState(groupKey);
        return state != null && state.expanded();
    }

    @Nullable
    public GroupState getGroupState(@NonNull GroupKey groupKey) {
        GroupState cached = groupStateByKey.get(groupKey);
        if (cached != null) {
            return cached;
        }
        // Cache miss. The cache is rebuilt from ALL rows on every submit, so a miss
        // means the key has no GROUP_HEADER row and the scan answers null. The scan
        // is deliberately kept as a fallback (not a bare map lookup): it stays
        // correct if a future path ever rebuilds the cache from a subset of rows,
        // and it is the shared single derivation source (GroupStates) either way.
        return GroupStates.computeGroupState(rows, groupKey);
    }

    /**
     * Group expansion looked up from the anchor row's group. Semantics equal the
     * scan-based history: {@code expanded} means the group currently has visible
     * child rows (an expanded group with zero children still reports false — the
     * documented flat-snapshot caveat).
     */
    public boolean isGroupExpandedAt(int anchorPosition) {
        if (anchorPosition < 0 || anchorPosition >= rows.size()) {
            return false;
        }
        GroupKey groupKey = rows.get(anchorPosition).mapping().groupKey();
        if (groupKey == null) {
            return false;
        }
        GroupState state = getGroupState(groupKey);
        return state != null && state.expanded();
    }

    public int findGroupHeaderPosition(@NonNull GroupKey groupKey) {
        GroupState state = getGroupState(groupKey);
        return state != null ? state.headerPosition() : -1;
    }

    public int findGroupFooterPosition(@NonNull GroupKey groupKey) {
        GroupState state = getGroupState(groupKey);
        return state != null ? state.footerPosition() : -1;
    }

    /** Explicit bounds error instead of a buried IndexOutOfBounds: RecyclerView
     *  keeps positions 0..getItemCount()-1 valid at bind time. */
    @NonNull
    public ListSnapshot.Row<?> getRow(int position) {
        if (position < 0 || position >= rows.size()) {
            throw new IndexOutOfBoundsException(
                    "position=" + position + " is outside the current row count " + rows.size());
        }
        return rows.get(position);
    }

    // ---- expansion requests ----

    public boolean expandGroup(@NonNull GroupKey groupKey) {
        return setGroupExpanded(groupKey, true);
    }

    public boolean collapseGroup(@NonNull GroupKey groupKey) {
        return setGroupExpanded(groupKey, false);
    }

    public int setGroupsExpanded(@Nullable Collection<GroupKey> groupKeys, boolean expanded) {
        if (groupKeys == null || groupKeys.isEmpty()) {
            return 0;
        }
        int changed = 0;
        Map<GroupKey, Boolean> dedupe = new LinkedHashMap<>();
        for (GroupKey key : groupKeys) {
            if (key != null) {
                dedupe.put(key, Boolean.TRUE);
            }
        }
        for (GroupKey key : dedupe.keySet()) {
            if (setGroupExpanded(key, expanded)) {
                changed++;
            }
        }
        return changed;
    }

    public boolean setGroupExpanded(@NonNull GroupKey groupKey, boolean expanded) {
        GroupState state = getGroupState(groupKey);
        if (state == null || state.expanded() == expanded) {
            return false;
        }
        return expansionRequest.onExpansionRequest(groupKey, expanded);
    }

    // ---- internals ----

    private void rebuildGroupStateCache() {
        groupStateByKey.clear();
        groupStateByKey.putAll(GroupStates.groupStateByKey(rows));
    }

    private void applyChangeSet(ChangeSet changeSet) {
        // Non-null by construction: ListSnapshot normalizes a null changeSet to
        // empty, and resolveApplicableChangeSet never returns null.
        if (changeSet.channel() == ChangeSet.Channel.RELOAD) {
            notifier.onDataReload();
            return;
        }
        if (changeSet.isEmpty()) {
            // Incremental no-op: previously this fell through to notifyDataSetChanged,
            // repainting the whole list on every no-op dispatch. Contract: no repaint.
            return;
        }

        for (ChangeSet.Operation op : changeSet.operations()) {
            // DISPATCH_BY_TYPE covers every ChangeSet.Type (class-init guard), so a
            // missing entry is impossible here; the map is the single source of truth.
            DISPATCH_BY_TYPE.get(op.type()).notify(notifier, op);
        }
    }

    private static Map<GroupKey, Boolean> expandedByGroupKey(List<ListSnapshot.Row<?>> rows) {
        Map<GroupKey, Boolean> expanded = new LinkedHashMap<>();
        for (ListSnapshot.Row<?> row : rows) {
            PositionMapping mapping = row.mapping();
            GroupKey groupKey = mapping.groupKey();
            if (groupKey == null) {
                continue;
            }
            if (!expanded.containsKey(groupKey)) {
                expanded.put(groupKey, false);
            }
            if (mapping.kind() == PositionKind.GROUP_ITEM) {
                expanded.put(groupKey, true);
            }
        }
        return expanded;
    }

    private static Map<GroupKey, Boolean> changedExpandedGroups(
            Map<GroupKey, Boolean> oldExpandedByGroup,
            Map<GroupKey, Boolean> newExpandedByGroup
    ) {
        Map<GroupKey, Boolean> changed = new LinkedHashMap<>();
        for (Map.Entry<GroupKey, Boolean> entry : newExpandedByGroup.entrySet()) {
            Boolean oldValue = oldExpandedByGroup.get(entry.getKey());
            if (oldValue == null || !oldValue.equals(entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return changed;
    }

    private static List<Integer> chromePositionsForGroups(
            List<ListSnapshot.Row<?>> rows,
            Map<GroupKey, Boolean> targetGroups
    ) {
        List<Integer> positions = new ArrayList<>();
        if (targetGroups.isEmpty()) {
            return positions;
        }

        for (int i = 0; i < rows.size(); i++) {
            PositionMapping mapping = rows.get(i).mapping();
            if (mapping.groupKey() == null) {
                continue;
            }
            if (mapping.kind() != PositionKind.GROUP_HEADER && mapping.kind() != PositionKind.GROUP_FOOTER) {
                continue;
            }
            if (targetGroups.containsKey(mapping.groupKey())) {
                positions.add(i);
            }
        }
        return positions;
    }
}
