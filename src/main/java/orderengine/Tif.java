package orderengine;

// Time in force. GTC rests on the book, IOC cancels its remainder,
// FOK must fill in full or not trade at all.
public enum Tif {
    GTC, IOC, FOK
}
