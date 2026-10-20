package orderengine;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

// Owns one OrderBook per symbol and times every submission and cancel.
//
// Each book is an independent state machine, which is why real venues shard
// symbols across threads and keep a single writer per book. This prototype runs
// them on the calling thread so a replay is bit-for-bit deterministic.
public final class MatchingEngine {
    private final TreeMap<String, OrderBook> books = new TreeMap<>();
    private final LatencyHistogram latency = new LatencyHistogram();
    private boolean measure = true;
    private long nextId = 1;

    // Creates the book on first use.
    public OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, OrderBook::new);
    }

    public OrderBook find(String symbol) {
        return books.get(symbol);
    }

    // Order ids are handed out engine-wide so an id identifies an order
    // uniquely, whatever symbol it belongs to.
    public long nextOrderId() {
        return nextId++;
    }

    public ExecReport submit(String symbol, long id, Side side, OrderType type, Tif tif, long price, long qty) {
        final OrderBook b = book(symbol);
        if (id == 0) id = nextOrderId();
        if (!measure) return b.submit(id, side, type, tif, price, qty);

        final long start = System.nanoTime();
        final ExecReport r = b.submit(id, side, type, tif, price, qty);
        latency.record(System.nanoTime() - start);
        return r;
    }

    public boolean cancel(String symbol, long id) {
        final OrderBook b = books.get(symbol);
        if (b == null) return false;
        if (!measure) return b.cancel(id);

        final long start = System.nanoTime();
        final boolean ok = b.cancel(id);
        latency.record(System.nanoTime() - start);
        return ok;
    }

    public ExecReport amend(String symbol, long id, long price, long qty) {
        final OrderBook b = books.get(symbol);
        if (b != null) return b.amend(id, price, qty);

        final ExecReport r = new ExecReport();
        r.reset(id);
        r.reject = Reject.UNKNOWN_ORDER;
        return r;
    }

    public List<String> symbols() {
        return new ArrayList<>(books.keySet());
    }

    public LatencyHistogram latency() {
        return latency;
    }

    // Set false around benchmarks where clock reads would dominate the work.
    public void measureLatency(boolean on) {
        measure = on;
    }

    public BookStats totals() {
        final BookStats sum = new BookStats();
        for (OrderBook b : books.values()) sum.add(b.stats());
        return sum;
    }

    public void reset() {
        books.clear();
        latency.reset();
        nextId = 1;
    }
}
