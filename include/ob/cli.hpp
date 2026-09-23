#pragma once

#include <iosfwd>
#include <string>

#include "ob/matching_engine.hpp"

namespace ob {

// Text front end for the engine. One command per line, so the same parser
// serves an interactive session, a piped script and a recorded order feed --
// which also makes every example in the README a runnable regression test.
class Cli {
public:
    explicit Cli(std::ostream& out) : out_(out) {}

    // Runs one command. Returns false when the session should end.
    bool execute(const std::string& line);

    // Reads commands until EOF. `interactive` prints a prompt and echoes
    // nothing; a feed echoes each command so the output is self-explaining.
    void run(std::istream& in, bool interactive);

    MatchingEngine&    engine() noexcept { return engine_; }
    const std::string& symbol() const noexcept { return symbol_; }

private:
    void print_help();
    void print_report(const ExecReport& report, const Order& submitted);
    void print_book(std::size_t levels);
    void print_tape(std::size_t count);
    void print_stats();
    void print_latency(bool table);
    void load_feed(const std::string& path);
    void do_bench(std::size_t ops, std::uint64_t seed);

    std::ostream&  out_;
    MatchingEngine engine_;
    std::string    symbol_{"AAPL"};
};

// Parses "100", "100.5" or "100.25" into integer ticks. False on junk.
bool parse_price(const std::string& text, Price& out);

}  // namespace ob
