package orderengine.bench;

import java.util.SplittableRandom;

import orderengine.Book;
import orderengine.OrderType;
import orderengine.Side;
import orderengine.Tif;

// A synthetic order flow with the shape real venues see: mostly passive
// quoting near a random-walking mid, a minority of aggressive takers, IOC, FOK
// and market orders, and ~10% cancels.
//
// The whole stream is generated up front into flat primitive arrays, so that
// replaying it -- the part that gets timed -- does nothing but call the book.
public final class Flow {
    private static final byte CANCEL = 0;
    private static final byte LIMIT_GTC = 1;
    private static final byte LIMIT_IOC = 2;
    private static final byte LIMIT_FOK = 3;
    private static final byte MARKET = 4;

    private final byte[] kind;
    private final boolean[] buy;
    private final long[] id;      // order id, or the cancel target
    private final long[] price;
    private final long[] qty;
    private final int submits;

    private Flow(byte[] kind, boolean[] buy, long[] id, long[] price, long[] qty, int submits) {
        this.kind = kind;
        this.buy = buy;
        this.id = id;
        this.price = price;
        this.qty = qty;
        this.submits = submits;
    }

    public int size() {
        return kind.length;
    }

    public int submits() {
        return submits;
    }

    public int cancels() {
        return kind.length - submits;
    }

    public static Flow generate(int n, long seed) {
        final SplittableRandom rng = new SplittableRandom(seed);
        final byte[] kind = new byte[n];
        final boolean[] buy = new boolean[n];
        final long[] id = new long[n];
        final long[] price = new long[n];
        final long[] qty = new long[n];

        long mid = 10_000;  // 100.00
        long nextId = 1;
        int submits = 0;

        for (int i = 0; i < n; ++i) {
            if (i % 16 == 0) {
                mid = Math.max(1_000, mid + rng.nextInt(3) - 1);  // -1, 0 or +1 tick
            }
            final int roll = rng.nextInt(100);
            final boolean b = rng.nextBoolean();
            buy[i] = b;
            qty[i] = 10L * (1 + rng.nextInt(20));
            kind[i] = LIMIT_GTC;

            if (roll < 55) {
                // Passive: rests one to twenty ticks behind the touch.
                final long back = 1 + rng.nextInt(20);
                price[i] = b ? mid - back : mid + back;
            } else if (roll < 76) {
                // Aggressive limit: crosses the touch by a few ticks.
                final long into = rng.nextInt(6);
                price[i] = b ? mid + into : mid - into;
            } else if (roll < 82) {
                kind[i] = LIMIT_IOC;
                price[i] = b ? mid + 3 : mid - 3;
            } else if (roll < 87) {
                kind[i] = MARKET;
                qty[i] = 10L * (1 + rng.nextInt(5));
            } else if (roll < 90) {
                kind[i] = LIMIT_FOK;
                qty[i] = 10L * (1 + rng.nextInt(3));
                price[i] = b ? mid + 2 : mid - 2;
            } else if (nextId > 1) {
                // Cancel an earlier order. It may already be filled or gone, and
                // a cancel that misses is just as real as one that hits.
                kind[i] = CANCEL;
                id[i] = 1 + (long) rng.nextInt((int) (nextId - 1));
                continue;
            } else {
                price[i] = b ? mid - 1 : mid + 1;
            }
            id[i] = nextId++;
            ++submits;
        }
        return new Flow(kind, buy, id, price, qty, submits);
    }

    // Sends operation `i` to the book. Returns the shares filled, so callers
    // have something to consume and the JIT cannot discard the call.
    public long apply(Book book, int i) {
        final Side side = buy[i] ? Side.BUY : Side.SELL;
        switch (kind[i]) {
            case CANCEL:
                return book.cancel(id[i]) ? 1 : 0;
            case LIMIT_GTC:
                return book.submit(id[i], side, OrderType.LIMIT, Tif.GTC, price[i], qty[i]).filled();
            case LIMIT_IOC:
                return book.submit(id[i], side, OrderType.LIMIT, Tif.IOC, price[i], qty[i]).filled();
            case LIMIT_FOK:
                return book.submit(id[i], side, OrderType.LIMIT, Tif.FOK, price[i], qty[i]).filled();
            default:
                return book.submit(id[i], side, OrderType.MARKET, Tif.IOC, 0, qty[i]).filled();
        }
    }
}
