package io.github.xesam.android.grouplist.vnext.engine;

import java.util.List;

import io.github.xesam.android.grouplist.vnext.core.CoreRow;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;

/**
 * Creation hook from Engine-side business rows to Core-layer structural rows.
 *
 * <p>Footer contract (aligned with {@link CoreRow.Group}): a null {@code footer} is
 * the SINGLE source of "no footer"; in that case the {@code footerViewType} argument
 * is unspecified and ignored by the Core layer (Engine may pass any sentinel).
 */
public interface CoreRowFactory {
    CoreRow createStandalone(Object row, int standaloneViewType);

    <I> CoreRow createGroup(
            GroupKey groupKey,
            Object header,
            Object footer,
            List<I> children,
            boolean expanded,
            boolean showFooterWhenCollapsed,
            int headerViewType,
            int footerViewType,
            CoreRow.Group.ItemViewTypeResolver<I> itemViewTypeResolver
    );
}
