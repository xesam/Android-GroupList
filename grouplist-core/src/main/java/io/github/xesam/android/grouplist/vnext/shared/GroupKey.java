package io.github.xesam.android.grouplist.vnext.shared;

import java.util.Objects;

public final class GroupKey {

    private final String value;

    private GroupKey(String value) {
        this.value = value;
    }

    public static GroupKey of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("GroupKey value must not be empty");
        }
        // Normalize: " a " and "a" would otherwise be two distinct keys while the
        // empty-check above already rejects whitespace-only input — surprising
        // asymmetry for callers passing untrimmed domain ids.
        return new GroupKey(value.trim());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupKey)) {
            return false;
        }
        GroupKey groupKey = (GroupKey) o;
        return value.equals(groupKey.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
