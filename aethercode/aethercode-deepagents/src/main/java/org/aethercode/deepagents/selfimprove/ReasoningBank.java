package org.aethercode.deepagents.selfimprove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R241.2 + R241.3 + R243.1 (O-3): the persistent store of
 * {@link ReasoningUnit}s, indexed by
 * {@link ReasoningUnit#taskKind() taskKind}. Modelled on the
 * <em>ReasoningBank</em> pattern (paper 1 §4.2.2, 2025) —
 * "success and failure are both abstracted into reusable
 * reasoning units, enabling test-time scaling and robust
 * learning".
 *
 * <h2>R241.2 surface (unchanged)</h2>
 *
 * <p>{@code new ReasoningBank()} gives you the same
 * in-memory, no-cap bank R241.2 shipped. All R241.2
 * methods keep their signatures and behaviour.
 *
 * <h2>R241.3 collaborators (unchanged)</h2>
 *
 * <ul>
 *   <li>{@link BankStorage} — write-through persistence.</li>
 *   <li>{@link UtilityDecay} — recall-time decay policy.</li>
 * </ul>
 *
 * <h2>R243.1 collaborator</h2>
 *
 * <ul>
 *   <li>{@link BankGrowthPolicy} — bound on the bank's size.
 *       The default is {@link NoGrowthCap} (R241.2 behaviour
 *       preserved). Set it to {@link UtilityBasedEviction}
 *       or {@link LruEviction} to cap the bank at a
 *       configurable maximum.</li>
 * </ul>
 *
 * <h2>LRU bookkeeping</h2>
 *
 * <p>{@link LruEviction} needs a "last touched at" timestamp
 * per unit. To keep the on-disk JSON schema unchanged, the
 * map lives only in memory and is reset on cold-start;
 * {@link LruEviction} falls back to {@code createdAt} for
 * units that have no entry in the LRU index.
 */
public class ReasoningBank {

    private static final Logger LOG = LoggerFactory.getLogger(ReasoningBank.class);

    /** Case-insensitive "key: value" matcher used by {@link #parse}. */
    private static final Pattern KV_LINE = Pattern.compile(
            "^\\s*(?:(error[_-]?pattern|fix[_-]?strategy|example|reason|why|fix|error))\\s*[:=]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private final BankStorage storage;
    private final UtilityDecay decay;
    private final BankGrowthPolicy growthPolicy;
    private final Map<String, List<ReasoningUnit>> byKind = new ConcurrentHashMap<>();
    private final Map<String, ReasoningUnit> byId = new ConcurrentHashMap<>();
    /** In-memory LRU index for {@link LruEviction}. Not
     *  persisted — see class javadoc. */
    private final Map<String, Instant> lastTouchedAt = new ConcurrentHashMap<>();
    private final List<ReasoningBankListener> listeners = new CopyOnWriteArrayList<>();

    /** R241.2 default: in-memory storage, no decay, no cap. */
    public ReasoningBank() {
        this(new InMemoryBankStorage(), UtilityDecay.NO_DECAY, NoGrowthCap.INSTANCE);
    }

    /** R241.3: arbitrary storage, no decay, no cap. */
    public ReasoningBank(BankStorage storage) {
        this(storage, UtilityDecay.NO_DECAY, NoGrowthCap.INSTANCE);
    }

    /** R241.3: arbitrary storage + decay policy, no cap. */
    public ReasoningBank(BankStorage storage, UtilityDecay decay) {
        this(storage, decay, NoGrowthCap.INSTANCE);
    }

    /** R243.1: full configuration. */
    public ReasoningBank(BankStorage storage, UtilityDecay decay, BankGrowthPolicy growthPolicy) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.decay = Objects.requireNonNull(decay, "decay");
        this.growthPolicy = Objects.requireNonNull(growthPolicy, "growthPolicy");
        for (ReasoningUnit u : storage.loadAll()) {
            byId.put(u.id(), u);
            byKind.computeIfAbsent(u.taskKind(), k -> new CopyOnWriteArrayList<>())
                    .add(u);
        }
    }

    public static ReasoningBank withFileStorage(Path dir) {
        return withFileStorage(dir, UtilityDecay.NO_DECAY, NoGrowthCap.INSTANCE);
    }

    public static ReasoningBank withFileStorage(Path dir, UtilityDecay decay) {
        return withFileStorage(dir, decay, NoGrowthCap.INSTANCE);
    }

    public static ReasoningBank withFileStorage(Path dir, UtilityDecay decay, BankGrowthPolicy growthPolicy) {
        return new ReasoningBank(new JsonFileBankStorage(dir), decay, growthPolicy);
    }

    public BankStorage storage() { return storage; }
    public UtilityDecay decay() { return decay; }
    public BankGrowthPolicy growthPolicy() { return growthPolicy; }

    /**
     * Add a new unit. Returns the unit (so callers can capture
     * the assigned id). The unit is written through to storage
     * before this method returns. If the bank now exceeds the
     * configured growth cap, eviction runs synchronously.
     */
    public ReasoningUnit add(ReasoningUnit unit) {
        Objects.requireNonNull(unit, "unit");
        byId.put(unit.id(), unit);
        byKind.computeIfAbsent(unit.taskKind(), k -> new CopyOnWriteArrayList<>())
                .add(unit);
        lastTouchedAt.put(unit.id(), Instant.now());
        storage.save(unit);
        fireAdded(unit);
        evictIfNeeded();
        return unit;
    }

    public Optional<ReasoningUnit> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** Number of stored units across all kinds. */
    public int size() {
        return byId.size();
    }

    /**
     * R243.3 (O-3): every unit in the bank, in arbitrary
     * order. The returned list is a defensive copy; callers
     * may sort, filter, or aggregate it freely. For
     * rank-ordered access by kind, use
     * {@link #recallFor(String, int, Instant)} instead.
     */
    public List<ReasoningUnit> all() {
        return List.copyOf(byId.values());
    }

    /** Number of stored units for a given task kind. */
    public int sizeForKind(String taskKind) {
        if (taskKind == null) return 0;
        return byKind.getOrDefault(taskKind, List.of()).size();
    }

    public java.util.Set<String> kinds() {
        return java.util.Set.copyOf(byKind.keySet());
    }

    public List<ReasoningUnit> recallFor(String taskKind, int n) {
        return recallFor(taskKind, n, Instant.now());
    }

    public List<ReasoningUnit> recallFor(String taskKind) {
        return recallFor(taskKind, 3);
    }

    public List<ReasoningUnit> recallFor(String taskKind, int n, Instant now) {
        if (taskKind == null || n <= 0) return List.of();
        List<ReasoningUnit> all = byKind.get(taskKind);
        if (all == null || all.isEmpty()) return List.of();
        Instant t = now == null ? Instant.now() : now;
        List<ReasoningUnit> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingDouble((ReasoningUnit u) -> decay.effectiveUtility(u, t)).reversed()
                .thenComparing(Comparator.comparingLong(ReasoningUnit::uses).reversed())
                .thenComparing(Comparator.comparing(ReasoningUnit::createdAt).reversed()));
        return Collections.unmodifiableList(sorted.subList(0, Math.min(n, sorted.size())));
    }

    /**
     * Bump a unit's {@code uses} counter and increase its
     * utility score (capped at 1.0). Returns the updated
     * unit, or empty if the id is unknown. The updated unit
     * is written through to storage, and the unit's
     * {@code lastTouchedAt} is refreshed for
     * {@link LruEviction}.
     */
    public Optional<ReasoningUnit> touch(String id) {
        ReasoningUnit u = byId.get(id);
        if (u == null) return Optional.empty();
        double newUtility = Math.min(1.0, u.utility() + 0.05);
        long newUses = u.uses() + 1L;
        ReasoningUnit updated = u.withUtility(newUtility).withUses(newUses);
        byId.put(id, updated);
        List<ReasoningUnit> list = byKind.get(updated.taskKind());
        if (list != null) {
            list.replaceAll(cur -> cur.id().equals(id) ? updated : cur);
        }
        lastTouchedAt.put(id, Instant.now());
        storage.save(updated);
        return Optional.of(updated);
    }

    /**
     * R244.1 (O-6): record a binary self-eval outcome for a
     * unit. {@code true} increments the unit's
     * {@link ReasoningUnit#okCount()}, {@code false}
     * increments {@link ReasoningUnit#notOkCount()}. The
     * updated unit is written through to storage and
     * returned; empty if the id is unknown.
     *
     * <p>Outcome counts feed into
     * {@link ReasoningUnit#confidence()}, which the
     * recall ranking uses to weight effective utility.
     * A unit with high utility but zero observations has
     * Laplace-smoothed confidence ~0 and ranks lower
     * than a unit with the same utility that has been
     * observed three times as "ok".</p>
     */
    public Optional<ReasoningUnit> recordOutcome(String id, boolean ok) {
        ReasoningUnit u = byId.get(id);
        if (u == null) return Optional.empty();
        ReasoningUnit updated = u.withOutcome(ok);
        byId.put(id, updated);
        List<ReasoningUnit> list = byKind.get(updated.taskKind());
        if (list != null) {
            list.replaceAll(cur -> cur.id().equals(id) ? updated : cur);
        }
        storage.save(updated);
        return Optional.of(updated);
    }

    /** R241.3: rewrite the in-memory utility of every unit
     *  to the decayed value at {@code now}, and write through
     *  to storage. */
    public int decayPass(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        int changed = 0;
        for (String id : List.copyOf(byId.keySet())) {
            ReasoningUnit u = byId.get(id);
            if (u == null) continue;
            double base = u.utility();
            double eff = decay.effectiveUtility(u, t);
            if (eff < base - 1e-9) {
                ReasoningUnit updated = u.withUtility(eff);
                byId.put(id, updated);
                List<ReasoningUnit> list = byKind.get(updated.taskKind());
                if (list != null) {
                    list.replaceAll(cur -> cur.id().equals(id) ? updated : cur);
                }
                storage.save(updated);
                changed++;
            }
        }
        if (changed > 0) LOG.debug("decay pass: rewrote {} units", changed);
        return changed;
    }

    /**
     * R243.1: ask the configured {@link BankGrowthPolicy}
     * which units (if any) should be removed right now, and
     * remove them. Returns the number of units evicted. A
     * no-op for {@link NoGrowthCap}; idempotent across
     * calls when the bank is already at or below the cap.
     */
    public int evictIfNeeded() {
        if (growthPolicy instanceof NoGrowthCap) {
            return 0;
        }
        int currentSize = byId.size();
        if (currentSize == 0) return 0;
        // Build the snapshot once; LruEviction has an
        // overload that accepts the live lastTouchedAt
        // map, UtilityBasedEviction ignores it.
        List<ReasoningUnit> snapshot = new ArrayList<>(byId.values());
        List<ReasoningUnit> toEvict;
        if (growthPolicy instanceof LruEviction lru) {
            toEvict = lru.selectEvictions(currentSize, snapshot, lastTouchedAt);
        } else {
            toEvict = growthPolicy.selectEvictions(currentSize, snapshot, Instant.now());
        }
        if (toEvict.isEmpty()) return 0;
        int removed = 0;
        for (ReasoningUnit u : toEvict) {
            if (removeInternal(u.id())) {
                storage.remove(u.id());
                removed++;
            }
        }
        if (removed > 0) {
            LOG.debug("evict: removed {} units (cap exceeded by {})",
                    removed, currentSize - capSizeOrZero());
        }
        return removed;
    }

    private int capSizeOrZero() {
        if (growthPolicy instanceof UtilityBasedEviction u) return u.maxUnits();
        if (growthPolicy instanceof LruEviction l) return l.maxUnits();
        return 0;
    }

    /** Test seam + internal use: remove from in-memory
     *  indices without touching storage. */
    private boolean removeInternal(String id) {
        ReasoningUnit removed = byId.remove(id);
        if (removed == null) return false;
        lastTouchedAt.remove(id);
        List<ReasoningUnit> list = byKind.get(removed.taskKind());
        if (list != null) {
            list.removeIf(cur -> cur.id().equals(id));
            if (list.isEmpty()) byKind.remove(removed.taskKind());
        }
        return true;
    }

    /** Test seam: wipe all units. Storage is left intact. */
    public void clear() {
        byKind.clear();
        byId.clear();
        lastTouchedAt.clear();
        LOG.debug("reasoning bank cleared");
    }

    public ReasoningUnit parse(String taskKind, String response) {
        Objects.requireNonNull(taskKind, "taskKind");
        Objects.requireNonNull(response, "response");
        String err = "";
        String fix = "";
        String example = "";
        int matched = 0;
        for (String line : response.split("\\r?\\n")) {
            Matcher m = KV_LINE.matcher(line);
            if (m.matches()) {
                String key = m.group(1).toLowerCase();
                String value = m.group(2).trim();
                if (key.equals("error") || key.startsWith("error") || key.equals("reason") || key.equals("why")) {
                    err = value; matched++;
                } else if (key.startsWith("fix")) {
                    fix = value; matched++;
                } else if (key.equals("example")) {
                    example = truncate(value, 240);
                }
            }
        }
        if (matched == 0) {
            fix = truncate(response.trim(), 240);
        }
        if (err.isEmpty()) {
            err = "(unspecified)";
        }
        if (fix.isEmpty()) {
            fix = "(unspecified)";
        }
        return add(ReasoningUnit.of(taskKind, err, fix, example));
    }

    /** Visible for tests: render the bank as a deterministic map. */
    public Map<String, Integer> histogram() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : byKind.keySet()) {
            out.put(k, byKind.get(k).size());
        }
        return out;
    }

    // -----------------------------------------------------------------
    //  Listeners
    // -----------------------------------------------------------------

    public interface ReasoningBankListener {
        default void onAdded(ReasoningUnit unit) {}
    }

    public void addListener(ReasoningBankListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeListener(ReasoningBankListener listener) {
        return listeners.remove(listener);
    }

    private void fireAdded(ReasoningUnit u) {
        for (ReasoningBankListener l : listeners) {
            try { l.onAdded(u); }
            catch (RuntimeException re) { /* listener bugs must not corrupt */ }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
