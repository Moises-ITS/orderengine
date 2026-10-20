package orderengine.bench;

import orderengine.BaselineOrderBook;
import orderengine.Book;
import orderengine.LatencyHistogram;
import orderengine.OrderBook;
import orderengine.Side;

// The two end-to-end benchmarks behind --bench and --latency. The JMH suite in
// OrderBookBenchmark measures the same flow per operation.
public final class Bench {
    private Bench() {}

    // Both books are pre-sized for the resting orders the flow builds up.
    public static final int EXPECTED_ORDERS = 1 << 19;

    public static Book newBook(String impl) {
        switch (impl) {
            case "pooled": return new OrderBook("BENCH", EXPECTED_ORDERS);
            case "baseline": return new BaselineOrderBook("BENCH", EXPECTED_ORDERS);
            default: throw new IllegalArgumentException("unknown implementation: " + impl
                    + " (expected pooled or baseline)");
        }
    }

    public record Result(String impl, int operations, int submits, int cancels, long trades,
                         long volume, int restingOrders, int restingLevels, double seconds,
                         long targetRate, LatencyHistogram latency) {
        public double opsPerSec() {
            return seconds > 0 ? operations / seconds : 0;
        }
    }

    // Unpaced: replays the flow as fast as the book can take it.
    public static Result throughput(String impl, Flow flow) {
        final Book book = newBook(impl);
        book.setTapeLimit(1_024);  // bounded tape, we only want the counts

        long sink = 0;
        final long start = System.nanoTime();
        for (int i = 0, n = flow.size(); i < n; ++i) sink += flow.apply(book, i);
        final long elapsed = System.nanoTime() - start;
        if (sink < 0) System.out.print("");  // keep the loop observable

        return result(impl, flow, book, elapsed, 0, null);
    }

    // Paced: issues operations at a fixed `rate` per second and records each
    // one's latency from when it was *scheduled* to when it finished, not from
    // when it happened to start. If the book falls behind, the backlog shows up
    // as latency instead of quietly lowering the offered load (coordinated
    // omission), which is what a client of a real engine would see.
    public static Result paced(String impl, Flow flow, long rate) {
        final Book book = newBook(impl);
        book.setTapeLimit(1_024);
        final LatencyHistogram hist = new LatencyHistogram();
        System.gc();  // both books start the clock on a clean heap

        long sink = 0;
        final long start = System.nanoTime();
        for (int i = 0, n = flow.size(); i < n; ++i) {
            final long due = start + i * 1_000_000_000L / rate;
            while (System.nanoTime() < due) Thread.onSpinWait();
            sink += flow.apply(book, i);
            hist.record(System.nanoTime() - due);
        }
        final long elapsed = System.nanoTime() - start;
        if (sink < 0) System.out.print("");

        return result(impl, flow, book, elapsed, rate, hist);
    }

    // Lets the JIT compile the hot path -- and the paced driver loop itself --
    // before anything is measured.
    public static void warmUp(String impl, int ops, long seed) {
        final Flow flow = Flow.generate(ops, seed + 1);
        for (int round = 0; round < 3; ++round) throughput(impl, flow);
        paced(impl, Flow.generate(Math.min(ops, 500_000), seed + 2), 2_000_000);
        System.gc();
    }

    private static Result result(String impl, Flow flow, Book book, long elapsedNs, long rate,
                                 LatencyHistogram hist) {
        return new Result(impl, flow.size(), flow.submits(), flow.cancels(),
                book.stats().trades(), book.stats().volume(), book.openOrders(),
                book.levelCount(Side.BUY) + book.levelCount(Side.SELL),
                elapsedNs / 1e9, rate, hist);
    }

    public static String format(Result r) {
        final StringBuilder out = new StringBuilder();
        out.append(String.format("  book         %s%n", r.impl()));
        out.append(String.format("  operations   %d  (%d submits, %d cancels)%n",
                r.operations(), r.submits(), r.cancels()));
        out.append(String.format("  elapsed      %.3f s%n", r.seconds()));
        if (r.targetRate() > 0) {
            out.append(String.format("  offered      %d ops/sec%n", r.targetRate()));
            out.append(String.format("  achieved     %.0f ops/sec%n", r.opsPerSec()));
        } else {
            out.append(String.format("  throughput   %.0f ops/sec%n", r.opsPerSec()));
        }
        out.append(String.format("  trades       %d%n", r.trades()));
        out.append(String.format("  volume       %d shares%n", r.volume()));
        out.append(String.format("  book left    %d orders across %d levels%n",
                r.restingOrders(), r.restingLevels()));
        if (r.latency() != null) {
            out.append("  latency      ").append(r.latency().summary()).append('\n');
        }
        return out.toString();
    }
}
