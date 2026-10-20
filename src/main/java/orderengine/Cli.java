package orderengine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

import orderengine.bench.Bench;
import orderengine.bench.Flow;

// Text front end for the engine. One command per line, so the same parser
// serves an interactive session, a piped script and a recorded order feed --
// which also makes every example in the README a runnable regression test.
public final class Cli {
    private final PrintStream out;
    private final MatchingEngine engine = new MatchingEngine();
    private String symbol = "AAPL";

    public Cli(PrintStream out) {
        this.out = out;
    }

    public MatchingEngine engine() {
        return engine;
    }

    public String symbol() {
        return symbol;
    }

    // Reads commands until EOF. `interactive` prints a prompt.
    public void run(BufferedReader in, boolean interactive) throws IOException {
        if (interactive) out.println("limit order book / matching engine  --  type help");
        while (true) {
            if (interactive) {
                out.print(symbol + "> ");
                out.flush();
            }
            final String line = in.readLine();
            if (line == null) break;
            if (line.startsWith("#")) continue;
            if (!execute(line)) break;
        }
        if (interactive) out.println("bye");
    }

    // Runs one command. Returns false when the session should end.
    public boolean execute(String line) {
        final String trimmed = line.strip();
        if (trimmed.isEmpty()) return true;
        final String[] tok = trimmed.split("\\s+");
        final String cmd = tok[0].toLowerCase(Locale.ROOT);

        switch (cmd) {
            case "quit": case "exit": case "q":
                return false;
            case "help": case "h": case "?":
                printHelp();
                return true;
            case "buy": case "sell": case "limit":
                limit(cmd, tok);
                return true;
            case "market":
                market(tok);
                return true;
            case "cancel": {
                final long id = Prices.parseDigits(arg(tok, 1));
                if (id < 0) {
                    out.println("  usage: cancel <id>");
                } else {
                    out.println((engine.cancel(symbol, id) ? "  cancelled #" : "  no resting order #") + id);
                }
                return true;
            }
            case "amend":
                amend(tok);
                return true;
            case "book":
                printBook((int) number(tok, 1, 10));
                return true;
            case "tape":
                printTape((int) number(tok, 1, 10));
                return true;
            case "stats":
                printStats();
                return true;
            case "latency":
                out.println("  " + engine.latency().summary());
                if (arg(tok, 1).equalsIgnoreCase("table")) out.print(engine.latency().table());
                return true;
            case "bench":
                bench((int) number(tok, 1, 200_000), number(tok, 2, 42));
                return true;
            case "load":
                if (arg(tok, 1).isEmpty()) {
                    out.println("  usage: load <path>");
                } else {
                    loadFeed(arg(tok, 1));
                }
                return true;
            case "symbol":
                if (!arg(tok, 1).isEmpty()) {
                    symbol = arg(tok, 1);
                    engine.book(symbol);
                }
                out.println("  active symbol " + symbol);
                return true;
            case "reset":
                engine.reset();
                out.println("  all books cleared");
                return true;
            default:
                out.println("  unknown command: " + tok[0] + " (try help)");
                return true;
        }
    }

    private static String arg(String[] tok, int i) {
        return i < tok.length ? tok[i] : "";
    }

    private static long number(String[] tok, int i, long fallback) {
        final long v = Prices.parseDigits(arg(tok, i));
        return v < 0 ? fallback : v;
    }

    private static Side parseSide(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "buy": case "b": case "bid": return Side.BUY;
            case "sell": case "s": case "ask": case "offer": return Side.SELL;
            default: return null;
        }
    }

    private static Tif parseTif(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "gtc": case "day": return Tif.GTC;
            case "ioc": return Tif.IOC;
            case "fok": return Tif.FOK;
            default: return null;
        }
    }

    // "limit buy 100.25 500" and "buy 100.25 500" are the same order.
    private void limit(String cmd, String[] tok) {
        int i = 1;
        final Side side;
        if (cmd.equals("limit")) {
            side = parseSide(arg(tok, 1));
            if (side == null) {
                out.println("  usage: limit <buy|sell> <price> <qty> [gtc|ioc|fok]");
                return;
            }
            i = 2;
        } else {
            side = cmd.equals("buy") ? Side.BUY : Side.SELL;
        }

        final OptionalLong price = Prices.parse(arg(tok, i));
        final long qty = Prices.parseDigits(arg(tok, i + 1));
        if (price.isEmpty() || qty < 0) {
            out.println("  usage: " + cmd + " <price> <qty> [gtc|ioc|fok]");
            return;
        }
        Tif tif = Tif.GTC;
        if (!arg(tok, i + 2).isEmpty()) {
            tif = parseTif(arg(tok, i + 2));
            if (tif == null) {
                out.println("  unknown time in force: " + arg(tok, i + 2));
                return;
            }
        }
        final ExecReport r = engine.submit(symbol, 0, side, OrderType.LIMIT, tif, price.getAsLong(), qty);
        printReport(r, side, OrderType.LIMIT, tif, price.getAsLong(), qty);
    }

    private void market(String[] tok) {
        final Side side = parseSide(arg(tok, 1));
        final long qty = Prices.parseDigits(arg(tok, 2));
        if (side == null || qty < 0) {
            out.println("  usage: market <buy|sell> <qty>");
            return;
        }
        // A market order never rests.
        final ExecReport r = engine.submit(symbol, 0, side, OrderType.MARKET, Tif.IOC, 0, qty);
        printReport(r, side, OrderType.MARKET, Tif.IOC, 0, qty);
    }

    private void amend(String[] tok) {
        final long id = Prices.parseDigits(arg(tok, 1));
        final OptionalLong price = Prices.parse(arg(tok, 2));
        final long qty = Prices.parseDigits(arg(tok, 3));
        if (id < 0 || price.isEmpty() || qty < 0) {
            out.println("  usage: amend <id> <price> <qty>");
            return;
        }
        final ExecReport r = engine.amend(symbol, id, price.getAsLong(), qty);
        if (!r.accepted()) {
            out.println("  rejected: " + r.reject());
            return;
        }
        out.println("  amended #" + r.id() + " -> " + Prices.format(price.getAsLong()) + " x " + qty
                + (r.tradeCount() == 0 ? "" : " (traded on re-entry)"));
        printFills(r);
    }

    private void printFills(ExecReport r) {
        for (int i = 0; i < r.tradeCount(); ++i) {
            out.println("    fill " + r.tradeQty(i) + " @ " + Prices.format(r.tradePrice(i))
                    + " vs #" + r.makerId(i));
        }
    }

    private void printReport(ExecReport r, Side side, OrderType type, Tif tif, long price, long qty) {
        if (!r.accepted()) {
            out.println("  rejected: " + r.reject());
            return;
        }
        out.println("  #" + r.id() + " " + side + " " + qty
                + (type == OrderType.LIMIT ? " @ " + Prices.format(price) : " @ market") + " " + tif);
        printFills(r);
        out.println("    filled " + r.filled() + ", remaining " + r.remaining()
                + (r.resting() ? " (resting)" : r.cancelled() ? " (cancelled)" : ""));
    }

    private void printHelp() {
        out.print("""
                commands
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
                """);
    }

    private void printBook(int levels) {
        final OrderBook book = engine.find(symbol);
        if (book == null) {
            out.println("  no book for " + symbol + " yet");
            return;
        }
        final List<LevelView> bids = book.depth(Side.BUY, levels);
        final List<LevelView> asks = book.depth(Side.SELL, levels);

        out.println("  " + symbol + "  " + book.openOrders() + " orders");
        out.println("        price      qty  orders");
        // Asks print worst-first so the touch sits in the middle of the ladder,
        // the way a trading screen shows it.
        for (int i = asks.size() - 1; i >= 0; --i) printLevel("ASK", asks.get(i));

        final long spread = book.spread();
        out.println(spread == Book.NO_PRICE
                ? "      ---- one sided ----"
                : "      ---- spread " + Prices.format(spread) + " ----");

        for (LevelView l : bids) printLevel("BID", l);
        if (bids.isEmpty() && asks.isEmpty()) out.println("  (empty)");
    }

    private void printLevel(String label, LevelView l) {
        out.printf("  %s %9s%9d%8d%n", label, Prices.format(l.price()), l.qty(), l.orders());
    }

    private void printTape(int count) {
        final OrderBook book = engine.find(symbol);
        final List<Trade> tape = book == null ? List.of() : book.tape();
        if (tape.isEmpty()) {
            out.println("  no trades yet");
            return;
        }
        for (Trade t : tape.subList(Math.max(0, tape.size() - count), tape.size())) {
            out.println("  " + t.qty() + " @ " + Prices.format(t.price()) + "  taker #" + t.takerId()
                    + " " + t.takerSide() + "  maker #" + t.makerId());
        }
    }

    private void printStats() {
        final OrderBook book = engine.find(symbol);
        if (book == null) {
            out.println("  no book for " + symbol + " yet");
            return;
        }
        final BookStats s = book.stats();
        out.println("  symbol       " + symbol);
        out.println("  accepted     " + s.ordersAccepted());
        out.println("  rejected     " + s.ordersRejected());
        out.println("  trades       " + s.trades());
        out.println("  volume       " + s.volume() + " shares");
        out.println("  notional     " + Prices.format(s.notional()));
        out.println("  resting      " + book.openOrders() + " orders, " + book.levelCount(Side.BUY)
                + " bid / " + book.levelCount(Side.SELL) + " ask levels");
        if (book.bestBid() != Book.NO_PRICE) out.println("  best bid     " + Prices.format(book.bestBid()));
        if (book.bestAsk() != Book.NO_PRICE) out.println("  best ask     " + Prices.format(book.bestAsk()));
    }

    private void bench(int ops, long seed) {
        out.println("  warming up...");
        Bench.warmUp("pooled", Math.min(ops, 200_000), seed);
        out.print(Bench.format(Bench.throughput("pooled", Flow.generate(ops, seed))));
    }

    private void loadFeed(String path) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path));
        } catch (IOException e) {
            out.println("  cannot open " + path);
            return;
        }
        int executed = 0;
        for (String line : lines) {
            final String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            out.println("> " + trimmed);
            ++executed;
            if (!execute(trimmed)) break;
        }
        out.println("  replayed " + executed + " commands from " + path);
    }
}
