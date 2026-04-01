package io.github.xesam.android.grouplist.example.ui.model;

import java.util.ArrayList;
import java.util.List;

public final class FeedUiModels {

    private FeedUiModels() {
    }

    public enum GroupFilterMode {
        ALL_GROUPS,
        COMMENT_ONLY,
        TOPIC_ONLY
    }

    public enum GroupType {
        COMMENT,
        TOPIC
    }

    public interface TopLevelRow {
    }

    public static final class StandaloneUiModel implements TopLevelRow {
        public final Object data;
        public final int viewType;

        public StandaloneUiModel(Object data, int viewType) {
            this.data = data;
            this.viewType = viewType;
        }
    }

    public static final class GroupUiModel implements TopLevelRow {
        public final String id;
        public final GroupType type;
        public final Object header;
        public final Object footer;
        public final boolean showFooterWhenCollapsed;
        public final List<Object> items = new ArrayList<>();
        /** Initial expansion state. Live expansion state lives in the list controller —
         * query it there instead of duplicating it here. */
        public final boolean expanded;

        public GroupUiModel(
                String id,
                GroupType type,
                Object header,
                Object footer,
                List<?> items,
                boolean expanded,
                boolean showFooterWhenCollapsed
        ) {
            this.id = id;
            this.type = type;
            this.header = header;
            this.footer = footer;
            this.expanded = expanded;
            this.showFooterWhenCollapsed = showFooterWhenCollapsed;
            if (items != null) {
                this.items.addAll(items);
            }
        }
    }

    /**
     * 值语义（mode 即全量状态）：UpdateStandalone 的 ofData 窗口按 equals 定位槽位，
     * 值语义消除“必须持有上次 submit 的同一实例作锚点”的隐性纪律。
     */
    public static final class GroupFilterCard {
        public final GroupFilterMode mode;

        public GroupFilterCard(GroupFilterMode mode) {
            this.mode = mode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GroupFilterCard)) {
                return false;
            }
            return mode == ((GroupFilterCard) o).mode;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(mode);
        }
    }

    /** 顶格 standalone 卡片（公告/发现类），结构化替代旧“标题\n正文”字符串拼接。
     *  tag 是后端下发的分类标签，由 Mapper 填充，渲染层直接使用，
     *  禁止再从 title 反推（两处重复的魔法字符串推断裂痕已被此字段消灭）。 */
    public static final class StandaloneCardUi {
        public final String tag;
        public final String title;
        public final String body;

        public StandaloneCardUi(String tag, String title, String body) {
            this.tag = tag;
            this.title = title;
            this.body = body;
        }
    }

    /** 评论分组的结构化 header，替代旧“meta\ntitle”字符串拼接与充血解析。 */
    public static final class CommentHeader {
        public final String meta;
        public final String title;

        public CommentHeader(String meta, String title) {
            this.meta = meta;
            this.title = title;
        }
    }

    public static final class TopicHeader {
        public final String title;
        public final String sponsor;
        public final String deadline;

        public TopicHeader(String title, String sponsor, String deadline) {
            this.title = title;
            this.sponsor = sponsor;
            this.deadline = deadline;
        }
    }

    public static final class TopicOption {
        public final String optionText;
        public final int voteCount;

        public TopicOption(String optionText, int voteCount) {
            this.optionText = optionText;
            this.voteCount = voteCount;
        }
    }

    public static final class FeedCommentItem {
        public final boolean loading;
        public final String author;
        public final String content;

        private FeedCommentItem(boolean loading, String author, String content) {
            this.loading = loading;
            this.author = author;
            this.content = content;
        }

        public static FeedCommentItem loaded(String author, String content) {
            return new FeedCommentItem(false, author, content);
        }

        public static FeedCommentItem loading() {
            return new FeedCommentItem(true, "", "");
        }
    }

    public static final class TopicOptionItem {
        public final boolean loading;
        public final TopicOption option;

        private TopicOptionItem(boolean loading, TopicOption option) {
            this.loading = loading;
            this.option = option;
        }

        public static TopicOptionItem loaded(String optionText, int voteCount) {
            return new TopicOptionItem(false, new TopicOption(optionText, voteCount));
        }

        public static TopicOptionItem loading() {
            return new TopicOptionItem(true, null);
        }
    }

    public static final class TopicFooter {
        public final int participantCount;
        public final String lastUpdated;

        public TopicFooter(int participantCount, String lastUpdated) {
            this.participantCount = participantCount;
            this.lastUpdated = lastUpdated;
        }
    }

}
