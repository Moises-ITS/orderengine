package orderengine;

import java.util.List;

// A single-symbol limit order book with price-time priority. Prices are in
// integer ticks. There are two implementations: OrderBook, the pooled engine,
// and BaselineOrderBook, the same algorithm on plain JDK collections, kept so
// the optimizations can be benchmarked and differentially tested.
public interface Book {
    // Returned by the top-of-book queries when that side is empty.
    long NO_PRICE = Long.MIN_VALUE;

    String symbol();

    // Matches against the resting book first; a GTC limit remainder then rests.
    // `id` of 0 means "assign one for me". The returned report may be reused by
    // the next call on this book.
    ExecReport submit(long id, Side side, OrderType type, Tif tif, long price, long qty);

    // Removes a resting order. False if the id is not live.
    boolean cancel(long id);

    // Exchange-style amend: a pure size reduction keeps the order's place in the
    // queue, anything else is a cancel/replace and loses time priority.
    ExecReport amend(long id, long newPrice, long newQty);

    long bestBid();
    long bestAsk();

    // best ask - best bid, or NO_PRICE unless both sides are populated.
    default long spread() {
        final long bid = bestBid();
        final long ask = bestAsk();
        return bid == NO_PRICE || ask == NO_PRICE ? NO_PRICE : ask - bid;
    }

    long qtyAt(Side side, long price);
    boolean contains(long id);
    int openOrders();

    default boolean isEmpty() {
        return openOrders() == 0;
    }

    // Number of populated price levels on a side.
    int levelCount(Side side);

    // Aggregated ladder, best price first.
    List<LevelView> depth(Side side, int maxLevels);

    // The most recent trades, oldest first, up to the tape limit.
    List<Trade> tape();

    void setTapeLimit(int limit);

    BookStats stats();

    void clear();

    // Debug aid: the book must never be crossed once submit() returns, and
    // every cached total and index must agree with the queues.
    boolean invariantsHold();
}
