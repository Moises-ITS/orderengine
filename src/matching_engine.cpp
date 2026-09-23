#include "ob/matching_engine.hpp"

#include <chrono>

namespace ob {

namespace {
using Clock = std::chrono::steady_clock;

std::uint64_t elapsed_ns(Clock::time_point start) {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count());
}
}  // namespace

OrderBook& MatchingEngine::book(const std::string& symbol) {
    const auto it = books_.find(symbol);
    if (it != books_.end()) return it->second;
    return books_.emplace(symbol, OrderBook{symbol}).first->second;
}

const OrderBook* MatchingEngine::find(const std::string& symbol) const {
    const auto it = books_.find(symbol);
    return it == books_.end() ? nullptr : &it->second;
}

OrderBook* MatchingEngine::lookup(const std::string& symbol) {
    const auto it = books_.find(symbol);
    return it == books_.end() ? nullptr : &it->second;
}

ExecReport MatchingEngine::submit(const std::string& symbol, Order order) {
    OrderBook& b = book(symbol);
    if (order.id == 0) order.id = next_order_id();

    if (!measure_) return b.submit(order);

    const auto start  = Clock::now();
    ExecReport report = b.submit(order);
    latency_.record(elapsed_ns(start));
    return report;
}

bool MatchingEngine::cancel(const std::string& symbol, OrderId id) {
    OrderBook* b = lookup(symbol);
    if (b == nullptr) return false;

    if (!measure_) return b->cancel(id);

    const auto start = Clock::now();
    const bool ok    = b->cancel(id);
    latency_.record(elapsed_ns(start));
    return ok;
}

ExecReport MatchingEngine::amend(const std::string& symbol, OrderId id, Price price, Qty qty) {
    OrderBook* b = lookup(symbol);
    if (b == nullptr) {
        ExecReport report;
        report.id     = id;
        report.reject = Reject::UnknownOrder;
        return report;
    }
    return b->amend(id, price, qty);
}

std::vector<std::string> MatchingEngine::symbols() const {
    std::vector<std::string> out;
    out.reserve(books_.size());
    for (const auto& [name, unused] : books_) out.push_back(name);
    return out;
}

OrderBook::Stats MatchingEngine::totals() const {
    OrderBook::Stats sum{};
    for (const auto& [name, b] : books_) {
        const OrderBook::Stats& s = b.stats();
        sum.orders_accepted += s.orders_accepted;
        sum.orders_rejected += s.orders_rejected;
        sum.trades          += s.trades;
        sum.volume          += s.volume;
        sum.notional        += s.notional;
    }
    return sum;
}

void MatchingEngine::reset() {
    books_.clear();
    latency_.reset();
    next_id_ = 1;
}

}  // namespace ob
