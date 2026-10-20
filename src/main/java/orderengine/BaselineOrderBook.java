package orderengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// The same matching algorithm as OrderBook, written the way it is usually
// written first: plain JDK collections and a fresh object for everything.
//
//   - a new node per resting order and a new report per call
//   - HashMap<Long, Node> for the index, boxing every id it is asked about
//   - TreeMap lookups by boxed price, and a Trade object per fill on the tape
//   - emptied levels removed from the TreeMap immediately, so a cancel that
//     empties a level costs O(log L)
//
// It exists as the "before" for the benchmarks and as a second implementation
// the tests check OrderBook against. Matching behaviour is identical.
public final class BaselineOrderBook implements Book {
    private static final class Node {
        final long id;
        final Side side;
        final Tif tif;
        final long price;
        long qty;
        long remaining;
        Level level;
        Node prev;
        Node next;

        Node(long id, Side side, Tif tif, long price, long qty, long remaining) {
            this.id = id;
            this.side = side;
            this.tif = tif;
            this.price = price;
            this.qty = qty;
            this.remaining = remaining;
        }
    }

    private static final class Level {
        final long price;
        long total;
        int count;
        Node head;
        Node tail;

        Level(long price) {
            this.price = price;
        }
    }

    private final String symbol;
    private final TreeMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Level> asks = new TreeMap<>();
    private final Map<Long, Node> index;
    private final List<Trade> tape = new ArrayList<>();
    private final BookStats stats = new BookStats();
    private int tapeLimit = 4096;
    private long nextSeq = 1;
    private long nextId = 1;

    public BaselineOrderBook() {
        this("TEST");
    }

    public BaselineOrderBook(String symbol) {
        this(symbol, 1 << 10);
    }

    // Pre-sizes the index like OrderBook's, so the comparison is not about rehashing.
    public BaselineOrderBook(String symbol, int expectedOrders) {
        this.symbol = symbol;
        this.index = new HashMap<>(expectedOrders * 2);
    }

    @Override
    public String symbol() {
        return symbol;
    }

    private TreeMap<Long, Level> sideOf(Side s) {
        return s == Side.BUY ? bids : asks;
    }

    private static boolean crosses(Side taker, long takerPrice, long makerPrice) {
        return taker == Side.BUY ? takerPrice >= makerPrice : takerPrice <= makerPrice;
    }

    @Override
    public ExecReport submit(long id, Side side, OrderType type, Tif tif, long price, long qty) {
        if (id == 0) {
            id = nextId++;
        } else {
            nextId = Math.max(nextId, id + 1);
        }
        final ExecReport r = new ExecReport();
        r.reset(id);

        if (qty <= 0) return reject(r, Reject.ZERO_QUANTITY);
        if (index.containsKey(id)) return reject(r, Reject.DUPLICATE_ID);

        nextSeq++;
        final TreeMap<Long, Level> book = side == Side.BUY ? asks : bids;
        final boolean limit = type == OrderType.LIMIT;

        if (!limit && book.isEmpty()) return reject(r, Reject.NO_LIQUIDITY);
        if (tif == Tif.FOK) {
            long fillable = 0;
            for (Level l : book.values()) {
                if (limit && !crosses(side, price, l.price)) break;
                fillable += l.total;
                if (fillable >= qty) break;
            }
            if (fillable < qty) return reject(r, Reject.FILL_OR_KILL);
        }

        ++stats.ordersAccepted;

        long remaining = qty;
        while (remaining > 0 && !book.isEmpty()) {
            final Map.Entry<Long, Level> top = book.firstEntry();
            final Level level = top.getValue();
            if (limit && !crosses(side, price, level.price)) break;

            while (remaining > 0 && level.head != null) {
                final Node maker = level.head;
                final long fill = Math.min(remaining, maker.remaining);
                remaining -= fill;
                maker.remaining -= fill;
                level.total -= fill;

                record(new Trade(nextSeq++, id, maker.id, side, level.price, fill));
                r.addTrade(maker.id, level.price, fill);

                if (maker.remaining == 0) {
                    index.remove(maker.id);
                    unlink(level, maker);
                }
            }
            if (level.head == null) book.remove(top.getKey());
        }

        r.remaining = remaining;
        if (remaining > 0) {
            if (limit && tif == Tif.GTC) {
                final Node n = new Node(id, side, tif, price, qty, remaining);
                final Level level = sideOf(side).computeIfAbsent(price, Level::new);
                pushBack(level, n);
                index.put(id, n);
                r.resting = true;
            } else {
                r.cancelled = true;
            }
        }
        return r;
    }

    private ExecReport reject(ExecReport r, Reject why) {
        r.reject = why;
        ++stats.ordersRejected;
        return r;
    }

    private void record(Trade t) {
        stats.record(t.price(), t.qty());
        if (tape.size() >= tapeLimit) {
            // Drop the oldest half in one shift so trimming stays amortised O(1).
            tape.subList(0, tapeLimit / 2).clear();
        }
        tape.add(t);
    }

    private static void pushBack(Level level, Node node) {
        node.level = level;
        node.prev = level.tail;
        node.next = null;
        if (level.tail != null) {
            level.tail.next = node;
        } else {
            level.head = node;
        }
        level.tail = node;
        level.total += node.remaining;
        ++level.count;
    }

    private static void unlink(Level level, Node node) {
        if (node.prev != null) node.prev.next = node.next; else level.head = node.next;
        if (node.next != null) node.next.prev = node.prev; else level.tail = node.prev;
        level.total -= node.remaining;
        --level.count;
    }

    @Override
    public boolean cancel(long id) {
        final Node n = index.remove(id);
        if (n == null) return false;
        final Level level = n.level;
        unlink(level, n);
        if (level.head == null) sideOf(n.side).remove(level.price);
        return true;
    }

    @Override
    public ExecReport amend(long id, long newPrice, long newQty) {
        final ExecReport r = new ExecReport();
        r.reset(id);
        final Node n = index.get(id);
        if (n == null) return reject(r, Reject.UNKNOWN_ORDER);

        if (newQty <= 0) {
            cancel(id);
            r.cancelled = true;
            return r;
        }
        if (newPrice == n.price && newQty < n.remaining) {
            final long delta = n.remaining - newQty;
            n.remaining = newQty;
            n.qty -= delta;
            n.level.total -= delta;
            r.remaining = newQty;
            r.resting = true;
            return r;
        }
        cancel(id);
        return submit(id, n.side, OrderType.LIMIT, n.tif, newPrice, newQty);
    }

    @Override
    public long bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.firstKey();
    }

    @Override
    public long bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.firstKey();
    }

    @Override
    public long qtyAt(Side side, long price) {
        final Level l = sideOf(side).get(price);
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
        return sideOf(side).size();
    }

    @Override
    public List<LevelView> depth(Side side, int maxLevels) {
        final List<LevelView> out = new ArrayList<>();
        for (Level l : sideOf(side).values()) {
            if (out.size() >= maxLevels) break;
            out.add(new LevelView(l.price, l.total, l.count));
        }
        return out;
    }

    @Override
    public List<Trade> tape() {
        final int from = Math.max(0, tape.size() - tapeLimit);
        return Collections.unmodifiableList(new ArrayList<>(tape.subList(from, tape.size())));
    }

    @Override
    public void setTapeLimit(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("tape limit must be positive");
        tapeLimit = limit;
        tape.clear();
    }

    @Override
    public BookStats stats() {
        return stats;
    }

    @Override
    public void clear() {
        bids.clear();
        asks.clear();
        index.clear();
        tape.clear();
        stats.reset();
        nextSeq = 1;
        nextId = 1;
    }

    @Override
    public boolean invariantsHold() {
        if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) return false;
        long counted = 0;
        for (TreeMap<Long, Level> side : List.of(bids, asks)) {
            for (Level l : side.values()) {
                if (l.head == null) return false;
                long sum = 0;
                int n = 0;
                for (Node o = l.head; o != null; o = o.next) {
                    if (o.remaining <= 0 || o.price != l.price) return false;
                    if (o.next == null && o != l.tail) return false;
                    sum += o.remaining;
                    ++n;
                }
                if (n != l.count || sum != l.total) return false;
                counted += n;
            }
        }
        return counted == index.size();
    }
}
