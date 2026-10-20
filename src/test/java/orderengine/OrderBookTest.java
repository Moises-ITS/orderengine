package orderengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// Prices here are raw ticks: the core is integer-only, decimals are a CLI
// concern. 10_000 ticks == 100.00.
class OrderBookTest {
    private final OrderBook book = new OrderBook();

    // Reports are reused by the book, so each helper hands back a copy.
    private ExecReport limit(Side side, long price, long qty, Tif tif, long id) {
        return book.submit(id, side, OrderType.LIMIT, tif, price, qty).copy();
    }

    private ExecReport limit(Side side, long price, long qty) {
        return limit(side, price, qty, Tif.GTC, 0);
    }

    private ExecReport rest(Side side, long price, long qty, long id) {
        return limit(side, price, qty, Tif.GTC, id);
    }

    private ExecReport market(Side side, long qty) {
        return book.submit(0, side, OrderType.MARKET, Tif.IOC, 0, qty).copy();
    }

    @Test
    void restingOrderDoesNotTrade() {
        final ExecReport r = limit(Side.BUY, 10_000, 100);

        assertTrue(r.accepted());
        assertTrue(r.resting());
        assertEquals(0, r.filled());
        assertEquals(100, r.remaining());
        assertEquals(10_000, book.bestBid());
        assertEquals(Book.NO_PRICE, book.bestAsk());
        assertTrue(book.invariantsHold());
    }

    @Test
    void timePriorityWithinAPriceLevel() {
        rest(Side.BUY, 10_000, 100, 1);  // first in
        rest(Side.BUY, 10_000, 100, 2);  // second in

        final ExecReport r = limit(Side.SELL, 10_000, 100);

        assertEquals(1, r.tradeCount());
        assertEquals(1, r.makerId(0));  // the earlier order, not the later one
        assertFalse(book.contains(1));
        assertTrue(book.contains(2));
        assertTrue(book.invariantsHold());
    }

    @Test
    void pricePriorityBeatsTimePriority() {
        rest(Side.BUY, 10_000, 100, 1);  // earlier, worse price
        rest(Side.BUY, 10_050, 100, 2);  // later, better price

        final ExecReport r = limit(Side.SELL, 10_000, 100);

        assertEquals(1, r.tradeCount());
        assertEquals(2, r.makerId(0));
        assertEquals(10_050, r.tradePrice(0));  // the maker sets the trade price
    }

    @Test
    void crossingOrderTradesAtTheMakerPrice() {
        rest(Side.SELL, 10_000, 100, 1);

        // A buyer willing to pay 101.00 against an ask of 100.00 trades at 100.00
        // and keeps the difference: price improvement goes to the taker.
        final ExecReport r = limit(Side.BUY, 10_100, 100);

        assertEquals(1, r.tradeCount());
        assertEquals(10_000, r.tradePrice(0));
        assertTrue(book.isEmpty());
    }

    @Test
    void partialFillTakerRemainderRestsAtItsOwnPrice() {
        rest(Side.SELL, 10_000, 40, 1);

        final ExecReport r = limit(Side.BUY, 10_050, 100);

        assertEquals(40, r.filled());
        assertEquals(60, r.remaining());
        assertTrue(r.resting());
        assertEquals(10_050, book.bestBid());
        assertEquals(60, book.qtyAt(Side.BUY, 10_050));
        assertEquals(Book.NO_PRICE, book.bestAsk());
        assertTrue(book.invariantsHold());
    }

    @Test
    void partialFillMakerRemainderKeepsItsPlace() {
        rest(Side.SELL, 10_000, 100, 1);

        final ExecReport r = limit(Side.BUY, 10_000, 30);

        assertEquals(30, r.filled());
        assertFalse(r.resting());
        assertTrue(book.contains(1));
        assertEquals(70, book.qtyAt(Side.SELL, 10_000));
        assertTrue(book.invariantsHold());
    }

    @Test
    void crossingOrderSweepsLevelsInPriceOrder() {
        rest(Side.SELL, 10_020, 50, 1);
        rest(Side.SELL, 10_000, 50, 2);  // best ask
        rest(Side.SELL, 10_010, 50, 3);

        final ExecReport r = limit(Side.BUY, 10_020, 150);

        assertEquals(3, r.tradeCount());
        assertEquals(10_000, r.tradePrice(0));
        assertEquals(10_010, r.tradePrice(1));
        assertEquals(10_020, r.tradePrice(2));
        assertEquals(150, r.filled());
        assertTrue(book.isEmpty());
        assertTrue(book.invariantsHold());
    }

    @Test
    void noTradeWhenPricesDoNotCross() {
        rest(Side.SELL, 10_050, 100, 1);

        final ExecReport r = limit(Side.BUY, 10_000, 100);

        assertEquals(0, r.tradeCount());
        assertTrue(r.resting());
        assertEquals(50, book.spread());
        assertTrue(book.invariantsHold());
    }

    @Test
    void cancelRemovesLiquidity() {
        rest(Side.BUY, 10_000, 100, 7);

        assertTrue(book.cancel(7));
        assertFalse(book.cancel(7));   // already gone
        assertFalse(book.cancel(99));  // never existed
        assertTrue(book.isEmpty());
        assertEquals(Book.NO_PRICE, book.bestBid());
        assertTrue(book.invariantsHold());
    }

    @Test
    void cancelFromTheMiddleKeepsTheQueueIntact() {
        rest(Side.BUY, 10_000, 10, 1);
        rest(Side.BUY, 10_000, 10, 2);
        rest(Side.BUY, 10_000, 10, 3);

        assertTrue(book.cancel(2));
        assertEquals(20, book.qtyAt(Side.BUY, 10_000));

        final ExecReport r = limit(Side.SELL, 10_000, 20);
        assertEquals(2, r.tradeCount());
        assertEquals(1, r.makerId(0));
        assertEquals(3, r.makerId(1));  // order 3 kept its position
        assertTrue(book.invariantsHold());
    }

    @Test
    void cancellingTheBestLevelMovesTopOfBookAndRecyclesLevels() {
        rest(Side.BUY, 10_000, 10, 1);
        rest(Side.BUY, 9_990, 10, 2);
        rest(Side.BUY, 9_980, 10, 3);

        // Cancels leave emptied levels for lazy pruning; top of book must
        // still be exact after each one.
        assertTrue(book.cancel(1));
        assertEquals(9_990, book.bestBid());
        assertEquals(2, book.levelCount(Side.BUY));
        assertTrue(book.cancel(3));
        assertEquals(9_990, book.bestBid());
        assertTrue(book.cancel(2));
        assertEquals(Book.NO_PRICE, book.bestBid());
        assertEquals(0, book.levelCount(Side.BUY));

        // An order at a price whose level is empty but not yet pruned revives it.
        rest(Side.BUY, 9_980, 10, 4);
        assertEquals(9_980, book.bestBid());
        assertTrue(book.invariantsHold());

        // Thousands of levels created and cancelled: the sweep keeps the maps
        // bounded and the pool reuses the same nodes.
        final int nodes = book.pooledNodes();
        for (int i = 0; i < 5_000; ++i) {
            rest(Side.SELL, 20_000 + i, 1, 1_000 + i);
            assertTrue(book.cancel(1_000 + i));
        }
        assertEquals(Book.NO_PRICE, book.bestAsk());
        assertEquals(0, book.levelCount(Side.SELL));
        assertEquals(nodes, book.pooledNodes());
        assertTrue(book.invariantsHold());
    }

    @Test
    void iocFillsWhatItCanAndCancelsTheRest() {
        rest(Side.SELL, 10_000, 40, 1);

        final ExecReport r = limit(Side.BUY, 10_000, 100, Tif.IOC, 0);

        assertEquals(40, r.filled());
        assertEquals(60, r.remaining());
        assertTrue(r.cancelled());
        assertFalse(r.resting());
        assertTrue(book.isEmpty());  // nothing left behind on either side
    }

    @Test
    void fokRejectsWithoutTouchingTheBook() {
        rest(Side.SELL, 10_000, 40, 1);

        final ExecReport r = limit(Side.BUY, 10_000, 100, Tif.FOK, 0);

        assertFalse(r.accepted());
        assertEquals(Reject.FILL_OR_KILL, r.reject());
        assertEquals(0, r.tradeCount());
        assertEquals(0, r.filled());
        assertEquals(40, book.qtyAt(Side.SELL, 10_000));  // untouched
        assertEquals(0, book.stats().trades());
    }

    @Test
    void fokFillsCompletelyWhenItCan() {
        rest(Side.SELL, 10_000, 40, 1);
        rest(Side.SELL, 10_010, 80, 2);

        final ExecReport r = limit(Side.BUY, 10_010, 100, Tif.FOK, 0);

        assertTrue(r.accepted());
        assertEquals(100, r.filled());
        assertEquals(0, r.remaining());
        assertEquals(2, r.tradeCount());
        assertEquals(20, book.qtyAt(Side.SELL, 10_010));
        assertTrue(book.invariantsHold());
    }

    @Test
    void fokIgnoresLiquidityItCannotReach() {
        rest(Side.SELL, 10_000, 40, 1);
        rest(Side.SELL, 20_000, 400, 2);  // far away, not crossable

        final ExecReport r = limit(Side.BUY, 10_000, 100, Tif.FOK, 0);

        assertFalse(r.accepted());
        assertEquals(Reject.FILL_OR_KILL, r.reject());
    }

    @Test
    void marketOrderSweepsAndNeverRests() {
        rest(Side.SELL, 10_000, 30, 1);
        rest(Side.SELL, 10_050, 30, 2);

        final ExecReport r = market(Side.BUY, 100);

        assertEquals(60, r.filled());
        assertEquals(40, r.remaining());
        assertTrue(r.cancelled());
        assertFalse(r.resting());
        assertTrue(book.isEmpty());
    }

    @Test
    void marketOrderNeedsABook() {
        rest(Side.BUY, 10_000, 100, 1);  // same side only

        final ExecReport r = market(Side.BUY, 10);

        assertFalse(r.accepted());
        assertEquals(Reject.NO_LIQUIDITY, r.reject());
        assertEquals(1, book.stats().ordersRejected());
    }

    @Test
    void rejectsZeroQuantityAndDuplicateIds() {
        assertFalse(limit(Side.BUY, 10_000, 0).accepted());

        rest(Side.BUY, 10_000, 100, 5);
        final ExecReport dup = rest(Side.BUY, 10_000, 100, 5);

        assertFalse(dup.accepted());
        assertEquals(Reject.DUPLICATE_ID, dup.reject());
        assertEquals(100, book.qtyAt(Side.BUY, 10_000));
    }

    @Test
    void amendDownKeepsQueuePosition() {
        rest(Side.BUY, 10_000, 100, 1);
        rest(Side.BUY, 10_000, 100, 2);

        final ExecReport a = book.amend(1, 10_000, 40).copy();
        assertTrue(a.accepted());
        assertTrue(a.resting());
        assertEquals(140, book.qtyAt(Side.BUY, 10_000));

        final ExecReport r = limit(Side.SELL, 10_000, 40);
        assertEquals(1, r.makerId(0));  // still at the front
        assertTrue(book.invariantsHold());
    }

    @Test
    void cancelReplaceWithMoreSizeGoesToTheBackOfTheQueue() {
        rest(Side.BUY, 10_000, 100, 1);
        rest(Side.BUY, 10_000, 100, 2);

        book.amend(1, 10_000, 200);  // more size, so it loses priority
        assertEquals(300, book.qtyAt(Side.BUY, 10_000));

        final ExecReport r = limit(Side.SELL, 10_000, 100);
        assertEquals(2, r.makerId(0));
    }

    @Test
    void cancelReplaceToANewPriceMovesTheOrder() {
        rest(Side.BUY, 10_000, 100, 1);
        rest(Side.BUY, 9_990, 100, 2);

        final ExecReport a = book.amend(1, 9_990, 100).copy();

        assertTrue(a.accepted());
        assertTrue(a.resting());
        assertEquals(0, book.qtyAt(Side.BUY, 10_000));
        assertEquals(200, book.qtyAt(Side.BUY, 9_990));
        assertEquals(9_990, book.bestBid());
        assertEquals(1, book.levelCount(Side.BUY));

        // Replaced behind order 2, which was already resting at 99.90.
        final ExecReport r = limit(Side.SELL, 9_990, 100);
        assertEquals(2, r.makerId(0));
        assertTrue(book.invariantsHold());
    }

    @Test
    void cancelReplaceAcrossTheSpreadTradesImmediately() {
        rest(Side.SELL, 10_050, 100, 1);
        rest(Side.BUY, 10_000, 100, 2);

        final ExecReport r = book.amend(2, 10_050, 100);

        assertTrue(r.accepted());
        assertEquals(100, r.filled());
        assertTrue(book.isEmpty());
    }

    @Test
    void amendToZeroCancelsAndUnknownIdsReject() {
        rest(Side.BUY, 10_000, 100, 1);

        final ExecReport zero = book.amend(1, 10_000, 0).copy();
        assertTrue(zero.cancelled());
        assertTrue(book.isEmpty());

        final ExecReport missing = book.amend(42, 10_000, 10);
        assertFalse(missing.accepted());
        assertEquals(Reject.UNKNOWN_ORDER, missing.reject());
    }

    @Test
    void partiallyFilledOrderCanBeAmendedDown() {
        rest(Side.SELL, 10_000, 100, 1);
        rest(Side.BUY, 10_000, 30, 2);

        // Reducing to 50 after 30 already traded leaves the level consistent.
        book.amend(1, 10_000, 50);
        assertEquals(50, book.qtyAt(Side.SELL, 10_000));
        assertTrue(book.invariantsHold());
    }

    @Test
    void depthAggregatesOrdersPerLevel() {
        rest(Side.BUY, 10_000, 10, 1);
        rest(Side.BUY, 10_000, 20, 2);
        rest(Side.BUY, 9_900, 30, 3);

        final List<LevelView> bids = book.depth(Side.BUY, 10);

        assertEquals(2, bids.size());
        assertEquals(new LevelView(10_000, 30, 2), bids.get(0));  // best first
        assertEquals(9_900, bids.get(1).price());
        assertEquals(2, book.levelCount(Side.BUY));
        assertEquals(1, book.depth(Side.BUY, 1).size());  // honours the cap
    }

    @Test
    void statsTrackVolumeAndNotional() {
        rest(Side.SELL, 10_000, 100, 1);
        rest(Side.BUY, 10_000, 60, 2);

        assertEquals(1, book.stats().trades());
        assertEquals(60, book.stats().volume());
        assertEquals(600_000, book.stats().notional());  // 60 * 10_000 ticks
        assertEquals(2, book.stats().ordersAccepted());
        assertEquals(List.of(new Trade(3, 2, 1, Side.BUY, 10_000, 60)), book.tape());
    }

    @Test
    void clearResetsEverything() {
        rest(Side.BUY, 10_000, 100, 1);
        book.clear();

        assertTrue(book.isEmpty());
        assertEquals(0, book.stats().ordersAccepted());
        assertTrue(book.tape().isEmpty());
        assertTrue(book.invariantsHold());
    }
}
