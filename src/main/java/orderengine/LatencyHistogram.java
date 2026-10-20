package orderengine;

import java.util.Arrays;

// A small HdrHistogram-style latency recorder.
//
// Recording a sample is a shift, a mask and an increment: no allocation, so
// measuring barely perturbs what is being measured. Buckets are log-linear --
// SUB linear slots per power of two -- which keeps relative error under ~1/SUB
// across the whole range instead of the 2x a plain log2 histogram would give.
public final class LatencyHistogram {
    static final int SUB_BITS = 3;  // 8 slots per octave
    static final int SUB = 1 << SUB_BITS;
    static final int BUCKETS = 512; // covers up to ~2^63 ns

    private final long[] buckets = new long[BUCKETS];
    private long count;
    private long total;
    private long min = Long.MAX_VALUE;
    private long max;

    public void record(long ns) {
        if (ns < 0) ns = 0;
        ++buckets[index(ns)];
        ++count;
        total += ns;
        if (ns < min) min = ns;
        if (ns > max) max = ns;
    }

    public long count() { return count; }
    public long min() { return count == 0 ? 0 : min; }
    public long max() { return max; }

    public double mean() {
        return count == 0 ? 0.0 : (double) total / count;
    }

    // Value at the given percentile (0-100), resolved to the bucket midpoint.
    public long percentile(double p) {
        if (count == 0) return 0;
        p = Math.max(0.0, Math.min(100.0, p));

        final long target = (long) ((p / 100.0) * count + 0.5);
        long seen = 0;
        for (int i = 0; i < BUCKETS; ++i) {
            seen += buckets[i];
            if (seen >= target) return lowerBound(i) + width(i) / 2;
        }
        return max;
    }

    public void reset() {
        Arrays.fill(buckets, 0);
        count = total = max = 0;
        min = Long.MAX_VALUE;
    }

    // "p50 1.2us  p99 8.4us ..." -- one line, ready to print.
    public String summary() {
        if (count == 0) return "no samples";
        return "n=" + count
                + "  min " + formatNs(min())
                + "  mean " + formatNs((long) mean())
                + "  p50 " + formatNs(percentile(50))
                + "  p90 " + formatNs(percentile(90))
                + "  p99 " + formatNs(percentile(99))
                + "  p99.9 " + formatNs(percentile(99.9))
                + "  max " + formatNs(max());
    }

    // Multi-line table of the populated buckets, for eyeballing the shape.
    public String table() {
        if (count == 0) return "no samples\n";
        final StringBuilder out = new StringBuilder("          latency    count    share\n");
        for (int i = 0; i < BUCKETS; ++i) {
            if (buckets[i] == 0) continue;
            final double share = 100.0 * buckets[i] / count;
            out.append(String.format("%17s  %7d  %5.1f%% %s%n",
                    formatNs(lowerBound(i)), buckets[i], share, "#".repeat((int) (share / 2.0))));
        }
        return out.toString();
    }

    // Small values get their own bucket; above that, the SUB_BITS bits below
    // the leading one select the slot within the octave.
    static int index(long ns) {
        if (ns < SUB) return (int) ns;
        final int exp = 63 - Long.numberOfLeadingZeros(ns);  // floor(log2), >= SUB_BITS
        final int sub = (int) ((ns >>> (exp - SUB_BITS)) & (SUB - 1));
        return ((exp - SUB_BITS + 1) << SUB_BITS) + sub;
    }

    static long lowerBound(int idx) {
        if (idx < SUB) return idx;
        final int octave = (idx >>> SUB_BITS) - 1;
        return ((long) SUB | (idx & (SUB - 1))) << octave;  // implicit top bit
    }

    static long width(int idx) {
        return idx < SUB ? 1 : 1L << ((idx >>> SUB_BITS) - 1);
    }

    // Nanoseconds as "842ns" / "1.24us" / "3.10ms", whichever reads best.
    public static String formatNs(long ns) {
        if (ns < 1_000) return ns + "ns";
        if (ns < 1_000_000) return String.format("%.2fus", ns / 1e3);
        if (ns < 1_000_000_000) return String.format("%.2fms", ns / 1e6);
        return String.format("%.2fs", ns / 1e9);
    }
}
