package orderengine.bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import orderengine.Book;

// JMH microbenchmarks: one invocation is one operation from the synthetic flow
// (a submit or a cancel), run against both implementations.
//
//   java -jar target/orderengine.jar --jmh                  # everything
//   java -jar target/orderengine.jar --jmh -prof gc         # plus allocation per op
//
// SampleTime reports the per-operation distribution (p50/p99/p99.9), Throughput
// the sustained rate. Both are unpaced: for latency under a fixed offered load,
// see --latency.
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class OrderBookBenchmark {
    private static final int FLOW_OPS = 1 << 21;

    @Param({"pooled", "baseline"})
    public String impl;

    private Flow flow;
    private Book book;
    private Book topBook;
    private int next;

    @Setup(Level.Trial)
    public void generate() {
        flow = Flow.generate(FLOW_OPS, 42);
        // A populated book for the top-of-book benchmark.
        topBook = Bench.newBook(impl);
        for (int i = 0; i < 200_000; ++i) flow.apply(topBook, i);
    }

    @Setup(Level.Iteration)
    public void freshBook() {
        book = Bench.newBook(impl);
        book.setTapeLimit(1_024);
        next = 0;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public long mixedFlow() {
        if (next == FLOW_OPS) {
            // Ids repeat on the next pass, so start from an empty book.
            book.clear();
            next = 0;
        }
        return flow.apply(book, next++);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public long topOfBook() {
        return topBook.bestBid() + topBook.bestAsk();
    }
}
