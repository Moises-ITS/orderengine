#pragma once

#include <cstddef>
#include <functional>
#include <list>
#include <map>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "ob/types.hpp"

namespace ob {

// A single-symbol limit order book with price-time priority.
//
// Structure
//   bids_ : std::map<Price, Level, std::greater<>>  -> begin() is the best bid
//   asks_ : std::map<Price, Level, std::less<>>     -> begin() is the best ask
//   Level : std::list<Order> in arrival order       -> FIFO gives time priority
//   index_: OrderId -> {side, price, list iterator} -> O(1) cancel / amend
//
// std::list is the deliberate choice for the level queue: splicing an order out
// of the middle on cancel is O(1) and, unlike a vector or a priority_queue,
// iterators to the other orders stay valid, which is what lets index_ work.
class OrderBook {
public:
    struct LevelView {
        Price       price{};
        Qty         qty{};
        std::size_t orders{};
    };

    struct Stats {
        std::uint64_t orders_accepted{};
        std::uint64_t orders_rejected{};
        std::uint64_t trades{};
        Qty           volume{};          // shares traded
        std::uint64_t notional{};        // sum(price * qty), in ticks
    };

    explicit OrderBook(std::string symbol = "TEST");

    const std::string& symbol() const noexcept { return symbol_; }

    // Submits an order, matching it against the resting book first. `order.id`
    // of 0 means "assign one for me". `remaining`/`seq` are set internally.
    ExecReport submit(Order order);

    // Removes a resting order. False if the id is not live.
    bool cancel(OrderId id);

    // Exchange-style amend: a pure size reduction keeps the order's place in
    // the queue, anything else is a cancel/replace and loses time priority.
    ExecReport amend(OrderId id, Price new_price, Qty new_qty);

    std::optional<Price> best_bid() const;
    std::optional<Price> best_ask() const;
    // best_ask - best_bid, only when both sides are populated.
    std::optional<Price> spread() const;

    Qty         qty_at(Side side, Price price) const;
    bool        contains(OrderId id) const { return index_.contains(id); }
    std::size_t open_orders() const noexcept { return index_.size(); }
    bool        empty() const noexcept { return index_.empty(); }

    // Number of distinct populated price levels on a side.
    std::size_t level_count(Side side) const noexcept;

    // Aggregated ladder, best price first.
    std::vector<LevelView> depth(Side side, std::size_t max_levels) const;

    const std::vector<Trade>& tape() const noexcept { return tape_; }
    const Stats&              stats() const noexcept { return stats_; }

    // Trades are kept for inspection; cap the tape when replaying big feeds.
    void set_tape_limit(std::size_t n) { tape_limit_ = n; }

    void clear();

    // Debug aid: the book must never be crossed once submit() returns.
    bool invariants_hold() const;

private:
    using Queue = std::list<Order>;

    struct Level {
        Qty   total{};  // cached sum of remaining qty, so depth() stays O(levels)
        Queue orders;
    };

    using BidMap = std::map<Price, Level, std::greater<Price>>;
    using AskMap = std::map<Price, Level, std::less<Price>>;

    struct Locator {
        Side           side{};
        Price          price{};
        Queue::iterator it{};
    };

    // A taker at `taker_price` crosses a maker at `maker_price` when it is
    // willing to pay at least as much (buy) or accept at most as much (sell).
    static bool crosses(Side taker_side, Price taker_price, Price maker_price) noexcept {
        return taker_side == Side::Buy ? taker_price >= maker_price
                                       : taker_price <= maker_price;
    }

    Level* level_at(Side side, Price price);
    void   erase_level(Side side, Price price);

    template <class Book>
    Qty available(const Book& book, const Order& taker) const;

    template <class Book>
    void match(Book& book, Order& taker, ExecReport& report);

    void rest(Order order);
    void record(const Trade& t);

    std::string        symbol_;
    BidMap             bids_;
    AskMap             asks_;
    std::unordered_map<OrderId, Locator> index_;
    std::vector<Trade> tape_;
    std::size_t        tape_limit_{4096};
    Stats              stats_{};
    Seq                next_seq_{1};
    OrderId            next_id_{1};
};

}  // namespace ob
