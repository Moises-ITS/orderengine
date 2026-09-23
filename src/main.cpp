#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>

#include "ob/bench.hpp"
#include "ob/cli.hpp"

#if defined(_WIN32)
#include <io.h>
#define ob_isatty _isatty
#define ob_fileno _fileno
#else
#include <unistd.h>
#define ob_isatty isatty
#define ob_fileno fileno
#endif

namespace {

void usage() {
    std::cout << R"(usage: orderengine [options]

  no options        interactive session on stdin
  --feed <path>     replay a file of commands, then exit
  --bench [ops]     run the throughput benchmark, then exit
  --seed <n>        seed for --bench (default 42)
  --hist            print the latency histogram after --bench
  --help            this text

Piping also works:  echo "buy 100 500" | orderengine
)";
}

}  // namespace

int main(int argc, char** argv) {
    std::string   feed;
    std::size_t   bench_ops = 0;
    std::uint64_t seed      = 42;
    bool          hist      = false;

    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        const auto        next = [&](std::size_t fallback) -> std::size_t {
            if (i + 1 < argc && argv[i + 1][0] != '-') return std::strtoull(argv[++i], nullptr, 10);
            return fallback;
        };

        if (a == "--help" || a == "-h") {
            usage();
            return 0;
        }
        if (a == "--feed" && i + 1 < argc) {
            feed = argv[++i];
        } else if (a == "--bench") {
            bench_ops = next(1'000'000);
        } else if (a == "--seed") {
            seed = next(42);
        } else if (a == "--hist") {
            hist = true;
        } else {
            std::cerr << "unknown option: " << a << "\n\n";
            usage();
            return 2;
        }
    }

    if (bench_ops > 0) {
        std::cout << "benchmark: " << bench_ops << " operations, seed " << seed << "\n";
        std::cout << "  warming up...\n";
        ob::run_benchmark(std::min<std::size_t>(bench_ops, 50'000), seed);

        std::cout << "\nthroughput pass\n";
        std::cout << ob::format_bench(ob::run_benchmark(bench_ops, seed, false));

        std::cout << "\nlatency pass (two clock reads per operation)\n";
        const ob::BenchResult timed = ob::run_benchmark(bench_ops, seed, true);
        std::cout << ob::format_bench(timed);
        if (hist) std::cout << '\n' << timed.latency.table();
        return 0;
    }

    ob::Cli cli(std::cout);

    if (!feed.empty()) {
        cli.execute("load " + feed);
        return 0;
    }

    const bool interactive = ob_isatty(ob_fileno(stdin)) != 0;
    cli.run(std::cin, interactive);
    return 0;
}
