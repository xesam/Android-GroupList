package io.github.xesam.android.grouplist.vnext.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Positioned change operations for a flat row list.
 *
 * <h3>坐标系统约定（公开契约）</h3>
 * 消费者按 operations 的给定顺序逐条应用，每条 operation 的 positionStart
 * 都以“<b>之前各条已应用后的列表状态</b>”为坐标系（与 Android DiffUtil /
 * RecyclerView 顺序通知惯例一致；RecyclerView 侧
 * {@code SnapshotAdapterDelegate#applyChangeSet} 的 for 循环即此语义，
 * {@code GroupListRecyclerAdapter} 门面经 delegate 共享同一实现）。
 * 不存在“旧坐标系/新坐标系”的双坐标概念。
 *
 * <p>库内生产者（DefaultCoreController 各通道）的发射顺序保证：同一批
 * reconcile 差分中，先按位置降序发 REMOVE，再按升序发 INSERT；同点
 * REMOVE 先于 INSERT。在该顺序下每条坐标自洽，消费者无需任何修正。
 *
 * <h3>通道语义（公开契约）</h3>
 * 每个 ChangeSet 携带产生它的通道标记 {@link Channel}，语义不可互混：
 * <ul>
 *   <li>{@link Channel#RELOAD}：整表重载（{@code submit}），消费者应整体重建
 *     （adapter 对应 notifyDataSetChanged），operations 恒为空；</li>
 *   <li>{@link Channel#INCREMENTAL}：精确增量。operations 为空表示"本次无任何
 *     可见变化"——消费者<b>不得</b>发起任何重绘（与 RELOAD 相反的硬性行为契约）。</li>
 * </ul>
 *
 * <h3>稳定性承诺</h3>
 * 接口级的顺序应用等价性由 perf-guard 风格的不变量测试公证：对旧 rows
 * 顺次模拟应用全部 operations，终态结构必须与对应新 snapshot 一致。新增
 * 生产者通道时必须维护该不变量，禁止“发布时坐标自嗨、清空了事”的 
 * silent 假等价。
 */
public final class ChangeSet {

    /** Producing channel: distinguishes a full reload from a precise (possibly empty) delta. */
    public enum Channel {
        RELOAD,
        INCREMENTAL
    }

    public enum Type {
        INSERT,
        REMOVE,
        /**
         * Reserved: no library producer currently emits MOVE (a reordered block shows
         * up as REMOVE + INSERT). Consumers must still map it — the RecyclerView
         * adapter's type-dispatch map covers every enum value and fails loudly at
         * class-init if one is unmapped.
         */
        MOVE,
        /**
         * In-place content change: rows at [positionStart, positionStart + itemCount) keep their
         * positions but their data changed. Consumers should rebind in place, not recreate holders.
         */
        UPDATE
    }

    public static final class Operation {
        private final Type type;
        private final int positionStart;
        private final int itemCount;
        private final int toPosition;

        public static Operation of(Type type, int positionStart, int itemCount, int toPosition) {
            return new Operation(type, positionStart, itemCount, toPosition);
        }

        private Operation(Type type, int positionStart, int itemCount, int toPosition) {
            this.type = type;
            this.positionStart = positionStart;
            this.itemCount = itemCount;
            this.toPosition = toPosition;
        }

        public Type type() {
            return type;
        }

        public int positionStart() {
            return positionStart;
        }

        public int itemCount() {
            return itemCount;
        }

        public int toPosition() {
            return toPosition;
        }
    }

    private final List<Operation> operations;
    private final Channel channel;

    /** Full reload: no positional operations; consumers rebuild the whole list. */
    public static ChangeSet reload() {
        return new ChangeSet(Channel.RELOAD, null);
    }

    /** Precise incremental delta in sequential-application coordinates. */
    public static ChangeSet of(List<Operation> operations) {
        return new ChangeSet(Channel.INCREMENTAL, operations);
    }

    private ChangeSet(Channel channel, List<Operation> operations) {
        this.channel = channel;
        this.operations = operations == null ? new ArrayList<>() : new ArrayList<>(operations);
    }

    public Channel channel() {
        return channel;
    }

    /** @return true 表示本批增量不产生任何可见变化（RELOAD 通道不属于此范畴）。 */
    public boolean isEmpty() {
        return channel == Channel.INCREMENTAL && operations.isEmpty();
    }

    public static ChangeSet empty() {
        return new ChangeSet(Channel.INCREMENTAL, Collections.emptyList());
    }

    /**
     * Sequential composition: the result transforms the base of {@code this} into the
     * target of {@code next}. Under the sequential-application coordinate convention this
     * is plain concatenation; any RELOAD on either side absorbs the whole composition.
     */
    public ChangeSet andThen(ChangeSet next) {
        if (next == null) {
            return this;
        }
        if (channel == Channel.RELOAD || next.channel == Channel.RELOAD) {
            return reload();
        }
        if (next.operations.isEmpty()) {
            return this;
        }
        if (operations.isEmpty()) {
            return next;
        }
        List<Operation> combined = new ArrayList<>(operations.size() + next.operations.size());
        combined.addAll(operations);
        combined.addAll(next.operations);
        return new ChangeSet(Channel.INCREMENTAL, combined);
    }

    public List<Operation> operations() {
        return Collections.unmodifiableList(operations);
    }
}
