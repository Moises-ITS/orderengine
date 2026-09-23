#include <random>
#include <string>

#include "ob/bench.hpp"
#include "ob/cli.hpp"
#include "ob/latency.hpp"
#include "ob/matching_engine.hpp"
#include "test_harness.hpp"

using namespace ob;

TEST(books_are_independent_per_symbol) {
    MatchingEngine engine;

    Order buy;
    buy.side  = Side::Buy;
    buy.price = 10'000;
    buy.qty   = 100;
    engine.submit("AAPL", buy);

    Order sell;
    sell.side  = Side::Sell;
    sell.price = 10'000;
    sell.qty   = 100;
    const auto r = engine.submit("MSFT", sell);

    // Same price, opposite sides, different symbols: they must not trade.
    CHECK(r.trades.empty());
    CHECK_EQ(engine.totals().trades, 0u);
    CHECK_EQ(engine.symbols().size(), 2u);
    CHECK_EQ(engine.find("AAPL")->best_bid().value(), 10'000);
    CHECK_EQ(engine.find("MSFT")->best_ask().value(), 10'000);
    CHECK(engine.find("TSLA") == nullptr);
}

TEST(engine_ids_are_unique_across_symbols) {
    MatchingEngine engine;

    Order o;
    o.side  = Side::Buy;
    o.price = 10'000;
    o.qty   = 10;

    const auto a = engine.submit("AAPL", o);
    const auto b = engine.submit("MSFT", o);

    CHECK(a.id != b.id);
    CHECK(engine.find("AAPL")->contains(a.id));
    CHECK(!engine.find("MSFT")->contains(a.id));
}

TEST(engine_cancel_needs_the_right_symbol) {
    MatchingEngine engine;

    Order o;
    o.side  = Side::Buy;
    o.price = 10'000;
    o.qty   = 10;
    const auto r = engine.submit("AAPL", o);

    CHECK(!engine.cancel("MSFT", r.id));  // wrong book
    CHECK(engine.cancel("AAPL", r.id));
}

TEST(engine_totals_aggregate_every_book) {
    MatchingEngine engine;

    for (const std::string& sym : {"AAPL", "MSFT"}) {
        Order sell;
        sell.side  = Side::Sell;
        sell.price = 10'000;
        sell.qty   = 50;
        engine.submit(sym, sell);

        Order buy;
        buy.side  = Side::Buy;
        buy.price = 10'000;
        buy.qty   = 50;
        engine.submit(sym, buy);
    }

    CHECK_EQ(engine.totals().trades, 2u);
    CHECK_EQ(engine.totals().volume, 100u);
    CHECK_EQ(engine.totals().orders_accepted, 4u);
    CHECK(engine.latency().count() == 4);  // every submit was timed
}

TEST(prices_parse_as_exact_ticks) {
    Price p = 0;

    CHECK(parse_price("100", p));
    CHECK_EQ(p, 10'000);
    CHECK(parse_price("100.5", p));
    CHECK_EQ(p, 10'050);
    CHECK(parse_price("100.25", p));
    CHECK_EQ(p, 10'025);
    CHECK(parse_price("0.01", p));
    CHECK_EQ(p, 1);
    CHECK(parse_price("0", p));
    CHECK_EQ(p, 0);

    CHECK(!parse_price("", p));
    CHECK(!parse_price("abc", p));
    CHECK(!parse_price("100.256", p));  // finer than a tick
    CHECK(!parse_price("1.2.3", p));
    CHECK(!parse_price("100.", p));
    CHECK(!parse_price("-1", p));
}

TEST(histogram_reports_exact_counts) {
    LatencyHistogram h;
    for (int i = 0; i < 1'000; ++i) h.record(1'000);

    CHECK_EQ(h.count(), 1'000u);
    CHECK_EQ(h.min(), 1'000u);
    CHECK_EQ(h.max(), 1'000u);
    CHECK(h.mean() > 999.0 && h.mean() < 1'001.0);

    // Bucket resolution at 1us is 64ns, so the reported percentile lands within
    // one bucket width of the truth.
    const auto p50 = h.percentile(50);
    CHECK(p50 > 1'000 - 64 && p50 < 1'000 + 64);
}

TEST(histogram_percentiles_are_ordered) {
    LatencyHistogram h;
    for (std::uint64_t ns = 1; ns <= 10'000; ++ns) h.record(ns);

    CHECK(h.percentile(50) < h.percentile(90));
    CHECK(h.percentile(90) < h.percentile(99));
    CHECK(h.percentile(99) <= h.max());
    CHECK_EQ(h.min(), 1u);
    CHECK_EQ(h.max(), 10'000u);

    // Roughly where a uniform 1..10000 distribution should put them.
    CHECK(h.percentile(50) > 4'500 && h.percentile(50) < 5'500);
    CHECK(h.percentile(90) > 8'500 && h.percentile(90) < 9'500);
}

TEST(histogram_handles_small_and_huge_values) {
    LatencyHistogram h;
    h.record(0);
    h.record(1);
    h.record(7);
    h.record(1'000'000'000);

    CHECK_EQ(h.count(), 4u);
    CHECK_EQ(h.min(), 0u);
    CHECK_EQ(h.max(), 1'000'000'000u);
    CHECK_EQ(h.percentile(0.1), 0u);  // small values are exact
    CHECK(h.percentile(100) >= 1'000'000'000u / 2);

    h.reset();
    CHECK_EQ(h.count(), 0u);
    CHECK_EQ(h.percentile(50), 0u);
}

TEST(random_flow_never_breaks_the_book) {
    // A deterministic fuzz: whatever the order of events, the book must stay
    // uncrossed, its cached totals must match its queues, and the shares the
    // takers report as filled must equal the volume the book recorded.
    OrderBook       book{"FUZZ"};
    std::mt19937_64 rng{12'345};
    const auto      pick = [&rng](std::uint64_t n) { return rng() % n; };

    std::vector<OrderId> seen;
    Qty                  taker_filled = 0;

    for (int i = 0; i < 20'000; ++i) {
        const std::uint64_t roll = pick(100);

        if (roll < 12 && !seen.empty()) {
            book.cancel(seen[pick(seen.size())]);
        } else if (roll < 20 && !seen.empty()) {
            Price       p = 9'950 + static_cast<Price>(pick(100));
            const auto  r = book.amend(seen[pick(seen.size())], p, 1 + pick(50));
            taker_filled += r.filled;
        } else {
            Order o;
            o.side  = pick(2) == 0 ? Side::Buy : Side::Sell;
            o.qty   = 1 + pick(200);
            o.price = 9'950 + static_cast<Price>(pick(100));
            o.type  = roll < 26 ? OrderType::Market : OrderType::Limit;
            o.tif   = roll < 30 ? Tif::Ioc : (roll < 34 ? Tif::Fok : Tif::Gtc);
            if (o.type == OrderType::Market) o.tif = Tif::Ioc;

            const auto r = book.submit(o);
            taker_filled += r.filled;
            if (r.resting) seen.push_back(r.id);
        }

        if (i % 500 == 0) CHECK(book.invariants_hold());
    }

    CHECK(book.invariants_hold());
    CHECK_EQ(taker_filled, book.stats().volume);
    CHECK(book.stats().trades > 0u);
}

TEST(benchmark_is_deterministic_for_a_seed) {
    const BenchResult a = run_benchmark(20'000, 7);
    const BenchResult b = run_benchmark(20'000, 7);
    const BenchResult c = run_benchmark(20'000, 8);

    CHECK_EQ(a.trades, b.trades);
    CHECK_EQ(a.volume, b.volume);
    CHECK_EQ(a.resting_orders, b.resting_orders);
    CHECK(a.trades != c.trades);  // a different seed is a different market
    CHECK(a.trades > 0u);
    CHECK(a.ops_per_sec() > 0.0);
    CHECK_EQ(a.submits + a.cancels, a.operations);
}
