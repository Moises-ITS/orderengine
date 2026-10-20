package orderengine;

import java.util.OptionalLong;

// Prices are integer ticks (cents), never floating point: exchanges match on
// exact integers so that `a == b` is meaningful and totals always reconcile.
// The decimal scale lives at the edges of the system only.
public final class Prices {
    public static final int TICK_SCALE = 100;

    private Prices() {}

    // Ticks -> "123.45".
    public static String format(long ticks) {
        final boolean neg = ticks < 0;
        final long mag = Math.abs(ticks);
        return String.format("%s%d.%02d", neg ? "-" : "", mag / TICK_SCALE, mag % TICK_SCALE);
    }

    // "100", "100.5" or "100.25" -> ticks; empty on junk. Parsing the fraction
    // by hand rather than through a double keeps "100.05" exact.
    public static OptionalLong parse(String text) {
        if (text == null || text.isEmpty()) return OptionalLong.empty();

        final int dot = text.indexOf('.');
        final String whole = dot < 0 ? text : text.substring(0, dot);
        final long units = parseDigits(whole);
        if (units < 0) return OptionalLong.empty();

        long frac = 0;
        if (dot >= 0) {
            String digits = text.substring(dot + 1);
            if (digits.isEmpty() || digits.length() > 2) return OptionalLong.empty();
            if (digits.length() == 1) digits += '0';
            frac = parseDigits(digits);
            if (frac < 0) return OptionalLong.empty();
        }
        return OptionalLong.of(units * TICK_SCALE + frac);
    }

    // Unsigned decimal, or -1 when `s` is not one.
    static long parseDigits(String s) {
        if (s.isEmpty() || s.length() > 15) return -1;
        long v = 0;
        for (int i = 0; i < s.length(); ++i) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
