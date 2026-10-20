package orderengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class PricesAndLatencyTest {
    @Test
    void pricesParseAsExactTicks() {
        assertEquals(OptionalLong.of(10_000), Prices.parse("100"));
        assertEquals(OptionalLong.of(10_050), Prices.parse("100.5"));
        assertEquals(OptionalLong.of(10_025), Prices.parse("100.25"));
        assertEquals(OptionalLong.of(1), Prices.parse("0.01"));
        assertEquals(OptionalLong.of(0), Prices.parse("0"));
        assertEquals("100.05", Prices.format(10_005));
        assertEquals("-0.50", Prices.format(-50));

        for (String junk : new String[] {"", "abc", "100.256", "1.2.3", "100.", "-1", "1e3"}) {
            assertTrue(Prices.parse(junk).isEmpty(), junk);
        }
    }

    @Test
    void histogramReportsExactCounts() {
        final LatencyHistogram h = new LatencyHistogram();
        for (int i = 0; i < 1_000; ++i) h.record(1_000);

        assertEquals(1_000, h.count());
        assertEquals(1_000, h.min());
        assertEquals(1_000, h.max());
        assertEquals(1_000.0, h.mean(), 1.0);

        // Bucket resolution at 1us is 64ns, so the reported percentile lands
        // within one bucket width of the truth.
        assertEquals(1_000, h.percentile(50), 64);
    }

    @Test
    void histogramPercentilesAreOrdered() {
        final LatencyHistogram h = new LatencyHistogram();
        for (long ns = 1; ns <= 10_000; ++ns) h.record(ns);

        assertTrue(h.percentile(50) < h.percentile(90));
        assertTrue(h.percentile(90) < h.percentile(99));
        assertTrue(h.percentile(99) <= h.max());
        assertEquals(1, h.min());
        assertEquals(10_000, h.max());

        // Roughly where a uniform 1..10000 distribution should put them.
        assertEquals(5_000, h.percentile(50), 500);
        assertEquals(9_000, h.percentile(90), 500);
    }

    @Test
    void histogramHandlesSmallAndHugeValues() {
        final LatencyHistogram h = new LatencyHistogram();
        h.record(0);
        h.record(1);
        h.record(7);
        h.record(1_000_000_000);

        assertEquals(4, h.count());
        assertEquals(0, h.min());
        assertEquals(1_000_000_000, h.max());
        assertEquals(0, h.percentile(0.1));  // small values are exact
        assertTrue(h.percentile(100) >= 1_000_000_000 / 2);

        h.reset();
        assertEquals(0, h.count());
        assertEquals(0, h.percentile(50));
    }
}
