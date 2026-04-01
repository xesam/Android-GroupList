package io.github.xesam.android.grouplist.vnext.engine;

import java.util.List;

import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * 告诉 Engine 如何理解一个业务分组对象，6 个方法的接入教程见根 README §4.2。
 */
public interface GroupSchema<G, H, C, F> {

    /**
     * 分组唯一标识。契约要求：应为稳定的业务域身份
     * （如内容 ID、类目名等跨命令存活且基数随内容可控的标识），
     * 不应含时间戳、会话自增、内容哈希等每次刷新都会长出新值的成分。
     *
     * 原因：Controller 的展开状态 map 以 key 为键，跨 ReconcileRows 命令
     * 保留出现过的全部 key（消失的 key 保留共重现时带出），仅 submit 时全清；
     * key 的基数即该 map 在 controller 生命周期内的大小上界。
     */
    GroupKey keyOf(G group);

    H headerOf(G group);

    List<C> childrenOf(G group);

    F footerOf(G group);

    boolean initiallyExpanded(G group);

    boolean showFooterWhenCollapsed(G group);
}
