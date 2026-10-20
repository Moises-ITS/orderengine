# orderengine

A limit order book and matching engine in Java 17, matching orders by
**price-time priority** the way a real exchange does: best price first, and
within a price, whoever arrived first.

The engine keeps its price levels in a `TreeMap`, reads the top of the book in
O(1), and cancels in O(1). Object pooling and primitive collections keep its
steady state free of garbage. At an offered load of **2M orders/sec**, its p99
latency is **3.5–7 µs**. A straightforward version of the same algorithm on JDK
collections, kept in the repo as the baseline, has a p99 of **10–20 ms**. It
ships as one jar with an interactive CLI, a file-based order feed, a
coordinated-omission-correct load test and a JMH suite. There are 40 JUnit tests.

```
$ java -jar target/orderengine.jar
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

You need JDK 17 or newer. Maven comes with the repo through the wrapper, so you
don't have to install it.

```sh
./mvnw verify                 # compile, run the 40 JUnit tests, build the jar
```

That produces `target/orderengine.jar`, which holds the CLI, the benchmarks and
the JMH suite. On Windows, use `mvnw.cmd`.

## Run it

```sh
java -jar target/orderengine.jar                          # interactive
java -jar target/orderengine.jar --feed examples/feed.txt # replay a recorded order feed
java -jar target/orderengine.jar --bench                  # unpaced throughput, both books
java -jar target/orderengine.jar --latency --impl pooled  # p50/p99 at 2M orders/sec
java -jar target/orderengine.jar --jmh                    # JMH suite
echo "buy 100 500" | java -jar target/orderengine.jar     # pipe commands in
```

| command | what it does |
| --- | --- |
| `buy <price> <qty> [gtc\|ioc\|fok]` | limit buy, e.g. `buy 100.25 500` |
| `sell <price> <qty> [gtc\|ioc\|fok]` | limit sell |
| `market <buy\|sell> <qty>` | market order, sweeps the book |
| `cancel <id>` | pull a resting order |
| `amend <id> <price> <qty>` | cancel-replace: a reduction keeps queue position, anything else re-queues |
| `book [levels]` | the ladder |
| `tape [n]` | recent trades |
| `stats` / `latency [table]` | counters, latency distribution |
| `bench <ops> [seed]` | benchmark from inside a session |
| `load <path>` | run a file of these commands |
| `symbol [name]` | show or switch the active symbol |

## How it works

### The data structure

```
per side
  levels   TreeMap<Long, Level>          price-ordered; bids reversed, so firstKey() is best on both sides
  byPrice  LongObjectHashMap<Level>      price -> level, no boxing and no tree walk
  best     Level                         cached top of book
per book
  index    LongObjectHashMap<OrderNode>  order id -> node, for O(1) cancel and amend
  pool     OrderPool                     recycled order nodes
Level      { price, total, count, head, tail }   intrusive FIFO; head is the oldest order
```

Matching reads the same way on either side. It takes the best level, fills from
the front of that level's queue, and moves on when the level empties. It stops
when the taker is filled or the prices no longer cross.

These are the decisions worth explaining:

**The queue at each price is an intrusive doubly linked list.** Time priority is
FIFO, so no heap is needed. What a queue does need is removal from the
*middle*, because most orders in a real market are cancelled rather than filled.
Each `OrderNode` carries `prev`/`next` links and a pointer to its level, so
unlinking an order is O(1). A `PriorityQueue` can only remove an arbitrary
element in O(n). An `ArrayList` would shift every element behind it.

**Cancels never touch the `TreeMap`.** Removing a key from a `TreeMap` is
O(log L). When a cancel empties a level, the book marks the level empty and
leaves it in place. If the emptied level was the best one, the cached top of
book is flagged as stale. The next read prunes empty levels from the front of
the tree, each one exactly once, so the cost is paid by the insert that created
the level. If an order arrives at that price before the prune, the empty level
is simply reused, with no tree insert. A sweep runs when empty levels
outnumber live ones, and it runs on the insert path, so cancel stays O(1).

**Top of book is a cached reference.** `bestBid()` and `bestAsk()` return
`best.price` without walking the tree. The cache is updated when an order rests
at a better price, and recomputed lazily after the best level empties.

**Object pooling.** Resting orders live in `OrderNode`s from an `OrderPool`, a
free list pre-sized at startup. Resting or retiring an order is an array pop or
push. `ExecReport` is reused across calls, and it stores fills in parallel
`long[]` arrays rather than a `List<Trade>`. The trade tape is a ring buffer of
primitives. Once the book is warm, the hot path allocates nothing; the only
allocation left is the `TreeMap` entry when a brand-new price level appears.

**Primitive collections.** `LongObjectHashMap` is an open-addressing map from
`long` to object, written for this project. It never boxes a key, it allocates
no node per entry, and it deletes by backward shift instead of tombstones, so
probe chains don't decay as orders churn. Its hash keeps runs of 64 consecutive
keys in adjacent slots. Order ids are mostly sequential, and the orders being
filled and cancelled are the recent ones, so their slots share cache lines. The
runs themselves are scattered with a Fibonacci hash, so strided ids don't pile
up in one region. That one change took unpaced throughput from ~6.4M to ~10M
ops/sec once the index was pre-sized to 1M slots.

### Cost

`L` = populated price levels, `F` = fills an order generates.

| operation | cost |
| --- | --- |
| best bid / best ask | O(1); amortised O(1) right after the best level empties |
| cancel | O(1) |
| amend (size reduction) | O(1) |
| submit that rests at an existing price | O(1) |
| submit that opens a new price level | O(log L) |
| submit that trades | O(F), plus O(log L) per level it empties |
| depth of k levels | O(k + empty levels passed over) |

### Matching rules

- **Price-time priority**: better price first; at an equal price, earlier arrival first.
- **Trades print at the maker's price.** A buy at 101.00 that hits an ask at
  100.00 trades at 100.00, so the price improvement goes to the taker.
- **Prices are integer ticks** (`long`), never `double`. Decimals like `100.25`
  are converted at the CLI boundary, so `a == b` means what it says and totals
  always reconcile.
- **Order types**: limit and market. **Time in force**: GTC (rests), IOC (fills
  what it can and cancels the rest), FOK (all or nothing; a rejected FOK leaves
  the book untouched).
- **Cancel-replace** (`amend`) works the way real venues do. A pure size
  reduction keeps queue position. A price change or a size increase is a
  cancel-replace that goes to the back of the queue and may trade immediately.
- Self-trading is allowed, because there is no notion of an account yet.

## Benchmarks

All three benchmarks replay the same synthetic flow, which has the shape a real
venue sees. Most orders are passive quotes near a random-walking mid. A minority
are aggressive takers, plus IOC, FOK and market orders, and about 10% are
cancels. The flow is generated into primitive arrays **before** the clock
starts, so the timed loop does matching-engine work and nothing else.

Each benchmark runs two implementations of the same `Book` interface:

- **`pooled`**: `OrderBook`, described above.
- **`baseline`**: `BaselineOrderBook`, the same algorithm written the obvious
  way. It allocates a new node per order and a new report per call, uses a
  `HashMap<Long, Node>` index and boxed `TreeMap` lookups, records a `Trade`
  object per fill, and removes levels eagerly. Both books are pre-sized for the
  same number of orders. A test replays 300,000 operations through both and
  requires identical fills, so the only difference between them is speed.

Every figure below comes from one core of an AMD Ryzen 5 3600, Windows 10,
JDK 17.0.17 with the default G1 collector.

### Latency at 2M orders/sec: `--latency`

This is the benchmark the headline number comes from. It offers 6M operations at
a fixed 2M/sec, one every 500 ns. Each operation's latency is measured from when
it was **scheduled**, not from when it started. So if the book stalls, every
order queued behind the stall is charged for the wait. That is what a client
would see, and it avoids coordinated omission, the usual way load tests
understate their tail. The paced loop is warmed up before measuring, and each
book runs in its own JVM.

Five alternating runs of each:

| book | p50 | p99 | p99.9 | max |
| --- | --- | --- | --- | --- |
| pooled | 100 ns | 3.5–6.9 µs | 19 µs – 0.56 ms | 0.16–1.3 ms |
| baseline | 100–200 ns | 10–20 ms | 18–24 ms | 19–25 ms |

The medians are the same, because both books run the same algorithm. The tails
are not. The baseline allocates about 590 bytes per operation, which is about
1.2 GB/sec at this rate. That triggers a young GC several times a second, and
each pause backs up thousands of orders. The pooled book doesn't collect at all
while it is being measured. Run with `-Xlog:gc` to see this directly.

Caveats I would want a reader to know:

- On Windows, `System.nanoTime` has ~100 ns granularity. Sub-microsecond
  percentiles land on multiples of 100 ns, and individual samples below that are
  noise.
- The pooled book's tail beyond p99 comes from the OS (a desktop machine,
  scheduler preemption). The spread between runs above is real.
- The comparison depends on heap sizing and the collector. A low-pause
  collector such as ZGC would shrink the baseline's pauses. Pooling removes the
  need for one.

### Throughput: `--bench`

Unpaced: 2M operations replayed as fast as the book will take them.

```
book         pooled
operations   2000000  (1800674 submits, 199326 cancels)
throughput   10250276 ops/sec
trades       1395226
book left    216752 orders across 454 levels

book         baseline
operations   2000000  (1800674 submits, 199326 cancels)
throughput   5105522 ops/sec
trades       1395226
book left    216752 orders across 454 levels
```

Across runs, the pooled book lands between 10.2M and 11.5M ops/sec, and the
baseline around 5.1M.

### JMH: `--jmh`

`OrderBookBenchmark` times a single operation from the same flow, in Throughput
and SampleTime modes, for both books, plus a top-of-book read. Add `-prof gc` to
get allocation per operation. The defaults are 2 forks, 5 warm-up iterations and
5 measured iterations, with a 2 GB heap.

| benchmark | book | throughput | p50 | p99 | p99.9 | p99.99 | allocation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `mixedFlow` | pooled | 9.2 ops/µs | 0.1 µs | 0.6 µs | 1.2 µs | 14.7 µs | 4 B/op |
| `mixedFlow` | baseline | 7.4 ops/µs | 0.3 µs | 0.7 µs | 3.3 µs | 72.5 µs | 586 B/op |
| `topOfBook` | pooled | 134 ops/µs | | 0.1 µs | | | 0 |
| `topOfBook` | baseline | 118 ops/µs | | 0.1 µs | | | 0 |

The pooled book's 4 B/op is the fresh, pre-sized book that JMH builds before
each iteration, spread across the operations in it.

JMH runs unpaced, so it shows what a single operation costs: the pooled book
is about 25% faster per operation, and at p99 the two are close. The large gap
appears only under a fixed offered load, in `--latency`. There, the baseline's
GC pauses queue up the orders behind them, and the queueing drives its p99 from
under a microsecond to milliseconds. Use the right benchmark for the question:
JMH for per-operation cost, `--latency` for what a client sees at 2M orders/sec.

## Tests

There are 40 JUnit 5 tests. They cover the rules that are easy to get quietly
wrong: FIFO within a level, price priority beating time priority, partial fills
on both the taker and maker side, crossing orders that sweep several levels,
cancel from the middle of a queue, IOC/FOK/market semantics, a rejected FOK
leaving the book untouched, and each cancel-replace path.

Four of them do the heavy lifting:

- **`priceTimePriorityMatchesAReferenceModel`** replays 10,000 randomized
  sequences, each 100 events of limit, market and cancel orders over a narrow
  band of prices so that orders queue, cross and fill partially. It does this
  for *both* books. Every sequence also runs through a deliberately naive
  reference book: one list, a linear scan for the best price, and the earliest
  arrival wins a tie. Every fill must agree on maker, price and size, in order.
  Cancels, rejects, resting state and the final book must agree too.
- **`pooledBookMatchesBaselineFillForFill`** runs 300,000 operations of the
  benchmark flow through both books. Every operation must fill identically, and
  the final books, stats and tapes must match. The optimizations change speed
  and nothing else.
- **`randomFlowNeverBreaksTheBook`** replays 20,000 random events, including
  amends, and checks the invariants every 500 events. The book is never
  crossed. Every level's cached total equals its queue. Empty-level counts, the
  cached top of book, the id index and the node pool all agree. It also checks
  conservation: the shares takers report as filled equal the volume the book
  recorded.
- **`primitiveIndexBehavesLikeAHashMap`** runs 200,000 random puts and removes
  against `java.util.HashMap`, over a key space small enough to force long
  probe chains, wrap-around and every case of backward-shift deletion.

## Layout

```
src/main/java/orderengine/
  Book.java                 the interface both books implement
  OrderBook.java            the pooled engine
  BaselineOrderBook.java    the same algorithm on JDK collections
  Level.java  OrderNode.java  OrderPool.java  LongObjectHashMap.java
  ExecReport.java  Trade.java  LevelView.java  BookStats.java  Prices.java
  Side.java  OrderType.java  Tif.java  Reject.java
  MatchingEngine.java       one book per symbol, timed
  LatencyHistogram.java     HdrHistogram-style, allocation-free recording
  Cli.java  Main.java
  bench/                    Flow (synthetic order flow), Bench, OrderBookBenchmark (JMH)
src/test/java/orderengine/  OrderBookTest  PriceTimePriorityTest  RandomizedTest
                            MatchingEngineTest  PricesAndLatencyTest
examples/feed.txt
```

`MatchingEngine` owns one `OrderBook` per symbol and times every submit and
cancel. `Cli` is a thin text layer over it. An interactive session, a piped
script and a recorded feed all go through the same parser, and
`examples/feed.txt` runs in CI as a smoke test.

## Roadmap

- **Threading.** Each book is an independent state machine. The natural split
  is a single writer per symbol, with books sharded across threads and an SPSC
  ring buffer for ingest. I deliberately haven't done this yet: the engine is
  single-threaded, so it is bit-for-bit deterministic on replay, and that is
  what makes the differential tests meaningful.
- **An array-of-ticks book** behind the same `Book` interface, to measure what
  removing the remaining `log L` on new levels is worth.
- **Stop and iceberg orders**, self-trade prevention, and an account model.
- **Binary feed replay** (ITCH-style) instead of text, to benchmark parsing
  separately from matching.
