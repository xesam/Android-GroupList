package io.github.xesam.android.grouplist.vnext.shared;

/**
 * Snapshot-derived view of one group. Derivation caveats (both are public contract):
 * <ul>
 *   <li>{@code expanded} is INFERRED from the presence of visible child rows —
 *     it is an approximation of the controller's expansion state. An expanded
 *     group with zero children reports {@code expanded == false} (nothing
 *     distinguishes it from a collapsed one in a flat snapshot);</li>
 *   <li>{@code visibleItemCount} counts ALL visible rows of the group, header and
 *     footer included (not just child items).</li>
 * </ul>
 */
public final class GroupState {
    private final GroupKey groupKey;
    private final boolean expanded;
    private final int headerPosition;
    private final int footerPosition;
    private final int visibleItemCount;

    public GroupState(
            GroupKey groupKey,
            boolean expanded,
            int headerPosition,
            int footerPosition,
            int visibleItemCount
    ) {
        this.groupKey = groupKey;
        this.expanded = expanded;
        this.headerPosition = headerPosition;
        this.footerPosition = footerPosition;
        this.visibleItemCount = visibleItemCount;
    }

    public GroupKey groupKey() {
        return groupKey;
    }

    public boolean expanded() {
        return expanded;
    }

    public int headerPosition() {
        return headerPosition;
    }

    public int footerPosition() {
        return footerPosition;
    }

    public int visibleItemCount() {
        return visibleItemCount;
    }
}
