#pragma once
#include <cstdint>
// Protected by the renderer's existing renderMutex. Sequence numbers identify source updates,
// not pixel differences. Reusing the same swapchain buffer still advances the sequence.
struct FramePipelineCounters {
    uint64_t accepted=0, unique=0, repeats=0, skipped=0, lastQueued=0, androidQueued=0;
    void accept() { ++accepted; }
    void presented(uint64_t source) {
        ++androidQueued;
        if (!source) return;
        if (source==lastQueued) { ++repeats; return; }
        if (source<lastQueued) return;
        skipped += source-lastQueued-1;
        lastQueued=source; ++unique;
    }
};

// Timing feedback has a bounded driver history. A missing ID may mean discarded
// history, so a partial set of feedback records cannot be reported as display FPS.
struct DisplayTimingCounters {
    uint64_t records=0;
    uint32_t expected=1;
    bool complete=true;
    void beginSwapchain(uint32_t firstId) {
        if (firstId!=1) complete=false; // Outstanding feedback from the old swapchain is lost.
        expected=firstId;
    }
    void record(uint32_t id) {
        if (!id) {complete=false;return;}
        if (id<expected) return;
        if (id!=expected) complete=false;
        ++records; expected=id+1;
        if (!expected) complete=false;
    }
};
