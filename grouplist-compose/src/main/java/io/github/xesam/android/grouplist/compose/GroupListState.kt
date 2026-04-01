package io.github.xesam.android.grouplist.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xesam.android.grouplist.vnext.core.CoreCommand
import io.github.xesam.android.grouplist.vnext.core.CoreController
import io.github.xesam.android.grouplist.vnext.core.CoreRow
import io.github.xesam.android.grouplist.vnext.core.DefaultCoreController
import io.github.xesam.android.grouplist.vnext.shared.ChangeSet
import io.github.xesam.android.grouplist.vnext.shared.GroupKey
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot

/**
 * Observable state wrapper around a [CoreController] for Compose consumption.
 *
 * The compose consumption protocol delegates to the same single-threaded
 * contract as the core layer (see the vnext package-info.java): every
 * [submit] / [dispatch] must run on the recomposition-owning thread
 * (typically the Android main thread).
 *
 * [ChangeSet] is intentionally NOT consumed here: Compose derives the diff
 * itself from the published [snapshot]; the pending change set is drained
 * only to keep the core protocol consistent (hasPendingChanges == false).
 *
 * Ownership contract: this wrapper OWNS the controller it wraps. The
 * controller is deliberately not exposed — reading the snapshot via
 * [snapshot] keeps `mutableStateOf` invalidation the single writes funnel,
 * and attaching a second consumer to the same controller (e.g. a
 * RecyclerView adapter draining takeChangeSet) would fight over the same
 * pending-delta channel. One controller, one consumption channel.
 *
 * No-op guard: a [dispatch] that changes nothing visible keeps the SAME
 * snapshot instance, so `mutableStateOf` does not invalidate and LazyColumn
 * recomposes nothing. Prefer calling [submit]/[dispatch] from event or
 * effect callbacks, not mid-composition, to avoid double frames.
 */
class GroupListState(
    private val controller: CoreController = DefaultCoreController(),
) {

    private var current by mutableStateOf(controller.snapshot())

    /** The latest snapshot; reading it inside composition subscribes to changes. */
    val snapshot: ListSnapshot get() = current

    fun submit(rows: List<CoreRow>) {
        controller.submit(rows)
        refresh()
    }

    fun dispatch(command: CoreCommand) {
        controller.dispatch(command)
        refresh()
    }

    fun toggleGroup(groupKey: GroupKey) {
        dispatch(CoreCommand.ToggleGroup(groupKey))
    }

    fun setGroupExpanded(groupKey: GroupKey, expanded: Boolean) {
        dispatch(CoreCommand.SetGroupExpanded(groupKey, expanded))
    }

    fun replaceChildren(groupKey: GroupKey, children: List<Any?>) {
        dispatch(CoreCommand.ReplaceChildren(groupKey, children))
    }

    fun reconcileRows(rows: List<CoreRow>) {
        dispatch(CoreCommand.ReconcileRows(rows))
    }

    fun isGroupExpanded(groupKey: GroupKey): Boolean = controller.isGroupExpanded(groupKey)

    private fun refresh() {
        val latest = controller.snapshot()
        // Drain + republish ONLY when the controller actually published something:
        // controller.takeChangeSet() re-wraps the snapshot unconditionally, so calling
        // it on every dispatch would swap in a fresh (content-identical) wrapper for
        // every fail-silent no-op and invalidate the whole LazyColumn.
        if (latest !== current) {
            controller.takeChangeSet() // drain; Compose does not consume positional diffs
            current = controller.snapshot()
        }
    }
}
