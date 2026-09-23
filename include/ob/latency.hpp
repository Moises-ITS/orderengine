#pragma once

#include <array>
#include <bit>
#include <cstdint>
#include <string>

namespace ob {

// A small HdrHistogram-style latency recorder.
//
// Recording a sample is a shift, a mask and an increment: no allocation, no
// branching on data, so measuring barely perturbs what is being measured.
// Buckets are log-linear -- kSub linear slots per power of two -- which keeps
// relative error under ~1/kSub across the whole range instead of the 2x error
// a plain log2 histogram would give.
class LatencyHistogram {
public:
    static constexpr int kSubBits = 3;             // 8 slots per octave
    static constexpr int kSub     = 1 << kSubBits;
    static constexpr int kBuckets = 512;           // covers up to ~2^63 ns

    void record(std::uint64_t ns) {
        const std::size_t idx = index(ns);
        ++buckets_[idx];
        ++count_;
        total_ += ns;
        if (ns < min_) min_ = ns;
        if (ns > max_) max_ = ns;
    }

    std::uint64_t count() const noexcept { return count_; }
    std::uint64_t min() const noexcept { return count_ ? min_ : 0; }
    std::uint64_t max() const noexcept { return max_; }
    double        mean() const noexcept {
        return count_ ? static_cast<double>(total_) / static_cast<double>(count_) : 0.0;
    }

    // Value at the given percentile (0-100), resolved to the bucket midpoint.
    std::uint64_t percentile(double p) const {
        if (count_ == 0) return 0;
        if (p < 0.0) p = 0.0;
        if (p > 100.0) p = 100.0;

        const auto    target = static_cast<std::uint64_t>(
            (p / 100.0) * static_cast<double>(count_) + 0.5);
        std::uint64_t seen = 0;
        for (std::size_t i = 0; i < kBuckets; ++i) {
            seen += buckets_[i];
            if (seen >= target) {
                const std::uint64_t lo = lower_bound(i);
                return lo + width(i) / 2;
            }
        }
        return max_;
    }

    void reset() {
        buckets_.fill(0);
        count_ = 0;
        total_ = 0;
        min_   = UINT64_MAX;
        max_   = 0;
    }

    // "p50 1.2us  p99 8.4us ..." -- one line, ready to print.
    std::string summary() const;

    // Multi-line table of the populated buckets, for eyeballing the shape.
    std::string table() const;

private:
    // Small values get their own bucket; above that, the top kSubBits bits
    // below the leading one select the slot within the octave.
    static std::size_t index(std::uint64_t ns) noexcept {
        if (ns < static_cast<std::uint64_t>(kSub)) return static_cast<std::size_t>(ns);
        const int exp   = 63 - std::countl_zero(ns);  // floor(log2(ns)), >= kSubBits
        const int shift = exp - kSubBits;
        const auto sub  = static_cast<int>((ns >> shift) & (kSub - 1));
        return static_cast<std::size_t>(((exp - kSubBits + 1) << kSubBits) + sub);
    }

    static std::uint64_t lower_bound(std::size_t idx) noexcept {
        if (idx < static_cast<std::size_t>(kSub)) return idx;
        const int octave = static_cast<int>(idx >> kSubBits) - 1;
        const auto sub   = static_cast<std::uint64_t>(idx & (kSub - 1));
        return (static_cast<std::uint64_t>(kSub) | sub) << octave;  // implicit top bit
    }

    static std::uint64_t width(std::size_t idx) noexcept {
        if (idx < static_cast<std::size_t>(kSub)) return 1;
        return std::uint64_t{1} << (static_cast<int>(idx >> kSubBits) - 1);
    }

    std::array<std::uint64_t, kBuckets> buckets_{};
    std::uint64_t                       count_{};
    std::uint64_t                       total_{};
    std::uint64_t                       min_{UINT64_MAX};
    std::uint64_t                       max_{};
};

// Nanoseconds as "842ns" / "1.24us" / "3.10ms", whichever reads best.
std::string format_ns(std::uint64_t ns);

}  // namespace ob
