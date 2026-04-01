package io.github.xesam.android.grouplist.vnext.core;

/**
 * Identity of STANDALONE and GROUP_ITEM row data for the keyed full-reconcile channel
 * ({@link CoreCommand.ReplaceRows}). Group headers / footers are always identified by
 * their {@code GroupKey}, so this hook never sees them.
 *
 * <p>Two rows with equal identities (by {@code equals}) are "the same item": when their
 * data differ (by {@code equals}) the diff publishes an in-place UPDATE instead of
 * REMOVE + INSERT. The default identity is the data itself, i.e. a content change of a
 * key-less row shows up as REMOVE + INSERT.
 *
 * <p>Contract: must be a pure function of {@code data}; may return null.
 */
public interface ItemIdentity {

    ItemIdentity DATA_EQUALS = data -> data;

    Object identityOf(Object data);
}
