package io.github.xesam.android.grouplist.example.ui.common;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.xesam.android.grouplist.example.data.repository.FeedRepository;
import io.github.xesam.android.grouplist.example.ui.mapper.FeedRowMapper;
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

public abstract class BaseDemoViewModel extends ViewModel {

    private final FeedRepository repository = new FeedRepository();
    private final FeedRowMapper mapper = new FeedRowMapper();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<ListSnapshot> uiState = new MutableLiveData<>();
    /** Set in onCleared: guards the already-scheduled mainHandler callbacks from
     *  writing state back into a cleared ViewModel (executor.shutdownNow() cannot
     *  recall a task that already fired and is queued on the main looper). */
    private volatile boolean cleared;

    protected final List<FeedUiModels.TopLevelRow> sourceRows = new ArrayList<>();
    protected final Map<GroupKey, FeedUiModels.GroupUiModel> groupsByKey = new LinkedHashMap<>();
    private final Map<GroupKey, Boolean> pendingLoadByGroupKey = new LinkedHashMap<>();

    protected FeedUiModels.GroupFilterMode currentFilterMode = FeedUiModels.GroupFilterMode.ALL_GROUPS;
    /** Last normalized FilterCard instance submitted to the engine — the old-data anchor
     *  for EngineCommand.UpdateStandalone (slot located by equals). */
    private FeedUiModels.GroupFilterCard lastSubmittedFilterCard;

    protected final void initialize() {
        bootstrapRows();
        onInitialized();
        publishState();
    }

    public final LiveData<ListSnapshot> getUiState() {
        return uiState;
    }

    public final void onFilterModeSelected(FeedUiModels.GroupFilterMode mode) {
        if (currentFilterMode == mode) {
            return;
        }
        currentFilterMode = mode;
        onFilterModeChanged(mode);
        publishState();
    }

    public final void onToggleGroupRequested(GroupKey groupKey) {
        FeedUiModels.GroupUiModel group = groupsByKey.get(groupKey);
        if (group == null) {
            return;
        }

        // 只转发 toggle 意图：目标态由 controller 内部从唯一事实源派生（单一事实源，
        // 不在 ViewModel 侧预先取反）。dispatch 之后再查询同一事实做出加载决策。
        onGroupToggleRequested(groupKey);
        publishState();

        if (isGroupExpanded(groupKey)) {
            maybeLoadGroupItems(groupKey, group);
        }
    }

    protected abstract void onInitialized();

    protected abstract void onFilterModeChanged(FeedUiModels.GroupFilterMode mode);

    /** 转发 toggle 意图；目标态由 controller 从其内部状态派生，见 onToggleGroupRequested。 */
    protected abstract void onGroupToggleRequested(GroupKey groupKey);

    /** Single source of truth for expansion state lives in the controller layer. */
    protected abstract boolean isGroupExpanded(GroupKey groupKey);

    protected abstract void onGroupItemsResolved(GroupKey groupKey, List<Object> resolvedChildren);

    protected abstract ListSnapshot currentSnapshot();

    protected final Object normalizeStandaloneData(FeedUiModels.StandaloneUiModel standalone) {
        if (standalone.data instanceof FeedUiModels.GroupFilterCard) {
            return createFilterCardForSubmit(currentFilterMode);
        }
        return standalone.data;
    }

    /** 每次要进入列表的 FilterCard 都必须经由此方法创建：它同步刷新
     *  lastSubmittedFilterCard 锚点（UpdateStandalone 按 equals 定位槽位），
     *  否则第二次切换筛选后锚点过期，卡片内容永远不再更新。 */
    protected final FeedUiModels.GroupFilterCard createFilterCardForSubmit(FeedUiModels.GroupFilterMode mode) {
        FeedUiModels.GroupFilterCard fresh = new FeedUiModels.GroupFilterCard(mode);
        lastSubmittedFilterCard = fresh;
        return fresh;
    }

    protected final FeedUiModels.GroupFilterCard lastSubmittedFilterCard() {
        return lastSubmittedFilterCard;
    }

    private void bootstrapRows() {
        sourceRows.clear();
        groupsByKey.clear();
        pendingLoadByGroupKey.clear();

        sourceRows.addAll(mapper.mapBackendRowsToUiRows(repository.loadInitialRows()));
        for (FeedUiModels.TopLevelRow row : sourceRows) {
            if (!(row instanceof FeedUiModels.GroupUiModel)) {
                continue;
            }
            FeedUiModels.GroupUiModel group = (FeedUiModels.GroupUiModel) row;
            GroupKey groupKey = GroupKey.of(group.id);
            groupsByKey.put(groupKey, group);
            pendingLoadByGroupKey.put(groupKey, false);
        }
    }

    private void maybeLoadGroupItems(GroupKey groupKey, FeedUiModels.GroupUiModel groupModel) {
        Boolean pending = pendingLoadByGroupKey.get(groupKey);
        if (pending != null && pending) {
            return;
        }
        if (!hasLoadingItems(groupModel)) {
            return;
        }

        pendingLoadByGroupKey.put(groupKey, true);
        // executor 只负责模拟加载延迟；状态变更(items 替换 + controller dispatch
        // + publishState)必须回主线程，保证 controller 单线程访问且 setValue 合法。
        executor.schedule(() -> mainHandler.post(() -> resolveLoadingItems(groupKey)),
                1600, TimeUnit.MILLISECONDS);
    }

    private boolean hasLoadingItems(FeedUiModels.GroupUiModel groupModel) {
        for (Object item : groupModel.items) {
            if (item instanceof FeedUiModels.FeedCommentItem
                    && ((FeedUiModels.FeedCommentItem) item).loading) {
                return true;
            }
            if (item instanceof FeedUiModels.TopicOptionItem
                    && ((FeedUiModels.TopicOptionItem) item).loading) {
                return true;
            }
        }
        return false;
    }

    private void resolveLoadingItems(GroupKey groupKey) {
        if (cleared) {
            // onCleared ran between scheduling and delivery: write nothing back.
            return;
        }
        pendingLoadByGroupKey.put(groupKey, false);
        FeedUiModels.GroupUiModel groupModel = groupsByKey.get(groupKey);
        if (groupModel == null) {
            return;
        }

        List<Object> resolved = new ArrayList<>();
        int loadedCommentCursor = 0;
        int loadedTopicCursor = 0;

        for (Object item : groupModel.items) {
            if (item instanceof FeedUiModels.FeedCommentItem) {
                FeedUiModels.FeedCommentItem commentItem = (FeedUiModels.FeedCommentItem) item;
                if (commentItem.loading) {
                    loadedCommentCursor++;
                    resolved.add(FeedUiModels.FeedCommentItem.loaded(
                            "@AutoLoad" + loadedCommentCursor,
                            "这条评论在分组展开后加载完成。"
                    ));
                } else {
                    resolved.add(commentItem);
                }
                continue;
            }

            if (item instanceof FeedUiModels.TopicOptionItem) {
                FeedUiModels.TopicOptionItem optionItem = (FeedUiModels.TopicOptionItem) item;
                if (optionItem.loading) {
                    loadedTopicCursor++;
                    resolved.add(FeedUiModels.TopicOptionItem.loaded(
                            "新增候选项（已加载）" + loadedTopicCursor,
                            90 + loadedTopicCursor * 17
                    ));
                } else {
                    resolved.add(optionItem);
                }
                continue;
            }

            resolved.add(item);
        }

        groupModel.items.clear();
        groupModel.items.addAll(resolved);
        onGroupItemsResolved(groupKey, resolved);
        publishState();
    }

    protected final void publishState() {
        // Both callers run on the main thread (ViewModel methods and the mainHandler
        // callbacks) — setValue requires it: the thread contract is part of the
        // library's vnext package-info, kept visible here on purpose.
        uiState.setValue(currentSnapshot());
        // ChangeSet 消费协议（根 README 公开契约 #5）：快照帧交给 UI 后必须 drain，
        // 否则 pending ops 跨 publish 复合（incremental after incremental compounds），
        // 下一帧会携带上一帧已应用过的 ops —— RecyclerView 重复应用后以
        // "Inconsistency detected" 崩溃。此前 demo 正是漏掉了这一步。
        drainPendingChanges();
    }

    /** 见 {@link #publishState()}：把本帧快照标记为已消费（takeChangeSet）。 */
    protected abstract void drainPendingChanges();

    @Override
    protected void onCleared() {
        cleared = true;
        executor.shutdownNow();
    }
}
