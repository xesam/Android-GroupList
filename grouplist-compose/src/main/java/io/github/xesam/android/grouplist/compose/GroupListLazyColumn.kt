package io.github.xesam.android.grouplist.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot
import io.github.xesam.android.grouplist.vnext.shared.PositionKind

/**
 * Renders a [GroupListState] as a LazyColumn.
 *
 * Design notes (deliberately different from the RecyclerView engine):
 * - Items come straight from the snapshot; LazyColumn derives the diff itself,
 *   so the core's ChangeSet plays no role here.
 * - Item [key] comes from [composeKeys]: group rows key on (kind, groupKey,
 *   childIndex); standalone rows key on their standalone slot ORDINAL — the
 *   Nth standalone row in submit order. The ordinal is stable across expansion
 *   and visibility changes (standalones are never hidden/collapsed — same
 *   rationale as EngineCommand.UpdateStandalone's index window) and survives
 *   in-place content updates, so a core UPDATE recomposes instead of
 *   remove+insert. The previous identityHashCode-based key could collide
 *   (duplicate keys crash LazyColumn) and degraded every content update to a
 *   structural swap.
 * - [ListSnapshot.Row.viewType] maps to LazyColumn's contentType (node reuse
 *   pools) AND to the [ComposeRowRegistry] slot selection.
 * - Footer vs header keys are kind-prefixed, so one group's header and footer
 *   never collide; a [CoreRow.Group.children] count change only adds/removes
 *   the TAIL child keys (kept childIndex keys recompose in place).
 * - [itemAnimation] toggles placement-only `Modifier.animateItem()` for expand/collapse
 *   moves. Fade in/out specs are explicitly null: with the default specs every newly
 *   composed item entering the viewport during scrolling fades in, which degrades
 *   scroll performance badly — RecyclerView's diff animations never affect scrolling.
 */
@Composable
fun GroupListLazyColumn(
    state: GroupListState,
    registry: ComposeRowRegistry,
    modifier: Modifier = Modifier,
    itemAnimation: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    GroupListLazyColumn(state.snapshot, registry, modifier, itemAnimation, contentPadding)
}

/**
 * Snapshot-driven variant for consumers that own the production loop
 * themselves (e.g. an existing ViewModel publishing ListSnapshots).
 */
@Composable
fun GroupListLazyColumn(
    snapshot: ListSnapshot,
    registry: ComposeRowRegistry,
    modifier: Modifier = Modifier,
    itemAnimation: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // ListSnapshot is reference invalidated (core publishes a new instance on
    // real change, keeps it on no-op dispatches — see GroupListState). Referential
    // snapshot equality is therefore the correct remember key, and it shields the
    // O(n) key build from recompositions driven by other unstable inputs.
    val keys = remember(snapshot) { snapshot.composeKeys() }
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        itemsIndexed(
            items = snapshot.rows(),
            key = { index, _ -> keys[index] },
            contentType = { _, row -> row.viewType() },
        ) { _, row ->
            when (val lookup = registry.slotFor(row.viewType())) {
                is ComposeRowRegistry.SlotLookup.Present ->
                    if (itemAnimation) {
                        // The modifier must reach the real content, not only the
                        // missing-slot placeholder. Slots don't take a modifier param,
                        // so wrap in a Box only when animation is on.
                        Box(modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                        )) {
                            lookup.slot.Content(row)
                        }
                    } else {
                        lookup.slot.Content(row)
                    }

                is ComposeRowRegistry.SlotLookup.Absent ->
                    BasicText(
                        text = "Missing Compose slot for viewType=${lookup.viewType}",
                    )
            }
        }
    }
}

/**
 * Stable, collision-free LazyColumn keys for one snapshot: group rows key on
 * (kind, groupKey, childIndex); standalone rows key on their ordinal among
 * standalone rows. Internal (not private) for unit-testing the stability
 * contracts directly.
 */
internal fun ListSnapshot.composeKeys(): List<String> {
    var standaloneOrdinal = 0
    return rows().map { row ->
        val mapping = row.mapping()
        when (mapping.kind()) {
            PositionKind.STANDALONE -> {
                // Ordinal among standalone rows; equals the slot ordinal in submit
                // order, which survives expansion/visibility/content changes.
                PositionKind.STANDALONE.name + ":" + standaloneOrdinal++
            }

            PositionKind.GROUP_HEADER, PositionKind.GROUP_FOOTER ->
                mapping.kind().name + ":" + (mapping.groupKey()?.value() ?: "null")

            PositionKind.GROUP_ITEM ->
                mapping.kind().name + ":" + (mapping.groupKey()?.value() ?: "null") + ":" + mapping.childIndex()
        }
    }
}
