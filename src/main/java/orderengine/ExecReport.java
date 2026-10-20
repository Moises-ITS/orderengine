package orderengine;

import java.util.Arrays;

// What the engine tells the client happened to one submission.
//
// Fills are kept in parallel primitive arrays rather than a List<Trade>, so a
// report can be reset and refilled without allocating. OrderBook hands out the
// same instance on every call: a report is valid until the next call on that
// book, and copy() keeps one for longer.
public final class ExecReport {
    long id;
    long filled;
    long remaining;
    boolean resting;    // remainder is live on the book
    boolean cancelled;  // remainder was killed (IOC/market/FOK)
    Reject reject = Reject.NONE;

    private int tradeCount;
    private long[] makerIds = new long[16];
    private long[] prices = new long[16];
    private long[] qtys = new long[16];

    void reset(long orderId) {
        id = orderId;
        filled = 0;
        remaining = 0;
        resting = false;
        cancelled = false;
        reject = Reject.NONE;
        tradeCount = 0;
    }

    void addTrade(long makerId, long price, long qty) {
        if (tradeCount == makerIds.length) {
            final int n = tradeCount * 2;
            makerIds = Arrays.copyOf(makerIds, n);
            prices = Arrays.copyOf(prices, n);
            qtys = Arrays.copyOf(qtys, n);
        }
        makerIds[tradeCount] = makerId;
        prices[tradeCount] = price;
        qtys[tradeCount] = qty;
        ++tradeCount;
        filled += qty;
    }

    public long id() { return id; }
    public long filled() { return filled; }
    public long remaining() { return remaining; }
    public boolean resting() { return resting; }
    public boolean cancelled() { return cancelled; }
    public Reject reject() { return reject; }
    public boolean accepted() { return reject == Reject.NONE; }

    public int tradeCount() { return tradeCount; }
    public long makerId(int i) { return makerIds[check(i)]; }
    public long tradePrice(int i) { return prices[check(i)]; }
    public long tradeQty(int i) { return qtys[check(i)]; }

    private int check(int i) {
        if (i < 0 || i >= tradeCount) {
            throw new IndexOutOfBoundsException("trade " + i + " of " + tradeCount);
        }
        return i;
    }

    public ExecReport copy() {
        final ExecReport c = new ExecReport();
        c.reset(id);
        c.filled = filled;
        c.remaining = remaining;
        c.resting = resting;
        c.cancelled = cancelled;
        c.reject = reject;
        c.tradeCount = tradeCount;
        c.makerIds = Arrays.copyOf(makerIds, Math.max(16, tradeCount));
        c.prices = Arrays.copyOf(prices, Math.max(16, tradeCount));
        c.qtys = Arrays.copyOf(qtys, Math.max(16, tradeCount));
        return c;
    }
}
