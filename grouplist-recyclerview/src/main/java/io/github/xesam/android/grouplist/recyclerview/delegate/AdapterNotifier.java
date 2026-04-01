package io.github.xesam.android.grouplist.recyclerview.delegate;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Direct {@link SnapshotAdapterDelegate.Notifier} wiring for composition consumers:
 * every delegate notification routes straight into the notify family of the
 * consumer's own {@link RecyclerView.Adapter}, which is the whole point of the
 * composition path — the notifications must fire from the consumer's adapter
 * instance, and the inheritance path is not available to it.
 *
 * <p>Composition usage:</p>
 * <pre>{@code
 * class MyAdapter extends MyCompanyBaseAdapter {
 *     private final SnapshotAdapterDelegate delegate =
 *             new SnapshotAdapterDelegate(new AdapterNotifier(this));
 *     // forward getItemCount/getItemViewType/onCreateViewHolder/onBindViewHolder to the delegate
 * }
 * }</pre>
 */
public final class AdapterNotifier implements SnapshotAdapterDelegate.Notifier {

    private final RecyclerView.Adapter<?> adapter;

    public AdapterNotifier(@NonNull RecyclerView.Adapter<?> adapter) {
        this.adapter = adapter;
    }

    @Override
    public void onDataReload() {
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onItemChanged(int position) {
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onItemRangeInserted(int positionStart, int itemCount) {
        adapter.notifyItemRangeInserted(positionStart, itemCount);
    }

    @Override
    public void onItemRangeRemoved(int positionStart, int itemCount) {
        adapter.notifyItemRangeRemoved(positionStart, itemCount);
    }

    @Override
    public void onItemMoved(int fromPosition, int toPosition) {
        adapter.notifyItemMoved(fromPosition, toPosition);
    }

    @Override
    public void onItemRangeChanged(int positionStart, int itemCount) {
        adapter.notifyItemRangeChanged(positionStart, itemCount);
    }

    @Override
    public void onItemRangeChanged(int positionStart, int itemCount, @NonNull Object payload) {
        adapter.notifyItemRangeChanged(positionStart, itemCount, payload);
    }
}
