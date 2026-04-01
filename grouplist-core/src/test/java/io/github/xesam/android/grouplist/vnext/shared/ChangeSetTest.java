package io.github.xesam.android.grouplist.vnext.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Direct contract tests for {@link ChangeSet#andThen(ChangeSet)} — the pending
 * accumulator behind DefaultCoreController's incremental-after-incremental
 * compounding — and for the empty/reload channel semantics it composes with.
 */
public class ChangeSetTest {

    private static ChangeSet.Operation op(ChangeSet.Type type, int positionStart, int itemCount) {
        return ChangeSet.Operation.of(type, positionStart, itemCount, -1);
    }

    @Test
    public void andThen_nullNext_shouldReturnThis() {
        ChangeSet base = ChangeSet.of(Arrays.asList(op(ChangeSet.Type.INSERT, 1, 2)));
        assertSame(base, base.andThen(null));
    }

    @Test
    public void andThen_reloadOnEitherSide_shouldAbsorbEverythingIntoReload() {
        ChangeSet incremental = ChangeSet.of(Arrays.asList(op(ChangeSet.Type.INSERT, 1, 2)));

        assertEquals(ChangeSet.Channel.RELOAD, ChangeSet.reload().andThen(incremental).channel());
        assertEquals(ChangeSet.Channel.RELOAD, incremental.andThen(ChangeSet.reload()).channel());
        assertEquals(ChangeSet.Channel.RELOAD, ChangeSet.reload().andThen(ChangeSet.reload()).channel());
        // the absorbed composition carries no positional operations
        assertTrue(ChangeSet.reload().andThen(incremental).operations().isEmpty());
        assertTrue(incremental.andThen(ChangeSet.reload()).operations().isEmpty());
    }

    @Test
    public void andThen_emptyIncrementalOnEitherSide_shouldReturnTheOther() {
        ChangeSet empty = ChangeSet.empty();
        ChangeSet base = ChangeSet.of(Arrays.asList(op(ChangeSet.Type.REMOVE, 3, 1)));

        // an empty side never wraps the result: composition returns the meaningful side as-is
        assertSame(base, base.andThen(empty));
        assertSame(base, empty.andThen(base));
        assertSame(empty, empty.andThen(empty));
    }

    @Test
    public void andThen_incrementalAfterIncremental_shouldConcatenateInOrder() {
        ChangeSet first = ChangeSet.of(Arrays.asList(
                op(ChangeSet.Type.INSERT, 1, 2),
                op(ChangeSet.Type.UPDATE, 4, 1)
        ));
        ChangeSet second = ChangeSet.of(Arrays.asList(
                op(ChangeSet.Type.REMOVE, 0, 1)
        ));

        ChangeSet combined = first.andThen(second);

        assertEquals(ChangeSet.Channel.INCREMENTAL, combined.channel());
        List<ChangeSet.Operation> ops = combined.operations();
        assertEquals(3, ops.size());
        // sequential-application convention: plain concatenation, base ops first
        assertEquals(ChangeSet.Type.INSERT, ops.get(0).type());
        assertEquals(1, ops.get(0).positionStart());
        assertEquals(2, ops.get(0).itemCount());
        assertEquals(ChangeSet.Type.UPDATE, ops.get(1).type());
        assertEquals(4, ops.get(1).positionStart());
        assertEquals(ChangeSet.Type.REMOVE, ops.get(2).type());
        assertEquals(0, ops.get(2).positionStart());
    }

    @Test
    public void isEmpty_shouldDistinguishChannels() {
        // RELOAD is never "empty": a reload always means a visible full repaint
        assertTrue(ChangeSet.empty().isEmpty());
        assertTrue(ChangeSet.of(Arrays.asList()).isEmpty());
        assertFalse(ChangeSet.reload().isEmpty());
        assertFalse(ChangeSet.of(Arrays.asList(op(ChangeSet.Type.UPDATE, 0, 1))).isEmpty());
    }
}
