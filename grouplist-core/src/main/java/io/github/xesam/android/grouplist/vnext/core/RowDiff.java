package io.github.xesam.android.grouplist.vnext.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;
import io.github.xesam.android.grouplist.vnext.shared.PositionKind;

/**
 * Flat visible-row diff behind {@link CoreCommand.ReplaceRows}.
 *
 * <p>Row identity: headers / footers by (kind, GroupKey); standalone rows and group items
 * by {@link ItemIdentity} (group items additionally scoped to their GroupKey). Matched rows
 * whose data (by {@code equals}) or viewType differ become in-place UPDATEs.
 *
 * <p>Algorithm: common prefix / suffix trimming, then Myers' O((N+M)D) greedy diff on the
 * remaining middle. Middles whose edit distance exceeds {@link #MAX_EDIT_DISTANCE} fall back
 * to a single REMOVE + INSERT of the middle range (still a correct incremental delta, just
 * without holder reuse) to keep the transient trace memory bounded (~D² ints).
 *
 * <p>Emission order: one forward walk, so every operation is in the sequential-application
 * coordinates of {@link ChangeSet} (the evolving list's prefix always equals the new list's
 * prefix up to the current position). MOVE is never emitted; a reordered block shows up as
 * REMOVE + INSERT.
 */
final class RowDiff {

    static final int MAX_EDIT_DISTANCE = 512;

    private static final byte EQUAL = 0;
    private static final byte DELETE = 1;
    private static final byte INSERT = 2;

    private RowDiff() {
    }

    static ChangeSet diff(
            List<ListSnapshot.Row<?>> oldRows,
            List<ListSnapshot.Row<?>> newRows,
            ItemIdentity identity
    ) {
        int n = oldRows.size();
        int m = newRows.size();
        Token[] a = tokens(oldRows, identity);
        Token[] b = tokens(newRows, identity);

        int prefix = 0;
        while (prefix < n && prefix < m && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
                && a[n - 1 - suffix].equals(b[m - 1 - suffix])) {
            suffix++;
        }

        OpBuilder ops = new OpBuilder();
        for (int i = 0; i < prefix; i++) {
            if (contentChanged(oldRows.get(i), newRows.get(i))) {
                ops.update(i);
            }
        }

        int oldMiddle = n - prefix - suffix;
        int newMiddle = m - prefix - suffix;
        if (oldMiddle == 0) {
            ops.insert(prefix, newMiddle);
        } else if (newMiddle == 0) {
            ops.remove(prefix, oldMiddle);
        } else {
            byte[] script = myers(a, prefix, oldMiddle, b, prefix, newMiddle);
            if (script == null) {
                ops.remove(prefix, oldMiddle);
                ops.insert(prefix, newMiddle);
            } else {
                int oldIndex = prefix;
                int newIndex = prefix;
                int position = prefix;
                for (byte step : script) {
                    if (step == EQUAL) {
                        if (contentChanged(oldRows.get(oldIndex), newRows.get(newIndex))) {
                            ops.update(position);
                        }
                        oldIndex++;
                        newIndex++;
                        position++;
                    } else if (step == DELETE) {
                        ops.remove(position, 1);
                        oldIndex++;
                    } else {
                        ops.insert(position, 1);
                        newIndex++;
                        position++;
                    }
                }
            }
        }

        // Suffix rows: the evolving list already equals the new list before them,
        // so their new positions are final sequential coordinates.
        for (int j = 0; j < suffix; j++) {
            int oldIndex = n - suffix + j;
            int newIndex = m - suffix + j;
            if (contentChanged(oldRows.get(oldIndex), newRows.get(newIndex))) {
                ops.update(newIndex);
            }
        }
        return ChangeSet.of(ops.build());
    }

    private static boolean contentChanged(ListSnapshot.Row<?> oldRow, ListSnapshot.Row<?> newRow) {
        return oldRow.viewType() != newRow.viewType() || !Objects.equals(oldRow.data(), newRow.data());
    }

    private static Token[] tokens(List<ListSnapshot.Row<?>> rows, ItemIdentity identity) {
        Token[] tokens = new Token[rows.size()];
        for (int i = 0; i < tokens.length; i++) {
            ListSnapshot.Row<?> row = rows.get(i);
            PositionKind kind = row.mapping().kind();
            Object itemIdentity = kind == PositionKind.STANDALONE || kind == PositionKind.GROUP_ITEM
                    ? identity.identityOf(row.data())
                    : null;
            tokens[i] = new Token(kind, row.mapping().groupKey(), itemIdentity);
        }
        return tokens;
    }

    /**
     * Myers greedy diff of a[aOff, aOff+n) vs b[bOff, bOff+m), both non-empty.
     *
     * @return the forward edit script, or null when the edit distance exceeds
     *         {@link #MAX_EDIT_DISTANCE}.
     */
    private static byte[] myers(Token[] a, int aOff, int n, Token[] b, int bOff, int m) {
        int max = Math.min(n + m, MAX_EDIT_DISTANCE);
        int offset = max + 1;
        int[] v = new int[2 * offset + 1];
        // trace.get(d) = state of v BEFORE step d, windowed to diagonals [-d-1, d+1]
        List<int[]> trace = new ArrayList<>();
        for (int d = 0; d <= max; d++) {
            int[] window = new int[2 * d + 3];
            System.arraycopy(v, offset - d - 1, window, 0, window.length);
            trace.add(window);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1]; // down: insert b[y]
                } else {
                    x = v[offset + k - 1] + 1; // right: delete a[x - 1]
                }
                int y = x - k;
                while (x < n && y < m && a[aOff + x].equals(b[bOff + y])) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    return backtrack(trace, n, m);
                }
            }
        }
        return null;
    }

    private static byte[] backtrack(List<int[]> trace, int n, int m) {
        byte[] reversed = new byte[n + m];
        int length = 0;
        int equal = 0;
        int deleted = 0;
        int inserted = 0;
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] window = trace.get(d);
            int base = d + 1;
            int k = x - y;
            int prevK = (k == -d || (k != d && window[k - 1 + base] < window[k + 1 + base]))
                    ? k + 1
                    : k - 1;
            int prevX = window[prevK + base];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                reversed[length++] = EQUAL;
                equal++;
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) {
                    reversed[length++] = INSERT;
                    inserted++;
                } else {
                    reversed[length++] = DELETE;
                    deleted++;
                }
            }
            x = prevX;
            y = prevY;
        }
        if (equal + deleted != n || equal + inserted != m) {
            return null; // defensive: never publish a script that does not cover both ranges
        }
        byte[] script = new byte[length];
        for (int i = 0; i < length; i++) {
            script[i] = reversed[length - 1 - i];
        }
        return script;
    }

    private static final class Token {
        private final PositionKind kind;
        private final GroupKey groupKey;
        private final Object identity;
        private final int hash;

        Token(PositionKind kind, GroupKey groupKey, Object identity) {
            this.kind = kind;
            this.groupKey = groupKey;
            this.identity = identity;
            this.hash = Objects.hash(kind, groupKey, identity);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Token)) {
                return false;
            }
            Token other = (Token) o;
            return hash == other.hash
                    && kind == other.kind
                    && Objects.equals(groupKey, other.groupKey)
                    && Objects.equals(identity, other.identity);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** Coalesces adjacent single-row edits into range operations. */
    private static final class OpBuilder {
        private final List<ChangeSet.Operation> operations = new ArrayList<>();
        private ChangeSet.Type type;
        private int start;
        private int count;

        void update(int position) {
            if (type == ChangeSet.Type.UPDATE && start + count == position) {
                count++;
                return;
            }
            begin(ChangeSet.Type.UPDATE, position, 1);
        }

        void insert(int position, int itemCount) {
            if (itemCount <= 0) {
                return;
            }
            if (type == ChangeSet.Type.INSERT && start + count == position) {
                count += itemCount;
                return;
            }
            begin(ChangeSet.Type.INSERT, position, itemCount);
        }

        void remove(int position, int itemCount) {
            if (itemCount <= 0) {
                return;
            }
            if (type == ChangeSet.Type.REMOVE && start == position) {
                count += itemCount;
                return;
            }
            begin(ChangeSet.Type.REMOVE, position, itemCount);
        }

        private void begin(ChangeSet.Type newType, int position, int itemCount) {
            flush();
            type = newType;
            start = position;
            count = itemCount;
        }

        private void flush() {
            if (type != null) {
                operations.add(ChangeSet.Operation.of(type, start, count, -1));
                type = null;
            }
        }

        List<ChangeSet.Operation> build() {
            flush();
            return operations;
        }
    }
}
