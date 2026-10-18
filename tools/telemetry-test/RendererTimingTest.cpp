#include "RendererTiming.h"
#include <cassert>
#include <thread>
#include <vector>

int main() {
    RendererTiming timing;
    { ScopedRendererTiming disabled(timing, false); }
    assert(timing.calls == 0);
    std::vector<std::thread> threads;
    for (int i = 0; i < 4; ++i) threads.emplace_back([&timing, i] {
        for (int n = 0; n < 10000; ++n) timing.record(i + 1);
    });
    for (auto& thread : threads) thread.join();
    assert(timing.calls == 40000);
    assert(timing.totalUs == 100000);
    assert(timing.maxUs == 4);
    { ScopedRendererTiming enabled(timing, true); }
    assert(timing.calls == 40001);
}
