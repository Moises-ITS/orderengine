#include "ob/order_book.hpp"

#include <algorithm>
#include <cstdio>
#include <utility>

namespace ob {

const char* to_string(Side s) noexcept { return s == Side::Buy ? "BUY" : "SELL"; }

const char* to_string(OrderType t) noexcept {
    return t == OrderType::Limit ? "LIMIT" : "MARKET";
}

const char* to_string(Tif t) noexcept {
    switch (t) {
        case Tif::Gtc: return "GTC";
        case Tif::Ioc: return "IOC";
        case Tif::Fok: return "FOK";
    }
    return "?";
}

const char* to_string(Reject r) noexcept {
    switch (r) {
        case Reject::None:         return "none";
        case Reject::ZeroQuantity: return "zero quantity";
        case Reject::DuplicateId:  return "duplicate order id";
        case Reject::NoLiquidity:  return "no liquidity for market order";
        case Reject::FillOrKill:   return "fill-or-kill could not be filled";
        case Reject::UnknownOrder: return "unknown order id";
    }
    return "?";
}

std::string format_price(Price ticks) {
    const bool  neg = ticks < 0;
    const Price mag = neg ? -ticks : ticks;
    char        buf[32];
    std::snprintf(buf, sizeof(buf), "%s%lld.%02lld", neg ? "-" : "",
                  static_cast<long long>(mag / kTickScale),
                  static_cast<long long>(mag % kTickScale));
    return buf;
}

OrderBook::OrderBook(std::string symbol) : symbol_(std::move(symbol)) {}

OrderBook::Level* OrderBook::level_at(Side side, Price price) {
    if (side == Side::Buy) {
        auto it = bids_.find(price);
        return it == bids_.end() ? nullptr : &it->second;
    }
    auto it = asks_.find(price);
    return it == asks_.end() ? nullptr : &it->second;
}

void OrderBook::erase_level(Side side, Price price) {
    if (side == Side::Buy) {
        bids_.erase(price);
    } else {
        asks_.erase(price);
    }
}

// Liquidity a taker could consume right now, without mutating anything.
// Only Fok needs this, so it stops as soon as it knows the answer.
template <class Book>
Qty OrderBook::available(const Book& book, const Order& taker) const {
    Qty total = 0;
    for (const auto& [price, level] : book) {
        if (taker.type == OrderType::Limit && !crosses(taker.side, taker.price, price)) {
            break;  // the map is price-ordered, so nothing deeper can cross either
        }
        total += level.total;
        if (total >= taker.remaining) break;
    }
    return total;
}

// Walks the opposite book best-price-first, and within a price level
// front-to-back, which is exactly price-time priority. Trades print at the
// resting order's price: the maker set the terms, the taker accepted them.
template <class Book>
void OrderBook::match(Book& book, Order& taker, ExecReport& report) {
    while (taker.remaining > 0 && !book.empty()) {
        auto        level_it    = book.begin();
        const Price maker_price = level_it->first;

        if (taker.type == OrderType::Limit &&
            !crosses(taker.side, taker.price, maker_price)) {
            break;
        }

        Level& level = level_it->second;
        auto&  queue = level.orders;

        while (taker.remaining > 0 && !queue.empty()) {
            Order&    maker = queue.front();
            const Qty fill  = std::min(taker.remaining, maker.remaining);

            taker.remaining -= fill;
            maker.remaining -= fill;
            level.total     -= fill;

            const Trade t{next_seq_++, taker.id, maker.id, taker.side, maker_price, fill};
            record(t);
            report.trades.push_back(t);
            report.filled += fill;

            if (maker.remaining == 0) {
                index_.erase(maker.id);
                queue.pop_front();
            }
        }

        if (queue.empty()) book.erase(level_it);
    }
}

void OrderBook::record(const Trade& t) {
    ++stats_.trades;
    stats_.volume   += t.qty;
    stats_.notional += static_cast<std::uint64_t>(t.price) * t.qty;

    if (tape_limit_ > 0 && tape_.size() >= tape_limit_) {
        // Drop the oldest half in one shift so trimming stays amortised O(1).
        tape_.erase(tape_.begin(),
                    tape_.begin() + static_cast<std::ptrdiff_t>(tape_limit_ / 2));
    }
    tape_.push_back(t);
}

void OrderBook::rest(Order order) {
    if (order.side == Side::Buy) {
        Level& level = bids_[order.price];
        level.total += order.remaining;
        level.orders.push_back(order);
        index_[order.id] = Locator{Side::Buy, order.price, std::prev(level.orders.end())};
    } else {
        Level& level = asks_[order.price];
        level.total += order.remaining;
        level.orders.push_back(order);
        index_[order.id] = Locator{Side::Sell, order.price, std::prev(level.orders.end())};
    }
}

ExecReport OrderBook::submit(Order order) {
    ExecReport report;

    if (order.id == 0) {
        order.id = next_id_++;
    } else {
        next_id_ = std::max(next_id_, order.id + 1);
    }
    report.id = order.id;

    if (order.qty == 0) {
        report.reject = Reject::ZeroQuantity;
        ++stats_.orders_rejected;
        return report;
    }
    if (index_.contains(order.id)) {
        report.reject = Reject::DuplicateId;
        ++stats_.orders_rejected;
        return report;
    }

    order.remaining = order.qty;
    order.seq       = next_seq_++;

    const bool buy = order.side == Side::Buy;

    // Pre-trade checks that need to look at the book run before any state
    // changes, so a reject leaves the book exactly as it was.
    if (order.type == OrderType::Market && (buy ? asks_.empty() : bids_.empty())) {
        report.reject = Reject::NoLiquidity;
        ++stats_.orders_rejected;
        return report;
    }
    if (order.tif == Tif::Fok) {
        const Qty fillable = buy ? available(asks_, order) : available(bids_, order);
        if (fillable < order.remaining) {
            report.reject = Reject::FillOrKill;
            ++stats_.orders_rejected;
            return report;
        }
    }

    ++stats_.orders_accepted;

    if (buy) {
        match(asks_, order, report);
    } else {
        match(bids_, order, report);
    }

    report.remaining = order.remaining;

    if (order.remaining > 0) {
        if (order.type == OrderType::Limit && order.tif == Tif::Gtc) {
            rest(order);
            report.resting = true;
        } else {
            report.cancelled = true;  // Ioc remainder, or unfilled market size
        }
    }
    return report;
}

bool OrderBook::cancel(OrderId id) {
    const auto it = index_.find(id);
    if (it == index_.end()) return false;

    const Locator loc = it->second;
    index_.erase(it);

    Level* level = level_at(loc.side, loc.price);
    level->total -= loc.it->remaining;
    level->orders.erase(loc.it);
    if (level->orders.empty()) erase_level(loc.side, loc.price);
    return true;
}

ExecReport OrderBook::amend(OrderId id, Price new_price, Qty new_qty) {
    ExecReport report;
    report.id = id;

    const auto it = index_.find(id);
    if (it == index_.end()) {
        report.reject = Reject::UnknownOrder;
        ++stats_.orders_rejected;
        return report;
    }

    const Locator loc     = it->second;
    Order&        current = *loc.it;

    if (new_qty == 0) {
        cancel(id);
        report.cancelled = true;
        return report;
    }

    // A pure size reduction at the same price keeps queue position, exactly as
    // a real venue would: the order never leaves the queue.
    if (new_price == loc.price && new_qty < current.remaining) {
        const Qty delta = current.remaining - new_qty;
        current.remaining = new_qty;
        current.qty      -= delta;  // keeps filled() == qty - remaining honest
        level_at(loc.side, loc.price)->total -= delta;
        report.remaining = new_qty;
        report.resting   = true;
        return report;
    }

    // Anything else is a cancel/replace: a new price or more size sends the
    // order to the back of the queue, and it may now trade immediately.
    Order replacement = current;
    cancel(id);
    replacement.price     = new_price;
    replacement.qty       = new_qty;
    replacement.remaining = new_qty;
    return submit(replacement);
}

std::optional<Price> OrderBook::best_bid() const {
    if (bids_.empty()) return std::nullopt;
    return bids_.begin()->first;
}

std::optional<Price> OrderBook::best_ask() const {
    if (asks_.empty()) return std::nullopt;
    return asks_.begin()->first;
}

std::optional<Price> OrderBook::spread() const {
    if (bids_.empty() || asks_.empty()) return std::nullopt;
    return asks_.begin()->first - bids_.begin()->first;
}

Qty OrderBook::qty_at(Side side, Price price) const {
    if (side == Side::Buy) {
        const auto it = bids_.find(price);
        return it == bids_.end() ? 0 : it->second.total;
    }
    const auto it = asks_.find(price);
    return it == asks_.end() ? 0 : it->second.total;
}

std::size_t OrderBook::level_count(Side side) const noexcept {
    return side == Side::Buy ? bids_.size() : asks_.size();
}

std::vector<OrderBook::LevelView> OrderBook::depth(Side side, std::size_t max_levels) const {
    std::vector<LevelView> out;
    out.reserve(std::min(max_levels, level_count(side)));

    const auto take = [&](const auto& book) {
        for (const auto& [price, level] : book) {
            if (out.size() >= max_levels) break;
            out.push_back(LevelView{price, level.total, level.orders.size()});
        }
    };
    if (side == Side::Buy) {
        take(bids_);
    } else {
        take(asks_);
    }
    return out;
}

void OrderBook::clear() {
    bids_.clear();
    asks_.clear();
    index_.clear();
    tape_.clear();
    stats_    = Stats{};
    next_seq_ = 1;
    next_id_  = 1;
}

bool OrderBook::invariants_hold() const {
    if (!bids_.empty() && !asks_.empty() &&
        bids_.begin()->first >= asks_.begin()->first) {
        return false;  // a crossed book means match() stopped too early
    }

    std::size_t counted = 0;
    const auto  check   = [&](const auto& book) {
        for (const auto& [price, level] : book) {
            if (level.orders.empty()) return false;  // empty levels must be erased
            Qty sum = 0;
            for (const Order& o : level.orders) {
                if (o.remaining == 0) return false;
                if (o.price != price) return false;
                sum += o.remaining;
            }
            if (sum != level.total) return false;
            counted += level.orders.size();
        }
        return true;
    };
    if (!check(bids_) || !check(asks_)) return false;
    return counted == index_.size();
}

}  // namespace ob
