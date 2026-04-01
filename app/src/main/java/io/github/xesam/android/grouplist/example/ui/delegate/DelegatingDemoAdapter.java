package io.github.xesam.android.grouplist.example.ui.delegate;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import io.github.xesam.android.grouplist.example.ui.common.FeedItemBinders;
import io.github.xesam.android.grouplist.recyclerview.delegate.AdapterNotifier;
import io.github.xesam.android.grouplist.recyclerview.delegate.SnapshotAdapterDelegate;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.GroupState;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * 组合路径 Demo：Adapter 继承的是公司基类（DemoBaseAdapter），与 GroupList
 * 库的接入完全通过持有 {@link SnapshotAdapterDelegate} 完成。
 *
 * <p>binder / Holder 一律来自 {@link FeedItemBinders}（与继承路径共用的同一份
 * 渲染实现），本类只保留接入方式差异：</p>
 * <ul>
 *   <li> 快照提交：delegate.submitSnapshotWithGroupChrome(...)（继承路径里是同名 facade 方法）；</li>
 *   <li> 通知发射：{@link AdapterNotifier} 包装 this，精确通知从本 Adapter 实例发出；</li>
 *   <li> 展开意图：delegate.setOnExpansionRequest(...) 回调（继承路径里是覆写 protected
 *        requestSetGroupExpanded 钩子）；</li>
 *   <li> group 查询：delegate.getGroupState(...) 等（GroupState 是 core 下沉的共享类型）。</li>
 * </ul>
 */
public final class DelegatingDemoAdapter extends DemoBaseAdapter {

    private final SnapshotAdapterDelegate delegate = new SnapshotAdapterDelegate(new AdapterNotifier(this));

    public DelegatingDemoAdapter(
            FeedItemBinders.FilterModeSelectedListener filterModeSelectedListener,
            FeedItemBinders.GroupToggleRequestedListener groupToggleRequestedListener
    ) {
        super("delegate-demo");
        // 展开/收起意图经回调上抛（继承路径的写法是覆写 requestSetGroupExpanded 钩子）。
        // Binder 的 toggle 点击先进 delegate 的展开入口，让回调成为真实意图通道。
        // 注意：demo 与 Engine 页同为 toggle 语义，回调忽略 expanded 方向参数——
        // 对本 Adapter 的其他调用方仍不要调用明确方向的 setGroupExpanded(key, false)。
        delegate.setOnExpansionRequest((groupKey, expanded) -> {
            groupToggleRequestedListener.onToggleRequested(groupKey);
            return true;
        });
        FeedItemBinders.GroupToggleRequestedListener viaExpansionEntry = groupKey ->
                setGroupExpanded(groupKey, !isGroupExpanded(groupKey));
        new FeedItemBinders(
                filterModeSelectedListener,
                viaExpansionEntry,
                // group chrome：组合路径从 delegate 查询（继承路径是 facade 同名方法），经本类包装透出
                this::isGroupExpandedAt
        ).install(delegate::registerBinder);
    }

    public void submitSnapshot(@NonNull ListSnapshot snapshot) {
        delegate.submitSnapshotWithGroupChrome(snapshot);
    }

    // ---- RecyclerView.Adapter 契约：转发给 delegate ----

    @Override
    public int getItemCount() {
        return delegate.getItemCount();
    }

    @Override
    public int getItemViewType(int position) {
        return delegate.getItemViewType(position);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return delegate.onCreateViewHolder(inflater, parent, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        recordImpression(position, "vt=" + delegate.getItemViewType(position));
        delegate.onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        // forward the payloads variant too, or UPDATE 的 PAYLOAD_CONTENT_UPDATE
        // 部分重绑会静默退化为全量重绑（RecyclerView.Adapter 默认只回调无 payload 版本）
        recordImpression(position, "vt=" + delegate.getItemViewType(position));
        delegate.onBindViewHolder(holder, position, payloads);
    }

    // ---- group 查询：从 delegate 透出（供 UI / 协调器使用） ----

    public boolean isGroupExpanded(@NonNull GroupKey groupKey) {
        return delegate.isGroupExpanded(groupKey);
    }

    /** 组合路径的展开便捷入口，与继承路径 facade 的同名方法一一对应。 */
    public boolean setGroupExpanded(@NonNull GroupKey groupKey, boolean expanded) {
        return delegate.setGroupExpanded(groupKey, expanded);
    }

    public boolean isGroupExpandedAt(int anchorPosition) {
        return delegate.isGroupExpandedAt(anchorPosition);
    }

    @Nullable
    public GroupState getGroupState(@NonNull GroupKey groupKey) {
        return delegate.getGroupState(groupKey);
    }
}
