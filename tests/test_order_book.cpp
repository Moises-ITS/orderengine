#include "ob/order_book.hpp"

#include "test_harness.hpp"

using namespace ob;

namespace {

// Prices here are raw ticks: the core is integer-only, decimals are a CLI
// concern. 10'000 ticks == 100.00.
ExecReport limit(OrderBook& book, Side side, Price price, Qty qty, Tif tif = Tif::Gtc,
                 OrderId id = 0) {
    Order o;
    o.id    = id;
    o.side  = side;
    o.type  = OrderType::Limit;
    o.tif   = tif;
    o.price = price;
    o.qty   = qty;
    return book.submit(o);
}

ExecReport market(OrderBook& book, Side side, Qty qty, OrderId id = 0) {
    Order o;
    o.id   = id;
    o.side = side;
    o.type = OrderType::Market;
    o.tif  = Tif::Ioc;
    o.qty  = qty;
    return book.submit(o);
}

}  // namespace

TEST(resting_order_does_not_trade) {
    OrderBook book;
    const auto r = limit(book, Side::Buy, 10'000, 100);

    CHECK(r.accepted());
    CHECK(r.resting);
    CHECK_EQ(r.filled, 0u);
    CHECK_EQ(r.remaining, 100u);
    CHECK_EQ(book.best_bid().value(), 10'000);
    CHECK(!book.best_ask().has_value());
    CHECK(book.invariants_hold());
}

TEST(time_priority_within_a_price_level) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);  // first in
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 2);  // second in

    const auto r = limit(book, Side::Sell, 10'000, 100);

    CHECK_EQ(r.trades.size(), 1u);
    CHECK_EQ(r.trades.at(0).maker_id, 1u);  // the earlier order, not the later one
    CHECK(!book.contains(1));
    CHECK(book.contains(2));
    CHECK(book.invariants_hold());
}

TEST(price_priority_beats_time_priority) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);  // earlier, worse price
    limit(book, Side::Buy, 10'050, 100, Tif::Gtc, 2);  // later, better price

    const auto r = limit(book, Side::Sell, 10'000, 100);

    CHECK_EQ(r.trades.size(), 1u);
    CHECK_EQ(r.trades.at(0).maker_id, 2u);
    CHECK_EQ(r.trades.at(0).price, 10'050);  // the maker sets the trade price
}

TEST(trade_prints_at_the_maker_price) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 100, Tif::Gtc, 1);

    // A buyer willing to pay 101.00 against an ask of 100.00 trades at 100.00
    // and keeps the difference: price improvement goes to the taker.
    const auto r = limit(book, Side::Buy, 10'100, 100);

    CHECK_EQ(r.trades.size(), 1u);
    CHECK_EQ(r.trades.at(0).price, 10'000);
    CHECK(book.empty());
}

TEST(taker_remainder_rests_at_its_own_price) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 40, Tif::Gtc, 1);

    const auto r = limit(book, Side::Buy, 10'050, 100);

    CHECK_EQ(r.filled, 40u);
    CHECK_EQ(r.remaining, 60u);
    CHECK(r.resting);
    CHECK_EQ(book.best_bid().value(), 10'050);
    CHECK_EQ(book.qty_at(Side::Buy, 10'050), 60u);
    CHECK(!book.best_ask().has_value());
    CHECK(book.invariants_hold());
}

TEST(maker_remainder_keeps_its_place) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 100, Tif::Gtc, 1);

    const auto r = limit(book, Side::Buy, 10'000, 30);

    CHECK_EQ(r.filled, 30u);
    CHECK(!r.resting);
    CHECK(book.contains(1));
    CHECK_EQ(book.qty_at(Side::Sell, 10'000), 70u);
    CHECK(book.invariants_hold());
}

TEST(sweeps_levels_in_price_order) {
    OrderBook book;
    limit(book, Side::Sell, 10'020, 50, Tif::Gtc, 1);
    limit(book, Side::Sell, 10'000, 50, Tif::Gtc, 2);  // best ask
    limit(book, Side::Sell, 10'010, 50, Tif::Gtc, 3);

    const auto r = limit(book, Side::Buy, 10'020, 150);

    CHECK_EQ(r.trades.size(), 3u);
    CHECK_EQ(r.trades.at(0).price, 10'000);
    CHECK_EQ(r.trades.at(1).price, 10'010);
    CHECK_EQ(r.trades.at(2).price, 10'020);
    CHECK_EQ(r.filled, 150u);
    CHECK(book.empty());
    CHECK(book.invariants_hold());
}

TEST(no_trade_when_prices_do_not_cross) {
    OrderBook book;
    limit(book, Side::Sell, 10'050, 100, Tif::Gtc, 1);

    const auto r = limit(book, Side::Buy, 10'000, 100);

    CHECK(r.trades.empty());
    CHECK(r.resting);
    CHECK_EQ(book.spread().value(), 50);
    CHECK(book.invariants_hold());
}

TEST(cancel_removes_liquidity) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 7);

    CHECK(book.cancel(7));
    CHECK(!book.cancel(7));   // already gone
    CHECK(!book.cancel(99));  // never existed
    CHECK(book.empty());
    CHECK(!book.best_bid().has_value());
    CHECK(book.invariants_hold());
}

TEST(cancel_from_the_middle_keeps_the_queue_intact) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 10, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 10, Tif::Gtc, 2);
    limit(book, Side::Buy, 10'000, 10, Tif::Gtc, 3);

    CHECK(book.cancel(2));
    CHECK_EQ(book.qty_at(Side::Buy, 10'000), 20u);

    const auto r = limit(book, Side::Sell, 10'000, 20);
    CHECK_EQ(r.trades.size(), 2u);
    CHECK_EQ(r.trades.at(0).maker_id, 1u);
    CHECK_EQ(r.trades.at(1).maker_id, 3u);  // order 3 kept its position
    CHECK(book.invariants_hold());
}

TEST(ioc_fills_what_it_can_and_cancels_the_rest) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 40, Tif::Gtc, 1);

    const auto r = limit(book, Side::Buy, 10'000, 100, Tif::Ioc);

    CHECK_EQ(r.filled, 40u);
    CHECK_EQ(r.remaining, 60u);
    CHECK(r.cancelled);
    CHECK(!r.resting);
    CHECK(book.empty());  // nothing left behind on either side
}

TEST(fok_rejects_without_touching_the_book) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 40, Tif::Gtc, 1);

    const auto r = limit(book, Side::Buy, 10'000, 100, Tif::Fok);

    CHECK(!r.accepted());
    CHECK_EQ(static_cast<int>(r.reject), static_cast<int>(Reject::FillOrKill));
    CHECK(r.trades.empty());
    CHECK_EQ(r.filled, 0u);
    CHECK_EQ(book.qty_at(Side::Sell, 10'000), 40u);  // untouched
    CHECK_EQ(book.stats().trades, 0u);
}

TEST(fok_fills_completely_when_it_can) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 40, Tif::Gtc, 1);
    limit(book, Side::Sell, 10'010, 80, Tif::Gtc, 2);

    const auto r = limit(book, Side::Buy, 10'010, 100, Tif::Fok);

    CHECK(r.accepted());
    CHECK_EQ(r.filled, 100u);
    CHECK_EQ(r.remaining, 0u);
    CHECK_EQ(r.trades.size(), 2u);
    CHECK_EQ(book.qty_at(Side::Sell, 10'010), 20u);
    CHECK(book.invariants_hold());
}

TEST(fok_ignores_liquidity_it_cannot_reach) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 40, Tif::Gtc, 1);
    limit(book, Side::Sell, 20'000, 400, Tif::Gtc, 2);  // far away, not crossable

    const auto r = limit(book, Side::Buy, 10'000, 100, Tif::Fok);

    CHECK(!r.accepted());
    CHECK_EQ(static_cast<int>(r.reject), static_cast<int>(Reject::FillOrKill));
}

TEST(market_order_sweeps_and_never_rests) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 30, Tif::Gtc, 1);
    limit(book, Side::Sell, 10'050, 30, Tif::Gtc, 2);

    const auto r = market(book, Side::Buy, 100);

    CHECK_EQ(r.filled, 60u);
    CHECK_EQ(r.remaining, 40u);
    CHECK(r.cancelled);
    CHECK(!r.resting);
    CHECK(book.empty());
}

TEST(market_order_needs_a_book) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);  // same side only

    const auto r = market(book, Side::Buy, 10);

    CHECK(!r.accepted());
    CHECK_EQ(static_cast<int>(r.reject), static_cast<int>(Reject::NoLiquidity));
    CHECK_EQ(book.stats().orders_rejected, 1u);
}

TEST(rejects_zero_quantity_and_duplicate_ids) {
    OrderBook book;
    CHECK(!limit(book, Side::Buy, 10'000, 0).accepted());

    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 5);
    const auto dup = limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 5);

    CHECK(!dup.accepted());
    CHECK_EQ(static_cast<int>(dup.reject), static_cast<int>(Reject::DuplicateId));
    CHECK_EQ(book.qty_at(Side::Buy, 10'000), 100u);
}

TEST(amend_down_keeps_queue_position) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 2);

    const auto a = book.amend(1, 10'000, 40);
    CHECK(a.accepted());
    CHECK(a.resting);
    CHECK_EQ(book.qty_at(Side::Buy, 10'000), 140u);

    const auto r = limit(book, Side::Sell, 10'000, 40);
    CHECK_EQ(r.trades.at(0).maker_id, 1u);  // still at the front
    CHECK(book.invariants_hold());
}

TEST(amend_up_goes_to_the_back_of_the_queue) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 2);

    book.amend(1, 10'000, 200);  // more size, so it loses priority
    CHECK_EQ(book.qty_at(Side::Buy, 10'000), 300u);

    const auto r = limit(book, Side::Sell, 10'000, 100);
    CHECK_EQ(r.trades.at(0).maker_id, 2u);
}

TEST(amend_across_the_spread_trades_immediately) {
    OrderBook book;
    limit(book, Side::Sell, 10'050, 100, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 2);

    const auto r = book.amend(2, 10'050, 100);

    CHECK(r.accepted());
    CHECK_EQ(r.filled, 100u);
    CHECK(book.empty());
}

TEST(amend_to_zero_cancels_and_unknown_ids_reject) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);

    const auto zero = book.amend(1, 10'000, 0);
    CHECK(zero.cancelled);
    CHECK(book.empty());

    const auto missing = book.amend(42, 10'000, 10);
    CHECK(!missing.accepted());
    CHECK_EQ(static_cast<int>(missing.reject), static_cast<int>(Reject::UnknownOrder));
}

TEST(partially_filled_order_reports_its_fill_history) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 100, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 30, Tif::Gtc, 2);

    // Reducing to 50 after 30 already traded leaves filled() consistent.
    book.amend(1, 10'000, 50);
    CHECK_EQ(book.qty_at(Side::Sell, 10'000), 50u);
    CHECK(book.invariants_hold());
}

TEST(depth_aggregates_orders_per_level) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 10, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 20, Tif::Gtc, 2);
    limit(book, Side::Buy, 9'900, 30, Tif::Gtc, 3);

    const auto bids = book.depth(Side::Buy, 10);

    CHECK_EQ(bids.size(), 2u);
    CHECK_EQ(bids.at(0).price, 10'000);  // best first
    CHECK_EQ(bids.at(0).qty, 30u);
    CHECK_EQ(bids.at(0).orders, 2u);
    CHECK_EQ(bids.at(1).price, 9'900);
    CHECK_EQ(book.level_count(Side::Buy), 2u);

    CHECK_EQ(book.depth(Side::Buy, 1).size(), 1u);  // honours the cap
}

TEST(stats_track_volume_and_notional) {
    OrderBook book;
    limit(book, Side::Sell, 10'000, 100, Tif::Gtc, 1);
    limit(book, Side::Buy, 10'000, 60, Tif::Gtc, 2);

    CHECK_EQ(book.stats().trades, 1u);
    CHECK_EQ(book.stats().volume, 60u);
    CHECK_EQ(book.stats().notional, 600'000u);  // 60 * 10'000 ticks
    CHECK_EQ(book.stats().orders_accepted, 2u);
    CHECK_EQ(book.tape().size(), 1u);
}

TEST(clear_resets_everything) {
    OrderBook book;
    limit(book, Side::Buy, 10'000, 100, Tif::Gtc, 1);
    book.clear();

    CHECK(book.empty());
    CHECK_EQ(book.stats().orders_accepted, 0u);
    CHECK(book.tape().empty());
    CHECK(book.invariants_hold());
}
