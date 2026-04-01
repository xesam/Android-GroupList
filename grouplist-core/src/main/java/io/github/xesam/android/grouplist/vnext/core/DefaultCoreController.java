package io.github.xesam.android.grouplist.vnext.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping;

public final class DefaultCoreController implements CoreController {

    private final List<CoreRow> sourceRows = new ArrayList<>();
    private final Map<GroupKey, CoreRow.Group<?, ?, ?>> groupRowByKey = new LinkedHashMap<>();
    private final Map<GroupKey, Boolean> expandedByKey = new LinkedHashMap<>();

    /**
     * Fenwick (binary indexed) tree over the visible size of each source row, 1-based.
     * Stores no per-row state; every query/update is O(log R) instead of the previous
     * linear scan over all groups. Rebuilt by every structural channel
     * (submit / ReplaceRows / ReconcileRows) via {@link #rebuildVisibleSizeIndex()}.
     */
    private int[] fenwickVisibleSize = new int[1];
    /**
     * GroupKey -&gt; index in sourceRows. Rebuilt together with the Fenwick tree by
     * {@link #rebuildVisibleSizeIndex()}, so an entry stays valid until the next
     * structural channel — submit, ReplaceRows and ReconcileRows all replace sourceRows
     * wholesale (clear + refill) rather than reordering it in place.
     */
    private final Map<GroupKey, Integer> sourceIndexByKey = new LinkedHashMap<>();

    private ListSnapshot latestSnapshot = ListSnapshot.of(new ArrayList<ListSnapshot.Row<?>>(), ChangeSet.empty());
    /** Exclusive reference to the raw backing list of {@link #latestSnapshot}; never mutated after handoff. */
    private List<ListSnapshot.Row<?>> latestRawRows = new ArrayList<>();
    /** Pending change accumulator: survives across publishSnapshot calls until drained by takeChangeSet. */
    private ChangeSet pendingChangeSet = ChangeSet.empty();
    /** Version counter for snapshots, incremented on every publish. */
    private long versionCounter = 0;

    /**
     * Publishes a freshly built, exclusively-owned raw list via the trusted no-copy path.
     * The {@code deltaFromCurrent} transforms the current snapshot into the new one;
     * accumulation rules: new RELOAD replaces everything; incremental after RELOAD starts
     * fresh; incremental after incremental compounds.
     */
    private void publishSnapshot(List<ListSnapshot.Row<?>> rows, ChangeSet deltaFromCurrent) {
        long baseVersion = latestSnapshot.version();
        long newVersion = ++versionCounter;
        // RELOAD always replaces prior pending; incremental after RELOAD starts fresh;
        // incremental after incremental accumulates.
        if (deltaFromCurrent.channel() == ChangeSet.Channel.RELOAD) {
            pendingChangeSet = deltaFromCurrent;
        } else if (pendingChangeSet.channel() == ChangeSet.Channel.RELOAD) {
            pendingChangeSet = deltaFromCurrent; // incremental after RELOAD: start fresh
        } else {
            pendingChangeSet = pendingChangeSet.andThen(deltaFromCurrent);
        }
        latestRawRows = rows;
        latestSnapshot = ListSnapshot.ofTrusted(rows, pendingChangeSet, newVersion, baseVersion);
    }

    @Override
    public void submit(List<? extends CoreRow> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        requireValidRows(rows);
        sourceRows.clear();
        sourceRows.addAll(rows);

        groupRowByKey.clear();
        expandedByKey.clear();
        sourceIndexByKey.clear();
        for (CoreRow row : sourceRows) {
            if (!(row instanceof CoreRow.Group<?, ?, ?>)) {
                continue;
            }
            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            // duplicates were rejected by the pre-mutation validation pass above
            groupRowByKey.put(groupRow.groupKey(), groupRow);
            expandedByKey.put(groupRow.groupKey(), groupRow.initiallyExpanded());
        }
        rebuildVisibleSizeIndex();

        // submit is a full replacement: pending becomes RELOAD. Do NOT accumulate
        // via publishSnapshot/andThen, because that would absorb subsequent incrementals.
        long newVersion = ++versionCounter;
        pendingChangeSet = ChangeSet.reload();
        latestRawRows = buildRawRows();
        latestSnapshot = ListSnapshot.ofTrusted(latestRawRows, pendingChangeSet, newVersion, newVersion);
    }

    private void rebuildVisibleSizeIndex() {
        // Self-contained: clear on entry. The Fenwick/array and the key->index map
        // are always rebuilt from the CURRENT sourceRows; every channel (submit,
        // ReconcileRows, ReplaceRows) gets a consistent index without depending on
        // caller-side clearing discipline — a stale entry here would surface as a
        // wrong source index and crash rebuildSnapshotAroundGroup's group cast.
        sourceIndexByKey.clear();
        fenwickVisibleSize = new int[sourceRows.size() + 1];
        for (int index = 0; index < sourceRows.size(); index++) {
            GroupKey key = keyOfIndex(index);
            if (key != null) {
                sourceIndexByKey.putIfAbsent(key, index);
            }
            fenwickAdd(index + 1, visibleSizeOfSourceIndex(index));
        }
    }

    private GroupKey keyOfIndex(int index) {
        CoreRow row = sourceRows.get(index);
        return row instanceof CoreRow.Group<?, ?, ?>
                ? ((CoreRow.Group<?, ?, ?>) row).groupKey()
                : null;
    }

    private int visibleSizeOfSourceIndex(int index) {
        CoreRow row = sourceRows.get(index);
        if (row instanceof CoreRow.Group<?, ?, ?>) {
            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            return calculateGroupVisibleSize(
                    groupRow,
                    Boolean.TRUE.equals(expandedByKey.get(groupRow.groupKey()))
            );
        }
        return 1;
    }

    private void fenwickAdd(int oneBasedIndex, int delta) {
        for (int i = oneBasedIndex; i < fenwickVisibleSize.length; i += i & (-i)) {
            fenwickVisibleSize[i] += delta;
        }
    }

    /** Flat position of the first visible row of the source row at {@code index}. */
    private int prefixVisibleSize(int sourceIndex) {
        int sum = 0;
        for (int i = sourceIndex; i > 0; i -= i & (-i)) {
            sum += fenwickVisibleSize[i];
        }
        return sum;
    }

    @Override
    public void dispatch(CoreCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command instanceof CoreCommand.ToggleGroup) {
            CoreCommand.ToggleGroup toggleGroup = (CoreCommand.ToggleGroup) command;
            if (!groupRowByKey.containsKey(toggleGroup.groupKey())) {
                // fail-silent by contract (unknown key): no state change, pending
                // consumption state untouched under the takeChangeSet draining model.
                return;
            }

            // Toggle is syntactic sugar over the set-semantics branch: the factual state
            // lives only here, so the target is derived from it instead of from a second copy.
            boolean wasExpanded = Boolean.TRUE.equals(expandedByKey.get(toggleGroup.groupKey()));
            applyExpanded(toggleGroup.groupKey(), !wasExpanded);
            return;
        }

        if (command instanceof CoreCommand.SetGroupExpanded) {
            CoreCommand.SetGroupExpanded setExpanded = (CoreCommand.SetGroupExpanded) command;
            CoreRow.Group<?, ?, ?> groupRow = groupRowByKey.get(setExpanded.groupKey());
            if (groupRow == null) {
                return; // fail-silent by contract (unknown key)
            }

            boolean wasExpanded = Boolean.TRUE.equals(expandedByKey.get(setExpanded.groupKey()));
            if (wasExpanded == setExpanded.expanded()) {
                return; // no state change
            }

            applyExpanded(setExpanded.groupKey(), setExpanded.expanded());
            return;
        }

        if (command instanceof CoreCommand.ReplaceChildren) {
            CoreCommand.ReplaceChildren replaceChildren = (CoreCommand.ReplaceChildren) command;
            handleReplaceChildren(replaceChildren.groupKey(), replaceChildren.children());
            return;
        }

        if (command instanceof CoreCommand.UpdateStandaloneFlat) {
            CoreCommand.UpdateStandaloneFlat updateStandalone = (CoreCommand.UpdateStandaloneFlat) command;
            handleUpdateStandaloneFlat(updateStandalone.flatPosition(), updateStandalone.newData());
            return;
        }

        if (command instanceof CoreCommand.ReconcileRows) {
            CoreCommand.ReconcileRows reconcileRows = (CoreCommand.ReconcileRows) command;
            handleReconcileRows(reconcileRows.rows());
            return;
        }

        if (command instanceof CoreCommand.ReplaceRows) {
            CoreCommand.ReplaceRows replaceRows = (CoreCommand.ReplaceRows) command;
            handleReplaceRows(replaceRows.rows(), replaceRows.identity());
            return;
        }

        throw new IllegalArgumentException(
                "Unknown CoreCommand type: " + command.getClass().getName()
        );
    }

    /**
     * 变更前校验门：对将要整体进入 controller 的行做全部结构性校验——null 元素
     * 与重复 GroupKey。所有入口（submit / ReconcileRows / ReplaceRows）必须在任何
     * 状态变更之前调用，保证抛出时 controller 保持调用前状态，不留半提交残留。
     * 失败策略矩阵见 {@code vnext} package-info.java。
     */
    private static void requireValidRows(List<? extends CoreRow> rows) {
        Set<GroupKey> pendingKeys = new HashSet<>();
        for (CoreRow row : rows) {
            if (row == null) {
                // fail-fast: a null element would otherwise surface much later
                // as a confusing ClassCastException inside buildRawRows' group cast.
                throw new IllegalArgumentException("rows must not contain null elements");
            }
            if (row instanceof CoreRow.Group<?, ?, ?>) {
                GroupKey groupKey = ((CoreRow.Group<?, ?, ?>) row).groupKey();
                if (!pendingKeys.add(groupKey)) {
                    throw new IllegalArgumentException("Duplicate groupKey found: " + groupKey.value());
                }
            }
        }
    }

    /**
     * Keyed full reconcile: diff the new rows against the current visible model,
     * preserving expansion state. Structural misuse (null rows / null elements /
     * duplicate GroupKeys) is rejected by the pre-mutation validation gate with
     * {@code IllegalArgumentException} — the never-throw policy covers only the
     * diff itself. Contract: see {@link CoreCommand.ReplaceRows}.
     */
    private void handleReplaceRows(List<? extends CoreRow> rows, ItemIdentity identity) {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        ItemIdentity effectiveIdentity = identity != null ? identity : ItemIdentity.DATA_EQUALS;

        // 变更前校验门：与 submit 同一结构性违约矩阵（null 元素 / 重复 GroupKey）。
        // 校验必须先于 expandedByKey 合并与任何状态替换，保证抛出时不留半提交状态。
        requireValidRows(rows);

        Map<GroupKey, CoreRow.Group<?, ?, ?>> newGroupsByKey = new LinkedHashMap<>();
        for (CoreRow row : rows) {
            if (!(row instanceof CoreRow.Group<?, ?, ?>)) {
                continue;
            }
            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            newGroupsByKey.put(groupRow.groupKey(), groupRow);
        }

        // Merge expansion state: new keys initialize from initiallyExpanded,
        // survivors and vanished keys keep their state.
        for (CoreRow.Group<?, ?, ?> groupRow : newGroupsByKey.values()) {
            if (!expandedByKey.containsKey(groupRow.groupKey())) {
                expandedByKey.put(groupRow.groupKey(), groupRow.initiallyExpanded());
            }
        }

        // Build new visible rows with merged expansion state
        List<ListSnapshot.Row<?>> oldVisibleRows = latestSnapshot.rows();
        sourceRows.clear();
        sourceRows.addAll(rows);
        groupRowByKey.clear();
        groupRowByKey.putAll(newGroupsByKey);
        rebuildVisibleSizeIndex();
        List<ListSnapshot.Row<?>> newVisibleRows = buildRawRows();

        // Diff and publish
        ChangeSet delta = RowDiff.diff(oldVisibleRows, newVisibleRows, effectiveIdentity);
        publishSnapshot(newVisibleRows, delta);
    }

    /**
     * Visibility-only reconcile channel; contract documented on
     * {@link CoreCommand.ReconcileRows}. Implementation notes:
     * <ul>
     *   <li>diff granularity is the whole top-level BLOCK (standalone row or group's
     *     visible rows including header/children/footer); kept blocks are trusted
   *       content-identical and emitted as zero ops;</li>
     *   <li>expansion map is merged, never cleared: surviving keys keep their state,
   *       vanished keys keep theirs for reappearance (bounded by business key count),
   *       brand-new keys initialize from {@code initiallyExpanded}.</li>
     *   <li>v1 deliberately rebuilds the raw rows in full (O(n) allocation) — visibility
     *     changes are rare user taps, not scroll-frequency events; precision lives in
     *     the ChangeSet, snapshot segment reuse is a later perf topic.</li>
     * </ul>
     */
    private void handleReconcileRows(List<? extends CoreRow> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        // 变更前校验门：必须在 expandedByKey 合并 + 任何状态变更之前调用，保证
        // 结构性违约在任何半提交发生前抛出，controller 不留中间状态。
        requireValidRows(rows);
        requireStandaloneCountAligned(rows);

        // Merge expansion state FIRST (new keys only; survivors and vanished keys untouched)
        // so insertion block sizes can be computed from merged state.
        for (CoreRow row : rows) {
            if (!(row instanceof CoreRow.Group<?, ?, ?>)) {
                continue;
            }
            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            if (!expandedByKey.containsKey(groupRow.groupKey())) {
                expandedByKey.put(groupRow.groupKey(), groupRow.initiallyExpanded());
            }
        }

        List<ChangeSet.Operation> operations = new ArrayList<>();

        // Phase 1: REMOVED blocks (present in old visible rows, absent from new) in
        // DESCENDING old position — later removals never invalidate earlier coordinates.
        List<ChangeSet.Operation> updateOps = standaloneUpdateOps(rows);
        List<ChangeSet.Operation> removals = new ArrayList<>();
        int oldCursor = 0;
        for (CoreRow row : sourceRows) {
            int blockSize = visibleSizeOfSourceRow(row);
            if (row instanceof CoreRow.Group<?, ?, ?>) {
                CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
                if (!containsGroupKey(rows, groupRow.groupKey())) {
                    removals.add(ChangeSet.Operation.of(ChangeSet.Type.REMOVE, oldCursor, blockSize, -1));
                }
            }
            oldCursor += blockSize;
        }
        for (int i = removals.size() - 1; i >= 0; i--) {
            operations.add(removals.get(i));
        }

        // Phase 2: INSERTED blocks in ascending order; positions are in the EVOLVING
        // list's coordinates (all removals applied, previous insertions applied).
        for (CoreRow row : rows) {
            if (!(row instanceof CoreRow.Group<?, ?, ?>)) {
                continue;
            }
            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            if (groupRowByKey.containsKey(groupRow.groupKey())) {
                continue; // kept: content-identical by contract, zero ops
            }
            operations.add(ChangeSet.Operation.of(
                    ChangeSet.Type.INSERT,
                    visiblePositionAfterRemovals(rows, groupRow),
                    visibleSizeOfSourceRow(row),
                    -1
            ));
        }

        operations.addAll(updateOps);

        // State swap: rows, group index; expansion map deliberately NOT cleared.
        // (The Fenwick index + sourceIndexByKey are rebuilt by rebuildVisibleSizeIndex.)
        sourceRows.clear();
        sourceRows.addAll(rows);
        groupRowByKey.clear();
        for (CoreRow row : sourceRows) {
            if (row instanceof CoreRow.Group<?, ?, ?>) {
                CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
                // duplicates were rejected by the pre-mutation requireValidRows gate
                groupRowByKey.put(groupRow.groupKey(), groupRow);
            }
        }
        rebuildVisibleSizeIndex();

        if (operations.isEmpty()) {
            // No visibility delta: the visible list is contract-identical, so nothing
            // is published. Under the draining consumption model the pending changeSet
            // stays as-is: rows did not change, so pending ops (if any) remain valid.
            return;
        }
        publishSnapshot(buildRawRows(), ChangeSet.of(operations));
    }

    private static boolean containsGroupKey(List<? extends CoreRow> rows, GroupKey groupKey) {
        for (CoreRow row : rows) {
            if (row instanceof CoreRow.Group<?, ?, ?>) {
                CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
                if (groupRow.groupKey().equals(groupKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int visibleSizeOfSourceRow(CoreRow row) {
        if (row instanceof CoreRow.Group<?, ?, ?>) {
            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            return calculateGroupVisibleSize(
                    groupRow,
                    Boolean.TRUE.equals(expandedByKey.get(groupRow.groupKey()))
            );
        }
        return 1;
    }

    /**
     * Flat position of {@code target} in the EVOLVING list where ALL Phase-1 removals
     * and every PRECEDING Phase-2 insertion are already applied — sequential
     * convention coordinates.
     *
     * <p>Phase 2 emits insertions in ascending {@code rows} order, so when the
     * consumer applies this INSERT every preceding inserted block IS already present
     * in its evolving list and MUST be counted (skipping them shifts every
     * subsequent INSERT of a multi-reveal reconcile left by the preceding inserted
     * block sizes, corrupting the consumer's list). New blocks
     * are sized via {@link #visibleSizeOfSourceRow} which reads the group's
     * initiallyExpanded state merged into {@code expandedByKey} before diffing.</p>
     */
    private int visiblePositionAfterRemovals(List<? extends CoreRow> rows, CoreRow.Group<?, ?, ?> target) {
        int evolvedPosition = 0;
        for (CoreRow row : rows) {
            if (row == target) {
                return evolvedPosition;
            }
            evolvedPosition += visibleSizeOfSourceRow(row);
        }
        return evolvedPosition;
    }

    /**
     * 变更前校验门（公开契约见 {@link CoreCommand.ReconcileRows}）：新旧 standalone
     * 行计数守恒，漂移即抛 IllegalArgumentException。
     *
     * <p>调用时序是本函数语义的一部分：只能在 expandedByKey 合并之前调用——
     * 校验失败时 controller 保持未变更，不留半提交状态。差分生产职责不在本函数，
     * 见 {@link #standaloneUpdateOps}。
     */
    private void requireStandaloneCountAligned(List<? extends CoreRow> rows) {
        int oldCount = 0;
        for (CoreRow row : sourceRows) {
            if (row instanceof CoreRow.Standalone<?>) {
                oldCount++;
            }
        }
        int newCount = 0;
        for (CoreRow row : rows) {
            if (row instanceof CoreRow.Standalone<?>) {
                newCount++;
            }
        }
        if (oldCount != newCount) {
            throw new IllegalArgumentException(
                    "ReconcileRows is a visibility-only channel: standalone row count must not "
                            + "drift; use submit for structural changes"
            );
        }
    }

    /**
     * UPDATE op 生产者：standalones 永不被隐藏，等数内容漂移（data 或 viewType 任一变化）
     * 以原地 UPDATE（终态坐标、升序）呈现。调用前置条件是 {@link #requireStandaloneCountAligned}
     * 已通过；坐标依赖合并后的展开态，须在 expandedByKey 合并之后调用。
     */
    private List<ChangeSet.Operation> standaloneUpdateOps(List<? extends CoreRow> rows) {
        List<CoreRow.Standalone<?>> oldStandalones = new ArrayList<>();
        for (CoreRow row : sourceRows) {
            if (row instanceof CoreRow.Standalone<?>) {
                oldStandalones.add((CoreRow.Standalone<?>) row);
            }
        }
        List<ChangeSet.Operation> updateOps = new ArrayList<>();
        int occurrence = 0;
        int finalPosition = 0;
        for (CoreRow row : rows) {
            if (row instanceof CoreRow.Standalone<?>) {
                CoreRow.Standalone<?> newStandalone = (CoreRow.Standalone<?>) row;
                CoreRow.Standalone<?> oldStandalone = oldStandalones.get(occurrence);
                if (!Objects.equals(newStandalone.data(), oldStandalone.data())
                        || newStandalone.viewType() != oldStandalone.viewType()) {
                    // viewType 也是内容语义的一部分：忽略它会让 binder 静默拿到旧映射。
                    updateOps.add(ChangeSet.Operation.of(ChangeSet.Type.UPDATE, finalPosition, 1, -1));
                }
                occurrence++;
            }
            finalPosition += visibleSizeOfSourceRow(row);
        }
        return updateOps;
    }

    /**
     * The single mutation point of the incremental expansion path: ToggleGroup and
     * SetGroupExpanded both converge here to compute old/new sizes, update the Fenwick
     * index, then rebuild the snapshot around the affected group. The whole-model
     * channels (submit / ReconcileRows / ReplaceRows) seed {@code expandedByKey}
     * themselves while merging the new row set and never route through this method.
     */
    private void applyExpanded(GroupKey groupKey, boolean expanded) {
        Integer sourceIndexBoxed = sourceIndexByKey.get(groupKey);
        if (sourceIndexBoxed == null) {
            // groupRowByKey and sourceIndexByKey are maintained together; this guard
            // turns a future bookkeeping inconsistency into a fail-silent no-op
            // (consistent with dispatch error style) instead of an NPE.
            return;
        }
        int sourceIndex = sourceIndexBoxed;
        int headerPosition = prefixVisibleSize(sourceIndex);
        int oldGroupSize = visibleSizeOfSourceIndex(sourceIndex);

        expandedByKey.put(groupKey, expanded);
        int newGroupSize = visibleSizeOfSourceIndex(sourceIndex);
        fenwickAdd(sourceIndex + 1, newGroupSize - oldGroupSize);

        publishSnapshot(
                rebuildSnapshotAroundGroup(sourceIndex, headerPosition, oldGroupSize, newGroupSize),
                toChangeSet(headerPosition, newGroupSize - oldGroupSize)
        );
    }

    /**
     * Precisely-scoped standalone data change: same flat structure, one Row's data
     * swapped. Visible sizes are unchanged, so the Fenwick index stays valid; the
     * snapshot is rebuilt by reference-reuse (prefix and suffix Rows keep identity,
     * only the target Row object is recreated) and a single final-coordinate UPDATE
     * is published.
     */
    private void handleUpdateStandaloneFlat(int flatPosition, Object newData) {
        List<ListSnapshot.Row<?>> oldRows = latestSnapshot.rows();
        if (flatPosition < 0 || flatPosition >= oldRows.size()) {
            throw new IllegalArgumentException(
                    "UpdateStandaloneFlat position out of range: " + flatPosition
                            + " (visible size " + oldRows.size() + ")");
        }
        ListSnapshot.Row<?> target = oldRows.get(flatPosition);
        if (target.mapping().kind() != PositionKind.STANDALONE) {
            throw new IllegalArgumentException(
                    "UpdateStandaloneFlat position " + flatPosition
                            + " does not point at a standalone row (kind=" + target.mapping().kind() + ")");
        }

        if (Objects.equals(newData, target.data())) {
            // no-op short-circuit (same discipline as SetGroupExpanded's no-change branch):
            // an equal-payload update publishes neither a snapshot nor a pending op.
            return;
        }

        int sourceIndex = sourceIndexOfStandalone(flatPosition);
        CoreRow.Standalone<?> oldStandalone = (CoreRow.Standalone<?>) sourceRows.get(sourceIndex);
        sourceRows.set(sourceIndex, CoreRow.Standalone.of(newData, oldStandalone.viewType()));

        List<ListSnapshot.Row<?>> newRows = new ArrayList<>(oldRows.size());
        for (int i = 0; i < oldRows.size(); i++) {
            newRows.add(i == flatPosition
                    ? ListSnapshot.Row.of(target.mapping(), newData, target.viewType())
                    : oldRows.get(i));
        }
        publishSnapshot(newRows, ChangeSet.of(Collections.singletonList(
                ChangeSet.Operation.of(ChangeSet.Type.UPDATE, flatPosition, 1, -1)
        )));
    }

    /** Locates {@code flatPosition} among the source rows using the Fenwick prefix sums. */
    private int sourceIndexOfStandalone(int flatPosition) {
        int sourceCount = sourceRows.size();
        // Fenwick binary-lifting descent: start at the largest power of two <= sourceCount.
        int probe = Integer.highestOneBit(Math.max(sourceCount, 1));
        int sourceIndex = 0;
        while (probe > 0) {
            int next = sourceIndex + probe;
            if (next <= sourceCount && prefixVisibleSize(next) <= flatPosition) {
                sourceIndex = next;
            }
            probe >>= 1;
        }
        return sourceIndex;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void handleReplaceChildren(GroupKey groupKey, List<?> newChildren) {
        CoreRow.Group<?, ?, ?> existing = groupRowByKey.get(groupKey);
        if (existing == null) {
            return; // fail-silent by contract (unknown key)
        }

        Integer sourceIndexBoxed = sourceIndexByKey.get(groupKey);
        if (sourceIndexBoxed == null) {
            // see applyExpanded: fail-silent guard against bookkeeping inconsistency
            return;
        }
        int sourceIndex = sourceIndexBoxed;
        int headerPosition = prefixVisibleSize(sourceIndex);
        boolean expanded = Boolean.TRUE.equals(expandedByKey.get(groupKey));
        int oldGroupSize = calculateGroupVisibleSize(existing, expanded);

        // Replace the group row in place, preserving order, key, header/footer and state.
        List<Object> childrenList = new ArrayList<Object>(newChildren);
        CoreRow.Group<Object, Object, Object> replacement = CoreRow.Group.builder(existing.groupKey())
        .header(existing.header())
        .footer(existing.footer())
        .children(childrenList)
        .initiallyExpanded(existing.initiallyExpanded())
        .showFooterWhenCollapsed(existing.showFooterWhenCollapsed())
        .headerViewType(existing.headerViewType())
        .footerViewType(existing.footerViewType())
        .childViewTypeResolver((CoreRow.Group.ItemViewTypeResolver) ((CoreRow.Group) existing)::childViewType)
        .build();
        sourceRows.set(sourceIndex, replacement);
        groupRowByKey.put(groupKey, replacement);

        int newGroupSize = calculateGroupVisibleSize(replacement, expanded);
        int delta = newGroupSize - oldGroupSize;
        fenwickAdd(sourceIndex + 1, delta);

        if (!expanded) {
            // Collapsed: no visible rows change, nothing published, but future expands
            // reveal the new children via the replaced source row. Pending consumption
            // state is untouched under the draining model.
            return;
        }

        publishSnapshot(
                rebuildSnapshotAroundGroup(sourceIndex, headerPosition, oldGroupSize, newGroupSize),
                toReplaceChangeSet(headerPosition + 1, existing.children(), newChildren)
        );
    }

    /**
     * Precise child-replace delta in sequential-application coordinates: the shared
     * prefix emits an UPDATE only where the child actually changed (an unchanged
     * position emits nothing — the consumer simply keeps the holder as-is), and a
     * count drift becomes one INSERT or REMOVE at the group's tail. Content equality
     * is {@code equals}-based, so children without a value {@code equals} degrade to
     * "everything changed" (the conservative behaviour, never a stale rebind).
     */
    private static ChangeSet toReplaceChangeSet(int childrenStartPosition, List<?> oldChildren, List<?> newChildren) {
        int oldCount = oldChildren.size();
        int newCount = newChildren.size();
        List<ChangeSet.Operation> operations = new ArrayList<>();
        int common = Math.min(oldCount, newCount);
        int runStart = -1;
        for (int i = 0; i <= common; i++) {
            boolean changed = i < common && !Objects.equals(oldChildren.get(i), newChildren.get(i));
            if (changed) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                operations.add(ChangeSet.Operation.of(
                        ChangeSet.Type.UPDATE, childrenStartPosition + runStart, i - runStart, -1));
                runStart = -1;
            }
        }
        if (oldCount > newCount) {
            operations.add(ChangeSet.Operation.of(
                    ChangeSet.Type.REMOVE, childrenStartPosition + common, oldCount - newCount, -1));
        } else if (newCount > oldCount) {
            operations.add(ChangeSet.Operation.of(
                    ChangeSet.Type.INSERT, childrenStartPosition + common, newCount - oldCount, -1));
        }
        return ChangeSet.of(operations);
    }

    @Override
    public ListSnapshot snapshot() {
        return latestSnapshot;
    }

    @Override
    public ChangeSet takeChangeSet() {
        ChangeSet taken = pendingChangeSet;
        pendingChangeSet = ChangeSet.empty();
        // Refresh snapshot: cleared changeSet, same version (idempotent read contract).
        latestSnapshot = ListSnapshot.ofTrusted(
                latestRawRows,
                pendingChangeSet,
                latestSnapshot.version(),
                latestSnapshot.baseVersion()
        );
        return taken;
    }

    @Override
    public boolean hasPendingChanges() {
        return !pendingChangeSet.isEmpty();
    }

    @Override
    public boolean isGroupExpanded(GroupKey groupKey) {
        return Boolean.TRUE.equals(expandedByKey.get(groupKey));
    }

    /** buildRawRows: full flat rebuild; returns the exclusively-owned raw list. */
    private List<ListSnapshot.Row<?>> buildRawRows() {
        List<ListSnapshot.Row<?>> rows = new ArrayList<>();
        int flatPosition = 0;

        for (CoreRow row : sourceRows) {
            if (row instanceof CoreRow.Standalone<?>) {
                CoreRow.Standalone<?> standaloneRow = (CoreRow.Standalone<?>) row;
                rows.add(ListSnapshot.Row.of(
                        new PositionMapping(flatPosition, PositionKind.STANDALONE, null, -1),
                        standaloneRow.data(),
                        standaloneRow.viewType()
                ));
                flatPosition++;
                continue;
            }

            CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) row;
            GroupKey groupKey = groupRow.groupKey();
            boolean expanded = Boolean.TRUE.equals(expandedByKey.get(groupKey));

            rows.add(ListSnapshot.Row.of(
                    new PositionMapping(flatPosition, PositionKind.GROUP_HEADER, groupKey, -1),
                    groupRow.header(),
                    groupRow.headerViewType()
            ));
            flatPosition++;

            if (expanded) {
                List<?> children = groupRow.children();
                for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                    Object childData = children.get(childIndex);
                    rows.add(ListSnapshot.Row.of(
                            new PositionMapping(flatPosition, PositionKind.GROUP_ITEM, groupKey, childIndex),
                            childData,
                            resolveChildViewType(groupRow, childIndex, childData)
                    ));
                    flatPosition++;
                }
                if (groupRow.footer() != null) {
                    rows.add(ListSnapshot.Row.of(
                            new PositionMapping(flatPosition, PositionKind.GROUP_FOOTER, groupKey, -1),
                            groupRow.footer(),
                            groupRow.footerViewType()
                    ));
                    flatPosition++;
                }
                continue;
            }

            if (groupRow.showFooterWhenCollapsed() && groupRow.footer() != null) {
                rows.add(ListSnapshot.Row.of(
                        new PositionMapping(flatPosition, PositionKind.GROUP_FOOTER, groupKey, -1),
                        groupRow.footer(),
                        groupRow.footerViewType()
                ));
                flatPosition++;
            }
        }

        return rows;
    }

    /**
     * Segment-scoped snapshot rebuild for a single structural change:
     * <ul>
     *   <li>rows before the affected group's header: REFERENCE REUSE (zero allocation);</li>
     *   <li>the affected group's header: reference reuse when available (position/key unchanged);</li>
     *   <li>the affected group's children: fresh rows (their data/visibility actually changed);</li>
     *   <li>rows after the affected group: new PositionMapping/Row with shifted flat positions,
     *       data and viewType carried over by reference. NOTE: shifting rows must get new mappings
     *       because {@link ListSnapshot.Row} owns its PositionMapping; this is the accepted boundary
     *       of the reuse optimization given the current snapshot API shape.</li>
     * </ul>
     */
    private List<ListSnapshot.Row<?>> rebuildSnapshotAroundGroup(
            int sourceIndex,
            int headerPosition,
            int oldGroupSize,
            int newGroupSize
    ) {
        CoreRow.Group<?, ?, ?> groupRow = (CoreRow.Group<?, ?, ?>) sourceRows.get(sourceIndex);
        GroupKey groupKey = groupRow.groupKey();
        boolean expanded = Boolean.TRUE.equals(expandedByKey.get(groupKey));

        List<ListSnapshot.Row<?>> oldRows = latestSnapshot.rows();
        int oldCount = oldRows.size();
        int shift = newGroupSize - oldGroupSize;

        List<ListSnapshot.Row<?>> rows = new ArrayList<>(Math.max(oldCount + shift, 0) + 1);
        // Prefix: untouched rows keep positions, reuse Row objects as-is.
        rows.addAll(oldRows.subList(0, headerPosition));

        // Header: position and key unchanged by any single-group structural command -> reuse.
        int flatPosition = headerPosition;
        if (headerPosition < oldCount) {
            ListSnapshot.Row<?> header = oldRows.get(headerPosition);
            PositionMapping mapping = header.mapping();
            if (mapping.kind() == PositionKind.GROUP_HEADER && groupKey.equals(mapping.groupKey())) {
                rows.add(header);
                flatPosition++;
            }
        }
        if (rows.size() <= headerPosition) {
            rows.add(ListSnapshot.Row.of(
                    new PositionMapping(flatPosition, PositionKind.GROUP_HEADER, groupKey, -1),
                    groupRow.header(),
                    groupRow.headerViewType()
            ));
            flatPosition++;
        }

        if (expanded) {
            List<?> children = groupRow.children();
            for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                Object childData = children.get(childIndex);
                rows.add(ListSnapshot.Row.of(
                        new PositionMapping(flatPosition, PositionKind.GROUP_ITEM, groupKey, childIndex),
                        childData,
                        resolveChildViewType(groupRow, childIndex, childData)
                ));
                flatPosition++;
            }
            if (groupRow.footer() != null) {
                rows.add(ListSnapshot.Row.of(
                        new PositionMapping(flatPosition, PositionKind.GROUP_FOOTER, groupKey, -1),
                        groupRow.footer(),
                        groupRow.footerViewType()
                ));
                flatPosition++;
            }
        } else if (groupRow.showFooterWhenCollapsed() && groupRow.footer() != null) {
            rows.add(ListSnapshot.Row.of(
                    new PositionMapping(flatPosition, PositionKind.GROUP_FOOTER, groupKey, -1),
                    groupRow.footer(),
                    groupRow.footerViewType()
            ));
            flatPosition++;
        }

        // Suffix: same rows, shifted flat positions. Data/viewType move by reference.
        for (int i = headerPosition + oldGroupSize; i < oldCount; i++) {
            ListSnapshot.Row<?> old = oldRows.get(i);
            PositionMapping mapping = old.mapping();
            rows.add(ListSnapshot.Row.of(
                    new PositionMapping(mapping.flatPosition() + shift, mapping.kind(), mapping.groupKey(), mapping.childIndex()),
                    old.data(),
                    old.viewType()
            ));
        }

        return rows;
    }

    private int calculateGroupVisibleSize(CoreRow.Group<?, ?, ?> groupRow, boolean expanded) {
        int size = 1; // header always visible
        if (expanded) {
            size += groupRow.children().size();
            if (groupRow.footer() != null) {
                size += 1;
            }
            return size;
        }
        if (groupRow.showFooterWhenCollapsed() && groupRow.footer() != null) {
            size += 1;
        }
        return size;
    }

    private ChangeSet toChangeSet(int headerPosition, int delta) {
        if (delta == 0) {
            // A group with no children and no collapsed footer has the same visible
            // size in both states, so a toggle can legitimately produce no delta.
            return ChangeSet.empty();
        }

        List<ChangeSet.Operation> operations = new ArrayList<>();
        if (delta > 0) {
            operations.add(ChangeSet.Operation.of(ChangeSet.Type.INSERT, headerPosition + 1, delta, -1));
        } else {
            operations.add(ChangeSet.Operation.of(ChangeSet.Type.REMOVE, headerPosition + 1, -delta, -1));
        }
        return ChangeSet.of(operations);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int resolveChildViewType(CoreRow.Group<?, ?, ?> groupRow, int childIndex, Object childData) {
        return ((CoreRow.Group) groupRow).childViewType(childIndex, childData);
    }
}
