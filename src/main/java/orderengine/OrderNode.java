package orderengine;

// A resting order, linked into its price level's FIFO queue. Nodes come from
// an OrderPool and are recycled, never left for the garbage collector.
final class OrderNode {
    long id;
    Side side;
    Tif tif;
    long price;
    long qty;        // original size, less any amend-down
    long remaining;  // unfilled size
    long seq;        // arrival stamp -> time priority

    Level level;
    OrderNode prev;
    OrderNode next;
}
