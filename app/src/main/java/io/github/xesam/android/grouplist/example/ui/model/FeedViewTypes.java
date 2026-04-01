package io.github.xesam.android.grouplist.example.ui.model;

public final class FeedViewTypes {

    private FeedViewTypes() {
    }

    public static final int TYPE_STANDALONE = 1;
    public static final int TYPE_GROUP_HEADER = 2;
    public static final int TYPE_GROUP_ITEM = 3;
    public static final int TYPE_GROUP_FOOTER = 4;

    public static final int TYPE_FILTER_STANDALONE = 2001;
    public static final int TYPE_TOPIC_GROUP_HEADER = 1001;
    public static final int TYPE_TOPIC_GROUP_ITEM = 1002;
    public static final int TYPE_TOPIC_GROUP_FOOTER = 1003;
    public static final int TYPE_FEED_GROUP_ITEM_LOADING = 1004;
    public static final int TYPE_TOPIC_GROUP_ITEM_LOADING = 1005;
}
