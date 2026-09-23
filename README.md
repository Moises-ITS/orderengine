# orderengine

A limit order book and matching engine in C++20, matching orders by **price-time
priority** the way a real exchange does: best price first, and within a price,
whoever arrived first.

Around 4-5 million operations per second on one core, with a median matching
latency of ~200ns. It ships as a single binary with an interactive CLI, a
file-based order feed, a throughput benchmark and a latency histogram.

```
$ orderengine
AAPL> sell 100.05 200
  #1 SELL 200 @ 100.05 GTC
    filled 0, remaining 200 (resting)
AAPL> sell 100.05 100
  #2 SELL 100 @ 100.05 GTC
    filled 0, remaining 100 (resting)
AAPL> buy 100.05 250
  #3 BUY 250 @ 100.05 GTC
    fill 200 @ 100.05 vs #1      <- order 1 was there first
    fill 50 @ 100.05 vs #2
    filled 250, remaining 0
```

## Build

Needs a C++20 compiler and CMake 3.20+. No third-party dependencies.

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build --output-on-failure
```

That produces `build/orderengine` (the CLI) and `build/ob_tests` (35 tests,
216 assertions, no test framework to install).

## Run it

```sh
build/orderengine                          # interactive
build/orderengine --feed examples/feed.txt # replay a recorded order feed
build/orderengine --bench 1000000 --hist   # throughput + latency histogram
echo "buy 100 500" | build/orderengine     # pipe commands in
```

| command | what it does |
| --- | --- |
| `buy <price> <qty> [gtc\|ioc\|fok]` | limit buy, e.g. `buy 100.25 500` |
| `sell <price> <qty> [gtc\|ioc\|fok]` | limit sell |
| `market <buy\|sell> <qty>` | market order, sweeps the book |
| `cancel <id>` | pull a resting order |
| `amend <id> <price> <qty>` | reduce keeps queue position, anything else re-queues |
| `book [levels]` | the ladder |
| `tape [n]` | recent trades |
| `stats` / `latency [table]` | counters, latency distribution |
| `bench <ops> [seed]` | benchmark from inside a session |
| `load <path>` | run a file of these commands |
| `symbol [name]` | show or switch the active symbol |

## How it works

### The data structure

```
bids_  std::map<Price, Level, std::greater<>>   begin() == best bid
asks_  std::map<Price, Level, std::less<>>      begin() == best ask
Level  { Qty total; std::list<Order> orders; }  list order == arrival order
index_ std::unordered_map<OrderId, {side, price, list iterator}>
```

Two sorted maps, one per side, with the comparator reversed on the bid side so
that `begin()` is the best price on *both* books. Matching then reads the same
either way: take `begin()`, fill from the front of its queue, erase the level
when it empties, repeat while the taker still has size and the prices cross.

Three decisions worth spelling out:

**`std::list` for the queue at each price, not `std::vector` or
`std::priority_queue`.** Time priority is already FIFO, so it needs no heap at
all — a queue is enough. What it does need is cancellation from the *middle*,
because most orders in a real market are cancelled rather than filled.
`std::list` splices a node out in O(1) and, crucially, leaves every other
iterator valid, which is what makes the `index_` shortcut below safe. A
`priority_queue` cannot remove an arbitrary element at all, and a `vector`
invalidates everything behind the erase point.

**An `OrderId` index straight to the list node.** Cancelling is a hash lookup
plus a splice, with no scan of the price level. Without it, cancelling would
mean searching the queue.

**Cached `total` per level.** Depth queries and fill-or-kill checks read one
number per level instead of walking its queue, so they cost O(levels) rather
than O(orders).

### Cost

`L` = populated price levels, `F` = fills an order generates.

| operation | cost |
| --- | --- |
| best bid / best ask | O(1) |
| submit that rests | O(log L) |
| submit that trades | O(log L + F) |
| cancel | O(log L) |
| amend (size reduction) | O(1) hash + O(log L) |
| depth of k levels | O(k) |

The `log L` is a `std::map` lookup. Production engines at the top of the latency
league table replace it with a flat array indexed by tick, making it O(1) at the
cost of pre-committing to a price range, and they intern symbols to avoid the
string lookup this engine does per submission. Both are deliberate trade-offs
here in favour of a book that cannot silently mis-handle an unexpected price.

### Matching rules

- **Price-time priority**: better price first; equal price, earlier arrival first.
- **Trades print at the maker's price.** A buy at 101.00 hitting an ask at
  100.00 trades at 100.00 — price improvement goes to the taker.
- **Prices are integer ticks**, never `double`. The core is integer-only, and
  decimals like `100.25` are converted at the CLI boundary, so `a == b` means
  what it says and totals always reconcile.
- **Order types**: limit and market. **Time in force**: GTC (rests), IOC (fills
  what it can, cancels the rest), FOK (all or nothing, and a rejected FOK leaves
  the book untouched).
- **Amend** mirrors real venues: a pure size reduction keeps queue position,
  while a price change or a size increase is a cancel/replace that goes to the
  back of the queue and may trade immediately.
- Self-trading is allowed: there is no notion of an account yet.

## Benchmark

`--bench` replays a synthetic flow with the shape a real venue sees: mostly
passive quoting near the touch, a minority of aggressive takers, plus IOC, FOK,
market orders and ~10% cancels. The whole stream is generated **before** the
timer starts, so the measurement contains matching-engine work and nothing else.

One core of an AMD Ryzen 5 3600, Windows 10, g++ 15.2 `-O3`, 1M operations:

```
throughput pass
  operations   1000000  (900098 submits, 99902 cancels)
  elapsed      0.196 s
  throughput   5094900 ops/sec
  trades       684587
  volume       35307190 shares
  book left    117569 orders across 308 levels

latency pass (two clock reads per operation)
  throughput   3219277 ops/sec
  latency      n=1000000  min 0ns  mean 266ns  p50 200ns  p90 496ns
               p99 1.09us  p99.9 2.18us  max 1.92ms
```

Run to run it lands between 4.0 and 5.1M ops/sec. Two caveats I would want a
reader to know:

- The throughput and latency passes are reported separately on purpose.
  Timestamping every operation costs two clock reads and drops throughput by
  about a third, so the two numbers cannot be read off one run.
- `steady_clock` on Windows has ~100ns granularity, which is the same order as
  the thing being measured. That is why 3.8% of samples read as 0ns and the
  buckets cluster on multiples of 100ns. The percentiles are sound; the
  individual sub-100ns samples are not.

The latency histogram is HdrHistogram-style: 8 linear buckets per power of two,
so relative error stays around 1/8 across the range instead of the 2x a plain
log2 histogram gives. Recording a sample is a shift, a mask and an increment —
no allocation, so measuring barely perturbs what it measures.

## Tests

`ob_tests` covers the rules that are easy to get quietly wrong: FIFO within a
level, price priority beating time priority, partial fills on both sides, sweeps
across levels, cancel from the middle of a queue, IOC/FOK/market semantics, a
rejected FOK leaving the book byte-for-byte unchanged, and each amend path.

Two of them are worth singling out:

- **`random_flow_never_breaks_the_book`** replays 20,000 pseudo-random events
  and asserts the invariants after every 500: the book is never crossed, each
  level's cached total equals the sum of its queue, no zero-quantity order is
  left resting, and the `OrderId` index matches the queues exactly. It also
  checks conservation — the shares takers report as filled equal the volume the
  book recorded.
- **`benchmark_is_deterministic_for_a_seed`** pins the benchmark: the same seed
  must produce the same trade count, so a performance change can never be a
  workload change in disguise.

## Layout

```
include/ob/   types.hpp  order_book.hpp  matching_engine.hpp  latency.hpp  bench.hpp  cli.hpp
src/          order_book.cpp  matching_engine.cpp  latency.cpp  bench.cpp  cli.cpp  main.cpp
tests/        test_order_book.cpp  test_engine.cpp  test_harness.hpp  test_main.cpp
examples/     feed.txt
```

`MatchingEngine` owns one `OrderBook` per symbol and times every submission.
`Cli` is a thin text layer over it, which is why an interactive session, a piped
script and a recorded feed all go through the same parser — every example above
is runnable, and `examples/feed.txt` runs in CI as a smoke test.

## Roadmap

- **Threading.** Each book is an independent state machine, so the natural split
  is a single writer per symbol with books sharded across threads, and an SPSC
  ring buffer for ingest. Deliberately not done yet: the engine is
  single-threaded and therefore bit-for-bit deterministic on replay, which is
  what makes the fuzz test meaningful.
- **Array-of-ticks book** behind the same interface, to measure what dropping
  `log L` is actually worth.
- **Stop and iceberg orders**, self-trade prevention, and an account model.
- **Binary feed replay** (ITCH-style) instead of text, to benchmark parsing
  separately from matching.

## A note for Windows users

If a MinGW build segfaults inside `<fstream>`, an MSVCRT-built
`libstdc++-6.dll` earlier on `PATH` — Git for Windows ships one — is being
loaded by a UCRT-built binary. `CMakeLists.txt` links the runtime statically on
MinGW so this cannot happen; if you compile by hand, pass
`-static-libstdc++ -static-libgcc`.
