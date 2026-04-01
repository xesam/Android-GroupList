package io.github.xesam.android.grouplist.example.ui.mapper;

import java.util.ArrayList;
import java.util.List;

import io.github.xesam.android.grouplist.example.data.model.FeedBackendModels;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.example.ui.model.FeedViewTypes;

public final class FeedRowMapper {

    public List<FeedUiModels.TopLevelRow> mapBackendRowsToUiRows(List<FeedBackendModels.BackendRow> backendRows) {
        List<FeedUiModels.TopLevelRow> rows = new ArrayList<>();
        for (FeedBackendModels.BackendRow backendRow : backendRows) {
            if (backendRow instanceof FeedBackendModels.StandaloneCard) {
                FeedBackendModels.StandaloneCard card = (FeedBackendModels.StandaloneCard) backendRow;
                rows.add(new FeedUiModels.StandaloneUiModel(
                        new FeedUiModels.StandaloneCardUi(card.tag, card.title, card.body),
                        FeedViewTypes.TYPE_STANDALONE
                ));
                continue;
            }

            if (backendRow instanceof FeedBackendModels.FilterEntry) {
                rows.add(new FeedUiModels.StandaloneUiModel(
                        new FeedUiModels.GroupFilterCard(FeedUiModels.GroupFilterMode.ALL_GROUPS),
                        FeedViewTypes.TYPE_FILTER_STANDALONE
                ));
                continue;
            }

            if (backendRow instanceof FeedBackendModels.CommentGroup) {
                FeedBackendModels.CommentGroup group = (FeedBackendModels.CommentGroup) backendRow;
                FeedUiModels.CommentHeader header = new FeedUiModels.CommentHeader(
                        "帖子  " + group.authorMeta,
                        group.title
                );
                List<FeedUiModels.FeedCommentItem> items = new ArrayList<>();
                for (FeedBackendModels.CommentItem comment : group.comments) {
                    if (comment != null) {
                        items.add(FeedUiModels.FeedCommentItem.loaded(comment.author, comment.content));
                    } else {
                        items.add(FeedUiModels.FeedCommentItem.loading());
                    }
                }
                rows.add(new FeedUiModels.GroupUiModel(
                        group.id,
                        FeedUiModels.GroupType.COMMENT,
                        header,
                        group.footerStats,
                        items,
                        group.expanded,
                        group.showFooterWhenCollapsed
                ));
                continue;
            }

            if (backendRow instanceof FeedBackendModels.TopicGroup) {
                FeedBackendModels.TopicGroup group = (FeedBackendModels.TopicGroup) backendRow;
                FeedUiModels.TopicHeader header = new FeedUiModels.TopicHeader(
                        group.topicTitle,
                        group.sponsor,
                        group.deadline
                );
                List<FeedUiModels.TopicOptionItem> items = new ArrayList<>();
                for (FeedBackendModels.TopicOptionItem option : group.options) {
                    if (option != null) {
                        items.add(FeedUiModels.TopicOptionItem.loaded(option.optionText, option.voteCount));
                    } else {
                        items.add(FeedUiModels.TopicOptionItem.loading());
                    }
                }
                rows.add(new FeedUiModels.GroupUiModel(
                        group.id,
                        FeedUiModels.GroupType.TOPIC,
                        header,
                        new FeedUiModels.TopicFooter(group.participantCount, group.lastUpdated),
                        items,
                        group.expanded,
                        group.showFooterWhenCollapsed
                ));
            }
        }
        return rows;
    }
}
