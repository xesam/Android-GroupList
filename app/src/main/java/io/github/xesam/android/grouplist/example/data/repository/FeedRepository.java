package io.github.xesam.android.grouplist.example.data.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.xesam.android.grouplist.example.data.model.FeedBackendModels;

public final class FeedRepository {

    public List<FeedBackendModels.BackendRow> loadInitialRows() {
        List<FeedBackendModels.BackendRow> rows = new ArrayList<>();
        rows.add(new FeedBackendModels.StandaloneCard(
                "公告",
                "置顶公告",
                "社区今晚 23:00 - 23:30 维护，发帖和评论将短暂不可用。"
        ));
        rows.add(new FeedBackendModels.StandaloneCard(
                "发现",
                "今日热榜",
                "1. RecyclerView 局部刷新实践\n2. Compose 列表性能排查\n3. Room 分页缓存方案"
        ));
        rows.add(new FeedBackendModels.FilterEntry());

        // 列表中的 null 槽位表示"数据尚在途"，仅在 UI 层映射为骨架 item
        rows.add(new FeedBackendModels.CommentGroup(
                "comment:1",
                "@AlexDev · 2 小时前",
                "如何设计高性能分组列表，避免全量 notifyDataSetChanged？",
                "42 赞  ·  18 评论  ·  7 收藏",
                Arrays.<FeedBackendModels.CommentItem>asList(
                        FeedBackendModels.CommentItem.loaded("@Mina", "可以先做扁平索引缓存，再做增量更新。"),
                        null,
                        FeedBackendModels.CommentItem.loaded("@Qing", "我用 ChangeSet + payload，滑动稳定很多。")
                ),
                false,
                false
        ));

        rows.add(new FeedBackendModels.TopicGroup(
                "topic:1",
                "下个版本优先做什么？",
                "社区运营组",
                "投票截止：今晚 22:00",
                386,
                "3 分钟前更新",
                Arrays.<FeedBackendModels.TopicOptionItem>asList(
                        FeedBackendModels.TopicOptionItem.loaded("完善 GroupList 动画与 payload", 174),
                        null,
                        FeedBackendModels.TopicOptionItem.loaded("补充复杂分组嵌套案例", 83)
                ),
                false,
                true
        ));

        rows.add(new FeedBackendModels.CommentGroup(
                "comment:2",
                "@JudyUI · 43 分钟前",
                "你们在信息流里怎么做多样式 item 的可维护性？",
                "31 赞  ·  11 评论  ·  4 转发",
                Arrays.<FeedBackendModels.CommentItem>asList(
                        null,
                        FeedBackendModels.CommentItem.loaded("@Han", "先定义数据协议，再反推样式层会更稳。")
                ),
                false,
                true
        ));

        rows.add(new FeedBackendModels.StandaloneCard(
                "发现",
                "你关注的话题更新",
                "#Android性能 过去 1 小时新增 12 条讨论。"
        ));

        rows.add(new FeedBackendModels.CommentGroup(
                "comment:3",
                "@BackendFan · 15 分钟前",
                "评论区排序：按热度还是按时间？",
                "19 赞  ·  23 评论  ·  2 收藏",
                Arrays.<FeedBackendModels.CommentItem>asList(
                        FeedBackendModels.CommentItem.loaded("@Stone", "默认按热度，但提供时间排序切换。"),
                        FeedBackendModels.CommentItem.loaded("@Amy", "新帖建议先按时间，冷启动更公平。"),
                        null,
                        null
                ),
                false,
                false
        ));
        return rows;
    }
}
