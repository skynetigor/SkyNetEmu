#include "../../app/src/main/cpp/winlator/renderer/vulkan/FramePipelineCounters.h"
#include <cassert>
#include <iostream>
int main() {
 FramePipelineCounters c;
 c.presented(0); assert(c.unique==0 && c.androidQueued==1);
 c.accept(); c.presented(1); c.presented(1);
 assert(c.accepted==1 && c.unique==1 && c.repeats==1 && c.skipped==0);
 // Several arrivals before the next composition: only the latest update is sampled.
 c.accept();c.accept();c.accept();c.presented(4);
 assert(c.accepted==4 && c.unique==2 && c.skipped==2);
 // No successful Android present: do not commit the captured sequence.
 c.accept(); auto captured=c.accepted; c.accept();c.presented(captured);
 assert(c.unique==3 && c.lastQueued==5 && c.accepted==6);
 c.presented(6);assert(c.unique==4 && c.skipped==2);
 // A cursor redraw must not consume a later incoming game frame.
 c.accept();c.presented(6);assert(c.repeats==2 && c.lastQueued==6);
 DisplayTimingCounters d;
 d.beginSwapchain(1);d.record(1);d.record(2);d.record(2);
 assert(d.complete && d.records==2);
 d.record(80);assert(!d.complete && d.records==3);
 d.beginSwapchain(81);d.record(81);assert(!d.complete);
 DisplayTimingCounters recreated;recreated.record(1);recreated.beginSwapchain(2);
 assert(!recreated.complete);
 std::cout<<"PASS source updates, repeats, overwrite skips, and failed/in-flight present accounting\n";
}
