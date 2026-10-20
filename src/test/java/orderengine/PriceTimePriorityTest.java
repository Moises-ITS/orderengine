package orderengine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class PriceTimePriorityTest {
    // A deliberately naive book that states price-time priority as directly as
    // possible: resting orders sit in one list in arrival order, and each fill
    // scans the whole thing for the best price, taking the earliest arrival on
    // a tie. Slow, but hard to get wrong, which is the point of a reference.
    private static final class ReferenceBook {
        record Fill(long makerId, long price, long qty) {}

        record Result(List<Fill> fills, boolean rejected, boolean resting) {}

        private static final class Resting {
            final long id;
            final Side side;
            final long price;
            long remaining;

            Resting(long id, Side side, long price, long remaining) {
                this.id = id;
                this.side = side;
                this.price = price;
                this.remaining = remaining;
            }
        }

        private final List<Resting> resting = new ArrayList<>();

        Result submit(long id, Side side, OrderType type, long price, long qty) {
            final boolean anyMaker = resting.stream().anyMatch(o -> o.side != side);
            if (type == OrderType.MARKET && !anyMaker) return new Result(List.of(), true, false);

            final List<Fill> fills = new ArrayList<>();
            while (qty > 0) {
                final Resting best = bestMaker(side, type, price);
                if (best == null) break;
                final long fill = Math.min(qty, best.remaining);
                fills.add(new Fill(best.id, best.price, fill));
                qty -= fill;
                best.remaining -= fill;
                if (best.remaining == 0) resting.remove(best);
            }

            final boolean rests = qty > 0 && type == OrderType.LIMIT;
            if (rests) resting.add(new Resting(id, side, price, qty));
            return new Result(fills, false, rests);
        }

        boolean cancel(long id) {
            return resting.removeIf(o -> o.id == id);
        }

        // True when `book` holds exactly these orders at the same best prices.
        boolean agreesWith(Book book) {
            if (book.openOrders() != resting.size()) return false;
            long bid = Book.NO_PRICE;
            long ask = Book.NO_PRICE;
            for (Resting o : resting) {
                if (!book.contains(o.id)) return false;
                if (o.side == Side.BUY && (bid == Book.NO_PRICE || o.price > bid)) bid = o.price;
                if (o.side == Side.SELL && (ask == Book.NO_PRICE || o.price < ask)) ask = o.price;
            }
            return book.bestBid() == bid && book.bestAsk() == ask;
        }

        private Resting bestMaker(Side taker, OrderType type, long limit) {
            Resting best = null;
            for (Resting o : resting) {
                if (o.side == taker) continue;
                if (type == OrderType.LIMIT && (taker == Side.BUY ? o.price > limit : o.price < limit)) {
                    continue;  // does not cross
                }
                // Strictly better price only: on a tie the earlier arrival, which
                // sits earlier in the list, keeps its claim.
                if (best == null || (taker == Side.BUY ? o.price < best.price : o.price > best.price)) {
                    best = o;
                }
            }
            return best;
        }
    }

    // How often each situation the test is meant to exercise actually came up.
    private static final class Coverage {
        long crossingOrders;  // takers that traded at least once
        long partialFills;    // fills that left the maker resting
        long liveCancels;     // cancels that removed a resting order
    }

    // Replays one random sequence of limit, market and cancel orders through
    // both books. Returns null on success, or what diverged first.
    private static String replay(Book book, long seed, Coverage cov) {
        final SplittableRandom rng = new SplittableRandom(seed);
        final ReferenceBook ref = new ReferenceBook();
        final List<Long> ids = new ArrayList<>();

        for (int i = 0; i < 100; ++i) {
            final int roll = rng.nextInt(100);
            final String at = "event " + i + ": ";

            if (roll < 15 && !ids.isEmpty()) {
                // Includes ids already filled or cancelled: both books must refuse.
                final long id = ids.get(rng.nextInt(ids.size()));
                final boolean removed = ref.cancel(id);
                if (book.cancel(id) != removed) return at + "cancel disagrees";
                if (removed) ++cov.liveCancels;
                continue;
            }

            final Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            final OrderType type = roll < 25 ? OrderType.MARKET : OrderType.LIMIT;
            final Tif tif = type == OrderType.MARKET ? Tif.IOC : Tif.GTC;
            // A narrow band of prices and small sizes so that orders stack up at
            // the same price, cross often and fill partially.
            final long price = 10_000 + rng.nextInt(7) - 3;
            final long qty = 1 + rng.nextInt(20);

            final ExecReport got = book.submit(0, side, type, tif, price, qty);
            final ReferenceBook.Result want = ref.submit(got.id(), side, type, price, qty);
            ids.add(got.id());

            if ((got.reject() == Reject.NO_LIQUIDITY) != want.rejected()) return at + "reject disagrees";
            if (got.tradeCount() != want.fills().size()) return at + "fill count disagrees";
            for (int f = 0; f < got.tradeCount(); ++f) {
                final ReferenceBook.Fill w = want.fills().get(f);
                if (got.makerId(f) != w.makerId() || got.tradePrice(f) != w.price() || got.tradeQty(f) != w.qty()) {
                    return at + "fill " + f + " disagrees";
                }
            }
            if (got.resting() != want.resting()) return at + "resting disagrees";

            if (got.tradeCount() > 0) ++cov.crossingOrders;
            for (int f = 0; f < got.tradeCount(); ++f) {
                if (book.contains(got.makerId(f))) ++cov.partialFills;
            }
        }

        if (!ref.agreesWith(book)) return "final book disagrees";
        if (!book.invariantsHold()) return "invariants broken";
        return null;
    }

    @Test
    void priceTimePriorityMatchesAReferenceModel() {
        // 10,000 independent random sequences per implementation, each checked
        // fill by fill (maker, price and size, in order) against the reference.
        final List<Supplier<Book>> impls =
                List.of(() -> new OrderBook("PRIO"), () -> new BaselineOrderBook("PRIO"));
        for (Supplier<Book> impl : impls) {
            final Coverage cov = new Coverage();
            for (long seed = 1; seed <= 10_000; ++seed) {
                final Book book = impl.get();
                final String err = replay(book, seed, cov);
                if (err != null) fail(book.getClass().getSimpleName() + " seed " + seed + ", " + err);
            }

            // Guard against the generator drifting into a shape that no longer
            // tests what this is meant to test.
            assertTrue(cov.crossingOrders > 100_000, "crossing orders " + cov.crossingOrders);
            assertTrue(cov.partialFills > 100_000, "partial fills " + cov.partialFills);
            assertTrue(cov.liveCancels > 10_000, "live cancels " + cov.liveCancels);
        }
    }
}
