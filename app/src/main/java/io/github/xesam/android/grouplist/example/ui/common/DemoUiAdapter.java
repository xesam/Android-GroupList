package io.github.xesam.android.grouplist.example.ui.common;

import androidx.annotation.NonNull;

import io.github.xesam.android.grouplist.recyclerview.adapter.GroupListRecyclerAdapter;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * 继承路径 Demo 渲染器。binder / Holder 一律来自 {@link FeedItemBinders}（与组合
 * 路径共用的同一份渲染实现），本类只保留接入方式差异：继承
 * {@link GroupListRecyclerAdapter} 并覆写 protected 展开钩子。
 */
public final class DemoUiAdapter extends GroupListRecyclerAdapter {

    private final FeedItemBinders.GroupToggleRequestedListener groupToggleRequestedListener;

    public DemoUiAdapter(
            FeedItemBinders.FilterModeSelectedListener filterModeSelectedListener,
            FeedItemBinders.GroupToggleRequestedListener groupToggleRequestedListener
    ) {
        this.groupToggleRequestedListener = groupToggleRequestedListener;
        // Binder 的 toggle 点击先进本类的展开便捷入口（而非直接通知 ViewModel），
        // 让 protected requestSetGroupExpanded 钩子成为真实意图通道——这正是
        // 继承路径文档承诺的接线方式。
        FeedItemBinders.GroupToggleRequestedListener viaExpansionEntry = groupKey ->
                setGroupExpanded(groupKey, !isGroupExpanded(groupKey));
        new FeedItemBinders(
                filterModeSelectedListener,
                viaExpansionEntry,
                this::isGroupExpandedAt
        ).install(this::registerBinder);
    }

    @Override
    public void submitSnapshot(@NonNull ListSnapshot snapshot) {
        submitSnapshotWithGroupChrome(snapshot);
    }

    @Override
    protected boolean requestSetGroupExpanded(@NonNull GroupKey groupKey, boolean expanded) {
        // The expanded-equality guard was already applied by setGroupExpanded (base class):
        // this hook is only ever reached for an actual state transition.
        groupToggleRequestedListener.onToggleRequested(groupKey);
        return true;
    }
}
