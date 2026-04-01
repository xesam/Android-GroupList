package io.github.xesam.android.grouplist.vnext.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.xesam.android.grouplist.vnext.core.CoreCommand;
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.core.CoreController;
import io.github.xesam.android.grouplist.vnext.core.CoreRow;
import io.github.xesam.android.grouplist.vnext.core.DefaultCoreController;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

public final class DefaultEngineController implements EngineController {
    public interface GroupVisibilityResolver {
        boolean isVisible(GroupKey groupKey, Object sourceGroup, Object filterPayload);
    }

    private final CoreController coreController;
    private final BinderRegistry binderRegistry;
    private final GroupVisibilityResolver groupVisibilityResolver;
    private final CoreRowFactory coreRowFactory;
    private final Map<Class<?>, GroupSchema<?, ?, ?, ?>> schemaByClass;
    /**
     * Explicit slot model for the submitted order: no private sentinel type ever
     * mixes with caller-owned business objects in one list; standalone slot ordinals
     * are by definition the standalone order (they are never hidden / collapsed).
     */
    private interface Slot {
        final class Standalone implements Slot {
            Object data;

            Standalone(Object data) {
                this.data = data;
            }
        }

        final class Group implements Slot {
            final EngineGroupRecord record;

            Group(EngineGroupRecord record) {
                this.record = record;
            }
        }
    }

    private final List<Slot> orderedSlots = new ArrayList<>();
    private final Map<GroupKey, EngineGroupRecord> groupRecordByKey = new LinkedHashMap<>();
    private Object filterPayload;

    public DefaultEngineController(
            BinderRegistry binderRegistry,
            Map<Class<?>, GroupSchema<?, ?, ?, ?>> schemas,
            GroupVisibilityResolver groupVisibilityResolver,
            CoreRowFactory coreRowFactory
    ) {
        this.coreController = new DefaultCoreController();
        this.binderRegistry = binderRegistry;
        this.schemaByClass = new LinkedHashMap<>(schemas);
        this.groupVisibilityResolver = groupVisibilityResolver == null
                ? (groupKey, sourceGroup, payload) -> true
                : groupVisibilityResolver;
        this.coreRowFactory = coreRowFactory == null
                ? defaultCoreRowFactory()
                : coreRowFactory;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void submit(List<?> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        // Build into TEMP structures first and swap atomically after ALL validation:
        // a throwing submit must leave the engine (and the core controller behind
        // it, which is never reached) in its pre-call state — no half-applied
        // submit, same discipline as DefaultCoreController.requireValidRows.
        // Building the CoreRows from the pending slots up front also keeps the
        // unmapped-viewType / null-model failure paths (binderRegistry) pre-swap.
        List<Slot> pendingSlots = new ArrayList<>();
        Map<GroupKey, EngineGroupRecord> pendingRecords = new LinkedHashMap<>();
        for (Object row : rows) {
            if (row == null) {
                // 与 Core 层同一结构性违约矩阵：null 元素显式 fail-fast，
                // 否则会以"null schema"之名向下钻到 BinderRegistry 才以 ISE 形式炸出，
                // 失败类型与 package-info 声明的 IAE 矩阵不一致。
                throw new IllegalArgumentException("rows must not contain null elements");
            }
            GroupSchema<?, ?, ?, ?> schema = findSchema(row);
            if (schema == null) {
                pendingSlots.add(new Slot.Standalone(row));
                continue;
            }

            EngineGroupRecord record = new EngineGroupRecord(row, schema);
            if (pendingRecords.containsKey(record.groupKey)) {
                throw new IllegalArgumentException("Duplicate groupKey found: " + record.groupKey.value());
            }
            pendingSlots.add(new Slot.Group(record));
            pendingRecords.put(record.groupKey, record);
        }
        List<CoreRow> pendingCoreRows = buildCoreRows(pendingSlots);

        orderedSlots.clear();
        orderedSlots.addAll(pendingSlots);
        groupRecordByKey.clear();
        groupRecordByKey.putAll(pendingRecords);

        coreController.submit(pendingCoreRows);
    }

    @Override
    public void dispatch(EngineCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        if (command instanceof EngineCommand.ToggleGroup) {
            EngineCommand.ToggleGroup toggleGroup = (EngineCommand.ToggleGroup) command;
            if (!groupRecordByKey.containsKey(toggleGroup.groupKey())) {
                return; // fail-silent by contract (unknown key)
            }
            // Expansion state lives ONLY in the core controller; the target is derived
            // from it instead of from a second mutable copy.
            boolean target = !coreController.isGroupExpanded(toggleGroup.groupKey());
            coreController.dispatch(new CoreCommand.SetGroupExpanded(toggleGroup.groupKey(), target));
            return;
        }

        if (command instanceof EngineCommand.SetGroupExpanded) {
            EngineCommand.SetGroupExpanded setGroupExpanded = (EngineCommand.SetGroupExpanded) command;
            if (!groupRecordByKey.containsKey(setGroupExpanded.groupKey())) {
                return; // fail-silent by contract (unknown key)
            }
            coreController.dispatch(new CoreCommand.SetGroupExpanded(
                    setGroupExpanded.groupKey(), setGroupExpanded.expanded()
            ));
            return;
        }

        if (command instanceof EngineCommand.ReplaceChildren) {
            EngineCommand.ReplaceChildren replaceChildren = (EngineCommand.ReplaceChildren) command;
            EngineGroupRecord record = groupRecordByKey.get(replaceChildren.groupKey());
            if (record == null) {
                return; // fail-silent by contract (unknown key)
            }
            record.childrenOverride = new ArrayList<Object>(replaceChildren.children());
            if (!groupVisibilityResolver.isVisible(record.groupKey, record.source, filterPayload)) {
                // Group is currently filtered out: keep the override for a later full submit,
                // but nothing visible changes.
                return;
            }
            coreController.dispatch(new CoreCommand.ReplaceChildren(
                    replaceChildren.groupKey(),
                    record.children()
            ));
            return;
        }

        if (command instanceof EngineCommand.SetVisibilityFilter) {
            EngineCommand.SetVisibilityFilter setVisibilityFilter = (EngineCommand.SetVisibilityFilter) command;
            filterPayload = setVisibilityFilter.payload();
            // narrow-the-visible-world: precise reconcile with slow-frozen expansion,
            // NOT the reload channel — see command contract for the split rationale.
            coreController.dispatch(new CoreCommand.ReconcileRows(buildCoreRows()));
            return;
        }

        if (command instanceof EngineCommand.UpdateStandalone) {
            EngineCommand.UpdateStandalone updateStandalone = (EngineCommand.UpdateStandalone) command;
            handleUpdateStandalone(updateStandalone);
            return;
        }

        throw new IllegalArgumentException(
                "Unknown EngineCommand type: " + command.getClass().getName()
        );
    }

    /**
     * UpdateStandalone publishing: a precisely-scoped UPDATE via
     * CoreCommand.UpdateStandaloneFlat — no longer the ReconcileRows channel, so no
     * count-conservation / content-trust preconditions apply. Standalone slot
     * ordinals double as the visible standalone order (standalones are never
     * hidden/collapsed); the flat position is resolved from the current snapshot.
     */
    private void handleUpdateStandalone(EngineCommand.UpdateStandalone command) {
        int standaloneIndex = command.standaloneIndex() >= 0
                ? command.standaloneIndex()
                : standaloneSlotIndexByData(command.oldData());
        if (standaloneIndex < 0) {
            return; // fail-silent by contract (unknown index / data)
        }
        Slot slot = slotAtStandaloneOrdinal(standaloneIndex);
        if (!(slot instanceof Slot.Standalone)) {
            return; // fail-silent by contract (index beyond the standalone slots)
        }
        Slot.Standalone target = (Slot.Standalone) slot;
        int flatPosition = flatPositionOfStandalone(standaloneIndex);
        if (flatPosition < 0) {
            return; // currently not visible; nothing to update
        }
        Object newData = command.newData();
        target.data = newData; // keep the mirror in sync for later full re-submits
        coreController.dispatch(new CoreCommand.UpdateStandaloneFlat(flatPosition, newData));
    }

    /** First standalone slot whose data equals {@code oldData} (submit order). */
    private int standaloneSlotIndexByData(Object oldData) {
        int ordinal = 0;
        for (Slot slot : orderedSlots) {
            if (slot instanceof Slot.Standalone) {
                if (Objects.equals(((Slot.Standalone) slot).data, oldData)) {
                    return ordinal;
                }
                ordinal++;
            }
        }
        return -1;
    }

    /** Resolves the standalone slot with the given standalone ordinal. */
    private Slot slotAtStandaloneOrdinal(int standaloneIndex) {
        int ordinal = 0;
        for (Slot slot : orderedSlots) {
            if (slot instanceof Slot.Standalone) {
                if (ordinal == standaloneIndex) {
                    return slot;
                }
                ordinal++;
            }
        }
        return null;
    }

    /**
     * Flat (visible) position of the Nth standalone row, counted from the current
     * snapshot rows; standalones are always visible so the two orders coincide.
     */
    private int flatPositionOfStandalone(int standaloneIndex) {
        List<ListSnapshot.Row<?>> snapshotRows = coreController.snapshot().rows();
        int ordinal = 0;
        for (ListSnapshot.Row<?> row : snapshotRows) {
            if (row.mapping().kind() == PositionKind.STANDALONE) {
                if (ordinal == standaloneIndex) {
                    return row.mapping().flatPosition();
                }
                ordinal++;
            }
        }
        return -1;
    }

    @Override
    public ListSnapshot snapshot() {
        return coreController.snapshot();
    }

    @Override
    public boolean hasPendingChanges() {
        return coreController.hasPendingChanges();
    }

    @Override
    public ChangeSet takeChangeSet() {
        return coreController.takeChangeSet();
    }

    @Override
    public boolean isGroupExpanded(GroupKey groupKey) {
        return coreController.isGroupExpanded(groupKey);
    }

    private List<CoreRow> buildCoreRows() {
        return buildCoreRows(orderedSlots);
    }

    private List<CoreRow> buildCoreRows(List<Slot> slots) {
        List<CoreRow> rows = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot instanceof Slot.Standalone) {
                Object data = ((Slot.Standalone) slot).data;
                rows.add(coreRowFactory.createStandalone(
                        data,
                        binderRegistry.resolveViewType(BinderRegistry.ViewRole.STANDALONE, data)
                ));
                continue;
            }

            EngineGroupRecord record = ((Slot.Group) slot).record;
            if (!groupVisibilityResolver.isVisible(record.groupKey, record.source, filterPayload)) {
                continue;
            }
            Object footer = record.footer();
            rows.add(coreRowFactory.createGroup(
                    record.groupKey,
                    record.header(),
                    footer,
                    record.children(),
                    record.initiallyExpanded,
                    record.showFooterWhenCollapsed,
                    binderRegistry.resolveViewType(BinderRegistry.ViewRole.GROUP_HEADER, record.header()),
                    // footer == null is the contract for "no footer"; the footerViewType
                    // argument is then unspecified/ignored by Core (no magic-0 semantics needed)
                    footer == null
                            ? 0
                            : binderRegistry.resolveViewType(BinderRegistry.ViewRole.GROUP_FOOTER, footer),
                    (childIndex, childData) -> binderRegistry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, childData)
            ));
        }
        return rows;
    }

    private static CoreRowFactory defaultCoreRowFactory() {
        return new CoreRowFactory() {
            @Override
            public CoreRow createStandalone(Object row, int standaloneViewType) {
                return CoreRow.Standalone.of(row, standaloneViewType);
            }

            @Override
            public <I> CoreRow createGroup(
                    GroupKey groupKey,
                    Object header,
                    Object footer,
                    List<I> children,
                    boolean expanded,
                    boolean showFooterWhenCollapsed,
                    int headerViewType,
                    int footerViewType,
                    CoreRow.Group.ItemViewTypeResolver<I> itemViewTypeResolver
            ) {
                return CoreRow.Group.<Object, I, Object>builder(groupKey)
                .header(header)
                .footer(footer)
                .children(children)
                .initiallyExpanded(expanded)
                .showFooterWhenCollapsed(showFooterWhenCollapsed)
                .headerViewType(headerViewType)
                .footerViewType(footerViewType)
                .childViewTypeResolver(itemViewTypeResolver)
                .build();
            }
        };
    }

    /**
     * Deterministic schema lookup (contract):
     * exact class match first; otherwise the MOST SPECIFIC assignable class wins
     * (subtype beats supertype), regardless of registration order — result only
     * depends on the registered class set, never on map iteration order.
     */
    private GroupSchema<?, ?, ?, ?> findSchema(Object row) {
        GroupSchema<?, ?, ?, ?> direct = schemaByClass.get(row.getClass());
        if (direct != null) {
            return direct;
        }

        Class<?> modelClass = row.getClass();
        GroupSchema<?, ?, ?, ?> best = null;
        Class<?> bestClass = null;
        for (Map.Entry<Class<?>, GroupSchema<?, ?, ?, ?>> entry : schemaByClass.entrySet()) {
            Class<?> registered = entry.getKey();
            if (!registered.isAssignableFrom(modelClass)) {
                continue;
            }
            if (bestClass == null || bestClass.isAssignableFrom(registered)) {
                best = entry.getValue();
                bestClass = registered;
            }
        }
        return best;
    }

    public static final class Builder {
        private final BinderRegistry binderRegistry = new BinderRegistry();
        private final Map<Class<?>, GroupSchema<?, ?, ?, ?>> schemas = new LinkedHashMap<>();
        private GroupVisibilityResolver groupVisibilityResolver;
        private CoreRowFactory coreRowFactory;

        /**
         * Convenience API.
         * Maps the same viewType to all roles. Prefer role-based APIs for production usage.
         */
        public Builder registerViewType(Class<?> modelClass, int viewType) {
            binderRegistry.registerForAllRoles(modelClass, viewType);
            return this;
        }

        /**
         * Recommended V1 path: explicit role-based mapping.
         */
        public Builder registerStandaloneViewType(Class<?> modelClass, int viewType) {
            binderRegistry.register(BinderRegistry.ViewRole.STANDALONE, modelClass, viewType);
            return this;
        }

        public Builder registerGroupHeaderViewType(Class<?> modelClass, int viewType) {
            binderRegistry.register(BinderRegistry.ViewRole.GROUP_HEADER, modelClass, viewType);
            return this;
        }

        public Builder registerGroupItemViewType(Class<?> modelClass, int viewType) {
            binderRegistry.register(BinderRegistry.ViewRole.GROUP_ITEM, modelClass, viewType);
            return this;
        }

        public Builder registerGroupFooterViewType(Class<?> modelClass, int viewType) {
            binderRegistry.register(BinderRegistry.ViewRole.GROUP_FOOTER, modelClass, viewType);
            return this;
        }

        /**
         * Optional advanced hook for dynamic role/model mapping.
         * The key is used as resolver id. Registering the same key again replaces the previous resolver.
         */
        public Builder registerViewTypeResolver(String key, BinderRegistry.ViewTypeResolver resolver) {
            binderRegistry.registerResolver(key, resolver);
            return this;
        }

        /**
         * Registers a business model class with its GroupSchema. Lookup contract:
         * exact class match first; otherwise the most specific assignable class
         * (subtype beats supertype) wins, independent of registration order.
         */
        public <G, H, C, F> Builder registerSchema(Class<G> modelClass, GroupSchema<G, H, C, F> schema) {
            schemas.put(modelClass, schema);
            return this;
        }

        public Builder setGroupVisibilityResolver(GroupVisibilityResolver groupVisibilityResolver) {
            this.groupVisibilityResolver = groupVisibilityResolver;
            return this;
        }

        /**
         * Optional advanced extension hook for custom CoreRow creation strategy.
         * Most V1 scenarios can keep the default implementation.
         */
        public Builder setCoreRowFactory(CoreRowFactory coreRowFactory) {
            this.coreRowFactory = coreRowFactory;
            return this;
        }

        public DefaultEngineController build() {
            return new DefaultEngineController(
                    binderRegistry,
                    schemas,
                    groupVisibilityResolver,
                    coreRowFactory
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static final class EngineGroupRecord {
        private final Object source;
        private final GroupSchema<Object, Object, Object, Object> schema;
        private final GroupKey groupKey;
        private final boolean showFooterWhenCollapsed;
        /**
         * Mirror of the group's INITIAL expanded state, captured at submit time and used
         * when re-building CoreRows for a full re-submit. Deliberately final and named
         * accordingly: live expansion lives exclusively in the core controller — this
         * field is never consulted as runtime state, and a re-submit resets expansion
         * to this initial value (the documented public contract of submit).
         */
        private final boolean initiallyExpanded;
        private List<Object> childrenOverride;

        private EngineGroupRecord(Object source, GroupSchema<?, ?, ?, ?> schema) {
            this.source = source;
            this.schema = (GroupSchema<Object, Object, Object, Object>) schema;
            this.groupKey = this.schema.keyOf(source);
            this.initiallyExpanded = this.schema.initiallyExpanded(source);
            this.showFooterWhenCollapsed = this.schema.showFooterWhenCollapsed(source);
        }

        private Object header() {
            return schema.headerOf(source);
        }

        private Object footer() {
            return schema.footerOf(source);
        }

        private List<Object> children() {
            if (childrenOverride != null) {
                return childrenOverride;
            }
            return new ArrayList<Object>(schema.childrenOf(source));
        }
    }
}
