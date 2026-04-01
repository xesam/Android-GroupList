package io.github.xesam.android.grouplist.example.ui.delegate;

import android.util.Log;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 组合路径 Demo 的“公司存量基类”示意：自带统一埋点能力。
 *
 * 它已经占用了唯一的 extends 名额——这正是 SnapshotAdapterDelegate 存在的原因：
 * 存量基类的子类无法再继承 GroupListRecyclerAdapter，只能通过持有 delegate 接入。
 */
public abstract class DemoBaseAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final String analyticsTag;

    protected DemoBaseAdapter(String analyticsTag) {
        this.analyticsTag = analyticsTag;
    }

    /** 公司基类自带的埋点通道：任何子类都能在绑定时上报曝光。 */
    protected void recordImpression(int position, String what) {
        Log.d("GroupListDemo", "[" + analyticsTag + "] impression@" + position + " " + what);
    }
}
