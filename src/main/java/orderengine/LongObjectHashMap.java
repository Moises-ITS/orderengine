package orderengine;

import java.util.Arrays;
import java.util.function.Consumer;

// An open-addressing hash map from primitive long keys to objects.
//
// java.util.HashMap<Long, V> boxes every key it is asked about and allocates a
// node per entry; this map does neither. Keys and values live in two flat
// arrays, collisions probe linearly, and remove() shifts the probe chain back
// instead of leaving tombstones, so lookups never slow down as orders churn.
// A null value marks an empty slot, so null values are not allowed.
public final class LongObjectHashMap<V> {
    private static final int MIN_CAPACITY = 16;

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int resizeAt;

    public LongObjectHashMap() {
        this(MIN_CAPACITY);
    }

    // Sized so that `expected` entries fit without a resize.
    public LongObjectHashMap(int expected) {
        allocate(capacityFor(expected));
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        for (int i = slot(key); values[i] != null; i = (i + 1) & mask) {
            if (keys[i] == key) return (V) values[i];
        }
        return null;
    }

    public boolean containsKey(long key) {
        return get(key) != null;
    }

    // Returns the previous value, or null.
    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        if (value == null) throw new NullPointerException("null values are not supported");

        int i = slot(key);
        for (; values[i] != null; i = (i + 1) & mask) {
            if (keys[i] == key) {
                final V old = (V) values[i];
                values[i] = value;
                return old;
            }
        }
        keys[i] = key;
        values[i] = value;
        if (++size > resizeAt) rehash(values.length * 2);
        return null;
    }

    // Returns the removed value, or null.
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        int i = slot(key);
        for (; values[i] != null; i = (i + 1) & mask) {
            if (keys[i] == key) break;
        }
        if (values[i] == null) return null;

        final V old = (V) values[i];
        // Backward-shift deletion: pull later entries of the probe chain into
        // the gap whenever the gap lies between their home slot and where they
        // sit, so every remaining key stays reachable without tombstones.
        int gap = i;
        for (int j = (i + 1) & mask; values[j] != null; j = (j + 1) & mask) {
            final int home = slot(keys[j]);
            if (((j - home) & mask) >= ((j - gap) & mask)) {
                keys[gap] = keys[j];
                values[gap] = values[j];
                gap = j;
            }
        }
        values[gap] = null;
        --size;
        return old;
    }

    // Empties the map but keeps its capacity.
    public void clear() {
        Arrays.fill(values, null);
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public void forEachValue(Consumer<? super V> action) {
        for (Object v : values) {
            if (v != null) action.accept((V) v);
        }
    }

    private int slot(long key) {
        // Consecutive keys stay together in runs of 64. Order ids are mostly
        // sequential and recent orders are the ones being filled and cancelled,
        // so their slots share cache lines instead of costing a miss each. The
        // runs themselves are scattered by a Fibonacci hash, so strided or
        // clustered keys do not pile into one region of the table.
        final long run = (key >>> 6) * 0x9E3779B97F4A7C15L;
        return (int) (((run ^ (run >>> 32)) << 6) | (key & 63)) & mask;
    }

    private static int capacityFor(int expected) {
        final int wanted = Math.max(MIN_CAPACITY, expected * 2);
        return Integer.highestOneBit(wanted - 1) << 1;
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        resizeAt = capacity / 2;  // load factor 0.5 keeps probe chains short
    }

    private void rehash(int capacity) {
        final long[] oldKeys = keys;
        final Object[] oldValues = values;
        allocate(capacity);
        for (int i = 0; i < oldValues.length; ++i) {
            if (oldValues[i] == null) continue;
            int j = slot(oldKeys[i]);
            while (values[j] != null) j = (j + 1) & mask;
            keys[j] = oldKeys[i];
            values[j] = oldValues[i];
        }
    }
}
