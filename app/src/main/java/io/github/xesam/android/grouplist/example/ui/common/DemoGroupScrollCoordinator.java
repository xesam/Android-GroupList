package io.github.xesam.android.grouplist.example.ui.common;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.github.xesam.android.grouplist.recyclerview.adapter.GroupListRecyclerAdapter;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.GroupState;

public final class DemoGroupScrollCoordinator {

    private final RecyclerView recyclerView;
    private final LinearLayoutManager layoutManager;
    private final GroupListRecyclerAdapter adapter;

    /**
     * 待滚动展开组的队列（旧单槽实现会被连续 toggle 互相覆盖）。
     * 语义：toggle 为展开时排到队尾；随后的收起把同组的待滚动画图撤销。
     */
    private final ArrayDeque<GroupKey> pendingExpandedGroupKeys = new ArrayDeque<>();

    public DemoGroupScrollCoordinator(
            @NonNull RecyclerView recyclerView,
            @NonNull LinearLayoutManager layoutManager,
            @NonNull GroupListRecyclerAdapter adapter
    ) {
        this.recyclerView = recyclerView;
        this.layoutManager = layoutManager;
        this.adapter = adapter;
    }

    public void onGroupToggleRequested(@NonNull GroupKey groupKey) {
        if (adapter.isGroupExpanded(groupKey)) {
            pendingExpandedGroupKeys.remove(groupKey); // 即将收起：撤销仍在途的展开滚动
        } else {
            pendingExpandedGroupKeys.addLast(groupKey);
        }
    }

    public void onSnapshotApplied() {
        if (pendingExpandedGroupKeys.isEmpty()) {
            return;
        }
        List<GroupKey> batch = new ArrayList<>(pendingExpandedGroupKeys);
        pendingExpandedGroupKeys.clear();
        recyclerView.post(() -> {
            // 顺序应用，最终停留位置由最后一个能整组放进的展开组决定
            for (GroupKey groupKey : batch) {
                scrollExpandedGroupToTopIfFullyFits(groupKey);
            }
        });
    }

    private void scrollExpandedGroupToTopIfFullyFits(@NonNull GroupKey groupKey) {
        GroupState state = adapter.getGroupState(groupKey);
        if (state == null || !state.expanded()) {
            return;
        }

        int headerPosition = state.headerPosition();
        int lastPosition = headerPosition + state.visibleItemCount() - 1;
        if (headerPosition < 0 || lastPosition < headerPosition) {
            return;
        }
        if (lastPosition > layoutManager.findLastVisibleItemPosition()) {
            return;
        }

        RecyclerView.ViewHolder headerHolder = recyclerView.findViewHolderForAdapterPosition(headerPosition);
        RecyclerView.ViewHolder lastHolder = recyclerView.findViewHolderForAdapterPosition(lastPosition);
        if (headerHolder == null || lastHolder == null) {
            return;
        }

        int viewportTop = recyclerView.getPaddingTop();
        int viewportHeight = recyclerView.getHeight() - recyclerView.getPaddingTop() - recyclerView.getPaddingBottom();
        int groupHeight = lastHolder.itemView.getBottom() - headerHolder.itemView.getTop();
        if (groupHeight > viewportHeight) {
            return;
        }
        if (headerHolder.itemView.getTop() == viewportTop) {
            return;
        }

        layoutManager.scrollToPositionWithOffset(headerPosition, viewportTop);
    }
}
