package orderengine;

// One aggregated price level, as depth() reports it.
public record LevelView(long price, long qty, int orders) {}
