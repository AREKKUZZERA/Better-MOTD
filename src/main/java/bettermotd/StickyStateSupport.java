package bettermotd;

import java.util.Deque;
import java.util.Map;
import java.util.function.BiPredicate;

final class StickyStateSupport {

    private StickyStateSupport() {}

    static <K, V> void cleanupExpired(
            Map<K, V> entries, long nowMs, long ttlMs, int batchLimit, BiPredicate<V, Long> isValid) {
        int checked = 0;
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            if (checked++ >= batchLimit) break;
            if (!isValid.test(entry.getValue(), nowMs - ttlMs)) {
                entries.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Evicts oldest entries from the insertion-order deque until entries.size() <= maxEntries.
     * The deque and map are kept in sync by callers (see createStickyEntry).
     */
    static <K, V> void enforceLimit(Map<K, V> entries, Deque<K> order, int maxEntries, int evictionBatch) {
        if (maxEntries <= 0 || entries.size() <= maxEntries) return;

        int evicted = 0;
        while (entries.size() > maxEntries && evicted < evictionBatch) {
            K key = order.pollFirst();
            if (key == null) break;
            if (entries.remove(key) != null) evicted++;
        }
        // No fallback: if order is exhausted the map will self-correct on next cleanup cycle.
    }
}
