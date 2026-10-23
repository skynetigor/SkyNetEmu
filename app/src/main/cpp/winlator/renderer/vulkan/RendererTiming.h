#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>

// Host wall time only: these counters do not measure GPU execution time.
struct RendererTiming {
    std::atomic<uint64_t> calls{0}, totalUs{0}, maxUs{0};
    void record(uint64_t us) {
        totalUs.fetch_add(us, std::memory_order_relaxed);
        calls.fetch_add(1, std::memory_order_relaxed);
        uint64_t old = maxUs.load(std::memory_order_relaxed);
        while (old < us && !maxUs.compare_exchange_weak(old, us, std::memory_order_relaxed)) {}
    }
};

class ScopedRendererTiming {
    RendererTiming* timing;
    std::chrono::steady_clock::time_point start;
public:
    ScopedRendererTiming(RendererTiming& counter, bool enabled) : timing(enabled ? &counter : nullptr) {
        if (timing) start = std::chrono::steady_clock::now();
    }
    ~ScopedRendererTiming() {
        if (timing) timing->record(static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count()));
    }
    ScopedRendererTiming(const ScopedRendererTiming&) = delete;
    ScopedRendererTiming& operator=(const ScopedRendererTiming&) = delete;
};
