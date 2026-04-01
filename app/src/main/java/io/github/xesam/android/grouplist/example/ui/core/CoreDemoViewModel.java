package io.github.xesam.android.grouplist.example.ui.core;

import java.util.ArrayList;
import java.util.List;

import io.github.xesam.android.grouplist.example.ui.common.BaseDemoViewModel;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.example.ui.model.FeedViewTypes;
import io.github.xesam.android.grouplist.vnext.core.CoreCommand;
import io.github.xesam.android.grouplist.vnext.core.CoreController;
import io.github.xesam.android.grouplist.vnext.core.CoreRow;
import io.github.xesam.android.grouplist.vnext.core.DefaultCoreController;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

public final class CoreDemoViewModel extends BaseDemoViewModel {

    // Core demo is the advanced path: build CoreRow explicitly for full control.
    private final CoreController coreController = new DefaultCoreController();

    public CoreDemoViewModel() {
        initialize();
    }

    @Override
    protected void onInitialized() {
        submitCurrentRows();
    }

    @Override
    protected void onFilterModeChanged(FeedUiModels.GroupFilterMode mode) {
        // visibility-only change: reconcile keeps expansion (and the filter card's
        // content drift is carried out as a precise standalone UPDATE)
        coreController.dispatch(new CoreCommand.ReconcileRows(buildRows()));
    }

    @Override
    protected void onGroupToggleRequested(GroupKey groupKey) {
        coreController.dispatch(new CoreCommand.ToggleGroup(groupKey));
    }

    @Override
    protected void onGroupItemsResolved(GroupKey groupKey, List<Object> resolvedChildren) {
        // 全量 submit 会把展开状态重置为 initiallyExpanded(公开契约)，导致骨架填充后组被收起；
        // 子项已就位时走 ReplaceChildren 精确增量通道，保持展开态并只下发 ChildChange。
        coreController.dispatch(new CoreCommand.ReplaceChildren(groupKey, resolvedChildren));
    }

    @Override
    protected boolean isGroupExpanded(GroupKey groupKey) {
        return coreController.isGroupExpanded(groupKey);
    }

    @Override
    protected ListSnapshot currentSnapshot() {
        return coreController.snapshot();
    }

    @Override
    protected void drainPendingChanges() {
        coreController.takeChangeSet();
    }

    private void submitCurrentRows() {
        coreController.submit(buildRows());
    }

    private List<CoreRow> buildRows() {
        List<CoreRow> rows = new ArrayList<>();
        for (FeedUiModels.TopLevelRow row : sourceRows) {
            if (row instanceof FeedUiModels.StandaloneUiModel) {
                FeedUiModels.StandaloneUiModel standalone = (FeedUiModels.StandaloneUiModel) row;
                rows.add(CoreRow.Standalone.of(normalizeStandaloneData(standalone), standalone.viewType));
                continue;
            }

            FeedUiModels.GroupUiModel group = (FeedUiModels.GroupUiModel) row;
            if (!isVisible(group)) {
                continue;
            }
            rows.add(CoreRow.Group.builder(GroupKey.of(group.id))
            .header(group.header)
            .footer(group.footer)
            .children(group.items)
            .initiallyExpanded(group.expanded)
            .showFooterWhenCollapsed(group.showFooterWhenCollapsed)
            .headerViewType(resolveHeaderType(group))
            .footerViewType(resolveFooterType(group))
            .childViewTypeResolver((childIndex, childData) -> resolveChildType(group, childData))
            .build());
        }
        return rows;
    }

    private boolean isVisible(FeedUiModels.GroupUiModel group) {
        if (currentFilterMode == FeedUiModels.GroupFilterMode.COMMENT_ONLY) {
            return group.type == FeedUiModels.GroupType.COMMENT;
        }
        if (currentFilterMode == FeedUiModels.GroupFilterMode.TOPIC_ONLY) {
            return group.type == FeedUiModels.GroupType.TOPIC;
        }
        return true;
    }

    private int resolveHeaderType(FeedUiModels.GroupUiModel group) {
        return group.type == FeedUiModels.GroupType.TOPIC
                ? FeedViewTypes.TYPE_TOPIC_GROUP_HEADER
                : FeedViewTypes.TYPE_GROUP_HEADER;
    }

    private int resolveFooterType(FeedUiModels.GroupUiModel group) {
        return group.type == FeedUiModels.GroupType.TOPIC
                ? FeedViewTypes.TYPE_TOPIC_GROUP_FOOTER
                : FeedViewTypes.TYPE_GROUP_FOOTER;
    }

    private int resolveChildType(FeedUiModels.GroupUiModel group, Object childData) {
        if (group.type == FeedUiModels.GroupType.TOPIC) {
            if (childData instanceof FeedUiModels.TopicOptionItem
                    && ((FeedUiModels.TopicOptionItem) childData).loading) {
                return FeedViewTypes.TYPE_TOPIC_GROUP_ITEM_LOADING;
            }
            return FeedViewTypes.TYPE_TOPIC_GROUP_ITEM;
        }

        if (childData instanceof FeedUiModels.FeedCommentItem
                && ((FeedUiModels.FeedCommentItem) childData).loading) {
            return FeedViewTypes.TYPE_FEED_GROUP_ITEM_LOADING;
        }
        return FeedViewTypes.TYPE_GROUP_ITEM;
    }
}
