package io.github.xesam.android.grouplist.vnext.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Randomized applicability fuzz: any ChangeSet the controller publishes must be
 * applicable — its ordered ops must transform the previously published rows into
 * the new rows. RecyclerView applies ops blindly; a violation crashes it at
 * rebind time ("Inconsistency detected. Invalid view holder adapter position").
 *
 * <p>The walk mirrors what a real page can legally do: submit, toggle / expand /
 * collapse, ReplaceChildren with drifting counts (while visible or hidden),
 * visible-set reconciles (subsets, in contract: no reorder), standalone content
 * updates, and no-op redeliveries. Deterministic seed => reproducible failures.</p>
 */
public class ChangeSetApplicabilityFuzzTest {

    private static final int VT_STANDALONE = 1;
    private static final int VT_ITEM = 3;

    private static final int GROUP_COUNT = 6;
    private static final int STANDALONE_COUNT = 3;
    private static final int STEPS = 4000;
    private static final long SEED = 20261009L;

    @Test
    public void randomCommandWalk_everyStepMustBeApplicable() {
        for (long seed = SEED; seed < SEED + 8; seed++) {
            Random random = new Random(seed);
            walkOnce(random, seed);
        }
    }

    private static final class ApplicabilityFailure extends RuntimeException {
        ApplicabilityFailure(String message) {
            super(message);
        }
    }

    private void walkOnce(Random random, long seed) {
        DefaultCoreController controller = new DefaultCoreController();
        // children mirror: reconcile must present CURRENT children for kept blocks
        // (kept blocks content-identical contract); ReplaceChildren mutates this map.
        List<List<Object>> childrenByKey = new ArrayList<>();
        for (int g = 0; g < GROUP_COUNT; g++) {
            childrenByKey.add(baseChildren("g" + g));
        }
        controller.submit(seedRows(childrenByKey));
        controller.takeChangeSet();
        ListSnapshot displayed = controller.snapshot();
        long displayedVersion = displayed.version();

        for (int step = 0; step < STEPS; step++) {
            dispatchRandom(random, controller, step, childrenByKey);
            ListSnapshot next = controller.snapshot();
            if (next.version() == displayedVersion) {
                // fail-silent no-op: nothing was published, so there is nothing to apply.
                // Compared by version, not by instance: identity stops being a reliable
                // no-op signal as soon as takeChangeSet re-wraps the snapshot (i.e. after
                // the first publish), because `displayed` would then point at the
                // pre-drain instance.
                continue;
            }
            ChangeSet ops = next.changeSet();
            if (ops.channel() == ChangeSet.Channel.INCREMENTAL) {
                // Empty deltas go through the oracle too instead of being skipped: an
                // empty ChangeSet claims nothing visible changed, and a producer that
                // dropped a real delta would otherwise pass silently.
                assertApplicable("seed=" + seed + " step=" + step, displayed, next, ops);
            }
            controller.takeChangeSet();
            displayed = next;
            displayedVersion = next.version();
        }
    }

    private void dispatchRandom(Random random, CoreController controller, int step, List<List<Object>> childrenByKey) {
        int kind = random.nextInt(100);
        if (kind < 34) {
            controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g" + random.nextInt(GROUP_COUNT))));
        } else if (kind < 46) {
            controller.dispatch(new CoreCommand.SetGroupExpanded(
                    GroupKey.of("g" + random.nextInt(GROUP_COUNT)), random.nextBoolean()));
        } else if (kind < 68) {
            int g = random.nextInt(GROUP_COUNT);
            String key = "g" + g;
            List<Object> children = new ArrayList<>();
            int count = random.nextInt(5);
            for (int i = 0; i < count; i++) {
                children.add(key + ":swap" + step + ":" + i);
            }
            childrenByKey.set(g, children);
            controller.dispatch(new CoreCommand.ReplaceChildren(GroupKey.of(key), children));
        } else if (kind < 86) {
            List<CoreRow> rows = new ArrayList<>();
            for (int i = 0; i < STANDALONE_COUNT; i++) {
                rows.add(CoreRow.Standalone.of("s" + i + ":" + step, VT_STANDALONE));
            }
            for (int g = 0; g < GROUP_COUNT; g++) {
                if (random.nextBoolean()) {
                    rows.add(groupOf("g" + g, childrenByKey.get(g)));
                }
            }
            controller.dispatch(new CoreCommand.ReconcileRows(rows));
        } else if (kind < 92) {
            controller.dispatch(new CoreCommand.UpdateStandaloneFlat(
                    random.nextInt(STANDALONE_COUNT), "standalone@" + step));
        } else {
            controller.dispatch(new CoreCommand.ToggleGroup(
                    GroupKey.of("g" + random.nextInt(GROUP_COUNT)))); // double-toggle pressure
        }
    }

    // ---- builders ----

    private static List<CoreRow> seedRows(List<List<Object>> childrenByKey) {
        List<CoreRow> rows = new ArrayList<>();
        for (int i = 0; i < STANDALONE_COUNT; i++) {
            rows.add(CoreRow.Standalone.of("s" + i, VT_STANDALONE));
        }
        for (int g = 0; g < GROUP_COUNT; g++) {
            rows.add(groupOf("g" + g, childrenByKey.get(g)));
        }
        return rows;
    }

    private static List<Object> baseChildren(String key) {
        List<Object> children = new ArrayList<>();
        for (int i = 0; i < 2 + (key.hashCode() & 1); i++) {
            children.add(key + ":base" + i);
        }
        return children;
    }

    private static CoreRow.Group<Object, Object, Object> groupOf(String key, List<Object> children) {
        return CoreRow.Group.<Object, Object, Object>builder(GroupKey.of(key))
                .header(key + ":header")
                .footer(key + ":footer")
                .children(children)
                .initiallyExpanded(false)
                .childViewTypeResolver((childIndex, childData) -> VT_ITEM)
                .build();
    }

    // ---- applicability oracle: leaner variant of ChangeSetApplicabilityTest's
    // (token alignment without the hand test's bounds/move diagnostics) ----

    private static void assertApplicable(String where, ListSnapshot oldSnap, ListSnapshot newSnap, ChangeSet ops) {
        List<Slot> slots = new ArrayList<>();
        for (ListSnapshot.Row<?> row : oldSnap.rows()) {
            slots.add(new Slot(row.data()));
        }
        for (ChangeSet.Operation op : ops.operations()) {
            switch (op.type()) {
                case INSERT:
                    for (int i = 0; i < op.itemCount(); i++) {
                        slots.add(op.positionStart() + i, new Slot(null));
                    }
                    break;
                case REMOVE:
                    slots.subList(op.positionStart(), op.positionStart() + op.itemCount()).clear();
                    break;
                case MOVE:
                    Slot moved = slots.remove(op.positionStart());
                    slots.add(op.toPosition(), moved);
                    break;
                case UPDATE:
                    for (int i = 0; i < op.itemCount(); i++) {
                        int at = op.positionStart() + i;
                        Slot slot = slots.get(at);
                        // UPDATE must cover content that actually changed; otherwise a
                        // spurious / over-wide UPDATE would silently pass (and mask a
                        // real change the producer failed to report).
                        if (slot.token != null
                                && Objects.equals(slot.token, newSnap.rows().get(at).data())) {
                            throw new ApplicabilityFailure(where + ": UPDATE at " + at
                                    + " claims a change but the row is unchanged (" + slot.token + ")");
                        }
                        slot.updated = true;
                    }
                    break;
                default:
                    throw new ApplicabilityFailure(where + ": unexpected op " + op.type());
            }
        }
        if (slots.size() != newSnap.rows().size()) {
            throw new ApplicabilityFailure(where + ": rebuilt count " + slots.size()
                    + " != actual " + newSnap.rows().size());
        }
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.token == null || slot.updated) {
                continue;
            }
            Object actual = newSnap.rows().get(i).data();
            if (!Objects.equals(slot.token, actual)) {
                throw new ApplicabilityFailure(where + ": row " + i + " token mismatch: expected "
                        + slot.token + " got " + actual);
            }
        }
    }

    private static final class Slot {
        final Object token;
        boolean updated;

        Slot(Object token) {
            this.token = token;
        }
    }
}
