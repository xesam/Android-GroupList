package io.github.xesam.android.grouplist.vnext.shared;

public final class PositionMapping {

    private final int flatPosition;
    private final PositionKind kind;
    private final GroupKey groupKey;
    private final int childIndex;

    public PositionMapping(int flatPosition, PositionKind kind, GroupKey groupKey, int childIndex) {
        this.flatPosition = flatPosition;
        this.kind = kind;
        this.groupKey = groupKey;
        this.childIndex = childIndex;
    }

    public int flatPosition() {
        return flatPosition;
    }

    public PositionKind kind() {
        return kind;
    }

    public GroupKey groupKey() {
        return groupKey;
    }

    public int childIndex() {
        return childIndex;
    }
}
