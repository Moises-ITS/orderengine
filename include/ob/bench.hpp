#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "ob/latency.hpp"
#include "ob/types.hpp"

namespace ob {

struct BenchResult {
    std::uint64_t    operations{};   // submits + cancels fed to the book
    std::uint64_t    submits{};
    std::uint64_t    cancels{};
    std::uint64_t    trades{};
    Qty              volume{};
    std::size_t      resting_orders{};
    std::size_t      resting_levels{};
    double           seconds{};
    bool             latency_measured{false};
    LatencyHistogram latency;

    double ops_per_sec() const {
        return seconds > 0.0 ? static_cast<double>(operations) / seconds : 0.0;
    }
};

// Replays a synthetic but realistic order flow -- passive quotes around a
// random-walking mid, aggressive takers, IOC/FOK/market orders and cancels --
// against a single book. The whole stream is generated up front so that the
// timed section contains nothing but matching-engine work.
//
// `record_latency` adds two clock reads per operation. Leave it off for the
// honest throughput number, on for the distribution.
BenchResult run_benchmark(std::size_t operations, std::uint64_t seed = 42,
                          bool record_latency = false);

std::string format_bench(const BenchResult& r);

}  // namespace ob
