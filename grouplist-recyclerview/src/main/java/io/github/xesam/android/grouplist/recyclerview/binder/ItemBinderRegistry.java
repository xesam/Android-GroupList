package io.github.xesam.android.grouplist.recyclerview.binder;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * viewType -> {@link ItemBinder} routing table used by
 * {@link io.github.xesam.android.grouplist.recyclerview.delegate.SnapshotAdapterDelegate}
 * (and thereby by both adapter entry points, facade inheritance and delegate composition).
 *
 * <p>Registering the same viewType again REPLACES the previous binder (last
 * registration wins). The adapters' {@code registerBinder} is the primary
 * registration channel; keep this class public for API-contract testing and
 * for layouts that rebuild an adapter + registry pair from scratch.</p>
 *
 * <p>Deliberately a JDK {@link java.util.LinkedHashMap} rather than
 * {@code SparseArray}: android.util classes are not stubbed in local JVM unit
 * tests, and keeping this registry plain-JDK preserves the module's
 * run-adapter-tests-without-Robolectric property. The bind-path Integer boxing
 * is an accepted cost at this scale.</p>
 */
public final class ItemBinderRegistry {

    private final Map<Integer, ItemBinder> binderByViewType = new LinkedHashMap<>();

    @NonNull
    public ItemBinderRegistry register(int viewType, @NonNull ItemBinder binder) {
        binderByViewType.put(viewType, binder);
        return this;
    }

    @NonNull
    public ItemBinder require(int viewType) {
        ItemBinder binder = binderByViewType.get(viewType);
        if (binder == null) {
            throw new IllegalStateException("No ItemBinder registered for viewType=" + viewType);
        }
        return binder;
    }
}
