package io.github.xesam.android.grouplist.example.ui.common;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import io.github.xesam.android.grouplist.example.R;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.example.ui.model.FeedViewTypes;
import io.github.xesam.android.grouplist.recyclerview.binder.ItemBinder;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

/**
 * All feed-demos share one binder set: every renderer entry (inheritance demo,
 * composition demo) registers exactly these binders — only the adapter plumbing
 * (entry-point wiring) differs between demos. Holder classes and bind helpers
 * live here too, so the two demo adapters contain nothing but their wiring.
 */
public final class FeedItemBinders {

    public interface FilterModeSelectedListener {
        void onSelected(FeedUiModels.GroupFilterMode mode);
    }

    public interface GroupToggleRequestedListener {
        void onToggleRequested(GroupKey groupKey);
    }

    /** Receives one (viewType, binder) pair; each demo adapter implements its own channel. */
    public interface BinderRegistrar {
        void registerBinder(int viewType, ItemBinder binder);
    }

    /** Group-chrome queries the header/footer binders need to render expanded state. */
    public interface GroupChromeQueries {
        boolean isGroupExpandedAt(int anchorPosition);
    }

    private final FilterModeSelectedListener filterModeSelectedListener;
    private final GroupToggleRequestedListener groupToggleRequestedListener;
    private final GroupChromeQueries groupChromeQueries;

    public FeedItemBinders(
            FilterModeSelectedListener filterModeSelectedListener,
            GroupToggleRequestedListener groupToggleRequestedListener,
            GroupChromeQueries groupChromeQueries
    ) {
        this.filterModeSelectedListener = filterModeSelectedListener;
        this.groupToggleRequestedListener = groupToggleRequestedListener;
        this.groupChromeQueries = groupChromeQueries;
    }

    public void install(BinderRegistrar registrar) {
        registrar.registerBinder(FeedViewTypes.TYPE_FILTER_STANDALONE, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new FilterStandaloneHolder(inflater.inflate(R.layout.item_group_filter_standalone, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindFilterStandalone((FilterStandaloneHolder) holder, (FeedUiModels.GroupFilterCard) row.data());
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_STANDALONE, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new StandaloneHolder(inflater.inflate(R.layout.item_feed_standalone, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindStandalone((StandaloneHolder) holder, (FeedUiModels.StandaloneCardUi) row.data(), row.mapping());
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_GROUP_HEADER, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new GroupHeaderHolder(inflater.inflate(R.layout.item_feed_group_header, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindGroupHeader((GroupHeaderHolder) holder, (FeedUiModels.CommentHeader) row.data(), row.mapping(), position);
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_GROUP_ITEM, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new GroupContentHolder(inflater.inflate(R.layout.item_feed_group_content, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindGroupContent((GroupContentHolder) holder, (FeedUiModels.FeedCommentItem) row.data(), row.mapping());
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_FEED_GROUP_ITEM_LOADING, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new GroupContentLoadingHolder(inflater.inflate(R.layout.item_feed_group_content_loading, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                holder.itemView.setOnClickListener(null);
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_GROUP_FOOTER, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new GroupFooterHolder(inflater.inflate(R.layout.item_feed_group_footer, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindGroupFooter((GroupFooterHolder) holder, String.valueOf(row.data()), row.mapping(), position);
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_TOPIC_GROUP_HEADER, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new TopicHeaderHolder(inflater.inflate(R.layout.item_topic_group_header, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindTopicHeader((TopicHeaderHolder) holder, (FeedUiModels.TopicHeader) row.data(), row.mapping(), position);
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_TOPIC_GROUP_ITEM, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new TopicContentHolder(inflater.inflate(R.layout.item_topic_group_content, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindTopicContent((TopicContentHolder) holder, (FeedUiModels.TopicOptionItem) row.data(), row.mapping());
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_TOPIC_GROUP_ITEM_LOADING, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new TopicContentLoadingHolder(inflater.inflate(R.layout.item_topic_group_content_loading, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                holder.itemView.setOnClickListener(null);
            }
        });

        registrar.registerBinder(FeedViewTypes.TYPE_TOPIC_GROUP_FOOTER, new ItemBinder() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                return new TopicFooterHolder(inflater.inflate(R.layout.item_topic_group_footer, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @NonNull ListSnapshot.Row<?> row, int position) {
                bindTopicFooter((TopicFooterHolder) holder, (FeedUiModels.TopicFooter) row.data(), row.mapping(), position);
            }
        });
    }

    // ---- bind helpers ----

    private void bindFilterStandalone(FilterStandaloneHolder holder, FeedUiModels.GroupFilterCard card) {
        bindFilterChip(holder.allGroups, card.mode == FeedUiModels.GroupFilterMode.ALL_GROUPS,
                () -> filterModeSelectedListener.onSelected(FeedUiModels.GroupFilterMode.ALL_GROUPS));
        bindFilterChip(holder.commentOnly, card.mode == FeedUiModels.GroupFilterMode.COMMENT_ONLY,
                () -> filterModeSelectedListener.onSelected(FeedUiModels.GroupFilterMode.COMMENT_ONLY));
        bindFilterChip(holder.topicOnly, card.mode == FeedUiModels.GroupFilterMode.TOPIC_ONLY,
                () -> filterModeSelectedListener.onSelected(FeedUiModels.GroupFilterMode.TOPIC_ONLY));
    }

    private void bindFilterChip(TextView chip, boolean selected, Runnable onClick) {
        chip.setSelected(selected);
        chip.setOnClickListener(v -> onClick.run());
    }

    private void bindStandalone(StandaloneHolder holder, FeedUiModels.StandaloneCardUi card, PositionMapping mapping) {
        holder.tag.setText(card.tag);
        holder.title.setText(card.title);
        holder.body.setText(card.body);
        holder.itemView.setOnClickListener(v -> showMappingToast(holder, mapping));
    }

    private void bindGroupHeader(GroupHeaderHolder holder, FeedUiModels.CommentHeader header, PositionMapping mapping, int position) {
        GroupKey groupKey = mapping.groupKey();
        boolean expanded = isExpandedAt(groupKey, position);

        holder.state.setText(expanded ? "收起评论" : "展开评论");
        holder.meta.setText(header.meta);
        holder.title.setText(header.title);
        bindGroupToggle(holder.itemView, groupKey);
    }

    private void bindGroupContent(GroupContentHolder holder, FeedUiModels.FeedCommentItem item, PositionMapping mapping) {
        holder.author.setText(item.author);
        holder.content.setText(item.content);
        holder.avatar.setText(firstChar(item.author));
        holder.itemView.setOnClickListener(v -> showMappingToast(holder, mapping));
    }

    private void bindGroupFooter(GroupFooterHolder holder, String raw, PositionMapping mapping, int position) {
        GroupKey groupKey = mapping.groupKey();
        boolean expanded = isExpandedAt(groupKey, position);
        holder.stats.setText(raw);
        holder.actions.setText(expanded ? "收起评论" : "展开评论");
        bindGroupToggle(holder.actions, groupKey);
    }

    private void bindTopicHeader(TopicHeaderHolder holder, FeedUiModels.TopicHeader header, PositionMapping mapping, int position) {
        GroupKey groupKey = mapping.groupKey();
        boolean expanded = isExpandedAt(groupKey, position);

        holder.state.setText(expanded ? "收起投票选项" : "展开投票选项");
        holder.title.setText(header.title);
        holder.meta.setText(header.sponsor + "  ·  " + header.deadline);
        bindGroupToggle(holder.itemView, groupKey);
    }

    private void bindTopicContent(TopicContentHolder holder, FeedUiModels.TopicOptionItem item, PositionMapping mapping) {
        FeedUiModels.TopicOption option = item.option;
        if (option == null) {
            throw new IllegalStateException("Loaded topic option item must contain option data");
        }
        holder.optionText.setText(option.optionText);
        holder.voteCount.setText(option.voteCount + " 票");
        holder.itemView.setOnClickListener(v -> showMappingToast(holder, mapping));
    }

    private void bindTopicFooter(TopicFooterHolder holder, FeedUiModels.TopicFooter footer, PositionMapping mapping, int position) {
        GroupKey groupKey = mapping.groupKey();
        boolean expanded = isExpandedAt(groupKey, position);
        holder.summary.setText("已参与 " + footer.participantCount + " 人");
        holder.updatedAt.setText(footer.lastUpdated);
        holder.toggle.setText(expanded ? "收起投票选项" : "展开投票选项");
        bindGroupToggle(holder.toggle, groupKey);
    }

    private String firstChar(String text) {
        if (text == null || text.isEmpty()) {
            return "U";
        }
        return text.substring(0, 1).toUpperCase();
    }

    private boolean isExpandedAt(GroupKey groupKey, int position) {
        return groupKey != null && groupChromeQueries.isGroupExpandedAt(position);
    }

    private void bindGroupToggle(View target, GroupKey groupKey) {
        target.setOnClickListener(v -> {
            if (groupKey != null) {
                groupToggleRequestedListener.onToggleRequested(groupKey);
            }
        });
    }

    private void showMappingToast(RecyclerView.ViewHolder holder, PositionMapping mapping) {
        String group = mapping.groupKey() == null ? "-1" : mapping.groupKey().value();
        int child = mapping.kind() == PositionKind.GROUP_ITEM ? mapping.childIndex() : -1;
        String text = "flat=" + mapping.flatPosition() + ", group=" + group + ", child=" + child;
        Toast.makeText(holder.itemView.getContext(), text, Toast.LENGTH_SHORT).show();
    }

    // ---- holders ----

    static final class FilterStandaloneHolder extends RecyclerView.ViewHolder {
        final TextView allGroups;
        final TextView commentOnly;
        final TextView topicOnly;

        FilterStandaloneHolder(@NonNull View itemView) {
            super(itemView);
            allGroups = itemView.findViewById(R.id.filter_all_groups);
            commentOnly = itemView.findViewById(R.id.filter_comment_groups);
            topicOnly = itemView.findViewById(R.id.filter_topic_groups);
        }
    }

    static final class StandaloneHolder extends RecyclerView.ViewHolder {
        final TextView tag;
        final TextView title;
        final TextView body;

        StandaloneHolder(@NonNull View itemView) {
            super(itemView);
            tag = itemView.findViewById(R.id.standalone_tag);
            title = itemView.findViewById(R.id.standalone_title);
            body = itemView.findViewById(R.id.standalone_body);
        }
    }

    static final class GroupHeaderHolder extends RecyclerView.ViewHolder {
        final TextView state;
        final TextView meta;
        final TextView title;

        GroupHeaderHolder(@NonNull View itemView) {
            super(itemView);
            state = itemView.findViewById(R.id.group_state);
            meta = itemView.findViewById(R.id.group_meta);
            title = itemView.findViewById(R.id.group_title);
        }
    }

    static final class GroupContentHolder extends RecyclerView.ViewHolder {
        final TextView avatar;
        final TextView author;
        final TextView content;

        GroupContentHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.comment_avatar);
            author = itemView.findViewById(R.id.comment_author);
            content = itemView.findViewById(R.id.comment_content);
        }
    }

    static final class GroupFooterHolder extends RecyclerView.ViewHolder {
        final TextView stats;
        final TextView actions;

        GroupFooterHolder(@NonNull View itemView) {
            super(itemView);
            stats = itemView.findViewById(R.id.footer_stats);
            actions = itemView.findViewById(R.id.footer_actions);
        }
    }

    static final class GroupContentLoadingHolder extends RecyclerView.ViewHolder {
        GroupContentLoadingHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static final class TopicHeaderHolder extends RecyclerView.ViewHolder {
        final TextView state;
        final TextView title;
        final TextView meta;

        TopicHeaderHolder(@NonNull View itemView) {
            super(itemView);
            state = itemView.findViewById(R.id.topic_state);
            title = itemView.findViewById(R.id.topic_title);
            meta = itemView.findViewById(R.id.topic_meta);
        }
    }

    static final class TopicContentHolder extends RecyclerView.ViewHolder {
        final TextView optionText;
        final TextView voteCount;

        TopicContentHolder(@NonNull View itemView) {
            super(itemView);
            optionText = itemView.findViewById(R.id.topic_option_text);
            voteCount = itemView.findViewById(R.id.topic_option_votes);
        }
    }

    static final class TopicContentLoadingHolder extends RecyclerView.ViewHolder {
        TopicContentLoadingHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static final class TopicFooterHolder extends RecyclerView.ViewHolder {
        final TextView summary;
        final TextView updatedAt;
        final TextView toggle;

        TopicFooterHolder(@NonNull View itemView) {
            super(itemView);
            summary = itemView.findViewById(R.id.topic_footer_summary);
            updatedAt = itemView.findViewById(R.id.topic_footer_updated_at);
            toggle = itemView.findViewById(R.id.topic_footer_toggle);
        }
    }
}
