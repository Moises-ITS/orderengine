package orderengine;

public final class BookStats {
    long ordersAccepted;
    long ordersRejected;
    long trades;
    long volume;    // shares traded
    long notional;  // sum(price * qty), in ticks

    public long ordersAccepted() { return ordersAccepted; }
    public long ordersRejected() { return ordersRejected; }
    public long trades() { return trades; }
    public long volume() { return volume; }
    public long notional() { return notional; }

    void record(long price, long qty) {
        ++trades;
        volume += qty;
        notional += price * qty;
    }

    void add(BookStats other) {
        ordersAccepted += other.ordersAccepted;
        ordersRejected += other.ordersRejected;
        trades += other.trades;
        volume += other.volume;
        notional += other.notional;
    }

    void reset() {
        ordersAccepted = ordersRejected = trades = volume = notional = 0;
    }
}
