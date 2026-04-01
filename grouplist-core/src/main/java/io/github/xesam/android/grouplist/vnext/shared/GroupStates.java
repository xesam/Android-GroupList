package io.github.xesam.android.grouplist.vnext.shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure derivation of {@link GroupState} views over a flat snapshot row list.
 *
 * <p>Moved down from the RecyclerView adapter so that BOTH adapter layers derive
 * group state from the same single source (shared by recyclerview delegate and
 * any future compose-side consumer). Semantics are unchanged from the original
 * adapter implementation.</p>
 *
 * <p>Input contract: a group's rows must appear CONTIGUOUSLY in the flat row
 * list (the core layer guarantees this — a group block is emitted as one
 * unbroken header/children/footer segment).</p>
 */
public final class GroupStates {

    private GroupStates() {
    }

    /**
     * Scan-based derivation for a single key.
     *
     * <p>A key without a GROUP_HEADER row yields {@code null}; the same rule is
     * enforced by {@link #groupStateByKey} so the two derivation paths agree
     * for identical input (same-input, same-answer contract).</p>
     */
    public static GroupState computeGroupState(List<ListSnapshot.Row<?>> rows, GroupKey groupKey) {
        int headerPosition = -1;
        int footerPosition = -1;
        int visibleItemCount = 0;
        boolean expanded = false;
        for (int i = 0; i < rows.size(); i++) {
            ListSnapshot.Row<?> row = rows.get(i);
            PositionMapping mapping = row.mapping();
            if (!groupKey.equals(mapping.groupKey())) {
                if (headerPosition >= 0) {
                    break;
                }
                continue;
            }
            if (headerPosition < 0 && mapping.kind() == PositionKind.GROUP_HEADER) {
                headerPosition = i;
            }
            if (mapping.kind() == PositionKind.GROUP_FOOTER) {
                footerPosition = i;
            }
            if (mapping.kind() == PositionKind.GROUP_ITEM) {
                expanded = true;
            }
            visibleItemCount++;
        }
        if (headerPosition < 0) {
            return null;
        }
        return new GroupState(groupKey, expanded, headerPosition, footerPosition, visibleItemCount);
    }

    /**
     * Rebuilds the key -> GroupState cache in one pass over the rows.
     * One contiguous group segment = one segment; segments without a
     * GROUP_HEADER row are skipped so the cache answers exactly like
     * {@link #computeGroupState} would (same-input, same-answer contract
     * between the two paths).
     */
    public static Map<GroupKey, GroupState> groupStateByKey(List<ListSnapshot.Row<?>> rows) {
        Map<GroupKey, GroupState> states = new LinkedHashMap<>();
        GroupKey currentKey = null;
        int headerPosition = -1;
        int footerPosition = -1;
        int visibleItemCount = 0;
        boolean expanded = false;

        for (int i = 0; i < rows.size(); i++) {
            ListSnapshot.Row<?> row = rows.get(i);
            PositionMapping mapping = row.mapping();
            GroupKey groupKey = mapping.groupKey();

            if (groupKey != null && !groupKey.equals(currentKey)) {
                if (currentKey != null && headerPosition >= 0) {
                    states.put(currentKey, new GroupState(
                            currentKey, expanded, headerPosition, footerPosition, visibleItemCount
                    ));
                }
                currentKey = groupKey;
                headerPosition = -1;
                footerPosition = -1;
                visibleItemCount = 0;
                expanded = false;
            }

            if (groupKey != null) {
                if (headerPosition < 0 && mapping.kind() == PositionKind.GROUP_HEADER) {
                    headerPosition = i;
                }
                if (mapping.kind() == PositionKind.GROUP_FOOTER) {
                    footerPosition = i;
                }
                if (mapping.kind() == PositionKind.GROUP_ITEM) {
                    expanded = true;
                }
                visibleItemCount++;
            }
        }

        if (currentKey != null && headerPosition >= 0) {
            states.put(currentKey, new GroupState(
                    currentKey, expanded, headerPosition, footerPosition, visibleItemCount
            ));
        }
        return states;
    }
}
