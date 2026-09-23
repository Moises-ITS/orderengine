#pragma once

#include <map>
#include <string>
#include <vector>

#include "ob/latency.hpp"
#include "ob/order_book.hpp"
#include "ob/types.hpp"

namespace ob {

// Owns one OrderBook per symbol and times every submission.
//
// Each book is an independent state machine, which is why real venues shard
// symbols across threads and keep a single writer per book. This prototype runs
// them on the calling thread so a replay is bit-for-bit deterministic.
class MatchingEngine {
public:
    OrderBook&       book(const std::string& symbol);  // creates on first use
    const OrderBook* find(const std::string& symbol) const;

    // Order ids are handed out engine-wide so an id identifies an order
    // uniquely, whatever symbol it belongs to.
    OrderId next_order_id() noexcept { return next_id_++; }

    ExecReport submit(const std::string& symbol, Order order);
    bool       cancel(const std::string& symbol, OrderId id);
    ExecReport amend(const std::string& symbol, OrderId id, Price price, Qty qty);

    std::vector<std::string> symbols() const;

    LatencyHistogram&       latency() noexcept { return latency_; }
    const LatencyHistogram& latency() const noexcept { return latency_; }

    // Set false around benchmarks where clock reads would dominate the work.
    void measure_latency(bool on) noexcept { measure_ = on; }

    OrderBook::Stats totals() const;
    void             reset();

private:
    OrderBook* lookup(const std::string& symbol);

    std::map<std::string, OrderBook> books_;
    LatencyHistogram                 latency_;
    bool                             measure_{true};
    OrderId                          next_id_{1};
};

}  // namespace ob
