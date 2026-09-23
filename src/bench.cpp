#include "ob/bench.hpp"

#include <chrono>
#include <cstdio>
#include <random>
#include <vector>

#include "ob/order_book.hpp"

namespace ob {

namespace {
using Clock = std::chrono::steady_clock;

enum class Op : std::uint8_t { Submit, Cancel };

struct Action {
    Op      op{};
    Order   order{};
    OrderId target{};  // for Cancel
};

// A synthetic flow with the shape real venues see: mostly passive quoting near
// the touch, a minority of aggressive takers, and a lot of cancels.
std::vector<Action> generate(std::size_t n, std::uint64_t seed) {
    std::mt19937_64 rng{seed};
    const auto      pick = [&rng](std::uint64_t n_max) { return rng() % n_max; };

    std::vector<Action>  stream;
    std::vector<OrderId> live;  // ids we have submitted as resting candidates
    stream.reserve(n);
    live.reserve(n);

    Price   mid     = 10'000;  // 100.00
    OrderId next_id = 1;

    for (std::size_t i = 0; i < n; ++i) {
        if (i % 16 == 0) {
            mid += static_cast<Price>(pick(3)) - 1;  // -1, 0 or +1 tick
            if (mid < 1'000) mid = 1'000;
        }

        const std::uint64_t roll = pick(100);
        const Side          side = pick(2) == 0 ? Side::Buy : Side::Sell;
        const Qty           qty  = 10 * (1 + pick(20));

        Action a;
        a.op                = Op::Submit;
        a.order.id          = next_id;
        a.order.side        = side;
        a.order.qty         = qty;
        a.order.type        = OrderType::Limit;
        a.order.tif         = Tif::Gtc;

        if (roll < 55) {
            // Passive: rests one to twenty ticks behind the touch.
            const Price back = 1 + static_cast<Price>(pick(20));
            a.order.price    = side == Side::Buy ? mid - back : mid + back;
        } else if (roll < 76) {
            // Aggressive limit: crosses the touch by a few ticks.
            const Price into = static_cast<Price>(pick(6));
            a.order.price    = side == Side::Buy ? mid + into : mid - into;
        } else if (roll < 82) {
            a.order.tif   = Tif::Ioc;
            a.order.price = side == Side::Buy ? mid + 3 : mid - 3;
        } else if (roll < 87) {
            a.order.type  = OrderType::Market;
            a.order.qty   = 10 * (1 + pick(5));
        } else if (roll < 90) {
            a.order.tif   = Tif::Fok;
            a.order.qty   = 10 * (1 + pick(3));
            a.order.price = side == Side::Buy ? mid + 2 : mid - 2;
        } else if (!live.empty()) {
            // Cancel an earlier order. It may already be filled or gone, and a
            // cancel that misses is just as real as one that hits.
            a.op     = Op::Cancel;
            a.target = live[pick(live.size())];
            stream.push_back(a);
            continue;
        } else {
            a.order.price = side == Side::Buy ? mid - 1 : mid + 1;
        }

        live.push_back(next_id);
        ++next_id;
        stream.push_back(a);
    }
    return stream;
}
}  // namespace

BenchResult run_benchmark(std::size_t operations, std::uint64_t seed, bool record_latency) {
    const std::vector<Action> stream = generate(operations, seed);

    OrderBook book{"BENCH"};
    book.set_tape_limit(1'024);  // keep the tape bounded, we only want the counts

    BenchResult result;
    result.operations       = stream.size();
    result.latency_measured = record_latency;

    const auto wall_start = Clock::now();
    if (record_latency) {
        for (const Action& a : stream) {
            const auto start = Clock::now();
            if (a.op == Op::Submit) {
                book.submit(a.order);
            } else {
                book.cancel(a.target);
            }
            result.latency.record(static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start)
                    .count()));
        }
    } else {
        for (const Action& a : stream) {
            if (a.op == Op::Submit) {
                book.submit(a.order);
            } else {
                book.cancel(a.target);
            }
        }
    }
    const auto wall_end = Clock::now();

    result.seconds =
        std::chrono::duration<double>(wall_end - wall_start).count();

    for (const Action& a : stream) {
        if (a.op == Op::Submit) {
            ++result.submits;
        } else {
            ++result.cancels;
        }
    }
    result.trades         = book.stats().trades;
    result.volume         = book.stats().volume;
    result.resting_orders = book.open_orders();
    result.resting_levels =
        book.level_count(Side::Buy) + book.level_count(Side::Sell);
    return result;
}

std::string format_bench(const BenchResult& r) {
    char buf[1'024];
    std::snprintf(buf, sizeof(buf),
                  "  operations   %llu  (%llu submits, %llu cancels)\n"
                  "  elapsed      %.3f s\n"
                  "  throughput   %.0f ops/sec\n"
                  "  trades       %llu\n"
                  "  volume       %llu shares\n"
                  "  book left    %llu orders across %llu levels\n",
                  static_cast<unsigned long long>(r.operations),
                  static_cast<unsigned long long>(r.submits),
                  static_cast<unsigned long long>(r.cancels), r.seconds,
                  r.ops_per_sec(), static_cast<unsigned long long>(r.trades),
                  static_cast<unsigned long long>(r.volume),
                  static_cast<unsigned long long>(r.resting_orders),
                  static_cast<unsigned long long>(r.resting_levels));

    std::string out = buf;
    if (r.latency_measured) {
        out += "  latency      " + r.latency.summary() + "\n";
    }
    return out;
}

}  // namespace ob
