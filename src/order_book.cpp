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

void OrderBook::push_back(Level& level, OrderNode* node) noexcept {
    node->prev = level.tail;
    node->next = nullptr;
    if (level.tail != nullptr) {
        level.tail->next = node;
    } else {
        level.head = node;
    }
    level.tail   = node;
    level.total += node->order.remaining;
    ++level.count;
}

void OrderBook::unlink(Level& level, OrderNode* node) noexcept {
    (node->prev != nullptr ? node->prev->next : level.head) = node->next;
    (node->next != nullptr ? node->next->prev : level.tail) = node->prev;
    level.total -= node->order.remaining;
    --level.count;
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

        while (taker.remaining > 0 && level.head != nullptr) {
            OrderNode* node  = level.head;
            Order&     maker = node->order;
            const Qty  fill  = std::min(taker.remaining, maker.remaining);

            taker.remaining -= fill;
            maker.remaining -= fill;
            level.total     -= fill;

            const Trade t{next_seq_++, taker.id, maker.id, taker.side, maker_price, fill};
            record(t);
            report.trades.push_back(t);
            report.filled += fill;

            if (maker.remaining == 0) {
                index_.erase(maker.id);
                unlink(level, node);
                pool_.destroy(node);
            }
        }

        if (level.head == nullptr) book.erase(level_it);
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
    OrderNode* node = pool_.create(order);
    Locator    loc{order.side, node, {}, {}};
    if (order.side == Side::Buy) {
        loc.bid = bids_.try_emplace(order.price).first;
        push_back(loc.bid->second, node);
    } else {
        loc.ask = asks_.try_emplace(order.price).first;
        push_back(loc.ask->second, node);
    }
    index_.emplace(order.id, loc);
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

    // Every step is O(1): the node and its level come straight from the index,
    // and erasing a map element by iterator is amortised constant time.
    Level& level = level_of(loc);
    unlink(level, loc.node);
    pool_.destroy(loc.node);
    if (level.head == nullptr) {
        if (loc.side == Side::Buy) {
            bids_.erase(loc.bid);
        } else {
            asks_.erase(loc.ask);
        }
    }
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
    Order&        current = loc.node->order;

    if (new_qty == 0) {
        cancel(id);
        report.cancelled = true;
        return report;
    }

    // A pure size reduction at the same price keeps queue position, exactly as
    // a real venue would: the order never leaves the queue.
    if (new_price == current.price && new_qty < current.remaining) {
        const Qty delta = current.remaining - new_qty;
        current.remaining = new_qty;
        current.qty      -= delta;  // keeps filled() == qty - remaining honest
        level_of(loc).total -= delta;
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
            out.push_back(LevelView{price, level.total, level.count});
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
    pool_.clear();
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
            if (level.head == nullptr) return false;  // empty levels must be erased
            Qty         sum = 0;
            std::size_t n   = 0;
            for (const OrderNode* node = level.head; node != nullptr; node = node->next) {
                const Order& o = node->order;
                if (o.remaining == 0) return false;
                if (o.price != price) return false;
                if (node->next == nullptr && node != level.tail) return false;
                sum += o.remaining;
                ++n;
            }
            if (n != level.count) return false;
            if (sum != level.total) return false;
            counted += level.count;
        }
        return true;
    };
    if (!check(bids_) || !check(asks_)) return false;
    return counted == index_.size() && counted == pool_.live();
}

}  // namespace ob
