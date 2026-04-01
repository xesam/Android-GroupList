package io.github.xesam.android.grouplist.example.data.model;

import java.util.List;

public final class FeedBackendModels {

    private FeedBackendModels() {
    }

    public interface BackendRow {
    }

    public static final class StandaloneCard implements BackendRow {
        public final String tag;
        public final String title;
        public final String body;

        public StandaloneCard(String tag, String title, String body) {
            this.tag = tag;
            this.title = title;
            this.body = body;
        }
    }

    public static final class FilterEntry implements BackendRow {
    }

    public static final class CommentGroup implements BackendRow {
        public final String id;
        public final String authorMeta;
        public final String title;
        public final String footerStats;
        /** 未到位的槽位用 null 占位，骨架渲染由 UI 层决定。 */
        public final List<CommentItem> comments;
        public final boolean expanded;
        public final boolean showFooterWhenCollapsed;

        public CommentGroup(
                String id,
                String authorMeta,
                String title,
                String footerStats,
                List<CommentItem> comments,
                boolean expanded,
                boolean showFooterWhenCollapsed
        ) {
            this.id = id;
            this.authorMeta = authorMeta;
            this.title = title;
            this.footerStats = footerStats;
            this.comments = comments;
            this.expanded = expanded;
            this.showFooterWhenCollapsed = showFooterWhenCollapsed;
        }
    }

    public static final class TopicGroup implements BackendRow {
        public final String id;
        public final String topicTitle;
        public final String sponsor;
        public final String deadline;
        public final int participantCount;
        public final String lastUpdated;
        /** 未到位的槽位用 null 占位，骨架渲染由 UI 层决定。 */
        public final List<TopicOptionItem> options;
        public final boolean expanded;
        public final boolean showFooterWhenCollapsed;

        public TopicGroup(
                String id,
                String topicTitle,
                String sponsor,
                String deadline,
                int participantCount,
                String lastUpdated,
                List<TopicOptionItem> options,
                boolean expanded,
                boolean showFooterWhenCollapsed
        ) {
            this.id = id;
            this.topicTitle = topicTitle;
            this.sponsor = sponsor;
            this.deadline = deadline;
            this.participantCount = participantCount;
            this.lastUpdated = lastUpdated;
            this.options = options;
            this.expanded = expanded;
            this.showFooterWhenCollapsed = showFooterWhenCollapsed;
        }
    }

    public static final class CommentItem {
        public final String author;
        public final String content;

        public static CommentItem loaded(String author, String content) {
            return new CommentItem(author, content);
        }

        private CommentItem(String author, String content) {
            this.author = author;
            this.content = content;
        }
    }

    public static final class TopicOptionItem {
        public final String optionText;
        public final int voteCount;

        public static TopicOptionItem loaded(String optionText, int voteCount) {
            return new TopicOptionItem(optionText, voteCount);
        }

        private TopicOptionItem(String optionText, int voteCount) {
            this.optionText = optionText;
            this.voteCount = voteCount;
        }
    }
}
