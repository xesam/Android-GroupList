package io.github.xesam.android.grouplist.recyclerview.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collection;
import java.util.List;

import io.github.xesam.android.grouplist.recyclerview.binder.ItemBinder;
import io.github.xesam.android.grouplist.recyclerview.delegate.AdapterNotifier;
import io.github.xesam.android.grouplist.recyclerview.delegate.SnapshotAdapterDelegate;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.GroupState;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * RecyclerView adapter over a {@link ListSnapshot}: the INHERITANCE entry point of
 * the recyclerview layer.
 *
 * <p>Since the delegate extraction this class is a thin forwarding facade: the
 * single real implementation lives in {@link SnapshotAdapterDelegate}, shared
 * with composition consumers (adapters that extend a consumer-owned base class
 * and hold a delegate + {@link AdapterNotifier} instead of inheriting this one).
 * Notifications and expansion intents route through the facade's protected hooks,
 * so existing subclasses keep working without any change.</p>
 *
 * <p>Protected surface after the extraction: {@code requestSetGroupExpanded},
 * {@code dispatchNotifyDataSetChanged} and the {@code notifyXxxCompat} family all
 * survived in place. The former {@code protected submitRows} extension hook was
 * dropped (it had zero callers and its interception point no longer exists in
 * the delegate) — subclasses overriding it will compile but the override is dead
 * code; use the snapshot-based submit APIs instead.</p>
 *
 * <p>Scope notes (public contract, unchanged by the extraction):
 * <ul>
 *   <li>Main-thread only (RecyclerView notification contract + the vnext
 *     controller threading contract);</li>
 *   <li>{@code setHasStableIds} is NOT enabled: scroll/restoration state is not
 *     id-based in this version. Group rows COULD derive stable ids from
 *     (kind, GroupKey, childIndex), standalone rows cannot across re-submits —
 *     partial enablement would silently break {@code notifyDataSetChanged} paths.</li>
 * </ul>
 *
 * <p>API note: {@code getGroupState} now returns the shared
 * {@link GroupState} type whose derivation was sunk into the core module
 * (single derivation source for both adapter layers). The former nested type
 * {@code GroupListRecyclerAdapter.GroupState} moved home with it.</p>
 */
public class GroupListRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /**
     * Payload marker delivered with in-place UPDATE notifications. Binders may
     * override the payloads-aware {@link ItemBinder#onBindViewHolder} to implement
     * partial rebinds on it; the default falls back to a full bind.
     */
    public static final String PAYLOAD_CONTENT_UPDATE = SnapshotAdapterDelegate.PAYLOAD_CONTENT_UPDATE;

    private final SnapshotAdapterDelegate delegate;

    public GroupListRecyclerAdapter() {
        // Notifications route through the protected compat hooks via the inner
        // bridge, so subclass overrides of those hooks keep working. The expansion
        // intent routes through a method reference on this instance: virtual
        // dispatch delivers it to subclass overrides of the protected hook.
        this.delegate = new SnapshotAdapterDelegate(new CompatNotifier());
        this.delegate.setOnExpansionRequest(this::requestSetGroupExpanded);
    }

    @NonNull
    public GroupListRecyclerAdapter registerBinder(int viewType, @NonNull ItemBinder binder) {
        delegate.registerBinder(viewType, binder);
        return this;
    }

    /**
     * Baseline snapshot submit API. See {@link SnapshotAdapterDelegate#submitSnapshot}
     * for the change-set dispatch and version lineage contracts (unchanged).
     */
    public void submitSnapshot(@NonNull ListSnapshot snapshot) {
        delegate.submitSnapshot(snapshot);
    }

    /** Recommended public submit API when group header/footer chrome depends on expanded state. */
    public void submitSnapshotWithGroupChrome(@NonNull ListSnapshot snapshot) {
        delegate.submitSnapshotWithGroupChrome(snapshot);
    }

    public boolean isGroupExpanded(@NonNull GroupKey groupKey) {
        return delegate.isGroupExpanded(groupKey);
    }

    @Nullable
    public GroupState getGroupState(@NonNull GroupKey groupKey) {
        return delegate.getGroupState(groupKey);
    }

    public boolean isGroupExpandedAt(int anchorPosition) {
        return delegate.isGroupExpandedAt(anchorPosition);
    }

    public int findGroupHeaderPosition(@NonNull GroupKey groupKey) {
        return delegate.findGroupHeaderPosition(groupKey);
    }

    public int findGroupFooterPosition(@NonNull GroupKey groupKey) {
        return delegate.findGroupFooterPosition(groupKey);
    }

    /** Explicit bounds error instead of a buried IndexOutOfBounds: RecyclerView
     *  keeps positions 0..getItemCount()-1 valid at bind time. */
    @NonNull
    public ListSnapshot.Row<?> getRow(int position) {
        return delegate.getRow(position);
    }

    public boolean expandGroup(@NonNull GroupKey groupKey) {
        return delegate.expandGroup(groupKey);
    }

    public boolean collapseGroup(@NonNull GroupKey groupKey) {
        return delegate.collapseGroup(groupKey);
    }

    public int setGroupsExpanded(@Nullable Collection<GroupKey> groupKeys, boolean expanded) {
        return delegate.setGroupsExpanded(groupKeys, expanded);
    }

    public boolean setGroupExpanded(@NonNull GroupKey groupKey, boolean expanded) {
        return delegate.setGroupExpanded(groupKey, expanded);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return delegate.onCreateViewHolder(inflater, parent, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        delegate.onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        delegate.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() {
        return delegate.getItemCount();
    }

    @Override
    public int getItemViewType(int position) {
        return delegate.getItemViewType(position);
    }

    /**
     * Expansion-intent hook for subclasses: the delegate consults it for every
     * group expansion state change. Return {@code true} when the request was
     * accepted (typically by forwarding to an engine/core controller that will
     * submit the resulting snapshot).
     */
    protected boolean requestSetGroupExpanded(@NonNull GroupKey groupKey, boolean expanded) {
        return false;
    }

    // ---- protected notification hooks (kept so existing subclass overrides keep working) ----

    protected void dispatchNotifyDataSetChanged() {
        notifyDataSetChanged();
    }

    protected void notifyItemChangedCompat(int position) {
        notifyItemChanged(position);
    }

    protected void notifyItemRangeInsertedCompat(int positionStart, int itemCount) {
        notifyItemRangeInserted(positionStart, itemCount);
    }

    protected void notifyItemRangeRemovedCompat(int positionStart, int itemCount) {
        notifyItemRangeRemoved(positionStart, itemCount);
    }

    protected void notifyItemMovedCompat(int fromPosition, int toPosition) {
        notifyItemMoved(fromPosition, toPosition);
    }

    protected void notifyItemRangeChangedCompat(int positionStart, int itemCount) {
        notifyItemRangeChanged(positionStart, itemCount);
    }

    protected void notifyItemRangeChangedCompat(int positionStart, int itemCount, Object payload) {
        notifyItemRangeChanged(positionStart, itemCount, payload);
    }

    /**
     * Bridge from the delegate's notification channel onto this instance's protected
     * compat hooks: subclass overrides (e.g. JVM recording test doubles) stay in the
     * call path with zero source change.
     */
    private final class CompatNotifier implements SnapshotAdapterDelegate.Notifier {

        @Override
        public void onDataReload() {
            dispatchNotifyDataSetChanged();
        }

        @Override
        public void onItemChanged(int position) {
            notifyItemChangedCompat(position);
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            notifyItemRangeInsertedCompat(positionStart, itemCount);
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            notifyItemRangeRemovedCompat(positionStart, itemCount);
        }

        @Override
        public void onItemMoved(int fromPosition, int toPosition) {
            notifyItemMovedCompat(fromPosition, toPosition);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            notifyItemRangeChangedCompat(positionStart, itemCount);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, @NonNull Object payload) {
            notifyItemRangeChangedCompat(positionStart, itemCount, payload);
        }
    }
}
