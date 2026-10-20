package orderengine;

// One execution. `price` is always the resting (maker) order's price.
public record Trade(long seq, long takerId, long makerId, Side takerSide, long price, long qty) {}
