package io.github.xesam.android.grouplist.vnext.core;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.xesam.android.grouplist.vnext.shared.ChangeSet;
import io.github.xesam.android.grouplist.vnext.shared.GroupKey;
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot;

/**
 * Consumer-side mirror of the core perf-guard: a ChangeSet is only usable by a
 * positional consumer (RecyclerView) if its ordered operations transform the
 * rows of the BASE snapshot into the rows of the NEW snapshot. RecyclerView
 * applies ops blindly; ops that don't reconstruct the new rows crash it at
 * rebind time with "Inconsistency detected. Invalid view holder adapter position".
 *
 * <p>Oracle: unique data tokens make old rows individually identifiable. Apply
 * the ordered ops to the old row list (INSERT = fresh unknown slots, REMOVE /
 * MOVE = membership bookkeeping), then every surviving old slot must sit exactly
 * on a new row with the SAME token. Over/under-insert and off-by-one coordinates
 * break that alignment. An UPDATE is checked the other way round: it must cover a
 * row whose content genuinely differs from its old token, so a spurious or
 * over-wide UPDATE is caught rather than skipped. Unknown (insert) slots consume
 * whatever remains — counts must balance to the new size.</p>
 *
 * <p>MOVE is modelled for consumer parity ({@link ChangeSet.Type#MOVE} obliges
 * consumers to map it), but no library producer emits it and no fixture below
 * exercises that branch — its coordinates are therefore not notarized here.</p>
 */
public class ChangeSetApplicabilityTest {

    private static final int VT_STANDALONE = 1;
    private static final int VT_ITEM = 3;

    @Test
    public void realFlows_orderedOps_shouldReconstructNewRows() {
        DefaultCoreController controller = new DefaultCoreController();

        List<CoreRow> rows = new ArrayList<>();
        rows.add(CoreRow.Standalone.of("standalone-0", VT_STANDALONE));
        rows.add(group("g-load", "load-header", "load-footer", "load-c0", "load-c1", "load-c2"));
        rows.add(group("g-a", "a-header", "a-footer", "a-c0", "a-c1"));
        rows.add(group("g-b", "b-header", "b-footer", "b-c0"));
        rows.add(group("g-hidden", "hidden-header", "hidden-footer", "hidden-c0"));
        controller.submit(rows);
        checkStep(controller, "initial submit");

        // expand / collapse with loads pending in between (demo auto-load timeline)
        GroupKey gLoad = GroupKey.of("g-load");
        controller.dispatch(new CoreCommand.SetGroupExpanded(gLoad, true));
        checkStep(controller, "expand g-load");
        controller.dispatch(new CoreCommand.ReplaceChildren(gLoad, tokens("g-load", "new-c0", "new-c1", "new-c2")));
        checkStep(controller, "replaceChildren same count");
        controller.dispatch(new CoreCommand.ReplaceChildren(gLoad, tokens("g-load", "new-c0")));
        checkStep(controller, "replaceChildren shrink");
        controller.dispatch(new CoreCommand.ReplaceChildren(gLoad, tokens("g-load", "new-c0", "new-c1", "new-c2", "new-c3", "new-c4")));
        checkStep(controller, "replaceChildren grow");
        // Identical children on an EXPANDED group: the replace still publishes a frame
        // (version advances) but its delta is EMPTY. That is the one legitimately empty
        // incremental frame in these flows, and the step exists so the empty-delta
        // branch of the oracle is actually exercised rather than merely reachable. Both
        // assertions are needed: an empty changeSet alone would also hold for a silent
        // no-op that never published.
        long versionBeforeIdenticalReplace = controller.snapshot().version();
        controller.dispatch(new CoreCommand.ReplaceChildren(gLoad, tokens("g-load", "new-c0", "new-c1", "new-c2", "new-c3", "new-c4")));
        assertTrue("identical children still publish a frame",
                controller.snapshot().version() > versionBeforeIdenticalReplace);
        assertTrue("identical children publish an empty delta frame",
                controller.snapshot().changeSet().isEmpty());
        checkStep(controller, "replaceChildren identical children (empty delta frame)");
        controller.dispatch(new CoreCommand.SetGroupExpanded(gLoad, false));
        checkStep(controller, "collapse g-load");

        // toggles interleaved
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g-a")));
        checkStep(controller, "toggle g-a");
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g-b")));
        checkStep(controller, "toggle g-b");
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g-a")));
        checkStep(controller, "toggle g-a back");

        // standalone content update
        controller.dispatch(new CoreCommand.UpdateStandaloneFlat(0, "standalone-0-changed"));
        checkStep(controller, "updateStandalone");

        // reconcile, IN CONTRACT (no reorder, kept blocks content-identical):
        // drop g-hidden (visibility change), update the standalone in place
        List<CoreRow> reconciled = new ArrayList<>();
        reconciled.add(CoreRow.Standalone.of("standalone-0-changed", VT_STANDALONE));
        reconciled.add(group("g-load", "load-header", "load-footer", "new-c0", "new-c1", "new-c2", "new-c3", "new-c4"));
        reconciled.add(group("g-a", "a-header", "a-footer", "a-c0", "a-c1"));
        reconciled.add(group("g-b", "b-header", "b-footer", "b-c0"));
        controller.dispatch(new CoreCommand.ReconcileRows(reconciled));
        checkStep(controller, "reconcile drop g-hidden");

        // repeat reconcile idempotent
        controller.dispatch(new CoreCommand.ReconcileRows(reconciled));
        checkStep(controller, "reconcile idempotent");

        // re-reveal g-hidden (visibility change revealed again)
        List<CoreRow> revealed = new ArrayList<>(reconciled);
        revealed.add(group("g-hidden", "hidden-header", "hidden-footer", "hidden-c0"));
        controller.dispatch(new CoreCommand.ReconcileRows(revealed));
        checkStep(controller, "reconcile re-reveal g-hidden");

        // and a final batch of toggles to shake animation-coordinate paths
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g-load")));
        checkStep(controller, "toggle g-load");
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g-a")));
        checkStep(controller, "toggle g-a again");
        controller.dispatch(new CoreCommand.ToggleGroup(GroupKey.of("g-b")));
        checkStep(controller, "toggle g-b again");
    }

    /**
     * Mirrors the demo's filter flow: mixed reconcile removing MULTIPLE blocks and
     * revealing MULTIPLE previously-hidden blocks in one dispatch, then back again.
     */
    @Test
    public void filterFlow_mixedMultiRemovalsAndInserts_shouldReconstructNewRows() {
        DefaultCoreController controller = new DefaultCoreController();

        List<CoreRow> rows = new ArrayList<>();
        rows.add(CoreRow.Standalone.of("card", VT_STANDALONE));
        rows.add(group("c1", "c1-header", "c1-footer", "c1-a", "c1-b"));
        rows.add(group("t1", "t1-header", "t1-footer", "t1-a", "t1-b", "t1-c"));
        rows.add(group("c2", "c2-header", "c2-footer", "c2-a"));
        rows.add(group("t2", "t2-header", "t2-footer", "t2-a", "t2-b"));
        rows.add(group("c3", "c3-header", "c3-footer", "c3-a", "c3-b", "c3-c"));
        controller.submit(rows);
        checkStep(controller, "initial submit");
        // expand two groups across the whole list first (mixed expanded states)
        controller.dispatch(new CoreCommand.SetGroupExpanded(GroupKey.of("c1"), true));
        checkStep(controller, "expand c1");
        controller.dispatch(new CoreCommand.SetGroupExpanded(GroupKey.of("t2"), true));
        checkStep(controller, "expand t2");

        // filter -> topic only: remove ALL comment blocks, keep/insert topics
        List<CoreRow> topicOnly = new ArrayList<>();
        topicOnly.add(CoreRow.Standalone.of("card-changed", VT_STANDALONE));
        topicOnly.add(group("t1", "t1-header", "t1-footer", "t1-a", "t1-b", "t1-c"));
        topicOnly.add(group("t2", "t2-header", "t2-footer", "t2-a", "t2-b"));
        controller.dispatch(new CoreCommand.ReconcileRows(topicOnly));
        checkStep(controller, "filter topic-only");

        // filter -> comment only: remove all topic blocks, re-reveal comment blocks
        List<CoreRow> commentOnly = new ArrayList<>();
        commentOnly.add(CoreRow.Standalone.of("card-changed-2", VT_STANDALONE));
        commentOnly.add(group("c1", "c1-header", "c1-footer", "c1-a", "c1-b"));
        commentOnly.add(group("c2", "c2-header", "c2-footer", "c2-a"));
        commentOnly.add(group("c3", "c3-header", "c3-footer", "c3-a", "c3-b", "c3-c"));
        controller.dispatch(new CoreCommand.ReconcileRows(commentOnly));
        checkStep(controller, "filter comment-only");
        // expansion survived hidden periods: c1 was expanded pre-filter, re-revealed expanded
        assertTrue("expansion must survive hidden periods",
                controller.isGroupExpanded(GroupKey.of("c1")));

        // filter -> all: both families visible together, multi insert after multi removal
        List<CoreRow> all = new ArrayList<>();
        all.add(CoreRow.Standalone.of("card-changed-2", VT_STANDALONE));
        all.add(group("c1", "c1-header", "c1-footer", "c1-a", "c1-b"));
        all.add(group("t1", "t1-header", "t1-footer", "t1-a", "t1-b", "t1-c"));
        all.add(group("c2", "c2-header", "c2-footer", "c2-a"));
        all.add(group("t2", "t2-header", "t2-footer", "t2-a", "t2-b"));
        all.add(group("c3", "c3-header", "c3-footer", "c3-a", "c3-b", "c3-c"));
        controller.dispatch(new CoreCommand.ReconcileRows(all));
        checkStep(controller, "filter all-groups");
    }

    // ---- oracle ----

    private ListSnapshot displayed;
    private long displayedVersion;

    private void checkStep(CoreController controller, String step) {
        ListSnapshot next = controller.snapshot();
        if (next.version() == displayedVersion) {
            // no-op dispatch: nothing published, the consumer would re-deliver the
            // current frame and apply nothing — legal step, nothing to check.
            controller.takeChangeSet();
            return;
        }
        if (displayed != null) {
            ChangeSet ops = next.changeSet();
            if (ops.channel() == ChangeSet.Channel.INCREMENTAL) {
                // Empty deltas are checked too, not skipped: "nothing visible changed"
                // is itself a claim, so an empty ChangeSet that hides a real delta must
                // not pass. With zero ops the oracle degenerates to "same size, same
                // token at every position". The step below that re-submits identical
                // children is what actually reaches this branch.
                assertApplicable(step, displayed, next, ops);
            }
        }
        controller.takeChangeSet();
        assertTrue(step + ": version must advance", next.version() > displayedVersion);
        displayed = next;
        displayedVersion = next.version();
    }

    private static void assertApplicable(
            String step, ListSnapshot oldSnap, ListSnapshot newSnap, ChangeSet ops) {
        List<ListSnapshot.Row<?>> newRows = newSnap.rows();
        List<Slot> slots = new ArrayList<>();
        for (ListSnapshot.Row<?> row : oldSnap.rows()) {
            slots.add(new Slot(row.data()));
        }
        for (ChangeSet.Operation op : ops.operations()) {
            switch (op.type()) {
                case INSERT:
                    checkRange(step, slots, op.positionStart(), op.itemCount(), true);
                    for (int i = 0; i < op.itemCount(); i++) {
                        slots.add(op.positionStart() + i, new Slot(null));
                    }
                    break;
                case REMOVE:
                    checkRange(step, slots, op.positionStart(), op.itemCount(), false);
                    slots.subList(op.positionStart(), op.positionStart() + op.itemCount()).clear();
                    break;
                case MOVE:
                    checkMove(step, slots, op.positionStart(), op.toPosition());
                    // notifyItemMoved semantics: from is the pre-move index, to is the FINAL index
                    Slot moved = slots.remove(op.positionStart());
                    slots.add(op.toPosition(), moved);
                    break;
                case UPDATE:
                    checkRange(step, slots, op.positionStart(), op.itemCount(), false);
                    for (int i = 0; i < op.itemCount(); i++) {
                        int at = op.positionStart() + i;
                        Slot slot = slots.get(at);
                        // An UPDATE must mark content that ACTUALLY changed. A slot
                        // still carrying its old token means the op is spurious
                        // (the oracle would otherwise be blind to over-wide /
                        // no-op UPDATEs, which mask a real change or fabricate one).
                        if (slot.token != null) {
                            assertTrue(step + ": UPDATE at " + at
                                            + " claims a content change but the row is unchanged ("
                                            + slot.token + ")",
                                    !tokenEquals(slot.token, newRows.get(at).data()));
                        }
                        slot.updated = true;
                    }
                    break;
                default:
                    fail(step + ": unexpected op type " + op.type());
            }
        }

        assertTrue(step + ": ops must rebuild the new row count (simulated="
                        + slots.size() + " actual=" + newRows.size() + ")",
                slots.size() == newRows.size());
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.token == null || slot.updated) {
                continue;
            }
            assertTrue(step + ": surviving old row at " + i + " must keep its token (expected "
                    + slot.token + " got " + newRows.get(i).data() + ")",
                    tokenEquals(slot.token, newRows.get(i).data()));
        }
    }

    private static final class Slot {
        final Object token;
        boolean updated;

        Slot(Object token) {
            this.token = token;
        }
    }

    private static void checkRange(String step, List<?> slots, int positionStart, int itemCount, boolean allowEnd) {
        int bound = allowEnd ? slots.size() : slots.size() - itemCount;
        assertTrue(step + ": range " + positionStart + "+" + itemCount
                + " out of simulated bounds 0.." + slots.size(),
                positionStart >= 0 && itemCount >= 0 && positionStart <= bound);
    }

    private static void checkMove(String step, List<?> slots, int from, int to) {
        assertTrue(step + ": move " + from + "->" + to + " out of simulated bounds " + slots.size(),
                from >= 0 && to >= 0 && from < slots.size() && to <= slots.size());
    }

    private static boolean tokenEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- builders ----

    private static List<Object> tokens(String group, String... datas) {
        List<Object> out = new ArrayList<>();
        for (String data : datas) {
            out.add(group + ":" + data);
        }
        return out;
    }

    private static CoreRow.Group<Object, Object, Object> group(
            String key, String header, String footer, String... children) {
        List<Object> childrenList = tokens(key, children);
        return CoreRow.Group.<Object, Object, Object>builder(GroupKey.of(key))
                .header(key + ":" + header)
                .footer(key + ":" + footer)
                .children(childrenList)
                .initiallyExpanded(false)
                .childViewTypeResolver((childIndex, childData) -> VT_ITEM)
                .build();
    }
}
