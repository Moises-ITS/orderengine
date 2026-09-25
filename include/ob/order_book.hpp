#pragma once

#include <cstddef>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "ob/object_pool.hpp"
#include "ob/types.hpp"

namespace ob {

// A single-symbol limit order book with price-time priority.
//
// Structure
//   bids_ : std::map<Price, Level, std::greater<>>  -> begin() is the best bid
//   asks_ : std::map<Price, Level, std::less<>>     -> begin() is the best ask
//   Level : intrusive FIFO list of OrderNodes       -> FIFO gives time priority
//   pool_ : ObjectPool<OrderNode>                   -> order nodes are recycled, not new/delete
//   index_: OrderId -> {node, level iterator}       -> O(1) cancel / amend
//
// Each resting order lives in a pool-allocated node linked into its level's
// queue. The index holds a pointer to that node and an iterator to its level,
// so a cancel is a hash lookup, an unlink and, if the level empties, an erase
// by iterator (amortised O(1)): it never searches the queue or the price map.
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

    // The index points into pool_ and the price maps, so a book stays put.
    OrderBook(const OrderBook&)            = delete;
    OrderBook& operator=(const OrderBook&) = delete;

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
    struct OrderNode {
        Order      order;
        OrderNode* prev{};
        OrderNode* next{};
    };

    struct Level {
        Qty         total{};  // cached sum of remaining qty, so depth() stays O(levels)
        std::size_t count{};
        OrderNode*  head{};   // oldest order: next to trade
        OrderNode*  tail{};
    };

    using BidMap = std::map<Price, Level, std::greater<Price>>;
    using AskMap = std::map<Price, Level, std::less<Price>>;

    // std::map iterators stay valid until their own element is erased, and a
    // level is only erased once no order points at it.
    struct Locator {
        Side             side{};
        OrderNode*       node{};
        BidMap::iterator bid{};  // valid when side == Buy
        AskMap::iterator ask{};  // valid when side == Sell
    };

    // A taker at `taker_price` crosses a maker at `maker_price` when it is
    // willing to pay at least as much (buy) or accept at most as much (sell).
    static bool crosses(Side taker_side, Price taker_price, Price maker_price) noexcept {
        return taker_side == Side::Buy ? taker_price >= maker_price
                                       : taker_price <= maker_price;
    }

    static void push_back(Level& level, OrderNode* node) noexcept;
    static void unlink(Level& level, OrderNode* node) noexcept;

    Level& level_of(const Locator& loc) noexcept {
        return loc.side == Side::Buy ? loc.bid->second : loc.ask->second;
    }

    template <class Book>
    Qty available(const Book& book, const Order& taker) const;

    template <class Book>
    void match(Book& book, Order& taker, ExecReport& report);

    void rest(Order order);
    void record(const Trade& t);

    std::string        symbol_;
    BidMap             bids_;
    AskMap             asks_;
    ObjectPool<OrderNode> pool_;
    std::unordered_map<OrderId, Locator> index_;
    std::vector<Trade> tape_;
    std::size_t        tape_limit_{4096};
    Stats              stats_{};
    Seq                next_seq_{1};
    OrderId            next_id_{1};
};

}  // namespace ob
