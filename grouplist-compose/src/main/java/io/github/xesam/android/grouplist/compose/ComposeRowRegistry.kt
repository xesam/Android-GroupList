package io.github.xesam.android.grouplist.compose

import androidx.compose.runtime.Composable
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot

/**
 * A composable slot rendering one snapshot row. Declared as a fun interface with a
 * @Composable method so the compiler wraps call-site lambdas into ComposableLambdaImpl
 * legally (plain `@Composable` function types cannot be erased/cast to Function1).
 */
fun interface ComposeRowSlot {
    @Composable
    fun Content(row: ListSnapshot.Row<*>)
}

/**
 * Compose counterpart of the engine-layer [io.github.xesam.android.grouplist.vnext.engine.BinderRegistry]:
 * maps a [ListSnapshot.Row.viewType] to the composable that renders it.
 *
 * The core layer stays Compose-unaware: this registry lives entirely in the
 * compose module and only sees the core's numeric viewType.
 *
 * Freeze contract: registration must be COMPLETE before the first composition
 * that reads this registry (store it in `remember {}` and register inside the
 * remember block). Reading is tracked with a [frozen] flag: any `register`
 * AFTER the first [slotFor] lookup fails fast instead of silently affecting
 * only the slots composed afterwards (which would surface as sporadic layout
 * mismatch, not as a crash). Re-registering a viewType before freeze replaces
 * the previous slot. It is intentionally NOT annotated `@Immutable` because the
 * backing map is mutable — the class relies on the freeze discipline above
 * rather than on compiler-verified immutability.
 */
class ComposeRowRegistry {

    private val slots = HashMap<Int, ComposeRowSlot>()
    private var frozen = false

    fun <T> register(viewType: Int, slot: @Composable (ListSnapshot.Row<T>) -> Unit) {
        require(viewType >= 0) { "viewType must not be negative" }
        check(!frozen) {
            "ComposeRowRegistry is frozen: register(viewType=$viewType) called after " +
                "the first slot lookup. Register all slots inside the remember {} block " +
                "before the first composition reads the registry."
        }
        slots[viewType] = ComposeRowSlot { row ->
            @Suppress("UNCHECKED_CAST")
            slot(row as ListSnapshot.Row<T>)
        }
    }

    internal sealed interface SlotLookup {
        data class Absent(val viewType: Int) : SlotLookup
        data class Present(val slot: ComposeRowSlot) : SlotLookup
    }

    internal fun slotFor(viewType: Int): SlotLookup {
        frozen = true
        return when (val slot = slots[viewType]) {
            null -> SlotLookup.Absent(viewType)
            else -> SlotLookup.Present(slot)
        }
    }
}
