package orderengine;

import java.util.Arrays;

// A free list of OrderNodes. Nodes are allocated in chunks up front and handed
// back on fill or cancel, so once the pool has warmed up, resting or retiring
// an order is an array push or pop and the hot path produces no garbage.
final class OrderPool {
    private final int chunk;
    private OrderNode[] free;
    private int top;       // free[0..top) are available
    private int capacity;  // nodes ever allocated
    private int live;

    OrderPool(int initial, int chunk) {
        this.chunk = chunk;
        this.free = new OrderNode[initial];
        grow(initial);
    }

    OrderNode acquire() {
        if (top == 0) grow(chunk);
        ++live;
        return free[--top];
    }

    void release(OrderNode node) {
        node.level = null;
        node.prev = null;
        node.next = null;
        free[top++] = node;
        --live;
    }

    int live() {
        return live;
    }

    int capacity() {
        return capacity;
    }

    private void grow(int n) {
        capacity += n;
        if (free.length < capacity) free = Arrays.copyOf(free, capacity);
        for (int i = 0; i < n; ++i) free[top++] = new OrderNode();
    }
}
