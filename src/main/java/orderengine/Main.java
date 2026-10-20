package orderengine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import orderengine.bench.Bench;
import orderengine.bench.Flow;

public final class Main {
    private static final String USAGE = """
            usage: java -jar orderengine.jar [options]

              no options          interactive session on stdin
              --feed <path>       replay a file of commands, then exit
              --bench [ops]       throughput: replay the synthetic flow unpaced (default 2000000)
              --latency [ops]     latency under load: offer the flow at --rate (default 4000000 ops)
              --rate <n>          ops/sec offered by --latency (default 2000000)
              --impl <name>       pooled, baseline or both (default both)
              --seed <n>          seed for the synthetic flow (default 42)
              --hist              print the latency histogram after --latency
              --jmh [args]        run the JMH suite; remaining args go to JMH
              --help              this text

            Piping also works:  echo "buy 100 500" | java -jar orderengine.jar
            """;

    public static void main(String[] args) throws Exception {
        String feed = null;
        int benchOps = 0;
        int latencyOps = 0;
        long rate = 2_000_000;
        long seed = 42;
        String impl = "both";
        boolean hist = false;

        for (int i = 0; i < args.length; ++i) {
            final String a = args[i];
            final boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("-");
            switch (a) {
                case "--help": case "-h":
                    System.out.print(USAGE);
                    return;
                case "--jmh":
                    org.openjdk.jmh.Main.main(Arrays.copyOfRange(args, i + 1, args.length));
                    return;
                case "--feed":
                    if (!hasValue) { usageError("--feed needs a path"); return; }
                    feed = args[++i];
                    break;
                case "--bench":
                    benchOps = hasValue ? Integer.parseInt(args[++i]) : 2_000_000;
                    break;
                case "--latency":
                    latencyOps = hasValue ? Integer.parseInt(args[++i]) : 4_000_000;
                    break;
                case "--rate":
                    if (!hasValue) { usageError("--rate needs a number"); return; }
                    rate = Long.parseLong(args[++i]);
                    break;
                case "--impl":
                    if (!hasValue) { usageError("--impl needs pooled, baseline or both"); return; }
                    impl = args[++i];
                    break;
                case "--seed":
                    if (!hasValue) { usageError("--seed needs a number"); return; }
                    seed = Long.parseLong(args[++i]);
                    break;
                case "--hist":
                    hist = true;
                    break;
                default:
                    usageError("unknown option: " + a);
                    return;
            }
        }

        final List<String> impls = impl.equals("both") ? List.of("pooled", "baseline") : List.of(impl);
        for (String name : impls) Bench.newBook(name);  // fail fast on a bad name

        if (benchOps > 0) {
            final Flow flow = Flow.generate(benchOps, seed);
            System.out.println("throughput: " + benchOps + " operations, seed " + seed);
            for (String name : impls) {
                Bench.warmUp(name, Math.min(benchOps, 1_000_000), seed);
                System.out.println();
                System.out.print(Bench.format(Bench.throughput(name, flow)));
            }
            return;
        }

        if (latencyOps > 0) {
            final Flow flow = Flow.generate(latencyOps, seed);
            System.out.println("latency under load: " + latencyOps + " operations offered at "
                    + rate + " ops/sec, seed " + seed);
            if (impls.size() > 1) {
                System.out.println("(both books share one JVM here; run --impl pooled and --impl baseline"
                        + " separately for the cleanest comparison)");
            }
            for (String name : impls) {
                Bench.warmUp(name, 1_000_000, seed);
                final Bench.Result r = Bench.paced(name, flow, rate);
                System.out.println();
                System.out.print(Bench.format(r));
                if (hist) System.out.print(r.latency().table());
            }
            return;
        }

        final Cli cli = new Cli(System.out);
        if (feed != null) {
            cli.execute("load " + feed);
            return;
        }
        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        cli.run(in, System.console() != null);
    }

    private static void usageError(String message) {
        System.err.println(message);
        System.err.println();
        System.err.print(USAGE);
        System.exit(2);
    }
}
