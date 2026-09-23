#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ob {

// Prices are integer ticks (cents), never floating point: exchanges match on
// exact integers so that `a == b` is meaningful and totals always reconcile.
using Price   = std::int64_t;
using Qty     = std::uint64_t;
using OrderId = std::uint64_t;
using Seq     = std::uint64_t;  // monotonic arrival stamp -> time priority

enum class Side : std::uint8_t { Buy, Sell };
enum class OrderType : std::uint8_t { Limit, Market };

// Time in force. Gtc rests on the book, Ioc cancels its remainder,
// Fok must fill in full or not trade at all.
enum class Tif : std::uint8_t { Gtc, Ioc, Fok };

constexpr Side opposite(Side s) noexcept {
    return s == Side::Buy ? Side::Sell : Side::Buy;
}

const char* to_string(Side s) noexcept;
const char* to_string(OrderType t) noexcept;
const char* to_string(Tif t) noexcept;

// Ticks -> "123.45". The scale lives at the edges of the system only.
constexpr int kTickScale = 100;
std::string format_price(Price ticks);

struct Order {
    OrderId   id{};
    Side      side{};
    OrderType type{OrderType::Limit};
    Tif       tif{Tif::Gtc};
    Price     price{};      // unused for OrderType::Market
    Qty       qty{};        // original size, never mutated
    Qty       remaining{};  // unfilled size
    Seq       seq{};        // arrival order within the book

    Qty filled() const noexcept { return qty - remaining; }
};

struct Trade {
    Seq     seq{};
    OrderId taker_id{};
    OrderId maker_id{};
    Side    taker_side{};
    Price   price{};  // always the resting (maker) order's price
    Qty     qty{};
};

enum class Reject : std::uint8_t {
    None,
    ZeroQuantity,
    DuplicateId,
    NoLiquidity,     // market order with an empty opposite book
    FillOrKill,      // Fok that could not be filled completely
    UnknownOrder,    // cancel/amend for an id that is not resting
};

const char* to_string(Reject r) noexcept;

// What the engine tells the client happened to one submission.
struct ExecReport {
    OrderId            id{};
    Qty                filled{};
    Qty                remaining{};
    bool               resting{false};    // remainder is live on the book
    bool               cancelled{false};  // remainder was killed (Ioc/market/Fok)
    Reject             reject{Reject::None};
    std::vector<Trade> trades;

    bool accepted() const noexcept { return reject == Reject::None; }
};

}  // namespace ob
