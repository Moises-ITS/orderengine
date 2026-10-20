package orderengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import orderengine.bench.Bench;
import orderengine.bench.Flow;

class RandomizedTest {
    @Test
    void randomFlowNeverBreaksTheBook() {
        // A deterministic fuzz: whatever the order of events, the book must stay
        // uncrossed, its cached totals must match its queues, and the shares the
        // takers report as filled must equal the volume the book recorded.
        final OrderBook book = new OrderBook("FUZZ");
        final SplittableRandom rng = new SplittableRandom(12_345);
        final List<Long> seen = new ArrayList<>();
        long takerFilled = 0;

        for (int i = 0; i < 20_000; ++i) {
            final int roll = rng.nextInt(100);

            if (roll < 12 && !seen.isEmpty()) {
                book.cancel(seen.get(rng.nextInt(seen.size())));
            } else if (roll < 20 && !seen.isEmpty()) {
                final long price = 9_950 + rng.nextInt(100);
                takerFilled += book.amend(seen.get(rng.nextInt(seen.size())), price, 1 + rng.nextInt(50)).filled();
            } else {
                final Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
                final long qty = 1 + rng.nextInt(200);
                final long price = 9_950 + rng.nextInt(100);
                final OrderType type = roll < 26 ? OrderType.MARKET : OrderType.LIMIT;
                Tif tif = roll < 30 ? Tif.IOC : roll < 34 ? Tif.FOK : Tif.GTC;
                if (type == OrderType.MARKET) tif = Tif.IOC;

                final ExecReport r = book.submit(0, side, type, tif, price, qty);
                takerFilled += r.filled();
                if (r.resting()) seen.add(r.id());
            }

            if (i % 500 == 0) assertTrue(book.invariantsHold(), "after event " + i);
        }

        assertTrue(book.invariantsHold());
        assertEquals(book.stats().volume(), takerFilled);
        assertTrue(book.stats().trades() > 0);
    }

    @Test
    void pooledBookMatchesBaselineFillForFill() {
        // The optimizations must not change a single execution: replay the
        // benchmark flow through both books, comparing every operation and the
        // final state.
        final Flow flow = Flow.generate(300_000, 99);
        final OrderBook pooled = new OrderBook();
        final BaselineOrderBook baseline = new BaselineOrderBook();
        final Snapshot a = new Snapshot();
        final Snapshot b = new Snapshot();

        for (int i = 0; i < flow.size(); ++i) {
            final long fa = flow.apply(pooled, i);
            final long fb = flow.apply(baseline, i);
            assertEquals(fb, fa, "operation " + i);
            if (i % 50_000 == 0) {
                assertTrue(pooled.invariantsHold());
                assertTrue(baseline.invariantsHold());
            }
        }
        a.capture(pooled);
        b.capture(baseline);

        for (int i = 0; i < a.state.size(); ++i) assertEquals(b.state.get(i), a.state.get(i), "snapshot part " + i);
        assertTrue(pooled.stats().trades() > 100_000);
        assertTrue(pooled.invariantsHold());
    }

    // Everything observable about a book once a replay has finished.
    private static final class Snapshot {
        final List<Object> state = new ArrayList<>();

        void capture(Book book) {
            final BookStats s = book.stats();
            state.add(List.of(s.ordersAccepted(), s.ordersRejected(), s.trades(), s.volume(), s.notional()));
            state.add(List.of(book.openOrders(), book.bestBid(), book.bestAsk()));
            state.add(book.depth(Side.BUY, Integer.MAX_VALUE));
            state.add(book.depth(Side.SELL, Integer.MAX_VALUE));
            // The books trim their tapes differently, so compare the latest trades.
            final List<Trade> tape = book.tape();
            state.add(tape.subList(tape.size() - 1_000, tape.size()));
        }
    }

    @Test
    void primitiveIndexBehavesLikeAHashMap() {
        // Random puts and removes over a small key space force long probe
        // chains, wrap-around and backward-shift deletion through every case.
        final LongObjectHashMap<Long> map = new LongObjectHashMap<>(4);
        final Map<Long, Long> ref = new HashMap<>();
        final SplittableRandom rng = new SplittableRandom(7);

        for (int i = 0; i < 200_000; ++i) {
            final long key = rng.nextInt(2_000) - 1_000;  // negatives and zero too
            if (rng.nextInt(3) == 0) {
                assertEquals(ref.remove(key), map.remove(key));
            } else {
                final long value = rng.nextLong();
                assertEquals(ref.put(key, value), map.put(key, value));
            }
            assertEquals(ref.size(), map.size());
        }
        for (long key = -1_000; key < 1_000; ++key) {
            assertEquals(ref.get(key), map.get(key), "key " + key);
        }
        map.clear();
        assertEquals(0, map.size());
        assertEquals(null, map.get(5));
    }

    @Test
    void benchmarkIsDeterministicForASeed() {
        final Bench.Result a = Bench.throughput("pooled", Flow.generate(20_000, 7));
        final Bench.Result b = Bench.throughput("pooled", Flow.generate(20_000, 7));
        final Bench.Result c = Bench.throughput("pooled", Flow.generate(20_000, 8));

        assertEquals(a.trades(), b.trades());
        assertEquals(a.volume(), b.volume());
        assertEquals(a.restingOrders(), b.restingOrders());
        assertNotEquals(a.trades(), c.trades());  // a different seed is a different market
        assertTrue(a.trades() > 0);
        assertTrue(a.opsPerSec() > 0);
        assertEquals(a.operations(), a.submits() + a.cancels());
    }
}
