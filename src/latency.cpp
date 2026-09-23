#include "ob/latency.hpp"

#include <cstdio>
#include <string>

namespace ob {

std::string format_ns(std::uint64_t ns) {
    char buf[32];
    if (ns < 1'000) {
        std::snprintf(buf, sizeof(buf), "%lluns", static_cast<unsigned long long>(ns));
    } else if (ns < 1'000'000) {
        std::snprintf(buf, sizeof(buf), "%.2fus", static_cast<double>(ns) / 1e3);
    } else if (ns < 1'000'000'000) {
        std::snprintf(buf, sizeof(buf), "%.2fms", static_cast<double>(ns) / 1e6);
    } else {
        std::snprintf(buf, sizeof(buf), "%.2fs", static_cast<double>(ns) / 1e9);
    }
    return buf;
}

std::string LatencyHistogram::summary() const {
    if (count_ == 0) return "no samples";

    std::string out;
    out += "n=" + std::to_string(count_);
    out += "  min " + format_ns(min());
    out += "  mean " + format_ns(static_cast<std::uint64_t>(mean()));
    out += "  p50 " + format_ns(percentile(50));
    out += "  p90 " + format_ns(percentile(90));
    out += "  p99 " + format_ns(percentile(99));
    out += "  p99.9 " + format_ns(percentile(99.9));
    out += "  max " + format_ns(max());
    return out;
}

std::string LatencyHistogram::table() const {
    if (count_ == 0) return "no samples\n";

    std::string out = "          latency  count    share\n";
    std::uint64_t cumulative = 0;
    for (std::size_t i = 0; i < kBuckets; ++i) {
        if (buckets_[i] == 0) continue;
        cumulative += buckets_[i];

        const double share = 100.0 * static_cast<double>(buckets_[i]) /
                             static_cast<double>(count_);
        const auto   bars  = static_cast<int>(share / 2.0);  // 50 chars = 100%

        char line[160];
        std::snprintf(line, sizeof(line), "%17s  %7llu  %5.1f%% %s\n",
                      format_ns(lower_bound(i)).c_str(),
                      static_cast<unsigned long long>(buckets_[i]), share,
                      std::string(static_cast<std::size_t>(bars), '#').c_str());
        out += line;
    }
    return out;
}

}  // namespace ob
