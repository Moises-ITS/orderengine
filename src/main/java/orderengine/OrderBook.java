package orderengine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

// The pooled order book.
//
// Structure, per side (see HalfBook below):
//   levels  : TreeMap<Long, Level>          price-ordered; firstKey() is the best price
//   byPrice : LongObjectHashMap<Level>      price -> level without boxing or a tree walk
//   best    : Level                         cached top of book, read in O(1)
// and for the whole book:
//   index   : LongObjectHashMap<OrderNode>  order id -> node, for O(1) cancel / amend
//   pool    : OrderPool                     order nodes are recycled, not garbage
//
// A cancel is a hash lookup and an unlink from the level's intrusive queue.
// When that empties a level, the level is not removed from the TreeMap there
// and then (an O(log L) operation): it is marked empty and pruned the next time
// it surfaces at the top of the book, or swept when empty levels outnumber live
// ones. That keeps every cancel O(1); the pruning is paid for by the insert
// that created the level. An empty level that receives a new order before it
// is pruned is simply reused, with no tree insert at all.
public final class OrderBook implements Book {
    private static final int DEFAULT_TAPE = 4096;
    private static final int DEFAULT_CAPACITY = 1 << 10;

    private final String symbol;
    private final HalfBook bids = new HalfBook(true);
    private final HalfBook asks = new HalfBook(false);
    private final LongObjectHashMap<OrderNode> index;
    private final OrderPool pool;
    private final ArrayDeque<Level> spareLevels = new ArrayDeque<>();
    private final ExecReport report = new ExecReport();
    private final BookStats stats = new BookStats();

    // The tape is a ring buffer of primitives: recording a trade allocates nothing.
    private long[] tapeSeq, tapeTaker, tapeMaker, tapePrice, tapeQty;
    private boolean[] tapeBuy;
    private int tapeNext, tapeSize;

    private long nextSeq = 1;
    private long nextId = 1;

    public OrderBook() {
        this("TEST");
    }

    public OrderBook(String symbol) {
        this(symbol, DEFAULT_CAPACITY);
    }

    // `expectedOrders` pre-sizes the node pool and the id index, the way a
    // production engine sizes its memory at startup: as long as the book stays
    // under it, the steady state allocates nothing and never rehashes. Going
    // over is still correct, just with a one-off growth step.
    public OrderBook(String symbol, int expectedOrders) {
        this.symbol = symbol;
        this.index = new LongObjectHashMap<>(expectedOrders);
        this.pool = new OrderPool(expectedOrders, 4096);
        setTapeLimit(DEFAULT_TAPE);
    }

    @Override
    public String symbol() {
        return symbol;
    }

    // One side of the book.
    private final class HalfBook {
        final boolean buy;
        final TreeMap<Long, Level> levels;
        final LongObjectHashMap<Level> byPrice = new LongObjectHashMap<>(1024);
        Level best;     // best non-empty level, unless stale
        boolean stale;  // best was emptied and has not been recomputed yet
        int empty;      // empty levels still in the maps, awaiting pruning

        HalfBook(boolean buy) {
            this.buy = buy;
            // Bids sort descending so that firstKey() is the best price on both sides.
            this.levels = buy ? new TreeMap<>(Comparator.reverseOrder()) : new TreeMap<>();
        }

        boolean better(long a, long b) {
            return buy ? a > b : a < b;
        }

        // O(1) unless the cached best was emptied since the last read; then the
        // empty levels at the front are pruned, each one exactly once.
        Level top() {
            if (stale) refresh();
            return best;
        }

        private void refresh() {
            while (!levels.isEmpty()) {
                final Level l = byPrice.get(levels.firstKey());
                if (l.count > 0) {
                    best = l;
                    stale = false;
                    return;
                }
                prune(l);
            }
            best = null;
            stale = false;
        }

        private void prune(Level l) {
            levels.remove(l.key);
            byPrice.remove(l.price);
            --empty;
            spareLevels.push(l);
        }

        // The level at `price`, creating it if needed. Creation is the only
        // step that touches the TreeMap.
        Level levelFor(long price) {
            Level l = byPrice.get(price);
            if (l == null) {
                if (empty >= 256 && empty > levels.size() - empty) sweep();
                l = spareLevels.isEmpty() ? new Level() : spareLevels.pop();
                l.init(price);
                levels.put(l.key, l);
                byPrice.put(price, l);
            } else if (l.count == 0) {
                --empty;  // revived before it was pruned
            }
            if (!stale && (best == null || better(price, best.price))) best = l;
            return l;
        }

        // Called whenever a level's last order leaves. O(1).
        void emptied(Level l) {
            ++empty;
            if (l == best) stale = true;
        }

        // Drops every empty level. O(L), but only runs once empty levels are
        // the majority, so it is amortised across the cancels that made them.
        private void sweep() {
            final var it = levels.values().iterator();
            while (it.hasNext()) {
                final Level l = it.next();
                if (l.count > 0) continue;
                it.remove();
                byPrice.remove(l.price);
                spareLevels.push(l);
            }
            empty = 0;
            refresh();
        }

        int liveLevels() {
            return levels.size() - empty;
        }

        void clear() {
            for (Level l : levels.values()) {
                for (OrderNode n = l.head; n != null; ) {
                    final OrderNode next = n.next;
                    pool.release(n);
                    n = next;
                }
                spareLevels.push(l);
            }
            levels.clear();
            byPrice.clear();
            best = null;
            stale = false;
            empty = 0;
        }
    }

    private HalfBook sideOf(Side s) {
        return s == Side.BUY ? bids : asks;
    }

    // A taker at `takerPrice` crosses a maker at `makerPrice` when it is willing
    // to pay at least as much (buy) or accept at most as much (sell).
    private static boolean crosses(boolean takerBuys, long takerPrice, long makerPrice) {
        return takerBuys ? takerPrice >= makerPrice : takerPrice <= makerPrice;
    }

    @Override
    public ExecReport submit(long id, Side side, OrderType type, Tif tif, long price, long qty) {
        if (id == 0) {
            id = nextId++;
        } else {
            nextId = Math.max(nextId, id + 1);
        }
        final ExecReport r = report;
        r.reset(id);

        if (qty <= 0) return reject(Reject.ZERO_QUANTITY);
        if (index.containsKey(id)) return reject(Reject.DUPLICATE_ID);

        final long seq = nextSeq++;
        final boolean buy = side == Side.BUY;
        final boolean limit = type == OrderType.LIMIT;
        final HalfBook book = buy ? asks : bids;

        // Pre-trade checks run before any state changes, so a reject leaves the
        // book exactly as it was.
        if (!limit && book.top() == null) return reject(Reject.NO_LIQUIDITY);
        if (tif == Tif.FOK && available(book, buy, limit, price, qty) < qty) {
            return reject(Reject.FILL_OR_KILL);
        }

        ++stats.ordersAccepted;
        final long remaining = match(book, id, buy, limit, price, qty);
        r.remaining = remaining;

        if (remaining > 0) {
            if (limit && tif == Tif.GTC) {
                rest(id, side, tif, price, qty, remaining, seq);
                r.resting = true;
            } else {
                r.cancelled = true;  // IOC remainder, or unfilled market size
            }
        }
        return r;
    }

    private ExecReport reject(Reject why) {
        report.reject = why;
        ++stats.ordersRejected;
        return report;
    }

    // Liquidity a taker could consume right now, without mutating anything.
    // Only FOK needs this, so it stops as soon as it knows the answer.
    private static long available(HalfBook book, boolean buy, boolean limit, long price, long qty) {
        long total = 0;
        for (Level l : book.levels.values()) {
            if (limit && !crosses(buy, price, l.price)) break;
            total += l.total;  // empty levels contribute 0
            if (total >= qty) break;
        }
        return total;
    }

    // Walks the opposite book best-price-first, and within a level front to
    // back, which is exactly price-time priority. Trades print at the resting
    // order's price: the maker set the terms, the taker accepted them.
    private long match(HalfBook book, long takerId, boolean buy, boolean limit, long price, long remaining) {
        final ExecReport r = report;
        while (remaining > 0) {
            final Level level = book.top();
            if (level == null || (limit && !crosses(buy, price, level.price))) break;

            while (remaining > 0 && level.head != null) {
                final OrderNode maker = level.head;
                final long fill = Math.min(remaining, maker.remaining);

                remaining -= fill;
                maker.remaining -= fill;
                level.total -= fill;

                recordTrade(takerId, maker.id, buy, level.price, fill);
                r.addTrade(maker.id, level.price, fill);

                if (maker.remaining == 0) {
                    index.remove(maker.id);
                    level.unlink(maker);
                    pool.release(maker);
                }
            }
            if (level.count == 0) book.emptied(level);
        }
        return remaining;
    }

    private void rest(long id, Side side, Tif tif, long price, long qty, long remaining, long seq) {
        final OrderNode n = pool.acquire();
        n.id = id;
        n.side = side;
        n.tif = tif;
        n.price = price;
        n.qty = qty;
        n.remaining = remaining;
        n.seq = seq;
        sideOf(side).levelFor(price).pushBack(n);
        index.put(id, n);
    }

    private void recordTrade(long takerId, long makerId, boolean buy, long price, long qty) {
        stats.record(price, qty);
        final int i = tapeNext;
        tapeSeq[i] = nextSeq++;
        tapeTaker[i] = takerId;
        tapeMaker[i] = makerId;
        tapeBuy[i] = buy;
        tapePrice[i] = price;
        tapeQty[i] = qty;
        tapeNext = i + 1 == tapeSeq.length ? 0 : i + 1;
        if (tapeSize < tapeSeq.length) ++tapeSize;
    }

    // Every step is O(1): the node and its level come straight from the index,
    // and an emptied level is left for top() or sweep() to prune.
    @Override
    public boolean cancel(long id) {
        final OrderNode n = index.remove(id);
        if (n == null) return false;

        final Level level = n.level;
        level.unlink(n);
        pool.release(n);
        if (level.count == 0) sideOf(n.side).emptied(level);
        return true;
    }

    @Override
    public ExecReport amend(long id, long newPrice, long newQty) {
        final OrderNode n = index.get(id);
        if (n == null) {
            report.reset(id);
            return reject(Reject.UNKNOWN_ORDER);
        }

        if (newQty <= 0) {
            cancel(id);
            report.reset(id);
            report.cancelled = true;
            return report;
        }

        // A pure size reduction at the same price keeps queue position, exactly
        // as a real venue would: the order never leaves the queue.
        if (newPrice == n.price && newQty < n.remaining) {
            final long delta = n.remaining - newQty;
            n.remaining = newQty;
            n.qty -= delta;  // keeps filled == qty - remaining honest
            n.level.total -= delta;
            report.reset(id);
            report.remaining = newQty;
            report.resting = true;
            return report;
        }

        // Anything else is a cancel/replace: a new price or more size sends the
        // order to the back of the queue, and it may now trade immediately.
        final Side side = n.side;
        final Tif tif = n.tif;
        cancel(id);
        return submit(id, side, OrderType.LIMIT, tif, newPrice, newQty);
    }

    @Override
    public long bestBid() {
        final Level l = bids.top();
        return l == null ? NO_PRICE : l.price;
    }

    @Override
    public long bestAsk() {
        final Level l = asks.top();
        return l == null ? NO_PRICE : l.price;
    }

    @Override
    public long qtyAt(Side side, long price) {
        final Level l = sideOf(side).byPrice.get(price);
        return l == null ? 0 : l.total;
    }

    @Override
    public boolean contains(long id) {
        return index.containsKey(id);
    }

    @Override
    public int openOrders() {
        return index.size();
    }

    @Override
    public int levelCount(Side side) {
        return sideOf(side).liveLevels();
    }

    @Override
    public List<LevelView> depth(Side side, int maxLevels) {
        final List<LevelView> out = new ArrayList<>(Math.min(maxLevels, levelCount(side)));
        for (Level l : sideOf(side).levels.values()) {
            if (out.size() >= maxLevels) break;
            if (l.count > 0) out.add(new LevelView(l.price, l.total, l.count));
        }
        return out;
    }

    @Override
    public List<Trade> tape() {
        final List<Trade> out = new ArrayList<>(tapeSize);
        final int cap = tapeSeq.length;
        for (int k = 0; k < tapeSize; ++k) {
            final int i = Math.floorMod(tapeNext - tapeSize + k, cap);
            out.add(new Trade(tapeSeq[i], tapeTaker[i], tapeMaker[i],
                    tapeBuy[i] ? Side.BUY : Side.SELL, tapePrice[i], tapeQty[i]));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public void setTapeLimit(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("tape limit must be positive");
        tapeSeq = new long[limit];
        tapeTaker = new long[limit];
        tapeMaker = new long[limit];
        tapePrice = new long[limit];
        tapeQty = new long[limit];
        tapeBuy = new boolean[limit];
        tapeNext = tapeSize = 0;
    }

    @Override
    public BookStats stats() {
        return stats;
    }

    // Order nodes and levels go back to their pools, so a cleared book reuses
    // all the memory it had already warmed up.
    @Override
    public void clear() {
        bids.clear();
        asks.clear();
        index.clear();
        tapeNext = tapeSize = 0;
        stats.reset();
        nextSeq = 1;
        nextId = 1;
    }

    int pooledNodes() {
        return pool.capacity();
    }

    @Override
    public boolean invariantsHold() {
        final Level bid = bids.top();
        final Level ask = asks.top();
        if (bid != null && ask != null && bid.price >= ask.price) {
            return false;  // a crossed book means match() stopped too early
        }

        final long counted = check(bids) + check(asks);
        return counted >= 0 && counted == index.size() && counted == pool.live();
    }

    // Number of orders on the side, or a large negative number if it is broken.
    private long check(HalfBook s) {
        final long broken = Long.MIN_VALUE / 4;
        if (s.byPrice.size() != s.levels.size()) return broken;

        long orders = 0;
        int empties = 0;
        Level firstLive = null;
        for (Level l : s.levels.values()) {
            if (s.byPrice.get(l.price) != l || l.key != l.price) return broken;
            if (l.count == 0) {
                if (l.head != null || l.tail != null || l.total != 0) return broken;
                ++empties;
                continue;
            }
            if (firstLive == null) firstLive = l;

            long sum = 0;
            int n = 0;
            for (OrderNode o = l.head; o != null; o = o.next) {
                if (o.remaining <= 0 || o.price != l.price || o.level != l) return broken;
                if (o.next == null && o != l.tail) return broken;
                if (index.get(o.id) != o) return broken;
                sum += o.remaining;
                ++n;
            }
            if (n != l.count || sum != l.total) return broken;
            orders += n;
        }
        if (empties != s.empty) return broken;
        if (s.top() != firstLive) return broken;
        return orders;
    }
}
