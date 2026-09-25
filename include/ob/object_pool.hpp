#pragma once

#include <cstddef>
#include <memory>
#include <new>
#include <type_traits>
#include <utility>
#include <vector>

namespace ob {

// A fixed-size object pool: slots are carved out of large blocks and recycled
// through an intrusive free list, so after warm-up creating or destroying an
// object is a pointer pop or push with no trip to the allocator. Blocks are
// never freed or moved while the pool lives, so object addresses are stable.
//
// Restricted to trivially destructible types, which is all the order book
// needs and lets clear() drop every live object at once.
template <class T>
class ObjectPool {
    static_assert(std::is_trivially_destructible_v<T>,
                  "ObjectPool skips destructors; T must be trivially destructible");

public:
    explicit ObjectPool(std::size_t block_size = 4096) : block_size_(block_size) {}

    ObjectPool(const ObjectPool&)            = delete;
    ObjectPool& operator=(const ObjectPool&) = delete;

    template <class... Args>
    T* create(Args&&... args) {
        if (free_ == nullptr) grow();
        Slot* slot = free_;
        free_      = slot->next;
        ++live_;
        return ::new (static_cast<void*>(slot->storage)) T{std::forward<Args>(args)...};
    }

    void destroy(T* obj) noexcept {
        Slot* slot = reinterpret_cast<Slot*>(obj);
        slot->next = free_;
        free_      = slot;
        --live_;
    }

    // Returns every slot to the free list, keeping the memory for reuse.
    void clear() noexcept {
        free_ = nullptr;
        for (auto& block : blocks_) thread(block.get());
        live_ = 0;
    }

    std::size_t live() const noexcept { return live_; }
    std::size_t capacity() const noexcept { return blocks_.size() * block_size_; }

private:
    union Slot {
        Slot* next;
        alignas(T) unsigned char storage[sizeof(T)];
    };

    void grow() {
        blocks_.push_back(std::make_unique<Slot[]>(block_size_));
        thread(blocks_.back().get());
    }

    // Pushes all of a block's slots onto the free list.
    void thread(Slot* block) noexcept {
        for (std::size_t i = block_size_; i-- > 0;) {
            block[i].next = free_;
            free_         = &block[i];
        }
    }

    std::size_t                          block_size_;
    std::vector<std::unique_ptr<Slot[]>> blocks_;
    Slot*                                free_{nullptr};
    std::size_t                          live_{0};
};

}  // namespace ob
