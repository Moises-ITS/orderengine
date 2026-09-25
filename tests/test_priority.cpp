#include <algorithm>
#include <cstdint>
#include <optional>
#include <random>
#include <string>
#include <vector>

#include "ob/order_book.hpp"
#include "test_harness.hpp"

using namespace ob;

namespace {

// A deliberately naive book that states price-time priority as directly as
// possible: resting orders sit in one vector in arrival order, and each fill
// scans the whole thing for the best price, taking the earliest arrival on a
// tie. Slow, but hard to get wrong, which is the point of a reference model.
class ReferenceBook {
public:
    struct Fill {
        OrderId maker_id;
        Price   price;
        Qty     qty;
    };

    struct Result {
        std::vector<Fill> fills;
        bool              rejected{false};
        bool              resting{false};
    };

    Result submit(OrderId id, Side side, OrderType type, Price price, Qty qty) {
        Result r;
        const bool any_maker = std::any_of(resting_.begin(), resting_.end(),
                                           [&](const Resting& o) { return o.side != side; });
        if (type == OrderType::Market && !any_maker) {
            r.rejected = true;
            return r;
        }

        while (qty > 0) {
            const auto best = best_maker(side, type, price);
            if (best == resting_.end()) break;

            const Qty fill = std::min(qty, best->remaining);
            r.fills.push_back(Fill{best->id, best->price, fill});
            qty             -= fill;
            best->remaining -= fill;
            if (best->remaining == 0) resting_.erase(best);
        }

        if (qty > 0 && type == OrderType::Limit) {
            resting_.push_back(Resting{id, side, price, qty});
            r.resting = true;
        }
        return r;
    }

    bool cancel(OrderId id) {
        const auto it = std::find_if(resting_.begin(), resting_.end(),
                                     [&](const Resting& o) { return o.id == id; });
        if (it == resting_.end()) return false;
        resting_.erase(it);
        return true;
    }

    // True when `book` holds exactly these orders at the same best prices.
    bool agrees_with(const OrderBook& book) const {
        if (book.open_orders() != resting_.size()) return false;
        std::optional<Price> bid, ask;
        for (const Resting& o : resting_) {
            if (!book.contains(o.id)) return false;
            auto&      best  = o.side == Side::Buy ? bid : ask;
            const bool buy   = o.side == Side::Buy;
            if (!best || (buy ? o.price > *best : o.price < *best)) best = o.price;
        }
        return book.best_bid() == bid && book.best_ask() == ask;
    }

private:
    struct Resting {
        OrderId id;
        Side    side;
        Price   price;
        Qty     remaining;
    };

    std::vector<Resting>::iterator best_maker(Side taker, OrderType type, Price limit) {
        auto best = resting_.end();
        for (auto it = resting_.begin(); it != resting_.end(); ++it) {
            if (it->side == taker) continue;
            if (type == OrderType::Limit &&
                (taker == Side::Buy ? it->price > limit : it->price < limit)) {
                continue;  // does not cross
            }
            // Strictly better price only: on a tie the earlier arrival, which
            // sits earlier in the vector, keeps its claim.
            if (best == resting_.end() ||
                (taker == Side::Buy ? it->price < best->price : it->price > best->price)) {
                best = it;
            }
        }
        return best;
    }

    std::vector<Resting> resting_;
};

bool same_fills(const ExecReport& got, const ReferenceBook::Result& want) {
    if (got.trades.size() != want.fills.size()) return false;
    for (std::size_t i = 0; i < want.fills.size(); ++i) {
        const Trade& t = got.trades[i];
        const auto&  f = want.fills[i];
        if (t.maker_id != f.maker_id || t.price != f.price || t.qty != f.qty) return false;
    }
    return true;
}

// How often each situation the test is meant to exercise actually came up.
struct Coverage {
    std::uint64_t crossing_orders{};  // takers that traded at least once
    std::uint64_t partial_fills{};    // fills that left the maker resting
    std::uint64_t live_cancels{};     // cancels that removed a resting order
};

// Replays one random sequence of limit, market and cancel orders through both
// books. Returns an empty string on success, or what diverged first.
std::string replay(std::uint64_t seed, Coverage& cov) {
    constexpr int kEvents = 100;

    std::mt19937_64 rng{seed};
    const auto      pick = [&rng](std::uint64_t n) { return rng() % n; };

    OrderBook            book{"PRIO"};
    ReferenceBook        ref;
    std::vector<OrderId> ids;

    for (int i = 0; i < kEvents; ++i) {
        const std::uint64_t roll = pick(100);
        const std::string   at   = "event " + std::to_string(i) + ": ";

        if (roll < 15 && !ids.empty()) {
            // Includes ids already filled or cancelled: both books must refuse.
            const OrderId id = ids[pick(ids.size())];
            const bool removed = ref.cancel(id);
            if (book.cancel(id) != removed) return at + "cancel disagrees";
            cov.live_cancels += removed;
            continue;
        }

        Order o;
        o.side = pick(2) == 0 ? Side::Buy : Side::Sell;
        o.type = roll < 25 ? OrderType::Market : OrderType::Limit;
        o.tif  = o.type == OrderType::Market ? Tif::Ioc : Tif::Gtc;
        // A narrow band of prices and small sizes so that orders stack up at
        // the same price, cross often and fill partially.
        o.price = 10'000 + static_cast<Price>(pick(7)) - 3;
        o.qty   = 1 + pick(20);

        const ExecReport got  = book.submit(o);
        const auto       want = ref.submit(got.id, o.side, o.type, o.price, o.qty);
        ids.push_back(got.id);

        if ((got.reject == Reject::NoLiquidity) != want.rejected) return at + "reject disagrees";
        if (!same_fills(got, want)) return at + "fills disagree";
        if (got.resting != want.resting) return at + "resting disagrees";

        if (!got.trades.empty()) ++cov.crossing_orders;
        for (const Trade& t : got.trades) cov.partial_fills += book.contains(t.maker_id);
    }

    if (!ref.agrees_with(book)) return "final book disagrees";
    if (!book.invariants_hold()) return "invariants broken";
    return {};
}

}  // namespace

TEST(price_time_priority_matches_a_reference_model) {
    // 10,000 independent random sequences, each checked fill by fill (maker,
    // price and size, in order) against the reference book above.
    constexpr std::uint64_t kSequences = 10'000;

    Coverage cov;
    for (std::uint64_t seed = 1; seed <= kSequences; ++seed) {
        const std::string err = replay(seed, cov);
        CHECK(err.empty());
        if (!err.empty()) {
            test::fail(__FILE__, __LINE__, "seed " + std::to_string(seed) + ", " + err);
            return;
        }
    }

    // Guard against the generator drifting into a shape that no longer tests
    // what this is meant to test.
    CHECK(cov.crossing_orders > 100'000u);
    CHECK(cov.partial_fills > 100'000u);
    CHECK(cov.live_cancels > 10'000u);
}
