#include "ob/cli.hpp"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <cstdio>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>

#include "ob/bench.hpp"

namespace ob {

namespace {

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

bool parse_u64(const std::string& text, std::uint64_t& out) {
    if (text.empty()) return false;
    const char* first = text.data();
    const char* last  = text.data() + text.size();
    const auto  res   = std::from_chars(first, last, out);
    return res.ec == std::errc{} && res.ptr == last;
}

bool parse_side(const std::string& text, Side& out) {
    const std::string s = lower(text);
    if (s == "buy" || s == "b" || s == "bid") {
        out = Side::Buy;
        return true;
    }
    if (s == "sell" || s == "s" || s == "ask" || s == "offer") {
        out = Side::Sell;
        return true;
    }
    return false;
}

bool parse_tif(const std::string& text, Tif& out) {
    const std::string s = lower(text);
    if (s == "gtc" || s == "day") {
        out = Tif::Gtc;
        return true;
    }
    if (s == "ioc") {
        out = Tif::Ioc;
        return true;
    }
    if (s == "fok") {
        out = Tif::Fok;
        return true;
    }
    return false;
}

}  // namespace

// Decimal text in, integer ticks out. Parsing the fraction by hand rather than
// going through a double keeps "100.05" exact instead of 100.04999...
bool parse_price(const std::string& text, Price& out) {
    if (text.empty()) return false;

    const std::size_t dot   = text.find('.');
    const std::string whole = text.substr(0, dot);
    std::uint64_t     units = 0;
    if (!parse_u64(whole, units)) return false;

    std::uint64_t frac = 0;
    if (dot != std::string::npos) {
        std::string digits = text.substr(dot + 1);
        if (digits.empty() || digits.size() > 2) return false;
        if (digits.size() == 1) digits += '0';
        if (!parse_u64(digits, frac)) return false;
    }
    out = static_cast<Price>(units) * kTickScale + static_cast<Price>(frac);
    return true;
}

void Cli::print_help() {
    out_ << R"(commands
  buy  <price> <qty> [gtc|ioc|fok]   limit buy   (e.g. buy 100.25 500)
  sell <price> <qty> [gtc|ioc|fok]   limit sell
  market <buy|sell> <qty>            market order, sweeps the book
  cancel <id>                        pull a resting order
  amend  <id> <price> <qty>          reduce keeps queue position, else re-queues
  book [levels]                      the ladder, default 10 levels a side
  tape [n]                           last n trades, default 10
  stats                              counters for the active symbol
  latency [table]                    latency of every op measured so far
  bench <ops> [seed]                 replay synthetic flow, report throughput
  load <path>                        run a file of these commands
  symbol [name]                      show or switch the active symbol
  reset                              wipe all books
  help / quit
prices are decimals (100.25), quantities are whole shares
)";
}

void Cli::print_report(const ExecReport& report, const Order& submitted) {
    if (!report.accepted()) {
        out_ << "  rejected: " << to_string(report.reject) << "\n";
        return;
    }

    out_ << "  #" << report.id << ' ' << to_string(submitted.side) << ' '
         << submitted.qty;
    if (submitted.type == OrderType::Limit) {
        out_ << " @ " << format_price(submitted.price);
    } else {
        out_ << " @ market";
    }
    out_ << ' ' << to_string(submitted.tif) << '\n';

    for (const Trade& t : report.trades) {
        out_ << "    fill " << t.qty << " @ " << format_price(t.price) << " vs #"
             << t.maker_id << '\n';
    }

    out_ << "    filled " << report.filled << ", remaining " << report.remaining;
    if (report.resting) {
        out_ << " (resting)";
    } else if (report.cancelled) {
        out_ << " (cancelled)";
    }
    out_ << '\n';
}

void Cli::print_book(std::size_t levels) {
    const OrderBook* book = engine_.find(symbol_);
    if (book == nullptr) {
        out_ << "  no book for " << symbol_ << " yet\n";
        return;
    }

    const auto bids = book->depth(Side::Buy, levels);
    const auto asks = book->depth(Side::Sell, levels);

    out_ << "  " << symbol_ << "  " << book->open_orders() << " orders\n";
    out_ << "        price      qty  orders\n";

    // Asks print worst-first so the touch sits in the middle of the ladder,
    // the way a trading screen shows it.
    for (auto it = asks.rbegin(); it != asks.rend(); ++it) {
        out_ << "  ASK " << std::setw(9) << format_price(it->price) << std::setw(9)
             << it->qty << std::setw(8) << it->orders << '\n';
    }

    if (const auto s = book->spread()) {
        out_ << "      ---- spread " << format_price(*s) << " ----\n";
    } else {
        out_ << "      ---- one sided ----\n";
    }

    for (const auto& level : bids) {
        out_ << "  BID " << std::setw(9) << format_price(level.price) << std::setw(9)
             << level.qty << std::setw(8) << level.orders << '\n';
    }

    if (bids.empty() && asks.empty()) out_ << "  (empty)\n";
}

void Cli::print_tape(std::size_t count) {
    const OrderBook* book = engine_.find(symbol_);
    if (book == nullptr || book->tape().empty()) {
        out_ << "  no trades yet\n";
        return;
    }

    const auto&       tape  = book->tape();
    const std::size_t start = tape.size() > count ? tape.size() - count : 0;
    for (std::size_t i = start; i < tape.size(); ++i) {
        const Trade& t = tape[i];
        out_ << "  " << t.qty << " @ " << format_price(t.price) << "  taker #"
             << t.taker_id << ' ' << to_string(t.taker_side) << "  maker #"
             << t.maker_id << '\n';
    }
}

void Cli::print_stats() {
    const OrderBook* book = engine_.find(symbol_);
    if (book == nullptr) {
        out_ << "  no book for " << symbol_ << " yet\n";
        return;
    }
    const OrderBook::Stats& s = book->stats();

    out_ << "  symbol       " << symbol_ << '\n'
         << "  accepted     " << s.orders_accepted << '\n'
         << "  rejected     " << s.orders_rejected << '\n'
         << "  trades       " << s.trades << '\n'
         << "  volume       " << s.volume << " shares\n"
         << "  notional     " << format_price(static_cast<Price>(s.notional)) << '\n'
         << "  resting      " << book->open_orders() << " orders, "
         << book->level_count(Side::Buy) << " bid / "
         << book->level_count(Side::Sell) << " ask levels\n";

    if (const auto b = book->best_bid()) out_ << "  best bid     " << format_price(*b) << '\n';
    if (const auto a = book->best_ask()) out_ << "  best ask     " << format_price(*a) << '\n';
}

void Cli::print_latency(bool table) {
    out_ << "  " << engine_.latency().summary() << '\n';
    if (table) out_ << engine_.latency().table();
}

void Cli::do_bench(std::size_t ops, std::uint64_t seed) {
    out_ << "  warming up...\n";
    run_benchmark(std::min<std::size_t>(ops, 50'000), seed);

    out_ << "  throughput pass (no clock reads in the loop)\n";
    const BenchResult fast = run_benchmark(ops, seed, false);
    out_ << format_bench(fast);

    out_ << "  latency pass (two clock reads per operation)\n";
    const BenchResult timed = run_benchmark(ops, seed, true);
    out_ << format_bench(timed);
}

void Cli::load_feed(const std::string& path) {
    std::ifstream in(path);
    if (!in) {
        out_ << "  cannot open " << path << '\n';
        return;
    }

    std::size_t executed = 0;
    std::string line;
    while (std::getline(in, line)) {
        const std::string trimmed = line.substr(0, line.find_first_of("\r\n"));
        if (trimmed.empty() || trimmed[0] == '#') continue;
        out_ << "> " << trimmed << '\n';
        ++executed;
        if (!execute(trimmed)) break;
    }
    out_ << "  replayed " << executed << " commands from " << path << '\n';
}

bool Cli::execute(const std::string& line) {
    std::istringstream       words(line);
    std::vector<std::string> tok;
    for (std::string w; words >> w;) tok.push_back(w);
    if (tok.empty()) return true;

    const std::string cmd = lower(tok[0]);
    const auto        arg = [&tok](std::size_t i) -> std::string {
        return i < tok.size() ? tok[i] : std::string{};
    };
    const auto number = [&](std::size_t i, std::uint64_t fallback) {
        std::uint64_t v = 0;
        return parse_u64(arg(i), v) ? v : fallback;
    };

    if (cmd == "quit" || cmd == "exit" || cmd == "q") return false;

    if (cmd == "help" || cmd == "h" || cmd == "?") {
        print_help();
        return true;
    }

    if (cmd == "buy" || cmd == "sell" || cmd == "limit") {
        // "limit buy 100.25 500" and "buy 100.25 500" are the same order.
        std::size_t i = 1;
        Side        side{};
        if (cmd == "limit") {
            if (!parse_side(arg(1), side)) {
                out_ << "  usage: limit <buy|sell> <price> <qty> [gtc|ioc|fok]\n";
                return true;
            }
            i = 2;
        } else {
            side = cmd == "buy" ? Side::Buy : Side::Sell;
        }

        Order order;
        order.side = side;
        order.type = OrderType::Limit;
        if (!parse_price(arg(i), order.price) || !parse_u64(arg(i + 1), order.qty)) {
            out_ << "  usage: " << cmd << " <price> <qty> [gtc|ioc|fok]\n";
            return true;
        }
        if (!arg(i + 2).empty() && !parse_tif(arg(i + 2), order.tif)) {
            out_ << "  unknown time in force: " << arg(i + 2) << '\n';
            return true;
        }
        print_report(engine_.submit(symbol_, order), order);
        return true;
    }

    if (cmd == "market") {
        Order order;
        order.type = OrderType::Market;
        order.tif  = Tif::Ioc;  // a market order never rests
        if (!parse_side(arg(1), order.side) || !parse_u64(arg(2), order.qty)) {
            out_ << "  usage: market <buy|sell> <qty>\n";
            return true;
        }
        print_report(engine_.submit(symbol_, order), order);
        return true;
    }

    if (cmd == "cancel") {
        std::uint64_t id = 0;
        if (!parse_u64(arg(1), id)) {
            out_ << "  usage: cancel <id>\n";
            return true;
        }
        out_ << (engine_.cancel(symbol_, id) ? "  cancelled #" : "  no resting order #")
             << id << '\n';
        return true;
    }

    if (cmd == "amend") {
        std::uint64_t id = 0;
        Price         price{};
        std::uint64_t qty = 0;
        if (!parse_u64(arg(1), id) || !parse_price(arg(2), price) ||
            !parse_u64(arg(3), qty)) {
            out_ << "  usage: amend <id> <price> <qty>\n";
            return true;
        }
        const ExecReport report = engine_.amend(symbol_, id, price, qty);
        if (!report.accepted()) {
            out_ << "  rejected: " << to_string(report.reject) << '\n';
        } else {
            out_ << "  amended #" << report.id << " -> " << format_price(price) << " x "
                 << qty << (report.trades.empty() ? "" : " (traded on re-entry)") << '\n';
            for (const Trade& t : report.trades) {
                out_ << "    fill " << t.qty << " @ " << format_price(t.price) << " vs #"
                     << t.maker_id << '\n';
            }
        }
        return true;
    }

    if (cmd == "book") {
        print_book(static_cast<std::size_t>(number(1, 10)));
        return true;
    }
    if (cmd == "tape") {
        print_tape(static_cast<std::size_t>(number(1, 10)));
        return true;
    }
    if (cmd == "stats") {
        print_stats();
        return true;
    }
    if (cmd == "latency") {
        print_latency(lower(arg(1)) == "table");
        return true;
    }
    if (cmd == "bench") {
        do_bench(static_cast<std::size_t>(number(1, 200'000)), number(2, 42));
        return true;
    }
    if (cmd == "load") {
        if (arg(1).empty()) {
            out_ << "  usage: load <path>\n";
        } else {
            load_feed(arg(1));
        }
        return true;
    }
    if (cmd == "symbol") {
        if (arg(1).empty()) {
            out_ << "  active symbol " << symbol_ << '\n';
        } else {
            symbol_ = arg(1);
            engine_.book(symbol_);
            out_ << "  active symbol " << symbol_ << '\n';
        }
        return true;
    }
    if (cmd == "reset") {
        engine_.reset();
        out_ << "  all books cleared\n";
        return true;
    }

    out_ << "  unknown command: " << tok[0] << " (try help)\n";
    return true;
}

void Cli::run(std::istream& in, bool interactive) {
    if (interactive) {
        out_ << "limit order book / matching engine  --  type help\n";
    }

    std::string line;
    while (true) {
        if (interactive) {
            out_ << symbol_ << "> " << std::flush;
        }
        if (!std::getline(in, line)) break;

        const std::size_t cut = line.find_first_of("\r\n");
        if (cut != std::string::npos) line.resize(cut);
        if (!line.empty() && line[0] == '#') continue;
        if (!execute(line)) break;
    }
    if (interactive) out_ << "bye\n";
}

}  // namespace ob
