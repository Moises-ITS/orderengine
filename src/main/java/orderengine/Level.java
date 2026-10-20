package orderengine;

// One price level: an intrusive doubly linked FIFO of resting orders. The
// head is the oldest order and trades first, which is time priority; the
// prev/next links let a cancel unlink an order from the middle in O(1).
final class Level {
    long price;
    Long key;     // boxed once per level, so the TreeMap never re-boxes the price
    long total;   // cached sum of remaining qty, so depth() stays O(levels)
    int count;
    OrderNode head;
    OrderNode tail;

    void init(long p) {
        price = p;
        key = p;
        total = 0;
        count = 0;
        head = tail = null;
    }

    void pushBack(OrderNode node) {
        node.level = this;
        node.prev = tail;
        node.next = null;
        if (tail != null) {
            tail.next = node;
        } else {
            head = node;
        }
        tail = node;
        total += node.remaining;
        ++count;
    }

    void unlink(OrderNode node) {
        if (node.prev != null) node.prev.next = node.next; else head = node.next;
        if (node.next != null) node.next.prev = node.prev; else tail = node.prev;
        total -= node.remaining;
        --count;
    }
}
