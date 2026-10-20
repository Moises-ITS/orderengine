package orderengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MatchingEngineTest {
    private final MatchingEngine engine = new MatchingEngine();

    private ExecReport limit(String symbol, Side side, long price, long qty) {
        return engine.submit(symbol, 0, side, OrderType.LIMIT, Tif.GTC, price, qty);
    }

    @Test
    void booksAreIndependentPerSymbol() {
        limit("AAPL", Side.BUY, 10_000, 100);
        final ExecReport r = limit("MSFT", Side.SELL, 10_000, 100);

        // Same price, opposite sides, different symbols: they must not trade.
        assertEquals(0, r.tradeCount());
        assertEquals(0, engine.totals().trades());
        assertEquals(2, engine.symbols().size());
        assertEquals(10_000, engine.find("AAPL").bestBid());
        assertEquals(10_000, engine.find("MSFT").bestAsk());
        assertNull(engine.find("TSLA"));
    }

    @Test
    void orderIdsAreUniqueAcrossSymbols() {
        final long a = limit("AAPL", Side.BUY, 10_000, 10).id();
        final long b = limit("MSFT", Side.BUY, 10_000, 10).id();

        assertNotEquals(a, b);
        assertTrue(engine.find("AAPL").contains(a));
        assertFalse(engine.find("MSFT").contains(a));
    }

    @Test
    void cancelNeedsTheRightSymbol() {
        final long id = limit("AAPL", Side.BUY, 10_000, 10).id();

        assertFalse(engine.cancel("MSFT", id));  // wrong book
        assertTrue(engine.cancel("AAPL", id));
    }

    @Test
    void totalsAggregateEveryBook() {
        for (String sym : new String[] {"AAPL", "MSFT"}) {
            limit(sym, Side.SELL, 10_000, 50);
            limit(sym, Side.BUY, 10_000, 50);
        }

        assertEquals(2, engine.totals().trades());
        assertEquals(100, engine.totals().volume());
        assertEquals(4, engine.totals().ordersAccepted());
        assertEquals(4, engine.latency().count());  // every submit was timed
    }
}
