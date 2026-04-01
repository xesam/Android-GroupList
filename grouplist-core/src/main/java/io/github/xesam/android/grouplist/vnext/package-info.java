/**
 * Cross-layer public contract notes for the vnext API family
 * ({@code vnext.core} / {@code vnext.engine} / {@code vnext.shared}).
 *
 * <h3>Threading contract (single canonical statement)</h3>
 * All controllers are not thread-safe. {@code submit}, {@code dispatch},
 * {@code snapshot}/{@code hasPendingChanges}/{@code takeChangeSet} (Engine versions
 * delegate to Core) must be called from a single thread — typically the Android
 * main thread. The library additionally provides NO cross-thread memory-visibility
 * guarantee: {@code ListSnapshot} and {@code ChangeSet} are meant for same-thread
 * consumption only.
 *
 * <h3>Failure-policy matrix (public contract)</h3>
 * <ul>
 *   <li>Structural programming errors THROW {@code IllegalArgumentException}: null
 *     command / rows list / rows element, duplicate {@code GroupKey} (submit,
 *     ReconcileRows AND ReplaceRows — the keyed reconcile's never-throw policy
 *     covers only its diff algorithm, not structural misuse), standalone count
 *     drift on ReconcileRows, out-of-range / non-standalone
 *     {@code UpdateStandaloneFlat} position, unknown command type. The one place a
 *     {@code NullPointerException} can come out instead is a {@code CoreRow} factory
 *     rejecting a null field directly, e.g. {@code CoreRow.Group.builder(null)}.
 *     An unmapped model / viewType is NOT in this group: {@code BinderRegistry}
 *     throws {@code IllegalStateException} for it (registry lookup failure, not
 *     argument validation).</li>
 *   <li>Race-tolerant unknown state is FAIL-SILENT (documented per command in the
 *     Command classes): toggle/set on an unknown group key, ReplaceChildren on an
 *     unknown or currently-hidden group (override retained, "hidden = paused"),
 *     UpdateStandalone on an unknown index / data.</li>
 *   <li>After a throwing dispatch the controller keeps its pre-dispatch state —
 *     structural validation happens before any mutation (no half-applied state).</li>
 * </ul>
 *
 * <h3>ChangeSet channel + consumption protocol</h3>
 * {@code Channel.RELOAD} is produced by {@code submit} only (operations always
 * empty; consumers rebuild everything); every precise channel produces
 * {@code Channel.INCREMENTAL} where EMPTY means "nothing visible changed — do not
 * repaint". {@code snapshot()} is a pure non-draining read; {@code takeChangeSet()}
 * is the single draining consumption point.
 *
 * <h3>Key identity contract</h3>
 * {@code GroupKey} values must be stable business-domain identities; see
 * {@link io.github.xesam.android.grouplist.vnext.engine.GroupSchema#keyOf(java.lang.Object)}
 * for the rationale (state-map growth bound).
 */
package io.github.xesam.android.grouplist.vnext;
