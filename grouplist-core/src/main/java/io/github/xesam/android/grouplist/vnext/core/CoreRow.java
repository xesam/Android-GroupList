package io.github.xesam.android.grouplist.vnext.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

public interface CoreRow {

    interface Standalone<T> extends CoreRow {
        T data();

        int viewType();

        static <T> Standalone<T> of(T data, int viewType) {
            return new Default<>(data, viewType);
        }

        final class Default<T> implements Standalone<T> {
            private final T data;
            private final int viewType;

            private Default(T data, int viewType) {
                this.data = data;
                this.viewType = viewType;
            }

            @Override
            public T data() {
                return data;
            }

            @Override
            public int viewType() {
                return viewType;
            }
        }
    }

    /**
     * A top-level group block. Public contract:
     * <ul>
     *   <li>{@code footer() == null} is the SINGLE source of "no footer";
     *     in that case {@code footerViewType()}'s value is unspecified and ignored.</li>
     *   <li>Construct via {@link #builder(GroupKey)} (the flat 9-arg factory was
     *     removed pre-release: adjacent boolean/int params are position-error prone).</li>
     * </ul>
     */
    interface Group<H, I, F> extends CoreRow {
        /**
         * Derives a child's viewType from its position and data.
         *
         * <p>Contract: a pure function of {@code (childIndex, childData)}. The precise
         * child diffs ({@code ReplaceChildren}, {@code ReplaceRows}) compare children by
         * {@code equals} and read equal data as "nothing visible changed", so a resolver
         * whose answer also depends on outside state can change a row's viewType without
         * any operation being emitted to tell the consumer to rebind.</p>
         */
        interface ItemViewTypeResolver<I> {
            int resolve(int childIndex, I childData);
        }

        GroupKey groupKey();

        H header();

        F footer();

        List<I> children();

        boolean initiallyExpanded();

        boolean showFooterWhenCollapsed();

        int headerViewType();

        int footerViewType();

        int childViewType(int childIndex, I childData);

        /**
         * Entry point of the group construction API.
         *
         * <p>Required: {@code groupKey} (here, non-null), a non-null
         * {@link Builder#childViewTypeResolver(ItemViewTypeResolver)} whenever
         * {@link Builder#children(List)} is non-empty. Everything else has a
         * documented default; build() fails fast on contract violations.
         */
        static <H, I, F> Builder<H, I, F> builder(GroupKey groupKey) {
            return new Builder<>(groupKey);
        }

        /**
         * Fluent builder over {@link Group}. Defaults: footer null (no footer),
         * children empty, initiallyExpanded false, showFooterWhenCollapsed false,
         * viewTypes 0 (unspecified/ignored until the corresponding element is present).
         */
        final class Builder<H, I, F> {
            private final GroupKey groupKey;
            private H header;
            private F footer;
            private List<I> children;
            private boolean initiallyExpanded;
            private boolean showFooterWhenCollapsed;
            private int headerViewType;
            private int footerViewType;
            private ItemViewTypeResolver<I> itemViewTypeResolver;

            private Builder(GroupKey groupKey) {
                this.groupKey = Objects.requireNonNull(groupKey, "groupKey must not be null");
            }

            public Builder<H, I, F> header(H header) {
                this.header = header;
                return this;
            }

            /** Pass null (or skip) to create a footer-less group; the null-ness is contract. */
            public Builder<H, I, F> footer(F footer) {
                this.footer = footer;
                return this;
            }

            public Builder<H, I, F> children(List<I> children) {
                this.children = children;
                return this;
            }

            public Builder<H, I, F> initiallyExpanded(boolean initiallyExpanded) {
                this.initiallyExpanded = initiallyExpanded;
                return this;
            }

            public Builder<H, I, F> showFooterWhenCollapsed(boolean showFooterWhenCollapsed) {
                this.showFooterWhenCollapsed = showFooterWhenCollapsed;
                return this;
            }

            public Builder<H, I, F> headerViewType(int headerViewType) {
                this.headerViewType = headerViewType;
                return this;
            }

            /** Unspecified/ignored when {@link #footer(Object)} is null. */
            public Builder<H, I, F> footerViewType(int footerViewType) {
                this.footerViewType = footerViewType;
                return this;
            }

            /** Required when {@link #children(List)} is non-empty. */
            public Builder<H, I, F> childViewTypeResolver(ItemViewTypeResolver<I> itemViewTypeResolver) {
                this.itemViewTypeResolver = itemViewTypeResolver;
                return this;
            }

            public Group<H, I, F> build() {
                List<I> effectiveChildren = children == null ? new ArrayList<>() : children;
                ItemViewTypeResolver<I> effectiveResolver = itemViewTypeResolver;
                if (effectiveResolver == null && !effectiveChildren.isEmpty()) {
                    throw new IllegalArgumentException(
                            "childViewTypeResolver must be set when children is non-empty: "
                                    + groupKey.value());
                }
                if (effectiveResolver == null) {
                    // no children: the resolver is never invoked; keep a null-safe constant
                    effectiveResolver = (childIndex, childData) -> 0;
                }
                return new Default<>(
                        groupKey,
                        header,
                        footer,
                        effectiveChildren,
                        initiallyExpanded,
                        showFooterWhenCollapsed,
                        headerViewType,
                        footerViewType,
                        effectiveResolver
                );
            }
        }

        final class Default<H, I, F> implements Group<H, I, F> {
            private final GroupKey groupKey;
            private final H header;
            private final F footer;
            private final List<I> children;
            private final boolean initiallyExpanded;
            private final boolean showFooterWhenCollapsed;
            private final int headerViewType;
            private final int footerViewType;
            private final ItemViewTypeResolver<I> itemViewTypeResolver;

            private Default(
                    GroupKey groupKey,
                    H header,
                    F footer,
                    List<I> children,
                    boolean initiallyExpanded,
                    boolean showFooterWhenCollapsed,
                    int headerViewType,
                    int footerViewType,
                    ItemViewTypeResolver<I> itemViewTypeResolver
            ) {
                this.groupKey = Objects.requireNonNull(groupKey, "groupKey must not be null");
                this.header = header;
                this.footer = footer;
                this.children = children == null ? new ArrayList<>() : new ArrayList<>(children);
                this.initiallyExpanded = initiallyExpanded;
                this.showFooterWhenCollapsed = showFooterWhenCollapsed;
                this.headerViewType = headerViewType;
                this.footerViewType = footerViewType;
                this.itemViewTypeResolver = Objects.requireNonNull(
                        itemViewTypeResolver,
                        "itemViewTypeResolver must not be null"
                );
            }

            @Override
            public GroupKey groupKey() {
                return groupKey;
            }

            @Override
            public H header() {
                return header;
            }

            @Override
            public F footer() {
                return footer;
            }

            @Override
            public List<I> children() {
                return Collections.unmodifiableList(children);
            }

            @Override
            public boolean initiallyExpanded() {
                return initiallyExpanded;
            }

            @Override
            public boolean showFooterWhenCollapsed() {
                return showFooterWhenCollapsed;
            }

            @Override
            public int headerViewType() {
                return headerViewType;
            }

            @Override
            public int footerViewType() {
                return footerViewType;
            }

            @Override
            public int childViewType(int childIndex, I childData) {
                return itemViewTypeResolver.resolve(childIndex, childData);
            }
        }
    }
}
