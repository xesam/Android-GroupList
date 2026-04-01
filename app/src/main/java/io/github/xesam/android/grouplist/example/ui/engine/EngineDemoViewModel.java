package io.github.xesam.android.grouplist.example.ui.engine;

import java.util.ArrayList;
import java.util.List;

import io.github.xesam.android.grouplist.example.ui.common.BaseDemoViewModel;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.example.ui.model.FeedViewTypes;
import io.github.xesam.android.grouplist.vnext.engine.BinderRegistry;
import io.github.xesam.android.grouplist.vnext.engine.DefaultEngineController;
import io.github.xesam.android.grouplist.vnext.engine.EngineCommand;
import io.github.xesam.android.grouplist.vnext.engine.GroupSchema;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

public final class EngineDemoViewModel extends BaseDemoViewModel {

    // Engine demo is the default learning path: submit business rows and let schema drive Core conversion.
    private final DefaultEngineController engineController;

    public EngineDemoViewModel() {
        engineController = createEngineController();
        initialize();
    }

    @Override
    protected void onInitialized() {
        submitSourceRowsToEngine();
        engineController.dispatch(new EngineCommand.SetVisibilityFilter(currentFilterMode));
    }

    @Override
    protected void onFilterModeChanged(FeedUiModels.GroupFilterMode mode) {
        // The filter CARD is a standalone whose content changes with the mode: update it
        // in place instead of a full re-submit (re-submit reload-reset would collapse
        // everything the user expanded — the exact acceptance failure we are fixing).
        //
        // Atomic pair: the card UPDATE and the SetVisibilityFilter dispatch below MUST
        // be issued together (and the card content must always mirror the filter mode)
        // — the card is the only visual representation of filterPayload, so delivering
        // one without the other leaves UI state and engine state diverged.
        FeedUiModels.GroupFilterCard previousCard = lastSubmittedFilterCard();
        if (previousCard != null) {
            engineController.dispatch(EngineCommand.UpdateStandalone.ofData(
                    previousCard,
                    createFilterCardForSubmit(mode)
            ));
        }
        engineController.dispatch(new EngineCommand.SetVisibilityFilter(mode));
    }

    @Override
    protected void onGroupToggleRequested(GroupKey groupKey) {
        // Toggle 由 controller 从唯一事实源派生目标，ViewModel 不传预派生状态
        engineController.dispatch(new EngineCommand.ToggleGroup(groupKey));
    }

    @Override
    protected void onGroupItemsResolved(GroupKey groupKey, List<Object> resolvedChildren) {
        engineController.dispatch(new EngineCommand.ReplaceChildren(groupKey, resolvedChildren));
    }

    @Override
    protected boolean isGroupExpanded(GroupKey groupKey) {
        return engineController.isGroupExpanded(groupKey);
    }

    @Override
    protected ListSnapshot currentSnapshot() {
        return engineController.snapshot();
    }

    @Override
    protected void drainPendingChanges() {
        engineController.takeChangeSet();
    }

    private void submitSourceRowsToEngine() {
        List<Object> rows = new ArrayList<>();
        for (FeedUiModels.TopLevelRow row : sourceRows) {
            if (row instanceof FeedUiModels.StandaloneUiModel) {
                FeedUiModels.StandaloneUiModel standalone = (FeedUiModels.StandaloneUiModel) row;
                rows.add(normalizeStandaloneData(standalone));
                continue;
            }
            rows.add((FeedUiModels.GroupUiModel) row);
        }
        engineController.submit(rows);
    }

    private DefaultEngineController createEngineController() {
        return DefaultEngineController.builder()
                .registerSchema(FeedUiModels.GroupUiModel.class, new UiGroupSchema())
                .registerStandaloneViewType(FeedUiModels.StandaloneCardUi.class, FeedViewTypes.TYPE_STANDALONE)
                .registerStandaloneViewType(FeedUiModels.GroupFilterCard.class, FeedViewTypes.TYPE_FILTER_STANDALONE)
                .registerGroupHeaderViewType(FeedUiModels.CommentHeader.class, FeedViewTypes.TYPE_GROUP_HEADER)
                .registerGroupHeaderViewType(FeedUiModels.TopicHeader.class, FeedViewTypes.TYPE_TOPIC_GROUP_HEADER)
                .registerGroupItemViewType(FeedUiModels.FeedCommentItem.class, FeedViewTypes.TYPE_GROUP_ITEM)
                .registerGroupItemViewType(FeedUiModels.TopicOptionItem.class, FeedViewTypes.TYPE_TOPIC_GROUP_ITEM)
                .registerGroupFooterViewType(String.class, FeedViewTypes.TYPE_GROUP_FOOTER)
                .registerGroupFooterViewType(FeedUiModels.TopicFooter.class, FeedViewTypes.TYPE_TOPIC_GROUP_FOOTER)
                // demo-specific escape hatch: class-registered viewTypes describe TYPE,
                // this resolver describes STATE (loading skeletons of the same class).
                // Nothing in the library contract depends on this tag's value.
                .registerViewTypeResolver("loading-state", (role, model) -> {
                    if (role != BinderRegistry.ViewRole.GROUP_ITEM) {
                        return BinderRegistry.ViewTypeResolver.UNHANDLED;
                    }
                    if (model instanceof FeedUiModels.FeedCommentItem
                            && ((FeedUiModels.FeedCommentItem) model).loading) {
                        return FeedViewTypes.TYPE_FEED_GROUP_ITEM_LOADING;
                    }
                    if (model instanceof FeedUiModels.TopicOptionItem
                            && ((FeedUiModels.TopicOptionItem) model).loading) {
                        return FeedViewTypes.TYPE_TOPIC_GROUP_ITEM_LOADING;
                    }
                    return BinderRegistry.ViewTypeResolver.UNHANDLED;
                })
                .setGroupVisibilityResolver((groupKey, sourceGroup, filterPayload) -> {
                    if (!(sourceGroup instanceof FeedUiModels.GroupUiModel)) {
                        return true;
                    }
                    if (!(filterPayload instanceof FeedUiModels.GroupFilterMode)) {
                        return true;
                    }
                    FeedUiModels.GroupUiModel group = (FeedUiModels.GroupUiModel) sourceGroup;
                    FeedUiModels.GroupFilterMode mode = (FeedUiModels.GroupFilterMode) filterPayload;
                    if (mode == FeedUiModels.GroupFilterMode.COMMENT_ONLY) {
                        return group.type == FeedUiModels.GroupType.COMMENT;
                    }
                    if (mode == FeedUiModels.GroupFilterMode.TOPIC_ONLY) {
                        return group.type == FeedUiModels.GroupType.TOPIC;
                    }
                    return true;
                })
                .build();
    }

    private static final class UiGroupSchema implements GroupSchema<FeedUiModels.GroupUiModel, Object, Object, Object> {
        @Override
        public GroupKey keyOf(FeedUiModels.GroupUiModel group) {
            return GroupKey.of(group.id);
        }

        @Override
        public Object headerOf(FeedUiModels.GroupUiModel group) {
            return group.header;
        }

        @Override
        public List<Object> childrenOf(FeedUiModels.GroupUiModel group) {
            return group.items;
        }

        @Override
        public Object footerOf(FeedUiModels.GroupUiModel group) {
            return group.footer;
        }

        @Override
        public boolean initiallyExpanded(FeedUiModels.GroupUiModel group) {
            return group.expanded;
        }

        @Override
        public boolean showFooterWhenCollapsed(FeedUiModels.GroupUiModel group) {
            return group.showFooterWhenCollapsed;
        }
    }
}
