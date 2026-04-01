package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Public contract of the REPLACE/PROBE/DRAIN consumption protocol:
 * <ul>
 *   <li>{@code snapshot()} is a pure, non-draining read;</li>
 *   <li>{@code hasPendingChanges()} is a non-draining probe;</li>
 *   <li>{@code takeChangeSet()} is the single draining consumption point;</li>
 *   <li>fail-silent no-op dispatches never alter pending consumption state;</li>
 *   <li>an unknown {@link CoreCommand} type fails fast with IllegalArgumentException.</li>
 * </ul>
 */
public class ChangeSetConsumptionContractTest {

    private static CoreRow.Group<String, String, String> group(String key, String... children) {
        return CoreRow.Group.<String, String, String>builder(GroupKey.of(key))
        .header("header-" + key)
        .footer("footer-" + key)
        .children(Arrays.asList(children))
        .initiallyExpanded(false)
        .showFooterWhenCollapsed(false)
        .headerViewType(1)
        .footerViewType(2)
        .childViewTypeResolver((i, d) -> 3)
        .build();
    }

    @Test
    public void submit_shouldMarkReloadPendingUntilTaken() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Collections.singletonList(group("g:1", "a")));

        assertTrue(controller.hasPendingChanges());
        ChangeSet taken = controller.takeChangeSet();
        assertEquals(ChangeSet.Channel.RELOAD, taken.channel());
        assertTrue(taken.operations().isEmpty());

        // draining is idempotent
        assertFalse(controller.hasPendingChanges());
        assertTrue(controller.takeChangeSet().isEmpty());
    }

    /**
     * Crash-机制公证：增量 publish 的 pending 若不被 drain，下一帧的 changeSet 会复合
     * 上一帧已交付过的 ops（incremental-after-incremental compounds）。消费端把这帧再
     * 应用一次即重复插入 —— RecyclerView 以 "Inconsistency detected. Invalid view holder
     * adapter position" 崩溃（demo 曾因漏 drain 而复现）。本测试记录契约行为：snapshot
     * 帧自带复合 ops 是合法设计（Redis 类重放消费），但 LINEAGE 消费端（RecyclerView
     * adapter）必须一帧一 drain 才能拿到与显示版本对应的精确增量。
     */
    @Test
    public void undrainedIncremental_compoundsIntoNextFrameAndDoublesOnSecondFrame() {
        DefaultCoreController controller = new DefaultCoreController();
        GroupKey g0 = GroupKey.of("g0");
        controller.submit(Arrays.asList(group("g0", "a", "b")));
        controller.takeChangeSet(); // 合法消费：submit 帧交付后 drain

        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, true));
        ListSnapshot frame1 = controller.snapshot();
        // 本帧只含本体增量：一条 INSERT(1,3)。逐字段记下它，下一帧的首条 op 要与之一致
        // —— 这就是「已交付过的变化被再次携带」的可验证形态（比对字段而非实例，避免
        // 把 "andThen 复用同一批 Operation 实例" 这个实现细节写进契约）。
        assertEquals(1, frame1.changeSet().operations().size());
        assertEquals(ChangeSet.Type.INSERT, frame1.changeSet().operations().get(0).type());
        assertEquals(1, frame1.changeSet().operations().get(0).positionStart());
        assertEquals(3, frame1.changeSet().operations().get(0).itemCount());

        // 违规：frame1 交付给 RecyclerView 后忘 drain → 下一帧复合了已应用的 INSERT
        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, false));
        ListSnapshot frame2 = controller.snapshot();
        assertEquals(2, frame2.changeSet().operations().size());
        // 复合 payload：本体 REMOVE 前还背着 frame1 未 drain 的 INSERT(1,3)
        assertEquals(ChangeSet.Type.INSERT, frame2.changeSet().operations().get(0).type());
        assertEquals(1, frame2.changeSet().operations().get(0).positionStart());
        assertEquals(3, frame2.changeSet().operations().get(0).itemCount());
        assertEquals(ChangeSet.Type.REMOVE, frame2.changeSet().operations().get(1).type());
        assertEquals(1, frame2.changeSet().operations().get(1).positionStart());
        assertEquals(3, frame2.changeSet().operations().get(1).itemCount());
        // 上面三条已证明 frame2 的首条 op 与 frame1 那条逐字段一致：同一批变化被第二次
        // 投递，lineage 消费端把复合结果整体再应用一次，就是同一内容的重复插入 ——
        // "doubles on second frame" 的可验证形态，也是 MUST drain 的全部理由。
        assertEquals(frame1.version(), frame2.baseVersion());    // 但 baseVersion 依然咬合显示版本 → adapter 不会降级 RELOAD

        // 合规路径对比：drain 后每帧只携带本体增量
        controller.takeChangeSet();
        controller.dispatch(new CoreCommand.SetGroupExpanded(g0, true));
        ListSnapshot frame3 = controller.snapshot();
        assertEquals(1, frame3.changeSet().operations().size());
    }

    @Test
    public void snapshot_shouldBePureNonDrainingRead() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Collections.singletonList(group("g:1", "a")));

        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g:1")));

        // multiple reads observe the same pending operations; nothing drains
        assertTrue(controller.hasPendingChanges());
        assertSame(controller.snapshot(), controller.snapshot());
        assertEquals(1, controller.snapshot().changeSet().operations().size());
        assertTrue(controller.hasPendingChanges());

        // a single drain clears pending for both probe and snapshot changeSet
        ChangeSet taken = controller.takeChangeSet();
        assertEquals(ChangeSet.Channel.INCREMENTAL, taken.channel());
        assertEquals(1, taken.operations().size());
        assertFalse(controller.hasPendingChanges());
        assertTrue(controller.snapshot().changeSet().isEmpty());

        // rows stay identical through the drain (pure consumption, no data change)
        ListSnapshot after = controller.snapshot();
        assertEquals(3, after.itemCount());
    }

    @Test
    public void failSilentNoOp_afterRealDelta_shouldKeepPendingAvailable() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Collections.singletonList(group("g:1", "a")));
        controller.takeChangeSet();

        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g:1")));
        assertTrue(controller.hasPendingChanges());

        // fail-silent no-op: does not consume/erase the still-unapplied delta
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("missing")));
        assertTrue(controller.hasPendingChanges());
        ChangeSet taken = controller.takeChangeSet();
        assertEquals(1, taken.operations().size());
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void dispatch_withUnknownCommandType_shouldThrow() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Collections.singletonList(group("g:1", "a")));

        CoreCommand unknown = new CoreCommand() {
        };
        try {
            controller.dispatch(unknown);
            fail("Expected unknown command type to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown CoreCommand"));
        }
    }

    @Test
    public void updateStandaloneFlat_shouldEmitSingleUpdateAndKeepEveryOtherRowReference() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", "a"),
                CoreRow.Standalone.of("s1", 12)
        ));
        controller.takeChangeSet();
        List<ListSnapshot.Row<?>> before = controller.snapshot().rows();

        // visible layout (group collapsed): [s0, header, s1] -> s1 at flatPosition 2
        controller.dispatch(new CoreCommand.UpdateStandaloneFlat(2, "s1-new"));

        ListSnapshot snapshot = controller.snapshot();
        assertEquals("s1-new", snapshot.rows().get(2).data());
        // single UPDATE in final coordinates, structure untouched
        ChangeSet taken = controller.takeChangeSet();
        assertEquals(1, taken.operations().size());
        assertEquals(ChangeSet.Type.UPDATE, taken.operations().get(0).type());
        assertEquals(2, taken.operations().get(0).positionStart());
        assertEquals(1, taken.operations().get(0).itemCount());
        // every other row keeps its identity (reference reuse)
        List<ListSnapshot.Row<?>> after = snapshot.rows();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < after.size(); i++) {
            if (i == 2) {
                continue;
            }
            assertSame(before.get(i), after.get(i));
        }
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void updateStandaloneFlat_withEqualData_shouldBeANoOp() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11)
        ));
        controller.takeChangeSet();
        ListSnapshot before = controller.snapshot();

        // equal-payload update: neither a new snapshot wrapper nor a pending op
        controller.dispatch(new CoreCommand.UpdateStandaloneFlat(0, "s0"));

        assertSame(before, controller.snapshot());
        assertTrue(controller.takeChangeSet().isEmpty());
        assertSame(before.rows().get(0), controller.snapshot().rows().get(0));
    }

    @Test
    public void updateStandaloneFlat_withOutOfRangeOrNonStandalonePosition_shouldThrow() {
        DefaultCoreController controller = new DefaultCoreController();
        controller.submit(Arrays.asList(
                CoreRow.Standalone.of("s0", 11),
                group("g:1", "a")
        ));
        controller.takeChangeSet(); // drain the reload

        // out of range
        try {
            controller.dispatch(new CoreCommand.UpdateStandaloneFlat(99, "x"));
            fail("Expected out-of-range position to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("out of range"));
        }
        // header is not a standalone
        try {
            controller.dispatch(new CoreCommand.UpdateStandaloneFlat(1, "x"));
            fail("Expected non-standalone position to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("does not point at a standalone row"));
        }
        // fail-fast leaves no pending state change
        assertTrue(controller.takeChangeSet().isEmpty());
        assertEquals("s0", controller.snapshot().rows().get(0).data());
    }
}
