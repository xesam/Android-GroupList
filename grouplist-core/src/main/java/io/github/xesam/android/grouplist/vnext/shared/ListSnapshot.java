package io.github.xesam.android.grouplist.vnext.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ListSnapshot {

    public static final class Row<T> {
        private final PositionMapping mapping;
        private final T data;
        private final int viewType;

        /** Production snapshots are built inside the Core layer; tests may use this factory. */
        public static <T> Row<T> of(PositionMapping mapping, T data, int viewType) {
            return new Row<>(mapping, data, viewType);
        }

        private Row(PositionMapping mapping, T data, int viewType) {
            this.mapping = mapping;
            this.data = data;
            this.viewType = viewType;
        }

        public PositionMapping mapping() {
            return mapping;
        }

        public T data() {
            return data;
        }

        public int viewType() {
            return viewType;
        }
    }

    /** Version marker of snapshots built outside a controller (no lineage information). */
    public static final long UNVERSIONED = -1L;

    private final List<Row<?>> rows;
    private final ChangeSet changeSet;
    private final long version;
    private final long baseVersion;

    /** Trusted factory: same defensive semantics as the (private) constructor. */
    public static ListSnapshot of(List<? extends Row<?>> rows, ChangeSet changeSet) {
        return new ListSnapshot(rows, changeSet, false, UNVERSIONED, UNVERSIONED);
    }

    /**
     * Trusted path: NO defensive copy. The caller must own the passed list, hand it
     * over exclusively and never mutate it after this call.
     *
     * <p>Unversioned on purpose, which also means un-gated: a consumer has no
     * {@link #baseVersion()} to check, so an incremental {@code changeSet} handed over
     * here is applied as-is. Produced only by hand-built snapshots (tests, callers that
     * publish a snapshot without a controller); the Core controller itself uses the
     * versioned overload below.</p>
     */
    public static ListSnapshot ofTrusted(List<? extends Row<?>> rows, ChangeSet changeSet) {
        return new ListSnapshot(rows, changeSet, true, UNVERSIONED, UNVERSIONED);
    }

    /**
     * Trusted versioned path for controllers: {@code changeSet} transforms the rows of
     * the snapshot whose {@link #version()} is {@code baseVersion} into these rows.
     * Versions are globally unique, so a consumer can tell whether the delta applies
     * to what it currently displays (see {@link #version()}).
     */
    public static ListSnapshot ofTrusted(
            List<? extends Row<?>> rows,
            ChangeSet changeSet,
            long version,
            long baseVersion
    ) {
        return new ListSnapshot(rows, changeSet, true, version, baseVersion);
    }

    @SuppressWarnings("unchecked")
    private ListSnapshot(
            List<? extends Row<?>> rows,
            ChangeSet changeSet,
            boolean trusted,
            long version,
            long baseVersion
    ) {
        this.rows = rows == null ? new ArrayList<>() : (trusted ? (List<Row<?>>) rows : new ArrayList<>(rows));
        this.changeSet = changeSet == null ? ChangeSet.empty() : changeSet;
        this.version = version;
        this.baseVersion = baseVersion;
    }

    /**
     * Consumer-safety contract: a positional consumer that currently displays the
     * snapshot with version {@code V} may apply {@link #changeSet()} precisely ONLY when
     * {@code baseVersion() == V}; {@code version() == V} means a re-delivery of what is
     * already displayed (nothing to apply); anything else means intermediate snapshots
     * were skipped and the consumer must rebuild fully.
     *
     * @return the globally unique version, or {@link #UNVERSIONED} for hand-built snapshots.
     */
    public long version() {
        return version;
    }

    /** @return the version the {@link #changeSet()} applies to, or {@link #UNVERSIONED}. */
    public long baseVersion() {
        return baseVersion;
    }

    public boolean isVersioned() {
        return version != UNVERSIONED;
    }

    public List<Row<?>> rows() {
        return Collections.unmodifiableList(rows);
    }

    public int itemCount() {
        return rows.size();
    }

    public ChangeSet changeSet() {
        return changeSet;
    }
}
